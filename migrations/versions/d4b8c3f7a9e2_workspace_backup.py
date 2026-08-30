"""workspace backup on saved tenders (Sprint 6)

Revision ID: d4b8c3f7a9e2
Revises: c8f2a6d4e1b7
Create Date: 2026-08-30 00:00:00.000000

Adds ``note`` and ``checklist_json`` to ``saved_tenders`` so the Android bid
workspace (note + checklist per tender) can back up to the server and be
restored on a reinstall or new device. Uses batch_alter_table for SQLite
compatibility.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = 'd4b8c3f7a9e2'
down_revision: Union[str, None] = 'c8f2a6d4e1b7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    with op.batch_alter_table('saved_tenders', schema=None) as batch_op:
        batch_op.add_column(sa.Column('note', sa.Text(), nullable=True))
        batch_op.add_column(sa.Column('checklist_json', sa.Text(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('saved_tenders', schema=None) as batch_op:
        batch_op.drop_column('checklist_json')
        batch_op.drop_column('note')
