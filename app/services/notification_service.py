"""Notification matching and dispatch.

Flow: new/amended tender -> match user preferences (category + province) ->
create a NotificationEvent (deduplicated by DB constraint) -> push via FCM.

Duplicate prevention is guaranteed by the unique constraint on
(user_id, tender_id, notification_type): if an event already exists the insert
is skipped, so a user is never alerted twice for the same tender+type.
"""
from __future__ import annotations

from datetime import timedelta
from typing import List, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.logging import get_logger
from app.core.timeutils import utcnow, ensure_utc
from app.database.models import (
    Tender,
    TenderStatus,
    Category,
    Province,
    UserPreference,
    NotificationToken,
    NotificationEvent,
    SavedTender,
    TenderCategory,
)
from app.database.models.notifications import NotificationType, NotificationEventStatus
from app.services.fcm_client import FCMClient

logger = get_logger("notifications")


class NotificationService:
    def __init__(self, db: Session):
        self.db = db
        self.fcm = FCMClient()

    # --------------------------------------------------------- matching core
    def _matching_user_ids(self, tender: Tender) -> List[int]:
        """Return ids of users whose preferences match this tender.

        A preference matches when (category is NULL or is one of the tender's
        normalized categories) AND (province is NULL or equals the tender's
        province), and notifications are enabled.
        """
        # All normalized category ids for this tender (not just the primary).
        # Query the link table directly (the ORM relationship may be stale when
        # rows were inserted without touching the collection).
        cat_ids = list(
            self.db.execute(
                select(TenderCategory.category_id).where(
                    TenderCategory.tender_id == tender.id
                )
            ).scalars()
        )
        if not cat_ids:
            cid = self._category_id(tender.category)
            cat_ids = [cid] if cid is not None else []
        prov_id = self._province_id(tender.province)

        stmt = (
            select(UserPreference.user_id)
            .where(UserPreference.notifications_enabled.is_(True))
            .where(
                (UserPreference.category_id.is_(None))
                | (UserPreference.category_id.in_(cat_ids) if cat_ids else False)
            )
            .where(
                (UserPreference.province_id.is_(None))
                | (UserPreference.province_id == prov_id)
            )
        )
        rows = self.db.execute(stmt).scalars().all()
        return sorted(set(rows))

    def _category_id(self, name: Optional[str]) -> Optional[int]:
        if not name:
            return None
        c = self.db.execute(select(Category).where(Category.name == name)).scalar_one_or_none()
        return c.id if c else None

    def _province_id(self, name: Optional[str]) -> Optional[int]:
        if not name:
            return None
        p = self.db.execute(select(Province).where(Province.name == name)).scalar_one_or_none()
        return p.id if p else None

    # --------------------------------------------------------- digest core
    # (user_id, notification type) -> [(event, tender), ...] pending dispatch
    def _dispatch_digests(self, digests: dict) -> None:
        """One push per (user, type) per batch when digesting is enabled.

        A single pending item keeps the classic per-tender message (with its
        deep link); multiple items collapse into a summary push."""
        from app.core.config import settings as _settings

        for (user_id, ntype), items in digests.items():
            if not items:
                continue
            if _settings.digest_notifications and len(items) > 1:
                self._send_digest(user_id, ntype, items)
            else:
                for event, tender in items:
                    self._dispatch(event, user_id, tender, ntype)

    def _send_digest(self, user_id: int, ntype, items) -> None:
        tokens = self._active_tokens(user_id)
        if not tokens:
            for event, _ in items:
                event.status = NotificationEventStatus.SKIPPED
                if not event.detail:
                    event.detail = "no active device tokens"
            return

        count = len(items)
        titles = [t.title for _, t in items]
        if ntype == NotificationType.NEW_TENDER:
            title = f"{count} new tenders match your alerts"
        elif ntype == NotificationType.TENDER_AMENDED:
            title = f"{count} saved tenders were amended"
        elif ntype == NotificationType.DEADLINE_REMINDER:
            title = f"{count} saved tenders close soon"
        else:
            title = f"{count} tender updates"

        shown = ", ".join(f"\u201c{t[:48]}\u201d" for t in titles[:3])
        if count > 3:
            shown += f" and {count - 3} more"
        body = f"Including: {shown}" if titles else ""

        ok = self.fcm.send_multicast(
            [t.device_token for t in tokens],
            title=title,
            body=body,
            data={"type": ntype.value, "count": str(count)},
        )
        for event, _ in items:
            if ok:
                event.status = NotificationEventStatus.SENT
                event.sent_at = utcnow()
            else:
                event.status = NotificationEventStatus.FAILED
                if not event.detail:
                    event.detail = "fcm digest dispatch failed or disabled"

    def _active_tokens(self, user_id: int):
        return self.db.execute(
            select(NotificationToken).where(
                NotificationToken.user_id == user_id,
                NotificationToken.active.is_(True),
            )
        ).scalars().all()

    # ------------------------------------------------------------ new tender
    def notify_new_tenders(self, tender_ids: List[int]) -> int:
        return self._notify(tender_ids, NotificationType.NEW_TENDER)

    def notify_amended_tenders(self, tender_ids: List[int]) -> int:
        return self._notify(tender_ids, NotificationType.TENDER_AMENDED)

    def notify_saved_search_matches(self, tender_ids: List[int]) -> int:
        """Alert users whose saved searches match newly ingested tenders.

        Reuses the NEW_TENDER event type, so the (user, tender, type) unique
        constraint guarantees one alert per tender even when preference
        matching already notified the same user.
        """
        from app.services.saved_search_service import SavedSearchService

        svc = SavedSearchService(self.db)
        sent = 0
        digests: dict = {}
        for tid in tender_ids:
            tender = self.db.get(Tender, tid)
            if not tender or tender.status in (TenderStatus.EXPIRED, TenderStatus.CANCELLED):
                continue
            per_user: dict = {}
            for search in svc.matching_searches(tender):
                per_user.setdefault(search.user_id, []).append(search.name)
            for uid, names in per_user.items():
                detail = f"saved search: {names[0]}"
                event = self._create_event(
                    uid, tender, NotificationType.NEW_TENDER, detail=detail
                )
                if event is not None:
                    digests.setdefault((uid, NotificationType.NEW_TENDER), []).append(
                        (event, tender)
                    )
                    sent += 1
        self._dispatch_digests(digests)
        self.db.commit()
        return sent

    def _notify(self, tender_ids: List[int], ntype: NotificationType) -> int:
        sent = 0
        digests: dict = {}
        for tid in tender_ids:
            tender = self.db.get(Tender, tid)
            if not tender or tender.status in (TenderStatus.EXPIRED, TenderStatus.CANCELLED):
                continue
            user_ids = self._matching_user_ids(tender)
            for uid in user_ids:
                event = self._create_event(uid, tender, ntype)
                if event is not None:
                    digests.setdefault((uid, ntype), []).append((event, tender))
                    sent += 1
        self._dispatch_digests(digests)
        self.db.commit()
        return sent

    def _create_event(
        self, user_id: int, tender: Tender, ntype: NotificationType, detail: Optional[str] = None
    ):
        """Create a NotificationEvent unless one already exists (dedup).

        Returns the new event (undispatched — the caller batches dispatch), or
        None when a duplicate/race suppressed it."""
        existing = self.db.execute(
            select(NotificationEvent).where(
                NotificationEvent.user_id == user_id,
                NotificationEvent.tender_id == tender.id,
                NotificationEvent.notification_type == ntype,
            )
        ).scalar_one_or_none()
        if existing is not None:
            return None

        event = NotificationEvent(
            user_id=user_id,
            tender_id=tender.id,
            notification_type=ntype,
            status=NotificationEventStatus.PENDING,
            detail=detail,
        )
        self.db.add(event)
        try:
            self.db.flush()
        except Exception:  # unique race -> already exists
            self.db.rollback()
            return None

        return event

    def _dispatch(self, event, user_id, tender, ntype):
        tokens = self._active_tokens(user_id)
        if not tokens:
            event.status = NotificationEventStatus.SKIPPED
            if not event.detail:
                event.detail = "no active device tokens"
            return

        title, body = self._compose(tender, ntype)
        data = {
            "type": ntype.value,
            "tender_id": str(tender.id),
            "category": tender.category or "",
            "province": tender.province or "",
        }
        ok = self.fcm.send_multicast(
            [t.device_token for t in tokens], title=title, body=body, data=data
        )
        if ok:
            event.status = NotificationEventStatus.SENT
            event.sent_at = utcnow()
        else:
            event.status = NotificationEventStatus.FAILED
            if not event.detail:
                event.detail = "fcm dispatch failed or disabled"

    @staticmethod
    def _compose(tender: Tender, ntype: NotificationType):
        if ntype == NotificationType.NEW_TENDER:
            return "New tender", tender.title
        if ntype == NotificationType.TENDER_AMENDED:
            return "Tender amended", tender.title
        if ntype == NotificationType.DEADLINE_REMINDER:
            when = tender.closing_date.isoformat() if tender.closing_date else "soon"
            return "Closing soon", f"{tender.title} closes {when}"
        return "Tender update", tender.title

    # ---------------------------------------------------- deadline reminders
    def send_deadline_reminders(self) -> int:
        """Send DEADLINE_REMINDER for SAVED tenders approaching their deadline.

        Only saved tenders with reminders enabled are considered, so users are
        never spammed. One reminder per (user, tender) — dedup via the event
        table constraint (a single DEADLINE_REMINDER per tender).
        """
        now = utcnow()
        sent = 0
        # Any offset window that is currently "due".
        max_offset = max(settings.reminder_offsets_hours) if settings.reminder_offsets_hours else 168
        horizon = now + timedelta(hours=max_offset)

        saved = self.db.execute(
            select(SavedTender).where(SavedTender.reminders_enabled.is_(True))
        ).scalars().all()

        digests: dict = {}
        for s in saved:
            tender = self.db.get(Tender, s.tender_id)
            if not tender or not tender.closing_at:
                continue
            if tender.status in (TenderStatus.CLOSED, TenderStatus.EXPIRED, TenderStatus.CANCELLED):
                continue
            closing_at = ensure_utc(tender.closing_at)
            if not (now < closing_at <= horizon):
                continue
            event = self._create_event(s.user_id, tender, NotificationType.DEADLINE_REMINDER)
            if event is not None:
                digests.setdefault((s.user_id, NotificationType.DEADLINE_REMINDER), []).append(
                    (event, tender)
                )
                sent += 1
        self._dispatch_digests(digests)
        self.db.commit()
        return sent
