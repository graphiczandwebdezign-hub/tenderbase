"""API key model. Raw keys are never stored — only salted hashes."""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from sqlalchemy import String, Boolean, DateTime, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class ApiKey(Base):
    __tablename__ = "api_keys"

    id: Mapped[int] = mapped_column(primary_key=True)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    # SHA-256 hash of the raw key. The raw key is shown once at creation time.
    key_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    # First 8 chars of the raw key, for identification in the admin UI only.
    key_prefix: Mapped[Optional[str]] = mapped_column(String(12))
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_used_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
