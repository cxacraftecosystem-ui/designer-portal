"""THE ADMIN SURFACE OVER THE PLATFORM ALLOW-LIST: the roster, the pending queue, the decision.

Five endpoints, all Admin-and-above, all under ``/api/access``:

* ``GET  /access/roster``                — the whole list, filterable by status, paged.
* ``GET  /access/roster/pending-count``  — **the notification.** One integer, cheap enough to sit on
  every admin screen's first paint.
* ``POST /access/roster``                — admit an address by hand, before it has an account.
* ``POST /access/roster/{id}/decision``  — approve or reject a request.
* ``PATCH /access/roster/{id}``          — correct the admin-typed columns.
* ``DELETE /access/roster/{id}``         — suspend. Never deletes; see below.

**WHY THE NOTIFICATION IS A COUNT.** The requirement asks that admins and master admins be told
when somebody is turned away so they can approve or reject them. Neither application has an email
sender, a push transport, or a job runner to drive one — so an out-of-band notification would have
to be built from nothing, and a half-built one that silently stops sending is worse than none. What
both applications DO have is admins who open a screen. The queue is a section on the roster and the
count is a number the surfaces they already open can render, which is a notification that cannot
fail to arrive because it is not sent.

**DELETE IS A SUSPENSION**, exactly as it is on the designer roster and for the same reason: the row
is the RECORD that this address was admitted, and it outlives the access. Deleting it would also
delete the joining date, the attempt history and the name of the admin who approved them — and,
because the gate treats a missing row as PENDING, would silently put the person back in the queue
they were removed from.

**NOTHING HERE MAY BE THE ONLY WAY BACK IN.** These endpoints are how a locked-out institution is
unlocked, which is why the master admin is exempt from the gate in ``auth.py`` rather than by a row
in this table. An exemption stored in the table the gate reads is not an exemption; it is one bad
UPDATE away from an outage nobody inside the product can fix.

**THREE THINGS IN THIS REPOSITORY ARE CALLED "ACCESS" AND THEY ARE NOT THE SAME SUBJECT.**
``routes/data_access.py`` and ``services/access.py`` are researcher-to-researcher data sharing;
``schemas/access.py`` holds the bodies for that and for workshop access. This module, its schemas in
``schemas/access_roster.py`` and its service in ``services/access_roster.py`` are the sign-in
allow-list, and everything of theirs carries the ``_roster`` suffix so an import cannot land in the
wrong one by accident. Keep the suffix.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

# THE ONE IMPLEMENTATION OF "WHICH ROLES MAY THIS PERSON HAND OUT", imported rather than copied.
# Approving a request AT a tier and creating an account at that tier are the same grant through two
# doors, and a second copy of the rule is a door that disagrees: an admin who cannot create a master
# admin through /users must not be able to mint one by approving a pending request. The import
# direction (route module to route module) is unusual in this codebase and is accepted here for that
# reason — if it ever needs to go the other way, move the function to app/core/deps.py rather than
# duplicating it.
from app.api.routes.users import assert_role
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    invalidate_cached_user,
    require_access_manager,
    role_rank,
    role_value,
)
from app.schemas.access_roster import AccessDecision, AccessRosterCreate, AccessRosterUpdate
from app.services import access_roster
from app.services.access_roster import access_payload, normalise_email
from app.services.designers import canonical_email, email_match_keys, ensure_empanelled
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import enum_filter_list_or_422
from app.services.records import add_date_range, contains, count_and_page, enum_filter_or_422

router = APIRouter(prefix="/access", tags=["access"])

#: The statuses a caller may filter on, spelled once. An unknown value is a 422 rather than an empty
#: list, because "no rows matched" and "you typed the status wrong" look identical on a screen and
#: only one of them is the admin's fault. A ``frozenset`` (not the tuple this used to be) because it
#: is now fed straight to :func:`enum_filter_list_or_422`, whose ``allowed`` parameter is one.
FILTERABLE = frozenset(
    {access_roster.ACTIVE, access_roster.PENDING, access_roster.REJECTED, access_roster.SUSPENDED}
)

#: The eight-tier role ladder plus the reserved ``default`` token, which a caller ticks to mean
#: "rows admitted at the platform default" — ``AccessRoster.admitRole IS NULL``
#: (``prisma/schema.prisma:4255-4263``: NULL is documented there as meaning
#: ``DEFAULT_SIGNUP_ROLE``, the lowest rung, not "unset"). BUILT FROM ``ROLE_RANK`` RATHER THAN
#: WRITTEN OUT A SECOND TIME, so a tier added to or removed from the ladder changes this filter's
#: vocabulary for free. A hand-copied tuple is exactly the kind of second copy that has already
#: drifted once in this file family — see the comment on ``ROLE_RANK`` itself in ``core/deps.py``
#: about the tier count that stayed wrong in two other documents for as long as DESIGNER existed.
#: Pinned against drift by ``tests/test_role_ladder_parity.py``.
ACCESS_ROLE_FILTER_TOKENS = frozenset(ROLE_RANK) | {"default"}

#: ``dateField`` -> the column it selects. ONE RANGE PER REQUEST, NOT FIVE (DROPDOWN_DESIGN §4.1,
#: "One date range per request, not five"): a control that let a caller stack all five ranges at
#: once would need five indexes apiece instead of the one each that
#: ``20260829120000_roster_filter_indexes`` actually added, over a combination of filters nobody has
#: asked for. A caller wanting a different column sends a different request; ``dateField`` absent
#: means ``dateFrom``/``dateTo`` do nothing at all, never a range on whichever column was filtered
#: last time.
ACCESS_DATE_COLUMNS = {
    "added": "createdAt",
    "requested": "requestedAt",
    "decided": "decidedAt",
    "joined": "joinedAt",
    "firstSeen": "firstSeenAt",
}

#: ``sort`` -> the column it orders by. Kept beside :data:`ACCESS_SORT_DEFAULT_DIR`, which is the
#: OTHER half of the same table in DROPDOWN_DESIGN §4.3, rather than merging the two into one dict
#: of tuples: every existing caller of a "sort token -> column" map in this codebase (there was none
#: before this route; this is the first) would have had to learn a new shape, and the two questions
#: — "which column" and "which way does it point when the caller does not say" — are answered by two
#: different call sites below and read better apart.
ACCESS_SORT_COLUMNS = {
    "added": "createdAt",
    "email": "email",
    "name": "fullName",
    "standing": "status",
    "joined": "joinedAt",
    "requested": "requestedAt",
    "decided": "decidedAt",
    "firstSeen": "firstSeenAt",
    "attempts": "attemptCount",
}

#: The direction each ``sort`` token points when the caller names ``sort`` and says nothing about
#: ``dir``. NOT A SINGLE GLOBAL DEFAULT, AND THAT IS THE CONTRACT, NOT AN INCONSISTENCY
#: (DROPDOWN_DESIGN §4.3's own table). ``firstSeen`` defaults to ``desc`` so that an OUTSTANDING
#: invitation — ``firstSeenAt IS NULL``, which Postgres sorts first on ``desc`` — floats to the top
#: by default: exactly what Android's now-deleted device-side sort did on purpose ("An admin opens
#: this screen to answer who have I added who has not turned up"), now true on every page rather
#: than only the first. ``requested`` ALSO defaults to ``desc`` — newest request first — precisely
#: so the oldest-first queue view an admin actually works from is something they ask for with
#: ``dir=asc`` rather than something they get by accident and cannot turn off. ``email``, ``name``
#: and ``standing`` default to ``asc`` because there is no "newest" reading of an address, a typed
#: name, or an enum member.
ACCESS_SORT_DEFAULT_DIR = {
    "added": "desc",
    "email": "asc",
    "name": "asc",
    "standing": "asc",
    "joined": "desc",
    "requested": "desc",
    "decided": "desc",
    "firstSeen": "desc",
    "attempts": "desc",
}

#: What ``sort`` means when the caller does not send it at all. This is exactly what this route
#: already did before it could be named — ``createdAt desc`` was hardcoded into the one order the
#: old hand-rolled query ever built — so a caller that never sends ``sort`` sees no change at all;
#: naming it is what lets a caller ask for one of the other eight instead.
ACCESS_DEFAULT_SORT = "added"


def _clean(value: Any) -> Any:
    """Trim, and store an all-whitespace value as NULL rather than as " ".

    ``designers._clean``'s rule: a note that is one space is not blank to any ``if row.notes`` test
    in this codebase, so it renders as an empty line the admin cannot see or delete.
    """
    return value.strip() or None if isinstance(value, str) else value


# --------------------------------------------------------------------------------------
# Reading: the list, and the count that is the notification
# --------------------------------------------------------------------------------------


# DECLARED BEFORE THE ``/{row_id}`` ROUTES. FastAPI matches in declaration order and this codebase
# has already lost an endpoint to that once (see the note above ``design_workshop_viewers`` in
# app/api/router.py). Nothing below currently collides with the literal path, and putting it first
# is what keeps that true when somebody adds ``GET /access/roster/{id}``.
@router.get("/roster/pending-count")
async def pending_request_count(_: Any = Depends(require_access_manager)) -> dict[str, Any]:
    """**THE NOTIFICATION.** How many people are waiting for a decision.

    Deliberately its own endpoint rather than a field on the roster list: the surfaces that show
    this — the admin dashboard, the settings screen, a badge on a nav item — want the number without
    the page of rows, and a screen that had to fetch fifty roster rows to render a badge would
    either not render it or fetch them on every paint.

    ``capacity`` and ``capReached`` are here because an admin looking at a queue that has stopped
    growing needs to know whether that is because nobody is asking or because the product stopped
    recording the asks. See ACCESS_PENDING_MAX in app/core/config.py.
    """
    waiting = await access_roster.pending_count()
    cap = access_roster.pending_cap()
    return {"pending": waiting, "capacity": cap, "capReached": waiting >= cap}


@router.get("/roster")
async def list_access_roster(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    status_filter: list[str] | None = Query(default=None, alias="status"),
    roles: list[str] | None = Query(default=None),
    dateField: str | None = Query(default=None),
    dateFrom: datetime | None = Query(default=None),
    dateTo: datetime | None = Query(default=None),
    sort: str | None = Query(default=None),
    dir: str | None = Query(default=None),
    _: Any = Depends(require_access_manager),
) -> dict[str, Any]:
    """The allow-list, filterable, sortable and paged — requirement 30's full grammar on the one
    route Android and the web have both been calling since this endpoint existed.

    EVERY PARAMETER BELOW IS ADDITIVE, AND THE SINGLE-VALUE CASE IS BYTE-IDENTICAL TO BEFORE. Both
    clients already send ``?status=PENDING`` today (``WorkshopRepositoryApi.kt``'s ``accessRoster``
    call, and the web's own ``listAccessRoster``), so ``status`` becoming plural must not change what
    a lone value means. It does not: one value still folds through
    :func:`app.services.record_filters.enum_filter_list_or_422` onto
    ``{"status": {"in": ["PENDING"]}}``, which Postgres plans identically to the old
    ``{"status": "PENDING"}`` — same rows, same order — so a client that lags this rollout by a
    release keeps working with no code of its own to change, and a caller that upgrades gains
    ``?status=PENDING,SUSPENDED`` / repeated ``status=`` for free.

    EMPTY MEANS EVERYTHING, BY ABSENCE — never by an all-ticked state. Neither ``status`` nor
    ``roles`` has a value that means "every row"; each means it by not being sent at all, which is
    what :func:`enum_filter_list_or_422` returns ``None`` for. That is what keeps EVERY STATUS
    VISIBLE BY DEFAULT, pending, rejected and suspended included: an admin arriving here is usually
    holding a message from somebody who cannot sign in, and the row that explains why is exactly the
    one a tidied-up default would hide — they would re-add the address, get the 409, and have no way
    to see what is actually refusing their colleague. The queue is still ``?status=PENDING`` over
    this ONE list rather than a second endpoint with a second ordering, for the reason it always
    was: two orderings behind one set of rows is how a paged walk starts repeating and skipping.

    ``roles`` CARRIES A NINTH, RESERVED OPTION THE EIGHT NAMED TIERS CANNOT EXPRESS. ``admitRole
    IS NULL`` means "the platform default", and ticking every named tier would silently exclude
    every one of those rows — the same failure ``UNASSIGNED_WORKSHOP`` exists to prevent for a
    workshop scope. The reserved token is ``"default"``; see :data:`ACCESS_ROLE_FILTER_TOKENS`.

    ONE DATE RANGE PER REQUEST, NOT FIVE (DROPDOWN_DESIGN §4.1). ``dateField`` names ONE of the five
    columns this route's indexes cover and ``dateFrom``/``dateTo`` bound THAT column; a caller
    wanting a different column sends a different request rather than stacking a second range on top.
    See :data:`ACCESS_DATE_COLUMNS` for the five tokens and the 422 an unknown one gets.

    ``sort``/``dir`` ARE STABLE ACROSS A PAGED WALK BY CONSTRUCTION, not by a caller remembering to
    ask. Every order this route can build is handed to
    :func:`app.services.records.count_and_page`, which appends the ``id`` tiebreak on the way
    through — see that function and :func:`app.services.records.with_id_tiebreak` for why an
    ``ORDER BY`` with no unique column silently repeats and skips rows across an offset-paged walk,
    and why that is not hypothetical on THIS table: the migration that grandfathered every
    pre-existing account onto the allow-list inserted several hundred of them with one
    ``CURRENT_TIMESTAMP``, so even the default sort alone has a large tie group. THERE IS NO
    EXPLICIT ``with_id_tiebreak`` CALL IN THIS FUNCTION — ``count_and_page`` is what applies it, and
    calling it a second time here would be redundant with every other list route built on
    ``count_and_page`` (``artisans.py``, ``crafts.py``, ``users.py`` among them), none of which
    pre-wrap their own order either. :data:`ACCESS_SORT_DEFAULT_DIR` is what a caller gets when it
    names ``sort`` and says nothing about ``dir``; see its docstring for why the default direction
    differs by column rather than being one global setting.

    ``roleMatchTruncated`` IS ALWAYS ``False`` HERE, SENT ANYWAY. ``admitRole`` is a real column on
    ``AccessRoster``, so the role filter above is one clause in the same query and never needs a
    second read — unlike the designer roster's role filter, which matches through a separate query
    over ``User`` because that roster carries no role of its own (DROPDOWN_DESIGN §4.4). The flag
    rides the envelope regardless, so both rosters answer the SAME SHAPE and a client component
    shared between them never has to know which roster it is looking at before deciding whether a
    cut notice applies.

    EVERY FILTER ABOVE IS ASSEMBLED INTO ``where`` BEFORE THE ONE CALL TO ``count_and_page``, and
    nothing after that call touches ``rows``. A post-fetch ``[r for r in rows if ...]`` would report
    a page that is the right SIZE while having silently dropped whoever the extra clause excluded —
    a complete-looking list that is missing people, the same failure ``services/designers.py``
    documents for the directory's own cap, one layer up from a filter instead of a limit.
    """
    where: dict[str, Any] = {}

    statuses = enum_filter_list_or_422(status_filter, FILTERABLE, field="status")
    if statuses is not None:
        where["status"] = {"in": sorted(statuses)}

    if search:
        token = search.strip()
        # ``records.contains`` and not a hand-rolled filter: it strips the control bytes Postgres
        # cannot store (a pasted NUL was a bare 500 from this box) and escapes the LIKE
        # metacharacters, so an admin pasting ``first_last@org`` to find one colleague gets that
        # colleague rather than every row where ``_`` matched any character.
        where["OR"] = [
            {"email": contains(token)},
            {"fullName": contains(token)},
            {"notes": contains(token)},
        ]
        #  ^ THE ONE `OR` KEY IS TAKEN, by search. Every clause below AND-composes into
        #    ``where["AND"]`` and is never assigned to ``where["OR"]`` directly: two assignments to
        #    one dict key is not a merge, it is the second one silently overwriting the first, which
        #    would make a role filter combined with a search term quietly stop searching. The same
        #    trap is called out at ``design_workshops.py``'s own accessible-scope clause and is why
        #    ``owned_or_granted_where`` composes the same way.

    wanted_roles = enum_filter_list_or_422(roles, ACCESS_ROLE_FILTER_TOKENS, field="roles")
    if wanted_roles is not None:
        arms: list[dict[str, Any]] = []
        named = sorted(wanted_roles - {"default"})
        if named:
            arms.append({"admitRole": {"in": named}})
        if "default" in wanted_roles:
            # THE RESERVED NINTH OPTION. ``admitRole`` NULL means "the platform default, the lowest
            # rung" (``schema.prisma:4255-4263``), and both clients already render it as its own
            # phrase. A picker with eight rows and no ninth cannot express it, and ticking all eight
            # would silently exclude every default-tier admission — see :data:`ACCESS_ROLE_FILTER_TOKENS`.
            arms.append({"admitRole": None})
        # ``wanted_roles`` is a non-empty SUBSET of ``ACCESS_ROLE_FILTER_TOKENS`` whenever it is not
        # ``None`` — ``enum_filter_list_or_422``'s own guarantee — so at least one of the two
        # branches above always ran and ``arms`` is never empty on this route.
        where.setdefault("AND", []).append({"OR": arms})

    if dateField:
        # A NAMED, VALIDATED COLUMN, not a raw dict subscript. ``ACCESS_DATE_COLUMNS[dateField]``
        # with no check in front of it would answer an unrecognised token with a Python
        # ``KeyError`` — an unhandled 500 with a stack trace in the log — which is precisely the
        # failure ``enum_filter_or_422`` exists to turn into a 422 naming the five valid values.
        column = ACCESS_DATE_COLUMNS[
            enum_filter_or_422(dateField, frozenset(ACCESS_DATE_COLUMNS), field="dateField")
        ]
        add_date_range(where, column, dateFrom, dateTo)

    sort_token = enum_filter_or_422(
        sort or ACCESS_DEFAULT_SORT, frozenset(ACCESS_SORT_COLUMNS), field="sort"
    )
    dir_token = enum_filter_or_422(
        dir or ACCESS_SORT_DEFAULT_DIR[sort_token], frozenset({"asc", "desc"}), field="dir"
    )

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await count_and_page(
        db.accessroster,
        where=where,
        skip=skip,
        take=clean_size,
        order={ACCESS_SORT_COLUMNS[sort_token]: dir_token},
    )
    return page_payload([access_payload(r) for r in rows], total, clean_page, clean_size) | {
        # ALWAYS FALSE ON THIS ROUTE — see the docstring: ``admitRole`` is a real column here, so
        # the role filter above never truncates and needs no second read. Sent anyway so both
        # rosters answer one envelope shape.
        "roleMatchTruncated": False,
    }


# --------------------------------------------------------------------------------------
# Writing
# --------------------------------------------------------------------------------------


@router.post("/roster", status_code=status.HTTP_201_CREATED)
async def add_to_access_roster(
    payload: AccessRosterCreate,
    current_user: Any = Depends(require_access_manager),
) -> dict[str, Any]:
    """Admit an address by hand. No account is required and none is created.

    A duplicate is a 409 NAMING THE EXISTING ROW rather than a silent overwrite, for the designer
    roster's reason: the common way to arrive here is an admin adding somebody who is already in the
    pending queue, and overwriting would erase the request — including how long they have been
    waiting and how many times they have tried — which is the one thing the row exists to record.
    The answer says where the row is and that deciding it is a different call.

    **THE DUPLICATE TEST ASKS ABOUT THE MAILBOX, AND HAS TO, BECAUSE THE WRITE BELOW DOES.**
    ``access_roster.admit`` now looks up both spellings of a Gmail address and updates whichever row
    it finds. A pre-check that still asked ``find_unique`` about the literal string would therefore
    answer "no such row" for ``sandy.craft3@gmail.com`` while the queue held
    ``sandycraft3@gmail.com``, fall through, and let ``admit`` OVERWRITE that pending request with
    an ACTIVE grant — the exact silent overwrite this 409 exists to prevent, reached through the
    door that was supposed to be guarding it.

    **AND THE REFUSAL SAYS WHY, IN THE CASE AN ADMIN CANNOT OTHERWISE EXPLAIN.** When the address
    typed and the address stored are different strings, "already on the access roster" reads as the
    server being wrong — the admin is looking at their own screen, searching for what they typed,
    and not finding it. So the sentence names both spellings and says Google delivers them to one
    mailbox. That is a fact the admin can act on; "duplicate key value violates unique constraint"
    is what they would otherwise eventually get, from the database, in a 500.
    """
    assert_role(payload.role, current_user)
    email = canonical_email(payload.email)
    existing = await db.accessroster.find_first(
        where={"email": {"in": email_match_keys(payload.email)}}
    )
    if existing is not None:
        typed = normalise_email(payload.email)
        stored = normalise_email(existing.email)
        same_mailbox = (
            ""
            if stored == typed
            else (
                f" You typed {typed} and the row is stored as {stored}; Google delivers both to "
                "the same mailbox, so this application treats them as one address."
            )
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{stored} is already on the access roster as {access_roster.status_of(existing)}. "
                f"Decide or update entry {existing.id} instead of adding it again.{same_mailbox}"
            ),
        )
    row = await access_roster.admit(
        email,
        admit_role=payload.role,
        actor_id=current_user.id,
        full_name=_clean(payload.fullName),
        note=_clean(payload.notes),
    )
    await _empanel_an_admitted_designer(row, current_user.id)
    return access_payload(row)


@router.post("/roster/{row_id}/decision")
async def decide_access_request(
    row_id: str,
    payload: AccessDecision,
    current_user: Any = Depends(require_access_manager),
) -> dict[str, Any]:
    """Approve or reject — the action the pending queue exists for.

    **APPROVING ALSO PROMOTES AN ACCOUNT THAT ALREADY EXISTS.** A request from somebody who has an
    account (an admin moved them to PENDING, or the account was made by a path that did not admit
    it) would otherwise be approved at a role that only takes effect when a NEW account is created —
    which never happens for them. The lift is strictly-below, never a demotion: an admin approving a
    colleague at RESEARCHER must not knock a professor down by doing so.

    **REJECTING IS FINAL UNTIL AN ADMIN SAYS OTHERWISE.** The person's next attempt does not
    re-queue them; it bumps ``attemptCount`` on the rejected row and they are told they were not
    approved. That is the only version of this that leaves the queue workable — see
    ``app/services/access_roster.py`` — and reopening a rejection is this same endpoint with
    APPROVE, which is an admin's decision rather than a stranger's persistence.

    Approving a SUSPENDED row is how access is restored, and ``joinedAt`` is not moved by it: a
    person who joined in 2024, lost access and was let back in has still been here since 2024.
    """
    row = await db.accessroster.find_unique(where={"id": row_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    if payload.decision == "REJECT":
        updated = await db.accessroster.update(
            where={"id": row_id},
            data={
                "status": access_roster.REJECTED,
                "decidedAt": _now(),
                "decidedById": current_user.id,
                **({"notes": _clean(payload.notes)} if payload.notes is not None else {}),
            },
        )
        return access_payload(updated)

    assert_role(payload.role, current_user)
    granted = payload.role or access_roster.role_of(row)
    updated = await access_roster.admit(
        row.email,
        admit_role=granted,
        actor_id=current_user.id,
        note=_clean(payload.notes) if payload.notes is not None else None,
    )
    if granted:
        await _lift_existing_account(row.email, granted)
    await _empanel_an_admitted_designer(updated, current_user.id)
    return access_payload(updated)


@router.patch("/roster/{row_id}")
async def update_access_entry(
    row_id: str,
    payload: AccessRosterUpdate,
    current_user: Any = Depends(require_access_manager),
) -> dict[str, Any]:
    """Correct the admin-typed columns. Cannot change ``status`` — see the schema's docstring."""
    row = await db.accessroster.find_unique(where={"id": row_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    values = payload.model_dump(exclude_unset=True)
    data: dict[str, Any] = {}
    if "role" in values:
        assert_role(values["role"], current_user)
        data["admitRole"] = values["role"]
    for key in ("fullName", "notes"):
        if key in values:
            data[key] = _clean(values[key])
    if not data:
        return access_payload(row)
    updated = await db.accessroster.update(where={"id": row_id}, data=data)
    # An edit is one of the two ways a row comes to admit a DESIGNER — the other is the decision
    # endpoint above. An admin correcting a row from RESEARCHER to DESIGNER has empanelled that
    # person exactly as surely as one who approved them at DESIGNER in the first place, and if only
    # the approval path empanelled, the corrected row would be the one that silently does not.
    await _empanel_an_admitted_designer(updated, current_user.id)
    return access_payload(updated)


@router.delete("/roster/{row_id}")
async def suspend_access_entry(
    row_id: str, current_user: Any = Depends(require_access_manager)
) -> dict[str, Any]:
    """SUSPEND, never delete. See the module docstring.

    Idempotent, and it keeps the ORIGINAL ``decidedAt`` on a row that is already suspended: that
    date answers "when did this person lose access", and a second click on the button would
    otherwise move it to today and destroy the answer.

    A PENDING row suspended through here becomes SUSPENDED rather than REJECTED, and the difference
    is not pedantry: REJECTED is the answer to a request and carries the sentence "your request was
    not approved", while SUSPENDED says access was ended. Rejecting is what this button's caller
    almost always means for a pending row, so the clients send the decision endpoint for that; this
    arm exists so that whichever one is called, nothing is deleted and the row keeps saying
    something true.
    """
    row = await db.accessroster.find_unique(where={"id": row_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if access_roster.status_of(row) == access_roster.SUSPENDED:
        return access_payload(row)
    updated = await db.accessroster.update(
        where={"id": row_id},
        data={
            "status": access_roster.SUSPENDED,
            "decidedAt": row.decidedAt or _now(),
            "decidedById": row.decidedById or current_user.id,
        },
    )
    return access_payload(updated)


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


async def _empanel_an_admitted_designer(row: Any, actor_id: str | None) -> None:
    """An ACTIVE allow-list row admitting a DESIGNER gets the empanelment that admission implies.

    **THE SIGN-IN PATH ALREADY DOES THIS, AND THAT IS NOT ENOUGH.** ``auth.login`` empanels an
    admitted designer on their way in, so nobody is locked out either way; what this call buys is
    that the admin can SEE it. Without it, ``/admin/designers`` shows nothing about the person until
    their first sign-in — so an admin who has just approved a designer opens the roster screen, does
    not find them, and reasonably concludes the approval did not take. What they do next is add the
    row by hand, which answers 409 if they type the address the same way. It used to create a
    second, unmatchable row if they did not: ``normalise_email`` is only ``.strip().lower()``, so
    one Gmail dot was a different key. :func:`app.services.designers.canonical_email` closed that
    half of it — the roster row this call writes is stored under the mailbox rather than under one
    spelling of it — but the reason for writing the row HERE is unchanged, and is the stronger of
    the two: an approval an admin cannot see is an approval they will make again. The empanelment
    is a consequence of the approval, so it is written when the approval is.

    **READ OFF THE STORED ROW, NEVER OFF THE REQUEST BODY.** All three callers reach here by
    different routes to the same fact: ``add_to_access_roster`` takes the role from the payload,
    ``decide_access_request`` takes it from the payload OR from what the row already carried, and
    ``update_access_entry`` changes ``admitRole`` while the status stays whatever it was. Asking the
    saved row is the one formulation that is correct for all three and cannot drift from what was
    actually written.

    **BOTH TESTS ARE LOAD-BEARING, AND THE STATUS ONE IS AN ADMISSION DECISION RATHER THAN A
    TIDINESS ONE.** ``AccessRosterUpdate`` deliberately cannot change ``status`` — approving is one
    explicit act at one endpoint — so an admin may edit ``admitRole`` on a row in any state at all.
    Empanelling on the role alone would therefore give a still-PENDING request an ACTIVE designer
    row, and the empanelment clause in ``auth.assert_access_admits`` accepts an ACTIVE designer row
    as an admission for anybody whose allow-list row is missing or PENDING: changing a dropdown
    would have approved somebody, through a second table, with no decision recorded anywhere. On a
    REJECTED or SUSPENDED row the same edit does not let them in — neither state is "waiting" — but
    it does put an active empanelment on ``/admin/designers`` for somebody an administrator barred,
    and nothing downstream ever corrects it, because suspending an allow-list row does not suspend
    an empanelment. ``status_of`` and ``role_of`` rather than the raw attributes for their own
    documented reason: Prisma returns an enum member on a live row and a bare string on a hand-built
    one, and ``==`` answers False for the first of those — a comparison that fails OPEN here.

    **IT ONLY EVER CREATES.** :func:`ensure_empanelled` will not revive a suspended roster row, and
    nothing here suspends one either: barring an address from the application is not the same
    decision as withdrawing an empanelment, which is exactly why ``auth.py`` keeps the two refusals
    in two distinct sentences. An admin who means to end an empanelment does it on the designer
    roster, where the act is visible on the screen that records it.
    """
    if access_roster.status_of(row) != access_roster.ACTIVE:
        return
    if access_roster.role_of(row) != "DESIGNER":
        return
    # The acting admin, and not None as on the sign-in path: an administrator really did take this
    # action, and ``addedById`` is how the roster screen says who.
    await ensure_empanelled(getattr(row, "email", None), actor_id=actor_id)


async def _lift_existing_account(email: str, role: str) -> None:
    """Raise an existing account to the approved tier. NEVER lowers it.

    The strictly-below comparison is ``login_with_google``'s, for its reason: an admin or professor
    whose allow-list row says something modest must not be demoted by somebody approving them.
    """
    address = normalise_email(email)
    user = await db.user.find_unique(where={"email": address})
    if user is None or role_value(user) == "MASTER_ADMIN":
        return
    if role_rank(user) >= ROLE_RANK.get(role, 0):
        return
    await db.user.update(where={"id": user.id}, data={"role": role})
    # The cached identity now describes authority the account did not have a moment ago; it must not
    # outlive the write by even one request. Every User write in this codebase invalidates.
    invalidate_cached_user(user.id)


def _now() -> datetime:
    return datetime.now(UTC)
