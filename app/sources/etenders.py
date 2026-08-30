"""eTenders (SA National Treasury OCPO) source adapter.

Live data source
----------------
Official OCDS Release API published by the National Treasury Office of the
Chief Procurement Officer:

    https://ocds-api.etenders.gov.za/api/OCDSReleases

It is a paginated OCDS *release package* endpoint. Query parameters:

    PageNumber   1-based page index
    PageSize     records per page
    dateFrom     inclusive lower bound (yyyy-mm-dd) on release date
    dateTo       inclusive upper bound (yyyy-mm-dd) on release date

The response is an OCDS release package::

    {
      "releases": [ { "ocid": ..., "tender": {...}, "parties": [...] }, ... ],
      "links": { "next": "..." }
    }

Each release carries a ``tender`` object (title, description, status,
tenderPeriod.endDate = closing instant, mainProcurementCategory, documents)
and ``parties``/``buyer`` for the procuring entity. See
https://data.etenders.gov.za/Home/LearnMore for the publication policy.

Network note
------------
The government host restricts access from some cloud IP ranges. This adapter
targets the real endpoint; when it is unreachable the ingestion service applies
its resilience policy (keep existing data, log, optionally load the bundled
development sample). No endpoint is invented and no success is faked.
"""
from __future__ import annotations

import json
import os
import time
from datetime import date, datetime, timezone
from typing import Iterable, List, Optional

import httpx

from app.core.config import settings
from app.core.logging import get_logger, log_event
from app.core import normalization as norm
from app.core.date_extraction import extract_closing
from app.sources.base import (
    NormalizedDocument,
    NormalizedTender,
    TenderSourceAdapter,
)

logger = get_logger("sources.etenders")

# Map OCDS tenderStatus codelist values onto our raw status vocabulary.
_OCDS_STATUS_MAP = {
    "active": "ACTIVE",
    "planning": "ACTIVE",
    "planned": "ACTIVE",
    "complete": "CLOSED",
    "unsuccessful": "CLOSED",
    "cancelled": "CANCELLED",
    "withdrawn": "CANCELLED",
    "amended": "AMENDED",
}

_SAMPLE_PATH = os.path.join(
    os.path.dirname(os.path.dirname(__file__)), "..", "scripts", "sample_tenders.json"
)


class ETendersSourceAdapter(TenderSourceAdapter):
    name = "eTenders"

    def __init__(
        self,
        base_url: Optional[str] = None,
        page_size: Optional[int] = None,
        timeout: Optional[int] = None,
    ):
        self.base_url = base_url or settings.etenders_base_url
        self.page_size = page_size or settings.etenders_page_size
        self.timeout = timeout or settings.etenders_timeout_seconds

    # ------------------------------------------------------------------ fetch
    def fetch_tenders(
        self,
        date_from: Optional[date] = None,
        date_to: Optional[date] = None,
        max_pages: Optional[int] = None,
    ) -> Iterable[dict]:
        max_pages = max_pages or settings.etenders_max_pages
        date_to = date_to or datetime.now(timezone.utc).date()
        params_base = {
            "PageSize": self.page_size,
        }
        if date_from:
            params_base["dateFrom"] = date_from.isoformat()
        if date_to:
            params_base["dateTo"] = date_to.isoformat()

        # A browser-like User-Agent reduces the chance of a WAF dropping the
        # request. Some government endpoints reject the default httpx UA.
        headers = {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            ),
            "Accept": "application/json, text/plain, */*",
        }

        # Fast-fail on connect so a blocked/unreachable host errors quickly
        # instead of hanging the request for the full read timeout.
        timeout = httpx.Timeout(self.timeout, connect=10.0)

        with httpx.Client(timeout=timeout, follow_redirects=True, headers=headers) as client:
            for page in range(1, max_pages + 1):
                params = dict(params_base, PageNumber=page)
                # The eTenders API is slow and intermittently times out. Retry
                # each page a few times before letting the error propagate, so a
                # single sluggish response does not abort the whole run.
                data = self._get_page_with_retries(client, params, page)
                releases = data.get("releases") if isinstance(data, dict) else None
                if not releases:
                    break
                for rel in releases:
                    yield rel
                # Stop when the source signals the last page.
                if len(releases) < self.page_size and not (
                    isinstance(data.get("links"), dict) and data["links"].get("next")
                ):
                    break

    def _get_page_with_retries(self, client, params, page, attempts: int = 3):
        last_exc: Optional[Exception] = None
        for attempt in range(1, attempts + 1):
            try:
                resp = client.get(self.base_url, params=params)
                resp.raise_for_status()
                return resp.json()
            except (httpx.TimeoutException, httpx.TransportError) as exc:
                last_exc = exc
                log_event(
                    logger, 30, "page_fetch_retry",
                    page=page, attempt=attempt, error=str(exc),
                )
                if attempt < attempts:
                    time.sleep(min(2 * attempt, 5))
                    continue
                raise
        # Unreachable, but keeps type-checkers happy.
        if last_exc:
            raise last_exc

    # -------------------------------------------------------------- normalize
    def normalize_tender(self, raw: dict) -> Optional[NormalizedTender]:
        tender = raw.get("tender") or {}
        # Only ingest tender-stage releases (must have an id/title).
        if not tender:
            return None

        ocid = raw.get("ocid")
        external_id = str(tender.get("id") or ocid or raw.get("id") or "").strip()
        if not external_id:
            return None

        title = (tender.get("title") or "").strip()
        description = (tender.get("description") or "").strip() or None

        organisation, org_identifier = self._extract_buyer(raw, tender)

        # Dates
        advertised_date = self._parse_date(
            raw.get("date") or tender.get("datePublished")
        )
        closing_at = self._parse_datetime(
            (tender.get("tenderPeriod") or {}).get("endDate")
        )
        # Sprint 8: when the structured deadline is missing, recover it from
        # the title/description ("Closing date: 12 September 2026 at 11:00").
        if closing_at is None:
            closing_at = extract_closing(title, description)
        closing_date = closing_at.date() if closing_at else None
        closing_time = closing_at.timetz().replace(tzinfo=None) if closing_at else None

        # Classification
        source_category = tender.get("mainProcurementCategory")
        category_slugs = norm.normalize_categories(source_category, title, description)
        province_slug = norm.normalize_province(
            organisation, self._entity_region(raw), title, description
        )

        # Sprint 8: recover the municipality (and with it a province fallback)
        # when the source publishes no explicit region.
        municipality = norm.detect_municipality(
            organisation, self._entity_region(raw), title, description
        )
        municipality_name = municipality[0] if municipality else None
        if province_slug is None and municipality:
            province_slug = municipality[1]

        raw_status = self._map_status(tender.get("status"))

        submission_methods = tender.get("submissionMethod") or []
        submission_method = (
            ", ".join(submission_methods) if isinstance(submission_methods, list) else str(submission_methods)
        ) or (tender.get("submissionMethodDetails") or None)

        documents = self._extract_documents(tender)

        source_url = self._extract_source_url(raw, tender, documents)

        return NormalizedTender(
            source=self.name,
            external_id=external_id,
            ocid=ocid,
            tender_number=tender.get("id"),
            title=title or "(untitled tender)",
            description=description,
            organisation=organisation,
            organisation_identifier=org_identifier,
            province_slug=province_slug,
            municipality=municipality_name,
            category_slugs=category_slugs,
            tender_type=tender.get("procurementMethodDetails") or tender.get("procurementMethod"),
            raw_status=raw_status,
            advertised_date=advertised_date,
            closing_date=closing_date,
            closing_time=closing_time,
            closing_at=closing_at,
            submission_method=submission_method,
            source_url=source_url,
            documents=documents,
        )

    # ------------------------------------------------------------- documents
    def fetch_documents(self, raw: dict) -> List[NormalizedDocument]:
        return self._extract_documents(raw.get("tender") or {})

    def _extract_documents(self, tender: dict) -> List[NormalizedDocument]:
        docs: List[NormalizedDocument] = []
        for d in tender.get("documents") or []:
            url = d.get("url")
            if not url:
                continue
            docs.append(
                NormalizedDocument(
                    url=url,
                    title=d.get("title"),
                    document_type=d.get("documentType"),
                    filename=(d.get("url") or "").split("/")[-1] or None,
                    mime_type=d.get("format"),
                )
            )
        # De-dup by URL preserving order.
        seen = set()
        unique: List[NormalizedDocument] = []
        for d in docs:
            if d.url in seen:
                continue
            seen.add(d.url)
            unique.append(d)
        return unique

    # --------------------------------------------------------------- helpers
    @staticmethod
    def _map_status(status: Optional[str]) -> str:
        if not status:
            return "ACTIVE"
        return _OCDS_STATUS_MAP.get(str(status).lower(), "ACTIVE")

    @staticmethod
    def _parse_date(value) -> Optional[date]:
        dt = ETendersSourceAdapter._parse_datetime(value)
        return dt.date() if dt else None

    @staticmethod
    def _parse_datetime(value) -> Optional[datetime]:
        if not value:
            return None
        if isinstance(value, datetime):
            dt = value
        else:
            s = str(value).strip()
            if not s:
                return None
            if s.endswith("Z"):
                s = s[:-1] + "+00:00"
            try:
                dt = datetime.fromisoformat(s)
            except ValueError:
                for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
                    try:
                        dt = datetime.strptime(s[: len(fmt) + 2], fmt)
                        break
                    except ValueError:
                        continue
                else:
                    return None
        if dt.tzinfo is None:
            # eTenders publishes SAST (UTC+2) local times without offset.
            from datetime import timedelta

            dt = dt.replace(tzinfo=timezone(timedelta(hours=2)))
        return dt.astimezone(timezone.utc)

    @staticmethod
    def _extract_buyer(raw: dict, tender: dict):
        buyer = raw.get("buyer") or {}
        name = buyer.get("name")
        identifier = None
        procuring = tender.get("procuringEntity") or {}
        if not name and procuring:
            name = procuring.get("name")
        # Look up full party for identifier.
        target_id = buyer.get("id") or procuring.get("id")
        for party in raw.get("parties") or []:
            if target_id and party.get("id") == target_id:
                name = name or party.get("name")
                ident = party.get("identifier") or {}
                identifier = ident.get("id") or ident.get("legalName")
                break
        return name, identifier

    @staticmethod
    def _entity_region(raw: dict) -> Optional[str]:
        for party in raw.get("parties") or []:
            addr = party.get("address") or {}
            region = addr.get("region") or addr.get("locality")
            if region:
                return region
        return None

    @staticmethod
    def _extract_source_url(raw: dict, tender: dict, documents) -> Optional[str]:
        # Prefer an explicit notice document; else the first document; else the
        # portal search deep-link for the ocid.
        for d in documents:
            if (d.document_type or "").lower() in {"notice", "tendernotice"}:
                return d.url
        if documents:
            return documents[0].url
        ocid = raw.get("ocid")
        if ocid:
            return f"https://www.etenders.gov.za/Home/opportunities?id={ocid}"
        return None

    # --------------------------------------------------------- sample loader
    def load_sample_raw(self) -> List[dict]:
        """Load bundled development sample records (already in OCDS-ish shape).

        Used only by the ingestion service's resilience fallback. Records are
        tagged so they can be flagged as development data in the DB.
        """
        path = os.path.normpath(_SAMPLE_PATH)
        if not os.path.exists(path):
            log_event(logger, 30, "sample_file_missing", path=path)
            return []
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data.get("releases", data) if isinstance(data, dict) else data
