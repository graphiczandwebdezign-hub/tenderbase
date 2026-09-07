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
