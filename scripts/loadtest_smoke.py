"""Sprint 10 load-test smoke: fire concurrent requests at a RUNNING server's
discovery endpoints and report latency percentiles.

Complements scripts/benchmark_discovery.py (which times queries in-process
against a synthetic DB) — this one exercises real HTTP, auth, middleware and
serialisation. Safe by design: read-only endpoints only.

Usage:
    # start a server, then:
    .venv/bin/python scripts/loadtest_smoke.py --url http://localhost:8000 --key $API_KEY
    .venv/bin/python scripts/loadtest_smoke.py -n 400 -c 20            # bigger pass

Exits non-zero if any request errors, so it can gate a deploy pipeline.
"""
from __future__ import annotations

import argparse
import asyncio

import sys
import time

import httpx

async def run_scenario(
    client: httpx.AsyncClient, path: str, total: int, concurrency: int
) -> dict:
    latencies: list[float] = []
    errors = 0
    sem = asyncio.Semaphore(concurrency)

    async def one() -> None:
        nonlocal errors
        async with sem:
            t0 = time.perf_counter()
            try:
                r = await client.get(path)
                if r.status_code != 200:
                    errors += 1
            except Exception:  # noqa: BLE001 - transport errors count as errors
                errors += 1
            latencies.append((time.perf_counter() - t0) * 1000)

    t0 = time.perf_counter()
    await asyncio.gather(*(one() for _ in range(total)))
    wall = time.perf_counter() - t0

    latencies.sort()

    def pct(p: float) -> float:
        if not latencies:
            return 0.0
        return round(latencies[min(int(len(latencies) * p), len(latencies) - 1)], 1)

    return {
        "path": path,
        "n": len(latencies),
        "errors": errors,
        "rps": round(len(latencies) / wall, 1) if wall else 0,
        "p50_ms": pct(0.50),
        "p95_ms": pct(0.95),
        "p99_ms": pct(0.99),
        "max_ms": round(latencies[-1], 1) if latencies else 0,
    }

async def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", default="http://localhost:8000")
    ap.add_argument("--key", default="testkey", help="X-API-Key value")
    ap.add_argument("-n", "--requests", type=int, default=120, help="per scenario")
    ap.add_argument("-c", "--concurrency", type=int, default=10)
    args = ap.parse_args()

    scenarios = [
        "/api/v1/tenders?limit=25",
        "/api/v1/tenders?search=construction&province=KwaZulu-Natal&limit=25",
        "/api/v1/tenders/closing-soon?hours=168",
        "/api/v1/tenders/facets",
        "/ready",
    ]

    headers = {"X-API-Key": args.key}
    print(f"load-test smoke against {args.url} "
          f"({args.requests} reqs x {len(scenarios)} scenarios, c={args.concurrency})")
    failures = 0
    async with httpx.AsyncClient(base_url=args.url, headers=headers, timeout=30) as client:
        for path in scenarios:
            r = await run_scenario(client, path, args.requests, args.concurrency)
            failures += r["errors"]
            print(
                f"  {r['path'][:52]:<54} n={r['n']:<4} errors={r['errors']:<3} "
                f"rps={r['rps']:<7} p50={r['p50_ms']:<7} p95={r['p95_ms']:<7} "
                f"p99={r['p99_ms']:<7} max={r['max_ms']}"
            )
    if failures:
        print(f"FAILED: {failures} errored requests")
        return 1
    print("OK: all requests succeeded")
    return 0

if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
