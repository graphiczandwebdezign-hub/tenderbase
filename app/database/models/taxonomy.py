"""Category and province taxonomy models."""
from __future__ import annotations

from datetime import datetime
from typing import List

from sqlalchemy import String, ForeignKey, UniqueConstraint, DateTime, func, Index
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.database import Base


class Category(Base):
    __tablename__ = "categories"

    id: Mapped[int] = mapped_column(primary_key=True)
    slug: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(128), nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    tender_links: Mapped[List["TenderCategory"]] = relationship(back_populates="category")


class Province(Base):
    __tablename__ = "provinces"

    id: Mapped[int] = mapped_column(primary_key=True)
    slug: Mapped[str] = mapped_column(String(64), unique=True, nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(64), nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class TenderCategory(Base):
    """Many-to-many between tenders and normalized categories.

    A tender may carry more than one normalized category where the source data
    supports it.
    """

    __tablename__ = "tender_categories"
    __table_args__ = (
        UniqueConstraint("tender_id", "category_id", name="uq_tender_category"),
        Index("ix_tender_categories_category", "category_id"),
    )

    id: Mapped[int] = mapped_column(primary_key=True)
    tender_id: Mapped[int] = mapped_column(
        ForeignKey("tenders.id", ondelete="CASCADE"), nullable=False
    )
    category_id: Mapped[int] = mapped_column(
        ForeignKey("categories.id", ondelete="CASCADE"), nullable=False
    )

    tender: Mapped["Tender"] = relationship(back_populates="categories")
    category: Mapped["Category"] = relationship(back_populates="tender_links")
