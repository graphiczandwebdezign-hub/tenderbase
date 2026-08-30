"""saved searches (Sprint 2)

Revision ID: c8f2a6d4e1b7
Revises: b7c4e9f1a2d3
Create Date: 2026-08-30 00:00:00.000000

Creates the ``saved_searches`` table: persisted discovery queries per user
(device owner) with an alerts toggle. Matching runs after each ingestion pass
and reuses the existing notification_events dedup, so no schema support is
needed beyond the table itself.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'c8f2a6d4e1b7'
down_revision: Union[str, None] = 'b7c4e9f1a2d3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'saved_searches',
        sa.Column('id', sa.Integer(), nullable=False),
        sa.Column('user_id', sa.Integer(), nullable=False),
        sa.Column('name', sa.String(length=128), nullable=False),
        sa.Column('params_json', sa.Text(), nullable=False),
        sa.Column('alerts_enabled', sa.Boolean(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True),
                  server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True),
                  server_default=sa.text('(CURRENT_TIMESTAMP)'), nullable=False),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('user_id', 'name', name='uq_saved_search_name'),
    )
    with op.batch_alter_table('saved_searches', schema=None) as batch_op:
        batch_op.create_index('ix_saved_searches_user', ['user_id'])


def downgrade() -> None:
    op.drop_index('ix_saved_searches_user', table_name='saved_searches')
    op.drop_table('saved_searches')
