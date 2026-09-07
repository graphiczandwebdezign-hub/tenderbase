"""Admin API tests: auth gate, dashboard, sync runs, tender correction/removal,
API key management."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.database.database import SessionLocal
from app.services.ingestion_service import IngestionService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"


def _future(hours=72):
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _seed(n=3):
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter(
            [make_release(f"AD{i}", closing_iso=_future()) for i in range(n)]
        )).run_sync(trigger="manual")
    finally:
        db.close()


def test_bundled_credentials_are_defaults_only(monkeypatch):
    """Pin the TEMPORARY bundled credentials in app/core/config.py.

    Two guarantees: a fresh process with no env config authenticates with the
    bundled key/secret, and any API_KEY / ADMIN_SECRET in the environment still
    overrides them (so the Render deployment keeps its generated values).
    Delete this test together with the BUNDLED_* constants when they are
    rotated.
    """
    from app.core.config import Settings, BUNDLED_API_KEY, BUNDLED_ADMIN_SECRET

    for var in ("API_KEY", "ADMIN_SECRET"):
        monkeypatch.delenv(var, raising=False)
    fresh = Settings(_env_file=None)
    assert fresh.api_key == BUNDLED_API_KEY
    assert fresh.admin_secret == BUNDLED_ADMIN_SECRET

    monkeypatch.setenv("API_KEY", "env-key")
    monkeypatch.setenv("ADMIN_SECRET", "env-secret")
    overridden = Settings(_env_file=None)
    assert overridden.api_key == "env-key"
    assert overridden.admin_secret == "env-secret"


def test_bundled_credentials_are_accepted_alongside_env_ones(client):
    """The Render situation: the host sets its own API_KEY / ADMIN_SECRET, so
    the bundled defaults are NOT the configured values — yet with
    ALLOW_BUNDLED_CREDENTIALS=true (default) both the bundled API key and the
    bundled admin secret must still authenticate."""
    from app.core.config import settings, BUNDLED_API_KEY, BUNDLED_ADMIN_SECRET

    # conftest configures different values, proving the env wins as defaults.
    assert settings.api_key == "test-api-key" != BUNDLED_API_KEY
    assert settings.admin_secret == "test-admin-secret" != BUNDLED_ADMIN_SECRET

    r = client.get(f"{API}/tenders", headers={"X-API-Key": BUNDLED_API_KEY})
    assert r.status_code == 200

    r = client.get(f"{API}/admin/dashboard", headers={"X-Admin-Secret": BUNDLED_ADMIN_SECRET})
    assert r.status_code == 200

    # And the host's own credentials keep working.
    assert client.get(
        f"{API}/admin/dashboard", headers={"X-Admin-Secret": "test-admin-secret"}
    ).status_code == 200


def test_bundled_credentials_can_be_switched_off(monkeypatch):
    """ALLOW_BUNDLED_CREDENTIALS=false must reject the bundled pair while the
    environment credentials keep working."""
    from fastapi.testclient import TestClient

    from app.core.config import settings, BUNDLED_API_KEY, BUNDLED_ADMIN_SECRET
    from app.main import app

    monkeypatch.setattr(settings, "allow_bundled_credentials", False)
    with TestClient(app) as c:
        c.headers.update({"X-API-Key": "test-api-key"})
        assert c.get(f"{API}/tenders").status_code == 200
        assert c.get(f"{API}/tenders", headers={"X-API-Key": BUNDLED_API_KEY}).status_code == 401
        assert c.get(
            f"{API}/admin/dashboard", headers={"X-Admin-Secret": "test-admin-secret"}
        ).status_code == 200
        assert c.get(
            f"{API}/admin/dashboard", headers={"X-Admin-Secret": BUNDLED_ADMIN_SECRET}
        ).status_code == 401


def test_ensure_bootstrap_key_does_not_resurrect_revoked_keys(db, monkeypatch):
    """A revoked bundled key stays revoked across restarts."""
    _ = db  # creates/seeds the schema
    from sqlalchemy import select

    from app.core.config import settings, BUNDLED_API_KEY
    from app.core.security import ensure_bootstrap_key, hash_key
    from app.database.models import ApiKey

    monkeypatch.setattr(settings, "api_key", "env-key")
    db = SessionLocal()
    try:
        ensure_bootstrap_key(db)
        row = db.execute(
            select(ApiKey).where(ApiKey.key_hash == hash_key(BUNDLED_API_KEY))
        ).scalar_one()
        assert row.name == "bootstrap-bundled" and row.active is True
        row.active = False
        db.commit()
        ensure_bootstrap_key(db)  # simulates a restart
        db.expire_all()
        assert db.execute(
            select(ApiKey).where(ApiKey.key_hash == hash_key(BUNDLED_API_KEY))
        ).scalar_one().active is False
    finally:
        db.close()


def test_admin_requires_secret(client):
    r = client.get(f"{API}/admin/dashboard")
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "INVALID_ADMIN_SECRET"


def test_admin_dashboard(client, admin_headers):
    _seed(3)
    r = client.get(f"{API}/admin/dashboard", headers=admin_headers)
    assert r.status_code == 200
    assert r.json()["total_tenders"] == 3


def test_admin_sync_runs(client, admin_headers):
    _seed(2)
    r = client.get(f"{API}/admin/sync-runs", headers=admin_headers)
    assert r.status_code == 200
    assert len(r.json()) >= 1


def test_admin_patch_and_delete_tender(client, admin_headers):
    _seed(1)
    lst = client.get(f"{API}/admin/tenders", headers=admin_headers).json()["data"]
    tid = lst[0]["id"]
    patched = client.patch(f"{API}/admin/tenders/{tid}", headers=admin_headers,
                           json={"status": "CANCELLED"})
    assert patched.status_code == 200
    assert patched.json()["status"] == "CANCELLED"

    deleted = client.delete(f"{API}/admin/tenders/{tid}", headers=admin_headers)
    assert deleted.status_code == 200
    gone = client.get(f"{API}/admin/tenders", headers=admin_headers).json()["data"]
    assert all(t["id"] != tid for t in gone)


def test_api_key_lifecycle(client, admin_headers):
    created = client.post(f"{API}/admin/api-keys", headers=admin_headers,
                          json={"name": "android"})
    assert created.status_code == 200
    raw = created.json()["api_key"]
    assert raw.startswith("sk_")

    # The new key should authenticate.
    r = client.get(f"{API}/tenders", headers={"X-API-Key": raw})
    assert r.status_code == 200

    key_id = created.json()["id"]
    revoked = client.delete(f"{API}/admin/api-keys/{key_id}", headers=admin_headers)
    assert revoked.status_code == 200
    # Revoked key no longer authenticates.
    r2 = client.get(f"{API}/tenders", headers={"X-API-Key": raw})
    assert r2.status_code == 401
