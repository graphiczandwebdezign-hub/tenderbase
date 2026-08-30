"""Tender query service: filtering, search, sorting, pagination, deadline-derived state.

All queries use the database for filtering/pagination (no in-Python paging) and
eager-load documents to avoid N+1 queries.

Discovery semantics (Sprint 1):
- Search is tokenized (AND across terms) and matches title, description,
  reference number, organisation, category, province and municipality.
- Sorting: newest (default), closing (soonest open deadline first), updated
  (recently changed first) and relevance (weighted field match; meaningful only
  with a search query, otherwise identical to newest).
- Status accepts the stored enum values plus derived lifecycle aliases
  (open / closing_soon / closed) computed from stored dates — never the
  device clock.
"""
from __future__ import annotations

from datetime import date, timedelta
from typing import List, Optional, Sequence, Tuple

from sqlalchemy import or_, select, func, case
from sqlalchemy.orm import Session, selectinload
from sqlalchemy.sql.elements import ColumnElement

from app.core.config import settings
from app.core import normalization as norm
from app.core.timeutils import utcnow, ensure_utc
from app.database.models import Tender, TenderStatus, TenderCategory


# Statuses considered "relevant / live" for the default feed.
_ACTIVE_STATUSES = (TenderStatus.ACTIVE, TenderStatus.AMENDED)

# Accepted sort values (validated; surfaced by the API docs).
SORT_OPTIONS = ("newest", "closing", "updated", "relevance")

# Status values derived from stored dates rather than the raw status enum.
_DERIVED_STATUSES = ("OPEN", "CLOSING_SOON", "CLOSED")

# Fields covered by free-text search.
_SEARCH_FIELDS = (
    Tender.title,
    Tender.description,
    Tender.tender_number,
    Tender.organisation,
    Tender.category,
    Tender.province,
    Tender.municipality,
)


class InvalidParameter(ValueError):
    """Raised when a client-supplied filter/sort value is not recognised."""


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


def _like_escape(term: str) -> str:
    """Escape LIKE wildcards so user input is matched literally."""
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _contains(field, term: str):
    """Case-insensitive literal-contains condition, portable across engines
    (an explicit escape char keeps SQLite and PostgreSQL identical)."""
    return field.ilike(f"%{_like_escape(term)}%", escape="\\")


def _any_of(field, values: List[str]):
    """OR of equality conditions for a multi-value facet filter."""
    return or_(*[field == v for v in values])


class TenderService:
    def __init__(self, db: Session):
        self.db = db

    def _base_query(self):
        return select(Tender).options(
            selectinload(Tender.documents),
            selectinload(Tender.categories).selectinload(TenderCategory.category),
        )

    # ------------------------------------------------------------- list/search
    def list_tenders(
        self,
        *,
        page: int = 1,
        limit: int = 25,
        category: Optional[str] = None,
        province: Optional[str] = None,
        municipality: Optional[str] = None,
        organisation: Optional[str] = None,
        status: Optional[str] = None,
        source: Optional[str] = None,
        search: Optional[str] = None,
        closing_within: Optional[str] = None,
        closing_before: Optional[date] = None,
        closing_after: Optional[date] = None,
        advertised_after: Optional[date] = None,
        advertised_before: Optional[date] = None,
        active_only: bool = True,
        order: str = "newest",
    ) -> Tuple[List[Tender], int]:
        page = max(1, page)
        limit = max(1, min(limit, 100))
        order = (order or "newest").strip().lower()
        if order not in SORT_OPTIONS:
            raise InvalidParameter(
                f"Invalid sort '{order}'. Valid values: {', '.join(SORT_OPTIONS)}."
            )

        stmt = self._base_query()
        conditions: List[ColumnElement] = []

        if status:
            # Single status value (enum or derived lifecycle alias).
            raw = status.strip().upper()
            if "," in raw:
                raise InvalidParameter(
                    "status must be a single value, not a comma-separated list."
                )
            conditions.extend(self._status_condition(raw))
            active_only = False

        if active_only:
            conditions.append(Tender.status.in_(_ACTIVE_STATUSES))

        # Multi-value taxonomy filters (comma separated, OR within a facet).
        categories = [self._resolve_category_name(c) for c in self._split_list(category)]
        if categories:
            conditions.append(_any_of(Tender.category, categories))
        provinces = [self._resolve_province_name(p) for p in self._split_list(province)]
        if provinces:
            conditions.append(_any_of(Tender.province, provinces))

        if municipality:
            conditions.append(_contains(Tender.municipality, municipality.strip()))

        if source:
            sources = [s.strip() for s in source.split(",") if s.strip()]
            if sources:
                conditions.append(_any_of(Tender.source, sources))

        if organisation:
            conditions.append(_contains(Tender.organisation, organisation.strip()))

        # Free-text search: every term must match at least one field (AND of ORs).
        terms = self._search_terms(search)
        score = None
        if terms:
            for term in terms:
                conditions.append(or_(*[_contains(f, term) for f in _SEARCH_FIELDS]))
            score = self._relevance_score(terms)

        if closing_within:
            delta = _parse_closing_within(closing_within)
            if delta is not None:
                now = utcnow()
                conditions.append(Tender.closing_at.is_not(None))
                conditions.append(Tender.closing_at >= now)
                conditions.append(Tender.closing_at <= now + delta)
            else:
                raise InvalidParameter(
                    "Invalid closing_within. Use e.g. '48h', '7d' or '30m'."
                )

        if closing_before:
            conditions.append(Tender.closing_date <= closing_before)
        if closing_after:
            conditions.append(Tender.closing_date >= closing_after)
        if advertised_after:
            conditions.append(Tender.advertised_date >= advertised_after)
        if advertised_before:
            conditions.append(Tender.advertised_date <= advertised_before)

        # "Closing soonest" must never surface already-closed tenders when the
        # caller did not explicitly ask for closed ones.
        if order == "closing" and active_only:
            conditions.append(Tender.closing_at.is_not(None))
            conditions.append(Tender.closing_at >= utcnow())

        for c in conditions:
            stmt = stmt.where(c)

        # Total count (without pagination), same conditions.
        count_stmt = select(func.count()).select_from(Tender)
        for c in conditions:
            count_stmt = count_stmt.where(c)
        total = self.db.execute(count_stmt).scalar_one()

        stmt = stmt.order_by(*self._order_by(order, score, terms))

        stmt = stmt.offset((page - 1) * limit).limit(limit)
        rows = list(self.db.execute(stmt).scalars())
        return rows, total

    # ------------------------------------------------------------------ facets
    def facets(self) -> dict:
        """Distinct filter options with counts, over the default open feed.

        Powers the client filter UI without inventing values: whatever is
        returned actually exists on open tenders in the database.
        """
        base_status = Tender.status.in_(_ACTIVE_STATUSES)

        def _facet(column):
            stmt = (
                select(column, func.count())
                .where(column.is_not(None), base_status)
                .group_by(column)
                .order_by(func.count().desc(), column.asc())
            )
            return [
                {"name": name, "count": count}
                for name, count in self.db.execute(stmt).all()
            ]

        return {
            "provinces": _facet(Tender.province),
            "categories": _facet(Tender.category),
            "sources": _facet(Tender.source),
        }

    def latest(self, limit: int = 25) -> Tuple[List[Tender], int]:
        return self.list_tenders(page=1, limit=limit, order="newest", active_only=True)

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

    # -------------------------------------------------------------- internals
    @staticmethod
    def _status_condition(raw: str) -> List[ColumnElement]:
        """Map a requested status onto a SQL condition.

        Accepts stored enum values (ACTIVE, AMENDED, CLOSED, CANCELLED,
        EXPIRED) plus lifecycle aliases derived from stored dates:

        - OPEN: live status and the deadline (when set) has not passed.
        - CLOSING_SOON: open and closing within the configured window.
        - CLOSED: closed/expired status or a past deadline.
        """
        now = utcnow()
        live = Tender.status.in_(_ACTIVE_STATUSES)
        if raw in _DERIVED_STATUSES:
            if raw == "OPEN":
                return [live, or_(Tender.closing_at.is_(None), Tender.closing_at >= now)]
            if raw == "CLOSING_SOON":
                window = now + timedelta(hours=settings.closing_soon_hours)
                return [
                    live,
                    Tender.closing_at.is_not(None),
                    Tender.closing_at >= now,
                    Tender.closing_at <= window,
                ]
            # CLOSED
            return [or_(Tender.status.in_((TenderStatus.CLOSED, TenderStatus.EXPIRED)),
                        Tender.closing_at < now)]
        try:
            return [Tender.status == TenderStatus(raw)]
        except ValueError:
            raise InvalidParameter(
                f"Invalid status '{raw}'. Valid values: "
                f"{', '.join(list(_DERIVED_STATUSES) + [s.value for s in TenderStatus])}."
            ) from None

    @staticmethod
    def _split_list(value: Optional[str]) -> List[str]:
        if not value:
            return []
        return [v.strip() for v in value.split(",") if v.strip()]

    @staticmethod
    def _search_terms(search: Optional[str]) -> List[str]:
        if not search:
            return []
        terms = [t for t in (s.strip() for s in search.split()) if t]
        # Bound query complexity; longest terms carry the signal anyway.
        return terms[:8]

    @staticmethod
    def _relevance_score(terms: Sequence[str]) -> ColumnElement:
        """Weighted field-match score: title beats reference beats issuer beats
        classification beats description. Deterministic, no magic."""
        total = None
        for term in terms:
            per_term = case(
                (_contains(Tender.title, term), 32),
                (_contains(Tender.tender_number, term), 16),
                (_contains(Tender.organisation, term), 8),
                (or_(_contains(Tender.category, term),
                     _contains(Tender.province, term),
                     _contains(Tender.municipality, term)), 4),
                else_=1,
            )
            total = per_term if total is None else total + per_term
        return total

    def _order_by(self, order: str, score, terms: Sequence[str]) -> List:
        """Deterministic orderings — a stable tie-breaker on id keeps offset
        pagination free of duplicates/shuffles."""
        if order == "relevance" and terms and score is not None:
            return [
                score.desc(),
                Tender.advertised_date.desc().nullslast(),
                Tender.id.desc(),
            ]
        if order == "relevance":
            # No query to rank — identical to newest (honest fallback).
            order = "newest"
        if order == "closing":
            return [Tender.closing_at.asc().nullslast(), Tender.id.asc()]
        if order == "updated":
            return [Tender.updated_at.desc().nullslast(), Tender.id.desc()]
        return [Tender.advertised_date.desc().nullslast(),
                Tender.first_seen_at.desc(),
                Tender.id.desc()]

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

