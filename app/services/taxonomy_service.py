"""Seeding and lookup helpers for categories and provinces."""
from __future__ import annotations

from typing import Dict

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core import normalization as norm
from app.database.models.taxonomy import Category, Province


def seed_taxonomy(db: Session) -> None:
    """Idempotently ensure all canonical categories and provinces exist."""
    existing_cats = {c.slug for c in db.execute(select(Category)).scalars()}
    for slug, name in norm.CATEGORIES:
        if slug not in existing_cats:
            db.add(Category(slug=slug, name=name))
    existing_provs = {p.slug for p in db.execute(select(Province)).scalars()}
    for slug, name in norm.PROVINCES:
        if slug not in existing_provs:
            db.add(Province(slug=slug, name=name))
    db.commit()


def category_map(db: Session) -> Dict[str, Category]:
    return {c.slug: c for c in db.execute(select(Category)).scalars()}


def province_map(db: Session) -> Dict[str, Province]:
    return {p.slug: p for p in db.execute(select(Province)).scalars()}
