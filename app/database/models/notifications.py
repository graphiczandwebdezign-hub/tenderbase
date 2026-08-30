"""User, preference, device token and notification event models.

Deliberately minimal: no billing, no subscriptions, no teams, no profiles
beyond what push notifications require. A "user" is effectively a device owner
identified by a client-supplied opaque id.
"""
from __future__ import annotations

import enum
from datetime import datetime
from typing import List, Optional

from sqlalchemy import (
    String,
    Boolean,
    DateTime,
    ForeignKey,
    UniqueConstraint,
    Enum as SAEnum,
    Text,
    Index,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.database import Base


class NotificationType(str, enum.Enum):
    NEW_TENDER = "NEW_TENDER"
    TENDER_AMENDED = "TENDER_AMENDED"
    DEADLINE_REMINDER = "DEADLINE_REMINDER"


class NotificationEventStatus(str, enum.Enum):
    PENDING = "PENDING"
    SENT = "SENT"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    # Opaque client id supplied by the Android app (e.g. an install UUID).
    client_id: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    preferences: Mapped[List["UserPreference"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    tokens: Mapped[List["NotificationToken"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    saved_tenders: Mapped[List["SavedTender"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    saved_searches: Mapped[List["SavedSearch"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class UserPreference(Base):
    __tablename__ = "user_preferences"
    __table_args__ = (
        UniqueConstraint(
            "user_id", "category_id", "province_id", name="uq_user_pref"
        ),
        Index("ix_user_pref_category", "category_id"),
        Index("ix_user_pref_province", "province_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # NULL category means "any category"; NULL province means "any province".
    category_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("categories.id", ondelete="CASCADE")
    )
    province_id: Mapped[Optional[int]] = mapped_column(
        ForeignKey("provinces.id", ondelete="CASCADE")
    )
    notifications_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    user: Mapped["User"] = relationship(back_populates="preferences")


class NotificationToken(Base):
    __tablename__ = "notification_tokens"
    __table_args__ = (Index("ix_tokens_user", "user_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    # FCM registration token. Unique so re-registration updates in place.
    device_token: Mapped[str] = mapped_column(String(512), unique=True, nullable=False)
    platform: Mapped[str] = mapped_column(String(32), default="android")
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    user: Mapped["User"] = relationship(back_populates="tokens")


class NotificationEvent(Base):
    """One row per (user, tender, type). The uniqueness constraint prevents a
    user from being notified twice about the same event."""

    __tablename__ = "notification_events"
    __table_args__ = (
        UniqueConstraint(
            "user_id", "tender_id", "notification_type", name="uq_notification_dedup"
        ),
        Index("ix_notif_events_tender", "tender_id"),
        Index("ix_notif_events_user", "user_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    tender_id: Mapped[int] = mapped_column(
        ForeignKey("tenders.id", ondelete="CASCADE"), nullable=False
    )
    notification_type: Mapped[NotificationType] = mapped_column(
        SAEnum(NotificationType, native_enum=False, length=32), nullable=False
    )
    status: Mapped[NotificationEventStatus] = mapped_column(
        SAEnum(NotificationEventStatus, native_enum=False, length=16),
        default=NotificationEventStatus.PENDING,
        nullable=False,
    )
    detail: Mapped[Optional[str]] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    sent_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))


class SavedTender(Base):
    """Server-side saved tenders. Present so deadline reminders can target only
    tenders a user explicitly saved. Kept minimal for V1."""

    __tablename__ = "saved_tenders"
    __table_args__ = (
        UniqueConstraint("user_id", "tender_id", name="uq_saved_tender"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    tender_id: Mapped[int] = mapped_column(
        ForeignKey("tenders.id", ondelete="CASCADE"), nullable=False
    )
    reminders_enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    # Bid workspace backup (Sprint 6): free-form note + checklist state,
    # synced from the device so a reinstall/new device can restore them.
    note: Mapped[Optional[str]] = mapped_column(Text)
    checklist_json: Mapped[Optional[str]] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    user: Mapped["User"] = relationship(back_populates="saved_tenders")
