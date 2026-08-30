"""Small management CLI for common operations.

Usage:
  python scripts/manage.py migrate        # alembic upgrade head
  python scripts/manage.py seed           # load development sample data
  python scripts/manage.py sync           # run one ingestion cycle now
  python scripts/manage.py cleanup        # run expiry + cleanup now
  python scripts/manage.py create-key NAME# create an API key
  python scripts/manage.py stats          # print quick DB counts
"""
from __future__ import annotations

import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _migrate():
    subprocess.check_call(["alembic", "upgrade", "head"])


def _seed():
    from scripts import seed
    seed.main()


def _sync():
    from app.workers import sync_worker
    run = sync_worker.run_once(trigger="manual")
    if run:
        print(f"Sync {run.status.value}: created={run.records_created} "
              f"updated={run.records_updated} amended={run.records_amended} "
              f"expired={run.records_expired} failed={run.records_failed}")
    else:
        print("Sync skipped (already running).")


def _cleanup():
    from app.workers import cleanup_worker
    print(cleanup_worker.run_once())


def _create_key():
    from scripts import create_api_key
    create_api_key.main()


def _stats():
    from app.database.database import session_scope
    from app.database.models import Tender, User, NotificationToken, SyncRun
    db = session_scope()
    try:
        print("tenders:", db.query(Tender).count())
        print("users:", db.query(User).count())
        print("devices:", db.query(NotificationToken).count())
        print("sync_runs:", db.query(SyncRun).count())
    finally:
        db.close()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    {
        "migrate": _migrate,
        "seed": _seed,
        "sync": _sync,
        "cleanup": _cleanup,
        "create-key": _create_key,
        "stats": _stats,
    }.get(cmd, lambda: print(__doc__))()


if __name__ == "__main__":
    main()
