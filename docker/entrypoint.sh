#!/usr/bin/env bash
# Container entrypoint. Waits for PostgreSQL, applies migrations, then launches
# the requested role: `api` (default) runs the FastAPI server (which also hosts
# the in-process scheduler). Roles are separated so a dedicated worker container
# can later be split out without code changes.
set -euo pipefail

ROLE="${1:-api}"

echo "[entrypoint] role=${ROLE} env=${APP_ENV:-unknown}"

# --- Wait for the database (PostgreSQL) to accept connections ---
python - <<'PY'
import os, time, sys
url = os.environ.get("DATABASE_URL", "")
if url.startswith("postgres"):
    import sqlalchemy
    from sqlalchemy import create_engine, text
    engine = create_engine(url)
    for attempt in range(60):
        try:
            with engine.connect() as c:
                c.execute(text("SELECT 1"))
            print("[entrypoint] database is ready")
            break
        except Exception as exc:
            print(f"[entrypoint] waiting for db ({attempt+1}/60): {exc}")
            time.sleep(2)
    else:
        print("[entrypoint] database not reachable, exiting")
        sys.exit(1)
else:
    print("[entrypoint] non-postgres DATABASE_URL, skipping wait")
PY

# --- Apply migrations (idempotent) ---
echo "[entrypoint] running alembic migrations"
alembic upgrade head

case "${ROLE}" in
  api)
    # Respect a platform-provided PORT (Render/Railway/Heroku); default 8000.
    echo "[entrypoint] starting API server on port ${PORT:-8000}"
    exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8000}" --workers "${WEB_CONCURRENCY:-2}"
    ;;
  worker)
    # Reserved for a future dedicated scheduler/worker process (Celery/RQ).
    echo "[entrypoint] starting standalone scheduler worker"
    exec python -m app.workers.standalone
    ;;
  seed)
    echo "[entrypoint] seeding development data"
    exec python scripts/seed.py
    ;;
  *)
    echo "[entrypoint] unknown role: ${ROLE}"
    exit 1
    ;;
esac
