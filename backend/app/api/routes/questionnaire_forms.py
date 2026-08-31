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
    GET  /questionnaires/{id}/question-set.xlsx   download the QUESTIONS ALONE, to send to somebody
    POST /questionnaires/{id}/upload          re-upload an edited copy  -> an EDIT, under the rule
    POST /questionnaires/{id}/reuse           copy it for ANOTHER workshop, questions only
    PATCH /questionnaires/{id}                rename, attach to a workshop, deactivate
    POST /questionnaires/{id}/entries         start a sitting
    PUT  /questionnaires/{id}/entries/{eid}/answers   record answers

THERE ARE TWO DOWNLOADS AND THEY HAVE TWO DIFFERENT GATES, WHICH IS THE POINT RATHER THAN AN
INCONSISTENCY. ``/xlsx`` is a LOSSLESS copy of the questionnaire — every sitting, every respondent's
name, every answer — so it is fieldwork and it is gated like fieldwork. ``/question-set.xlsx`` is the
INSTRUMENT and nothing else, so it is gated exactly as READING the form is: any designer. Sharing a
questionnaire with a colleague was impossible before the second one existed, and the only workaround
was to widen the first gate, which would have moved a leak rather than closing it.

REUSE IS A COPY, AND THAT IS THE ANSWER TO "ONE QUESTIONNAIRE, SEVERAL WORKSHOPS".
``Questionnaire.designWorkshopId`` is a single nullable column, so a questionnaire is at exactly one
workshop or at none. ``POST /questionnaires/{id}/reuse`` does not widen that — it writes a SECOND
questionnaire carrying the same questions and no fieldwork at all. Making the column a join table
instead would have put workshop A's named respondents in workshop B's ministry annexure, because a
SITTING has no workshop and ``report_items`` selects on ``designWorkshopId`` alone; the full argument
is in ``reuse_questionnaire``. The cost of the copy is divergence between the two instruments, and it
is the cost the feature accepts on purpose.

THERE IS NO WAY TO DELETE A QUESTIONNAIRE HERE, and that is the point rather than an omission. A
questionnaire with answers against it is somebody's fieldwork; ``PATCH {isActive: false}`` takes it
out of every list and every dropdown, and the answers stay. The one DELETE in this file removes a
single QUESTION, and even that only really deletes when nobody has answered it — otherwise it
retires. The database agrees with both: see the ``ON DELETE RESTRICT`` on
``QuestionnaireFormAnswer.questionId``.
"""

from datetime import UTC, datetime, timedelta
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
    QuestionnaireReuse,
    QuestionnaireUpdate,
)
from app.services.concurrency import gather_reads
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
    export_question_set_payload,
    guard_question_edit,
    load_form,
    reuse_questionnaire,
    save_answers,
    supersede_question,
)
from app.services.questionnaire_kinds import coerce_kind, label_for
from app.services.questionnaire_xlsx import (
    PRO_FORMA_FILENAME,
    XLSX_MIME,
    QuestionnaireXlsxError,
    build_pro_forma,
    build_question_set_workbook,
    build_questionnaire_workbook,
    derive_section_code,
    download_filename,
    parse_questionnaire_workbook,
    question_set_filename,
)
from app.services.records import (
    clean_data,
    contains,
    public_encode,
    require_record,
    with_id_tiebreak,
)

router = APIRouter(prefix="/questionnaires", tags=["questionnaires"])

# A questionnaire is a page of typing, not a media file. The ceiling is generous enough for a
# thousand-question instrument with answers in it and low enough that this endpoint cannot be used
# to push a hundred megabytes through a synchronous parser.
MAX_UPLOAD_BYTES = 8 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


# EACH PATCH'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null`` through
# for, so emptying a box on the questionnaire editor actually empties the column instead of answering
# 200 and keeping the old text. Four models, four different lists, for the reason ``clean_data``'s
# ``clearable`` docstring gives: the set is per MODEL and a global one would trade a silent no-op on
# one table for a constraint violation on another.
#
# MODULE CONSTANTS RATHER THAN TUPLES WRITTEN INLINE AT THE CALL, matching the four record routes.
# That is what lets ``tests/test_record_patch_clearing`` derive each expected list from
# ``schema.prisma`` and compare it with what the ROUTE declares. A test that retyped these names
# would agree with itself and would never notice a newly nullable column nobody wired up — which is
# exactly how ``description`` here, and ``phone`` on the artisan route, became a 200 that did nothing.
#
# Every one of these is only sound because its PATCH dumps with ``exclude_unset=True``; see the note
# at each call site.

#: ``Questionnaire``. ``sourceFilename`` is nullable too but ``QuestionnaireUpdate`` does not accept
#: it — the uploader names the file, not an editor — and ``title``/``isActive`` are NOT NULL.
#:
#: ``designWorkshopId`` USED TO BE NAMED HERE AND DELIBERATELY IS NOT ANY MORE. When the six record
#: models gained a ``designWorkshopId`` of their own (2026-08-28), the name went into the GLOBAL
#: :data:`app.services.records.CLEARABLE_KEYS` so that every one of those routes clears it by the
#: same rule. ``clearable`` ADDS to that set and can never subtract from it, so repeating the name
#: here changed nothing about behaviour — and ``test_record_patch_clearing`` refuses the repetition
#: by name, because a route tuple that lists a column the global set already owns is dead weight the
#: next reader would take for the mechanism. Detaching a questionnaire from its workshop still
#: works, and ``test_a_questionnaire_patch_detaches_the_workshop_it_was_filed_under`` still drives
#: it; the null now arrives through the global door instead of this one.
#:
#: ``kind`` IS HERE BECAUSE "NOT STATED" IS A REAL STATE AND HAS TO BE REACHABLE. The picker on both
#: clients carries a blank row, and choosing it sends ``kind: null``; without the name in this tuple
#: ``clean_data`` would drop that null and the PATCH would answer 200 having changed nothing, leaving
#: a designer who had un-set a kind looking at the kind they thought they had removed — and leaving
#: the report still filing the form under a stage the designer no longer claims it belongs to.
#: ``test_record_patch_clearing`` derives this list from ``schema.prisma`` and would have failed on
#: the omission, which is the point of deriving it.
_QUESTIONNAIRE_CLEARABLE_COLUMNS = ("description", "kind")

#: ``QuestionnaireFormQuestion``. ``retiredAt`` and ``supersededById`` are nullable as well, but they
#: belong to the retirement machinery — written by the supersede branch in ``update_question``, never
#: by an editor — and ``CustomQuestionUpdate`` cannot reach either.
_QUESTION_CLEARABLE_COLUMNS = ("helpText",)

#: ``QuestionnaireFormEntry``, and here the PII case is the whole point: ``respondentName`` is the
#: person interviewed and ``notes`` is what was written down about them during the sitting. ``title``
#: is NOT NULL.
_ENTRY_CLEARABLE_COLUMNS = ("respondentName", "notes")

#: ``QuestionnaireFormSection`` — EMPTY, and empty on purpose rather than by omission. The model has
#: no nullable column at all, so there is nothing an explicit null could legitimately clear. It is
#: still declared, and still passed, so that the completeness test reads a route's answer for this
#: model instead of a silence it would have to interpret.
_SECTION_CLEARABLE_COLUMNS: tuple[str, ...] = ()


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
    """The designer set in front of every route in this file. NOT a rank floor — read the message.

    ── WHY THE SENTENCE BELOW NAMES ROLES INSTEAD OF A THRESHOLD ────────────────────────────────

    It used to read *"Building a questionnaire requires Designer access or above."* and that was
    false in the one case where anybody reads a 403: ``can_run_design_workshops`` is the SET
    ``{DESIGNER, ADMIN, MASTER_ADMIN}`` and not a floor over ``ROLE_RANK``, so a PROFESSOR — rank
    40, ABOVE Designer's 35 — is refused by it and was then told they lacked a rank they exceed.
    Measured on this build, 2026-08-30: ``POST /api/questionnaires`` as a PROFESSOR answered
    ``403 {"detail":"Building a questionnaire requires Designer access or above."}``. A professor
    reading that has no move available: they cannot be promoted to a tier they already outrank, and
    nothing on any screen says the gate is a set.

    So the refusal now NAMES THE THREE ROLES and NAMES WHO TO ASK. Naming the roles is what makes
    the non-monotonic rule legible — a reader can see their own role is simply not on the list,
    rather than concluding the app is broken — and naming the administrator is what turns the
    sentence into something actionable, because an admin is exactly who can widen it (by role, or
    by putting the account on a workshop with a ``DesignWorkshopViewer`` grant).

    IT DOES NOT APOLOGISE AND IT DOES NOT EXPLAIN THE LADDER. UI copy in this repository is terse by
    house rule; the whole argument for why a professor is outside the set lives in
    ``deps.can_run_design_workshops`` and is not repeated at the person being refused.
    """
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Questionnaires are for Designer, Admin and Master Admin accounts. This is a set of "
                "roles rather than a seniority level, so other roles are outside it whatever their "
                "rank. Ask an administrator to change your role or to add you to the design "
                "workshop this questionnaire belongs to."
            ),
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
            # ── THE FOURTH CLAUSE: THE PUBLISHED DEFAULT, added 2026-08-28 ──────────────────────
            #
            # An administrator's standard instrument, marked ``isShared``. The other three clauses
            # are all "yours, or your workshop's", so a form that belongs to EVERY workshop matched
            # none of them and was invisible to every designer while sitting in the table — which is
            # what the owner reported. See ``Questionnaire.isShared`` in schema.prisma for why this
            # is a column somebody SET rather than a convention inferred from the owner's role or
            # from the absence of a workshop.
            #
            # ``isActive`` IS PART OF THE CLAUSE AND NOT LEFT TO THE CALLER. Two of this function's
            # three callers pass ``activeOnly=True`` and one does not, and a retired instrument must
            # stop appearing in everybody's picker the moment it is retired — a shared form is the
            # one row where "still listed after retirement" is wrong for every designer at once
            # rather than for its author. The pair is indexed together for the same reason.
            #
            # IT ADMITS THE FORM AND NOTHING ELSE. ``read_questionnaire`` still narrows ``entries``
            # to the caller's own sittings unless they own the form, are an admin, or work on its
            # workshop; that check reads ``ownerId`` / ``_works_on_this_questionnaires_workshop``
            # and does NOT consult this flag, deliberately. Adding it there would turn one published
            # instrument into a window onto every respondent's name and answers in the repository.
            {"isShared": True, "isActive": True},
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


def _kind_or_422(value: str | None) -> str | None:
    """``coerce_kind`` with its ``ValueError`` turned into the 422 the rest of this API answers.

    The JSON routes get this for free — ``QuestionnaireCreate``/``QuestionnaireUpdate`` carry a
    pydantic validator, and pydantic renders a raised ``ValueError`` as a 422 naming the field. The
    UPLOAD route takes multipart ``Form`` fields, which have no model behind them, so without this
    an unknown token would travel straight into the column and the report would later try to file the
    questionnaire under a stage that does not exist. One helper rather than a try/except at the call
    site, so a second multipart door added later cannot forget the check.
    """
    try:
        return coerce_kind(value)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc


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
                "workshop, or an admin. If you want the QUESTIONS — to run this instrument "
                "yourself, or to send it on — download the question set instead: it carries the "
                "questions and no answers, and any designer may take it. Record answers from the "
                "questionnaire page."
            ),
        )
    payload = await export_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    workbook = build_questionnaire_workbook(**payload)
    return _xlsx_response(workbook, download_filename(payload["title"]))


@router.get("/{questionnaire_id}/question-set.xlsx")
async def download_question_set(
    questionnaire_id: str, current_user: Any = Depends(get_current_user)
) -> Response:
    """This questionnaire's QUESTIONS ALONE — the artefact a designer sends to another designer.

    ================================================================================================
    WHY THIS IS A SECOND ROUTE AND NOT A QUERY PARAMETER ON ``/xlsx``
    ================================================================================================

    Because the two files have different gates, and a parameter that changes a gate is a gate one
    typo away from opening. ``?questionsOnly=true`` would have put the whole decision inside a
    boolean that defaults, and the wrong default on this particular boolean hands a stranger every
    respondent's name in the questionnaire. Two paths, two permission blocks, no shared default.

    ================================================================================================
    WHY ANY DESIGNER MAY TAKE IT, WHICH IS A WIDER GATE THAN ``/xlsx`` AND DELIBERATELY SO
    ================================================================================================

    ``read_questionnaire`` above already hands the QUESTIONS of any questionnaire to any designer —
    that is the stated rule ("the form is open to any designer and its sittings are not"), and it is
    what lets a designer hand a colleague a form to fill in. This file is exactly that openly
    readable half, written into a spreadsheet. Refusing the file while serving the same content as
    JSON would protect nothing and would be routed around by copy-and-paste within the hour.

    The SITTINGS are what ``/xlsx`` is gated on, and this endpoint cannot reach one:
    ``export_question_set_payload`` is built on ``load_question_set``, which never issues the entry
    or answer queries at all. So the difference between the two endpoints is not a filter that could
    be forgotten — it is two different reads of the database.

    ``_require_designer`` rather than ``get_current_user`` alone, because a questionnaire is a
    designer's instrument and a researcher who is not running design workshops has no use for one;
    that is the same floor every other route in this file stands on.
    """
    await _require_questionnaire(questionnaire_id, current_user)
    payload = await export_question_set_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    workbook = build_question_set_workbook(
        title=payload["title"],
        description=payload["description"],
        sections=payload["sections"],
        source_title=payload["source_title"],
        # WHO exported it and WHEN, on the Details sheet. Not decoration: the file's whole purpose is
        # to travel between people, and a spreadsheet in a shared drive with no idea where it came
        # from is one a designer re-uploads twice or attributes to the wrong colleague. The NAME is
        # the exporter's own, which they are entitled to disclose about themselves; nothing here
        # names anybody who was interviewed.
        shared_by=getattr(current_user, "name", None) or None,
        exported_on=datetime.now(UTC).date().isoformat(),
    )
    return _xlsx_response(workbook, question_set_filename(payload["title"]))


# --- Upload -------------------------------------------------------------------------------------


@router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_questionnaire(
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    designWorkshopId: str | None = Form(default=None),
    kind: str | None = Form(default=None),
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
        # VALIDATED HERE AND NOT BY A PYDANTIC MODEL, because this route takes multipart form fields
        # rather than a JSON body and so has no model to hang a validator on. ``coerce_kind`` raises
        # ``ValueError``; the wrapper turns it into the 422 a bad enum gets everywhere else in this
        # API, rather than letting an unknown token reach the column and be filed under a stage that
        # does not exist. An empty field — the picker untouched — is "not stated", not an error.
        kind=_kind_or_422(kind),
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
        # ``records.contains``, not a hand-rolled filter: it strips the control bytes Postgres cannot
        # store (a pasted NUL was a bare 500 from this box) and escapes the LIKE metacharacters, so a
        # title typed with an underscore matches that title rather than every questionnaire.
        where["OR"] = [
            {"title": contains(search)},
            {"description": contains(search)},
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
    # Count and page together — the shape ``records.count_and_page`` exists for, spelled out here
    # because this read needs ``include=`` and that helper takes ``relations``. Neither reads the
    # other, so awaiting them in turn spent a cross-region round trip on the total alone.
    total, rows = await gather_reads(
        db.questionnaire.count(where=where),
        db.questionnaire.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            # ── THE PUBLISHED DEFAULT SORTS FIRST, AND THAT IS A BUG FIX RATHER THAN A PREFERENCE ──
            #
            # This was ``{"createdAt": "desc"}`` alone, and the standard instrument is by definition
            # the OLDEST row a designer can see: an administrator seeds it once, and every form the
            # designer builds afterwards is newer. So the one row published to everybody sorted LAST
            # — below every draft they had made themselves — and a designer with more than a page of
            # questionnaires never reached it. The `isShared` badge this list already draws was
            # therefore a badge on a page nobody opens, which is the same defect Android's own list
            # comment describes for a grant-holder's row ("the one row a grant exists to reveal is
            # the one row a single page cannot reach").
            #
            # A SORT AND NOT A FILTER, deliberately. A "standard forms" filter would be a control a
            # designer has to know to press before the feature exists for them; this puts the form in
            # front of them without asking them to look for it, and costs nothing when there is no
            # shared form at all — every row has ``isShared = false`` and the order is what it was.
            #
            # STILL TOTAL, so paging is still stable: ``with_id_tiebreak`` appends ``id`` to whatever
            # it is given, and a list is what it takes when the ordering has more than one clause.
            order=with_id_tiebreak([{"isShared": "desc"}, {"createdAt": "desc"}]),
            include={"owner": True, "designWorkshop": True},
        ),
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
            # See the same key on `/options`: a designer's list can now contain a form they did not
            # upload, and a row that cannot say why reads as somebody else's work leaking in.
            "isShared": row.isShared,
            # THE KIND, AND ITS LABEL BESIDE IT. The token is what a client filters and PATCHes on;
            # the label is what it prints. Both are sent because the alternative is each client
            # carrying its own translation of a server vocabulary, which is how the two of them come
            # to word one value differently — and the owner asked for identical wording. The clients
            # DO still carry the list (they have to draw a picker before any row exists to label),
            # and `tests/test_questionnaire_kinds.py` holds those copies to the server's.
            "kind": row.kind,
            "kindLabel": label_for(row.kind),
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
        # OWN FORMS **OR THE PUBLISHED DEFAULT**, and this used to be `ownerId` alone.
        #
        # That single equality is the other half of the defect the fourth clause of
        # `_visible_questionnaire_where` closes: even once a designer could SEE the shared
        # instrument in their list, the dropdown that attaches a questionnaire to a workshop was
        # still built from `ownerId == me`, so the one control whose whole job is offering could not
        # offer it. The owner's words are "designers can directly select and utilize it" — the list
        # is the seeing half and this is the selecting half, and shipping one without the other
        # would have looked like the feature working right up to the moment it was used.
        #
        # Written out here rather than calling `_visible_questionnaire_where`: this dropdown is
        # deliberately NARROWER than the list. It offers what a designer may ATTACH, and a
        # colleague's form that happens to hang off a shared workshop is not that — reattaching it
        # would take it out of the workshop its author put it in.
        where["OR"] = [{"ownerId": current_user.id}, {"isShared": True}]
    rows = await db.questionnaire.find_many(where=where, order={"title": "asc"}, take=500)
    return [
        {
            "id": row.id,
            "title": row.title,
            "version": row.version,
            "designWorkshopId": row.designWorkshopId,
            # So a client can label the row "Standard form" rather than leaving a designer to wonder
            # why a questionnaire they never uploaded is in their list. Also what stops a shared form
            # being drawn as though it were the designer's own to edit — the PATCH still refuses
            # anybody but its owner or an admin, and a control that offers an edit it cannot perform
            # is worse than one that says whose form this is.
            "isShared": row.isShared,
            # So the attach dropdown on the design-workshop screen can say WHICH of the several forms
            # now attachable to one workshop this row is. That screen is the whole reason the kind
            # exists: a designer running a workshop interview and a market survey at one cluster sees
            # two rows here, and before this key the only thing telling them apart was whatever the
            # designer happened to type into the title.
            "kind": row.kind,
            "kindLabel": label_for(row.kind),
            # So the dropdown can mark "already attached to another workshop" rather than letting a
            # designer reattach one mid-fieldwork and wonder where it went.
            "attachedElsewhere": bool(
                row.designWorkshopId
                and designWorkshopId
                and row.designWorkshopId != designWorkshopId
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


@router.post("/{questionnaire_id}/reuse", status_code=status.HTTP_201_CREATED)
async def reuse_questionnaire_route(
    questionnaire_id: str,
    payload: QuestionnaireReuse,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Use this questionnaire again, as a template, at another design workshop.

    The owner's request in their own words: questionnaires "would usually be scoped to the workshops,
    but the designers would have the permission to use the same questionnaire later on for a
    different workshop as well in case they want to reuse the same template."

    ================================================================================================
    IT COPIES. THE COPY CARRIES QUESTIONS AND NO FIELDWORK.
    ================================================================================================

    A new ``Questionnaire`` row, a new section tree, a new question tree — and ZERO
    ``QuestionnaireFormEntry`` and ZERO ``QuestionnaireFormAnswer``. ``reuse_questionnaire`` reads the
    source through ``load_question_set``, which never issues the entry or answer queries at all, so
    the rows it feeds cannot contain an answer; see that function for the full argument, including
    why one questionnaire pointing at MANY workshops was rejected (a sitting has no workshop, and
    ``report_items`` selects on ``designWorkshopId`` alone, so it would print workshop A's named
    respondents in workshop B's ministry annexure).

    ================================================================================================
    A PATH ROUTE ON THE SOURCE, NOT A ``copyOf`` PARAMETER ON ``POST ""``
    ================================================================================================

    For the reason ``/question-set.xlsx`` is a second route rather than ``?questionsOnly=true`` on
    ``/xlsx``: a parameter that changes what an endpoint DOES is one typo away from doing the other
    thing. ``POST ""`` creates an empty questionnaire and this one clones an existing designer's
    instrument into the caller's ownership; sharing a body between the two would put that difference
    inside an optional field's default.

    ================================================================================================
    THE GATES, IN ORDER, AND WHY EACH IS THE ONE IT IS
    ================================================================================================

    1. ``_require_questionnaire`` — Designer or above, and the source row exists.
    2. **NOT** ``_require_owner``. This is the one mutating route in this module that does not demand
       ownership of the questionnaire it names, and that is deliberate: the INSTRUMENT already leaves
       this system for any designer through ``GET /{id}/question-set.xlsx``, whose docstring states
       the rule ("this file is exactly the openly readable half"). Refusing here would refuse in JSON
       precisely what the .xlsx door hands over — and be routed around by downloading that file and
       uploading it, which produces the same row with NO provenance recorded at all.
    3. ``_require_attachable_workshop`` when a target workshop is named — the SAME helper the three
       existing attachment routes call, because this is a fourth attachment route and that helper's
       own docstring says so ("If a fourth attachment route is ever added, it calls this"). Workshop
       creator, admin, or viewer grant; 404 for a workshop the caller cannot see, 409 for a
       soft-deleted one. Asked BEFORE anything is written, so a refusal leaves no orphan row.
    4. **No workshop check at all when no target is named.** An unattached copy is nobody's business
       but its owner's: ``_visible_questionnaire_where`` shows it under ``ownerId = me`` and nothing
       else, and ``report_items`` cannot reach a row with a NULL ``designWorkshopId``.

    A DEACTIVATED SOURCE IS STILL REUSABLE, and that is not an oversight. ``isActive: false`` is this
    API's stand-in for a delete — the form is out of use, its recorded answers preserved — and a
    retired instrument is exactly the thing a designer wants to lift for a new round. Refusing would
    force them to reactivate it first, which puts it back in every list and every dropdown for
    everyone, to make a copy.

    THE RESPONSE IS THE UPLOAD RESPONSE'S SHAPE — ``{"questionnaire": ..., "report": {...}}`` — so
    ``QFormUploadReport`` types it on the client and the existing ``UploadReport`` panel renders it.
    ``report.provenance`` carries ``action: "reused"``, the source id, ``answersSkipped: 0`` and a
    sentence written to be shown VERBATIM.

    It carries ONE KEY MORE than the upload response: ``sourceQuestionnaireId``, at the top level.
    The upload path has no single source to name — a workbook is a file, and the id it claims to have
    come from is untrusted text inside it — whereas this path copied a row it had already read and
    authorised. ``QFormReuseResult`` is therefore ``QFormUploadResult`` plus that key rather than an
    alias of it, so a client cannot read the id off an upload where it does not exist.
    """
    record = await _require_questionnaire(questionnaire_id, current_user)
    if payload.designWorkshopId:
        await _require_attachable_workshop(payload.designWorkshopId, current_user)

    # THE TRI-STATE ON ``description``, unpacked here rather than inside the service. ``exclude_unset``
    # is what tells "carry the source's description across" (key absent) apart from "start it empty"
    # (key sent as null), and the service reads an EMPTY STRING as the second of those — the same
    # convention ``renameQuestionnaire`` on the detail page already relies on, where a null would be
    # dropped by ``clean_data`` and an empty string genuinely clears the column.
    sent = payload.model_dump(exclude_unset=True)
    description: str | None = None
    if "description" in sent:
        description = sent["description"] if sent["description"] is not None else ""

    made = await reuse_questionnaire(
        questionnaire_id,
        owner_id=current_user.id,
        design_workshop_id=payload.designWorkshopId,
        title=payload.title,
        description=description,
        # The same unset/null/value tri-state ``description`` gets two lines up, and read the same
        # way: a key the client did not send leaves the service on its inherit sentinel, so the copy
        # keeps the source's kind. Spelled with ``model_fields_set`` rather than a truthiness test
        # because an explicit ``null`` — "copy it, but I have not decided what this one is" — is a
        # different instruction from silence and must reach the service as one.
        **(
            {"kind": payload.kind}
            if "kind" in payload.model_fields_set
            else {}
        ),
    )
    if made is None:
        # ``_require_questionnaire`` already read the row, so this is the source disappearing between
        # the two reads rather than a bad id. Still 404 rather than a 500 out of a None unpack.
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    new_id, report = made
    # ``include_retired`` is left at its default because a copy HAS no retired question — nothing was
    # copied that could be retired. Passing True would be a claim about the payload that is not
    # false, only meaningless, and the next reader would go looking for the retirement it implies.
    form = await load_form(new_id)
    return public_encode(
        {"questionnaire": form, "report": report, "sourceQuestionnaireId": record.id}
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_questionnaire(
    payload: QuestionnaireCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Start a questionnaire by hand. The .xlsx upload is the other door into the same tables.

    ── ALL OF IT OR NONE OF IT (D2) ──────────────────────────────────────────

    This wrote the ``Questionnaire`` row, then each section, then each question, on SEPARATE awaits
    with no transaction around them. A failure part way — a dropped connection, a statement timeout
    against the cross-region database ``services/records.py`` describes, a constraint violation on
    one section code — left the row and everything written before the failure COMMITTED while the
    client got a 500. The designer sees "it failed", presses the button again, and now owns two
    questionnaires: one truncated, one whole, with nothing on any screen able to say which is which.
    A half-written instrument is worse than none, because it is indistinguishable from a complete
    one — the identical argument ``reuse_questionnaire`` makes at length, and this is the same tree
    of tables written by a third door.

    THE PATTERN IS ``reuse_questionnaire``'s, DELIBERATELY, DOWN TO THE BOUNDS. One ``db.tx()``,
    ``create_many`` for the sections, one read-back keyed on ``@@unique([questionnaireId, code])``
    for their ids, and ``create_many`` for every question — four statements whatever the size of the
    instrument, instead of one per row. Batching is not a micro-optimisation here: it is what keeps
    the transaction short enough to be an honest transaction rather than a lock held open across a
    thousand sequential round trips to a database in another region.

    WHY THE WORKSHOP CHECK STAYS OUTSIDE THE TRANSACTION. It is a read that decides whether to write
    at all, and it already refuses BEFORE anything exists — the comment below has said so since the
    attach hole was closed. Pulling it inside would buy nothing and would hold the transaction open
    across an extra round trip.

    THE WEB CLIENT SENDS NO SECTIONS AT ALL (``QFormCreateBody`` is ``{title, description?,
    designWorkshopId?}``), so on that path the loop never ran and this defect could not be triggered
    from the browser — measured 2026-08-30 while reproducing the reported create failure. It is
    fixed anyway: the API is public, the Android create body is the same shape only by convention,
    and "unreachable from today's client" is the state every reachable defect was in the day before
    somebody added a field.
    """
    _require_designer(current_user)
    if payload.designWorkshopId:
        # Checked BEFORE the row is created, so a refused attachment leaves nothing behind. Creating
        # the questionnaire first and attaching second would hand back a 404 with an orphan
        # questionnaire already written, which reads to the designer as "it failed" and is not.
        await _require_attachable_workshop(payload.designWorkshopId, current_user)

    # The codes are derived BEFORE the transaction opens, because deriving one is pure string work
    # over a set this loop owns — ``derive_section_code`` mutates ``codes`` itself, which is what
    # makes two sections with the same title land on ``X`` and ``X_2`` rather than colliding on the
    # ``@@unique([questionnaireId, code])`` the read-back below depends on.
    codes: set[str] = set()
    section_data: list[dict[str, Any]] = []
    questions_by_code: dict[str, list[Any]] = {}
    for index, section in enumerate(payload.sections, start=1):
        code = (section.code or "").strip()
        if not code or code.lower() in codes:
            code = derive_section_code(section.title, codes)
        else:
            codes.add(code.lower())
        section_data.append(
            {
                "code": code,
                "title": section.title.strip(),
                "sortOrder": section.sortOrder or index,
            }
        )
        questions_by_code[code] = list(section.questions)

    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
        record = await tx.questionnaire.create(
            data={
                "title": payload.title.strip(),
                "description": payload.description,
                "ownerId": current_user.id,
                "designWorkshopId": payload.designWorkshopId,
                # Validated into a token (or None) by ``QuestionnaireCreate``; see
                # ``app/services/questionnaire_kinds.py`` for what it decides in the report.
                "kind": payload.kind,
            }
        )
        if section_data:
            await tx.questionnaireformsection.create_many(
                data=[row | {"questionnaireId": record.id} for row in section_data]
            )
            written = await tx.questionnaireformsection.find_many(
                where={"questionnaireId": record.id}
            )
            id_by_code = {row.code: row.id for row in written}
            question_data = [
                {
                    "sectionId": id_by_code[code],
                    "prompt": question.prompt.strip(),
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "sortOrder": question.sortOrder or position,
                }
                for code, questions in questions_by_code.items()
                for position, question in enumerate(questions, start=1)
            ]
            if question_data:
                await tx.questionnaireformquestion.create_many(data=question_data)
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
    # ``Questionnaire``'s own nullable scalars; the list and the reasoning for what is left out of it
    # are at ``_QUESTIONNAIRE_CLEARABLE_COLUMNS`` above. ``designWorkshopId`` used to be the only one
    # named here, and it was named by hand — put back into ``data`` after ``clean_data`` had dropped it —
    # on the claim that it was "the one field here that is meaningfully NULLABLE". It was not:
    # ``description`` is ``String?`` too, so emptying the description box answered 200 and left the
    # old description in the database and in every render of the form. Both go through ``clearable``
    # now, which is the mechanism the rest of the write paths use, so there is one rule to keep in
    # step instead of two. Valid only because this dump is ``exclude_unset=True``.
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_QUESTIONNAIRE_CLEARABLE_COLUMNS,
    )
    if data.get("designWorkshopId"):
        # `_require_owner` above answered a DIFFERENT question — who owns this QUESTIONNAIRE —
        # and owning the form has never said anything about the workshop it is being pointed at.
        # The truthiness test is the detach case (`None`) passing through unchecked, on purpose;
        # see the helper.
        await _require_attachable_workshop(data["designWorkshopId"], current_user)
    if "isShared" in data and not is_admin(current_user):
        # ── PUBLISHING IS AN ADMIN'S ACT, AND `_require_owner` ABOVE IS NOT THAT CHECK ───────────
        #
        # That helper admits the form's OWNER, which is every designer for their own forms. Ticking
        # this flag does not change the owner's form — it changes what every OTHER designer in the
        # country sees in their list and their attach dropdown, which is a repository-wide act and
        # not an edit to a record. Left on `_require_owner` alone, any designer could publish their
        # own draft to the whole fleet by PATCHing one boolean.
        #
        # KEYED ON PRESENCE and not on the value: sending `isShared: false` to UNPUBLISH is the same
        # act in reverse and needs the same authority, and a designer must not be able to withdraw
        # the standard instrument from everybody either.
        #
        # 403 and not 404, which is the opposite of `_require_attachable_workshop`'s rule and is
        # right here: this caller already holds the questionnaire and is reading it on screen, so
        # there is nothing left to conceal — what they need told is that the CONTROL is not theirs.
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only an admin can publish a questionnaire as the default form for every designer. "
                "Your own questionnaires are unaffected — ask an admin to publish this one."
            ),
        )
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
    existing = await db.questionnaireformsection.find_many(
        where={"questionnaireId": questionnaire_id}
    )
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
    # AN EMPTY ``clearable``, AND THAT IS THE MEASURED ANSWER RATHER THAN AN OVERSIGHT.
    # ``model QuestionnaireFormSection`` has no nullable column at all — id, questionnaireId, code,
    # title, sortOrder, isActive, createdAt, updatedAt are every one of them NOT NULL — so there is
    # nothing on this model an explicit null could legitimately clear. The three sibling PATCHes in
    # this module each name their own; this one names none because it has none.
    #
    # Passed EXPLICITLY, as ``_SECTION_CLEARABLE_COLUMNS``, rather than left off the call: an omitted
    # argument and a deliberately empty one look identical from here, and the completeness test needs
    # this route to have stated an answer for this model rather than said nothing.
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_SECTION_CLEARABLE_COLUMNS,
    )
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        await db.questionnaireformsection.update(where={"id": section_id}, data=data)
    return public_encode(await load_form(questionnaire_id, include_retired=True))


@router.post(
    "/{questionnaire_id}/sections/{section_id}/questions", status_code=status.HTTP_201_CREATED
)
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
            helpText=payload.helpText
            if "helpText" in payload.model_fields_set
            else question.helpText,
            isRequired=payload.isRequired
            if payload.isRequired is not None
            else question.isRequired,
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

    # ``helpText`` is the only nullable column ``CustomQuestionUpdate`` can reach; the list and what
    # is left out of it are at ``_QUESTION_CLEARABLE_COLUMNS`` above, including why the retirement
    # machinery's columns stay out. It used to be put back by hand after ``clean_data`` had dropped it; it goes
    # through ``clearable`` now so this route uses the same mechanism as its siblings instead of a
    # second one that has to be remembered. Valid only because this dump is ``exclude_unset=True``.
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_QUESTION_CLEARABLE_COLUMNS,
    )
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
    # ``QuestionnaireFormEntry``'s own nullable scalars — the list is at
    # ``_ENTRY_CLEARABLE_COLUMNS`` above — and on this route the PII case is the whole point:
    # ``respondentName`` is the name of the person interviewed and ``notes`` is what was written down
    # about them during the sitting. A researcher told to retract either had no way to do it — the
    # null was stripped, the PATCH answered 200, and the name stayed on the row and in the
    # questionnaire annexure. ``title`` is NOT NULL (and stripped below), ``source`` and
    # ``createdById`` are NOT NULL and not on the schema. Valid only because this dump is
    # ``exclude_unset=True``.
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_ENTRY_CLEARABLE_COLUMNS,
    )
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
