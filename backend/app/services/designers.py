from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException
from fastapi.encoders import jsonable_encoder
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.services import identity, rich_text
from app.services.concurrency import gather_reads
from app.services.standard_values import StandardValueError, apply_phone, compose_phone

PROFILE_FIELDS: tuple[str, ...] = (
    "displayName",
    "localName",
    "designation",
    "institution",
    "department",
    "qualification",
    "specialisation",
    "experienceYears",
    "experienceMonths",
    "biography",
    "phone",
    "phoneCountryCode",
    "email",
    "website",
    "addressLine",
    "city",
    "state",
    "pincode",
    "photoMediaId",
    "signatureMediaId",
    "cvMediaId",
    "empanelmentNo",
    "empanelmentDate",
)

PROFILE_DATE_FIELDS: frozenset[str] = frozenset({"empanelmentDate"})

PROFILE_INCLUDE: dict[str, Any] = {"location": True}


def normalise_email(email: Any) -> str:
    return str(email or "").strip().lower()


GMAIL_DOMAINS: frozenset[str] = frozenset({"gmail.com", "googlemail.com"})


def canonical_email(email: Any) -> str:
    address = normalise_email(email)
    local, at, domain = address.partition("@")
    if not at or domain not in GMAIL_DOMAINS:
        return address
    mailbox = local.split("+", 1)[0].replace(".", "")
    if not mailbox:
        return address
    return f"{mailbox}@gmail.com"


def email_match_keys(email: Any) -> list[str]:
    literal = normalise_email(email)
    if not literal:
        return []
    canonical = canonical_email(literal)
    if canonical == literal:
        return [literal]
    return [literal, canonical]



async def roster_allows(email: Any) -> bool:
    keys = email_match_keys(email)
    if not keys:
        return False
    rows = await db.designerroster.find_many(where={"email": {"in": keys}})
    return bool(rows) and all(row.isActive for row in rows)


DERIVED_EMPANELMENT_NOTE = (
    "Empanelled automatically because this address is admitted on the platform allow-list as a "
    "designer. No administrator added this row on the designer roster directly."
)


async def name_on_the_allow_list(email: Any, *, client: Any = None) -> str | None:
    reader = db if client is None else client
    keys = email_match_keys(email)
    if not keys:
        return None
    rows = await reader.accessroster.find_many(where={"email": {"in": keys}})
    named = [row for row in rows if str(getattr(row, "fullName", "") or "").strip()]
    if not named:
        return None
    canonical = canonical_email(email)

    def _order(row: Any) -> tuple[int, str]:
        spelling = str(getattr(row, "email", "") or "")
        return (0 if spelling == canonical else 1, spelling)

    named.sort(key=_order)
    return str(getattr(named[0], "fullName", "") or "").strip()


async def adopt_allow_list_name(email: Any, full_name: Any, *, client: Any = None) -> bool:
    cleaned = str(full_name or "").strip()
    if not cleaned:
        return False
    keys = email_match_keys(email)
    if not keys:
        return False
    writer = db if client is None else client
    existing = await writer.designerroster.find_first(where={"email": {"in": keys}})
    if existing is None:
        return False
    if str(getattr(existing, "fullName", "") or "").strip():
        return False
    await writer.designerroster.update(where={"id": existing.id}, data={"fullName": cleaned})
    return True


async def ensure_empanelled(
    email: Any,
    *,
    actor_id: str | None = None,
    note: str | None = None,
    client: Any = None,
    swallow_race: bool = True,
) -> bool:
    writer = db if client is None else client
    keys = email_match_keys(email)
    if not keys:
        return False
    existing = await writer.designerroster.find_first(where={"email": {"in": keys}})
    if existing is not None:
        return False
    address = canonical_email(email)
    full_name = await name_on_the_allow_list(email, client=client)
    try:
        await writer.designerroster.create(
            data={
                "email": address,
                "fullName": full_name,
                "isActive": True,
                "revokedAt": None,
                "notes": note or DERIVED_EMPANELMENT_NOTE,
                "addedById": actor_id,
            }
        )
    except UniqueViolationError:
        if not swallow_race:
            raise
        return False
    return True


def note_recording_a_consequence(existing: Any, sentence: str) -> str:
    kept = str(existing or "").strip()
    if not kept:
        return sentence
    if sentence in kept:
        return kept
    return f"{kept}\n\n{sentence}"


async def suspend_empanelment(email: Any, *, because: str) -> int:
    keys = email_match_keys(email)
    if not keys:
        return 0
    rows = await db.designerroster.find_many(where={"email": {"in": keys}, "isActive": True})
    now = datetime.now(UTC)
    suspended = 0
    for row in rows:
        suspended += await db.designerroster.update_many(
            where={"id": row.id, "isActive": True},
            data={
                "isActive": False,
                "revokedAt": row.revokedAt or now,
                "notes": note_recording_a_consequence(row.notes, because),
            },
        )
    return suspended


async def carry_ended_empanelment(old_email: Any, new_email: Any, *, because: str) -> bool:
    old_keys = email_match_keys(old_email)
    new_keys = email_match_keys(new_email)
    if not old_keys or not new_keys:
        return False
    ended = [
        row
        for row in await db.designerroster.find_many(where={"email": {"in": old_keys}})
        if not row.isActive
    ]
    if not ended:
        return False
    now = datetime.now(UTC)
    destination = await db.designerroster.find_many(where={"email": {"in": new_keys}})
    if not destination:
        source = ended[0]
        try:
            await db.designerroster.create(
                data={
                    "email": canonical_email(new_email),
                    "fullName": source.fullName,
                    "institution": source.institution,
                    "isActive": False,
                    "revokedAt": source.revokedAt or now,
                    "notes": because,
                }
            )
        except UniqueViolationError:
            destination = await db.designerroster.find_many(where={"email": {"in": new_keys}})
        else:
            return True
    ended_here = 0
    for row in destination:
        ended_here += await db.designerroster.update_many(
            where={"id": row.id, "isActive": True},
            data={
                "isActive": False,
                "revokedAt": row.revokedAt or now,
                "notes": note_recording_a_consequence(row.notes, because),
            },
        )
    return ended_here > 0


async def mark_roster_seen(email: Any) -> None:
    keys = email_match_keys(email)
    if not keys:
        return
    await db.designerroster.update_many(
        where={"email": {"in": keys}, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


def roster_payload(row: Any) -> dict[str, Any]:
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
        "addedByName": person_name(getattr(row, "addedBy", None)),
    }


def person_name(user: Any) -> str | None:
    if user is None:
        return None
    name = str(getattr(user, "name", "") or "").strip()
    return name or (getattr(user, "email", None) or None)


DIRECTORY_TAKE = 500


def workshop_capable_roles() -> list[str]:
    from app.core.deps import DESIGN_WORKSHOP_ROLES

    return sorted(DESIGN_WORKSHOP_ROLES)


NEVER_ROSTER_GATED_ROLES = ["ADMIN", "MASTER_ADMIN"]

ROSTER_GATED_ROLE = "DESIGNER"


def roster_exempt_workshop_roles() -> list[str]:
    return [role for role in workshop_capable_roles() if role != ROSTER_GATED_ROLE]


async def workshop_capable_accounts(
    *,
    search: str | None = None,
    include_suspended: bool = False,
    include_admins: bool = True,
    exclude_user_id: str | None = None,
) -> list[Any]:
    from app.services.design_workshop_viewers import active_roster_emails
    from app.services.records import contains

    roles = workshop_capable_roles() if include_admins else [ROSTER_GATED_ROLE]
    clauses: list[dict[str, Any]] = [{"role": {"in": roles}}]
    if exclude_user_id:
        clauses.append({"id": {"not": exclude_user_id}})
    if not include_suspended:
        admitted, _roster_read_was_cut = await active_roster_emails()
        arms: list[dict[str, Any]] = []
        if include_admins:
            arms.append({"role": {"in": roster_exempt_workshop_roles()}})
        arms.append(
            {
                "AND": [
                    {"role": ROSTER_GATED_ROLE},
                    {"email": {"in": admitted, "mode": "insensitive"}},
                ]
            }
        )
        clauses.append({"OR": arms})
    if search:
        token = search.strip()
        clauses.append({"OR": [{"name": contains(token)}, {"email": contains(token)}]})
    return await db.user.find_many(
        where={"AND": clauses},
        order=[{"name": "asc"}, {"id": "asc"}],
        take=DIRECTORY_TAKE,
    )


async def roster_directory_payload(
    users: list[Any], *, include_suspended: bool
) -> list[dict[str, Any]]:
    if not users:
        return []
    emails = sorted({u.email for u in users})
    ids = sorted({u.id for u in users})
    roster_rows, profiles = await gather_reads(
        db.designerroster.find_many(where={"email": {"in": emails}}),
        db.designerprofile.find_many(where={"userId": {"in": ids}}),
    )
    by_email = {r.email.lower(): r for r in roster_rows}
    by_user = {p.userId: p for p in profiles}

    out: list[dict[str, Any]] = []
    for user in users:
        roster = by_email.get((user.email or "").lower())
        profile = by_user.get(user.id)
        gated = _role_of(user) == "DESIGNER"
        can_sign_in = bool(roster and roster.isActive) if gated else True
        if gated and not can_sign_in and not include_suspended:
            continue
        out.append(
            {
                "id": user.id,
                "name": user.name,
                "email": user.email,
                "role": _role_of(user),
                "institution": (
                    getattr(profile, "institution", None) or getattr(roster, "institution", None)
                ),
                "rosterId": getattr(roster, "id", None),
                "rosterActive": bool(roster and roster.isActive),
                "canSignIn": can_sign_in,
                "firstSeenAt": (
                    roster.firstSeenAt.isoformat()
                    if roster is not None and roster.firstSeenAt
                    else None
                ),
                "hasProfile": profile is not None,
            }
        )
    return out


def assignable_designers_payload(users: list[Any]) -> list[dict[str, Any]]:
    return [{"id": u.id, "name": u.name, "email": u.email, "role": _role_of(u)} for u in users]


def _role_of(user: Any) -> str:
    role = getattr(user, "role", None)
    return str(getattr(role, "value", role) or "")



async def get_or_create_profile(user_id: str, *, client: Any = None) -> Any:
    writer = db if client is None else client
    return await writer.designerprofile.upsert(
        where={"userId": user_id},
        data={"create": {"user": {"connect": {"id": user_id}}}, "update": {}},
        include=PROFILE_INCLUDE,
    )


async def update_profile(user_id: str, values: dict[str, Any]) -> Any:
    data: dict[str, Any] = {}
    for key in PROFILE_FIELDS:
        if key not in values:
            continue
        value = values[key]
        if key in PROFILE_DATE_FIELDS:
            data[key] = _parse_date(value)
        elif isinstance(value, str):
            data[key] = value.strip() or None
        else:
            data[key] = value
    if "locationId" in values:
        data["locationId"] = values["locationId"]

    if "phone" in data or "phoneCountryCode" in data:
        try:
            apply_phone(data, await db.designerprofile.find_unique(where={"userId": user_id}))
        except StandardValueError as exc:
            raise HTTPException(status_code=422, detail=exc.message) from exc
    if "phone" in data or "empanelmentNo" in data:
        stored = await db.designerprofile.find_unique(where={"userId": user_id})
        keys = await identity.resolve_profile_keys(
            user_id=user_id,
            phone=data.get("phone", getattr(stored, "phone", None)),
            empanelment_no=data.get("empanelmentNo", getattr(stored, "empanelmentNo", None)),
        )
        data["phoneKey"] = keys.phone_key
        data["empanelmentKey"] = keys.empanelment_key

    if not data:
        return await get_or_create_profile(user_id)
    return await db.designerprofile.upsert(
        where={"userId": user_id},
        data={"create": {**data, "user": {"connect": {"id": user_id}}}, "update": data},
        include=PROFILE_INCLUDE,
    )


PROFILE_CONTENT_FIELDS: tuple[str, ...] = tuple(
    key for key in PROFILE_FIELDS if key not in {"empanelmentNo", "empanelmentDate"}
)


def profile_has_content(row: Any) -> bool:
    if row is None:
        return False
    for key in PROFILE_CONTENT_FIELDS:
        value = getattr(row, key, None)
        if value is None:
            continue
        if isinstance(value, str) and not value.strip():
            continue
        return True
    return False


def values_carry_content(values: dict[str, Any]) -> bool:
    for key in PROFILE_CONTENT_FIELDS:
        if key not in values:
            continue
        value = values[key]
        if value is None:
            continue
        if isinstance(value, str) and not value.strip():
            continue
        return True
    return bool(values.get("locationId"))


def profile_payload(row: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {"id": row.id, "userId": row.userId}
    for key in PROFILE_FIELDS:
        value = getattr(row, key, None)
        payload[key] = _iso(value) if isinstance(value, datetime) else value
    payload["locationId"] = getattr(row, "locationId", None)
    location = getattr(row, "location", None)
    payload["location"] = jsonable_encoder(location) if location is not None else None

    payload["signInByPhone"] = getattr(row, "phoneKey", None) is not None
    payload["signInByEmpanelmentNo"] = getattr(row, "empanelmentKey", None) is not None
    payload["empanelmentNoMissing"] = profile_has_content(row) and not (
        str(getattr(row, "empanelmentNo", "") or "").strip()
    )
    payload["createdAt"] = _iso(row.createdAt)
    payload["updatedAt"] = _iso(row.updatedAt)
    return payload


PREFILL_MAP: tuple[tuple[str, str], ...] = (
    ("displayName", "designerName"),
    ("institution", "designerInstitution"),
    ("biography", "designerProfile"),
    ("experienceYears", "designerExperience"),
    ("localName", "designerLocalName"),
    ("designation", "designerDesignation"),
    ("department", "designerDepartment"),
    ("qualification", "designerQualification"),
    ("specialisation", "designerSpecialisation"),
    ("phone", "designerPhone"),
    ("email", "designerEmail"),
    ("website", "designerWebsite"),
    ("addressLine", "designerAddress"),
    ("city", "designerCity"),
    ("state", "designerState"),
    ("pincode", "designerPincode"),
    ("empanelmentNo", "designerEmpanelmentNo"),
    ("empanelmentDate", "designerEmpanelmentDate"),
    ("photoMediaId", "designerPhoto"),
    ("signatureMediaId", "designerSignature"),
    ("cvMediaId", "designerCv"),
)

_SINGLE_LINE_PREFILL_COLUMNS: frozenset[str] = frozenset({"addressLine"})


def _fold_to_one_line(text: str) -> str:
    folded = text.replace("\r\n", "\n").replace("\r", "\n")
    lines = [part.strip() for part in folded.split("\n")]
    return " ".join(part for part in lines if part)


_LOCATION_FALLBACK: dict[str, tuple[str, ...]] = {
    "addressLine": ("address",),
    "city": ("district", "placeName"),
    "state": ("state",),
    "pincode": ("pincode",),
}


def _location_fallback(location: Any, column: str) -> str | None:
    if location is None:
        return None
    for attribute in _LOCATION_FALLBACK.get(column, ()):
        value = getattr(location, attribute, None)
        if isinstance(value, str) and value.strip():
            return value
    return None


async def prefill_from_profile(user_id: str) -> dict[str, Any]:
    profile = await db.designerprofile.find_unique(
        where={"userId": user_id}, include={"user": True, "location": True}
    )
    if profile is None:
        return {}

    values: dict[str, Any] = {}
    for column, field_key in PREFILL_MAP:
        value = getattr(profile, column, None)
        if value in (None, "") and column in _LOCATION_FALLBACK:
            value = _location_fallback(getattr(profile, "location", None), column)
        if column == "phone":
            value = compose_phone(getattr(profile, "phoneCountryCode", None), value)
        if isinstance(value, str):
            value = rich_text.plain_from_stored(value)
            if column in _SINGLE_LINE_PREFILL_COLUMNS:
                value = _fold_to_one_line(value)
            value = value.strip()
        if isinstance(value, datetime):
            value = value.date().isoformat()
        if value in (None, ""):
            continue
        values[field_key] = value

    if "designerName" not in values:
        account_name = str(getattr(getattr(profile, "user", None), "name", "") or "").strip()
        if account_name:
            values["designerName"] = account_name
    return values



def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


def _parse_date(raw: Any) -> datetime | None:
    if raw in (None, ""):
        return None
    if isinstance(raw, datetime):
        return raw if raw.tzinfo else raw.replace(tzinfo=UTC)
    try:
        return datetime.fromisoformat(str(raw)[:10]).replace(tzinfo=UTC)
    except ValueError:
        return None
