"""Sprint 9 tests: the admin console shows analytics + data quality and can
trigger re-enrichment (cookie-authenticated, PRG form flow)."""
from __future__ import annotations

from app.database.database import SessionLocal
from app.database.models import Tender
from app.services.ingestion_service import IngestionService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"


def _login(client, secret="test-admin-secret"):
    return client.post(
        "/admin/login", data={"secret": secret}, follow_redirects=False
    )


def _auth_cookie_client(client):
    resp = _login(client)
    assert resp.status_code == 303
    cookie = resp.headers["set-cookie"].split(";")[0]
    client.headers.update({"Cookie": cookie})
    return client


def _seed_incomplete(db):
    db.add(Tender(
        source="Legacy Feed",
        tender_number="C9-1",
        external_id="C9-1",
        title="Durban depot upgrade — closing date: 30 November 2026 at 11:00",
    ))
    db.commit()


# --------------------------------------------------------------------- auth

def test_console_requires_login(client):
    r = client.get("/admin", follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/admin/login"


def test_login_wrong_secret_rejected(client):
    r = _login(client, secret="nope")
    assert r.status_code == 401
    assert "Invalid" in r.text


def test_console_renders_after_login(client):
    _auth_cookie_client(client)
    r = client.get("/admin")
    assert r.status_code == 200
    assert "text/html" in r.headers["content-type"]
    for marker in (
        "Tender overview",
        "Discovery",
        "Data quality by source",
        "Zero-result terms",
        "Recent synchronizations",
        'class="bars"',
        'class="meter',
        "Dry-run backfill",
        "Fill gaps now",
    ):
        assert marker in r.text, marker


# ------------------------------------------------------------- analytics view

def test_console_shows_zero_result_terms_and_facets(client):
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter([
            make_release("C9S", title="Bridge Construction")
        ])).run_sync(trigger="manual")
    finally:
        db.close()
    client.get(f"{API}/tenders?search=ghost-term-xyz")       # zero results
    client.get(f"{API}/tenders?province=KwaZulu-Natal")      # facet usage

    _auth_cookie_client(client)
    html = client.get("/admin").text
    assert "ghost-term-xyz" in html
    assert "province" in html
    assert "Bridge Construction" not in html  # matched search isn't zero-result


# --------------------------------------------------------------- re-enrich UI

def test_console_re_enrich_dry_run_then_apply(client, db):
    _seed_incomplete(db)
    _auth_cookie_client(client)

    r = client.post(
        "/admin/re-enrich", data={"dry_run": "true"}, follow_redirects=False
    )
    assert r.status_code == 303
    assert r.headers["location"].startswith("/admin?enrich=")
    assert "#quality" in r.headers["location"]
    assert "dry+run" in r.headers["location"] or "dry%20run" in r.headers["location"]
    row = db.query(Tender).filter(Tender.tender_number == "C9-1").one()
    assert row.province is None  # nothing changed

    r2 = client.post(
        "/admin/re-enrich", data={"dry_run": "false"}, follow_redirects=False
    )
    assert r2.status_code == 303
    db.expire_all()
    row = db.query(Tender).filter(Tender.tender_number == "C9-1").one()
    assert row.province == "KwaZulu-Natal"
    assert row.municipality == "eThekwini"
    assert row.closing_at is not None


def test_console_re_enrich_requires_login(client):
    r = client.post("/admin/re-enrich", data={"dry_run": "true"}, follow_redirects=False)
    assert r.status_code == 303
    assert r.headers["location"] == "/admin/login"


def test_console_quality_shows_worst_first_with_meters(client, db):
    _seed_incomplete(db)
    _auth_cookie_client(client)
    html = client.get("/admin").text
    # the legacy row is the only incomplete one -> its source tops the table
    legacy_pos = html.find("Legacy Feed")
    overall_pos = html.find("(all)")
    assert legacy_pos > overall_pos  # overall row first, then sources
    assert "0.667" in html or "meter-fill" in html
