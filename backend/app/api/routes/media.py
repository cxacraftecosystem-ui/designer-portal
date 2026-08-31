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
from app.services.concurrency import gather_reads
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
MEDIA_PROCESSING_JOB_STATUSES = frozenset(
    {"QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED"}
)

router = APIRouter(prefix="/media", tags=["media"])

# "the caller did not say whose media bytes may travel". Distinct from None, which MEANS "all of
# them" — see ``records.public_encode``.
#
# IT NOW GOVERNS BOTH HALVES OF THAT ANSWER. ``records.media_url_scope`` returns a PAIR — the
# uploaders whose files may travel and the design workshops the caller may open — and this sentinel
# is what makes ``_public`` resolve the pair from the viewer. A caller that names ``media_urls``
# itself keeps whatever ``media_workshops`` it passed (empty unless it said otherwise).
#
# A LONE ``media_workshops`` IS REFUSED, THEREFORE, RATHER THAN QUIETLY RE-DECIDED.
# A ``media_workshops`` handed in with no ``media_urls`` beside it is never used as the call site
# wrote it: with a viewer it is OVERWRITTEN by ``media_url_scope``'s answer — which may be WIDER than
# what was passed — and with no viewer it is honoured beside an empty uploader set. Two different
# answers to one call, neither of them the one asked for, so a non-empty one raises. A guard and not
# a paragraph, because the sentence this replaced ("neither half can be widened by a call site that
# only thought about the other one") was true one way round and false the other, and nothing in the
# tree could tell a reader which.
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


async def _public(
    rows: Any,
    viewer: Any = None,
    *,
    media_urls: Any = _UNSET_URLS,
    media_workshops: frozenset[str] = frozenset(),
) -> Any:
    """``public_encode`` plus the derived display name for every media row it carries.

    ``originalFilename`` stays exactly as uploaded — it is the only handle a researcher has for
    matching a file against their own copy — and ``displayFilename`` is added beside it: the same
    ``{RecordType}-{RecordName}-{Descriptor}-{stamp}`` name the data browser and the export use, so
    a clip reads the same in the app list as it does in a downloaded zip. Nothing in storage moves.

    THE ``url`` IS NOT ALWAYS PRESENT, and this is the one file where that matters most. Reading the
    repository is open to every signed-in account, but a ``MediaFile.url`` is a fetchable object URL —
    it IS the file — so it travels only to callers entitled to those bytes. Every row still travels:
    name, type, caption, duration, parent record, uploader. ``media_url_scope`` answers that
    entitlement in TWO halves and both are handed to ``public_encode`` by name: the set of UPLOADERS
    whose files may travel (the viewer's own uploads plus every uploader who has granted them data
    access — one query, and only below professor, so a grantee who may download a researcher's data
    can also play their recordings), and the set of DESIGN WORKSHOPS this account may open. A caller
    that names neither a ``viewer`` nor the pair itself gets the cheap safe default — no uploaders
    and no workshops — which is correct for every internal use of this helper.

    THE WORKSHOP HALF IS NEW, AND IT EXISTS BECAUSE THIS ROUTE DISAGREED WITH FIVE OTHERS. A file
    captured at a design workshop carries ``linkedRecordType="designWorkshop"`` and that workshop's
    id (``dictation_consent.MEDIA_TAG``, the tag both clients send). A co-designer who may open the
    workshop could ALREADY take those bytes on five surfaces —
    ``GET /design-workshops/{id}/transcripts``, the AI layer's ``_readable_media_ids``, ``/export``,
    ``/data`` and the images baked into the generated report — because
    ``owned_or_granted_where(owner_field="uploadedById")`` carries a matching tag-keyed arm. This
    gate was the ONE that still keyed on uploader identity, so ``GET /media`` withheld the ``url``
    of a photograph the same account could obtain by generating the report and lifting the picture
    out of it: a refusal that protected nothing and read to a designer as the app being broken. The
    url now travels for a file TAGGED to a workshop the caller may open, which is what makes this
    route agree with the download surfaces that had already admitted that account.

    THE UPLOADER SET WAS DELIBERATELY NOT WIDENED, and that is the whole of the care in this change.
    Folding a co-designer into ``media_urls`` would say "this account may take that uploader's
    data", and the same set is applied on ``search``, ``products``, ``tools``, ``processes`` and the
    consolidated questionnaire — surfaces with no workshop in them — so it would hand over every
    file those uploaders have ever uploaded, everywhere. What widened is the TEST:
    ``records._redact_sensitive`` asks the narrower question against the FILE's own tag columns, and
    fails closed on any node that does not carry both of them.

    ``transcriptText`` TRAVELS UNDER THE SAME RULE, and this docstring used to promise the opposite —
    it listed "transcript" among the fields that always travel, and ``GET /media`` and
    ``GET /media/{id}`` duly served the verbatim text of every artisan interview in the repository to
    a CROWDSOURCE_VOLUNTEER, which made the per-clip transcript gate on the design-workshop surface
    decoration. The predicate now lives in exactly one place, ``records._MEDIA_TAKEABLE_KEYS``; do not
    re-open it here. Said plainly, because it is a real consequence rather than a side effect nobody
    weighed: the workshop arm above releases the transcript ALONGSIDE the url, for a workshop-tagged
    file only — ``_MEDIA_TAKEABLE_KEYS`` drops the two together on purpose, and the surface a
    co-designer would otherwise use, ``GET /design-workshops/{id}/transcripts``, admits exactly the
    same account through the same predicate. What is NOT re-opened is the rank-only behaviour that
    handed a volunteer every interview in the repository: a file with no design-workshop tag is
    judged by its uploader, as it has been since 2026-08-15.

    ``media_urls`` is for the one caller that has an uploader ID but no user object —
    ``_finish_pending_media``, which is handing somebody back the file they have just uploaded.
    ``media_workshops`` is its workshop-half twin. The two are resolved as a PAIR or stated as a
    pair, and that is now ENFORCED rather than merely asked for: naming ``media_urls`` keeps whatever
    ``media_workshops`` that call site passed rather than resolving a viewer behind its back, and
    naming a non-empty ``media_workshops`` with no ``media_urls`` beside it raises, because under the
    sentinel that set is not used as written — see the ``ValueError`` at the top of this function. So
    no caller can be handed the wider answer, or quietly given a different one, by thinking about one
    half and forgetting the other. Getting an entitlement by OMISSION is how the transcript leak
    described above happened once — see the ``THE TRANSCRIPT IS BYTES, NOT A CAPTION`` banner above
    ``records._MEDIA_URL_KEYS``. Cited by its heading and not by a line number on purpose: the pin
    that stood here read "``records.py`` lines 60-74", which named that paragraph exactly in ``main``
    and then, the moment this same change edited that file five lines above the banner, opened on a
    bare ``#`` and stopped five lines short of the paragraph's end. No replacement number is offered
    here: a heading is re-findable by grep after the next edit, and a number is not.
    """
    if media_urls is _UNSET_URLS and media_workshops:
        # THE PAIR RULE, MADE ENFORCEABLE INSTEAD OF WRITTEN DOWN. Under the sentinel this function
        # resolves BOTH halves from the viewer, so a workshop set arriving on its own is discarded
        # (viewer given) or acted on beside an empty uploader set (no viewer) — see the banner above
        # ``_UNSET_URLS`` for the two shapes. ``ENQUEUEABLE_PROCESSING_REQUESTS`` argues the same
        # case for a caller-facing refusal in this file — "the refusal is a 422 rather than a silent
        # drop" — and the argument is stronger here, because this drop is silent to a REVIEWER as
        # well as to a caller: nothing in the response says which of the two answers was used.
        #
        # It cannot fire on the wire. Every call site in this module names ``media_urls`` or names
        # neither half (true as of 2026-08-27; check
        # `grep -n '_public(' backend/app/api/routes/media.py`), so this is an invariant held for the
        # NEXT call site — which is why it is a ValueError and not an HTTPException. The empty set is
        # deliberately allowed through: stating "no workshops" and being handed the viewer's real
        # answer loses nothing, and ``_finish_pending_media`` writes it out for exactly that reason.
        raise ValueError(
            "_public(media_workshops=...) needs media_urls named beside it: under _UNSET_URLS both "
            "halves are resolved from the viewer, so a lone workshop set is either overwritten by "
            "that answer or acted on beside an empty uploader set."
        )
    many = isinstance(rows, list)
    items = list(rows) if many else [rows]
    # THE LABEL READ AND THE ENTITLEMENT READ DO NOT DEPEND ON EACH OTHER, SO THEY GO TOGETHER.
    # ``_interview_labels`` keys off the ROWS and ``media_url_scope`` off the VIEWER alone — neither
    # consumes the other's answer — and awaiting them in turn put the whole of ``media_url_scope``
    # behind the label read on all three routes that reach this helper (``GET /media``,
    # ``GET /media/orphans``, ``GET /media/{id}``).
    #
    # WHAT IT IS WORTH, HONESTLY: ``media_url_scope`` is TWO sequential queries below professor (the
    # grants, then the design-workshop tag ids) and ZERO at Professor and above, where it
    # short-circuits to (all URLs, no workshops). So a researcher gets a round trip back and an
    # admin gets nothing — which is why this does not move ``GET /media``'s 3.81/4.36 measured
    # trips in docs/SCALABILITY.md §1.2, taken as an admin. Two coroutines, well inside
    # ``pool_width()``; the pair inside ``media_url_scope`` stays sequential because the second read
    # is skipped entirely when the first returns ALL_MEDIA_URLS.
    labelled = [r for r in items if r is not None]
    if media_urls is _UNSET_URLS and viewer is not None:
        # Both halves come from one call, so the URL gate and the download gate cannot be given
        # different answers to "which workshops may this account open" — that drift IS the defect.
        # The second query is only paid below professor and only by a caller that named a viewer;
        # ``media_url_scope`` short-circuits a professor to (all URLs, no workshops) as before.
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
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="You can only manage your own uploads"
        )


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
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No parts to complete"
        )
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
            detail=str(
                result.get("message") or "Grid measurement is not configured on this server."
            ),
        )
    # ``content`` goes out of scope here. A FAILED read still returns 200: the provider was reachable
    # and answered, the message says so, and both clients already route that to "enter it manually" —
    # which is the correct next move and the same one a 502 would produce, without telling a researcher
    # that the server is broken when it is working exactly as designed.
    return result


async def _finish_pending_media(
    existing: Any, processing_requests: list[str] | None, user_id: str, settings: Any
) -> dict[str, Any]:
    """Return an already-created media row for a retried /complete, enqueuing its processing jobs once
    if the original request died before it could (so a retry never drops transcription)."""
    if processing_requests and not (existing.processingJobs or []):
        await enqueue_media_processing_jobs(existing, processing_requests, user_id, settings)
        existing = await db.mediafile.find_unique(where={"id": existing.id}, include=INCLUDE)
    # The uploader's own file, handed straight back to them: this function has their id rather than
    # their user row, so the entitlement is stated directly instead of resolved from a viewer.
    #
    # THE WORKSHOP HALF IS EMPTY, AND IT IS WRITTEN OUT RATHER THAN LEFT OFF. There is nothing to
    # widen here — the row being returned is the caller's OWN upload, which the uploader set already
    # covers, and a user id alone cannot answer "which workshops may this account open" without the
    # user row ``media_url_scope`` needs. Stating the empty set keeps the pair visible at the call
    # site, so the next person to touch this line sees a decision instead of a gap; that is the rule
    # the ``THE TRANSCRIPT IS BYTES, NOT A CAPTION`` banner above ``records._MEDIA_URL_KEYS`` exists
    # to enforce. Cited by its heading, because the line pin that stood here — ``records.py`` lines
    # 60-74 — rotted inside this very change, when that banner moved five lines down the file.
    return await _public(existing, media_urls={user_id}, media_workshops=frozenset())


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


#: THE TRANSCRIPT COLUMNS ARE THE SERVER'S TO WRITE, AND ``/complete`` IS NOT ONE OF ITS WRITERS.
#:
#: ``MediaCompleteRequest`` declares all four (``schemas/media.py``), and until this constant existed
#: ``payload.model_dump()`` carried every one of them straight into ``db.mediafile.create``. That made
#: the upload route a FOURTH writer of these columns, and the only unguarded one. The three that are
#: guarded, counted 2026-08-28 by reading every ``await db.mediafile.update``/``update_many`` call
#: site in ``backend/app`` — 15 of them, which is ``grep -rn 'await db\.mediafile\.update' backend/app
#: | grep -cv '#:'`` (re-run and re-counted 2026-08-28 during verification; the ``grep -v`` is not
#: decoration — THIS COMMENT MATCHES ITS OWN SEARCH, so the bare ``| wc -l`` the first draft of this
#: note quoted returns 16 and names a call site that is a sentence about call sites); the three call
#: sites outside the list below (``media.relink_media`` at :1141, the stage-8
#: retention route in ``design_workshops``, and ``media_queue``'s measurement writer) touch only the
#: link columns and ``extraMetadata``:
#:
#:   1. ``services/media_queue`` — every transcription result, and the only path a provider's text
#:      takes. It is reached solely through ``enqueue_media_processing_jobs``, the single choke point
#:      the artisan's consent gate sits in.
#:   2. ``POST /media/{id}/transcript`` (``set_media_transcript``, below) — a person typing or
#:      correcting the words, which is a person's own act rather than a send to a third party.
#:   3. ``services/dictation_consent`` — the revocation sweep, which only ever moves rows TO
#:      ``FAILED``.
#:
#: A caller who simply posted the words WITH the upload walked past all three — and since a
#: workshop's transcript now travels to every co-designer on that workshop, a planted one travels
#: with it.
#:
#: THE FOUR KEYS ARE KEPT ON THE SCHEMA AND DROPPED HERE, which is the treatment ``url`` already gets
#: below for the same reason: ``APIModel`` sets ``extra="forbid"``, so deleting a key from the schema
#: turns any client still sending it into a 422 — and on Android a 4xx is NOT queued by ``saveOrQueue``,
#: so a refused body loses the recording rather than retrying it. Neither shipped client sends any of
#: the four today (Android's ``MediaCompleteRequest`` in ``data/ApiModels.kt`` declares none of them;
#: the web's body in ``frontend/lib/media.ts`` lists none), but ``transcribeMediaFile`` in that same
#: web module still returns exactly ``{transcriptText, transcriptStatus, transcriptError}``, which is
#: the shape an older build merged into this body. Ignoring costs nothing; removing bets on the field.
#:
#: NOTHING IS REFUSED. A 422 would tell a phone at the end of a fortnight's sync that its recording
#: was lost, which is false — the file is stored and attached exactly as captured. What does not
#: happen is the caller's words being recorded as the artisan's. The columns stay NULL at create and
#: are filled only by the queue, so ``transcriptStatus`` in the response is the one the gate decided.
SERVER_WRITTEN_TRANSCRIPT_FIELDS = frozenset(
    {"transcriptText", "transcriptSummary", "transcriptStatus", "transcriptError"}
)


def _assert_enqueueable(processing_requests: list[str] | None) -> None:
    """Refuse a processing request this route will not queue. See ENQUEUEABLE_PROCESSING_REQUESTS."""
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

    AND THE GATE CANNOT BE WALKED PAST BY POSTING THE TEXT INSTEAD. The four transcript keys on
    ``MediaCompleteRequest`` are accepted and dropped before the create — see
    ``SERVER_WRITTEN_TRANSCRIPT_FIELDS`` — so this route creates no transcript, and the columns it
    leaves NULL are filled only by the writers the gate sits in front of.
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

    # New row: the staged object must live under the caller's own media/<user_id>/ prefix, so a user
    # cannot register a media row over another user's staged object.
    _assert_owns_object(payload.objectKey, current_user)

    # The transcript columns are dropped here rather than overwritten: there is nothing for the server
    # to derive at create time, and the queue is their only writer. See
    # SERVER_WRITTEN_TRANSCRIPT_FIELDS for why the keys survive on the schema instead of being deleted.
    data = clean_data(
        payload.model_dump(exclude={"processingRequests"} | SERVER_WRITTEN_TRANSCRIPT_FIELDS)
    )
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
        parent = (
            await delegate.find_unique(where={"id": data["linkedRecordId"]}) if delegate else None
        )
        if parent is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Linked record not found"
            )
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
    # THE DESIGN & PROTOTYPE WORKSHOP a designer FILED this upload under, which is a different scope
    # with different machinery from the line above and therefore a second gate rather than a
    # replacement. It comes straight off the payload — this is the one link on MediaFile that is NOT
    # derived from `linkedRecordType`, and `records.media_relation_data` carries the argument for why
    # deriving it would break the orphan-recovery split. Ungated, any client could file a loose file
    # into a stranger's workshop and have it appear in that workshop's scoped media list.
    await assert_may_file_under(data.get("designWorkshopId"), current_user)
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
        racer = await db.mediafile.find_unique(
            where={"objectKey": payload.objectKey}, include=INCLUDE
        )
        if racer is not None:
            return await _finish_pending_media(
                racer, processing_requests, current_user.id, settings
            )
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
    # THE DESIGN & PROTOTYPE WORKSHOP a MISCELLANEOUS upload was filed under — the column, never
    # the tag. `linkedRecordType=designWorkshop` above still answers "which stage photographs
    # belong to this workshop", and the two questions are different; see
    # `MediaFile.designWorkshopId` in schema.prisma.
    designWorkshopId: str | None = None,
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
        where["OR"] = [
            {"originalFilename": contains(search)},
            {"caption": contains(search)},
            {"mimeType": contains(search)},
        ]
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
    # The count and the page answer different questions about the same WHERE and neither reads the
    # other, so they go out together — the shape ``records.count_and_page`` exists for, spelled here
    # because this route needs ``include=INCLUDE`` and that helper takes ``relations`` instead.
    total, items = await gather_reads(
        db.mediafile.count(where=where),
        db.mediafile.find_many(
            where=where,
            include=INCLUDE,
            skip=skip,
            take=page_size,
            # A single upload session writes many rows in the same instant — a phone syncing a
            # fortnight's captures, the record forms' awaited upload loops — so ``createdAt`` ties
            # are the normal case here, not the edge one, and offset paging over them loses and
            # repeats files. ``records.with_id_tiebreak`` makes the order total.
            order=with_id_tiebreak({"createdAt": "desc"}),
        ),
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
    planned: list[tuple[str, Any]] = []
    for kind, ids in by_kind.items():
        delegate = _orphan_tag_delegate(kind)
        if delegate is None:  # pragma: no cover - the two dicts are written together
            live[kind] = ids
            continue
        planned.append((kind, delegate.find_many(where={"id": {"in": sorted(ids)}})))
    # ONE WAVE, NOT ONE ROUND TRIP PER TAGGED TYPE. There are at most four of them
    # (``_orphan_tag_delegate``) and none of them reads another's output, so awaiting them in turn
    # was up to four cross-region waits on an admin recovery screen that already pays several.
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
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported record type for re-linking"
        )
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
    two-segment path resolves here.

    **IT IS ALSO WHERE THE EDITED FLAG IS SET, BECAUSE THIS IS THE ONLY ROUTE BY WHICH A HUMAN'S WORDS
    REPLACE A MACHINE'S.** Owner, 2026-08-30: *"it should appear in the rich text box with the flag of
    whether it has been edited by the user or not"*. Until 2026-08-31 this route wrote the words and
    recorded neither that an edit had happened nor who made it, so a transcript a researcher had
    rewritten line by line and one straight off ElevenLabs were byte-indistinguishable to the
    consolidated interview page, the report annexures and both handsets.

    ``transcriptEditedAt`` IS the flag and it is stamped from the SERVER's clock, not from anything
    the caller sends: an edit stamp a client could choose is not an audit stamp. ``transcriptEditedById``
    is likewise ``current_user.id`` and never a field on the payload, so the pair cannot be attributed
    to somebody who was not at the keyboard.

    NEITHER COLUMN IS REACHABLE FROM ``POST /media/complete``, and that needs no code: they are absent
    from ``MediaCompleteRequest``, whose ``APIModel`` sets ``extra="forbid"``, so an upload that tried
    to arrive pre-stamped as human-edited is refused outright rather than dropped the way
    :data:`SERVER_WRITTEN_TRANSCRIPT_FIELDS` drops the four it does list. The distinction is worth
    keeping: those four are dropped precisely because a shipped client once sent them, and these two
    have never been on the wire.

    **ACCEPTING AN AI REFINEMENT ALSO STAMPS IT, AND THAT IS THE CONSERVATIVE ANSWER RATHER THAN A
    PRECISE ONE.** ``POST /media/{id}/refine-transcript`` returns text without persisting it, so a
    designer who accepts a refinement arrives here with words that are a MODEL's rather than their
    own — strictly, "a person corrected this" overclaims. This route cannot tell the two apart: it
    receives a string. Given that, it flags, because the two errors are not the same size. Flagging
    an accepted refinement says "a person was involved in the words on screen", which is true — they
    chose them over what was there. NOT flagging would say "this is what the transcription provider
    returned" about text that provider never produced, and that is the single claim this column was
    added to stop being made silently.

    THE QUEUE NEVER CLEARS THE FLAG, and that asymmetry is deliberate. ``media_queue`` writes
    ``transcriptText`` on every provider result — including the refined, translated pass that lands
    hours after a quick transcript — and if it also blanked ``transcriptEditedAt`` a designer's
    corrections would be recorded as the machine's own words at the moment they were overwritten. The
    two clients resolve that collision the other way round, in the place where the person can be asked:
    a refined transcript is OFFERED against an edited box and never imposed on it. See
    ``frontend/app/(protected)/questionnaire/page.tsx``.
    """
    media = await require_record(db.mediafile, media_id)
    if not is_admin(current_user) and getattr(media, "uploadedById", None) != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only edit the transcript of media you uploaded",
        )
    updated = await db.mediafile.update(
        where={"id": media.id},
        data={
            "transcriptText": payload.text,
            "transcriptStatus": "COMPLETED",
            "transcriptError": None,
            # Server clock and server-resolved identity — see the docstring. ``datetime.now(UTC)``
            # rather than a Prisma default because this column has no default: it must stay NULL for
            # every row the queue writes, and only ever gain a value here.
            "transcriptEditedAt": datetime.now(UTC),
            "transcriptEditedById": current_user.id,
        },
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
        where["status"] = enum_filter_or_422(
            statusFilter, MEDIA_PROCESSING_JOB_STATUSES, field="statusFilter"
        )
    # Count and page together, for the reason ``records.count_and_page`` exists — spelled out here
    # rather than delegated to it because this read needs ``include=`` and that helper takes
    # ``relations``.
    total, jobs = await gather_reads(
        db.mediaprocessingjob.count(where=where),
        db.mediaprocessingjob.find_many(
            where=where,
            include={"mediaFile": True, "requestedBy": True, "product": True, "tool": True},
            skip=skip,
            take=page_size,
            # Jobs are enqueued in batches (one ``/complete`` can raise several), so they share
            # creation instants and a paged read of them needs the ``id`` tiebreak to stay total.
            order=with_id_tiebreak({"createdAt": "desc"}),
        ),
    )
    # NO VIEWER, SO THE NESTED ``mediaFile`` TRAVELS WITHOUT ITS BYTES — for every caller, the
    # file's own uploader and a master admin included. ``public_encode`` with no viewer and no
    # ``media_urls`` takes the cheapest safe answer (``set()``, never ``ALL_MEDIA_URLS``) and
    # ``media_workshops`` defaults empty, so ``records._MEDIA_TAKEABLE_KEYS`` — ``url``,
    # ``publicUrl``, ``objectKey`` and both transcript columns — is dropped from the file hanging off
    # every job row, a transcription job on a design-workshop recording included.
    #
    # PRE-EXISTING, FAIL-CLOSED, AND LEFT ALONE. Said here because a reader arriving from the
    # design-workshop arm in ``records._redact_sensitive`` would otherwise take this for its fallout:
    # this encode is byte-for-byte what ``main`` has (true as of 2026-08-27; check
    # `git show main:backend/app/api/routes/media.py | grep -n "public_encode(jobs)"`). Both clients
    # are already TYPED to the absence and say so — ``MediaProcessingJob.mediaFile`` in
    # ``frontend/lib/media.ts`` is a ``Pick<>`` with no ``url``, warning that typing it wider "would
    # compile and then render a dead player", and ``MediaProcessingJobDto`` in Android's
    # ``ApiModels.kt`` says to "take the name and the type from it, never the bytes". Passing
    # ``current_user`` here would widen a queue-status surface no client asks bytes of; the bytes are
    # served by the media table and ``GET /media/{id}``, under the gate ``_public`` applies.
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
    # NO VIEWER, for the reason written out at ``list_media_processing_jobs``: the nested
    # ``mediaFile`` comes back stripped of ``url``, ``objectKey`` and its transcript, for the admin
    # driving the retry as much as for anyone else. Pre-existing and fail-closed — unchanged from
    # ``main`` (true as of 2026-08-27; check
    # `git show main:backend/app/api/routes/media.py | grep -n "public_encode(updated)"`), and
    # untouched by the design-workshop arm in ``records._redact_sensitive``. The client type for this
    # response is the same ``Pick<>`` without ``url`` that the list route returns, so nothing on
    # either client is waiting for bytes that never arrive.
    return public_encode(updated)


@router.delete("/object", status_code=status.HTTP_204_NO_CONTENT)
async def delete_staged_object(
    objectKey: str, current_user: Any = Depends(get_current_user)
) -> None:
    """Delete a staged S3 object that was pre-uploaded but never attached to a saved record.

    Scoped to the caller's own ``media/<user_id>/`` prefix, and refuses to touch any object that is
    already referenced by a MediaFile (those are deleted through the normal media delete route).
    """
    if not objectKey.startswith(f"media/{current_user.id}/"):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only delete your own staged uploads",
        )
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
