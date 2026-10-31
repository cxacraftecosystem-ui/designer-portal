"""designer-portal's half of the transcript pipeline: what only this portal knows.

NOT SHARED. documentation-portal has a file of the same name and the same functions, written over
its own access rules and consent gate. The shared pipeline (``services/transcript_pipeline.py``)
and routes (``api/routes/transcripts.py``) call only the functions below.

* reading follows ``GET /media/{id}``: the encoder every media read leaves by
  (``records.public_encode``); a transcript the viewer may not read is a 404;
* editing follows ``POST /media/{id}/transcript``: the uploader or a content admin, and never a
  person who inspects or supervises a workshop the recording belongs to; the view says so up front
  (``readOnly``) by the same rule the writes refuse by;
* consent is ``dictation_consent.transcription_verdict`` with :data:`dictation_consent.INSIGHTS`,
  re-read immediately before every send;
* a design workshop adds its craft, state and artisans to the recording's context.
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.db import db
from app.core.deps import is_admin as _is_admin, is_content_admin
from app.services import design_workshop_posts, dictation_consent
from app.services.records import media_url_scope, public_encode

logger = logging.getLogger(__name__)

PORTAL = "designer-portal"


def is_admin(user: Any) -> bool:
    return bool(_is_admin(user))


def may_see_spend(user: Any) -> bool:
    """Cost and the model names: ADMIN, MASTER_ADMIN and MINISTRY_ADMIN only (0.0.5)."""
    return bool(is_content_admin(user))


def may_edit(user: Any, media: Any) -> bool:
    return bool(
        is_content_admin(user) or getattr(media, "uploadedById", None) == getattr(user, "id", None)
    )


async def readable_ids(rows: list[Any], viewer: Any) -> set[str]:
    if not rows:
        return set()
    media_urls, media_workshops = await media_url_scope(viewer)
    encoded = public_encode(
        list(rows), viewer, media_urls=media_urls, media_workshops=media_workshops
    )
    return {
        str(out.get("id")) for out in encoded if isinstance(out, dict) and "transcriptText" in out
    }


#: Why a reader may not change this transcript, by ``readOnlyReason`` code (shared contract). The
#: words are the ones the app already uses: the 403 of ``POST /media/{id}/transcript`` and the
#: web's read-only note on a file of a workshop the reader oversees (``MEDIA_HELD_REASON``).
READ_ONLY_MESSAGES: dict[str, str] = {
    "NOT_EDITOR": "Only the uploader or an admin can change this transcript.",
    "HELD_POST": "Read-only: part of a workshop you oversee.",
}


async def _write_refusal(user: Any, media: Any) -> tuple[str, Any] | None:
    """``(code, the 403's detail)`` when ``user`` may not change this transcript; None when they may.

    THE ONE RULE. :func:`guard_write` refuses every transcript write by it and :func:`read_only`
    tells the view by it, so the controls a client draws can never disagree with the server.
    """
    if not may_edit(user, media):
        return "NOT_EDITOR", {"code": "FORBIDDEN", "message": READ_ONLY_MESSAGES["NOT_EDITOR"]}
    from fastapi import HTTPException  # here: the queue worker never loads FastAPI

    try:
        await design_workshop_posts.refuse_a_holders_media_write(media, user)
    except HTTPException as exc:
        return "HELD_POST", exc.detail
    return None


async def read_only(user: Any, media: Any) -> dict[str, str] | None:
    """``{code, message}`` when ``user`` may read this transcript but not change it; None when they may."""
    refusal = await _write_refusal(user, media)
    return None if refusal is None else {"code": refusal[0], "message": READ_ONLY_MESSAGES[refusal[0]]}


async def guard_write(user: Any, media: Any) -> None:
    """Raises 403 when ``user`` may not change this recording's transcript."""
    refusal = await _write_refusal(user, media)
    if refusal is not None:
        from fastapi import HTTPException, status  # here: the queue worker never loads FastAPI

        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=refusal[1])


async def may_send(media: Any) -> tuple[bool, str | None]:
    """Whether this recording's words may go to Gemini now. A failed read refuses."""
    try:
        verdict = await dictation_consent.transcription_verdict(
            media, send=dictation_consent.INSIGHTS, resolve_from_stages=True
        )
    except Exception as exc:  # noqa: BLE001 - an unreadable consent store sends nothing
        logger.warning("consent unreadable for media %s (%s)", getattr(media, "id", None), type(exc).__name__)
        return False, "The consent record could not be read, so nothing was sent."
    return bool(verdict.may_send), verdict.refusal


async def may_send_audio(media: Any) -> tuple[bool, str | None]:
    """Whether the RECORDING itself may go to ElevenLabs now (Voice Isolator). A failed read refuses."""
    try:
        verdict = await dictation_consent.transcription_verdict(media, resolve_from_stages=True)
    except Exception as exc:  # noqa: BLE001 - an unreadable consent store sends nothing
        logger.warning("consent unreadable for media %s (%s)", getattr(media, "id", None), type(exc).__name__)
        return False, "The consent record could not be read, so nothing was sent."
    return bool(verdict.may_send), verdict.refusal


async def extra_context(media: Any, context: dict[str, Any]) -> None:
    """A design workshop's craft, state and artisans, beside what the shared tables say."""
    workshop_id = getattr(media, "designWorkshopId", None)
    if not workshop_id and str(getattr(media, "linkedRecordType", "") or "").lower() == dictation_consent.MEDIA_TAG.lower():
        workshop_id = getattr(media, "linkedRecordId", None)
    context["designWorkshopId"] = workshop_id
    if not workshop_id:
        return
    try:
        rows = await db.query_raw(
            'SELECT "craftId", "craftName", "state", "district" FROM "DesignWorkshop" WHERE "id" = $1',
            workshop_id,
        )
        if rows:
            row = rows[0]
            if row.get("craftId") and all(c["id"] != row["craftId"] for c in context["crafts"]):
                name = row.get("craftName")
                found = await db.query_raw('SELECT "name" FROM "Craft" WHERE "id" = $1', row["craftId"])
                name = found[0]["name"] if found else name
                if name:
                    context["crafts"].insert(0, {"id": str(row["craftId"]), "name": name})
            elif row.get("craftName") and not context["crafts"]:
                context["crafts"].append({"id": "", "name": row["craftName"]})
            if row.get("state") and row["state"] not in context["states"]:
                context["states"].append(row["state"])
            context["district"] = context.get("district") or row.get("district")
        for artisan in await db.query_raw(
            'SELECT "id", "name" FROM "Artisan" WHERE "designWorkshopId" = $1 ORDER BY "name" LIMIT 30',
            workshop_id,
        ):
            if all(a["id"] != str(artisan["id"]) for a in context["artisans"]):
                context["artisans"].append({"id": str(artisan["id"]), "name": artisan["name"]})
    except Exception as exc:  # noqa: BLE001 - context only sharpens names and terms
        logger.warning("design workshop context unreadable (%s)", type(exc).__name__)


def entity_for(media: Any, context: dict[str, Any]) -> str:
    """The naming grammar's Entity for this recording's downloads and title."""
    if context.get("recordingId") or context.get("interviewId"):
        return "Interview"
    if context.get("designWorkshopId"):
        return "Workshop"
    if getattr(media, "artisanId", None):
        return "Artisan"
    return "Interview"


def kind_label(media: Any, context: dict[str, Any]) -> str:
    """The first word of the transcript's title."""
    if context.get("recordingId") or context.get("interviewId"):
        return "Interview"
    if context.get("designWorkshopId") and str(getattr(media, "mediaType", "")).endswith("AUDIO"):
        return "Dictation"
    return "Interview" if context.get("artisans") else "Recording"


def audio_url(media: Any, *, original: bool = False) -> str | None:
    """The player's signed URL: the Voice Isolator copy when there is one (0.0.5), else the recording
    as made. ``original=True`` is always the recording as made."""
    cleaned = None if original else getattr(media, "cleanedObjectKey", None)
    try:
        from app.services.s3 import presign_get_url

        return presign_get_url(
            cleaned or media.objectKey,
            filename=media.originalFilename or "recording",
            mime_type=(getattr(media, "cleanedMimeType", None) if cleaned else None) or media.mimeType or "audio/mpeg",
            disposition="inline",
        )
    except Exception as exc:  # noqa: BLE001 - the transcript reads without playback
        logger.warning("could not sign the audio of media %s (%s)", getattr(media, "id", None), type(exc).__name__)
        return None


async def requeue_transcription(media: Any) -> None:
    """Retry for a recording whose transcription never finished: back on the queue."""
    from app.services import media_queue

    await media_queue.enqueue_media_processing_jobs(
        media, ["TRANSCRIPTION"], getattr(media, "uploadedById", None) or ""
    )


async def transcribe_now(media: Any) -> dict[str, Any]:
    """The admin "Transcribe now", with this portal's refusals as contract errors."""
    from app.services import media_queue, transcription_jobs
    from app.transcription.transcript.errors import PipelineRefused

    try:
        return await media_queue.transcribe_media_now(media)
    except dictation_consent.SendRefused as exc:
        raise PipelineRefused("CONSENT_REFUSED", str(exc) or "Consent was refused.") from None
    except transcription_jobs.TranscriptionPaused as exc:
        raise PipelineRefused("TRANSCRIPTION_PAUSED", str(exc)) from None
