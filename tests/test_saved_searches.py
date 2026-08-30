"""Sprint 2 tests: saved searches — CRUD, matching semantics, alert dispatch.

Matching must mirror GET /api/v1/tenders semantics exactly; several tests
cross-check a saved search's params against the live list endpoint so the
"alerted set" and the "searched set" can never drift apart.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.database.database import SessionLocal
from app.database.models import NotificationEvent, Tender
from app.services.ingestion_service import IngestionService
from app.services.saved_search_service import SavedSearchService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"


def _future(hours=72):
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _past(days=10):
    return (datetime.now(timezone.utc) - timedelta(days=days)).replace(microsecond=0).isoformat()


def _seed(releases):
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter(releases)).run_sync(trigger="manual")
    finally:
        db.close()


def _create(client, client_id="dev-1", name="My search", **filters):
    return client.post(
        f"{API}/saved-searches",
        json={"client_id": client_id, "name": name, "filters": filters},
    )


# ---------------------------------------------------------------------- CRUD

def test_saved_search_crud_roundtrip(client):
    r = _create(client, name="KZN construction", search="construction",
                province="KwaZulu-Natal", closing_within="7d")
    assert r.status_code == 201
    body = r.json()
    assert body["name"] == "KZN construction"
    assert body["alerts_enabled"] is True
    # Canonical province name stored
    assert body["filters"]["province"] == "KwaZulu-Natal"
    sid = body["id"]

    listing = client.get(f"{API}/saved-searches?client_id=dev-1").json()
    assert [s["id"] for s in listing["searches"]] == [sid]

    # Toggle alerts off and on
    off = client.patch(f"{API}/saved-searches/{sid}/alerts",
                       json={"client_id": "dev-1", "alerts_enabled": False})
    assert off.json()["alerts_enabled"] is False
    on = client.patch(f"{API}/saved-searches/{sid}/alerts",
                      json={"client_id": "dev-1", "alerts_enabled": True})
    assert on.json()["alerts_enabled"] is True

    assert client.delete(f"{API}/saved-searches/{sid}?client_id=dev-1").json()["status"] == "deleted"
    assert client.get(f"{API}/saved-searches?client_id=dev-1").json()["searches"] == []


def test_duplicate_name_rejected(client):
    assert _create(client, name="Dup").status_code == 201
    r = _create(client, name="Dup")
    assert r.status_code == 409
    assert r.json()["error"]["code"] == "DUPLICATE_SEARCH_NAME"
    # Same name for a different user is fine.
    assert _create(client, client_id="dev-2", name="Dup").status_code == 201


def test_invalid_filters_rejected(client):
    # Semantically invalid status -> 400 from the service validator.
    assert _create(client, name="S1", status="banana").status_code == 400
    # Structurally invalid window -> 422 from schema validation.
    assert _create(client, name="S2", closing_within="next-week").status_code == 422
    # 422 for structurally invalid payloads
    r = client.post(f"{API}/saved-searches",
                    json={"client_id": "dev-1", "name": "", "filters": {}})
    assert r.status_code == 422


def test_client_isolation_and_404s(client):
    _create(client, client_id="dev-1", name="Mine")
    other = client.get(f"{API}/saved-searches?client_id=dev-2").json()["searches"]
    assert other == []
    # dev-2 cannot touch dev-1's search
    sid = client.get(f"{API}/saved-searches?client_id=dev-1").json()["searches"][0]["id"]
    assert client.delete(f"{API}/saved-searches/{sid}?client_id=dev-2").status_code == 404
    assert client.patch(f"{API}/saved-searches/{sid}/alerts",
                        json={"client_id": "dev-2", "alerts_enabled": False}).status_code == 404
    assert client.delete(f"{API}/saved-searches/999999?client_id=dev-1").status_code == 404


def test_saved_searches_require_api_key(noauth_client):
    assert noauth_client.get(f"{API}/saved-searches?client_id=x").status_code == 401


# ------------------------------------------------------------------- matching

def _first_tender(db_session, **kw) -> Tender:
    stmt = db_session.query(Tender).filter_by(**kw).first() if kw else db_session.query(Tender).first()
    return stmt


def test_matcher_mirrors_list_endpoint(client, db):
    """A saved search must alert on exactly what its params return from the
    discovery endpoint (cross-checked over heterogeneous tenders)."""
    _seed([
        make_release("M1", title="Construction of a school",
                     org="KwaZulu-Natal Department of Education", province="KwaZulu-Natal",
                     closing_iso=_future(24 * 3)),
        make_release("M2", title="Supply of stationery",
                     org="Gauteng Department of Education", province="Gauteng",
                     closing_iso=_future(24 * 40)),
        make_release("M3", title="Construction of a bridge",
                     org="Limpopo Department of Roads", province="Limpopo",
                     closing_iso=_past(2)),
        make_release("M4", title="Security services",
                     org="KwaZulu-Natal Department of Transport", province="KwaZulu-Natal",
                     closing_iso=_future(24)),
    ])
    params = {"search": "construction", "province": "KwaZulu-Natal", "closing_within": "7d"}
    svc = SavedSearchService(db)
    search = svc.create("dev-1", "KZN construction", params)

    api_ids = {
        t["id"] for t in client.get(
            f"{API}/tenders?search=construction&province=KwaZulu-Natal&closing_within=7d"
        ).json()["data"]
    }
    matched = {
        t.id for t in db.query(Tender).all() if svc.matches(search, t)
    }
    assert api_ids == matched == {_first_tender(db, tender_number="M1").id}


def test_matcher_status_and_date_filters(client, db):
    _seed([
        make_release("A1", title="Alpha", org="KwaZulu-Natal Department of Health",
                     province="KwaZulu-Natal", closing_iso=_future(24 * 30)),
        make_release("A2", title="Beta", org="KwaZulu-Natal Department of Health",
                     province="KwaZulu-Natal", closing_iso=_future(24)),
        make_release("A3", title="Gamma", org="KwaZulu-Natal Department of Health",
                     province="KwaZulu-Natal", closing_iso=_past(3)),
    ])
    svc = SavedSearchService(db)
    by_number = {t.tender_number: t for t in db.query(Tender).all()}

    open_search = svc.create("dev-1", "open", {"status": "open"})
    assert {n for n, t in by_number.items() if svc.matches(open_search, t)} == {"A1", "A2"}

    soon_search = svc.create("dev-1", "soon", {"status": "closing_soon"})
    assert {n for n, t in by_number.items() if svc.matches(soon_search, t)} == {"A2"}

    closed_search = svc.create("dev-1", "closed", {"status": "closed"})
    assert {n for n, t in by_number.items() if svc.matches(closed_search, t)} == {"A3"}

    window_search = svc.create("dev-1", "window",
                               {"closing_after": "2000-01-01", "closing_before": "2099-01-01"})
    assert all(svc.matches(window_search, t) for t in by_number.values())

    none_search = svc.create("dev-1", "none",
                             {"advertised_after": "2098-01-01"})
    assert not any(svc.matches(none_search, t) for t in by_number.values())


def test_matcher_multi_value_and_terms(client, db):
    _seed([
        make_release("B1", title="Construction of classrooms",
                     org="Gauteng Department of Infrastructure", province="Gauteng",
                     closing_iso=_future()),
        make_release("B2", title="Supply of stationery",
                     org="KwaZulu-Natal Department of Transport", province="KwaZulu-Natal",
                     closing_iso=_future()),
    ])
    svc = SavedSearchService(db)
    multi = svc.create("dev-1", "multi", {"province": "gauteng,kwazulu-natal",
                                          "category": "construction,medical"})
    by_number = {t.tender_number: t for t in db.query(Tender).all()}
    assert {n for n, t in by_number.items() if svc.matches(multi, t)} == {"B1"}

    terms = svc.create("dev-1", "terms", {"search": "supply stationery"})
    assert {n for n, t in by_number.items() if svc.matches(terms, t)} == {"B2"}


# -------------------------------------------------------------- alert flow

def test_ingestion_alerts_saved_search_and_dedups(client, db):
    _create(client, name="KZN construction", search="construction",
            province="KwaZulu-Natal")

    # A tender matching the saved search flows through the real pipeline.
    _seed([
        make_release("AL1", title="Construction of a clinic",
                     org="KwaZulu-Natal Department of Health", province="KwaZulu-Natal",
                     closing_iso=_future()),
        make_release("AL2", title="Security services",
                     org="Limpopo Department of Roads", province="Limpopo",
                     closing_iso=_future()),
    ])

    user_id = db.query(Tender).filter(Tender.tender_number == "AL1").first().id
    events = db.query(NotificationEvent).all()
    # Only the matching tender produced an event for the saved-search owner.
    matched = [e for e in events if e.tender_id == user_id]
    assert len(matched) == 1
    assert matched[0].detail == "saved search: KZN construction"
    assert all(e.detail is None or e.tender_id != 999 for e in events)
    # The non-matching tender never alerted this user.
    other_id = db.query(Tender).filter(Tender.tender_number == "AL2").first().id
    assert not [e for e in events if e.tender_id == other_id]

    # Re-ingesting the same releases creates no duplicate events.
    _seed([
        make_release("AL1", title="Construction of a clinic",
                     org="KwaZulu-Natal Department of Health", province="KwaZulu-Natal",
                     closing_iso=_future()),
    ])
    assert len(db.query(NotificationEvent).all()) == 1


def test_alerts_disabled_search_does_not_notify(client, db):
    r = _create(client, name="Muted", search="construction")
    sid = r.json()["id"]
    client.patch(f"{API}/saved-searches/{sid}/alerts",
                 json={"client_id": "dev-1", "alerts_enabled": False})
    _seed([make_release("MU1", title="Construction of a depot",
                        org="KwaZulu-Natal Department of Health",
                        province="KwaZulu-Natal", closing_iso=_future())])
    assert db.query(NotificationEvent).count() == 0


def test_preference_and_saved_search_alert_dedupe(client, db):
    """A user with BOTH a matching preference and a matching saved search gets
    exactly one NEW_TENDER event."""
    client.put(f"{API}/preferences", json={
        "client_id": "dev-1",
        "preferences": [{"category": "construction", "province": None,
                         "notifications_enabled": True}],
    })
    _create(client, name="Everything", search="")
    _seed([make_release("DP1", title="Construction of a hall",
                        org="KwaZulu-Natal Department of Health",
                        province="KwaZulu-Natal", closing_iso=_future())])
    # dev-1 should have exactly one event for the tender.
    events = db.query(NotificationEvent).all()
    tender_id = db.query(Tender).filter(Tender.tender_number == "DP1").first().id
    assert len([e for e in events if e.tender_id == tender_id]) == 1


def test_params_json_never_stores_sort_or_unknown_keys(client):
    r = _create(client, name="Clean", search="x", sort="relevance", page="99",
                municipality="eThekwini")
    filters = r.json()["filters"]
    assert "sort" not in filters and "page" not in filters
    assert set(filters.keys()) <= {
        "search", "province", "category", "source", "status",
        "closing_within", "closing_after", "closing_before",
        "advertised_after", "advertised_before",
    }
