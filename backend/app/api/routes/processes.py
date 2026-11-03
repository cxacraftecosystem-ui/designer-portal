from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    enum_or_raw,
    get_current_user,
    require_record_creator,
)
from app.schemas.records import ProcessCreate, ProcessStepInput, ProcessUpdate
from app.services.access import guard_record_edit, record_revision
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import (
    assert_may_write_a_record_filed_under,
    assert_payload_workshop,
)
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    enum_filter_or_422,
    hydrate_relations,
    lock_and_assert_expected_updated_at,
    media_url_owners,
    merge_field_provenance,
    prose_contains,
    public_encode,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/processes", tags=["processes"])

RELATIONS = (
    Relation("product", "productdocumentation", "productId"),
    Relation("createdBy", "user", "createdById"),
    Relation("steps", "processstep", "processId", many=True),
    Relation("workshop", "workshop", "workshopId"),
)

_CLEARABLE_COLUMNS = ("notes",)


def _encode_light(process: Any, viewer: Any) -> dict[str, Any]:
    encoded = public_encode(process, viewer)
    encoded["steps"] = sorted(encoded.get("steps") or [], key=lambda s: s.get("sortOrder", 0))
    return encoded


async def _hydrate(process: Any, viewer: Any) -> dict[str, Any]:
    encoded = _encode_light(process, viewer)
    step_ids = [s["id"] for s in encoded["steps"]]
    lookup_ids = [process.id, *step_ids]
    media = await db.mediafile.find_many(
        where={"linkedRecordId": {"in": lookup_ids}},
        order={"createdAt": "asc"},
    )
    media_encoded = public_encode(media, viewer, media_urls=await media_url_owners(viewer))
    by_record: dict[str, list[dict[str, Any]]] = {}
    for item in media_encoded:
        by_record.setdefault(item.get("linkedRecordId"), []).append(item)
    encoded["media"] = by_record.get(process.id, [])
    for step in encoded["steps"]:
        step["media"] = by_record.get(step["id"], [])
    return encoded


@dataclass
class _StepPlan:
    to_create: list[dict[str, Any]] = field(default_factory=list)
    to_update: list[tuple[str, dict[str, Any]]] = field(default_factory=list)
    removed: list[str] = field(default_factory=list)

    @property
    def touches_existing(self) -> bool:
        return bool(self.to_update or self.removed)

    @property
    def writes_anything(self) -> bool:
        return bool(self.to_create or self.to_update or self.removed)


def _plan_steps(process_id: str, existing: list[Any], steps: list[ProcessStepInput]) -> _StepPlan:
    by_id = {step.id: step for step in existing}
    keep: set[str] = set()
    plan = _StepPlan()
    for index, step in enumerate(steps):
        order = step.sortOrder or index + 1
        notes = (step.notes or "").strip() or None
        current = by_id.get(step.id) if step.id else None
        if current is not None:
            keep.add(step.id)
            unchanged = (
                current.name == step.name
                and str(enum_or_raw(current.stepType)) == str(step.stepType)
                and current.sortOrder == order
                and current.notes == notes
            )
            if not unchanged:
                plan.to_update.append(
                    (
                        step.id,
                        {
                            "name": step.name,
                            "stepType": step.stepType,
                            "sortOrder": order,
                            "notes": notes,
                        },
                    )
                )
            continue
        plan.to_create.append(
            {
                "processId": process_id,
                "name": step.name,
                "stepType": step.stepType,
                "sortOrder": order,
                "notes": notes,
            }
        )
    plan.removed.extend(step.id for step in existing if step.id not in keep)
    return plan


async def _apply_steps(plan: _StepPlan, *, client: Any = None) -> None:
    writer = db if client is None else client
    if plan.to_create:
        await writer.processstep.create_many(data=plan.to_create)
    for step_id, data in plan.to_update:
        await writer.processstep.update(where={"id": step_id}, data=data)
    if plan.removed:
        await writer.processstep.delete_many(where={"id": {"in": plan.removed}})


def _step_digest(steps: list[Any]) -> list[dict[str, Any]]:
    return [
        {
            "id": step.id,
            "name": step.name,
            "stepType": str(enum_or_raw(step.stepType)),
            "sortOrder": step.sortOrder,
            "notes": step.notes,
        }
        for step in sorted(steps, key=lambda s: (s.sortOrder or 0, s.id))
    ]


@router.get("")
async def list_processes(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    productId: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    designWorkshopId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        where["OR"] = [{"name": contains(search)}, prose_contains("notes", search)]
    if productId:
        where["productId"] = productId
    product_is: dict[str, Any] = {}
    if craftId:
        product_is["craftId"] = craftId
    if artisanId:
        product_is["artisanId"] = artisanId
    if product_is:
        where["product"] = {"is": product_is}
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
    if workshopId:
        and_filters.append(
            {
                "OR": [
                    {"workshopId": workshopId},
                    {"product": {"is": {"workshopId": workshopId}}},
                ]
            }
        )
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await count_and_page(
        db.process,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    return page_payload(
        [_encode_light(item, current_user) for item in items], total, page, page_size
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_process(
    payload: ProcessCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    replayed = await client_key_replay(db.process, payload.clientKey, user_id=current_user.id)
    if replayed is not None:
        await hydrate_relations([replayed], RELATIONS)
        return await _hydrate(replayed, current_user)
    await require_record(db.productdocumentation, payload.productId)
    data = clean_data(payload.model_dump(exclude={"steps"}))
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=None)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.process.create(data=data)
    except Exception as exc:
        raced = await client_key_replay_after_violation(
            db.process, payload.clientKey, exc, user_id=current_user.id
        )
        if raced is None:
            raise
        await hydrate_relations([raced], RELATIONS)
        return await _hydrate(raced, current_user)
    await _apply_steps(_plan_steps(created.id, [], payload.steps))
    await hydrate_relations([created], RELATIONS)
    return await _hydrate(created, current_user)


@router.get("/{process_id}")
async def get_process(
    process_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    process = await require_record(db.process, process_id)
    await hydrate_relations([process], RELATIONS)
    return await _hydrate(process, current_user)


@router.patch("/{process_id}")
async def update_process(
    process_id: str,
    payload: ProcessUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    process = await require_record(db.process, process_id)
    data = clean_data(
        payload.model_dump(exclude_unset=True, exclude={"steps"}), clearable=_CLEARABLE_COLUMNS
    )
    expected_updated_at = take_expected_updated_at(data)
    if "productId" in data:
        if data["productId"] is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "A process must belong to a product. Choose another product to move it to, or "
                    "delete the process."
                ),
            )
        await require_record(db.productdocumentation, data["productId"])
    check = None
    if "workshopId" in data and data.get("workshopId") != process.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=process.designWorkshopId)
    existing_steps: list[Any] = []
    plan: _StepPlan | None = None
    if payload.steps is not None:
        existing_steps = await db.processstep.find_many(where={"processId": process_id})
        plan = _plan_steps(process_id, existing_steps, payload.steps)
        if not await guard_record_edit(process, current_user, {}, "process"):
            assert_can_contribute_relation(process, current_user, plan.touches_existing, "steps")
    async with db.tx() as tx:
        await lock_and_assert_expected_updated_at(tx, "process", process, expected_updated_at)
        await guard_record_edit(process, current_user, data, "process", client=tx)
        await apply_status_policy_update(current_user, process, data)
        stamp_workshop_submission(data, check=check, record=process)
        pin_pending_if_late(data, current_user, check=check, record=process)
        merge_field_provenance(data, current_user, previous=process)
        resubmit_status(process, current_user, data)
        if data:
            await tx.process.update(where={"id": process_id}, data=data)
        if plan is not None:
            await _apply_steps(plan, client=tx)
            if plan.writes_anything:
                after = await tx.processstep.find_many(where={"processId": process_id})
                await record_revision(
                    {"id": process_id, "steps": _step_digest(existing_steps)},
                    current_user,
                    {"steps": _step_digest(after)},
                    "process",
                    client=tx,
                )
    hydrated = await db.process.find_unique(where={"id": process_id})
    await hydrate_relations([hydrated], RELATIONS)
    return await _hydrate(hydrated, current_user)


@router.delete("/{process_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_process(process_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    process = await require_record(db.process, process_id)
    await assert_may_write_a_record_filed_under(process.designWorkshopId, current_user)
    await db.process.delete(where={"id": process_id})
