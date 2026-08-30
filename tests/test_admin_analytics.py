"""Sprint 7 tests: admin discovery analytics + data quality."""
from __future__ import annotations

from app.database.database import SessionLocal
from app.database.models import SearchEvent
from app.services.ingestion_service import IngestionService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"
ADMIN = {"X-Admin-Secret": "test-admin-secret"}


def _ingest(client, ext, **kwargs):
    db = SessionLocal()
    try:
        IngestionService(
            db, MockSourceAdapter([make_release(ext, **kwargs)])
        ).run_sync(trigger="manual")
    finally:
        db.close()


def _search(client, **params):
    return client.get(f"{API}/tenders", params=params)


# ------------------------------------------------------------ search telemetry

def test_searches_are_recorded_anonymously(client):
    _ingest(client, "AQ1")
    _ingest(client, "AQ2")

    assert _search(client, search="construction").status_code == 200
    assert _search(client, province="kzn", closing_within="7d").status_code == 200

    db = SessionLocal()
    try:
        events = db.query(SearchEvent).order_by(SearchEvent.id).all()
        assert len(events) == 2
        assert events[0].endpoint == "list"
        assert events[0].query_text == "construction"
        assert events[1].query_text is None
        assert events[1].filters_json and "province" in events[1].filters_json
        # anonymity: nothing user-identifying is stored
        for ev in events:
            assert "client" not in (ev.filters_json or "")
    finally:
        db.close()


def test_zero_result_search_is_recorded(client):
    _search(client, search="nonexistent-xyz")
    db = SessionLocal()
    try:
        ev = db.query(SearchEvent).one()
        assert ev.results_count == 0
    finally:
        db.close()


def test_invalid_search_is_not_recorded(client):
    before = SessionLocal().query(SearchEvent).count()
    r = client.get(f"{API}/tenders?status=BOGUS")
    assert r.status_code == 400
    after = SessionLocal().query(SearchEvent).count()
    assert before == after


# ------------------------------------------------------------- admin analytics

def test_search_analytics_aggregation(client, db):
    _ingest(client, "AQ3", title="Bridge Construction")
    _search(client, search="Bridge Construction")   # caps normalised
    _search(client, search="bridge construction")
    _search(client, search="ghost-term")            # zero results
    _search(client, province="KwaZulu-Natal")

    r = client.get(f"{ADMIN and '/api/v1'}/admin/analytics/searches?days=7&top=5",
                   headers=ADMIN)
    assert r.status_code == 200
    body = r.json()
    assert body["days"] == 7
    assert body["total_searches"] == 4
    assert body["zero_result_searches"] == 1
    assert body["top_terms"][0]["count"] == 2
    assert body["facet_usage"]["province"] == 1
    top = body["top_terms"]
    assert top[0]["term"] == "bridge construction" and top[0]["count"] == 2
    assert [t["term"] for t in body["top_zero_result_terms"]] == ["ghost-term"]
    assert len(body["daily"]) == 7
    assert sum(d["count"] for d in body["daily"]) == 4


def test_search_analytics_requires_admin(client):
    assert client.get(f"{API}/admin/analytics/searches").status_code == 401


def test_saved_search_analytics(client):
    _ingest(client, "AQ4")
    for name, payload in [
        ("Roads", {"search": "road construction", "province": "kzn"}),
        ("Fencing", {"search": "fencing"}),
        ("Build", {"search": "school building"}),
    ]:
        r = client.post(f"{API}/saved-searches",
                        json={"client_id": "dev-1", "name": name, "filters": payload})
        assert r.status_code == 201
    # disable alerts on one
    listed = client.get(f"{API}/saved-searches?client_id=dev-1").json()
    sid = (listed["searches"] if isinstance(listed, dict) else listed)[0]["id"]
    assert client.patch(
        f"{API}/saved-searches/{sid}/alerts",
        json={"alerts_enabled": False, "client_id": "dev-1"}
    ).status_code == 200

    body = client.get(f"{API}/admin/analytics/saved-searches", headers=ADMIN).json()
    assert body["total"] == 3
    assert body["alerts_enabled"] == 2
    assert body["alerts_disabled"] == 1
    assert body["distinct_users"] == 1
    assert body["top_terms"][0]["term"] in {"road construction", "fencing", "school building"}
    assert body["facet_usage"]["province"] == 1


def test_dashboard_includes_discovery_counters(client):
    _ingest(client, "AQ5")
    _search(client, search="whatever")
    body = client.get(f"{API}/admin/dashboard", headers=ADMIN).json()
    assert body["saved_searches"] >= 0
    assert body["searches_last_7d"] >= 1


# ---------------------------------------------------------------- data quality

def test_data_quality_per_source(client):
    # Good source: complete records.
    _ingest(client, "G1")
    _ingest(client, "G2")
    # Poor records: province must dodge the org->city heuristic, description
    # blank, closing date absent (endDate None), no documents.
    for i in range(3):
        db = SessionLocal()
        try:
            IngestionService(db, MockSourceAdapter([
                make_release(
                    f"BAD{i}",
                    org="Private Vendor XYZ",
                    province=None,
                    description="",
                    closing_iso=None,
                    documents=[],
                )
            ])).run_sync(trigger="manual")
        finally:
            db.close()

    body = client.get(f"{API}/admin/data-quality", headers=ADMIN).json()
    overall = body["overall"]
    assert overall["total"] >= 5
    assert overall["missing_province"] == 3

    assert overall["missing_closing_date"] >= 3
    assert overall["missing_description"] >= 3
    assert overall["without_documents"] >= 3
    sources = {s["source"]: s for s in body["sources"]}
    assert len(sources) >= 1
    # worst-first ordering
    comps = [s["completeness"] for s in body["sources"]]
    assert comps == sorted(comps)
    # every source reports all fields
    first = body["sources"][0]
    for key in ("total", "missing_closing_date", "missing_province", "missing_category",
                "missing_organisation", "missing_description", "without_documents",
                "open_past_deadline", "completeness"):
        assert key in first


def test_data_quality_flags_open_past_deadline(client):
    # The pipeline auto-closes past deadlines, so a stale-open row is inserted
    # directly — the state a broken source would leave behind between syncs.
    from datetime import date, timedelta as _td

    from app.database.models import Tender, TenderStatus

    db = SessionLocal()
    try:
        db.add(Tender(
            source="Broken Feed",
            tender_number="STALE-1",
            external_id="STALE-1",
            title="Stale open tender",
            status=TenderStatus.ACTIVE,
            closing_date=date.today() - _td(days=5),
        ))
        db.commit()
    finally:
        db.close()

    body = client.get(f"{API}/admin/data-quality", headers=ADMIN).json()
    assert body["overall"]["open_past_deadline"] == 1


def test_data_quality_empty_db(client):
    body = client.get(f"{API}/admin/data-quality", headers=ADMIN).json()
    assert body["overall"]["total"] == 0
    assert body["sources"] == []
