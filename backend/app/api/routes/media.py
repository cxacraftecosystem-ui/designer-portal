import asyncio
import math
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile, status
from prisma.errors import UniqueViolationError

from app.api.consent_http import require_send
from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    get_current_user,
    is_content_admin,
    require_admin,
    require_content_admin,
    require_record_creator,
)
from app.questionnaire import chips as questionnaire_chips, filters as questionnaire_filters
from app.schemas.media import (
    MediaCompleteRequest,
    MediaRelinkRequest,
    MultipartAbortRequest,
    MultipartCompleteRequest,
    MultipartCompleteResponse,
    MultipartCreateRequest,
    MultipartCreateResponse,
    MultipartPresignPartsRequest,
    MultipartPresignPartsResponse,
    PresignRequest,
    PresignResponse,
    TranscriptRefineRequest,
    TranscriptUpdateRequest,
)
from app.services import (
    app_settings,
    design_workshop_posts,
    dictation_consent,
    media_mime,
    transcribe_choice,
    transcript_insights,
    transcription_jobs,
)
from app.services.ai import (
    UnknownDimension,
    analyze_measurement_image_bytes,
    normalize_dimension,
    refine_transcript_text,
    transcribe_audio,
)
from app.services.artisan_merge import redirect_payload
from app.services.concurrency import gather_reads
from app.services.dictation_consent import MEDIA_TAG, SendRefused
from app.services.identity_ocr import SUPPORTED_MIME_TYPES
from app.services.media_naming import display_filename, interview_record
from app.services.media_queue import (
    enqueue_media_processing_jobs,
    process_next_media_jobs,
    transcribe_media_now,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import assert_may_file_under
from app.services.records import (
    MEDIA_TYPES,
    RECORD_STATUSES,
    add_date_range,
    attach_location,
    clean_data,
    contains,
    enum_filter_or_422,
    jsonify_metadata,
    media_relation_data,
    media_url_scope,
    public_encode,
    require_record,
    viewable_where,
    with_id_tiebreak,
)
from app.services.s3 import (
    abort_multipart_upload,
    complete_multipart_upload,
    create_multipart_upload,
    delete_object,
    head_object,
    make_object_key,
    presign_put_url,
    presign_upload_part,
    public_url_for_key,
)
from app.services.uploads import read_upload_bounded
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

MULTIPART_PART_SIZE = 16 * 1024 * 1024

MEDIA_PROCESSING_JOB_STATUSES = frozenset(
    {"QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"}
)

router = APIRouter(prefix="/media", tags=["media"])

_MEDIA_OWN_FILTERS = ("q", "dateFrom", "dateTo", "designWorkshopId")


def _questionnaire_media_filters(request: Request | None) -> Any:
    return questionnaire_filters.parse(request.query_params if request is not None else {}).without(*_MEDIA_OWN_FILTERS)


async def _with_questionnaire_chips(payload: Any) -> Any:
    rows = payload.get("items") if isinstance(payload, dict) and isinstance(payload.get("items"), list) else (
        payload if isinstance(payload, list) else [payload])
    ids = [str(r.get("id")) for r in rows if isinstance(r, dict) and r.get("questionnaireInterviewId")]
    if ids:
        found = await questionnaire_chips.for_media(db, ids)
        for r in rows:
            if isinstance(r, dict) and str(r.get("id")) in found:
                r["questionnaireChips"] = found[str(r.get("id"))]
    await _with_transcript_previews(rows)
    return payload


async def _with_transcript_previews(rows: list[Any]) -> None:
    from app.services import transcript_pipeline

    texts = {
        str(r.get("id")): (r.get("transcriptText") if isinstance(r.get("transcriptText"), str) and r.get("transcriptText").strip() else None)
        for r in rows if isinstance(r, dict) and r.get("id")
    }
    if not any(texts.values()):
        for r in rows:
            if isinstance(r, dict) and r.get("id"):
                r["transcriptPreview"] = None
        return
    found = await transcript_pipeline.previews(texts)
    for r in rows:
        if isinstance(r, dict) and r.get("id"):
            r["transcriptPreview"] = found.get(str(r.get("id")))


_UNSET_URLS = object()

INCLUDE = {
    "uploadedBy": True,
    "location": True,
    "artisan": True,
    "craft": True,
    "workshop": True,
    "product": True,
    "tool": True,
    "processingJobs": True,
}


async def _interview_labels(rows: list[Any]) -> dict[str, tuple[str, str]]:
    ids = sorted({r.questionnaireInterviewId for r in rows if r.questionnaireInterviewId})
    if not ids:
        return {}
    interviews = await db.questionnaireinterview.find_many(
        where={"id": {"in": ids}}, include={"artisans": {"include": {"artisan": True}}}
    )
    return {i.id: interview_record(i) for i in interviews}


async def _public(
    rows: Any,
    viewer: Any = None,
    *,
    media_urls: Any = _UNSET_URLS,
    media_workshops: frozenset[str] = frozenset(),
) -> Any:
    if media_urls is _UNSET_URLS and media_workshops:
        raise ValueError(
            "_public(media_workshops=...) needs media_urls named beside it: under _UNSET_URLS both "
            "halves are resolved from the viewer, so a lone workshop set is either overwritten by "
            "that answer or acted on beside an empty uploader set."
        )
    many = isinstance(rows, list)
    items = list(rows) if many else [rows]
    labelled = [r for r in items if r is not None]
    if media_urls is _UNSET_URLS and viewer is not None:
        labels, (media_urls, media_workshops) = await gather_reads(
            _interview_labels(labelled),
            media_url_scope(viewer),
        )
    else:
        labels = await _interview_labels(labelled)
        if media_urls is _UNSET_URLS:
            media_urls = set()
    encoded = public_encode(rows, viewer, media_urls=media_urls, media_workshops=media_workshops)
    for row, out in zip(items, encoded if many else [encoded]):
        if row is None or not isinstance(out, dict):
            continue
        kind, name = labels.get(row.questionnaireInterviewId or "", ("", ""))
        out["displayFilename"] = display_filename(
            row, record_type=kind or None, record_name=name or None, fallback=row.id
        )
    return encoded


_UPLOAD_MIME_DENIED = media_mime.UPLOAD_MIME_DENIED
_normalized_mime = media_mime.normalized_mime
is_uploadable_mime = media_mime.is_uploadable_mime
signed_read_headers = media_mime.signed_read_headers


def _assert_uploadable(mime_type: str, size_bytes: int | None, settings: Any) -> None:
    normalized = _normalized_mime(mime_type)
    if not is_uploadable_mime(normalized):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{normalized or 'That file type'} cannot be uploaded here.",
        )
    ceiling = media_mime.upload_ceiling(settings)
    if media_mime.over_ceiling(size_bytes, settings):
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"That file is over the {ceiling // (1024 * 1024)} MB upload limit; "
                "split it or send a smaller one."
            ),
        )


async def presign_media_upload(
    payload: PresignRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    _assert_uploadable(payload.mimeType, payload.sizeBytes, settings)
    object_key = make_object_key(current_user.id, payload.filename)
    return {
        "uploadUrl": presign_put_url(object_key, payload.mimeType, payload.sizeBytes),
        "method": "PUT",
        "objectKey": object_key,
        "bucket": settings.aws_s3_bucket,
        "headers": {"Content-Type": payload.mimeType},
        "publicUrl": None,
    }


V2_TYPE = "questionnaire_session"


async def _json_body(request: Request) -> dict[str, Any]:
    from fastapi.exceptions import RequestValidationError

    try:
        body = await request.json()
    except Exception:  # noqa: BLE001 - not JSON: the old route's 422
        raise RequestValidationError(
            [{"type": "json_invalid", "loc": ("body",), "msg": "JSON body expected", "input": None}]
        ) from None
    if not isinstance(body, dict):
        raise RequestValidationError(
            [{"type": "dict_type", "loc": ("body",), "msg": "An object is expected", "input": body}]
        )
    return body


def _old_shape(model: Any, body: dict[str, Any]) -> Any:
    from fastapi.exceptions import RequestValidationError
    from pydantic import ValidationError

    try:
        return model.model_validate(body)
    except ValidationError as exc:
        errors = [{**e, "loc": ("body", *e.get("loc", ()))} for e in exc.errors()]
        raise RequestValidationError(errors) from None


async def _v2_shape(model: Any, body: dict[str, Any]) -> Any:
    from pydantic import ValidationError

    from app.api.routes.questionnaire_v2 import v2_enabled
    from app.questionnaire.common import fail

    if not v2_enabled():
        fail("NOT_FOUND")
    try:
        return model.model_validate(body)
    except ValidationError as exc:
        errors = [{"loc": [str(p) for p in e.get("loc", ())], "msg": e.get("msg")} for e in exc.errors()]
        fail("VALIDATION_FAILED", errors[0]["msg"] if errors else None, errors=errors)


def _is_v2(body: dict[str, Any]) -> bool:
    return str(body.get("linkedRecordType") or "").lower() == V2_TYPE


@router.post("/presign", response_model=PresignResponse)
async def presign_media_upload_route(
    request: Request, current_user: Any = Depends(get_current_user)
) -> Any:
    from fastapi.responses import JSONResponse

    from app.questionnaire import recordings as v2_recordings
    from app.questionnaire.schemas import PresignRequest as V2PresignRequest

    body = await _json_body(request)
    if _is_v2(body):
        return JSONResponse(await v2_recordings.presign(await _v2_shape(V2PresignRequest, body), current_user))
    return await presign_media_upload(_old_shape(PresignRequest, body), current_user)


@router.post("/multipart/create", response_model=MultipartCreateResponse)
async def create_multipart_route(
    request: Request, current_user: Any = Depends(get_current_user)
) -> Any:
    from fastapi.responses import JSONResponse

    from app.questionnaire import recordings as v2_recordings
    from app.questionnaire.schemas import MultipartCreateRequest as V2MultipartCreateRequest

    body = await _json_body(request)
    if _is_v2(body):
        return JSONResponse(
            await v2_recordings.multipart_create(await _v2_shape(V2MultipartCreateRequest, body), current_user)
        )
    return await create_multipart(_old_shape(MultipartCreateRequest, body), current_user)


@router.get("/uploads/{client_clip_key}")
async def upload_state(client_clip_key: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.api.routes.questionnaire_v2 import require_v2
    from app.questionnaire import recordings as v2_recordings

    await require_v2()
    return await v2_recordings.upload_state(client_clip_key, current_user)


@router.post("/complete", status_code=status.HTTP_201_CREATED)
async def complete_media_upload_route(
    request: Request, current_user: Any = Depends(get_current_user)
) -> Any:
    from fastapi.responses import JSONResponse

    from app.questionnaire import recordings as v2_recordings, shims_dr
    from app.questionnaire.schemas import CompleteRequest as V2CompleteRequest

    body = await _json_body(request)
    if _is_v2(body):
        recording, created = await v2_recordings.complete(await _v2_shape(V2CompleteRequest, body), current_user)
        return JSONResponse(recording, status_code=status.HTTP_201_CREATED if created else status.HTTP_200_OK)
    payload = _old_shape(MediaCompleteRequest, body)
    answer = await complete_media_upload(payload, current_user)
    await shims_dr.after_legacy_complete(answer, payload, current_user)
    return answer


def _assert_owns_object(object_key: str, current_user: Any) -> None:
    if not object_key.startswith(f"media/{current_user.id}/"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="You can only manage your own uploads"
        )


async def create_multipart(
    payload: MultipartCreateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    _assert_uploadable(payload.mimeType, payload.sizeBytes, settings)
    object_key = make_object_key(current_user.id, payload.filename)
    upload_id = await asyncio.to_thread(create_multipart_upload, object_key, payload.mimeType)
    part_count = max(1, math.ceil(payload.sizeBytes / MULTIPART_PART_SIZE))
    return {
        "objectKey": object_key,
        "uploadId": upload_id,
        "bucket": settings.aws_s3_bucket,
        "partSize": MULTIPART_PART_SIZE,
        "partCount": part_count,
        "publicUrl": None,
    }


@router.post("/multipart/presign-parts", response_model=MultipartPresignPartsResponse)
async def presign_multipart_parts(
    payload: MultipartPresignPartsRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _assert_owns_object(payload.objectKey, current_user)
    ceiling_part = media_mime.max_part_number(get_settings())
    if len(payload.partNumbers) > ceiling_part or any(n > ceiling_part for n in payload.partNumbers):
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="That upload has more parts than the upload limit allows.",
        )
    urls: dict[str, str] = {}
    for part_number in payload.partNumbers:
        urls[str(part_number)] = await asyncio.to_thread(
            presign_upload_part, payload.objectKey, payload.uploadId, part_number
        )
    return {"urls": urls}


@router.post("/multipart/complete", response_model=MultipartCompleteResponse)
async def complete_multipart(
    payload: MultipartCompleteRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _assert_owns_object(payload.objectKey, current_user)
    settings = get_settings()
    parts = sorted(
        ({"PartNumber": part.partNumber, "ETag": part.etag} for part in payload.parts),
        key=lambda item: item["PartNumber"],
    )
    if not parts:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="The upload didn't finish. Try again."
        )
    await asyncio.to_thread(complete_multipart_upload, payload.objectKey, payload.uploadId, parts)
    return {
        "objectKey": payload.objectKey,
        "bucket": settings.aws_s3_bucket,
        "publicUrl": None,
    }


@router.post("/multipart/abort")
async def abort_multipart(
    payload: MultipartAbortRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, bool]:
    _assert_owns_object(payload.objectKey, current_user)
    await asyncio.to_thread(abort_multipart_upload, payload.objectKey, payload.uploadId)
    return {"aborted": True}


@router.post("/transcribe")
async def transcribe_media_audio(
    file: UploadFile = File(...),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    if await app_settings.load_transcription_paused():
        return transcription_jobs.paused_result()
    return await transcribe_audio(file, get_settings())


MEASUREMENT_MAX_BYTES = 8 * 1024 * 1024


@router.post("/analyze-measurement")
async def analyze_media_measurement(
    request: Request,
    file: UploadFile = File(...),
    dimension: str | None = Query(default=None),
    _: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    try:
        requested = normalize_dimension(dimension)
    except UnknownDimension as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    content = await read_upload_bounded(
        file, MEASUREMENT_MAX_BYTES, request=request, purpose="image"
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="No image was uploaded. Photograph the object on the grid sheet and try again.",
        )
    mime_type = (file.content_type or "image/jpeg").split(";")[0].strip().lower()
    if mime_type not in SUPPORTED_MIME_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"{mime_type or 'That file type'} cannot be read; send a JPEG, PNG or WebP.",
        )

    result = await analyze_measurement_image_bytes(
        content,
        file.filename or "measurement.jpg",
        mime_type,
        get_settings(),
        requested,
    )
    if not result.get("available"):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(
                result.get("message") or "Grid measurement isn't available right now."
            ),
        )
    return result


async def _finish_pending_media(
    existing: Any, processing_requests: list[str] | None, user_id: str, settings: Any
) -> dict[str, Any]:
    processing_requests = await transcribe_choice.resolve_retry(existing, processing_requests)
    if processing_requests and not (existing.processingJobs or []):
        await enqueue_media_processing_jobs(existing, processing_requests, user_id, settings)
        existing = await db.mediafile.find_unique(where={"id": existing.id}, include=INCLUDE)
    return await _public(existing, media_urls={user_id}, media_workshops=frozenset())


ENQUEUEABLE_PROCESSING_REQUESTS = frozenset({"TRANSCRIPTION"})


SERVER_WRITTEN_TRANSCRIPT_FIELDS = frozenset(
    {"transcriptText", "transcriptSummary", "transcriptStatus", "transcriptError"}
)


async def _assert_stored_object_within_ceiling(object_key: str, settings: Any) -> None:
    ceiling = int(getattr(settings, "media_upload_max_bytes", 0) or 0)
    if not ceiling:
        return
    head = await asyncio.to_thread(head_object, object_key)
    if head is None or head.size_bytes <= ceiling:
        return
    try:
        await asyncio.to_thread(delete_object, object_key)
    except Exception:  # noqa: BLE001 — the refusal is the answer; a storage hiccup must not mask it
        pass
    raise HTTPException(
        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        detail=(
            f"That file is over the {ceiling // (1024 * 1024)} MB upload limit; "
            "split it or send a smaller one."
        ),
    )


def _upload_as_filed(payload: MediaCompleteRequest) -> SimpleNamespace:
    return SimpleNamespace(
        id="",
        designWorkshopId=payload.designWorkshopId,
        linkedRecordType=payload.linkedRecordType,
        linkedRecordId=payload.linkedRecordId,
    )


def _assert_enqueueable(processing_requests: list[str] | None) -> None:
    unsupported = sorted(
        {
            token
            for raw in processing_requests or []
            if (token := str(raw).strip().upper()) and token not in ENQUEUEABLE_PROCESSING_REQUESTS
        }
    )
    if not unsupported:
        return
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=(
            "A measurement can't be requested with the upload. Upload the photo, then use "
            "Measure and save the value once you have checked it."
        ),
    )


async def complete_media_upload(
    payload: MediaCompleteRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    processing_requests = payload.processingRequests
    _assert_enqueueable(processing_requests)

    _assert_uploadable(payload.mimeType, None, settings)

    existing = await db.mediafile.find_unique(
        where={"objectKey": payload.objectKey}, include=INCLUDE
    )
    if existing is not None:
        if getattr(existing, "uploadedById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="This upload belongs to another user",
            )
        return await _finish_pending_media(existing, processing_requests, current_user.id, settings)

    _assert_owns_object(payload.objectKey, current_user)

    await design_workshop_posts.refuse_a_holders_media_write(
        _upload_as_filed(payload), current_user
    )

    await _assert_stored_object_within_ceiling(payload.objectKey, settings)

    data = clean_data(
        payload.model_dump(exclude={"processingRequests"} | SERVER_WRITTEN_TRANSCRIPT_FIELDS)
    )
    data = await attach_location(data)
    data["bucket"] = data.get("bucket") or settings.aws_s3_bucket
    data["url"] = public_url_for_key(data["objectKey"])
    data["uploadedById"] = current_user.id
    await redirect_payload(data)
    relation_data = media_relation_data(data.get("linkedRecordType"), data.get("linkedRecordId"))
    parent: Any = None
    if relation_data:
        delegate = _relink_delegate(str(data["linkedRecordType"]).lower())
        parent = (
            await delegate.find_unique(where={"id": data["linkedRecordId"]}) if delegate else None
        )
        if parent is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Linked record not found"
            )
    data.update(relation_data)
    if parent is None:
        parent = await _tagged_parent(data.get("linkedRecordType"), data.get("linkedRecordId"))

    inherit_parent_workshop(data, parent)

    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_may_file_under(data.get("designWorkshopId"), current_user)
    tagged_workshop = (
        data.get("linkedRecordId")
        if str(data.get("linkedRecordType") or "").strip().lower() == MEDIA_TAG.lower()
        else None
    )
    if tagged_workshop and await db.designworkshop.find_unique(where={"id": tagged_workshop}):
        await assert_may_file_under(tagged_workshop, current_user)
    stamp_workshop_submission(data, check=check)
    data.setdefault("status", "PENDING")
    pin_pending_if_late(data, current_user, check=check)

    jsonify_metadata(data)
    try:
        created = await db.mediafile.create(data=data, include=INCLUDE)
    except UniqueViolationError:
        racer = await db.mediafile.find_unique(
            where={"objectKey": payload.objectKey}, include=INCLUDE
        )
        if racer is not None:
            return await _finish_pending_media(
                racer, processing_requests, current_user.id, settings
            )
        raise
    processing_requests = await transcribe_choice.resolve_upload(created, processing_requests)
    await enqueue_media_processing_jobs(created, processing_requests, current_user.id, settings)
    created = await db.mediafile.find_unique(where={"id": created.id}, include=INCLUDE)
    return await _public(created, current_user)


@router.get("")
async def list_media(
    current_user: Any = Depends(get_current_user),
    request: Request = None,  # type: ignore[assignment]  # FastAPI injects it; a direct call passes none
    search: str | None = None,
    mediaType: str | None = None,
    linkedRecordType: str | None = None,
    linkedRecordId: str | None = None,
    designWorkshopId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    uploadedBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    vis = await viewable_where(current_user, owner_field="uploadedById")
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [
            {"originalFilename": contains(search)},
            {"caption": contains(search)},
            {"mimeType": contains(search)},
        ]
        by_chips = await questionnaire_filters.media_text_ids(search.strip()[: questionnaire_filters.MAX_TEXT], client=db)
        if by_chips:
            where["OR"].append({"id": {"in": by_chips}})
    questionnaire_only = _questionnaire_media_filters(request)
    if questionnaire_filters.media_filtered(questionnaire_only):
        where.setdefault("AND", []).append(
            {"id": {"in": await questionnaire_filters.media_ids_matching(questionnaire_only, client=db)}}
        )
    if mediaType:
        where["mediaType"] = enum_filter_or_422(mediaType, MEDIA_TYPES, field="mediaType")
    if linkedRecordType:
        where["linkedRecordType"] = linkedRecordType
    if linkedRecordId:
        where["linkedRecordId"] = linkedRecordId
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if uploadedBy:
        where["uploadedById"] = uploadedBy
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total, items = await gather_reads(
        db.mediafile.count(where=where),
        db.mediafile.find_many(
            where=where,
            include=INCLUDE,
            skip=skip,
            take=page_size,
            order=with_id_tiebreak({"createdAt": "desc"}),
        ),
    )
    return await _with_questionnaire_chips(page_payload(await _public(items, current_user), total, page, page_size))


ORPHAN_FK_FIELDS = {
    "artisan": "artisanId",
    "craft": "craftId",
    "workshop": "workshopId",
    "product": "productId",
    "tool": "toolId",
    "questionnaire": "questionnaireInterviewId",
    "questionnaireinterview": "questionnaireInterviewId",
}

ORPHAN_TAG_TYPES: dict[str, str] = {
    "process": "process",
    "processstep": "processstep",
    "designWorkshop": "designworkshop",
    "designworkshop": "designworkshop",
    "media": "media",
    "misc": "media",
}


def _orphan_tag_delegate(kind: str) -> Any:
    return {
        "process": db.process,
        "processstep": db.processstep,
        "designworkshop": db.designworkshop,
        "media": db.mediafile,
    }.get(kind)


async def _tag_only_orphans() -> list[Any]:
    tagged = await db.mediafile.find_many(
        where={
            "linkedRecordType": {"in": list(ORPHAN_TAG_TYPES)},
            "linkedRecordId": {"not": None},
        },
    )
    if not tagged:
        return []
    by_kind: dict[str, set[str]] = {}
    for row in tagged:
        kind = ORPHAN_TAG_TYPES[(row.linkedRecordType or "")]
        by_kind.setdefault(kind, set()).add(row.linkedRecordId)
    live: dict[str, set[str]] = {}
    planned: list[tuple[str, Any]] = []
    for kind, ids in by_kind.items():
        delegate = _orphan_tag_delegate(kind)
        if delegate is None:  # pragma: no cover - the two dicts are written together
            live[kind] = ids
            continue
        planned.append((kind, delegate.find_many(where={"id": {"in": sorted(ids)}})))
    for (kind, _coro), rows in zip(
        planned, await gather_reads(*(coro for _kind, coro in planned)), strict=True
    ):
        live[kind] = {r.id for r in rows}
    orphan_ids = [
        row.id
        for row in tagged
        if row.linkedRecordId not in live.get(ORPHAN_TAG_TYPES[(row.linkedRecordType or "")], set())
    ]
    if not orphan_ids:
        return []
    return await db.mediafile.find_many(
        where={"id": {"in": orphan_ids}},
        include=INCLUDE,
        order={"createdAt": "desc"},
    )


async def _tagged_parent(record_type: str | None, record_id: str | None) -> Any:
    normalized = (record_type or "").strip().lower()
    if not normalized or not record_id:
        return None
    try:
        if normalized == "processstep":
            step = await db.processstep.find_unique(where={"id": record_id})
            process_id = getattr(step, "processId", None)
            return await db.process.find_unique(where={"id": process_id}) if process_id else None
        delegate = {
            "process": db.process,
            "artisan": db.artisan,
            "craft": db.craft,
            "product": db.productdocumentation,
            "tool": db.tooldocumentation,
            "questionnaire": db.questionnaireinterview,
            "questionnaireinterview": db.questionnaireinterview,
            "media": db.mediafile,
            "misc": db.mediafile,
        }.get(normalized)
        return await delegate.find_unique(where={"id": record_id}) if delegate else None
    except Exception:  # noqa: BLE001 — see the docstring: this may only ever fill in a blank.
        return None


def inherit_parent_workshop(
    data: dict[str, Any], parent: Any, *, replace: bool = False
) -> dict[str, Any]:
    if data.get("workshopId") and not replace:
        return data
    inherited = getattr(parent, "workshopId", None) if parent is not None else None
    if inherited:
        data["workshopId"] = inherited
    return data


def _relink_delegate(record_type: str) -> Any:
    return {
        "artisan": db.artisan,
        "craft": db.craft,
        "workshop": db.workshop,
        "product": db.productdocumentation,
        "tool": db.tooldocumentation,
        "questionnaire": db.questionnaireinterview,
        "questionnaireinterview": db.questionnaireinterview,
        "process": db.process,
        "processstep": db.processstep,
        "media": db.mediafile,
        "misc": db.mediafile,
    }.get(record_type)


MEDIA_BATCH_MAX = 100


@router.get("/batch")
async def get_media_batch(
    ids: str = Query(..., description="Comma-separated media ids, at most MEDIA_BATCH_MAX of them."),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    wanted = list(dict.fromkeys(part.strip() for part in ids.split(",") if part.strip()))
    if len(wanted) > MEDIA_BATCH_MAX:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Ask for at most {MEDIA_BATCH_MAX} files at a time.",
        )
    if not wanted:
        return {"items": [], "missing": []}
    rows = await db.mediafile.find_many(where={"id": {"in": wanted}}, include=INCLUDE)
    by_id = {row.id: row for row in rows}
    found = [by_id[media_id] for media_id in wanted if media_id in by_id]
    items = await _public(found, current_user) if found else []
    return {"items": items, "missing": [media_id for media_id in wanted if media_id not in by_id]}


@router.get("/orphans")
async def list_orphan_media(
    current_user: Any = Depends(require_content_admin),
) -> list[dict[str, Any]]:
    conditions = [
        {"linkedRecordType": rec_type, "linkedRecordId": {"not": None}, fk: None}
        for rec_type, fk in ORPHAN_FK_FIELDS.items()
    ]
    rows = await db.mediafile.find_many(
        where={"OR": conditions},
        include=INCLUDE,
        order={"createdAt": "desc"},
    )
    seen = {row.id for row in rows}
    combined = [*rows, *(row for row in await _tag_only_orphans() if row.id not in seen)]
    combined.sort(key=lambda row: row.createdAt, reverse=True)
    return await _public(combined, current_user)


@router.post("/{media_id}/relink")
async def relink_media(
    media_id: str,
    payload: MediaRelinkRequest,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    rec_type = payload.linkedRecordType.lower()
    delegate = _relink_delegate(rec_type)
    if delegate is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="This kind of record can't be linked to a file."
        )
    target = await delegate.find_unique(where={"id": payload.linkedRecordId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Target record not found")
    await design_workshop_posts.refuse_a_holders_media_write(
        media, current_user, relinked_to=(rec_type, payload.linkedRecordId)
    )
    data: dict[str, Any] = {
        "linkedRecordType": rec_type,
        "linkedRecordId": payload.linkedRecordId,
    }
    data.update(media_relation_data(rec_type, payload.linkedRecordId))
    inherit_parent_workshop(data, target, replace=True)
    updated = await db.mediafile.update(where={"id": media.id}, data=data, include=INCLUDE)
    return await _public(updated, current_user)


@router.post("/{media_id}/refine-transcript")
async def refine_media_transcript(
    media_id: str,
    payload: TranscriptRefineRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    if (
        not is_content_admin(current_user)
        and getattr(media, "uploadedById", None) != current_user.id
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only refine the transcript of media you uploaded",
        )
    await design_workshop_posts.refuse_a_holders_media_write(media, current_user)
    await require_send(dictation_consent.transcription_verdict(
        media, send=dictation_consent.REFINEMENT, resolve_from_stages=True
    ))
    transcript = getattr(media, "transcriptText", None)
    return await refine_transcript_text(transcript, payload.translate, get_settings())


@router.post("/{media_id}/transcribe-now")
async def transcribe_media_now_route(
    media_id: str,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    await design_workshop_posts.refuse_a_holders_media_write(media, current_user)
    if not transcribe_choice.transcribable(media):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only audio and video files can be transcribed.",
        )
    await require_send(dictation_consent.transcription_verdict(media, resolve_from_stages=True))
    try:
        await transcribe_media_now(media, get_settings())
    except transcription_jobs.TranscriptionPaused as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    except SendRefused as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    updated = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    return await _public(updated, current_user)


@router.post("/{media_id}/transcript")
async def set_media_transcript(
    media_id: str,
    payload: TranscriptUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    media = await require_record(db.mediafile, media_id)
    if (
        not is_content_admin(current_user)
        and getattr(media, "uploadedById", None) != current_user.id
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only edit the transcript of media you uploaded",
        )
    await design_workshop_posts.refuse_a_holders_media_write(media, current_user)
    updated = await db.mediafile.update(
        where={"id": media.id},
        data={
            "transcriptText": payload.text,
            "transcriptStatus": "COMPLETED",
            "transcriptError": None,
            "transcriptEditedAt": datetime.now(UTC),
            "transcriptEditedById": current_user.id,
        },
        include=INCLUDE,
    )
    if transcript_insights.enabled():
        await transcript_insights.enqueue_after_transcript(media.id)
    return await _public(updated, current_user)


@router.get("/jobs")
async def list_media_processing_jobs(
    current_user: Any = Depends(get_current_user),
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = (
        {} if is_content_admin(current_user) else {"requestedById": current_user.id}
    )
    if statusFilter:
        where["status"] = enum_filter_or_422(
            statusFilter, MEDIA_PROCESSING_JOB_STATUSES, field="statusFilter"
        )
    total, jobs = await gather_reads(
        db.mediaprocessingjob.count(where=where),
        db.mediaprocessingjob.find_many(
            where=where,
            include={"mediaFile": True, "requestedBy": True, "product": True, "tool": True},
            skip=skip,
            take=page_size,
            order=with_id_tiebreak({"createdAt": "desc"}),
        ),
    )
    return page_payload(public_encode(jobs), total, page, page_size)


@router.post("/jobs/process")
async def process_media_processing_jobs(
    limit: int | None = Query(default=None, ge=1, le=25),
    _: Any = Depends(require_content_admin),
) -> dict[str, int]:
    return await process_next_media_jobs(limit=limit, worker_id="manual-api", recover=False)


RETRYABLE_JOB_STATUSES = ("FAILED", "CANCELLED")


@router.post("/jobs/{job_id}/retry")
async def retry_media_processing_job(
    job_id: str,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    job = await require_record(db.mediaprocessingjob, job_id)
    media = await db.mediafile.find_unique(where={"id": job.mediaFileId})
    if media is not None:
        await design_workshop_posts.refuse_a_holders_media_write(media, current_user)
    requeued = await db.mediaprocessingjob.update_many(
        where={"id": job.id, "status": {"in": list(RETRYABLE_JOB_STATUSES)}},
        data={
            "status": "QUEUED",
            "runAfter": datetime.now(UTC),
            "lockedAt": None,
            "lockedBy": None,
            "completedAt": None,
            "error": None,
        },
    )
    if requeued != 1:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only a failed or cancelled job can be retried.",
        )
    updated = await db.mediaprocessingjob.find_unique(
        where={"id": job.id}, include={"mediaFile": True}
    )
    return public_encode(updated)


@router.delete("/object", status_code=status.HTTP_204_NO_CONTENT)
async def delete_staged_object(
    objectKey: str, current_user: Any = Depends(get_current_user)
) -> None:
    if not objectKey.startswith(f"media/{current_user.id}/"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only delete your own staged uploads",
        )
    existing = await db.mediafile.find_first(where={"objectKey": objectKey})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This upload is attached to a saved file. Delete that file instead.",
        )
    await asyncio.to_thread(delete_object, objectKey)


@router.get("/{media_id}")
async def get_media(media_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    media = media or await require_record(db.mediafile, media_id)
    return await _with_questionnaire_chips(await _public(media, current_user))


@router.delete("/{media_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_media(media_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    media = await require_record(db.mediafile, media_id)
    await design_workshop_posts.refuse_a_holders_media_write(media, current_user)
    object_key = getattr(media, "objectKey", None)
    await db.mediafile.delete(where={"id": media_id})
    if object_key:
        still_referenced = await db.mediafile.find_first(where={"objectKey": object_key})
        if still_referenced is None:
            try:
                await asyncio.to_thread(delete_object, object_key)
            except Exception:  # noqa: BLE001 - best-effort storage cleanup
                pass
