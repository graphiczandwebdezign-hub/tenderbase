from __future__ import annotations

from datetime import datetime
from typing import Dict, List, Optional

from pydantic import BaseModel, ConfigDict


class SyncRunOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    source: str
    trigger: str
    started_at: datetime
    completed_at: Optional[datetime] = None
    status: str
    records_received: int
    records_created: int
    records_updated: int
    records_amended: int
    records_expired: int
    records_failed: int
    notifications_sent: int
    error_message: Optional[str] = None


class DashboardOut(BaseModel):
    active_tenders: int
    amended_tenders: int
    new_today: int
    closing_today: int
    closing_tomorrow: int
    closed: int
    expired: int
    total_tenders: int
    total_users: int
    total_devices: int
    saved_searches: int = 0
    searches_last_7d: int = 0
    last_sync: Optional[SyncRunOut] = None
    last_successful_sync_at: Optional[datetime] = None


class TermStat(BaseModel):
    term: str
    count: int
    avg_results: Optional[float] = None


class DailyCount(BaseModel):
    date: str
    count: int


class SearchAnalyticsOut(BaseModel):
    days: int
    total_searches: int
    zero_result_searches: int
    avg_results: Optional[float] = None
    daily: List[DailyCount]
    top_terms: List[TermStat]
    top_zero_result_terms: List[TermStat]
    facet_usage: Dict[str, int]


class SavedSearchAnalyticsOut(BaseModel):
    total: int
    alerts_enabled: int
    alerts_disabled: int
    distinct_users: int
    top_terms: List[TermStat]
    facet_usage: Dict[str, int]


class SourceQualityOut(BaseModel):
    source: str
    total: int
    missing_closing_date: int
    missing_province: int
    missing_category: int
    missing_organisation: int
    missing_description: int
    without_documents: int
    open_past_deadline: int
    completeness: float


class DataQualityOut(BaseModel):
    overall: SourceQualityOut
    sources: List[SourceQualityOut]


class ReEnrichOut(BaseModel):
    scanned: int
    province_filled: int
    municipality_filled: int
    closing_filled: int
    dry_run: bool


class TenderUpdateIn(BaseModel):
    status: Optional[str] = None
    title: Optional[str] = None
    category: Optional[str] = None
    province: Optional[str] = None


class ApiKeyCreateIn(BaseModel):
    name: str
    expires_at: Optional[datetime] = None


class ApiKeyOut(BaseModel):
    id: int
    name: str
    key_prefix: Optional[str] = None
    active: bool
    created_at: datetime
    last_used_at: Optional[datetime] = None
    expires_at: Optional[datetime] = None


class ApiKeyCreatedOut(ApiKeyOut):
    api_key: str  # shown only once at creation
