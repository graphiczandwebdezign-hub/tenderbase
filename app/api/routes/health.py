"""Health endpoints (public — no API key required).

Sprint 10 splits the two probe concerns:

- ``/health``  — liveness: the process is up. Deliberately cheap (no DB
  round-trip) so a database outage never causes a restart loop.
- ``/ready``   — readiness: can this instance serve traffic? Checks the
  database connection and reports sync/scheduler state; returns 503 with the
  failing checks when it cannot.
"""
from __future__ import annotations

import time

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.core.config import settings
from app.database.database import get_db
from app.database.models import SyncRun, SyncStatus

router = APIRouter(tags=["health"])

_STARTED_MONOTONIC = time.monotonic()


@router.get("/health", summary="Liveness (public, cheap — no DB access)")
def health():
    return {
        "status": "healthy",
        "version": settings.app_version,
        "uptime_seconds": round(time.monotonic() - _STARTED_MONOTONIC, 1),
    }


@router.get(
    "/ready",
    summary="Readiness (public): database + sync state; 503 when not ready",
)
def ready(response: Response, db: Session = Depends(get_db)):
    checks: dict[str, str] = {}

    try:
        db.execute(text("SELECT 1"))
        checks["database"] = "connected"
    except Exception:  # noqa: BLE001 - any failure means not ready
        checks["database"] = "error"
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return {
            "status": "not_ready",
            "version": settings.app_version,
            "checks": checks,
            "last_sync": None,
        }

    checks["scheduler"] = "enabled" if settings.sync_enabled else "disabled"

    last_success = db.execute(
        select(SyncRun)
        .where(SyncRun.status == SyncStatus.SUCCESS)
        .order_by(SyncRun.completed_at.desc())
    ).scalars().first()
    checks["data"] = (
        "ingested" if last_success is not None else "no_successful_sync_yet"
    )

    ready_status = checks["database"] == "connected"
    body = {
        "status": "ready" if ready_status else "not_ready",
        "version": settings.app_version,
        "checks": checks,
        "last_sync": (
            last_success.completed_at.isoformat()
            if last_success and last_success.completed_at
            else None
        ),
    }
    if not ready_status:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return body
