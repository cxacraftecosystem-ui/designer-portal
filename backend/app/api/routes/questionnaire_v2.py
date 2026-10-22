"""Questionnaire v2 routes on designer-portal: the same contract as documentation-portal's module of
this name (UNIFY_2026-10-21.md 4.1), over the shared package ``app/questionnaire``.

Differences from documentation-portal, all of them this portal's own rules:

* a session on a DESIGN workshop (``designWorkshopId``) is checked with the design workshop's
  filing rule (``record_design_workshop.assert_may_file_under``) before it is created or moved;
* the artisan consent routes are ``api/routes/artisans.py``'s (the same ledger rows); this module
  adds only the artisan record;
* the consent gate and the transcription enqueue are ``services/questionnaire_consent`` and
  ``services/questionnaire_transcription`` (the package's ports).

``/api/questionnaire/...`` (sessions, coverage, recordings, answers, inbox, corpus, config) and
``/api/artisans/{id}/consent|record``. The media routes (presign, multipart, uploads, complete,
download) live in ``routes/media.py`` and call ``app/questionnaire/recordings.py``.

EVERY v2 ROUTE ANSWERS 404 WHILE ``QUESTIONNAIRE_V2`` IS FALSE, except ``GET /config`` (which says
so) and the consent routes (consent gates outside sends whatever screens a client draws). Bodies are
parsed here rather than by FastAPI so that every refusal has the contract's shape,
``{"detail": {"code", "message", ...}}``, and a ``title`` gets ``TITLE_IS_GENERATED``.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Query, Request, status
from fastapi.responses import JSONResponse, RedirectResponse, Response
from pydantic import BaseModel, ValidationError

from app.core.config import get_settings
from app.core.deps import get_current_user, require_dataset_downloader
from app.questionnaire import (
    answers,
    authoring,
    bindings,
    corpus,
    coverage,
    inbox,
    record,
    recordings,
    respondents,
    schemas as S,
    sessions,
    structure,
)
from app.questionnaire.common import fail
from app.questionnaire.recordings import MULTIPART_THRESHOLD, PART_SIZE
from app.services import questionnaire_transcription as transcription
from app.services.record_design_workshop import assert_may_file_under

CONTRACT_VERSION = 1

router = APIRouter(prefix="/questionnaire", tags=["questionnaire-v2"])
artisans_router = APIRouter(prefix="/artisans", tags=["questionnaire-v2"])


def v2_enabled() -> bool:
    return bool(get_settings().questionnaire_v2)


async def require_v2(_: Any = Depends(get_current_user)) -> None:
    """404 while QUESTIONNAIRE_V2 is off, asked AFTER the caller is signed in, so a stranger is
    refused (401) before being told whether the route exists (tests/test_anonymous_route_sweep.py)."""
    if not v2_enabled():
        fail("NOT_FOUND")


async def parse(request: Request, model: type[BaseModel], *, forbid_title: bool = False) -> Any:
    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 - a body that is not JSON is a validation failure
        fail("VALIDATION_FAILED", "The body is not JSON.")
    if forbid_title and isinstance(body, dict) and "title" in body:
        fail("TITLE_IS_GENERATED")
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        errors = [{"loc": [str(p) for p in e.get("loc", ())], "msg": e.get("msg")} for e in exc.errors()]
        fail("VALIDATION_FAILED", errors[0]["msg"] if errors else None, errors=errors)


# ── config ─────────────────────────────────────────────────────────────────────────────────────
@router.get("/config")
async def questionnaire_config(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    settings = get_settings()
    return {
        "contractVersion": CONTRACT_VERSION,
        "v2Enabled": v2_enabled(),
        "transcriptionPaused": await transcription.is_paused(),
        "noteMaxChars": S.NOTE_MAX,
        "multipartThresholdBytes": MULTIPART_THRESHOLD,
        "partSizeBytes": PART_SIZE,
        "snapshotPollSeconds": 60,
        "joinCodeLength": 6,
        # The version NAME was reset to 0.0.1 (21 Oct 2026), so a name minimum can no longer be
        # compared (audit C7): always null; clients compare their build number with the two below.
        "minAppVersionForCreate": None,
        "minAndroidVersionCodeForCreate": settings.questionnaire_min_android_version_code_for_create,
        "minWebVersionCodeForCreate": settings.questionnaire_min_web_version_code_for_create,
        # UNIFY 4.1: which portal answers, and the create cutoff per platform in one object.
        "portal": "designer-portal",
        "minVersionCode": {
            "android": settings.questionnaire_min_android_version_code_for_create,
            "web": settings.questionnaire_min_web_version_code_for_create,
        },
    }


# ── sessions ───────────────────────────────────────────────────────────────────────────────────
V2 = [Depends(require_v2)]


@router.get("/sessions/open-match", dependencies=V2)
async def sessions_open_match(
    questionnaireId: str, artisanIds: list[str] = Query(default=[]), workshopId: str | None = None, date: str | None = None,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    ids = [a for value in artisanIds for a in value.split(",") if a]
    return await sessions.open_match(questionnaire_id=questionnaireId, workshop_id=workshopId, artisan_ids=ids, day=date)


@router.post("/sessions", dependencies=V2)
async def sessions_create(request: Request, user: Any = Depends(get_current_user)) -> JSONResponse:
    payload = await parse(request, S.SessionCreate, forbid_title=True)
    await assert_may_file_under(payload.designWorkshopId, user)
    session, created = await sessions.create_session(payload, user)
    return JSONResponse(session, status_code=status.HTTP_201_CREATED if created else status.HTTP_200_OK)


@router.get("/sessions", dependencies=V2)
async def sessions_list(
    workshopId: str | None = None, questionnaireId: str | None = None, artisanId: str | None = None,
    researcherId: str | None = None, dateFrom: str | None = None, dateTo: str | None = None, search: str | None = None,
    cursor: str | None = None, limit: int = Query(20, ge=1, le=100), deleted: bool = False,
    user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    # The Deleted list (soft-deleted sessions, owner decision 3) is the content admins'.
    if deleted and not authoring.may_list_deleted_sessions(user):
        fail("FORBIDDEN", "Only an admin can see deleted sessions.")
    return await sessions.list_sessions(
        workshopId=workshopId, questionnaireId=questionnaireId, artisanId=artisanId, researcherId=researcherId,
        dateFrom=dateFrom, dateTo=dateTo, search=search, cursor=cursor, limit=limit, deleted=deleted,
    )


@router.post("/sessions/join", dependencies=V2)
async def sessions_join(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.JoinRequest)
    return await sessions.join(payload.joinCode, user)


@router.post("/sessions/join-by-key", dependencies=V2)
async def sessions_join_by_key(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.JoinByKeyRequest)
    return await sessions.join_by_key(payload.clientKey, user)


@router.post("/sessions/continue", dependencies=V2)
async def sessions_continue(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ContinueRequest)
    return await sessions.continue_sessions(payload, user)


@router.get("/sessions/{session_id}", dependencies=V2)
async def sessions_get(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.core.db import db

    session = await sessions.session_payload(db, session_id)
    rows = await recordings.recording_rows(db, session_id=session_id)
    recs = await recordings.serialize_recordings(db, rows, user)
    participant_ids = [p["artisanId"] for p in session["participants"]]
    guest_ids = [r["id"] for r in session.get("respondents", []) if not r.get("artisanId")]
    grid = (
        await coverage.grid(session["questionnaire"]["id"], artisan_ids=participant_ids, client=db, respondent_ids=guest_ids)
        if participant_ids or guest_ids else None
    )
    session_answers = await answers.answers_for(db, session_id=session_id)
    return {
        **session,
        "recordings": recs,
        "answers": [
            {"artisanId": str(a["artisanId"]) if a.get("artisanId") else None,
             "respondentId": str(a["respondentId"]) if a.get("respondentId") else None,
             "questionLineageId": str(a["questionLineageId"]), "status": a.get("status"),
             **(answers.answer_out(a) or {})}
            for a in session_answers
        ],
        "grid": grid,
    }


@router.patch("/sessions/{session_id}", dependencies=V2)
async def sessions_patch(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.SessionPatch, forbid_title=True)
    if "designWorkshopId" in payload.model_fields_set:
        await assert_may_file_under(payload.designWorkshopId, user)
    return await sessions.patch_session(session_id, payload, user)


@router.delete("/sessions/{session_id}", status_code=status.HTTP_204_NO_CONTENT, dependencies=V2)
async def sessions_delete(session_id: str, user: Any = Depends(get_current_user)) -> Response:
    await sessions.delete_session(session_id, user)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/sessions/{session_id}/restore", dependencies=V2)
async def sessions_restore(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await sessions.restore_session(session_id, user)


# ── guest respondents (owner decision 1; UNIFY U6, A17, A18) ───────────────────────────────────
@router.post("/sessions/{session_id}/respondents", dependencies=V2)
async def sessions_add_respondents(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.RespondentsAdd)
    return {"respondents": await respondents.add(session_id, payload.respondents, user)}


@router.patch("/sessions/{session_id}/respondents/{respondent_id}", dependencies=V2)
async def sessions_patch_respondent(
    session_id: str, respondent_id: str, request: Request, user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    payload = await parse(request, S.RespondentPatch)
    return await respondents.patch(session_id, respondent_id, payload, user)


@router.delete("/sessions/{session_id}/respondents/{respondent_id}", status_code=status.HTTP_204_NO_CONTENT, dependencies=V2)
async def sessions_remove_respondent(session_id: str, respondent_id: str, user: Any = Depends(get_current_user)) -> Response:
    await respondents.remove(session_id, respondent_id, user)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/respondents/{respondent_id}/register", dependencies=V2)
async def respondents_register(respondent_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.RespondentRegister)
    return await respondents.register(respondent_id, payload.artisanId, user)


# ── the start sheet's form, bindings and type defaults (UNIFY U5; owner amendment 2) ───────────
@router.get("/form-for", dependencies=V2)
async def form_for(
    workshopId: str | None = None, designWorkshopId: str | None = None, questionnaireId: str | None = None,
    _: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await bindings.resolve_for_workshop(workshop_id=workshopId, design_workshop_id=designWorkshopId, explicit=questionnaireId)


@router.get("/forms/{questionnaire_id}/structure", dependencies=V2)
async def form_structure(questionnaire_id: str, includeRetired: bool = False, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The form's sections and questions with their lineages, codes, required marks and help text."""
    return await structure.form_structure(questionnaire_id, include_retired=includeRetired)


@router.get("/forms/{questionnaire_id}/bindings", dependencies=V2)
async def form_bindings_get(questionnaire_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.core.db import db

    return {"questionnaireId": questionnaire_id, "bindings": await bindings.bindings_of_form(db, questionnaire_id)}


@router.put("/forms/{questionnaire_id}/bindings", dependencies=V2)
async def form_bindings_put(questionnaire_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.BindingsPut)
    return {"questionnaireId": questionnaire_id, "bindings": await bindings.set_bindings(questionnaire_id, payload.bindings, user)}


@router.put("/type-defaults", dependencies=V2)
async def type_default_put(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.TypeDefaultPut)
    return await bindings.set_type_default(
        payload.questionnaireId, user, workshop_type=payload.workshopType, design_workshop_kind=payload.designWorkshopKind
    )


@router.post("/sessions/{session_id}/participants", dependencies=V2)
async def sessions_add_participant(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ParticipantAdd)
    return await sessions.add_participant(session_id, payload.artisanId, payload.joinedAt, user)


@router.patch("/sessions/{session_id}/participants/{artisan_id}", dependencies=V2)
async def sessions_patch_participant(session_id: str, artisan_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ParticipantPatch)
    return await sessions.patch_participant(session_id, artisan_id, payload, user)


@router.delete("/sessions/{session_id}/participants/{artisan_id}", dependencies=V2)
async def sessions_remove_participant(
    session_id: str, artisan_id: str, detach: bool = False, user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await sessions.remove_participant(session_id, artisan_id, detach, user)


@router.post("/sessions/{session_id}/researchers", dependencies=V2)
async def sessions_add_researcher(session_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.ResearcherAdd)
    return await sessions.add_researcher_route(session_id, payload.userId, payload.role, user)


@router.post("/sessions/{session_id}/finish", dependencies=V2)
async def sessions_finish(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await sessions.finish(session_id, user)


@router.post("/sessions/{session_id}/merge-into/{target_id}", dependencies=V2)
async def sessions_merge(session_id: str, target_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await sessions.merge_into(session_id, target_id, user)


@router.get("/sessions/{session_id}/insights", dependencies=V2)
async def sessions_insights(session_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The summaries of the session's live takes, one item per take that has a transcript (audit
    M27): ``{"recordingId", "mediaId", "state", "stale", "canEdit", "canRegenerate", "error",
    "insight"}``, the per-clip view of ``GET /api/media/{id}/insights`` without the translation
    text. ``{"items": []}`` while the insights switch is off."""
    from app.api.routes.transcript_insights import may_edit
    from app.core.db import db
    from app.services import transcript_insights

    await sessions.require_session(db, session_id)
    if not transcript_insights.enabled():
        return {"items": []}
    rows = await recordings.recording_rows(db, session_id=session_id)
    by_media = {str(r["mediaFileId"]): str(r["id"]) for r in rows}
    if not by_media:
        return {"items": []}
    media_rows = await db.mediafile.find_many(where={"id": {"in": list(by_media)}})
    current = await transcript_insights.current_insights(list(by_media))
    jobs = await transcript_insights.latest_jobs(list(by_media))
    items = []
    for media in media_rows:
        view = transcript_insights.build_view(
            media, current.get(str(media.id)), jobs.get(str(media.id)), may_edit=may_edit(user, media), include_translation=False,
        )
        if view["state"] == "NO_TRANSCRIPT":
            continue
        items.append({"recordingId": by_media[str(media.id)], **view})
    order = {rid: i for i, rid in enumerate(str(r["id"]) for r in rows)}
    items.sort(key=lambda item: order.get(item["recordingId"], 0))
    return {"items": items}


# ── the report annexure (A12): the forms bound to a design workshop, grouped by kind ────────────
@router.get("/annexure", dependencies=V2)
async def annexure(designWorkshopId: str | None = None, workshopId: str | None = None,
                   user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The annexure a design workshop's report prints, as data: every bound form grouped by kind,
    each with its live sessions' answers (one sitting per respondent). Anyone who may read the
    workshop; a Field Repository ``workshopId`` has no annexure on this portal."""
    from app.questionnaire import kinds
    from app.services.design_workshops import load_workshop_or_404
    from app.services.questionnaire_forms import report_items

    if workshopId and not designWorkshopId:
        return {"designWorkshopId": None, "groups": []}
    if not designWorkshopId:
        fail("VALIDATION_FAILED", "Name a designWorkshopId.")
    await load_workshop_or_404(designWorkshopId, user)
    items = await report_items(designWorkshopId)
    groups: dict[str, list[dict[str, Any]]] = {}
    for item in items:
        groups.setdefault(item.kind or "", []).append({
            "questionnaireId": item.questionnaire_id, "title": item.title, "kind": item.kind or None,
            "version": item.version, "questionCount": item.question_count, "answeredTotal": item.answered_total,
            "sittings": [
                {
                    "id": s.entry_id, "label": s.label, "source": s.source, "recordedAt": s.recorded_at or None,
                    "recordedBy": s.recorded_by or None, "notes": s.notes or None,
                    "answers": [
                        {"prompt": a.prompt, "answer": a.plain_answer, "notes": a.notes or None,
                         "sectionCode": a.section_code or None, "retired": a.is_retired}
                        for a in s.printed_answers
                    ],
                }
                for s in item.printed_sittings
            ],
            "emptySittings": len(item.sittings) - len(item.printed_sittings),
        })
    return {
        "designWorkshopId": designWorkshopId,
        "groups": [
            {"kind": kind or None, "label": kinds.label_for(kind or None), "forms": forms}
            for kind, forms in groups.items()
        ],
    }


# ── coverage ───────────────────────────────────────────────────────────────────────────────────
@router.post("/coverage/check", dependencies=V2)
async def coverage_check(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.CoverageCheckRequest)
    return await coverage.check(payload, user)


@router.get("/coverage", dependencies=V2)
async def coverage_grid(
    questionnaireId: str, workshopId: str | None = None, artisanIds: list[str] = Query(default=[]), _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    ids = [a for value in artisanIds for a in value.split(",") if a]
    return await coverage.grid(questionnaireId, artisan_ids=ids or None, workshop_id=workshopId)


@router.get("/coverage/snapshot", dependencies=V2)
async def coverage_snapshot(
    questionnaireId: str, workshopId: str | None = None, sinceSeq: int | None = None, _: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await coverage.snapshot(questionnaireId, workshopId, sinceSeq)


# ── recordings ─────────────────────────────────────────────────────────────────────────────────
@router.patch("/recordings/{recording_id}", dependencies=V2)
async def recordings_patch(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.RecordingPatch)
    return await recordings.patch(recording_id, payload, user)


@router.delete("/recordings/{recording_id}", dependencies=V2)
async def recordings_remove(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await recordings.remove(recording_id, user)


@router.post("/recordings/{recording_id}/restore", dependencies=V2)
async def recordings_restore(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await recordings.restore(recording_id, user)


@router.post("/recordings/{recording_id}/decide", dependencies=V2)
async def recordings_decide(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.DecideRequest)
    return await recordings.decide(recording_id, payload, user)


@router.get("/recordings/removed", dependencies=V2)
async def recordings_removed(sessionId: str | None = None, artisanId: str | None = None,
                             user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Removed takes of one session or one artisan, newest first ("Show removed"; audit M12)."""
    return await recordings.removed_takes(user, session_id=sessionId, artisan_id=artisanId)


@router.post("/recordings/{recording_id}/file", dependencies=V2)
async def recordings_file(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """"File it" for a take waiting for a place (audit H14): targets, optionally subjects and session."""
    payload = await parse(request, S.FileRecordingRequest)
    return await recordings.file_recording(recording_id, payload, user)


@router.post("/media/{media_id}/file", dependencies=V2)
async def media_file(media_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """"File it" for an inbox "To file" clip with no recording yet (audit H14)."""
    payload = await parse(request, S.FileMediaRequest)
    return await recordings.file_media(media_id, payload, user)


@router.post("/media/{media_id}/not-needed", dependencies=V2)
async def media_not_needed(media_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """"Not needed" on an inbox "To file" clip, kept on the server (audit M22, H14)."""
    return await recordings.not_needed(media_id, user)


@router.post("/recordings/{recording_id}/make-primary", dependencies=V2)
async def recordings_make_primary(recording_id: str, request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.MakePrimaryRequest)
    return await recordings.make_primary(recording_id, payload, user)


@router.post("/recordings/{recording_id}/answer-proposals", dependencies=V2)
async def recordings_proposals(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await answers.proposals(recording_id, user)


@router.post("/recordings/{recording_id}/transcribe", status_code=status.HTTP_202_ACCEPTED, dependencies=V2)
async def recordings_transcribe(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Take-level Retry. While paused: 202 ``{"status": "QUEUED", "paused": true}``, never an error."""
    return await recordings.transcribe(recording_id, user)


@router.get("/recordings/{recording_id}/download", dependencies=V2)
async def recordings_download(recording_id: str, user: Any = Depends(get_current_user)) -> RedirectResponse:
    await assert_may_download_recording(recording_id, user)
    return RedirectResponse(await recordings.download_url(recording_id, user), status_code=status.HTTP_302_FOUND)


async def assert_may_download_recording(recording_id: str, user: Any) -> None:
    """The media-owner gate every other file download keeps (audit H3): the uploader, Professor and
    above, a dataset downloader, or a holder of the uploader's data-access grant. Any signed-in user,
    volunteers included, could download any recording."""
    from app.core.db import db
    from app.services.records import media_url_owners

    rows = await db.query_raw(
        'SELECT m."uploadedById" AS uploader FROM "QuestionnaireRecording" r JOIN "MediaFile" m ON m."id" = r."mediaFileId" '
        'WHERE r."id" = $1',
        recording_id,
    )
    if not rows:
        return  # download_url answers the 404
    owners = await media_url_owners(user)
    if owners is not None and str(rows[0]["uploader"]) not in owners:
        fail("FORBIDDEN", "You need download access to this researcher's recordings. Request it from them.")


# ── answers, inbox, corpus ─────────────────────────────────────────────────────────────────────
@router.put("/answers", dependencies=V2)
async def answers_put(request: Request, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    payload = await parse(request, S.AnswersPut)
    return await answers.put_answers(payload, user)


@router.get("/answers/{answer_id}/revisions", dependencies=V2)
async def answers_revisions(answer_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await answers.answer_revisions(answer_id)


@router.get("/recordings/{recording_id}/events", dependencies=V2)
async def recordings_events(recording_id: str, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await recordings.recording_events(recording_id, user)


@router.get("/inbox", dependencies=V2)
async def inbox_get(user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await inbox.inbox(user)


async def _corpus(questionnaire_id: str, status_filter: str | None, kind: str, fmt: str) -> Response:
    from app.services.naming import content_disposition

    data = await corpus.corpus(questionnaire_id, status=status_filter or None)
    names = corpus.file_names(data["title"], fmt)
    if kind == "meta":
        return JSONResponse({"schemaVersion": corpus.SCHEMA_VERSION, "files": [names["answers"], names["recordings"]]})
    rows, fields = (data["recordings"], corpus.RECORDING_FIELDS) if kind == "recordings" else (data["answers"], corpus.ANSWER_FIELDS)
    media_type = "application/x-ndjson" if fmt == "jsonl" else "text/csv"
    return Response(
        corpus.render(rows, fields, fmt), media_type=f"{media_type}; charset=utf-8",
        headers={"Content-Disposition": content_disposition(names[kind if kind == "recordings" else "answers"])},
    )


@router.get("/corpus.jsonl", dependencies=V2)
async def corpus_jsonl(
    questionnaireId: str, status_filter: str | None = Query("APPROVED", alias="status"),
    kind: str = Query("answers", pattern="^(answers|recordings|meta)$"), _: Any = Depends(require_dataset_downloader),
) -> Response:
    # A whole-instrument dump: the dataset-download gate /export/*.csv keeps (audit H3).
    return await _corpus(questionnaireId, status_filter, kind, "jsonl")


@router.get("/corpus.csv", dependencies=V2)
async def corpus_csv(
    questionnaireId: str, status_filter: str | None = Query("APPROVED", alias="status"),
    kind: str = Query("answers", pattern="^(answers|recordings|meta)$"), _: Any = Depends(require_dataset_downloader),
) -> Response:
    return await _corpus(questionnaireId, status_filter, kind, "csv")


# ── artisans: the record (v2; the consent routes are api/routes/artisans.py's) ─────────────────
@artisans_router.get("/{artisan_id}/record", dependencies=V2)
async def artisan_record_get(artisan_id: str, questionnaireId: str | None = None, user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return await record.artisan_record(artisan_id, user, questionnaire_id=questionnaireId)


@artisans_router.get("/{artisan_id}/record.csv", dependencies=V2)
async def artisan_record_csv(artisan_id: str, questionnaireId: str | None = None, user: Any = Depends(get_current_user)) -> Response:
    from app.core.db import db
    from app.services.access import owner_download_scope
    from app.services.naming import content_disposition

    # The gate the legacy consolidated CSV keeps (audit H3): download access to the artisan's owner,
    # and a subset grant has to name this artisan.
    artisan = await db.artisan.find_unique(where={"id": artisan_id})
    if not artisan:
        fail("NOT_FOUND", "No such artisan.")
    scope = await owner_download_scope(user, getattr(artisan, "createdById", None))
    if scope is not None and artisan_id not in scope.get("artisans", set()):
        fail("FORBIDDEN", "Your download access to this researcher's data does not cover this artisan.")
    filename, text = await record.artisan_record_csv(artisan_id, user, questionnaire_id=questionnaireId)
    return Response(text, media_type="text/csv; charset=utf-8", headers={"Content-Disposition": content_disposition(filename)})
