# South African Tender API Server

A small, reliable, **API-first backend** that continuously collects current
South African tender opportunities, keeps the database fresh, and exposes them
through a versioned REST API — purpose-built as the backend for a future
Android tender-notification app.

It is **not** a SaaS product: there is no billing, no subscriptions, no
multi-tenancy, no CRM, no bidding/evaluation, and no AI. The system does one
job well:

```
FIND → NOTIFY → SHOW DEADLINE → DOWNLOAD → SAVE → APPLY
```

```
OFFICIAL SOURCE → INGEST → NORMALIZE → DEDUPLICATE → POSTGRESQL → API → ANDROID
                                                                   │
                                                     new match → FCM push → device
```

---

## Table of contents

- [Quick start (Docker)](#quick-start-docker)
- [Quick start (local, no Docker)](#quick-start-local-no-docker)
- [Data source (eTenders / OCDS)](#data-source-etenders--ocds)
- [Architecture](#architecture)
- [Configuration](#configuration)
- [API overview](#api-overview)
- [Authentication](#authentication)
- [Ingestion, dedup, amendments, expiry](#ingestion-dedup-amendments--expiry)
- [Notifications & FCM](#notifications--fcm)
- [Admin API & dashboard](#admin-api--dashboard)
- [Database & migrations](#database--migrations)
- [Testing](#testing)
- [Production deployment](#production-deployment)
- [Backups](#backups)
- [Acceptance test](#acceptance-test)
- [Technology choices](#technology-choices)

---

## Quick start (Docker)

One command starts PostgreSQL, the API (with its in-process scheduler), and
Nginx:

```bash
cp .env.example .env         # then edit API_KEY, ADMIN_SECRET, etc.
docker compose up --build
```

- API: `http://localhost:8000` (direct) or `http://localhost/` (via Nginx)
- Swagger docs: `http://localhost:8000/docs`
- Admin dashboard: `http://localhost:8000/admin`
- Health: `http://localhost:8000/health`

The container entrypoint waits for PostgreSQL, runs Alembic migrations, then
starts the server. On first boot the taxonomy (categories/provinces) is seeded
and your bootstrap `API_KEY` is registered (hashed).

Trigger the first ingestion:

```bash
curl -X POST -H "X-Admin-Secret: <ADMIN_SECRET>" http://localhost:8000/api/v1/admin/sync
```

Or load development sample data (works offline):

```bash
docker compose run --rm api seed
```

---

## Quick start (local, no Docker)

Requires Python 3.11+. PostgreSQL is recommended but **SQLite is the default**
so it runs anywhere out of the box.

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

cp .env.example .env         # the defaults use sqlite:///./tenderbase.db

# Create the schema
alembic upgrade head

# (optional) load realistic development sample tenders
python scripts/generate_sample.py     # regenerate the sample file
python scripts/seed.py                # ingest it through the real pipeline

# Run the server
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Handy management commands:

```bash
python scripts/manage.py migrate       # alembic upgrade head
python scripts/manage.py seed          # load sample data
python scripts/manage.py sync          # run one ingestion cycle now
python scripts/manage.py cleanup       # run expiry + cleanup now
python scripts/manage.py create-key "android-app"
python scripts/manage.py stats
```

---

## Data source (eTenders / OCDS)

The initial source is the **South African National Treasury (OCPO) eTenders
Open Contracting (OCDS) Release API**:

```
https://ocds-api.etenders.gov.za/api/OCDSReleases
```

It's a paginated OCDS *release package* endpoint. Query parameters used by the
adapter:

| Param        | Meaning                              |
|--------------|--------------------------------------|
| `PageNumber` | 1-based page index                   |
| `PageSize`   | records per page                     |
| `dateFrom`   | inclusive lower bound (`yyyy-mm-dd`) |
| `dateTo`     | inclusive upper bound (`yyyy-mm-dd`) |

Each release carries a `tender` object (title, description, status,
`tenderPeriod.endDate` = the closing instant, `mainProcurementCategory`,
`documents`) plus `parties`/`buyer` for the procuring entity. Data is licensed
CC-BY 4.0. See the publication policy: <https://data.etenders.gov.za/Home/LearnMore>.

All source-specific logic lives in `app/sources/etenders.py` behind the
`TenderSourceAdapter` interface (`app/sources/base.py`) — never in the API
routes. Adding another source later (`OtherGovernmentSourceAdapter`,
`MunicipalitySourceAdapter`, …) means writing one new adapter class; the
ingestion pipeline, schema (`source` + `external_id`), and API are unchanged.

> **Network note.** The `.gov.za` host restricts access from some cloud/data-centre
> IP ranges (and was unreachable from the build sandbox). The adapter targets the
> **real** endpoint and does **not** fake success. When the live source is
> unreachable, the ingestion service applies its resilience policy: it **keeps all
> existing tenders**, logs the failure, marks the sync run `FAILED`, and — only in
> non-production and only when the DB is empty — loads the bundled development
> sample so the app is demonstrable. Set `INGESTION_ALLOW_SAMPLE_FALLBACK=false`
> to disable the fallback entirely. In production (`APP_ENV=production`) the
> fallback is always off.

---

## Architecture

```
tenderbase/
├── app/
│   ├── main.py                 # FastAPI app: routers, middleware, errors, lifespan
│   ├── api/
│   │   ├── routes/             # tenders, categories, provinces, notifications,
│   │   │                       #   preferences, health, admin
│   │   └── serializers.py      # model → stable mobile-friendly response
│   ├── core/                   # config, security, logging, normalization, timeutils
│   ├── database/
│   │   ├── database.py         # engine / session / Base
│   │   └── models/             # tenders, taxonomy, sync, auth, notifications
│   ├── schemas/                # Pydantic request/response models
│   ├── services/               # tender, ingestion, notification, expiry, document,
│   │                           #   taxonomy, fcm_client
│   ├── sources/                # base.py (adapter interface) + etenders.py
│   ├── workers/                # scheduler (APScheduler), sync_worker, cleanup_worker
│   └── admin/                  # server-rendered admin dashboard (Jinja2)
├── migrations/                 # Alembic
├── scripts/                    # seed, sample generator, backup, manage CLI
├── tests/                      # pytest suite
├── docker/                     # entrypoint + nginx config
├── docker-compose.yml, Dockerfile, .env.example, requirements.txt
```

The design principle:

```
                 TENDER API
       ┌─────────────┼─────────────┐
    INGEST        DATABASE       NOTIFY
       └─────────────┼─────────────┘
                  ANDROID
```

---

## Configuration

All configuration is environment-based (`.env`, never committed). See
[`.env.example`](.env.example) for the full list. Key variables:

| Variable | Default | Purpose |
|---|---|---|
| `APP_ENV` | `development` | `development` or `production` |
| `DATABASE_URL` | `sqlite:///./tenderbase.db` | Postgres in prod/Docker |
| `API_KEY` | – | Bootstrap application key (hashed into `api_keys`) |
| `ADMIN_SECRET` | – | Admin API + dashboard secret |
| `SYNC_INTERVAL_MINUTES` | `15` | Scheduled sync cadence |
| `SYNC_ENABLED` | `true` | Toggle the in-process scheduler |
| `TENDER_RETENTION_DAYS` | `7` | Keep expired tenders this long before deletion |
| `CLEANUP_INTERVAL_HOURS` | `24` | Cleanup/expiry cadence |
| `CLOSING_SOON_HOURS` | `48` | Window for `CLOSING_SOON` state |
| `RATE_LIMIT_PER_MINUTE` | `100` | Per-key (or per-IP) rate limit |
| `CORS_ORIGINS` | `*` | Comma-separated allowlist |
| `FCM_ENABLED` | `false` | Enable Firebase push |
| `FCM_PROJECT_ID` / `FCM_PRIVATE_KEY` / `FCM_CLIENT_EMAIL` | – | FCM service-account creds |
| `REMINDER_OFFSETS_HOURS` | `168,72,24,3` | Deadline reminder windows |
| `INGESTION_ALLOW_SAMPLE_FALLBACK` | `true` | Dev-only offline fallback |

Secrets (`DATABASE_URL`, API keys, FCM credentials, `ADMIN_SECRET`) are never
hard-coded and never logged.

---

## API overview

Base path: **`/api/v1/`** (versioned; future `/api/v2/` won't break clients).
Full interactive docs at **`/docs`** (Swagger) and **`/redoc`**.

### Public (no key)
```
GET  /health
GET  /api/v1/health
```

### Tenders (require `X-API-Key`)
```
GET  /api/v1/tenders                 # list + filter (default: active only)
GET  /api/v1/tenders/latest
GET  /api/v1/tenders/closing-soon
GET  /api/v1/tenders/search?q=...
GET  /api/v1/tenders/{id}            # detail incl. documents + amendments
```

**Filtering** (combine freely):
```
?category=construction
?province=KwaZulu-Natal
?category=construction&province=KwaZulu-Natal
?organisation=eThekwini
?status=CLOSED
?closing_within=24h            # or 7d
?closing_after=2026-09-01&closing_before=2026-09-30
?advertised_after=2026-08-01
?search=software
?page=1&limit=25
```

**Paginated response envelope:**
```json
{
  "data": [ /* tenders */ ],
  "pagination": { "page": 1, "limit": 25, "total": 1284, "total_pages": 52 }
}
```

**Tender object (mobile-friendly, no DB internals):**
```json
{
  "id": 18291,
  "tender_number": "ABC/2026/27",
  "ocid": "ocds-9nqbfw-...",
  "title": "Supply and Delivery of Equipment",
  "description": "...",
  "organisation": "Example Municipality",
  "province": "KwaZulu-Natal",
  "category": "Supplies",
  "categories": ["supplies"],
  "status": "ACTIVE",
  "deadline_state": "CLOSING_SOON",
  "advertised_date": "2026-08-28",
  "closing_date": "2026-09-15",
  "closing_time": "12:00:00",
  "closing_at": "2026-09-15T10:00:00Z",
  "source": "eTenders",
  "source_url": "https://www.etenders.gov.za/...",
  "documents": [ { "id": 1, "title": "Tender Document", "url": "...", "type": "PDF" } ]
}
```

`deadline_state` (`ACTIVE` / `CLOSING_SOON` / `CLOSED` / `EXPIRED` /
`CANCELLED`) is computed **server-side** from `closing_at` — clients must not
rely on the device clock.

### Taxonomy
```
GET  /api/v1/categories
GET  /api/v1/provinces
```

### Notifications / device / preferences
```
POST   /api/v1/notifications/register-device      { client_id, device_token, platform }
DELETE /api/v1/notifications/unregister-device     { device_token }
POST   /api/v1/notifications/saved                 { client_id, tender_id, reminders_enabled }
GET    /api/v1/preferences?client_id=...
PUT    /api/v1/preferences                         { client_id, preferences: [{category, province}] }
```

### Consistent errors
```json
{ "error": { "code": "INVALID_API_KEY", "message": "API key is invalid" } }
```
Stack traces and internals are never exposed.

---

## Authentication

Application endpoints require an API key header:

```
X-API-Key: <your key>
```

Missing/invalid/expired keys return `401` with a stable error `code`. Keys are
stored **only as SHA-256 hashes** in the `api_keys` table (raw keys are shown
once at creation). The bootstrap `API_KEY` from the environment is ensured on
startup; more keys are created/revoked via the admin API or
`scripts/manage.py create-key`.

Admin endpoints require `X-Admin-Secret: <ADMIN_SECRET>`.

Rate limiting is per-API-key (falling back to per-IP), default 100 req/min,
configurable via `RATE_LIMIT_PER_MINUTE`; excess returns `429 RATE_LIMITED`.

---

## Ingestion, dedup, amendments & expiry

Pipeline (`app/services/ingestion_service.py`):

```
FETCH → VALIDATE → PARSE → NORMALIZE → DEDUPLICATE → UPSERT
      → DETECT CHANGES → IDENTIFY NEW → TRIGGER NOTIFICATIONS
```

- **Idempotent.** Uniqueness on `(source, external_id)` and `(source, ocid)`
  means re-running never creates duplicates — existing rows are updated.
- **New-tender detection.** A `NEW_TENDER` notification fires only the first
  time a tender is seen (`first_seen_at`), never on subsequent syncs.
- **Amendment detection.** Changes to important fields (closing date/time,
  title, description, organisation, status, submission method) are recorded in
  `tender_amendments` (`field_changed`, `old_value`, `new_value`, `detected_at`)
  and the tender is flagged `AMENDED` (fires `TENDER_AMENDED`).
- **Statuses:** `ACTIVE`, `CLOSED`, `CANCELLED`, `AMENDED`, `EXPIRED`. The
  default feed returns only `ACTIVE`/`AMENDED` — expired tenders never appear
  in the latest feed.
- **Expiry.** Past the closing instant → `CLOSED`. After
  `TENDER_RETENTION_DAYS` → `EXPIRED`, then deleted by the cleanup job. The
  cleanup **never** deletes active tenders or tenders whose deadline hasn't
  passed.
- **Resilience.** A source failure never deletes existing data (see the data
  source note above).

Scheduling (`app/workers/scheduler.py`, APScheduler): sync every
`SYNC_INTERVAL_MINUTES`, cleanup every `CLEANUP_INTERVAL_HOURS`. Overlapping
sync jobs are prevented by a process-level lock (`sync_worker`). Every run is
logged to `sync_runs` (`RUNNING`/`SUCCESS`/`PARTIAL`/`FAILED` with counts).

---

## Notifications & FCM

```
new tender → match category & province → find users → create event → FCM push → device
```

- **Matching** uses a user's `user_preferences`. A preference with a `NULL`
  category means "any category" (same for province). A tender matches on **any**
  of its normalized categories.
- **Duplicate prevention.** `notification_events` has a unique constraint on
  `(user_id, tender_id, notification_type)`, so a user is never alerted twice
  for the same event.
- **Types:** `NEW_TENDER` (MVP priority), `TENDER_AMENDED`, `DEADLINE_REMINDER`.
  Deadline reminders are sent only for **saved** tenders with reminders enabled
  (no spam), at the `REMINDER_OFFSETS_HOURS` windows.
- **FCM.** `app/services/fcm_client.py` uses `firebase-admin`, initialised
  lazily from env credentials. Push works even when the Android app is closed
  (delivered by Play services). When FCM is disabled/unconfigured (the default),
  matching and event creation still run end-to-end; only the actual send is a
  graceful no-op — so you can develop the whole flow without Firebase.

---

## Admin API & dashboard

Admin API (header `X-Admin-Secret`), strictly for data-infrastructure
maintenance — monitor, sync, inspect, correct, remove, troubleshoot:

```
GET    /api/v1/admin/dashboard        # counters (active, new today, closing today/tomorrow, expired, ...)
GET    /api/v1/admin/sync-status
POST   /api/v1/admin/sync             # manual sync (409 if one is running)
GET    /api/v1/admin/sync-runs        # history
GET    /api/v1/admin/tenders          # list all (any status)
PATCH  /api/v1/admin/tenders/{id}     # correct status/title/category/province
DELETE /api/v1/admin/tenders/{id}     # remove a problematic tender
GET    /api/v1/admin/api-keys         # list keys
POST   /api/v1/admin/api-keys         # generate a key (raw shown once)
DELETE /api/v1/admin/api-keys/{id}    # revoke
```

Web dashboard at **`/admin`** (cookie login with the admin secret) shows active
tenders, new today, closing today/tomorrow, expired, last successful sync, sync
status, and recent sync runs (records imported/updated/errors), with a
"Run sync now" button.

---

## Database & migrations

PostgreSQL (production) via SQLAlchemy 2.0 + Alembic. SQLite is supported for
local/dev (default). Schema highlights:

- `tenders` (+ `tender_documents`, `tender_amendments`, `tender_categories`)
- `categories`, `provinces` (normalized taxonomy)
- `sync_runs` (observability)
- `api_keys` (hashed)
- `users`, `user_preferences`, `notification_tokens`, `notification_events`,
  `saved_tenders`

Timezone-safe: `closing_at` is stored in UTC (`TIMESTAMPTZ`); split
`closing_date`/`closing_time` are kept for display. Indexes exist on
`external_id`, `ocid`, `status`, `category`, `province`, `closing_at`,
`advertised_date`, `organisation`, `first_seen_at`.

```bash
alembic upgrade head        # create / migrate
alembic downgrade -1        # roll back
alembic revision --autogenerate -m "change"   # new migration
```

---

## Testing

```bash
pip install -r requirements.txt -r requirements-dev.txt
pytest
```

The suite (29 tests) covers API-key auth, tender retrieval, pagination, search,
category/province/closing filters, deduplication, updates, **running ingestion
twice creates no duplicates**, expiry, amendment detection, notification
matching (positive + negative), duplicate-notification prevention, device
registration, preferences, and the admin API. Tests use an isolated temporary
SQLite DB and a `MockSourceAdapter` (no network, deterministic).

---

## Deploy to Render (GitHub → managed Postgres, no local machine)

The repo ships a **Render Blueprint** (`render.yaml`) that provisions a managed
PostgreSQL database and a web service, wires `DATABASE_URL` automatically, and
generates `API_KEY` / `ADMIN_SECRET` for you.

1. Push this branch to GitHub (already done for `arena/01a04c59-tenderbase`).
2. In [Render](https://render.com): **New → Blueprint** → connect this repo →
   **Apply**. Render reads `render.yaml`, creates `tenderbase-db` and
   `tenderbase-api`, runs `alembic upgrade head`, and starts the server.
3. When it's live, grab the generated secrets from the service's **Environment**
   tab (`API_KEY`, `ADMIN_SECRET`), then load data:
   ```bash
   curl -X POST -H "X-Admin-Secret: <ADMIN_SECRET>" \
     https://<your-service>.onrender.com/api/v1/admin/sync
   ```
   Open `https://<your-service>.onrender.com/docs`.

`DATABASE_URL` is normalized automatically: managed hosts that emit
`postgres://` or driver-less `postgresql://` URLs (Render, Railway, Heroku,
Neon, Supabase) are rewritten to `postgresql+psycopg2://` — no hand-editing.

> ⚠️ **Free plan sleeps when idle**, which pauses the in-process scheduler. For
> continuous syncing either upgrade the web service to a paid always-on
> instance, or set `SYNC_ENABLED=false` and add a cron (e.g. cron-job.org) that
> calls `POST /api/v1/admin/sync` every 15 minutes.

The same pattern works on **Railway**, **Fly.io**, or any host that builds from
GitHub — point it at the repo, attach a Postgres add-on, and set the same
environment variables.

## Production deployment

Recommended single-VPS topology:

```
Internet → Nginx (TLS) → FastAPI (Uvicorn) → PostgreSQL
                          └ in-process APScheduler → ingestion/cleanup
```

Steps:

1. **Server**: a small VPS (2 vCPU / 2–4 GB RAM) with Docker + Docker Compose.
2. **DNS**: point `api.yourdomain.co.za` at the VPS.
3. **Config**: `cp .env.example .env`, set `APP_ENV=production`, a strong
   `API_KEY` and `ADMIN_SECRET`, a Postgres `DATABASE_URL`, `CORS_ORIGINS`, and
   FCM credentials if using push.
4. **Start**: `docker compose up -d --build` (entrypoint waits for the DB and
   runs migrations automatically).
5. **HTTPS**: obtain certs (e.g. certbot/Let's Encrypt) and enable the `443`
   server block in `docker/nginx/nginx.conf` (uncomment + point at your certs;
   redirect `80 → 443`).
6. **Scheduled sync**: runs in-process by default. To scale the API to multiple
   replicas, set `SYNC_ENABLED=false` on the API and run one `worker` container
   (see the commented service in `docker-compose.yml`).
7. **Restart policy**: services use `restart: unless-stopped`.
8. **Logs**: structured JSON to stdout — collect with `docker logs` or ship to
   your log stack. API keys/tokens/credentials are never logged.
9. **Backups**: see below.

Health/freshness: `GET /health` reports DB status and the last successful sync
time so the Android app (and your monitoring) can tell whether the backend is
current.

---

## Backups

`scripts/backup.sh` performs a compressed `pg_dump` and prunes old dumps:

```bash
DATABASE_URL=postgresql://... BACKUP_DIR=/var/backups BACKUP_KEEP_DAYS=14 \
  ./scripts/backup.sh
```

Schedule daily via cron:

```cron
0 2 * * * DATABASE_URL=postgresql://... /app/scripts/backup.sh >> /var/log/tender-backup.log 2>&1
```

The API does not depend on backups being run manually — they are an operational
safety net.

---

## Acceptance test

The end-to-end workflow in the build spec (start DB → migrate → ingest → query →
filter → detail → verify closing date → re-ingest with no duplicates →
amendment detection → expiry → register device → preferences → match new tender
→ dedup notifications → manual sync → sync logs → health → dashboard → restart
recovery) is exercised by the pytest suite and reproducible against a running
server. Quick manual walkthrough:

```bash
# 1–3: bring it up + migrate (Docker does this automatically)
docker compose up -d --build

# 4–5: ingest (live source, or sample fallback offline)
curl -X POST -H "X-Admin-Secret: $ADMIN_SECRET" localhost:8000/api/v1/admin/sync

# 6–12: query / filter / detail
curl -H "X-API-Key: $API_KEY" localhost:8000/api/v1/tenders
curl -H "X-API-Key: $API_KEY" "localhost:8000/api/v1/tenders?category=construction"
curl -H "X-API-Key: $API_KEY" "localhost:8000/api/v1/tenders?province=KwaZulu-Natal"
curl -H "X-API-Key: $API_KEY" localhost:8000/api/v1/tenders/1

# 13–14: re-ingest → no duplicates (verify total is unchanged)
# 19–23: device + preferences + matching (see Notifications)
# 24–27: manual sync, sync logs, health, dashboard
curl -H "X-Admin-Secret: $ADMIN_SECRET" localhost:8000/api/v1/admin/sync-runs
curl localhost:8000/health

# 28–29: restart recovery
docker compose restart && curl localhost:8000/health
```

---

## Technology choices

- **FastAPI + Uvicorn** — async, first-class OpenAPI/Swagger so an Android dev
  can build the app straight from `/docs`.
- **PostgreSQL + SQLAlchemy 2.0 + Alembic** — robust relational store with
  proper constraints (dedup, notification uniqueness) and versioned migrations.
  SQLite is wired up as a zero-config local/dev default.
- **APScheduler** — simple in-process scheduling that runs on one VPS today; the
  worker entry points are isolated so a move to Celery/RQ + Redis later touches
  only `app/workers/`.
- **Pydantic v2** — validation and stable response schemas.
- **slowapi** — lightweight rate limiting.
- **firebase-admin** — FCM push foundation, lazily initialised and fully
  optional. It is **not** in `requirements.txt` (its native `grpcio` dependency
  can exceed free-tier build limits); install it only when enabling push:
  `pip install -r requirements.txt -r requirements-fcm.txt` and set
  `FCM_ENABLED=true`. The app runs normally without it.

Deliberately **excluded** (out of scope for V1): AI features, subscriptions/
billing/multi-tenancy/CRM, bidding/evaluation, social features, and heavy
caching. Redis is optional and not required.
```
