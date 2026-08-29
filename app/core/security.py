"""API-key authentication and hashing helpers."""
from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timezone
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import APIKeyHeader
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.logging import get_logger, log_event
from app.database.database import get_db
from app.database.models.auth import ApiKey

logger = get_logger("security")

# Security schemes — these make the "Authorize" button appear in Swagger and
# document the required headers in the OpenAPI spec. auto_error=False so we can
# return our own consistent error envelope.
api_key_scheme = APIKeyHeader(name="X-API-Key", auto_error=False, scheme_name="ApiKeyAuth")
admin_scheme = APIKeyHeader(name="X-Admin-Secret", auto_error=False, scheme_name="AdminSecret")


def hash_key(raw_key: str) -> str:
    """Return the SHA-256 hex digest of a raw API key. Raw keys are never
    stored; only this digest is persisted."""
    return hashlib.sha256(raw_key.encode("utf-8")).hexdigest()


def generate_api_key() -> str:
    """Generate a new random API key (URL-safe)."""
    return "sk_" + secrets.token_urlsafe(32)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def ensure_bootstrap_key(db: Session) -> None:
    """Ensure the API_KEY from the environment exists in the api_keys table."""
    if not settings.api_key:
        return
    digest = hash_key(settings.api_key)
    existing = db.execute(
        select(ApiKey).where(ApiKey.key_hash == digest)
    ).scalar_one_or_none()
    if existing:
        return
    db.add(
        ApiKey(
            name="bootstrap",
            key_hash=digest,
            key_prefix=settings.api_key[:8],
            active=True,
        )
    )
    db.commit()
    log_event(logger, 20, "bootstrap_api_key_created", name="bootstrap")


def _unauthorized(code: str, message: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail={"code": code, "message": message},
    )


async def require_api_key(
    x_api_key: Optional[str] = Depends(api_key_scheme),
    db: Session = Depends(get_db),
) -> ApiKey:
    """Dependency: validate the X-API-Key header against the api_keys table."""
    if not x_api_key:
        log_event(logger, 30, "auth_failure", reason="missing_key")
        raise _unauthorized("MISSING_API_KEY", "API key is required")

    digest = hash_key(x_api_key)
    api_key = db.execute(
        select(ApiKey).where(ApiKey.key_hash == digest)
    ).scalar_one_or_none()

    if api_key is None or not api_key.active:
        log_event(logger, 30, "auth_failure", reason="invalid_key")
        raise _unauthorized("INVALID_API_KEY", "API key is invalid")

    if api_key.expires_at and api_key.expires_at < _now():
        log_event(logger, 30, "auth_failure", reason="expired_key", key_id=api_key.id)
        raise _unauthorized("EXPIRED_API_KEY", "API key has expired")

    api_key.last_used_at = _now()
    db.commit()
    return api_key


async def require_admin(
    x_admin_secret: Optional[str] = Depends(admin_scheme),
) -> bool:
    """Dependency: gate admin endpoints behind ADMIN_SECRET."""
    if not settings.admin_secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "ADMIN_DISABLED", "message": "Admin secret is not configured"},
        )
    if not x_admin_secret or not secrets.compare_digest(x_admin_secret, settings.admin_secret):
        log_event(logger, 30, "admin_auth_failure")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "INVALID_ADMIN_SECRET", "message": "Admin secret is invalid"},
        )
    return True
