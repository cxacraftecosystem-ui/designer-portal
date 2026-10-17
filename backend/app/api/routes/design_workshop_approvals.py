"""REPORTS TO APPROVE: the approving authority's surface — the queue, the waiting count, one report
read for a decision, its report file, and the three decisions: approve, send back or withdraw
(``/revise``), and hand on to the office.

The owner's requirement ends: *"This iterative review loop continues until the sanctioning authority
is satisfied and clicks 'approve'."* Until 2026-10-10 the edges this router owns — ``DECISION_EDGES``
in ``schemas/design_workshop_review_loop`` — named it and nothing answered, so APPROVED and SUBMITTED
were unreachable. WHO may decide is ``deps.APPROVAL_AUTHORITY_ROLES`` (the Ministry Admin and the
master admin, estate-wide; ``services/design_workshop_approvals`` carries the product's evidence) and,
on one workshop, rule 7 of ``services/design_workshop_posts``. WHAT each decision writes is planned in
``schemas/design_workshop_approvals``.

=======================================================================================
WHY THIS IS ITS OWN ROUTER ON ITS OWN PREFIX
=======================================================================================

For the deciding reason every sibling gives in ``app/api/router.py``: its callers are very often
somebody ``load_workshop_or_404`` turns away — a Ministry Admin who neither opened the workshop nor
holds designer access to it — and a route sharing ``/design-workshops`` invites widening that loader,
which grants STAGE WRITES rather than a read. Reading every report here is not a widening: the
Ministry Admin and the master admin already read every workshop's stage data on View Data and the
ministry register. The read carries no photographs, recordings or transcripts — the oversight read's
boundary — and the report route renders the same document the designer's report route does.

=======================================================================================
EACH DECISION IS ONE TRANSACTION, AND THE GUARD IS ON THE WRITE
=======================================================================================

Read the workshop; refuse (403) an account rule 7 bars; refuse (422) a state the decision cannot be
taken from; build the plans (422 for the text); then, in one transaction, the compare-and-set update
— its WHERE carries the state the decision was taken against, and for an approval the round and the
``updatedAt`` the approver read — then the correction row the send-back decisions file, then the
``ReviewLog`` row. A compare-and-set that matches nothing is somebody else's decision, or a designer's
save, landing in between: the transaction raises, nothing is written, and the answer is a 409 — except
two approvals of one report, where the second is told it is already approved (a no-op is never a
refusal). Nothing is e-mailed or transmitted: there is no mailer, and a hand-on RECORDS who handed the
report on, when, to which office and which exported file.

**REGISTRATION ORDER IS LOAD-BEARING.** ``GET /awaiting-count`` is declared before ``GET
/{workshop_id}``, which would otherwise swallow it and answer 404.
"""

import asyncio
import hashlib
import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

# THE STAGE SERIALISERS AND THE REPORT HELPERS ARE IMPORTED RATHER THAN COPIED — private names in
# another route module, the smell ``design_workshop_inspections`` states out loud above its identical
# import and argues is the smaller problem: a second implementation of the registry-aware serialiser,
# or of the report's inputs, is a second document. The approver must read and hand on the SAME report
# the designer generates.
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

router = APIRouter(prefix="/design-workshop-approvals", tags=["design-workshops"])
logger = logging.getLogger(__name__)


def _status(record: Any) -> str:
    value = getattr(record, "status", "")
    return str(getattr(value, "value", value) or "")


def _handed_on(record: Any) -> bool:
    return getattr(record, "handedOnAt", None) is not None


async def _read_for_decision(record: Any, user: Any) -> dict[str, Any]:
    """One report as the approving authority reads it, with what THIS reader may decide on it.

    The oversight read's payload — every stage, its completeness, who wrote each field, who supervises
    it — plus the correction register, the decision history, the inspectors, the sign-off names, and
    the decision view the client draws its buttons from and from nothing else: who may decide turns on
    rows the client cannot see.
    """
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
    # RESOLVED PER ROUTE, never inside the shared serialiser — `test_entry_provenance_readers.py`
    # asserts it of this route by name: a report whose fields all read "set by (unknown)" is one the
    # authority cannot judge.
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    summary["oversight"] = supervisors
    # C5: each inspector with their "Checked, no corrections" on the current round (or null).
    cycle = int(getattr(record, "submissionRound", 0) or 0)
    summary["inspectors"] = review_aids.with_verdicts(inspectors, clearances, cycle)
    summary["clearances"] = clearances
    # A19: each note says whether the stage it names was edited after it was filed.
    rows, cut = feedback
    edits = await review_aids.stage_edit_times(workshop_id, entries)
    summary["inspectionFeedback"] = review_aids.annotate_freshness(rows, edits)
    summary["inspectionFeedbackTruncated"] = cut
    # C13: the rounds whose hand-in copy was kept, for "Changed since round N".
    summary["roundSnapshots"] = snapshots
    summary["decisions"] = decisions
    summary.update(names)
    summary.update(approvals.decision_view(record, refusal=refusal))
    summary["handOnDefaultTo"] = approvals.hand_on_default_to(entries)
    summary["latestExport"] = latest
    # The content of this report is not writable from this surface, said on the wire as both sibling
    # reads say it, so a screen that renders it never offers a Save.
    summary["readOnly"] = True
    summary["mayRecordFeedback"] = False
    return summary


# --------------------------------------------------------------------------------------
# The reads. The literal path FIRST.
# --------------------------------------------------------------------------------------


@router.get("/awaiting-count")
async def awaiting_count(_: Any = Depends(require_approving_authority)) -> dict[str, int]:
    """``{awaiting, toHandOn}``: the badge on the desk row and the nav entry — this product's only
    shape for a notification, the one ``GET /sanction-orders/awaiting-count`` set."""
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
    """One tab of the queue — awaiting, approved, handedOn or returned — estate-wide, over the index
    ``schema.prisma`` built for exactly this predicate. Each row carries the two sign-off names,
    resolved for the page in one query, and its ``readiness`` (B4: completeness, this round's notes,
    inspectors cleared).

    ``sort``: ``oldest`` / ``newest`` by the tab's own date, or ``complete`` (most complete first).
    ``cleared``: ``all`` / ``some`` / ``none`` of the assigned inspectors marked "Checked, no
    corrections" on the current round (C5). Absent, both leave the tab's order and rows unchanged.
    """
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
    """One report, read for a decision. 404 for an unknown or deleted workshop."""
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    return await _read_for_decision(record, current_user)


@router.get("/{workshop_id}/media")
async def read_report_media_for_approval(
    workshop_id: str, current_user: Any = Depends(require_approving_authority)
) -> dict[str, Any]:
    """The workshop's photographs, recordings and attachments, signed and read-only (A23) — the
    inspector's and the supervisor's media read, behind this surface's own loader."""
    await approvals.load_approvable_workshop_or_404(workshop_id)
    return await workshop_media_for_reader(workshop_id, current_user)


@router.get("/{workshop_id}/rounds/{round}")
async def read_round_snapshot_for_approval(
    workshop_id: str, round: int, _: Any = Depends(require_approving_authority)
) -> dict[str, Any]:
    """The stage data as handed in for ``round`` — what "Changed since round N" compares (C13)."""
    await approvals.load_approvable_workshop_or_404(workshop_id)
    return await review_aids.snapshot_payload(workshop_id, round)


@router.post("/{workshop_id}/report")
async def generate_report_for_approval(
    workshop_id: str,
    payload: ReportGenerateIn,
    current_user: Any = Depends(require_approving_authority),
) -> Response:
    """The report file, rendered exactly as ``POST /design-workshops/{id}/report`` renders it, for an
    approver who may not open the designer's workshop. With ``record`` it writes the export ledger row
    a hand-on can then pin, generated by the approver."""
    record = await approvals.load_approvable_workshop_or_404(workshop_id)
    fmt = payload.formats[0]
    # A22: AN UNCHANGED REPORT IS SERVED AGAIN, NOT REBUILT. The fingerprint covers the header, every
    # stage row, the workshop's files, its own questions and this request's options; a stored file
    # built from the same fingerprint in the last fortnight is read back and answered with
    # `x-report-reused-from`. `fresh` rebuilds regardless. No new ledger row is written for a reuse.
    options = payload.model_dump(exclude={"record", "fresh"})
    fingerprint = await review_aids.report_fingerprint(
        record, fmt=fmt, options=options, viewer=current_user
    )
    if payload.record and not payload.fresh:
        reused = await _stored_report(workshop_id, fingerprint)
        if reused is not None:
            return reused
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id,
        record,
        viewer=current_user,
        requested_template_id=payload.templateId,
        transcripts=payload.includeTranscripts,
        ai_layers=payload.includeAiLayers,
    )
    blob, warnings, page_count = await asyncio.to_thread(
        render_report, data, template_id, resolver, record, fmt, payload
    )
    warnings = list(warnings) + load_warnings
    file_name = _report_file_name(record, fmt)
    headers = {
        "content-disposition": _content_disposition(file_name),
        "x-report-warnings": _warnings_header(warnings),
        "x-report-warning-count": str(len(warnings)),
    }
    if payload.record:
        export = await db.dwreportexport.create(
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
                "generatedById": current_user.id,
                "inputFingerprint": fingerprint,
            }
        )
        headers["x-report-export-id"] = export.id
        await _store_report(workshop_id, export.id, fmt, blob)
    return Response(content=blob, media_type=_MIME[fmt], headers=headers)


async def _store_report(workshop_id: str, export_id: str, fmt: str, blob: bytes) -> None:
    """Keep the file so an unchanged report can be served again. A failure only costs the reuse."""
    key = review_aids.report_object_key(workshop_id, export_id, fmt)
    try:
        await asyncio.to_thread(s3.put_object_bytes, key, blob, _MIME[fmt])
        await db.dwreportexport.update(where={"id": export_id}, data={"storageKey": key})
    except Exception:  # the download itself has succeeded
        logger.warning("could not store report %s for reuse", export_id, exc_info=True)
        return
    # One stored file per workshop and format: the older ones can never be served again.
    await review_aids.drop_superseded_reports(workshop_id, fmt, export_id)


async def _stored_report(workshop_id: str, fingerprint: str) -> Response | None:
    """The stored file of an identical earlier build, as the download's answer, or None."""
    prior = await review_aids.reusable_export(workshop_id, fingerprint)
    if prior is None:
        return None
    try:
        blob = await asyncio.to_thread(
            s3.get_object_bytes, prior.storageKey, max_bytes=review_aids.REUSE_MAX_BYTES
        )
    except Exception:  # fall back to building it
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


# --------------------------------------------------------------------------------------
# The three decisions
# --------------------------------------------------------------------------------------


class _LostTheRace(Exception):
    """Raised inside a decision's transaction when its compare-and-set matched nothing — which rolls
    the transaction back before anything was inserted. Caught outside it, to answer."""


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


async def _apply(decision: plans.DecisionPlans) -> None:
    """The decision's writes, in one transaction, the guarded update first."""
    async with db.tx() as tx:
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


def _parse_read_at(value: str) -> datetime:
    """The ``updatedAt`` the approver read, or a 422 naming the field. A naive moment is read as UTC."""
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
    """PRE_SUBMISSION -> APPROVED, for exactly the round and the content the approver read.

    403 for rule 7; 422 when the report is not in a state that can be approved; 409 when the round or
    the read stamp are no longer the report's. Already approved is a 200 with ``alreadyApproved``.
    """
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
    return await _read_for_decision(updated, current_user)


@router.post("/{workshop_id}/revise")
async def revise_report(
    workshop_id: str,
    payload: plans.DwReviseIn,
    current_user: Any = Depends(require_approving_authority),
) -> dict[str, Any]:
    """Back to the designers, with a mandatory sentence: sent back without approval (from
    Pre-submission), an approval withdrawn (from Approved), or a handed-on report returned (R1).

    The path is ``/revise`` because ``DECISION_EDGES`` and every refusal a header edit gives have
    named it so since 2026-09-13. The sentence lands in the register the designers work from, as the
    one that sent the report back, and in the audit row.
    """
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
    # THE SAME NOTICE AN INSPECTOR'S SEND-BACK QUEUES, after the transaction so a rolled-back decision
    # e-mails nobody: the report is back on its designers' desk, with the sentence to act on. Never
    # raises — the decision stands whether or not the notice is queued. A batch is one e-mail.
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
    """APPROVED -> SUBMITTED: the approved report recorded as handed on to the office.

    Records who, when, to which office (stage 20's "Submitted to", else the Office of the Development
    Commissioner (Handicrafts)) and — when ``exportId`` names one generated since the approval — which
    file went. Nothing is transmitted. After it the report is frozen and loses every header edge; only
    the approving authority's ``/revise`` returns it.
    """
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
    return await _read_for_decision(updated, current_user)
