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
from app.core.logging import get_logger, log_event
from app.core.timeutils import utcnow, ensure_utc
from app.database.models import (
    Tender,
    TenderStatus,
    Category,
    Province,
    User,
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

    # ------------------------------------------------------------ new tender
    def notify_new_tenders(self, tender_ids: List[int]) -> int:
        return self._notify(tender_ids, NotificationType.NEW_TENDER)

    def notify_amended_tenders(self, tender_ids: List[int]) -> int:
        return self._notify(tender_ids, NotificationType.TENDER_AMENDED)

    def _notify(self, tender_ids: List[int], ntype: NotificationType) -> int:
        sent = 0
        for tid in tender_ids:
            tender = self.db.get(Tender, tid)
            if not tender or tender.status in (TenderStatus.EXPIRED, TenderStatus.CANCELLED):
                continue
            user_ids = self._matching_user_ids(tender)
            for uid in user_ids:
                if self._create_event(uid, tender, ntype):
                    sent += 1
        self.db.commit()
        return sent

    def _create_event(self, user_id: int, tender: Tender, ntype: NotificationType) -> bool:
        """Create a NotificationEvent unless one already exists (dedup)."""
        existing = self.db.execute(
            select(NotificationEvent).where(
                NotificationEvent.user_id == user_id,
                NotificationEvent.tender_id == tender.id,
                NotificationEvent.notification_type == ntype,
            )
        ).scalar_one_or_none()
        if existing is not None:
            return False

        event = NotificationEvent(
            user_id=user_id,
            tender_id=tender.id,
            notification_type=ntype,
            status=NotificationEventStatus.PENDING,
        )
        self.db.add(event)
        try:
            self.db.flush()
        except Exception:  # unique race -> already exists
            self.db.rollback()
            return False

        self._dispatch(event, user_id, tender, ntype)
        return True

    def _dispatch(self, event, user_id, tender, ntype):
        tokens = self.db.execute(
            select(NotificationToken).where(
                NotificationToken.user_id == user_id,
                NotificationToken.active.is_(True),
            )
        ).scalars().all()
        if not tokens:
            event.status = NotificationEventStatus.SKIPPED
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

        for s in saved:
            tender = self.db.get(Tender, s.tender_id)
            if not tender or not tender.closing_at:
                continue
            if tender.status in (TenderStatus.CLOSED, TenderStatus.EXPIRED, TenderStatus.CANCELLED):
                continue
            closing_at = ensure_utc(tender.closing_at)
            if not (now < closing_at <= horizon):
                continue
            if self._create_event(s.user_id, tender, NotificationType.DEADLINE_REMINDER):
                sent += 1
        self.db.commit()
        return sent
