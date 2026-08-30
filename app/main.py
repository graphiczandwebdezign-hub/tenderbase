"""FastAPI application entrypoint.

Wires routers, middleware (CORS, security headers, rate limiting), consistent
error handling, the background scheduler, and startup bootstrap (schema
creation for SQLite/dev, taxonomy seeding, API-key bootstrap).
"""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from slowapi import Limiter
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.config import settings
from app.core.logging import get_logger, log_event, setup_logging
from app.database.database import Base, engine, session_scope
from app.core.security import ensure_bootstrap_key
from app.services import taxonomy_service
from app.workers.scheduler import start_scheduler, shutdown_scheduler

# Import models so metadata is populated for create_all (dev/SQLite path).
from app.database import models  # noqa: F401

from app.api.routes import (
    tenders,
    categories,
    provinces,
    notifications,
    preferences,
    saved_searches,
    health,
    admin,
)
from app.admin import dashboard as admin_dashboard

logger = get_logger("app")
setup_logging("DEBUG" if settings.debug else "INFO")


def _rate_limit_key(request: Request) -> str:
    """Rate limit per API key when present, else per client IP."""
    api_key = request.headers.get("X-API-Key")
    if api_key:
        return f"key:{api_key[:16]}"
    return f"ip:{get_remote_address(request)}"


limiter = Limiter(
    key_func=_rate_limit_key,
    default_limits=[f"{settings.rate_limit_per_minute}/minute"] if settings.rate_limit_enabled else [],
    enabled=settings.rate_limit_enabled,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Dev/SQLite convenience: ensure schema exists. In production, Alembic
    # migrations own the schema (create_all is a harmless no-op if up to date).
    if not settings.is_postgres:
        Base.metadata.create_all(bind=engine)
    db = session_scope()
    try:
        taxonomy_service.seed_taxonomy(db)
        ensure_bootstrap_key(db)
    finally:
        db.close()
    start_scheduler()
    log_event(logger, 20, "app_started", env=settings.app_env, postgres=settings.is_postgres)
    try:
        yield
    finally:
        shutdown_scheduler()
        log_event(logger, 20, "app_stopped")


app = FastAPI(
    title="South African Tender API",
    version=settings.app_version,
    description=(
        "Backend that continuously ingests South African tender opportunities "
        "from the National Treasury eTenders OCDS source, normalizes and "
        "deduplicates them, and exposes them via a versioned, API-key protected "
        "REST API designed for an Android tender-notification app.\n\n"
        "**Authentication:** send your key in the `X-API-Key` header on all "
        "`/api/v1` endpoints (except `/health`). Admin endpoints require the "
        "`X-Admin-Secret` header."
    ),
    lifespan=lifespan,
    contact={"name": "Tender API"},
    license_info={"name": "Data: CC-BY 4.0 (National Treasury OCPO)"},
)

app.state.limiter = limiter

# --------------------------------------------------------------- middleware
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)


# ---------------------------------------------------- request context (S10)
# Correlation id for every request: honoured when supplied (sanitised),
# generated otherwise; echoed as a response header, attached to error bodies
# and logged with the structured access-log line.
import contextvars  # noqa: E402
import time as _time  # noqa: E402
import uuid as _uuid  # noqa: E402

request_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "request_id", default=None
)

_PROBE_PATHS = {"/health", "/ready", "/api/v1/health", "/api/v1/ready"}


def current_request_id() -> str | None:
    return request_id_var.get()


def _sanitize_request_id(raw: str | None) -> str:
    if raw and len(raw) <= 64 and all(c.isalnum() or c in "-_" for c in raw):
        return raw
    return _uuid.uuid4().hex[:16]


@app.middleware("http")
async def request_context(request: Request, call_next):
    rid = _sanitize_request_id(request.headers.get("x-request-id"))
    request_id_var.set(rid)
    started = _time.perf_counter()
    response = await call_next(request)
    duration_ms = round((_time.perf_counter() - started) * 1000, 1)
    response.headers["X-Request-ID"] = rid
    # Probe endpoints log at DEBUG so orchestrator healthchecks don't flood INFO.
    level = 10 if request.url.path in _PROBE_PATHS and response.status_code < 400 else 20
    log_event(
        logger,
        level,
        "http_request",
        request_id=rid,
        method=request.method,
        path=request.url.path,
        status=response.status_code,
        duration_ms=duration_ms,
    )
    return response


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault(
        "Strict-Transport-Security", "max-age=31536000; includeSubDomains"
    )
    return response


# ------------------------------------------------------------ error handling
def _error(status_code: int, code: str, message: str) -> JSONResponse:
    # request_id links an error payload to the structured access-log line.
    payload = {"code": code, "message": message}
    rid = current_request_id()
    if rid:
        payload["request_id"] = rid
    return JSONResponse(status_code=status_code, content={"error": payload})


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException):
    detail = exc.detail
    if isinstance(detail, dict) and "code" in detail:
        rid = current_request_id()
        if rid:
            detail = {**detail, "request_id": rid}
        return JSONResponse(status_code=exc.status_code, content={"error": detail})
    code = {
        400: "BAD_REQUEST", 401: "UNAUTHORIZED", 403: "FORBIDDEN",
        404: "NOT_FOUND", 409: "CONFLICT", 429: "RATE_LIMITED",
    }.get(exc.status_code, "ERROR")
    return _error(exc.status_code, code, str(detail))


@app.exception_handler(RequestValidationError)
async def validation_handler(request: Request, exc: RequestValidationError):
    return _error(422, "VALIDATION_ERROR", "Request validation failed")


@app.exception_handler(RateLimitExceeded)
async def ratelimit_handler(request: Request, exc: RateLimitExceeded):
    return _error(429, "RATE_LIMITED", "Rate limit exceeded. Slow down.")


@app.exception_handler(Exception)
async def unhandled_handler(request: Request, exc: Exception):
    # Never leak stack traces / internals to clients.
    log_event(logger, 40, "unhandled_error", path=str(request.url.path), error=str(exc))
    return _error(500, "INTERNAL_ERROR", "An internal error occurred")


# --------------------------------------------------------------- routes
API_V1 = "/api/v1"

app.include_router(health.router)  # /health (public)
app.include_router(health.router, prefix=API_V1)  # /api/v1/health (public)

app.include_router(tenders.router, prefix=API_V1)
app.include_router(categories.router, prefix=API_V1)
app.include_router(provinces.router, prefix=API_V1)
app.include_router(notifications.router, prefix=API_V1)
app.include_router(preferences.router, prefix=API_V1)
app.include_router(saved_searches.router, prefix=API_V1)
app.include_router(admin.router, prefix=API_V1)

# Web admin dashboard (server-rendered, cookie auth).
app.include_router(admin_dashboard.router)


@app.get("/", include_in_schema=False)
async def root():
    return {
        "name": "South African Tender API",
        "version": settings.app_version,
        "docs": "/docs",
        "health": "/health",
        "api_base": API_V1,
        "admin": "/admin",
    }
