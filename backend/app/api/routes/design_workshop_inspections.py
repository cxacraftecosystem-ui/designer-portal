from datetime import UTC, date, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.api.routes.design_workshop_oversight import require_workshop_assigner
from app.api.routes.design_workshops import (
    _provenance_maps,
    _stages_payload,
)
from app.core.db import db
from app.core.deps import get_current_user
from app.schemas import design_workshop_review_loop
from app.schemas.design_workshop_inspections import (
    DesignWorkshopInspectorsIn,
    DwInspectionFeedbackIn,
    DwInspectionNotesIn,
    DwInspectionSendBackIn,
)
from app.services import (
    design_workshop_approvals,
    design_workshop_review_aids as review_aids,
    email_outbox,
)
from app.services.concurrency import gather_reads
from app.services.custom_sections import definition_payload, load_definition_or_empty
from app.services.design_workshop_inspectors import (
    assert_inspection_surface,
    eligible_inspectors,
    inspectable_by_clause,
    inspector_rows,
    load_inspectable_workshop_or_404,
    replace_inspectors,
)
from app.services.design_workshop_reader_media import workshop_media_for_reader
from app.services.design_workshops import entry_rows, workshop_completeness, workshop_summary
from app.services.entry_provenance import resolve_display_names
from app.services.pagination import normalize_pagination, page_payload
from app.services.reader_list_filters import apply_reader_list_filters
from app.services.records import contains
from app.services.stage_schema import registry_version

router = APIRouter(prefix="/design-workshop-inspections", tags=["design-workshops"])


async def require_inspector(current_user: Any = Depends(get_current_user)) -> Any:
    assert_inspection_surface(current_user)
    return current_user


async def _workshop_or_404(workshop_id: str) -> Any:
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record



@router.get("/awaiting-count")
async def awaiting_inspection_count(current_user: Any = Depends(require_inspector)) -> dict[str, int]:
    waiting = await db.designworkshop.find_many(
        where={
            "deletedAt": None,
            "submissionRound": {"gt": 0},
            "status": "PRE_SUBMISSION",
            "AND": [inspectable_by_clause(current_user.id)],
        },
    )
    if not waiting:
        return {"awaiting": 0}
    rounds = {row.id: int(row.submissionRound or 0) for row in waiting}
    mine = await db.dwinspectionfeedback.find_many(
        where={"actorId": current_user.id, "designWorkshopId": {"in": list(rounds)}},
    )
    reviewed = {row.designWorkshopId for row in mine if int(row.round) == rounds.get(row.designWorkshopId)}
    return {"awaiting": sum(1 for workshop_id in rounds if workshop_id not in reviewed)}


@router.get("/eligible-inspectors")
async def list_eligible_inspectors(
    search: str | None = Query(None, max_length=120),
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    return await eligible_inspectors(search=search, exclude_user_id=current_user.id)


@router.get("/{workshop_id}/inspectors")
async def list_inspectors(
    workshop_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    await _workshop_or_404(workshop_id)
    return {"inspectors": await inspector_rows(workshop_id)}


@router.put("/{workshop_id}/inspectors")
async def set_inspectors(
    workshop_id: str,
    payload: DesignWorkshopInspectorsIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    await _workshop_or_404(workshop_id)
    inspectors = await replace_inspectors(
        workshop_id, payload.userIds, assigned_by_id=current_user.id
    )
    return {"inspectors": inspectors}



_INSPECTION_GROUPS: dict[str, dict[str, Any]] = {
    "awaiting": {"status": design_workshop_review_loop.PRE_SUBMISSION},
    "withDesigners": {"status": design_workshop_review_loop.NEEDS_REVISION},
    "other": {
        "status": {
            "notIn": [
                design_workshop_review_loop.PRE_SUBMISSION,
                design_workshop_review_loop.NEEDS_REVISION,
            ]
        }
    },
}


@router.get("")
async def list_inspectable_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    statusFilter: str | None = Query(None, max_length=120),
    round: int | None = Query(None, ge=0, le=1000),
    state: str | None = Query(None, max_length=120),
    workshopKind: str | None = Query(None, max_length=120),
    dateFrom: date | None = None,
    dateTo: date | None = None,
    group: str | None = Query(None),
    sort: str | None = Query(None),
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    where: dict[str, Any] = {"deletedAt": None}
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"title": contains(term)},
            {"craftName": contains(term)},
            {"clusterName": contains(term)},
            {"workshopCode": contains(term)},
        ]
    apply_reader_list_filters(
        where,
        status=statusFilter,
        round_=round,
        state=state,
        workshop_kind=workshopKind,
        date_from=dateFrom,
        date_to=dateTo,
    )
    where.setdefault("AND", []).append(inspectable_by_clause(current_user.id))

    chosen_sort = review_aids.check_sort(sort)
    if group is not None and group not in _INSPECTION_GROUPS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"group must be one of {', '.join(_INSPECTION_GROUPS)}.",
        )
    counts = await gather_reads(
        *(
            db.designworkshop.count(where={"AND": [where, clause]})
            for clause in _INSPECTION_GROUPS.values()
        )
    )
    group_counts = dict(zip(_INSPECTION_GROUPS, counts, strict=True))
    if group is not None:
        where = {"AND": [where, _INSPECTION_GROUPS[group]]}

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    if chosen_sort == "complete":
        rows, total, readiness, truncated = await review_aids.ranked_page(
            where,
            {"createdAt": "desc"},
            page=clean_page,
            page_size=clean_size,
            sort=chosen_sort,
            cleared=None,
        )
        answer = page_payload(
            [{**workshop_summary(r), "readiness": readiness.get(r.id)} for r in rows],
            total,
            clean_page,
            clean_size,
        )
        answer["groupCounts"] = group_counts
        answer["candidatesTruncated"] = truncated
        return answer
    order = review_aids.dated_order("lastHandedInAt", chosen_sort) or {"createdAt": "desc"}
    total, rows = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order=order
        ),
    )
    readiness = await review_aids.readiness_for(rows)
    answer = page_payload(
        [{**workshop_summary(r), "readiness": readiness.get(r.id)} for r in rows],
        total,
        clean_page,
        clean_size,
    )
    answer["groupCounts"] = group_counts
    return answer


@router.get("/{workshop_id}")
async def read_workshop_under_inspection(
    workshop_id: str, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    summary["customSections"] = definition_payload(definition)
    summary["readOnly"] = True
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = (
        await review_aids.annotated_register(workshop_id, entries)
    )
    clearances, snapshots = await gather_reads(
        review_aids.clearance_rows(workshop_id), review_aids.snapshot_list(workshop_id)
    )
    cycle = int(getattr(record, "submissionRound", 0) or 0)
    summary["clearances"] = clearances
    summary["myClearedAt"] = review_aids.my_clearance(clearances, current_user.id, cycle)
    summary["roundSnapshots"] = snapshots
    summary["mayRecordFeedback"] = True
    summary.update(await design_workshop_approvals.approval_names(record))
    return summary


@router.get("/{workshop_id}/media")
async def read_workshop_media_under_inspection(
    workshop_id: str, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    await load_inspectable_workshop_or_404(workshop_id, current_user)
    return await workshop_media_for_reader(workshop_id, current_user)



def _under_review_or_422(record: Any) -> str:
    current = str(getattr(record, "status", "") or "")
    if current not in design_workshop_review_loop.UNDER_REVIEW:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=design_workshop_review_loop.review_closed_refusal(
                current, handed_on=getattr(record, "handedOnAt", None) is not None
            ),
        )
    return current


async def _apply(client: Any, plan: Any) -> Any:
    delegate = getattr(client, plan.table.lower())
    if plan.operation is design_workshop_review_loop.Operation.CREATE:
        return await delegate.create(data=dict(plan.data))
    return await delegate.update(where=dict(plan.where or {}), data=dict(plan.data))


async def _apply_while_under_review(client: Any, plan: Any) -> int:
    delegate = getattr(client, plan.table.lower())
    return await delegate.update_many(
        where={
            **dict(plan.where or {}),
            "status": {"in": sorted(design_workshop_review_loop.UNDER_REVIEW)},
        },
        data=dict(plan.data),
    )


def _parse_recorded_at(value: str | None) -> Any:
    text = (value or "").strip()
    if not text:
        return None
    try:
        moment = datetime.fromisoformat(text)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "The time on this suggestion couldn't be read. Update the app and file it again."
            ),
        ) from None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


async def _feedback_answer(
    record: Any, *, may_record: bool = True, user: Any = None
) -> dict[str, Any]:
    summary = workshop_summary(record)
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = (
        await review_aids.annotated_register(record.id)
    )
    summary["readOnly"] = True
    summary["mayRecordFeedback"] = may_record
    if user is not None:
        clearances = await review_aids.clearance_rows(record.id)
        summary["clearances"] = clearances
        summary["myClearedAt"] = review_aids.my_clearance(
            clearances, user.id, int(getattr(record, "submissionRound", 0) or 0)
        )
    return summary


@router.post("/{workshop_id}/feedback", status_code=status.HTTP_201_CREATED)
async def record_inspection_feedback(
    workshop_id: str,
    payload: DwInspectionFeedbackIn,
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    _under_review_or_422(record)
    try:
        plan = design_workshop_review_loop.feedback_plan(
            workshop_id=workshop_id,
            round=int(getattr(record, "submissionRound", 0) or 0),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            stage_key=payload.stageKey,
            field_key=payload.fieldKey,
            recorded_at=_parse_recorded_at(payload.recordedAt),
        )
    except design_workshop_review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    await _apply(db, plan)
    await review_aids.drop_clearance_after_note(workshop_id, current_user.id, plan.data["round"])
    await email_outbox.notify_review_note(
        record, actor=current_user, note=payload.note, stage_key=payload.stageKey, sent_back=False
    )
    return await _feedback_answer(record, user=current_user)


@router.post("/{workshop_id}/send-back")
async def send_workshop_back_for_revision(
    workshop_id: str,
    payload: DwInspectionSendBackIn,
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    current = _under_review_or_422(record)
    refusal = design_workshop_review_loop.transition_refusal(
        current, design_workshop_review_loop.NEEDS_REVISION, by_decision_route=True
    )
    if refusal:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
    try:
        plans = design_workshop_review_loop.send_back_plans(
            workshop_id=workshop_id,
            round=int(getattr(record, "submissionRound", 0) or 0),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            stage_key=payload.stageKey,
            field_key=payload.fieldKey,
            recorded_at=_parse_recorded_at(payload.recordedAt),
        )
    except design_workshop_review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    async with db.tx() as tx:
        await _apply(tx, plans.feedback)
        if not await _apply_while_under_review(tx, plans.workshop):
            moved = await tx.designworkshop.find_unique(where={"id": workshop_id})
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=design_workshop_review_loop.review_closed_refusal(
                    str(getattr(moved, "status", "") or ""),
                    handed_on=getattr(moved, "handedOnAt", None) is not None,
                ),
            )
        updated = await tx.designworkshop.find_unique(where={"id": workshop_id})
        await _apply(tx, plans.log)
    await review_aids.drop_clearance_after_note(
        workshop_id, current_user.id, plans.feedback.data["round"]
    )
    await email_outbox.notify_review_note(
        updated or record,
        actor=current_user,
        note=payload.note,
        stage_key=payload.stageKey,
        sent_back=True,
    )
    return await _feedback_answer(updated, user=current_user)



def _batch_rows(payload: DwInspectionNotesIn) -> list[tuple[str | None, str | None, str | None]]:
    return [(item.note, item.stageKey, item.fieldKey) for item in payload.notes]


@router.post("/{workshop_id}/feedback/batch", status_code=status.HTTP_201_CREATED)
async def record_inspection_feedback_batch(
    workshop_id: str,
    payload: DwInspectionNotesIn,
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    _under_review_or_422(record)
    cycle = int(getattr(record, "submissionRound", 0) or 0)
    try:
        batch = design_workshop_review_loop.note_batch(
            workshop_id=workshop_id,
            round=cycle,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            notes=_batch_rows(payload),
            recorded_at=_parse_recorded_at(payload.recordedAt),
        )
    except design_workshop_review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    async with db.tx() as tx:
        for plan in batch.rows:
            await _apply(tx, plan)
    await review_aids.drop_clearance_after_note(workshop_id, current_user.id, cycle)
    await email_outbox.notify_review_note(
        record, actor=current_user, note=batch.digest, stage_key=None, sent_back=False
    )
    return await _feedback_answer(record, user=current_user)


@router.post("/{workshop_id}/send-back/batch")
async def send_workshop_back_with_notes(
    workshop_id: str,
    payload: DwInspectionNotesIn,
    current_user: Any = Depends(require_inspector),
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    current = _under_review_or_422(record)
    refusal = design_workshop_review_loop.transition_refusal(
        current, design_workshop_review_loop.NEEDS_REVISION, by_decision_route=True
    )
    if refusal:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
    cycle = int(getattr(record, "submissionRound", 0) or 0)
    try:
        batch, workshop_plan, log_plan = design_workshop_review_loop.send_back_batch_plans(
            workshop_id=workshop_id,
            round=cycle,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            notes=_batch_rows(payload),
            cover=payload.note,
            recorded_at=_parse_recorded_at(payload.recordedAt),
        )
    except design_workshop_review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    async with db.tx() as tx:
        for plan in batch.rows:
            await _apply(tx, plan)
        if not await _apply_while_under_review(tx, workshop_plan):
            moved = await tx.designworkshop.find_unique(where={"id": workshop_id})
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=design_workshop_review_loop.review_closed_refusal(
                    str(getattr(moved, "status", "") or ""),
                    handed_on=getattr(moved, "handedOnAt", None) is not None,
                ),
            )
        updated = await tx.designworkshop.find_unique(where={"id": workshop_id})
        await _apply(tx, log_plan)
    await review_aids.drop_clearance_after_note(workshop_id, current_user.id, cycle)
    await email_outbox.notify_review_note(
        updated or record, actor=current_user, note=batch.digest, stage_key=None, sent_back=True
    )
    return await _feedback_answer(updated, user=current_user)


@router.post("/{workshop_id}/clearance")
async def clear_workshop_round(
    workshop_id: str, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    _under_review_or_422(record)
    await review_aids.clear_round(record, current_user)
    return await _feedback_answer(record, user=current_user)


@router.delete("/{workshop_id}/clearance")
async def withdraw_workshop_round_clearance(
    workshop_id: str, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    record = await load_inspectable_workshop_or_404(workshop_id, current_user)
    await review_aids.withdraw_clearance(record, current_user)
    return await _feedback_answer(record, user=current_user)


@router.get("/{workshop_id}/rounds/{round}")
async def read_round_snapshot_under_inspection(
    workshop_id: str, round: int, current_user: Any = Depends(require_inspector)
) -> dict[str, Any]:
    await load_inspectable_workshop_or_404(workshop_id, current_user)
    return await review_aids.snapshot_payload(workshop_id, round)
