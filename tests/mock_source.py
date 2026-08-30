"""Deterministic in-memory source adapter for tests."""
from __future__ import annotations

from datetime import date
from typing import Iterable, List, Optional

from app.sources.base import TenderSourceAdapter
from app.sources.etenders import ETendersSourceAdapter


class MockSourceAdapter(TenderSourceAdapter):
    """Wraps eTenders normalization but serves a controllable list of raw
    OCDS-shaped releases from memory instead of the network."""

    name = "eTenders"

    def __init__(self, releases: List[dict]):
        self._releases = releases
        self._delegate = ETendersSourceAdapter()

    def set_releases(self, releases: List[dict]):
        self._releases = releases

    def fetch_tenders(self, date_from: Optional[date] = None,
                      date_to: Optional[date] = None,
                      max_pages: Optional[int] = None) -> Iterable[dict]:
        return list(self._releases)

    def normalize_tender(self, raw: dict):
        return self._delegate.normalize_tender(raw)

    def fetch_documents(self, raw: dict):
        return self._delegate.fetch_documents(raw)


def make_release(ext_id: str, *, title="Test Tender", status="active",
                 category="works", org="eThekwini Municipality",
                 province="KwaZulu-Natal", closing_iso: str = "2030-01-01T11:00:00",
                 advertised_iso: str = "2026-08-01T00:00:00",
                 description="A test tender description.",
                 documents=None) -> dict:
    ocid = f"ocds-test-{ext_id}"
    return {
        "ocid": ocid,
        "id": f"{ocid}-01",
        "date": advertised_iso,
        "tag": ["tender"],
        "buyer": {"id": "ORG-1", "name": org},
        "parties": [
            {
                "id": "ORG-1",
                "name": org,
                "roles": ["buyer", "procuringEntity"],
                "identifier": {"scheme": "ZA", "id": "ORG-1", "legalName": org},
                "address": {"region": province, "countryName": "South Africa"},
            }
        ],
        "tender": {
            "id": ext_id,
            "title": title,
            "description": description,
            "status": status,
            "mainProcurementCategory": category,
            "procurementMethod": "open",
            "procurementMethodDetails": "Open Tender",
            "submissionMethod": ["electronicSubmission"],
            "datePublished": advertised_iso,
            "tenderPeriod": {"startDate": advertised_iso, "endDate": closing_iso},
            "procuringEntity": {"id": "ORG-1", "name": org},
            "documents": documents if documents is not None else [
                {
                    "id": f"{ext_id}-D1",
                    "documentType": "tenderNotice",
                    "title": "Notice",
                    "url": f"https://example.gov.za/{ext_id}/notice.pdf",
                    "format": "application/pdf",
                }
            ],
        },
    }
