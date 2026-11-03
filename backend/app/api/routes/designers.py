import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.api.routes.access import end_live_sessions
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    is_admin,
    require_designer,
    require_designer_roster_manager,
)
from app.schemas.designers import (
    DesignerProfileUpdate,
    DesignerRosterCreate,
    DesignerRosterUpdate,
)

from app.services import access_roster
from app.services.designers import (
    canonical_email,
    email_match_keys,
    get_or_create_profile,
    normalise_email,
    profile_has_content,
    profile_payload,
    roster_directory_payload,
    roster_payload,
    update_profile,
    values_carry_content,
    workshop_capable_accounts,
    workshop_capable_roles,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import enum_filter_list_or_422
from app.services.records import (
    Relation,
    add_date_range,
    attach_location,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
)

router = APIRouter(prefix="/designers", tags=["designers"])


ROSTER_ADDED_BY = (Relation("addedBy", "user", "addedById"),)


async def with_added_by(row: Any) -> Any:
    if row is not None:
        await hydrate_relations([row], ROSTER_ADDED_BY)
    return row

logger = logging.getLogger(__name__)

WORKSHOP_CAPABLE_ROLES = workshop_capable_roles()

_PROFILE_NOT_FOUND = "Record not found"


DESIGNER_STANDING_TOKENS: frozenset[str] = frozenset({"active", "suspended"})

ROLE_NEVER_SIGNED_IN = "never-signed-in"

DESIGNER_ROLE_FILTER_TOKENS: frozenset[str] = frozenset(ROLE_RANK) | {ROLE_NEVER_SIGNED_IN}

ROLE_MATCH_READ_LIMIT = 50_000

DESIGNER_DATE_COLUMNS: dict[str, str] = {
    "added": "createdAt",
    "firstSeen": "firstSeenAt",
    "revoked": "revokedAt",
}

DESIGNER_SORT_COLUMNS: dict[str, str] = {
    "added": "createdAt",
    "email": "email",
    "name": "fullName",
    "institution": "institution",
    "firstSeen": "firstSeenAt",
    "revoked": "revokedAt",
}

DESIGNER_SORT_DEFAULT_DIR: dict[str, str] = {
    "added": "desc",
    "email": "asc",
    "name": "asc",
    "institution": "asc",
    "firstSeen": "desc",
    "revoked": "desc",
}

DESIGNER_DEFAULT_SORT = "added"

INSTITUTION_NONE = "none"

INSTITUTION_LIST_CAP = 200



@router.get("/roster")
async def list_roster(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    activeOnly: bool = False,
    standing: str | None = Query(default=None),
    roles: list[str] | None = Query(default=None),
    institutions: list[str] | None = Query(default=None),
    dateField: str | None = Query(default=None),
    dateFrom: datetime | None = Query(default=None),
    dateTo: datetime | None = Query(default=None),
    sort: str | None = Query(default=None),
    dir: str | None = Query(default=None),
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    where: dict[str, Any] = {}

    if standing:
        standing = enum_filter_or_422(standing, DESIGNER_STANDING_TOKENS, field="standing")
    implied_standing = "active" if activeOnly else None
    if standing and implied_standing and standing != implied_standing:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"activeOnly=true and standing={standing!r} disagree about which designers to "
                "include: activeOnly=true means standing='active'. Send only one of the two — a "
                "request naming both, and disagreeing, is almost certainly a stale filter state "
                "rather than a deliberate choice, and guessing which one you meant is worse than "
                "asking."
            ),
        )
    effective_standing = standing or implied_standing
    if effective_standing == "active":
        where["isActive"] = True
    elif effective_standing == "suspended":
        where["isActive"] = False

    if search:
        token = search.strip()
        where["OR"] = [
            {"email": contains(token)},
            {"fullName": contains(token)},
            {"institution": contains(token)},
        ]

    wanted_roles = enum_filter_list_or_422(roles, DESIGNER_ROLE_FILTER_TOKENS, field="roles")
    role_match_truncated = False
    if wanted_roles is not None:
        role_arms: list[dict[str, Any]] = []
        named_roles = sorted(wanted_roles - {ROLE_NEVER_SIGNED_IN})
        if named_roles:
            accounts = await db.user.find_many(
                where={"role": {"in": named_roles}}, take=ROLE_MATCH_READ_LIMIT + 1
            )
            role_match_truncated = len(accounts) > ROLE_MATCH_READ_LIMIT
            if role_match_truncated:
                accounts = accounts[:ROLE_MATCH_READ_LIMIT]
                logger.error(
                    "more than %s accounts hold the filtered roles, so only part of that set was "
                    "read; designer roster rows whose account fell past that cut are missing from "
                    "this list for every filter that names those roles",
                    ROLE_MATCH_READ_LIMIT,
                )
            emails = sorted({normalise_email(a.email) for a in accounts})
            role_arms.append({"email": {"in": emails, "mode": "insensitive"}})
        if ROLE_NEVER_SIGNED_IN in wanted_roles:
            role_arms.append({"firstSeenAt": None})
        where.setdefault("AND", []).append({"OR": role_arms or [{"id": {"in": []}}]})

    resolved_institutions = _resolve_institutions(institutions)
    if resolved_institutions is not None:
        names, include_none = resolved_institutions
        institution_arms: list[dict[str, Any]] = []
        if names:
            institution_arms.append({"institution": {"in": names}})
        if include_none:
            institution_arms.append({"institution": None})
        where.setdefault("AND", []).append({"OR": institution_arms or [{"id": {"in": []}}]})

    if dateField:
        column = DESIGNER_DATE_COLUMNS[
            enum_filter_or_422(dateField, frozenset(DESIGNER_DATE_COLUMNS), field="dateField")
        ]
        add_date_range(where, column, dateFrom, dateTo)

    sort_token = enum_filter_or_422(
        sort or DESIGNER_DEFAULT_SORT, frozenset(DESIGNER_SORT_COLUMNS), field="sort"
    )
    dir_token = enum_filter_or_422(
        dir or DESIGNER_SORT_DEFAULT_DIR[sort_token], frozenset({"asc", "desc"}), field="dir"
    )

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await count_and_page(
        db.designerroster,
        where=where,
        skip=skip,
        take=clean_size,
        order={DESIGNER_SORT_COLUMNS[sort_token]: dir_token},
        relations=ROSTER_ADDED_BY,
    )
    return page_payload([roster_payload(r) for r in rows], total, clean_page, clean_size) | {
        "roleMatchTruncated": role_match_truncated,
    }


@router.get("/roster/institutions")
async def list_roster_institutions(
    _: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    rows = await db.designerroster.find_many(
        where={"AND": [{"institution": {"not": None}}, {"institution": {"not": ""}}]},
        distinct=["institution"],
        order=[{"institution": "asc"}],
        take=INSTITUTION_LIST_CAP + 1,
    )
    names = [row.institution for row in rows]
    truncated = len(names) > INSTITUTION_LIST_CAP
    if truncated:
        names = names[:INSTITUTION_LIST_CAP]
        logger.warning(
            "more than %s distinct institutions are on the designer roster, so the institution "
            "filter's vocabulary was cut; an institution past that cut cannot be ticked in the "
            "picker until this cap is raised, though it is still reachable through the roster's "
            "own free-text search",
            INSTITUTION_LIST_CAP,
        )
    return {"items": names, "total": len(names), "truncated": truncated}


@router.post("/roster", status_code=status.HTTP_201_CREATED)
async def add_to_roster(
    payload: DesignerRosterCreate,
    current_user: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    email = canonical_email(payload.email)
    existing = await db.designerroster.find_first(
        where={"email": {"in": email_match_keys(payload.email)}}
    )
    if existing is not None:
        typed = normalise_email(payload.email)
        stored = normalise_email(existing.email)
        same_mailbox = (
            ""
            if stored == typed
            else (
                f" You typed {typed} and it is saved as {stored}; Google delivers both to the "
                "same mailbox, so they count as one address."
            )
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{stored} is already on the roster"
                f"{'' if existing.isActive else ' (suspended)'}. "
                "Update or restore that roster entry instead of adding it again."
                f"{same_mailbox}"
            ),
        )
    row = await db.designerroster.create(
        data={
            "email": email,
            "fullName": _clean(payload.fullName),
            "institution": _clean(payload.institution),
            "notes": _clean(payload.notes),
            "isActive": payload.isActive,
            "revokedAt": None if payload.isActive else datetime.now(UTC),
            "addedById": current_user.id,
        }
    )
    return roster_payload(await with_added_by(row))


async def _end_what_the_empanelment_carried(email: Any, *, actor_id: str | None) -> None:
    carried = await access_roster.admissions_an_empanelment_carries(email)
    await access_roster.mirror_suspension(
        email, access_roster.MIRROR_EMPANELMENT_ENDED, actor_id=actor_id
    )
    if carried is None:
        logger.error(
            "sessions for %r could NOT be confirmed ended after the empanelment was ended: the "
            "sweep that finds every spelling of one mailbox was cut, so any token this person is "
            "already holding may still be live until it expires. The empanelment is ended and the "
            "next sign-in is refused. Raise GMAIL_ACCOUNT_SWEEP_LIMIT, then re-run the removal, or "
            "repair it from backend/ with python -m scripts.backfill_sessions_valid_from --write",
            email,
        )
    elif carried:
        await end_live_sessions(email)


@router.patch("/roster/{roster_id}")
async def update_roster_entry(
    roster_id: str,
    payload: DesignerRosterUpdate,
    current_user: Any = Depends(require_designer_roster_manager),
) -> dict[str, Any]:
    row = await db.designerroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    values = payload.model_dump(exclude_unset=True)
    data: dict[str, Any] = {}
    if "email" in values:
        email = canonical_email(values["email"])
        if email and email != row.email:
            clash = await db.designerroster.find_first(
                where={"email": {"in": email_match_keys(values["email"])}}
            )
            if clash is not None:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"{normalise_email(clash.email)} is already on the roster.",
                )
            data["email"] = email
    for key in ("fullName", "institution", "notes"):
        if key in values:
            data[key] = _clean(values[key])
    if "isActive" in values and values["isActive"] is not None:
        data["isActive"] = bool(values["isActive"])
        data["revokedAt"] = None if data["isActive"] else datetime.now(UTC)
    if not data:
        return roster_payload(await with_added_by(row))
    updated = await db.designerroster.update(where={"id": roster_id}, data=data)
    if data.get("isActive") is False and row.isActive:
        await _end_what_the_empanelment_carried(updated.email, actor_id=current_user.id)
    return roster_payload(await with_added_by(updated))


@router.delete("/roster/{roster_id}")
async def suspend_roster_entry(
    roster_id: str, current_user: Any = Depends(require_designer_roster_manager)
) -> dict[str, Any]:
    row = await db.designerroster.find_unique(where={"id": roster_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not row.isActive and row.revokedAt is not None:
        return roster_payload(await with_added_by(row))
    updated = await db.designerroster.update(
        where={"id": roster_id},
        data={"isActive": False, "revokedAt": row.revokedAt or datetime.now(UTC)},
    )
    await _end_what_the_empanelment_carried(updated.email, actor_id=current_user.id)
    return roster_payload(await with_added_by(updated))



@router.get("/directory")
async def designer_directory(
    search: str | None = None,
    includeSuspended: bool = False,
    _: Any = Depends(require_designer_roster_manager),
) -> list[dict[str, Any]]:
    users = await workshop_capable_accounts(search=search, include_suspended=includeSuspended)
    return await roster_directory_payload(users, include_suspended=includeSuspended)



_EMPANELMENT_REQUIRED = (
    "An empanelment number is required to create a designer profile. Enter the number the "
    "institution issued you."
)


async def _assert_empanelment_number(user_id: str, values: dict[str, Any]) -> None:
    number = values.get("empanelmentNo", _UNSET)
    if number is not _UNSET and str(number or "").strip():
        return
    stored = await db.designerprofile.find_unique(where={"userId": user_id})
    if number is _UNSET and str(getattr(stored, "empanelmentNo", "") or "").strip():
        return
    if profile_has_content(stored):
        return
    if not values_carry_content(values):
        return
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=_EMPANELMENT_REQUIRED
    )


_UNSET = object()


@router.get("/me/profile")
async def get_my_profile(current_user: Any = Depends(require_designer)) -> dict[str, Any]:
    return profile_payload(await get_or_create_profile(current_user.id))


@router.put("/me/profile")
async def put_my_profile(
    payload: DesignerProfileUpdate, current_user: Any = Depends(require_designer)
) -> dict[str, Any]:
    values = payload.model_dump(exclude_unset=True)
    values = await attach_location(values)
    await _assert_empanelment_number(current_user.id, values)
    return profile_payload(await update_profile(current_user.id, values))


@router.get("/{user_id}/profile")
async def get_designer_profile(
    user_id: str, current_user: Any = Depends(require_designer)
) -> dict[str, Any]:
    await _assert_may_touch_profile(user_id, current_user)
    return profile_payload(await get_or_create_profile(user_id))


@router.put("/{user_id}/profile")
async def put_designer_profile(
    user_id: str,
    payload: DesignerProfileUpdate,
    current_user: Any = Depends(require_designer),
) -> dict[str, Any]:
    await _assert_may_touch_profile(user_id, current_user)
    values = payload.model_dump(exclude_unset=True)
    values = await attach_location(values)
    await _assert_empanelment_number(user_id, values)
    return profile_payload(await update_profile(user_id, values))



async def _assert_may_touch_profile(user_id: str, current_user: Any) -> None:
    if user_id != current_user.id and not is_admin(current_user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_PROFILE_NOT_FOUND)
    target = await db.user.find_unique(where={"id": user_id})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=_PROFILE_NOT_FOUND)


def _clean(value: Any) -> Any:
    if isinstance(value, str):
        return value.strip() or None
    return value


def _resolve_institutions(raw: list[str] | None) -> tuple[list[str], bool] | None:
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return None
    include_none = INSTITUTION_NONE in wanted
    names = list(dict.fromkeys(value for value in wanted if value != INSTITUTION_NONE))
    return names, include_none
