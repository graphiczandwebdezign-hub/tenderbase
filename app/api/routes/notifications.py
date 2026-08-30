"""Device registration, preferences and saved tenders (API-key protected)."""
from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.security import require_api_key
from app.database.database import get_db
from app.database.models import (
    User,
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
    SaveTenderIn,
    ChecklistItem,
    WorkspaceIn,
    SavedTenderOut,
    SavedTenderListOut,
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


def _saved_out(row: SavedTender) -> SavedTenderOut:
    import json as _json

    checklist: List[ChecklistItem] = []
    if row.checklist_json:
        try:
            checklist = [ChecklistItem(**c) for c in _json.loads(row.checklist_json)]
        except Exception:  # noqa: BLE001 - corrupt payload must not break reads
            checklist = []
    return SavedTenderOut(
        tender_id=row.tender_id,
        reminders_enabled=row.reminders_enabled,
        note=row.note,
        checklist=checklist,
        created_at=row.created_at,
    )


@router.get("/saved", response_model=SavedTenderListOut, summary="List saved tenders incl. workspace")
def list_saved_tenders(
    client_id: str = Query(..., min_length=1, max_length=128),
    db: Session = Depends(get_db),
):
    """The caller's saved tenders with their backed-up bid workspace."""
    user = _get_or_create_user(db, client_id)
    rows = db.execute(
        select(SavedTender)
        .where(SavedTender.user_id == user.id)
        .order_by(SavedTender.created_at.desc())
    ).scalars().all()
    return SavedTenderListOut(
        client_id=client_id, saved=[_saved_out(r) for r in rows]
    )


@router.put(
    "/saved/{tender_id}/workspace",
    response_model=SavedTenderOut,
    summary="Back up a saved tender's workspace (note + checklist)",
)
def put_workspace(tender_id: int, payload: WorkspaceIn, db: Session = Depends(get_db)):
    user = _get_or_create_user(db, payload.client_id)
    tender = db.get(Tender, tender_id)
    if not tender:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "TENDER_NOT_FOUND", "message": "Tender not found"},
        )
    row = db.execute(
        select(SavedTender).where(
            SavedTender.user_id == user.id, SavedTender.tender_id == tender_id
        )
    ).scalar_one_or_none()
    if not row:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={
                "code": "SAVED_TENDER_NOT_FOUND",
                "message": "Save the tender before syncing its workspace.",
            },
        )

    import json as _json

    updates = payload.model_dump(exclude_unset=True, exclude={"client_id"})
    if "note" in updates:
        row.note = updates["note"] or None
    if "checklist" in updates:
        items = updates.get("checklist") or []
        row.checklist_json = _json.dumps(items) if items else None
    db.commit()
    db.refresh(row)
    return _saved_out(row)
