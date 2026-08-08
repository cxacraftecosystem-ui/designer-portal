"""The designer roster and the designer profile: the two facts kept deliberately apart.

**THE ROSTER GATES SIGN-IN. THE ROLE GRANTS POWERS.** ``User.role`` says what somebody may DO;
``DesignerRoster`` says whether the institution still recognises them at all. The temptation is
always to collapse the two and revoke access by demoting the account, and that is precisely what
must not happen here: the role column is read when deciding who may review whose work and whose
records may be edited, so a demotion is RETROACTIVE — it silently rewrites the standing of every
workshop the designer ever ran. Suspending a roster row ends their sessions and leaves the
authorship of two years of fieldwork exactly as it was.

**THE PROFILE IS COPIED, NEVER REFERENCED.** :func:`prefill_from_profile` returns values that a
new workshop's stage entries are seeded WITH; nothing downstream ever reads the profile again.
See that function's docstring for the failure that rule prevents.

Every email that reaches this module is lower-cased on the way in. ``DesignerRoster.email`` is
unique and is joined to ``User.email``, and a roster row typed ``A.Sharma@Example.org`` that
never matches the account signing in as ``a.sharma@example.org`` is a designer locked out of the
app by a capital letter, with an admin looking straight at the row that was supposed to let them
in. :func:`normalise_email` is the single place that happens.
"""

from datetime import UTC, datetime
from typing import Any

from app.core.db import db

# Every column of ``DesignerProfile`` a person may write. Named once, here, and consumed by the
# serializer, the updater and the schema's field list alike — three copies of a twenty-name list
# is two copies that will disagree, and the way that failure surfaces is a field the designer can
# save and then cannot see.
PROFILE_FIELDS: tuple[str, ...] = (
    "displayName",
    "localName",
    "designation",
    "institution",
    "department",
    "qualification",
    "specialisation",
    "experienceYears",
    "biography",
    "phone",
    "email",
    "website",
    "addressLine",
    "city",
    "state",
    "pincode",
    "photoMediaId",
    "signatureMediaId",
    "empanelmentNo",
    "empanelmentDate",
)

# Columns of DesignerProfile that are DateTime in Postgres and ISO strings on the wire. A raw
# string reaching a DateTime column is a driver-level error and therefore a bare 500, not a 422,
# so every one of these goes through :func:`_parse_date` before the write.
PROFILE_DATE_FIELDS: frozenset[str] = frozenset({"empanelmentDate"})


def normalise_email(email: Any) -> str:
    """The one canonical form of an email in the roster. See the module docstring."""
    return str(email or "").strip().lower()


# --------------------------------------------------------------------------------------
# The roster
# --------------------------------------------------------------------------------------


async def roster_allows(email: Any) -> bool:
    """Is there an ACTIVE roster row for this email?

    The single question the sign-in gate asks. ``isActive`` is checked in the WHERE clause rather
    than in Python because "a row exists" and "the row still admits them" are one query apart and
    a reader who conflates them has written an authentication bypass: a suspended designer's row
    is still there, which is the entire point of suspending rather than deleting it.
    """
    address = normalise_email(email)
    if not address:
        return False
    row = await db.designerroster.find_first(where={"email": address, "isActive": True})
    return row is not None


async def mark_roster_seen(email: Any) -> None:
    """Stamp ``firstSeenAt`` the first time an empanelled email actually signs in.

    An admin adds five designers to the roster in March and has no way, in April, to tell which
    of them ever opened the app: an invitation that never arrived looks exactly like one that was
    ignored. This is that signal, and it is written once — the WHERE clause carries
    ``firstSeenAt: None``, so the stamp records the FIRST sign-in rather than the most recent one
    and two simultaneous logins cannot race each other into overwriting it.

    Silent when no roster row exists. It is called on every successful sign-in, including the
    admins' — an ``update_many`` that matches nothing is cheaper than deciding beforehand whether
    to ask, and one query on a path a user takes once a week costs nothing worth optimising.
    """
    address = normalise_email(email)
    if not address:
        return
    await db.designerroster.update_many(
        where={"email": address, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


def roster_payload(row: Any) -> dict[str, Any]:
    """One roster row as the admin screen reads it."""
    return {
        "id": row.id,
        "email": row.email,
        "fullName": row.fullName,
        "institution": row.institution,
        "notes": row.notes,
        "isActive": row.isActive,
        "revokedAt": _iso(row.revokedAt),
        "firstSeenAt": _iso(row.firstSeenAt),
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
        "addedById": row.addedById,
    }


# --------------------------------------------------------------------------------------
# The profile
# --------------------------------------------------------------------------------------


async def get_or_create_profile(user_id: str) -> Any:
    """The user's profile row, created empty if they have never saved one.

    An upsert rather than a find, so ``GET`` and ``PUT`` cannot disagree about whether the row
    exists. The alternative — returning ``{}`` for a missing profile, as ``/preferences/me``
    does — was rejected here because the profile is READ by workshop creation, and a create path
    that has to handle "no row yet" as well as "row with no values" is two paths where one will
    do.
    """
    return await db.designerprofile.upsert(
        where={"userId": user_id},
        data={"create": {"user": {"connect": {"id": user_id}}}, "update": {}},
    )


async def update_profile(user_id: str, values: dict[str, Any]) -> Any:
    """Write the given profile columns, leaving every column not named here alone.

    ``values`` is expected to come from ``payload.model_dump(exclude_unset=True)`` — see the
    module docstring of ``app.schemas.designers`` for why absent and null have to mean different
    things on this particular body.
    """
    data: dict[str, Any] = {}
    for key in PROFILE_FIELDS:
        if key not in values:
            continue
        value = values[key]
        if key in PROFILE_DATE_FIELDS:
            data[key] = _parse_date(value)
        elif isinstance(value, str):
            # Trimmed, and an all-whitespace value stored as NULL rather than as " ". A profile
            # whose institution is a single space is not blank to any `if row.institution` test
            # in this codebase, so it would print as an empty line on the cover of every report.
            data[key] = value.strip() or None
        else:
            data[key] = value
    if not data:
        return await get_or_create_profile(user_id)
    return await db.designerprofile.upsert(
        where={"userId": user_id},
        data={"create": {**data, "user": {"connect": {"id": user_id}}}, "update": data},
    )


def profile_payload(row: Any) -> dict[str, Any]:
    """One profile as the clients read it."""
    payload: dict[str, Any] = {"id": row.id, "userId": row.userId}
    for key in PROFILE_FIELDS:
        value = getattr(row, key, None)
        payload[key] = _iso(value) if isinstance(value, datetime) else value
    payload["createdAt"] = _iso(row.createdAt)
    payload["updatedAt"] = _iso(row.updatedAt)
    return payload


# --------------------------------------------------------------------------------------
# Prefill
# --------------------------------------------------------------------------------------

#: Profile column -> registry field key. The report never learns that a profile exists; it reads
#: ordinary stage data written under these four keys, which is what keeps the designer's details
#: editable per workshop like every other captured value.
PREFILL_MAP: tuple[tuple[str, str], ...] = (
    ("displayName", "designerName"),            # stage 1, workshopSetup
    ("institution", "designerInstitution"),     # stage 1, workshopSetup
    ("biography", "designerProfile"),           # stage 3, workshopPlan
    ("experienceYears", "designerExperience"),  # stage 3, workshopPlan
)


async def prefill_from_profile(user_id: str) -> dict[str, Any]:
    """The stage-1 and stage-3 values a workshop this user creates should START with.

    Returns ``{registry field key: value}`` — ``designerName``, ``designerInstitution``,
    ``designerProfile``, ``designerExperience`` — for the keys the profile can actually answer,
    and an empty dict for a designer who has never filled one in.

    **THESE ARE COPIES, AND THEY MUST STAY COPIES.** A report is a HISTORICAL DOCUMENT. It records
    a workshop that was run, on given dates, by a named person working out of a named institution
    at the time. If a workshop's stages held a reference to the profile instead of a copy of its
    values, then a designer who moves from NIFT to NID in 2027 would retroactively rewrite the
    2026 report — regenerating it, or merely previewing it, would name an institution that had
    nothing to do with the workshop and had never sponsored it. The same is true of the biography
    and of the years of experience, which is a number the report prints as a statement about the
    designer *on the day*. Copying is not an optimisation here; referencing would be a
    falsification. Every value below is read once, at creation, and never consulted again — the
    stages own them from that moment, and a designer correcting the spelling of their name in one
    workshop must not touch any other.

    ``displayName`` falls back to the account's own name, because a designer who has filled in
    nothing at all still has a name and retyping it into stage 1 of every workshop is exactly the
    chore this exists to remove.
    """
    profile = await db.designerprofile.find_unique(
        where={"userId": user_id}, include={"user": True}
    )
    if profile is None:
        return {}

    values: dict[str, Any] = {}
    for column, field_key in PREFILL_MAP:
        value = getattr(profile, column, None)
        if isinstance(value, str):
            value = value.strip()
        if value in (None, ""):
            continue
        values[field_key] = value

    if "designerName" not in values:
        account_name = str(getattr(getattr(profile, "user", None), "name", "") or "").strip()
        if account_name:
            values["designerName"] = account_name
    return values


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


def _parse_date(raw: Any) -> datetime | None:
    """An ISO date string as a UTC datetime, or None for anything unreadable.

    Unreadable input becomes NULL rather than a 422 for the same reason ``_parse_date`` in the
    design-workshop routes does: this is one optional identifier on a twenty-field profile, and
    refusing the whole save because the empanelment date was typed ``12/03/2026`` would lose the
    nineteen fields the designer got right.
    """
    if raw in (None, ""):
        return None
    if isinstance(raw, datetime):
        return raw if raw.tzinfo else raw.replace(tzinfo=UTC)
    try:
        return datetime.fromisoformat(str(raw)[:10]).replace(tzinfo=UTC)
    except ValueError:
        return None
