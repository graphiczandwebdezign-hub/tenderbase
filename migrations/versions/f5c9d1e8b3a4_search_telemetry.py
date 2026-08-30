"""anonymous search telemetry (Sprint 7)

Revision ID: f5c9d1e8b3a4
Revises: d4b8c3f7a9e2
Create Date: 2026-08-30 00:00:00.000000

Adds the ``search_events`` table powering admin discovery analytics: one
anonymous row per public list/search request (endpoint, query text, non-
default filters, result count). No user or device identifiers.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'f5c9d1e8b3a4'
down_revision: Union[str, None] = 'd4b8c3f7a9e2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'search_events',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('endpoint', sa.String(length=32), nullable=False),
        sa.Column('query_text', sa.Text(), nullable=True),
        sa.Column('filters_json', sa.Text(), nullable=True),
        sa.Column('results_count', sa.Integer(), nullable=False, server_default='0'),
        sa.Column(
            'created_at', sa.DateTime(timezone=True), server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index('ix_search_events_created', 'search_events', ['created_at'])


def downgrade() -> None:
    op.drop_index('ix_search_events_created', table_name='search_events')
    op.drop_table('search_events')
