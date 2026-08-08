"""The designer roster, the designer profile, and the picker an admin assigns a workshop from.

THREE FAMILIES, AND THE FIRST TWO ANSWER TO DIFFERENT PEOPLE.

* ``/designers/roster`` — ADMIN ONLY, read and write. The empanelment list that gates sign-in
  for anyone holding the DESIGNER role. See ``app/services/designers.py`` for why this is a
  separate fact from ``User.role``, and ``app/api/routes/auth.py`` for the gate itself.
* ``/designers/me/profile`` and ``/designers/{user_id}/profile`` — the designer's own standing
  details, written by the owner or by an admin and by nobody else. This is the text that gets
  printed in a report, so a designer able to edit a colleague's copy could put words into a
  document submitted to a ministry under that colleague's name.
* ``/designers/directory`` — who an admin may hand a workshop to.

**DELETE ON THE ROSTER IS A SUSPENSION.** It sets ``isActive=false`` and stamps ``revokedAt``;
it never removes the row. The roster is the RECORD that somebody was empanelled, and that record
outlives their access to the app: an audit two years later asks who was recognised under which
programme, and a deleted row answers "nobody". Restoring is ``PATCH`` with ``isActive: true``,
which is also why a DELETE here answers 200 with the suspended row rather than 204 with nothing —
there is something left to show.

**ROUTE ORDER IS LOAD-BEARING.** ``/me/profile`` and ``/directory`` are declared before
``/{user_id}/profile``. FastAPI matches in declaration order, so the other way round the literal
paths would be swallowed by the parameterised one and ``GET /designers/me/profile`` would look up
a user whose id is the string "me" and 404 forever.
"""

import asyncio
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    is_admin,
    require_designer,
    require_designer_roster_manager,
    role_value,
)
from app.schemas.designers import (
    DesignerProfileUpdate,
    DesignerRosterCreate,
    DesignerRosterUpdate,
)
from app.services.designers import (
    get_or_create_profile,
    normalise_email,
    profile_payload,
    roster_payload,
    update_profile,
)
from app.services.pagination import normalize_pagination, page_payload

router = APIRouter(prefix="/designers", tags=["designers"])

# The roles that may run a design workshop, and therefore the accounts the assignment picker
# offers. Derived from the ladder rather than written out, so inserting a tier above DESIGNER
# next year does not quietly drop it out of every admin's picker.
WORKSHOP_CAPABLE_ROLES = [
    role for role, rank in ROLE_RANK.items() if rank >= ROLE_RANK["DESIGNER"]
]

# Identical text for "you may not see this profile" and "no such user", because they are answered
# with the same status and must not be told apart. See :func:`_assert_may_touch_profile`.
_PROFILE_NOT_FOUND = "Record not found"


# --------------------------------------------------------------------------------------
# The roster
# --------------------------------------------------------------------------------------


@router.get("/roster")
async def list_roster(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    activeOnly: bool = False,
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """The empanelment list, suspended rows included by default.

    Suspended rows are shown unless ``activeOnly`` is asked for, and that default is the point of
    the screen: an admin looking for a designer who says they cannot log in needs to SEE the row
    that is refusing them. A list that hid it would leave them re-adding an email the unique index
    then rejects, with no explanation visible anywhere in the UI.
    """
    where: dict[str, Any] = {}
    if activeOnly:
        where["isActive"] = True
    if search:
        token = search.strip()
        where["OR"] = [
            {"email": {"contains": token, "mode": "insensitive"}},
            {"fullName": {"contains": token, "mode": "insensitive"}},
            {"institution": {"contains": token, "mode": "insensitive"}},
        ]
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await asyncio.gather(
        db.designerroster.count(where=where),
        db.designerroster.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([roster_payload(r) for r in rows], total, clean_page, clean_size)


@router.post("/roster", status_code=status.HTTP_201_CREATED)
async def add_to_roster(
    payload: DesignerRosterCreate,
    current_user: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """Empanel an email.

    No user account is required and none is created. The row exists so that the account can
    provision itself: the first time that address signs in through Google, ``auth.py`` promotes
    it to DESIGNER. That is how an admin empanels somebody who has never opened the app.

    A duplicate is a 409 NAMING THE EXISTING ROW, not a 500 from the unique index and not a
    silent overwrite. The common way to arrive here is an admin re-empanelling a designer they
    suspended in March: overwriting would erase the note recording the original empanelment —
    the one thing the row exists to preserve — so the answer says where the row is and that
    restoring it is a PATCH.
    """
    email = normalise_email(payload.email)
    existing = await db.designerroster.find_unique(where={"email": email})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{email} is already on the roster"
                f"{'' if existing.isActive else ' (suspended)'}. "
                f"Update or restore roster entry {existing.id} instead of adding it again."
            ),
        )
    row = await db.designerroster.create(data={
        "email": email,
        "fullName": _clean(payload.fullName),
        "institution": _clean(payload.institution),
        "notes": _clean(payload.notes),
        "isActive": payload.isActive,
        # Stamped on creation when the row starts suspended, so `revokedAt` is never null on a
        # row that cannot sign in — the roster screen reads that pair together and a suspended
        # row with no date reads as a bug in the screen rather than as a deliberate state.
        "revokedAt": None if payload.isActive else datetime.now(UTC),
        "addedById": current_user.id,
    })
    return roster_payload(row)


@router.patch("/roster/{roster_id}")
async def update_roster_entry(
    roster_id: str,
    payload: DesignerRosterUpdate,
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    """Correct a roster row, or restore a suspended one with ``isActive: true``.

    Restoring CLEARS ``revokedAt``. Leaving the old timestamp behind would produce a row that is
    active and carries a revocation date, and the next admin reading it has no way to tell
    whether the person may sign in — the flag or the date, and they disagree.
    """
    row = await db.designerroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    values = payload.model_dump(exclude_unset=True)
    data: dict[str, Any] = {}
    if "email" in values:
        email = normalise_email(values["email"])
        if email and email != row.email:
            clash = await db.designerroster.find_unique(where={"email": email})
            if clash is not None:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"{email} is already on the roster as entry {clash.id}.",
                )
            data["email"] = email
    for key in ("fullName", "institution", "notes"):
        if key in values:
            data[key] = _clean(values[key])
    if "isActive" in values and values["isActive"] is not None:
        data["isActive"] = bool(values["isActive"])
        data["revokedAt"] = None if data["isActive"] else datetime.now(UTC)
    if not data:
        return roster_payload(row)
    return roster_payload(await db.designerroster.update(where={"id": roster_id}, data=data))


@router.delete("/roster/{roster_id}")
async def suspend_roster_entry(
    roster_id: str, _: Any = Depends(require_designer_roster_manager)
) -> dict[str, Any]:
    """SUSPEND, never delete. See the module docstring.

    Idempotent: suspending an already-suspended row keeps the ORIGINAL ``revokedAt``, because
    that date is the answer to "when did this designer lose access", and a second click on the
    button would otherwise move it to today and destroy it.
    """
    row = await db.designerroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not row.isActive and row.revokedAt is not None:
        return roster_payload(row)
    updated = await db.designerroster.update(
        where={"id": roster_id},
        data={"isActive": False, "revokedAt": row.revokedAt or datetime.now(UTC)},
    )
    return roster_payload(updated)


# --------------------------------------------------------------------------------------
# The directory — declared BEFORE /{user_id}/profile, see the module docstring
# --------------------------------------------------------------------------------------


@router.get("/directory")
async def designer_directory(
    search: str | None = None,
    includeSuspended: bool = False,
    _: Any = Depends(require_designer_roster_manager),
) -> list[dict[str, Any]]:
    """The accounts an admin may hand a workshop to, with each one's standing attached.

    SUSPENDED DESIGNERS ARE HIDDEN BY DEFAULT, and that is the useful part. Assigning a
    fortnight of fieldwork to somebody whose roster row was revoked last month produces a
    workshop nobody can open: the assignment succeeds, the designer's next sign-in is refused,
    and the gap is discovered when the report is due. ``includeSuspended=true`` shows them,
    marked, for an admin who is deciding whom to restore.

    Accounts above DESIGNER are listed unconditionally — they can run a workshop and the roster
    does not gate them, which is the whole reason the two facts are kept apart.
    """
    where: dict[str, Any] = {"role": {"in": WORKSHOP_CAPABLE_ROLES}}
    if search:
        token = search.strip()
        where["OR"] = [
            {"name": {"contains": token, "mode": "insensitive"}},
            {"email": {"contains": token, "mode": "insensitive"}},
        ]
    users = await db.user.find_many(where=where, order={"name": "asc"}, take=500)
    if not users:
        return []

    # Two lookups for the whole page rather than two per row: a directory of eighty designers
    # rendered one query at a time is a hundred and sixty sequential round trips on a
    # cross-region database, which is several seconds before the picker opens.
    emails = sorted({u.email for u in users})
    ids = sorted({u.id for u in users})
    roster_rows, profiles = await asyncio.gather(
        db.designerroster.find_many(where={"email": {"in": emails}}),
        db.designerprofile.find_many(where={"userId": {"in": ids}}),
    )
    by_email = {r.email: r for r in roster_rows}
    by_user = {p.userId: p for p in profiles}

    out: list[dict[str, Any]] = []
    for user in users:
        roster = by_email.get(user.email)
        profile = by_user.get(user.id)
        gated = role_value(user) == "DESIGNER"
        can_sign_in = bool(roster and roster.isActive) if gated else True
        if gated and not can_sign_in and not includeSuspended:
            continue
        out.append({
            "id": user.id,
            "name": user.name,
            "email": user.email,
            "role": role_value(user),
            "institution": (
                getattr(profile, "institution", None)
                or getattr(roster, "institution", None)
            ),
            "rosterId": getattr(roster, "id", None),
            "rosterActive": bool(roster and roster.isActive),
            # What the picker should actually disable a row on. `rosterActive` is a fact about a
            # table; this is the answer to the question being asked, and for a professor or an
            # admin with no roster row at all the two deliberately differ.
            "canSignIn": can_sign_in,
            "firstSeenAt": (
                roster.firstSeenAt.isoformat()
                if roster is not None and roster.firstSeenAt else None
            ),
            "hasProfile": profile is not None,
        })
    return out


# --------------------------------------------------------------------------------------
# The profile
# --------------------------------------------------------------------------------------


@router.get("/me/profile")
async def get_my_profile(current_user: Any = Depends(require_designer)) -> dict[str, Any]:
    """The signed-in account's own profile, created empty on first read.

    GATED AT ``can_run_design_workshops`` — Designer, Admin, Master Admin — and this paragraph
    replaces one that said the opposite. It used to read "any role, deliberately", on the argument
    that whoever fills in for an absent designer signs the report the same way and needs the same
    details on file. Half of that argument survives and is why ADMIN is in the set. What does not
    survive is a crowdsource volunteer or a researcher holding a designer profile: it is the name,
    institution and biography a report is SUBMITTED UNDER, and an account that cannot start a
    workshop has no report to sign.

    A PROFESSOR is deliberately outside the set even though they outrank a designer — see
    ``deps.can_run_design_workshops`` for why that one capability is a set rather than a rank
    threshold. If that turns out to be wrong for professors standing in, change the SET, not this
    route; the two must not disagree.
    """
    return profile_payload(await get_or_create_profile(current_user.id))


@router.put("/me/profile")
async def put_my_profile(
    payload: DesignerProfileUpdate, current_user: Any = Depends(require_designer)
) -> dict[str, Any]:
    """Save one's own profile. Fields absent from the body keep their stored value.

    Note what this does NOT do: it does not touch any workshop already created. The profile is
    copied into a workshop's stages once, when the workshop is created — see
    ``prefill_from_profile`` for why a report must not change under a designer who changes
    institution.
    """
    values = payload.model_dump(exclude_unset=True)
    return profile_payload(await update_profile(current_user.id, values))


@router.get("/{user_id}/profile")
async def get_designer_profile(
    user_id: str, current_user: Any = Depends(require_designer)
) -> dict[str, Any]:
    """Another account's profile — the owner or an admin, and 404 for everybody else."""
    await _assert_may_touch_profile(user_id, current_user)
    return profile_payload(await get_or_create_profile(user_id))


@router.put("/{user_id}/profile")
async def put_designer_profile(
    user_id: str,
    payload: DesignerProfileUpdate,
    current_user: Any = Depends(require_designer),
) -> dict[str, Any]:
    """Write another account's profile — the owner or an admin, and NOBODY else.

    A designer must not be able to edit another designer's biography. The text saved here is
    printed verbatim in a report that goes out under that person's name, so this is not a matter
    of tidiness: it is one designer being able to put words in another's mouth on a document
    submitted to a ministry.
    """
    await _assert_may_touch_profile(user_id, current_user)
    values = payload.model_dump(exclude_unset=True)
    return profile_payload(await update_profile(user_id, values))


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


async def _assert_may_touch_profile(user_id: str, current_user: Any) -> None:
    """Owner or admin, or a 404 — never a 403.

    404 AND NOT 403, matching ``load_workshop_or_404``. A 403 would confirm that the id belongs to
    a real account, so a designer with a list of cuids could enumerate the staff by watching which
    ones answer 403 and which answer 404. Both cases carry the identical detail string for the
    same reason: "you may not see this" and "there is no such user" have to be indistinguishable
    or the distinction is the leak.

    The authority check runs BEFORE the existence query, so a stranger's probe costs no round trip
    at all.
    """
    if user_id != current_user.id and not is_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=_PROFILE_NOT_FOUND
        )
    target = await db.user.find_unique(where={"id": user_id})
    if target is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=_PROFILE_NOT_FOUND
        )


def _clean(value: Any) -> Any:
    """Trim a roster string, storing an all-whitespace value as NULL rather than as " "."""
    if isinstance(value, str):
        return value.strip() or None
    return value
