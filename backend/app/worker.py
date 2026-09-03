"""Standalone media-processing queue worker.

Runs the transcription / measurement queue in its OWN process (systemd unit
``fieldrepo-queue.service``), completely separate from the web (uvicorn) process. This split is a
deliberate fix for a production outage, and the reasons it MUST stay separate are:

1. **No multiprocess supervisor to SIGKILL it.** The web service runs as a SINGLE uvicorn process.
   With ``--workers >1`` uvicorn runs a multiprocess supervisor that health-pings each worker over a
   pipe (the worker answers from a daemon thread) and **SIGKILLs any worker that fails to pong within
   ``timeout_worker_healthcheck``**. On this small, CPU-credit-throttled EC2 box a heavy transcription
   chunk — run via ``asyncio.to_thread`` — starved that pong thread for long enough that the supervisor
   killed the worker mid-job. A SIGKILLed process never runs its shutdown hook, so its Prisma
   query-engine subprocess was orphaned (reparented to init). One orphan per kill cycle eventually
   exhausted the database's client-connection ceiling, after which EVERY DB call (login included)
   returned HTTP 500 while ``/health`` (which touches no DB) kept returning 200.

2. **Request latency isolation.** Transcription/measurement read whole media files into memory and run
   ffmpeg + AI calls. Keeping that off the request-serving process means API responses stay fast and
   never trip CloudFront's origin-response timeout (the earlier HTTP 504 class of failure).

This process is a plain ``asyncio.run`` loop — no supervisor, no health-ping, nothing that can kill it
mid-flight. systemd restarts it (``Restart=always``) only if it actually exits, and ``KillMode=
control-group`` guarantees its query-engine is reaped on stop/restart so it can never be orphaned.

ON THE UNIT NAME. ``fieldrepo-queue.service`` is the product's PRE-REBRAND name and it is correct as
written — not a line the "Design Prototype Workshop" rename missed. It names a unit that is installed
and enabled on the live instance right now, and ``.github/workflows/deploy-backend.yml`` restarts it
by that literal string on every push to ``main``. Editing this docstring renames nothing on that box;
it only teaches the next reader that the unit is called something else, and the natural follow-up
edit — "correcting" the workflow to match — turns every deploy into a ``systemctl restart`` against a
unit that does not exist. Under ``set -e`` that exits non-zero AFTER the new code is already
unpacked, so the pipeline goes red while the old process keeps draining the queue with the old code:
a failure that looks like an application bug sitting on top of a service that is silently a release
behind. ``infra/terraform/user_data.sh`` and ``backend/DEPLOY_AWS.md`` pin the same name for the same
reason. Renaming is a coordinated migration performed over SSH in one change, not a find-and-replace.
"""

from __future__ import annotations

import asyncio
import logging
import os
import signal
from contextlib import suppress

from app.core.config import get_settings
from app.core.db import connect_db, disconnect_db, ensure_db_connected
from app.services.media_queue import acquire_queue_worker_lock, process_next_media_jobs

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("app.worker")


async def _run() -> None:
    settings = get_settings()
    interval = max(settings.media_queue_interval_seconds, 1.0)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        with suppress(NotImplementedError):  # add_signal_handler is POSIX-only
            loop.add_signal_handler(sig, stop.set)

    # Connect using the same resilient retry the web app uses. If it ultimately fails we do NOT crash:
    # the loop below keeps retrying, so a momentarily-full pooler can drain without a restart storm.
    with suppress(Exception):
        await connect_db()

    logger.info("Media queue worker started (interval=%.1fs)", interval)
    try:
        while not stop.is_set():
            try:
                # Self-heal like the web app: probe the connection each iteration and, when the probe
                # fails (or a previous iteration broke the engine), disconnect-first then reconnect —
                # is_connected() alone can read True while the engine is actually unusable.
                await ensure_db_connected()
                await process_next_media_jobs(
                    limit=settings.media_queue_batch_size,
                    worker_id="queue-service",
                    settings=settings,
                )
            except Exception:  # one bad iteration must never kill the worker
                logger.exception("Media queue iteration failed; backing off")
            # Sleep for the interval but wake immediately on shutdown.
            with suppress(asyncio.TimeoutError):
                await asyncio.wait_for(stop.wait(), timeout=interval)
    finally:
        await disconnect_db()
        logger.info("Media queue worker stopped")


def main() -> None:
    """Take the host-wide queue election, then drain. **A second drain exits rather than running.**

    "Host-wide" means the EC2 box: on Kubernetes each pod holds its own ``/tmp``, so the lock
    arbitrates nothing there and the container-level flag stays the protection — the banner above
    ``media_queue.QUEUE_LOCK_PATH`` carries the argument (noted 2026-09-03).

    THIS PROCESS USED TO IGNORE THE ELECTION ENTIRELY, and until 2026-09-03 that was the whole hole:
    the lock existed, it lived in ``app/main.py``, and it arbitrated only between uvicorn workers —
    of which there is exactly one (the Dockerfile pins ``--workers 1``, with a comment recording the
    outage ``--workers 2`` caused). So the contention the lock is named for, TWO DRAINS ON ONE BOX,
    was the case nobody was holding the door against. An API process with
    ``MEDIA_QUEUE_WORKER_ENABLED`` true beside this unit gave the host two drains, and
    ``media_queue._lock_job``'s compare-and-set does not defend against that: it makes the two claim
    DIFFERENT jobs honestly, then both run ffmpeg and paid provider calls on a box sized for one.
    Production escaped by convention — the web box ships ``MEDIA_QUEUE_WORKER_ENABLED=false``
    (DEPLOY_AWS.md) — and a convention is not a guard, which is why the flag's default has also been
    turned off (``core/config.media_queue_worker_enabled``).

    EXIT NON-ZERO, AND LOUDLY, RATHER THAN SLEEPING OR DEGRADING. ``Restart=always`` in
    ``fieldrepo-queue.service`` will bring this straight back, so a losing process becomes a visible
    restart loop in ``systemctl status`` with this message on every cycle — which is the report an
    operator can act on. The alternatives are both worse: idling would leave a unit reporting
    "active (running)" while draining nothing, and running anyway is the defect. A restart loop is
    noisy on purpose; the fix is to stop the other drain, and the log line says which one to look
    for.

    THE HANDLE IS HELD FOR THE PROCESS LIFETIME — the lock is released when it is closed, so it is
    bound to a local that outlives the drain rather than dropped on the floor. On a platform with no
    ``fcntl`` (Windows development) the helper grants unconditionally, so nothing here changes for a
    developer running the worker by hand.
    """
    lock = acquire_queue_worker_lock()
    if lock is None:
        logger.error(
            "Another media-queue drain already holds the host lock; refusing to start a second one "
            "(pid %s). Stop the other drain — most likely an API process still running with "
            "MEDIA_QUEUE_WORKER_ENABLED=true — and this unit will come back on its next restart.",
            os.getpid(),
        )
        raise SystemExit(1)
    logger.info("Media queue drain elected in pid %s", os.getpid())
    try:
        asyncio.run(_run())
    finally:
        if hasattr(lock, "close"):
            with suppress(Exception):
                lock.close()


if __name__ == "__main__":
    main()
