"""Sprint 6 tests: server-side workspace backup on saved tenders."""
from __future__ import annotations

import json

from app.database.database import SessionLocal
from app.services.ingestion_service import IngestionService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"


def _future(hours=72):
    from datetime import datetime, timedelta, timezone
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _seed_one(client, ext="WS1"):
    """Ingest one tender and return its id."""
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter([make_release(ext, closing_iso=_future())])).run_sync(
            trigger="manual"
        )
        from app.database.models import Tender

        return db.query(Tender).filter(Tender.tender_number == ext).first().id
    finally:
        db.close()


def _save(client, client_id, tender_id):
    return client.post(
        f"{API}/notifications/saved",
        json={"client_id": client_id, "tender_id": tender_id},
    )


def _put_workspace(client, client_id, tender_id, **body):
    payload = {"client_id": client_id}
    payload.update(body)
    return client.put(
        f"{API}/notifications/saved/{tender_id}/workspace", json=payload
    )


# ---------------------------------------------------------------- happy paths

def test_workspace_roundtrip(client):
    tid = _seed_one(client)
    assert _save(client, "dev-1", tid).status_code == 200

    r = _put_workspace(
        client, "dev-1", tid,
        note="Site visit 2 Sep",
        checklist=[
            {"label": "Register as a supplier / CIDB", "done": True},
            {"label": "Submit the bid", "done": False},
        ],
    )
    assert r.status_code == 200
    body = r.json()
    assert body["note"] == "Site visit 2 Sep"
    assert body["checklist"][0]["done"] is True
    assert body["reminders_enabled"] is True  # untouched by workspace sync

    listing = client.get(f"{API}/notifications/saved?client_id=dev-1").json()
    assert listing["client_id"] == "dev-1"
    assert len(listing["saved"]) == 1
    assert listing["saved"][0]["tender_id"] == tid
    assert listing["saved"][0]["note"] == "Site visit 2 Sep"


def test_absent_fields_are_unchanged_and_null_clears(client):
    tid = _seed_one(client, "WS2")
    _save(client, "dev-1", tid)
    _put_workspace(client, "dev-1", tid, note="keep me",
                   checklist=[{"label": "one", "done": False}])

    # Absent note -> unchanged; checklist replaced.
    r = _put_workspace(client, "dev-1", tid, checklist=[{"label": "two", "done": True}])
    assert r.json()["note"] == "keep me"
    assert [c["label"] for c in r.json()["checklist"]] == ["two"]

    # Explicit null clears both.
    r2 = _put_workspace(client, "dev-1", tid, note=None, checklist=[])
    assert r2.json()["note"] is None
    assert r2.json()["checklist"] == []


def test_workspace_requires_saved_tender(client):
    tid = _seed_one(client, "WS3")
    r = _put_workspace(client, "dev-1", tid, note="x")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "SAVED_TENDER_NOT_FOUND"


def test_workspace_unknown_tender_404(client):
    r = _put_workspace(client, "dev-1", 999999, note="x")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "TENDER_NOT_FOUND"


def test_workspace_is_per_client(client):
    tid = _seed_one(client, "WS4")
    _save(client, "dev-1", tid)
    _save(client, "dev-2", tid)
    _put_workspace(client, "dev-1", tid, note="private to dev-1")

    assert client.get(f"{API}/notifications/saved?client_id=dev-2").json()["saved"][0]["note"] is None
    # dev-2 cannot write dev-1's row either.
    r = _put_workspace(client, "dev-2", tid, note="hijack")
    assert r.status_code == 200  # writes its OWN row (per-user table)
    assert client.get(f"{API}/notifications/saved?client_id=dev-1").json()["saved"][0]["note"] == "private to dev-1"
    assert client.get(f"{API}/notifications/saved?client_id=dev-2").json()["saved"][0]["note"] == "hijack"


def test_workspace_validation(client):
    tid = _seed_one(client, "WS5")
    _save(client, "dev-1", tid)
    # empty label
    assert _put_workspace(client, "dev-1", tid, checklist=[{"label": ""}]).status_code == 422
    # too many items
    many = [{"label": f"item {i}"} for i in range(101)]
    assert _put_workspace(client, "dev-1", tid, checklist=many).status_code == 422
    # oversized note
    assert _put_workspace(client, "dev-1", tid, note="x" * 8001).status_code == 422


def test_saved_listing_requires_api_key(noauth_client):
    assert noauth_client.get(f"{API}/notifications/saved?client_id=x").status_code == 401


def test_checklist_json_storage_shape(client, db):
    """The stored JSON is a plain list of {label, done} — the client contract."""
    tid = _seed_one(client, "WS6")
    _save(client, "dev-1", tid)
    _put_workspace(client, "dev-1", tid, checklist=[{"label": "a", "done": True}])
    from app.database.models import SavedTender

    row = db.query(SavedTender).filter(SavedTender.tender_id == tid).one()
    parsed = json.loads(row.checklist_json)
    assert parsed == [{"label": "a", "done": True}]
