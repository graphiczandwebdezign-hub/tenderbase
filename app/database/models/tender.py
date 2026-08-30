"""Tender, document and amendment models."""
from __future__ import annotations

import enum
from datetime import datetime, date, time
from typing import List, Optional

from sqlalchemy import (
    String,
    Text,
    Integer,
    BigInteger,
    DateTime,
    Date,
    Time,
    ForeignKey,
    UniqueConstraint,
    Index,
    Enum as SAEnum,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.database import Base


class TenderStatus(str, enum.Enum):
    ACTIVE = "ACTIVE"
    CLOSED = "CLOSED"
    CANCELLED = "CANCELLED"
    AMENDED = "AMENDED"
    EXPIRED = "EXPIRED"


class Tender(Base):
    __tablename__ = "tenders"
    __table_args__ = (
        # Deduplication constraints: a tender is unique per source by its
        # external id and (when present) its ocid.
        UniqueConstraint("source", "external_id", name="uq_tender_source_external"),
        Index("ix_tenders_source_ocid", "source", "ocid"),
        Index("ix_tenders_status", "status"),
        Index("ix_tenders_category", "category"),
        Index("ix_tenders_province", "province"),
        Index("ix_tenders_closing_at", "closing_at"),
        Index("ix_tenders_advertised_date", "advertised_date"),
        Index("ix_tenders_organisation", "organisation"),
        Index("ix_tenders_first_seen_at", "first_seen_at"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)

    # ---- provenance ----
    source: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    external_id: Mapped[str] = mapped_column(String(255), nullable=False)
    ocid: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)

    # ---- identity ----
    tender_number: Mapped[Optional[str]] = mapped_column(String(255))
    title: Mapped[str] = mapped_column(Text, nullable=False, default="")
    description: Mapped[Optional[str]] = mapped_column(Text)

    # ---- issuer ----
    organisation: Mapped[Optional[str]] = mapped_column(String(512))
    organisation_identifier: Mapped[Optional[str]] = mapped_column(String(255))
    province: Mapped[Optional[str]] = mapped_column(String(64))
    municipality: Mapped[Optional[str]] = mapped_column(String(255))

    # ---- classification ----
    category: Mapped[Optional[str]] = mapped_column(String(64))
    tender_type: Mapped[Optional[str]] = mapped_column(String(64))

    # ---- lifecycle ----
    status: Mapped[TenderStatus] = mapped_column(
        SAEnum(TenderStatus, native_enum=False, length=16),
        nullable=False,
        default=TenderStatus.ACTIVE,
    )

    # ---- dates (timezone-safe) ----
    advertised_date: Mapped[Optional[date]] = mapped_column(Date)
    # Human-facing split fields kept for the mobile client.
    closing_date: Mapped[Optional[date]] = mapped_column(Date)
    closing_time: Mapped[Optional[time]] = mapped_column(Time)
    # Canonical closing instant, always stored in UTC. Deadline logic uses this.
    closing_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))

    submission_method: Mapped[Optional[str]] = mapped_column(String(255))
    source_url: Mapped[Optional[str]] = mapped_column(Text)

    # ---- bookkeeping ----
    is_sample: Mapped[bool] = mapped_column(default=False, nullable=False)
    content_hash: Mapped[Optional[str]] = mapped_column(String(64))

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )
    first_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    expires_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))

    documents: Mapped[List["TenderDocument"]] = relationship(
        back_populates="tender", cascade="all, delete-orphan"
    )
    amendments: Mapped[List["TenderAmendment"]] = relationship(
        back_populates="tender", cascade="all, delete-orphan"
    )
    categories: Mapped[List["TenderCategory"]] = relationship(
        back_populates="tender", cascade="all, delete-orphan"
    )


class TenderDocument(Base):
    __tablename__ = "tender_documents"
    __table_args__ = (
        UniqueConstraint("tender_id", "url", name="uq_document_tender_url"),
        Index("ix_documents_tender", "tender_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    tender_id: Mapped[int] = mapped_column(
        ForeignKey("tenders.id", ondelete="CASCADE"), nullable=False
    )
    document_type: Mapped[Optional[str]] = mapped_column(String(64))
    title: Mapped[Optional[str]] = mapped_column(Text)
    url: Mapped[str] = mapped_column(Text, nullable=False)
    filename: Mapped[Optional[str]] = mapped_column(String(512))
    mime_type: Mapped[Optional[str]] = mapped_column(String(128))
    file_size: Mapped[Optional[int]] = mapped_column(BigInteger)
    checksum: Mapped[Optional[str]] = mapped_column(String(128))

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )

    tender: Mapped["Tender"] = relationship(back_populates="documents")


class TenderAmendment(Base):
    __tablename__ = "tender_amendments"
    __table_args__ = (Index("ix_amendments_tender", "tender_id"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    tender_id: Mapped[int] = mapped_column(
        ForeignKey("tenders.id", ondelete="CASCADE"), nullable=False
    )
    field_changed: Mapped[str] = mapped_column(String(64), nullable=False)
    old_value: Mapped[Optional[str]] = mapped_column(Text)
    new_value: Mapped[Optional[str]] = mapped_column(Text)
    detected_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    source: Mapped[Optional[str]] = mapped_column(String(64))

    tender: Mapped["Tender"] = relationship(back_populates="amendments")
