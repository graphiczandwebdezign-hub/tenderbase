"""Saved searches: CRUD plus the alert matcher.

The matcher deliberately mirrors TenderService.list_tenders semantics in pure
Python (tokenized AND search across the same fields, multi-value facet lists,
derived lifecycle statuses, date windows), so "what alerted me" is always
answerable by running the saved params against GET /api/v1/tenders.
"""
from __future__ import annotations

import json
from datetime import date, timedelta
from typing import Dict, List, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.timeutils import utcnow, ensure_utc
from app.database.models import SavedSearch, Tender, TenderStatus, User
from app.services.tender_service import TenderService, InvalidParameter

# Fields scanned by saved-search free-text matching (same as discovery).
_MATCH_FIELDS = (
    "title",
    "description",
    "tender_number",
    "organisation",
    "category",
    "province",
    "municipality",
)

_LIVE_STATUSES = (TenderStatus.ACTIVE, TenderStatus.AMENDED)
_DERIVED_STATUSES = ("OPEN", "CLOSING_SOON", "CLOSED")

# Canonical parameter keys stored in params_json.
_ALLOWED_KEYS = (
    "search",
    "province",
    "category",
    "source",
    "status",
    "closing_within",
    "closing_after",
    "closing_before",
    "advertised_after",
    "advertised_before",
)


class DuplicateSearchName(Exception):
    """A saved search with this name already exists for the user."""


class SavedSearchService:
    def __init__(self, db: Session):
        self.db = db

    # ------------------------------------------------------------------ CRUD

    def _user(self, client_id: str) -> User:
        user = self.db.execute(
            select(User).where(User.client_id == client_id)
        ).scalar_one_or_none()
        if not user:
            user = User(client_id=client_id)
            self.db.add(user)
            self.db.commit()
            self.db.refresh(user)
        return user

    def create(self, client_id: str, name: str, filters: Dict[str, Optional[str]]) -> SavedSearch:
        """Validate + canonicalize discovery params and store them.

        Returns the created search; raises DuplicateSearchName on name clash
        and InvalidParameter on values the discovery endpoint would reject.
        """
        params = self._canonicalize(filters)
        user = self._user(client_id)
        existing = self.db.execute(
            select(SavedSearch).where(
                SavedSearch.user_id == user.id, SavedSearch.name == name
            )
        ).scalar_one_or_none()
        if existing is not None:
            raise DuplicateSearchName(name)
        search = SavedSearch(
            user_id=user.id,
            name=name,
            params_json=json.dumps(params),
            alerts_enabled=True,
        )
        self.db.add(search)
        self.db.commit()
        self.db.refresh(search)
        return search

    def list_for(self, client_id: str) -> List[SavedSearch]:
        user = self._user(client_id)
        return list(
            self.db.execute(
                select(SavedSearch)
                .where(SavedSearch.user_id == user.id)
                .order_by(SavedSearch.created_at.desc())
            ).scalars()
        )

    def get_owned(self, client_id: str, search_id: int) -> Optional[SavedSearch]:
        user = self._user(client_id)
        search = self.db.get(SavedSearch, search_id)
        if search is not None and search.user_id != user.id:
            return None
        return search

    def set_alerts(self, client_id: str, search_id: int, enabled: bool) -> Optional[SavedSearch]:
        search = self.get_owned(client_id, search_id)
        if search is None:
            return None
        search.alerts_enabled = enabled
        self.db.commit()
        self.db.refresh(search)
        return search

    def delete(self, client_id: str, search_id: int) -> bool:
        search = self.get_owned(client_id, search_id)
        if search is None:
            return False
        self.db.delete(search)
        self.db.commit()
        return True

    # ------------------------------------------------------------ validation

    @staticmethod
    def _canonicalize(filters: Dict[str, Optional[str]]) -> Dict[str, str]:
        """Keep only discovery-relevant keys, resolve taxonomy names to their
        canonical stored values, and validate status/window formats."""
        params: Dict[str, str] = {}
        for key in _ALLOWED_KEYS:
            value = filters.get(key)
            if value is None or (isinstance(value, str) and not value.strip()):
                continue
            params[key] = value.strip() if isinstance(value, str) else str(value)

        if "province" in params:
            names = [
                TenderService._resolve_province_name(v)
                for v in params["province"].split(",") if v.strip()
            ]
            params["province"] = ",".join(names)
        if "category" in params:
            names = [
                TenderService._resolve_category_name(v)
                for v in params["category"].split(",") if v.strip()
            ]
            params["category"] = ",".join(names)

        if "status" in params:
            status = params["status"].upper()
            valid = set(_DERIVED_STATUSES) | {s.value for s in TenderStatus}
            if status not in valid:
                raise InvalidParameter(
                    f"Invalid status '{params['status']}'. Valid values: "
                    f"{', '.join(sorted(valid))}."
                )
            params["status"] = status

        if "closing_within" in params:
            from app.services.tender_service import _parse_closing_within
            if _parse_closing_within(params["closing_within"]) is None:
                raise InvalidParameter(
                    "Invalid closing_within. Use e.g. '48h', '7d' or '30m'."
                )
        return params

    # --------------------------------------------------------------- matcher

    @staticmethod
    def matches(search: SavedSearch, tender: Tender, now=None) -> bool:
        """True when `tender` satisfies the saved discovery params.

        Mirrors TenderService.list_tenders: AND of ORs for search terms, OR
        within multi-value facet lists, derived lifecycle statuses and date
        windows evaluated at match time.
        """
        try:
            params = json.loads(search.params_json)
        except (ValueError, TypeError):
            return False
        now = now or utcnow()

        terms = [t for t in (params.get("search") or "").split() if t][:8]
        for term in terms:
            needle = term.lower()
            if not any(
                needle in (getattr(tender, f) or "").lower() for f in _MATCH_FIELDS
            ):
                return False

        for key, attr in (("province", "province"), ("category", "category"),
                          ("source", "source")):
            wanted = [v.strip() for v in (params.get(key) or "").split(",") if v.strip()]
            if wanted:
                value = (getattr(tender, attr) or "")
                if value not in wanted:
                    return False

        if "status" in params:
            if not _status_matches(params["status"], tender, now):
                return False

        if "closing_within" in params:
            from app.services.tender_service import _parse_closing_within
            delta = _parse_closing_within(params["closing_within"])
            closing = ensure_utc(tender.closing_at)
            if delta is None or closing is None or not (now < closing <= now + delta):
                return False

        closing_date = tender.closing_date
        if "closing_after" in params and (
            closing_date is None or closing_date < _as_date(params["closing_after"])
        ):
            return False
        if "closing_before" in params and (
            closing_date is None or closing_date > _as_date(params["closing_before"])
        ):
            return False

        advertised = tender.advertised_date
        if "advertised_after" in params and (
            advertised is None or advertised < _as_date(params["advertised_after"])
        ):
            return False
        if "advertised_before" in params and (
            advertised is None or advertised > _as_date(params["advertised_before"])
        ):
            return False

        return True

    def matching_searches(self, tender: Tender) -> List[SavedSearch]:
        """All alert-enabled saved searches that match this tender."""
        return [
            s for s in self.db.execute(
                select(SavedSearch).where(SavedSearch.alerts_enabled.is_(True))
            ).scalars()
            if self.matches(s, tender)
        ]


def _status_matches(wanted: str, tender: Tender, now) -> bool:
    """Same lifecycle derivation as the discovery SQL."""
    if wanted in _DERIVED_STATUSES:
        live = tender.status in _LIVE_STATUSES
        closing = ensure_utc(tender.closing_at)
        if wanted == "OPEN":
            return live and (closing is None or closing >= now)
        if wanted == "CLOSING_SOON":
            window = now + timedelta(hours=settings.closing_soon_hours)
            return live and closing is not None and now < closing <= window
        # CLOSED
        return tender.status in (TenderStatus.CLOSED, TenderStatus.EXPIRED) or (
            closing is not None and closing < now
        )
    try:
        return tender.status == TenderStatus(wanted)
    except ValueError:
        return False


def _as_date(value: str) -> date:
    from datetime import datetime
    return datetime.strptime(value, "%Y-%m-%d").date()
