"""Ingestion pipeline: FETCH -> VALIDATE -> PARSE -> NORMALIZE -> DEDUPLICATE
-> UPSERT -> DETECT CHANGES -> IDENTIFY NEW -> TRIGGER NOTIFICATIONS.

The pipeline is idempotent: running it repeatedly never creates duplicates
(enforced by DB constraints on source+external_id and source+ocid) and only
raises a NEW_TENDER notification the first time a tender is discovered.

Resilience: if the source is unreachable or returns garbage, existing tenders
are NEVER deleted. The failure is logged and the sync run is marked FAILED.
"""
from __future__ import annotations

import hashlib
from datetime import date, datetime, timedelta, timezone
from typing import List, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.logging import get_logger, log_event
from app.core.timeutils import utcnow, ensure_utc
from app.database.models import (
    Tender,
    TenderDocument,
    TenderAmendment,
    TenderStatus,
    TenderCategory,
    SyncRun,
    SyncStatus,
)
from app.services import taxonomy_service
from app.services.notification_service import NotificationService
from app.sources.base import NormalizedTender, TenderSourceAdapter
from app.sources.etenders import ETendersSourceAdapter

logger = get_logger("ingestion")

# Fields whose changes are recorded as amendments.
_AMENDMENT_FIELDS = [
    "title",
    "description",
    "closing_at",
    "closing_date",
    "closing_time",
    "status",
    "organisation",
    "submission_method",
]


class IngestionService:
    def __init__(self, db: Session, adapter: Optional[TenderSourceAdapter] = None):
        self.db = db
        self.adapter = adapter or ETendersSourceAdapter()
        self.notifier = NotificationService(db)

    # ------------------------------------------------------------------- run
    def run_sync(
        self,
        trigger: str = "scheduled",
        date_from: Optional[date] = None,
        date_to: Optional[date] = None,
        max_pages: Optional[int] = None,
    ) -> SyncRun:
        """Execute one full ingestion cycle and return the SyncRun record."""
        # Reap orphaned RUNNING rows from previously crashed/killed runs. The
        # worker's process lock guarantees no real run is active here, so any
        # RUNNING row is stale (e.g. an old synchronous request Render killed).
        orphans = self.db.execute(
            select(SyncRun).where(SyncRun.status == SyncStatus.RUNNING)
        ).scalars().all()
        for o in orphans:
            o.status = SyncStatus.FAILED
            o.completed_at = utcnow()
            o.error_message = o.error_message or "Interrupted (process restarted before completion)."
        if orphans:
            self.db.commit()

        run = SyncRun(source=self.adapter.name, trigger=trigger, status=SyncStatus.RUNNING)
        self.db.add(run)
        self.db.commit()

        if date_from is None:
            lookback = settings.sync_backfill_days if trigger in {"manual", "initial"} else settings.sync_lookback_days
            date_from = (utcnow() - timedelta(days=lookback)).date()

        received = created = updated = amended = failed = 0
        new_tender_ids: List[int] = []
        amended_tender_ids: List[int] = []
        used_sample = False
        fetch_error: Optional[str] = None

        # Fetch incrementally so a mid-stream timeout keeps whatever pages were
        # already retrieved instead of discarding the whole run. The eTenders
        # API is slow/flaky and often times out partway through a backfill.
        raw_records: List[dict] = []
        try:
            for rec in self.adapter.fetch_tenders(
                date_from=date_from, date_to=date_to, max_pages=max_pages
            ):
                raw_records.append(rec)
            log_event(logger, 20, "fetch_complete", source=self.adapter.name,
                      count=len(raw_records), trigger=trigger)
        except Exception as exc:  # noqa: BLE001 - resilience is intentional
            fetch_error = str(exc)
            log_event(logger, 40, "source_fetch_failed", source=self.adapter.name,
                      error=fetch_error, partial_count=len(raw_records))
            if not raw_records:
                # Nothing fetched at all: try the dev sample fallback.
                raw_records = self._maybe_sample_fallback()
                used_sample = bool(raw_records)
                if not used_sample:
                    # Source failed and no fallback: keep existing data untouched.
                    run.status = SyncStatus.FAILED
                    run.error_message = f"Source unavailable: {exc}"
                    run.completed_at = utcnow()
                    self.db.commit()
                    return run


        taxonomy_service.seed_taxonomy(self.db)
        cat_map = taxonomy_service.category_map(self.db)
        prov_map = taxonomy_service.province_map(self.db)

        for raw in raw_records:
            received += 1
            try:
                normalized = self.adapter.normalize_tender(raw)
                if normalized is None:
                    continue
                outcome, tender = self._upsert(normalized, cat_map, prov_map, is_sample=used_sample)
                if outcome == "created":
                    created += 1
                    new_tender_ids.append(tender.id)
                elif outcome == "amended":
                    amended += 1
                    updated += 1
                    amended_tender_ids.append(tender.id)
                elif outcome == "updated":
                    updated += 1
            except Exception as exc:  # noqa: BLE001
                failed += 1
                self.db.rollback()
                log_event(logger, 40, "record_ingest_failed", error=str(exc))

        # Expire tenders whose deadline has passed.
        expired = self.refresh_statuses()

        self.db.commit()

        # Trigger notifications (after commit so tenders have stable ids).
        notif_sent = 0
        try:
            notif_sent += self.notifier.notify_new_tenders(new_tender_ids)
            notif_sent += self.notifier.notify_amended_tenders(amended_tender_ids)
        except Exception as exc:  # noqa: BLE001
            log_event(logger, 40, "notification_dispatch_failed", error=str(exc))

        run.records_received = received
        run.records_created = created
        run.records_updated = updated
        run.records_amended = amended
        run.records_expired = expired
        run.records_failed = failed
        run.notifications_sent = notif_sent
        run.completed_at = utcnow()
        if used_sample:
            run.status = SyncStatus.PARTIAL if failed else SyncStatus.SUCCESS
            run.error_message = "Live source unreachable; loaded development sample data."
        elif fetch_error:
            # Some pages were ingested before the source timed out/errored.
            run.status = SyncStatus.PARTIAL
            run.error_message = (
                f"Partial fetch ({received} records processed before source "
                f"error): {fetch_error}"
            )
        else:
            run.status = SyncStatus.PARTIAL if failed else SyncStatus.SUCCESS
        self.db.commit()

        log_event(logger, 20, "sync_complete", run_id=run.id, status=run.status.value,
                  created=created, updated=updated, amended=amended, expired=expired,
                  failed=failed, notifications=notif_sent, sample=used_sample)
        return run

    # ---------------------------------------------------------------- upsert
    def _upsert(self, n: NormalizedTender, cat_map, prov_map, is_sample: bool):
        """Insert or update a tender. Returns (outcome, tender)."""
        # Deduplicate by (source, external_id), falling back to (source, ocid).
        existing = self.db.execute(
            select(Tender).where(
                Tender.source == n.source, Tender.external_id == n.external_id
            )
        ).scalar_one_or_none()
        if existing is None and n.ocid:
            existing = self.db.execute(
                select(Tender).where(Tender.source == n.source, Tender.ocid == n.ocid)
            ).scalar_one_or_none()

        province_name = None
        if n.province_slug and n.province_slug in prov_map:
            province_name = prov_map[n.province_slug].name

        primary_cat = None
        if n.category_slugs:
            slug = n.category_slugs[0]
            primary_cat = cat_map[slug].name if slug in cat_map else slug

        content_hash = self._content_hash(n)
        now = utcnow()

        if existing is None:
            tender = Tender(
                source=n.source,
                external_id=n.external_id,
                ocid=n.ocid,
                tender_number=n.tender_number,
                title=n.title,
                description=n.description,
                organisation=n.organisation,
                organisation_identifier=n.organisation_identifier,
                province=province_name,
                municipality=n.municipality,
                category=primary_cat,
                tender_type=n.tender_type,
                status=self._resolve_status(n),
                advertised_date=n.advertised_date,
                closing_date=n.closing_date,
                closing_time=n.closing_time,
                closing_at=n.closing_at,
                submission_method=n.submission_method,
                source_url=n.source_url,
                is_sample=is_sample,
                content_hash=content_hash,
                first_seen_at=now,
                last_seen_at=now,
                expires_at=self._compute_expiry(n.closing_at),
            )
            self.db.add(tender)
            self.db.flush()
            self._sync_categories(tender, n.category_slugs, cat_map)
            self._sync_documents(tender, n)
            self.db.commit()
            return "created", tender

        # ------ update path with amendment detection ------
        existing.last_seen_at = now
        amendments = self._detect_amendments(existing, n, province_name, primary_cat)

        existing.title = n.title or existing.title
        existing.description = n.description
        existing.organisation = n.organisation or existing.organisation
        existing.organisation_identifier = n.organisation_identifier or existing.organisation_identifier
        existing.province = province_name or existing.province
        existing.municipality = n.municipality or existing.municipality
        existing.category = primary_cat or existing.category
        existing.tender_type = n.tender_type or existing.tender_type
        existing.advertised_date = n.advertised_date or existing.advertised_date
        existing.closing_date = n.closing_date or existing.closing_date
        existing.closing_time = n.closing_time or existing.closing_time
        existing.closing_at = n.closing_at or existing.closing_at
        existing.submission_method = n.submission_method or existing.submission_method
        existing.source_url = n.source_url or existing.source_url
        existing.ocid = existing.ocid or n.ocid
        existing.expires_at = self._compute_expiry(existing.closing_at)
        existing.content_hash = content_hash

        # Status: honour explicit source status transitions.
        resolved = self._resolve_status(n, existing)
        status_changed = resolved != existing.status
        existing.status = resolved

        self._sync_categories(existing, n.category_slugs, cat_map)
        self._sync_documents(existing, n)

        if amendments:
            for field, old, new in amendments:
                self.db.add(
                    TenderAmendment(
                        tender_id=existing.id,
                        field_changed=field,
                        old_value=str(old) if old is not None else None,
                        new_value=str(new) if new is not None else None,
                        source=n.source,
                    )
                )
            # An amendment to a still-open tender is flagged AMENDED.
            if existing.status == TenderStatus.ACTIVE:
                existing.status = TenderStatus.AMENDED
            self.db.commit()
            return "amended", existing

        self.db.commit()
        outcome = "updated" if status_changed or True else "unchanged"
        return "updated", existing

    # ----------------------------------------------------- amendment detect
    def _detect_amendments(self, existing: Tender, n: NormalizedTender,
                           province_name, primary_cat):
        changes = []
        candidate = {
            "title": (existing.title, n.title or existing.title),
            "description": (existing.description, n.description),
            "closing_at": (existing.closing_at, n.closing_at),
            "closing_date": (existing.closing_date, n.closing_date),
            "closing_time": (existing.closing_time, n.closing_time),
            "organisation": (existing.organisation, n.organisation or existing.organisation),
            "submission_method": (existing.submission_method, n.submission_method or existing.submission_method),
        }
        for field, (old, new) in candidate.items():
            if new is None:
                continue
            if field not in _AMENDMENT_FIELDS:
                continue
            if self._values_differ(field, old, new):
                changes.append((field, old, new))
        return changes

    @staticmethod
    def _values_differ(field: str, old, new) -> bool:
        """Compare stored vs incoming values, robust to timezone-naive storage
        (SQLite) and datetime instances."""
        if isinstance(old, datetime) or isinstance(new, datetime):
            old_dt = ensure_utc(old) if isinstance(old, datetime) else old
            new_dt = ensure_utc(new) if isinstance(new, datetime) else new
            return old_dt != new_dt
        return str(old) != str(new)

    # --------------------------------------------------------- sub-entities
    def _sync_categories(self, tender: Tender, slugs, cat_map):
        wanted = {s for s in slugs if s in cat_map}
        existing_links = {
            link.category_id: link for link in tender.categories
        }
        wanted_ids = {cat_map[s].id for s in wanted}
        # Remove stale.
        for cid, link in list(existing_links.items()):
            if cid not in wanted_ids:
                self.db.delete(link)
        # Add new.
        for cid in wanted_ids:
            if cid not in existing_links:
                self.db.add(TenderCategory(tender_id=tender.id, category_id=cid))

    def _sync_documents(self, tender: Tender, n: NormalizedTender):
        existing_urls = {d.url for d in tender.documents}
        for doc in n.documents:
            if doc.url in existing_urls:
                continue
            self.db.add(
                TenderDocument(
                    tender_id=tender.id,
                    document_type=doc.document_type,
                    title=doc.title,
                    url=doc.url,
                    filename=doc.filename,
                    mime_type=doc.mime_type,
                    file_size=doc.file_size,
                )
            )

    # -------------------------------------------------------------- helpers
    @staticmethod
    def _content_hash(n: NormalizedTender) -> str:
        parts = [n.title or "", n.description or "", str(n.closing_at),
                 n.raw_status or "", n.organisation or ""]
        return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()

    @staticmethod
    def _compute_expiry(closing_at: Optional[datetime]) -> Optional[datetime]:
        if not closing_at:
            return None
        return closing_at + timedelta(days=settings.tender_retention_days)

    def _resolve_status(self, n: NormalizedTender, existing: Optional[Tender] = None) -> TenderStatus:
        raw = (n.raw_status or "ACTIVE").upper()
        if raw == "CANCELLED":
            return TenderStatus.CANCELLED
        if raw == "CLOSED":
            return TenderStatus.CLOSED
        # Deadline-driven: past closing time => CLOSED.
        if n.closing_at and n.closing_at <= utcnow():
            return TenderStatus.CLOSED
        return TenderStatus.ACTIVE

    def _maybe_sample_fallback(self) -> List[dict]:
        if not settings.ingestion_allow_sample_fallback or settings.is_production:
            return []
        # Only load the sample when the DB has no real tenders yet, to avoid
        # ever polluting a populated database.
        count = self.db.query(Tender).count()
        if count > 0:
            return []
        if isinstance(self.adapter, ETendersSourceAdapter):
            records = self.adapter.load_sample_raw()
            if records:
                log_event(logger, 30, "sample_fallback_loaded", count=len(records))
            return records
        return []

    # ------------------------------------------------- status maintenance
    def refresh_statuses(self) -> int:
        """Mark ACTIVE/AMENDED tenders past their closing time as CLOSED,
        then mark retained-past tenders EXPIRED. Returns count newly expired.

        NEVER deletes here — cleanup handles deletion after retention.
        """
        now = utcnow()
        # Close tenders whose deadline passed.
        to_close = self.db.execute(
            select(Tender).where(
                Tender.status.in_([TenderStatus.ACTIVE, TenderStatus.AMENDED]),
                Tender.closing_at.is_not(None),
                Tender.closing_at <= now,
            )
        ).scalars().all()
        for t in to_close:
            t.status = TenderStatus.CLOSED

        # Mark closed tenders past retention window as EXPIRED.
        expired = 0
        to_expire = self.db.execute(
            select(Tender).where(
                Tender.status == TenderStatus.CLOSED,
                Tender.expires_at.is_not(None),
                Tender.expires_at <= now,
            )
        ).scalars().all()
        for t in to_expire:
            t.status = TenderStatus.EXPIRED
            expired += 1
        return expired
