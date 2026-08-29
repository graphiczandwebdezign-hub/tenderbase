"""Minimal web admin dashboard.

Server-rendered HTML for maintaining the data infrastructure only. It reads the
admin secret from a cookie set at login. This is deliberately simple — not a
CMS/CRM.
"""
from __future__ import annotations

import os
import secrets

from fastapi import APIRouter, Depends, Form, Request, status
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.routes.admin import build_dashboard
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
def dashboard_page(request: Request, db: Session = Depends(get_db)):
    if not _authed(request):
        return RedirectResponse(url="/admin/login", status_code=status.HTTP_303_SEE_OTHER)
    stats = build_dashboard(db)
    runs = db.execute(select(SyncRun).order_by(SyncRun.started_at.desc()).limit(10)).scalars().all()
    return templates.TemplateResponse(
        request,
        "dashboard.html",
        {
            "stats": stats,
            "runs": [SyncRunOut.model_validate(r) for r in runs],
            "last_sync": stats.get("last_sync"),
        },
    )


@router.post("/sync")
def dashboard_sync(request: Request):
    if not _authed(request):
        return RedirectResponse(url="/admin/login", status_code=status.HTTP_303_SEE_OTHER)
    if not sync_worker.is_running():
        sync_worker.run_once(trigger="manual")
    return RedirectResponse(url="/admin", status_code=status.HTTP_303_SEE_OTHER)
