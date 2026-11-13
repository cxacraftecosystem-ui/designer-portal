import asyncio
import hashlib
import logging
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any

from fastapi import (
    APIRouter,
    Body,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)
from fastapi.responses import JSONResponse
from pydantic import model_validator

from app.services import craft_master
from app.services.text_format import by_count, count_noun
from app.core.config import get_settings
from app.core import odia_script
from app.core.db import db
from app.core.deps import (
    assert_can_create_design_workshops,
    assert_can_delete,
    can_run_design_workshops,
    get_current_user,
    is_content_admin,
    require_content_admin,
)
from app.schemas import design_workshop_approvals as design_workshop_approvals_schema
from app.schemas import design_workshop_review_loop
from app.schemas.design_workshops import (
    DESIGN_WORKSHOP_STATUSES,
    WORKSHOP_KINDS,
    AiExpandIn,
    AiLayerDecisionIn,
    AiLayerRegisterIn,
    AiMediaVerbIn,
    AiOnDeviceLayerIn,
    AiProofreadIn,
    AiTranslateIn,
    CustomSectionsIn,
    DesignWorkshopCreate,
    DesignWorkshopUpdate,
    DictationConsentIn,
    ExportRecordIn,
    ReportGenerateIn,
    StageSaveIn,
)
from app.services import (
    ai,
    ai_layers,
    ai_verb_cap,
    ai_verbs,
    app_settings,
    design_workshop_approvals,
    design_workshop_posts,
    dictation_cap,
    dictation_consent,
    email_outbox,
    naming,
)
from app.api.consent_http import require_send
from app.services.ai import transcribe_audio_bytes
from app.services.concurrency import gather_reads
from app.services.cost_integrity import analyse_cost_integrity, cost_findings_payload
from app.services.custom_sections import (
    CUSTOM_ENTITY_KEY,
    V1_FIELD_TYPES,
    CustomFieldSpec,
    CustomOption,
    CustomSectionEditError,
    CustomSectionSpec,
    answered_keys,
    apply_definition_plan,
    definition_payload,
    load_definition,
    load_definition_or_empty as load_custom_definition_or_empty,
    plan_definition,
    validate_definition,
)
from app.services.design_workshop_viewers import visible_to_clause
from app.services.entry_provenance import (
    canonical_divergence,
    resolve_display_names,
    resolve_entry_provenance,
)
from app.services.design_workshops import (
    REFERENCE_LIMIT_DEFAULT,
    REFERENCE_LIMIT_MAX,
    REFERENCE_OFFSET_MAX,
    assemble_workshop_data,
    report_survey_sittings,
    assert_every_designer_may_be_named,
    attach_district_anchors,
    attach_report_ai_layers,
    attach_report_custom_sections,
    attach_report_questionnaires,
    attach_report_references,
    attach_report_transcripts,
    attach_the_named_designers,
    entry_rows,
    load_workshop_or_404,
    media_resolver,
    named_designer_team,
    reference_options,
    render_report,
    resolve_template_id,
    save_stage,
    seed_designer_prefill,
    workshop_completeness,
    workshop_media_ids,
    workshop_summary,
)
from app.services.identity_ocr import (
    DISCARD as RETENTION_DISCARD,
    STORE as RETENTION_STORE,
    SUPPORTED_MIME_TYPES,
    IdentityOcrUnavailable,
    get_identity_ocr_settings,
    parse_retention,
    read_identity_card,
    retention_stamp,
    with_retention,
)
from app.services.market_analysis import analyse, market_findings_payload
from app.services.report_builder import sign_off_lines
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import (
    contains,
    enum_filter_or_422,
    jsonify_metadata,
    owned_or_granted_where,
    plain,
    with_id_tiebreak,
)
from app.services.report_docx import DOCX_MIME
from app.services.report_pdf import PDF_MIME
from app.services.report_templates import (
    SpecialSection,
    inert_section_toggles,
    template as get_template,
    template_choices,
)
from app.services.memory_budget import budget_bytes
from app.services.sanction_orders import can_record_sanction_orders
from app.services.s3 import (
    ObjectTooLarge,
    delete_object,
    discard_temp,
    download_to_temp,
    get_object_bytes,
    head_object,
)
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    registry_to_dict,
    registry_version,
    stages,
)
from app.services.uploads import read_upload_bounded
from app.services.workshop_transcripts import load_transcript_items
from app.transcription.schedule import PAUSED_MESSAGE
from app.transcription import workdir

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])

logger = logging.getLogger(__name__)

_MIME = {"DOCX": DOCX_MIME, "PDF": PDF_MIME}
_EXTENSION = {"DOCX": "docx", "PDF": "pdf"}

_SERVER_TIER = ai_layers.AiTier.TIER_3



_HEADER_TEXT_COLUMNS: tuple[str, ...] = (
    "title",
    "templateId",
    "workshopKind",
    "craftId",
    "craftName",
    "clusterName",
    "state",
    "district",
    "notes",
    "workshopId",
    "status",
)

_HEADER_DATE_COLUMNS: tuple[str, ...] = ("startDate", "endDate")

_HEADER_REQUIRED_COLUMNS = frozenset({"title", "templateId", "status"})

_CREATE_OPTIONAL_COLUMNS: tuple[str, ...] = tuple(
    key for key in _HEADER_TEXT_COLUMNS if key not in _HEADER_REQUIRED_COLUMNS
)

_CREATE_ONLY_STAMPS = frozenset({"createdById", "schemaVersion"})


_STAGE_ONE_OWNS = (
    "this comes from stage 1 (Workshop Setup & Cover Information). Open that stage and correct it "
    "there"
)

_CONSENT_HAS_ITS_OWN_ROUTE = (
    "the artisan's consent is recorded on the workshop's own page, where every answer is kept"
)

_DECISION_HAS_ITS_OWN_ROUTE = (
    "the last decision is recorded by POST /design-workshop-inspections/{id}/send-back or by the "
    "sanctioning authority's approve route, each of which also writes the audit entry in the same "
    "transaction. A decision that could be set from a header edit would be a decision that could "
    "be manufactured"
)

_SIGN_OFF_HAS_ITS_OWN_SCREEN = (
    "who approved this report, in which round and when, and who handed it on, to which office and "
    "with which file, are recorded by the Ministry Admin's own decisions on Reports to approve, each "
    "with an audit entry written in the same transaction. A sign-off that could be set from a header "
    "edit would be a sign-off that could be manufactured"
)

_SIGN_OFF_READ_BACK = (
    "read back for the single-record read and not a stored column: the names and role resolve "
    "approvedById and handedOnById, and decisions is the audit log of this report"
)

_HAND_IN_IS_COUNTED = (
    "when the report was last handed in is stamped by the server on every entry into "
    "Pre-submission, together with the round counter"
)


_NEVER_PATCHABLE: dict[str, str] = {
    "designerUserId": (
        "the lead designer is chosen when the workshop is created. Correct the designer's details "
        "in stages 1 and 3, or change the workshop's designers in its visibility settings"
    ),
    "designerUserIds": (
        "the designers a workshop is for are changed in its visibility settings, not in this form"
    ),
    "venue": _STAGE_ONE_OWNS,
    "scheme": _STAGE_ONE_OWNS,
    "designerName": (
        "the designer's name comes from stage 1. Correct it there; it is the name the report "
        "prints on the cover and in the certification block"
    ),
    "implementingAgency": _STAGE_ONE_OWNS,
    "sponsor": _STAGE_ONE_OWNS,
    "workshopCode": (
        "the workshop code comes from stage 1. It prints on the report cover and on the "
        "workshop's cards, so it can't be changed here"
    ),
    "id": "a workshop's ID can't be changed",
    "createdById": "who created the workshop can't be changed",
    "createdAt": "when the workshop was created is recorded automatically",
    "updatedAt": "this is recorded automatically each time the workshop is saved",
    "deletedAt": "use Delete or Restore on the workshop instead",
    "deletedById": "use Delete or Restore on the workshop instead",
    "schemaVersion": "this is recorded automatically each time a stage is saved",
    "dictationConsent": _CONSENT_HAS_ITS_OWN_ROUTE,
    "dictationConsentAt": _CONSENT_HAS_ITS_OWN_ROUTE,
    "dictationConsentById": _CONSENT_HAS_ITS_OWN_ROUTE,
    "dictationConsentByName": (
        "this is filled in automatically when the artisan's consent is recorded"
    ),
    "reviewNotes": _DECISION_HAS_ITS_OWN_ROUTE,
    "reviewedById": _DECISION_HAS_ITS_OWN_ROUTE,
    "reviewedAt": _DECISION_HAS_ITS_OWN_ROUTE,
    "approvedById": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "approvedAt": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "approvedRound": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "handedOnById": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "handedOnAt": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "handedOnTo": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "handedOnExportId": _SIGN_OFF_HAS_ITS_OWN_SCREEN,
    "lastHandedInAt": _HAND_IN_IS_COUNTED,
    "approvedByName": _SIGN_OFF_READ_BACK,
    "approvedByRole": _SIGN_OFF_READ_BACK,
    "handedOnByName": _SIGN_OFF_READ_BACK,
    "decisions": _SIGN_OFF_READ_BACK,
    "submissionRound": (
        "the submission count goes up automatically each time the report is handed in for "
        "inspection"
    ),
    "sanctionOrder": (
        "the sanction order that opened this workshop is recorded under Sanction orders and can't "
        "be changed from the workshop"
    ),
}


def _immutable_field_refusal(offending: list[str]) -> str:
    reasons = "; ".join(f"{key} — {_NEVER_PATCHABLE[key]}" for key in offending)
    subject = (
        "This field is not editable here"
        if len(offending) == 1
        else "These fields are not editable here"
    )
    return f"{subject}: {reasons}. Nothing was saved."


class DesignWorkshopPatch(DesignWorkshopUpdate):
    @model_validator(mode="before")
    @classmethod
    def _refuse_the_fields_this_endpoint_does_not_own(cls, data: Any) -> Any:
        if not isinstance(data, Mapping):
            return data
        offending = [key for key in _NEVER_PATCHABLE if key in data]
        if offending:
            raise ValueError(_immutable_field_refusal(offending))
        return data


def _header_patch_data(sent: Mapping[str, Any]) -> dict[str, Any]:
    data: dict[str, Any] = {}
    for key in _HEADER_TEXT_COLUMNS:
        if key not in sent:
            continue
        value = sent[key]
        if isinstance(value, str):
            value = value.strip() or None
        if value is None and key in _HEADER_REQUIRED_COLUMNS:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"{key} can't be left empty. Every workshop needs one.",
            )
        data[key] = value
    for key in _HEADER_DATE_COLUMNS:
        if key not in sent:
            continue
        raw = sent[key]
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            data[key] = None
            continue
        parsed = _parse_date(raw)
        if parsed is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"{key} isn't a valid date. Enter it as yyyy-mm-dd, or leave it empty to "
                    "clear it."
                ),
            )
        data[key] = parsed
    return data


async def _assert_linked_workshop_exists(workshop_record_id: str) -> None:
    if await db.workshop.find_unique(where={"id": workshop_record_id}) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No workshop record exists with that id, so this design workshop cannot be linked "
                "to it. Choose one from the list, or send null to remove the link."
            ),
        )



_SCHEMA_CACHE_CONTROL = "private, max-age=0, must-revalidate"


def _etag_opaque(tag: str) -> str:
    tag = tag.strip()
    return tag[2:] if tag[:2] in ("W/", "w/") else tag


def _if_none_match_matches(header: str, etag: str) -> bool:
    tags = [t for t in (part.strip() for part in header.split(",")) if t]
    if not tags:
        return False
    if "*" in tags:
        return True
    wanted = _etag_opaque(etag)
    return any(_etag_opaque(t) == wanted for t in tags)


@router.get("/schema")
async def get_stage_schema(request: Request, _: Any = Depends(get_current_user)) -> Response:
    payload = JSONResponse(registry_to_dict())
    etag = f'W/"{hashlib.sha256(payload.body).hexdigest()[:32]}"'
    headers = {"ETag": etag, "Cache-Control": _SCHEMA_CACHE_CONTROL}

    if _if_none_match_matches(request.headers.get("if-none-match", ""), etag):
        return Response(
            status_code=status.HTTP_304_NOT_MODIFIED,
            headers={**headers, "Vary": "Accept-Encoding"},
        )

    for name, value in headers.items():
        payload.headers[name] = value
    return payload


@router.get("/templates")
async def get_report_templates(_: Any = Depends(get_current_user)) -> list[dict[str, str]]:
    return template_choices()



@router.get("/ocr/identity")
async def identity_ocr_probe(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return {
        "ocrIdentity": True,
        "method": "POST",
        "retentionPath": "/design-workshops/ocr/identity/retention",
    }


@router.post("/ocr/identity")
async def scan_identity_card(
    request: Request,
    file: UploadFile = File(...),
    retention: str = Form(RETENTION_DISCARD),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    declared = parse_retention(retention)
    settings = get_identity_ocr_settings()
    content = await read_upload_bounded(
        file,
        settings.max_image_bytes,
        request=request,
        purpose="identity card photograph",
        remedy="Photograph the card alone rather than the whole page.",
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No image was uploaded."
        )
    mime_type = (file.content_type or "image/jpeg").split(";")[0].strip().lower()
    if mime_type not in SUPPORTED_MIME_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"{mime_type or 'That file type'} cannot be read; send a JPEG, PNG or WebP.",
        )
    try:
        result = await read_identity_card(content, mime_type)
    except IdentityOcrUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)
        ) from exc
    payload = result.payload()
    payload["photograph"] = {
        "stored": False,
        "retention": declared,
        "decisionRoute": "/design-workshops/ocr/identity/retention",
    }
    return payload


@router.post("/ocr/identity/retention")
async def decide_identity_photograph(
    mediaId: str = Body(..., embed=True),
    decision: str = Body(RETENTION_DISCARD, embed=True),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    choice = parse_retention(decision)
    media = await db.mediafile.find_unique(where={"id": mediaId})
    if media is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That photograph is no longer here — it may already have been deleted. Nothing was "
                "changed. Reload the stage to see what is still attached."
            ),
        )
    if (
        not is_content_admin(current_user)
        and getattr(media, "uploadedById", None) != current_user.id
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This photograph was attached by somebody else, so only they or an admin can decide "
                "whether it is kept. Ask them, or ask an admin to remove it."
            ),
        )
    await design_workshop_posts.refuse_a_holders_media_write(media, current_user)
    if getattr(media, "mediaType", None) != "IMAGE":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "Only a photograph can be kept or deleted here, and that attachment is not one. "
                "Remove it from the field instead if it does not belong."
            ),
        )

    if choice == RETENTION_STORE:
        stamp = retention_stamp(choice, user=current_user, at=datetime.now(UTC))
        await db.mediafile.update(
            where={"id": mediaId},
            data=jsonify_metadata(
                {"extraMetadata": with_retention(getattr(media, "extraMetadata", None), stamp)}
            ),
        )
        return {
            "mediaId": mediaId,
            "decision": RETENTION_STORE,
            "deleted": False,
            "retention": stamp,
        }

    object_key = getattr(media, "objectKey", None)
    if object_key:
        shared = await db.mediafile.find_first(
            where={"objectKey": object_key, "id": {"not": mediaId}}
        )
        if shared is None:
            try:
                await asyncio.to_thread(delete_object, object_key)
            except Exception as exc:  # noqa: BLE001 - the storage message is not for the caller
                logger.warning(
                    "Identity photograph object could not be deleted (%s)", type(exc).__name__
                )
                raise HTTPException(
                    status_code=status.HTTP_502_BAD_GATEWAY,
                    detail=(
                        "The photograph could not be deleted from storage, so nothing was removed "
                        "and it is still attached. Try again in a moment; if it keeps failing, tell "
                        "an admin that this file needs deleting by hand."
                    ),
                ) from exc
    await db.mediafile.delete(where={"id": mediaId})
    return {
        "mediaId": mediaId,
        "decision": RETENTION_DISCARD,
        "deleted": True,
        "objectDeleted": bool(object_key),
        "retention": None,
    }


DICTATION_MAX_BYTES = 6 * 1024 * 1024


async def _transcribe_one_dictation(
    file: UploadFile,
    languageHint: str | None,
    current_user: Any,
    request: Request,
    *,
    record: Any = None,
) -> dict[str, Any]:
    allowance = await dictation_cap.load_allowance(current_user.id)
    refusal = dictation_cap.cap_refusal(allowance)
    if refusal is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=refusal,
        )

    content = await read_upload_bounded(
        file,
        DICTATION_MAX_BYTES,
        request=request,
        purpose="dictated clip",
        remedy=(
            "Upload a longer recording as workshop audio instead — it is transcribed in the "
            "background."
        ),
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="No audio was uploaded."
        )
    if await app_settings.load_transcription_paused():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=PAUSED_MESSAGE
        )
    try:
        counted = await dictation_cap.reserve(current_user.id, allowance.day, allowance.limit)
    except dictation_cap.CapSpent:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=dictation_cap.cap_refusal(
                dictation_cap.Allowance(
                    day=allowance.day,
                    limit=allowance.limit,
                    used=allowance.limit if allowance.limit is not None else allowance.used,
                )
            ),
        ) from None
    try:
        result = await transcribe_audio_bytes(
            content,
            file.filename or "dictation.webm",
            (file.content_type or "audio/webm").split(";")[0].strip(),
            get_settings(),
            user_id=current_user.id,
        )
    except BaseException:
        if counted is not None:
            await dictation_cap.refund(current_user.id, allowance.day)
        raise
    if str(result.get("status") or "").upper() == "UNAVAILABLE":
        if counted is not None:
            await dictation_cap.refund(current_user.id, allowance.day)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(result.get("message") or "Transcription is not configured."),
        )

    odia_script.apply_to_result(result, languageHint)
    media_id = await _keep_dictation(content, file, result, current_user, record) if record is not None else None
    return {
        "status": result.get("status"),
        "mediaId": media_id,
        "text": result.get("text") or "",
        "provider": result.get("provider"),
        "languageHint": languageHint,
        "message": result.get("message"),
        **dictation_cap.allowance_payload(
            dictation_cap.Allowance(
                day=allowance.day,
                limit=allowance.limit,
                used=allowance.used if counted is None else counted,
            )
        ),
    }


DICTATION_LINK_TYPE = "dictation"


async def _keep_dictation(
    content: bytes, file: UploadFile, result: dict[str, Any], current_user: Any, record: Any
) -> str | None:
    if str(result.get("status") or "").upper() != "COMPLETED" or not str(result.get("text") or "").strip():
        return None
    workshop_id = str(getattr(record, "id", "") or "")
    if not workshop_id:
        return None
    try:
        from pathlib import PurePosixPath

        from app.services import transcript_insights, transcript_pipeline
        from app.services.s3 import make_object_key, public_url_for_key, put_object_bytes

        filename = file.filename or "dictation.webm"
        mime = (file.content_type or "audio/webm").split(";")[0].strip() or "audio/webm"
        ext = PurePosixPath(filename).suffix.lstrip(".") or mime.rsplit("/", 1)[-1]
        leaf = naming.file_name(
            entity="Workshop",
            what="Dictation",
            date=naming.now_ist(),
            time=True,
            subject=getattr(record, "workshopCode", None),
            ext=ext,
        )
        key = make_object_key(str(current_user.id), leaf)
        await asyncio.to_thread(put_object_bytes, key, content, mime_type=mime)
        media = await db.mediafile.create(
            data={
                "originalFilename": filename,
                "mediaType": "AUDIO",
                "mimeType": mime,
                "sizeBytes": len(content),
                "bucket": get_settings().aws_s3_bucket,
                "objectKey": key,
                "url": public_url_for_key(key),
                "uploadedBy": {"connect": {"id": str(current_user.id)}},
                "designWorkshop": {"connect": {"id": workshop_id}},
                "linkedRecordType": DICTATION_LINK_TYPE,
                "linkedRecordId": workshop_id,
                "transcriptText": str(result.get("text") or ""),
                "transcriptStatus": "COMPLETED",
                "transcriptProvider": result.get("provider"),
            }
        )
        await transcript_pipeline.store_transcription(media, result)
        try:
            await transcript_insights.enqueue_after_transcript(media.id)
        except Exception as exc:  # noqa: BLE001 - the transcript stands without its English
            logger.warning("dictation %s: translation not queued (%s)", media.id, type(exc).__name__)
        return str(media.id)
    except Exception as exc:  # noqa: BLE001 - see the docstring
        logger.warning("a dictation clip was not kept (%s)", type(exc).__name__)
        return None


DICTATION_ALLOWANCE_MAX_AGE_SECONDS = 60


@router.get("/dictation-allowance")
async def get_dictation_allowance(
    current_user: Any = Depends(get_current_user),
) -> JSONResponse:
    _require_designer(current_user)
    payload = dictation_cap.allowance_payload(await dictation_cap.load_allowance(current_user.id))
    return JSONResponse(
        payload,
        headers={"Cache-Control": f"private, max-age={DICTATION_ALLOWANCE_MAX_AGE_SECONDS}"},
    )


@router.get("/ai-verb-allowance")
async def get_ai_verb_allowance(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    return ai_verb_cap.allowance_payload(await ai_verb_cap.load_allowance(current_user.id))


@router.get("/dictate")
async def dictation_probe(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    return {
        "dictatePath": "/design-workshops/{workshop_id}/dictate",
        "allowancePath": "/design-workshops/dictation-allowance",
        "maxBytes": DICTATION_MAX_BYTES,
        "consentRequired": True,
    }


@router.post("/dictate")
async def dictate(
    file: UploadFile = File(...),
    languageHint: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    raise HTTPException(
        status_code=status.HTTP_410_GONE,
        detail="Update the app to use dictation. You can type the answer instead.",
    )


@router.post("/{workshop_id}/dictate")
async def dictate_for_workshop(
    workshop_id: str,
    request: Request,
    file: UploadFile = File(...),
    languageHint: str | None = Form(default=None),
    interviewId: str | None = Form(default=None),
    artisanIds: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    await require_send(
        dictation_consent.dictation_verdict(
            workshop_id,
            interview_id=interviewId,
            artisan_ids=dictation_consent.split_ids(artisanIds),
            record=record,
        )
    )
    return await _transcribe_one_dictation(file, languageHint, current_user, request, record=record)


@router.post("/{workshop_id}/dictation-consent")
async def record_dictation_consent(
    workshop_id: str,
    payload: DictationConsentIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True, allow_when_frozen=True)
    try:
        plans = dictation_consent.decision_plans(
            workshop_id=workshop_id,
            decision=dictation_consent.DictationConsent(payload.decision),
            actor_id=current_user.id,
            at=datetime.now(UTC),
            recorded_at=_parse_datetime(payload.recordedAt),
            note=payload.note,
        )
    except dictation_consent.ConsentRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    updated = await dictation_consent.apply_decision(plans)
    if (
        dictation_consent.DictationConsent(payload.decision)
        is dictation_consent.DictationConsent.REFUSED
    ):
        await dictation_consent.cancel_pending_transcriptions(workshop_id)
    summary = workshop_summary(updated)
    summary["dictationConsentByName"] = await dictation_consent.actor_name(
        getattr(updated, "dictationConsentById", None)
    )
    return {
        "workshop": summary,
        "decisions": [
            dictation_consent.decision_payload(row)
            for row in await dictation_consent.workshop_decisions(workshop_id)
        ],
    }



async def _attach_deleted_by(rows: list[Any], items: list[dict[str, Any]]) -> None:
    wanted = {actor for r in rows if (actor := getattr(r, "deletedById", None))}
    names: dict[str, Any] = {}
    if wanted:
        accounts = await db.user.find_many(where={"id": {"in": sorted(wanted)}})
        names = {account.id: getattr(account, "name", None) for account in accounts}
    for row, item in zip(rows, items, strict=True):
        actor = getattr(row, "deletedById", None)
        item["deletedById"] = actor
        item["deletedByName"] = names.get(actor) if actor else None


@router.get("")
async def list_design_workshops(
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
    search: str | None = None,
    statusFilter: str | None = None,
    workshopKind: str | None = None,
    craftName: str | None = None,
    craftId: str | None = None,
    state: str | None = None,
    mineOnly: bool = False,
    includeDeleted: bool = False,
    deletedOnly: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    if (includeDeleted or deletedOnly) and not is_content_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin access required")
    where: dict[str, Any] = {}
    if deletedOnly:
        where["deletedAt"] = {"not": None}
    elif not includeDeleted:
        where["deletedAt"] = None
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, DESIGN_WORKSHOP_STATUSES)
    if workshopKind:
        where["workshopKind"] = enum_filter_or_422(workshopKind, WORKSHOP_KINDS)
    if craftName:
        where["craftName"] = contains(craftName)
    if craftId:
        where["craftId"] = craftId
    if state:
        where["state"] = plain(state)
    if search:
        where["OR"] = [
            {"title": contains(search)},
            {"craftName": contains(search)},
            {"clusterName": contains(search)},
            {"workshopCode": contains(search)},
        ]
    if mineOnly:
        where["createdById"] = current_user.id
    elif not is_content_admin(current_user):
        where.setdefault("AND", []).append(visible_to_clause(current_user.id))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    order = {"deletedAt": "desc"} if deletedOnly else {"createdAt": "desc"}
    total, rows = await asyncio.gather(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            order=with_id_tiebreak(order),
        ),
    )
    items = [workshop_summary(r) for r in rows]
    if includeDeleted or deletedOnly:
        await _attach_deleted_by(rows, items)
    return page_payload(items, total, clean_page, clean_size)


@router.get("/default-for-me")
async def default_design_workshop_for_me(
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    grant, own = await asyncio.gather(
        db.designworkshopviewer.find_first(
            where={"userId": current_user.id, "designWorkshop": {"is": {"deletedAt": None}}},
            order={"createdAt": "desc"},
            include={"designWorkshop": True},
        ),
        db.designworkshop.find_first(
            where={"createdById": current_user.id, "deletedAt": None},
            order={"createdAt": "desc"},
        ),
    )

    candidates: list[tuple[datetime, Any, str]] = []
    if grant is not None and getattr(grant, "designWorkshop", None) is not None:
        candidates.append((grant.createdAt, grant.designWorkshop, "GRANTED"))
    if own is not None:
        candidates.append((own.createdAt, own, "CREATED"))
    if not candidates:
        return {"workshopId": None, "title": None, "accessAt": None, "reason": None}

    accessed_at, workshop, reason = max(candidates, key=lambda row: row[0])
    return {
        "workshopId": workshop.id,
        "title": workshop.title,
        "accessAt": accessed_at.isoformat() if accessed_at is not None else None,
        "reason": reason,
    }


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_design_workshop(
    payload: DesignWorkshopCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    assert_can_create_design_workshops(current_user)
    designer_id, designer_ids = named_designer_team(payload.designerUserId, payload.designerUserIds)
    wanted = set(designer_ids) - {current_user.id}
    if wanted:
        await assert_every_designer_may_be_named(wanted)
    data: dict[str, Any] = {
        "title": payload.title.strip(),
        "templateId": payload.templateId,
        "createdById": current_user.id,
        "schemaVersion": registry_version(),
        "status": "DRAFT",
    }
    for key in _CREATE_OPTIONAL_COLUMNS:
        value = getattr(payload, key)
        if value:
            data[key] = value
    for key in ("startDate", "endDate"):
        parsed = _parse_date(getattr(payload, key))
        if parsed:
            data[key] = parsed
    data.update(
        await craft_master.craft_columns(db, payload.model_dump(include={"craftId", "craftName"}, exclude_none=True))
    )
    data = {key: value for key, value in data.items() if value is not None}

    record = await db.designworkshop.create(data=data)
    if designer_ids:
        await attach_the_named_designers(
            record.id,
            designer_ids,
            granted_by_id=current_user.id,
            creator_id=record.createdById,
        )
    seeded = {
        key: value
        for key, value in (
            ("workshopKind", payload.workshopKind),
            ("craftRef", data.get("craftId")),
            ("craftName", data.get("craftName")),
            ("clusterName", payload.clusterName),
            ("state", payload.state),
            ("district", payload.district),
            ("startDate", payload.startDate),
            ("endDate", payload.endDate),
        )
        if value
    }
    record = await seed_designer_prefill(
        record, current_user, designer_id=designer_id, extra=seeded
    )
    return workshop_summary(record)


@router.get("/{workshop_id}")
async def get_design_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    record = await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_custom_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["transcripts"] = await _transcripts_payload(entries, current_user)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    summary["dictationConsentByName"] = await dictation_consent.actor_name(
        getattr(record, "dictationConsentById", None)
    )
    summary.update(await design_workshop_approvals.approval_names(record))
    summary["decisions"] = await design_workshop_approvals.decision_history(record.id)
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = (
        await _inspection_feedback_payload(workshop_id)
    )
    summary["mayRecordFeedback"] = False
    summary.update(await _register_provenance(record.id, current_user))
    return summary


async def _register_provenance(workshop_id: str, user: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {}
    if can_record_sanction_orders(user):
        order = await db.sanctionorder.find_unique(where={"designWorkshopId": workshop_id})
        payload["sanctionOrder"] = (
            None
            if order is None
            else {
                "sanctionOrderNo": order.sanctionOrderNo,
                "sanctionOrderDate": (
                    order.sanctionOrderDate.date().isoformat()
                    if isinstance(order.sanctionOrderDate, datetime)
                    else None
                ),
            }
        )
    return payload


async def _inspection_feedback_payload(workshop_id: str) -> tuple[list[dict[str, Any]], bool]:
    limit = design_workshop_review_loop.DESIGN_WORKSHOP_FEEDBACK_READ_LIMIT
    rows = await db.dwinspectionfeedback.find_many(
        where={"designWorkshopId": workshop_id},
        order={"createdAt": "desc"},
        take=limit + 1,
        include={"actor": True},
    )
    return (
        [design_workshop_review_loop.feedback_payload(row) for row in rows[:limit]],
        len(rows) > limit,
    )


@router.patch("/{workshop_id}")
async def update_design_workshop(
    workshop_id: str,
    payload: DesignWorkshopPatch,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    data = _header_patch_data(payload.model_dump(exclude_unset=True))
    data.update(
        await craft_master.craft_columns(
            db, payload.model_dump(exclude_unset=True, include={"craftId", "craftName"}), stored=record
        )
    )
    if not data:
        return workshop_summary(record)
    if "status" in data:
        current_status = str(getattr(record, "status", "") or "")
        refusal = design_workshop_review_loop.handed_on_refusal(
            current_status,
            str(data["status"]),
            handed_on=getattr(record, "handedOnAt", None) is not None,
        ) or design_workshop_review_loop.transition_refusal(current_status, str(data["status"]))
        if refusal:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal)
        if data["status"] == design_workshop_review_loop.PRE_SUBMISSION:
            data.update(design_workshop_review_loop.presubmission_header(current_status))
    if data.get("workshopId") is not None:
        await _assert_linked_workshop_exists(data["workshopId"])
    link = {k: data.pop(k) for k in ("workshopId",) if k in data}
    async with db.tx() as tx:
        written = await tx.designworkshop.update_many(
            where={"id": workshop_id, "NOT": dict(design_workshop_approvals_schema.FROZEN_WHERE)},
            data=data or {"schemaVersion": record.schemaVersion},
        )
        if written == 1 and link:
            await tx.designworkshop.update(where={"id": workshop_id}, data=link)
    updated = await db.designworkshop.find_unique(where={"id": workshop_id})
    if written != 1:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=design_workshop_approvals_schema.frozen_refusal(
                getattr(updated, "status", None), getattr(updated, "handedOnAt", None)
            )
            or design_workshop_approvals_schema.FROZEN_APPROVED,
        )
    if data.get("status") == design_workshop_review_loop.PRE_SUBMISSION:
        from app.services.design_workshop_review_aids import capture_round_snapshot

        await capture_round_snapshot(workshop_id)
    await _notify_if_handed_in(record, updated, current_user)
    return workshop_summary(updated)


async def _notify_if_handed_in(before: Any, after: Any, actor: Any) -> None:
    old_round = int(getattr(before, "submissionRound", 0) or 0)
    new_round = int(getattr(after, "submissionRound", 0) or 0) if after is not None else old_round
    if new_round > old_round:
        await email_outbox.notify_handed_in(after, actor=actor, resubmission=old_round > 0)


@router.delete("/{workshop_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_design_workshop(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> None:
    assert_can_delete(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    await db.designworkshop.update(
        where={"id": workshop_id},
        data={"deletedAt": datetime.now(UTC), "deletedById": current_user.id},
    )


@router.post("/{workshop_id}/restore")
async def restore_design_workshop(
    workshop_id: str, _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    restored = await db.designworkshop.update(
        where={"id": workshop_id}, data={"deletedAt": None, "deletedById": None}
    )
    return workshop_summary(restored)



@router.get("/{workshop_id}/stages")
async def list_stages(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_custom_definition_or_empty(workshop_id)
    payload = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(payload))
    return {
        "stages": payload,
        "completeness": workshop_completeness(entries, definition=definition),
        "transcripts": await _transcripts_payload(entries, current_user),
        "schemaVersion": registry_version(),
        "customSchemaVersion": definition.version,
    }


@router.get("/{workshop_id}/stages/{stage_key}")
async def get_stage(
    workshop_id: str, stage_key: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")
    entries, definition = await gather_reads(
        entry_rows(workshop_id, stage_key=stage_key),
        load_custom_definition_or_empty(workshop_id),
    )
    payload = _stages_payload(entries).get(stage_key) or {
        "singleton": {},
        "collections": {},
        "custom": {},
        "provenance": {"singleton": {}, "collections": {}, "custom": {}},
    }
    _, transcripts = await gather_reads(
        resolve_display_names(_provenance_maps({stage_key: payload})),
        _transcripts_payload(entries, current_user),
    )
    payload["completeness"] = workshop_completeness(entries, definition=definition).get(stage_key)
    payload["transcripts"] = transcripts
    payload["customSchemaVersion"] = definition.version
    return payload


@router.get("/{workshop_id}/stages/{stage_key}/assist")
async def get_stage_assist(
    workshop_id: str, stage_key: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    from app.services.stage_assist import load_assist

    record = await load_workshop_or_404(workshop_id, current_user)
    if not any(s.key == stage_key for s in stages()):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")
    return await load_assist(record, stage_key)


@router.get("/{workshop_id}/designer-prefill")
async def get_designer_prefill(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    from app.services.design_workshop_oversight import designer_prefill_for_workshop

    record = await load_workshop_or_404(workshop_id, current_user)
    designer_id, values = await designer_prefill_for_workshop(record)
    return {
        "designerId": designer_id,
        "values": {key: value for key, value in values.items() if value not in (None, "", [])},
    }


@router.get("/{workshop_id}/provenance")
async def workshop_provenance(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    if not is_content_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Field-by-field provenance across the shared records is an admin view. "
                "Ask a master admin if you need it for an audit."
            ),
        )
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    stamps = resolve_entry_provenance(entries)
    await resolve_display_names(stamps.values())
    divergence = await canonical_divergence(entries)
    return {
        "entries": [
            {
                "entryId": row.id,
                "stageKey": row.stageKey,
                "entityKey": row.entityKey,
                "ordinal": row.ordinal,
                "createdById": row.createdById,
                "fields": stamps.get(row.id, {}),
                "canonical": divergence.get(row.id, {}),
            }
            for row in entries
        ],
    }


def _submit_refusal_message(errors: Mapping[str, Any]) -> str:
    messages = [
        str(message)
        for fields in errors.values()
        for message in (fields.values() if isinstance(fields, Mapping) else [fields])
    ]
    required = any(" is required" in m for m in messages)
    unreadable = any(" is required" not in m for m in messages)
    if required and unreadable:
        return "Some required fields are missing, and some answers could not be read"
    if unreadable:
        return "Some answers could not be read"
    return "Some required fields are missing"


@router.put("/{workshop_id}/stages/{stage_key}")
async def save_stage_data(
    workshop_id: str,
    stage_key: str,
    payload: StageSaveIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    before = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")

    result = await save_stage(workshop_id, spec, payload, current_user, resubmits=True)
    if str(getattr(before, "status", "") or "").endswith("NEEDS_REVISION"):
        await _notify_if_handed_in(
            before, await db.designworkshop.find_unique(where={"id": workshop_id}), current_user
        )
    if result["errors"] and payload.submit:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={**result, "message": _submit_refusal_message(result["errors"])},
        )
    return result



def _spec_from_body(section: Any) -> CustomSectionSpec:
    from app.services.stage_schema import FieldType, Tier

    fields: list[CustomFieldSpec] = []
    for index, f in enumerate(section.fields):
        try:
            field_type = FieldType(f.type)
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "message": "This section can't be saved",
                    "problems": [
                        f"Field {f.key!r}: a custom question can't be of type {f.type!r}. "
                        f"Choose one of: "
                        + ", ".join(sorted(t.value for t in V1_FIELD_TYPES))
                    ],
                },
            ) from None
        try:
            tier = Tier(f.tier)
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail={
                    "message": "This section can't be saved",
                    "problems": [
                        f"Field {f.key!r} names the tier {f.tier!r}. Choose BASIC, STANDARD or "
                        f"ADVANCED — only a BASIC field may be required."
                    ],
                },
            ) from None
        fields.append(
            CustomFieldSpec(
                key=f.key,
                label=f.label,
                type=field_type,
                tier=tier,
                required=f.required,
                help=f.help,
                unit=f.unit,
                options=tuple(CustomOption(value=o.value, label=o.label) for o in f.options),
                max_length=f.maxLength,
                min_value=f.minValue,
                max_value=f.maxValue,
                sort_order=f.sortOrder or index,
            )
        )
    return CustomSectionSpec(
        key=section.key,
        title=section.title,
        stage_key=section.stageKey,
        description=section.description,
        sort_order=section.sortOrder,
        fields=tuple(fields),
    )


@router.get("/{workshop_id}/custom-sections")
async def get_custom_sections(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    return definition_payload(await load_definition(workshop_id))


@router.put("/{workshop_id}/custom-sections")
async def save_custom_sections(
    workshop_id: str,
    payload: CustomSectionsIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)

    incoming = [_spec_from_body(section) for section in payload.sections]
    problems = validate_definition(incoming)
    if problems:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"message": "These questions can't be saved", "problems": problems},
        )

    stored = await load_definition(workshop_id)

    if payload.customSchemaVersion is not None and payload.customSchemaVersion != stored.version:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "message": (
                    "These questions were changed by someone else while this page was open. "
                    "Reload to see the current version before saving."
                ),
                "code": "custom_sections_conflict",
                "expected": payload.customSchemaVersion,
                "actual": stored.version,
            },
        )

    entries = await entry_rows(workshop_id)
    answered = {
        row.stageKey: answered_keys(stored.fields_for(row.stageKey), dict(row.data or {}))
        for row in entries
        if row.entityKey == CUSTOM_ENTITY_KEY
    }

    try:
        plan = plan_definition(stored.sections, incoming, answered)
    except CustomSectionEditError as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    await apply_definition_plan(plan, workshop_id, actor_id=current_user.id)

    saved = await load_definition(workshop_id)
    return definition_payload(saved) | {
        "created": plan.created,
        "superseded": plan.superseded,
        "retired": plan.retired,
        "removed": plan.deleted,
    }



@router.get("/{workshop_id}/references")
async def list_references(
    workshop_id: str,
    model: str = Query(min_length=1, max_length=64),
    scope: str = Query(REF_SCOPE_ALL, max_length=16),
    filterBy: str | None = Query(None, max_length=64),
    search: str | None = Query(None, max_length=120),
    recordId: str | None = Query(None, max_length=64),
    limit: int = Query(REFERENCE_LIMIT_DEFAULT, ge=1, le=REFERENCE_LIMIT_MAX),
    offset: int = Query(0, ge=0, le=REFERENCE_OFFSET_MAX),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await load_workshop_or_404(workshop_id, current_user)
    return await reference_options(
        record,
        model,
        scope=scope.upper(),
        filter_by=filterBy,
        search=search,
        limit=limit,
        offset=offset,
        record_id=recordId,
        viewer=current_user,
    )


@router.get("/{workshop_id}/transcripts")
async def list_workshop_transcripts(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    items = await load_transcript_items(entries, viewer=current_user)
    payloads = []
    for item in items:
        payload = item.payload()
        payload["includedInReport"] = item.has_text
        payloads.append(payload)
    return {
        "items": payloads,
        "total": len(payloads),
        "withTranscript": sum(1 for item in items if item.has_text),
        "totalDurationSeconds": sum(item.duration_seconds or 0 for item in items) or None,
    }



async def _layer_or_404(workshop_id: str, layer_id: str) -> Any:
    row = await ai_layers.layer_in_workshop(workshop_id, layer_id)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That layer is not in this workshop's list any more — it may have been declined by "
                "somebody else while this screen was open. Reload the workshop's layers; a declined "
                "layer stays on record and can be registered again if the material is still wanted."
            ),
        )
    return row


@router.get("/{workshop_id}/ai-layers")
async def list_ai_layers(
    workshop_id: str,
    kind: str | None = Query(None, max_length=32),
    includeText: bool = Query(
        False,
        description=(
            "Carry each layer's full text. Off by default: a workshop can hold twenty-five "
            "interviews and an hour of speech is tens of kilobytes, so the list would be megabytes "
            "on one bar of signal — and unread, because a list shows titles. A row whose text you "
            "may read carries `preview` and `textChars` either way, which is what a list is scanned "
            "by; ask for the text when showing ONE layer to the person about to put their name to "
            "it. A row marked `textWithheld` carries neither, and this flag does not change that: "
            "the recording it stands on is not one this account may read."
        ),
    ),
    includeDeleted: bool = Query(
        False,
        description=(
            "Include layers a designer declined. They are soft-deleted and kept, so 'the model "
            "proposed this and a person said no' stays answerable — but they are out of the default "
            "list, because a declined suggestion re-offered is the same suggestion."
        ),
    ),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    wanted: ai_layers.LayerKind | None = None
    if kind:
        try:
            wanted = ai_layers.LayerKind(kind.strip().upper())
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    "That is not a layer kind. Ask for one of: "
                    f"{', '.join(k.value for k in ai_layers.LayerKind)}."
                ),
            ) from None
    everything = await ai_layers.workshop_layers(workshop_id, include_deleted=True)
    roots = ai_layers.chain_roots(everything)
    readable = await _readable_media_ids(ai_layers.media_ids_to_check(roots), current_user)
    rows = [
        row
        for row in everything
        if (includeDeleted or row.deletedAt is None)
        and (wanted is None or ai_layers.is_kind(row, wanted))
    ]
    return {
        "items": [
            ai_layers.layer_payload(
                row,
                include_text=includeText,
                text_withheld=_root_withheld(roots.get(row.id), readable),
            )
            for row in rows
        ],
        "total": len(rows),
        "accepted": sum(1 for row in rows if row.acceptedAt is not None and row.deletedAt is None),
    }


@router.get("/{workshop_id}/ai-layers/decisions")
async def list_ai_layer_decisions(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    layers = await ai_layers.workshop_layers(workshop_id, include_deleted=True)
    decisions = await ai_layers.decisions_for_layers([row.id for row in layers])
    items = await ai_layers.with_actor_names(ai_layers.history_entries(layers, decisions))
    return {"items": items, "total": len(items)}


@router.get("/{workshop_id}/ai-layers/{layer_id}")
async def read_ai_layer(
    workshop_id: str, layer_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    everything = await ai_layers.workshop_layers(workshop_id, include_deleted=True)
    row = next((layer for layer in everything if layer.id == layer_id), None)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="That layer is not one of this workshop's. Reload the workshop's layers.",
        )
    root = ai_layers.chain_roots(everything).get(row.id)
    wanted = (
        {root.media_id}
        if root is not None and root.kind is ai_layers.RootKind.MEDIA and root.media_id
        else set()
    )
    withheld = _root_withheld(root, await _readable_media_ids(wanted, current_user))
    decisions = await ai_layers.decisions_for_layers([row.id])
    history = await ai_layers.with_actor_names(ai_layers.history_entries([row], decisions))
    return {
        "layer": ai_layers.layer_payload(row, include_text=True, text_withheld=withheld),
        "decisions": history,
    }


def _root_withheld(root: Any, readable: set[str]) -> bool:
    return True if root is None else root.withheld_from(readable)


async def _readable_media_ids(media_ids: set[str], viewer: Any) -> set[str]:
    if not media_ids:
        return set()
    where: dict[str, Any] = {"id": {"in": sorted(media_ids)}}
    entitled = await owned_or_granted_where(viewer, owner_field="uploadedById")
    if entitled:
        where = {"AND": [where, entitled]}
    return {row.id for row in await db.mediafile.find_many(where=where)}


@router.post("/{workshop_id}/ai-layers", status_code=status.HTTP_201_CREATED)
async def register_ai_layer(
    workshop_id: str,
    payload: AiLayerRegisterIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)

    entries = await entry_rows(workshop_id)
    items = await load_transcript_items(entries, viewer=current_user)
    item = next((i for i in items if i.media_id == payload.sourceMediaId), None)
    if item is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That recording is not one of this workshop's. Attach it to an audio field in a "
                "stage first, and check it was uploaded by somebody whose media you can read."
            ),
        )
    if not item.has_text:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"This recording has no transcript yet — its status is "
                f"{item.status or 'not recorded'}. There is nothing to register until the "
                f"transcription finishes; the transcripts screen shows where it is up to."
            ),
        )

    media = await db.mediafile.find_unique(where={"id": item.media_id})
    try:
        raw_text, cleaned_text = ai_layers.transcript_rungs(
            transcript_text=item.text,
            transcript_summary=getattr(media, "transcriptSummary", None),
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc

    provider = (payload.provider or "").strip() or ai_layers.UNRECORDED
    model_id = (payload.modelId or "").strip() or ai_layers.UNRECORDED
    source = ai_layers.LayerSource.media(item.media_id)

    existing = await ai_layers.layers_from_media(workshop_id, item.media_id)
    duplicate = ai_layers.duplicate_of(
        existing,
        source=source,
        kind=ai_layers.LayerKind.RAW_TRANSCRIPT,
        tier=ai_layers.AiTier.TIER_3,
        provider=provider,
        model_id=model_id,
    )
    if duplicate is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "This recording's transcript is already in the list, made by the same model. "
                "Delete that one first if it is wrong."
            ),
        )

    produced_at = _parse_datetime(payload.producedAt)
    try:
        raw_plan = ai_layers.layer_create_plan(
            workshop_id=workshop_id,
            kind=ai_layers.LayerKind.RAW_TRANSCRIPT,
            tier=ai_layers.AiTier.TIER_3,
            source=source,
            provider=provider,
            model_id=model_id,
            model_version=payload.modelVersion,
            language=payload.language,
            produced_at=produced_at,
            text=raw_text,
            created_by_id=current_user.id,
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    raw_row = await ai_layers.apply_plan(raw_plan)
    rows = [raw_row]

    if cleaned_text is not None:
        cleaned_plan = ai_layers.layer_create_plan(
            workshop_id=workshop_id,
            kind=ai_layers.LayerKind.CLEANED_TRANSCRIPT,
            tier=ai_layers.AiTier.TIER_3,
            source=ai_layers.LayerSource.layer(raw_row.id, ai_layers.LayerKind.RAW_TRANSCRIPT),
            provider=ai_layers.UNRECORDED,
            model_id=ai_layers.UNRECORDED,
            text=cleaned_text,
            created_by_id=current_user.id,
        )
        rows.append(await ai_layers.apply_plan(cleaned_plan))

    return {
        "items": [ai_layers.layer_payload(row) for row in rows],
        "total": len(rows),
    }



async def _verb_gate(workshop_id: str, current_user: Any, verb: ai_verbs.Verb) -> Any:
    _require_designer(current_user)
    record = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    await require_send(
        dictation_consent.workshop_verdict(
            workshop_id, send=dictation_consent.send_for(verb.value), record=record
        )
    )
    allowance = await ai_verb_cap.load_allowance(current_user.id)
    spent = ai_verb_cap.cap_refusal(allowance)
    if spent is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=spent,
        )
    return allowance


async def _verb_media_gate(workshop_id: str, media: Any, verb: ai_verbs.Verb) -> None:
    await require_send(
        dictation_consent.transcription_verdict(
            media, design_workshop_id=workshop_id, send=dictation_consent.send_for(verb.value)
        )
    )


async def _layer_root_verdict(workshop_id: str, row: Any, verb: ai_verbs.Verb) -> Any:
    seen: set[str] = set()
    while row is not None and str(getattr(row, "id", "")) not in seen:
        seen.add(str(getattr(row, "id", "")))
        media_id = str(getattr(row, "sourceMediaId", "") or "").strip()
        if media_id:
            media = await db.mediafile.find_unique(where={"id": media_id})
            if media is None:
                break
            return await dictation_consent.transcription_verdict(
                media, design_workshop_id=workshop_id, send=dictation_consent.send_for(verb.value)
            )
        parent = str(getattr(row, "sourceLayerId", "") or "").strip()
        row = await db.dwailayer.find_unique(where={"id": parent}) if parent else None
    return dictation_consent.SendVerdict(dictation_consent.SendDecision.NOT_WORKSHOP_MATERIAL)


async def _verb_layer_gate(workshop_id: str, row: Any, verb: ai_verbs.Verb) -> None:
    await require_send(_layer_root_verdict(workshop_id, row, verb))


async def _verb_source_layer(workshop_id: str, layer_id: str, current_user: Any) -> Any:
    row = await _layer_or_404(workshop_id, layer_id)
    if await _layer_text_withheld(workshop_id, row, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "You can't open the recording this text was made from, so it can't be worked on. "
                "Ask whoever uploaded the recording for access to their media."
            ),
        )
    text = str(getattr(row, "text", "") or "").strip()
    if not text:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That layer holds structured data rather than prose, so there is no passage to work "
                "on. Choose a transcript, a summary or another text layer."
            ),
        )
    return row


def _verb_layer_kind(row: Any) -> Any:
    raw = getattr(row, "kind", None)
    token = str(getattr(raw, "value", raw) or "")
    try:
        return ai_layers.LayerKind(token)
    except ValueError as exc:
        raise ai_verbs.VerbError(
            "That text can't be worked on here. Reload the page to update the app, or choose "
            "another."
        ) from exc


_VERB_MEDIA_TYPES: dict[str, tuple[tuple[str, ...], str]] = {
    "CAPTION": (("IMAGE", "VIDEO"), "a photograph or a video"),
    "SUBTITLES": (("AUDIO", "VIDEO"), "a recording or a video"),
}

MAX_CAPTION_BYTES = 32 * 1024 * 1024
MAX_SUBTITLE_FETCH_BYTES = 1024 * 1024 * 1024


async def _refuse_oversize_verb_source(media: Any, ceiling: int, *, what: str) -> None:
    declared = int(getattr(media, "sizeBytes", 0) or 0)
    real = None
    if declared <= ceiling:
        head = await asyncio.to_thread(head_object, media.objectKey)
        real = head.size_bytes if head is not None else None
        if real is None or real <= ceiling:
            return
    size = real if real is not None else declared
    raise HTTPException(
        status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
        detail=(
            f"That file is {size} bytes, over the {ceiling}-byte limit for files to {what}. "
            f"Nothing was sent anywhere and nothing was spent."
        ),
    )


async def _verb_source_media(
    workshop_id: str, media_id: str, current_user: Any, *, verb: ai_verbs.Verb
) -> Any:
    entries = await entry_rows(workshop_id)
    attached = workshop_media_ids(entries)
    row = None
    if media_id in attached:
        readable = await _readable_media_ids({media_id}, current_user)
        if media_id in readable:
            row = await db.mediafile.find_unique(where={"id": media_id})
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "That file is not one of this workshop's. Attach it to a field in a stage first, "
                "and check it was uploaded by somebody whose media you can read."
            ),
        )
    wanted, in_words = _VERB_MEDIA_TYPES[verb.value]
    stored = str(getattr(row, "mediaType", "") or "").upper()
    if not any(stored.endswith(token) for token in wanted):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"{verb.human.capitalize()} needs {in_words}, and that file is "
                f"{stored.lower() or 'of an unknown kind'}. Choose another file "
                f"— nothing was sent anywhere and nothing was spent."
            ),
        )
    return row


async def _finish_verb(
    *,
    plan: Any,
    allowance: Any,
    verb: ai_verbs.Verb,
    current_user: Any,
) -> dict[str, Any]:
    row = await ai_layers.apply_plan(plan)
    await ai_verb_cap.spend(current_user.id, allowance.day, verb.value)
    refreshed = await ai_verb_cap.load_allowance(current_user.id)
    return {
        "layer": ai_layers.layer_payload(row, include_text=True),
        "accepted": False,
        "acceptanceRequired": True,
        **ai_verb_cap.allowance_payload(refreshed),
    }


async def _count_refused_run(
    answer: Any,
    *,
    allowance: Any,
    verb: ai_verbs.Verb,
    current_user: Any,
) -> None:
    if not isinstance(answer, Mapping):
        return
    if str(answer.get("status") or "").upper() == "UNAVAILABLE" or answer.get("available") is False:
        return
    await ai_verb_cap.spend(current_user.id, allowance.day, verb.value)


def _verb_http(exc: Exception) -> HTTPException:
    if isinstance(exc, ai_verbs.VerbUnavailable):
        return HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc))
    return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc))


@router.post("/{workshop_id}/ai-layers/proofread", status_code=status.HTTP_201_CREATED)
async def proofread_ai_layer(
    workshop_id: str,
    payload: AiProofreadIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.PROOFREAD)
    answer: Mapping[str, Any] | None = None
    try:
        language = ai_verbs.clean_language(payload.language, what="the passage")
        if payload.sourceLayerId:
            row = await _verb_source_layer(workshop_id, payload.sourceLayerId, current_user)
            await _verb_layer_gate(workshop_id, row, ai_verbs.Verb.PROOFREAD)
            text = str(getattr(row, "text", "") or "")
            source = ai_layers.LayerSource.layer(row.id, _verb_layer_kind(row))
            language = str(getattr(row, "language", "") or "").strip() or language
        else:
            text = payload.text or ""
            source = ai_layers.LayerSource.supplied_text(text)
        ai_layers.check_placement(ai_layers.LayerKind.PROOFREAD, source)
        answer = await ai.proofread_text(text, get_settings(), user_id=current_user.id)
        plan = ai_verbs.proofread(
            workshop_id=workshop_id,
            source=source,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            language=language,
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer,
            allowance=allowance,
            verb=ai_verbs.Verb.PROOFREAD,
            current_user=current_user,
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.PROOFREAD,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/expand", status_code=status.HTTP_201_CREATED)
async def expand_ai_layer(
    workshop_id: str,
    payload: AiExpandIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.EXPAND)
    answer: Mapping[str, Any] | None = None
    try:
        language = ai_verbs.clean_language(payload.language, what="the note")
        answer = await ai.expand_text(payload.text, get_settings(), user_id=current_user.id)
        plan = ai_verbs.expand(
            workshop_id=workshop_id,
            note=payload.text,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            language=language,
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.EXPAND, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.EXPAND,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/translate", status_code=status.HTTP_201_CREATED)
async def translate_ai_layer(
    workshop_id: str,
    payload: AiTranslateIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.TRANSLATE)
    answer: Mapping[str, Any] | None = None
    try:
        target = ai_verbs.clean_language(payload.targetLanguage, what="the target language")
        stated_source = ai_verbs.clean_language(payload.sourceLanguage, what="the source language")
        if payload.sourceLayerId:
            row = await _verb_source_layer(workshop_id, payload.sourceLayerId, current_user)
            await _verb_layer_gate(workshop_id, row, ai_verbs.Verb.TRANSLATE)
            text = str(getattr(row, "text", "") or "")
            source = ai_layers.LayerSource.layer(row.id, _verb_layer_kind(row))
            source_language = str(getattr(row, "language", "") or "").strip() or stated_source
        else:
            text = payload.text or ""
            source = ai_layers.LayerSource.supplied_text(text)
            source_language = stated_source
        ai_layers.check_placement(ai_layers.LayerKind.TRANSLATION, source)
        answer = await ai.translate_text(
            text,
            target_language=target or "",
            source_language=source_language,
            settings=get_settings(),
            user_id=current_user.id,
        )
        plan = ai_verbs.translate(
            workshop_id=workshop_id,
            source=source,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            source_language=source_language or ai_layers.UNRECORDED,
            target_language=target or "",
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.TRANSLATE, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.TRANSLATE,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/on-device", status_code=status.HTTP_201_CREATED)
async def record_on_device_ai_layer(
    workshop_id: str,
    payload: AiOnDeviceLayerIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    try:
        language = ai_verbs.clean_language(payload.language, what="the layer") or ""
        plan = ai_verbs.on_device(
            workshop_id=workshop_id,
            kind=ai_layers.LayerKind(payload.kind),
            text=payload.text,
            source_text=payload.sourceText,
            model_id=payload.modelId,
            model_version=payload.modelVersion,
            language=language,
            produced_at=datetime.fromisoformat(payload.producedAt),
            created_by_id=current_user.id,
        )
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        raise _verb_http(exc) from exc
    row = await ai_layers.apply_plan(plan)
    return {
        "layer": ai_layers.layer_payload(row, include_text=True),
        "accepted": False,
        "acceptanceRequired": True,
    }


@router.post("/{workshop_id}/ai-layers/caption", status_code=status.HTTP_201_CREATED)
async def caption_ai_layer(
    workshop_id: str,
    payload: AiMediaVerbIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.CAPTION)
    media = await _verb_source_media(
        workshop_id, payload.sourceMediaId, current_user, verb=ai_verbs.Verb.CAPTION
    )
    await _verb_media_gate(workshop_id, media, ai_verbs.Verb.CAPTION)
    ceiling = budget_bytes(MAX_CAPTION_BYTES)
    await _refuse_oversize_verb_source(media, ceiling, what="caption")
    answer: Mapping[str, Any] | None = None
    try:
        language = ai_verbs.clean_language(payload.language, what="the caption")
        if language and language.lower() == "multi":
            language = None
        content = await asyncio.to_thread(
            get_object_bytes, media.objectKey, max_bytes=ceiling
        )
        answer = await ai.caption_image_bytes(
            content,
            str(getattr(media, "mimeType", "") or "image/jpeg"),
            get_settings(),
            language,
            user_id=current_user.id,
        )
        plan = ai_verbs.caption(
            workshop_id=workshop_id,
            media_id=media.id,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            language=language,
            created_by_id=current_user.id,
        )
    except ObjectTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"That file is {exc.size_bytes} bytes, over the {exc.limit_bytes}-byte limit for "
                f"files to caption. Nothing was sent anywhere and nothing was spent."
            ),
        ) from exc
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.CAPTION, current_user=current_user
        )
        raise _verb_http(exc) from exc
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.CAPTION,
        current_user=current_user,
    )


@router.post("/{workshop_id}/ai-layers/subtitles", status_code=status.HTTP_201_CREATED)
async def subtitle_ai_layer(
    workshop_id: str,
    payload: AiMediaVerbIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    allowance = await _verb_gate(workshop_id, current_user, ai_verbs.Verb.SUBTITLES)
    media = await _verb_source_media(
        workshop_id, payload.sourceMediaId, current_user, verb=ai_verbs.Verb.SUBTITLES
    )
    await _verb_media_gate(workshop_id, media, ai_verbs.Verb.SUBTITLES)
    await _refuse_oversize_verb_source(media, MAX_SUBTITLE_FETCH_BYTES, what="subtitle")
    if await app_settings.load_transcription_paused():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=PAUSED_MESSAGE)
    answer: Mapping[str, Any] | None = None
    source_path: str | None = None
    try:
        source_path = await workdir.owned(
            download_to_temp, media.objectKey, max_bytes=MAX_SUBTITLE_FETCH_BYTES
        )
        answer = await ai.transcribe_timed_bytes(
            None,
            str(getattr(media, "originalFilename", "") or "recording.webm"),
            str(getattr(media, "mimeType", "") or "audio/webm"),
            get_settings(),
            source_path=source_path,
        )
        plan = ai_verbs.subtitle(
            workshop_id=workshop_id,
            media_id=media.id,
            answer=answer,
            run=ai_verbs.VerbRun.of_answer(answer, tier=_SERVER_TIER, at=datetime.now(UTC)),
            created_by_id=current_user.id,
        )
    except ObjectTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"That file is {exc.size_bytes} bytes, over the {exc.limit_bytes}-byte limit for "
                f"files to subtitle. Nothing was sent anywhere and nothing was spent."
            ),
        ) from exc
    except (ai_verbs.VerbError, ai_layers.LayerRuleViolation) as exc:
        await _count_refused_run(
            answer, allowance=allowance, verb=ai_verbs.Verb.SUBTITLES, current_user=current_user
        )
        raise _verb_http(exc) from exc
    finally:
        discard_temp(source_path)
    return await _finish_verb(
        plan=plan,
        allowance=allowance,
        verb=ai_verbs.Verb.SUBTITLES,
        current_user=current_user,
    )


@router.get("/{workshop_id}/ai-layers/{layer_id}/subtitles.{fmt}")
async def download_subtitles(
    workshop_id: str,
    layer_id: str,
    fmt: str,
    speakers: bool = Query(
        False,
        description=(
            "Put the speaker label in front of each line — `Speaker 1: With gum and clay`. **Off by "
            "default**, because a label costs characters out of a hard two-line caption budget and "
            "because every client written before this flag existed expects a file without them. Ask "
            "for it when the recording is a group sitting: this archive's run to five artisans plus "
            "an interviewer, and a subtitle with no labels attributes every voice to whoever happens "
            "to be on camera. **The labels are a MODEL'S GUESS.** Nobody told the engine how many "
            "people were in the room or who they were — it decided both from the audio, and it can "
            "merge two quiet voices or split one person who moved away from the microphone. The "
            ".vtt carries that caution inside the file as a WebVTT `NOTE`; SubRip has no comment "
            "syntax and cannot, so a .srt carries the labels alone. A layer whose cues hold no "
            "labels at all refuses this flag rather than serving the identical unlabelled file."
        ),
    ),
    current_user: Any = Depends(get_current_user),
) -> Response:
    record = await load_workshop_or_404(workshop_id, current_user)
    row = await _layer_or_404(workshop_id, layer_id)
    if await _layer_text_withheld(workshop_id, row, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "You cannot read the recording these subtitles were made from, and a cue list is "
                "its transcript with times on it. Ask whoever uploaded the recording for access to "
                "their media."
            ),
        )
    try:
        body, mime, extension = ai_verbs.render_subtitles(row, fmt=fmt, speakers=speakers)
    except ai_verbs.VerbError as exc:
        raise _verb_http(exc) from exc
    return Response(
        content=body.encode("utf-8"),
        media_type=mime,
        headers={
            "Content-Disposition": naming.content_disposition(
                naming.workshop_filename(
                    record,
                    "Subtitles-With-Speakers" if speakers else "Subtitles",
                    extension,
                )
            )
        },
    )


async def _layer_text_withheld(workshop_id: str, row: Any, viewer: Any) -> bool:
    root = ai_layers.chain_roots(
        await ai_layers.workshop_layers(workshop_id, include_deleted=True)
    ).get(str(getattr(row, "id", "")))
    if root is None:
        return True
    wanted = {root.media_id} if root.kind is ai_layers.RootKind.MEDIA and root.media_id else set()
    return root.withheld_from(await _readable_media_ids(wanted, viewer))


@router.post("/{workshop_id}/ai-layers/{layer_id}/accept")
async def accept_ai_layer(
    workshop_id: str,
    layer_id: str,
    payload: AiLayerDecisionIn | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    row = await _layer_or_404(workshop_id, layer_id)
    if await _layer_text_withheld(workshop_id, row, current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "You cannot read the recording this layer was made from, so you cannot accept it: "
                "an acceptance says a person read this text and stands behind it, and the report "
                "prints their name beside it. Ask whoever uploaded the recording for access to "
                "their media, or ask them to accept it themselves."
            ),
        )
    try:
        plans = ai_layers.acceptance_plan(
            row,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            note=payload.note if payload else None,
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    withheld = False
    updated = await ai_layers.apply_decision(plans, workshop_id=workshop_id)
    return {
        "layer": ai_layers.layer_payload(updated, text_withheld=withheld),
        "decisions": [
            ai_layers.decision_payload(d) for d in await ai_layers.layer_decisions(layer_id)
        ],
    }


@router.post("/{workshop_id}/ai-layers/{layer_id}/unaccept")
async def unaccept_ai_layer(
    workshop_id: str,
    layer_id: str,
    payload: AiLayerDecisionIn | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    row = await _layer_or_404(workshop_id, layer_id)
    try:
        plans = ai_layers.withdrawal_plan(
            row, actor_id=current_user.id, note=payload.note if payload else None
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    withheld = await _layer_text_withheld(workshop_id, row, current_user)
    updated = await ai_layers.apply_decision(plans, workshop_id=workshop_id)
    return {
        "layer": ai_layers.layer_payload(updated, text_withheld=withheld),
        "decisions": [
            ai_layers.decision_payload(d) for d in await ai_layers.layer_decisions(layer_id)
        ],
    }


@router.delete("/{workshop_id}/ai-layers/{layer_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_ai_layer(
    workshop_id: str, layer_id: str, current_user: Any = Depends(get_current_user)
) -> None:
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    row = await _layer_or_404(workshop_id, layer_id)
    try:
        plan = ai_layers.deletion_plan(
            row,
            actor_id=current_user.id,
            at=datetime.now(UTC),
            derived=await ai_layers.derived_from(layer_id),
        )
    except ai_layers.LayerRuleViolation as exc:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(exc)) from exc
    await ai_layers.apply_plan(plan)


@router.get("/{workshop_id}/market-analysis")
async def workshop_market_analysis(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)

    def rows_of(entity_key: str) -> list[dict[str, Any]]:
        return [dict(row.data or {}) for row in entries if row.entityKey == entity_key]

    findings = analyse(
        responses=rows_of("surveyResponse"),
        competitors=rows_of("competitorProduct"),
        bands=rows_of("priceBand"),
        swot=rows_of("swotPoint"),
    )
    return market_findings_payload(findings)


@router.get("/{workshop_id}/cost-integrity")
async def workshop_cost_integrity(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)

    def rows_of(entity_key: str) -> list[dict[str, Any]]:
        return [
            dict(row.data or {}, _entryId=row.id) for row in entries if row.entityKey == entity_key
        ]

    labels = {
        row.id: str((row.data or {}).get("name") or (row.data or {}).get("productCode") or "")
        for row in entries
        if row.entityKey == "finalProduct"
    }

    findings = analyse_cost_integrity(
        sheets=rows_of("costSheet"),
        material_lines=rows_of("costMaterialLine"),
        labour_lines=rows_of("costLabourLine"),
        labels={k: v for k, v in labels.items() if v},
    )
    return cost_findings_payload(findings)



@router.get("/{workshop_id}/report/preview")
async def preview_report(
    workshop_id: str,
    templateId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await load_workshop_or_404(workshop_id, current_user)
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id, record, viewer=current_user, requested_template_id=templateId
    )
    document, warnings = await asyncio.to_thread(
        _build_only,
        data,
        template_id,
        resolver,
        record,
    )
    warnings = list(warnings) + load_warnings
    return {
        "meta": _preview_meta(document),
        "blocks": [_block_payload(b) for b in document.blocks],
        "warnings": list(warnings),
    }


@router.get("/{workshop_id}/report/sources")
async def report_sources(
    workshop_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    from dataclasses import asdict

    from app.services.report_ai_layers import ai_layers_of
    from app.services.report_annexures import transcripts_of
    from app.services.report_questionnaires import questionnaires_of

    record = await load_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    data = assemble_workshop_data(record, entries)
    results = await gather_reads(
        attach_report_references(data, entries),
        attach_district_anchors(data),
        attach_report_questionnaires(data, workshop_id),
        attach_report_transcripts(data, entries, viewer=current_user, requested=True),
        attach_report_ai_layers(
            data,
            workshop_id,
            viewer=current_user,
            readable_media=lambda ids: _readable_media_ids(ids, current_user),
            requested=True,
        ),
    )
    reference_photos = results[0]
    resolver = await media_resolver(entries, viewer=current_user, extra_ids=reference_photos)
    media_warnings: list[str] = []
    if resolver.withheld:
        media_warnings.append(
            f"{count_noun(len(resolver.withheld), 'attached file')} could not be included: "
            f"{by_count(len(resolver.withheld), 'it was', 'they were')} "
            "uploaded by another account, or the file is gone."
        )
    return {
        "workshopId": workshop_id,
        "builtAt": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "references": {key: asdict(value) for key, value in data.references.items()},
        "districtPoints": {key: [lat, lon] for key, (lat, lon) in data.district_points.items()},
        "media": {key: asdict(value) for key, value in resolver.known_refs().items()},
        "questionnaires": [asdict(item) for item in questionnaires_of(data)],
        "transcripts": [asdict(item) for item in transcripts_of(data)],
        "aiLayers": [asdict(item) for item in ai_layers_of(data)],
        "warnings": {
            "questionnaires": list(results[2]),
            "transcripts": list(results[3]),
            "aiLayers": list(results[4]),
            "media": media_warnings,
        },
    }


@router.post("/{workshop_id}/report")
async def generate_report(
    workshop_id: str,
    payload: ReportGenerateIn,
    current_user: Any = Depends(get_current_user),
) -> Response:
    record = await load_workshop_or_404(workshop_id, current_user)
    fmt = payload.formats[0]
    data, resolver, load_warnings, template_id = await _report_inputs(
        workshop_id,
        record,
        viewer=current_user,
        requested_template_id=payload.templateId,
        transcripts=payload.includeTranscripts,
        ai_layers=payload.includeAiLayers,
    )
    blob, warnings, page_count = await asyncio.to_thread(
        render_report,
        data,
        template_id,
        resolver,
        record,
        fmt,
        payload,
    )
    warnings = list(warnings) + load_warnings

    file_name = _report_file_name(record, fmt)
    headers = {
        "content-disposition": _content_disposition(file_name),
        "x-report-warnings": _warnings_header(warnings),
        "x-report-warning-count": str(len(warnings)),
    }

    if payload.record:
        await db.dwreportexport.create(
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
            }
        )

    return Response(content=blob, media_type=_MIME[fmt], headers=headers)


@router.get("/{workshop_id}/exports")
async def list_exports(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> list[dict[str, Any]]:
    await load_workshop_or_404(workshop_id, current_user)
    rows = await db.dwreportexport.find_many(
        where={"designWorkshopId": workshop_id}, order={"generatedAt": "desc"}, take=100
    )
    return [
        {
            "id": r.id,
            "format": r.format,
            "templateId": r.templateId,
            "fileName": r.fileName,
            "fileSizeBytes": r.fileSizeBytes,
            "pageCount": r.pageCount,
            "checksumSha256": r.checksumSha256,
            "generatedOnDevice": r.generatedOnDevice,
            "generatedAt": r.generatedAt.isoformat() if r.generatedAt else None,
            "warnings": r.warnings,
        }
        for r in rows
    ]


@router.post("/{workshop_id}/exports", status_code=status.HTTP_201_CREATED)
async def record_device_export(
    workshop_id: str,
    payload: ExportRecordIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    await load_workshop_or_404(
        workshop_id,
        current_user,
        for_edit=True,
        barred_to_post_holders=False,
        allow_when_frozen=True,
    )
    fmt = payload.format.upper()
    if fmt not in _MIME:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Unknown export format"
        )
    row = await db.dwreportexport.create(
        data={
            "designWorkshopId": workshop_id,
            "format": fmt,
            "templateId": payload.templateId,
            "fileName": payload.fileName,
            "fileSizeBytes": payload.fileSizeBytes,
            "pageCount": payload.pageCount,
            "checksumSha256": payload.checksumSha256,
            "generatedOnDevice": True,
            "schemaVersion": registry_version(),
            "warnings": payload.warnings,
            "generatedById": current_user.id,
            "generatedAt": _parse_datetime(payload.generatedAt) or datetime.now(UTC),
        }
    )
    return {"id": row.id}


_HISTORY_EXPORT_LIMIT = 100
_HISTORY_ENTRY_LIMIT = 2000


@router.get("/{workshop_id}/report-history")
async def report_history(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    record = await load_workshop_or_404(workshop_id, current_user)

    exports, entries = await gather_reads(
        db.dwreportexport.find_many(
            where={"designWorkshopId": workshop_id},
            order={"generatedAt": "desc"},
            take=_HISTORY_EXPORT_LIMIT + 1,
            include={"generatedBy": True},
        ),
        db.dwstageentry.find_many(
            where={"designWorkshopId": workshop_id},
            order={"updatedAt": "desc"},
            take=_HISTORY_ENTRY_LIMIT + 1,
        ),
    )
    entries_truncated = len(entries) > _HISTORY_ENTRY_LIMIT
    window = exports[:_HISTORY_EXPORT_LIMIT]
    generations_below = 0
    if window:
        generations_below = await db.dwreportexport.count(
            where={
                "designWorkshopId": workshop_id,
                "generatedAt": {"lt": window[-1].generatedAt},
            }
        )

    return {
        "workshopId": workshop_id,
        "workshopUpdatedAt": _iso(getattr(record, "updatedAt", None)),
        "serverTime": datetime.now(UTC).isoformat(),
        "completeness": (
            {}
            if entries_truncated
            else workshop_completeness(
                [r for r in entries if r.deletedAt is None],
                definition=await load_custom_definition_or_empty(workshop_id),
            )
        ),
        "exports": [
            _export_payload(row, generation=generations_below + len(window) - index)
            for index, row in enumerate(window)
        ],
        "exportsTruncated": len(exports) > _HISTORY_EXPORT_LIMIT,
        "entries": [
            {
                "id": row.id,
                "stageKey": row.stageKey,
                "entityKey": row.entityKey,
                "ordinal": row.ordinal,
                "createdAt": _iso(row.createdAt),
                "updatedAt": _iso(row.updatedAt),
                "deletedAt": _iso(row.deletedAt),
            }
            for row in entries[:_HISTORY_ENTRY_LIMIT]
        ],
        "entriesTruncated": entries_truncated,
    }



def _iso(value: datetime | None) -> str | None:
    return value.isoformat() if value else None


def _export_payload(row: Any, *, generation: int) -> dict[str, Any]:
    author = getattr(row, "generatedBy", None)
    return {
        "id": row.id,
        "generation": generation,
        "format": row.format,
        "templateId": row.templateId,
        "fileName": row.fileName,
        "fileSizeBytes": row.fileSizeBytes,
        "pageCount": row.pageCount,
        "checksumSha256": row.checksumSha256,
        "generatedOnDevice": row.generatedOnDevice,
        "schemaVersion": row.schemaVersion,
        "warnings": row.warnings,
        "generatedAt": _iso(row.generatedAt),
        "generatedById": row.generatedById,
        "generatedByName": getattr(author, "name", None) if author else None,
    }


async def _transcripts_payload(entries: list[Any], viewer: Any) -> dict[str, Any]:
    items = await load_transcript_items(entries, viewer=viewer)
    return {item.media_id: item.payload() | {"text": item.text} for item in items}


def _require_designer(user: Any) -> None:
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Running a design workshop requires Designer access or above.",
        )


def _parse_date(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(str(raw)[:10]).replace(tzinfo=UTC)
    except ValueError:
        return None


def _parse_datetime(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        text = str(raw).replace("Z", "+00:00")
        parsed = datetime.fromisoformat(text)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)
    except ValueError:
        return None


def _stages_payload(entries: list[Any]) -> dict[str, Any]:
    from app.services.entry_provenance import entry_provenance
    from app.services.stage_schema import Cardinality

    entity_cardinality = {e.key: e.cardinality for s in stages() for e in s.entities}
    out: dict[str, Any] = {}
    for row in entries:
        bucket = out.setdefault(
            row.stageKey,
            {
                "singleton": {},
                "collections": {},
                "custom": {},
                "provenance": {"singleton": {}, "collections": {}, "custom": {}},
            },
        )
        stamps = entry_provenance(row)
        data = dict(row.data or {})
        if row.entityKey == CUSTOM_ENTITY_KEY:
            bucket["custom"] = data
            bucket["provenance"]["custom"] = stamps
        elif entity_cardinality.get(row.entityKey) is Cardinality.SINGLETON:
            bucket["singleton"] = data
            bucket["provenance"]["singleton"] = stamps
        else:
            data["_entryId"] = row.id
            data["_ordinal"] = row.ordinal
            if row.clientKey:
                data["_clientKey"] = row.clientKey
            bucket["collections"].setdefault(row.entityKey, []).append(data)
            bucket["provenance"]["collections"].setdefault(row.entityKey, {})[row.id] = stamps
    for bucket in out.values():
        for rows in bucket["collections"].values():
            rows.sort(key=lambda r: r.get("_ordinal", 0))
    return out


def _provenance_maps(payload: dict[str, Any]) -> list[dict[str, Any]]:
    maps: list[dict[str, Any]] = []
    for bucket in payload.values():
        prov = bucket.get("provenance") or {}
        for key in ("singleton", "custom"):
            stamps = prov.get(key)
            if isinstance(stamps, dict):
                maps.append(stamps)
        for by_entry in (prov.get("collections") or {}).values():
            maps.extend(s for s in by_entry.values() if isinstance(s, dict))
    return maps


async def _report_inputs(
    workshop_id: str,
    record: Any,
    *,
    viewer: Any,
    requested_template_id: Any = None,
    transcripts: bool | None = None,
    ai_layers: bool = False,
) -> tuple[Any, Any, list[str], str]:
    entries = await entry_rows(workshop_id)
    data = assemble_workshop_data(
        record, entries, survey_sittings=await report_survey_sittings(record)
    )
    if getattr(record, "approvedAt", None) is not None:
        names = await design_workshop_approvals.approval_names(record)
        data.sign_off_lines = sign_off_lines(
            approved_at=record.approvedAt,
            approved_by_name=names.get("approvedByName"),
            approver_role_label=design_workshop_approvals_schema.role_label(
                names.get("approvedByRole")
            ),
            handed_on_at=getattr(record, "handedOnAt", None),
            handed_on_to=getattr(record, "handedOnTo", None),
        )
    await resolve_display_names(data.field_provenance.values())
    template_id = resolve_template_id(
        requested_template_id, data.singleton("REPORT_GENERATION"), record
    )

    loads: list[Any] = [attach_report_references(data, entries)]
    base_template = get_template(template_id)
    specials = {section.special for section in base_template.sections}
    draws_map = SpecialSection.MAP in specials
    if draws_map:
        loads.append(attach_district_anchors(data))
    draws_questionnaires = SpecialSection.ANNEXURE_QUESTIONNAIRES in specials
    warning_slots: dict[str, int] = {}

    def _append(load: Any, *, warns_as: str = "") -> None:
        if warns_as:
            warning_slots[warns_as] = len(loads)
        loads.append(load)

    if draws_questionnaires:
        _append(attach_report_questionnaires(data, workshop_id), warns_as="questionnaires")
    _append(
        attach_report_transcripts(data, entries, viewer=viewer, requested=transcripts),
        warns_as="transcripts",
    )
    _append(
        attach_report_ai_layers(
            data,
            workshop_id,
            viewer=viewer,
            readable_media=lambda ids: _readable_media_ids(ids, viewer),
            requested=bool(ai_layers),
        ),
        warns_as="aiLayers",
    )
    _append(
        attach_report_custom_sections(data, entries, workshop_id),
        warns_as="customSections",
    )

    results = await gather_reads(*loads)
    reference_photos = results[0]
    warnings: list[str] = []
    for name in ("questionnaires", "transcripts", "aiLayers", "customSections"):
        slot = warning_slots.get(name)
        if slot is not None:
            warnings.extend(results[slot])

    resolver = await media_resolver(entries, viewer=viewer, extra_ids=reference_photos)
    if resolver.withheld:
        warnings.append(
            f"{count_noun(len(resolver.withheld), 'attached file')} could not be included: "
            f"{by_count(len(resolver.withheld), 'it was', 'they were')} "
            "uploaded by another account, or the file is gone."
        )
    warnings.extend(inert_section_toggles(base_template, data.singleton("REPORT_GENERATION")))
    return data, resolver, warnings, template_id


def _build_only(data: Any, template_id: str, resolver: Any, record: Any) -> tuple[Any, list[str]]:
    from dataclasses import replace

    from app.services.design_workshops import report_meta
    from app.services.report_builder import build_report
    from app.services.report_custom_sections import custom_sections_of
    from app.services.report_templates import apply_report_settings, template as get_template
    from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent

    settings = data.singleton("REPORT_GENERATION")
    template = get_template(template_id)
    accent = resolve_accent(None, settings)
    theme = theme_from_accent(accent, base=template.theme) if accent else template.theme
    fonts = resolve_font(None, settings)
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])

    return build_report(
        data,
        template_id,
        resolver.ref,
        meta=report_meta(record, template_id, settings),
        theme=theme,
        template=apply_report_settings(
            template, settings, custom_sections=custom_sections_of(data)
        ),
    )


def _report_file_name(record: Any, fmt: str) -> str:
    return naming.workshop_filename(record, "Report", _EXTENSION[fmt])


def _content_disposition(file_name: str) -> str:
    return naming.content_disposition(file_name)


_WARNINGS_HEADER_BUDGET = 900


def _warnings_header(warnings: list[str]) -> str:
    def one_piece(value: str) -> str:
        return str(value).encode("ascii", "replace").decode("ascii").replace(";", ",")

    items = [one_piece(w) for w in warnings if str(w).strip()]
    if not items:
        return ""

    joined = "; ".join(items)
    if len(joined) <= _WARNINGS_HEADER_BUDGET:
        return joined

    room = _WARNINGS_HEADER_BUDGET - len(_dropped_note(len(items))) - 2
    kept: list[str] = []
    used = 0
    for item in items:
        cost = len(item) + (2 if kept else 0)
        if used + cost > room:
            break
        kept.append(item)
        used += cost

    if not kept:
        kept = [items[0][: max(1, room - 3)].rstrip() + "..."]

    dropped = len(items) - len(kept)
    return "; ".join(kept + ([_dropped_note(dropped)] if dropped else []))


def _dropped_note(count: int) -> str:
    return (
        f"{count_noun(count, 'further warning')} did not fit in this header. The report preview "
        "lists all of them."
    )


def _preview_meta(document: Any) -> dict[str, Any]:
    return {
        "title": document.meta.title,
        "subtitle": document.meta.subtitle,
        "templateId": document.meta.template_id,
        "templateName": document.meta.template_name,
        "pageSize": document.meta.page_size.value,
        "marginMm": document.meta.margin_mm,
    }


def _block_payload(block: Any) -> dict[str, Any]:
    from dataclasses import asdict, is_dataclass

    name = type(block).__name__
    payload: dict[str, Any] = {"type": name.replace("Block", "").upper()}
    if is_dataclass(block):
        for key, value in asdict(block).items():
            payload[key] = value
    return payload
