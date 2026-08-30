"""Synchronization worker.

Prevents overlapping sync jobs via a process-level lock. Each run creates its
own DB session so it is safe to call from the scheduler or the admin API.
"""
from __future__ import annotations

import threading
from typing import Optional

from app.core.logging import get_logger, log_event
from app.database.database import session_scope
from app.database.models import SyncRun
from app.services.ingestion_service import IngestionService

logger = get_logger("worker.sync")

_lock = threading.Lock()


def is_running() -> bool:
    # If the lock is currently held, a sync is in progress.
    acquired = _lock.acquire(blocking=False)
    if acquired:
        _lock.release()
        return False
    return True


def run_once(trigger: str = "scheduled") -> Optional[SyncRun]:
    """Run a single sync cycle. Skips if another run holds the lock."""
    if not _lock.acquire(blocking=False):
        log_event(logger, 30, "sync_skipped_overlap", trigger=trigger)
        return None
    db = session_scope()
    try:
        service = IngestionService(db)
        run = service.run_sync(trigger=trigger)
        return run
    except Exception as exc:  # noqa: BLE001
        log_event(logger, 40, "sync_worker_error", error=str(exc))
        db.rollback()
        return None
    finally:
        db.close()
        _lock.release()
