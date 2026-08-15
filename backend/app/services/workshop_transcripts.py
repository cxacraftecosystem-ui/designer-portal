"""Transcription for design-workshop audio: enqueuing it, and reading the results back.

An interview recording uploaded through ``POST /media/complete`` has been transcribed
automatically for a long time: the client asks for it, ``media_queue.enqueue_media_processing_jobs``
creates a TRANSCRIPTION job, the background worker walks the provider chain in ``services/ai.py``
and the text lands on ``MediaFile.transcriptText``. A design workshop's AUDIO fields were the one
kind of recording that never entered that pipeline — the file uploaded, the media id was stored on
the stage, and the audio sat there. A designer who recorded an artisan explaining a technique in
stage 5 had no transcript, no searchable text, and nothing to put in the report.

This module closes that gap WITHOUT a second transcription pipeline. It does two things:

* :func:`enqueue_stage_transcriptions` finds the AUDIO media a stage save just referenced and
  hands each one to the very same ``enqueue_media_processing_jobs``. Same queue, same off-peak
  window, same rate-limit backoff, same provider ranking, same "Transcribe now" admin override. A
  workshop clip and an interview clip are indistinguishable to the worker, which is the point:
  nothing here can drift from how transcription behaves everywhere else.

* :func:`load_transcript_items` reads the results back, joined to the stage and the field they
  belong to, as the flat :class:`~app.services.report_annexures.TranscriptItem` that both the
  transcripts endpoint and the report annexure consume.

WHY THE ENQUEUE HAPPENS ON THE STAGE SAVE and not at upload time. The upload knows nothing about
the workshop: a designer records into the phone's media picker, the file is uploaded through the
generic media route, and only the subsequent stage save says "this clip is the artisan's spoken
explanation for stage 5". Enqueuing at upload would mean teaching every client to pass
``processingRequests`` for exactly the right field, in three codebases, and forgetting it anywhere
is a silently untranscribed recording. Enqueuing on the save means a clip is transcribed because it
was ATTACHED to a workshop, which is the fact that actually matters, and it also picks up a
recording attached weeks after it was uploaded.

The enqueue is idempotent and cheap: a clip that already has a transcript, or already has a JOB
queued or running, is skipped. Re-saving a stage twenty times does not queue twenty jobs, and does
not re-transcribe a clip a researcher has already corrected by hand.

"A JOB", and not "a ``transcriptStatus`` that says QUEUED" — the two are not the same fact and the
difference stranded recordings for good. See :data:`_IN_FLIGHT_TRANSCRIPT_STATUSES`.
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings
from app.core.db import db
from app.services.report_annexures import TranscriptItem
from app.services.stage_schema import FieldType, StageSpec, stages

logger = logging.getLogger(__name__)

TRANSCRIPTION = "TRANSCRIPTION"

# A clip in one of these states must not be queued again, ever: the transcription question has been
# answered for it. These are ``MediaFile.transcriptStatus`` values, NOT job statuses, and the two
# vocabularies are not the same one — RATE_LIMITED is a MediaProcessingJob status and never reaches
# this column, because a throttled run writes the clip back to QUEUED so the worker finishes it
# later (``media_queue._apply_transcription_result``). Listing it here would have matched nothing.
#
# FAILED and UNAVAILABLE are deliberately absent, so a clip whose provider run failed becomes a
# candidate again on the next save of its stage. That is the retry, and the pending-job read below
# is what stops it queueing a second job while the first is still in flight.
_SETTLED_TRANSCRIPT_STATUSES = frozenset({"COMPLETED", "EMPTY"})

# QUEUED AND PROCESSING ARE NOT SETTLED, THEY ARE A CLAIM THAT A JOB EXISTS — AND THE CLAIM CAN BE
# FALSE. This list used to be folded into the set above, and that is what stranded recordings.
# ``MediaFile.transcriptStatus`` is written QUEUED at enqueue time (``media_queue`` :220) and is
# written back ONLY by ``_apply_transcription_result``, which is reached only once a provider has
# ANSWERED. Every exception raised before the answer — the consent re-read, ``get_object_bytes`` on
# an object key that no longer resolves, the provider call itself, ``load_app_settings`` — lands in
# ``_handle_job_failure``, which updates the JOB row and nothing else. So at ``attempts >=
# maxAttempts`` the job is FAILED and the clip still reads QUEUED with no error on it, for ever:
# every later stage save skipped it as "one is on its way", ``/media/complete`` would not re-enqueue
# it either (``_finish_pending_media`` requires an EMPTY ``processingJobs`` list, and a FAILED job is
# still a job), and ``report_annexures.annexure_warnings`` classifies QUEUED as "still being
# transcribed" — so the one message the designer got told them to keep waiting. Recovery existed only
# through the admin queue panel's Retry, which no designer can reach.
#
# The truthful reading of QUEUED/PROCESSING is therefore conditional: in flight IF AND ONLY IF a live
# TRANSCRIPTION job backs it. The pending-job read further down already asks exactly that question
# for its own reasons, so this costs no extra round trip — it just stops the column being believed
# over the queue table it is supposed to be describing.
#
# Do NOT "simplify" this back into one set. Doing so restores a permanent, silent loss of a
# transcript for any clip whose job died before the provider replied — an S3 blip overnight is
# enough — with every designer-facing surface still saying the transcript is coming.
_IN_FLIGHT_TRANSCRIPT_STATUSES = frozenset({"QUEUED", "PROCESSING"})

# Where an uploader might have put a clip's length. The web recorder and the Android recorder each
# write their own key into ``extraMetadata`` and neither is authoritative, so all of them are read
# and the first usable one wins. A clip with none is reported with no duration at all rather than
# with a fabricated zero — see ``report_annexures.duration_text``.
_DURATION_KEYS = ("durationSeconds", "duration_seconds", "durationSec", "duration")


def audio_field_map() -> dict[str, dict[str, str]]:
    """``entity key -> {field key: field label}`` for every AUDIO field in the registry.

    Walked from the registry rather than listed here, for the same reason
    ``design_workshops._media_ids`` walks it: an AUDIO field added to a stage tomorrow is
    transcribed with no change to this file, and a TEXT field that happens to hold something
    id-shaped is never mistaken for a recording.
    """
    out: dict[str, dict[str, str]] = {}
    for spec in stages():
        for entity in spec.entities:
            fields = {f.key: f.label for f in entity.fields if f.type is FieldType.AUDIO}
            if fields:
                out[entity.key] = fields
    return out


def _entity_stage() -> dict[str, StageSpec]:
    return {e.key: spec for spec in stages() for e in spec.entities}


def _media_ids_in(value: Any) -> list[str]:
    """The media ids held by one field value, whether it is a single id or a list of them."""
    if isinstance(value, str):
        return [value] if value.strip() else []
    if isinstance(value, (list, tuple)):
        return [str(v).strip() for v in value if isinstance(v, (str, int)) and str(v).strip()]
    return []


def audio_references(entries: list[Any]) -> dict[str, tuple[str, str, str]]:
    """``media id -> (stage key, entity key, field key)`` for every AUDIO field in ``entries``.

    One media id can be referenced from more than one field; the first reference wins, because the
    annexure prints a recording once and titling it after the place it was first attached is the
    least surprising of the available answers.
    """
    audio_fields = audio_field_map()
    entity_stage = _entity_stage()
    found: dict[str, tuple[str, str, str]] = {}
    for row in entries:
        entity_key = getattr(row, "entityKey", "")
        fields = audio_fields.get(entity_key)
        if not fields:
            continue
        stage_key = getattr(row, "stageKey", "") or getattr(
            entity_stage.get(entity_key), "key", ""
        )
        data = getattr(row, "data", None) or {}
        for field_key in fields:
            for media_id in _media_ids_in(data.get(field_key)):
                found.setdefault(media_id, (stage_key, entity_key, field_key))
    return found


# --------------------------------------------------------------------------------------
# Enqueuing
# --------------------------------------------------------------------------------------


async def enqueue_stage_transcriptions(
    entries: list[Any],
    requested_by_id: str,
    settings: Settings | None = None,
    *,
    viewer: Any,
    design_workshop_id: str | None = None,
) -> list[str]:
    """Queue a TRANSCRIPTION job for every untranscribed AUDIO clip in ``entries``.

    ``design_workshop_id`` IS THE CONSENT GATE'S EVIDENCE AND SHOULD ALWAYS BE PASSED. Every clip
    reached from here belongs to the workshop whose stage was just saved, and the artisan's recorded
    answer about that workshop is what decides whether it may be written down by a third party. The
    caller has the id in a path parameter, so nothing needs looking up — it is handed to
    ``enqueue_media_processing_jobs``, which refuses any clip whose workshop has not answered GRANTED.
    See ``dictation_consent.transcription_verdict``.

    It is nonetheless OPTIONAL rather than a keyword with no default, and that is a considered choice
    against this file's own ``load_transcript_items`` precedent. The clips themselves carry the workshop
    in their ``linkedRecordType``/``linkedRecordId`` tag — both clients file every design-workshop
    upload under it — so a caller that omits this is gated by the row's own link rather than waved
    through. Making it mandatory would buy a compile-time reminder at the cost of a runtime crash in a
    ``try``-wrapped path whose whole contract is never to fail a designer's stage save.

    Returns the media ids actually queued, so a caller can report the count without asking the
    queue again. Never raises: a stage save is a designer's fieldwork reaching the server, and a
    transcription that could not be scheduled — the media row was deleted, the queue table is
    locked, the media id on the stage points at nothing — must not be allowed to fail that write
    and send the phone away to retry a save that already succeeded. The clip is picked up on the
    next save of the stage, and the failure is in the log.

    ``viewer`` narrows the load to clips this account may actually be handed, for the same reason
    :func:`load_transcript_items` does: a media id on a stage is whatever a client wrote there, and
    a foreign AUDIO id here would spend provider credit transcribing a stranger's recording and
    write the result onto their row.

    TWO QUERIES, NOT TWO PER CLIP. The already-queued check used to be a ``find_first`` inside the
    loop — eighteen prototypes each carrying the artisan's spoken explanation meant eighteen extra
    sequential round trips on a link this repository measured at 756ms (``services/concurrency``),
    inside a save a designer is watching a spinner for in a village. The question is the same for
    every clip, so it is asked once for all of them.
    """
    from app.services.media_queue import enqueue_media_processing_jobs
    from app.services.records import owned_or_granted_where

    references = audio_references(entries)
    if not references:
        return []
    try:
        where: dict[str, Any] = {"id": {"in": sorted(references)}}
        entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
        if entitled:
            where = {"AND": [where, entitled]}
        rows = await db.mediafile.find_many(where=where)
    except Exception as exc:  # noqa: BLE001 - see the docstring: never fail the stage save
        logger.warning("Could not load workshop audio for transcription: %s", exc)
        return []

    candidates = [
        row for row in rows
        if str(getattr(row, "mediaType", "")).endswith("AUDIO")
        and str(getattr(row, "transcriptStatus", "") or "").upper()
        not in _SETTLED_TRANSCRIPT_STATUSES
        and not str(getattr(row, "transcriptText", "") or "").strip()
    ]
    if not candidates:
        return []

    # A job already in flight for a clip means another save (or the upload itself) got there
    # first. Creating a second one would transcribe the same audio twice, pay the provider twice,
    # and have the two results race to write the same column.
    #
    # THIS READ IS NOW THE ARBITER OF QUEUED/PROCESSING, so a failure of it can no longer be waved
    # through the way it was. The old comment here said an empty set means "ask the queue, never skip
    # the clip", on the grounds that `enqueue_media_processing_jobs` is idempotent — it is not: that
    # function calls `db.mediaprocessingjob.create` unconditionally (media_queue.py:203-217), so an
    # empty set genuinely does buy a second job and a second provider bill. That was harmless only
    # because a clip whose status said QUEUED had already been filtered out above. It is not harmless
    # now. So on a failed read we fall back to believing the column: an in-flight-looking clip is
    # skipped this pass and picked up on the next save, which is the direction that costs a delay
    # rather than a duplicate charge and a write race over one transcript column.
    already_queued: set[str] = set()
    pending_jobs_known = False
    try:
        pending = await db.mediaprocessingjob.find_many(
            where={
                "mediaFileId": {"in": [row.id for row in candidates]},
                "jobType": TRANSCRIPTION,
                "status": {"in": ["QUEUED", "PROCESSING"]},
            }
        )
        already_queued = {job.mediaFileId for job in pending}
        pending_jobs_known = True
    except Exception as exc:  # noqa: BLE001 - see the docstring: never fail the stage save
        logger.warning("Could not read pending transcription jobs: %s", exc)

    queued: list[str] = []
    for row in candidates:
        if row.id in already_queued:
            continue
        in_flight_claim = (
            str(getattr(row, "transcriptStatus", "") or "").upper()
            in _IN_FLIGHT_TRANSCRIPT_STATUSES
        )
        if in_flight_claim and not pending_jobs_known:
            continue
        if in_flight_claim:
            # The column says a job is on its way and the queue table says there is none. That is a
            # job that died before the provider answered (see _IN_FLIGHT_TRANSCRIPT_STATUSES) — the
            # clip is stranded, and this save is the designer-reachable recovery it never had.
            logger.info(
                "Re-queueing transcription for media %s: status %s with no live job",
                row.id,
                getattr(row, "transcriptStatus", ""),
            )
        try:
            created = await enqueue_media_processing_jobs(
                row,
                [TRANSCRIPTION],
                requested_by_id,
                settings,
                design_workshop_id=design_workshop_id,
            )
            if created:
                queued.append(row.id)
        except Exception as exc:  # noqa: BLE001 - one bad clip must not lose the others
            logger.warning("Could not queue transcription for media %s: %s", row.id, exc)
    return queued


async def enqueue_stage_transcriptions_for_stage(
    spec: StageSpec,
    entries: list[Any],
    requested_by_id: str,
    settings: Settings | None = None,
    *,
    viewer: Any,
    design_workshop_id: str | None = None,
) -> list[str]:
    """:func:`enqueue_stage_transcriptions` narrowed to the rows of one stage."""
    scoped = [row for row in entries if getattr(row, "stageKey", "") == spec.key]
    return await enqueue_stage_transcriptions(
        scoped,
        requested_by_id,
        settings,
        viewer=viewer,
        design_workshop_id=design_workshop_id,
    )


# --------------------------------------------------------------------------------------
# Reading the results back
# --------------------------------------------------------------------------------------


def _duration_seconds(extra: Any) -> int | None:
    if not isinstance(extra, dict):
        return None
    for key in _DURATION_KEYS:
        raw = extra.get(key)
        if raw in (None, ""):
            continue
        try:
            value = int(float(raw))
        except (TypeError, ValueError):
            continue
        if value > 0:
            return value
    return None


def build_transcript_item(row: Any, reference: tuple[str, str, str]) -> TranscriptItem:
    """One media row plus where it is attached, as the value object every surface reads."""
    stage_key, entity_key, field_key = reference
    spec = next((s for s in stages() if s.key == stage_key), None)
    label = ""
    if spec is not None:
        entity = next((e for e in spec.entities if e.key == entity_key), None)
        field = entity.field(field_key) if entity is not None else None
        label = field.label if field is not None else ""
    recorded = getattr(row, "recordedAt", None) or getattr(row, "createdAt", None)
    return TranscriptItem(
        media_id=row.id,
        stage_key=stage_key,
        stage_number=spec.number if spec is not None else 0,
        stage_title=spec.title if spec is not None else "",
        entity_key=entity_key,
        field_key=field_key,
        field_label=label,
        filename=getattr(row, "originalFilename", "") or "",
        recorded_at=recorded.isoformat() if recorded else "",
        duration_seconds=_duration_seconds(getattr(row, "extraMetadata", None)),
        status=str(getattr(row, "transcriptStatus", "") or ""),
        # ``transcriptText`` holds the refined, speaker-labelled form when the deployment's
        # transcription mode produced one, and the raw text otherwise; ``transcriptSummary`` holds
        # the raw text in both cases (see ``media_queue._transcript_write``). The refined form is
        # what an annexure should print — it is the one a human can read as a conversation.
        text=str(getattr(row, "transcriptText", "") or ""),
    )


async def load_transcript_items(entries: list[Any], *, viewer: Any) -> list[TranscriptItem]:
    """Every AUDIO clip referenced by ``entries``, with whatever transcript it has, in stage order.

    Includes the clips that have NO transcript yet. The transcripts endpoint has to show a designer
    that a recording exists but is still being processed — a picker that lists only finished
    transcripts looks, to someone who made six recordings and sees four, like two recordings were
    lost. The annexure filters them out at print time instead.

    ``viewer`` IS REQUIRED, AND WITHOUT IT THIS READ WAS A LEAK. A media id on a stage is whatever a
    client wrote there, and ``GET /api/media`` hands every signed-in account the id of every file in
    the repository. An AUDIO id belonging to somebody else therefore handed back that recording's
    FULL text on the stage read (``_transcripts_payload``) and its filename, duration, speaker count
    and opening line on ``GET /design-workshops/{id}/transcripts`` — and now that
    ``append_transcript_annexure`` has its call site in ``report_builder.ReportBuilder.build``, the
    whole of a stranger's transcript would be printed into a delivered document. The
    predicate is the one every download surface already uses — ``owned_or_granted_where(user,
    owner_field="uploadedById")`` — AND-composed under the id list rather than merged into it,
    because it is an ``OR`` of its own. Keyword with no default, so no future call site can omit it.
    """
    from app.services.records import owned_or_granted_where

    references = audio_references(entries)
    if not references:
        return []
    where: dict[str, Any] = {"id": {"in": sorted(references)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    rows = await db.mediafile.find_many(where=where)
    items = [build_transcript_item(row, references[row.id]) for row in rows if row.id in references]
    items.sort(key=lambda item: (item.stage_number, item.stage_key, item.label, item.media_id))
    return items


def wants_transcripts(option: bool | None, report_settings: dict[str, Any] | None) -> bool:
    """Whether this report should carry the transcript annexure.

    The request wins when it says anything at all, so a designer can generate one report with the
    transcripts and one without from the same saved settings; otherwise the stage-20
    ``includeTranscripts`` answer applies. Absent both, OFF — a report generated by a deployment
    that has never heard of this feature is unchanged.
    """
    if option is not None:
        return bool(option)
    return bool((report_settings or {}).get("includeTranscripts"))
