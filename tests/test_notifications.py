"""Notification matching, duplicate prevention, device registration,
preferences."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import select

from app.database.database import SessionLocal
from app.database.models import (
    User, UserPreference, NotificationToken, NotificationEvent, Category, Province,
)
from app.database.models.notifications import NotificationType
from app.services.ingestion_service import IngestionService
from app.services.notification_service import NotificationService
from tests.mock_source import MockSourceAdapter, make_release

API = "/api/v1"


def _future(hours=72):
    return (datetime.now(timezone.utc) + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _make_user_with_pref(db, client_id, category_slug=None, province_slug=None):
    user = User(client_id=client_id)
    db.add(user)
    db.flush()
    db.add(NotificationToken(user_id=user.id, device_token=f"tok-{client_id}", active=True))
    cat_id = None
    prov_id = None
    if category_slug:
        cat_id = db.execute(select(Category).where(Category.slug == category_slug)).scalar_one().id
    if province_slug:
        prov_id = db.execute(select(Province).where(Province.slug == province_slug)).scalar_one().id
    db.add(UserPreference(user_id=user.id, category_id=cat_id, province_id=prov_id,
                          notifications_enabled=True))
    db.commit()
    return user


def test_notification_matching_positive_and_negative(db):
    # Match user: Construction + KwaZulu-Natal
    _make_user_with_pref(db, "match", "construction", "kwazulu-natal")
    # Non-match user: Cleaning + Gauteng
    _make_user_with_pref(db, "nomatch", "cleaning", "gauteng")

    adapter = MockSourceAdapter([
        make_release("N1", title="Construction of a bridge", category="works",
                     province="KwaZulu-Natal", org="eThekwini Municipality",
                     closing_iso=_future()),
    ])
    run = IngestionService(db, adapter).run_sync(trigger="manual")

    events = db.execute(select(NotificationEvent)).scalars().all()
    matched_users = {db.get(User, e.user_id).client_id for e in events}
    assert "match" in matched_users
    assert "nomatch" not in matched_users


def test_duplicate_notification_prevention(db):
    _make_user_with_pref(db, "dup", "construction", "kwazulu-natal")
    r = make_release("DUP1", title="Construction works", category="works",
                     province="KwaZulu-Natal", org="eThekwini Municipality",
                     closing_iso=_future())
    adapter = MockSourceAdapter([r])
    IngestionService(db, adapter).run_sync(trigger="manual")
    count1 = db.query(NotificationEvent).count()

    # Re-run: no NEW_TENDER re-notification for the same tender.
    IngestionService(db, adapter).run_sync(trigger="manual")
    count2 = db.query(NotificationEvent).count()
    assert count1 == count2 == 1


def test_null_preference_matches_any(db):
    # A user with no category/province set (any/any) should match everything.
    _make_user_with_pref(db, "any", None, None)
    adapter = MockSourceAdapter([make_release("ANY1", closing_iso=_future())])
    IngestionService(db, adapter).run_sync(trigger="manual")
    events = db.query(NotificationEvent).count()
    assert events == 1


def test_device_registration_and_preferences_via_api(client):
    # Register device
    r = client.post(f"{API}/notifications/register-device", json={
        "client_id": "device-1", "device_token": "fcm-token-1", "platform": "android"
    })
    assert r.status_code == 200
    assert r.json()["active"] is True

    # Set preferences
    r2 = client.put(f"{API}/preferences", json={
        "client_id": "device-1",
        "preferences": [{"category": "construction", "province": "gauteng"}],
    })
    assert r2.status_code == 200
    prefs = r2.json()["preferences"]
    assert prefs[0]["category"] == "construction"
    assert prefs[0]["province"] == "gauteng"

    # Get preferences
    r3 = client.get(f"{API}/preferences?client_id=device-1")
    assert r3.status_code == 200
    assert len(r3.json()["preferences"]) == 1

    # Unregister
    r4 = client.request("DELETE", f"{API}/notifications/unregister-device",
                        json={"device_token": "fcm-token-1"})
    assert r4.status_code == 200


def test_new_tender_notification_only_first_time(db):
    _make_user_with_pref(db, "first", None, None)
    r1 = make_release("FT1", closing_iso=_future())
    adapter = MockSourceAdapter([r1])
    run1 = IngestionService(db, adapter).run_sync(trigger="manual")
    # Add a second tender; first must not re-notify.
    adapter.set_releases([r1, make_release("FT2", closing_iso=_future())])
    run2 = IngestionService(db, adapter).run_sync(trigger="manual")
    # Total NEW_TENDER events == number of distinct tenders (2).
    total = db.query(NotificationEvent).filter(
        NotificationEvent.notification_type == NotificationType.NEW_TENDER
    ).count()
    assert total == 2
