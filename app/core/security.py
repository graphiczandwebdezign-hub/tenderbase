"""API-key authentication and hashing helpers."""
from __future__ import annotations

import hashlib
import secrets
from datetime import datetime, timezone
from typing import List, Optional, Tuple

from fastapi import Depends, HTTPException, status
from fastapi.security import APIKeyHeader
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.config import settings, BUNDLED_API_KEY, BUNDLED_ADMIN_SECRET
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


BUNDLED_KEY_NAME = "bootstrap-bundled"


def bootstrap_keys() -> List[Tuple[str, str]]:
    """(raw_key, name) pairs to ensure in the api_keys table at startup.

    The environment's API_KEY always comes first. While bundled credentials are
    allowed, the bundled key is ensured too — as a *separate* active row — so
    the same build works whether or not the host defines its own API_KEY.
    """
    out: List[Tuple[str, str]] = []
    if settings.api_key:
        out.append((settings.api_key, "bootstrap"))
    if (
        settings.allow_bundled_credentials
        and settings.api_key != BUNDLED_API_KEY
    ):
        out.append((BUNDLED_API_KEY, BUNDLED_KEY_NAME))
    return out


def ensure_bootstrap_key(db: Session) -> None:
    """Ensure the bootstrap API key(s) exist in the api_keys table."""
    created = 0
    for raw_key, name in bootstrap_keys():
        digest = hash_key(raw_key)
        if db.execute(
            select(ApiKey).where(ApiKey.key_hash == digest)
        ).scalar_one_or_none():
            # Already present (possibly revoked on purpose) — never resurrect it.
            continue
        db.add(
            ApiKey(
                name=name,
                key_hash=digest,
                key_prefix=raw_key[:8],
                active=True,
            )
        )
        created += 1
    if created:
        db.commit()
        log_event(logger, 20, "bootstrap_api_key_created", count=created)


def prune_bundled_credentials(db: Session) -> int:
    """Deactivate API-key rows that were bootstrapped from the bundled value.

    Called at startup when ALLOW_BUNDLED_CREDENTIALS is false, so turning the
    kill switch off actually revokes a key that an earlier run inserted —
    otherwise the flag would only prevent *new* rows and the leaked credential
    would keep working forever.
    """
    if settings.allow_bundled_credentials:
        return 0
    rows = db.execute(
        select(ApiKey).where(ApiKey.name == BUNDLED_KEY_NAME, ApiKey.active.is_(True))
    ).scalars().all()
    for row in rows:
        row.active = False
    if rows:
        db.commit()
        log_event(logger, 30, "bundled_credentials_pruned", count=len(rows))
    return len(rows)


def accepted_admin_secrets() -> List[str]:
    """Admin secrets that authenticate, environment value first."""
    out: List[str] = []
    if settings.admin_secret:
        out.append(settings.admin_secret)
    if (
        settings.allow_bundled_credentials
        and settings.admin_secret != BUNDLED_ADMIN_SECRET
    ):
        out.append(BUNDLED_ADMIN_SECRET)
    return out


def matches_admin_secret(candidate: Optional[str]) -> bool:
    """Constant-time compare against every accepted admin secret."""
    if not candidate:
        return False
    return any(secrets.compare_digest(candidate, known) for known in accepted_admin_secrets())


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
    if not accepted_admin_secrets():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": "ADMIN_DISABLED", "message": "Admin secret is not configured"},
        )
    if not matches_admin_secret(x_admin_secret):
        log_event(logger, 30, "admin_auth_failure")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "INVALID_ADMIN_SECRET", "message": "Admin secret is invalid"},
        )
    return True
