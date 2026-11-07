import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.api.routes.users import assert_role
from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    invalidate_cached_user,
    require_access_manager,
    role_value,
)
from app.schemas.access_roster import AccessDecision, AccessRosterCreate, AccessRosterUpdate
from app.schemas.users import UserCreate
from app.services import access_roster, signin_role
from app.services.access_roster import access_payload, normalise_email
from app.services.account_provisioning import create_account
from app.services.designers import (
    adopt_allow_list_name,
    canonical_email,
    email_match_keys,
    ensure_empanelled,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_filters import enum_filter_list_or_422
from app.services.records import (
    Relation,
    add_date_range,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
)

router = APIRouter(prefix="/access", tags=["access"])

ROSTER_PEOPLE = (
    Relation("addedBy", "user", "addedById"),
    Relation("decidedBy", "user", "decidedById"),
)


async def with_roster_people(row: Any) -> Any:
    if row is not None:
        await hydrate_relations([row], ROSTER_PEOPLE)
    return row

logger = logging.getLogger(__name__)

FILTERABLE = frozenset(
    {access_roster.ACTIVE, access_roster.PENDING, access_roster.REJECTED, access_roster.SUSPENDED}
)

ACCESS_ROLE_FILTER_TOKENS = frozenset(ROLE_RANK) | {"default"}

ACCESS_DATE_COLUMNS = {
    "added": "createdAt",
    "requested": "requestedAt",
    "decided": "decidedAt",
    "joined": "joinedAt",
    "firstSeen": "firstSeenAt",
}

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

ACCESS_DEFAULT_SORT = "added"


def _clean(value: Any) -> Any:
    return value.strip() or None if isinstance(value, str) else value



@router.get("/roster/pending-count")
async def pending_request_count(_: Any = Depends(require_access_manager)) -> dict[str, Any]:
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
    where: dict[str, Any] = {}

    statuses = enum_filter_list_or_422(status_filter, FILTERABLE, field="status")
    if statuses is not None:
        where["status"] = {"in": sorted(statuses)}

    if search:
        token = search.strip()
        where["OR"] = [
            {"email": contains(token)},
            {"fullName": contains(token)},
            {"notes": contains(token)},
        ]

    wanted_roles = enum_filter_list_or_422(roles, ACCESS_ROLE_FILTER_TOKENS, field="roles")
    if wanted_roles is not None:
        arms: list[dict[str, Any]] = []
        named = sorted(wanted_roles - {"default"})
        if named:
            arms.append({"admitRole": {"in": named}})
        if "default" in wanted_roles:
            arms.append({"admitRole": None})
        where.setdefault("AND", []).append({"OR": arms})

    if dateField:
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
        relations=ROSTER_PEOPLE,
    )
    return page_payload([access_payload(r) for r in rows], total, clean_page, clean_size) | {
        "roleMatchTruncated": False,
    }



@router.post("/roster", status_code=status.HTTP_201_CREATED)
async def add_to_access_roster(
    payload: AccessRosterCreate,
    current_user: Any = Depends(require_access_manager),
) -> dict[str, Any]:
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
                f" You typed {typed} and it is saved as {stored}; Google delivers both to the "
                "same mailbox, so they count as one address."
            )
        )
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{stored} is already on the access roster as {access_roster.status_of(existing)}. "
                f"Decide or update that entry instead of adding it again.{same_mailbox}"
            ),
        )
    if payload.password is not None:
        return await _admit_with_a_password(payload, email, current_user)
    row = await access_roster.admit(
        email,
        admit_role=payload.role,
        actor_id=current_user.id,
        full_name=_clean(payload.fullName),
        note=_clean(payload.notes),
    )
    held = await _role_kept(row.email, access_roster.role_of(row))
    await _empanel_an_admitted_designer(row, current_user.id)
    return _decision_payload(await with_roster_people(row), promotion_held=held)


async def _admit_with_a_password(payload: AccessRosterCreate, email: str, actor: Any) -> dict[str, Any]:
    name = _clean(payload.fullName) or email.split("@", maxsplit=1)[0]
    body = UserCreate(
        email=email,
        name=name[:160],
        password=payload.password,
        role=payload.role or get_settings().default_signup_role,
        mustChangePassword=payload.mustChangePassword,
    )
    await create_account(actor, body, via="on the access list")
    row = await access_roster.access_row(email)
    note = _clean(payload.notes)
    if row is not None and note:
        row = await db.accessroster.update(where={"id": row.id}, data={"notes": note})
    answer = access_payload(await with_roster_people(row)) if row is not None else {"email": email}
    answer["accountCreated"] = True
    return answer


@router.post("/roster/{row_id}/decision")
async def decide_access_request(
    row_id: str,
    payload: AccessDecision,
    current_user: Any = Depends(require_access_manager),
) -> dict[str, Any]:
    row = await db.accessroster.find_unique(where={"id": row_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    if payload.decision == "REJECT":
        was_barred = access_roster.status_of(row) in access_roster.BARRED
        updated = await db.accessroster.update(
            where={"id": row_id},
            data={
                "status": access_roster.REJECTED,
                "decidedAt": _now(),
                "decidedById": current_user.id,
                **({"notes": _clean(payload.notes)} if payload.notes is not None else {}),
            },
        )
        if not was_barred:
            await access_roster.mirror_suspension(
                updated.email, access_roster.MIRROR_ACCESS_REJECTED, actor_id=current_user.id
            )
        await end_live_sessions(updated.email)
        return _decision_payload(await with_roster_people(updated))

    assert_role(payload.role, current_user)
    granted = payload.role or access_roster.role_of(row)
    updated = await access_roster.admit(
        row.email,
        admit_role=granted,
        actor_id=current_user.id,
        note=_clean(payload.notes) if payload.notes is not None else None,
    )
    held = await _role_kept(row.email, granted)
    await _empanel_an_admitted_designer(updated, current_user.id)
    return _decision_payload(await with_roster_people(updated), promotion_held=held)


@router.patch("/roster/{row_id}")
async def update_access_entry(
    row_id: str,
    payload: AccessRosterUpdate,
    current_user: Any = Depends(require_access_manager),
) -> dict[str, Any]:
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
        return access_payload(await with_roster_people(row))
    updated = await db.accessroster.update(where={"id": row_id}, data=data)
    await _empanel_an_admitted_designer(updated, current_user.id)
    return access_payload(await with_roster_people(updated))


@router.delete("/roster/{row_id}")
async def suspend_access_entry(
    row_id: str, current_user: Any = Depends(require_access_manager)
) -> dict[str, Any]:
    row = await db.accessroster.find_unique(where={"id": row_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    was_barred = access_roster.status_of(row) in access_roster.BARRED
    if access_roster.status_of(row) == access_roster.SUSPENDED:
        return access_payload(await with_roster_people(row))
    updated = await db.accessroster.update(
        where={"id": row_id},
        data={
            "status": access_roster.SUSPENDED,
            "decidedAt": row.decidedAt or _now(),
            "decidedById": row.decidedById or current_user.id,
        },
    )
    if not was_barred:
        await access_roster.mirror_suspension(
            updated.email, access_roster.MIRROR_ACCESS_SUSPENDED, actor_id=current_user.id
        )
    await end_live_sessions(updated.email)
    return access_payload(await with_roster_people(updated))



async def _empanel_an_admitted_designer(row: Any, actor_id: str | None) -> None:
    if access_roster.status_of(row) != access_roster.ACTIVE:
        return
    if access_roster.role_of(row) != "DESIGNER":
        return
    created = await ensure_empanelled(getattr(row, "email", None), actor_id=actor_id)
    if not created:
        await adopt_allow_list_name(getattr(row, "email", None), getattr(row, "fullName", None))


async def end_live_sessions(email: Any) -> None:
    accounts = await access_roster.accounts_on_the_mailbox(email)
    if accounts is None:
        logger.error(
            "sessions for %r were ended only on the accounts at this exact address: the sweep that "
            "finds every spelling of one mailbox was cut, so a token held by an account filed "
            "under another spelling may still be live until it expires. The allow-list row was "
            "barred and the next sign-in is refused. Raise GMAIL_ACCOUNT_SWEEP_LIMIT, then re-run "
            "the bar, or repair it from backend/ with python -m scripts.backfill_sessions_valid_from "
            "--write",
            email,
        )
        accounts = await access_roster.accounts_at_the_address(email)
    now = _now()
    for user in accounts:
        await db.user.update(where={"id": user.id}, data={"sessionsValidFrom": now})
        invalidate_cached_user(user.id)


def _decision_payload(row: Any, *, promotion_held: str | None = None) -> dict[str, Any]:
    return {**access_payload(row), "accountPromotionHeld": promotion_held}


async def _role_kept(email: str, role: str | None) -> str | None:
    if not role:
        return None
    address = normalise_email(email)
    user = await db.user.find_unique(where={"email": address})
    if user is None or role_value(user) == "MASTER_ADMIN":
        return None
    return signin_role.role_kept_sentence(address, role_value(user), role)


def _now() -> datetime:
    return datetime.now(UTC)
