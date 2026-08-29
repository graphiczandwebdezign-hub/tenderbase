"""Health and freshness endpoints (public — no API key required)."""
from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.core.config import settings
from app.database.database import get_db
from app.database.models import SyncRun, SyncStatus

router = APIRouter(tags=["health"])


def _health_payload(db: Session) -> dict:
    db_status = "connected"
    try:
        db.execute(text("SELECT 1"))
    except Exception:  # noqa: BLE001
        db_status = "error"

    last_success = db.execute(
        select(SyncRun)
        .where(SyncRun.status == SyncStatus.SUCCESS)
        .order_by(SyncRun.completed_at.desc())
    ).scalars().first()

    last_any = db.execute(
        select(SyncRun).order_by(SyncRun.started_at.desc())
    ).scalars().first()

    return {
        "status": "healthy" if db_status == "connected" else "degraded",
        "version": settings.app_version,
        "database": db_status,
        "last_sync": last_success.completed_at.isoformat() if last_success and last_success.completed_at else None,
        "last_sync_status": last_any.status.value if last_any else None,
    }


@router.get("/health", summary="Liveness/health (public)")
def health(db: Session = Depends(get_db)):
    return _health_payload(db)
