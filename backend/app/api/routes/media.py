import asyncio
import math
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile, status
from prisma.errors import UniqueViolationError

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user, is_admin, require_admin, require_record_creator
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
from app.services import dictation_consent
from app.services.ai import (
    UnknownDimension,
    analyze_measurement_image_bytes,
    normalize_dimension,
    refine_transcript_text,
    transcribe_audio,
)
from app.services.dictation_consent import SendRefused

# The formats a vision model can read are a property of the PROVIDER, not of either feature that uses
# it — both this route and the identity-card reader send their bytes to the same Gemini model. One
# imported set rather than two lists that can disagree: a format the card reader accepts and the grid
# reader refuses would be a failure nobody could explain from either file alone.
from app.services.identity_ocr import SUPPORTED_MIME_TYPES
from app.services.media_naming import display_filename, interview_record
from app.services.media_queue import (
    enqueue_media_processing_jobs,
    process_next_media_jobs,
    transcribe_media_now,
)
from app.services.pagination import normalize_pagination, page_payload
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
    media_url_owners,
    public_encode,
    require_record,
    viewable_where,
)
from app.services.s3 import (
    abort_multipart_upload,
    complete_multipart_upload,
    create_multipart_upload,
    delete_object,
    make_object_key,
    presign_put_url,
    presign_upload_part,
    public_url_for_key,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

# S3 multipart part size. >= 5 MiB (S3 minimum for all but the last part); 16 MiB keeps the part
# count low for large videos while staying small enough to retry a single part cheaply.
MULTIPART_PART_SIZE = 16 * 1024 * 1024

#: ``MediaProcessingJobStatus``, mirrored from prisma/schema.prisma:126-132 the same way
#: ``records.RECORD_STATUSES`` and ``records.MEDIA_TYPES`` mirror their enums — as a frozenset, so a
#: route can validate a filter without the generated client being present.
#:
#: THIS FILE VALIDATES AGAINST TWO DIFFERENT STATUS ENUMS AND THEY SHARE NO VALUE. ``MediaFile.status``
#: is a ``RecordStatus`` (DRAFT/PENDING/APPROVED/REJECTED/NEEDS_REVISION) and is filtered with
#: ``RECORD_STATUSES`` in ``list_media``; ``MediaProcessingJob.status`` is the queue's own lifecycle
#: and is filtered with THIS set in ``list_media_processing_jobs``. ``GET /media/jobs`` was passing
#: ``RECORD_STATUSES``, and because the intersection of the two enums is EMPTY the route could not
#: answer a single filter value any client is able to send: every one of the six pills in
#: ``frontend/components/media/MediaJobsPanel.tsx`` 422'd with "status must be one of APPROVED,
#: DRAFT, NEEDS_REVISION, PENDING, REJECTED" — a list naming a column the caller never mentioned.
#: Even the "All" pill failed, because the panel always issues a second ``statusFilter=FAILED``
#: request alongside it to count failures. The whole processing-queue surface was dead on the wire.
#:
#: DO NOT "TIDY" THE TWO CALL SITES INTO ONE CONSTANT. Their similarity is the trap: the two columns
#: are on different tables, mean different things, and a value that is valid for one is a 500 from
#: Prisma on the other (``enum_filter_or_422`` exists to turn exactly that 500 into a 422).
#:
#: Kept here rather than beside ``RECORD_STATUSES`` in ``services/records.py`` only because this
#: route is the sole caller; move it there the day a second module needs it, not before.
MEDIA_PROCESSING_JOB_STATUSES = frozenset({"QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"})

router = APIRouter(prefix="/media", tags=["media"])

# "the caller did not say which uploaders' URLs may travel". Distinct from None, which MEANS "all of
# them" — see ``records.public_encode``.
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
    """(RecordType, RecordName) per interview id, for the questionnaire clips in one response.

    A questionnaire recording is named after the artisan it is WITH, and that name is two hops from
    the media row — through the interview, through its artisan links. Resolved in one batched query
    for the whole page instead of widening ``INCLUDE``: nesting the interview and its artisans into
    every media row would triple the size of a hundred-row list response, on a screen that only ever
    wanted the name.
    """
    ids = sorted({r.questionnaireInterviewId for r in rows if r.questionnaireInterviewId})
    if not ids:
        return {}
    interviews = await db.questionnaireinterview.find_many(
        where={"id": {"in": ids}}, include={"artisans": {"include": {"artisan": True}}}
    )
    return {i.id: interview_record(i) for i in interviews}


async def _public(rows: Any, viewer: Any = None, *, media_urls: Any = _UNSET_URLS) -> Any:
    """``public_encode`` plus the derived display name for every media row it carries.

    ``originalFilename`` stays exactly as uploaded — it is the only handle a researcher has for
    matching a file against their own copy — and ``displayFilename`` is added beside it: the same
    ``{RecordType}-{RecordName}-{Descriptor}-{stamp}`` name the data browser and the export use, so
    a clip reads the same in the app list as it does in a downloaded zip. Nothing in storage moves.

    THE ``url`` IS NOT ALWAYS PRESENT, and this is the one file where that matters most. Reading the
    repository is open to every signed-in account, but a ``MediaFile.url`` is a fetchable object URL —
    it IS the file — so it travels only to callers who may take that uploader's data. Every row still
    travels: name, type, caption, duration, parent record, uploader. ``media_url_owners`` resolves
    the grant set (one query, and only below professor) so a grantee who may download a researcher's
    data can also play their recordings. Callers that pass no ``viewer`` get the cheap safe default and
    no URLs at all, which is correct for every internal use of this helper.

    ``transcriptText`` TRAVELS UNDER THE SAME RULE, and this docstring used to promise the opposite —
    it listed "transcript" among the fields that always travel, and ``GET /media`` and
    ``GET /media/{id}`` duly served the verbatim text of every artisan interview in the repository to
    a CROWDSOURCE_VOLUNTEER, which made the per-clip transcript gate on the design-workshop surface
    decoration. The predicate now lives in exactly one place, ``records._MEDIA_TAKEABLE_KEYS``; do not
    re-open it here. The surface a co-designer needs is
    ``GET /design-workshops/{id}/transcripts``, which uses the wider ``owned_or_granted_where``.

    ``media_urls`` is for the one caller that has an uploader ID but no user object —
    ``_finish_pending_media``, which is handing somebody back the file they have just uploaded.
    """
    many = isinstance(rows, list)
    items = list(rows) if many else [rows]
    labels = await _interview_labels([r for r in items if r is not None])
    if media_urls is _UNSET_URLS:
        media_urls = await media_url_owners(viewer) if viewer is not None else set()
    encoded = public_encode(rows, viewer, media_urls=media_urls)
    for row, out in zip(items, encoded if many else [encoded]):
        if row is None or not isinstance(out, dict):
            continue
        kind, name = labels.get(row.questionnaireInterviewId or "", ("", ""))
        out["displayFilename"] = display_filename(
            row, record_type=kind or None, record_name=name or None, fallback=row.id
        )
    return encoded


@router.post("/presign", response_model=PresignResponse)
async def presign_media_upload(
    payload: PresignRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = get_settings()
    object_key = make_object_key(current_user.id, payload.filename)
    return {
        "uploadUrl": presign_put_url(object_key, payload.mimeType),
        "method": "PUT",
        "objectKey": object_key,
        "bucket": settings.aws_s3_bucket,
        "headers": {"Content-Type": payload.mimeType},
        "publicUrl": public_url_for_key(object_key),
    }


def _assert_owns_object(object_key: str, current_user: Any) -> None:
    """Multipart object keys live under media/<user_id>/ — refuse to touch another user's upload."""
    if not object_key.startswith(f"media/{current_user.id}/"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only manage your own uploads")


@router.post("/multipart/create", response_model=MultipartCreateResponse)
async def create_multipart(
    payload: MultipartCreateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Start a multipart (chunked) upload for a large file. The client uploads each part straight to
    S3 via the presigned URLs from /multipart/presign-parts, then calls /multipart/complete; S3
    stitches the parts into a single object."""
    settings = get_settings()
    object_key = make_object_key(current_user.id, payload.filename)
    upload_id = await asyncio.to_thread(create_multipart_upload, object_key, payload.mimeType)
    part_count = max(1, math.ceil(payload.sizeBytes / MULTIPART_PART_SIZE))
    return {
        "objectKey": object_key,
        "uploadId": upload_id,
        "bucket": settings.aws_s3_bucket,
        "partSize": MULTIPART_PART_SIZE,
        "partCount": part_count,
        "publicUrl": public_url_for_key(object_key),
    }


@router.post("/multipart/presign-parts", response_model=MultipartPresignPartsResponse)
async def presign_multipart_parts(
    payload: MultipartPresignPartsRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _assert_owns_object(payload.objectKey, current_user)
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
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No parts to complete")
    await asyncio.to_thread(complete_multipart_upload, payload.objectKey, payload.uploadId, parts)
    return {
        "objectKey": payload.objectKey,
        "bucket": settings.aws_s3_bucket,
        "publicUrl": public_url_for_key(payload.objectKey),
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
    """Transcribe a clip that is not a media row: bytes in, words out, nothing stored anywhere.

    **THE THIRD DOOR ONTO THE SAME PROVIDER CHAIN, AND IT WAS THE WIDEST OF THE THREE.**
    ``DesignWorkshop.dictationConsent`` — the artisan's recorded answer to "may recordings from this
    workshop be sent out to be written down" — is consulted in exactly one place in this repository:
    the gate on ``POST /design-workshops/{workshop_id}/dictate``. Its id-less sibling was retired to a
    410 precisely because a second URL onto ``ai.transcribe_audio_bytes`` that could consult no consent
    made the gate ornamental. This route is a third, it hands the same bytes to the same chain, and
    until this change it was ``get_current_user`` — EVERY signed-in account, down to
    ``CROWDSOURCE_VOLUNTEER`` at rank 10.

    Measured rather than assumed, by driving the mounted router with ``db`` replaced by a tripwire: a
    volunteer's POST here ran the whole handler into ``transcribe_audio_bytes`` and stopped only
    because the machine it was driven on has no provider key configured. On a deployment that has one,
    that is an artisan's recorded voice on its way to ElevenLabs, Deepgram or OpenAI — sent by an
    account that cannot create a single record — with no consent consulted, no daily allowance
    consumed, and no row left behind to say it happened.

    **THE FAILURE IN ONE SENTENCE:** a designer refused with the consent 409 on the gated dictation
    route could re-post the identical clip to this address and read the words back.

    ``require_admin``, WHICH IS THE SET ITS OWN TWIN ALREADY HAS. ``POST /{media_id}/transcribe-now``
    below is this same call against a STORED clip — same provider chain, same transcription mode — and
    has always been admin-only. Two spellings of one capability with different permissions is the
    shape of defect the analyse-measurement route above was just narrowed for, in the same file and
    for the same reason: each call spends this deployment's provider credit on a caller-supplied file.

    **WHAT THIS DOES NOT DO, SAID PLAINLY.** It does not make the route consent-aware, and no gate here
    could: the request carries no workshop, so there is no consent column to read. An admin is inside
    ``DESIGN_WORKSHOP_ROLES``, so an admin can record a GRANTED decision on any workshop themselves and
    can already transcribe any stored clip through the route below — the consent gate has never been a
    refusal an admin could meet. What this removes is the four ranks for whom the 409 IS a refusal,
    ``DESIGNER`` above all, plus the three tiers below researcher for whom this was simply a free
    transcription service.

    THE BODY SIZE IS UNMEASURED AND DELIBERATELY UNCAPPED. ``transcribe_audio`` reads the whole upload
    into memory before the provider round trip and nothing bounds it, where dictation caps at 6 MB and
    the grid-measurement route at 8 MB — each with its argument written above it. No ceiling is
    invented here because nothing in this repository sends anything but ``scripts/live-smoke.ps1``'s
    twelve-byte clip, so any number would be a guess about a caller nobody has measured; the admin gate
    is what makes an unbounded read an operator's own doing rather than a stranger's. Naming it keeps it
    a decision.

    THE CALLERS, MEASURED: ``scripts/live-smoke.ps1`` signs in as MASTER_ADMIN and posts here, so it is
    unaffected. ``transcribeMediaFile`` in ``frontend/lib/media.ts`` is exported and has no call site,
    and no Android declaration for this path exists — the app's only transcription call is
    ``media/{id}/transcribe-now``.
    """
    return await transcribe_audio(file, get_settings())


# A grid-sheet photograph is one object on a sheet of squared paper, taken from a phone at arm's
# length. The ceiling is here for the reason ``identity_ocr``'s is: the bytes are base64-encoded into a
# JSON request body, which inflates them by a third, and the whole string is held in memory for the
# length of the provider round trip — so an accidental 40 MB upload is a 55 MB string on a 1 GiB box,
# and a handful of them at once is the box. 8 MB is a generous phone photograph and refuses a video
# frame-grab or a scanned page that was never a grid sheet.
MEASUREMENT_MAX_BYTES = 8 * 1024 * 1024


@router.post("/analyze-measurement")
async def analyze_media_measurement(
    file: UploadFile = File(...),
    dimension: str | None = Query(default=None),
    _: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    """Estimate one dimension of a craft object photographed on a 1-inch grid sheet.

    THE RESPONSE FILLS A FORM FIELD. IT NEVER WRITES ANYTHING — no product row, no tool row, no media
    row, and the image is not retained. Held to ``design_workshops.scan_identity_card``'s discipline
    deliberately, because the two endpoints are the same shape: a model reads a photograph, a person
    checks the answer against the object still in their hand, and the person's save is the only write.

    **WHAT THE RESPONSE NOW CARRIES, AND WHY IT IS NOT OPTIONAL.** ``ProductDocumentation.lengthInches``
    and its siblings are printed as a documented dimension and read by somebody costing a production
    run. Three processes write them — a typed tape reading, ``DwPhotoMeasure``'s arithmetic, and this
    model's estimate — and the row recorded no difference between them, while
    ``records.merge_field_provenance`` stamped the estimate with the name of whoever pressed Save. So
    every answer here states its ``method``, ``provider``, ``modelId``, the model's own
    ``selfReportedConfidence`` (labelled ``confidenceIsCalibrated: false``, because nothing in this
    repository has ever calibrated it), and ``requiresAcceptance: true`` — plus a ``methodMarker`` the
    client sends back with the confirmed value.

    THE OTHER END OF THAT MARKER NOW EXISTS. ``records.merge_field_provenance`` reads it off the save
    body and writes the method BESIDE ``{by, byName, at}``, so an accepted reading is stored as *a
    vision model estimated this, and this person accepted it into the record at that moment* rather
    than as a measurement they took — and ``record_fields.dims_with_method`` prints that on the record
    sheet, in the workbook and in the CSV downloads. What still has to be built is the gesture in the
    middle: a client that shows the number as a proposal, writes nothing into form state until
    somebody presses a button, and sends the marker back with the value.
    ``services/measurement_provenance`` holds the argument, the exact marker shape and the call sites.
    Nothing on this route depends on that having landed: a save carrying no marker is recorded as
    UNRECORDED, which is honest, is distinguishable, and is never the false human claim.

    THE FOUR REFUSALS ARE THE IDENTITY ENDPOINT'S, and the 503 is the one worth arguing. An unconfigured
    provider used to answer 200 with ``available: false``, which a client cannot tell from "the grid was
    unreadable" — so a researcher re-photographs an object in better light for ever while the real
    answer is that nobody has set GEMINI_API_KEY. The status code makes it the operator's problem, and
    the sentence names the setting.

    Researcher and above, matching ``require_record_creator`` on ``POST /products`` exactly rather than
    copying the identity endpoint's designer-only set. That set would be wrong here: the grid control
    lives on the researcher-facing product and tool forms, so a designer gate would take a working
    capability away from the accounts this feature is for. Researcher is nonetheless a real narrowing —
    this used to be open to every signed-in account down to a crowdsource volunteer, none of whom can
    create a product or edit anybody's dimension field, and each call spends this deployment's provider
    credit on a caller-supplied image.
    """
    try:
        requested = normalize_dimension(dimension)
    except UnknownDimension as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="No image was uploaded. Photograph the object on the grid sheet and try again.",
        )
    if len(content) > MEASUREMENT_MAX_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"The image is larger than the {MEASUREMENT_MAX_BYTES // (1024 * 1024)} MB limit. "
                "Photograph the object alone on the grid sheet rather than the whole workbench."
            ),
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
        # 503 and not a 200, for the reason in the docstring. The sentence comes from the service,
        # which is the layer that knows which key is missing.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(result.get("message") or "Grid measurement is not configured on this server."),
        )
    # ``content`` goes out of scope here. A FAILED read still returns 200: the provider was reachable
    # and answered, the message says so, and both clients already route that to "enter it manually" —
    # which is the correct next move and the same one a 502 would produce, without telling a researcher
    # that the server is broken when it is working exactly as designed.
    return result


async def _finish_pending_media(existing: Any, processing_requests: list[str] | None, user_id: str, settings: Any) -> dict[str, Any]:
    """Return an already-created media row for a retried /complete, enqueuing its processing jobs once
    if the original request died before it could (so a retry never drops transcription)."""
    if processing_requests and not (existing.processingJobs or []):
        await enqueue_media_processing_jobs(existing, processing_requests, user_id, settings)
        existing = await db.mediafile.find_unique(where={"id": existing.id}, include=INCLUDE)
    # The uploader's own file, handed straight back to them: this function has their id rather than
    # their user row, so the entitlement is stated directly instead of resolved from a viewer.
    return await _public(existing, media_urls={user_id})


#: Processing requests ``POST /media/complete`` will enqueue. TRANSCRIPTION and nothing else.
#:
#: MEASUREMENT IS DELIBERATELY ABSENT, AND THIS IS THE SHARPEST FORM OF THE PROVENANCE DEFECT.
#: A queued measurement is a background worker reading a photograph with no person in the loop at any
#: point and no client involved to show the answer to anybody. Until this refusal existed,
#: ``media_queue._measurement_update_data`` wrote the model's ``lengthInches`` /
#: ``breadthInches`` straight onto ``ProductDocumentation`` / ``ToolDocumentation`` whenever the
#: column was empty — a vision model's estimate landing in a costed, printed dimension field that
#: nobody had ever seen.
#:
#: NO SHIPPED CLIENT ASKS FOR IT. The web's ``resolveProcessing`` adds only TRANSCRIPTION and no
#: component passes ``processingRequests``; Android's ``uploadMeasurement`` sets it but has no call
#: site anywhere in the tree. So the path was dead from both clients and live in the API — any
#: authenticated caller of this route could drive it.
#:
#: BOTH HALVES ARE NOW SHUT, AND THIS ONE IS STILL THE ONE THAT MATTERS. The queue's writer has since
#: stopped writing the two columns and keeps only ``measurementAnalysis`` /
#: ``measurementAnalysisStatus`` (its docstring carries the argument and the alternative it refused),
#: so a legacy job row queued before this refusal landed, or one inserted by an operator driving the
#: database directly, can no longer fill a dimension either. This refusal stays because it is the
#: door: a request that never becomes a job cannot depend on the worker being careful.
#:
#: The refusal is a 422 rather than a silent drop. Dropping it would return 201 to a caller that
#: believes an analysis is coming and will wait for a ``measurementAnalysisStatus`` that never moves.
ENQUEUEABLE_PROCESSING_REQUESTS = frozenset({"TRANSCRIPTION"})


def _assert_enqueueable(processing_requests: list[str] | None) -> None:
    """Refuse a processing request this route will not queue. See ENQUEUEABLE_PROCESSING_REQUESTS."""
    unsupported = sorted(
        {
            token
            for raw in processing_requests or []
            if (token := str(raw).strip().upper())
            and token not in ENQUEUEABLE_PROCESSING_REQUESTS
        }
    )
    if not unsupported:
        return
    raise HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
        detail=(
            f"This upload asked for {', '.join(unsupported)}, which this server does not queue. "
            "Upload the photograph on its own, then read the measurement with "
            "POST /media/analyze-measurement and save the value a person has confirmed — a dimension "
            "no human ever saw must not be written onto a record that is used for costing."
        ),
    )


@router.post("/complete", status_code=status.HTTP_201_CREATED)
async def complete_media_upload(
    payload: MediaCompleteRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Register an uploaded object as a media row, and queue the processing it asked for.

    **TRANSCRIPTION IS NOW GATED ON CONSENT, AND THE GATE IS NOT IN THIS FUNCTION.** It is in
    ``media_queue.enqueue_media_processing_jobs``, the single choke point every TRANSCRIPTION job in
    this system is created by — see its docstring for the argument, which is that a check written here
    and a check written in ``workshop_transcripts`` would be two places to forget.

    WHAT THAT MEANS FOR THIS ROUTE'S CONTRACT, said plainly because it is a behaviour change: a design
    workshop's AUDIO upload — identified by the ``linkedRecordType="designWorkshop"`` tag both clients
    send, with the workshop id in ``linkedRecordId`` — is stored exactly as before and **is not queued
    for transcription unless that workshop's artisan has agreed**. The response still carries the row;
    what it will not carry is a ``processingJobs`` entry, and ``transcriptStatus`` comes back FAILED with
    the gate's sentence in ``transcriptError`` rather than QUEUED. Nothing else changes: an upload with
    no design-workshop tag — an interview clip, a product photograph, a misc-media file — behaves
    identically to before, because a consent about a workshop says nothing about it.

    THE STATUS CODE STAYS 201 AND THE REFUSAL IS NOT AN ERROR HERE. The upload succeeded; the file is
    stored and attached and is exactly what the designer captured. What did not happen is a send to a
    third party, and failing the request would tell a phone at the end of a fortnight's sync that its
    recording was lost — which is false, and which would make the app retry the upload for ever.
    """
    settings = get_settings()
    processing_requests = payload.processingRequests
    # Before the row is created, so a refused request leaves nothing behind and the caller can retry
    # the same upload without the flag. This covers the retry branch too: ``_finish_pending_media`` is
    # only ever reached from below this line.
    _assert_enqueueable(processing_requests)

    # Idempotency. The client retries /complete when the first call is slow or times out (504
    # resilience), but that first call may already have created the row. ``objectKey`` is unique and
    # embeds the uploader id + a per-upload uuid, so a row already present for this key IS this same
    # upload — return it instead of failing with a 500 UniqueViolationError (the bug users hit).
    # The replay is only honoured for the row's own uploader; anyone else gets a 403.
    existing = await db.mediafile.find_unique(where={"objectKey": payload.objectKey}, include=INCLUDE)
    if existing is not None:
        if getattr(existing, "uploadedById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="This upload belongs to another user",
            )
        return await _finish_pending_media(existing, processing_requests, current_user.id, settings)

    # New row: the staged object must live under the caller's own media/<user_id>/ prefix, so a user
    # cannot register a media row over another user's staged object.
    _assert_owns_object(payload.objectKey, current_user)

    data = clean_data(payload.model_dump(exclude={"processingRequests"}))
    data = await attach_location(data)
    data["bucket"] = data.get("bucket") or settings.aws_s3_bucket
    # THE SERVER DERIVES THE URL. IT NEVER TAKES THE CALLER'S.
    #
    # This used to be `data.get("url") or public_url_for_key(...)`, so the payload's value won. Any
    # signed-in account could therefore complete a one-byte upload with `url:
    # "https://attacker.example/portal-login"` and plant a row that `GET /data/media/{id}/download`
    # 307-redirects to (`api/routes/data_browser.py`) and the media screen renders in an `<img>` /
    # `<iframe>` — a phishing hop that begins on the portal's own domain, and an off-site fetch that
    # hands the attacker the viewer's IP every time the lightbox opens.
    #
    # The field is KEPT on `MediaCompleteRequest` and simply ignored: `APIModel` sets
    # `extra="forbid"`, and both clients send the key today, so dropping it would 422 every upload
    # from every installed build — including phones in the field that cannot be updated.
    # `_assert_owns_object` above has already forced the key under `media/<user_id>/`, so the
    # derived URL is the only one the server can vouch for. It is None when the deployment has no
    # public base URL configured, which the download path already handles by streaming the bytes.
    data["url"] = public_url_for_key(data["objectKey"])
    data["uploadedById"] = current_user.id
    relation_data = media_relation_data(data.get("linkedRecordType"), data.get("linkedRecordId"))
    parent: Any = None
    if relation_data:
        # The link maps to a typed FK — verify the target exists (mirrors /relink) so a bad id is a
        # clean 404 instead of a ForeignKeyViolation 500 at create time. The row is KEPT rather than
        # discarded: its ``workshopId`` is what this file inherits (see below).
        delegate = _relink_delegate(str(data["linkedRecordType"]).lower())
        parent = await delegate.find_unique(where={"id": data["linkedRecordId"]}) if delegate else None
        if parent is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Linked record not found")
    data.update(relation_data)
    # THE PARENTS WITH NO TYPED FOREIGN KEY still have a workshop to inherit, and this is where they get
    # it. ``media_relation_data`` only knows the six link types that have a column on MediaFile, but the
    # app sends more than six: ProcessForm uploads with ``process`` and ``processstep``, and the misc-media
    # picker offers ``process`` and ``media`` as well. Those land carrying only the string tags — so
    # without this lookup a whole record type's attachments keep arriving with a NULL workshop, which is
    # the exact condition this change exists to end.
    #
    # It is a SEPARATE, non-fatal read on purpose. The 404 above stays where it is: for a link type with
    # no column, a bad id has never been an error, and turning it into one now would break a client the
    # moment it referenced something deleted.
    if parent is None:
        parent = await _tagged_parent(data.get("linkedRecordType"), data.get("linkedRecordId"))

    # A media upload attached to a workshop IS a workshop submission: MediaFile carries both
    # workshopId and status, and the review routes approve media exactly like any other record — so
    # it has to pass the gate products.py applies, or an unassigned user can push files into a
    # restricted workshop and a recording made long after the workshop ended is never flagged for
    # admin approval. The effective workshop is whatever `relation_data` resolved (the /complete
    # payload has no workshopId of its own; a "workshop"-tagged upload sets it from
    # linkedRecordId), which is why this runs after the update above and before the metadata is
    # Json-wrapped — stamp_workshop_submission writes a plain dict into extraMetadata.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    # The column already defaults to PENDING and the payload carries no status, so seed the key the
    # pin acts on: a late upload must be pinned to PENDING even if a client ever starts sending one.
    data.setdefault("status", "PENDING")
    pin_pending_if_late(data, current_user, check=check)

    inherit_parent_workshop(data, parent)

    jsonify_metadata(data)
    try:
        created = await db.mediafile.create(data=data, include=INCLUDE)
    except UniqueViolationError:
        # Lost a race with a concurrent retry that inserted the row first — return that one.
        racer = await db.mediafile.find_unique(where={"objectKey": payload.objectKey}, include=INCLUDE)
        if racer is not None:
            return await _finish_pending_media(racer, processing_requests, current_user.id, settings)
        raise
    await enqueue_media_processing_jobs(created, processing_requests, current_user.id, settings)
    created = await db.mediafile.find_unique(where={"id": created.id}, include=INCLUDE)
    return await _public(created, current_user)


@router.get("")
async def list_media(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    mediaType: str | None = None,
    linkedRecordType: str | None = None,
    linkedRecordId: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # WHOSE UPLOADS — the media equivalent of ``createdBy`` on the record lists. Same reason: with
    # reading open, "my uploads" is a question, not a by-product of the row filter.
    uploadedBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # Visibility is AND-composed so the search OR (assigned below) can never overwrite it.
    vis = await viewable_where(current_user, owner_field="uploadedById")
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [{"originalFilename": contains(search)}, {"caption": contains(search)}, {"mimeType": contains(search)}]
    if mediaType:
        where["mediaType"] = enum_filter_or_422(mediaType, MEDIA_TYPES, field="mediaType")
    if linkedRecordType:
        where["linkedRecordType"] = linkedRecordType
    if linkedRecordId:
        where["linkedRecordId"] = linkedRecordId
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if uploadedBy:
        where["uploadedById"] = uploadedBy
    add_date_range(where, "createdAt", dateFrom, dateTo)
    total = await db.mediafile.count(where=where)
    items = await db.mediafile.find_many(
        where=where,
        include=INCLUDE,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(await _public(items, current_user), total, page, page_size)


# linkedRecordType -> the typed foreign-key column that should point at the parent record. When a
# parent record is deleted its media FK is SET NULL (so the file and its S3 object are NOT lost), but
# these string tag columns survive, leaving the row an "orphan": still tagged with a type/id, no live
# parent. We surface and re-link those instead of letting the recordings disappear from every screen.
ORPHAN_FK_FIELDS = {
    "artisan": "artisanId",
    "craft": "craftId",
    "workshop": "workshopId",
    "product": "productId",
    "tool": "toolId",
    "questionnaire": "questionnaireInterviewId",
    "questionnaireinterview": "questionnaireInterviewId",
}

# THE OTHER HALF OF THE ORPHAN QUESTION, AND ITS ABSENCE MADE THE RECOVERY SCREEN LIE.
# ``ORPHAN_FK_FIELDS`` asks "tagged with a type and an id, but the typed FK is NULL". That question
# cannot be asked at all of a link that has no typed FK: a media row attached to a Process, a
# ProcessStep, a DesignWorkshop or another media file carries ONLY ``linkedRecordType`` /
# ``linkedRecordId`` (processes.py says so out loud: "Media is linked purely through
# linkedRecordType/linkedRecordId … so no MediaFile foreign keys are needed"). There is no ``fk`` to
# be null, so no condition in ``list_orphan_media`` could ever match one, and
# ``POST /media/{id}/relink`` answered 400 "Unsupported record type for re-linking" — the recovery
# feature reported the repository as clean while refusing to recover the very rows it was built for.
#
# THE ORDINARY TRIGGER IS AN EDIT, NOT A DELETE. ``processes._sync_steps`` hard-deletes every
# ProcessStep the form did not re-send, so a researcher who opens a process, removes one duplicated
# step and saves has just orphaned that step's photographs — no delete of anything else required. The
# same shape follows a Process delete, a ProductDocumentation delete cascading into both
# (schema.prisma:1210, :1232) and a DesignWorkshop delete against the tag both clients write.
#
# BOTH SPELLINGS OF THE WORKSHOP TAG ARE HERE ON PURPOSE. ``dictation_consent.MEDIA_TAG`` is the
# camelCase ``designWorkshop`` the clients send, but the relink route lowercases whatever it is given
# before storing it, so rows written by a recovery carry ``designworkshop``. Matching one and not the
# other would leave half the orphans invisible again, which is the defect this constant exists to end.
ORPHAN_TAG_TYPES: dict[str, str] = {
    "process": "process",
    "processstep": "processstep",
    "designWorkshop": "designworkshop",
    "designworkshop": "designworkshop",
    # A misc file linked to another misc file. Both spellings the pickers write, matching
    # ``_relink_delegate`` and ``_tagged_parent``, which already treat the two as one type.
    "media": "media",
    "misc": "media",
}


def _orphan_tag_delegate(kind: str) -> Any:
    """The delegate whose live ids decide whether a TAG-ONLY link still points at something."""
    return {
        "process": db.process,
        "processstep": db.processstep,
        "designworkshop": db.designworkshop,
        "media": db.mediafile,
    }.get(kind)


async def _tag_only_orphans() -> list[Any]:
    """Media whose only link is a string tag naming a record that no longer exists.

    ONE QUERY PER TAGGED TYPE, NOT ONE PER ROW. The tagged rows are read once, grouped by the type
    they name, and each group is answered with a single ``id: {"in": [...]}`` read against the live
    table; whatever is missing from the answer is an orphan. The naive shape — a ``find_unique`` per
    media row — is the sort of loop that turns an admin screen into a minute-long request the first
    time a deployment accumulates a few thousand tagged uploads.

    THE LOOKUP IS BOUNDED BY THE TAGS, NOT BY THE MEDIA TABLE. Only the five ``ORPHAN_TAG_TYPES``
    spellings are read, so a repository whose media is overwhelmingly FK-linked pays almost nothing
    here; the FK half of the question is still answered by the cheap ``fk: None`` predicates, which is
    why this runs alongside them rather than replacing them.

    THE RELATIONS ARE LOADED LAST, ON THE ORPHANS ONLY. The survey read deliberately omits
    ``INCLUDE``: it has to visit every tagged row to find the few whose parent is gone, and joining
    seven relations onto every process photograph in the repository to discard almost all of them is
    the expensive way to ask a cheap question. The extra query costs one round trip and is skipped
    entirely — the common, healthy case — when nothing turned out to be an orphan.
    """
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
    for kind, ids in by_kind.items():
        delegate = _orphan_tag_delegate(kind)
        if delegate is None:  # pragma: no cover - the two dicts are written together
            live[kind] = ids
            continue
        rows = await delegate.find_many(where={"id": {"in": sorted(ids)}})
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
    """The record a STRING-TAGGED upload hangs off, for workshop inheritance only. Never raises.

    ``media_relation_data`` covers the six link types that have a typed column on MediaFile. The clients
    send more: ``process`` and ``processstep`` from the process form, ``media`` from the misc-media
    picker. Those rows carry only ``linkedRecordType``/``linkedRecordId``, so the only way to read their
    parent's workshop is to look it up by the tag.

    A ProcessStep has no workshop of its own — it is a step inside a Process — so its PARENT for this
    purpose is that Process, which is the same one hop the process form itself makes.

    Every failure answers None. This runs only to fill in a blank, so a missing row, a tag this server
    has never heard of, or a database hiccup must all leave the upload alone rather than fail it.
    """
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
            # The five types that DO have a typed column are handled by the caller's own lookup; they
            # are repeated here only so a future caller of this helper is not surprised by a gap.
            "artisan": db.artisan,
            "craft": db.craft,
            "product": db.productdocumentation,
            "tool": db.tooldocumentation,
            "questionnaire": db.questionnaireinterview,
            "questionnaireinterview": db.questionnaireinterview,
            # A misc file linked to another misc file: the parent is itself a MediaFile, and its own
            # workshop (inherited or chosen) is the honest answer for the child.
            "media": db.mediafile,
            "misc": db.mediafile,
        }.get(normalized)
        return await delegate.find_unique(where={"id": record_id}) if delegate else None
    except Exception:  # noqa: BLE001 — see the docstring: this may only ever fill in a blank.
        return None


def inherit_parent_workshop(
    data: dict[str, Any], parent: Any, *, replace: bool = False
) -> dict[str, Any]:
    """Give a media row the workshop its parent record already names. Mutates and returns ``data``.

    WHY MEDIA NEEDS THIS AND THE RECORD TYPES DO NOT. Every other type has a workshop PICKER on its
    form, so a researcher answers the question once and the answer is on the row. Media has no such
    form — a clip is captured inside an interview, a photo inside a product — and ``/media/complete``
    carries no ``workshopId`` of its own. ``media_relation_data`` sets ``workshopId`` for exactly one
    upload shape, the one tagged ``workshop``, where the workshop IS the parent. Every other upload
    landed with a NULL workshop, which is how 924 of 925 files in the live corpus came to be invisible
    under every workshop scope while their parent records were perfectly well scoped.

    ON CREATE IT ONLY FILLS A BLANK. An explicit ``workshopId`` in the payload, or the one
    ``media_relation_data`` derived from a ``workshop``-tagged upload, is the caller's answer and wins.
    ``replace=True`` is for RE-LINKING, where the parent is not filling a gap but changing: the file has
    been moved under a different record, the parent is the authority on which workshop a file belongs
    to, so a workshop inherited from the record it used to hang off is stale and must not survive the
    move. It still never writes a NULL over a real id — a new parent with no workshop of its own leaves
    the existing value alone, because "I do not know" is not an answer that should erase one.

    IT RUNS AFTER THE SUBMISSION GATE, and that is deliberate rather than an ordering accident.
    ``enforce_workshop_submission`` asks "may THIS user submit to THAT workshop, and is the window
    still open" — a question about a submission somebody is making. Inheriting a parent's workshop is
    not a submission: the parent already passed that gate when it was created, and the clip is being
    filed under it, not offered to the workshop afresh. Running the gate on the inherited id would
    block a researcher from adding a photo to a colleague's product at a workshop they were not
    assigned to, and would flag every clip of an interview that was itself a late submission as a
    second late submission. Both are the parent's status to carry, not the file's.

    A parent with no workshop of its own contributes nothing, so nothing is guessed here: closing that
    gap is ``services/workshop_inference``'s job, which has evidence this function does not.
    """
    if data.get("workshopId") and not replace:
        return data
    inherited = getattr(parent, "workshopId", None) if parent is not None else None
    if inherited:
        data["workshopId"] = inherited
    return data


def _relink_delegate(record_type: str) -> Any:
    """The Prisma delegate for a re-link target record type (or None if unsupported)."""
    return {
        "artisan": db.artisan,
        "craft": db.craft,
        "workshop": db.workshop,
        "product": db.productdocumentation,
        "tool": db.tooldocumentation,
        "questionnaire": db.questionnaireinterview,
        "questionnaireinterview": db.questionnaireinterview,
        # PROCESS AND PROCESS STEP, WHICH THIS MAP REFUSED UNTIL 2026-08-15. They were absent because
        # they have no typed FK on MediaFile, and their absence turned the ordinary consequence of
        # editing a process — ``_sync_steps`` hard-deleting a dropped step — into an unrecoverable
        # loss: the step's photographs were invisible to ``/media/orphans`` (now fixed above) and a
        # 400 to this route even when an admin found their ids by hand. Having no FK is precisely why
        # re-linking them is SAFE and cheap: ``media_relation_data`` contributes nothing for these
        # types, so only the tag pair is rewritten, which is exactly how the client attached them in
        # the first place. Neither model carries a ``workshopId``, so ``inherit_parent_workshop``
        # finds nothing to inherit and — because it never writes a NULL over a real id — leaves the
        # workshop the file already had. That is the right answer here: a photograph moved from a
        # dead step to a live one has not changed workshops.
        "process": db.process,
        "processstep": db.processstep,
        # Miscellaneous media can be linked to another miscellaneous media item (no typed FK; the
        # generic linkedRecordType/linkedRecordId tags carry the association).
        "media": db.mediafile,
        "misc": db.mediafile,
    }.get(record_type)


@router.get("/orphans")
async def list_orphan_media(current_user: Any = Depends(require_admin)) -> list[dict[str, Any]]:
    """Admin-only recovery list: media still tagged to a record type/id whose parent no longer exists
    (the typed FK was nulled when the parent was deleted). The file is intact in object storage; this
    lets an admin see it again — and re-link it to a live record via ``/media/{id}/relink``.

    The caller is bound rather than discarded so the URLs come back: this is a recovery screen, an
    orphan's only remaining handle IS its file, and ``_public`` withholds the URL from a caller it was
    not given. An admin outranks the entitlement, so nothing here is widened — it is simply not
    accidentally narrowed.

    TWO QUESTIONS, BECAUSE THERE ARE TWO KINDS OF LINK. A typed FK that has been SET NULL is found by
    the ``fk: None`` predicates below. A tag-only link — process, process step, design workshop, misc
    — has no FK to be null and is found by :func:`_tag_only_orphans`, which resolves the tag. Asking
    only the first question is what let a deleted process step's photographs sit in the repository
    while this screen reported it clean; see ``ORPHAN_TAG_TYPES``."""
    conditions = [
        {"linkedRecordType": rec_type, "linkedRecordId": {"not": None}, fk: None}
        for rec_type, fk in ORPHAN_FK_FIELDS.items()
    ]
    rows = await db.mediafile.find_many(
        where={"OR": conditions},
        include=INCLUDE,
        order={"createdAt": "desc"},
    )
    # De-duplicated by id even though the two questions are disjoint by construction (a type is
    # either in ORPHAN_FK_FIELDS or in ORPHAN_TAG_TYPES, never both): if a future link type is ever
    # added to both dicts, the recovery screen must show it once, not twice.
    seen = {row.id for row in rows}
    combined = [*rows, *(row for row in await _tag_only_orphans() if row.id not in seen)]
    combined.sort(key=lambda row: row.createdAt, reverse=True)
    return await _public(combined, current_user)


@router.post("/{media_id}/relink")
async def relink_media(
    media_id: str,
    payload: MediaRelinkRequest,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Re-attach an orphaned (or mis-linked) media file to an existing record. Validates the target
    record exists, then sets both the string tag columns and the typed foreign key so it reappears
    under that record everywhere."""
    media = await require_record(db.mediafile, media_id)
    rec_type = payload.linkedRecordType.lower()
    delegate = _relink_delegate(rec_type)
    if delegate is None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported record type for re-linking")
    target = await delegate.find_unique(where={"id": payload.linkedRecordId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Target record not found")
    data: dict[str, Any] = {
        "linkedRecordType": rec_type,
        "linkedRecordId": payload.linkedRecordId,
    }
    data.update(media_relation_data(rec_type, payload.linkedRecordId))
    # Re-linking moves the file under a different record, so the workshop moves with it — otherwise an
    # orphan recovered onto an interview at a workshop stays invisible under that workshop's scope,
    # which is the condition this recovery screen exists to end, and a file moved BETWEEN workshops
    # keeps counting towards the one it left. ``replace=True`` because the old value was the old
    # parent's answer to a question the new parent now answers; a new parent with no workshop still
    # leaves the existing id alone.
    inherit_parent_workshop(data, target, replace=True)
    updated = await db.mediafile.update(where={"id": media.id}, data=data, include=INCLUDE)
    return await _public(updated, current_user)


@router.post("/{media_id}/refine-transcript")
async def refine_media_transcript(
    media_id: str,
    payload: TranscriptRefineRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Refine this media file's existing transcript into a clean interviewer/interviewee conversation
    (Markdown), optionally translating it to English. On-demand and billable — the client warns the
    user about extra cost before calling, and it is restricted to the uploader or an admin (the same
    rule as replacing the transcript). Returns the refined text; it is not persisted, so each call
    reflects the current transcript and the user stays in control of when the cost is incurred.

    Declared before the ``GET /{media_id}`` catch-all so the ``{media_id}/refine-transcript`` path is
    matched as this route, not swallowed by the single-segment id route.

    **THE FOURTH DOOR ONTO A PAID PROVIDER, AND THE ONE THE CONSENT AUDIT ALMOST MISSED**, because what
    leaves here is text rather than audio and it goes to a different company. It posts an artisan's
    transcribed words to OpenAI's chat model, and until this change it read no consent — so a designer
    refused the recording's transcription by the gate could still take a transcript that already existed
    and have it rewritten and translated abroad. ``_verb_gate`` settled this argument for the five AI
    verbs and the reasoning transfers unchanged: *"a transcript is the artisan's words with the audio
    compressed out of them, so posting one to OpenAI is the same export in a smaller shape, and a gate
    that covered the recording but not its transcript would be a gate with a door beside it."*

    The refusal names OpenAI rather than the transcription service — see
    ``dictation_consent.REFINEMENT``, and see ``Send`` for why a sentence that named the wrong recipient
    would be the same defect in a smaller font.
    """
    media = await require_record(db.mediafile, media_id)
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only refine the transcript of media you uploaded",
        )
    # ``resolve_from_stages`` because the tag alone is not how a recording is known to be workshop
    # material — a clip a stage NAMES but whose upload carried no tag was measured leaving here for
    # OpenAI with HTTP 200, on a workshop whose answer on record is REFUSED, at DESIGNER rank rather
    # than admin. See ``dictation_consent.stage_attached_workshop_ids``.
    verdict = await dictation_consent.transcription_verdict(
        media, send=dictation_consent.REFINEMENT, resolve_from_stages=True
    )
    if not verdict.may_send:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=verdict.refusal)
    transcript = getattr(media, "transcriptText", None)
    return await refine_transcript_text(transcript, payload.translate, get_settings())


@router.post("/{media_id}/transcribe-now")
async def transcribe_media_now_route(
    media_id: str,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    """Admin/master-admin: transcribe this audio file right now, applying the transcription mode set on
    the settings page, and store the result — bypassing the queue and the off-peak window. Returns the
    updated media row (its ``transcriptStatus``/``transcriptText`` reflect the outcome, including a
    FAILED/UNAVAILABLE status when the AI key is missing or the call failed). Declared before
    ``GET /{media_id}`` so the two-segment path resolves here."""
    media = await require_record(db.mediafile, media_id)
    if str(getattr(media, "mediaType", "") or "").upper() != "AUDIO":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Only audio files can be transcribed.",
        )
    try:
        await transcribe_media_now(media, get_settings())
    except SendRefused as exc:
        # A 409 AND NOT A 403, the same choice `POST /design-workshops/{id}/dictate` makes and for its
        # reason: a 403 is about the CALLER, and this admin is entitled to press the button. What is not
        # in a state to permit the send is the WORKSHOP whose artisan has not agreed. The detail is the
        # gate's own sentence, printed verbatim by both clients.
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    updated = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    return await _public(updated, current_user)


@router.post("/{media_id}/transcript")
async def set_media_transcript(
    media_id: str,
    payload: TranscriptUpdateRequest,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """Replace a media file's stored transcript with approved text (e.g. an AI-refined transcript the
    user accepted). Allowed for the uploader or an admin, mirroring the media-delete permission. Marks
    the transcript COMPLETED and clears any prior error. Declared before ``GET /{media_id}`` so the
    two-segment path resolves here."""
    media = await require_record(db.mediafile, media_id)
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only edit the transcript of media you uploaded",
        )
    updated = await db.mediafile.update(
        where={"id": media.id},
        data={"transcriptText": payload.text, "transcriptStatus": "COMPLETED", "transcriptError": None},
        include=INCLUDE,
    )
    return await _public(updated, current_user)


@router.get("/jobs")
async def list_media_processing_jobs(
    current_user: Any = Depends(get_current_user),
    statusFilter: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {} if is_admin(current_user) else {"requestedById": current_user.id}
    if statusFilter:
        # MediaProcessingJob.status, NOT MediaFile.status — see MEDIA_PROCESSING_JOB_STATUSES above
        # for why passing RECORD_STATUSES here (as this line did) 422'd every request the panel makes.
        # ``field="statusFilter"`` names the query parameter the client actually sent, matching the
        # mediaType filter in ``list_media``; the default "status" would name a key that appears
        # nowhere in the request the caller can see.
        where["status"] = enum_filter_or_422(statusFilter, MEDIA_PROCESSING_JOB_STATUSES, field="statusFilter")
    total = await db.mediaprocessingjob.count(where=where)
    jobs = await db.mediaprocessingjob.find_many(
        where=where,
        include={"mediaFile": True, "requestedBy": True, "product": True, "tool": True},
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
    )
    return page_payload(public_encode(jobs), total, page, page_size)


@router.post("/jobs/process")
async def process_media_processing_jobs(
    limit: int | None = Query(default=None, ge=1, le=25),
    _: Any = Depends(require_admin),
) -> dict[str, int]:
    """The media-jobs panel's "Run queue now" button.

    ``recover=False`` because this runs in the WEB process and the queue drain runs in its own
    systemd unit, so the stale-lock recovery would be judging locks held by a process it cannot see:
    a provider call hung past the 30-minute cutoff would be reset to QUEUED here and immediately
    re-sent while the queue process is still awaiting the first answer. See
    ``media_queue.process_next_media_jobs`` for the full argument; recovery still happens on every
    pass of the queue process itself.
    """
    return await process_next_media_jobs(limit=limit, worker_id="manual-api", recover=False)


@router.post("/jobs/{job_id}/retry")
async def retry_media_processing_job(
    job_id: str,
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    job = await require_record(db.mediaprocessingjob, job_id)
    updated = await db.mediaprocessingjob.update(
        where={"id": job.id},
        data={
            "status": "QUEUED",
            "runAfter": datetime.now(UTC),
            "lockedAt": None,
            "lockedBy": None,
            "completedAt": None,
            "error": None,
        },
        include={"mediaFile": True},
    )
    return public_encode(updated)


@router.delete("/object", status_code=status.HTTP_204_NO_CONTENT)
async def delete_staged_object(objectKey: str, current_user: Any = Depends(get_current_user)) -> None:
    """Delete a staged S3 object that was pre-uploaded but never attached to a saved record.

    Scoped to the caller's own ``media/<user_id>/`` prefix, and refuses to touch any object that is
    already referenced by a MediaFile (those are deleted through the normal media delete route).
    """
    if not objectKey.startswith(f"media/{current_user.id}/"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="You can only delete your own staged uploads")
    existing = await db.mediafile.find_first(where={"objectKey": objectKey})
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Object is attached to a saved media file; delete that record instead",
        )
    await asyncio.to_thread(delete_object, objectKey)


@router.get("/{media_id}")
async def get_media(media_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    media = await db.mediafile.find_unique(where={"id": media_id}, include=INCLUDE)
    media = media or await require_record(db.mediafile, media_id)
    return await _public(media, current_user)


@router.delete("/{media_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_media(media_id: str, current_user: Any = Depends(get_current_user)) -> None:
    """Remove a saved media file and (best-effort) its S3 object.

    Admins may delete any media; everyone else may delete only media they uploaded — so a
    contributor can prune attachments on their own records straight from the edit screen without
    holding full delete rights on the parent record.
    """
    media = await require_record(db.mediafile, media_id)
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only delete media you uploaded",
        )
    object_key = getattr(media, "objectKey", None)
    await db.mediafile.delete(where={"id": media_id})
    # Drop the underlying object too, but only once no other MediaFile still references it, and never
    # let a storage hiccup fail the request — the database row (the user-visible record) is gone.
    if object_key:
        still_referenced = await db.mediafile.find_first(where={"objectKey": object_key})
        if still_referenced is None:
            try:
                await asyncio.to_thread(delete_object, object_key)
            except Exception:  # noqa: BLE001 - best-effort storage cleanup
                pass
