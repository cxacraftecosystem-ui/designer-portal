import asyncio
import contextlib
import logging
import os
import tempfile
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi.encoders import jsonable_encoder
from prisma.errors import UniqueViolationError

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import (
    ai_verb_cap,
    audio_isolation,
    dictation_consent,
    job_supersede,
    paid_ai_allowance,
    transcribe_choice,
    transcript_insights,
    transcript_pipeline,
    transcription_jobs,
)
from app.services.ai import (
    analyze_measurement_image_bytes,
    redact_secrets,
    refine_transcript_text,
    transcribe_audio_bytes,
)
from app.services.app_settings import (
    load_app_settings,
    transcription_mode,
    transcription_paused,
    within_processing_window,
)
from app.services.memory_budget import budget_bytes
from app.services.s3 import (
    ObjectTooLarge,
    discard_temp,
    download_to_temp,
    get_object_bytes,
    head_object,
)
from app.services.transcription_store import open_key
from app.transcription import schedule, workdir
from prisma import Json

logger = logging.getLogger(__name__)

TRANSCRIPTION = "TRANSCRIPTION"
MEASUREMENT = "MEASUREMENT"
TRANSCRIPT_INSIGHTS = "TRANSCRIPT_INSIGHTS"
AUDIO_ISOLATION = "AUDIO_ISOLATION"
SHA_VERIFY = "SHA_VERIFY"
QUEUED = "QUEUED"
PROCESSING = "PROCESSING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
UNAVAILABLE = "UNAVAILABLE"
RATE_LIMITED = "RATE_LIMITED"

STALE_PROCESSING_AFTER = timedelta(minutes=30)

MAX_TRANSCRIBE_FETCH_BYTES = 1024 * 1024 * 1024
MAX_MEASUREMENT_BYTES = 32 * 1024 * 1024

RATE_LIMIT_BASE_SECONDS = 30
RATE_LIMIT_MAX_SECONDS = 900


QUEUE_LOCK_PATH = os.path.join(tempfile.gettempdir(), "design-workshop-media-queue.lock")


def acquire_queue_worker_lock(path: str | None = None) -> Any | None:
    lock_path = path or QUEUE_LOCK_PATH
    try:
        import fcntl
    except ImportError:
        return object()
    try:
        handle = open(lock_path, "w")  # noqa: SIM115 - kept open for the process lifetime
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        handle.write(str(os.getpid()))
        handle.flush()
        return handle
    except OSError:
        return None


_rate_limit_cooldown_until: datetime | None = None
_consecutive_rate_limits = 0


class RateLimited(Exception):
    def __init__(self, retry_after: float | None = None) -> None:
        super().__init__("Transcription rate-limited")
        self.retry_after = retry_after




def _transcription_in_cooldown(now: datetime | None = None) -> bool:
    return (
        _rate_limit_cooldown_until is not None
        and (now or datetime.now(UTC)) < _rate_limit_cooldown_until
    )


def _enter_rate_limit_cooldown(retry_after: float | None) -> datetime:
    global _rate_limit_cooldown_until, _consecutive_rate_limits
    _consecutive_rate_limits += 1
    if retry_after and retry_after > 0:
        delay = max(retry_after, RATE_LIMIT_BASE_SECONDS)
    else:
        delay = min(
            RATE_LIMIT_BASE_SECONDS * (2 ** (_consecutive_rate_limits - 1)), RATE_LIMIT_MAX_SECONDS
        )
    _rate_limit_cooldown_until = datetime.now(UTC) + timedelta(seconds=delay)
    return _rate_limit_cooldown_until


def _clear_rate_limit_cooldown() -> None:
    global _rate_limit_cooldown_until, _consecutive_rate_limits
    _rate_limit_cooldown_until = None
    _consecutive_rate_limits = 0


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


TRANSCRIBABLE_TYPES = frozenset({"AUDIO", "VIDEO"})


def _job_requests(media: Any, requested: list[str] | None) -> set[str]:
    media_type = str(_value(media, "mediaType") or "").upper()
    normalized = {str(item).strip().upper() for item in requested or [] if str(item).strip()}
    if media_type not in TRANSCRIBABLE_TYPES:
        normalized.discard(TRANSCRIPTION)
    if media_type != "IMAGE":
        normalized.discard(MEASUREMENT)
    return normalized & {TRANSCRIPTION, MEASUREMENT}


def _target_data(media: Any) -> dict[str, str]:
    data: dict[str, str] = {}
    product_id = _value(media, "productId")
    tool_id = _value(media, "toolId")
    record_type = str(_value(media, "linkedRecordType") or "").lower()
    linked_record_id = _value(media, "linkedRecordId")
    if record_type == "product" and linked_record_id:
        product_id = linked_record_id
    if record_type == "tool" and linked_record_id:
        tool_id = linked_record_id
    if product_id:
        data["productId"] = str(product_id)
    if tool_id:
        data["toolId"] = str(tool_id)
    return data


async def enqueue_media_processing_jobs(
    media: Any,
    processing_requests: list[str] | None,
    requested_by_id: str,
    settings: Settings | None = None,
    *,
    design_workshop_id: str | None = None,
) -> list[Any]:
    settings = settings or get_settings()
    requests = _job_requests(media, processing_requests)
    if TRANSCRIPTION in requests:
        verdict = await dictation_consent.transcription_verdict(
            media, design_workshop_id=design_workshop_id
        )
        if not verdict.may_send:
            requests.discard(TRANSCRIPTION)
            await _record_transcription_refused(media, verdict)
    if not requests:
        return []

    media_id = str(_value(media, "id"))
    target_data = _target_data(media)
    created_jobs: list[Any] = []
    for request in sorted(requests):
        existing = await db.mediaprocessingjob.find_first(
            where={"mediaFileId": media_id, "jobType": request, "status": {"in": [QUEUED, PROCESSING]}}
        )
        if existing is not None:
            created_jobs.append(existing)
            continue
        try:
            job = await _create_open_job(
                {
                "jobType": request,
                "status": QUEUED,
                "priority": 50 if request == TRANSCRIPTION else 70,
                "maxAttempts": max(settings.media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
                "requestedById": requested_by_id,
                **target_data,
                }
            )
        except UniqueViolationError:
            raced = await db.mediaprocessingjob.find_first(
                where={"openKey": open_key(media_id, request)}
            )
            if raced is not None:
                created_jobs.append(raced)
            continue
        created_jobs.append(job)

    if TRANSCRIPTION in requests:
        await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})

    if MEASUREMENT in requests:
        await _mark_measurement_queued(media_id, target_data)

    return created_jobs


async def _record_transcription_refused(media: Any, verdict: Any) -> None:
    media_id = str(_value(media, "id") or "")
    if not media_id:
        return
    if str(_value(media, "transcriptText") or "").strip():
        return
    try:
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": FAILED, "transcriptError": verdict.refusal},
        )
    except Exception as exc:  # noqa: BLE001 — the refusal already held; this is only the note.
        logger.warning(
            "media_queue: transcription of media %s was refused for consent, and the reason could "
            "not be written onto the row (%s)",
            media_id,
            exc,
        )


async def _create_open_job(data: dict[str, Any]) -> Any:
    return await job_supersede.open_job(
        {**data, "openKey": open_key(str(data["mediaFileId"]), str(data["jobType"]))}, client=db
    )


async def _mark_measurement_queued(media_id: str, target_data: dict[str, str]) -> None:
    data = {"measurementImageId": media_id, "measurementAnalysisStatus": QUEUED}
    if target_data.get("productId"):
        await db.productdocumentation.update(where={"id": target_data["productId"]}, data=data)
    if target_data.get("toolId"):
        await db.tooldocumentation.update(where={"id": target_data["toolId"]}, data=data)


async def process_next_media_jobs(
    limit: int | None = None,
    worker_id: str = "api-worker",
    settings: Settings | None = None,
    *,
    recover: bool = True,
    skip_insights: bool = False,
) -> dict[str, int]:
    settings = settings or get_settings()
    batch_size = max(1, limit or settings.media_queue_batch_size)
    if recover:
        await recover_stale_processing_jobs()
    now = datetime.now(UTC)
    where: dict[str, Any] = {"status": QUEUED, "runAfter": {"lte": now}}
    app_settings = await load_app_settings()
    allow_transcription = (
        not transcription_paused(app_settings)
        and within_processing_window(app_settings)
        and not _transcription_in_cooldown(now)
    )
    held_back: list[str] = []
    if not allow_transcription:
        held_back.append(TRANSCRIPTION)
    if (
        skip_insights
        or not transcript_insights.enabled()
        or transcript_pipeline.holds_translation(app_settings)
    ):
        held_back.append(TRANSCRIPT_INSIGHTS)
    if len(held_back) == 1:
        where["jobType"] = {"not": held_back[0]}
    elif held_back:
        where["jobType"] = {"not_in": held_back}
    jobs = await db.mediaprocessingjob.find_many(
        where=where,
        include={"mediaFile": True},
        order=[{"priority": "asc"}, {"createdAt": "asc"}],
        take=batch_size,
    )

    processed = 0
    succeeded = 0
    failed = 0
    for job in jobs:
        processed += 1
        try:
            locked = await _lock_job(job.id, worker_id)
            if locked is None:
                continue
            await _run_locked(locked, settings, worker_id)
            succeeded += 1
            if locked.jobType == TRANSCRIPTION:
                _clear_rate_limit_cooldown()
        except RateLimited as exc:
            until = _enter_rate_limit_cooldown(exc.retry_after)
            await _defer_rate_limited_job(job, until)
            await _park_transcription_jobs_until(until)
            break
        except Exception as exc:  # noqa: BLE001
            failed += 1
            await _handle_job_failure(job.id, exc)
    return {"processed": processed, "succeeded": succeeded, "failed": failed}


async def _defer_rate_limited_job(job: Any, until: datetime) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": job.attempts,
            "runAfter": until,
            "error": "The service is busy; will retry automatically.",
        },
    )


async def _park_transcription_jobs_until(until: datetime) -> None:
    await db.mediaprocessingjob.update_many(
        where={"jobType": TRANSCRIPTION, "status": QUEUED, "runAfter": {"lt": until}},
        data={"runAfter": until},
    )


def _size_label(size_bytes: int) -> str:
    megabytes = size_bytes / (1024 * 1024)
    return f"{megabytes:.1f} MB" if megabytes < 10 else f"{megabytes:,.0f} MB"


async def _oversize_reason(media: Any, ceiling: int, *, what: str) -> str | None:
    declared = 0
    try:
        declared = int(_value(media, "sizeBytes") or 0)
    except (TypeError, ValueError):
        declared = 0
    if declared > ceiling:
        return (
            f"This file is too large to {what} (the limit is {_size_label(ceiling)}). Nothing "
            "was spent."
        )
    head = await asyncio.to_thread(head_object, _value(media, "objectKey"))
    if head is not None and head.size_bytes > ceiling:
        return (
            f"This file is too large to {what} (the limit is {_size_label(ceiling)}). Nothing "
            "was spent."
        )
    return None


async def transcribe_media_now(media: Any, settings: Settings | None = None) -> dict[str, Any]:
    settings = settings or get_settings()
    verdict = await dictation_consent.transcription_verdict(media, resolve_from_stages=True)
    if not verdict.may_send:
        raise dictation_consent.SendRefused(verdict)
    if transcription_paused(await load_app_settings()):
        raise transcription_jobs.TranscriptionPaused()
    await transcribe_choice.mark_requested(media, transcribe_choice.BUTTON)
    media_id = str(_value(media, "id"))
    oversize = await _oversize_reason(media, MAX_TRANSCRIBE_FETCH_BYTES, what="transcribe")
    if oversize is not None:
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": UNAVAILABLE, "transcriptError": oversize},
        )
        return {
            "available": False,
            "status": UNAVAILABLE,
            "text": None,
            "formattedTranscript": None,
            "message": oversize,
        }
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": PROCESSING})
    try:
        source_path = await workdir.owned(
            download_to_temp, _value(media, "objectKey"), max_bytes=MAX_TRANSCRIBE_FETCH_BYTES
        )
    except ObjectTooLarge as exc:
        message = (
            f"This file is too large to transcribe (the limit is "
            f"{_size_label(exc.limit_bytes)}). Nothing was spent."
        )
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": UNAVAILABLE, "transcriptError": message},
        )
        return {
            "available": False,
            "status": UNAVAILABLE,
            "text": None,
            "formattedTranscript": None,
            "message": message,
        }
    try:
        result = await transcription_jobs.transcribe_recording(
            media,
            settings,
            source_path=source_path,
            user_id=_value(media, "uploadedById"),
            transcribe=transcribe_audio_bytes,
        )
    finally:
        discard_temp(source_path)
    mode = transcription_mode(await load_app_settings())
    if (
        result.get("status") == "COMPLETED"
        and mode in {"REFINED", "REFINED_TRANSLATED"}
        and result.get("text")
    ):
        refined = await refine_transcript_text(
            result.get("text"), mode == "REFINED_TRANSLATED", settings
        )
        if refined.get("status") == "COMPLETED" and refined.get("refined"):
            result = {
                **result,
                "formattedTranscript": refined["refined"],
                "transcriptionMode": mode,
                "translated": mode == "REFINED_TRANSLATED",
            }
    status = str(result.get("status") or FAILED).upper()
    if status == RATE_LIMITED:
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": QUEUED, "transcriptError": result.get("message")},
        )
        pending = await db.mediaprocessingjob.find_first(
            where={
                "mediaFileId": media_id,
                "jobType": TRANSCRIPTION,
                "status": {"in": [QUEUED, PROCESSING]},
            }
        )
        if pending is None:
            job_data: dict[str, Any] = {
                "jobType": TRANSCRIPTION,
                "status": QUEUED,
                "priority": 50,
                "maxAttempts": max(settings.media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
                **_target_data(media),
            }
            uploader_id = _value(media, "uploadedById")
            if uploader_id:
                job_data["requestedById"] = str(uploader_id)
            try:
                await _create_open_job(job_data)
            except UniqueViolationError:
                pass
        return result
    write = _transcript_write(result, status)
    await db.mediafile.update(where={"id": media_id}, data=write)
    if status == COMPLETED and write.get("transcriptText"):
        await job_supersede.complete_open_jobs(
            media_id,
            TRANSCRIPTION,
            {"status": COMPLETED, "completedAt": datetime.now(UTC), "openKey": None, "error": None},
            client=db,
        )
        await transcript_insights.enqueue_after_transcript(media_id)
        await audio_isolation.enqueue_if_enabled(media_id)
    return result


def _transcript_write(result: dict[str, Any], status: str) -> dict[str, Any]:
    data: dict[str, Any] = {
        "transcriptStatus": status,
        "transcriptError": None if status in {COMPLETED, "EMPTY"} else result.get("message"),
    }
    transcript = result.get("formattedTranscript") or result.get("text")
    if transcript:
        data.update(transcription_jobs.transcript_extras(result))
        data["transcriptText"] = transcript
        data["transcriptSummary"] = result.get("text")
    return data


async def recover_stale_processing_jobs() -> int:
    cutoff = datetime.now(UTC) - STALE_PROCESSING_AFTER
    stale_jobs = await db.mediaprocessingjob.find_many(
        where={"status": PROCESSING, "lockedAt": {"lt": cutoff}},
        take=25,
    )
    requeued = 0
    for job in stale_jobs:
        now = datetime.now(UTC)
        if _attempts_exhausted(job):
            logger.warning(
                "Job %s was interrupted while held by %s and is out of attempts (%s of %s)",
                job.id,
                _value(job, "lockedBy") or "an unnamed worker",
                _value(job, "attempts"),
                _value(job, "maxAttempts"),
            )
            error = "Processing was interrupted and has stopped. Retry it to try again."
            await db.mediaprocessingjob.update(
                where={"id": job.id},
                data={
                    "status": FAILED,
                    "lockedAt": None,
                    "lockedBy": None,
                    "completedAt": now,
                    "error": error,
                },
            )
            await _finalize_failed_clip(job, error)
            continue
        await db.mediaprocessingjob.update(
            where={"id": job.id},
            data={
                "status": QUEUED,
                "lockedAt": None,
                "lockedBy": None,
                "runAfter": now,
                "error": "Restarted after an interruption.",
            },
        )
        requeued += 1
    return requeued


def _attempts_exhausted(job: Any) -> bool:
    try:
        attempts = int(_value(job, "attempts") or 0)
    except (TypeError, ValueError):
        attempts = 0
    try:
        max_attempts = int(_value(job, "maxAttempts") or 0)
    except (TypeError, ValueError):
        max_attempts = 0
    return attempts >= max(max_attempts, 1)


LOCK_HEARTBEAT_SECONDS = 300.0


async def _hold_lock(job_id: str, worker_id: str) -> None:
    while True:
        await asyncio.sleep(LOCK_HEARTBEAT_SECONDS)
        try:
            await db.mediaprocessingjob.update_many(
                where={"id": job_id, "status": PROCESSING, "lockedBy": worker_id},
                data={"lockedAt": datetime.now(UTC)},
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("Could not refresh the lock on job %s: %s", job_id, exc)


async def _run_locked(job: Any, settings: Settings, worker_id: str) -> None:
    beat = asyncio.create_task(_hold_lock(job.id, worker_id))
    try:
        await _process_job(job, settings)
    finally:
        beat.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await beat


async def _lock_job(job_id: str, worker_id: str) -> Any | None:
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job or job.status != QUEUED:
        return None
    now = datetime.now(UTC)
    claimed = await db.mediaprocessingjob.update_many(
        where={"id": job_id, "status": QUEUED},
        data={
            "status": PROCESSING,
            "lockedAt": now,
            "lockedBy": worker_id,
            "startedAt": now,
            "attempts": job.attempts + 1,
            "error": None,
        },
    )
    if claimed != 1:
        return None
    return await db.mediaprocessingjob.find_unique(
        where={"id": job_id}, include={"mediaFile": True}
    )


async def _process_job(job: Any, settings: Settings) -> None:
    media = job.mediaFile
    if job.jobType == SHA_VERIFY:
        from app.questionnaire import recordings as v2_recordings

        result = await v2_recordings.verify_sha(media.id)
        await _complete_job(job.id, result)
        return
    if job.jobType == TRANSCRIPT_INSIGHTS:
        await _process_insights_job(job, media)
        return
    if job.jobType == AUDIO_ISOLATION:
        await _process_isolation_job(job, media)
        return
    if job.jobType == TRANSCRIPTION:
        verdict = await dictation_consent.transcription_verdict(media, resolve_from_stages=True)
        if not verdict.may_send:
            await _finalize_refused_job(job, verdict)
            return
        oversize = await _oversize_reason(media, MAX_TRANSCRIBE_FETCH_BYTES, what="transcribe")
        if oversize is not None:
            await _apply_transcription_result(
                job,
                {
                    "available": False,
                    "status": UNAVAILABLE,
                    "text": None,
                    "formattedTranscript": None,
                    "message": oversize,
                },
            )
            return
        try:
            source_path = await workdir.owned(
                download_to_temp, media.objectKey, max_bytes=MAX_TRANSCRIBE_FETCH_BYTES
            )
        except ObjectTooLarge as exc:
            await _apply_transcription_result(
                job,
                {
                    "available": False,
                    "status": UNAVAILABLE,
                    "text": None,
                    "formattedTranscript": None,
                    "message": (
                        f"This file is too large to transcribe (the limit is "
                        f"{_size_label(exc.limit_bytes)}). Nothing was spent."
                    ),
                },
            )
            return
        try:
            result = await transcription_jobs.transcribe_recording(
                media,
                settings,
                source_path=source_path,
                user_id=getattr(job, "requestedById", None) or getattr(media, "uploadedById", None),
                job=job,
                transcribe=transcribe_audio_bytes,
            )
        finally:
            discard_temp(source_path)
        mode = transcription_mode(await load_app_settings())
        if (
            result.get("status") == "COMPLETED"
            and mode in {"REFINED", "REFINED_TRANSLATED"}
            and result.get("text")
        ):
            try:
                refined = await refine_transcript_text(
                    result.get("text"), mode == "REFINED_TRANSLATED", settings
                )
            except Exception as exc:  # noqa: BLE001 — see above: the raw transcript is worth keeping.
                logger.warning(
                    "media_queue: transcript refinement failed for media %s; storing the raw "
                    "transcript instead (%s)",
                    job.mediaFileId,
                    exc,
                )
                refined = {}
            if refined.get("status") == "COMPLETED" and refined.get("refined"):
                result = {
                    **result,
                    "formattedTranscript": refined["refined"],
                    "transcriptionMode": mode,
                    "translated": mode == "REFINED_TRANSLATED",
                }
        await _apply_transcription_result(job, result)
        return
    if job.jobType == MEASUREMENT:
        ceiling = budget_bytes(MAX_MEASUREMENT_BYTES)
        oversize = await _oversize_reason(media, ceiling, what="measure")
        if oversize is not None:
            await _apply_measurement_result(
                job,
                {"available": False, "status": UNAVAILABLE, "analysis": None, "message": oversize},
            )
            return
        try:
            content = await asyncio.to_thread(
                get_object_bytes, media.objectKey, max_bytes=ceiling
            )
        except ObjectTooLarge as exc:
            await _apply_measurement_result(
                job,
                {
                    "available": False,
                    "status": UNAVAILABLE,
                    "analysis": None,
                    "message": (
                        f"This image is too large to measure (the limit is "
                        f"{_size_label(exc.limit_bytes)}). Nothing was spent."
                    ),
                },
            )
            return
        try:
            result = await analyze_measurement_image_bytes(
                content,
                media.originalFilename or "measurement.jpg",
                media.mimeType or "image/jpeg",
                settings,
                user_id=_measurement_payer(job, media),
            )
        except paid_ai_allowance.AllowanceSpent as exc:
            await _defer_for_allowance(job, exc)
            return
        await _apply_measurement_result(job, result)
        return
    raise RuntimeError(f"Unsupported media processing job type: {job.jobType}")


def _measurement_payer(job: Any, media: Any) -> str | None:
    return _value(job, "requestedById") or _value(media, "uploadedById") or None


async def _defer_for_allowance(job: Any, exc: Any) -> None:
    try:
        attempts = int(_value(job, "attempts") or 0)
    except (TypeError, ValueError):
        attempts = 0
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": max(attempts - 1, 0),
            "runAfter": datetime.now(UTC) + timedelta(seconds=max(int(exc.retry_after), 1)),
            "error": str(exc.message)[:2000],
        },
    )


async def _apply_transcription_result(job: Any, result: dict[str, Any]) -> None:
    status = str(result.get("status") or FAILED).upper()
    message = result.get("message")
    if status == RATE_LIMITED and result.get("reason") == "budget":
        await _defer_for_budget(job, message)
        return
    if status == RATE_LIMITED:
        await db.mediafile.update(
            where={"id": job.mediaFileId},
            data={"transcriptStatus": QUEUED, "transcriptError": message},
        )
        raise RateLimited(result.get("retryAfter"))
    await db.mediafile.update(where={"id": job.mediaFileId}, data=_transcript_write(result, status))
    if status in {COMPLETED, "EMPTY"}:
        await _complete_job(job.id, result)
        if status == COMPLETED and (result.get("formattedTranscript") or result.get("text")):
            await transcript_insights.enqueue_after_transcript(job.mediaFileId)
            await audio_isolation.enqueue_if_enabled(job.mediaFileId)
    elif status == UNAVAILABLE:
        await _finalize_unavailable_job(job.id, result, message)
    else:
        raise RuntimeError(message or "Transcription failed")


async def _process_insights_job(job: Any, media: Any) -> None:
    requested_by = await transcript_insights.job_requester(job)
    if requested_by is None:
        current = await transcript_insights.current_insight(job.mediaFileId)
        if current is not None and current.editedAt is not None:
            await _complete_job(job.id, {"status": "SKIPPED", "reason": "edited"})
            return
    if not transcript_insights.select_sources(media)[0]:
        await _complete_job(job.id, {"status": "EMPTY"})
        return
    verdict = await dictation_consent.transcription_verdict(
        media, send=dictation_consent.INSIGHTS, resolve_from_stages=True
    )
    if not verdict.may_send:
        await _finalize_refused_job(job, verdict)
        return
    try:
        outcome = await transcript_pipeline.generate(media, user_id=requested_by)
    except transcript_insights.InsightsUnavailable as exc:
        await _finalize_unavailable_job(job.id, {"status": UNAVAILABLE}, str(exc))
        return
    except transcript_insights.InsightsFailed as exc:
        if getattr(exc, "hold_seconds", None):
            await _hold_insights_job(job, str(exc), exc.hold_seconds)
            return
        if exc.retry:
            raise RuntimeError(str(exc)) from None
        await _spend_insights_allowance(requested_by)
        await _finalize_unavailable_job(job.id, {"status": FAILED}, str(exc))
        return
    await _spend_insights_allowance(requested_by)
    await _complete_job(job.id, outcome)


async def _spend_insights_allowance(user_id: str | None) -> None:
    if not user_id:
        return
    await ai_verb_cap.spend(user_id, ai_verb_cap.ist_day(), transcript_insights.CAP_VERB)



async def _defer_for_budget(job: Any, message: Any) -> None:
    now = datetime.now(UTC)
    opens = schedule.next_window_start(await load_app_settings(), now)
    until = opens if opens > now else now + timedelta(hours=1)
    await db.mediafile.update(
        where={"id": job.mediaFileId},
        data={"transcriptStatus": QUEUED, "transcriptError": message},
    )
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": max(int(job.attempts or 0) - 1, 0),
            "runAfter": until,
            "error": str(message or "The transcription budget is used up.")[:2000],
        },
    )


async def _apply_measurement_result(job: Any, result: dict[str, Any]) -> None:
    status = str(result.get("status") or FAILED).upper()
    message = result.get("message")
    analysis = result.get("analysis")
    metadata = _merge_measurement_metadata(job.mediaFile.extraMetadata, result)
    await db.mediafile.update(where={"id": job.mediaFileId}, data={"extraMetadata": Json(metadata)})

    if job.productId:
        await _apply_measurement_to_product(job.productId, job.mediaFileId, status, analysis)
    if job.toolId:
        await _apply_measurement_to_tool(job.toolId, job.mediaFileId, status, analysis)

    if status == COMPLETED:
        await _complete_job(job.id, result)
    elif status == UNAVAILABLE:
        await _finalize_unavailable_job(job.id, result, message)
    else:
        raise RuntimeError(message or "Measurement analysis failed")


def _merge_measurement_metadata(existing: Any, result: dict[str, Any]) -> dict[str, Any]:
    metadata = dict(existing) if isinstance(existing, dict) else {}
    metadata["measurementProcessing"] = jsonable_encoder(result)
    return metadata


async def _apply_measurement_to_product(
    product_id: str,
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
) -> None:
    product = await db.productdocumentation.find_unique(where={"id": product_id})
    if not product:
        return
    data = _measurement_update_data(media_id, status, analysis)
    await db.productdocumentation.update(where={"id": product_id}, data=data)


async def _apply_measurement_to_tool(
    tool_id: str,
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
) -> None:
    tool = await db.tooldocumentation.find_unique(where={"id": tool_id})
    if not tool:
        return
    data = _measurement_update_data(media_id, status, analysis)
    await db.tooldocumentation.update(where={"id": tool_id}, data=data)


def _measurement_update_data(
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
) -> dict[str, Any]:
    data: dict[str, Any] = {
        "measurementImageId": media_id,
        "measurementAnalysisStatus": status,
    }
    if analysis:
        data["measurementAnalysis"] = Json(jsonable_encoder(analysis))
    return data


async def _complete_job(job_id: str, result: dict[str, Any]) -> None:
    await job_supersede.complete_job(
        job_id,
        {
            "status": COMPLETED,
            "lockedAt": None,
            "lockedBy": None,
            "openKey": None,
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": None,
        },
        client=db,
    )


async def _finalize_unavailable_job(job_id: str, result: dict[str, Any], message: Any) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": FAILED,
            "lockedAt": None,
            "lockedBy": None,
            "openKey": None,
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": str(
                message
                or "This isn't available right now. An administrator can turn it on in Settings."
            ),
        },
    )


async def _finalize_refused_job(job: Any, verdict: Any) -> None:
    await _record_transcription_refused(job.mediaFile, verdict)
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": FAILED,
            "lockedAt": None,
            "lockedBy": None,
            "openKey": None,
            "completedAt": datetime.now(UTC),
            "error": str(verdict.refusal or "")[:2000],
        },
    )


async def _handle_job_failure(job_id: str, exc: Exception) -> None:
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job:
        return
    now = datetime.now(UTC)
    error = redact_secrets(str(exc))[:2000]
    exhausted = _attempts_exhausted(job)
    retry_delay = timedelta(minutes=min(60, 2 ** max(job.attempts - 1, 0)))
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": FAILED if exhausted else QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "runAfter": now if exhausted else now + retry_delay,
            "completedAt": now if exhausted else None,
            **({"openKey": None} if exhausted else {}),
            "error": error,
        },
    )
    if exhausted:
        await _finalize_failed_clip(job, error)


async def _finalize_failed_clip(job: Any, error: str) -> None:
    if _value(job, "jobType") != TRANSCRIPTION:
        return
    media_id = _value(job, "mediaFileId")
    if not media_id:
        return
    try:
        await db.mediafile.update_many(
            where={
                "id": media_id,
                "OR": [{"transcriptText": None}, {"transcriptText": ""}],
            },
            data={"transcriptStatus": FAILED, "transcriptError": error},
        )
    except Exception as exc:  # noqa: BLE001 — the job's own terminal state is already written.
        logger.warning(
            "media_queue: transcription job %s is finished but the clip's status could not be "
            "written, so media %s may stay stuck at QUEUED (%s)",
            _value(job, "id"),
            media_id,
            exc,
        )


_HOLD_MESSAGES = (
    "Gemini is busy just now; this will be tried again.",
    "Gemini could not be reached; this will be tried again.",
    "Gemini's credit for this project has run out. Translation resumes by itself once it is topped up.",
)
REVIVE_INTERVAL_SECONDS = 600.0
_last_revive = float("-inf")


async def _hold_insights_job(job: Any, message: str, hold_seconds: int) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": max(int(job.attempts or 0) - 1, 0),
            "runAfter": datetime.now(UTC) + timedelta(seconds=int(hold_seconds)),
            "error": str(message or "")[:2000],
        },
    )


async def revive_held_insights(*, now: float | None = None) -> int:
    global _last_revive
    import time as _time

    clock = _time.monotonic() if now is None else now
    if clock - _last_revive < REVIVE_INTERVAL_SECONDS:
        return 0
    _last_revive = clock
    return await db.mediaprocessingjob.update_many(
        where={
            "jobType": TRANSCRIPT_INSIGHTS,
            "status": FAILED,
            "error": {"in": list(_HOLD_MESSAGES)},
        },
        data={"status": QUEUED, "attempts": 0, "runAfter": datetime.now(UTC), "lockedAt": None, "lockedBy": None},
    )


async def process_next_insights_jobs(
    *,
    worker_id: str = "queue-insights",
    settings: Settings | None = None,
    width: int | None = None,
) -> dict[str, int]:
    settings = settings or get_settings()
    if not transcript_insights.enabled():
        return {"processed": 0, "succeeded": 0, "failed": 0}
    app_settings = await load_app_settings()
    if transcript_pipeline.holds_translation(app_settings):
        return {"processed": 0, "succeeded": 0, "failed": 0}
    await revive_held_insights()
    lanes = max(1, int(width or getattr(settings, "media_queue_insights_concurrency", 2) or 2))
    jobs = await db.mediaprocessingjob.find_many(
        where={"status": QUEUED, "runAfter": {"lte": datetime.now(UTC)}, "jobType": TRANSCRIPT_INSIGHTS},
        include={"mediaFile": True},
        order=[{"priority": "asc"}, {"createdAt": "asc"}],
        take=lanes,
    )
    outcome = {"processed": 0, "succeeded": 0, "failed": 0}

    async def one(job: Any, n: int) -> None:
        holder = f"{worker_id}-{n}"
        locked = await _lock_job(job.id, holder)
        if locked is None:
            return
        outcome["processed"] += 1
        try:
            await _run_locked(locked, settings, holder)
            outcome["succeeded"] += 1
        except Exception as exc:  # noqa: BLE001 - the queue's own failure path
            outcome["failed"] += 1
            await _handle_job_failure(job.id, exc)

    await asyncio.gather(*(one(job, n) for n, job in enumerate(jobs)))
    return outcome


async def run_insights_lane(stop: Any, settings: Settings | None = None, *, idle_seconds: float = 10.0) -> None:
    settings = settings or get_settings()
    while not stop.is_set():
        busy = False
        try:
            result = await process_next_insights_jobs(settings=settings)
            busy = result["processed"] > 0
        except Exception:
            logger.exception("Translation lane pass failed; backing off")
        if not busy:
            try:
                await asyncio.wait_for(stop.wait(), timeout=idle_seconds)
            except TimeoutError:
                pass


async def _process_isolation_job(job: Any, media: Any) -> None:
    from app.transcription import isolation

    if transcription_paused(await load_app_settings()):
        await _hold_insights_job(job, "Transcription is paused.", 900)
        return
    try:
        result = await audio_isolation.process(media)
    except isolation.IsolationError as exc:
        if exc.hold_seconds:
            await _hold_insights_job(job, str(exc), exc.hold_seconds)
            return
        if exc.retry:
            raise RuntimeError(str(exc)) from None
        await _finalize_unavailable_job(job.id, {"status": FAILED}, str(exc))
        return
    await _complete_job(job.id, result)
