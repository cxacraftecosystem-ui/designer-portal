"""The design-workshop API: the 22-stage record, its stages, and the reports generated from it.

Three families of endpoint:

* ``/design-workshops`` — the workshop header. List, create, read, update, soft-delete.
* ``/design-workshops/{id}/stages*`` — read and write one stage at a time. A stage is saved
  whole, never field by field; see :class:`StageSaveIn` for why.
* ``/design-workshops/{id}/report*`` — generate a .docx or .pdf, and record an export the
  phone generated offline.

Plus one that belongs to none of them and is the most important of the four:
``GET /design-workshops/schema`` serves the field registry itself. Every client — the web form,
the Android capture screens, the on-device report builder — renders from that payload rather
than from its own copy of the field list, which is what keeps three surfaces describing one
workshop the same way. It is cached by ``version``; a changed version is the signal an Android
draft store uses to run its migration.

**Nothing here hard-deletes.** ``DELETE`` sets ``deletedAt``. This is a research data set, the
requirement is explicit that data is retained, and a designer's two weeks of fieldwork is not
something a mis-tap should end. Every read filters ``deletedAt: null``; an admin can restore.

**Permissions**, as this file actually enforces them — read the four clauses, not the ladder,
because one of them is not a rank threshold:

* CREATING one needs BOTH ``assert_can_create_records`` (Researcher and above, the repository-wide
  rule for making any record) AND ``_require_designer``, which is ``can_run_design_workshops`` — a
  SET, ``{DESIGNER, ADMIN, MASTER_ADMIN}``, not a floor. A PROFESSOR outranks a designer on every
  other surface in this codebase and still cannot start a workshop. ``PATCH``, every stage write and
  the two capture aids (OCR and dictation) call ``_require_designer`` ALONE:
  ``assert_can_create_records`` appears on the create route and nowhere else in this file. No caller
  gets in that way who would not get in anyway — the designer set sits above Researcher on the
  ladder, so whatever ``_require_designer`` admits ``can_create_records`` admits too — but which gate
  is written where is exactly what this header is read for, so it says which.
* OPENING someone else's is decided entirely by ``load_workshop_or_404``: the creator, an admin, or
  an account an admin has given a ``DesignWorkshopViewer`` row. A grant carries read AND the stage
  writes that go through that helper — see ``services/design_workshop_viewers.py`` for what it
  deliberately does not carry.
* DELETING is ``assert_can_delete``; restoring is ``require_admin``.
* GENERATING A REPORT is open to anyone who can READ the workshop — a report is a view of data the
  caller can already see, and refusing it would only push people to screenshot the screen. The
  photographs and recordings it EMBEDS are a different question, gated per file by
  ``owned_or_granted_where`` in ``media_resolver``/``load_transcript_items``.

``require_workshop_manager`` is NOT used here. It belongs to ``api/routes/workshops.py``, a
different router over a different model.
"""

import asyncio
import hashlib
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Response,
    UploadFile,
    status,
)

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    assert_can_create_records,
    assert_can_delete,
    can_run_design_workshops,
    get_current_user,
    is_admin,
    require_admin,
)
from app.schemas.design_workshops import (
    DESIGN_WORKSHOP_STATUSES,
    DesignWorkshopCreate,
    DesignWorkshopUpdate,
    ExportRecordIn,
    ReportGenerateIn,
    StageSaveIn,
)
from app.services.ai import transcribe_audio_bytes
from app.services.concurrency import gather_reads
from app.services.cost_integrity import analyse_cost_integrity, cost_findings_payload
from app.services.design_workshop_viewers import visible_to_clause
from app.services.design_workshops import (
    REFERENCE_LIMIT_DEFAULT,
    REFERENCE_LIMIT_MAX,
    assemble_workshop_data,
    attach_district_anchors,
    attach_report_questionnaires,
    attach_report_references,
    attach_report_transcripts,
    entry_rows,
    load_workshop_or_404,
    media_resolver,
    reference_options,
    render_report,
    resolve_template_id,
    save_stage,
    seed_designer_prefill,
    workshop_completeness,
    workshop_summary,
)
from app.services.identity_ocr import (
    SUPPORTED_MIME_TYPES,
    IdentityOcrUnavailable,
    get_identity_ocr_settings,
    read_identity_card,
)
from app.services.market_analysis import analyse, market_findings_payload
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains, enum_filter_or_422, plain
from app.services.report_docx import DOCX_MIME
from app.services.report_pdf import PDF_MIME
from app.services.report_templates import SpecialSection, template as get_template, template_choices
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    registry_to_dict,
    registry_version,
    stages,
)
from app.services.workshop_transcripts import load_transcript_items

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])

# A generated report is CPU-bound and can take seconds on a 26-page workshop with forty photos.
# Every render therefore goes through asyncio.to_thread, exactly as xlsx_report does, so one
# export cannot stall the event loop for every other request on a single-worker deployment.
_MIME = {"DOCX": DOCX_MIME, "PDF": PDF_MIME}
_EXTENSION = {"DOCX": "docx", "PDF": "pdf"}


# --------------------------------------------------------------------------------------
# The registry
# --------------------------------------------------------------------------------------


@router.get("/schema")
async def get_stage_schema(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """The field registry every client renders its forms from.

    Served rather than duplicated, for the same reason ``/reference/address`` is: a field list
    that lives in three codebases is three field lists, and they drift. Whatever a form offers
    is by construction exactly what this API accepts and exactly what the report prints.

    A pure constant — no database read — so a client should cache it and re-fetch only when
    ``version`` changes.
    """
    return registry_to_dict()


@router.get("/templates")
async def get_report_templates(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    """The report templates a designer may choose between at stage 20."""
    return template_choices()


# --------------------------------------------------------------------------------------
# Speech and scanning
#
# Two capabilities that belong to the capture screens rather than to a workshop record, which is
# why neither takes a ``workshop_id``: a designer scans a card and dictates a paragraph before the
# stage they are filling has ever been saved.
# --------------------------------------------------------------------------------------


@router.post("/ocr/identity")
async def scan_identity_card(
    file: UploadFile = File(...),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Read the Aadhaar / Pehchan numbers off a photograph of an artisan's card.

    THE RESPONSE FILLS A FORM FIELD. IT NEVER WRITES ANYTHING. No artisan is created, no number is
    stored, nothing is matched against an existing record — the candidates come back, the designer
    reads them against the card still in their hand and presses save. That is not caution for its
    own sake: ``Artisan.aadhaarNumber`` is the deduplication key for the entire repository, and a
    number that arrives from an OCR read and commits itself is a wrong national identity number
    entering a research database with nobody in the loop to catch it. The one human who can compare
    the digits to the card is standing right there; this endpoint's whole job is to save them the
    typing, not the checking.

    Every 12-digit candidate has already been through the UIDAI Verhoeff checksum in
    :mod:`app.services.identity_ocr` — the read is the failure mode, and the checksum is what
    catches it. Pehchan numbers are normalised through ``artisan_identity.normalize_pehchan`` so an
    OCR read and a typed entry of the same card cannot be stored as two different strings.

    503 when no vision provider is configured, naming the setting, because the alternative — a 200
    with an empty candidate list — is indistinguishable from "the card was unreadable" and would
    have a designer re-photographing a card in better light forever.
    """
    _require_designer(current_user)
    settings = get_identity_ocr_settings()
    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No image was uploaded."
        )
    if len(content) > settings.max_image_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"The image is larger than the {settings.max_image_bytes // (1024 * 1024)} MB "
                "limit. Photograph the card alone rather than the whole page."
            ),
        )
    mime_type = (file.content_type or "image/jpeg").split(";")[0].strip().lower()
    if mime_type not in SUPPORTED_MIME_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"{mime_type or 'That file type'} cannot be read; send a JPEG, PNG or WebP.",
        )
    try:
        result = await read_identity_card(content, mime_type)
    except IdentityOcrUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)
        ) from exc
    # ``content`` goes out of scope here and is never written anywhere. A photograph of a national
    # identity document is retained only when a designer deliberately uploads it through the media
    # flow, which is a visible act with a record; this endpoint has no storage path at all.
    return result.payload()


# A dictated sentence is seconds of speech. The cap is what stops this synchronous endpoint from
# becoming a back door into the transcription queue: a designer who wants a 40-minute interview
# transcribed uploads it as media and it is queued off-peak with retries and rate-limit backoff,
# whereas this holds a worker for the whole provider round trip. Two minutes of Opus is well under
# a megabyte; 6 MB covers a long dictation in an uncompressed format and refuses a recording that
# was never a dictation.
DICTATION_MAX_BYTES = 6 * 1024 * 1024


@router.post("/dictate")
async def dictate(
    file: UploadFile = File(...),
    languageHint: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Speech to text for one dictated passage — THE FALLBACK PATH, not the live one.

    LIVE DICTATION IS A CLIENT CAPABILITY AND HAS NO BACKEND. The browser has
    ``SpeechRecognition``/``webkitSpeechRecognition`` and Android has ``SpeechRecognizer``; both
    stream on-device or through the platform's own service, show words as they are spoken, and cost
    nothing. Putting a streaming socket here instead would be slower, would spend provider credit
    per sentence, and would stop working the moment the signal did — which in a village workshop is
    most of the day.

    What the backend owes dictation is the devices that have neither: an Android build with no
    Google speech services, a locked-down browser, a WebView. Those record a short clip and post it
    here, and it comes back as text through the SAME ``ai.transcribe_audio_bytes`` the queue uses,
    so a dictated sentence and a transcribed interview are produced by the same provider chain with
    the same craft vocabulary and cannot drift apart.

    Synchronous and small on purpose — see :data:`DICTATION_MAX_BYTES`. The text is returned and
    nothing is stored: the designer is about to type it into a field, and this endpoint has no
    business knowing which one.
    """
    _require_designer(current_user)
    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No audio was uploaded."
        )
    if len(content) > DICTATION_MAX_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"A dictated clip may be at most {DICTATION_MAX_BYTES // (1024 * 1024)} MB. "
                "Upload a longer recording as workshop audio instead — it is transcribed in the "
                "background and the transcript comes back onto the stage."
            ),
        )
    result = await transcribe_audio_bytes(
        content,
        file.filename or "dictation.webm",
        (file.content_type or "audio/webm").split(";")[0].strip(),
        get_settings(),
    )
    if str(result.get("status") or "").upper() == "UNAVAILABLE":
        # The same reasoning as the OCR route: an empty 200 reads as "you said nothing".
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(result.get("message") or "Transcription is not configured."),
        )
    return {
        "status": result.get("status"),
        # The plain text, never the speaker-labelled Markdown: this is going straight into a form
        # field, and "**Speaker 1:**" is not something a designer wants to delete by hand.
        "text": result.get("text") or "",
        "provider": result.get("provider"),
        "languageHint": languageHint,
        "message": result.get("message"),
    }


# --------------------------------------------------------------------------------------
# The workshop header
# --------------------------------------------------------------------------------------


@router.get("")
async def list_design_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1),
    search: str | None = None,
    statusFilter: str | None = None,
    craftName: str | None = None,
    state: str | None = None,
    mineOnly: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """List workshops, newest first.

    The filters read the promoted columns rather than the JSON, which is the entire reason
    those columns exist — see the note above ``DesignWorkshop`` in schema.prisma.
    """
    where: dict[str, Any] = {"deletedAt": None}
    if statusFilter:
        # THROUGH THE ENUM CHECK, because the raw string went into a Postgres enum column and
        # anything not in it — a lowercase "draft", an "ALL" from a client whose dropdown labels
        # its empty option, a stale bookmarked URL — came back as a bare 500 with
        # {"error": "FieldNotFoundError"} rather than a 422 naming the values a client can send.
        where["status"] = enum_filter_or_422(statusFilter, DESIGN_WORKSHOP_STATUSES)
    if craftName:
        where["craftName"] = contains(craftName)
    if state:
        # `plain`, not the raw value: a NUL byte cannot live in a Postgres text column, so
        # ?state=%00 raised a DataError inside the driver and surfaced as a 500 — a logged server
        # error with a stack trace, which the web then shows the designer as "you are offline".
        where["state"] = plain(state)
    if search:
        # `contains` rather than the hand-rolled dict this used to build: the helper is where the
        # bytes Postgres cannot store are stripped, and building the filter by hand here was
        # exactly how this one search box opted out of that (?search=%00 -> 500).
        where["OR"] = [
            {"title": contains(search)},
            {"craftName": contains(search)},
            {"clusterName": contains(search)},
            {"workshopCode": contains(search)},
        ]
    # A non-admin sees the workshops they created OR were let into by an admin. An explicit
    # mineOnly narrows to their own without changing anyone else's — and it means OWN, so it
    # deliberately excludes the granted ones rather than reusing the scope clause below.
    #
    # THE LIST IS HALF THE FEATURE. `load_workshop_or_404` admitting a grant-holder is invisible on
    # its own, because nothing in either client navigates to a design workshop by typed id: a
    # colleague whose grant the list does not honour is simultaneously told the workshop exists and
    # that it does not. See app/services/design_workshop_viewers.py.
    #
    # AND-composed, NOT assigned to where["OR"], which the search box above has already taken. Two
    # assignments to that one key and the later silently wins — either the search stops narrowing
    # or the grant vanishes the moment somebody types. Same warning, same reason, as
    # `services/records.owned_or_granted_where`.
    if mineOnly:
        where["createdById"] = current_user.id
    elif not is_admin(current_user):
        where.setdefault("AND", []).append(visible_to_clause(current_user.id))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await asyncio.gather(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_design_workshop(
    payload: DesignWorkshopCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Start a workshop.

    Only the title is required. A workshop is created in a room on day one, before the sanction
    order number is to hand; the Basic-tier fields of stage 1 are enforced when a report is
    generated, not here.
    """
    # BOTH gates, and the designer one is the point.
    #
    # `assert_can_create_records` is Researcher-and-above — the repository-wide rule for making any
    # record. On its own it left this endpoint open to a RESEARCHER and a PROFESSOR while
    # `frontend/lib/permissions.ts` hid the pages from exactly those two, which is a UI guard over
    # an open route: the link disappears and the URL, the API and the Android client do not. A
    # design workshop is a fortnight of a named designer's work that ends in a document submitted
    # under their name, so the capability that decides who may start one is
    # `can_run_design_workshops`, and it has to be enforced HERE rather than in the browser.
    assert_can_create_records(current_user)
    _require_designer(current_user)
    data: dict[str, Any] = {
        "title": payload.title.strip(),
        "templateId": payload.templateId,
        "createdById": current_user.id,
        "schemaVersion": registry_version(),
        "status": "DRAFT",
    }
    for key in ("craftName", "clusterName", "state", "district", "notes", "workshopId"):
        value = getattr(payload, key)
        if value:
            data[key] = value
    for key in ("startDate", "endDate"):
        parsed = _parse_date(getattr(payload, key))
        if parsed:
            data[key] = parsed

    record = await db.designworkshop.create(data=data)
    # The designer's own details, copied out of their profile into stage 1 and stage 3 before the
    # form is ever opened, so nobody retypes their institution and biography twenty-two stages
    # into their fifth workshop of the year. Written as ordinary stage entries — the report reads
    # them with no special case at all — and copied rather than referenced, because a report is a
    # historical document. See ``seed_designer_prefill``.
    record = await seed_designer_prefill(record, current_user)
    return workshop_summary(record)


@router.get("/{workshop_id}")
async def get_design_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """One workshop with every stage's data and its completeness scores."""
    record = await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    summary["completeness"] = workshop_completeness(entries)
    summary["transcripts"] = await _transcripts_payload(entries, current_user)
    summary["schemaVersion"] = registry_version()
    return summary


@router.patch("/{workshop_id}")
async def update_design_workshop(
    workshop_id: str, payload: DesignWorkshopUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    data: dict[str, Any] = {}
    for key in ("title", "templateId", "craftName", "clusterName", "state", "district",
                "notes", "workshopId", "status"):
        value = getattr(payload, key)
        if value is not None:
            data[key] = value.strip() if isinstance(value, str) else value
    for key in ("startDate", "endDate"):
        raw = getattr(payload, key)
        if raw is not None:
            data[key] = _parse_date(raw)
    if not data:
        return workshop_summary(record)
    updated = await db.designworkshop.update(where={"id": workshop_id}, data=data)
    return workshop_summary(updated)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_design_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> None:
    """Soft-delete. The row and every stage entry stay; only ``deletedAt`` is set.

    Deliberately not a hard delete, and deliberately unlike the rest of this codebase, which
    has no soft delete anywhere. The requirement that data is retained for research, and the
    fact that one row here represents weeks of fieldwork by someone who is no longer in the
    village, both point the same way.
    """
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    assert_can_delete(current_user)
    await db.designworkshop.update(
        where={"id": workshop_id},
        data={"deletedAt": datetime.now(UTC), "deletedById": current_user.id},
    )


@router.post("/{workshop_id}/restore")
async def restore_design_workshop(
    workshop_id: str, _: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Undo a soft delete. Admin only — the point of the safety net is that it is not per-user."""
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    restored = await db.designworkshop.update(
        where={"id": workshop_id}, data={"deletedAt": None, "deletedById": None}
    )
    return workshop_summary(restored)


# --------------------------------------------------------------------------------------
# Stages
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}/stages")
async def list_stages(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    return {
        "stages": _stages_payload(entries),
        "completeness": workshop_completeness(entries),
        "transcripts": await _transcripts_payload(entries, current_user),
        "schemaVersion": registry_version(),
    }


@router.get("/{workshop_id}/stages/{stage_key}")
async def get_stage(
    workshop_id: str, stage_key: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")
    entries = await entry_rows(workshop_id, stage_key=stage_key)
    payload = _stages_payload(entries).get(stage_key) or {"singleton": {}, "collections": {}}
    payload["completeness"] = workshop_completeness(entries).get(stage_key)
    payload["transcripts"] = await _transcripts_payload(entries, current_user)
    return payload


@router.put("/{workshop_id}/stages/{stage_key}")
async def save_stage_data(
    workshop_id: str, stage_key: str, payload: StageSaveIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Save a whole stage in one write.

    Returns the cleaned data as stored, any per-field validation errors, and the field keys
    that were DROPPED because the registry does not know them — the last of which is how a
    server notices that a phone is running ahead of it, rather than by rejecting the sync.

    ``submit=true`` enforces the Basic-tier required fields and 422s if any is missing; the
    default leaves the stage a draft, because a stage half-filled overnight is the normal state
    of this app, not an error.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")

    result = await save_stage(workshop_id, spec, payload, current_user)
    if result["errors"] and payload.submit:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"message": "Some required fields are missing", "errors": result["errors"]},
        )
    return result


# --------------------------------------------------------------------------------------
# Reference pickers
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}/references")
async def list_references(
    workshop_id: str,
    model: str = Query(min_length=1, max_length=64),
    scope: str = Query(REF_SCOPE_ALL, max_length=16),
    filterBy: str | None = Query(None, max_length=64),
    search: str | None = Query(None, max_length=120),
    limit: int = Query(REFERENCE_LIMIT_DEFAULT, ge=1, le=REFERENCE_LIMIT_MAX),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The options one REF field's dropdown shows.

    This is the endpoint that lets a designer STOP RETYPING records the database already holds.
    Every REF field in the registry carries a ``refModel``, a ``refScope`` and sometimes a
    ``refFilterBy``; a client renders a picker from those three and asks here for its contents,
    which is why all three arrive back as query parameters rather than being re-derived on this
    side. Deriving them here would mean the form and the server each held their own idea of how
    wide the artisan list should be, and the day they disagreed the picker would quietly widen
    rather than fail.

    ``filterBy`` is the cascade: the artisan chosen on the row, so the product dropdown holds
    that artisan's products and nothing else. It accepts either an ``Artisan`` id or a roster
    entry id, because stage 6 and stage 13 hold different kinds of id in the same-named field
    and no client should have to know that.

    Readable by anyone who can read the workshop. The options are records they can already list
    through ``/records``; refusing them here would only mean the designer opens a second tab and
    copies the name across by hand, which is the behaviour being replaced.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    return await reference_options(
        record, model, scope=scope.upper(), filter_by=filterBy, search=search, limit=limit
    )


@router.get("/{workshop_id}/transcripts")
async def list_workshop_transcripts(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Every recording this workshop collected, and what its transcript currently looks like.

    This is what the ``includeTranscripts`` toggle shows BEFORE it is committed to. A designer
    about to append transcripts to a report submitted to a ministry needs to see what they are
    appending — which stage each recording came from, how long it is, how many voices are in it and
    its opening line — because a transcript annexure is the one part of the report whose contents
    nobody has read. Generating a 60-page document to find out is not a preview.

    Recordings with no transcript yet are LISTED, with their status. Hiding them would mean a
    designer who made six recordings and sees four concludes that two were lost, when in fact two
    are still in the queue; ``includedInReport`` says plainly which ones the annexure would carry.
    """
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    items = await load_transcript_items(entries, viewer=current_user)
    payloads = []
    for item in items:
        payload = item.payload()
        payload["includedInReport"] = item.has_text
        payloads.append(payload)
    return {
        "items": payloads,
        "total": len(payloads),
        "withTranscript": sum(1 for item in items if item.has_text),
        "totalDurationSeconds": sum(item.duration_seconds or 0 for item in items) or None,
    }


@router.get("/{workshop_id}/market-analysis")
async def workshop_market_analysis(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Stage 9's Advanced tier: what the stage-8 survey actually says about the stage-9 claims.

    THE SERVER COPY OF A CALCULATION THAT ALSO RUNS ON THE CLIENT, and that is deliberate rather
    than duplicated by accident. `app/services/market_analysis.py` is pure arithmetic over rows the
    designer already entered, so the browser and the handset run the same analysis with no network
    at all — which is the only way it is available in the village where the survey was taken. This
    endpoint exists for the two cases the client cannot serve: a report render, which must not
    depend on whichever device happens to be looking; and a designer opening the workshop on a
    machine that has not synced stage 8.

    It is READ-ONLY and writes nothing to stage 9. The designer's declared bands, SWOT and demand
    level stay exactly as typed — this returns findings BESIDE them. They were in the room and the
    arithmetic was not.
    """
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)

    def rows_of(entity_key: str) -> list[dict[str, Any]]:
        return [dict(row.data or {}) for row in entries if row.entityKey == entity_key]

    findings = analyse(
        responses=rows_of("surveyResponse"),
        competitors=rows_of("competitorProduct"),
        bands=rows_of("priceBand"),
        swot=rows_of("swotPoint"),
    )
    return market_findings_payload(findings)


@router.get("/{workshop_id}/cost-integrity")
async def workshop_cost_integrity(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Stage 17's cost sheets checked against the line items underneath them.

    A cost sheet can contradict ITSELF: the material lines add up to ₹1,650.00 and the header
    declares ₹1,560.00, and the header is what the report prints into a document submitted to a
    government office. The registry cannot catch it — `derive_value` reads one row and its own
    fields, never a sibling collection — so `app/services/cost_integrity.py` does the roll-up.

    It is READ-ONLY and writes nothing back to stage 17. The designer's typed subtotal stays
    exactly as typed and this returns a FINDING beside it: a subtotal may legitimately differ from
    its lines, and silently replacing a considered figure with a computed one would be a worse bug
    than the one being fixed.

    The calculation is PURE so that it can run in the browser and on the handset — but as of
    2026-08-08 it does not, and that matters more than the intent. The market analysis beside it has
    a proven-equal TypeScript port and a stage-9 panel; this has neither, and a Kotlin
    `DwCostIntegrity` exists that nothing calls. **So no designer sees a cost-integrity finding on
    any surface today**: this endpoint is the whole of the feature, and nothing consumes it.

    That sentence is here rather than in a tracker because the previous version of this docstring
    asserted the ports existed, and a claim like that is how a gap stops being visible: a reader
    checking whether the handset warns about a self-contradicting cost sheet would have read "yes"
    and stopped. Delete this paragraph when the ports and a panel land, not before.

    It is READ-ONLY and serves the two cases a client could not serve even once ported: a report
    render, which must not depend on whichever device is looking, and a device that has not synced
    the stage.
    """
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)

    def rows_of(entity_key: str) -> list[dict[str, Any]]:
        # `_entryId` TRAVELS WITH THE ROW HERE, unlike the market analysis above, and without it
        # this endpoint returns nothing useful. It is the row's database id rather than part of the
        # stored `data`, and it is the only key a `costSheetRef` can be matched against — omit it
        # and every line becomes an orphan of a sheet that is sitting right there. Same injection,
        # under the same `_`-prefixed name, as `_stages_payload` and `_workshop_data`.
        return [dict(row.data or {}, _entryId=row.id)
                for row in entries if row.entityKey == entity_key]

    # A cost sheet is labelled by `productRef`, which points at a stage-13 final product. The
    # service is pure and cannot resolve a reference, so the names are looked up here and passed
    # in — a finding headed by a raw cuid is one a designer cannot trace back to a row.
    labels = {
        row.id: str((row.data or {}).get("name") or (row.data or {}).get("productCode") or "")
        for row in entries if row.entityKey == "finalProduct"
    }

    findings = analyse_cost_integrity(
        sheets=rows_of("costSheet"),
        material_lines=rows_of("costMaterialLine"),
        labour_lines=rows_of("costLabourLine"),
        labels={k: v for k, v in labels.items() if v},
    )
    return cost_findings_payload(findings)


# --------------------------------------------------------------------------------------
# Reports
# --------------------------------------------------------------------------------------


@router.get("/{workshop_id}/report/preview")
async def preview_report(
    workshop_id: str, templateId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The report as structured blocks, for the web preview to render as HTML.

    The preview reads the SAME :class:`ReportDocument` the .docx and .pdf are rendered from, so
    what a designer approves on screen is what the file contains. A preview built from its own
    traversal of the data would be a fourth renderer, and the first to drift.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    # THE TEMPLATE COMES BACK FROM THE LOAD, THROUGH THE SAME PRECEDENCE EVERY OTHER STAGE-20
    # SETTING USES. It used to be resolved here as `templateId or record.templateId`, which skipped
    # the stage-20 answer entirely — so the required, Basic-tier "Report template" picker a designer
    # had to fill in to satisfy the completeness gate changed nothing about the document it names.
    # `_report_inputs` now resolves it (see `resolve_template_id`) because it has to know whether
    # the document draws a map before deciding what to load.
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id, record, viewer=current_user, requested_template_id=templateId
    )
    document, warnings = await asyncio.to_thread(
        _build_only, data, template_id, resolver, record,
    )
    warnings = list(warnings) + load_warnings
    return {
        "meta": {
            "title": document.meta.title,
            "subtitle": document.meta.subtitle,
            "templateId": document.meta.template_id,
            "templateName": document.meta.template_name,
            "pageSize": document.meta.page_size.value,
        },
        "blocks": [_block_payload(b) for b in document.blocks],
        "warnings": list(warnings),
    }


@router.post("/{workshop_id}/report")
async def generate_report(
    workshop_id: str, payload: ReportGenerateIn,
    current_user: Any = Depends(get_current_user),
) -> Response:
    """Render and return one report file.

    Returns the bytes directly rather than a link. A designer generating a report is about to
    attach it to an email; an intermediate storage round trip would add a failure mode and a
    retention question for a file that is reproducible from the record at any time.

    Warnings — a missing required field, a photo that could not be embedded — travel in the
    ``X-Report-Warnings`` header rather than in the file, because they describe the act of
    generating rather than the document, and an officer opening the .docx next month should not
    find a note about what was missing on the day.
    """
    record = await load_workshop_or_404(workshop_id, current_user)
    fmt = payload.formats[0]
    # The template the file is actually built from is resolved ONCE, inside `_report_inputs`, and
    # used for the loads, the render AND the export row — a recorded export that names a different
    # template from the one in the file is worse than no record, because the checksum makes it
    # look authoritative.
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id,
        record,
        viewer=current_user,
        requested_template_id=payload.templateId,
        transcripts=payload.includeTranscripts,
    )
    blob, warnings, page_count = await asyncio.to_thread(
        render_report, data, template_id, resolver, record, fmt, payload,
    )
    warnings = list(warnings) + load_warnings

    file_name = _report_file_name(record, fmt)
    # Built BEFORE the export row is written, so a name this response cannot carry fails the
    # request without first recording a phantom export of a file nobody received.
    headers = {
        "content-disposition": _content_disposition(file_name),
        # `_warnings_header` and NOT `"; ".join(...)[:900]`, which dropped the tail of the list in
        # silence and cut the last surviving sentence mid-word. The load warnings — "your attached
        # questionnaire had no answers and is not in this file" — are appended last and were the
        # first casualties on the default template. See that function.
        "x-report-warnings": _warnings_header(warnings),
        # The TRUE total, never what fitted above.
        "x-report-warning-count": str(len(warnings)),
    }

    if payload.record:
        await db.dwreportexport.create(data={
            "designWorkshopId": workshop_id,
            "format": fmt,
            "templateId": template_id,
            "fileName": file_name,
            "fileSizeBytes": len(blob),
            "pageCount": page_count,
            "checksumSha256": hashlib.sha256(blob).hexdigest(),
            "generatedOnDevice": False,
            "schemaVersion": registry_version(),
            "warnings": "\n".join(warnings) if warnings else None,
            "generatedById": current_user.id,
        })

    return Response(content=blob, media_type=_MIME[fmt], headers=headers)


@router.get("/{workshop_id}/exports")
async def list_exports(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> list[dict[str, Any]]:
    await load_workshop_or_404(workshop_id, current_user)
    rows = await db.dwreportexport.find_many(
        where={"designWorkshopId": workshop_id}, order={"generatedAt": "desc"}, take=100
    )
    return [
        {
            "id": r.id, "format": r.format, "templateId": r.templateId,
            "fileName": r.fileName, "fileSizeBytes": r.fileSizeBytes,
            "pageCount": r.pageCount, "checksumSha256": r.checksumSha256,
            "generatedOnDevice": r.generatedOnDevice,
            "generatedAt": r.generatedAt.isoformat() if r.generatedAt else None,
            "warnings": r.warnings,
        }
        for r in rows
    ]


@router.post("/{workshop_id}/exports", status_code=status.HTTP_201_CREATED)
async def record_device_export(
    workshop_id: str, payload: ExportRecordIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Record a report the phone generated offline.

    The bytes are not uploaded — only the fact, the checksum and the size. A designer on a
    metered field connection should not be charged for a thirty-megabyte report merely to prove
    one was made; the checksum is enough to match the file later.
    """
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    fmt = payload.format.upper()
    if fmt not in _MIME:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                            detail="Unknown export format")
    row = await db.dwreportexport.create(data={
        "designWorkshopId": workshop_id,
        "format": fmt,
        "templateId": payload.templateId,
        "fileName": payload.fileName,
        "fileSizeBytes": payload.fileSizeBytes,
        "pageCount": payload.pageCount,
        "checksumSha256": payload.checksumSha256,
        "generatedOnDevice": True,
        "schemaVersion": registry_version(),
        "warnings": payload.warnings,
        "generatedById": current_user.id,
        "generatedAt": _parse_datetime(payload.generatedAt) or datetime.now(UTC),
    })
    return {"id": row.id}


# How much of each list one history request may carry. The export cap matches ``list_exports``'s
# hundred; the entry cap is an order of magnitude above the 270 rows a fully-populated 22-stage
# workshop holds, so in practice it never bites — but a cap that bites silently would let the
# client claim a stage was "unchanged" when the rows that changed it were simply not sent, which
# is a confident wrong answer to the one question this feature exists to answer. Both truncations
# are reported, and the entry list is ordered by ``updatedAt`` descending so that if the cap ever
# does bite it keeps the rows a diff is about and drops the ones nobody has touched in months.
_HISTORY_EXPORT_LIMIT = 100
_HISTORY_ENTRY_LIMIT = 2000


@router.get("/{workshop_id}/report-history")
async def report_history(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Every report ever generated for this workshop, and the timestamps a diff can be built from.

    A report that goes to a ministry is revised three or four times, and nothing in the system could
    answer "did you update the cost sheet before you resubmitted?". Almost everything that question
    needs was already recorded — ``DwReportExport`` has carried the checksum, size, page count,
    template, registry version and timestamp of every file since the feature shipped, INCLUDING the
    ones a phone produced offline. What no client could reach was two things:

    * **Who generated a file.** ``GET /{id}/exports`` returns ten fields and the generator is not
      among them, though the column has always been populated.
    * **When each stage row was last written.** ``GET /{id}`` returns the data with no timestamps at
      all, and ``entry_rows`` filters ``deletedAt: None`` — so a row DELETED between two exports, a
      struck-out cost line being the obvious case, is invisible in every existing payload. A diff
      built on those payloads would report the cost sheet unchanged on exactly the revision that
      changed it.

    **THIS ENDPOINT SERVES FACTS, NOT A DIFF**, and that is a deliberate split rather than an
    omission. The comparison itself — which stages moved between two files, which are provably
    untouched — is arithmetic over data the caller now holds, so it belongs on the device
    (``frontend/lib/reportDiff.ts``), where a designer can flip between generation 1 and generation
    4 with no further request. What could not be done on the device is knowing the exports and the
    timestamps exist at all: the export table records files made on other devices by other people,
    so unlike a stage form this screen genuinely cannot be served from the local draft.

    **WHAT IS DELIBERATELY NOT HERE.** No snapshot of the stage data as it stood at each export is
    kept anywhere, so no field-level diff is possible from this payload and none is implied by it.
    What a client can honestly say is which stages were WRITTEN TO inside a window and which were
    provably not — and the two halves are not equally strong. ``save_stage`` updates every row a
    payload names without comparing it to what is stored, so "written" means SAVED and never
    "differs"; "not written", by contrast, is a proof that both files carried identical data. Any
    client rendering this must keep that asymmetry (``frontend/lib/reportDiff.ts`` does).

    Stage titles and template names are absent for a different reason — every client already caches
    the field registry and ``/templates``, and a second copy of a stage's title travelling on this
    wire is a second thing to drift.

    ``serverTime`` is here because "edited since the newest export" compares a server-written
    ``updatedAt`` against now, and a field laptop whose clock is a day out would otherwise invent or
    hide a day of edits. Read against this, not against the device's own clock.

    **NOTHING HERE IS WRITTEN.** An export row whose size or checksum could be rewritten afterwards
    would not be evidence of anything; the checksum is the whole point of the record.

    Gated by ``load_workshop_or_404`` like every other read of this workshop — 404 rather than 403,
    so the id is not confirmed to somebody entitled to know nothing about it.
    """
    record = await load_workshop_or_404(workshop_id, current_user)

    exports = await db.dwreportexport.find_many(
        where={"designWorkshopId": workshop_id},
        order={"generatedAt": "desc"},
        take=_HISTORY_EXPORT_LIMIT + 1,
        include={"generatedBy": True},
    )
    # Deliberately unfiltered on `deletedAt`: a removed row IS a change between two files, and it
    # is the only kind of change that leaves nothing behind to notice.
    entries = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id},
        order={"updatedAt": "desc"},
        take=_HISTORY_ENTRY_LIMIT + 1,
    )
    entries_truncated = len(entries) > _HISTORY_ENTRY_LIMIT

    return {
        "workshopId": workshop_id,
        # The cover page's craft, cluster, dates and title live on the workshop row rather than in
        # any stage entry, so a diff without this reports "nothing changed" on a revision whose
        # whole point was a corrected cluster name. One timestamp only knows the LAST write — the
        # client says so rather than counting header edits it cannot see.
        "workshopUpdatedAt": _iso(getattr(record, "updatedAt", None)),
        "serverTime": datetime.now(UTC).isoformat(),
        # TODAY'S SCORES, computed from the rows already in hand rather than left to the client to
        # go and fetch. Without this the screen has to call `GET /{id}` purely to reach
        # `completeness` — which returns every field of all 270 stage rows plus the transcript
        # annexure, a payload measured in hundreds of kilobytes, over the metered rural connection
        # this whole application is written for. Here it costs no extra query and a few hundred
        # bytes. Deleted rows are excluded, exactly as `entry_rows` would: a struck-out cost line
        # belongs in the TIMELINE above, because its removal is a change, and nowhere near a score
        # of what the workshop currently holds.
        #
        # It is today's figure and only today's — nothing stored says what a stage scored when a
        # past report was generated. The client attaches it to an export only where the timeline
        # proves the stage has not been written to since; see `reportDiff.currentReflectsBoth`.
        #
        # WITHHELD ENTIRELY once the entry cap has bitten, rather than scored from the rows that
        # happened to fit. A percentage computed over a truncated set is not a slightly-off
        # percentage, it is a wrong one that looks exactly like a right one — and the client draws
        # nothing for a stage it has no score for, which is the correct outcome.
        "completeness": (
            {} if entries_truncated
            else workshop_completeness([r for r in entries if r.deletedAt is None])
        ),
        "exports": [_export_payload(row) for row in exports[:_HISTORY_EXPORT_LIMIT]],
        "exportsTruncated": len(exports) > _HISTORY_EXPORT_LIMIT,
        "entries": [
            {
                "id": row.id,
                "stageKey": row.stageKey,
                "entityKey": row.entityKey,
                "ordinal": row.ordinal,
                "createdAt": _iso(row.createdAt),
                "updatedAt": _iso(row.updatedAt),
                "deletedAt": _iso(row.deletedAt),
            }
            for row in entries[:_HISTORY_ENTRY_LIMIT]
        ],
        "entriesTruncated": entries_truncated,
    }


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def _export_payload(row: Any) -> dict[str, Any]:
    """One recorded export, as the history screen reads it.

    A superset of what ``list_exports`` returns, and additive to it rather than a replacement: the
    generator, the registry version in force at generation, and the storage key where one exists.
    ``generatedBy`` is ``SetNull``, so an export made by an account that has since been deleted
    names NOBODY — not the workshop's owner, who is the tempting default and would put a name
    against a file they never produced.
    """
    author = getattr(row, "generatedBy", None)
    return {
        "id": row.id,
        "format": row.format,
        "templateId": row.templateId,
        "fileName": row.fileName,
        "fileSizeBytes": row.fileSizeBytes,
        "pageCount": row.pageCount,
        "checksumSha256": row.checksumSha256,
        "generatedOnDevice": row.generatedOnDevice,
        "schemaVersion": row.schemaVersion,
        "warnings": row.warnings,
        "generatedAt": _iso(row.generatedAt),
        "generatedById": row.generatedById,
        "generatedByName": getattr(author, "name", None) if author else None,
    }


async def _transcripts_payload(entries: list[Any], viewer: Any) -> dict[str, Any]:
    """THE TRANSCRIPT COMING BACK ONTO THE STAGE, keyed by the media id the AUDIO field holds.

    A designer records an artisan explaining a technique, the media queue transcribes it minutes or
    hours later, and the text has to appear against the field they recorded it into — otherwise the
    transcription is invisible and the feature may as well not exist. Keying by media id rather
    than by field key is what makes that work for a collection: five prototypes each with a voice
    note are five ids in one payload, and the client matches each to the row holding it.

    A stage with no audio costs one dictionary and no query.

    ``viewer`` gates it: the transcript is the CONTENT of a recording, so an AUDIO id a client wrote
    onto a stage is not on its own permission to read one back. See ``load_transcript_items``.
    """
    items = await load_transcript_items(entries, viewer=viewer)
    return {item.media_id: item.payload() | {"text": item.text} for item in items}


def _require_designer(user: Any) -> None:
    """Gate the two capture aids on the same rank that may run a workshop at all.

    Stated here rather than as a dependency because both routes already take ``current_user`` for
    other reasons, and because these are not record operations: there is no workshop to own and no
    row to check, only a question of whether this account is one the app invites to capture. A card
    scan sends a photograph of somebody's Aadhaar to a third-party model and a dictation spends
    provider credit per press, so neither is something to leave open to every signed-in account.
    """
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Running a design workshop requires Designer access or above.",
        )


def _parse_date(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(str(raw)[:10]).replace(tzinfo=UTC)
    except ValueError:
        return None


def _parse_datetime(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        text = str(raw).replace("Z", "+00:00")
        parsed = datetime.fromisoformat(text)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)
    except ValueError:
        return None


def _stages_payload(entries: list[Any]) -> dict[str, Any]:
    """Group stage entry rows into ``{stage: {singleton, collections}}``."""
    from app.services.stage_schema import Cardinality

    entity_cardinality = {
        e.key: e.cardinality for s in stages() for e in s.entities
    }
    out: dict[str, Any] = {}
    for row in entries:
        bucket = out.setdefault(row.stageKey, {"singleton": {}, "collections": {}})
        data = dict(row.data or {})
        if entity_cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            bucket["singleton"] = data
        else:
            data["_entryId"] = row.id
            data["_ordinal"] = row.ordinal
            if row.clientKey:
                data["_clientKey"] = row.clientKey
            bucket["collections"].setdefault(row.entityKey, []).append(data)
    for bucket in out.values():
        for rows in bucket["collections"].values():
            rows.sort(key=lambda r: r.get("_ordinal", 0))
    return out


async def _report_inputs(
    workshop_id: str,
    record: Any,
    *,
    viewer: Any,
    requested_template_id: Any = None,
    transcripts: bool | None = None,
) -> tuple[Any, Any, list[str], str]:
    """Everything the synchronous render needs, loaded on the event loop before it starts.

    The third element is the warnings this loading produced — a transcript annexure shorter than
    the designer expected, a photograph that could not be resolved — which the caller merges with
    the builder's own. The fourth is the resolved template id, resolved HERE because the loads
    below have to know what the document will contain; the callers use it rather than resolving it
    a second time and risking two answers.

    THREE WAVES, NOT FIVE SEQUENTIAL LOADS, and on this deployment that is the whole cost of the
    endpoint. The database is in another AWS region and one round trip measured 756ms against
    queries that execute in under a millisecond (``services/concurrency``), so a fully-referenced
    workshop paid roughly 6.8s of pure network before the renderer started, and again on Generate.
    The dependency graph only ever demanded three: the entries, then everything that reads the
    entries, then the media resolver — which cannot start until ``attach_report_references`` has
    told it about the photographs hanging off the REFERENCED records rather than off the stages.
    The loads in wave 2 write DIFFERENT attributes of ``data`` (``references``,
    ``district_points``, the transcripts, the questionnaires), so gathering them is safe; anything
    that shared one would have to stay sequential. Two of the four are CONDITIONAL — the map's
    anchors and the questionnaire annexure are appended only when the resolved template draws them —
    which is why the warnings below are indexed from the END of ``results`` rather than by position.

    ``viewer`` is threaded through to the two media reads because a media id on a stage is whatever
    a client wrote there — see ``design_workshops.media_resolver``.
    """
    entries = await entry_rows(workshop_id)
    data = assemble_workshop_data(record, entries)
    # RESOLVED BEFORE THE LOADS, because one of them is only worth paying for on some templates.
    # `resolve_template_id` needs the stage-20 answers, which is why this cannot sit any earlier.
    template_id = resolve_template_id(
        requested_template_id, data.singleton("REPORT_GENERATION"), record
    )

    # `attach_report_references` fills the photographs that are not in the entries at all — an
    # artisan's portrait, the catalogue picture of the product a prototype copied — and the map's
    # artisan pins, whose home district lives on the Artisan's Location and nowhere on the roster
    # row. Its result feeds the resolver, which is what makes it wave 2 and the resolver wave 3.
    loads: list[Any] = [attach_report_references(data, entries)]
    # POSITIONS ONLY WHEN SOMETHING DRAWS THEM. This reads every pinned Location in the repository
    # and folds it across all 795 districts; four of the six templates contain no map at all and
    # threw the whole result away. The cost tracked the size of the archive rather than the size of
    # the workshop, on both Preview and Generate. `apply_report_settings` can only REMOVE sections
    # and MAP is not one of the toggles it removes, so the base template is the right thing to ask.
    specials = {section.special for section in get_template(template_id).sections}
    draws_map = SpecialSection.MAP in specials
    if draws_map:
        loads.append(attach_district_anchors(data))
    # THE SAME "ONLY WHEN SOMETHING DRAWS IT" RULE, and it is not a micro-optimisation here either:
    # this is five queries against the questionnaire tables, and `PHOTO_CATALOGUE` — or any template
    # a later release ships without the annexure — would throw every row away. `apply_report_settings`
    # can only REMOVE sections, so the base template is the right thing to ask, exactly as the map
    # above asks it.
    draws_questionnaires = SpecialSection.ANNEXURE_QUESTIONNAIRES in specials
    if draws_questionnaires:
        loads.append(attach_report_questionnaires(data, workshop_id))
    loads.append(
        attach_report_transcripts(data, entries, viewer=viewer, requested=transcripts)
    )

    results = await gather_reads(*loads)
    reference_photos = results[0]
    # The LAST two loads, in the order they were appended: the questionnaire annexure's warnings when
    # it was loaded at all, and the transcript annexure's always. Indexed from the end rather than by
    # position because the map load in the middle is conditional.
    warnings = list(results[-1])
    if draws_questionnaires:
        warnings = list(results[-2]) + warnings

    resolver = await media_resolver(entries, viewer=viewer, extra_ids=reference_photos)
    if resolver.withheld:
        # NOT SILENT. A photograph that is missing from a report reads as a photograph nobody took;
        # saying so is what tells a designer to ask the colleague who uploaded it for a data-access
        # grant instead of re-photographing an artisan who has gone home.
        warnings.append(
            f"{len(resolver.withheld)} attached file(s) could not be included: they were "
            "uploaded by another account, or the file is gone."
        )
    return data, resolver, warnings, template_id


def _build_only(data: Any, template_id: str, resolver: Any, record: Any) -> tuple[Any, list[str]]:
    """Build the document without rendering it, for the preview.

    ``resolver.ref`` and not ``resolver`` — the builder wants geometry, not bytes, and the
    preview never needs the bytes at all because the browser fetches each photo by its own
    media URL.

    EVERY STAGE-20 ANSWER IS APPLIED HERE TOO, and that is the whole point of this function
    changing. The preview used to build the bare template: no accent, no cover overrides, none of
    the section toggles. So a designer switched the report to maroon, turned the annexures off,
    looked at a preview that was still indigo with both annexures in it, and either submitted a
    file they had never actually seen or concluded the settings were broken. A preview that does
    not match the file is worse than no preview, because it is trusted.

    The colour is resolved here rather than only in the browser for the same reason. The web page
    mirrors this palette client-side, and two independent derivations of one colour are two
    chances to disagree.
    """
    from dataclasses import replace

    from app.services.design_workshops import report_meta
    from app.services.report_builder import build_report
    from app.services.report_templates import apply_report_settings, template as get_template
    from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent

    settings = data.singleton("REPORT_GENERATION")
    template = get_template(template_id)
    accent = resolve_accent(None, settings)
    theme = theme_from_accent(accent, base=template.theme) if accent else template.theme
    fonts = resolve_font(None, settings)
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])

    return build_report(
        data, template_id, resolver.ref,
        meta=report_meta(record, template_id, settings),
        theme=theme,
        template=apply_report_settings(template, settings),
    )


def _report_file_name(record: Any, fmt: str) -> str:
    """A file name safe on every filesystem the report will land on.

    Windows forbids nine characters outright and a report named after a craft is routinely
    saved onto a departmental share; a name that fails to save is a report that was not
    delivered.

    Unicode letters are kept here — a designer whose workshop is titled in Odia should get a
    file named in Odia — and :func:`_content_disposition` is what makes that safe to put in a
    header. Sanitising to ASCII in this function instead would have been the easy fix and the
    wrong one: it would have named every Devanagari workshop ``workshop_20260807.docx``, and
    a folder of thirty identically-named reports is its own kind of data loss.
    """
    stem = record.workshopCode or record.title or "workshop"
    safe = "".join(c if (c.isalnum() or c in " -_") else "_" for c in str(stem)).strip()
    safe = "_".join(safe.split())[:60] or "workshop"
    stamp = datetime.now(UTC).strftime("%Y%m%d")
    return f"DesignWorkshop_{safe}_{stamp}.{_EXTENSION[fmt]}"


def _content_disposition(file_name: str) -> str:
    """An RFC 6266 Content-Disposition that survives a non-ASCII file name.

    THE BUG THIS EXISTS FOR. Every ASGI header value is encoded latin-1, so a single codepoint
    above U+00FF raises ``UnicodeEncodeError`` inside Starlette — after the handler has already
    returned, as a bare 500 with no indication which header did it. ``str.isalnum()`` is True
    for every Unicode letter, so a workshop titled ``ସମ୍ବଲପୁରୀ ଇକତ କର୍ମଶାଳା`` sailed through the
    sanitiser above and made report generation impossible for that record, permanently, with no
    in-app workaround. On an app built for Indian craft clusters that disabled the product's
    primary deliverable for a large share of workshops.

    The fix is the form the RFC defines for exactly this: an ASCII fallback in ``filename=`` for
    anything that cannot read the extended form, and a percent-encoded UTF-8 ``filename*=`` that
    every browser released this decade prefers. The designer gets the Odia name; the header
    stays latin-1.
    """
    ascii_name = "".join(
        c if (c.isascii() and (c.isalnum() or c in " -_.")) else "_" for c in file_name
    ).strip("_ ") or "workshop-report"
    quoted = quote(file_name, safe="")
    return f'attachment; filename="{ascii_name}"; filename*=UTF-8\'\'{quoted}'


#: How much of ``X-Report-Warnings`` a response may spend. A cap is not optional — every warning is
#: a whole sentence, a fully-referenced DCH_STANDARD workshop raises a dozen of them, and proxies in
#: front of this API refuse a response whose headers exceed 4-8 KB outright, which would turn a
#: successful report into a failed download.
_WARNINGS_HEADER_BUDGET = 900


def _warnings_header(warnings: list[str]) -> str:
    """The warnings as one ``X-Report-Warnings`` value: whole sentences, and never a silent tail.

    THE DEFECT THIS EXISTS FOR, MEASURED RATHER THAN IMAGINED. This header used to be built as
    ``"; ".join(warnings)[:900]``, and on DCH_STANDARD — the DEFAULT template — a workshop with an
    attached questionnaire raised twelve warnings of which the header carried eight. The twelfth was
    ``"1 questionnaire(s) attached to this workshop have no recorded answers and were left out of the
    questionnaire annexure (…)"``: the one sentence that tells a designer WHY the annexure they were
    promised is not in the file. It was cut off, and nothing said so — the designer saw a report with
    no questionnaire annexure and no explanation, which is exactly the complaint that sent this lane
    looking. The load warnings are appended last (see ``generate_report``), so they are always the
    first casualties, and they are the ones that describe a WHOLE ANNEXURE missing from the document
    rather than a field missing from inside it.

    The eighth item was also cut MID-WORD — the header ended ``"Stage 9 (…): 2 required "`` — and
    ``frontend/lib/designWorkshops.ts`` splits this value on ``";"`` and shows each piece to the
    designer, so a half-sentence was rendered as a complete warning.

    So: pack WHOLE warnings until the budget, then say how many did not fit. This is the same rule
    ``report_annexures.MAX_PARAGRAPHS_PER_TRANSCRIPT`` and ``report_questionnaires``' sitting cap
    already apply inside the document — a visible note explaining where it stopped, never a silent
    drop — applied to the transport that carries the warnings about it.

    ``x-report-warning-count`` stays the TRUE total and is not reduced to what fitted: a client that
    compares the two can tell that it is not holding the whole list, and a client that ignores the
    header entirely still gets the count right.

    ONE WARNING IS ONE PIECE, and the packing is only half of what makes that true — the other half
    is :func:`one_piece` below, because a semicolon inside a warning splits it just as effectively
    as truncation did.

    Non-ASCII is replaced rather than dropped for the reason :func:`_content_disposition` exists:
    every ASGI header value is encoded latin-1, so a warning naming a craft in Odia would raise
    inside Starlette after the handler returned and turn a generated report into a bare 500.
    """
    def one_piece(value: str) -> str:
        """One warning as exactly ONE piece of this header, whatever text it carries.

        ``";"`` is this header's item separator and ``frontend/lib/designWorkshops.ts`` splits the
        value on it, so a semicolon INSIDE a warning is indistinguishable from the boundary between
        two — and the packing above, which exists so the designer never reads a fragment, cannot
        help with a fragment the content itself creates.

        MEASURED, NOT IMAGINED. ``report_questionnaires.questionnaire_warnings`` interpolates the
        questionnaire's TITLE, which a designer types. A form called "Loom survey; round two"
        produced ``x-report-warning-count: 2`` and THREE pieces on the screen, the last of them
        ``"round two)."`` — a warning that a whole annexure is missing from the report, delivered as
        two half-sentences, one of which means nothing on its own.

        A comma rather than a deletion because the semicolon is doing work in the sentence a
        designer wrote, and losing the pause reads worse than shifting it. See :func:`_dropped_note`
        for the same constraint stated from the other side.

        Non-ASCII is replaced first, for the reason in this function's own docstring.
        """
        return str(value).encode("ascii", "replace").decode("ascii").replace(";", ",")

    items = [one_piece(w) for w in warnings if str(w).strip()]
    if not items:
        return ""

    joined = "; ".join(items)
    if len(joined) <= _WARNINGS_HEADER_BUDGET:
        return joined

    # The note has to fit inside the same budget, so the room left for real warnings is measured
    # against the WIDEST note this call could end up printing — one naming every item as dropped.
    room = _WARNINGS_HEADER_BUDGET - len(_dropped_note(len(items))) - 2
    kept: list[str] = []
    used = 0
    for item in items:
        cost = len(item) + (2 if kept else 0)
        if used + cost > room:
            break
        kept.append(item)
        used += cost

    # A single warning longer than the whole budget would otherwise produce a header that says only
    # that something was dropped and nothing about what. Truncating that one is the lesser loss, and
    # the ellipsis marks it as truncated rather than passing a fragment off as a sentence.
    if not kept:
        kept = [items[0][: max(1, room - 3)].rstrip() + "..."]

    dropped = len(items) - len(kept)
    return "; ".join(kept + ([_dropped_note(dropped)] if dropped else []))


def _dropped_note(count: int) -> str:
    """What the header says instead of the warnings it could not carry.

    Names the preview because that is where the full list is actually reachable — ``GET
    /report/preview`` returns ``warnings`` as an uncapped JSON array built from the same load — so
    this is an instruction a designer can act on rather than an apology.

    NO SEMICOLON INSIDE IT, and that is a constraint rather than a preference: ``"; "`` is this
    header's item separator and ``frontend/lib/designWorkshops.ts`` splits on ``";"`` and prints
    each piece as its own warning, so a semicolon here would break this one sentence into two
    half-sentences on the designer's screen — the same "a fragment shown as a whole warning" defect
    the packing above exists to stop.
    """
    return (
        f"{count} further warning(s) did not fit in this header. The report preview lists all of "
        "them."
    )


def _block_payload(block: Any) -> dict[str, Any]:
    """One report block as JSON, for the web preview.

    Deliberately a shallow projection rather than a full serialisation: the preview needs to
    draw the block, not to reconstruct the dataclass, and a lossless encoding would tempt a
    client into rendering from its own reassembled model instead of from this one.
    """
    from dataclasses import asdict, is_dataclass

    name = type(block).__name__
    payload: dict[str, Any] = {"type": name.replace("Block", "").upper()}
    if is_dataclass(block):
        for key, value in asdict(block).items():
            payload[key] = value
    return payload
