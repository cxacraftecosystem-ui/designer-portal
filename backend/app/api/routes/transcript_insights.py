"""Interview summaries, key quotes and translations: read, edit, regenerate.

Every route answers 404 ``{"detail": "Not found"}`` while the AI capability flag is off, and the
clients hide the section on a 404.

WHO MAY READ IS WHO MAY READ THE TRANSCRIPT. A summary, a quote and a translation are copies of the
transcript, so a media row is encoded for the viewer through ``records.public_encode`` and, when
``transcriptText`` does not survive that encoding (``_MEDIA_TAKEABLE_KEYS``), the insight does not
travel either: 404 for one item, left out of a batch.

Editing and regenerating follow ``POST /media/{id}/transcript``: the uploader or an admin, and not a
person who inspects or supervises a workshop the recording belongs to.

Regenerate checks the artisan's consent and the requester's daily AI allowance before it queues;
the allowance is spent by the worker when the run reaches a provider, and the worker checks consent
again at send time.

INCLUDED IN ``api/router.py`` BEFORE ``media.router``. ``GET /media/insights`` would otherwise be
matched by ``GET /media/{media_id}``.

Nothing here generates. Regenerate queues a job and returns 202; the worker does the work.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import JSONResponse

from app.api.consent_http import require_send
from app.core.db import db
from app.core.deps import get_current_user, is_admin
from app.schemas.transcript_insights import TranscriptInsightPatch, TranscriptInsightRegenerate
from app.services import (
    ai_verb_cap,
    design_workshop_posts,
    dictation_consent,
    transcript_insights as insights,
)
from app.services.records import media_url_scope, public_encode
from prisma import Json

router = APIRouter(prefix="/media", tags=["media"])

BATCH_MAX = 50

_NOT_FOUND = "Not found"


def require_enabled(_user: Any = Depends(get_current_user)) -> None:
    """404 while the switch is off — AFTER authentication, so a caller with no session is refused
    as on every other route rather than told whether the feature exists."""
    if not insights.enabled():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)


def may_edit(user: Any, media: Any) -> bool:
    return bool(is_admin(user) or getattr(media, "uploadedById", None) == getattr(user, "id", None))


async def readable_media_ids(rows: list[Any], viewer: Any) -> set[str]:
    """The ids among ``rows`` whose transcript ``viewer`` may read.

    Decided by the one encoder every media read leaves by, so this cannot drift from what
    ``GET /media/{id}`` hands the same viewer: whatever ``_MEDIA_TAKEABLE_KEYS`` drops from a row,
    its insight is dropped with it. One entitlement read for the whole list.
    """
    if not rows:
        return set()
    media_urls, media_workshops = await media_url_scope(viewer)
    encoded = public_encode(
        list(rows), viewer, media_urls=media_urls, media_workshops=media_workshops
    )
    return {
        str(out.get("id"))
        for out in encoded
        if isinstance(out, dict) and "transcriptText" in out
    }


async def _readable_media_or_404(media_id: str, viewer: Any) -> Any:
    media = await db.mediafile.find_unique(where={"id": media_id})
    if media is None or str(media.id) not in await readable_media_ids([media], viewer):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)
    return media


async def _view(media: Any, user: Any) -> dict[str, Any]:
    media_id = str(media.id)
    row = await insights.current_insight(media_id)
    job = (await insights.latest_jobs([media_id])).get(media_id)
    return insights.build_view(media, row, job, may_edit=may_edit(user, media))


async def _require_edit(user: Any, media: Any) -> None:
    if not may_edit(user, media):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the uploader or an admin can change this summary.",
        )
    # The transcript's own rule (2026-10-09): whoever inspects or supervises a workshop this
    # recording belongs to reads its transcript and does not rewrite it, or anything copied from it.
    await design_workshop_posts.refuse_a_holders_media_write(media, user)


def _conflict(detail: str, code: str) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_409_CONFLICT, content={"detail": detail, "code": code}
    )


@router.get("/insights", dependencies=[Depends(require_enabled)])
async def list_insights(
    ids: str = Query("", max_length=4_000),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """``{"items": {mediaId: view}}`` for up to 50 media ids, translation text left out. Ids that do
    not exist, or whose transcript this caller may not read, are left out."""
    wanted = list(dict.fromkeys(part.strip() for part in ids.split(",") if part.strip()))
    if len(wanted) > BATCH_MAX:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Ask for at most {BATCH_MAX} at a time.",
        )
    if not wanted:
        return {"items": {}}
    found_rows = await db.mediafile.find_many(where={"id": {"in": wanted}})
    readable = await readable_media_ids(found_rows, current_user)
    media_rows = [m for m in found_rows if str(m.id) in readable]
    found = [str(m.id) for m in media_rows]
    rows = await insights.current_insights(found)
    jobs = await insights.latest_jobs(found)
    items = {
        str(m.id): insights.build_view(
            m,
            rows.get(str(m.id)),
            jobs.get(str(m.id)),
            may_edit=may_edit(current_user, m),
            include_translation=False,
        )
        for m in media_rows
    }
    return {"items": items}


@router.get("/{media_id}/insights", dependencies=[Depends(require_enabled)])
async def get_insight(
    media_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    media = await _readable_media_or_404(media_id, current_user)
    return await _view(media, current_user)


@router.patch("/{media_id}/insights", dependencies=[Depends(require_enabled)], response_model=None)
async def patch_insight(
    media_id: str,
    payload: TranscriptInsightPatch,
    current_user: Any = Depends(get_current_user),
) -> Any:
    """Edit the current summary. Stamps ``editedAt``/``editedById`` from the server; the frozen
    machine output in ``generated`` is never touched."""
    media = await _readable_media_or_404(media_id, current_user)
    await _require_edit(current_user, media)
    row = await insights.current_insight(media_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_NOT_FOUND)
    if row.version != payload.version:
        return _conflict("This summary changed since you opened it.", "VERSION_CONFLICT")
    data: dict[str, Any] = {
        "editedAt": datetime.now(UTC),
        "editedById": current_user.id,
    }
    if payload.summaryLines is not None:
        data["summaryLines"] = payload.summaryLines
    if payload.quotes is not None:
        data["quotes"] = Json([quote.model_dump() for quote in payload.quotes])
    if payload.translationText is not None:
        text = payload.translationText.strip()
        data["translationText"] = text or None
        if text and row.translationStatus == insights.FAILED:
            data["translationStatus"] = insights.COMPLETED
            data["translationNote"] = None
    # Conditional on the version still being current, so a regeneration landing between the read
    # above and this write is a conflict rather than an edit on a superseded row.
    updated = await db.transcriptinsight.update_many(
        where={"id": row.id, "version": payload.version, "supersededAt": None}, data=data
    )
    if updated != 1:
        return _conflict("This summary changed since you opened it.", "VERSION_CONFLICT")
    return await _view(media, current_user)


@router.post(
    "/{media_id}/insights/regenerate",
    dependencies=[Depends(require_enabled)],
    status_code=status.HTTP_202_ACCEPTED,
    response_model=None,
)
async def regenerate_insight(
    media_id: str,
    payload: TranscriptInsightRegenerate,
    current_user: Any = Depends(get_current_user),
) -> Any:
    """Queue a new version. The run uses the caller's own AI key when they have one."""
    media = await _readable_media_or_404(media_id, current_user)
    await _require_edit(current_user, media)
    primary, _original = insights.select_sources(media)
    if not primary:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="There is no transcript to summarise yet.",
        )
    active = await insights.active_job(media_id)
    row = await insights.current_insight(media_id)
    edited = row is not None and row.editedAt is not None
    # An active automatic job skips an edited summary, so on an edited one it is no answer to this
    # ask: the job is claimed for the caller instead. Any other active job already is the answer.
    if active is not None and not (edited and getattr(active, "requestedById", None) is None):
        return await _view(media, current_user)
    if edited and not payload.discardEdits:
        return _conflict("Regenerating replaces your edits.", "EDITS_WOULD_BE_REPLACED")
    # A refusal answers 409 with its sentence; a consent store that cannot be read answers 503.
    await require_send(dictation_consent.transcription_verdict(
        media, send=dictation_consent.INSIGHTS, resolve_from_stages=True
    ))
    spent = ai_verb_cap.cap_refusal(await ai_verb_cap.load_allowance(current_user.id))
    if spent is not None:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=spent)
    # A claimed job runs on the caller's key and allowance, as a queued Regenerate would.
    if active is None or not await insights.claim_for_requester(active.id, current_user.id):
        await insights.enqueue(media_id, requested_by_id=current_user.id)
    return await _view(media, current_user)
