"""Admin API (gated by X-Admin-Secret).

Scope is strictly data-infrastructure maintenance: monitor, sync, inspect,
correct, remove, troubleshoot. No CMS/CRM/social features.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.serializers import serialize_tender
from app.core.security import require_admin, generate_api_key, hash_key
from app.core.timeutils import utcnow
from app.database.database import get_db
from app.database.models import (
    Tender,
    TenderStatus,
    SyncRun,
    SyncStatus,
    User,
    NotificationToken,
    ApiKey,
)
from app.schemas.admin import (
    DashboardOut,
    SyncRunOut,
    TenderUpdateIn,
    ApiKeyCreateIn,
    ApiKeyOut,
    ApiKeyCreatedOut,
)
from app.schemas.common import Paginated, paginate
from app.schemas.tender import TenderOut
from app.services.ingestion_service import IngestionService
from app.services.tender_service import TenderService
from app.workers import sync_worker

router = APIRouter(prefix="/admin", tags=["admin"], dependencies=[Depends(require_admin)])


def _count(db: Session, *conditions) -> int:
    stmt = select(func.count()).select_from(Tender)
    for c in conditions:
        stmt = stmt.where(c)
    return db.execute(stmt).scalar_one()


def build_dashboard(db: Session) -> dict:
    now = utcnow()
    today = now.date()
    tomorrow = today + timedelta(days=1)

    last_any = db.execute(
        select(SyncRun).order_by(SyncRun.started_at.desc())
    ).scalars().first()
    last_success = db.execute(
        select(SyncRun).where(SyncRun.status == SyncStatus.SUCCESS)
        .order_by(SyncRun.completed_at.desc())
    ).scalars().first()

    return {
        "active_tenders": _count(db, Tender.status == TenderStatus.ACTIVE),
        "amended_tenders": _count(db, Tender.status == TenderStatus.AMENDED),
        "new_today": _count(db, func.date(Tender.first_seen_at) == today),
        "closing_today": _count(db, Tender.closing_date == today,
                                Tender.status.in_([TenderStatus.ACTIVE, TenderStatus.AMENDED])),
        "closing_tomorrow": _count(db, Tender.closing_date == tomorrow,
                                   Tender.status.in_([TenderStatus.ACTIVE, TenderStatus.AMENDED])),
        "closed": _count(db, Tender.status == TenderStatus.CLOSED),
        "expired": _count(db, Tender.status == TenderStatus.EXPIRED),
        "total_tenders": _count(db),
        "total_users": db.execute(select(func.count()).select_from(User)).scalar_one(),
        "total_devices": db.execute(
            select(func.count()).select_from(NotificationToken).where(NotificationToken.active.is_(True))
        ).scalar_one(),
        "last_sync": last_any,
        "last_successful_sync_at": last_success.completed_at if last_success else None,
    }


@router.get("/dashboard", response_model=DashboardOut, summary="System dashboard counters")
def dashboard(db: Session = Depends(get_db)):
    return build_dashboard(db)


@router.get("/sync-status", summary="Current sync status")
def sync_status(db: Session = Depends(get_db)):
    running = db.execute(
        select(SyncRun).where(SyncRun.status == SyncStatus.RUNNING)
    ).scalars().first()
    last = db.execute(select(SyncRun).order_by(SyncRun.started_at.desc())).scalars().first()
    return {
        "in_progress": sync_worker.is_running() or running is not None,
        "last_run": SyncRunOut.model_validate(last) if last else None,
    }


@router.post("/sync", summary="Trigger a manual sync")
def trigger_sync(db: Session = Depends(get_db)):
    if sync_worker.is_running():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "SYNC_IN_PROGRESS", "message": "A sync is already running"},
        )
    run = sync_worker.run_once(trigger="manual")
    return SyncRunOut.model_validate(run)


@router.get("/sync-runs", response_model=List[SyncRunOut], summary="Sync run history")
def sync_runs(db: Session = Depends(get_db), limit: int = Query(25, ge=1, le=100)):
    rows = db.execute(
        select(SyncRun).order_by(SyncRun.started_at.desc()).limit(limit)
    ).scalars().all()
    return [SyncRunOut.model_validate(r) for r in rows]


@router.get("/tenders", response_model=Paginated[TenderOut], summary="List all tenders (admin)")
def admin_tenders(
    db: Session = Depends(get_db),
    page: int = Query(1, ge=1),
    limit: int = Query(25, ge=1, le=100),
    status_: Optional[str] = Query(None, alias="status"),
    search: Optional[str] = Query(None),
):
    svc = TenderService(db)
    rows, total = svc.list_tenders(
        page=page, limit=limit, status=status_, search=search, active_only=False
    )
    return paginate([serialize_tender(t) for t in rows], page, limit, total)


@router.patch("/tenders/{tender_id}", response_model=TenderOut, summary="Correct a tender")
def update_tender(tender_id: int, payload: TenderUpdateIn, db: Session = Depends(get_db)):
    tender = db.get(Tender, tender_id)
    if not tender:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "TENDER_NOT_FOUND", "message": "Tender not found"},
        )
    if payload.status:
        try:
            tender.status = TenderStatus(payload.status.upper())
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={"code": "INVALID_STATUS", "message": "Invalid status"},
            )
    if payload.title is not None:
        tender.title = payload.title
    if payload.category is not None:
        tender.category = payload.category
    if payload.province is not None:
        tender.province = payload.province
    db.commit()
    return serialize_tender(TenderService(db).get(tender_id))


@router.delete("/tenders/{tender_id}", summary="Remove a problematic tender")
def delete_tender(tender_id: int, db: Session = Depends(get_db)):
    tender = db.get(Tender, tender_id)
    if not tender:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "TENDER_NOT_FOUND", "message": "Tender not found"},
        )
    db.delete(tender)
    db.commit()
    return {"status": "deleted", "id": tender_id}


# --------------------------------------------------------------- API keys
@router.get("/api-keys", response_model=List[ApiKeyOut], summary="List API keys")
def list_api_keys(db: Session = Depends(get_db)):
    rows = db.execute(select(ApiKey).order_by(ApiKey.created_at.desc())).scalars().all()
    return [ApiKeyOut.model_validate(r, from_attributes=True) for r in rows]


@router.post("/api-keys", response_model=ApiKeyCreatedOut, summary="Generate an API key")
def create_api_key(payload: ApiKeyCreateIn, db: Session = Depends(get_db)):
    raw = generate_api_key()
    key = ApiKey(
        name=payload.name,
        key_hash=hash_key(raw),
        key_prefix=raw[:8],
        active=True,
        expires_at=payload.expires_at,
    )
    db.add(key)
    db.commit()
    db.refresh(key)
    return ApiKeyCreatedOut(
        id=key.id,
        name=key.name,
        key_prefix=key.key_prefix,
        active=key.active,
        created_at=key.created_at,
        last_used_at=key.last_used_at,
        expires_at=key.expires_at,
        api_key=raw,  # shown once
    )


@router.delete("/api-keys/{key_id}", summary="Revoke an API key")
def revoke_api_key(key_id: int, db: Session = Depends(get_db)):
    key = db.get(ApiKey, key_id)
    if not key:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "KEY_NOT_FOUND", "message": "API key not found"},
        )
    key.active = False
    db.commit()
    return {"status": "revoked", "id": key_id}
