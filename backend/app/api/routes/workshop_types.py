"""``/api/workshop-types`` — the administrator-managed "Type of workshop" list.

WHAT THIS IS, IN ONE PARAGRAPH. Every record form in this product (artisan, product, process, tool,
craft, questionnaire) is about to carry exactly TWO workshop controls: "Type of workshop", filled
from this endpoint, and "Workshop", filled from whichever table the chosen type points at. This
router owns the first list and the flag that decides the second. It owns nothing else — no record is
written here, no workshop is created here, and the type a researcher picks is not stored on the
record they save.

── THE PERMISSION SPLIT, AND IT IS THE ONE `crafts.py` USES ───────────────────────────────────────

READ is ``get_current_user``: every signed-in account needs this list, because every record form
draws it. WRITE is ``require_admin`` — MASTER_ADMIN and ADMIN, the same helper and the same set as
``/admin`` and every other admin-only surface — because this is a shared vocabulary and renaming a
type renames it under everybody. That asymmetry is not an oversight in either direction: a list
nobody could read would be a dropdown with no members, and a list anybody could write would let a
researcher rename the category another researcher's fieldwork is filed under.

The gate is HERE, on the route, and not only on the admin screen. A designer who types
``/admin/workshop-types`` meets ``AppShell``'s lock panel; a designer who types
``POST /api/workshop-types`` meets a 403 from ``require_admin``. The second one is the boundary; the
first is a courtesy.

── WHY DELETING IS REFUSED RATHER THAN CASCADED, AND WHAT IS OFFERED INSTEAD ──────────────────────

**THERE IS NO FOREIGN KEY ONTO ``WorkshopTypeOption``**, by design — a record stores the WORKSHOP it
belongs to and the workshop already knows its own type, so a second copy of the type on the record
would be a second copy that can disagree. The cost of that decision lands exactly here: Postgres
cannot refuse this DELETE for us, because from its point of view nothing references the row.

So the refusal is written by hand, and it is a REFUSAL rather than a cascade or a silent success:

  * A type with nothing filed under it is deleted, 204, no ceremony. This is the ordinary case for a
    type an administrator added by mistake ten seconds ago, and making that a two-step retirement
    would leave the list carrying somebody's typo forever.
  * A type with workshops under it is refused 409, and the sentence NAMES HOW MANY and names the
    remedy. Deactivating (``PATCH {"isActive": false}``) takes the type out of every picker while
    leaving every workshop filed under it exactly where it is, with its token still resolving to a
    label — which is the whole point, because the token is stored in `DesignWorkshop.workshopKind`,
    in `AnnualPlanEntry.workshopKind`, inside stage documents and in every export ever taken, and
    none of those is reachable from here.

The alternative — delete the row and let the workshops keep a token nothing can resolve — is the
failure this repository has shipped often enough to have a name for it: the workshop would render
with no type at all, indistinguishable from a workshop whose type was never answered, and the only
record that the category ever existed would be in an export somebody took last year.

── EDITING A LABEL IS ALWAYS SAFE, AND THE SCHEMA IS WHAT GUARANTEES IT ───────────────────────────

``WorkshopTypeOptionUpdate`` does not carry ``key`` at all, and ``APIModel`` is ``extra="forbid"``,
so a PATCH that tries to move a key is answered 422 rather than quietly ignored. See that module's
header for the four places the token is already written and why none of them is rewritable from
here. A label edit touches one column and can never be anything else.
"""

from __future__ import annotations

import logging
from typing import Any, NamedTuple

from fastapi import APIRouter, Depends, HTTPException, Query, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import get_current_user, require_admin
from app.schemas.workshop_types import (
    WorkshopTypeOptionCreate,
    WorkshopTypeOptionReorder,
    WorkshopTypeOptionUpdate,
)
from app.services.records import public_encode

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/workshop-types", tags=["workshop-types"])

#: The order every reader sees, and it is TOTAL. ``sortOrder`` is not unique (the model comment says
#: why a unique ordinal is refused), so ``key`` breaks the tie — without it two identical requests
#: could legitimately return two different lists, and a dropdown that reshuffles between page loads
#: is one a researcher stops trusting.
_ORDER: list[dict[str, str]] = [{"sortOrder": "asc"}, {"key": "asc"}]


class Noun(NamedTuple):
    """What a count is counting, in both numbers, as the refusal will print it.

    BOTH SPELLINGS ARE DECLARED AND NEITHER IS DERIVED. English pluralises the HEAD of a noun
    phrase, not its end: "design workshop" takes an "s" and "planned workshop in the annual
    directory" does not — "planned workshopS in the annual directory" is correct and
    "planned workshop in the annual directorys" is what a bare rule produces. A refusal an
    administrator reads at the moment a delete fails is the last place to print that.
    """

    one: str
    many: str


#: WHAT COUNTS AS "IN USE", AS ``(prisma model attribute, column, :class:`Noun`, extra where)``.
#:
#: THE FOURTH MEMBER IS WHAT "STILL COUNTS" MEANS FOR THAT TABLE, declared beside the table rather
#: than as a branch inside :func:`_count_use` on the model's name. The two rows disagree about it and
#: the disagreement is deliberate:
#:
#:   * ``DesignWorkshop`` narrows on ``deletedAt: None``. A workshop in the trash is not in use.
#:     Nothing here is ever hard-deleted and every read in the product filters that column, so
#:     counting the trash would refuse the delete of a type whose only workshops are in the bin — and
#:     nothing would be able to tell the administrator WHICH, because the only screen that lists them
#:     is the admin hub's recovery card.
#:   * ``AnnualPlanEntry`` narrows on nothing. Its retirement column is ``withdrawnAt``, and a
#:     WITHDRAWN plan row is still counted on purpose: the directory keeps it on screen, still
#:     rendering its kind, precisely because the plan is the record of what WAS intended.
#:
#: The shape — and the reason a missing model answers ``None`` rather than raising — is
#: ``routes/users.py::_NAMED_ON_RELATIONS`` and ``_count_relation``, whose docstrings argue it at
#: length and are worth reading before touching this: a generated Prisma client that has drifted
#: behind ``schema.prisma`` is the DEFAULT outcome of running ``prisma generate`` on Windows (it
#: prints ``Error: spawn prisma-client-py ENOENT`` and exits 0), so code downstream of it cannot
#: treat the client's contents as a guarantee. Here the consequence is milder than it is there —
#: this router's refusal is a decision rather than a decoration on one Postgres has already made —
#: which is exactly why the drift must not be allowed to turn it into a 500. An UNCOUNTABLE relation
#: refuses the delete too, and says it could not count; see :func:`_usage_tally`.
#:
#: ``AnnualPlanEntry`` IS ON THIS LIST AND IS DELIBERATELY NOT CALLED A WORKSHOP. ``api/router.py``
#: is emphatic that a row on ``/annual-plan`` "is a line in a document and not a workshop", and
#: counting plan rows into a workshop total would be the exact confusion that file exists to stop.
#: It is here because it stores the same token in the same spelling, so deleting a type would leave
#: the ministry's directory rendering a kind nothing can resolve — the same failure, on a different
#: screen — and it is reported under its own noun so an administrator can see which is which.
_USES: tuple[tuple[str, str, Noun, dict[str, Any]], ...] = (
    (
        "designworkshop",
        "workshopKind",
        Noun("design workshop", "design workshops"),
        {"deletedAt": None},
    ),
    (
        "annualplanentry",
        "workshopKind",
        Noun(
            "planned workshop in the annual directory",
            "planned workshops in the annual directory",
        ),
        {},
    ),
)


def _payload(row: Any) -> dict[str, Any]:
    """One row, as every client reads it.

    ``public_encode`` rather than a hand-built dict, for the reason every other router uses it: it is
    the one function that knows what must never leave this API. Nothing on this table is sensitive
    today, and a payload built by hand here is a payload that would not be scrubbed on the day
    somebody adds a column that is.
    """
    return public_encode(row)


async def _count_use(
    client: Any, model: str, column: str, key: str, extra: dict[str, Any]
) -> int | None:
    """How many rows of ``model`` are filed under ``key``, or ``None`` if the count could not be run.

    ``client`` IS AN ARGUMENT AND NOT THE MODULE SINGLETON, and that is not decoration. ``db.tx()``
    hands back a DIFFERENT client, so a callee reading through ``db`` is NOT inside the caller's
    transaction however it looks — ``crafts.py::update_craft`` writes that warning out in full where
    it passes ``client=tx``. The count that decides this delete has to run in the same transaction as
    the delete, so the client travels down rather than being reached for.

    ``None`` RATHER THAN ``0``, and the distinction reaches the administrator's screen. A relation
    that could not be read is not a relation with no rows; answering 0 would let this router delete a
    type that twelve workshops are filed under, because a migration had not been applied to the
    database serving the request. ``users.py::_count_relation`` argues the same distinction for a
    message; here it decides whether a row is destroyed, so the safe direction is the opposite of
    that one's — see :func:`_usage_tally`.
    """
    delegate = getattr(client, model, None)
    if delegate is None:
        logger.error(
            "workshop_types: the generated Prisma client carries no model %r, so a delete of a "
            "workshop type cannot check whether %s.%s still uses it. The client is behind "
            "schema.prisma — regenerate it with backend/scripts/regenerate-client.md, NOT with a "
            "bare `prisma generate`, which exits 0 without doing anything on Windows. Refusing the "
            "delete rather than performing one that cannot be checked; see _count_use.",
            model,
            model,
            column,
        )
        return None
    try:
        # ``extra`` is declared beside its table in :data:`_USES`, which is where the argument for
        # each narrowing lives. It is spread AFTER the key so a malformed entry can never shadow the
        # thing being counted.
        return await delegate.count(where={column: key, **extra})
    except Exception:
        # Deliberately every exception and not a named Prisma error, for the reason
        # ``users.py::_count_relation`` gives: the set of ways a count can fail — a migration not
        # applied to this database, a column applied in part, the pool exhausted, the connection
        # dropped — is not a set this file can enumerate correctly and has no business trying to.
        # ``CancelledError`` derives from ``BaseException``, so a cancelled request still cancels.
        logger.exception(
            "workshop_types: counting %s.%s for workshop type key %r failed, so a delete cannot be "
            "checked. Usually a migration that has not been applied to this database. Refusing the "
            "delete; see _count_use.",
            model,
            column,
            key,
        )
        return None


async def _usage_tally(client: Any, key: str) -> tuple[list[tuple[Noun, int]], list[Noun]]:
    """``([(noun, count)], [uncountable noun])`` for one key — what stands in the way of a delete.

    TWO LISTS RATHER THAN ONE, because they say different things and an administrator must not be
    able to confuse them: the first is "these are in the way, this many of them", the second is "and
    I could not look here at all". Collapsing an unreadable relation into a zero would be a lie of
    exactly the kind this function exists to stop telling — and, unlike the tally in ``users.py``
    which decorates a refusal Postgres has already made, THIS one decides whether rows are destroyed.
    So an uncountable relation refuses the delete rather than being skipped.
    """
    named: list[tuple[Noun, int]] = []
    uncounted: list[Noun] = []
    for model, column, noun, extra in _USES:
        count = await _count_use(client, model, column, key, extra)
        if count is None:
            uncounted.append(noun)
        elif count > 0:
            named.append((noun, count))
    named.sort(key=lambda pair: pair[1], reverse=True)
    return named, uncounted


#: The remedy, and the half of the refusal that decides whether an administrator gets what they
#: actually wanted. One constant because all three branches of :func:`_in_use_message` end with it.
_DEACTIVATE_INSTEAD = (
    " Deactivate it instead — it disappears from every “Type of workshop” dropdown and every "
    "workshop already filed under it keeps its type and its label."
)


def _in_use_message(label: str, named: list[tuple[Noun, int]], uncounted: list[Noun]) -> str:
    """The 409, worded for the administrator who pressed Delete and has to decide what to do next.

    IT NAMES THE NUMBER, THE THING COUNTED AND THE REMEDY, in that order, because those are the three
    things missing from "Cannot delete: in use". The remedy is the sentence that matters most: an
    administrator who is told only that they may not delete something will try again tomorrow, and
    "deactivate it instead" is an action available on the same screen that does what they actually
    wanted — the type disappears from every dropdown — without touching a single workshop.

    THREE BRANCHES, BECAUSE "I COUNTED SOME" AND "I COULD NOT LOOK" ARE DIFFERENT SENTENCES AND THE
    SECOND ONE ALONE HAS TO STAND UP ON ITS OWN. Gluing the uncounted clause onto the end of a
    comma-list is how a refusal comes to read "“Other” cannot be deleted: and this server could not
    check … still filed under it" — a sentence that is about nothing and is the ONLY thing on screen
    at the moment an administrator most needs to understand what happened.

    THE PLURAL COMES FROM :data:`_USES` AND IS NOT DERIVED. A bare "s" is right for "design
    workshop" and wrong for "planned workshop in the annual directory" — English pluralises the HEAD
    of a noun phrase, not its end — so the two spellings are declared beside the table and this
    function only chooses between them.
    """
    if named:
        counted = ", ".join(
            f"{count} {noun.one if count == 1 else noun.many}" for noun, count in named
        )
        sentence = f"“{label}” cannot be deleted: {counted} still filed under it."
        if uncounted:
            # Worded as a fact about this SERVER rather than about the data, so nobody reads it as
            # "there might be some": the count did not run, and that is a deployment problem with a
            # log line behind it.
            sentence += (
                " This server could also not check "
                + ", ".join(noun.many for noun in uncounted)
                + ", so there may be more."
            )
        return sentence + _DEACTIVATE_INSTEAD
    return (
        f"“{label}” cannot be deleted: this server could not check whether any "
        + ", ".join(noun.many for noun in uncounted)
        + " are filed under it, so the delete was refused rather than performed unchecked. The "
        "cause is on this server rather than in the data — usually a migration that has not been "
        "applied here, or a Prisma client behind schema.prisma — and it is in the logs."
        + _DEACTIVATE_INSTEAD
    )


async def _require_type(type_id: str) -> Any:
    row = await db.workshoptypeoption.find_unique(where={"id": type_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop type not found")
    return row


@router.get("")
async def list_workshop_types(
    _: Any = Depends(get_current_user),
    # DEFAULT FALSE, so the picker gets the list it should offer without asking for it. A retired
    # type must not appear in a dropdown — that is the whole meaning of retiring one — and a default
    # that included them would put the decision in every call site instead of in one.
    includeInactive: bool = Query(False),
) -> list[dict[str, Any]]:
    """Every type a form may offer, in the administrator's order.

    NOT PAGINATED, AND THAT IS A DECISION. There are six rows; the screen that manages them and the
    dropdown that consumes them both want all of them, and a picker that could only see page one of
    its own vocabulary is the silent-truncation failure this repository keeps rediscovering. The
    ``@@index([isActive, sortOrder])`` serves this read exactly.

    SIGNED-IN AND OTHERWISE UNGATED for the same reason ``/reference`` is: what a form may offer is
    not per-user and is not sensitive, and a list a researcher could not read is a dropdown with no
    members. Writing is ``require_admin``; see the routes below.
    """
    where = {} if includeInactive else {"isActive": True}
    rows = await db.workshoptypeoption.find_many(where=where, order=_ORDER)
    return [_payload(row) for row in rows]


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop_type(
    payload: WorkshopTypeOptionCreate, _: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Mint a type. The one request that decides a key.

    THE 409 IS THE UNIQUE INDEX ON ``key``, CONVERTED IN PLACE. Without it the driver's
    ``UniqueViolationError`` reaches the catch-all in ``main.py``, which logs a stack trace and
    answers 500 with "Something went wrong on the server" — telling an administrator the server is
    broken when the truth is that the token is taken and retrying cannot help. ``crafts.py`` converts
    the identical collision the identical way and its comment argues it at length.
    """
    try:
        created = await db.workshoptypeoption.create(data=payload.model_dump())
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"A workshop type with the key {payload.key} already exists. Keys are permanent "
                "because other tables store them, so edit that type's label rather than adding a "
                "second type under the same key."
            ),
        ) from exc
    return _payload(created)


@router.patch("/reorder")
async def reorder_workshop_types(
    payload: WorkshopTypeOptionReorder, _: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    """Set the order of the list in ONE request.

    ⚠ DECLARED BEFORE EVERY ``/{type_id}`` ROUTE, AND THAT POSITION IS LOAD-BEARING. FastAPI matches
    in registration order, and ``PATCH /{type_id}`` matches ``/reorder`` perfectly well — with
    ``type_id="reorder"``, which would answer 404 "Workshop type not found" on a server where this
    route exists. It is the same hazard ``api/router.py`` records for ``/design-workshops`` and its
    literal paths, one level down. Do not move this below the parameterised routes.

    ONE TRANSACTION, because a reorder that half-applied would leave the list in an order nobody
    chose — and, unlike a half-applied label edit, there is nothing on screen to say it happened.
    Positions are ``(index + 1) * 10``, leaving gaps so that a later insert-between does not have to
    renumber the world.

    AN UNKNOWN ID IS REFUSED, NOT SKIPPED. Skipping would renumber the rows around it and answer 200,
    so the administrator would see an order they did not ask for with nothing to say why.
    """
    rows = await db.workshoptypeoption.find_many()
    known = {row.id for row in rows}
    missing = [type_id for type_id in payload.ids if type_id not in known]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "Cannot reorder: this server has no workshop type with "
                + ("id " if len(missing) == 1 else "ids ")
                + ", ".join(missing)
                + ". Reload the list — somebody else may have removed one."
            ),
        )
    if len(set(payload.ids)) != len(payload.ids):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot reorder: the same workshop type is named twice in one request.",
        )
    async with db.tx() as tx:
        for index, type_id in enumerate(payload.ids):
            await tx.workshoptypeoption.update(
                where={"id": type_id}, data={"sortOrder": (index + 1) * 10}
            )
    updated = await db.workshoptypeoption.find_many(order=_ORDER)
    return [_payload(row) for row in updated]


@router.patch("/{type_id}")
async def update_workshop_type(
    type_id: str, payload: WorkshopTypeOptionUpdate, _: Any = Depends(require_admin)
) -> dict[str, Any]:
    """Correct a type: its label, its position, whether it is offered, where it routes.

    **NEVER ITS KEY.** ``WorkshopTypeOptionUpdate`` does not carry the field and ``APIModel`` forbids
    extras, so a client that tries is answered 422 naming it. That is the guarantee the whole screen
    rests on — see the schema module's header for the four places the token is already stored.

    ``exclude_unset=True`` IS THE PRECONDITION OF THAT GUARANTEE and not a stylistic choice: it is
    what makes a present key mean "the caller sent this". Drop it and every optional the client left
    alone would arrive as ``None`` and be written as an explicit NULL over stored data — which on
    this model means a label edit would blank the routing flag.
    """
    row = await _require_type(type_id)
    data = payload.model_dump(exclude_unset=True)
    # An empty PATCH is a no-op, not an error: a form that sends only what changed legitimately sends
    # nothing when nothing changed, and answering 422 for it would make every client diff twice. The
    # row already read for the 404 is the answer — a second read to say the same thing would be a
    # round trip spent proving nothing happened.
    if not data:
        return _payload(row)
    updated = await db.workshoptypeoption.update(where={"id": type_id}, data=data)
    return _payload(updated)


@router.delete("/{type_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop_type(type_id: str, _: Any = Depends(require_admin)) -> None:
    """Delete a type that nothing is filed under. Refuse, with a count, when something is.

    See this module's header for the argument. The short version: there is no foreign key onto this
    table, so Postgres cannot make this refusal for us, and a delete that went through would leave
    every workshop filed under the key rendering a type nothing can resolve — indistinguishable from
    a workshop whose type was never answered.

    THE COUNT IS TAKEN INSIDE THE TRANSACTION THAT DELETES, so a workshop filed under this type
    between the count and the delete cannot slip through the gap. The count is two indexed reads; the
    gap it closes is small, and the cost of leaving it open is a type deleted out from under a
    workshop somebody was saving. Raising from inside the block rolls it back, which is the same
    arrangement ``crafts.py::update_craft`` uses to answer its 409 without leaving a half-written
    audit row behind.
    """
    row = await _require_type(type_id)
    async with db.tx() as tx:
        named, uncounted = await _usage_tally(tx, row.key)
        if named or uncounted:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=_in_use_message(row.label, named, uncounted),
            )
        await tx.workshoptypeoption.delete(where={"id": type_id})
