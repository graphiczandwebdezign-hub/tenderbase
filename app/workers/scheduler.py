"""APScheduler setup.

Schedules the periodic sync and daily cleanup. Designed so it can later be
replaced by Celery/RQ workers without touching the services layer — the
scheduler only calls worker entry points.
"""
from __future__ import annotations

from typing import Optional

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.interval import IntervalTrigger

from app.core.config import settings
from app.core.logging import get_logger, log_event
from app.workers import sync_worker, cleanup_worker

logger = get_logger("scheduler")

_scheduler: Optional[BackgroundScheduler] = None


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
