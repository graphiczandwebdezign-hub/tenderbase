"""Firebase Cloud Messaging client (foundation).

FCM works even when the Android app is closed — messages are delivered by
Google Play services on the device, independent of the app process.

The firebase-admin SDK is imported lazily and guarded: if FCM is not
configured (the default), sends are no-ops that report failure gracefully so
the rest of the pipeline is unaffected. Credentials come only from the
environment; nothing is hard-coded and no secret is ever logged.
"""
from __future__ import annotations

import threading
from typing import List, Optional

from app.core.config import settings
from app.core.logging import get_logger, log_event

logger = get_logger("fcm")

_init_lock = threading.Lock()
_app = None
_messaging = None


def _ensure_initialized() -> bool:
    global _app, _messaging
    if not settings.fcm_enabled:
        return False
    if _app is not None:
        return True
    with _init_lock:
        if _app is not None:
            return True
        if not (settings.fcm_project_id and settings.fcm_private_key and settings.fcm_client_email):
            log_event(logger, 30, "fcm_not_configured")
            return False
        try:
            import firebase_admin
            from firebase_admin import credentials, messaging

            cred = credentials.Certificate(
                {
                    "type": "service_account",
                    "project_id": settings.fcm_project_id,
                    "private_key": settings.fcm_private_key.replace("\\n", "\n"),
                    "client_email": settings.fcm_client_email,
                    "token_uri": "https://oauth2.googleapis.com/token",
                }
            )
            _app = firebase_admin.initialize_app(cred)
            _messaging = messaging
            log_event(logger, 20, "fcm_initialized", project=settings.fcm_project_id)
            return True
        except Exception as exc:  # noqa: BLE001
            log_event(logger, 40, "fcm_init_failed", error=str(exc))
            return False


class FCMClient:
    def send_multicast(
        self,
        tokens: List[str],
        title: str,
        body: str,
        data: Optional[dict] = None,
    ) -> bool:
        """Send a push to multiple device tokens. Returns True on success.

        When FCM is disabled/unconfigured this returns False (the caller marks
        the event accordingly) without raising, so notification matching and
        the ingestion pipeline still function end-to-end in development.
        """
        if not tokens:
            return False
        if not _ensure_initialized():
            log_event(logger, 20, "fcm_send_skipped", reason="disabled", recipients=len(tokens))
            return False
        try:
            message = _messaging.MulticastMessage(
                tokens=tokens,
                notification=_messaging.Notification(title=title, body=body),
                data={k: str(v) for k, v in (data or {}).items()},
            )
            response = _messaging.send_each_for_multicast(message)
            log_event(logger, 20, "fcm_sent", success=response.success_count,
                      failure=response.failure_count)
            return response.success_count > 0
        except Exception as exc:  # noqa: BLE001
            log_event(logger, 40, "fcm_send_failed", error=str(exc))
            return False
