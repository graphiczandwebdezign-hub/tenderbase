"""Sprint 8 tests: text extraction heuristics, re-enrichment, digest batching."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.core.date_extraction import extract_closing
from app.core.normalization import detect_municipality
from app.database.models import Tender
from app.database.database import SessionLocal
from app.services.ingestion_service import IngestionService
from app.services.notification_service import NotificationService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"
ADMIN = {"X-Admin-Secret": "test-admin-secret"}

UTC = timezone.utc


def _future(hours=72):
    return (
        (datetime.now(UTC) + timedelta(hours=hours)).replace(microsecond=0).isoformat()
    )


# ------------------------------------------------------------ date extraction

def test_extract_closing_formats():
    cases = [
        ("Closing date: 12 September 2026 at 11:00", datetime(2026, 9, 12, 9, 0, tzinfo=UTC)),
        ("close on 3 Jan 2027 before 12h00", datetime(2027, 1, 3, 10, 0, tzinfo=UTC)),
        ("Deadline: 2026-10-01 14:30", datetime(2026, 10, 1, 12, 30, tzinfo=UTC)),
    ]
    for text, expected in cases:
        got = extract_closing(text)
        assert got == expected, (text, got, expected)


def test_extract_closing_date_only_means_end_of_day_sast():
    got = extract_closing("Sealed bids close on 3/10/2026 at our offices")
    assert got == datetime(2026, 10, 3, 21, 59, tzinfo=UTC)  # 23:59 SAST


def test_extract_closing_keyword_beats_leftmost_date():
    text = "Meeting 1/9/2026 to discuss. Closing date: 15/9/2026."
    got = extract_closing(text)
    assert (got.year, got.month, got.day) == (2026, 9, 15)


def test_extract_closing_rejects_implausible_years_and_garbage():
    assert extract_closing("Renovations ref 1999-01-01") is None
    assert extract_closing("version 2026.09.12 build") is None  # dots not a date
    assert extract_closing("") is None
    assert extract_closing(None) is None


def test_extract_closing_time_window_guard():
    # A time 100 chars away from the date must not attach.
    text = "12 September 2026 " + ("x" * 100) + " meeting at 08:00"
    got = extract_closing(text)
    assert got.hour == 21 and got.minute == 59  # fell back to end-of-day


# ------------------------------------------------------------- municipalities

def test_detect_municipality_and_province_fallback():
    assert detect_municipality("Road works in Durban area") == ("eThekwini", "kwazulu-natal")
    assert detect_municipality("Ray Nkonyeni Municipality: fencing") == ("Ray Nkonyeni", "kwazulu-natal")
    assert detect_municipality("Gqeberha office park") == ("Nelson Mandela Bay", "eastern-cape")
    assert detect_municipality("no municipality here") is None


def test_ingestion_recovers_missing_province_and_closing(client):
    """A record with no structured region/deadline still lands complete."""
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter([
            make_release(
                "RE1",
                org="Private Vendor XYZ",          # no city heuristic
                province=None,
                closing_iso=None,                  # no structured deadline
                description=(
                    "Fencing at the Durban depot. "
                    "Closing date: 30 September 2026 at 11:00."
                ),
            )
        ])).run_sync(trigger="manual")
        from app.database.models import Tender

        t = db.query(Tender).filter(Tender.tender_number == "RE1").one()
        assert t.province == "KwaZulu-Natal"       # via municipality fallback
        assert t.municipality == "eThekwini"
        assert t.closing_at is not None
        assert t.closing_at.astimezone(UTC).date().isoformat() == "2026-09-30"
    finally:
        db.close()


# ----------------------------------------------------------------- re-enrich

def _stale_row(**kw):
    from app.database.models import Tender

    defaults = dict(
        source="Legacy Feed",
        tender_number="S8-1",
        external_id="S8-1",
        title="Waterworks",
    )
    defaults.update(kw)
    return Tender(**defaults)


def test_re_enrich_fills_missing_fields_without_overwrite(client, db):
    db.add(_stale_row(
        title="Durban harbour repairs — closing date: 30 November 2026 at 11:00",
        description="",
    ))
    db.add(_stale_row(
        tender_number="S8-2", external_id="S8-2",
        title="Complete row",
        province="Gauteng",
        municipality="Mbombela",
        closing_at=datetime(2026, 12, 1, 10, 0, tzinfo=UTC),
    ))
    db.commit()

    r = client.post(f"{API}/admin/re-enrich", headers=ADMIN)
    assert r.status_code == 200
    body = r.json()
    assert body["dry_run"] is False
    assert body["province_filled"] == 1
    assert body["municipality_filled"] == 1
    assert body["closing_filled"] == 1

    fixed = db.query(Tender).filter(Tender.tender_number == "S8-1").one()
    assert fixed.province == "KwaZulu-Natal"
    assert fixed.municipality == "eThekwini"
    assert fixed.closing_at is not None
    assert fixed.expires_at is not None  # retention applied

    # Complete rows are untouched (scanned excludes them).
    untouched = db.query(Tender).filter(Tender.tender_number == "S8-2").one()
    assert untouched.province == "Gauteng"


def test_re_enrich_dry_run_changes_nothing(client, db):
    db.add(_stale_row(title="Durban depot upgrade"))
    db.commit()
    body = client.post(f"{API}/admin/re-enrich?dry_run=true", headers=ADMIN).json()
    assert body["dry_run"] is True
    assert body["province_filled"] == 1
    row = db.query(Tender).filter(Tender.tender_number == "S8-1").one()
    assert row.province is None  # not applied


def test_re_enrich_requires_admin(client):
    assert client.post(f"{API}/admin/re-enrich").status_code == 401


# ------------------------------------------------------------ digest batching

class _FakeFCM:
    calls: list = []

    def send_multicast(self, tokens, title, body, data):
        _FakeFCM.calls.append({"tokens": tokens, "title": title, "body": body, "data": data})
        return True


def _setup_alertable_user(client):
    r = client.post(
        f"{API}/notifications/register-device",
        json={"client_id": "dev-digest", "device_token": "tok-digest", "platform": "android"},
    )
    assert r.status_code == 200
    r2 = client.put(
        f"{API}/preferences",
        json={  # one preference with no category/province -> matches everything
            "client_id": "dev-digest",
            "preferences": [{"notifications_enabled": True}],
        },
    )
    assert r2.status_code == 200


class _NoNotify:
    """Stand-in that suppresses the ingestion pipeline's own notifications."""

    def __init__(self, db):
        pass

    def notify_new_tenders(self, ids):
        return 0

    def notify_amended_tenders(self, ids):
        return 0

    def notify_saved_search_matches(self, ids):
        return 0


def _ingest_two(client, monkeypatch):
    monkeypatch.setattr(
        "app.services.ingestion_service.NotificationService", _NoNotify
    )
    db = SessionLocal()
    try:
        IngestionService(db, MockSourceAdapter([
            make_release("DG1", title="Bridge refurbishment", closing_iso=_future()),
            make_release("DG2", title="School fencing", closing_iso=_future()),
        ])).run_sync(trigger="manual")
        return [
            t.id for t in db.query(Tender)
            .filter(Tender.tender_number.in_((["DG1", "DG2"]))).all()
        ]
    finally:
        db.close()


def test_multiple_alerts_collapse_into_one_digest(client, db, monkeypatch):
    _setup_alertable_user(client)
    ids = _ingest_two(client, monkeypatch)
    assert len(ids) == 2

    monkeypatch.setattr(
        "app.services.notification_service.FCMClient", _FakeFCM
    )
    _FakeFCM.calls = []
    svc = NotificationService(db)
    sent = svc.notify_new_tenders(ids)

    assert sent == 2                      # one event per (user, tender)
    assert len(_FakeFCM.calls) == 1       # ONE push
    call = _FakeFCM.calls[0]
    assert call["title"] == "2 new tenders match your alerts"
    assert "Bridge refurbishment" in call["body"] and "School fencing" in call["body"]
    assert call["data"]["count"] == "2"

    from app.database.models.notifications import NotificationEvent, NotificationEventStatus

    events = db.query(NotificationEvent).all()
    assert len(events) == 2
    assert all(e.status == NotificationEventStatus.SENT for e in events)


def test_single_alert_keeps_per_tender_message(client, db, monkeypatch):
    _setup_alertable_user(client)
    ids = _ingest_two(client, monkeypatch)

    monkeypatch.setattr(
        "app.services.notification_service.FCMClient", _FakeFCM
    )
    _FakeFCM.calls = []
    svc = NotificationService(db)
    sent = svc.notify_new_tenders(ids[:1])

    assert sent == 1
    assert len(_FakeFCM.calls) == 1
    assert _FakeFCM.calls[0]["title"] == "New tender"  # classic compose
    assert _FakeFCM.calls[0]["data"].get("tender_id")


def test_digest_can_be_disabled(client, db, monkeypatch):
    _setup_alertable_user(client)
    ids = _ingest_two(client, monkeypatch)

    from app.core.config import settings as _settings

    monkeypatch.setattr(_settings, "digest_notifications", False)
    monkeypatch.setattr(
        "app.services.notification_service.FCMClient", _FakeFCM
    )
    _FakeFCM.calls = []
    svc = NotificationService(db)
    assert svc.notify_new_tenders(ids) == 2
    assert len(_FakeFCM.calls) == 2       # one push per tender again
