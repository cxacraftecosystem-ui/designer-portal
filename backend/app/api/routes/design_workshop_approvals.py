import asyncio
import hashlib
import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from app.api.routes.design_workshops import (
    _MIME,
    _content_disposition,
    _inspection_feedback_payload,
    _provenance_maps,
    _report_file_name,
    _report_inputs,
    _stages_payload,
    _warnings_header,
)
from app.core.db import db
from app.core.deps import require_approving_authority
from app.schemas import design_workshop_approvals as plans
from app.schemas.design_workshop_review_loop import APPROVED, InspectionRuleViolation
from app.schemas.design_workshops import ReportGenerateIn
from app.services import (
    design_workshop_approvals as approvals,
    design_workshop_oversight as oversight,
    design_workshop_posts as posts,
    design_workshop_review_aids as review_aids,
    email_outbox,
    mailer,
    report_delivery,
    s3,
)
from app.services.concurrency import gather_reads
from app.services.custom_sections import load_definition_or_empty
from app.services.design_workshop_inspectors import inspector_rows
from app.services.design_workshop_reader_media import workshop_media_for_reader
from app.services.design_workshops import (
    entry_rows,
    render_report,
    workshop_completeness,
    workshop_summary,
)
from app.services.entry_provenance import resolve_display_names
from app.services.pagination import normalize_pagination, page_payload
from app.services.stage_schema import registry_version

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/design-workshop-approvals", tags=["design-workshops"])


def _status(record: Any) -> str:
    value = getattr(record, "status", "")
    return str(getattr(value, "value", value) or "")


def _handed_on(record: Any) -> bool:
    return getattr(record, "handedOnAt", None) is not None


async def _read_for_decision(record: Any, user: Any) -> dict[str, Any]:
    workshop_id = record.id
    (
        entries,
        definition,
        supervisors,
        inspectors,
        feedback,
        decisions,
        names,
        refusal,
        latest,
        clearances,
        snapshots,
    ) = await gather_reads(
        entry_rows(workshop_id),
        load_definition_or_empty(workshop_id),
        oversight.oversight_rows(workshop_id),
        inspector_rows(workshop_id),
        _inspection_feedback_payload(workshop_id),
        approvals.decision_history(workshop_id),
        approvals.approval_names(record),
        posts.decision_refusal(workshop_id, user),
        approvals.latest_export(workshop_id),
        review_aids.clearance_rows(workshop_id),
        review_aids.snapshot_list(workshop_id),
    )
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    summary["oversight"] = supervisors
    cycle = int(getattr(record, "submissionRound", 0) or 0)
    summary["inspectors"] = review_aids.with_verdicts(inspectors, clearances, cycle)
    summary["clearances"] = clearances
    rows, cut = feedback
    edits = await review_aids.stage_edit_times(workshop_id, entries)
    summary["inspectionFeedback"] = review_aids.annotate_freshness(rows, edits)
    summary["inspectionFeedbackTruncated"] = cut
    summary["roundSnapshots"] = snapshots
    summary["decisions"] = decisions
    summary.update(names)
    summary.update(approvals.decision_view(record, refusal=refusal))
    summary["handOnDefaultTo"] = approvals.hand_on_default_to(entries)
    summary["latestExport"] = latest
    summary["delivery"] = await report_delivery.delivery_view(workshop_id)
    summary["readOnly"] = True
    summary["mayRecordFeedback"] = False
    return summary



@router.get("/awaiting-count")
async def awaiting_count(_: Any = Depends(require_approving_authority)) -> dict[str, int]:
    return await approvals.awaiting_counts()


@router.get("")
async def list_reports_to_approve(
    state: str = Query("awaiting", max_length=20),
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    sort: str | None = Query(None),
    cleared: str | None = Query(None),
    _: Any = Depends(require_approving_authority),
) -> dict[str, Any]:
    chosen_sort = review_aids.check_sort(sort)
    wanted = review_aids.check_cleared(cleared)
    where = approvals.queue_where(state, search)
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    order = approvals.queue_order(state)
    dated = review_aids.dated_order(next(iter(order[0])), chosen_sort)
    truncated = False
    if chosen_sort == "complete" or wanted:
        rows, total, readiness, truncated = await review_aids.ranked_page(
            where,
            dated or order,
            page=clean_page,
            page_size=clean_size,
            sort=chosen_sort,
            cleared=wanted,
        )
    else:
        total, rows = await gather_reads(
            db.designworkshop.count(where=where),
            db.designworkshop.find_many(
                where=where, skip=skip, take=clean_size, order=dated or order
            ),
        )
        readiness = await review_aids.readiness_for(rows)
    names = await approvals.names_for(rows)
    items = []
    for row in rows:
        item = workshop_summary(row)
        item["approvedByName"] = names.get(row.approvedById) if row.approvedById else None
        item["handedOnByName"] = names.get(row.handedOnById) if row.handedOnById else None
        item["readiness"] = readiness.get(row.id)
        items.append(item)
    answer = page_payload(items, total, clean_page, clean_size)
    if truncated:
        answer["candidatesTruncated"] = True
    return answer


@router.get("/{workshop_id}")
async def read_report_to_approve(
    workshop_id: str, current_user: Any = Depends(require_approving_authority)
) -> dict[str, Any]:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    return await _read_for_decision(record, current_user)


@router.get("/{workshop_id}/media")
async def read_report_media_for_approval(
    workshop_id: str, current_user: Any = Depends(require_approving_authority)
) -> dict[str, Any]:
    await approvals.load_approvable_workshop_or_404(workshop_id)
    return await workshop_media_for_reader(workshop_id, current_user)


@router.get("/{workshop_id}/rounds/{round}")
async def read_round_snapshot_for_approval(
    workshop_id: str, round: int, _: Any = Depends(require_approving_authority)
) -> dict[str, Any]:
    await approvals.load_approvable_workshop_or_404(workshop_id)
    return await review_aids.snapshot_payload(workshop_id, round)


@router.post("/{workshop_id}/report")
async def generate_report_for_approval(
    workshop_id: str,
    payload: ReportGenerateIn,
    current_user: Any = Depends(require_approving_authority),
) -> Response:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    fmt = payload.formats[0]
    options = payload.model_dump(exclude={"record", "fresh"})
    fingerprint = await review_aids.report_fingerprint(
        record, fmt=fmt, options=options, viewer=current_user
    )
    if payload.record and not payload.fresh:
        reused = await _stored_report(workshop_id, fingerprint)
        if reused is not None:
            return reused
    blob, warnings, page_count, file_name, template_id = await _render(record, current_user, payload)
    headers = {
        "content-disposition": _content_disposition(file_name),
        "x-report-warnings": _warnings_header(warnings),
        "x-report-warning-count": str(len(warnings)),
    }
    if payload.record:
        export = await _record_export(
            workshop_id,
            fmt,
            template_id,
            file_name,
            blob,
            page_count,
            warnings,
            current_user,
            input_fingerprint=fingerprint,
        )
        headers["x-report-export-id"] = export.id
        await _store_report(workshop_id, export.id, fmt, blob)
    return Response(content=blob, media_type=_MIME[fmt], headers=headers)


async def _render(record: Any, user: Any, payload: ReportGenerateIn) -> tuple[bytes, list[str], Any, str, str]:
    fmt = payload.formats[0]
    data, resolver, load_warnings, template_id = await _report_inputs(
        record.id,
        record,
        viewer=user,
        requested_template_id=payload.templateId,
        transcripts=payload.includeTranscripts,
        ai_layers=payload.includeAiLayers,
    )
    blob, warnings, page_count = await asyncio.to_thread(
        render_report, data, template_id, resolver, record, fmt, payload
    )
    return blob, list(warnings) + load_warnings, page_count, _report_file_name(record, fmt), template_id


async def _record_export(
    workshop_id: str,
    fmt: str,
    template_id: str,
    file_name: str,
    blob: bytes,
    page_count: Any,
    warnings: list[str],
    user: Any,
    storage_key: str | None = None,
    client: Any = None,
    input_fingerprint: str | None = None,
) -> Any:
    return await (client or db).dwreportexport.create(
        data={
            "designWorkshopId": workshop_id,
            "format": fmt,
            "templateId": template_id,
            "fileName": file_name,
            "fileSizeBytes": len(blob),
            "pageCount": page_count,
            "checksumSha256": hashlib.sha256(blob).hexdigest(),
            "generatedOnDevice": False,
            "schemaVersion": registry_version(),
            "warnings": "\n".join(warnings) if warnings else None,
            "generatedById": user.id,
            "storageKey": storage_key,
            "inputFingerprint": input_fingerprint,
        }
    )


async def _store_report(workshop_id: str, export_id: str, fmt: str, blob: bytes) -> None:
    key = review_aids.report_object_key(workshop_id, export_id, fmt)
    try:
        await asyncio.to_thread(s3.put_object_bytes, key, blob, mime_type=_MIME[fmt])
        await db.dwreportexport.update(where={"id": export_id}, data={"storageKey": key})
    except Exception:
        logger.warning("could not store report %s for reuse", export_id, exc_info=True)
        return
    await review_aids.drop_superseded_reports(workshop_id, fmt, export_id)


async def _stored_report(workshop_id: str, fingerprint: str) -> Response | None:
    prior = await review_aids.reusable_export(workshop_id, fingerprint)
    if prior is None:
        return None
    try:
        blob = await asyncio.to_thread(
            s3.get_object_bytes, prior.storageKey, max_bytes=review_aids.REUSE_MAX_BYTES
        )
    except Exception:
        logger.warning("stored report %s could not be read back", prior.id, exc_info=True)
        return None
    warnings = [line for line in (prior.warnings or "").split("\n") if line.strip()]
    headers = {
        "content-disposition": _content_disposition(prior.fileName),
        "x-report-warnings": _warnings_header(warnings),
        "x-report-warning-count": str(len(warnings)),
        "x-report-export-id": prior.id,
        "x-report-reused-from": prior.generatedAt.isoformat() if prior.generatedAt else "",
    }
    return Response(
        content=blob, media_type=_MIME.get(prior.format, _MIME["DOCX"]), headers=headers
    )



class _LostTheRace(Exception):
    pass


async def _refuse_by_rule_seven(workshop_id: str, user: Any) -> None:
    refusal = await posts.decision_refusal(workshop_id, user)
    if refusal:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=refusal)


def _plan_or_422(build: Any) -> Any:
    try:
        return build()
    except InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused


async def _apply_in(tx: Any, decision: plans.DecisionPlans) -> None:
    written = await tx.designworkshop.update_many(
        where=dict(decision.workshop.where or {}), data=dict(decision.workshop.data)
    )
    if written != 1:
        raise _LostTheRace
    if decision.feedback is not None:
        await tx.dwinspectionfeedback.create(data=dict(decision.feedback.data))
    for extra in decision.extra:
        await tx.dwinspectionfeedback.create(data=dict(extra.data))
    await tx.reviewlog.create(data=dict(decision.log.data))


async def _apply(decision: plans.DecisionPlans) -> None:
    async with db.tx() as tx:
        await _apply_in(tx, decision)


def _parse_read_at(value: str) -> datetime:
    try:
        moment = datetime.fromisoformat(value.strip())
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="readAt must be the report's updatedAt exactly as it was read.",
        ) from None
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


@router.post("/{workshop_id}/approve")
async def approve_report(
    workshop_id: str,
    payload: plans.DwApproveIn,
    current_user: Any = Depends(require_approving_authority),
) -> dict[str, Any]:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    await _refuse_by_rule_seven(workshop_id, current_user)
    current = _status(record)
    if current == APPROVED:
        answer = await _read_for_decision(record, current_user)
        answer["alreadyApproved"] = True
        return answer
    refusal = plans.approve_refusal(current, handed_on=_handed_on(record))
    if refusal:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
    if payload.round != int(record.submissionRound or 0):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=plans.CHANGED_AFTER_OPENED)
    read_at = _parse_read_at(payload.readAt)
    decision = _plan_or_422(
        lambda: plans.approve_plans(
            workshop_id=workshop_id,
            round=payload.round,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            read_at=read_at,
        )
    )
    try:
        await _apply(decision)
    except _LostTheRace:
        moved = await approvals.load_approvable_workshop_or_404(workshop_id)
        if _status(moved) == APPROVED:
            answer = await _read_for_decision(moved, current_user)
            answer["alreadyApproved"] = True
            return answer
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=plans.CHANGED_AFTER_OPENED
        ) from None
    updated = await approvals.load_approvable_workshop_or_404(workshop_id)
    await email_outbox.notify_workshop_designers(
        updated, mailer.REPORT_APPROVED, actor=current_user, extra={"note": (payload.note or "").strip()}
    )
    return await _read_for_decision(updated, current_user)


@router.post("/{workshop_id}/revise")
async def revise_report(
    workshop_id: str,
    payload: plans.DwReviseIn,
    current_user: Any = Depends(require_approving_authority),
) -> dict[str, Any]:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    await _refuse_by_rule_seven(workshop_id, current_user)
    current = _status(record)
    handed_on = _handed_on(record)
    refusal = plans.revise_refusal(current, handed_on=handed_on)
    if refusal:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
    decision = _plan_or_422(
        lambda: plans.revise_plans(
            workshop_id=workshop_id,
            round=int(record.submissionRound or 0),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            current_status=current,
            handed_on=handed_on,
            stage_key=payload.stageKey,
            field_key=payload.fieldKey,
            notes=[(item.note, item.stageKey, item.fieldKey) for item in payload.notes] or None,
        )
    )
    try:
        await _apply(decision)
    except _LostTheRace:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=plans.CHANGED_AFTER_OPENED
        ) from None
    updated = await approvals.load_approvable_workshop_or_404(workshop_id)
    await email_outbox.notify_review_note(
        updated,
        actor=current_user,
        note=decision.digest or payload.note,
        stage_key=None if decision.digest else payload.stageKey,
        sent_back=True,
    )
    return await _read_for_decision(updated, current_user)


@router.post("/{workshop_id}/hand-on")
async def hand_on_report(
    workshop_id: str,
    payload: plans.DwHandOnIn,
    current_user: Any = Depends(require_approving_authority),
) -> dict[str, Any]:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    await _refuse_by_rule_seven(workshop_id, current_user)
    refused = plans.hand_on_refusal(_status(record), handed_on=_handed_on(record))
    if refused:
        raise HTTPException(status_code=refused[0], detail=refused[1])
    export_id = (payload.exportId or "").strip() or None
    if export_id:
        await approvals.export_for_hand_on(workshop_id, export_id, record.approvedAt)
    office = (payload.handedOnTo or "").strip() or approvals.hand_on_default_to(
        await entry_rows(workshop_id, stage_key="REPORT_GENERATION")
    )
    decision = _plan_or_422(
        lambda: plans.hand_on_plans(
            workshop_id=workshop_id,
            round=int(record.submissionRound or 0),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note,
            handed_on_to=office,
            export_id=export_id,
        )
    )
    try:
        await _apply(decision)
    except _LostTheRace:
        moved = await approvals.load_approvable_workshop_or_404(workshop_id)
        detail = plans.ALREADY_HANDED_ON if _handed_on(moved) else plans.CHANGED_AFTER_OPENED
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail) from None
    updated = await approvals.load_approvable_workshop_or_404(workshop_id)
    await email_outbox.notify_workshop_designers(
        updated, mailer.REPORT_HANDED_ON, actor=current_user, extra={"officeName": office}
    )
    return await _read_for_decision(updated, current_user)


STORAGE_FAILED = "The report could not be stored for sending. Nothing was changed; try again."


@router.post("/{workshop_id}/approve-and-hand-on")
async def approve_and_hand_on(
    workshop_id: str,
    payload: plans.DwApproveAndHandOnIn,
    current_user: Any = Depends(require_approving_authority),
) -> dict[str, Any]:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    await _refuse_by_rule_seven(workshop_id, current_user)
    office = await report_delivery.office_settings()
    if not office["addresses"] or not mailer.mail_configured():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=report_delivery.NOT_CONFIGURED)
    current = _status(record)
    handed_on = _handed_on(record)
    if current != APPROVED:
        refusal = plans.approve_refusal(current, handed_on=handed_on)
        if refusal:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
        if payload.round is None or not payload.readAt:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="round and readAt are needed to approve this report.",
            )
        if payload.round != int(record.submissionRound or 0):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=plans.CHANGED_AFTER_OPENED)
    else:
        refused = plans.hand_on_refusal(current, handed_on=handed_on)
        if refused:
            raise HTTPException(status_code=refused[0], detail=refused[1])

    report = ReportGenerateIn(formats=[payload.format], templateId=payload.templateId)
    blob, warnings, page_count, file_name, template_id = await _render(record, current_user, report)
    try:
        storage_key = await report_delivery.store_file(workshop_id, file_name, blob, _MIME[payload.format])
    except Exception:  # noqa: BLE001 - every storage refusal is answered the same way: nothing changed
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=STORAGE_FAILED) from None

    approval = None
    if current != APPROVED:
        read_at = _parse_read_at(payload.readAt or "")
        approval = _plan_or_422(
            lambda: plans.approve_plans(
                workshop_id=workshop_id,
                round=int(payload.round or 0),
                actor_id=current_user.id,
                at=datetime.now(UTC),
                note=payload.note,
                read_at=read_at,
            )
        )
    handed_on_at = datetime.now(UTC)

    def hand_on(export_id: str | None) -> plans.DecisionPlans:
        return plans.hand_on_plans(
            workshop_id=workshop_id,
            round=int(record.submissionRound or 0),
            actor_id=current_user.id,
            at=handed_on_at,
            note=None if approval is not None else payload.note,
            handed_on_to=office["officeName"],
            export_id=export_id,
        )

    _plan_or_422(lambda: hand_on(None))

    try:
        async with db.tx() as tx:
            if approval is not None:
                await _apply_in(tx, approval)
            export = await _record_export(
                workshop_id,
                payload.format,
                template_id,
                file_name,
                blob,
                page_count,
                warnings,
                current_user,
                storage_key,
                client=tx,
            )
            await _apply_in(tx, hand_on(export.id))
    except _LostTheRace:
        await _discard_stored(storage_key)
        moved = await approvals.load_approvable_workshop_or_404(workshop_id)
        detail = plans.ALREADY_HANDED_ON if _handed_on(moved) else plans.CHANGED_AFTER_OPENED
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail) from None

    updated = await approvals.load_approvable_workshop_or_404(workshop_id)
    delivered = True
    try:
        await report_delivery.send(
            updated,
            storage_key=storage_key,
            file_name=file_name,
            export_id=export.id,
            actor=current_user,
            addresses=office["addresses"],
            office_name=office["officeName"],
            sanction_order_no=await report_delivery.sanction_number(workshop_id),
        )
    except Exception:
        delivered = False
        logger.exception("report delivery failed after the hand-on of workshop %s", workshop_id)
    if approval is not None:
        await email_outbox.notify_workshop_designers(
            updated,
            mailer.REPORT_APPROVED,
            actor=current_user,
            extra={"note": (payload.note or "").strip()},
        )
    await email_outbox.notify_workshop_designers(
        updated,
        mailer.REPORT_HANDED_ON,
        actor=current_user,
        extra={"officeName": office["officeName"], "emailed": delivered},
    )
    answer = await _read_for_decision(updated, current_user)
    if not delivered:
        answer["deliveryFailed"] = True
    return answer


async def _discard_stored(storage_key: str) -> None:
    try:
        await asyncio.to_thread(s3.delete_object, storage_key)
    except Exception:  # noqa: BLE001 - an orphan in the bucket is harmless; the answer is the 409
        logger.warning("could not remove the unused report file %s", storage_key)


@router.post("/{workshop_id}/delivery/resend")
async def resend_delivery(
    workshop_id: str, current_user: Any = Depends(require_approving_authority)
) -> dict[str, Any]:
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    await _refuse_by_rule_seven(workshop_id, current_user)
    if not mailer.mail_configured():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=report_delivery.NOT_CONFIGURED)
    row = await report_delivery.reissue(record, actor=current_user)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This report has not been e-mailed to the office, or no office addresses are set.",
        )
    return await _read_for_decision(record, current_user)
