"""Seed the database with development sample tenders.

Loads scripts/sample_tenders.json through the real ingestion pipeline (so the
same normalization/dedup/upsert path is exercised) and flags them is_sample.
Idempotent: running twice does not create duplicates.

Usage:  python scripts/seed.py
"""
from __future__ import annotations

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.database.database import session_scope, Base, engine
from app.core.config import settings
from app.services import taxonomy_service
from app.services.ingestion_service import IngestionService
from app.sources.etenders import ETendersSourceAdapter


def main() -> None:
    if not settings.is_postgres:
        Base.metadata.create_all(bind=engine)

    db = session_scope()
    try:
        taxonomy_service.seed_taxonomy(db)
        adapter = ETendersSourceAdapter()
        records = adapter.load_sample_raw()
        if not records:
            print("No sample records found. Run scripts/generate_sample.py first.")
            return

        cat_map = taxonomy_service.category_map(db)
        prov_map = taxonomy_service.province_map(db)
        ing = IngestionService(db, adapter)

        created = updated = 0
        for raw in records:
            normalized = adapter.normalize_tender(raw)
            if not normalized:
                continue
            outcome, _ = ing._upsert(normalized, cat_map, prov_map, is_sample=True)
            if outcome == "created":
                created += 1
            else:
                updated += 1
        ing.refresh_statuses()
        db.commit()
        print(f"Seed complete: {created} created, {updated} updated (development data).")
    finally:
        db.close()


if __name__ == "__main__":
    main()
