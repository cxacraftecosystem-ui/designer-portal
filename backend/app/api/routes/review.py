from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder
from pydantic import Field, ValidationError

from app.core.db import db
from app.core.deps import (
    can_edit_others_record,
    can_review_record,
    enum_or_raw,
    get_value,
    is_admin,
    is_master_admin,
    require_reviewer,
    role_rank,
    values_match,
)
from app.schemas.common import APIModel, ReviewAction
from app.schemas.questionnaire import QuestionnaireInterviewUpdate
from app.schemas.records import (
    ArtisanUpdate,
    ProcessUpdate,
    ProductUpdate,
    ToolUpdate,
    WorkshopUpdate,
    drop_masked_identity_numbers,
)
from app.services import aadhaar_fingerprint, design_workshop_posts
from app.services.access import record_revision
from app.services.concurrency import gather_reads
from app.services.record_design_workshop import (
    DESIGN_WORKSHOP_KEY,
    assert_may_write_a_record_filed_under,
)
from app.services.records import (
    clean_data,
    decimal_to_string,
    jsonify_metadata,
    merge_field_provenance,
    public_encode,
    review_update,
)
from app.services.review_queue import reviewable_creator_roles
from app.services.workshop_access import record_needs_admin_approval, stamp_workshop_submission
from prisma import Json

router = APIRouter(prefix="/review", tags=["review"])

LATE_SUBMISSION_LOG_NOTE = "Late workshop submission — approved by an admin."

EDIT_LOG_PREFIX = "EDITED"


_REVIEW_TYPES: dict[str, tuple[str, str, str]] = {
    "artisan": ("artisan", "ARTISAN", "createdBy"),
    "workshop": ("workshop", "WORKSHOP", "createdBy"),
    "product": ("productdocumentation", "PRODUCT", "createdBy"),
    "tool": ("tooldocumentation", "TOOL", "createdBy"),
    "process": ("process", "PROCESS", "createdBy"),
    "questionnaire": ("questionnaireinterview", "QUESTIONNAIRE", "createdBy"),
    "media": ("mediafile", "MEDIA", "uploadedBy"),
}


def _review_type_or_404(record_type: str) -> tuple[str, str, str]:
    key = record_type.lower()
    if key not in _REVIEW_TYPES:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="This kind of record can't be reviewed here."
        )
    return _REVIEW_TYPES[key]


def delegate_for(record_type: str) -> tuple[Any, str, str]:
    attribute, log_type, creator_relation = _review_type_or_404(record_type)
    return getattr(db, attribute), log_type, creator_relation


def delegate_on(client: Any, record_type: str) -> Any:
    attribute, _log_type, _creator_relation = _review_type_or_404(record_type)
    return getattr(client, attribute)


_PENDING_SOURCES: list[tuple[str, Any, tuple[str, ...]]] = [
    ("artisan", db.artisan, ("name",)),
    ("workshop", db.workshop, ("title",)),
    ("product", db.productdocumentation, ("productName", "artisanName", "craftName")),
    ("tool", db.tooldocumentation, ("toolkitName", "englishName", "craftName")),
    ("process", db.process, ("name",)),
    ("questionnaire", db.questionnaireinterview, ("title",)),
]


PENDING_TAKE = 200


_reviewable_creator_roles = reviewable_creator_roles


def _label_for(row: Any, fields: tuple[str, ...]) -> str:
    for field in fields:
        value = get_value(row, field)
        if value:
            return str(value)
    return str(get_value(row, "id") or "Record")


def _creator_summary(creator: Any) -> dict[str, Any] | None:
    if creator is None:
        return None
    return {
        "id": get_value(creator, "id"),
        "name": get_value(creator, "name"),
        "role": str(getattr(get_value(creator, "role"), "value", get_value(creator, "role"))),
    }


PENDING_PAGE_MAX = 200


def _decode_before(before: str | None) -> tuple[datetime, str] | None:
    if not before:
        return None
    stamp, sep, record_id = before.rpartition("|")
    try:
        if not sep or not record_id:
            raise ValueError(before)
        return datetime.fromisoformat(stamp), record_id
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="That page of the review queue could not be found. Reload the queue.",
        ) from exc


def _encode_before(item: dict[str, Any]) -> str:
    return f"{item.get('createdAt') or ''}|{item.get('id') or ''}"


def _queue_item(record_type: str, row: Any, label_fields: tuple[str, ...]) -> dict[str, Any]:
    creator = get_value(row, "createdBy")
    return {
        "recordType": record_type,
        "id": get_value(row, "id"),
        "label": _label_for(row, label_fields),
        "place": get_value(row, "place"),
        "createdAt": jsonable_encoder(get_value(row, "createdAt")),
        "createdBy": _creator_summary(creator),
        "needsAdminApproval": record_needs_admin_approval(row),
    }


async def _pending_page(where: dict[str, Any], page_size: int, before: str | None) -> dict[str, Any]:
    cursor = _decode_before(before)
    page_where: dict[str, Any] = where
    if cursor is not None:
        stamp, record_id = cursor
        page_where = {
            "AND": [
                where,
                {"OR": [{"createdAt": {"lt": stamp}}, {"createdAt": stamp, "id": {"lt": record_id}}]},
            ]
        }
    reads = await gather_reads(
        *(
            delegate.find_many(
                where=page_where,
                include={"createdBy": True},
                order=[{"createdAt": "desc"}, {"id": "desc"}],
                take=page_size + 1,
            )
            for _record_type, delegate, _label_fields in _PENDING_SOURCES
        ),
        *(delegate.count(where=where) for _record_type, delegate, _label_fields in _PENDING_SOURCES),
    )
    row_sets = reads[: len(_PENDING_SOURCES)]
    total = sum(reads[len(_PENDING_SOURCES) :])
    merged = [
        _queue_item(record_type, row, label_fields)
        for (record_type, _delegate, label_fields), rows in zip(_PENDING_SOURCES, row_sets, strict=True)
        for row in rows
    ]
    merged.sort(key=lambda item: (item.get("createdAt") or "", item.get("id") or ""), reverse=True)
    items = merged[:page_size]
    more = len(merged) > page_size
    return {
        "items": items,
        "shown": len(items),
        "total": total,
        "cap": page_size,
        "truncated": more,
        "nextBefore": _encode_before(items[-1]) if more and items else None,
    }


@router.get("/pending")
async def list_pending_reviews(
    reviewer: Any = Depends(require_reviewer),
    pageSize: int | None = Query(None, ge=1, le=PENDING_PAGE_MAX),
    before: str | None = Query(None, max_length=200),
) -> dict[str, Any]:
    roles = _reviewable_creator_roles(reviewer)
    if roles is not None and not roles:
        empty: dict[str, Any] = {"items": [], "shown": 0, "total": 0, "cap": pageSize or PENDING_TAKE,
                                 "truncated": False}
        if pageSize is not None:
            empty["nextBefore"] = None
        return empty
    where: dict[str, Any] = {"status": "PENDING"}
    if roles is not None:
        where["createdBy"] = {"is": {"role": {"in": roles}}}

    if pageSize is not None:
        return await _pending_page(where, pageSize, before)

    items: list[dict[str, Any]] = []
    total = 0

    row_sets = await gather_reads(
        *(
            delegate.find_many(
                where=where,
                include={"createdBy": True},
                order={"createdAt": "desc"},
                take=PENDING_TAKE + 1,
            )
            for _record_type, delegate, _label_fields in _PENDING_SOURCES
        )
    )

    overflowed = [index for index, rows in enumerate(row_sets) if len(rows) > PENDING_TAKE]
    counted = dict(
        zip(
            overflowed,
            await gather_reads(
                *(_PENDING_SOURCES[index][1].count(where=where) for index in overflowed)
            ),
            strict=True,
        )
    )
    truncated = bool(overflowed)

    for index, (record_type, _delegate, label_fields) in enumerate(_PENDING_SOURCES):
        rows = row_sets[index]
        if index in counted:
            rows = rows[:PENDING_TAKE]
            total += counted[index]
        else:
            total += len(rows)
        items.extend(_queue_item(record_type, row, label_fields) for row in rows)
    items.sort(key=lambda item: item.get("createdAt") or "", reverse=True)
    return {
        "items": items,
        "shown": len(items),
        "total": total,
        "cap": PENDING_TAKE,
        "truncated": truncated,
    }


ALREADY_DECIDED = "Someone has already reviewed this record. Reload it to see where it stands."


async def _decided_above(reviewer: Any, record: Any) -> bool:
    previous = get_value(record, "reviewedById")
    if not previous or previous == get_value(reviewer, "id") or is_master_admin(reviewer):
        return False
    decider = await db.user.find_unique(where={"id": previous})
    return decider is not None and role_rank(reviewer) < role_rank(decider)


async def set_review_status(
    record_type: str,
    record_id: str,
    new_status: str,
    payload: ReviewAction,
    reviewer: Any,
) -> dict[str, Any]:
    delegate, log_type, creator_relation = delegate_for(record_type)
    record = await delegate.find_unique(where={"id": record_id}, include={creator_relation: True})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    creator = get_value(record, creator_relation)
    if not can_review_record(reviewer, get_value(creator, "role") if creator else None):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only review records created by people with a role below your own",
        )
    from app.questionnaire import review as v2_review, shims_dr

    v2_session = record_type.lower() == "questionnaire" and shims_dr.enabled() and bool(get_value(record, "clientKey"))
    current_status = str(enum_or_raw(get_value(record, "status")))
    reopened = v2_session and get_value(record, "pendingChangesAt") is not None
    if current_status != "PENDING" and not reopened and await _decided_above(reviewer, record):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_DECIDED)
    late = record_needs_admin_approval(record)
    if late and new_status == "APPROVED" and not is_admin(reviewer):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This record was submitted outside its workshop's dates. Only an admin or master "
                "admin can approve a late submission."
            ),
        )
    data = review_update(new_status, payload.notes, reviewer.id)
    log_notes = payload.notes
    if new_status == "APPROVED" and late:
        extra = get_value(record, "extraMetadata")
        submission = extra.get("workshopSubmission")
        data["extraMetadata"] = Json(
            {**extra, "workshopSubmission": {**submission, "needsAdminApproval": False}}
        )
        notes = (payload.notes or "").strip()
        log_notes = f"{notes}\n\n{LATE_SUBMISSION_LOG_NOTE}" if notes else LATE_SUBMISSION_LOG_NOTE
    if v2_session and new_status == "APPROVED":
        await v2_review.assert_may_approve(db, record_id)
        data["pendingChangesAt"] = None
    async with db.tx() as tx:
        target = delegate_on(tx, record_type)
        if not await target.update_many(where={"id": record_id, "status": current_status}, data=data):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=ALREADY_DECIDED)
        updated = await target.find_unique(where={"id": record_id})
        await tx.reviewlog.create(
            data={
                "recordType": log_type,
                "recordId": record_id,
                "status": new_status,
                "notes": log_notes,
                "reviewerId": reviewer.id,
            }
        )
        if v2_session and new_status == "APPROVED":
            await v2_review.approve_revisions(tx, record_id)
    return public_encode(updated)


@router.post("/{record_type}/{record_id}/approve")
async def approve_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "APPROVED", payload, reviewer)


@router.post("/{record_type}/{record_id}/reject")
async def reject_record(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    return await set_review_status(record_type, record_id, "REJECTED", payload, reviewer)


class MediaReviewEdit(APIModel):
    caption: str | None = None
    transcriptText: str | None = None
    transcriptSummary: str | None = None


_EDIT_SCHEMAS: dict[str, type[APIModel]] = {
    "artisan": ArtisanUpdate,
    "workshop": WorkshopUpdate,
    "product": ProductUpdate,
    "tool": ToolUpdate,
    "process": ProcessUpdate,
    "questionnaire": QuestionnaireInterviewUpdate,
    "media": MediaReviewEdit,
}

_NOT_REVIEW_EDITABLE = frozenset(
    {
        "status",
        "extraMetadata",
        "workshopId",
        DESIGN_WORKSHOP_KEY,
        "location",
        "artisanIds",
        "craftIds",
        "responses",
        "steps",
        "recordedAt",
        "recordedTimezone",
        "formContract",
    }
)


class ReviewEdit(APIModel):
    fields: dict[str, Any] = Field(default_factory=dict)
    note: str | None = None
    approve: bool = False


@router.post("/{record_type}/{record_id}/edit")
async def edit_reviewed_record(
    record_type: str,
    record_id: str,
    payload: ReviewEdit,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    key = record_type.lower()
    delegate, log_type, creator_relation = delegate_for(record_type)
    schema = _EDIT_SCHEMAS.get(key)
    if schema is None:  # pragma: no cover - delegate_for already rejects unknown types
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="This kind of record can't be reviewed here."
        )

    if not payload.fields:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Change at least one field before saving.",
        )
    blocked = sorted(set(payload.fields) & _NOT_REVIEW_EDITABLE)
    if blocked:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"These fields cannot be changed from a review edit: {', '.join(blocked)}. "
                "Use Approve, Reject or Send for revision for the status, and the record's own edit screen for "
                "its workshop, location and linked records."
            ),
        )
    try:
        parsed = schema(**payload.fields)
    except ValidationError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=jsonable_encoder(exc.errors(include_url=False)),
        ) from exc

    record = await delegate.find_unique(where={"id": record_id}, include={creator_relation: True})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    creator = get_value(record, creator_relation)
    if not can_edit_others_record(reviewer, get_value(creator, "role") if creator else None):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Editing someone else's record needs Professor access or above, and only for a "
                "record created by someone with a role below your own. Approve, reject, or send it "
                "back for revision with your comments instead."
            ),
        )
    if record_needs_admin_approval(record) and not is_admin(reviewer):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This record was submitted outside its workshop's dates. Only an admin or master "
                "admin can edit or approve a late submission."
            ),
        )
    if key == "media":
        await design_workshop_posts.refuse_a_holders_media_write(record, reviewer)
    else:
        await assert_may_write_a_record_filed_under(
            get_value(record, DESIGN_WORKSHOP_KEY), reviewer
        )

    data = drop_masked_identity_numbers(
        decimal_to_string(clean_data(parsed.model_dump(exclude_unset=True)))
    )
    if not data:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Change at least one field before saving.",
        )
    if key == "artisan":
        try:
            aadhaar_fingerprint.seal(data, previous=record)
        except aadhaar_fingerprint.FingerprintKeyMissing as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Aadhaar numbers can't be saved right now. Try again shortly.",
            ) from exc
    changed = sorted(
        {
            aadhaar_fingerprint.NUMBER_FIELD
            if field in (aadhaar_fingerprint.FINGERPRINT_FIELD, aadhaar_fingerprint.LAST4_FIELD)
            else field
            for field, value in data.items()
            if not values_match(get_value(record, field), value)
        }
    )

    async with db.tx() as tx:
        await record_revision(record, reviewer, data, key, client=tx)
        stamp_workshop_submission(data, record=record)
        merge_field_provenance(data, reviewer, previous=record)
        jsonify_metadata(data)
        updated = await delegate_on(tx, record_type).update(where={"id": record_id}, data=data)

        summary = (
            f"{EDIT_LOG_PREFIX}: {', '.join(changed)}"
            if changed
            else f"{EDIT_LOG_PREFIX}: no values changed"
        )
        note = (payload.note or "").strip()
        await tx.reviewlog.create(
            data={
                "recordType": log_type,
                "recordId": record_id,
                "status": str(enum_or_raw(get_value(record, "status"))),
                "notes": f"{summary}\n\n{note}" if note else summary,
                "reviewerId": reviewer.id,
            }
        )

    if payload.approve:
        return await set_review_status(
            record_type, record_id, "APPROVED", ReviewAction(notes=payload.note), reviewer
        )
    return public_encode(updated)


@router.post("/{record_type}/{record_id}/revise")
async def send_record_for_revision(
    record_type: str,
    record_id: str,
    payload: ReviewAction,
    reviewer: Any = Depends(require_reviewer),
) -> dict[str, Any]:
    if not payload.notes or not payload.notes.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Comments are required when sending a record back for revision",
        )
    return await set_review_status(record_type, record_id, "NEEDS_REVISION", payload, reviewer)
