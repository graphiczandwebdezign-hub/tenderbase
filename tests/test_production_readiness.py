"""Sprint 10 tests: liveness/readiness split, request-id correlation, and
deployment-config sanity."""
from __future__ import annotations

import logging
from pathlib import Path

from app.main import app
from app.database.database import get_db

ROOT = Path(__file__).resolve().parents[1]
API = "/api/v1"


# ------------------------------------------------------------------ liveness

def test_liveness_is_cheap_and_stable(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "healthy"
    assert body["version"]
    assert isinstance(body["uptime_seconds"], (int, float))
    assert "database" not in body  # no DB coupling: db outage must not kill pods


def test_liveness_also_mounted_under_api(client):
    assert client.get(f"{API}/health").status_code == 200


# ----------------------------------------------------------------- readiness

def test_ready_when_database_connected(client):
    r = client.get("/ready")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ready"
    assert body["checks"]["database"] == "connected"
    assert body["checks"]["scheduler"] in {"enabled", "disabled"}
    assert body["checks"]["data"] in {"ingested", "no_successful_sync_yet"}


class _BrokenSession:
    def execute(self, *_a, **_k):
        raise RuntimeError("simulated db outage")

    def close(self):
        pass


def test_not_ready_503_when_database_down(client):
    def _broken_db():
        yield _BrokenSession()  # type: ignore[misc]

    app.dependency_overrides[get_db] = _broken_db
    try:
        r = client.get("/ready")
        assert r.status_code == 503
        body = r.json()
        assert body["status"] == "not_ready"
        assert body["checks"]["database"] == "error"
    finally:
        app.dependency_overrides.clear()


# ------------------------------------------------------- request-id correlation

def test_request_id_generated_and_echoed(client):
    r = client.get(f"{API}/provinces")
    assert r.status_code == 200
    rid = r.headers.get("x-request-id")
    assert rid  # generated when absent


def test_supplied_request_id_sanitised(client):
    r = client.get(f"{API}/provinces", headers={"X-Request-ID": "abc-123_def"})
    assert r.headers["x-request-id"] == "abc-123_def"  # valid value honoured

    r2 = client.get(f"{API}/provinces", headers={"X-Request-ID": "bad id !!"})
    assert r2.headers["x-request-id"] != "bad id !!"  # invalid value replaced


def test_access_log_line_carries_request_id(client, caplog):
    with caplog.at_level(logging.INFO, logger="app"):
        r = client.get(f"{API}/provinces", headers={"X-Request-ID": "rid-log-1"})
    assert r.status_code == 200
    events = [
        rec for rec in caplog.records
        if rec.message == "http_request"
        and rec.__dict__.get("extra_fields", {}).get("request_id") == "rid-log-1"
    ]
    assert events, "expected a structured http_request log line with the request id"
    fields = events[0].__dict__["extra_fields"]
    assert fields["path"] == f"{API}/provinces"
    assert fields["status"] == 200
    assert "duration_ms" in fields


def test_error_payloads_include_request_id(noauth_client):
    r = noauth_client.get(f"{API}/tenders", headers={"X-Request-ID": "rid-err-1"})
    assert r.status_code == 401
    assert r.headers["x-request-id"] == "rid-err-1"
    assert r.json()["error"]["request_id"] == "rid-err-1"


# ------------------------------------------------------------ deploy artefacts

def test_compose_and_render_use_readiness():
    compose = (ROOT / "docker-compose.yml").read_text()
    assert "localhost:8000/ready" in compose
    assert 'command: ["worker"]' in compose  # worker role available via profile

    render = (ROOT / "render.yaml").read_text()
    assert "healthCheckPath: /ready" in render
    assert "branch: main" in render  # blueprint deploys from main, not a stale arena branch
