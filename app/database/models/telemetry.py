"""Anonymous discovery telemetry: what users search and filter for."""
from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Index, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database.database import Base


class SearchEvent(Base):
    """One discovery request against the public list/search endpoints.

    Deliberately anonymous — no user/client identifier is stored. The goal is
    aggregate insight (top terms, facet usage, zero-result searches) to guide
    data-quality work, never individual tracking.

    ``filters_json`` holds only the non-default filters of the request
    (``search`` is kept separately in ``query_text``).
    """

    __tablename__ = "search_events"
    __table_args__ = (Index("ix_search_events_created", "created_at"),)

    id: Mapped[int] = mapped_column(primary_key=True)
    endpoint: Mapped[str] = mapped_column(String(32), nullable=False)
    query_text: Mapped[str | None] = mapped_column(Text)
    filters_json: Mapped[str | None] = mapped_column(Text)
    results_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
