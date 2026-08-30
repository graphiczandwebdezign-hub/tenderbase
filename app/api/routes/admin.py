"""Admin API (gated by X-Admin-Secret).

Scope is strictly data-infrastructure maintenance: monitor, sync, inspect,
correct, remove, troubleshoot. No CMS/CRM/social features.
"""
from __future__ import annotations

import json as _json
from datetime import timedelta
from typing import List, Optional

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
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
    SavedSearch,
    SearchEvent,
)
from app.schemas.admin import (
    DashboardOut,
    SyncRunOut,
    TenderUpdateIn,
    ApiKeyCreateIn,
    ApiKeyOut,
    ApiKeyCreatedOut,
    SearchAnalyticsOut,
    SavedSearchAnalyticsOut,
    DataQualityOut,
    SourceQualityOut,
    TermStat,
    DailyCount,
)
from app.schemas.common import Paginated, paginate
from app.schemas.tender import TenderOut
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
        "saved_searches": db.execute(
            select(func.count()).select_from(SavedSearch)
        ).scalar_one(),
        "searches_last_7d": db.execute(
            select(func.count()).select_from(SearchEvent).where(
                SearchEvent.created_at >= now - timedelta(days=7)
            )
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
def trigger_sync(background_tasks: BackgroundTasks, db: Session = Depends(get_db)):
    """Start a sync in the background and return immediately.

    Ingestion can take a while (network fetch + normalization), so the request
    does not block on it. Poll ``GET /api/v1/admin/sync-status`` or
    ``GET /api/v1/admin/sync-runs`` to follow progress and see the result.
    """
    if sync_worker.is_running():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "SYNC_IN_PROGRESS", "message": "A sync is already running"},
        )
    background_tasks.add_task(sync_worker.run_once, trigger="manual")
    return {
        "status": "STARTED",
        "message": "Sync started in the background. Check /api/v1/admin/sync-runs for the result.",
    }


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


# ---------------------------------------------------------------- Sprint 7: analytics & data quality

def _term_stats(events: list) -> dict:
    """Aggregate (term -> {count, results[]}) over events with query text."""
    stats: dict[str, dict] = {}
    for ev in events:
        if not ev.query_text:
            continue
        term = " ".join(ev.query_text.lower().split())
        bucket = stats.setdefault(term, {"count": 0, "results": []})
        bucket["count"] += 1
        bucket["results"].append(ev.results_count)
    return stats


def _top_terms(events: list, top: int, zero_results_only: bool = False) -> list:
    stats = _term_stats(events)
    out = []
    for term, b in stats.items():
        zero = all(r == 0 for r in b["results"])
        if zero_results_only and not zero:
            continue
        avg = round(sum(b["results"]) / len(b["results"]), 1)
        out.append(TermStat(term=term, count=b["count"], avg_results=avg))
    out.sort(key=lambda t: (-t.count, t.term))
    return out[:top]


def _facet_usage(payloads: list[str | None]) -> dict:
    usage: dict[str, int] = {}
    for raw in payloads:
        if not raw:
            continue
        try:
            parsed = _json.loads(raw)
        except Exception:  # noqa: BLE001 - corrupt rows must not break analytics
            continue
        if isinstance(parsed, dict):
            for key in parsed:
                usage[key] = usage.get(key, 0) + 1
    return dict(sorted(usage.items(), key=lambda kv: -kv[1]))


@router.get(
    "/analytics/searches",
    response_model=SearchAnalyticsOut,
    summary="Discovery telemetry: top searches, facet usage, zero-result searches",
)
def search_analytics(
    days: int = Query(30, ge=1, le=365),
    top: int = Query(10, ge=1, le=50),
    db: Session = Depends(get_db),
):
    """Aggregated, anonymous discovery behaviour — what users search and
    filter for, and where discovery comes up empty (the data-quality to-do
    list)."""
    since = utcnow() - timedelta(days=days)
    events = (
        db.execute(
            select(SearchEvent).where(SearchEvent.created_at >= since)
        )
        .scalars()
        .all()
    )

    daily_rows = db.execute(
        select(func.date(SearchEvent.created_at), func.count())
        .where(SearchEvent.created_at >= since)
        .group_by(func.date(SearchEvent.created_at))
    ).all()
    daily_map = {str(d): c for d, c in daily_rows}
    daily = [
        DailyCount(date=(utcnow() - timedelta(days=days - 1 - i)).date().isoformat(),
                   count=daily_map.get((utcnow() - timedelta(days=days - 1 - i)).date().isoformat(), 0))
        for i in range(days)
    ]

    total = len(events)
    zero = sum(1 for e in events if e.results_count == 0)
    avg = round(sum(e.results_count for e in events) / total, 1) if total else None
    return SearchAnalyticsOut(
        days=days,
        total_searches=total,
        zero_result_searches=zero,
        avg_results=avg,
        daily=daily,
        top_terms=_top_terms(events, top),
        top_zero_result_terms=_top_terms(events, top, zero_results_only=True),
        facet_usage=_facet_usage([e.filters_json for e in events]),
    )


@router.get(
    "/analytics/saved-searches",
    response_model=SavedSearchAnalyticsOut,
    summary="Saved-search analytics: volume, alert uptake, top filters",
)
def saved_search_analytics(
    top: int = Query(10, ge=1, le=50),
    db: Session = Depends(get_db),
):
    rows = db.execute(select(SavedSearch)).scalars().all()
    enabled = sum(1 for r in rows if r.alerts_enabled)
    users = {r.user_id for r in rows}

    # Terms are embedded in the stored params; mirror them as pseudo-events so
    # term aggregation stays identical with search telemetry.
    class _Pseudo:  # minimal duck-typed stand-in
        def __init__(self, query_text: str | None):
            self.query_text = query_text
            self.results_count = 0

    pseudo: list = []
    for r in rows:
        try:
            params = _json.loads(r.params_json)
        except Exception:  # noqa: BLE001
            continue
        if isinstance(params, dict) and params.get("search"):
            pseudo.append(_Pseudo(str(params["search"])))

    return SavedSearchAnalyticsOut(
        total=len(rows),
        alerts_enabled=enabled,
        alerts_disabled=len(rows) - enabled,
        distinct_users=len(users),
        top_terms=_top_terms(pseudo, top),
        facet_usage=_facet_usage([r.params_json for r in rows]),
    )


def _source_quality(source: str, tenders: list) -> SourceQualityOut:
    total = len(tenders)
    if not total:
        return SourceQualityOut(
            source=source, total=0, missing_closing_date=0, missing_province=0,
            missing_category=0, missing_organisation=0, missing_description=0,
            without_documents=0, open_past_deadline=0, completeness=0.0,
        )
    today = utcnow().date()
    counts = {
        "missing_closing_date": sum(1 for t in tenders if t.closing_date is None),
        "missing_province": sum(1 for t in tenders if not t.province),
        "missing_category": sum(1 for t in tenders if not t.categories),
        "missing_organisation": sum(1 for t in tenders if not t.organisation),
        "missing_description": sum(1 for t in tenders if not t.description),
        "without_documents": sum(1 for t in tenders if not t.documents),
        "open_past_deadline": sum(
            1 for t in tenders
            if t.closing_date and t.closing_date < today
            and t.status in (TenderStatus.ACTIVE, TenderStatus.AMENDED)
        ),
    }
    # Completeness: six equally weighted required fields; documents weigh less.
    field_parts = [
        1 - counts["missing_closing_date"] / total,
        1 - counts["missing_province"] / total,
        1 - counts["missing_category"] / total,
        1 - counts["missing_organisation"] / total,
        1 - counts["missing_description"] / total,
        1 - counts["without_documents"] / total,
    ]
    completeness = round(sum(field_parts) / len(field_parts), 3)
    return SourceQualityOut(source=source, total=total, completeness=completeness, **counts)


@router.get(
    "/data-quality",
    response_model=DataQualityOut,
    summary="Per-source data quality (missing deadlines, provinces, categories…)",
)
def data_quality(db: Session = Depends(get_db)):
    """Where ingestion is losing structured data: per-source counts of tenders
    missing closing dates, provinces, categories, organisation, description or
    documents, plus tenders still open past their deadline. Sorted worst
    completeness first so the top row is the next fix."""
    tenders = db.execute(select(Tender)).scalars().all()
    by_source: dict[str, list] = {}
    for t in tenders:
        by_source.setdefault(t.source or "unknown", []).append(t)
    sources = [
        _source_quality(src, ts) for src, ts in by_source.items()
    ]
    sources.sort(key=lambda s: (s.completeness, -s.total))
    return DataQualityOut(overall=_source_quality("(all)", tenders), sources=sources)
