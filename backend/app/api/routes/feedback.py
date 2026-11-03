import logging
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.core.deps import get_current_user, require_admin, require_master_admin
from app.schemas.feedback import (
    FeedbackReportCreate,
    FeedbackReportDecision,
    FeedbackUpsertRequest,
)
from app.services.concurrency import gather_reads
from app.services.feedback_vocabulary import (
    FEEDBACK_STATUSES,
    label_for,
    validate_choice,
    vocabulary_payload,
)
from app.services.pagination import normalize_pagination, page_payload

router = APIRouter(prefix="/feedback", tags=["feedback"])

logger = logging.getLogger(__name__)

FEEDBACK_TAKE = 200


FEEDBACK_FIELDS = (
    "rating",
    "easeOfUse",
    "reliability",
    "performance",
    "design",
    "features",
    "recommend",
    "comment",
    "likeMost",
    "improve",
    "bugs",
    "featureRequests",
    "role",
)


def _serialize(row: Any) -> dict[str, Any]:
    user = getattr(row, "user", None)
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "createdAt": jsonable_encoder(row.createdAt),
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in FEEDBACK_FIELDS:
        data[field] = getattr(row, field, None)
    data["user"] = (
        None
        if user is None
        else {
            "id": user.id,
            "name": user.name,
            "email": user.email,
            "role": getattr(user.role, "value", user.role),
        }
    )
    return data


@router.get("/me")
async def my_feedback(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    feedback = await db.feedback.find_unique(where={"userId": current_user.id})
    return _serialize(feedback) if feedback else {}


@router.put("/me")
async def upsert_my_feedback(
    payload: FeedbackUpsertRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    fields = {field: getattr(payload, field) for field in FEEDBACK_FIELDS}
    feedback = await db.feedback.upsert(
        where={"userId": current_user.id},
        data={
            "create": {**fields, "user": {"connect": {"id": current_user.id}}},
            "update": fields,
        },
    )
    return _serialize(feedback)


@router.get("")
async def list_feedback(
    response: Response,
    _: Any = Depends(require_master_admin),
    page: int = Query(1, ge=1),
    pageSize: int = Query(FEEDBACK_TAKE, ge=1, le=FEEDBACK_TAKE),
) -> list[dict[str, Any]]:
    skip = (page - 1) * pageSize
    total, rows = await gather_reads(
        db.feedback.count(),
        db.feedback.find_many(
            order=[{"updatedAt": "desc"}, {"id": "desc"}],
            include={"user": True},
            skip=skip,
            take=pageSize,
        ),
    )
    truncated = total > skip + len(rows)
    response.headers["X-Total-Count"] = str(total)
    response.headers["X-Truncated"] = "true" if truncated else "false"
    if truncated and page == 1 and pageSize == FEEDBACK_TAKE:
        logger.error(
            "GET /feedback returned %s of %s rows to a caller that asked for no page; the master "
            "admin's feedback screen is showing an incomplete list and cannot say so",
            len(rows),
            total,
        )
    return [_serialize(row) for row in rows]


REPORT_FIELDS = (
    "kind",
    "severity",
    "area",
    "subject",
    "details",
    "client",
    "clientVersion",
    "platform",
    "pagePath",
    "status",
    "acknowledgedAt",
    "resolvedAt",
    "responseNote",
)

REPORT_PAGE_SIZE = 25


def _actor(user: Any) -> dict[str, Any] | None:
    if user is None:
        return None
    return {
        "id": user.id,
        "name": user.name,
        "email": user.email,
        "role": getattr(user.role, "value", user.role),
    }


def _serialize_report(row: Any) -> dict[str, Any]:
    data: dict[str, Any] = {
        "id": row.id,
        "userId": row.userId,
        "createdAt": jsonable_encoder(row.createdAt),
        "updatedAt": jsonable_encoder(row.updatedAt),
    }
    for field in REPORT_FIELDS:
        data[field] = jsonable_encoder(getattr(row, field, None))
    for field in ("kind", "severity", "area", "status", "client"):
        data[f"{field}Label"] = label_for(field, getattr(row, field, None))
    data["user"] = _actor(getattr(row, "user", None))
    data["acknowledgedBy"] = _actor(getattr(row, "acknowledgedBy", None))
    data["resolvedBy"] = _actor(getattr(row, "resolvedBy", None))
    return data


REPORT_INCLUDE = {"user": True, "acknowledgedBy": True, "resolvedBy": True}


@router.get("/vocabulary")
async def feedback_vocabulary(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return vocabulary_payload()


@router.post("/reports", status_code=status.HTTP_201_CREATED)
async def create_report(
    payload: FeedbackReportCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    kind = validate_choice("kind", payload.kind, required=True)
    severity = validate_choice("severity", payload.severity)
    area = validate_choice("area", payload.area)
    client = validate_choice("client", payload.client)

    row = await db.feedbackreport.create(
        data={
            "kind": kind,
            "severity": severity,
            "area": area,
            "subject": payload.subject.strip(),
            "details": payload.details.strip(),
            "client": client,
            "clientVersion": (payload.clientVersion or "").strip() or None,
            "platform": (payload.platform or "").strip() or None,
            "pagePath": (payload.pagePath or "").strip() or None,
            "user": {"connect": {"id": current_user.id}},
        },
        include=REPORT_INCLUDE,
    )
    return _serialize_report(row)


@router.get("/reports/mine")
async def my_reports(
    current_user: Any = Depends(get_current_user),
    page: int = Query(1, ge=1),
    pageSize: int = Query(REPORT_PAGE_SIZE, ge=1),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = {"userId": current_user.id}
    total, rows = await gather_reads(
        db.feedbackreport.count(where=where),
        db.feedbackreport.find_many(
            where=where,
            order=[{"createdAt": "desc"}, {"id": "desc"}],
            include=REPORT_INCLUDE,
            skip=skip,
            take=clean_size,
        ),
    )
    payload = page_payload([_serialize_report(row) for row in rows], total, clean_page, clean_size)
    payload["openCount"] = await db.feedbackreport.count(
        where={"AND": [{"userId": current_user.id}, {"NOT": {"status": "RESOLVED"}}]}
    )
    return payload


@router.get("/reports")
async def list_reports(
    _: Any = Depends(require_admin),
    kind: str | None = Query(None),
    reportStatus: str | None = Query(None, alias="status"),
    severity: str | None = Query(None),
    area: str | None = Query(None),
    page: int = Query(1, ge=1),
    pageSize: int = Query(REPORT_PAGE_SIZE, ge=1),
) -> dict[str, Any]:
    clauses: list[dict[str, Any]] = []
    if kind:
        clauses.append({"kind": validate_choice("kind", kind, required=True)})
    if reportStatus:
        clauses.append({"status": validate_choice("status", reportStatus, required=True)})
    if severity:
        clauses.append({"severity": validate_choice("severity", severity, required=True)})
    if area:
        clauses.append({"area": validate_choice("area", area, required=True)})
    where: dict[str, Any] = {"AND": clauses} if clauses else {}

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await gather_reads(
        db.feedbackreport.count(where=where),
        db.feedbackreport.find_many(
            where=where,
            order=[{"createdAt": "desc"}, {"id": "desc"}],
            include=REPORT_INCLUDE,
            skip=skip,
            take=clean_size,
        ),
    )
    payload = page_payload([_serialize_report(row) for row in rows], total, clean_page, clean_size)
    payload["openCount"] = await db.feedbackreport.count(where={"status": "SUBMITTED"})
    return payload


async def _decide(
    report_id: str,
    *,
    to_status: str,
    note: str | None,
    actor: Any,
    note_required: bool,
) -> dict[str, Any]:
    row = await db.feedbackreport.find_unique(where={"id": report_id})
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="That feedback report no longer exists.",
        )
    current = str(getattr(row, "status", "") or "")
    if current == "RESOLVED":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That report is already resolved, and this register keeps one decision per report "
                "so the answer its author was given cannot be overwritten."
            ),
        )
    if current == to_status:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"That report is already {FEEDBACK_STATUSES[to_status].lower()}.",
        )

    cleaned = (note or "").strip()
    if note_required and not cleaned:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "Resolving a report needs a note saying what was done — the person who filed it "
                "reads it."
            ),
        )

    now = datetime.now(UTC)
    data: dict[str, Any] = {"status": to_status}
    if to_status == "ACKNOWLEDGED":
        data["acknowledgedAt"] = now
        data["acknowledgedBy"] = {"connect": {"id": actor.id}}
    else:
        data["resolvedAt"] = now
        data["resolvedBy"] = {"connect": {"id": actor.id}}
        if not getattr(row, "acknowledgedAt", None):
            data["acknowledgedAt"] = now
            data["acknowledgedBy"] = {"connect": {"id": actor.id}}
    if cleaned:
        data["responseNote"] = cleaned

    updated = await db.feedbackreport.update(
        where={"id": report_id}, data=data, include=REPORT_INCLUDE
    )
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="That feedback report no longer exists.",
        )
    return _serialize_report(updated)


@router.post("/reports/{report_id}/acknowledge")
async def acknowledge_report(
    report_id: str,
    payload: FeedbackReportDecision | None = None,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    return await _decide(
        report_id,
        to_status="ACKNOWLEDGED",
        note=payload.note if payload else None,
        actor=current_user,
        note_required=False,
    )


@router.post("/reports/{report_id}/resolve")
async def resolve_report(
    report_id: str,
    payload: FeedbackReportDecision,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    return await _decide(
        report_id,
        to_status="RESOLVED",
        note=payload.note,
        actor=current_user,
        note_required=True,
    )
