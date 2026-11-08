from __future__ import annotations

import asyncio
import logging
import os
import signal
import time
from contextlib import suppress
from typing import Any

from app.core.config import get_settings
from app.core.db import connect_db, disconnect_db, ensure_db_connected
from app.services import (
    artisan_duplicates,
    artisan_merge,
    media_queue,
    ministry_exports,
    transcript_backfill,
)
from app.services.aadhaar import log_retention as aadhaar_log_retention
from app.services.design_workshop_review_aids import purge_expired_snapshots
from app.services.email_outbox import process_next_email_jobs
from app.services.media_queue import acquire_queue_worker_lock, process_next_media_jobs
from app.services.ministry_export import process_next_ministry_exports
from app.services.report_schedules import run_report_schedules
from app.services.search_indexer import (
    compact_queue as compact_search_queue,
    drain as drain_search_index,
    embed_pending as embed_search_documents,
)
from app.services.weekly_digest import run_weekly_digests
from app.transcription import workdir

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logger = logging.getLogger("app.worker")

SEARCH_INDEX_INTERVAL_SECONDS = 15.0
SEARCH_INDEX_BATCH = 100
SEARCH_EMBED_BATCH = 32
SEARCH_COMPACT_INTERVAL_SECONDS = 600.0

RECOVERY_INTERVAL_SECONDS = 60.0

_clock = time.monotonic

_last_search_compaction = {"at": float("-inf")}


async def run_search_index_pass(settings) -> None:
    if not settings.unified_search_enabled:
        now = time.monotonic()
        if now - _last_search_compaction["at"] >= SEARCH_COMPACT_INTERVAL_SECONDS:
            _last_search_compaction["at"] = now
            await compact_search_queue()
        return
    await drain_search_index(batch=SEARCH_INDEX_BATCH)
    await asyncio.sleep(0)
    await embed_search_documents(batch=SEARCH_EMBED_BATCH, settings=settings)


async def drain_pass(settings: Any, *, recover: bool, search: bool) -> bool:
    swept = True
    try:
        await ensure_db_connected()
        await process_next_media_jobs(
            limit=settings.media_queue_batch_size,
            worker_id="queue-service",
            settings=settings,
            recover=recover,
            skip_insights=True,
        )
    except Exception:
        swept = False
        logger.exception("Media queue iteration failed; backing off")
    try:
        await process_next_email_jobs(worker_id="queue-service", settings=settings, recover=recover)
    except Exception:
        swept = False
        logger.exception("E-mail outbox iteration failed; backing off")
    await purge_expired_snapshots()
    try:
        await process_next_ministry_exports(worker_id="queue-service", recover=recover)
    except Exception:
        swept = False
        logger.exception("Ministry export iteration failed; backing off")
    try:
        await aadhaar_log_retention.maybe_run(settings)
    except Exception:
        logger.exception("Aadhaar log retention pass failed; it runs again tomorrow")
    try:
        await run_weekly_digests(settings=settings)
    except Exception:
        logger.exception("Weekly digest pass failed")
    if search:
        try:
            await run_search_index_pass(settings)
        except Exception:
            logger.exception("Search index pass failed; backing off")
    try:
        await artisan_duplicates.maybe_run(settings)
    except Exception:
        logger.exception("Duplicate-artisan scan failed; it runs again tomorrow")
    try:
        await transcript_backfill.maybe_run()
    except Exception:
        logger.exception("Transcript backfill pass failed; it runs again later")
    try:
        await artisan_merge.maybe_purge(settings)
    except Exception:
        logger.exception("Artisan merge snapshot purge failed; it runs again tomorrow")
    try:
        await ministry_exports.maybe_run(settings)
    except Exception:
        logger.exception("Ministry export iteration failed; backing off")
    return swept or not recover


async def run_scheduled_reports(settings: Any) -> None:
    try:
        await run_report_schedules(settings=settings)
    except Exception:
        logger.exception("Scheduled report pass failed")


async def run_reports_lane(stop: asyncio.Event, settings: Any) -> None:
    from app.services.datareport import jobs as report_jobs

    await report_jobs.run_lane(stop, between=lambda: run_scheduled_reports(settings))


async def _wait_for_stop(stop: asyncio.Event, interval: float) -> None:
    with suppress(asyncio.TimeoutError):
        await asyncio.wait_for(stop.wait(), timeout=interval)


async def _run() -> None:
    settings = get_settings()
    interval = max(settings.media_queue_interval_seconds, 1.0)

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        with suppress(NotImplementedError):
            loop.add_signal_handler(sig, stop.set)

    with suppress(Exception):
        await connect_db()

    logger.info("Media queue worker started (interval=%.1fs)", interval)
    with suppress(Exception):
        await asyncio.to_thread(workdir.on_worker_start)
    lane = asyncio.create_task(media_queue.run_insights_lane(stop, settings))
    reports = asyncio.create_task(run_reports_lane(stop, settings))
    last_search_pass = 0.0
    last_recovery = float("-inf")
    try:
        while not stop.is_set():
            now = _clock()
            recover = now - last_recovery >= RECOVERY_INTERVAL_SECONDS
            search = now - last_search_pass >= SEARCH_INDEX_INTERVAL_SECONDS
            if search:
                last_search_pass = now
            swept = await drain_pass(settings, recover=recover, search=search)
            if recover and swept:
                last_recovery = now
            await _wait_for_stop(stop, interval)
    finally:
        stop.set()
        lane.cancel()
        reports.cancel()
        with suppress(asyncio.CancelledError, Exception):
            await lane
        with suppress(asyncio.CancelledError, Exception):
            await reports
        await disconnect_db()
        logger.info("Media queue worker stopped")


def main() -> None:
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
