from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Body, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    has_rank,
    require_admin,
    require_content_admin,
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
from app.services.artisan_merge import redirect_payload
from app.services.completeness import (
    attach as attach_completeness,
    filter_or_422 as completeness_filter,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    lock_and_assert_expected_updated_at,
    merge_field_provenance,
    public_encode,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.text_format import by_count
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

_CLEARABLE_COLUMNS = ("startDate", "endDate", "description", "notes")

RELATIONS = (
    Relation("location", "location", "locationId"),
    Relation("createdBy", "user", "createdById"),
    Relation("artisans", "workshopartisan", "workshopId", many=True, include={"artisan": True}),
    Relation("crafts", "workshopcraft", "workshopId", many=True, include={"craft": True}),
)

ASSIGNMENT_INCLUDE: dict[str, Any] = {
    "user": True,
    "assignedBy": True,
    "requestedBy": True,
    "decidedBy": True,
}
REQUEST_INCLUDE: dict[str, Any] = {**ASSIGNMENT_INCLUDE, "workshop": True}


def _level_or_422(value: Any, fallback: str | None) -> str | None:
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
    workshopType: str | None = None,
    createdBy: str | None = None,
    accessibleOnly: bool = False,
    completeness: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if accessibleOnly:
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
    complete_clause = completeness_filter("workshop", completeness)
    if complete_clause:
        where.setdefault("AND", []).append(complete_clause)
    add_date_range(where, "startDate", dateFrom, dateTo)
    total, items = await count_and_page(
        db.workshop,
        where=where,
        skip=skip,
        take=page_size,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}],
        relations=RELATIONS,
    )
    return page_payload(await attach_completeness("workshop", public_encode(items)), total, page, page_size)


WORKSHOP_CREATE_REFUSAL = (
    "Workshops are created by the ministry — a Ministry Admin, an admin or the master admin. Ask "
    "them to open the workshop for your cluster; it will appear in your list as soon as you are "
    "added to it, and you can then record artisans, products, tools, processes and interviews "
    "against it exactly as before. Any workshop you already have access to is open to you now."
)


async def require_workshop_opener(current_user: Any = Depends(get_current_user)) -> Any:
    if not has_rank(current_user, "MINISTRY_ADMIN"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=WORKSHOP_CREATE_REFUSAL,
        )
    return current_user


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop(
    payload: WorkshopCreate,
    current_user: Any = Depends(require_workshop_opener),
) -> dict[str, Any]:
    replayed = await client_key_replay(db.workshop, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        await hydrate_relations([replayed], RELATIONS)
        return await attach_completeness("workshop", public_encode(replayed))
    data = clean_data(payload.model_dump())
    await redirect_payload(data)
    artisan_ids = data.pop("artisanIds", [])
    craft_ids = data.pop("craftIds", [])
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
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
        return await attach_completeness("workshop", public_encode(raced))
    await hydrate_relations([created], RELATIONS)
    return await attach_completeness("workshop", public_encode(created))



@router.get("/unmapped")
async def unmapped_records(_: Any = Depends(require_content_admin)) -> dict[str, Any]:
    return await plan_workshop_mapping()


@router.post("/unmapped/map")
async def map_unmapped_records(
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    return await apply_workshop_mapping(user=current_user)


@router.post("/unmapped/{bucket}/{record_id}")
async def file_one_unmapped_record(
    bucket: str,
    record_id: str,
    workshopId: str = Body(..., embed=True),
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    return await file_one_unmapped(bucket, record_id, workshopId, user=current_user)


@router.delete("/unmapped/{bucket}/{record_id}")
async def discard_one_unmapped_record(
    bucket: str,
    record_id: str,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    return await discard_one_unmapped(bucket, record_id, user=current_user)



@router.get("/access-levels")
async def list_access_levels(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    return [
        {"level": level, "description": WORKSHOP_LEVEL_DESCRIPTIONS[level]}
        for level in WORKSHOP_LEVELS
    ]


@router.get("/requestable")
async def list_requestable_workshops(
    current_user: Any = Depends(get_current_user),
    limit: int = Query(WORKSHOP_REQUEST_MAX, ge=1, le=WORKSHOP_REQUEST_MAX),
    offset: int = Query(0, ge=0),
) -> dict[str, Any]:
    uid = get_value(current_user, "id")
    offset = offset if isinstance(offset, int) else 0
    workshops = await db.workshop.find_many(
        skip=offset,
        take=limit + 1,
        order=[{"startDate": "desc"}, {"date": "desc"}, {"createdAt": "desc"}, {"id": "asc"}],
    )
    truncated = len(workshops) > limit
    workshops = workshops[:limit]
    ids = [w.id for w in workshops]
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
    return {
        "items": public_encode(items),
        "truncated": truncated,
        "nextOffset": offset + len(items) if truncated else None,
    }


@router.post("/access-requests", status_code=status.HTTP_201_CREATED)
async def request_workshop_access(
    payload: WorkshopAccessRequestIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    uid = get_value(current_user, "id")
    level = _level_or_422(payload.accessLevel, DEFAULT_GRANT_LEVEL)
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
            detail=f"{by_count(len(missing), 'Workshop', 'Workshops')} not found: {', '.join(missing)}",
        )
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
    rows = await db.workshopassignment.find_many(
        where={"workshopId": {"in": wanted}, "userId": uid}, include=REQUEST_INCLUDE
    )
    return {"outcomes": outcomes, "requests": public_encode(rows)}


@router.get("/access-requests/mine")
async def my_workshop_access(
    current_user: Any = Depends(get_current_user), statusFilter: str | None = None
) -> list[dict[str, Any]]:
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
    decision = _status_or_422(payload.status, ("GRANTED", "DENIED"))
    row = await db.workshopassignment.find_unique(where={"id": request_id})
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Access request not found"
        )
    if enum_str(row.status) != "PENDING":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This request was already decided. Change access from the list.",
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
    return await attach_completeness("workshop", public_encode(workshop))


@router.patch("/{workshop_id}")
async def update_workshop(
    workshop_id: str,
    payload: WorkshopUpdate,
    current_user: Any = Depends(require_workshop_manager),
) -> dict[str, Any]:
    workshop = await require_record(db.workshop, workshop_id)
    data = clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    expected_updated_at = take_expected_updated_at(data)
    await redirect_payload(data)
    artisan_ids = data.pop("artisanIds", None)
    craft_ids = data.pop("craftIds", None)
    data = normalize_workshop_dates(data)
    data = await attach_location(data)
    access = await resolve_workshop_access(current_user, workshop_id)
    if get_value(workshop, "createdById") != get_value(current_user, "id") and not access.at_least(
        "CONTRIBUTE"
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=access_denied_detail(access, "CONTRIBUTE")
        )
    async with db.tx() as tx:
        await lock_and_assert_expected_updated_at(tx, "workshop", workshop, expected_updated_at)
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
    await hydrate_relations([updated], RELATIONS)
    return await attach_completeness("workshop", public_encode(updated))


@router.get("/{workshop_id}/submission-check")
async def workshop_submission_check(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    check = await describe_workshop_submission(current_user, workshop_id)
    return check.payload()


@router.get("/{workshop_id}/assignments")
async def list_workshop_assignments(
    workshop_id: str, _: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    await require_record(db.workshop, workshop_id)
    return await _assignment_list(workshop_id)


@router.put("/{workshop_id}/assignments")
async def set_workshop_assignments(
    workshop_id: str, payload: WorkshopAssignmentIn, current_user: Any = Depends(require_admin)
) -> list[dict[str, Any]]:
    await require_record(db.workshop, workshop_id)
    requested_level = _level_or_422(payload.accessLevel, None)
    wanted = {uid for uid in payload.userIds if uid}
    existing = await db.workshopassignment.find_many(where={"workshopId": workshop_id})
    by_user = {r.userId: r for r in existing}
    now = datetime.now(UTC)
    admin_id = get_value(current_user, "id")

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
    data["decidedById"] = get_value(current_user, "id")
    data["decidedAt"] = datetime.now(UTC)
    if "note" in payload.model_fields_set:
        data["decisionNote"] = payload.note
    await db.workshopassignment.update(where={"id": row.id}, data=data)
    return await _hydrate_assignment(row.id)


@router.delete("/{workshop_id}/assignments/{user_id}")
async def revoke_workshop_assignment(
    workshop_id: str, user_id: str, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
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
