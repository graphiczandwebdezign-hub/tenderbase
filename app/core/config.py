"""Application configuration loaded from environment variables.

All configuration is environment-based. Secrets that should never be baked in
(DATABASE_URL, FCM credentials) come from the environment only.

TEMPORARY EXCEPTION (2026-09-07): `API_KEY` and `ADMIN_SECRET` currently have
hard-coded fallback values — see BUNDLED_* constants below. They are defaults
only: any value set in the environment or `.env` still wins, so Render's
generated env vars keep overriding them. Remove these defaults and ROTATE both
credentials before this service holds real users — this repository is public on
GitHub.
"""
from __future__ import annotations

from functools import lru_cache
from typing import List, Optional
from typing_extensions import Annotated

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict, NoDecode

# --------------------------------------------------------------------------- #
# TEMPORARY bundled credentials — added 2026-09-07 at the operator's request
# ("hardcode these into the api for now"), so a fresh checkout / fresh database
# authenticates without any env setup.
#
# They work in TWO ways:
#   1. As DEFAULTS for API_KEY / ADMIN_SECRET — a value in the environment or
#      .env replaces the default (so a deployment that already sets them keeps
#      its own values).
#   2. As ADDITIONAL accepted credentials while ALLOW_BUNDLED_CREDENTIALS=true —
#      the bundled API key is ensured into the api_keys table at startup and the
#      bundled admin secret is accepted by the admin API/dashboard alongside
#      whatever the environment says. This is what makes the bundled key work on
#      a host that already has API_KEY set (e.g. Render's generated secret).
#
# Kill switch: set ALLOW_BUNDLED_CREDENTIALS=false (env only, no code change)
# to stop accepting the bundled pair immediately.
#
# TODO(security): delete both constants, the flag and their usages, and rotate
# the two credentials before this service has real users. This repository is
# PUBLIC on GitHub, so these values must be treated as disclosed.
# --------------------------------------------------------------------------- #
BUNDLED_API_KEY = "fy944HfxOInWK13NIl8tdmocAyrrPqt7eJpX3PRqO0I="
BUNDLED_ADMIN_SECRET = "HUprlD1oQBFKDrNHxPw/N09ZpldgNWCfJ1HfC7LuFH8="


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ----- Application -----
    app_env: str = Field(default="development")
    app_name: str = Field(default="SA Tender API")
    app_version: str = Field(default="1.0.0")
    debug: bool = Field(default=False)
    timezone: str = Field(default="Africa/Johannesburg")

    # Deployment identity. Render populates RENDER_GIT_COMMIT / RENDER_GIT_BRANCH
    # at build and runtime; APP_COMMIT / APP_BRANCH override them for any other
    # host (Docker, VMs). Surfaced on the public /health so "is my change
    # actually deployed?" is answerable with one GET and no log access.
    render_git_commit: Optional[str] = Field(default=None)
    render_git_branch: Optional[str] = Field(default=None)
    app_commit: Optional[str] = Field(default=None)
    app_branch: Optional[str] = Field(default=None)

    # ----- Database -----
    # Defaults to a local SQLite file so the project runs anywhere out of the
    # box (e.g. sandboxes/CI without PostgreSQL). Production/Docker set a
    # postgresql:// DATABASE_URL via the environment.
    database_url: str = Field(default="sqlite:///./tenderbase.db")

    # ----- Authentication -----
    # Bootstrap API key. On startup this key is ensured to exist in the
    # api_keys table (hashed). Additional keys are managed via the admin API.
    api_key: Optional[str] = Field(default=BUNDLED_API_KEY)
    admin_secret: Optional[str] = Field(default=BUNDLED_ADMIN_SECRET)
    # TEMPORARY: also accept the bundled key/secret even when the environment
    # defines its own. Set to false to disable (see BUNDLED_* above).
    allow_bundled_credentials: bool = Field(default=True)

    # ----- Sync / ingestion -----
    sync_interval_minutes: int = Field(default=15)
    sync_enabled: bool = Field(default=True)
    # How many days back to fetch on each incremental sync window.
    sync_lookback_days: int = Field(default=3)
    # Full backfill window used by the manual/initial sync.
    sync_backfill_days: int = Field(default=30)
    # Run one sync at process start when the last successful sync is older than
    # SYNC_INTERVAL_MINUTES. Essential on hosts that sleep when idle (Render
    # free tier), where the interval timer restarts on every wake.
    sync_on_boot: bool = Field(default=True)
    etenders_base_url: str = Field(
        default="https://ocds-api.etenders.gov.za/api/OCDSReleases"
    )
    etenders_page_size: int = Field(default=50)
    etenders_max_pages: int = Field(default=40)
    etenders_timeout_seconds: int = Field(default=45)
    # When the live government source is unreachable and the DB is empty, load
    # the bundled development sample so the app is demonstrable. Never enabled
    # implicitly in production.
    ingestion_allow_sample_fallback: bool = Field(default=True)

    # ----- Expiry / retention -----
    tender_retention_days: int = Field(default=7)
    cleanup_interval_hours: int = Field(default=24)
    # A tender is CLOSING_SOON when its closing datetime is within this window.
    closing_soon_hours: int = Field(default=48)

    # ----- Rate limiting -----
    rate_limit_per_minute: int = Field(default=100)
    rate_limit_enabled: bool = Field(default=True)

    # ----- CORS -----
    cors_origins: Annotated[List[str], NoDecode] = Field(default_factory=lambda: ["*"])

    # ----- FCM / push notifications -----
    fcm_enabled: bool = Field(default=False)
    fcm_project_id: Optional[str] = Field(default=None)
    fcm_private_key: Optional[str] = Field(default=None)
    fcm_client_email: Optional[str] = Field(default=None)

    # ----- Deadline reminders (hours before closing) -----
    reminder_offsets_hours: Annotated[List[int], NoDecode] = Field(
        default_factory=lambda: [168, 72, 24, 3]
    )

    @field_validator("cors_origins", "reminder_offsets_hours", mode="before")
    @classmethod
    def _split_csv(cls, v):
        if isinstance(v, str):
            v = v.strip()
            if not v:
                return []
            parts = [p.strip() for p in v.split(",") if p.strip()]
            return parts
        return v

    @field_validator("reminder_offsets_hours", mode="after")
    @classmethod
    def _coerce_ints(cls, v):
        return [int(x) for x in v]

    @field_validator("database_url", mode="after")
    @classmethod
    def _normalize_database_url(cls, v: str) -> str:
        """Normalize DB URLs from managed hosts so no hand-editing is needed.

        Many providers (Render, Railway, Heroku, Neon, Supabase) hand out a
        URL beginning with ``postgres://`` (an alias SQLAlchemy 2.x no longer
        accepts) and without an explicit driver. Rewrite both cases to the
        canonical ``postgresql+psycopg2://`` form.
        """
        if not v:
            return v
        if v.startswith("postgres://"):
            v = "postgresql://" + v[len("postgres://"):]
        if v.startswith("postgresql://"):
            v = "postgresql+psycopg2://" + v[len("postgresql://"):]
        return v

    @property
    def build_info(self) -> dict:
        """Commit/branch the running process was built from, omitting unknowns."""
        commit = self.app_commit or self.render_git_commit
        branch = self.app_branch or self.render_git_branch
        info: dict = {}
        if commit:
            info["commit"] = commit
            info["short_commit"] = commit[:7]
        if branch:
            info["branch"] = branch
        return info

    @property
    def is_production(self) -> bool:
        return self.app_env.lower() in {"production", "prod"}

    @property
    def is_postgres(self) -> bool:
        return self.database_url.startswith("postgres")


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
