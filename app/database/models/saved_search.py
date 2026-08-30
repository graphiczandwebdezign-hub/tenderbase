"""Saved searches: persisted discovery queries with optional alerts."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    String,
    Text,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.database import Base


class SavedSearch(Base):
    """A persisted discovery query for a user (device owner).

    ``params_json`` holds the validated list-endpoint filter parameters
    exactly as the client applies them (search, province, category, source,
    status, closing_within, closing_after/before, advertised_after/before)
    so alert matching mirrors the discovery semantics one-to-one. ``sort`` is
    presentation-only and never stored.

    Duplicate prevention for alerts is inherited from the notification_events
    unique constraint on (user_id, tender_id, notification_type): a user is
    alerted once per tender regardless of how many searches match.
    """

    __tablename__ = "saved_searches"
    __table_args__ = (
        UniqueConstraint("user_id", "name", name="uq_saved_search_name"),
        Index("ix_saved_searches_user", "user_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    params_json: Mapped[str] = mapped_column(Text, nullable=False)
    alerts_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    user: Mapped["User"] = relationship(back_populates="saved_searches")
