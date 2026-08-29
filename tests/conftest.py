"""Pytest fixtures. Tests run against an isolated temporary SQLite database and
never touch the live source (a MockSourceAdapter provides deterministic data).
"""
from __future__ import annotations

import os
import tempfile

import pytest

# Configure environment BEFORE importing app modules so settings pick it up.
_tmpdir = tempfile.mkdtemp()
os.environ.setdefault("APP_ENV", "test")
os.environ["DATABASE_URL"] = f"sqlite:///{_tmpdir}/test.db"
os.environ["API_KEY"] = "test-api-key"
os.environ["ADMIN_SECRET"] = "test-admin-secret"
os.environ["SYNC_ENABLED"] = "false"
os.environ["RATE_LIMIT_ENABLED"] = "false"
os.environ["INGESTION_ALLOW_SAMPLE_FALLBACK"] = "false"

from app.core.config import get_settings  # noqa: E402

get_settings.cache_clear()

from app.database.database import Base, engine, SessionLocal  # noqa: E402
from app.database import models  # noqa: F401,E402
from app.services import taxonomy_service  # noqa: E402
from app.core.security import ensure_bootstrap_key  # noqa: E402


@pytest.fixture(scope="function")
def db():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    session = SessionLocal()
    taxonomy_service.seed_taxonomy(session)
    ensure_bootstrap_key(session)
    try:
        yield session
    finally:
        session.close()


@pytest.fixture(scope="function")
def client(db):
    from fastapi.testclient import TestClient
    from app.main import app

    # Ensure schema/seed exist for the app's own sessions too.
    with TestClient(app) as c:
        c.headers.update({"X-API-Key": "test-api-key"})
        yield c


@pytest.fixture(scope="function")
def noauth_client(db):
    """TestClient WITHOUT default auth headers (for testing auth failures)."""
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as c:
        yield c


@pytest.fixture
def admin_headers():
    return {"X-Admin-Secret": "test-admin-secret"}


API = "/api/v1"
