"""Public tender endpoints (API-key protected)."""
from __future__ import annotations

from datetime import date
from typing import Optional

import json as _json

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.api.serializers import serialize_tender, serialize_tender_detail
from app.core.config import settings
from app.core.security import require_api_key
from app.database.database import SessionLocal, get_db
from app.schemas.common import Paginated, paginate
from app.schemas.tender import TenderOut, TenderDetailOut
from app.services.tender_service import SORT_OPTIONS, InvalidParameter, TenderService

router = APIRouter(prefix="/tenders", tags=["tenders"], dependencies=[Depends(require_api_key)])

_STATUS_DESC = (
    "Single value: ACTIVE|AMENDED|CLOSED|CANCELLED|EXPIRED, or a lifecycle alias "
    "derived from stored dates: OPEN (live, deadline not passed), CLOSING_SOON "
    f"(open and closing within {settings.closing_soon_hours}h), CLOSED (closed/expired status or past deadline)"
)


def _bad_request(exc: InvalidParameter) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail={"code": "INVALID_PARAMETER", "message": str(exc)},
    )


def _record_search_event(
    endpoint: str, search: Optional[str], filters: dict, results_count: int
) -> None:
    """Anonymous telemetry write on its own session; never fails a request."""
    import logging

    from app.database.models import SearchEvent

    try:
        with SessionLocal() as db:
            db.add(
                SearchEvent(
                    endpoint=endpoint,
                    query_text=(search or "").strip() or None,
                    filters_json=_json.dumps(filters) if filters else None,
                    results_count=results_count,
                )
            )
            db.commit()
    except Exception:  # noqa: BLE001 - telemetry must never break discovery
        logging.getLogger(__name__).debug("search event write failed", exc_info=True)


@router.get("", response_model=Paginated[TenderOut], summary="List/search/filter tenders")
def list_tenders(
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    page: int = Query(1, ge=1, description="1-based page number"),
    limit: int = Query(25, ge=1, le=100, description="Items per page (max 100)"),
    category: Optional[str] = Query(None, description="Category slug/name; comma-separated for multiple"),
    province: Optional[str] = Query(None, description="Province slug/name; comma-separated for multiple"),
    municipality: Optional[str] = Query(None, description="Substring match on municipality"),
    organisation: Optional[str] = Query(None, description="Substring match on organisation"),
    source: Optional[str] = Query(None, description="Tender source name; comma-separated for multiple"),
    status_: Optional[str] = Query(None, alias="status", description=_STATUS_DESC),
    search: Optional[str] = Query(None, description="Keyword search (all terms must match) over title, description, reference, organisation, category, province, municipality"),
    sort: Optional[str] = Query("newest", description=f"Sort order: {'|'.join(SORT_OPTIONS)}. 'relevance' ranks weighted field matches and is meaningful only together with `search`"),
    closing_within: Optional[str] = Query(None, description="Window e.g. '24h' or '7d'"),
    closing_before: Optional[date] = Query(None),
    closing_after: Optional[date] = Query(None),
    advertised_after: Optional[date] = Query(None),
    advertised_before: Optional[date] = Query(None),
):
    """List active tenders by default. Supply `status` to include others.

    The default feed returns only ACTIVE/AMENDED tenders — expired opportunities
    are never shown in the default latest feed.
    """
    svc = TenderService(db)
    try:
        rows, total = svc.list_tenders(
            page=page, limit=limit, category=category, province=province,
            municipality=municipality, organisation=organisation, status=status_,
            source=source, search=search, closing_within=closing_within,
            closing_before=closing_before, closing_after=closing_after,
            advertised_after=advertised_after, advertised_before=advertised_before,
            order=sort or "newest",
        )
    except InvalidParameter as exc:
        raise _bad_request(exc) from None
    background_tasks.add_task(
        _record_search_event, "list", search,
        {
            key: str(value) for key, value in (
                ("category", category), ("province", province),
                ("municipality", municipality), ("organisation", organisation),
                ("source", source), ("status", status_),
                ("closing_within", closing_within), ("closing_before", closing_before),
                ("closing_after", closing_after), ("advertised_after", advertised_after),
                ("advertised_before", advertised_before),
            ) if value is not None
        },
        total,
    )
    return paginate([serialize_tender(t) for t in rows], page, limit, total)


@router.get("/facets", summary="Filter options with counts (open tenders)")
def tender_facets(db: Session = Depends(get_db)):
    """Distinct province/category/source values present on currently open
    tenders, with counts — used to build filter UIs without inventing values."""
    return TenderService(db).facets()


@router.get("/latest", response_model=Paginated[TenderOut], summary="Latest active tenders")
def latest(db: Session = Depends(get_db), limit: int = Query(25, ge=1, le=100)):
    svc = TenderService(db)
    rows, total = svc.latest(limit=limit)
    return paginate([serialize_tender(t) for t in rows], 1, limit, total)


@router.get("/closing-soon", response_model=Paginated[TenderOut], summary="Tenders closing soon")
def closing_soon(
    db: Session = Depends(get_db),
    hours: int = Query(48, ge=1, le=720, description="Closing within N hours"),
    limit: int = Query(25, ge=1, le=100),
):
    svc = TenderService(db)
    rows, total = svc.closing_soon(hours=hours, limit=limit)
    return paginate([serialize_tender(t) for t in rows], 1, limit, total)


@router.get("/search", response_model=Paginated[TenderOut], summary="Search tenders")
def search(
    db: Session = Depends(get_db),
    q: str = Query(..., min_length=1, description="Search query"),
    page: int = Query(1, ge=1),
    limit: int = Query(25, ge=1, le=100),
    category: Optional[str] = Query(None),
    province: Optional[str] = Query(None),
    sort: Optional[str] = Query("relevance", description="Sort order for this search"),
):
    svc = TenderService(db)
    try:
        rows, total = svc.list_tenders(
            page=page, limit=limit, search=q, category=category, province=province,
            order=sort or "relevance",
        )
    except InvalidParameter as exc:
        raise _bad_request(exc) from None
    return paginate([serialize_tender(t) for t in rows], page, limit, total)


@router.get("/{tender_id}", response_model=TenderDetailOut, summary="Tender detail")
def get_tender(tender_id: int, db: Session = Depends(get_db)):
    svc = TenderService(db)
    tender = svc.get(tender_id)
    if not tender:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "TENDER_NOT_FOUND", "message": "Tender not found"},
        )
    return serialize_tender_detail(tender)
