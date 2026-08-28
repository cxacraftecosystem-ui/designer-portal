"""The load driver: a closed-loop, many-identity ramp against a running API, with no new dependencies.

    cd backend
    ./.venv/Scripts/python.exe loadtest/drive.py --base-url http://127.0.0.1:8111 \
        --identities 1000 --ramp 25,50,100,200,400,800,1000 --seconds 30

WHY NOT LOCUST / k6 / JMETER. Nothing of the sort is in this repository or in ``backend/.venv``
(checked 2026-08-27: ``httpx`` 0.28.1, ``uvicorn``, ``prisma``, ``pytest`` — no ``locust``, no
``aiohttp``, no ``psutil``). Adding one would mean a new dependency, a new lockfile entry and a new
DSL, to run an event loop issuing HTTP requests — which is thirty lines of ``asyncio`` and the
``httpx`` that is already here. The repository's habit is a dependency-free script in the venv it
already has, and that is what this is. What Locust would have added over this file is a web UI and
distributed workers; neither is useful for a single-box pre-launch measurement.

────────────────────────────────────────────────────────────────────────────────────────────────
WHAT "CONCURRENT USERS" MEANS HERE, BECAUSE THE WORD IS USED TO MEAN TWO THINGS
────────────────────────────────────────────────────────────────────────────────────────────────

This is a CLOSED-LOOP model: N virtual users, each looping `request → think → request`. N is
therefore "people with the app open", not "requests in flight". With the default think time of 1.0 s
and a fast server, one virtual user offers a little under 1 request per second, so N ≈ 1,000 offers
roughly 900-1,000 rps — and the achieved rate is reported beside N precisely so the difference
between what was OFFERED and what was SERVED is visible. When a server saturates, a closed loop
self-throttles (users wait for their own response before sending the next), which is exactly how
real users behave and is why the latency curve bends rather than the error rate exploding.

``--think 0`` turns this into an open hammer. That is the right setting for finding the absolute
service-rate ceiling and the WRONG one for answering "does it feel fine with 1,000 people using it",
so it is not the default.

────────────────────────────────────────────────────────────────────────────────────────────────
THE THREE THINGS THIS FILE DOES THAT A NAIVE DRIVER DOES NOT
────────────────────────────────────────────────────────────────────────────────────────────────

1. **ONE BEARER TOKEN PER SIMULATED PERSON.** The rate limiter that went live on 2026-08-27 buckets
   by a digest of the Authorization header. A driver that reuses one token puts every worker in one
   120-request-a-minute bucket and measures the limiter. See ``scenario.Session``.

2. **SIGN-IN IS A WARM-UP PHASE, MEASURED SEPARATELY.** bcrypt at cost 12 is ~370 ms of CPU, it runs
   synchronously inside ``async def login`` (``app/api/routes/auth.py`` calls ``verify_password``
   directly, not through a thread), and the deployment runs ONE uvicorn worker. So sign-in has a
   throughput ceiling of its own that has nothing to do with the rest of the API, and folding it
   into the steady-state mix would drag every other endpoint's number down with it. The warm-up
   reports that ceiling as a number; the mix keeps a 1 % sign-in slice for new sessions arriving.

3. **A CONTROL LINE.** ``/health`` touches no database, no auth and no serialiser. Its p95 is the
   harness's own honesty check: if it rises with concurrency, the machine — or this driver — is
   saturated, and every other percentile in that level is contaminated. On a shared developer box
   that is not a hypothetical, and a run without it cannot tell "the API got slow" from "the laptop
   got busy".

Percentiles are NEAREST-RANK over the raw samples (no interpolation, no reservoir): every request's
latency is kept, because a run of a few hundred thousand samples is a few megabytes and an exact p99
is worth more than the memory.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import random
import statistics
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

import httpx  # noqa: E402

from loadtest.scenario import (  # noqa: E402
    BY_NAME,
    EMAIL_PATTERN,
    LOAD_PASSWORD,
    MIX,
    SYNC_BURST,
    Session,
    Step,
)

#: Matches ``seed_load_identities.WORKSHOP_ID``. Deterministic rather than discovered, so the driver
#: needs no extra round trip per identity at start-up and so a run that finds a 404 here is telling
#: you the seed did not happen — a clearer failure than a silently-skipped step.
WORKSHOP_ID = "loadtest-ws-{index:05d}"


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Recording
# ────────────────────────────────────────────────────────────────────────────────────────────────


@dataclass
class Recorder:
    """Every sample from one ramp level.

    Latencies are stored per step AND in aggregate. Storing both rather than deriving the aggregate
    by concatenation at report time keeps the report cheap enough to print between levels, which
    matters when a level is aborted early: a partial level still prints.
    """

    latencies: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    statuses: Counter = field(default_factory=Counter)
    step_statuses: dict[str, Counter] = field(default_factory=lambda: defaultdict(Counter))
    failures: Counter = field(default_factory=Counter)
    started: float = 0.0
    ended: float = 0.0

    def record(self, step: str, ms: float, status: int) -> None:
        self.latencies[step].append(ms)
        self.statuses[status] += 1
        self.step_statuses[step][status] += 1

    def fail(self, step: str, kind: str) -> None:
        self.failures[f"{step}:{kind}"] += 1

    @property
    def total(self) -> int:
        return sum(self.statuses.values())

    @property
    def bad(self) -> int:
        """Requests whose status the SCENARIO did not declare acceptable, plus transport failures.

        Per step, not a blanket ``>= 400``: the schema fetch's 304 and the sign-in's 429 are
        declared successes in ``scenario.MIX`` and counting them as errors would hide a
        conditional-GET win and misreport the limiter as breakage. See ``Step.expect``.
        """
        bad = sum(self.failures.values())
        for name, counter in self.step_statuses.items():
            expected = BY_NAME[name].expect if name in BY_NAME else frozenset({200})
            bad += sum(n for status, n in counter.items() if status not in expected)
        return bad


def pct(values: list[float], p: float) -> float:
    """Nearest-rank percentile. ``values`` need not be sorted; it is sorted in place."""
    if not values:
        return float("nan")
    values.sort()
    rank = max(1, math.ceil(p / 100.0 * len(values)))
    return values[min(rank, len(values)) - 1]


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Resource sampling — no psutil, because it is not installed and this needs no new dependency
# ────────────────────────────────────────────────────────────────────────────────────────────────


class Samplers:
    """Background subprocesses that watch the server and the database while the ramp runs.

    LONG-LIVED PROCESSES, NOT A SUBPROCESS PER SAMPLE. Spawning ``docker exec`` once a second on a
    box that is already the thing under test would add its own load to the measurement — process
    creation on Windows is not cheap. Each sampler here is ONE process that emits a line on an
    interval, and the driver just reads its stdout.

    Every sampler degrades to "not sampled" rather than failing the run. A load result with no
    CPU number is worth less than one with it; a load result that did not happen because
    ``docker`` was not on PATH is worth nothing.
    """

    def __init__(self, *, container: str | None, pid: int | None, database: str, pg_container: str) -> None:
        self.container = container
        self.pid = pid
        self.database = database
        self.pg_container = pg_container
        self.cpu: list[tuple[float, float]] = []      # (t, cpu_percent)
        self.rss: list[tuple[float, float]] = []      # (t, bytes)
        self.conns: list[tuple[float, int, int]] = [] # (t, total, active)
        self._procs: list[Any] = []
        self._tasks: list[asyncio.Task] = []
        self.notes: list[str] = []

    async def start(self) -> None:
        await self._start_server_sampler()
        await self._start_pg_sampler()

    async def _spawn(self, *argv: str) -> Any | None:
        try:
            return await asyncio.create_subprocess_exec(
                *argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
            )
        except Exception as exc:  # noqa: BLE001 - a missing tool is a degraded run, not a failed one
            self.notes.append(f"sampler {argv[0]} unavailable: {type(exc).__name__}")
            return None

    async def _start_server_sampler(self) -> None:
        if self.container:
            # `docker stats` without --no-stream streams one block per interval. `--format` keeps it
            # to the two numbers wanted, and CPUPerc is already normalised against the container's
            # CPU allowance, which is exactly what "is the server pinned" means when the container
            # is capped to 2 CPUs to imitate the production box.
            proc = await self._spawn(
                "docker", "stats", self.container, "--format", "{{.CPUPerc}}|{{.MemUsage}}"
            )
            if proc:
                self._procs.append(proc)
                self._tasks.append(asyncio.create_task(self._read_docker_stats(proc)))
            return
        if self.pid:
            script = (
                f"while($true){{$p=Get-Process -Id {self.pid} -ErrorAction SilentlyContinue;"
                "if($p){\"{0}|{1}\" -f $p.CPU,$p.WorkingSet64};Start-Sleep -Milliseconds 1000}"
            )
            proc = await self._spawn("powershell", "-NoProfile", "-Command", script)
            if proc:
                self._procs.append(proc)
                self._tasks.append(asyncio.create_task(self._read_powershell(proc)))
            return
        self.notes.append("no --server-container or --server-pid: CPU/RSS not sampled")

    async def _start_pg_sampler(self) -> None:
        # `\watch 1` inside a psql script is what makes ONE psql session emit a row a second. The
        # alternative — `docker exec` per sample — spawns a container exec 1,800 times in a 30-minute
        # ramp, on the machine being measured.
        sql = (
            "SELECT count(*), count(*) FILTER (WHERE state = 'active') "
            f"FROM pg_stat_activity WHERE datname = '{self.database}';\n\\watch 1\n"
        )
        try:
            proc = await asyncio.create_subprocess_exec(
                "docker", "exec", "-i", self.pg_container, "psql", "-U", "postgres",
                "-d", self.database, "-At", "-F", "|", "-f", "-",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
        except Exception as exc:  # noqa: BLE001
            self.notes.append(f"pg sampler unavailable: {type(exc).__name__}")
            return
        proc.stdin.write(sql.encode())
        await proc.stdin.drain()
        self._procs.append(proc)
        self._tasks.append(asyncio.create_task(self._read_pg(proc)))

    async def _read_docker_stats(self, proc: Any) -> None:
        while True:
            line = await proc.stdout.readline()
            if not line:
                return
            text = line.decode("utf-8", "replace").strip()
            # docker stats redraws the screen with ANSI escapes; keep only lines with our separator.
            for chunk in text.replace("\x1b", "\n").split("\n"):
                if "|" not in chunk or "%" not in chunk:
                    continue
                cpu_text, _, mem_text = chunk.rpartition("|")
                cpu_text = cpu_text.split("[")[-1].strip().rstrip("%")
                try:
                    self.cpu.append((time.perf_counter(), float(cpu_text)))
                except ValueError:
                    continue
                used = mem_text.split("/")[0].strip()
                self.rss.append((time.perf_counter(), _parse_bytes(used)))

    async def _read_powershell(self, proc: Any) -> None:
        last: tuple[float, float] | None = None
        while True:
            line = await proc.stdout.readline()
            if not line:
                return
            text = line.decode("utf-8", "replace").strip()
            if "|" not in text:
                continue
            cpu_text, _, rss_text = text.partition("|")
            try:
                cpu_seconds, rss = float(cpu_text), float(rss_text)
            except ValueError:
                continue
            now = time.perf_counter()
            if last is not None and now > last[0]:
                # Get-Process .CPU is CUMULATIVE processor seconds across all cores, so the
                # difference over wall time is a percentage that can exceed 100 on a multi-core box.
                # Reported as-is: "180%" means the process used 1.8 cores, which is the number that
                # matters when the question is whether ONE uvicorn worker is CPU-bound.
                self.cpu.append((now, (cpu_seconds - last[1]) / (now - last[0]) * 100.0))
            self.rss.append((now, rss))
            last = (now, cpu_seconds)

    async def _read_pg(self, proc: Any) -> None:
        while True:
            line = await proc.stdout.readline()
            if not line:
                return
            text = line.decode("utf-8", "replace").strip()
            if "|" not in text:
                continue
            total, _, active = text.partition("|")
            try:
                self.conns.append((time.perf_counter(), int(total), int(active)))
            except ValueError:
                continue

    def window(self, start: float, end: float) -> dict[str, Any]:
        """The samples that fall inside one ramp level, reduced to peak/mean."""
        cpu = [v for t, v in self.cpu if start <= t <= end]
        rss = [v for t, v in self.rss if start <= t <= end]
        conns = [(a, b) for t, a, b in self.conns if start <= t <= end]
        return {
            "cpuPercentMean": round(statistics.fmean(cpu), 1) if cpu else None,
            "cpuPercentPeak": round(max(cpu), 1) if cpu else None,
            "rssMiBPeak": round(max(rss) / 1048576, 1) if rss else None,
            "dbConnectionsPeak": max((a for a, _ in conns), default=None),
            "dbConnectionsActivePeak": max((b for _, b in conns), default=None),
            "dbConnectionsMean": round(statistics.fmean([a for a, _ in conns]), 1) if conns else None,
            "samples": {"cpu": len(cpu), "db": len(conns)},
        }

    async def stop(self) -> None:
        for proc in self._procs:
            try:
                proc.kill()
            except Exception:  # noqa: BLE001 - best effort teardown
                pass
        for task in self._tasks:
            task.cancel()


def _parse_bytes(text: str) -> float:
    """``docker stats`` memory strings: ``123.4MiB``, ``1.02GiB``, ``900KiB``."""
    text = text.strip()
    for suffix, mult in (("GiB", 1073741824), ("MiB", 1048576), ("KiB", 1024), ("B", 1)):
        if text.endswith(suffix):
            try:
                return float(text[: -len(suffix)]) * mult
            except ValueError:
                return 0.0
    return 0.0


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Phases
# ────────────────────────────────────────────────────────────────────────────────────────────────


async def sign_in_all(
    client: httpx.AsyncClient, sessions: list[Session], *, concurrency: int, verbose: bool
) -> dict[str, Any]:
    """Give every identity a token, and measure how fast the server can hand them out.

    THIS IS A RESULT, NOT SETUP. ``POST /api/auth/login`` runs bcrypt (cost 12) synchronously on the
    event loop of a single-worker process, so its throughput is a hard ceiling independent of
    everything else the API does — and while a sign-in is being hashed, EVERY other request on that
    worker is stopped. The number this returns is the answer to "what happens at 09:00 when the
    field teams all open the app".

    The concurrency here is deliberately modest. Pushing 1,000 simultaneous sign-ins at a server that
    can serve two or three a second just fills a queue and measures the queue; a smaller pipe
    measures the SERVICE rate, which is the property that does not depend on how hard you push.
    """
    latencies: list[float] = []
    statuses: Counter = Counter()
    gate = asyncio.Semaphore(concurrency)
    started = time.perf_counter()

    async def one(session: Session) -> None:
        async with gate:
            t0 = time.perf_counter()
            try:
                response = await client.post(
                    "/api/auth/login",
                    json={"email": session.email, "password": LOAD_PASSWORD},
                )
            except Exception as exc:  # noqa: BLE001
                statuses[type(exc).__name__] += 1
                return
            latencies.append((time.perf_counter() - t0) * 1000)
            statuses[response.status_code] += 1
            if response.status_code == 200:
                body = response.json()
                session.token = body["accessToken"]
                session.user_id = body.get("user", {}).get("id", "")

    await asyncio.gather(*(one(s) for s in sessions))
    elapsed = time.perf_counter() - started
    signed_in = sum(1 for s in sessions if s.token)
    result = {
        "requested": len(sessions),
        "signedIn": signed_in,
        "seconds": round(elapsed, 2),
        "signInsPerSecond": round(signed_in / elapsed, 2) if elapsed else None,
        "concurrency": concurrency,
        "p50Ms": round(pct(latencies, 50), 1) if latencies else None,
        "p95Ms": round(pct(latencies, 95), 1) if latencies else None,
        "p99Ms": round(pct(latencies, 99), 1) if latencies else None,
        "statuses": {str(k): v for k, v in statuses.items()},
    }
    if verbose:
        print(f"  sign-in: {signed_in}/{len(sessions)} in {elapsed:.1f}s "
              f"= {result['signInsPerSecond']}/s, p50={result['p50Ms']}ms p95={result['p95Ms']}ms")
    return result


async def issue(
    client: httpx.AsyncClient, session: Session, step: Step, rec: Recorder, timeout: float
) -> None:
    """One request, timed, recorded. Never raises — a driver that dies on the first 500 is useless."""
    method, path, body = step.build(session)
    headers = dict(session.auth) if step.name != "sign_in" and step.name != "control" else {}
    if step.name == "schema_fetch" and session.schema_etag:
        # Send the validator a warm client would send. Without this every schema fetch is a 149 KB
        # body and the run overstates both bandwidth and gzip CPU for a real population, most of whom
        # already hold the registry.
        headers["If-None-Match"] = session.schema_etag
    t0 = time.perf_counter()
    try:
        response = await client.request(
            method, path, json=body, headers=headers or None, timeout=timeout
        )
    except httpx.TimeoutException:
        rec.fail(step.name, "timeout")
        return
    except Exception as exc:  # noqa: BLE001
        rec.fail(step.name, type(exc).__name__)
        return
    rec.record(step.name, (time.perf_counter() - t0) * 1000, response.status_code)
    if step.name == "schema_fetch" and response.status_code == 200:
        session.schema_etag = response.headers.get("etag") or session.schema_etag
    if step.name == "sign_in" and response.status_code == 200:
        session.token = response.json()["accessToken"]


async def run_level(
    client: httpx.AsyncClient,
    sessions: list[Session],
    *,
    concurrency: int,
    seconds: float,
    think: float,
    timeout: float,
    sync_burst: int,
    rng_seed: int,
) -> Recorder:
    """One rung of the ramp: ``concurrency`` virtual users for ``seconds``.

    IDENTITIES ARE ASSIGNED ROUND-ROBIN AND NOT RESAMPLED. At concurrency 1,000 with 1,000 seeded
    identities every worker gets its own; at concurrency 100 the first 100 are used. That means a
    small rung deliberately exercises a SMALL identity working set — which is the control half of
    the identity-cache experiment the README describes, and it is why the rung list and the
    ``--identities`` count are separate knobs.
    """
    rec = Recorder()
    stop_at = time.perf_counter() + seconds
    weights = [s.weight for s in MIX]

    async def worker(session: Session, rng: random.Random) -> None:
        while time.perf_counter() < stop_at:
            step = rng.choices(MIX, weights=weights, k=1)[0]
            if step.needs_workshop and not session.workshop_id:
                continue
            await issue(client, session, step, rec, timeout)
            if think > 0:
                # Uniform around the nominal think time. A fixed delay makes every worker's requests
                # land in lockstep, which produces a self-synchronising thundering herd that no real
                # population exhibits and that flatters or ruins a result depending on the phase.
                await asyncio.sleep(rng.uniform(think * 0.5, think * 1.5))

    async def burst_worker(session: Session, rng: random.Random) -> None:
        """A handset coming back from an offline stretch: everything it has, back to back."""
        while time.perf_counter() < stop_at:
            for name in SYNC_BURST:
                if time.perf_counter() >= stop_at:
                    return
                await issue(client, session, BY_NAME[name], rec, timeout)
            await asyncio.sleep(rng.uniform(2.0, 5.0))

    workers = []
    for i in range(concurrency):
        session = sessions[i % len(sessions)]
        workers.append(worker(session, random.Random(rng_seed + i)))
    for j in range(sync_burst):
        session = sessions[(concurrency + j) % len(sessions)]
        workers.append(burst_worker(session, random.Random(rng_seed + 10_000 + j)))

    rec.started = time.perf_counter()
    await asyncio.gather(*workers)
    rec.ended = time.perf_counter()
    return rec


def summarise(rec: Recorder, *, concurrency: int, resources: dict[str, Any]) -> dict[str, Any]:
    everything: list[float] = []
    per_step: dict[str, Any] = {}
    for name, values in rec.latencies.items():
        everything.extend(values)
        per_step[name] = {
            "n": len(values),
            "p50Ms": round(pct(values, 50), 1),
            "p95Ms": round(pct(values, 95), 1),
            "p99Ms": round(pct(values, 99), 1),
            "maxMs": round(max(values), 1),
            "statuses": {str(k): v for k, v in rec.step_statuses[name].items()},
        }
    elapsed = max(1e-9, rec.ended - rec.started)
    total = rec.total + sum(rec.failures.values())
    return {
        "concurrency": concurrency,
        "seconds": round(elapsed, 2),
        "requests": total,
        "rps": round(total / elapsed, 1),
        "errorRatePercent": round(100.0 * rec.bad / total, 3) if total else 0.0,
        "p50Ms": round(pct(everything, 50), 1) if everything else None,
        "p95Ms": round(pct(everything, 95), 1) if everything else None,
        "p99Ms": round(pct(everything, 99), 1) if everything else None,
        "statuses": {str(k): v for k, v in rec.statuses.items()},
        "transportFailures": dict(rec.failures),
        "resources": resources,
        "steps": per_step,
    }


def print_level(row: dict[str, Any]) -> None:
    control = row["steps"].get("control", {})
    print(
        f"  N={row['concurrency']:>5}  rps={row['rps']:>7.1f}  "
        f"p50={row['p50Ms']:>7}  p95={row['p95Ms']:>8}  p99={row['p99Ms']:>8}  "
        f"err={row['errorRatePercent']:>6}%  "
        f"cpu={row['resources'].get('cpuPercentMean')}%  "
        f"rss={row['resources'].get('rssMiBPeak')}MiB  "
        f"db={row['resources'].get('dbConnectionsPeak')}"
        f"({row['resources'].get('dbConnectionsActivePeak')} active)  "
        f"control_p95={control.get('p95Ms')}"
    )


async def main_async(args: argparse.Namespace) -> int:
    sessions = [
        Session(index=i, email=EMAIL_PATTERN.format(index=i), workshop_id=WORKSHOP_ID.format(index=i))
        for i in range(args.identities)
    ]
    levels = [int(x) for x in args.ramp.split(",") if x.strip()]
    peak = max(levels) + args.sync_burst

    limits = httpx.Limits(
        # One connection per virtual user, kept alive. Ephemeral-port exhaustion is a real Windows
        # failure at this scale and it looks exactly like the server refusing connections, so the
        # pool is sized to the peak and keep-alive is left on rather than churning sockets.
        max_connections=peak + 64,
        max_keepalive_connections=peak + 64,
    )
    async with httpx.AsyncClient(
        base_url=args.base_url, limits=limits, timeout=args.timeout, http2=False
    ) as client:
        print(f"Load driver -> {args.base_url}")
        try:
            probe = await client.get("/health", timeout=10.0)
            probe.raise_for_status()
        except Exception as exc:  # noqa: BLE001
            print(f"FATAL: {args.base_url}/health did not answer: {exc!r}", file=sys.stderr)
            return 2

        print(f"Warm-up: signing in {len(sessions)} distinct identities "
              f"(concurrency {args.signin_concurrency})")
        signin = await sign_in_all(
            client, sessions, concurrency=args.signin_concurrency, verbose=True
        )
        live = [s for s in sessions if s.token]
        if not live:
            print("FATAL: no identity signed in. Run loadtest/seed_load_identities.py first.",
                  file=sys.stderr)
            print(f"  statuses: {signin['statuses']}", file=sys.stderr)
            return 2
        if len(live) < len(sessions):
            print(f"  WARNING: only {len(live)}/{len(sessions)} identities signed in; "
                  f"the ramp will reuse them round-robin, which inflates the identity-cache hit rate.")

        samplers = Samplers(
            container=args.server_container,
            pid=args.server_pid,
            database=args.database,
            pg_container=args.pg_container,
        )
        await samplers.start()
        # One second of sampling before the first rung, so the idle baseline is in the record.
        await asyncio.sleep(1.5)

        rows: list[dict[str, Any]] = []
        print(f"\nRamp ({args.seconds}s per rung, think={args.think}s, "
              f"sync-burst workers={args.sync_burst}):")
        try:
            for level in levels:
                window_start = time.perf_counter()
                rec = await run_level(
                    client, live,
                    concurrency=level, seconds=args.seconds, think=args.think,
                    timeout=args.timeout, sync_burst=args.sync_burst, rng_seed=args.seed,
                )
                window_end = time.perf_counter()
                row = summarise(
                    rec, concurrency=level, resources=samplers.window(window_start, window_end)
                )
                rows.append(row)
                print_level(row)
                # Let queues drain and the identity cache settle before the next rung, so a rung
                # never inherits the previous rung's backlog and reports it as its own latency.
                await asyncio.sleep(args.settle)
        finally:
            await samplers.stop()

        report = {
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "baseUrl": args.base_url,
            "identities": args.identities,
            "thinkSeconds": args.think,
            "secondsPerLevel": args.seconds,
            "syncBurstWorkers": args.sync_burst,
            "signIn": signin,
            "levels": rows,
            "samplerNotes": samplers.notes,
        }
        if args.out:
            Path(args.out).write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(f"\nWrote {args.out}")
        knee = next((r for r in rows if (r["p95Ms"] or 0) > args.knee_ms), None)
        if knee:
            print(f"\np95 crosses {args.knee_ms}ms at N={knee['concurrency']} "
                  f"(p95={knee['p95Ms']}ms, rps={knee['rps']}, err={knee['errorRatePercent']}%)")
        else:
            print(f"\np95 stayed under {args.knee_ms}ms at every rung up to N={levels[-1]}")
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--base-url", default=os.environ.get("LOADTEST_BASE_URL", "http://127.0.0.1:8111"))
    parser.add_argument("--identities", type=int, default=1000,
                        help="distinct seeded accounts to sign in (must already be seeded)")
    parser.add_argument("--ramp", default="25,50,100,200,400,800,1000",
                        help="comma-separated virtual-user counts, one rung each")
    parser.add_argument("--seconds", type=float, default=30.0, help="seconds per rung")
    parser.add_argument("--think", type=float, default=1.0,
                        help="mean think time between a user's requests; 0 makes this an open hammer")
    parser.add_argument("--settle", type=float, default=3.0, help="idle seconds between rungs")
    parser.add_argument("--timeout", type=float, default=30.0, help="per-request timeout, seconds")
    parser.add_argument("--sync-burst", type=int, default=0,
                        help="extra workers replaying an offline handset's queue with no think time")
    parser.add_argument("--signin-concurrency", type=int, default=16)
    parser.add_argument("--knee-ms", type=float, default=500.0,
                        help="the p95 threshold whose crossing is the headline number")
    parser.add_argument("--server-container", default=None,
                        help="docker container name to sample CPU/memory from")
    parser.add_argument("--server-pid", type=int, default=None,
                        help="local PID to sample CPU/memory from, when not running in docker")
    parser.add_argument("--pg-container", default="design-workshop-postgres")
    parser.add_argument("--database", default="design_workshop")
    parser.add_argument("--out", default=None, help="write the full report as JSON here")
    parser.add_argument("--seed", type=int, default=1729, help="RNG seed, so a run is repeatable")
    args = parser.parse_args()
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
