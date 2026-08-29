"""Shared response envelopes."""
from __future__ import annotations

from typing import Generic, List, Optional, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class PaginationMeta(BaseModel):
    page: int
    limit: int
    total: int
    total_pages: int


class Paginated(BaseModel, Generic[T]):
    data: List[T]
    pagination: PaginationMeta


class ErrorDetail(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorDetail


def paginate(items: list, page: int, limit: int, total: int) -> dict:
    total_pages = (total + limit - 1) // limit if limit else 0
    return {
        "data": items,
        "pagination": {
            "page": page,
            "limit": limit,
            "total": total,
            "total_pages": total_pages,
        },
    }
