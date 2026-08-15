"""HTTP surface for custom questionnaires: the .xlsx pro-forma, the upload, and answering.

Mounted at ``/api/questionnaires`` — PLURAL, and deliberately not under the existing
``/api/questionnaire`` (singular), which is the ONE global artisan questionnaire every researcher
answers. The two are different objects with the same word for a name, and a client that had to tell
``/questionnaire/sections`` from ``/questionnaires/{id}/sections`` by counting characters would get
it wrong. See the block comment above ``model Questionnaire`` in schema.prisma for why they are
separate all the way down.

The loop a designer walks:

    GET  /questionnaires/pro-forma            download a blank workbook
    POST /questionnaires/upload               upload the filled-in one  -> a questionnaire
    GET  /questionnaires                      list (the attach-to-a-workshop dropdown reads this)
    GET  /questionnaires/{id}                 read the form, its sittings and its answers
    GET  /questionnaires/{id}/xlsx            download it back, with question ids and answers
    POST /questionnaires/{id}/upload          re-upload an edited copy  -> an EDIT, under the rule
    PATCH /questionnaires/{id}                rename, attach to a workshop, deactivate
    POST /questionnaires/{id}/entries         start a sitting
    PUT  /questionnaires/{id}/entries/{eid}/answers   record answers

THERE IS NO WAY TO DELETE A QUESTIONNAIRE HERE, and that is the point rather than an omission. A
questionnaire with answers against it is somebody's fieldwork; ``PATCH {isActive: false}`` takes it
out of every list and every dropdown, and the answers stay. The one DELETE in this file removes a
single QUESTION, and even that only really deletes when nobody has answered it — otherwise it
retires. The database agrees with both: see the ``ON DELETE RESTRICT`` on
``QuestionnaireFormAnswer.questionId``.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile, status
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import (
    can_run_design_workshops,
    get_current_user,
    is_admin,
)
from app.schemas.questionnaire import (
    CustomAnswerBatch,
    CustomQuestionCreate,
    CustomQuestionUpdate,
    CustomSectionCreate,
    CustomSectionUpdate,
    QuestionnaireCreate,
    QuestionnaireEntryCreate,
    QuestionnaireEntryUpdate,
    QuestionnaireUpdate,
)
from app.services.design_workshop_viewers import has_viewer_grant
from app.services.design_workshops import load_workshop_or_404
from app.services.pagination import normalize_pagination, page_payload
from app.services.questionnaire_forms import (
    APP_ENTRY_SOURCE,
    QuestionnaireEditError,
    apply_parsed_edit,
    bump_version,
    create_from_parsed,
    export_payload,
    guard_question_edit,
    load_form,
    save_answers,
    supersede_question,
)
from app.services.questionnaire_xlsx import (
    PRO_FORMA_FILENAME,
    XLSX_MIME,
    QuestionnaireXlsxError,
    build_pro_forma,
    build_questionnaire_workbook,
    derive_section_code,
    download_filename,
    parse_questionnaire_workbook,
)
from app.services.records import clean_data, public_encode, require_record

router = APIRouter(prefix="/questionnaires", tags=["questionnaires"])

# A questionnaire is a page of typing, not a media file. The ceiling is generous enough for a
# thousand-question instrument with answers in it and low enough that this endpoint cannot be used
# to push a hundred megabytes through a synchronous parser.
MAX_UPLOAD_BYTES = 8 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


# --- Access -------------------------------------------------------------------------------------
#
# Two levels, matching design_workshops.py rather than inventing a third scheme:
#   * running a design workshop at all  -> may read questionnaires and record answers,
#   * owning this questionnaire (or admin) -> may change its questions.
# The split is what lets a designer hand a colleague a form to fill in without also handing them
# the ability to reword it halfway through the fieldwork.
#
# NEITHER OF THOSE TWO SAYS ANYTHING ABOUT THE WORKSHOP a questionnaire is attached to, and that is
# the gap that let any designer post their form into a stranger's report annexure. That gap has TWO
# doors and both are now gated by a third question, asked of design_workshops.py's own helper:
#   * ATTACHING a form to a workshop        -> ``_require_attachable_workshop``
#   * WRITING sittings and answers to a form ALREADY attached to one
#                                           -> ``_require_recordable_questionnaire``
# Closing only the first left the second wide open — the id of an attached form is enough to inject
# an interview into somebody else's ministry submission — so if a third door ever appears (a route
# that writes anything a workshop's report prints), it asks the same question through the same
# helper rather than growing a fourth rule here.


def _require_designer(user: Any) -> None:
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Building a questionnaire requires Designer access or above.",
        )


async def _require_questionnaire(questionnaire_id: str, user: Any) -> Any:
    _require_designer(user)
    return await require_record(db.questionnaire, questionnaire_id)


async def _works_on_this_questionnaires_workshop(record: Any, user: Any) -> bool:
    """Whether `user` is on the design workshop this questionnaire is attached to.

    THE HOLE THIS CLOSES. `DesignWorkshopViewer` lets an admin put a second designer on a workshop,
    and a grant admits them to the workshop AND to writing its stages. But a questionnaire is owned
    by whoever uploaded it and scoped on `ownerId` alone, so the co-designer opened the workshop,
    saw stage 7 tell them a survey questionnaire exists, and found nothing in the questionnaire list
    — the two halves of one piece of fieldwork disagreeing about who is working on it.

    IT ADMITS THE SITTINGS TOO, and that is deliberate rather than an oversight about sensitive
    data. A sitting carries a respondent's name and answers — but so does stage 8's `surveyResponse`
    collection, which a granted co-designer can already read AND EDIT through the stage form. Hiding
    the questionnaire's copy of the same interview while showing the stage's copy would protect
    nothing and would only make the questionnaire look empty. The grant is the decision; this
    follows it.

    An unattached questionnaire (`designWorkshopId` is null) is untouched by this and stays the
    owner's alone.

    A DELETED WORKSHOP ADMITS NOBODY, BY EITHER CLAUSE. The grant branch used to answer before the
    workshop row was ever read, so a viewer row outlived the workshop it was about: after a soft
    delete the colleague was 404'd from the workshop, 404'd from its stages and shown nothing in any
    list — `_visible_questionnaire_where` carries `deletedAt: None` on both of its grant clauses —
    while `GET /questionnaires/{id}` still handed over every sitting and `/xlsx` still served the
    lossless workbook of respondents' names and answers. The creator branch below already asked, so
    the two halves of this one helper disagreed and the GRANTED colleague outlived the designer who
    CREATED the workshop. Reading the row first is what makes the two agree, and it makes this
    function agree with `load_workshop_or_404`, which refuses a deleted workshop to everyone but an
    admin.

    Ordered creator-then-grant for the same reason `load_workshop_or_404` is: the creator is a field
    comparison on a row already in hand, and the grant is a second (indexed, primary-key) lookup that
    only the co-designer path has to pay for.
    """
    workshop_id = getattr(record, "designWorkshopId", None)
    if not workshop_id:
        return False
    workshop = await db.designworkshop.find_unique(where={"id": workshop_id})
    if workshop is None or workshop.deletedAt is not None:
        return False
    # The workshop's own creator, who holds no grant row because they never needed one.
    if workshop.createdById == user.id:
        return True
    return await has_viewer_grant(workshop_id, user.id)


def _visible_questionnaire_where(user: Any) -> dict[str, Any]:
    """The row filter for "questionnaires this designer may see".

    AND-COMPOSED BY THE CALLER, NEVER ASSIGNED TO `where["OR"]`. The list endpoint already spends
    `OR` on its search box, so writing this as a top-level `OR` would silently replace the search
    and widen the result set — the identical trap the design-workshop list hit when viewer grants
    were added there. Returning a fragment for `where["AND"]` makes that mistake impossible to make.
    """
    return {
        "OR": [
            {"ownerId": user.id},
            {"designWorkshop": {"is": {"createdById": user.id, "deletedAt": None}}},
            {
                "designWorkshop": {
                    "is": {"deletedAt": None, "viewers": {"some": {"userId": user.id}}}
                }
            },
        ]
    }


def _require_owner(record: Any, user: Any) -> None:
    if record.ownerId != user.id and not is_admin(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the designer who created this questionnaire, or an admin, can change it.",
        )


async def _require_attachable_workshop(workshop_id: str, user: Any) -> None:
    """The workshop a questionnaire is about to be pointed at — or a 404, as if it did not exist.

    ATTACHING IS A WRITE TO SOMEBODY ELSE'S RECORD, WHICH IS WHY THIS IS NOT ``require_record``.
    All three routes that set ``Questionnaire.designWorkshopId`` (upload, create, patch) used to ask
    only whether a workshop ROW EXISTS: ``require_record(db.designworkshop, id)`` is a bare
    ``find_unique`` — no creator test, no ``DesignWorkshopViewer`` test, no admin test, not even a
    ``deletedAt`` filter. ``_require_designer`` above admits EVERY designer account, and
    ``_require_owner`` on the PATCH is ownership of the QUESTIONNAIRE, not of the workshop being
    named. So one ordinary call — ``POST /api/questionnaires`` with
    ``{"designWorkshopId": "<a workshop the caller is 404'd from>"}`` — returned 201, and the door
    swung both ways:

    * ``report_items`` (services/questionnaire_forms.py:292) selects on
      ``{"designWorkshopId": ..., "isActive": True}`` with NO permission filter, on the stated
      ground that "the attachment is what puts the questionnaire inside the workshop's own access
      boundary" — an assumption only this function makes true. ``QUESTIONNAIRE_ANNEXURE`` is in
      ALL SIX report templates (report_templates.py:354, 367, 400, 431, 448, 479 — that is every
      entry of ``TEMPLATES``, counted 2026-08-15), so a stranger's sittings — respondent names,
      interviewer notes, every recorded answer — printed as an annexure of another designer's
      fieldwork in the .docx submitted to a ministry under that designer's name.
      ``questionnaire_warnings`` fires only for an attached form with NO answers, so an ANSWERED
      one went in silently, warning neither party.
    * ``_works_on_this_questionnaires_workshop`` above then admits everybody on the target workshop
      to the attached form, so the attacker's own respondents' names and answers were pushed into a
      stranger's access boundary as well, readable through ``GET /questionnaires/{id}`` and
      downloadable through ``/xlsx``. A mistyped or stale workshop id produced all of this by
      accident.

    ``load_workshop_or_404(..., for_edit=True)`` is deliberately the SAME helper ``save_stage_data``
    and ``save_custom_sections`` use, and not a private near-copy of it. Attaching a questionnaire
    changes what that workshop's report SAYS, so it has to be exactly as hard as editing one of its
    stages — creator, admin, or grant-holder — and it must refuse a soft-deleted workshop (409)
    instead of quietly filling the annexure of a record nobody can open. If a fourth attachment
    route is ever added, it calls this; do not reach for ``require_record`` again because it is one
    line shorter.

    404 rather than 403, inherited from the helper and for the helper's reason: a 403 would confirm
    that the id exists to precisely the caller being turned away.

    DETACHING NEEDS NO CHECK, and none of the three callers makes one. Sending
    ``designWorkshopId: null`` only ever removes a pointer; demanding rights over the workshop in
    order to let go of it would strand a questionnaire on a workshop whose grant was withdrawn, with
    its owner unable to take their own form back.
    """
    await load_workshop_or_404(workshop_id, user, for_edit=True)


async def _require_recordable_questionnaire(record: Any, user: Any) -> None:
    """The other half of ``_require_attachable_workshop``: WRITING to a form already attached.

    THE ATTACH FIX CLOSED ONE OF TWO DOORS AND THIS IS THE SECOND ONE. Stopping a designer POINTING
    a questionnaire at a stranger's workshop does nothing about a designer who already holds the id
    of a form that is attached to one: ``POST /questionnaires/{id}/entries`` and
    ``PUT /questionnaires/{id}/entries/{eid}/answers`` were gated by ``_require_questionnaire``
    alone — role plus "the row exists" — so any DESIGNER account could add a sitting, with a
    respondent's NAME on it, and a full set of answers, to another team's instrument. Those
    sittings print: ``report_items`` selects on ``designWorkshopId`` with no permission filter and
    ``QUESTIONNAIRE_ANNEXURE`` is in all six templates, so the fabricated interview goes into the
    .docx another designer submits to a ministry under their own name. Exactly the consequence the
    attach hole had, reached from the other side.

    IT IS THE SAME CHECK, DELIBERATELY, and ``load_workshop_or_404(..., for_edit=True)`` is called
    through the same helper the attach path uses rather than a second predicate written here.
    Adding a sitting changes what that workshop's report SAYS, which is the argument
    ``_require_attachable_workshop`` makes for attaching and ``save_stage_data`` makes for a stage
    write; three ways of saying the same thing would be three things to keep in step. So: the
    workshop's creator, an admin, or the holder of a ``DesignWorkshopViewer`` grant — and a
    REVOKED grant now takes effect on writes exactly as it already does on reads
    (``_works_on_this_questionnaires_workshop``), which was the point of the finding.

    AN UNATTACHED QUESTIONNAIRE IS UNTOUCHED AND THAT IS NOT AN OVERSIGHT. ``create_entry``'s rule
    — "a form only its author may answer is a form nobody uses" — is the product working as
    designed: a designer hands a colleague a form, the colleague fills it in, and
    ``read_questionnaire`` already shows that colleague their OWN sittings and nobody else's
    (``only_entries_of``). Narrowing writes to the owner here would make that ``only_entries_of``
    branch unreachable for the very people it exists for. The workshop is what introduces a second
    party with a stake in the answers, so the workshop is what the gate asks about.

    NO OWNER BYPASS, for ``_require_attachable_workshop``'s reason: owning the FORM has never said
    anything about the workshop it points at. An owner who has lost their grant is not stranded —
    detaching needs no check at all (see that helper's last paragraph), so they can take their own
    questionnaire back and keep recording. Adding ``record.ownerId == user.id`` here would reopen
    the hole for precisely the account the revocation was about.

    404/409 rather than 403, inherited from the helper: a 403 would confirm the workshop id exists
    to the caller being turned away, and a soft-deleted workshop gets the one sentence that names
    the way out ("Restore it before editing") instead of silently collecting sittings for a record
    nobody can open.
    """
    workshop_id = getattr(record, "designWorkshopId", None)
    if not workshop_id:
        return
    await load_workshop_or_404(workshop_id, user, for_edit=True)


async def _read_upload(file: UploadFile) -> bytes:
    """The uploaded bytes, or a 4xx a designer can act on rather than a 500 from openpyxl."""
    name = (file.filename or "").lower()
    if name and not name.endswith(_XLSX_SUFFIXES):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{file.filename}' is not an Excel workbook. Fill in the .xlsx pro-forma and "
                "upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
            ),
        )
    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The upload was empty. Attach the filled-in pro-forma.",
        )
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"That workbook is larger than the {MAX_UPLOAD_BYTES // (1024 * 1024)} MB limit. A "
                "questionnaire is text — if the file is this big it probably has images or extra "
                "sheets in it."
            ),
        )
    return content


def _xlsx_response(payload: bytes, filename: str) -> Response:
    return Response(
        content=payload,
        media_type=XLSX_MIME,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# --- The pro-forma ------------------------------------------------------------------------------


@router.get("/pro-forma")
async def download_pro_forma(current_user: Any = Depends(get_current_user)) -> Response:
    """The blank .xlsx a designer builds their questionnaire in.

    Generated on every request rather than cached: it is a few kilobytes of openpyxl, and a stale
    cached copy whose columns no longer match the parser is a designer typing forty questions into
    headings the app no longer recognises.
    """
    _require_designer(current_user)
    return _xlsx_response(build_pro_forma(), PRO_FORMA_FILENAME)


@router.get("/{questionnaire_id}/xlsx")
async def download_questionnaire(
    questionnaire_id: str, current_user: Any = Depends(get_current_user)
) -> Response:
    """This questionnaire as the same workbook — question ids filled in, answers in their columns.

    The download half of edit-in-Excel. The ids are what make re-uploading this file an EDIT of
    these questions rather than a second copy of them, which is why the Question ID column is greyed
    and the instructions sheet says not to touch it.

    OWNER-GATED, unlike reading the form, and it is the workbook's CONTENTS that draw the line
    rather than the fact that it is a file: ``export_payload`` is deliberately lossless — every
    sitting, every respondent's name, every answer and every retired question — because the round
    trip has to be. That is the whole answer set of somebody else's fieldwork in one download, and
    the same data ``read_questionnaire`` now narrows per sitting. Re-upload has always been the
    owner's alone; this is the door beside it.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    # Exactly the rule `read_questionnaire` applies to the sittings, and it has to be exactly that
    # rule: this workbook IS the sittings, losslessly. Letting a co-designer read the answers on the
    # page and refusing them the download of the same answers would be a distinction the data cannot
    # support, and one they would route around by copying the page.
    if not (
        record.ownerId == current_user.id
        or is_admin(current_user)
        or await _works_on_this_questionnaires_workshop(record, current_user)
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This workbook carries every sitting recorded against the questionnaire, so it "
                "belongs to the designer who created it, a designer working on its design "
                "workshop, or an admin. Record answers from the questionnaire page instead."
            ),
        )
    payload = await export_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    workbook = build_questionnaire_workbook(**payload)
    return _xlsx_response(workbook, download_filename(payload["title"]))


# --- Upload -------------------------------------------------------------------------------------


@router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_questionnaire(
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    designWorkshopId: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Create a questionnaire from a filled-in pro-forma.

    ``title`` and ``designWorkshopId`` are FORM FIELDS on the same multipart body as the file, not
    query parameters — matching ``design_workshops.dictate``, the repo's other multipart endpoint
    with a scalar alongside the upload. Left as bare defaults they would have been read as query
    parameters instead, and a client that appended them to the body would have had them silently
    ignored: an untitled questionnaire attached to nothing, with a 201 saying it went fine.

    The response carries ``report.problems``: every row the parser could not read and every
    assumption it had to make, with its Excel row number. THAT LIST IS THE FEATURE. A designer who
    uploads forty questions and is shown thirty-eight, with no way to find out which two are missing
    or why, does not trust the import again — and the likeliest causes (a merged cell, a formula
    Excel never calculated, "maybe" in the Required column) are all invisible from the result.
    """
    _require_designer(current_user)
    content = await _read_upload(file)
    try:
        parsed = parse_questionnaire_workbook(content, filename=file.filename)
    except QuestionnaireXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    if designWorkshopId:
        # Rights over the WORKSHOP, not merely its existence — see _require_attachable_workshop.
        # The multipart door is the same door: a form field named designWorkshopId attaches the
        # questionnaire just as completely as the JSON one on POST/PATCH below does.
        await _require_attachable_workshop(designWorkshopId, current_user)
    questionnaire_id, report = await create_from_parsed(
        parsed,
        owner_id=current_user.id,
        title=title,
        design_workshop_id=designWorkshopId,
        source_filename=file.filename,
    )
    form = await load_form(questionnaire_id)
    return public_encode({"questionnaire": form, "report": report})


@router.post("/{questionnaire_id}/upload")
async def reupload_questionnaire(
    questionnaire_id: str,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Re-upload an edited workbook over an existing questionnaire.

    Runs the edit-after-answers rule (services/questionnaire_forms.py). The response's
    ``report.details`` names every question that was superseded or retired and says why in a
    sentence meant to be shown verbatim — a designer whose six reworded questions came back as six
    NEW questions needs to be told that happened and that their answers are safe, not left to work
    it out from a question count.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    content = await _read_upload(file)
    try:
        parsed = parse_questionnaire_workbook(content, filename=file.filename)
    except QuestionnaireXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    # A Questionnaire ID on the Details sheet that names a DIFFERENT questionnaire means the
    # designer picked the wrong file out of their downloads folder. Applying it would retire this
    # questionnaire's entire question set as "absent from the upload" in one press.
    if parsed.questionnaireId and parsed.questionnaireId != questionnaire_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That workbook was downloaded from a different questionnaire "
                f"(its Details sheet says {parsed.questionnaireId}). Download this questionnaire "
                "again and edit that copy, or upload the file as a new questionnaire."
            ),
        )
    try:
        report = await apply_parsed_edit(
            questionnaire_id, parsed, user_id=current_user.id, title=title
        )
    except QuestionnaireEditError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc
    if file.filename and file.filename != record.sourceFilename:
        await db.questionnaire.update(
            where={"id": questionnaire_id}, data={"sourceFilename": file.filename}
        )
    # RETIRED QUESTIONS INCLUDED, unlike the create path, which has none. This is the one response
    # whose report can say "superseded 1, retired 1" — and a form that then omitted both of them
    # would leave a client unable to show the designer the questions it just told them about, or to
    # find the answers still hanging off them.
    form = await load_form(questionnaire_id, include_retired=True)
    return public_encode({"questionnaire": form, "report": report})


# --- List, read, update -------------------------------------------------------------------------


@router.get("")
async def list_questionnaires(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1),
    search: str | None = None,
    designWorkshopId: str | None = None,
    activeOnly: bool = True,
    mineOnly: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Questionnaires this designer may use, newest first.

    Scoped exactly as design workshops are: a non-admin sees their own, an explicit ``mineOnly``
    narrows an admin's view without changing anybody else's. ``designWorkshopId`` is what the
    workshop screen passes to show only the forms attached to it.
    """
    _require_designer(current_user)
    where: dict[str, Any] = {}
    if activeOnly:
        where["isActive"] = True
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
    if search:
        where["OR"] = [
            {"title": {"contains": search, "mode": "insensitive"}},
            {"description": {"contains": search, "mode": "insensitive"}},
        ]
    if mineOnly:
        # An explicit "mine" means MINE — the ones this designer uploaded — and must not quietly
        # include a colleague's form that happens to hang off a shared workshop.
        where["ownerId"] = current_user.id
    elif not is_admin(current_user):
        # Own forms, plus the forms attached to a design workshop this designer works on. AND-composed
        # because `where["OR"]` above already belongs to the search box.
        where.setdefault("AND", []).append(_visible_questionnaire_where(current_user))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total = await db.questionnaire.count(where=where)
    rows = await db.questionnaire.find_many(
        where=where,
        skip=skip,
        take=clean_size,
        order={"createdAt": "desc"},
        include={"owner": True, "designWorkshop": True},
    )
    summaries = [
        {
            "id": row.id,
            "title": row.title,
            "description": row.description,
            "ownerId": row.ownerId,
            "ownerName": getattr(row.owner, "name", None),
            "designWorkshopId": row.designWorkshopId,
            "designWorkshopTitle": getattr(row.designWorkshop, "title", None),
            "isActive": row.isActive,
            "version": row.version,
            "sourceFilename": row.sourceFilename,
            "createdAt": row.createdAt,
            "updatedAt": row.updatedAt,
        }
        for row in rows
    ]
    return public_encode(page_payload(summaries, total, clean_page, clean_size))


@router.get("/options")
async def questionnaire_options(
    designWorkshopId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """The attach-to-a-workshop dropdown: id + label, nothing else, no paging.

    Its own endpoint rather than a page-size-500 call to the list, because a dropdown that silently
    stops at page one is a designer who cannot find the questionnaire they uploaded this morning.
    """
    _require_designer(current_user)
    where: dict[str, Any] = {"isActive": True}
    if not is_admin(current_user):
        where["ownerId"] = current_user.id
    rows = await db.questionnaire.find_many(where=where, order={"title": "asc"}, take=500)
    return [
        {
            "id": row.id,
            "title": row.title,
            "version": row.version,
            "designWorkshopId": row.designWorkshopId,
            # So the dropdown can mark "already attached to another workshop" rather than letting a
            # designer reattach one mid-fieldwork and wonder where it went.
            "attachedElsewhere": bool(
                row.designWorkshopId and designWorkshopId and row.designWorkshopId != designWorkshopId
            ),
        }
        for row in rows
    ]


@router.get("/{questionnaire_id}")
async def read_questionnaire(
    questionnaire_id: str,
    includeRetired: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """The form, its sittings and its answers.

    ``includeRetired`` is what the EDITOR passes and the answer screen does not: a retired question
    has to stay visible where its recorded answers are read, and must never be offered for a new
    answer. ``question.hasAnswers`` on every question is what lets a client grey out the rewording
    box BEFORE the designer types into it.

    THE FORM IS OPEN TO ANY DESIGNER AND ITS SITTINGS ARE NOT. Reading the questions has to stay
    open or a colleague handed the form cannot fill it in — that is the rule the route table in
    frontend/lib/permissions.ts states and the reason ``_require_owner`` is not applied here. The
    ``entries`` array is a different thing wearing the same payload: each sitting carries the
    respondent's NAME, the interviewer's notes and every recorded answer. Any designer holding
    the id — from a shared link, a browser history entry, a report annexure — got all of it, while
    their own list view showed ``total: 0`` for the same questionnaire, so the app said it did
    not exist and the API handed it over.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    entitled = (
        record.ownerId == current_user.id
        or is_admin(current_user)
        # A co-designer on the workshop this form belongs to. See the helper for why the sittings
        # come with it rather than being withheld.
        or await _works_on_this_questionnaires_workshop(record, current_user)
    )
    mine_only = None if entitled else current_user.id
    form = await load_form(
        questionnaire_id, include_retired=includeRetired, only_entries_of=mine_only
    )
    if form is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return public_encode(form)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_questionnaire(
    payload: QuestionnaireCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Start a questionnaire by hand. The .xlsx upload is the other door into the same tables."""
    _require_designer(current_user)
    if payload.designWorkshopId:
        # Checked BEFORE the row is created, so a refused attachment leaves nothing behind. Creating
        # the questionnaire first and attaching second would hand back a 404 with an orphan
        # questionnaire already written, which reads to the designer as "it failed" and is not.
        await _require_attachable_workshop(payload.designWorkshopId, current_user)
    record = await db.questionnaire.create(
        data={
            "title": payload.title.strip(),
            "description": payload.description,
            "ownerId": current_user.id,
            "designWorkshopId": payload.designWorkshopId,
        }
    )
    codes: set[str] = set()
    for index, section in enumerate(payload.sections, start=1):
        code = (section.code or "").strip()
        if not code or code.lower() in codes:
            code = derive_section_code(section.title, codes)
        else:
            codes.add(code.lower())
        made = await db.questionnaireformsection.create(
            data={
                "questionnaireId": record.id,
                "code": code,
                "title": section.title.strip(),
                "sortOrder": section.sortOrder or index,
            }
        )
        for position, question in enumerate(section.questions, start=1):
            await db.questionnaireformquestion.create(
                data={
                    "sectionId": made.id,
                    "prompt": question.prompt.strip(),
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "sortOrder": question.sortOrder or position,
                }
            )
    return public_encode(await load_form(record.id))


@router.patch("/{questionnaire_id}")
async def update_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Rename, re-describe, attach to a workshop, or deactivate.

    ``isActive: false`` is what this API has INSTEAD of a delete — see the module docstring.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    # designWorkshopId is the one field here that is meaningfully NULLABLE — sending null detaches
    # the questionnaire from its workshop — so it is put back after clean_data drops it.
    if "designWorkshopId" in payload.model_fields_set:
        data["designWorkshopId"] = payload.designWorkshopId
        if payload.designWorkshopId:
            # `_require_owner` above answered a DIFFERENT question — who owns this QUESTIONNAIRE —
            # and owning the form has never said anything about the workshop it is being pointed at.
            # The `if` is the detach case passing through unchecked, on purpose; see the helper.
            await _require_attachable_workshop(payload.designWorkshopId, current_user)
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        await db.questionnaire.update(where={"id": questionnaire_id}, data=data)
    return public_encode(await load_form(questionnaire_id, include_retired=True))


# --- Sections and questions, one at a time -------------------------------------------------------


@router.post("/{questionnaire_id}/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    questionnaire_id: str,
    payload: CustomSectionCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    existing = await db.questionnaireformsection.find_many(where={"questionnaireId": questionnaire_id})
    codes = {s.code.strip().lower() for s in existing}
    code = (payload.code or "").strip()
    if not code or code.lower() in codes:
        code = derive_section_code(payload.title, codes)
    made = await db.questionnaireformsection.create(
        data={
            "questionnaireId": questionnaire_id,
            "code": code,
            "title": payload.title.strip(),
            "sortOrder": payload.sortOrder or (max((s.sortOrder for s in existing), default=0) + 1),
        }
    )
    for position, question in enumerate(payload.questions, start=1):
        await db.questionnaireformquestion.create(
            data={
                "sectionId": made.id,
                "prompt": question.prompt.strip(),
                "helpText": question.helpText,
                "isRequired": question.isRequired,
                "sortOrder": question.sortOrder or position,
            }
        )
    return public_encode(await load_form(questionnaire_id))


@router.patch("/{questionnaire_id}/sections/{section_id}")
async def update_section(
    questionnaire_id: str,
    section_id: str,
    payload: CustomSectionUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """A section's title and position may change even when its questions have been answered.

    A heading is not what an answer answers — that is the whole distinction the rule turns on. Only
    the QUESTION's wording is frozen by an answer.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    section = await require_record(db.questionnaireformsection, section_id)
    if section.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        await db.questionnaireformsection.update(where={"id": section_id}, data=data)
    return public_encode(await load_form(questionnaire_id, include_retired=True))


@router.post("/{questionnaire_id}/sections/{section_id}/questions", status_code=status.HTTP_201_CREATED)
async def create_question(
    questionnaire_id: str,
    section_id: str,
    payload: CustomQuestionCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    section = await require_record(db.questionnaireformsection, section_id)
    if section.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    siblings = await db.questionnaireformquestion.find_many(where={"sectionId": section_id})
    await db.questionnaireformquestion.create(
        data={
            "sectionId": section_id,
            "prompt": payload.prompt.strip(),
            "helpText": payload.helpText,
            "isRequired": payload.isRequired,
            "sortOrder": payload.sortOrder or (max((q.sortOrder for q in siblings), default=0) + 1),
        }
    )
    return public_encode(await load_form(questionnaire_id))


@router.patch("/{questionnaire_id}/questions/{question_id}")
async def update_question(
    questionnaire_id: str,
    question_id: str,
    payload: CustomQuestionUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Edit one question — under the same rule the re-upload path uses, via ``guard_question_edit``.

    Rewording a question that has answers does NOT fail and does NOT overwrite. It supersedes: the
    original wording keeps its answers, the new wording is added in the same place, and the response
    carries ``action: "superseded"`` with both ids so the editor can say what happened. Stating this
    once, in the service, is what keeps the single-question editor and the spreadsheet re-upload
    from drifting into two different answers to the same question.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    question = await _question_in(questionnaire_id, question_id)

    action = await guard_question_edit(question, new_prompt=payload.prompt, deleting=False)
    if action == "supersede":
        replacement = await supersede_question(
            question,
            prompt=payload.prompt.strip(),
            helpText=payload.helpText if "helpText" in payload.model_fields_set else question.helpText,
            isRequired=payload.isRequired if payload.isRequired is not None else question.isRequired,
            sortOrder=payload.sortOrder or question.sortOrder,
        )
        await bump_version(questionnaire_id)
        return public_encode(
            {
                "action": "superseded",
                "questionId": question.id,
                "replacementId": replacement.id,
                "detail": (
                    "This question already has answers recorded against it, so its original wording "
                    "and those answers were kept and your new wording was added as a new question."
                ),
                "questionnaire": await load_form(questionnaire_id, include_retired=True),
            }
        )

    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    if "helpText" in payload.model_fields_set:
        data["helpText"] = payload.helpText
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    section_id = data.pop("sectionId", None)
    if section_id:
        section = await require_record(db.questionnaireformsection, section_id)
        if section.questionnaireId != questionnaire_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        data["sectionId"] = section_id
    if data:
        await db.questionnaireformquestion.update(where={"id": question_id}, data=data)
    return public_encode(
        {
            "action": "updated",
            "questionId": question_id,
            "questionnaire": await load_form(questionnaire_id, include_retired=True),
        }
    )


@router.delete("/{questionnaire_id}/questions/{question_id}")
async def remove_question(
    questionnaire_id: str,
    question_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Remove a question — really deleted if nobody answered it, RETIRED if somebody did.

    Returns 200 with the action taken rather than 204, precisely because the two outcomes are
    different and the designer has to be told which one happened. A retired question stops being
    asked and keeps its answers; a client that assumed 204-means-gone would show a question the
    designer just deleted still sitting in the list, with no explanation.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    question = await _question_in(questionnaire_id, question_id)

    action = await guard_question_edit(question, new_prompt=None, deleting=True)
    if action == "retire":
        await db.questionnaireformquestion.update(
            where={"id": question_id},
            data={"isActive": False, "retiredAt": datetime.now(UTC)},
        )
        await bump_version(questionnaire_id)
        return public_encode(
            {
                "action": "retired",
                "questionId": question_id,
                "detail": (
                    "This question has answers recorded against it, so it was retired rather than "
                    "deleted. It is no longer asked, and its answers are still in the record."
                ),
                "questionnaire": await load_form(questionnaire_id, include_retired=True),
            }
        )
    # THE BLANK ANSWER ROWS GO FIRST, AND WITHOUT THEM THIS ENDPOINT 500s.
    #
    # `guard_question_edit` decides retire-vs-delete on whether any answer has actual TEXT — a row
    # saved as "" or " " is null-ish to a person and does not freeze a question nobody answered.
    # That judgement is right and stays. But the ROW still exists, and
    # `QuestionnaireFormAnswer.questionId` is `onDelete: Restrict`, so Postgres refuses the delete
    # and the designer gets a bare "Something went wrong on the server" with a
    # ForeignKeyViolationError in the log.
    #
    # It is the ordinary path, not an edge case: the app writes exactly these rows when somebody
    # opens a sitting, tabs through it and saves — which is what a designer does the first time they
    # look at their own form. Then they tidy up a question they mistyped, and the app breaks.
    #
    # Deleting the blank rows loses no fieldwork: they assert nothing, which is the same reason the
    # guard does not count them. Anything with text took the retire branch above and never reaches
    # here, so this cannot remove a recorded answer.
    await db.questionnaireformanswer.delete_many(where={"questionId": question_id})
    await db.questionnaireformquestion.delete(where={"id": question_id})
    return public_encode(
        {
            "action": "deleted",
            "questionId": question_id,
            "questionnaire": await load_form(questionnaire_id, include_retired=True),
        }
    )


async def _question_in(questionnaire_id: str, question_id: str) -> Any:
    """A question, checked to belong to THIS questionnaire.

    Without the check, a valid question id from somebody else's form would be edited through this
    questionnaire's ownership guard — the id is in the path and the ownership was checked on the
    questionnaire, not on the question.
    """
    question = await require_record(db.questionnaireformquestion, question_id)
    section = await require_record(db.questionnaireformsection, question.sectionId)
    if section.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return question


# --- Sittings and answers -------------------------------------------------------------------------


@router.post("/{questionnaire_id}/entries", status_code=status.HTTP_201_CREATED)
async def create_entry(
    questionnaire_id: str,
    payload: QuestionnaireEntryCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Start a sitting: one filled-in copy of this questionnaire.

    Not owner-gated, and that stays. Recording answers is the whole point of attaching a
    questionnaire to a workshop, and a form only its author may answer is a form nobody uses.

    WHAT IT IS GATED ON IS THE WORKSHOP, once the form is attached to one — see
    ``_require_recordable_questionnaire``. "Not owner-gated" had been read as "not gated", so any
    designer holding the id could push a sitting, respondent's name and all, into another team's
    report annexure. The two sentences are not in tension: the owner still does not have to be the
    one answering, the workshop's team does.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    await _require_recordable_questionnaire(record, current_user)
    if not record.isActive:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire has been deactivated, so no new answers can be recorded against it.",
        )
    entry = await db.questionnaireformentry.create(
        data={
            "questionnaireId": questionnaire_id,
            "title": (payload.title or payload.respondentName or "Answers").strip(),
            "respondentName": payload.respondentName,
            "notes": payload.notes,
            "source": APP_ENTRY_SOURCE,
            "createdById": current_user.id,
        }
    )
    return public_encode(entry)


@router.patch("/{questionnaire_id}/entries/{entry_id}")
async def update_entry(
    questionnaire_id: str,
    entry_id: str,
    payload: QuestionnaireEntryUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Rename a sitting, or correct the respondent's name and the notes taken during it.

    ``rewriting=True`` because these three fields are the sitting's IDENTITY. Without the check
    it carries, any designer could PATCH any sitting of any questionnaire — the route needs only
    the entry id, which ``GET /questionnaires/{id}`` hands out — and overwrite ``respondentName``,
    which is the name of the person interviewed. That is one designer relabelling whose testimony
    a recorded answer belongs to, on a research instrument whose report goes to a ministry, and
    nothing in the document would show it had happened.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    entry = await _entry_in(
        questionnaire_id, entry_id, user=current_user, questionnaire=record, rewriting=True
    )
    data = clean_data(payload.model_dump(exclude_unset=True), title_case=False)
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        entry = await db.questionnaireformentry.update(where={"id": entry.id}, data=data)
    return public_encode(entry)


@router.put("/{questionnaire_id}/entries/{entry_id}/answers")
async def record_answers(
    questionnaire_id: str,
    entry_id: str,
    payload: CustomAnswerBatch,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Record answers against one sitting. Idempotent — re-sending an unchanged section writes nothing.

    PUT rather than POST because sending the same section twice must not produce two sets of
    answers; ``@@unique([entryId, questionId])`` makes that true at the database as well.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    # The workshop gate, before a single answer row is touched. ``rewriting=False`` below decides
    # who may write to a SITTING; this decides who may write to this QUESTIONNAIRE at all, and the
    # two are different questions — the permissive answer to the first was being read as an answer
    # to the second, which is how a stranger's answers reached another workshop's annexure.
    await _require_recordable_questionnaire(record, current_user)
    # ``rewriting=False``: recording answers against somebody else's sitting is DELIBERATE — two
    # people filling in different sections of one interview is the ordinary case, and a sitting
    # only its starter may answer is a sitting nobody finishes. The authorship rule that belongs
    # here is per ANSWER and lives in ``save_answers``: an answer that already has text may only
    # be changed by whoever recorded it, or an admin.
    entry = await _entry_in(
        questionnaire_id, entry_id, user=current_user, questionnaire=record, rewriting=False
    )
    try:
        result = await save_answers(
            entry, payload.answers, user_id=current_user.id, is_admin=is_admin(current_user)
        )
    except QuestionnaireEditError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc
    answers = await db.questionnaireformanswer.find_many(where={"entryId": entry.id})
    return public_encode({"saved": result, "answers": answers})


async def _entry_in(
    questionnaire_id: str,
    entry_id: str,
    *,
    user: Any,
    questionnaire: Any,
    rewriting: bool,
) -> Any:
    """The sitting, if it belongs to this questionnaire and this caller may do that to it.

    ``rewriting`` HAS NO DEFAULT, and that is the point of the signature. Every entry-scoped
    route has to state which of the two it is, because the permissive answer is the one that
    shipped: ``update_entry`` checked that the caller was A designer and that the sitting was in
    the named questionnaire, and nothing else — so Designer B could rewrite Designer A's sitting,
    including the respondent's name. A future route that forgets to decide fails here rather than
    inheriting the wrong half.

    Rewriting a sitting belongs to the person who ran it, the designer who owns the form, or an
    admin. The questionnaire owner is included because the form is theirs and a sitting recorded
    by a colleague on their instrument is still their fieldwork to correct.
    """
    entry = await require_record(db.questionnaireformentry, entry_id)
    if entry.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if rewriting and not _may_rewrite_entry(entry, questionnaire, user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only the designer who recorded this sitting, the owner of the questionnaire, "
                "or an admin can change whose answers these are."
            ),
        )
    return entry


def _may_rewrite_entry(entry: Any, questionnaire: Any, user: Any) -> bool:
    return (
        entry.createdById == user.id
        or getattr(questionnaire, "ownerId", None) == user.id
        or is_admin(user)
    )
