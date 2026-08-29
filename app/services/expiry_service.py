"""Expiry and cleanup service.

- refresh: CLOSE tenders past their deadline, EXPIRE closed tenders past the
  retention window (delegated to IngestionService.refresh_statuses).
- cleanup: DELETE expired tenders once the retention period has fully elapsed.

Safety rules (enforced here):
  * Never delete ACTIVE/AMENDED tenders.
  * Never delete a tender whose closing time has not passed.
  * Never delete before the retention period elapses.
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.logging import get_logger, log_event
from app.core.timeutils import utcnow
from app.database.models import Tender, TenderStatus
from app.services.ingestion_service import IngestionService

logger = get_logger("expiry")


class ExpiryService:
    def __init__(self, db: Session):
        self.db = db

    def refresh(self) -> int:
        """Recompute deadline-driven statuses. Returns count newly expired."""
        ing = IngestionService(self.db)
        expired = ing.refresh_statuses()
        self.db.commit()
        log_event(logger, 20, "expiry_refresh", newly_expired=expired)
        return expired

    def cleanup(self) -> int:
        """Delete EXPIRED tenders whose retention window has fully elapsed.

        Returns the number of tenders deleted.
        """
        now = utcnow()
        candidates = self.db.execute(
            select(Tender).where(
                Tender.status == TenderStatus.EXPIRED,
                Tender.expires_at.is_not(None),
                Tender.expires_at <= now,
            )
        ).scalars().all()

        deleted = 0
        for t in candidates:
            # Defensive re-checks: never delete active/undue tenders.
            if t.status in (TenderStatus.ACTIVE, TenderStatus.AMENDED):
                continue
            if t.closing_at and t.closing_at > now:
                continue
            self.db.delete(t)  # cascades to documents/amendments/categories
            deleted += 1
        self.db.commit()
        log_event(logger, 20, "cleanup_complete", deleted=deleted)
        return deleted
