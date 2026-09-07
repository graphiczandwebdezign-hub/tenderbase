"""APScheduler setup.

Schedules the periodic sync and daily cleanup. Designed so it can later be
replaced by Celery/RQ workers without touching the services layer — the
scheduler only calls worker entry points.
"""
from __future__ import annotations

from typing import Optional

from datetime import datetime, timedelta, timezone
from typing import Optional

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.date import DateTrigger
from apscheduler.triggers.interval import IntervalTrigger
from sqlalchemy import select

from app.core.config import settings
from app.core.logging import get_logger, log_event
from app.core.timeutils import ensure_utc
from app.database.database import session_scope
from app.workers import sync_worker, cleanup_worker

logger = get_logger("scheduler")

_scheduler: Optional[BackgroundScheduler] = None


def minutes_since_last_success() -> Optional[float]:
    """Minutes since the last SUCCESSFUL sync, or None if there never was one.

    A fresh process on a sleeping host has a brand-new scheduler, so the
    interval job will not fire for another full interval even when the data is
    hours out of date. The boot check below uses this to decide whether to
    catch up immediately.
    """
    from app.database.models import SyncRun, SyncStatus

    db = session_scope()
    try:
        completed_at = db.execute(
            select(SyncRun.completed_at)
            .where(SyncRun.status == SyncStatus.SUCCESS)
            .order_by(SyncRun.completed_at.desc())
            .limit(1)
        ).scalars().first()
    finally:
        db.close()
    if completed_at is None:
        return None
    return (datetime.now(timezone.utc) - ensure_utc(completed_at)).total_seconds() / 60.0


def start_scheduler() -> Optional[BackgroundScheduler]:
    global _scheduler
    if not settings.sync_enabled:
        log_event(logger, 20, "scheduler_disabled")
        return None
    if _scheduler is not None:
        return _scheduler

    _scheduler = BackgroundScheduler(timezone="UTC")
    _scheduler.add_job(
        lambda: sync_worker.run_once(trigger="scheduled"),
        trigger=IntervalTrigger(minutes=settings.sync_interval_minutes),
        id="tender_sync",
        max_instances=1,
        coalesce=True,
        replace_existing=True,
    )
    _scheduler.add_job(
        cleanup_worker.run_once,
        trigger=IntervalTrigger(hours=settings.cleanup_interval_hours),
        id="tender_cleanup",
        max_instances=1,
        coalesce=True,
        replace_existing=True,
    )
    _scheduler.start()

    # Free/low-traffic hosts (Render's free plan spins services down when idle)
    # would otherwise serve stale data indefinitely: every wake resets the
    # interval timer, so the periodic job never reaches its first fire. Run one
    # sync at startup whenever the database is already stale.
    if settings.sync_on_boot:
        try:
            age = minutes_since_last_success()
        except Exception as exc:  # noqa: BLE001 - never block boot on this
            log_event(logger, 30, "sync_catchup_check_failed", error=str(exc))
            age = None
        stale = age is None or age >= settings.sync_interval_minutes
        if stale:
            _scheduler.add_job(
                lambda: sync_worker.run_once(trigger="startup"),
                trigger=DateTrigger(run_date=datetime.now(timezone.utc)),
                id="tender_sync_catchup",
                max_instances=1,
                coalesce=True,
                replace_existing=True,
            )
            log_event(logger, 20, "sync_catchup_scheduled",
                      minutes_since_last_success=None if age is None else round(age, 1))
        else:
            log_event(logger, 20, "sync_catchup_skipped",
                      minutes_since_last_success=round(age or 0, 1),
                      threshold_minutes=settings.sync_interval_minutes)

    log_event(logger, 20, "scheduler_started",
              sync_interval_minutes=settings.sync_interval_minutes,
              cleanup_interval_hours=settings.cleanup_interval_hours)
    return _scheduler


def shutdown_scheduler() -> None:
    global _scheduler
    if _scheduler is not None:
        _scheduler.shutdown(wait=False)
        _scheduler = None
        log_event(logger, 20, "scheduler_stopped")
