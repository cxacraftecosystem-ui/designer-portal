"""Workshop CRUD plus the whole workshop-access lifecycle.

Access to a workshop is a two-sided conversation held in ONE WorkshopAssignment row per (workshop,
user): an admin grants and revokes; a user requests and is approved or denied. The enforcement that
reads those rows lives in ``app.services.workshop_access`` — including the rule that decides whether
a workshop is curated at all, which is subtler than "does it have rows" and is documented there.

Endpoints come in two families:

* ``/workshops/{id}/assignments*`` — admin-side roster management for ONE workshop.
* ``/workshops/access-requests*`` — the user-side request flow plus the admin's cross-workshop
  approval queue, so an approver works from one list instead of opening every workshop in turn.

Nothing here DELETEs an assignment. A refusal or a withdrawal of access is history worth keeping, so
rows move to DENIED/REVOKED and stay put; see :func:`revoke_workshop_assignment`.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    require_admin,
    require_workshop_manager,
)
from app.schemas.access import (
    WORKSHOP_REQUEST_MAX,
    WorkshopAccessDecisionIn,
    WorkshopAccessRequestIn,
    WorkshopAssignmentIn,
    WorkshopAssignmentUpdateIn,
    WorkshopGrantIn,
)
from app.schemas.records import WORKSHOP_TYPES, WorkshopCreate, WorkshopUpdate
from app.services.access import guard_record_edit, record_revision
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    merge_field_provenance,
    public_encode,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.workshop_access import (
    DEFAULT_GRANT_LEVEL,
    WORKSHOP_DECISIONS,
    WORKSHOP_LEVEL_DESCRIPTIONS,
    WORKSHOP_LEVELS,
    access_denied_detail,
    accessible_workshops_where,
    describe_workshop_submission,
    enum_str,
    resolve_workshop_access,
    valid_level,
    workshop_is_curated,
)
from app.services.workshop_inference import (
    apply_workshop_mapping,
    discard_one_unmapped,
    file_one_unmapped,
    plan_workshop_mapping,
)

router = APIRouter(prefix="/workshops", tags=["workshops"])

# What a workshop carries on the wire, loaded in one parallel wave (see services/records.py for why
# that is worth the indirection). Writes hydrate the row they just saved rather than passing an
# ``include`` to Prisma, so this is the single description of a workshop's relations.
RELATIONS = (
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("artisans", "workshopartisan", "workshopId", many=True, include={"artisan": True}),
    Relation("crafts", "workshopcraft", "workshopId", many=True, include={"craft": True}),
)

# Every party to an assignment row, so a UI can render "granted by X / requested by Y / decided by Z"
# without a second round of lookups.
ASSIGNMENT_INCLUDE: dict[str, Any] = {
    "user": True,
    "assignedBy": True,
    "requestedBy": True,
    "decidedBy": True,
}
# The cross-workshop views also need to name the workshop each row belongs to.
REQUEST_INCLUDE: dict[str, Any] = {**ASSIGNMENT_INCLUDE, "workshop": True}


def _level_or_422(value: Any, fallback: str | None) -> str | None:
    """Validate an incoming accessLevel against the ladder, falling back when the caller omits it."""
    if value is None:
        return fallback
    level = enum_str(value)
    if not valid_level(level):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"accessLevel must be one of {', '.join(WORKSHOP_LEVELS)}",
        )
    return level


def _status_or_422(value: Any, allowed: tuple[str, ...]) -> str:
    state = enum_str(value)
    if state not in allowed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"status must be one of {', '.join(allowed)}",
        )
    return str(state)


async def _assignment_or_404(workshop_id: str, user_id: str) -> Any:
    row = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop_id, "userId": user_id}}
    )
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No assignment for that user on this workshop",
        )
    return row


async def _assignment_list(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.workshopassignment.find_many(
        where={"workshopId": workshop_id}, include=ASSIGNMENT_INCLUDE, order={"createdAt": "asc"}
    )
    return public_encode(rows)


async def _hydrate_assignment(row_id: str) -> dict[str, Any]:
    row = await db.workshopassignment.find_unique(where={"id": row_id}, include=REQUEST_INCLUDE)
    return public_encode(row)


async def replace_workshop_artisans(
    workshop_id: str, artisan_ids: list[str], *, client: Any = None
) -> None:
    """Rewrite the roster in two statements rather than one per artisan.

    A workshop with forty artisans cost forty-one sequential inserts, and on this deployment every
    one of them is a cross-region round trip — the save took longer than the form took to fill in.
    Duplicates are dropped here because the link table is unique on (workshopId, artisanId) and a
    bulk insert cannot skip a clash the way forty separate ones each could.

    ── ``client`` IS THE CALLER'S TRANSACTION AND EVERY CALLER NOW PASSES ONE (2026-09-03) ──────────

    "Two statements rather than one per artisan" was an honest description of the cost and a
    dangerous one of the SEMANTICS: a delete-then-create pair outside a transaction is a window in
    which the roster is EMPTY and committed. Two things walked through it.

    * A failure between the two — P2024 on the cross-region pool, a dropped socket, the process
      restarting — committed the wipe and nothing else. Forty artisans an administrator had picked
      were gone, the save reported an error, and the obvious retry re-sends the same list from a form
      that is still on screen; the retry is what has hidden this. When the form is NOT still on
      screen there is nothing anywhere that can say what the roster held, because the join rows are
      the only record of it. ``update_workshop`` ran this twice back to back with the workshop's own
      update — four commits for one save — so a save could also leave the row's new dates beside the
      old roster, or a wiped artisan roster beside an intact craft one.
    * Two administrators saving the same workshop at once interleaved into a 500. A's delete, B's
      delete, A's insert, B's insert against ``@@unique([workshopId, artisanId])`` — and unlike
      ``data_access._upsert_grant``, which meets the same index, this insert has no
      ``skip_duplicates``, so the loser got the driver's UniqueViolationError through the catch-all
      in ``main.py``: "Something went wrong on the server", for a save that was merely concurrent.
      A transaction serialises the pair, so the second save rewrites a whole roster instead of
      colliding with half of one.

    ``skip_duplicates`` IS DELIBERATELY STILL NOT SET, and the transaction is why it does not need to
    be: inside one, the delete and the insert see a roster no other writer can be halfway through, so
    a duplicate here would mean a duplicate in the CALLER'S OWN list — which ``dict.fromkeys`` above
    has already removed. Setting it would turn a future bug in this function into silence.

    The parameter cannot be defaulted away or discovered: ``db.tx()`` hands back a DIFFERENT client,
    so a callee writing through the module singleton is not in the caller's transaction however the
    ``async with`` reads — the mechanical fact ``access_roster`` writes out at length. ``None``
    remains accepted so the signature does not lie about what it can do, but there is no call site in
    the tree that omits it; a new one that does gets the old, broken atomicity back.
    """
    writer = db if client is None else client
    await writer.workshopartisan.delete_many(where={"workshopId": workshop_id})
    unique_ids = list(dict.fromkeys(aid for aid in artisan_ids if aid))
    if unique_ids:
        await writer.workshopartisan.create_many(
            data=[{"workshopId": workshop_id, "artisanId": aid} for aid in unique_ids]
        )


async def replace_workshop_crafts(
    workshop_id: str, craft_ids: list[str], *, client: Any = None
) -> None:
    """The craft roster, rewritten in two statements rather than one per craft — see above.

    ``client`` carries the caller's transaction for exactly the reasons the artisan twin sets out at
    length; the delete-then-create window and the concurrent-save collision are the same two, over
    ``@@unique([workshopId, craftId])`` instead. It matters MORE here, not less: ``craft_workshop_clause``
    records that on the live repository almost every craft predates the ``workshopId`` column, so
    these join rows are not a second opinion about which workshop a craft belongs to — they are the
    only one, and a committed wipe of them is a loss with nothing to reconstruct it from.
    """
    writer = db if client is None else client
    await writer.workshopcraft.delete_many(where={"workshopId": workshop_id})
    unique_ids = list(dict.fromkeys(cid for cid in craft_ids if cid))
    if unique_ids:
        await writer.workshopcraft.create_many(
            data=[{"workshopId": workshop_id, "craftId": cid} for cid in unique_ids]
        )


def normalize_workshop_dates(data: dict[str, Any]) -> dict[str, Any]:
    if not data.get("date") and data.get("startDate"):
        data["date"] = data["startDate"]
    if not data.get("startDate") and data.get("date"):
        data["startDate"] = data["date"]
    if not data.get("endDate") and data.get("startDate"):
        data["endDate"] = data["startDate"]
    return data


@router.get("")
async def list_workshops(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    place: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    statusFilter: str | None = None,
    # WHICH KIND. The design-workshop picker asks for DESIGN_PROTOTYPE only: a 22-stage record's
    # cover page is built from its workshop's sanction, cluster and dates, and offering every
    # craft-documentation visit ever recorded made the right row hard to find and the wrong row
    # easy to pick. Omitted means every kind, so no existing caller changes behaviour.
    workshopType: str | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    # WHICH WORKSHOPS THIS ACCOUNT MAY ACTUALLY FILE AGAINST — off by default, and the default is
    # not laziness. Reading the repository is open to every signed-in account on purpose
    # (``records.viewable_where``), so narrowing this list for everybody would empty the data
    # browser's workshop column, the map's scope and the funnel filters of rows the caller is
    # entitled to READ — the "a scoped column matched nothing so a full corpus rendered empty"
    # failure this repository has already shipped once, from ``workshopId`` being NULL.
    #
    # What asks for it is a PICKER: a control that offers a workshop to save a record against. There,
    # an option the API will 403 is not a convenience, it is a form that cannot be submitted and
    # whose refusal arrives after the researcher has typed everything. ``accessible_workshops_where``
    # excludes the curated rosters this caller is not on AT CONTRIBUTE — an admin is never narrowed,
    # and an uncurated workshop is nobody's to withhold because every account already holds
    # CONTRIBUTE on it (``services/workshop_access``, ``OPEN_WORKSHOP_LEVEL``).
    #
    # AT CONTRIBUTE, AND THE LEVEL IS THE HALF THAT WAS MISSING. This comment used to say "exactly
    # the curated rosters this caller is not on", and the predicate behind it tested membership and
    # never read ``accessLevel`` — so a designer holding a GRANTED row at VIEW on a curated workshop
    # was still offered it here and still 403'd by ``enforce_workshop_submission`` on save, with the
    # detail "your access to this workshop is view-only". The narrowing now asks for the same level
    # the write gate asks for, which is what makes the sentence above true; the argument is written
    # out in ``unreachable_workshop_ids``.
    #
    # It composes with ``search``: the narrowing is AND-ed, so typing into a server-backed search box
    # searches the accessible set rather than reopening the whole table. That is what lets a scoped
    # picker keep a real search box over a truncated page.
    accessibleOnly: bool = False,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # Visibility is AND-composed so the search OR (assigned below) can never overwrite it.
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if accessibleOnly:
        # Appended to the same AND list rather than written as a top-level key, for the reason the
        # line above states: ``where["OR"]`` below is the search, and a narrowing that shared the
        # top level with it would be satisfied by any row the search matched.
        scope = await accessible_workshops_where(current_user)
        if scope:
            where.setdefault("AND", []).append(scope)
    if search:
        where["OR"] = [
            {"title": contains(search)},
            {"place": contains(search)},
            {"description": contains(search)},
        ]
    if place:
        where["place"] = contains(place)
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if workshopType:
        if workshopType not in WORKSHOP_TYPES:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"workshopType must be one of {', '.join(sorted(WORKSHOP_TYPES))}",
            )
        where["workshopType"] = workshopType
    if createdBy:
        where["createdById"] = createdBy
    # dateFrom/dateTo filter on startDate — the workshop's own timeline, matching the ordering below.
    add_date_range(where, "startDate", dateFrom, dateTo)
    # ORDERING IS DELIBERATE — by the date the workshop HAPPENED, most recent first, NOT by
    # createdAt. Do not flip it to ``createdAt desc`` for consistency with the other record lists.
    # This is the same ``startDate ?: date ?: createdAt`` occurrence key both clients re-sort by
    # after fetching (``sortWorkshopsByOccurrence`` in the web WorkshopSelect, ``workshopsByRecency``
    # on Android), and the first row is what every record form pre-selects. Ordering by createdAt
    # here makes that client-side sort a lie whenever the two disagree: a workshop HELD last week but
    # ENTERED months ago falls past ``pageSize`` (capped at 100), never reaches the client to be
    # re-sorted, and "the most recent workshop" silently defaults to the wrong one. It has to be
    # fixed HERE, at the source, not in the clients.
    # Prisma cannot express COALESCE in ``order``, so this is the multi-key equivalent, and it is
    # exact rather than approximate here: ``date`` is a required column and ``startDate`` is
    # backfilled from it (migration 20260616093000) and kept in lock-step by
    # ``normalize_workshop_dates`` on every create/update — so ``startDate`` decides every row and
    # the remaining keys only break ties, which also makes the order total for pagination.
    total, items = await count_and_page(
        db.workshop,
        where=where,
        skip=skip,
        take=page_size,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}],
        relations=RELATIONS,
    )
    return page_payload(public_encode(items), total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop(
    payload: WorkshopCreate,
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    # ── THE IDEMPOTENT REPLAY, ABOVE EVERY WRITE IN THIS ROUTE ──────────────────────────────────
    #
    # ``products.create_product`` carries the shared argument. What is particular to a workshop is
    # that a second landing duplicates TWO ROSTERS as well as the row: ``replace_workshop_artisans``
    # and ``replace_workshop_crafts`` run below, so a replayed create produced a second workshop with
    # a second full copy of "Artisans attending" and "Crafts covered". Answering from the stored row
    # and hydrating it writes neither.
    replayed = await client_key_replay(db.workshop, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        await hydrate_relations([replayed], RELATIONS)
        return public_encode(replayed)
    data = clean_data(payload.model_dump())
    artisan_ids = data.pop("artisanIds", [])
    craft_ids = data.pop("craftIds", [])
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # THE ROW AND ITS TWO ROSTERS ARE ONE WRITE (2026-09-03), for the reason the helpers now spell
    # out: three commits in a row meant a failure after the first committed a workshop whose "Artisans
    # attending" and "Crafts covered" lists are empty, indistinguishable on every screen from one an
    # administrator deliberately left blank — and the administrator saw an error, so the one person
    # who knew the lists had been filled in believes nothing was saved at all.
    #
    # THE ``clientKey`` HANDLER WRAPS THE WHOLE ``async with`` AND NOT THE ``create`` INSIDE IT, and
    # that placement is load-bearing rather than tidy. A unique violation has already aborted this
    # transaction in Postgres, so any further statement issued on ``tx`` — including the re-read that
    # finds the winning row — would fail with "current transaction is aborted", and a 201 would
    # arrive as a 500 naming nothing. Catching outside the block means the transaction has been
    # rolled back and the re-read goes through the module client, which is the only place it can
    # work and also the right one: it only needs committed rows.
    # ``artisans.update_artisan`` documents the identical trap on its own identity conflict.
    try:
        async with db.tx() as tx:
            created = await tx.workshop.create(data=data)
            if artisan_ids:
                await replace_workshop_artisans(created.id, artisan_ids, client=tx)
            if craft_ids:
                await replace_workshop_crafts(created.id, craft_ids, client=tx)
    except Exception as exc:
        raced = await client_key_replay_after_violation(
            db.workshop, payload.clientKey, exc, user_id=current_user.id
        )
        if raced is None:
            raise
        await hydrate_relations([raced], RELATIONS)
        return public_encode(raced)
    # The row we just wrote IS the response; only its links changed after the insert, and those are
    # loaded here. Reading the whole workshop back to learn what we already know cost another
    # cross-region round trip, plus one more per relation behind it.
    await hydrate_relations([created], RELATIONS)
    return public_encode(created)


# --------------------------------------------------------------------------- the mapping gap
# Declared above ``/{workshop_id}`` for the reason stated below the access-request banner: FastAPI
# matches in declaration order, so ``/workshops/unmapped`` registered after ``/workshops/{workshop_id}``
# would be swallowed as a workshop whose id is the word "unmapped".
#
# AND THE RULE IS NOT ONLY ABOUT THE SECOND SEGMENT ANY MORE. The two single-row routes below are
# ``/unmapped/{bucket}/{record_id}`` — three segments, the same shape as
# ``/{workshop_id}/assignments/{user_id}`` further down this file, whose DELETE would match
# ``DELETE /workshops/unmapped/interviews/<id>`` perfectly if it came first and would then look for
# an assignment on a workshop called "unmapped". Everything in this block stays above every
# parameterised path in the file, whatever its length.


@router.get("/unmapped")
async def unmapped_records(_: Any = Depends(require_admin)) -> dict[str, Any]:
    """Which records carry no workshop at all, and which workshop each one's own evidence points at.

    A READ. Nothing is written, so this is safe to render, safe to re-render and safe to poll — it is
    the preview an admin approves before :func:`map_unmapped_records` writes anything.

    WHY THIS IS A SCREEN AND NOT A MIGRATION ALONE. ``workshopId`` arrived after a workshop's worth of
    fieldwork was already recorded, and a row without it is invisible under every workshop scope while
    still visible under "All records" — which reads as an empty workshop rather than as a broken
    filter. A migration closes the gap that exists today; this closes the gap that appears the next
    time a client is a version behind, without waiting for a deploy. See
    ``services/workshop_inference`` for the evidence ladder and for why ambiguity is reported rather
    than resolved.

    ADMIN-GATED even though it only reads, because what it returns is a to-do list for an admin action
    and a per-row account of records the caller may not own. The COUNT that ordinary users need — how
    many interviews the completion matrix is missing — is served by ``GET /questionnaire/completion``
    itself, so nobody has to hold this entitlement to understand their own screen.
    """
    return await plan_workshop_mapping()


@router.post("/unmapped/map")
async def map_unmapped_records(_: Any = Depends(require_admin)) -> dict[str, Any]:
    """Stamp every unassigned record whose own evidence names exactly one workshop.

    Takes NO body. The plan is re-derived server-side rather than accepted from the caller: a
    client-supplied list of "set this row's workshop to that id" is a much wider power than "close the
    gap the server itself found", and it would already be stale between the report rendering and the
    button being pressed.

    Idempotent. Every write carries ``workshopId: None`` in its ``where``, so a second press changes
    nothing and a row somebody assigned by hand in between keeps the answer the person gave it. The
    response is the same shape as the preview, plus ``applied`` counts taken from what the database
    reported it changed — so a row that slipped out from under a write appears as a shortfall rather
    than being quietly absorbed.
    """
    return await apply_workshop_mapping()


@router.post("/unmapped/{bucket}/{record_id}")
async def file_one_unmapped_record(
    bucket: str,
    record_id: str,
    workshopId: str = Body(..., embed=True),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """File ONE record the ladder could not settle, under the workshop an admin names.

    THE COMPANION TO THE REPORT'S "left alone" LIST, and the reason it needed one. The ladder
    deliberately refuses a row whose evidence is absent or points two ways
    (``services/workshop_inference``'s header says why picking one would be worse than refusing), so
    those rows are reported by name and by reason — and until this route existed that report was
    where the story stopped. An admin who could see "this interview was at Bagru" had no way to say
    so without leaving the screen, hunting the record down in another list, and editing it there.

    NOT A GENERAL "MOVE THIS RECORD" ROUTE, and that is the whole shape of it. It writes only where
    ``workshopId`` is still NULL, so it can close a gap and can never quietly re-file something a
    person already decided; a row filed since the report was read comes back 409 naming the workshop
    it went to. Changing a record's workshop AFTER it has one is the record's own edit form, which
    writes a ``RecordRevision`` — as it should, because that is an edit to a record rather than the
    closing of a hole.

    NO REVISION ROW HERE, deliberately, and it is the same call the bulk button makes: filling in a
    column that was never populated is not an edit somebody made to somebody else's answer, and the
    two paths that close this one gap must not disagree about whether it appears in a record's
    history. ``update_many`` could not write one per row in any case.

    ``require_admin``, exactly as the two routes above — a per-row account of records the caller may
    not own, and a write into them.
    """
    return await file_one_unmapped(bucket, record_id, workshopId)


@router.delete("/unmapped/{bucket}/{record_id}")
async def discard_one_unmapped_record(
    bucket: str,
    record_id: str,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Delete ONE unfiled record permanently. Admin and master admin only.

    THE OTHER THING AN ADMIN LOOKING AT THIS LIST NEEDS. Some of what lands here should not exist —
    a test row, a duplicated sync, a file uploaded twice — and the reason it is on the report at all
    is that nothing in the repository claims it. ``discard_one_unmapped`` carries the argument for
    the delete being real rather than a flag.

    THE SAME PREDICATE AS EVERY OTHER DELETE IN THIS API, not a looser one. ``require_admin`` here is
    what ``deps.assert_can_delete`` enforces on ``DELETE /artisans/{id}``, ``/products/{id}``,
    ``/tools/{id}``, ``/processes/{id}``, ``/questionnaire/interviews/{id}`` and
    ``/media/{id}``'s admin branch — one rule, reached from the screen where the record is visible.

    200 WITH A BODY, where the per-type deletes answer 204. They have nothing to report; this does.
    Every MediaFile relation is ``onDelete: SetNull``, so deleting a parent record DETACHES its
    attachments rather than removing them, and the count of what survived is the difference between
    "deleted permanently" and "deleted permanently, and its nine photographs are still in the
    repository with nothing pointing at them". The client says that sentence out loud; it cannot if
    the server answers 204.
    """
    return await discard_one_unmapped(bucket, record_id)


# --------------------------------------------------------------------------- access requests
# These MUST stay declared above ``/{workshop_id}``: FastAPI matches in declaration order, and a
# literal path registered after the parameterised one would be swallowed as a workshop id.


@router.get("/access-levels")
async def list_access_levels(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    """The level ladder with human definitions, so a grant/request UI can say what it is handing out."""
    return [
        {"level": level, "description": WORKSHOP_LEVEL_DESCRIPTIONS[level]}
        for level in WORKSHOP_LEVELS
    ]


@router.get("/requestable")
async def list_requestable_workshops(
    current_user: Any = Depends(get_current_user),
    limit: int = Query(WORKSHOP_REQUEST_MAX, ge=1, le=WORKSHOP_REQUEST_MAX),
) -> dict[str, Any]:
    """Every workshop the caller could ASK about, with what they already hold on each.

    This exists because ``GET /workshops`` was the wrong list to build a request picker from, in a way
    that silently disabled the whole feature for the people it is for. That endpoint AND-composed the
    old row-visibility predicate, which below PROFESSOR narrowed the result to records the caller had
    CREATED (plus ones shared with them). A researcher or volunteer who had just joined had created
    nothing, so the picker rendered "No workshops to ask about" and there was no way to ask for access
    to anything — to exactly the workshops they could not see, which is the only reason to ask in the
    first place.

    Reading is open now (``records.viewable_where``), so ``GET /workshops`` would no longer strand
    anybody. This route stays, for the second reason it was built: it carries the caller's own standing
    on each workshop, which the plain list does not, and it is capped and hand-projected. Keeping it is
    also what stops the picker regressing if a read rule is ever introduced.

    The projection is deliberately narrow. Only the identifying fields — title, place, and the dates —
    cross the wire. No description, no notes, no artisans, no crafts, no records, no creator. If you
    add a field to this projection, ask first whether somebody with zero access to the workshop may
    read it.

    Each row also carries the caller's OWN standing, so the picker can be honest rather than offering
    to re-request something already held:

    * ``accessStatus`` — the status of the caller's assignment row: GRANTED / PENDING / DENIED /
      REVOKED, or null when they have never asked and were never assigned.
    * ``accessLevel`` — the level on that same row (null when there is no row). Read it together with
      the status: on a DENIED row it is the level that was refused, not one that is held.
    * ``restricted`` — whether the workshop is curated at all (see ``services.workshop_access``). On
      an open workshop everybody already holds CONTRIBUTE, so a request is only worth filing to ask
      for EDIT; saying so stops new users queueing up for access they already have.

    Capped at ``WORKSHOP_REQUEST_MAX`` — the same limit ``POST /access-requests`` puts on one call —
    so everything offered here can be selected in one submission. Ordered by when the workshop
    HAPPENED, most recent first, matching ``list_workshops`` so the truncation drops the oldest.

    THE RESPONSE IS AN ENVELOPE, ``{"items": [...], "truncated": bool}``, NOT THE BARE ARRAY THIS
    ROUTE USED TO ANSWER — this is the one shape change in the whole route and it is deliberate.
    A bare array carries no way to say "this is every workshop that exists" apart from "this is every
    workshop up to the cap", so ``WorkshopAccessRequestPanel`` had no number to read and no boolean to
    branch on, and stayed honestly silent about a cut it had no way to prove was even happening — the
    one picker in either client that could not state its own ceiling. ``page_payload`` was not the
    fix: it answers ``page``/``pageSize``/``pages`` for a route that has no notion of a page, only a
    single capped fetch, and bolting paging vocabulary onto a picker that is not paged would be a
    second lie in place of the first. ``truncated`` is EXACT rather than guessed, the same trick
    ``GET /tasks/options`` uses for its three picker ceilings: one row is read past ``limit`` and
    trimmed back off before the projection runs, so a repository of exactly ``limit`` workshops
    reports ``False`` honestly and nobody pays for a second COUNT to find out. A caller still decoding
    this as a bare array breaks on the next response it reads — that follow-up belongs to
    ``WorkshopAccessRequestPanel.tsx`` and ``components/settings/workshopAccess.tsx``, not to this
    file.
    """
    uid = get_value(current_user, "id")
    # ONE ROW PAST THE CAP, TRIMMED — see the envelope paragraph above. ``take=limit`` alone cannot
    # tell a repository of exactly ``limit`` workshops apart from one with ten thousand more behind
    # it; both come back as a full page of ``limit`` rows and there is nothing left to compare against.
    # Reading one extra costs nothing this route does not already pay for the first ``limit`` of, and
    # it is what turns ``truncated`` from a guess into a fact.
    workshops = await db.workshop.find_many(
        take=limit + 1, order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}]
    )
    truncated = len(workshops) > limit
    workshops = workshops[:limit]
    ids = [w.id for w in workshops]
    # One query for every candidate's assignment rows rather than one per workshop: the caller's own
    # row and the curation test both come out of the same set. Deliberately NOT narrowed to
    # ``status=GRANTED, assignedById != null`` in SQL, tempting as that is — that predicate IS the
    # "is this workshop curated" rule, and ``workshop_is_curated`` owns it. Restating it here would
    # be a second copy to keep in step, and the module docstring on that function spells out what
    # getting it wrong costs: people locked out of workshops that were always open to them.
    rows = await db.workshopassignment.find_many(where={"workshopId": {"in": ids}}) if ids else []
    by_workshop: dict[str, list[Any]] = {}
    for row in rows:
        by_workshop.setdefault(row.workshopId, []).append(row)
    items: list[dict[str, Any]] = []
    for workshop in workshops:
        workshop_rows = by_workshop.get(workshop.id, [])
        mine = next((r for r in workshop_rows if r.userId == uid), None)
        items.append(
            {
                "id": workshop.id,
                "title": workshop.title,
                "place": workshop.place,
                "date": workshop.date,
                "startDate": workshop.startDate,
                "endDate": workshop.endDate,
                "accessStatus": enum_str(mine.status) if mine is not None else None,
                "accessLevel": enum_str(mine.accessLevel) if mine is not None else None,
                "restricted": workshop_is_curated(workshop_rows),
            }
        )
    return {"items": public_encode(items), "truncated": truncated}


@router.post("/access-requests", status_code=status.HTTP_201_CREATED)
async def request_workshop_access(
    payload: WorkshopAccessRequestIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Ask for access to one or more workshops in a single call.

    Multi-select because that is how the need arrives: a researcher joining a project wants the same
    access to a whole season of workshops, and filing them one at a time produces a queue nobody
    works through.

    Idempotent per workshop, because the "request access" button will be pressed twice:

    * ``ALREADY_GRANTED`` — access is already held, so the row is left completely alone. Re-requesting
      must never knock a working grant back to PENDING while an admin deliberates.
    * ``ALREADY_PENDING`` — a request is already in the queue; not duplicated, and the original
      timestamp and note are preserved so queue order reflects when they first asked.
    * ``RE_REQUESTED`` — a DENIED or REVOKED row may be asked for again, and that is a NEW request.
      ``assignedById`` IS cleared — the row's history as an admin grant ended when it was revoked, and
      leaving it set would let one approval silently re-close a workshop that had reopened (see
      ``workshop_access`` on what makes a workshop curated). ``decidedById`` / ``decidedAt`` /
      ``decisionNote`` are NOT: see the block comment on the update below.
    * ``CREATED`` — a fresh PENDING row.

    A PENDING row confers nothing. It cannot lock other people out of an open workshop either.
    """
    uid = get_value(current_user, "id")
    level = _level_or_422(payload.accessLevel, DEFAULT_GRANT_LEVEL)
    # Preserve the caller's order but drop blanks/duplicates, so asking twice in one body is not two rows.
    wanted: list[str] = list(dict.fromkeys(wid for wid in payload.workshopIds if wid))
    if not wanted:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Select at least one workshop"
        )
    found = {w.id for w in await db.workshop.find_many(where={"id": {"in": wanted}})}
    missing = [wid for wid in wanted if wid not in found]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Workshop(s) not found: {', '.join(missing)}",
        )
    # The whole multi-select is decided from ONE read of the caller's existing rows, then written as
    # at most one insert and one update. Asking for a season of twenty workshops previously cost two
    # cross-region round trips per workshop before the final read — the multi-select was slower than
    # filing them one at a time would have been.
    existing_rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}
    )
    by_workshop = {row.workshopId: row for row in existing_rows}

    outcomes: list[dict[str, str]] = []
    to_create: list[str] = []
    to_rerequest: list[str] = []
    for workshop_id in wanted:
        existing = by_workshop.get(workshop_id)
        if existing is None:
            to_create.append(workshop_id)
            outcomes.append({"workshopId": workshop_id, "outcome": "CREATED"})
            continue
        state = enum_str(existing.status)
        if state in {"GRANTED", "PENDING"}:
            outcomes.append(
                {
                    "workshopId": workshop_id,
                    "outcome": "ALREADY_GRANTED" if state == "GRANTED" else "ALREADY_PENDING",
                }
            )
            continue
        to_rerequest.append(existing.id)
        outcomes.append({"workshopId": workshop_id, "outcome": "RE_REQUESTED"})

    if to_create:
        await db.workshopassignment.create_many(
            data=[
                {
                    "workshopId": workshop_id,
                    "userId": uid,
                    "accessLevel": level,
                    "status": "PENDING",
                    "requestedById": uid,
                    "requestNote": payload.note,
                }
                for workshop_id in to_create
            ]
        )
    if to_rerequest:
        # ── THE PREVIOUS DECISION SURVIVES THE RE-REQUEST ────────────────────────────────────────
        # This update used to null ``decidedById``, ``decidedAt`` and ``decisionNote``. Those three
        # columns are the ONLY record a workshop-access decision has — there is no history table
        # beside WorkshopAssignment — so an admin's refusal and its reason were permanently destroyed
        # by the very person it was made about, simply by asking again. Three places in this codebase
        # promise the opposite: schema.prisma on ``WorkshopAssignment.status`` ("DENIED and REVOKED
        # are kept rather than deleted so the decision stays auditable"), the ``workshop_access``
        # module docstring, and docs/PERMISSIONS.md §"Workshop access". ``statusFilter=ALL`` on the
        # admin queue is documented as "the full history for auditing" and no longer had it.
        #
        # The clearing was DELIBERATE, and the worry behind it was real but is already answered by a
        # column we write two lines up: ``status`` is PENDING. Nobody can mistake a decision for THIS
        # request's outcome when this request has visibly not been decided — and the admin queue is
        # built that way already (``decidable = row.status === "PENDING"`` in
        # WorkshopAccessQueuePanel renders the decide form for a pending row and the "Decided by …"
        # line only for a settled one). What the admin gains is the thing that actually helps them
        # decide: "denied by Priya on the 3rd — asked again on the 9th".
        #
        # ``createdAt`` IS refreshed, and that is the second half of this fix rather than a nicety.
        # ``GET /workshops/access-requests`` orders ``createdAt: asc`` and calls itself "oldest
        # first"; an untouched ``createdAt`` on a row this route's own docstring calls "a NEW request"
        # sorted a re-request made this morning above requests that have genuinely been waiting a
        # week, so the queue mis-ranked exactly the rows carrying the least urgency. With the decision
        # columns kept, ``createdAt`` now also carries the meaning a ``reRequestedAt`` column would
        # have carried, at no schema cost: decided-then vs asked-again-now is readable off the pair.
        # If a dedicated ``reRequestedAt`` is ever added, this line is what it replaces — do not add
        # it and leave ``createdAt`` frozen, or the queue order regresses to the defect above.
        await db.workshopassignment.update_many(
            where={"id": {"in": to_rerequest}},
            data={
                "accessLevel": level,
                "status": "PENDING",
                "requestedById": uid,
                "requestNote": payload.note,
                "assignedById": None,
                "createdAt": datetime.now(UTC),
            },
        )
    # Re-read by (workshop, user) rather than by id: a bulk insert hands back no ids, and this is the
    # same set of rows the loop would have collected.
    rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}, include=REQUEST_INCLUDE
    )
    return {"outcomes": outcomes, "requests": public_encode(rows)}


@router.get("/access-requests/mine")
async def my_workshop_access(
    current_user: Any = Depends(get_current_user), statusFilter: str | None = None
) -> list[dict[str, Any]]:
    """Every workshop-access row belonging to the caller, across all workshops.

    Not just the pending ones: a user needs to see what they hold, what they are waiting on, and what
    was refused, in one place — that is the difference between "ask again" and "stop asking".
    """
    where: dict[str, Any] = {"userId": get_value(current_user, "id")}
    if statusFilter:
        where["status"] = _status_or_422(statusFilter, ("PENDING", "GRANTED", "DENIED", "REVOKED"))
    rows = await db.workshopassignment.find_many(
        where=where, include=REQUEST_INCLUDE, order={"updatedAt": "desc"}
    )
    return public_encode(rows)


@router.get("/access-requests")
async def list_workshop_access_requests(
    _: Any = Depends(require_admin), statusFilter: str = "PENDING"
) -> list[dict[str, Any]]:
    """The approval queue across ALL workshops (PENDING by default), oldest first.

    One place to work from. Opening each workshop's assignment screen in turn is how requests sit
    unanswered for a week, so the queue is cross-workshop and each row names its workshop.
    ``statusFilter=ALL`` widens it to the full history for auditing.
    """
    where: dict[str, Any] = {}
    if statusFilter and statusFilter.upper() != "ALL":
        where["status"] = _status_or_422(statusFilter, ("PENDING", "GRANTED", "DENIED", "REVOKED"))
    rows = await db.workshopassignment.find_many(
        where=where, include=REQUEST_INCLUDE, order={"createdAt": "asc"}
    )
    return public_encode(rows)


@router.post("/access-requests/{request_id}/decide")
async def decide_workshop_access_request(
    request_id: str, payload: WorkshopAccessDecisionIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Admin approves (GRANTED) or denies (DENIED) a pending request, optionally setting the level.

    Only a PENDING row can be decided here — changing an already-settled row is roster management, so
    it goes through ``PATCH /workshops/{id}/assignments/{userId}`` where the change is unambiguous.

    Approving deliberately does NOT set ``assignedById``: answering a request is not the same act as
    an admin choosing a roster, and treating it as one would turn the first approval on an open
    workshop into a silent lockout of everybody else. See ``services/workshop_access``.
    """
    decision = _status_or_422(payload.status, ("GRANTED", "DENIED"))
    row = await db.workshopassignment.find_unique(where={"id": request_id})
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Access request not found"
        )
    if enum_str(row.status) != "PENDING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only a PENDING request can be decided. Use the workshop's assignment endpoints to "
            "change an already-decided row.",
        )
    await db.workshopassignment.update(
        where={"id": row.id},
        data={
            "status": decision,
            "accessLevel": _level_or_422(payload.accessLevel, enum_str(row.accessLevel)),
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
            "decisionNote": payload.note,
        },
    )
    return await _hydrate_assignment(row.id)


@router.get("/{workshop_id}")
async def get_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    await hydrate_relations([workshop], RELATIONS)
    return public_encode(workshop)


@router.patch("/{workshop_id}")
async def update_workshop(
    workshop_id: str,
    payload: WorkshopUpdate,
    # Professor and above, the same floor as creating one. A workshop is the container the whole
    # submission window and assignment ladder hang off — moving its dates changes who is late — so
    # it is not a record anyone may contribute a field to. The workshop-access check below is a
    # SECOND, orthogonal gate (scoping, not rank): a professor still needs CONTRIBUTE on a curated
    # workshop, exactly as before.
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    data = clean_data(payload.model_dump(exclude_unset=True))
    # The precondition is a question, not a column — taken out of the body here, asked inside the
    # transaction below. See ``records.take_expected_updated_at``.
    expected_updated_at = take_expected_updated_at(data)
    artisan_ids = data.pop("artisanIds", None)
    craft_ids = data.pop("craftIds", None)
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    # The workshop's own row is a record IN that workshop, so the workshop's access levels govern
    # editing it. Resolved once and used for both decisions below.
    access = await resolve_workshop_access(current_user, workshop_id)
    # VIEW (or no access at all on a curated workshop) cannot edit. The creator is exempt: an admin
    # curating a roster that leaves the author off must not lock the author out of their own entry.
    if get_value(workshop, "createdById") != get_value(current_user, "id") and not access.at_least(
        "CONTRIBUTE"
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=access_denied_detail(access, "CONTRIBUTE")
        )
    # An EDIT-level assignee is a co-owner of this workshop's data and may change fields somebody
    # else populated, exactly as the creator or an admin can. guard_record_edit knows nothing about
    # workshops, so the elevation is applied here — and the revision is still written on that path,
    # so the shortcut never costs the edit audit.
    # ── ONE TRANSACTION FOR THE WHOLE SAVE (2026-09-03), AND IT CLOSES THREE THINGS AT ONCE ───────
    #
    # This route was the worst-affected write in the repository: up to FIVE independent commits for
    # one PATCH — the audit row, the workshop row, the artisan roster's delete and its insert, then
    # the craft roster's pair — with a failure window between every adjacent pair.
    #
    # 1. THE LEDGER. ``record_revision`` (and ``guard_record_edit``, which ends in it) committed
    #    before the update it describes, so a failure in the gap left an audit row asserting a change
    #    to dates or a sanction number that the row does not hold. ``client=tx`` is what joins it:
    #    ``db.tx()`` hands back a DIFFERENT client, so a callee writing through the module singleton
    #    is not inside this block however it reads. See ``access.record_revision``.
    # 2. THE ROSTERS. Each ``replace_*`` is a delete followed by an insert, and a failure between
    #    them committed the WIPE — forty artisans an administrator picked, gone, with the save
    #    reporting an error. See ``replace_workshop_artisans`` for the full argument and for the
    #    concurrent-save 500 the same transaction removes.
    # 3. THE REFUSAL ORDERING, WHICH WAS BACKWARDS AND IS THE ONE BEHAVIOUR CHANGE HERE. The two
    #    ``assert_can_contribute_relation`` calls below raise 403, and they sit AFTER the workshop
    #    row's update — so an ordinary contributor who edited the title and also tried to rewrite a
    #    populated roster was refused, correctly, having ALREADY had their title change committed
    #    (and, before this, an audit row for it too). That is precisely the defect
    #    ``processes.update_process`` moved its step guard upwards to avoid, and the rule it states
    #    out loud: a rejected request must leave no partial state behind. The guards are not moved —
    #    they need the link counts, and the counts are the truth this save is about to replace — the
    #    transaction simply takes the row update back with the refusal. The 403 itself, its detail
    #    string and the response shape are all unchanged; only the rollback is new.
    #
    # The two ``count`` reads go through ``tx`` because they must see this transaction's own writes:
    # on the module client they would be answering from outside it.
    async with db.tx() as tx:
        # Before the ledger write on either branch below — a refusal raised after ``record_revision``
        # or ``guard_record_edit`` would leave a committed claim about an edit that was then turned
        # down, which is point 1 of the paragraph above arrived at from the other side. ``None``
        # passes and changes nothing, which is every client shipped to date. See
        # ``records.assert_expected_updated_at``.
        assert_expected_updated_at(workshop, expected_updated_at)
        privileged = access.at_least("EDIT")
        if privileged:
            await record_revision(workshop, current_user, data, "workshop", client=tx)
        else:
            privileged = await guard_record_edit(
                workshop, current_user, data, "workshop", client=tx
            )
        await apply_status_policy_update(current_user, workshop, data)
        merge_field_provenance(data, current_user, previous=workshop)
        resubmit_status(workshop, current_user, data)
        updated = await tx.workshop.update(where={"id": workshop_id}, data=data)
        if artisan_ids is not None:
            link_count = await tx.workshopartisan.count(where={"workshopId": workshop_id})
            if not privileged:
                assert_can_contribute_relation(workshop, current_user, link_count > 0, "artisanIds")
            await replace_workshop_artisans(workshop_id, artisan_ids, client=tx)
        if craft_ids is not None:
            craft_link_count = await tx.workshopcraft.count(where={"workshopId": workshop_id})
            if not privileged:
                assert_can_contribute_relation(
                    workshop, current_user, craft_link_count > 0, "craftIds"
                )
            await replace_workshop_crafts(workshop_id, craft_ids, client=tx)
    # ``update`` already handed back the saved row, so the relations are grafted onto it instead of
    # reading the whole workshop a second time from another region.
    await hydrate_relations([updated], RELATIONS)
    return public_encode(updated)


@router.get("/{workshop_id}/submission-check")
async def workshop_submission_check(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """What submitting a record into this workshop would mean for the caller, BEFORE they submit.

    Lets a client confirm ("this workshop ended on <endDate> — your entry will need admin approval")
    instead of discovering it after the fact. ``canSubmit`` false means the workshop has assignments
    and the caller is not one of them, so a create would 403; ``needsAdminApproval`` true means the
    submission would be accepted but forced to PENDING until an admin approves it. Never 403s itself —
    it only reports.
    """
    check = await describe_workshop_submission(current_user, workshop_id)
    return check.payload()


@router.get("/{workshop_id}/assignments")
async def list_workshop_assignments(
    workshop_id: str, _: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    """Every assignment row on this workshop — granted, pending, denied and revoked alike.

    Still a flat list, because that is the shape both clients already consume. But it is no longer
    "the assigned researchers": a row now carries a ``status``, and only ``GRANTED`` means access.
    A caller building the assigned set MUST filter on ``status === "GRANTED"``; taking every
    ``userId`` would treat a pending request or a revoked researcher as a member.

    Admin-only, because it exposes request notes and decisions. The non-privileged question ("may I
    submit here?") is answered by ``/workshops/{id}/submission-check``, which is open to everyone.
    """
    await require_record(db.workshop, workshop_id)
    return await _assignment_list(workshop_id)


@router.put("/{workshop_id}/assignments")
async def set_workshop_assignments(
    workshop_id: str, payload: WorkshopAssignmentIn, current_user: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    """Admin sets the exact set of researchers assigned to this workshop (replaces the previous set).

    Kept whole-set for backwards compatibility — the Android app and the web assignment dialog both
    send ``{"userIds": [...]}`` and expect the roster to become exactly that. What changed underneath:

    * everyone in the set gets ``status = GRANTED`` and an ``assignedById``, which is what marks this
      workshop as deliberately curated;
    * a level is honoured if the caller sends one, otherwise an existing row keeps the level it
      already had (so re-saving the dialog cannot silently demote an EDIT assignee to CONTRIBUTE) and
      a new row gets CONTRIBUTE — the level this endpoint has always effectively granted;
    * everyone dropped from the set is REVOKED rather than deleted, so "X had access until Y removed
      them on Z" survives. Sending an EMPTY set therefore revokes everybody, which — with no GRANTED
      admin row left — reopens the workshop to all, exactly as clearing the list always did.

    Deliberately carries NO date guard: an admin or master admin may grant someone access to a
    workshop at any time, including long after it ended — that is how post-workshop access is given.
    The newly assigned user can then submit, with any out-of-window entry flagged for admin approval
    (see ``/workshops/{id}/submission-check`` and ``services/workshop_access``).
    """
    await require_record(db.workshop, workshop_id)
    requested_level = _level_or_422(payload.accessLevel, None)
    wanted = {uid for uid in payload.userIds if uid}
    existing = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    by_user = {r.userId: r for r in existing}
    now = datetime.now(UTC)
    admin_id = get_value(current_user, "id")

    # The roster is rewritten in a fixed handful of statements, not one per researcher. Every row
    # being revoked takes the same values, and so does every row being created; the rows being
    # re-granted differ only in the level they end up at, so they are grouped by that level. A
    # thirty-person roster used to cost thirty-one sequential cross-region round trips to save.
    revoke_ids = [
        row.id
        for uid, row in by_user.items()
        if uid not in wanted and enum_str(row.status) != "REVOKED"
    ]
    create_rows = [uid for uid in wanted if uid not in by_user]
    grant_ids_by_level: dict[str, list[str]] = {}
    for uid in wanted:
        row = by_user.get(uid)
        if row is None:
            continue
        level = requested_level or enum_str(row.accessLevel) or DEFAULT_GRANT_LEVEL
        grant_ids_by_level.setdefault(level, []).append(row.id)

    if revoke_ids:
        await db.workshopassignment.update_many(
            where={"id": {"in": revoke_ids}},
            data={
                "status": "REVOKED",
                "decidedById": admin_id,
                "decidedAt": now,
                "decisionNote": "Removed from the workshop roster.",
            },
        )
    if create_rows:
        await db.workshopassignment.create_many(
            data=[
                {
                    "workshopId": workshop_id,
                    "userId": uid,
                    "assignedById": admin_id,
                    "accessLevel": requested_level or DEFAULT_GRANT_LEVEL,
                    "status": "GRANTED",
                }
                for uid in create_rows
            ]
        )
    for level, ids in grant_ids_by_level.items():
        await db.workshopassignment.update_many(
            where={"id": {"in": ids}},
            data={
                "assignedById": admin_id,
                "accessLevel": level,
                "status": "GRANTED",
                "decidedById": admin_id,
                "decidedAt": now,
            },
        )
    return await _assignment_list(workshop_id)


@router.post("/{workshop_id}/assignments", status_code=status.HTTP_201_CREATED)
async def grant_workshop_assignment(
    workshop_id: str, payload: WorkshopGrantIn, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Admin grants ONE user access at a level, without disturbing the rest of the roster.

    An upsert on the (workshop, user) pair — the row is unique — so re-granting somebody who was
    REVOKED or DENIED flips them straight back to GRANTED at the requested level rather than failing
    on the unique constraint or stacking a second row. The previous decision note is replaced by this
    grant's, because the row now describes the current state; the audit of who did what and when
    lives in the timestamps and the ``assignedBy``/``decidedBy`` links.
    """
    await require_record(db.workshop, workshop_id)
    user = await db.user.find_unique(where={"id": payload.userId})
    if user is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    admin_id = get_value(current_user, "id")
    now = datetime.now(UTC)
    existing = await db.workshopassignment.find_unique(
        where={"workshopId_userId": {"workshopId": workshop_id, "userId": payload.userId}}
    )
    level = _level_or_422(
        payload.accessLevel,
        enum_str(existing.accessLevel) if existing is not None else DEFAULT_GRANT_LEVEL,
    )
    data: dict[str, Any] = {
        "accessLevel": level,
        "status": "GRANTED",
        "assignedById": admin_id,
        "decidedById": admin_id,
        "decidedAt": now,
        "decisionNote": payload.note,
    }
    if existing is None:
        created = await db.workshopassignment.create(
            data={**data, "workshopId": workshop_id, "userId": payload.userId}
        )
        return await _hydrate_assignment(created.id)
    await db.workshopassignment.update(where={"id": existing.id}, data=data)
    return await _hydrate_assignment(existing.id)


@router.patch("/{workshop_id}/assignments/{user_id}")
async def update_workshop_assignment(
    workshop_id: str,
    user_id: str,
    payload: WorkshopAssignmentUpdateIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin changes one assignment: raise/lower its level, and/or set GRANTED, DENIED or REVOKED.

    This is the per-workshop twin of ``/access-requests/{id}/decide`` and, unlike it, works on a row
    in ANY state — an admin looking at one workshop's roster should not have to care whether the row
    started as a grant or as a request.

    Like ``decide``, granting here records ``decidedById`` but does not touch ``assignedById``: if the
    row arrived as somebody's request, approving it from this screen is still an answer to that
    request, not the act of curating a roster. Use PUT or POST to actually put someone on the roster.
    """
    await require_record(db.workshop, workshop_id)
    row = await _assignment_or_404(workshop_id, user_id)
    data: dict[str, Any] = {}
    level = _level_or_422(payload.accessLevel, None)
    if level is not None:
        data["accessLevel"] = level
    if payload.status is not None:
        data["status"] = _status_or_422(payload.status, WORKSHOP_DECISIONS)
    if not data:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Send accessLevel, status, or both"
        )
    # Any admin touch is a decision worth stamping — including a bare level change, which is exactly
    # the edit somebody will later need to explain.
    data["decidedById"] = get_value(current_user, "id")
    data["decidedAt"] = datetime.now(UTC)
    if payload.note is not None:
        data["decisionNote"] = payload.note
    await db.workshopassignment.update(where={"id": row.id}, data=data)
    return await _hydrate_assignment(row.id)


@router.delete("/{workshop_id}/assignments/{user_id}")
async def revoke_workshop_assignment(
    workshop_id: str, user_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Revoke one user's access. The row is set to REVOKED, NOT deleted, and is returned.

    Deleting would lose the only evidence that access was ever held or withdrawn, and the row is the
    audit trail: who granted it, who asked for it, who took it away and when. It also matters
    operationally — a deleted row is indistinguishable from "never asked", so a user could
    re-request and quietly land back where they were with no sign a decision had been reversed.

    Returns the revoked row rather than a bare 204 so the caller can render the new state (and the
    decision stamp) without a follow-up fetch.
    """
    await require_record(db.workshop, workshop_id)
    row = await _assignment_or_404(workshop_id, user_id)
    await db.workshopassignment.update(
        where={"id": row.id},
        data={
            "status": "REVOKED",
            "decidedById": get_value(current_user, "id"),
            "decidedAt": datetime.now(UTC),
            "decisionNote": "Access revoked.",
        },
    )
    return await _hydrate_assignment(row.id)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop(workshop_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.workshop, workshop_id)
    await db.workshop.delete(where={"id": workshop_id})
