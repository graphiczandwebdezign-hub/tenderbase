"""Generate a new API key and store its hash. Prints the raw key ONCE.

Usage:  python scripts/create_api_key.py "android-app"
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.security import generate_api_key, hash_key
from app.database.database import session_scope, Base, engine
from app.core.config import settings
from app.database.models import ApiKey


def main() -> None:
    name = sys.argv[1] if len(sys.argv) > 1 else "api-key"
    if not settings.is_postgres:
        Base.metadata.create_all(bind=engine)
    db = session_scope()
    try:
        raw = generate_api_key()
        db.add(ApiKey(name=name, key_hash=hash_key(raw), key_prefix=raw[:8], active=True))
        db.commit()
        print("API key created (store it now, it will not be shown again):")
        print(raw)
    finally:
        db.close()


if __name__ == "__main__":
    main()
