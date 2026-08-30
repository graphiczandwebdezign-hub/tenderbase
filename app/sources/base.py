"""Tender source abstraction.

All source-specific logic must live inside implementations of
``TenderSourceAdapter`` (in this package) — never inside API routes or
services. This keeps the core architecture independent of any single source
and lets new sources be added without rewriting the pipeline.
"""
from __future__ import annotations

import abc
from dataclasses import dataclass, field
from datetime import date, datetime, time
from typing import Iterable, List, Optional


@dataclass
class NormalizedDocument:
    url: str
    title: Optional[str] = None
    document_type: Optional[str] = None
    filename: Optional[str] = None
    mime_type: Optional[str] = None
    file_size: Optional[int] = None


@dataclass
class NormalizedTender:
    """Source-agnostic representation produced by an adapter's normalizer.

    ``closing_at`` is always timezone-aware (UTC). ``category_slugs`` and
    ``province_slug`` use the internal taxonomy.
    """

    source: str
    external_id: str
    ocid: Optional[str] = None
    tender_number: Optional[str] = None
    title: str = ""
    description: Optional[str] = None
    organisation: Optional[str] = None
    organisation_identifier: Optional[str] = None
    province_slug: Optional[str] = None
    municipality: Optional[str] = None
    category_slugs: List[str] = field(default_factory=list)
    tender_type: Optional[str] = None
    raw_status: Optional[str] = None
    advertised_date: Optional[date] = None
    closing_date: Optional[date] = None
    closing_time: Optional[time] = None
    closing_at: Optional[datetime] = None
    submission_method: Optional[str] = None
    source_url: Optional[str] = None
    documents: List[NormalizedDocument] = field(default_factory=list)


class TenderSourceAdapter(abc.ABC):
    """Interface implemented by each tender source."""

    #: Stable identifier stored in ``tenders.source``.
    name: str = "base"

    @abc.abstractmethod
    def fetch_tenders(
        self,
        date_from: Optional[date] = None,
        date_to: Optional[date] = None,
        max_pages: Optional[int] = None,
    ) -> Iterable[dict]:
        """Yield raw tender records (source-native dicts)."""

    @abc.abstractmethod
    def normalize_tender(self, raw: dict) -> Optional[NormalizedTender]:
        """Map a raw record onto a :class:`NormalizedTender`, or return None if
        the record should be skipped (e.g. not a tender-stage release)."""

    def fetch_documents(self, raw: dict) -> List[NormalizedDocument]:
        """Extract documents from a raw record. Default: none (adapters that
        embed documents in the main record override normalize_tender)."""
        return []
