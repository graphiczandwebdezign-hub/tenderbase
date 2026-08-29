from __future__ import annotations

from datetime import datetime
from typing import Optional

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
    last_sync: Optional[SyncRunOut] = None
    last_successful_sync_at: Optional[datetime] = None


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
