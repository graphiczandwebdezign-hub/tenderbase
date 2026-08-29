from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.security import require_api_key
from app.database.database import get_db
from app.database.models import Province
from app.schemas.taxonomy import ProvinceOut

router = APIRouter(prefix="/provinces", tags=["taxonomy"], dependencies=[Depends(require_api_key)])


@router.get("", response_model=List[ProvinceOut], summary="List provinces")
def list_provinces(db: Session = Depends(get_db)):
    return list(db.execute(select(Province).order_by(Province.name)).scalars())
