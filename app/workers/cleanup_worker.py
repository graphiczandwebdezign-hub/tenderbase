"""Cleanup / expiry worker."""
from __future__ import annotations

from app.core.logging import get_logger, log_event
from app.database.database import session_scope
from app.services.expiry_service import ExpiryService
from app.services.notification_service import NotificationService

logger = get_logger("worker.cleanup")


def run_once() -> dict:
    db = session_scope()
    try:
        expiry = ExpiryService(db)
        newly_expired = expiry.refresh()
        deleted = expiry.cleanup()
        reminders = NotificationService(db).send_deadline_reminders()
        log_event(logger, 20, "cleanup_run", newly_expired=newly_expired,
                  deleted=deleted, reminders=reminders)
        return {"newly_expired": newly_expired, "deleted": deleted, "reminders": reminders}
    except Exception as exc:  # noqa: BLE001
        log_event(logger, 40, "cleanup_worker_error", error=str(exc))
        db.rollback()
        return {"error": str(exc)}
    finally:
        db.close()
