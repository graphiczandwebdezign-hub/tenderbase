from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class DeviceRegisterIn(BaseModel):
    client_id: str = Field(..., min_length=1, max_length=128,
                           description="Opaque per-install id from the Android app")
    device_token: str = Field(..., min_length=1, max_length=512,
                              description="FCM registration token")
    platform: str = Field(default="android", max_length=32)


class DeviceUnregisterIn(BaseModel):
    device_token: str = Field(..., min_length=1, max_length=512)


class DeviceOut(BaseModel):
    id: int
    platform: str
    active: bool


class PreferenceItem(BaseModel):
    category: Optional[str] = Field(None, description="Category slug or name; null = any")
    province: Optional[str] = Field(None, description="Province slug or name; null = any")
    notifications_enabled: bool = True


class PreferencesIn(BaseModel):
    client_id: str = Field(..., min_length=1, max_length=128)
    preferences: List[PreferenceItem] = Field(default_factory=list)


class PreferencesOut(BaseModel):
    client_id: str
    preferences: List[PreferenceItem]


class SaveTenderIn(BaseModel):
    client_id: str = Field(..., min_length=1, max_length=128)
    tender_id: int
    reminders_enabled: bool = True
