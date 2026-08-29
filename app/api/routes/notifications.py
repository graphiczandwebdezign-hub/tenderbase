"""Device registration, preferences and saved tenders (API-key protected)."""
from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import normalization as norm
from app.core.security import require_api_key
from app.database.database import get_db
from app.database.models import (
    User,
    UserPreference,
    NotificationToken,
    Category,
    Province,
    Tender,
    SavedTender,
)
from app.schemas.notifications import (
    DeviceRegisterIn,
    DeviceUnregisterIn,
    DeviceOut,
    PreferencesIn,
    PreferencesOut,
    PreferenceItem,
    SaveTenderIn,
)

router = APIRouter(
    prefix="/notifications", tags=["notifications"], dependencies=[Depends(require_api_key)]
)


def _get_or_create_user(db: Session, client_id: str) -> User:
    user = db.execute(select(User).where(User.client_id == client_id)).scalar_one_or_none()
    if not user:
        user = User(client_id=client_id)
        db.add(user)
        db.commit()
        db.refresh(user)
    return user


@router.post("/register-device", response_model=DeviceOut, summary="Register FCM device token")
def register_device(payload: DeviceRegisterIn, db: Session = Depends(get_db)):
    user = _get_or_create_user(db, payload.client_id)
    token = db.execute(
        select(NotificationToken).where(NotificationToken.device_token == payload.device_token)
    ).scalar_one_or_none()
    if token:
        token.user_id = user.id
        token.platform = payload.platform
        token.active = True
    else:
        token = NotificationToken(
            user_id=user.id, device_token=payload.device_token,
            platform=payload.platform, active=True,
        )
        db.add(token)
    db.commit()
    db.refresh(token)
    return DeviceOut(id=token.id, platform=token.platform, active=token.active)


@router.delete("/unregister-device", summary="Disable an FCM device token")
def unregister_device(payload: DeviceUnregisterIn, db: Session = Depends(get_db)):
    token = db.execute(
        select(NotificationToken).where(NotificationToken.device_token == payload.device_token)
    ).scalar_one_or_none()
    if not token:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "TOKEN_NOT_FOUND", "message": "Device token not found"},
        )
    token.active = False
    db.commit()
    return {"status": "disabled"}


def _resolve_category_id(db: Session, value):
    if not value:
        return None
    slug = value.strip().lower().replace(" ", "-")
    c = db.execute(select(Category).where(Category.slug == slug)).scalar_one_or_none()
    if not c:
        c = db.execute(select(Category).where(Category.name == value)).scalar_one_or_none()
    return c.id if c else None


def _resolve_province_id(db: Session, value):
    if not value:
        return None
    slug = value.strip().lower().replace(" ", "-")
    p = db.execute(select(Province).where(Province.slug == slug)).scalar_one_or_none()
    if not p:
        p = db.execute(select(Province).where(Province.name == value)).scalar_one_or_none()
    return p.id if p else None


@router.post("/saved", summary="Save a tender for reminders")
def save_tender(payload: SaveTenderIn, db: Session = Depends(get_db)):
    user = _get_or_create_user(db, payload.client_id)
    tender = db.get(Tender, payload.tender_id)
    if not tender:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "TENDER_NOT_FOUND", "message": "Tender not found"},
        )
    existing = db.execute(
        select(SavedTender).where(
            SavedTender.user_id == user.id, SavedTender.tender_id == tender.id
        )
    ).scalar_one_or_none()
    if existing:
        existing.reminders_enabled = payload.reminders_enabled
    else:
        db.add(SavedTender(user_id=user.id, tender_id=tender.id,
                           reminders_enabled=payload.reminders_enabled))
    db.commit()
    return {"status": "saved", "tender_id": tender.id}
