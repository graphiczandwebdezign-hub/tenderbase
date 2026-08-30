"""Closing-date extraction from free text (Sprint 8).

Some sources publish the deadline only inside the title or description
("Closing date: 12 September 2026 at 11:00"). This module recovers a
datetime from that text using the same conventions as the eTenders adapter:
naive times are SAST (UTC+2), everything is returned UTC-aware, and a date
without a time means end of the closing day (23:59 SAST) so the tender stays
open for its entire final day.
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone
from typing import Optional

_SAST = timezone(timedelta(hours=2))

_MONTHS = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5,
    "june": 6, "july": 7, "august": 8, "september": 9, "october": 10,
    "november": 11, "december": 12,
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "jun": 6, "jul": 7,
    "aug": 8, "sep": 9, "sept": 9, "oct": 10, "nov": 11, "dec": 12,
}
_MONTH_ALT = "|".join(_MONTHS)

# Keyword whose proximity boosts a candidate date (closing/deadline/submit).
_KEYWORD_RE = re.compile(r"clos(?:e|es|ed|ing)|deadline|submit|submissio", re.I)

_DATE_PATTERNS = [
    # 2026-09-12 / 2026-9-2
    (re.compile(r"\b(\d{4})-(\d{1,2})-(\d{1,2})\b"), "ymd"),
    # 12 September 2026 / 12th Sep 2026 / 12 SEPTEMBER 2026
    (re.compile(rf"\b(\d{{1,2}})(?:st|nd|rd|th)?\s+({_MONTH_ALT})\.?,?\s+(\d{{4}})\b", re.I), "dmonth"),
    # 12/09/2026 (SA convention: day first)
    (re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b"), "dmy"),
]

_TIME_RE = re.compile(r"\b(?:at\s+|before\s+)?(\d{1,2})[:h](\d{2})\b", re.I)

_MAX_TITLE_SCAN = 2000  # descriptions can be long; scan a bounded prefix


def _candidate_dates(text: str):
    """Yield (position, date) for every plausible date in the text."""
    for pattern, kind in _DATE_PATTERNS:
        for m in pattern.finditer(text):
            try:
                if kind == "ymd":
                    d = datetime(int(m.group(1)), int(m.group(2)), int(m.group(3)))
                elif kind == "dmonth":
                    d = datetime(int(m.group(3)), _MONTHS[m.group(2).lower()], int(m.group(1)))
                else:  # dmy
                    d = datetime(int(m.group(3)), int(m.group(2)), int(m.group(1)))
            except ValueError:
                continue
            yield m.start(), d, m.end()


def _keyword_distance(text: str, pos: int) -> int:
    """Distance from pos to the nearest deadline-ish keyword (large if none)."""
    best = len(text) + 1
    for m in _KEYWORD_RE.finditer(text):
        best = min(best, abs(m.start() - pos))
    return best


def extract_closing(*texts: Optional[str], now: Optional[datetime] = None) -> Optional[datetime]:
    """Best-effort closing datetime from free text, or None.

    Preference order: candidates near a closing/deadline keyword win, then the
    leftmost. A time within 40 characters of the chosen date is applied;
    otherwise the date means 23:59 SAST. Years outside [now-1, now+2] are
    rejected so incidental numbers never invent deadlines.
    """
    text = " ".join(t for t in texts if t)[:_MAX_TITLE_SCAN]
    if not text:
        return None

    candidates = list(_candidate_dates(text))
    if not candidates:
        return None

    now = now or datetime.now(timezone.utc)
    min_year, max_year = now.year - 1, now.year + 2
    candidates = [c for c in candidates if min_year <= c[1].year <= max_year]
    if not candidates:
        return None

    chosen = min(candidates, key=lambda c: (_keyword_distance(text, c[0]), c[0]))
    _, chosen_date, end = chosen

    hour = minute = None
    for m in _TIME_RE.finditer(text):
        if abs(m.start() - end) <= 40 or abs(m.start() - chosen[0]) <= 40:
            h, mi = int(m.group(1)), int(m.group(2))
            if h < 24 and mi < 60:
                hour, minute = h, mi
                break

    if hour is None:
        hour, minute = 23, 59
    local = datetime(
        chosen_date.year, chosen_date.month, chosen_date.day, hour, minute,
        tzinfo=_SAST,
    )
    return local.astimezone(timezone.utc)
