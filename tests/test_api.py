"""API tests: auth, retrieval, pagination, filtering, search."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.database.database import SessionLocal
from app.services.ingestion_service import IngestionService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"


def _future(hours=72):
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _seed(releases):
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter(releases)).run_sync(trigger="manual")
    finally:
        db.close()


def test_health_is_public(client):
    r = client.get("/health", headers={})
    assert r.status_code == 200
    assert r.json()["status"] == "healthy"

    r2 = client.get(f"{API}/health", headers={})
    assert r2.status_code == 200


def test_requires_api_key(noauth_client):
    r = noauth_client.get(f"{API}/tenders")
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "MISSING_API_KEY"


def test_invalid_api_key(noauth_client):
    r = noauth_client.get(f"{API}/tenders", headers={"X-API-Key": "nope"})
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "INVALID_API_KEY"


def test_list_and_pagination(client):
    _seed([make_release(f"P{i}", closing_iso=_future()) for i in range(10)])
    r = client.get(f"{API}/tenders?limit=4&page=1")
    assert r.status_code == 200
    body = r.json()
    assert len(body["data"]) == 4
    assert body["pagination"]["total"] == 10
    assert body["pagination"]["total_pages"] == 3


def test_category_filter(client):
    _seed([
        make_release("CAT1", title="Construction of clinic", category="works", closing_iso=_future()),
        make_release("CAT2", title="Supply of ICT laptops", description="servers", category="goods", closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders?category=information-technology")
    assert r.status_code == 200
    data = r.json()["data"]
    assert all(t["category"] == "Information Technology" for t in data)
    assert len(data) >= 1


def test_province_filter(client):
    _seed([
        make_release("PR1", province="Gauteng", org="City of Johannesburg", closing_iso=_future()),
        make_release("PR2", province="Western Cape", org="City of Cape Town", closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders?province=gauteng")
    data = r.json()["data"]
    assert all(t["province"] == "Gauteng" for t in data)


def test_search(client):
    _seed([
        make_release("S1", title="Software development services", closing_iso=_future()),
        make_release("S2", title="Road construction", closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders/search?q=software")
    assert r.json()["pagination"]["total"] == 1


def test_closing_soon_and_detail(client):
    _seed([
        make_release("CS1", closing_iso=_future(24)),
        make_release("CS2", closing_iso=_future(24 * 20)),
    ])
    r = client.get(f"{API}/tenders/closing-soon?hours=48")
    data = r.json()["data"]
    assert len(data) == 1
    tid = data[0]["id"]
    detail = client.get(f"{API}/tenders/{tid}")
    assert detail.status_code == 200
    assert detail.json()["deadline_state"] in ("CLOSING_SOON", "ACTIVE")
    assert "amendments" in detail.json()


def test_tender_not_found(client):
    r = client.get(f"{API}/tenders/999999")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "TENDER_NOT_FOUND"


def test_default_feed_excludes_expired(client):
    _seed([
        make_release("EXP1", closing_iso=(datetime.now(timezone.utc) - timedelta(days=20)).isoformat()),
        make_release("ACT1", closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders")
    ids = [t["tender_number"] for t in r.json()["data"]]
    assert "ACT1" in ids
    assert "EXP1" not in ids


def test_categories_and_provinces_endpoints(client):
    assert len(client.get(f"{API}/categories").json()) == 17
    assert len(client.get(f"{API}/provinces").json()) == 10
