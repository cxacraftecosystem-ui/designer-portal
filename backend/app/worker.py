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
   exhausted the Supabase pooler's client-connection ceiling, after which EVERY DB call (login
   included) returned HTTP 500 while ``/health`` (which touches no DB) kept returning 200.

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
import signal
from contextlib import suppress

from app.core.config import get_settings
from app.core.db import connect_db, disconnect_db, ensure_db_connected
from app.services.media_queue import process_next_media_jobs

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
    asyncio.run(_run())


if __name__ == "__main__":
    main()
