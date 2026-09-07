"""Startup catch-up behaviour.

Free/low-traffic hosts (Render free tier) spin the service down when idle. The
APScheduler interval timer is rebuilt on every cold start, so a 15-minute
cadence never actually fires unless the process stays up that long — the exact
failure this guards against. The scheduler must therefore sync at boot whenever
the database is already stale, and must NOT do so when it is fresh (otherwise a
cold start would hit the source on every wake).
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone

from app.database.database import SessionLocal
from app.database.models import SyncRun, SyncStatus
from app.workers import scheduler as sched


def _record_run(db, *, status: SyncStatus, age_minutes: float) -> None:
    db.add(
        SyncRun(
            source="eTenders",
            trigger="scheduled",
            status=status,
            started_at=datetime.now(timezone.utc) - timedelta(minutes=age_minutes + 1),
            completed_at=datetime.now(timezone.utc) - timedelta(minutes=age_minutes),
        )
    )
    db.commit()


def test_minutes_since_last_success(db):
    assert sched.minutes_since_last_success() is None

    _record_run(db, status=SyncStatus.FAILED, age_minutes=2)
    # A failed run does not count as fresh data.
    assert sched.minutes_since_last_success() is None

    _record_run(db, status=SyncStatus.SUCCESS, age_minutes=40)
    age = sched.minutes_since_last_success()
    assert age is not None and 39 <= age <= 41


def _wait_for(predicate, timeout: float = 3.0, interval: float = 0.05) -> bool:
    """APScheduler runs the catch-up job on a worker thread, so poll."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def test_catchup_sync_runs_at_boot_when_stale(db, monkeypatch):
    monkeypatch.setattr(sched.settings, "sync_enabled", True)
    monkeypatch.setattr(sched.settings, "sync_on_boot", True)
    monkeypatch.setattr(sched.settings, "sync_interval_minutes", 15)
    triggers = []
    monkeypatch.setattr(
        sched.sync_worker, "run_once",
        lambda trigger="scheduled": triggers.append(trigger),
    )

    # No successful sync at all -> stale -> must sync immediately on boot.
    scheduler = sched.start_scheduler()
    try:
        assert _wait_for(lambda: "startup" in triggers), "boot catch-up did not run"
    finally:
        sched.shutdown_scheduler()


def test_catchup_sync_skipped_when_fresh(db, monkeypatch):
    monkeypatch.setattr(sched.settings, "sync_enabled", True)
    monkeypatch.setattr(sched.settings, "sync_on_boot", True)
    monkeypatch.setattr(sched.settings, "sync_interval_minutes", 15)
    triggers = []
    monkeypatch.setattr(
        sched.sync_worker, "run_once",
        lambda trigger="scheduled": triggers.append(trigger),
    )
    _record_run(db, status=SyncStatus.SUCCESS, age_minutes=1)

    scheduler = sched.start_scheduler()
    try:
        # Give a wrongly-scheduled job time to fire before asserting.
        assert not _wait_for(lambda: bool(triggers), timeout=0.5)
        # ...while the normal periodic job is still registered.
        assert scheduler.get_job("tender_sync") is not None
    finally:
        sched.shutdown_scheduler()


def test_catchup_can_be_disabled(db, monkeypatch):
    monkeypatch.setattr(sched.settings, "sync_enabled", True)
    monkeypatch.setattr(sched.settings, "sync_on_boot", False)
    triggers = []
    monkeypatch.setattr(
        sched.sync_worker, "run_once",
        lambda trigger="scheduled": triggers.append(trigger),
    )
    scheduler = sched.start_scheduler()
    try:
        assert not _wait_for(lambda: bool(triggers), timeout=0.5)
        assert scheduler.get_job("tender_sync") is not None
    finally:
        sched.shutdown_scheduler()
