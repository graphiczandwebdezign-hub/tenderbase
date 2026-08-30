"""discovery sort/filter indexes

Revision ID: b7c4e9f1a2d3
Revises: a61e2bb7d2a3
Create Date: 2026-08-30 00:00:00.000000

Adds the two indexes used by Sprint 1 discovery sorting/filtering:

- tenders.updated_at  -> sort=updated ("Recently updated")
- tenders.closing_date -> closing_before / closing_after date filters

Both already-existing query paths relied on scans; these are the only new
access patterns Sprint 1 introduces. ILIKE free-text search with a leading
wildcard cannot use B-tree indexes and is intentionally left unindexed at
this dataset size (10k rows); a pg_trgm GIN index is the documented next
step if search latency ever warrants it.
"""
from typing import Sequence, Union

from alembic import op


revision: str = 'b7c4e9f1a2d3'
down_revision: Union[str, None] = 'a61e2bb7d2a3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_index('ix_tenders_updated_at', 'tenders', ['updated_at'])
    op.create_index('ix_tenders_closing_date', 'tenders', ['closing_date'])


def downgrade() -> None:
    op.drop_index('ix_tenders_updated_at', table_name='tenders')
    op.drop_index('ix_tenders_closing_date', table_name='tenders')
