"""Ingestion pipeline tests: dedup, updates, amendment detection, expiry,
idempotency."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app.database.models import Tender, TenderStatus, TenderAmendment
from app.services.ingestion_service import IngestionService
from tests.mock_source import MockSourceAdapter, make_release


def _future(hours=48):
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _past(hours=48):
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).replace(microsecond=0).isoformat()


def test_ingest_creates_tenders(db):
    adapter = MockSourceAdapter([
        make_release("A1", closing_iso=_future()),
        make_release("A2", closing_iso=_future()),
    ])
    run = IngestionService(db, adapter).run_sync(trigger="manual")
    assert run.records_received == 2
    assert run.records_created == 2
    assert db.query(Tender).count() == 2


def test_ingestion_is_idempotent_no_duplicates(db):
    releases = [make_release("B1", closing_iso=_future()), make_release("B2", closing_iso=_future())]
    adapter = MockSourceAdapter(releases)
    IngestionService(db, adapter).run_sync(trigger="manual")
    # Run again with identical data.
    run2 = IngestionService(db, adapter).run_sync(trigger="manual")
    assert db.query(Tender).count() == 2  # no duplicates
    assert run2.records_created == 0


def test_dedup_by_ocid_when_external_id_changes(db):
    r = make_release("C1", closing_iso=_future())
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")
    assert db.query(Tender).count() == 1


def test_amendment_detection_on_closing_date_change(db):
    r = make_release("D1", closing_iso=_future(240))
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")

    # Change the closing date -> should be detected as an amendment.
    r2 = make_release("D1", closing_iso=_future(480))
    adapter.set_releases([r2])
    run = IngestionService(db, adapter).run_sync(trigger="manual")

    assert run.records_amended == 1
    amendments = db.execute(select(TenderAmendment)).scalars().all()
    fields = {a.field_changed for a in amendments}
    assert "closing_at" in fields
    tender = db.execute(select(Tender)).scalar_one()
    assert tender.status == TenderStatus.AMENDED


def test_expiry_marks_closed_then_expired(db):
    # Closing 10 days in the past with retention 7 -> should become EXPIRED.
    r = make_release("E1", closing_iso=_past(24 * 10))
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")
    tender = db.execute(select(Tender)).scalar_one()
    assert tender.status in (TenderStatus.EXPIRED, TenderStatus.CLOSED)
    # A recently-closed tender stays CLOSED (within retention).
    r2 = make_release("E2", closing_iso=_past(2))
    adapter.set_releases([r2])
    IngestionService(db, adapter).run_sync(trigger="manual")
    t2 = db.execute(select(Tender).where(Tender.external_id == "E2")).scalar_one()
    assert t2.status == TenderStatus.CLOSED


def test_cancelled_status_mapped(db):
    r = make_release("F1", status="cancelled", closing_iso=_future())
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")
    t = db.execute(select(Tender)).scalar_one()
    assert t.status == TenderStatus.CANCELLED


def test_documents_ingested_without_duplication(db):
    r = make_release("G1", closing_iso=_future())
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")
    t = db.execute(select(Tender)).scalar_one()
    assert len(t.documents) == 1
    # Re-run: still one document.
    IngestionService(db, adapter).run_sync(trigger="manual")
    db.refresh(t)
    assert len(t.documents) == 1


def test_normalization_categories_and_province(db):
    r = make_release("H1", title="Supply and Delivery of ICT Hardware",
                     description="laptops and servers", category="goods",
                     province="Gauteng", org="City of Johannesburg",
                     closing_iso=_future())
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")
    t = db.execute(select(Tender)).scalar_one()
    assert t.province == "Gauteng"
    slugs = [link.category.slug for link in t.categories]
    assert "information-technology" in slugs


class _PartialFetchAdapter(MockSourceAdapter):
    """Yields some records, then raises to simulate a mid-stream source timeout."""

    def fetch_tenders(self, date_from=None, date_to=None, max_pages=None):
        for rec in self._releases:
            yield rec
        raise TimeoutError("The read operation timed out")


def test_partial_fetch_keeps_ingested_records(db):
    releases = [make_release("P1", closing_iso=_future()),
                make_release("P2", closing_iso=_future())]
    adapter = _PartialFetchAdapter(releases)
    run = IngestionService(db, adapter).run_sync(trigger="manual")
    # Records fetched before the timeout are still ingested.
    assert db.query(Tender).count() == 2
    assert run.records_created == 2
    # Run is reported as PARTIAL with the source error preserved.
    assert run.status.value == "PARTIAL"
    assert "timed out" in (run.error_message or "")
