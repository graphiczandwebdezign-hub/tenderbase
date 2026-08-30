"""Discovery performance benchmark (dev tool).

Generates ~12k realistic tenders into a throwaway SQLite DB (benign.db in the
system temp dir) and times Sprint 1 discovery queries: search, sorting,
filtering, combined filters and facets. Run with the project venv:

    .venv/bin/python scripts/benchmark_discovery.py

Never touches production data."""
import os
import random
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.environ["DATABASE_URL"] = "sqlite:///" + os.path.join(tempfile.gettempdir(), "tenderbase_bench.db")
os.environ["API_KEY"] = "benchkey"
os.environ["SYNC_ENABLED"] = "false"

_BENCH_DB = os.path.join(tempfile.gettempdir(), "tenderbase_bench.db")
if os.path.exists(_BENCH_DB):
    os.remove(_BENCH_DB)

from app.database.database import Base, engine, SessionLocal
from app.database import models  # noqa
from app.database.models import Tender, TenderStatus
from app.services import taxonomy_service

Base.metadata.create_all(bind=engine)
db = SessionLocal()
taxonomy_service.seed_taxonomy(db)

random.seed(42)
PROVINCES = ["KwaZulu-Natal", "Gauteng", "Western Cape", "Limpopo", "Mpumalanga",
             "Eastern Cape", "Free State", "North West", "Northern Cape"]
CATEGORIES = ["Construction", "Information Technology", "Supplies", "Security",
              "Transport", "Professional Services", "Medical", "Engineering",
              "Cleaning", "Civil Works", "Electrical", "Other"]
ORGS = ["Department of Transport", "Department of Health", "Department of Education",
        "Department of Public Works", "City of Cape Town", "eThekwini Municipality",
        "Department of Water and Sanitation", "Transnet", "Eskom", "City of Johannesburg"]
WORDS = ["construction", "supply", "maintenance", "cleaning", "consulting", "installation",
         "refurbishment", "delivery", "upgrading", "repair", "security", "gardening"]
NOUNS = ["school buildings", "hospital equipment", "roads", "fleet vehicles", "offices",
         "water pipelines", "IT infrastructure", "electrical substations", "clinics",
         "sports facilities", "bridges", "streetlights"]

now = datetime.now(timezone.utc)
rows = []
for i in range(12_000):
    closing = now + timedelta(days=random.randint(-10, 120), hours=random.randint(0, 23))
    if random.random() < 0.12:
        closing = None
    advertised = now - timedelta(days=random.randint(0, 365))
    status = TenderStatus.ACTIVE
    if closing and closing < now:
        status = TenderStatus.CLOSED if random.random() < 0.7 else TenderStatus.EXPIRED
    rows.append(Tender(
        source="eTenders" if random.random() < 0.9 else "Manual",
        external_id=f"BENCH-{i}",
        ocid=f"ocds-bench-{i}",
        tender_number=f"KZN-2026-{i:05d}",
        title=f"{random.choice(WORDS).title()} and {random.choice(WORDS)} of {random.choice(NOUNS)}",
        description=f"Request for {random.choice(WORDS)} services for {random.choice(NOUNS)}.",
        organisation=f"{random.choice(PROVINCES)} {random.choice(ORGS)}",
        province=random.choice(PROVINCES),
        category=random.choice(CATEGORIES),
        status=status,
        advertised_date=advertised.date(),
        closing_date=closing.date() if closing else None,
        closing_at=closing,
        updated_at=now - timedelta(days=random.randint(0, 90)),
    ))
db.add_all(rows)
db.commit()
print(f"seeded {len(rows)} tenders")

from app.services.tender_service import TenderService

svc = TenderService(db)


def bench(label, **kw):
    t0 = time.perf_counter()
    rows, total = svc.list_tenders(**kw)
    dt = (time.perf_counter() - t0) * 1000
    print(f"{label:55s} total={total:>6} page={len(rows):>3}  {dt:7.1f} ms")


bench("default feed p1", page=1, limit=20)
bench("default feed p50", page=50, limit=20)
bench("search 'construction school'", page=1, limit=20, search="construction school")
bench("search 'KZN-2026-00' (reference prefix)", page=1, limit=20, search="KZN-2026-00")
bench("search multi-term", page=1, limit=20, search="department health equipment")
bench("search + relevance sort", page=1, limit=20, search="construction", order="relevance")
bench("sort=closing", page=1, limit=20, order="closing")
bench("sort=updated", page=1, limit=20, order="updated")
bench("province filter", page=1, limit=20, province="kwazulu-natal")
bench("multi province+category", page=1, limit=20, province="kwazulu-natal,gauteng", category="construction,medical")
bench("status=closing_soon", page=1, limit=20, status="closing_soon")
bench("status=closed", page=1, limit=20, status="closed")
bench("combined search+filters+sort", page=1, limit=20, search="construction",
      province="kwazulu-natal", category="construction", closing_within="7d", order="closing")
bench("advertised window", page=1, limit=20, advertised_after=(now - timedelta(days=7)).date())

t0 = time.perf_counter()
f = svc.facets()
print(f"{'facets':55s} prov={len(f['provinces'])} cat={len(f['categories'])} src={len(f['sources'])}  {(time.perf_counter()-t0)*1000:7.1f} ms")

db.close()
