"""Minimal web admin dashboard.

Server-rendered HTML for maintaining the data infrastructure only. It reads the
admin secret from a cookie set at login. This is deliberately simple — not a
CMS/CRM.
"""
from __future__ import annotations

import os
import secrets
from typing import Optional
from urllib.parse import quote

from fastapi import APIRouter, BackgroundTasks, Depends, Form, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.routes.admin import (
    build_dashboard,
    data_quality,
    re_enrich as run_re_enrich,
    saved_search_analytics,
    search_analytics,
)
from app.core.config import settings
from app.database.database import get_db
from app.database.models import SyncRun
from app.schemas.admin import SyncRunOut
from app.workers import sync_worker

_TEMPLATE_DIR = os.path.join(os.path.dirname(__file__), "templates")
templates = Jinja2Templates(directory=_TEMPLATE_DIR)

router = APIRouter(prefix="/admin", tags=["admin-ui"], include_in_schema=False)

_COOKIE = "admin_session"


def _authed(request: Request) -> bool:
    if not settings.admin_secret:
        return False
    cookie = request.cookies.get(_COOKIE)
    return bool(cookie) and secrets.compare_digest(cookie, settings.admin_secret)


@router.get("/login", response_class=HTMLResponse)
def login_form(request: Request):
    return templates.TemplateResponse(request, "login.html", {"error": None})


@router.post("/login")
def login(request: Request, secret: str = Form(...)):
    if settings.admin_secret and secrets.compare_digest(secret, settings.admin_secret):
        resp = RedirectResponse(url="/admin", status_code=status.HTTP_303_SEE_OTHER)
        resp.set_cookie(_COOKIE, secret, httponly=True, samesite="lax", max_age=3600)
        return resp
    return templates.TemplateResponse(
        request, "login.html", {"error": "Invalid admin secret"}, status_code=401
    )


@router.get("/logout")
def logout():
    resp = RedirectResponse(url="/admin/login", status_code=status.HTTP_303_SEE_OTHER)
    resp.delete_cookie(_COOKIE)
    return resp


@router.get("", response_class=HTMLResponse)
def dashboard_page(
    request: Request, enrich: Optional[str] = None, db: Session = Depends(get_db)
):
    if not _authed(request):
        return RedirectResponse(url="/admin/login", status_code=status.HTTP_303_SEE_OTHER)
    stats = build_dashboard(db)
    runs = db.execute(select(SyncRun).order_by(SyncRun.started_at.desc()).limit(10)).scalars().all()

    # Sprint 9: discovery analytics + data quality, reusing the admin API logic.
    analytics = search_analytics(days=30, top=8, db=db)
    saved = saved_search_analytics(top=8, db=db)
    quality = data_quality(db)
    max_daily = max((d.count for d in analytics.daily), default=0) or 1
    bars = [
        {
            "date": d.date,
            "count": d.count,
            "h": max(3, round(d.count / max_daily * 40)) if d.count else 1,
        }
        for d in analytics.daily
    ]

    return templates.TemplateResponse(
        request,
        "dashboard.html",
        {
            "stats": stats,
            "runs": [SyncRunOut.model_validate(r) for r in runs],
            "last_sync": stats.get("last_sync"),
            "analytics": analytics,
            "saved": saved,
            "quality": quality,
            "bars": bars,
            "enrich_summary": enrich,
        },
    )


@router.post("/re-enrich")
def dashboard_re_enrich(
    request: Request, dry_run: Optional[str] = Form(None), db: Session = Depends(get_db)
):
    """Sprint 9: backfill extraction heuristics from the console (PRG)."""
    if not _authed(request):
        return RedirectResponse(url="/admin/login", status_code=status.HTTP_303_SEE_OTHER)
    is_dry = (dry_run or "").lower() in {"true", "1", "on"}
    result = run_re_enrich(dry_run=is_dry, db=db)
    summary = (
        f"{result.province_filled} provinces, {result.municipality_filled} municipalities, "
        f"{result.closing_filled} deadlines filled"
        + (" (dry run — nothing changed)" if is_dry else "")
    )
    return RedirectResponse(
        url=f"/admin?enrich={quote(summary)}#quality", status_code=status.HTTP_303_SEE_OTHER
    )


@router.post("/sync")
def dashboard_sync(request: Request, background_tasks: BackgroundTasks):
    if not _authed(request):
        return RedirectResponse(url="/admin/login", status_code=status.HTTP_303_SEE_OTHER)
    if not sync_worker.is_running():
        # Run in the background so the dashboard responds immediately.
        background_tasks.add_task(sync_worker.run_once, trigger="manual")
    return RedirectResponse(url="/admin", status_code=status.HTTP_303_SEE_OTHER)
