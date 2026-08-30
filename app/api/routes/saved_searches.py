"""Saved searches: persisted discovery queries with alerts (API-key protected)."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.core.security import require_api_key
from app.database.database import get_db
from app.schemas.saved_search import (
    SavedSearchAlertsIn,
    SavedSearchCreateIn,
    SavedSearchListOut,
    SavedSearchOut,
)
from app.services.saved_search_service import (
    DuplicateSearchName,
    SavedSearchService,
)
from app.services.tender_service import InvalidParameter

router = APIRouter(
    prefix="/saved-searches", tags=["saved-searches"], dependencies=[Depends(require_api_key)]
)


def _to_out(search) -> SavedSearchOut:
    import json
    return SavedSearchOut(
        id=search.id,
        name=search.name,
        alerts_enabled=search.alerts_enabled,
        filters=json.loads(search.params_json),
        created_at=search.created_at,
    )


@router.get("", response_model=SavedSearchListOut, summary="List saved searches")
def list_saved_searches(
    client_id: str = Query(..., min_length=1, max_length=128),
    db: Session = Depends(get_db),
):
    svc = SavedSearchService(db)
    return SavedSearchListOut(
        client_id=client_id,
        searches=[_to_out(s) for s in svc.list_for(client_id)],
    )


@router.post(
    "",
    response_model=SavedSearchOut,
    status_code=status.HTTP_201_CREATED,
    summary="Save a search (alerts on by default)",
)
def create_saved_search(payload: SavedSearchCreateIn, db: Session = Depends(get_db)):
    svc = SavedSearchService(db)
    try:
        search = svc.create(
            payload.client_id, payload.name, payload.filters.model_dump(exclude_none=True)
        )
    except InvalidParameter as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "INVALID_PARAMETER", "message": str(exc)},
        ) from None
    except DuplicateSearchName:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "code": "DUPLICATE_SEARCH_NAME",
                "message": "A saved search with this name already exists.",
            },
        ) from None
    return _to_out(search)


@router.patch(
    "/{search_id}/alerts",
    response_model=SavedSearchOut,
    summary="Enable/disable alerts for a saved search",
)
def set_alerts(search_id: int, payload: SavedSearchAlertsIn, db: Session = Depends(get_db)):
    svc = SavedSearchService(db)
    search = svc.set_alerts(payload.client_id, search_id, payload.alerts_enabled)
    if search is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "SAVED_SEARCH_NOT_FOUND", "message": "Saved search not found."},
        )
    return _to_out(search)


@router.delete("/{search_id}", summary="Delete a saved search")
def delete_saved_search(
    search_id: int,
    client_id: str = Query(..., min_length=1, max_length=128),
    db: Session = Depends(get_db),
):
    svc = SavedSearchService(db)
    if not svc.delete(client_id, search_id):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "SAVED_SEARCH_NOT_FOUND", "message": "Saved search not found."},
        )
    return {"status": "deleted", "id": search_id}
