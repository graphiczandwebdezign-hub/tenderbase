"""Tender API schemas — stable, mobile-friendly, no DB internals leaked."""
from __future__ import annotations

from datetime import date, datetime, time
from typing import List, Optional

from pydantic import BaseModel, ConfigDict


class DocumentOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    title: Optional[str] = None
    url: str
    type: Optional[str] = None
    filename: Optional[str] = None
    mime_type: Optional[str] = None
    file_size: Optional[int] = None


class AmendmentOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    field_changed: str
    old_value: Optional[str] = None
    new_value: Optional[str] = None
    detected_at: datetime


class TenderOut(BaseModel):
    id: int
    source: str
    tender_number: Optional[str] = None
    ocid: Optional[str] = None
    title: str
    description: Optional[str] = None
    organisation: Optional[str] = None
    province: Optional[str] = None
    municipality: Optional[str] = None
    category: Optional[str] = None
    categories: List[str] = []
    tender_type: Optional[str] = None
    status: str
    deadline_state: str
    advertised_date: Optional[date] = None
    closing_date: Optional[date] = None
    closing_time: Optional[time] = None
    closing_at: Optional[datetime] = None
    submission_method: Optional[str] = None
    source_url: Optional[str] = None
    is_sample: bool = False
    documents: List[DocumentOut] = []


class TenderDetailOut(TenderOut):
    amendments: List[AmendmentOut] = []
