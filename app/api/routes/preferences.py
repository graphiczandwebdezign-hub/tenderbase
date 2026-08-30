"""User notification preferences (API-key protected).

GET/PUT /api/v1/preferences. The client is identified by a `client_id` query
param (GET) or body field (PUT) — the Android install id. No accounts, no
passwords: just the minimal record needed to route notifications.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.security import require_api_key
from app.database.database import get_db
from app.database.models import User, UserPreference, Category, Province
from app.schemas.notifications import PreferencesIn, PreferencesOut, PreferenceItem

router = APIRouter(prefix="/preferences", tags=["notifications"], dependencies=[Depends(require_api_key)])


def _get_or_create_user(db: Session, client_id: str) -> User:
    user = db.execute(select(User).where(User.client_id == client_id)).scalar_one_or_none()
    if not user:
        user = User(client_id=client_id)
        db.add(user)
        db.commit()
        db.refresh(user)
    return user


def _cat_slug(db, cid):
    if cid is None:
        return None
    c = db.get(Category, cid)
    return c.slug if c else None


def _prov_slug(db, pid):
    if pid is None:
        return None
    p = db.get(Province, pid)
    return p.slug if p else None


@router.get("", response_model=PreferencesOut, summary="Get notification preferences")
def get_preferences(client_id: str = Query(...), db: Session = Depends(get_db)):
    user = _get_or_create_user(db, client_id)
    items = []
    for pref in user.preferences:
        items.append(
            PreferenceItem(
                category=_cat_slug(db, pref.category_id),
                province=_prov_slug(db, pref.province_id),
                notifications_enabled=pref.notifications_enabled,
            )
        )
    return PreferencesOut(client_id=client_id, preferences=items)


@router.put("", response_model=PreferencesOut, summary="Replace notification preferences")
def put_preferences(payload: PreferencesIn, db: Session = Depends(get_db)):
    user = _get_or_create_user(db, payload.client_id)
    # Replace-all semantics keep the client simple.
    for pref in list(user.preferences):
        db.delete(pref)
    db.flush()

    for item in payload.preferences:
        cat_id = _resolve_category_id(db, item.category)
        prov_id = _resolve_province_id(db, item.province)
        db.add(
            UserPreference(
                user_id=user.id,
                category_id=cat_id,
                province_id=prov_id,
                notifications_enabled=item.notifications_enabled,
            )
        )
    db.commit()
    db.refresh(user)
    return get_preferences(client_id=payload.client_id, db=db)


def _resolve_category_id(db: Session, value):
    if not value:
        return None
    slug = value.strip().lower().replace(" ", "-")
    c = db.execute(select(Category).where(Category.slug == slug)).scalar_one_or_none()
    if not c:
        c = db.execute(select(Category).where(Category.name == value)).scalar_one_or_none()
    if not c:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"code": "INVALID_CATEGORY", "message": f"Unknown category: {value}"},
        )
    return c.id


def _resolve_province_id(db: Session, value):
    if not value:
        return None
    slug = value.strip().lower().replace(" ", "-")
    p = db.execute(select(Province).where(Province.slug == slug)).scalar_one_or_none()
    if not p:
        p = db.execute(select(Province).where(Province.name == value)).scalar_one_or_none()
    if not p:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"code": "INVALID_PROVINCE", "message": f"Unknown province: {value}"},
        )
    return p.id
