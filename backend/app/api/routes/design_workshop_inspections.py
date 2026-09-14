"""The INSPECTOR's surface — a read, and now a note — and the admin screen that assigns inspections.

Seven routes. **THIS HEADER USED TO SAY "Five routes. Every route an inspector can reach is a GET,
and that is not an accident of what has been built so far — it is the feature." THAT SENTENCE IS
NOW WRONG IN ITS LETTER AND RIGHT IN ITS SPIRIT, AND IT IS CORRECTED RATHER THAN DELETED**, because
the property it was defending is the one that still matters and is unchanged:

    AN INSPECTOR STILL CANNOT TOUCH THE DESIGNER'S CONTENT.

The two POST routes added on 2026-09-13 write ONE table — ``DwInspectionFeedback`` — plus the three
decision-cache columns and one audit row on the workshop. They cannot reach a ``DwStageEntry``:
``InspectionWritePlan`` refuses every table outside its three by construction, the read-only loader
below still takes no argument that turns a read into a write, and the six write doors on
``/design-workshops`` still answer 403 to an INSPECTOR before the database. What changed is that an
inspection is now a read AND A NOTE, which is what the owner's requirement asks for: *"Inspecting
Officers can review them and submit correction suggestions directly in a feedback box."* Until this
landed an inspector could record nothing at all, which made the tier a viewer with extra steps.

See ``app/services/design_workshop_inspectors.py`` for the argument in full; this module is the
wire, and the two things it adds are the doors.

=======================================================================================
WHY THIS IS ITS OWN ROUTER ON ITS OWN PREFIX
=======================================================================================

``/design-workshops`` is already shared by two routers and carries ``GET /{workshop_id}``, which
swallows any literal path mounted after it (see the note above ``design_workshop_viewers`` in
``app/api/router.py``). That is the ordering hazard, and it is the lesser of the two reasons.

The deciding reason is the one ``design_ratings`` and ``design_workshop_access`` both give for their
own prefixes: **the caller of every route in this file is, by definition, somebody
``load_workshop_or_404`` turns away.** An inspector is not in ``DESIGN_WORKSHOP_ROLES``, so that
loader 404s them. A route sharing that prefix invites the next reader to "fix" the inconsistency by
widening the shared loader — and widening it grants STAGE WRITES.

THAT LAST CLAUSE USED TO REST ON "``load_workshop_or_404(for_edit=True)`` performs no role check at
all", AND IT NO LONGER DOES (corrected 2026-09-03). Since that date the loader honours a
``DesignWorkshopViewer`` row only for an account inside ``DESIGN_WORKSHOP_ROLES``, so an inspector is
now refused twice over rather than once. The conclusion is unchanged and so is the guard rail:
``for_edit=True`` still carries no role check of its own — all it changes is that a deleted workshop
answers 409 instead of 404 — so the reader who "fixes" the inconsistency by adding INSPECTION_ROLES
to that set, or by hanging a fourth arm off the loader, is granting stage writes and not reads. The
prefix boundary is a guard rail, not a filing decision.

=======================================================================================
THE TWO DOORS
=======================================================================================

* :func:`require_inspector` — the inspector's own read surface. **403 for admins too**, naming the
  route they actually want; ``assert_inspection_surface`` argues why.
* ``Depends(require_admin)`` — the administration of who inspects what. THE INSPECTED MUST NOT
  CHOOSE THE INSPECTOR, so there is no route here through which a designer can add, remove or even
  suggest one, and the workshop's creator gets no say at all.

**REGISTRATION ORDER INSIDE THIS MODULE IS LOAD-BEARING.** ``GET /eligible-inspectors`` is declared
before ``GET /{workshop_id}``, which matches it perfectly well and would answer 404 "Record not
found" — the same trap that once left the admin's designer picker empty on a server where the route
existed. FastAPI matches in declaration order, so the literal path is declared first.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

# THE STAGE SERIALISER IS IMPORTED RATHER THAN COPIED, and it is a PRIVATE name in another route
# module, which is a smell worth stating out loud instead of quietly living with.
#
# The alternative is worse. ``_stages_payload`` is registry-aware — it reads entity CARDINALITY out
# of the stage registry and has a dedicated branch for the reserved ``_custom`` entity key — and its
# own docstring records what a second implementation gets wrong: the custom row falls through to the
# collection arm and comes back as a phantom repeating entity, on every stage that has custom
# answers, which both clients render as a table of one row nobody can delete. A private import is a
# smaller problem than shipping that bug to a second reader of the same rows, and an inspector
# reading a DIFFERENT shape from the designer who typed it defeats the point of an inspection.
#
# NEITHER OF THESE AUTHORISES ANYTHING. Both are pure serialisers over rows this route has already
# decided the caller may read; the authorisation is `load_inspectable_workshop_or_404`, above them.
# The clean fix is to promote both to ``services/design_workshops`` beside ``workshop_summary``,
# which this wave deliberately does not do because that file is being edited by another workstream.
#
# `_inspection_feedback_payload` IS IMPORTED FOR THE SAME REASON AND WITH THE SAME EXEMPTION: the
# register both screens read must come back in ONE shape, and the `include={"actor": True}` that
# puts a name on every suggestion is inside it. A second implementation here would differ first in
# exactly that include, and the symptom would be a panel of corrections attributed to nobody.
from app.api.routes.design_workshops import (
    _inspection_feedback_payload,
    _provenance_maps,
    _stages_payload,
)
from app.core.db import db
from app.core.deps import get_current_user, require_admin
from app.schemas import design_workshop_review_loop
from app.schemas.design_workshop_inspections import (
    DesignWorkshopInspectorsIn,
    DwInspectionFeedbackIn,
    DwInspectionSendBackIn,
)
from app.services.concurrency import gather_reads
from app.services.custom_sections import load_definition_or_empty
from app.services.design_workshop_inspectors import (
    assert_inspection_surface,
    eligible_inspectors,
    inspectable_by_clause,
    inspector_rows,
    load_inspectable_workshop_or_404,
    replace_inspectors,
)
from app.services.design_workshops import entry_rows, workshop_completeness, workshop_summary
from app.services.entry_provenance import resolve_display_names
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains
from app.services.stage_schema import registry_version

router = APIRouter(prefix="/design-workshop-inspections", tags=["design-workshops"])


async def require_inspector(current_user: Any = Depends(get_current_user)) -> Any:
    """The inspector's own read surface: the INSPECTOR tier and nobody else.

    A dependency rather than a call inside each handler, so that a route added to this file without
    one is visible as a missing ``Depends`` rather than as a missing line in a body.

    ⚠ **LIVES HERE AND NOT IN ``core/deps.py``**, which is where ``require_admin``,
    ``require_designer`` and the rest of the ladder's doors live, and that asymmetry is temporary
    rather than principled: ``deps.py`` is owned by another workstream in this wave. If you are the
    person consolidating them, move :data:`INSPECTION_ROLES` and this function together — splitting
    them is how "who is an inspector" comes to have two answers.
    """
    assert_inspection_surface(current_user)
    return current_user


async def _workshop_or_404(workshop_id: str) -> Any:
    """The workshop, for an ADMINISTRATOR.

    Deliberately NOT ``load_inspectable_workshop_or_404``: that helper answers "may THIS INSPECTOR
    read it", and every caller of the two administration routes is an admin, for whom the answer is
    always yes — including for a soft-deleted workshop, which an admin has to be able to administer
    in order to restore it with its inspection intact. The sibling viewers router makes the same
    split for the same reason.
    """
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# --------------------------------------------------------------------------------------
# The admin's screen: who inspects what
#
# DECLARED FIRST because of the literal path below. See the module docstring.
# --------------------------------------------------------------------------------------


@router.get("/eligible-inspectors")
async def list_eligible_inspectors(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """The accounts that may be assigned an inspection at all.

    Not the user directory narrowed by the client. The eligible set is a SET of roles and not a rank
    threshold, and it further excludes anyone the platform allow-list has rejected or suspended —
    accounts that cannot sign in, for whom an inspection row would mean this screen saying somebody
    is inspecting while they are shown a refusal at the door. All of it is a rule the client cannot
    see and would drift from within one release.

    ``truncated`` in the answer says the list was cut. Both clients must say so when it is true and
    say nothing when it is false; that is the whole contract, and an empty list with no explanation
    is this repository's most repeated bug class.
    """
    return await eligible_inspectors(search=search)


@router.get("/{workshop_id}/inspectors")
async def list_inspectors(workshop_id: str, _: Any = Depends(require_admin)) -> dict[str, Any]:
    """Everyone assigned to inspect this workshop.

    An empty list means NOBODY IS INSPECTING IT — the literal truth, unlike the viewers list, where
    an empty answer still leaves the creator holding the workshop through ``createdById``. Nobody
    holds an inspection by any route other than a row in this table.
    """
    await _workshop_or_404(workshop_id)
    return {"inspectors": await inspector_rows(workshop_id)}


@router.put("/{workshop_id}/inspectors")
async def set_inspectors(
    workshop_id: str,
    payload: DesignWorkshopInspectorsIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Replace the whole inspection set, and answer with it as it now stands.

    **ADMIN ONLY, AND THAT INCLUDES THE WORKSHOP'S OWN CREATOR.** The inspected must not choose the
    inspector; if a designer could put somebody on their own workshop as its inspector — or take
    somebody off it — the inspection is worth nothing. There is deliberately no "suggest an
    inspector" route either, because a suggestion an admin rubber-stamps is the same thing wearing a
    queue.

    REPLACES. There is no add route and no remove route: taking somebody off is sending the list
    without them. So a client that posts only what it just ticked has silently ended everybody
    else's inspection, which is why the body is named for the whole set and why the answer is the
    set as the SERVER now holds it rather than an echo of what was sent — two admins on the same
    screen must not each end up believing their own payload was the outcome.

    Idempotent: saving an unchanged screen writes nothing. An unknown, ineligible, barred or
    already-on-the-workshop id refuses the ENTIRE call with a 422 naming the account and the remedy,
    never a silent skip — see ``services/design_workshop_inspectors``.
    """
    await _workshop_or_404(workshop_id)
    inspectors = await replace_inspectors(
        workshop_id, payload.userIds, assigned_by_id=current_user.id
    )
    return {"inspectors": inspectors}


# --------------------------------------------------------------------------------------
# The inspector's own surface. EVERY ROUTE BELOW IS A GET, AND THAT IS THE FEATURE.
# --------------------------------------------------------------------------------------


@router.get("")
async def list_inspectable_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    """The design & prototype workshops this inspector has been assigned, newest first.

    **AN INSPECTOR WITH NO INSPECTION ROW SEES AN EMPTY PAGE, AND THAT IS THE WHOLE SCOPE.** There
    is no "all workshops" arm, no rank fallback and no ``createdById`` arm — an inspector creates
    nothing — so this list has exactly one source and there is no second way in to reason about.
    ``tests/test_dw_inspector_scope.py`` asserts the empty case directly, because a scope whose
    zero state is untested is a scope that will quietly widen.

    THE LIST IS HALF THE FEATURE, the same lesson ``design_workshop_viewers`` records: a scope the
    list does not honour tells its holder that a workshop exists (they can open it by id) and
    simultaneously that it does not (it is absent from every list they can reach). Nothing in either
    client navigates to a workshop by typed id.

    ``deletedAt: None`` because a soft-deleted workshop is a 404 for everyone but an admin, and an
    inspector is not an admin and has no restore button. Leaving it out would list workshops the
    detail route then refuses.

    THE SCOPE IS AND-COMPOSED AND THE SEARCH IS NOT. The search box takes ``where["OR"]``; writing
    the scope there too is two assignments to one key, the later silently wins, and either the
    search stops narrowing or the scope vanishes the moment somebody types. Same warning, same
    reason, as ``services/records.owned_or_granted_where``.
    """
    where: dict[str, Any] = {"deletedAt": None}
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"title": contains(term)},
            {"craftName": contains(term)},
            {"clusterName": contains(term)},
            {"workshopCode": contains(term)},
        ]
    where.setdefault("AND", []).append(inspectable_by_clause(current_user.id))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    # Count and page together: neither reads the other, and in series the count was a whole round
    # trip added to every page of an inspector's list — a cross-region one when this was written,
    # a co-located one or two milliseconds since 2026-09-02 (``services/concurrency.py``). One wait
    # instead of two is the claim, and it survives the move. The ORDER is deliberately
    # left exactly as it was rather than routed through ``records.count_and_page``, which would add
    # an ``id`` tiebreak and quietly change which rows land on which page of an existing client.
    total, rows = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.get("/{workshop_id}")
async def read_workshop_under_inspection(
    workshop_id: str, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    """One workshop under inspection, with every stage's data and its completeness scores.

    **THE READ-ONLY TWIN OF ``GET /design-workshops/{workshop_id}``, and the differences are the
    point rather than an omission.** What is deliberately absent from this payload:

    * ``transcripts``. The designer's read fills that key from ``owned_or_granted_where``, which
      admits an account below professor only for media it uploaded, media whose owner granted it a
      ``DataAccessGrant``, or media tagged to a workshop it holds through ``DesignWorkshopViewer`` /
      ``createdById``. An inspector holds none of those, so calling it here would cost a query to
      produce an empty list — and, worse, would put this route on the media path at all, so that the
      next person widening that predicate widens this surface without noticing. It is not called.
    * Anything that writes. There is no ``for_edit``, no PATCH twin, no stage save and no report
      route on this prefix. ``load_inspectable_workshop_or_404`` takes no ``for_edit`` parameter, so
      there is no argument this request could carry that turns the read into a write.

    Whether an inspector SHOULD see the workshop's photographs and recordings is an owner's decision
    that has not been made. It is deliberately not made here by accident: today the answer is no,
    stated in one place, rather than yes by inheritance from a predicate written for co-designers.

    Provenance names ARE resolved, because "who wrote this field" is most of what an inspection is
    for, and the ids without them are unreadable. ``resolve_display_names`` is one query for the
    whole workshop and reads only ``User.name``.
    """
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    # SAID ON THE WIRE RATHER THAN INFERRED FROM THE URL, because both clients will eventually render
    # this payload through the same screen as the designer's read, and a screen that cannot tell the
    # two apart will offer a Save button that the API answers 404 to. One boolean is cheaper than the
    # bug report.
    summary["readOnly"] = True
    # THE FEEDBACK ALREADY FILED, NEWEST FIRST, SO AN OFFICER CAN SEE WHAT COLLEAGUES HAVE ASKED FOR
    # BEFORE ASKING FOR IT AGAIN — and so the designer's panel and this one are built from the same
    # rows in the same shape. Bounded rather than paged: a report goes through a handful of rounds,
    # and `inspectionFeedbackTruncated` says so out loud rather than letting a long history look
    # like a short one.
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = (
        await _inspection_feedback_payload(workshop_id)
    )
    # SAID ON THE WIRE RATHER THAN INFERRED FROM `readOnly`, AND THE TWO ARE NOT THE SAME ANSWER.
    # `readOnly` is about the workshop's CONTENT and must stay true — the handset's own helper is
    # `readOnly != false`, so it fails CLOSED, and flipping it to enable a feedback box would offer
    # a Save button on every stage form that this API answers 404 to, and would open nine designer
    # screens on a payload that cannot write any of them. This is a second, narrower key: may THIS
    # account file a suggestion about this report. The designer's own detail read carries the same
    # key with the opposite value, so a shared component cannot be wrong about which screen it is.
    #
    # TRUE WITHOUT RE-ASKING THE DATABASE: reaching this line means `require_inspector` admitted the
    # account and the read-only loader found their row on this workshop, which is the whole of what
    # the two write routes below also require. A workshop that is not under review is a different
    # question — it is refused at the write with a sentence, rather than by hiding the box, because
    # "this report has not been handed in yet" is something an officer needs to be told.
    summary["mayRecordFeedback"] = True
    return summary


# --------------------------------------------------------------------------------------
# THE FIRST WRITE DOORS ON THIS ROUTER, ADDED 2026-09-13
#
# TWO ROUTES AND NOT ONE ROUTE WITH A `sendBack` BOOLEAN, and the reason is the record page's own:
# "ONE BUTTON PER STATUS, NEVER ONE BUTTON PER INTENTION". Adding a suggestion to an open round and
# sending a report back to its designers are different acts with different consequences — the
# second one moves a status, writes a ReviewLog row and puts a fortnight of somebody's work back on
# their desk — and a boolean on one endpoint is how the second happens by accident.
#
# DECLARED LAST, AND BOTH PATHS END IN A LITERAL SEGMENT. `GET /{workshop_id}` above matches one
# path segment and cannot swallow either of these, so the ordering hazard the module docstring
# names does not apply — but they are declared after it anyway, so that the file goes on reading in
# the order FastAPI matches.
#
# NEITHER ROUTE TAKES A `round` FROM THE CLIENT. It is copied off the workshop row inside the
# handler, because a client that could choose the cycle could file a correction against a round the
# designer has already answered.
# --------------------------------------------------------------------------------------


def _under_review_or_422(record: Any) -> str:
    """The workshop's status, or the sentence that refuses a decision on a report nobody handed in.

    **STEP ONE OF BOTH WRITE HANDLERS, AND IT IS NOT A COURTESY.** The inspector's scope is a row
    plus a soft-delete test and carries NO status term, so an officer holding a row on a DRAFT or an
    IN_PROGRESS workshop reaches the write with `submissionRound == 0` — and the CHECK constraint
    `DwInspectionFeedback_round_check` then refuses the INSERT, the driver raises, and the officer
    is told the server is broken about a report that simply has not been handed in yet.
    """
    current = str(getattr(record, "status", "") or "")
    if current not in design_workshop_review_loop.UNDER_REVIEW:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=design_workshop_review_loop.NOT_UNDER_REVIEW_REFUSAL,
        )
    return current


async def _apply(client: Any, plan: Any) -> Any:
    """Run one :class:`InspectionWritePlan` against a client — the real one, or a transaction's.

    THE DELEGATE IS RESOLVED OFF THE CLIENT IT IS HANDED, because `db.tx()` returns a DIFFERENT
    client and a write issued against the module singleton inside a transaction commits on its own.
    That is the failure `api/routes/review.py` records beside its own send-back: the decision and
    the log of it must stand or fall together.

    The plan has already refused every table but its three, and refused an update with no `where`,
    at construction. This function therefore performs no validation of its own — a second copy of
    those rules is a second place for them to differ.
    """
    delegate = getattr(client, plan.table.lower())
    if plan.operation is design_workshop_review_loop.Operation.CREATE:
        return await delegate.create(data=dict(plan.data))
    return await delegate.update(where=dict(plan.where or {}), data=dict(plan.data))


async def _apply_while_under_review(client: Any, plan: Any) -> int:
    """Run an UPDATE plan, but only against a workshop that is STILL under review. Answers the count.

    ── WHY THE STATUS CHECK CANNOT LIVE WHERE `_under_review_or_422` PUTS IT ─────────────────────

    `send_workshop_back_for_revision` reads the workshop OUTSIDE the transaction, validates its
    status against `UNDER_REVIEW`, refuses an illegal edge with `transition_refusal` — and then
    applies a write plan whose WHERE is the primary key alone. Between that read and that commit
    there are three round trips (building the plans, BEGIN, the feedback INSERT), and the workshop
    can leave the set the check validated against inside them. It is not a hypothetical hole: the
    ONLY header edit `patchable_from("PRE_SUBMISSION")` offers is `-> IN_PROGRESS`, the web record
    page ships it as a literal button labelled "Withdraw from inspection", and the review graph's
    own commentary instructs designers to take it. So: officer presses "Send the report back" while
    the designer presses "Withdraw from inspection"; the designer's PATCH commits first; the
    officer's UPDATE re-evaluates a predicate that is the id and overwrites IN_PROGRESS with
    NEEDS_REVISION — an edge `LEGAL_TRANSITIONS` does not contain from that state at all, refused by
    this very module's `transition_refusal` even with `by_decision_route=True`. The graph then holds
    a state it declares unreachable, a `ReviewLog` row asserts a decision on a report that had left
    review, and the designer's NEXT content-changing save trips
    `design_workshops`' `NEEDS_REVISION and _content_changed` arm and silently RESUBMITS the report
    they had deliberately withdrawn, spending a submission round.

    Under READ COMMITTED an `UPDATE … WHERE id = ?` waits on the designer's row lock and then
    re-evaluates its predicate; adding the status term to that predicate is therefore the whole fix,
    and it is why this is `update_many` (the only call that takes a predicate beyond the primary key
    and answers with a count) rather than `update`.

    **THE `NEEDS_REVISION` MEMBER OF THE SET IS DELIBERATE AND MUST STAY.** `send_workshop_back` is
    documented as a 200 for a second officer sending back an already-sent-back report — "refusing
    the second would lose their correction to a race" — and `UNDER_REVIEW` is `{PRE_SUBMISSION,
    NEEDS_REVISION}`, so that case still writes. It is imported rather than retyped so a ninth
    status cannot change what "under review" means without changing this too.

    A COUNT AND NOT A RECORD: `update_many` answers rows affected. The caller re-reads inside the
    same transaction, which it has to do anyway because `_feedback_answer` needs a workshop row.
    """
    delegate = getattr(client, plan.table.lower())
    return await delegate.update_many(
        where={
            **dict(plan.where or {}),
            "status": {"in": sorted(design_workshop_review_loop.UNDER_REVIEW)},
        },
        data=dict(plan.data),
    )


def _parse_recorded_at(value: str | None) -> Any:
    """The device's own moment, or None, refusing a string that is not a time.

    A 422 NAMING THE FIELD rather than a 500 from the driver: the value crosses the wire as text
    from a handset, and "recordedAt is not a time" is something a client can act on.

    A NAIVE MOMENT IS READ AS UTC rather than refused, matching every other clock this API parses
    off a handset: the fleet's devices send Z-suffixed times, and an older build that drops the
    suffix has not lied about anything — it has been imprecise about a field whose whole purpose is
    to be compared against this server's clock.
    """
    text = (value or "").strip()
    if not text:
        return None
    try:
        # NO `.replace("Z", "+00:00")`. `requires-python` is >=3.11 and 3.11's `fromisoformat` reads
        # the military Z itself, so the substitution was a no-op that FURB162 reports as a lint
        # error — and `pyproject.toml`'s own baseline note says the redundancy is real and belongs
        # to whoever is in the function. The handsets go on sending Z-suffixed times and go on
        # being parsed; nothing about the wire contract moves.
        moment = datetime.fromisoformat(text)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "recordedAt must be an ISO-8601 moment (for example 2026-09-13T10:15:00Z). Leave "
                "it out entirely when the suggestion is being filed straight against the server."
            ),
        ) from None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


async def _feedback_answer(record: Any, *, may_record: bool = True) -> dict[str, Any]:
    """The workshop header plus the register, which is what both write routes answer with.

    THE ANSWER IS THE REGISTER AND NOT THE ROW JUST WRITTEN. An officer who files a suggestion is
    looking at a list of them; answering with only the new one would make every client re-read the
    workshop to redraw its own screen, and the two screens would disagree for as long as it took.
    """
    summary = workshop_summary(record)
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = (
        await _inspection_feedback_payload(record.id)
    )
    summary["readOnly"] = True
    summary["mayRecordFeedback"] = may_record
    return summary


@router.post("/{workshop_id}/feedback", status_code=status.HTTP_201_CREATED)
async def record_inspection_feedback(
    workshop_id: str,
    payload: DwInspectionFeedbackIn,
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    """File one correction suggestion. **THE WORKSHOP'S STATUS IS NOT MOVED.**

    THE TWO ACTS ARE SEPARATE ON PURPOSE. An officer reading a report writes down four things they
    want changed and then decides whether that is enough to send it back; folding both into one
    endpoint would make the second happen by accident. This route adds a row to the open round and
    nothing else — no status, no ReviewLog entry, no designer's afternoon lost.

    ``round`` IS COPIED OFF THE WORKSHOP and never sent by a client, so a suggestion filed today is
    filed against the cycle the report is in today. It stays in that cycle for ever: the column is
    copied at write time and never recomputed, which is what makes "what was asked for in round 2"
    answerable after round 5.

    ``recordedAt`` IS THE DEVICE'S OWN MOMENT — an officer can write a suggestion in a courtyard a
    fortnight before the handset syncs — and a clock more than the shared skew AHEAD of this server
    is REFUSED rather than corrected. Storing a corrected time would file the suggestion at a moment
    nobody chose. ``createdAt`` is always when this server heard it.

    APPEND-ONLY: there is no PATCH and no DELETE for these rows anywhere, and there must never be
    one. An officer who changes their mind files another suggestion; a correction the designer has
    already acted on is not editable out of the record afterwards.

    A ``stageKey`` IS VALIDATED AGAINST THE REGISTRY AND A ``fieldKey`` DELIBERATELY IS NOT — there
    are 22 stages and every client draws them from the same dump, while a field key can legitimately
    come from a client one release ahead, and refusing that row would lose the suggestion rather
    than the typo.
    """
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    _under_review_or_422(record)
    try:
        plan = design_workshop_review_loop.feedback_plan(
            workshop_id=workshop_id,
            round=int(getattr(record, "submissionRound", 0) or 0),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            stage_key=payload.stageKey,
            field_key=payload.fieldKey,
            recorded_at=_parse_recorded_at(payload.recordedAt),
        )
    except design_workshop_review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    await _apply(db, plan)
    return await _feedback_answer(record)


@router.post("/{workshop_id}/send-back")
async def send_workshop_back_for_revision(
    workshop_id: str,
    payload: DwInspectionSendBackIn,
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    """Send the report back to its designers with mandatory comments (status NEEDS_REVISION).

    When any of the workshop's designers next SAVES A STAGE AND CHANGES SOMETHING, the workshop
    returns to PRE_SUBMISSION for a fresh pass — the edit IS the resubmission. That is the rule
    ``services/records.resubmit_status`` has run over six record types since it was written, applied
    to a record whose content is rows rather than columns; see ``services/design_workshops`` and its
    ``_content_changed``.

    **COMMENTS ARE MANDATORY**, a 422 if blank, byte-for-byte the refusal ``api/routes/review.py``
    gives for the six record types. A send-back with no sentence tells a designer only that a
    fortnight of work is wrong.

    **THREE WRITES, ONE TRANSACTION.** The feedback row, the workshop's status and decision cache,
    and the ReviewLog entry. ``api/routes/review.py`` records why the decision and the log of it are
    one write: a failure in the gap leaves a report sent back with nothing anywhere saying who sent
    it back, when, or on what note, and "a status change with no log entry is a decision that
    appears to have made itself". The delegates are re-resolved against ``tx``, because ``db.tx()``
    hands back a DIFFERENT client.

    A SECOND OFFICER SENDING BACK AN ALREADY-SENT-BACK REPORT IS A 200 AND A ROW, not a refusal: the
    transition is a no-op, the status does not move, and their sentence joins the register for the
    same round. Two officers reading one report at the same time is the ordinary case, and refusing
    the second would lose their correction to a race.

    **THE STATUS CHECK ABOVE IS ADVISORY AND THE ONE ON THE WRITE IS NOT.** ``_under_review_or_422``
    reads a row fetched before the transaction opened, so it answers about the workshop as it stood
    three round trips ago; the write itself carries the status in its WHERE through
    :func:`_apply_while_under_review`, and a zero count is the designer having withdrawn the report
    from inspection in the gap. That function's docstring carries the whole argument, including why
    the 422 rolls the suggestion row back with it.

    NO ReviewLog ROW IS EVER WRITTEN FOR THE RESUBMISSION that follows. ``ReviewLog.status`` is
    ``RecordStatus``, which has no ``PRE_SUBMISSION``; the resubmission is the designer's edit and is
    already recorded by the stage rows' own timestamps and provenance.
    """
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    current = _under_review_or_422(record)
    refusal = design_workshop_review_loop.transition_refusal(
        current, design_workshop_review_loop.NEEDS_REVISION, by_decision_route=True
    )
    if refusal:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
    try:
        plans = design_workshop_review_loop.send_back_plans(
            workshop_id=workshop_id,
            round=int(getattr(record, "submissionRound", 0) or 0),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            stage_key=payload.stageKey,
            field_key=payload.fieldKey,
            recorded_at=_parse_recorded_at(payload.recordedAt),
        )
    except design_workshop_review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    async with db.tx() as tx:
        # THE ORDER IS THE ORDER A PERSON WOULD TELL IT IN, and inside one transaction it is
        # cosmetic: the suggestion, then the decision it caused, then the audit entry. All three or
        # none of them.
        await _apply(tx, plans.feedback)
        if not await _apply_while_under_review(tx, plans.workshop):
            # SOMEBODY MOVED IT BETWEEN THE READ AT THE TOP AND THIS WRITE — in practice the
            # designer withdrawing the report from inspection. Raising here rolls the whole
            # transaction back, INCLUDING the suggestion row: a correction filed against a round
            # that is no longer open would sit in the register naming a cycle nobody can answer,
            # because `round` is copied at write time and never recomputed. The officer gets the
            # same sentence they would have got a second earlier, which is the true one.
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=design_workshop_review_loop.NOT_UNDER_REVIEW_REFUSAL,
            )
        # RE-READ INSIDE THE TRANSACTION. `update_many` answers a count, and `_feedback_answer`
        # needs the row as this transaction has just left it — read outside, it would be the row as
        # some other transaction has left it.
        updated = await tx.designworkshop.find_unique(where={"id": workshop_id})
        await _apply(tx, plans.log)
    return await _feedback_answer(updated)
