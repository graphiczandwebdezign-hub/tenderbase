"""Sprint 1 discovery tests: search, filters, sorting, pagination, facets.

Realistic tenders flow through the real ingestion pipeline (MockSourceAdapter
wrapping the eTenders normalizer); exotic field values (municipality, extra
sources, enum statuses) are inserted directly against the model.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import List

from app.database.database import SessionLocal
from app.database.models import Tender, TenderStatus
from app.services.ingestion_service import IngestionService
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


def _insert(db_session, **kwargs) -> Tender:
    """Insert a tender row directly (for fields the eTenders feed never sets)."""
    defaults = dict(
        source="eTenders",
        external_id=f"MAN-{kwargs.get('title', 'X')[:20]}-{datetime.now(timezone.utc).timestamp()}",
        title="Manual tender",
        status=TenderStatus.ACTIVE,
        closing_at=datetime.now(timezone.utc) + timedelta(days=9),
        closing_date=(datetime.now(timezone.utc) + timedelta(days=9)).date(),
    )
    defaults.update(kwargs)
    t = Tender(**defaults)
    db_session.add(t)
    db_session.commit()
    return t


# --------------------------------------------------------------------- search

def test_search_matches_reference_organisation_province_category(client):
    _seed([
        make_release("REF1", title="Supply of desks", org="Department of Education",
                     province="Gauteng", category="goods", closing_iso=_future()),
        make_release("OTHER", title="Unrelated works", org="Department of Roads",
                     province="Limpopo", category="works", closing_iso=_future()),
    ])
    # by reference number
    assert client.get(f"{API}/tenders?search=REF1").json()["pagination"]["total"] == 1
    # by organisation
    assert client.get(f"{API}/tenders?search=Department of Education").json()["pagination"]["total"] == 1
    # by province
    assert client.get(f"{API}/tenders?search=Gauteng").json()["pagination"]["total"] == 1
    # by (normalized) category — "Supply of desks" ingests as Furniture
    assert client.get(f"{API}/tenders?search=furniture").json()["pagination"]["total"] == 1


def test_search_terms_are_anded(client):
    _seed([
        make_release("AND1", title="Construction of a clinic", province="Gauteng",
                     closing_iso=_future()),
        make_release("AND2", title="Construction of a road", province="Limpopo",
                     closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders?search=construction clinic")
    titles = [t["title"] for t in r.json()["data"]]
    assert titles == ["Construction of a clinic"]


def test_search_escapes_like_wildcards(client):
    _seed([
        make_release("WILD1", title="Supply of 100% steel", closing_iso=_future()),
        make_release("WILD2", title="Supply of wood", closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders?search=100%25")
    assert r.status_code == 200
    titles = [t["title"] for t in r.json()["data"]]
    assert titles == ["Supply of 100% steel"]


def test_search_endpoint_relevance_default(client):
    _seed([
        make_release("REL1", title="Appointment of plumbers", description="general maintenance",
                     closing_iso=_future()),
        make_release("REL2", title="General maintenance panel", description="plumbers and fitters",
                     closing_iso=_future()),
    ])
    data = client.get(f"{API}/tenders/search?q=plumbers").json()["data"]
    assert data[0]["title"] == "Appointment of plumbers"


# ---------------------------------------------------------------------- sort

def test_sort_newest_first(client):
    _seed([
        make_release("N1", title="Older", advertised_iso="2026-01-01T00:00:00", closing_iso=_future()),
        make_release("N2", title="Newest", advertised_iso="2026-08-28T00:00:00", closing_iso=_future()),
        make_release("N3", title="Middle", advertised_iso="2026-05-05T00:00:00", closing_iso=_future()),
    ])
    titles = [t["title"] for t in client.get(f"{API}/tenders?sort=newest").json()["data"]]
    assert titles == ["Newest", "Middle", "Older"]


def test_sort_closing_soonest_and_excludes_closed(client):
    _seed([
        make_release("C1", title="Closes later", closing_iso=_future(24 * 10)),
        make_release("C2", title="Closes sooner", closing_iso=_future(24 * 2)),
        make_release("C3", title="Already closed", closing_iso=_past(3)),
    ])
    r = client.get(f"{API}/tenders?sort=closing")
    titles = [t["title"] for t in r.json()["data"]]
    assert titles == ["Closes sooner", "Closes later"]


def test_sort_recently_updated(client, db):
    _seed([
        make_release("U1", title="Stale", closing_iso=_future()),
        make_release("U2", title="Fresh change", closing_iso=_future()),
    ])
    stale = db.query(Tender).filter(Tender.title == "Stale").one()
    fresh = db.query(Tender).filter(Tender.title == "Fresh change").one()
    stale.updated_at = datetime.now(timezone.utc) - timedelta(days=30)
    fresh.updated_at = datetime.now(timezone.utc)
    db.commit()
    titles = [t["title"] for t in client.get(f"{API}/tenders?sort=updated").json()["data"]]
    assert titles[0] == "Fresh change"


def test_sort_relevance_ranks_title_match_first(client):
    _seed([
        make_release("R1", title="Borehole drilling services", description="water",
                     closing_iso=_future()),
        make_release("R2", title="Water services panel", description="borehole drilling and more",
                     closing_iso=_future()),
    ])
    titles = [t["title"] for t in client.get(f"{API}/tenders?search=drilling&sort=relevance").json()["data"]]
    assert titles[0] == "Borehole drilling services"


def test_invalid_sort_returns_400(client):
    r = client.get(f"{API}/tenders?sort=random")
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PARAMETER"


# -------------------------------------------------------------------- status

def test_status_lifecycle_aliases(client, db):
    _seed([
        make_release("SOON", title="Due tomorrow", closing_iso=_future(24)),
        make_release("OPEN", title="Due next month", closing_iso=_future(24 * 30)),
        make_release("GONE", title="Past deadline", closing_iso=_past(2)),
    ])
    _insert(db, title="Marked expired", status=TenderStatus.EXPIRED,
            closing_at=datetime.now(timezone.utc) - timedelta(days=5))

    open_titles = [t["title"] for t in client.get(f"{API}/tenders?status=open").json()["data"]]
    assert sorted(open_titles) == ["Due next month", "Due tomorrow"]

    soon = [t["title"] for t in client.get(f"{API}/tenders?status=closing_soon").json()["data"]]
    assert soon == ["Due tomorrow"]

    closed = [t["title"] for t in client.get(f"{API}/tenders?status=closed").json()["data"]]
    assert sorted(closed) == ["Marked expired", "Past deadline"]


def test_status_enum_still_accepted(client):
    # Past-closing tenders are ingested/stored with status CLOSED.
    _seed([make_release("ACT", closing_iso=_future()),
           make_release("OLD", closing_iso=_past(1))])
    r = client.get(f"{API}/tenders?status=CLOSED")
    assert r.json()["pagination"]["total"] == 1
    assert r.json()["data"][0]["tender_number"] == "OLD"


def test_invalid_status_returns_400(client):
    r = client.get(f"{API}/tenders?status=banana")
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PARAMETER"
    r2 = client.get(f"{API}/tenders?status=open,closed")
    assert r2.status_code == 400


# -------------------------------------------------------------------- filters

def test_multi_province_and_category_filter(client):
    _seed([
        make_release("MP1", title="Construction of classrooms",
                     org="Gauteng Department of Infrastructure", province="Gauteng",
                     closing_iso=_future()),
        make_release("MP2", title="Supply of stationery",
                     org="KwaZulu-Natal Department of Transport", province="KwaZulu-Natal",
                     closing_iso=_future()),
        make_release("MP3", title="Construction of a bridge",
                     org="Limpopo Department of Public Works", province="Limpopo",
                     closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders?province=gauteng,kwazulu-natal")
    assert r.json()["pagination"]["total"] == 2
    r2 = client.get(f"{API}/tenders?province=gauteng,kwazulu-natal&category=construction")
    assert r2.json()["pagination"]["total"] == 1
    assert r2.json()["data"][0]["title"] == "Construction of classrooms"


def test_source_filter_and_facets(client, db):
    _seed([
        make_release("SRC1", title="Construction of a depot",
                     org="Gauteng Department of Roads", province="Gauteng", closing_iso=_future()),
        make_release("SRC2", title="Construction of a workshop",
                     org="Gauteng Department of Roads", province="Gauteng", closing_iso=_future()),
    ])
    _insert(db, title="Manually captured", source="Manual", province="Gauteng")

    r = client.get(f"{API}/tenders?source=eTenders")
    assert r.json()["pagination"]["total"] == 2
    r2 = client.get(f"{API}/tenders?source=Manual")
    assert [t["title"] for t in r2.json()["data"]] == ["Manually captured"]

    facets = client.get(f"{API}/tenders/facets").json()
    assert facets["sources"] == [
        {"name": "eTenders", "count": 2},
        {"name": "Manual", "count": 1},
    ]
    assert facets["provinces"] == [{"name": "Gauteng", "count": 3}]
    assert facets["categories"] == [{"name": "Construction", "count": 2}]


def test_facets_exclude_closed_tenders(client, db):
    _seed([make_release("FC1", province="Gauteng", closing_iso=_past(1))])
    _insert(db, title="Live manual", province="North West")
    facets = client.get(f"{API}/tenders/facets").json()
    assert facets["provinces"] == [{"name": "North West", "count": 1}]


def test_municipality_substring_filter(client, db):
    # Sprint 8: ingestion auto-detects municipality from the org name, so the
    # ingested record already carries eThekwini; the manual row uses another.
    _seed([make_release("MUN1", closing_iso=_future())])
    _insert(db, title="Municipal works", municipality="uMhlathuze")
    assert client.get(f"{API}/tenders?municipality=ethekwini").json()["pagination"]["total"] == 1


def test_published_date_window_filter(client):
    _seed([
        make_release("D1", advertised_iso="2026-08-29T00:00:00", closing_iso=_future()),
        make_release("D2", advertised_iso="2026-08-10T00:00:00", closing_iso=_future()),
        make_release("D3", advertised_iso="2026-01-02T00:00:00", closing_iso=_future()),
    ])
    r = client.get(f"{API}/tenders?advertised_after=2026-08-01&advertised_before=2026-08-29")
    assert r.json()["pagination"]["total"] == 2


def test_closing_date_window_and_invalid_window(client):
    _seed([
        make_release("W1", closing_iso=_future(24 * 5)),
        make_release("W2", closing_iso=_future(24 * 20)),
    ])
    r = client.get(f"{API}/tenders?closing_within=7d")
    assert [t["title"] for t in r.json()["data"]] == ["Test Tender"]
    assert client.get(f"{API}/tenders?closing_before=2026-12-31").json()["pagination"]["total"] == 2
    bad = client.get(f"{API}/tenders?closing_within=next-week")
    assert bad.status_code == 400


def test_combined_search_and_filters_preserved(client):
    _seed([
        make_release("CB1", title="Construction of a school",
                     org="KwaZulu-Natal Department of Education", province="KwaZulu-Natal",
                     closing_iso=_future(24 * 5)),
        make_release("CB2", title="Construction of a school",
                     org="City of Tshwane", province="Gauteng",
                     closing_iso=_future(24 * 5)),
        make_release("CB3", title="Construction of a school",
                     org="KwaZulu-Natal Department of Education", province="KwaZulu-Natal",
                     closing_iso=_future(24 * 20)),
        make_release("CB4", title="Supply of stationery",
                     org="KwaZulu-Natal Department of Education", province="KwaZulu-Natal",
                     closing_iso=_future(24 * 5)),
    ])
    r = client.get(f"{API}/tenders?search=construction school&province=kwazulu-natal&closing_within=7d")
    assert r.json()["pagination"]["total"] == 1
    assert r.json()["data"][0]["tender_number"] == "CB1"


# ----------------------------------------------------------------- pagination

def test_pagination_no_duplicates_and_meta(client):
    _seed([make_release(f"P{i}", closing_iso=_future()) for i in range(10)])
    seen: List[int] = []
    for page in (1, 2, 3, 4):
        body = client.get(f"{API}/tenders?limit=3&page={page}").json()
        meta = body["pagination"]
        assert meta["total"] == 10
        assert meta["total_pages"] == 4
        assert meta["page"] == page
        seen.extend(t["id"] for t in body["data"])
    assert len(seen) == len(set(seen)) == 10


def test_empty_result_set_meta(client):
    _seed([make_release("E1", closing_iso=_future())])
    body = client.get(f"{API}/tenders?search=zzzznothing").json()
    assert body["data"] == []
    assert body["pagination"]["total"] == 0
    assert body["pagination"]["total_pages"] == 0


# ----------------------------------------------------------------------- auth

def test_facets_requires_api_key(noauth_client):
    assert noauth_client.get(f"{API}/tenders/facets").status_code == 401
