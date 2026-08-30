from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.security import require_api_key
from app.database.database import get_db
from app.database.models import Category
from app.schemas.taxonomy import CategoryOut

router = APIRouter(prefix="/categories", tags=["taxonomy"], dependencies=[Depends(require_api_key)])


@router.get("", response_model=List[CategoryOut], summary="List categories")
def list_categories(db: Session = Depends(get_db)):
    return list(db.execute(select(Category).order_by(Category.name)).scalars())
