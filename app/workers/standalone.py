"""Standalone scheduler process.

Runs the same APScheduler jobs as the in-process scheduler, but as a dedicated
container. Use this when you scale the API to multiple replicas and want a
single scheduler owner (set SYNC_ENABLED=false on the API replicas and run one
`worker` container). Kept minimal — the migration path to Celery/RQ only
touches this file and workers/scheduler.py.
"""
from __future__ import annotations

import signal
import time

from app.core.logging import get_logger, log_event, setup_logging
from app.workers.scheduler import start_scheduler, shutdown_scheduler

logger = get_logger("worker.standalone")


def main() -> None:
    setup_logging()
    start_scheduler()
    log_event(logger, 20, "standalone_worker_started")

    stop = {"flag": False}

    def _handle(signum, frame):
        stop["flag"] = True

    signal.signal(signal.SIGTERM, _handle)
    signal.signal(signal.SIGINT, _handle)

    try:
        while not stop["flag"]:
            time.sleep(1)
    finally:
        shutdown_scheduler()
        log_event(logger, 20, "standalone_worker_stopped")


if __name__ == "__main__":
    main()
