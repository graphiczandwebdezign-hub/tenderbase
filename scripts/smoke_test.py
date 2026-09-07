#!/usr/bin/env python3
"""Smoke-test a deployed Tender API from anywhere with plain network access.

Solves a specific operational problem: `/health` is public, but every endpoint
that proves *credentials* work requires a header, so you cannot tell from a
browser/Swagger panel whether a 401 is your key or a stale UI result. This
script prints verdicts, never secrets, and exits non-zero on failure so CI is
readable at a glance.

Usage:
    BASE_URL=https://tenderbase-api.onrender.com python scripts/smoke_test.py
    python scripts/smoke_test.py --base-url https://xxx --trigger-sync

Credentials: read from API_KEY / ADMIN_SECRET, else from the BUNDLED_* constants
in app/core/config.py (the temporary fallback). Nothing is ever printed.

Stdlib only, so it runs on a bare runner or in a container without the app's
dependencies installed.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import ssl
import sys
import urllib.error
import urllib.request

TIMEOUT_SECONDS = 45


def _bundled(name: str) -> str:
    """Pull a credential from the environment, else from the bundled constants."""
    value = os.environ.get(name, "").strip()
    if value:
        return value
    config = pathlib.Path(__file__).resolve().parent.parent / "app" / "core" / "config.py"
    try:
        text = config.read_text()
    except OSError:
        return ""
    # app/core/config.py names them BUNDLED_API_KEY / BUNDLED_ADMIN_SECRET.
    for constant in (f"BUNDLED_{name}", name):
        match = re.search(rf'^{constant} = "(.*)"$', text, re.MULTILINE)
        if match:
            return match.group(1)
    return ""


def _get_or_post(url: str, headers: dict, method: str = "GET") -> tuple[int, str]:
    req = urllib.request.Request(url, headers=headers, method=method)
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS, context=ctx) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001 - report, don't traceback
        return 0, f"{type(exc).__name__}: {exc}"


def _error_code(body: str) -> str:
    try:
        parsed = json.loads(body)
    except (json.JSONDecodeError, TypeError):
        return ""
    if isinstance(parsed, dict):
        err = parsed.get("error")
        if isinstance(err, dict):
            return str(err.get("code", ""))
    return ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://127.0.0.1:8000"))
    ap.add_argument("--trigger-sync", action="store_true",
                    help="also POST /api/v1/admin/sync (writes to the database)")
    ap.add_argument("--max-sync-age-minutes", type=float, default=90.0,
                    help="FAIL when the newest successful sync is older than this")
    ap.add_argument("--expect-commit", default=os.environ.get("EXPECT_COMMIT", ""),
                    help="FAIL unless /health reports this commit (checks either "
                         "the full SHA or its 7-char short form)")
    args = ap.parse_args()
    base = args.base_url.rstrip("/")

    api_key = _bundled("API_KEY")
    admin_secret = _bundled("ADMIN_SECRET")
    failures: list[str] = []

    def check(label: str, ok: bool, detail: str = "") -> None:
        print(f"[{'PASS' if ok else 'FAIL'}] {label}{f' — {detail}' if detail else ''}")
        if not ok:
            failures.append(label)

    # 1. Liveness + sync freshness (public).
    status, body = _get_or_post(f"{base}/health", {})
    if status != 200:
        check(f"GET {base}/health", False, f"HTTP {status} {body[:120]}")
        print("\nService unreachable — nothing else to check.")
        return 1
    health = json.loads(body)
    print(f"       health: status={health.get('status')} database={health.get('database')} "
          f"version={health.get('version')}")
    check("service healthy", health.get("status") in {"healthy", "degraded"},
          f"database={health.get('database')}")
    check("database connected", health.get("database") == "connected")

    build = health.get("build") or {}
    if args.expect_commit:
        expected = args.expect_commit.strip()
        reported = {str(build.get("commit", "")), str(build.get("short_commit", ""))}
        check("deployed commit matches", expected in reported or expected[:7] in reported,
              f"expected {expected[:7]}, /health reports {build or 'no build info (is RENDER_GIT_COMMIT set?)'}")
    elif build:
        print(f"       build: commit={build.get('short_commit', build.get('commit'))} "
              f"branch={build.get('branch', '?')}")

    last_sync = health.get("last_sync")
    if not last_sync:
        check("sync has ever succeeded", False, "last_sync is null")
    else:
        from datetime import datetime, timezone
        stamp = last_sync.replace("Z", "+00:00")
        try:
            when = datetime.fromisoformat(stamp)
            if when.tzinfo is None:
                when = when.replace(tzinfo=timezone.utc)
            age = (datetime.now(timezone.utc) - when).total_seconds() / 60
            check(
                "sync is fresh",
                age <= args.max_sync_age_minutes,
                f"last_sync {last_sync} = {age:.1f} min ago "
                f"(limit {args.max_sync_age_minutes:g} min), status "
                f"{health.get('last_sync_status')}",
            )
        except ValueError:
            check("last_sync parseable", False, last_sync)

    # 2. The credential that Swagger can't test: X-API-Key on a protected route.
    if not api_key:
        check("api key available to script", False, "API_KEY unset and no bundled constant found")
    else:
        status, body = _get_or_post(
            f"{base}/api/v1/tenders?limit=1", {"X-API-Key": api_key}
        )
        if status == 200:
            total = json.loads(body).get("pagination", {}).get("total")
            check("X-API-Key accepted on /api/v1/tenders", True, f"total tenders = {total}")
        else:
            check("X-API-Key accepted on /api/v1/tenders", False,
                  f"HTTP {status} code={_error_code(body) or 'n/a'}")

        # A bogus key must still be rejected, or auth has been disabled.
        status, _ = _get_or_post(f"{base}/api/v1/tenders?limit=1", {"X-API-Key": "definitely-not-a-key"})
        check("bogus key rejected", status == 401, f"HTTP {status}")

    # 3. Admin secret (read-only check unless --trigger-sync).
    if not admin_secret:
        check("admin secret available to script", False, "ADMIN_SECRET unset and no bundled constant found")
    else:
        status, body = _get_or_post(f"{base}/api/v1/admin/dashboard", {"X-Admin-Secret": admin_secret})
        if status == 200:
            dash = json.loads(body)
            check("X-Admin-Secret accepted on /admin/dashboard", True,
                  f"tenders={dash.get('total_tenders')} last_successful_sync={dash.get('last_successful_sync_at')}")
        else:
            check("X-Admin-Secret accepted on /admin/dashboard", False,
                  f"HTTP {status} code={_error_code(body) or 'n/a'}")

        if args.trigger_sync:
            status, body = _get_or_post(
                f"{base}/api/v1/admin/sync", {"X-Admin-Secret": admin_secret}, method="POST"
            )
            check("manual sync accepted", status == 200, f"HTTP {status} {body[:120]}")

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)} check(s)): {', '.join(failures)}")
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
