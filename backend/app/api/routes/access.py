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

import asyncio
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
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains, with_id_tiebreak

router = APIRouter(prefix="/access", tags=["access"])

#: The statuses a caller may filter on, spelled once. An unknown value is a 422 rather than an empty
#: list, because "no rows matched" and "you typed the status wrong" look identical on a screen and
#: only one of them is the admin's fault.
FILTERABLE = (access_roster.ACTIVE, access_roster.PENDING, access_roster.REJECTED,
              access_roster.SUSPENDED)


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
    status_filter: str | None = Query(default=None, alias="status"),
    _: Any = Depends(require_access_manager),
) -> dict[str, Any]:
    """The allow-list, newest first, with the pending queue reachable through ``?status=PENDING``.

    EVERY STATUS BY DEFAULT, including rejected and suspended rows. An admin arriving here is
    usually holding a message from somebody who cannot sign in, and the row that explains why is
    exactly the one a tidied-up default would hide — they would then re-add the address, get the
    409, and have no way to see what is actually refusing their colleague.

    Ordered by ``createdAt`` descending like the designer roster, NOT by ``requestedAt``: the queue
    view is a filter over this one list, and two orderings for one endpoint is how a paged list
    starts repeating and skipping rows between pages.
    """
    where: dict[str, Any] = {}
    if status_filter:
        wanted = status_filter.strip().upper()
        if wanted not in FILTERABLE:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Unknown access status {status_filter!r}. One of: {', '.join(FILTERABLE)}.",
            )
        where["status"] = wanted
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
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await asyncio.gather(
        db.accessroster.count(where=where),
        db.accessroster.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            # TOTAL ORDER, and on this table the ties are a certainty rather than a risk: the
            # migration that grandfathered the existing accounts onto the roster inserted every one
            # of them with a single ``CURRENT_TIMESTAMP``. Paging four hundred rows that share a
            # sort key without a tiebreaker hands some of them over twice and never shows others.
            # This read bypasses ``count_and_page``, so it appends the tiebreak itself.
            order=with_id_tiebreak({"createdAt": "desc"}),
        ),
    )
    return page_payload([access_payload(r) for r in rows], total, clean_page, clean_size)


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
    """
    assert_role(payload.role, current_user)
    email = normalise_email(payload.email)
    existing = await db.accessroster.find_unique(where={"email": email})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{email} is already on the access roster as {access_roster.status_of(existing)}. "
                f"Decide or update entry {existing.id} instead of adding it again."
            ),
        )
    row = await access_roster.admit(
        email,
        admit_role=payload.role,
        actor_id=current_user.id,
        full_name=_clean(payload.fullName),
        note=_clean(payload.notes),
    )
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
    return access_payload(await db.accessroster.update(where={"id": row_id}, data=data))


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
