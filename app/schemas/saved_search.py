"""Saved-search API schemas."""
from __future__ import annotations

from datetime import date, datetime
from typing import Dict, List, Optional

from pydantic import BaseModel, Field


class SavedSearchFilters(BaseModel):
    """The subset of discovery filter parameters an alert can be built from.

    Field names and semantics are identical to GET /api/v1/tenders so the
    client simply reuses the query params it already sends (minus `sort`).
    """

    model_config = {"extra": "ignore"}

    search: Optional[str] = Field(None, max_length=256)
    province: Optional[str] = Field(None, max_length=512,
                                    description="Province name(s), comma-separated")
    category: Optional[str] = Field(None, max_length=512,
                                    description="Category name(s), comma-separated")
    source: Optional[str] = Field(None, max_length=256,
                                  description="Source name(s), comma-separated")
    status: Optional[str] = Field(None, max_length=16,
                                  description="open|closing_soon|closed or an enum status")
    closing_within: Optional[str] = Field(None, max_length=8, pattern=r"^\d+[hdm]$")
    closing_after: Optional[date] = None
    closing_before: Optional[date] = None
    advertised_after: Optional[date] = None
    advertised_before: Optional[date] = None


class SavedSearchCreateIn(BaseModel):
    client_id: str = Field(..., min_length=1, max_length=128)
    name: str = Field(..., min_length=1, max_length=128)
    filters: SavedSearchFilters = Field(default_factory=SavedSearchFilters)


class SavedSearchAlertsIn(BaseModel):
    client_id: str = Field(..., min_length=1, max_length=128)
    alerts_enabled: bool = True


class SavedSearchOut(BaseModel):
    id: int
    name: str
    alerts_enabled: bool
    filters: Dict[str, str]
    created_at: datetime


class SavedSearchListOut(BaseModel):
    client_id: str
    searches: List[SavedSearchOut]
