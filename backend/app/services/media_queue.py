import asyncio
import logging
import os
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi.encoders import jsonable_encoder

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import dictation_consent
from app.services.ai import (
    analyze_measurement_image_bytes,
    redact_secrets,
    refine_transcript_text,
    transcribe_audio_bytes,
)
from app.services.app_settings import (
    load_app_settings,
    transcription_mode,
    within_processing_window,
)
from app.services.s3 import get_object_bytes
from prisma import Json

logger = logging.getLogger(__name__)

TRANSCRIPTION = "TRANSCRIPTION"
MEASUREMENT = "MEASUREMENT"
QUEUED = "QUEUED"
PROCESSING = "PROCESSING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
UNAVAILABLE = "UNAVAILABLE"
RATE_LIMITED = "RATE_LIMITED"

STALE_PROCESSING_AFTER = timedelta(minutes=30)

# Rate-limit backoff. When transcription is throttled (HTTP 429/503), we pause transcription for a
# growing cooldown and requeue the job WITHOUT consuming an attempt — so every clip is transcribed
# eventually instead of burning out its retries. Measurement jobs keep flowing through the cooldown.
RATE_LIMIT_BASE_SECONDS = 30
RATE_LIMIT_MAX_SECONDS = 900
# Outside the off-peak window, transcription still runs when the box is idle — 1-minute load average
# below this fraction of the CPU count — so spare daytime capacity is used instead of waiting for night.
IDLE_LOAD_FACTOR = 0.6

# Single elected worker (see main.py), so module-level cooldown state is safe.
_rate_limit_cooldown_until: datetime | None = None
_consecutive_rate_limits = 0


class RateLimited(Exception):
    """Raised when a transcription call is throttled. Carries an optional provider Retry-After (s)."""

    def __init__(self, retry_after: float | None = None) -> None:
        super().__init__("Transcription rate-limited")
        self.retry_after = retry_after


def _server_is_idle() -> bool:
    """True when the host has spare capacity (low 1-minute load average). Used to let transcription
    run outside the off-peak window when nothing else is keeping the box busy. Conservatively False
    where load average is unavailable (e.g. Windows dev)."""
    try:
        load1 = os.getloadavg()[0]
    except (OSError, AttributeError):
        return False
    return load1 < (os.cpu_count() or 1) * IDLE_LOAD_FACTOR


def _transcription_in_cooldown(now: datetime | None = None) -> bool:
    return _rate_limit_cooldown_until is not None and (now or datetime.now(UTC)) < _rate_limit_cooldown_until


def _enter_rate_limit_cooldown(retry_after: float | None) -> None:
    global _rate_limit_cooldown_until, _consecutive_rate_limits
    _consecutive_rate_limits += 1
    if retry_after and retry_after > 0:
        delay = max(retry_after, RATE_LIMIT_BASE_SECONDS)
    else:
        delay = min(RATE_LIMIT_BASE_SECONDS * (2 ** (_consecutive_rate_limits - 1)), RATE_LIMIT_MAX_SECONDS)
    _rate_limit_cooldown_until = datetime.now(UTC) + timedelta(seconds=delay)


def _clear_rate_limit_cooldown() -> None:
    global _rate_limit_cooldown_until, _consecutive_rate_limits
    _rate_limit_cooldown_until = None
    _consecutive_rate_limits = 0


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


def _job_requests(media: Any, requested: list[str] | None) -> set[str]:
    media_type = str(_value(media, "mediaType") or "").upper()
    normalized = {str(item).strip().upper() for item in requested or [] if str(item).strip()}
    if requested is None and media_type == "AUDIO":
        normalized.add(TRANSCRIPTION)
    if media_type != "AUDIO":
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
    """Queue the processing this upload asked for. **Transcription is gated on the artisan's consent.**

    ================================================================================================
    THE HOLE THIS CLOSES, AND IT WAS THE WIDEST DOOR ONTO A PAID PROVIDER IN THE REPOSITORY
    ================================================================================================

    ``DesignWorkshop.dictationConsent`` — the artisan's recorded answer to *"may recordings and
    dictation from this workshop leave the phone to be written down by a transcription service outside
    it"* — was read in exactly one place: the gate on ``POST /design-workshops/{id}/dictate``. This
    function is where the recordings actually go. Every design-workshop AUDIO clip reaches
    ``ai.transcribe_audio_bytes`` through a job created HERE, by one of two callers, and neither read
    the column:

    * ``POST /media/complete`` — and note that the auto-enqueue in :func:`_job_requests` is NOT the
      live path. **Both shipped clients ask for TRANSCRIPTION explicitly** on every audio upload
      (``WorkshopRepository.uploadDesignWorkshopMedia`` sends ``listOf("TRANSCRIPTION")``;
      ``frontend/lib/media.ts``'s ``resolveProcessing`` adds it whenever ``transcribeAudio``, which
      defaults to true). So a gate placed only on the ``requested is None`` branch would have closed a
      door nobody uses and left the one the fleet drives through wide open. The gate is therefore on
      the REQUEST, not on how the request was arrived at.
    * ``workshop_transcripts.enqueue_stage_transcriptions``, from ``save_stage`` — which reported
      ``"transcriptionsQueued": 1`` to a designer on a workshop whose consent was NOT_RECORDED, in the
      same minute that ``POST /{id}/dictate`` refused the same workshop with a 409.

    **THE FAILURE IN ONE SENTENCE:** an artisan who was asked and said no had their eleven-minute
    recording sent to ElevenLabs, Deepgram or OpenAI anyway, because the answer they gave governed the
    thirty-second dictation and not the interview — and the app told them so
    (``DwDictationConsent.kt``'s ``DW_CONSENT_MEDIA_NOT_GATED``: *"this answer was not given the power
    to stop that. Until it is, do not attach a recording of anybody who has said no."*). That sentence
    is now false in the artisan's favour, and correcting it is an Android change this lane cannot make.

    ================================================================================================
    WHY THE GATE IS HERE AND NOT IN THE TWO ROUTES
    ================================================================================================

    One choke point, because "one gate with a door beside it is not a gate" is the lesson this feature
    has already paid for twice — ``POST /design-workshops/dictate`` retired to a 410, and
    ``POST /media/transcribe`` narrowed to admin. Every TRANSCRIPTION job in this system is created by
    the loop below. A check in ``media.py`` and a check in ``workshop_transcripts.py`` would be two
    places to forget, and a third caller added next season would arrive ungated by default. Here, a new
    caller is gated whether or not its author has read this file.

    ``design_workshop_id`` is for a caller that knows the workshop from something other than the file —
    ``save_stage`` holds it in a path parameter. It is optional rather than required because
    ``/media/complete`` genuinely does not know it and must not be made to invent one; the row's own
    ``linkedRecordType``/``linkedRecordId`` tag answers for that path. See
    ``dictation_consent.MEDIA_TAG``.

    **MEASUREMENT IS NOT GATED, deliberately.** It sends a photograph of an object on a grid sheet to a
    vision model. That is not a recording of anybody's voice, no consent question in this repository
    asks about it, and ``ENQUEUEABLE_PROCESSING_REQUESTS`` already refuses it at the only route that
    could request it. Gating it on an answer nobody gave about photographs would be the same overreach
    §6 of this module's docstring warns about, in the other direction.
    """
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
        job = await db.mediaprocessingjob.create(
            data={
                "jobType": request,
                "status": QUEUED,
                "priority": 50 if request == TRANSCRIPTION else 70,
                "maxAttempts": max(settings.media_queue_job_max_attempts, 1),
                "mediaFileId": media_id,
                "requestedById": requested_by_id,
                **target_data,
            }
        )
        created_jobs.append(job)

    if TRANSCRIPTION in requests:
        await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": QUEUED})

    if MEASUREMENT in requests:
        await _mark_measurement_queued(media_id, target_data)

    return created_jobs


async def _record_transcription_refused(media: Any, verdict: Any) -> None:
    """Write the consent refusal onto the clip, so a designer is TOLD rather than left with silence.

    **THE ALTERNATIVE WAS THE REAL RISK OF FAILING CLOSED.** A recording that is simply never
    transcribed looks exactly like a recording the worker has not got to yet: the transcripts screen
    shows it with no text, the annexure leaves it out, and ``report_annexures.missing_transcript_note``
    counts it among the clips that "have no transcript yet". A designer would wait for a night that
    never comes, and the feature would be reported as broken rather than as refused — which is how a
    privacy gate gets ripped out by somebody debugging a support ticket.

    ``FAILED`` and not a new status token. It is already in this module's vocabulary, both clients
    already render it, and — decisively — it is deliberately absent from
    ``workshop_transcripts._SETTLED_TRANSCRIPT_STATUSES``, so the clip becomes a candidate again on the
    next save of its stage. That is exactly the behaviour a refusal needs: **the moment the artisan
    changes their mind and somebody records GRANTED, the next stage save picks the recording up.** A
    terminal status would have needed a person to find every refused clip by hand.

    The sentence in ``transcriptError`` is the gate's own field copy, unabbreviated. Never raises: this
    is a note about a send that did not happen, and failing to write the note must not turn into a
    failed upload or a failed stage save.

    **IT WILL NOT WRITE OVER A TRANSCRIPT THAT ALREADY EXISTS**, which is this file's own hardest-won
    lesson applied to a new writer. ``_transcript_write`` records what happened the last time something
    here wrote a status without looking: *"writing that empty result verbatim NULLED OUT a transcript
    that was already stored … destroyed it the moment the provider was down, and nothing else holds a
    copy."* The same shape is reachable here — a clip transcribed months ago under a grant since
    withdrawn, whose uploader retries ``/media/complete`` — and marking that row FAILED would leave a
    stored transcript sitting under a status saying it failed, which is a lie about the row and hides it
    from every screen that filters on the status. Nothing is sent either way; the refusal held. What is
    skipped is only the note, on the one row where the note would be false.
    """
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
) -> dict[str, int]:
    settings = settings or get_settings()
    batch_size = max(1, limit or settings.media_queue_batch_size)
    await recover_stale_processing_jobs()
    now = datetime.now(UTC)
    where: dict[str, Any] = {"status": QUEUED, "runAfter": {"lte": now}}
    # When transcription may run: inside the configured off-peak window, OR whenever the server is idle
    # (spare capacity) so we don't wait for the night when nothing else is happening — but never while a
    # rate-limit cooldown is in effect. Lighter MEASUREMENT jobs always run.
    app_settings = await load_app_settings()
    allow_transcription = (
        (within_processing_window(app_settings) or _server_is_idle())
        and not _transcription_in_cooldown(now)
    )
    if not allow_transcription:
        where["jobType"] = {"not": TRANSCRIPTION}
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
            await _process_job(locked, settings)
            succeeded += 1
            if locked.jobType == TRANSCRIPTION:
                _clear_rate_limit_cooldown()  # a clean success ends any backoff
        except RateLimited as exc:
            # Throttled: requeue this job without consuming an attempt, then pause transcription for a
            # cooldown and stop draining the batch so we don't keep hammering a throttled provider.
            await _defer_rate_limited_job(job, exc.retry_after)
            _enter_rate_limit_cooldown(exc.retry_after)
            break
        except Exception as exc:  # noqa: BLE001
            failed += 1
            await _handle_job_failure(job.id, exc)
    return {"processed": processed, "succeeded": succeeded, "failed": failed}


async def _defer_rate_limited_job(job: Any, retry_after: float | None) -> None:
    """Requeue a throttled transcription job, restoring its pre-lock attempt count so rate limiting
    never exhausts its retries — it will be picked up again after the cooldown."""
    delay = retry_after if (retry_after and retry_after > 0) else RATE_LIMIT_BASE_SECONDS
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "attempts": job.attempts,
            "runAfter": datetime.now(UTC) + timedelta(seconds=delay),
            "error": "Rate-limited; awaiting cooldown before automatic retry.",
        },
    )


async def transcribe_media_now(media: Any, settings: Settings | None = None) -> dict[str, Any]:
    """Transcribe one audio media file immediately and store the result, applying the transcription
    mode configured in the settings page (RAW / REFINED / REFINED+TRANSLATED). This is the admin
    "Transcribe now" action: it bypasses the queue AND the off-peak window so the result is produced on
    the spot. Mirrors the worker's TRANSCRIPTION path so a manual run and a queued run agree. Never
    raises on an AI failure — it records the status/error on the media row and returns the result so the
    caller can surface it.

    **IT BYPASSES THE QUEUE, SO IT NEEDS THE QUEUE'S GATE OF ITS OWN.** This function reaches
    ``transcribe_audio_bytes`` without passing through ``_process_job``, which is precisely the shape of
    the two doors this feature has already had to close — the retired id-less ``/dictate`` and
    ``POST /media/transcribe``. An admin pressing "Transcribe now" on a recording whose artisan refused
    would otherwise send it, and would be the one caller for whom nothing on screen said so.

    **AND AN ADMIN IS NOT AN EXCEPTION HERE, which is a departure from this repository's own note that
    "the consent gate has never been a refusal an admin could meet".** That observation is true of the
    routes it was written about, and it is an argument about PERMISSION: an admin is inside
    ``DESIGN_WORKSHOP_ROLES`` and can record a GRANTED decision themselves, so the gate cannot restrain
    them. It is not an argument for sending. Recording the grant is a visible, attributed, logged act
    with the artisan's name on the row; sending anyway is the same outcome with no record that anybody
    decided it. An admin who believes the artisan agreed has a route for saying so, and this refusal
    names it. :class:`dictation_consent.SendRefused` is raised — see that class for why not a result."""
    settings = settings or get_settings()
    # ``resolve_from_stages`` for the drain's reason above, and this route needed it just as badly:
    # measured on the running API, an admin pressing "Transcribe now" on an untagged clip that a stage
    # of a REFUSED workshop names got HTTP 200 and the recording went to all three providers.
    verdict = await dictation_consent.transcription_verdict(media, resolve_from_stages=True)
    if not verdict.may_send:
        raise dictation_consent.SendRefused(verdict)
    media_id = str(_value(media, "id"))
    await db.mediafile.update(where={"id": media_id}, data={"transcriptStatus": PROCESSING})
    content = await asyncio.to_thread(get_object_bytes, _value(media, "objectKey"))
    result = await transcribe_audio_bytes(
        content,
        _value(media, "originalFilename") or "recording.webm",
        _value(media, "mimeType") or "audio/webm",
        settings,
    )
    mode = transcription_mode(await load_app_settings())
    if result.get("status") == "COMPLETED" and mode in {"REFINED", "REFINED_TRANSLATED"} and result.get("text"):
        refined = await refine_transcript_text(result.get("text"), mode == "REFINED_TRANSLATED", settings)
        if refined.get("status") == "COMPLETED" and refined.get("refined"):
            result = {
                **result,
                "formattedTranscript": refined["refined"],
                "transcriptionMode": mode,
                "translated": mode == "REFINED_TRANSLATED",
            }
    status = str(result.get("status") or FAILED).upper()
    if status == RATE_LIMITED:
        # Throttled even on a manual run — keep it QUEUED so the background worker finishes it later.
        await db.mediafile.update(
            where={"id": media_id},
            data={"transcriptStatus": QUEUED, "transcriptError": result.get("message")},
        )
        # "Queued" must never be a dead end: if no queue job exists for this clip (e.g. it was
        # uploaded without a transcription request), create one so the worker actually picks it up.
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
            # requestedById is a real FK to User, so fall back to NULL (not a placeholder string)
            # when the uploader is unknown.
            uploader_id = _value(media, "uploadedById")
            if uploader_id:
                job_data["requestedById"] = str(uploader_id)
            await db.mediaprocessingjob.create(data=job_data)
        return result
    await db.mediafile.update(where={"id": media_id}, data=_transcript_write(result, status))
    return result


def _transcript_write(result: dict[str, Any], status: str) -> dict[str, Any]:
    """The MediaFile columns to write for a finished transcription run.

    A FAILED or UNAVAILABLE run carries ``text: None`` (see services/ai.py), and writing that empty
    result verbatim NULLED OUT a transcript that was already stored: re-running transcription on a
    clip that already had a good transcript — the admin "Transcribe now" button, or a requeued job —
    destroyed it the moment the provider was down, and nothing else holds a copy. A run that produced
    no text therefore records only its status and its error; the previous transcript stays, and the
    failure is still visible in ``transcriptStatus``/``transcriptError``. Clearing a transcript stays
    a deliberate act: POST /media/{id}/transcript is the one path that overwrites stored text.
    """
    data: dict[str, Any] = {
        "transcriptStatus": status,
        "transcriptError": None if status in {COMPLETED, "EMPTY"} else result.get("message"),
    }
    transcript = result.get("formattedTranscript") or result.get("text")
    if transcript:
        data["transcriptText"] = transcript
        data["transcriptSummary"] = result.get("text")
    return data


async def recover_stale_processing_jobs() -> int:
    cutoff = datetime.now(UTC) - STALE_PROCESSING_AFTER
    stale_jobs = await db.mediaprocessingjob.find_many(
        where={"status": PROCESSING, "lockedAt": {"lt": cutoff}},
        take=25,
    )
    for job in stale_jobs:
        await db.mediaprocessingjob.update(
            where={"id": job.id},
            data={
                "status": QUEUED,
                "lockedAt": None,
                "lockedBy": None,
                "runAfter": datetime.now(UTC),
                "error": "Recovered after worker interruption.",
            },
        )
    return len(stale_jobs)


async def _lock_job(job_id: str, worker_id: str) -> Any | None:
    # Read first for the attempts counter, then claim ATOMICALLY: the update only matches while the
    # row is still QUEUED, so of two concurrent claimers exactly one sees count==1 and the loser
    # backs off with None instead of double-processing the job.
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
    return await db.mediaprocessingjob.find_unique(where={"id": job_id}, include={"mediaFile": True})


async def _process_job(job: Any, settings: Settings) -> None:
    media = job.mediaFile
    if job.jobType == TRANSCRIPTION:
        # RE-READ AT THE MOMENT OF SENDING, AND THIS IS THE CHECK THAT MAKES A WITHDRAWAL MEAN
        # SOMETHING. The enqueue gate is not sufficient on its own, and the module docstring of
        # `dictation_consent` names the exact scenario: "An artisan agrees on the 3rd, nine dictations
        # go to a provider over the following week, the artisan changes their mind on the 9th." A job
        # queued under the grant sits in this table until the off-peak window opens; without this line
        # the withdrawal on the 9th would stop nothing that was already queued, and the recordings
        # would leave after the artisan had said stop.
        #
        # It costs one primary-key read per transcription job, on a path that is about to spend a
        # provider round trip and read the whole file out of object storage — and it is BEFORE the
        # bytes are fetched, so a refused job does not even pull the audio.
        #
        # ``resolve_from_stages`` BECAUSE A JOB CARRIES NO WORKSHOP AND THE CLIP MAY NOT EITHER, which
        # is the hole this argument closes and it was measured on the running API rather than reasoned
        # about: a clip uploaded through ``POST /media/complete`` with no ``designWorkshop`` tag is
        # queued here with nothing anywhere naming a workshop — correctly, since at that instant none
        # does — and is written into a stage field only afterwards, which is the ordinary order of
        # events. From then on the stage says whose recording it is. Without this the drain read only
        # the absent tag, answered NOT_WORKSHOP_MATERIAL, and handed the recording of an artisan who
        # had REFUSED to ElevenLabs, Deepgram and OpenAI in turn. See
        # ``dictation_consent.stage_attached_workshop_ids``; the extra read happens only for a clip
        # that carries no tag at all, which is never a clip either shipped client uploaded.
        verdict = await dictation_consent.transcription_verdict(media, resolve_from_stages=True)
        if not verdict.may_send:
            await _finalize_refused_job(job, verdict)
            return
    content = await asyncio.to_thread(get_object_bytes, media.objectKey)
    if job.jobType == TRANSCRIPTION:
        result = await transcribe_audio_bytes(
            content,
            media.originalFilename or "recording.webm",
            media.mimeType or "audio/webm",
            settings,
        )
        # Apply the configured transcription mode: RAW keeps the plain transcript; REFINED rewrites it
        # into a clean interviewer/interviewee dialogue; REFINED_TRANSLATED also translates to English.
        # The refined text is stored as the transcript (raw stays in transcriptSummary) and still lands
        # COMPLETED — i.e. awaiting human approval through the existing transcript-approval flow.
        mode = transcription_mode(await load_app_settings())
        if result.get("status") == "COMPLETED" and mode in {"REFINED", "REFINED_TRANSLATED"} and result.get("text"):
            refined = await refine_transcript_text(result.get("text"), mode == "REFINED_TRANSLATED", settings)
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
        result = await analyze_measurement_image_bytes(
            content,
            media.originalFilename or "measurement.jpg",
            media.mimeType or "image/jpeg",
            settings,
        )
        await _apply_measurement_result(job, result)
        return
    raise RuntimeError(f"Unsupported media processing job type: {job.jobType}")


async def _apply_transcription_result(job: Any, result: dict[str, Any]) -> None:
    status = str(result.get("status") or FAILED).upper()
    message = result.get("message")
    if status == RATE_LIMITED:
        # Leave the clip QUEUED and let the queue back off + retry without consuming an attempt.
        await db.mediafile.update(
            where={"id": job.mediaFileId},
            data={"transcriptStatus": QUEUED, "transcriptError": message},
        )
        raise RateLimited(result.get("retryAfter"))
    await db.mediafile.update(
        where={"id": job.mediaFileId}, data=_transcript_write(result, status)
    )
    if status in {COMPLETED, "EMPTY"}:
        await _complete_job(job.id, result)
    elif status == UNAVAILABLE:
        await _finalize_unavailable_job(job.id, result, message)
    else:
        raise RuntimeError(message or "Transcription failed")


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
    data = _measurement_update_data(media_id, status, analysis, product)
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
    data = _measurement_update_data(media_id, status, analysis, tool)
    await db.tooldocumentation.update(where={"id": tool_id}, data=data)


def _measurement_update_data(
    media_id: str,
    status: str,
    analysis: dict[str, Any] | None,
    record: Any,
) -> dict[str, Any]:
    data: dict[str, Any] = {
        "measurementImageId": media_id,
        "measurementAnalysisStatus": status,
    }
    if analysis:
        data["measurementAnalysis"] = Json(jsonable_encoder(analysis))
        length = _decimal_or_none(analysis.get("lengthInches"))
        breadth = _decimal_or_none(analysis.get("breadthInches"))
        if length is not None and _value(record, "lengthInches") is None:
            data["lengthInches"] = str(length)
        if breadth is not None and _value(record, "breadthInches") is None:
            data["breadthInches"] = str(breadth)
    return data


def _decimal_or_none(value: Any) -> Decimal | None:
    if value in (None, ""):
        return None
    try:
        return Decimal(str(value)).quantize(Decimal("0.01"))
    except (InvalidOperation, ValueError):
        return None


async def _complete_job(job_id: str, result: dict[str, Any]) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": COMPLETED,
            "lockedAt": None,
            "lockedBy": None,
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": None,
        },
    )


async def _finalize_unavailable_job(job_id: str, result: dict[str, Any], message: Any) -> None:
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": FAILED,
            "lockedAt": None,
            "lockedBy": None,
            "completedAt": datetime.now(UTC),
            "result": Json(jsonable_encoder(result)),
            "error": str(message or "Required AI API key is not configured."),
        },
    )


async def _finalize_refused_job(job: Any, verdict: Any) -> None:
    """Close a queued transcription that consent will not permit. Terminal, and it says why.

    ``FAILED`` immediately and NOT a retry, which is the opposite of every other failure in this file.
    The retry ladder above exists for conditions that clear by themselves — a provider that is down, a
    rate limit, a worker that died holding a lock. This one clears only when a person records a
    different answer, and re-attempting it every few minutes until ``maxAttempts`` is spent would put
    the same refusal in the log three times and tell a reader that something was being retried.

    IT DOES NOT RAISE, so the batch keeps draining. A refused clip is not an error in the queue; it is
    the queue working. Raising here would count it as a failure in the pass's tally, page whoever reads
    that tally, and — through ``_handle_job_failure`` — reschedule it.

    The clip itself is written by :func:`_record_transcription_refused`, which leaves it eligible for a
    later pass once the answer changes.
    """
    await _record_transcription_refused(job.mediaFile, verdict)
    await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": FAILED,
            "lockedAt": None,
            "lockedBy": None,
            "completedAt": datetime.now(UTC),
            "error": str(verdict.refusal or "")[:2000],
        },
    )


async def _handle_job_failure(job_id: str, exc: Exception) -> None:
    job = await db.mediaprocessingjob.find_unique(where={"id": job_id})
    if not job:
        return
    now = datetime.now(UTC)
    # This column is served with the job, and the exception reaching it is ANY exception the job
    # raised — including one built by a library out of a URL that carried a credential. services/ai
    # keeps its own results clean; this is the guard for everything else that can fail in here.
    error = redact_secrets(str(exc))[:2000]
    exhausted = job.attempts >= job.maxAttempts
    retry_delay = timedelta(minutes=min(60, 2 ** max(job.attempts - 1, 0)))
    await db.mediaprocessingjob.update(
        where={"id": job_id},
        data={
            "status": FAILED if exhausted else QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "runAfter": now if exhausted else now + retry_delay,
            "completedAt": now if exhausted else None,
            "error": error,
        },
    )
