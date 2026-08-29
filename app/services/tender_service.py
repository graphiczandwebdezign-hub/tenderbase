"""Tender query service: filtering, search, pagination, deadline-derived state.

All queries use the database for filtering/pagination (no in-Python paging) and
eager-load documents to avoid N+1 queries.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta
from typing import List, Optional, Tuple

from sqlalchemy import or_, select, func
from sqlalchemy.orm import Session, selectinload

from app.core.config import settings
from app.core import normalization as norm
from app.core.timeutils import utcnow, ensure_utc
from app.database.models import Tender, TenderStatus, TenderCategory


# Statuses considered "relevant / live" for the default feed.
_ACTIVE_STATUSES = (TenderStatus.ACTIVE, TenderStatus.AMENDED)


def _parse_closing_within(value: str) -> Optional[timedelta]:
    """Parse a '24h' / '7d' style window into a timedelta."""
    value = value.strip().lower()
    try:
        if value.endswith("h"):
            return timedelta(hours=int(value[:-1]))
        if value.endswith("d"):
            return timedelta(days=int(value[:-1]))
        if value.endswith("m"):
            return timedelta(minutes=int(value[:-1]))
        return timedelta(hours=int(value))
    except ValueError:
        return None


class TenderService:
    def __init__(self, db: Session):
        self.db = db

    def _base_query(self):
        return select(Tender).options(
            selectinload(Tender.documents),
            selectinload(Tender.categories).selectinload(TenderCategory.category),
        )

    def list_tenders(
        self,
        *,
        page: int = 1,
        limit: int = 25,
        category: Optional[str] = None,
        province: Optional[str] = None,
        organisation: Optional[str] = None,
        status: Optional[str] = None,
        search: Optional[str] = None,
        closing_within: Optional[str] = None,
        closing_before: Optional[date] = None,
        closing_after: Optional[date] = None,
        advertised_after: Optional[date] = None,
        advertised_before: Optional[date] = None,
        active_only: bool = True,
        order: str = "advertised",
    ) -> Tuple[List[Tender], int]:
        page = max(1, page)
        limit = max(1, min(limit, 100))

        stmt = self._base_query()
        conditions = []

        if status:
            try:
                conditions.append(Tender.status == TenderStatus(status.upper()))
                active_only = False
            except ValueError:
                pass

        if active_only:
            conditions.append(Tender.status.in_(_ACTIVE_STATUSES))

        if category:
            name = self._resolve_category_name(category)
            conditions.append(Tender.category == name)

        if province:
            name = self._resolve_province_name(province)
            conditions.append(Tender.province == name)

        if organisation:
            conditions.append(Tender.organisation.ilike(f"%{organisation}%"))

        if search:
            like = f"%{search}%"
            conditions.append(
                or_(
                    Tender.title.ilike(like),
                    Tender.description.ilike(like),
                    Tender.organisation.ilike(like),
                    Tender.tender_number.ilike(like),
                )
            )

        if closing_within:
            delta = _parse_closing_within(closing_within)
            if delta is not None:
                now = utcnow()
                conditions.append(Tender.closing_at.is_not(None))
                conditions.append(Tender.closing_at >= now)
                conditions.append(Tender.closing_at <= now + delta)

        if closing_before:
            conditions.append(Tender.closing_date <= closing_before)
        if closing_after:
            conditions.append(Tender.closing_date >= closing_after)
        if advertised_after:
            conditions.append(Tender.advertised_date >= advertised_after)
        if advertised_before:
            conditions.append(Tender.advertised_date <= advertised_before)

        for c in conditions:
            stmt = stmt.where(c)

        # Total count (without pagination), same conditions.
        count_stmt = select(func.count()).select_from(Tender)
        for c in conditions:
            count_stmt = count_stmt.where(c)
        total = self.db.execute(count_stmt).scalar_one()

        # Ordering
        if order == "closing":
            stmt = stmt.order_by(Tender.closing_at.asc().nullslast())
        else:
            stmt = stmt.order_by(
                Tender.advertised_date.desc().nullslast(), Tender.first_seen_at.desc()
            )

        stmt = stmt.offset((page - 1) * limit).limit(limit)
        rows = list(self.db.execute(stmt).scalars())
        return rows, total

    def latest(self, limit: int = 25) -> Tuple[List[Tender], int]:
        return self.list_tenders(page=1, limit=limit, order="advertised", active_only=True)

    def closing_soon(self, hours: Optional[int] = None, limit: int = 25) -> Tuple[List[Tender], int]:
        hours = hours or settings.closing_soon_hours
        return self.list_tenders(
            page=1, limit=limit, closing_within=f"{hours}h", order="closing", active_only=True
        )

    def get(self, tender_id: int) -> Optional[Tender]:
        return self.db.execute(
            self._base_query().where(Tender.id == tender_id)
        ).scalar_one_or_none()

    # --------------------------------------------------------- deadline state
    @staticmethod
    def deadline_state(tender: Tender) -> str:
        """Server-side deadline state. Never rely on the device clock."""
        if tender.status in (TenderStatus.CANCELLED,):
            return "CANCELLED"
        if tender.status == TenderStatus.EXPIRED:
            return "EXPIRED"
        now = utcnow()
        closing_at = ensure_utc(tender.closing_at)
        if closing_at:
            if closing_at <= now:
                return "CLOSED"
            if closing_at <= now + timedelta(hours=settings.closing_soon_hours):
                return "CLOSING_SOON"
        if tender.status == TenderStatus.CLOSED:
            return "CLOSED"
        return "ACTIVE"

    # ----------------------------------------------------------- name helpers
    @staticmethod
    def _resolve_category_name(value: str) -> str:
        v = value.strip().lower().replace(" ", "-")
        if v in norm.CATEGORY_NAMES:
            return norm.CATEGORY_NAMES[v]
        # Accept display names too.
        for slug, name in norm.CATEGORIES:
            if name.lower() == value.strip().lower():
                return name
        return value

    @staticmethod
    def _resolve_province_name(value: str) -> str:
        v = value.strip().lower().replace(" ", "-")
        if v in norm.PROVINCE_NAMES:
            return norm.PROVINCE_NAMES[v]
        for slug, name in norm.PROVINCES:
            if name.lower() == value.strip().lower():
                return name
        return value
