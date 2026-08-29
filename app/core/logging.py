"""Structured JSON logging.

Never logs API keys, passwords, tokens or credentials. Callers are responsible
for not passing secrets into log messages; helper redaction is provided.
"""
from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone

_SENSITIVE_KEYS = {
    "api_key",
    "x-api-key",
    "authorization",
    "password",
    "token",
    "device_token",
    "fcm_private_key",
    "private_key",
    "key_hash",
    "admin_secret",
    "secret",
}


def redact(data: dict) -> dict:
    """Return a shallow copy of ``data`` with sensitive values masked."""
    out = {}
    for k, v in data.items():
        if k.lower() in _SENSITIVE_KEYS:
            out[k] = "***REDACTED***"
        else:
            out[k] = v
    return out


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        # Attach structured extras (already redacted by caller convention).
        extra = getattr(record, "extra_fields", None)
        if isinstance(extra, dict):
            payload.update(redact(extra))
        return json.dumps(payload, default=str)


def setup_logging(level: str = "INFO") -> None:
    root = logging.getLogger()
    root.setLevel(level)
    # Clear existing handlers to avoid duplicate lines under uvicorn reload.
    for h in list(root.handlers):
        root.removeHandler(h)
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root.addHandler(handler)
    # Quiet noisy libraries.
    for noisy in ("httpx", "httpcore", "apscheduler.scheduler", "apscheduler.executors.default"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)


def log_event(logger: logging.Logger, level: int, message: str, **fields) -> None:
    """Emit a log line with structured, redacted extra fields."""
    logger.log(level, message, extra={"extra_fields": fields})
