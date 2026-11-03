from collections.abc import Collection
from typing import Any

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.services.measurement_provenance import MARKER_BODY_KEY
from prisma import Json

TIER_ORDER = {"DOWNLOAD": 1, "COMMENT": 2, "EDIT": 3}

TIER_DESCRIPTIONS = {
    "DOWNLOAD": "Minimum — download this researcher's data (the whole set, or the shared subset).",
    "COMMENT": "Medium — everything in Download, plus leave comments on their entries.",
    "EDIT": "Maximum — everything in Comment, plus edit fields on their entries (every change is "
    "tracked with its author; admins see the original for all but identity and contact fields, "
    "where only the fact and direction of the change is kept).",
}


def _enum_str(value: Any) -> str:
    return str(getattr(value, "value", value))


def tier_at_least(tier: str | None, minimum: str) -> bool:
    if not tier:
        return False
    return TIER_ORDER.get(tier, 0) >= TIER_ORDER.get(minimum, 99)


async def active_grant(grantee_id: str, owner_id: str) -> Any:
    if not grantee_id or not owner_id:
        return None
    return await db.dataaccessgrant.find_unique(
        where={"ownerId_granteeId": {"ownerId": owner_id, "granteeId": grantee_id}},
        include={"scopeItems": True},
    )


def _grant_covers(grant: Any, record_type: str, record_id: str) -> bool:
    if grant.allData:
        return True
    for item in grant.scopeItems or []:
        if item.recordType.lower() == record_type.lower() and item.recordId == record_id:
            return True
    return False


async def effective_tier_for_record(
    user: Any, owner_id: str | None, record_type: str, record_id: str | None
) -> str | None:
    from app.core.deps import get_value, is_content_admin

    if is_content_admin(user):
        return "EDIT"
    uid = get_value(user, "id")
    if owner_id and uid == owner_id:
        return "EDIT"
    grant = await active_grant(uid, owner_id) if owner_id else None
    if not grant or _enum_str(grant.status) != "GRANTED":
        return None
    if record_id is not None and not _grant_covers(grant, record_type, record_id):
        return None
    return _enum_str(grant.tier)


REVISION_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "updatedAt",
    "createdAt",
    "createdById",
    "recordedAt",
    "recordedTimezone",
    MARKER_BODY_KEY,
}


REVISION_REDACTED_FIELDS = {
    "aadhaarNumber",
    "pehchanCardNumber",
    "phone",
    "email",
    "address",
}


_REDACTED_HAD_VALUE = "(value recorded)"
_REDACTED_CLEARED = "(cleared)"
_REDACTED_REPLACED = "(value replaced)"
_REDACTED_EMPTY = "(empty)"
_REDACTED_STILL_EMPTY = "(still empty)"

REDACTED_PLACEHOLDER_PAIRS = frozenset(
    {
        (_REDACTED_EMPTY, _REDACTED_STILL_EMPTY),
        (_REDACTED_HAD_VALUE, _REDACTED_CLEARED),
        (_REDACTED_HAD_VALUE, _REDACTED_REPLACED),
        (_REDACTED_EMPTY, _REDACTED_HAD_VALUE),
    }
)

REDACTED_CHANGE_KEYS = frozenset({"old", "new", "redacted"})


def _redacted_change(old_value: Any, new_value: Any) -> dict[str, Any]:
    from app.core.deps import is_empty_value

    had = not is_empty_value(old_value)
    has = not is_empty_value(new_value)
    if not had and not has:
        return {"old": _REDACTED_EMPTY, "new": _REDACTED_STILL_EMPTY, "redacted": True}
    if not has:
        return {"old": _REDACTED_HAD_VALUE, "new": _REDACTED_CLEARED, "redacted": True}
    if had:
        return {"old": _REDACTED_HAD_VALUE, "new": _REDACTED_REPLACED, "redacted": True}
    return {"old": _REDACTED_EMPTY, "new": _REDACTED_HAD_VALUE, "redacted": True}


def redacted_placeholder(old_value: Any, new_value: Any) -> dict[str, Any]:
    return _redacted_change(old_value, new_value)


async def record_revision(
    record: Any,
    user: Any,
    data: dict[str, Any],
    record_type: str,
    *,
    client: Any = None,
) -> None:
    from app.core.deps import get_value, values_match

    changes: dict[str, Any] = {}
    for field, new_value in data.items():
        if field in REVISION_SKIP_FIELDS:
            continue
        old_value = get_value(record, field)
        if values_match(old_value, new_value):
            continue
        if field in REVISION_REDACTED_FIELDS:
            changes[field] = _redacted_change(old_value, new_value)
        else:
            changes[field] = {
                "old": jsonable_encoder(old_value),
                "new": jsonable_encoder(new_value),
            }
    if "aadhaarFingerprint" in changes or "aadhaarLast4" in changes:
        from app.services.aadhaar_fingerprint import has_aadhaar

        changes.pop("aadhaarFingerprint", None)
        changes.pop("aadhaarLast4", None)
        changes["aadhaarNumber"] = _redacted_change(
            "had" if has_aadhaar(record) else None,
            "has" if data.get("aadhaarFingerprint") else None,
        )
    if not changes:
        return
    writer = db if client is None else client
    await writer.recordrevision.create(
        data={
            "recordType": record_type.lower(),
            "recordId": get_value(record, "id"),
            "editedById": get_value(user, "id"),
            "changes": Json(changes),
        }
    )


async def guard_record_edit(
    record: Any,
    user: Any,
    data: dict[str, Any],
    record_type: str,
    *,
    client: Any = None,
    derived: Collection[str] = (),
) -> bool:
    from app.core.deps import (
        assert_can_contribute_fields,
        get_value,
        is_content_admin,
        may_edit_lower_ranked_record,
    )

    owner_id = get_value(record, "createdById")
    uid = get_value(user, "id")
    privileged = is_content_admin(user) or (owner_id is not None and uid == owner_id)
    if not privileged:
        if await may_edit_lower_ranked_record(user, owner_id) or (
            await effective_tier_for_record(user, owner_id, record_type, get_value(record, "id"))
            == "EDIT"
        ):
            privileged = True
        else:
            assert_can_contribute_fields(
                record,
                user,
                {key: value for key, value in data.items() if key not in derived}
                if derived
                else data,
            )
    await record_revision(record, user, data, record_type, client=client)
    return privileged


async def assert_can_comment(
    user: Any, owner_id: str | None, record_type: str, record_id: str
) -> None:
    tier = await effective_tier_for_record(user, owner_id, record_type, record_id)
    if not tier_at_least(tier, "COMMENT"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need comment access to this researcher's data. Request it from them.",
        )


async def owner_download_scope(user: Any, owner_id: str) -> dict[str, set[str]] | None:
    from app.core.deps import can_download_dataset, get_value, is_admin

    if is_admin(user) or can_download_dataset(user) or get_value(user, "id") == owner_id:
        return None
    grant = await active_grant(get_value(user, "id"), owner_id)
    if not (
        grant
        and _enum_str(grant.status) == "GRANTED"
        and tier_at_least(_enum_str(grant.tier), "DOWNLOAD")
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You need download access to this researcher's data. Request it from them.",
        )
    if grant.allData:
        return None
    scope: dict[str, set[str]] = {}
    for item in grant.scopeItems or []:
        scope.setdefault(item.recordType.lower(), set()).add(item.recordId)
    return scope
