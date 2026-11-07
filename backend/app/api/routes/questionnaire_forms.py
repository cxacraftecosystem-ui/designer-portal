import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    Query,
    Request,
    UploadFile,
    status,
)
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import (
    assert_can_delete,
    can_run_design_workshops,
    get_current_user,
    is_content_admin,
    require_admin,
    require_content_admin,
)
from app.questionnaire import (
    authoring as questionnaire_authoring,
    filters as shared_filters,
    forms as unified_forms,
    shims_dr,
)
from app.schemas.questionnaire import (
    CustomAnswerBatch,
    CustomQuestionCreate,
    CustomQuestionUpdate,
    CustomSectionCreate,
    CustomSectionUpdate,
    QuestionnaireCreate,
    QuestionnaireDefaultUpdate,
    QuestionnaireEntryCreate,
    QuestionnaireEntryUpdate,
    QuestionnaireReuse,
    QuestionnaireUpdate,
)
from app.services import cross_portal_codes
from app.services.concurrency import gather_reads
from app.services.design_workshop_viewers import has_viewer_grant
from app.services.design_workshops import load_workshop_or_404
from app.services.pagination import normalize_pagination, page_payload
from app.services.questionnaire_forms import (
    APP_ENTRY_SOURCE,
    QuestionnaireEditError,
    apply_parsed_edit,
    bump_version,
    create_from_parsed,
    export_payload,
    export_question_set_payload,
    guard_question_edit,
    load_form,
    load_question_set,
    reuse_questionnaire,
    save_answers,
    supersede_question,
    visible_questionnaire_where,
)
from app.services.questionnaire_kinds import coerce_kind, label_for
from app.services.questionnaire_xlsx import (
    PRO_FORMA_FILENAME,
    QuestionnaireXlsxError,
    build_pro_forma,
    build_question_set_workbook,
    build_questionnaire_workbook,
    derive_section_code,
    download_filename,
    parse_questionnaire_workbook,
    question_set_filename,
)
from app.services.record_design_workshop import assert_may_write_a_record_filed_under
from app.services.records import (
    clean_data,
    public_encode,
    require_record,
    with_id_tiebreak,
)
from app.services.uploads import read_body_bounded, read_upload_bounded
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/questionnaires", tags=["questionnaires"])

MAX_UPLOAD_BYTES = 8 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


_QUESTIONNAIRE_CLEARABLE_COLUMNS = ("description", "kind")

_QUESTION_CLEARABLE_COLUMNS = ("helpText",)

_ENTRY_CLEARABLE_COLUMNS = ("respondentName", "notes")

_SECTION_CLEARABLE_COLUMNS: tuple[str, ...] = ()



def _require_designer(user: Any) -> None:
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Questionnaires are for Designer, Assistant Director, Regional Director, Ministry "
                "Admin, Admin and Master Admin accounts. Ask an administrator if your role should "
                "change."
            ),
        )


async def _require_questionnaire(questionnaire_id: str, user: Any) -> Any:
    _require_designer(user)
    record = await require_record(db.questionnaire, questionnaire_id)
    if record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


async def _deleted_by_names(rows: list[Any]) -> dict[str, str]:
    ids = sorted({row.deletedById for row in rows if getattr(row, "deletedById", None)})
    if not ids:
        return {}
    users = await db.user.find_many(where={"id": {"in": ids}})
    return {u.id: u.name for u in users if getattr(u, "name", None)}


async def _works_on_this_questionnaires_workshop(record: Any, user: Any) -> bool:
    workshop_id = getattr(record, "designWorkshopId", None)
    if not workshop_id:
        return False
    workshop = await db.designworkshop.find_unique(where={"id": workshop_id})
    if workshop is None or workshop.deletedAt is not None:
        return False
    if workshop.createdById == user.id:
        return True
    return await has_viewer_grant(workshop_id, user.id)


_visible_questionnaire_where = visible_questionnaire_where


def _require_owner(record: Any, user: Any) -> None:
    if not questionnaire_authoring.may_change_form(user, record):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the designer who created this questionnaire, or an admin, can change it.",
        )


async def _refuse_its_workshops_holder(record: Any, user: Any) -> None:
    await assert_may_write_a_record_filed_under(getattr(record, "designWorkshopId", None), user)


async def _require_attachable_workshop(workshop_id: str, user: Any) -> None:
    await load_workshop_or_404(workshop_id, user, for_edit=True)


async def _require_recordable_questionnaire(record: Any, user: Any) -> None:
    workshop_id = getattr(record, "designWorkshopId", None)
    if not workshop_id:
        return
    await load_workshop_or_404(workshop_id, user, for_edit=True)


def _kind_or_422(value: str | None) -> str | None:
    try:
        return coerce_kind(value)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc


async def _read_upload(file: UploadFile, request: Request | None = None) -> bytes:
    name = (file.filename or "").lower()
    if name and not name.endswith(_XLSX_SUFFIXES):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{file.filename}' is not an Excel workbook. Fill in the .xlsx pro-forma and "
                "upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
            ),
        )
    content = await read_upload_bounded(
        file, MAX_UPLOAD_BYTES, request=request, purpose="questionnaire workbook"
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The upload was empty. Attach the filled-in pro-forma.",
        )
    return content


def _xlsx_response(payload: bytes, filename: str) -> Response:
    return xlsx_response(payload, filename)



@router.get("/pro-forma")
async def download_pro_forma(current_user: Any = Depends(get_current_user)) -> Response:
    _require_designer(current_user)
    return _xlsx_response(build_pro_forma(), PRO_FORMA_FILENAME)


@router.get("/{questionnaire_id}/xlsx")
async def download_questionnaire(
    questionnaire_id: str, current_user: Any = Depends(get_current_user)
) -> Response:
    record = await _require_questionnaire(questionnaire_id, current_user)
    if not (
        record.ownerId == current_user.id
        or is_content_admin(current_user)
        or await _works_on_this_questionnaires_workshop(record, current_user)
    ):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "This workbook carries every sitting recorded against the questionnaire, so it "
                "belongs to the designer who created it, a designer working on its design "
                "workshop, or an admin. If you want the QUESTIONS — to run this instrument "
                "yourself, or to send it on — download the question set instead: it carries the "
                "questions and no answers, and any designer may take it. Record answers from the "
                "questionnaire page."
            ),
        )
    payload = await export_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    workbook = build_questionnaire_workbook(**payload)
    return _xlsx_response(workbook, download_filename(payload["title"]))


@router.get("/{questionnaire_id}/question-set.xlsx")
async def download_question_set(
    questionnaire_id: str, current_user: Any = Depends(get_current_user)
) -> Response:
    await _require_questionnaire(questionnaire_id, current_user)
    payload = await export_question_set_payload(questionnaire_id)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    workbook = build_question_set_workbook(
        title=payload["title"],
        description=payload["description"],
        sections=payload["sections"],
        source_title=payload["source_title"],
        shared_by=getattr(current_user, "name", None) or None,
        exported_on=datetime.now(UTC).date().isoformat(),
    )
    return _xlsx_response(workbook, question_set_filename(payload["title"]))



@router.post("/bundles/import", status_code=status.HTTP_201_CREATED)
async def import_questionnaire_bundle(
    request: Request,
    response: Response,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    from app.questionnaire import library

    _require_designer(current_user)
    limit = library.BUNDLE_LIMITS["maxUncompressedBytes"]
    if request.headers.get("content-type", "").startswith("multipart/"):
        form = await request.form()
        upload = form.get("file")
        if upload is None or not hasattr(upload, "read"):
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Choose a bundle file.")
        raw = await upload.read(limit + 1)
    else:
        raw = await read_body_bounded(request, limit)
    if raw is None or len(raw) > limit:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="This bundle is too large.")
    document = library.read_bundle_bytes(raw)
    tables = unified_forms.tables(db, await unified_forms.authoring_unified())
    created_row, created = await library.import_bundle(document, current_user, tables=tables)
    if not created:
        response.status_code = status.HTTP_200_OK
    return public_encode({"questionnaire": await load_form(created_row.id, include_retired=True), "created": created})


@router.get("/{questionnaire_id}/bundle")
async def download_questionnaire_bundle(
    questionnaire_id: str,
    current_user: Any = Depends(get_current_user),
) -> Response:
    import secrets as _secrets

    from app.questionnaire import library
    from app.services.naming import content_disposition

    record = await _require_questionnaire(questionnaire_id, current_user)
    form = await load_question_set(questionnaire_id)
    if form is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    sections = form.get("sections") or []
    document = {
        "v": library.BUNDLE_VERSION,
        "bundleId": _secrets.token_hex(16),
        "createdAt": datetime.now(UTC).isoformat(),
        "portal": "designer-portal",
        "form": {"title": form.get("title"), "description": form.get("description"), "kind": form.get("kind")},
        "sections": [{"code": sec["code"], "title": sec["title"], "sortOrder": i} for i, sec in enumerate(sections, start=1)],
        "questions": [
            {
                "sectionCode": sec["code"], "prompt": q.get("prompt"), "helpText": q.get("helpText"),
                "isRequired": bool(q.get("isRequired")), "artisanField": q.get("artisanField"),
                "questionCode": q.get("questionCode"), "sortOrder": position,
            }
            for sec in sections
            for position, q in enumerate(sec.get("questions") or [], start=1)
        ],
    }
    return Response(
        content=library.bundle_bytes(document),
        media_type="application/gzip",
        headers={"Content-Disposition": content_disposition(library.bundle_filename(record.title))},
    )


@router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_questionnaire(
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    designWorkshopId: str | None = Form(default=None),
    kind: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    content = await _read_upload(file, request)
    try:
        parsed = await asyncio.to_thread(
            parse_questionnaire_workbook, content, filename=file.filename
        )
    except QuestionnaireXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    if designWorkshopId:
        await _require_attachable_workshop(designWorkshopId, current_user)
    questionnaire_id, report = await create_from_parsed(
        parsed,
        owner_id=current_user.id,
        title=title,
        design_workshop_id=designWorkshopId,
        kind=_kind_or_422(kind),
        source_filename=file.filename,
        user=current_user,
    )
    await _reconcile_codes(questionnaire_id)
    form = await load_form(questionnaire_id)
    return public_encode({"questionnaire": form, "report": report})


@router.post("/{questionnaire_id}/upload")
async def reupload_questionnaire(
    questionnaire_id: str,
    request: Request,
    file: UploadFile = File(...),
    title: str | None = Form(default=None),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    content = await _read_upload(file, request)
    try:
        parsed = await asyncio.to_thread(
            parse_questionnaire_workbook, content, filename=file.filename
        )
    except QuestionnaireXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    if parsed.questionnaireId and parsed.questionnaireId != questionnaire_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "That workbook was downloaded from a different questionnaire "
                f"(its Details sheet says {parsed.questionnaireId}). Download this questionnaire "
                "again and edit that copy, or upload the file as a new questionnaire."
            ),
        )
    try:
        report = await apply_parsed_edit(
            questionnaire_id,
            parsed,
            user_id=current_user.id,
            title=title,
            may_remove=True,
        )
    except QuestionnaireEditError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc
    if file.filename and file.filename != record.sourceFilename:
        await db.questionnaire.update(
            where={"id": questionnaire_id}, data={"sourceFilename": file.filename}
        )
    await _reconcile_codes(questionnaire_id)
    form = await load_form(questionnaire_id, include_retired=True)
    return public_encode({"questionnaire": form, "report": report})



@router.get("")
async def list_questionnaires(
    request: Request,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1),
    search: str | None = None,
    designWorkshopId: str | None = None,
    activeOnly: bool = True,
    mineOnly: bool = False,
    deletedOnly: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    where: dict[str, Any] = {"deletedAt": {"not": None} if deletedOnly else None}
    if deletedOnly and not is_content_admin(current_user):
        where["ownerId"] = current_user.id
    if activeOnly and not deletedOnly:
        where["isActive"] = True
    if designWorkshopId:
        chosen = [w.strip() for value in request.query_params.getlist("designWorkshopId") or [designWorkshopId]
                  for w in value.split(",") if w.strip()]
        where["designWorkshopId"] = chosen[0] if len(chosen) == 1 else {"in": chosen}
    library_filters = shared_filters.parse(request.query_params).without("designWorkshopId")
    if search and not library_filters.q:
        library_filters = shared_filters.Filters(**{**library_filters.__dict__, "q": search.strip()[: shared_filters.MAX_TEXT]})
    matching = await shared_filters.form_ids_matching(library_filters)
    if matching is not None:
        where.setdefault("AND", []).append({"id": {"in": sorted(matching)}})
    if mineOnly or (deletedOnly and not is_content_admin(current_user)):
        where["ownerId"] = current_user.id
    elif not is_content_admin(current_user):
        where.setdefault("AND", []).append(_visible_questionnaire_where(current_user))

    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await gather_reads(
        db.questionnaire.count(where=where),
        db.questionnaire.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            order=with_id_tiebreak(
                [{"deletedAt": "desc"}]
                if deletedOnly
                else [{"isShared": "desc"}, {"createdAt": "desc"}]
            ),
            include={"owner": True, "designWorkshop": True},
        ),
    )
    deleters = await _deleted_by_names(rows) if deletedOnly else {}
    summaries = [
        {
            "id": row.id,
            "title": row.title,
            "description": row.description,
            "ownerId": row.ownerId,
            "ownerName": getattr(row.owner, "name", None),
            "designWorkshopId": row.designWorkshopId,
            "designWorkshopTitle": getattr(row.designWorkshop, "title", None),
            "isActive": row.isActive,
            "isShared": row.isShared,
            "kind": row.kind,
            "kindLabel": label_for(row.kind),
            "version": row.version,
            "sourceFilename": row.sourceFilename,
            "createdAt": row.createdAt,
            "updatedAt": row.updatedAt,
            "deletedAt": row.deletedAt,
            "deletedByName": deleters.get(row.deletedById or ""),
        }
        for row in rows
    ]
    return public_encode(page_payload(summaries, total, clean_page, clean_size))


@router.get("/options")
async def questionnaire_options(
    designWorkshopId: str | None = None,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    _require_designer(current_user)
    where: dict[str, Any] = {"isActive": True, "deletedAt": None}
    if not is_content_admin(current_user):
        where["OR"] = [{"ownerId": current_user.id}, {"isShared": True}]
    rows = await db.questionnaire.find_many(where=where, order={"title": "asc"}, take=500)
    return [
        {
            "id": row.id,
            "title": row.title,
            "version": row.version,
            "designWorkshopId": row.designWorkshopId,
            "isShared": row.isShared,
            "kind": row.kind,
            "kindLabel": label_for(row.kind),
            "attachedElsewhere": bool(
                row.designWorkshopId
                and designWorkshopId
                and row.designWorkshopId != designWorkshopId
            ),
        }
        for row in rows
    ]


@router.get("/{questionnaire_id}")
async def read_questionnaire(
    questionnaire_id: str,
    includeRetired: bool = False,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    entitled = await _sees_every_sitting(record, current_user)
    mine_only = None if entitled else current_user.id
    form = await load_form(
        questionnaire_id, include_retired=includeRetired, only_entries_of=mine_only
    )
    if form is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if await shims_dr.form_is_unified(questionnaire_id):
        entries = await shims_dr.entries_for_form(questionnaire_id)
        form["entries"] = [e for e in entries if mine_only is None or e.get("createdById") == mine_only]
    return public_encode(form)


@router.post("/{questionnaire_id}/reuse", status_code=status.HTTP_201_CREATED)
async def reuse_questionnaire_route(
    questionnaire_id: str,
    payload: QuestionnaireReuse,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    if payload.designWorkshopId:
        await _require_attachable_workshop(payload.designWorkshopId, current_user)

    sent = payload.model_dump(exclude_unset=True)
    description: str | None = None
    if "description" in sent:
        description = sent["description"] if sent["description"] is not None else ""

    made = await reuse_questionnaire(
        questionnaire_id,
        owner_id=current_user.id,
        design_workshop_id=payload.designWorkshopId,
        title=payload.title,
        description=description,
        **(
            {"kind": payload.kind}
            if "kind" in payload.model_fields_set
            else {}
        ),
    )
    if made is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    new_id, report = made
    await _reconcile_codes(new_id)
    form = await load_form(new_id)
    return public_encode(
        {"questionnaire": form, "report": report, "sourceQuestionnaireId": record.id}
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_questionnaire(
    payload: QuestionnaireCreate, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    _require_designer(current_user)
    if payload.designWorkshopId:
        await _require_attachable_workshop(payload.designWorkshopId, current_user)

    codes: set[str] = set()
    section_data: list[dict[str, Any]] = []
    questions_by_code: dict[str, list[Any]] = {}
    for index, section in enumerate(payload.sections, start=1):
        code = (section.code or "").strip()
        if not code or code.lower() in codes:
            code = derive_section_code(section.title, codes)
        else:
            codes.add(code.lower())
        section_data.append(
            {
                "code": code,
                "title": section.title.strip(),
                "sortOrder": section.sortOrder or index,
            }
        )
        questions_by_code[code] = list(section.questions)

    unified = await unified_forms.authoring_unified()
    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
        Tx = unified_forms.tables(tx, unified)
        record = await tx.questionnaire.create(
            data={
                "title": payload.title.strip(),
                "description": payload.description,
                "ownerId": current_user.id,
                "designWorkshopId": payload.designWorkshopId,
                "kind": payload.kind,
            }
        )
        if section_data:
            await Tx.sections.create_many(
                data=[row | {"questionnaireId": record.id} for row in section_data]
            )
            written = await Tx.sections.find_many(
                where={"questionnaireId": record.id}
            )
            id_by_code = {row.code: row.id for row in written}
            question_data = [
                {
                    "sectionId": id_by_code[code],
                    "prompt": question.prompt.strip(),
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "sortOrder": question.sortOrder or position,
                }
                for code, questions in questions_by_code.items()
                for position, question in enumerate(questions, start=1)
            ]
            if question_data:
                await Tx.questions.create_many(data=question_data)
    await _reconcile_codes(record.id)
    return public_encode(await load_form(record.id))


@router.patch("/{questionnaire_id}")
async def update_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_QUESTIONNAIRE_CLEARABLE_COLUMNS,
    )
    if data.get("designWorkshopId"):
        await _require_attachable_workshop(data["designWorkshopId"], current_user)
    if "isShared" in data and not is_content_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only an admin can publish a questionnaire as the default form for every designer. "
                "Your own questionnaires are unaffected — ask an admin to publish this one."
            ),
        )
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        await db.questionnaire.update(where={"id": questionnaire_id}, data=data)
    await _reconcile_codes(questionnaire_id)
    return public_encode(await load_form(questionnaire_id, include_retired=True))



@router.post("/{questionnaire_id}/sections", status_code=status.HTTP_201_CREATED)
async def create_section(
    questionnaire_id: str,
    payload: CustomSectionCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    T = await _tables(questionnaire_id)
    existing = await T.sections.find_many(
        where={"questionnaireId": questionnaire_id}
    )
    codes = {s.code.strip().lower() for s in existing}
    code = (payload.code or "").strip()
    if not code or code.lower() in codes:
        code = derive_section_code(payload.title, codes)
    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
        Tx = unified_forms.tables(tx, T.unified)
        made = await Tx.sections.create(
            data={
                "questionnaireId": questionnaire_id,
                "code": code,
                "title": payload.title.strip(),
                "sortOrder": payload.sortOrder
                or (max((s.sortOrder for s in existing), default=0) + 1),
            }
        )
        if payload.questions:
            await Tx.questions.create_many(
                data=[
                    {
                        "sectionId": made.id,
                        "prompt": question.prompt.strip(),
                        "helpText": question.helpText,
                        "isRequired": question.isRequired,
                        "sortOrder": question.sortOrder or position,
                    }
                    for position, question in enumerate(payload.questions, start=1)
                ]
            )
    await _reconcile_codes(questionnaire_id)
    return public_encode(await load_form(questionnaire_id))


@router.patch("/{questionnaire_id}/sections/{section_id}")
async def update_section(
    questionnaire_id: str,
    section_id: str,
    payload: CustomSectionUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    T = await _tables(questionnaire_id)
    section = await require_record(T.sections, section_id)
    if section.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_SECTION_CLEARABLE_COLUMNS,
    )
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        await T.sections.update(where={"id": section_id}, data=data)
    await _reconcile_codes(questionnaire_id)
    return public_encode(await load_form(questionnaire_id, include_retired=True))


@router.post(
    "/{questionnaire_id}/sections/{section_id}/questions", status_code=status.HTTP_201_CREATED
)
async def create_question(
    questionnaire_id: str,
    section_id: str,
    payload: CustomQuestionCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    T = await _tables(questionnaire_id)
    section = await require_record(T.sections, section_id)
    if section.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    siblings = await T.questions.find_many(where={"sectionId": section_id})
    await T.questions.create(
        data={
            "sectionId": section_id,
            "prompt": payload.prompt.strip(),
            "helpText": payload.helpText,
            "isRequired": payload.isRequired,
            "sortOrder": payload.sortOrder or (max((q.sortOrder for q in siblings), default=0) + 1),
        }
    )
    await _reconcile_codes(questionnaire_id)
    return public_encode(await load_form(questionnaire_id))


@router.patch("/{questionnaire_id}/questions/{question_id}")
async def update_question(
    questionnaire_id: str,
    question_id: str,
    payload: CustomQuestionUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    question = await _question_in(questionnaire_id, question_id)

    action = await guard_question_edit(question, new_prompt=payload.prompt, deleting=False)
    if action == "supersede":
        replacement = await supersede_question(
            question,
            tables=await _tables(questionnaire_id),
            prompt=payload.prompt.strip(),
            helpText=payload.helpText
            if "helpText" in payload.model_fields_set
            else question.helpText,
            isRequired=payload.isRequired
            if payload.isRequired is not None
            else question.isRequired,
            sortOrder=payload.sortOrder or question.sortOrder,
        )
        await bump_version(questionnaire_id)
        await _reconcile_codes(questionnaire_id)
        return public_encode(
            {
                "action": "superseded",
                "questionId": question.id,
                "replacementId": replacement.id,
                "detail": (
                    "This question already has answers recorded against it, so its original wording "
                    "and those answers were kept and your new wording was added as a new question."
                ),
                "questionnaire": await load_form(questionnaire_id, include_retired=True),
            }
        )

    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_QUESTION_CLEARABLE_COLUMNS,
    )
    if "prompt" in data:
        data["prompt"] = data["prompt"].strip()
    section_id = data.pop("sectionId", None)
    T = await _tables(questionnaire_id)
    if section_id:
        section = await require_record(T.sections, section_id)
        if section.questionnaireId != questionnaire_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        data["sectionId"] = section_id
    if data:
        await T.questions.update(where={"id": question_id}, data=data)
    await _reconcile_codes(questionnaire_id)
    return public_encode(
        {
            "action": "updated",
            "questionId": question_id,
            "questionnaire": await load_form(questionnaire_id, include_retired=True),
        }
    )


@router.delete("/{questionnaire_id}/questions/{question_id}")
async def remove_question(
    questionnaire_id: str,
    question_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    question = await _question_in(questionnaire_id, question_id)

    action = await guard_question_edit(question, new_prompt=None, deleting=True)
    T = await _tables(questionnaire_id)
    if action == "retire":
        await T.questions.update(
            where={"id": question_id},
            data={"isActive": False, "retiredAt": datetime.now(UTC)},
        )
        await bump_version(questionnaire_id)
        return public_encode(
            {
                "action": "retired",
                "questionId": question_id,
                "detail": (
                    "This question has answers recorded against it, so it was retired rather than "
                    "deleted. It is no longer asked, and its answers are still in the record."
                ),
                "questionnaire": await load_form(questionnaire_id, include_retired=True),
            }
        )
    if T.unified:
        await T.questions.delete(where={"id": question_id})
    else:
        async with db.tx() as tx:
            await tx.questionnaireformanswer.delete_many(where={"questionId": question_id})
            await tx.questionnaireformquestion.delete(where={"id": question_id})
    return public_encode(
        {
            "action": "deleted",
            "questionId": question_id,
            "questionnaire": await load_form(questionnaire_id, include_retired=True),
        }
    )


async def _reconcile_codes(questionnaire_id: str) -> None:
    if await unified_forms.form_unified(questionnaire_id):
        await cross_portal_codes.reconcile_question_codes(db, questionnaire_id=questionnaire_id)
    else:
        await cross_portal_codes.reconcile_form_question_codes(db, questionnaire_id=questionnaire_id)


async def _tables(questionnaire_id: str, client: Any = None) -> Any:
    return unified_forms.tables(client if client is not None else db, await unified_forms.form_unified(questionnaire_id))


async def _question_in(questionnaire_id: str, question_id: str) -> Any:
    T = await _tables(questionnaire_id)
    question = await require_record(T.questions, question_id)
    section = await require_record(T.sections, question.sectionId)
    if section.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return question



@router.post("/{questionnaire_id}/entries", status_code=status.HTTP_201_CREATED)
async def create_entry(
    questionnaire_id: str,
    payload: QuestionnaireEntryCreate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    await _require_recordable_questionnaire(record, current_user)
    if not record.isActive:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This questionnaire has been deactivated, so no new answers can be recorded against it.",
        )
    if await shims_dr.form_is_unified(questionnaire_id):
        return await shims_dr.create_entry(questionnaire_id, payload, current_user)
    entry = await db.questionnaireformentry.create(
        data={
            "questionnaireId": questionnaire_id,
            "title": (payload.title or payload.respondentName or "Answers").strip(),
            "respondentName": payload.respondentName,
            "notes": payload.notes,
            "source": APP_ENTRY_SOURCE,
            "createdById": current_user.id,
        }
    )
    return public_encode(entry)


@router.patch("/{questionnaire_id}/entries/{entry_id}")
async def update_entry(
    questionnaire_id: str,
    entry_id: str,
    payload: QuestionnaireEntryUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    if await shims_dr.form_is_unified(questionnaire_id):
        await _refuse_its_workshops_holder(record, current_user)
        return await shims_dr.update_entry(entry_id, payload, current_user)
    entry = await _entry_in(
        questionnaire_id, entry_id, user=current_user, questionnaire=record, rewriting=True
    )
    await _refuse_its_workshops_holder(record, current_user)
    data = clean_data(
        payload.model_dump(exclude_unset=True),
        title_case=False,
        clearable=_ENTRY_CLEARABLE_COLUMNS,
    )
    if "title" in data:
        data["title"] = data["title"].strip()
    if data:
        entry = await db.questionnaireformentry.update(where={"id": entry.id}, data=data)
    return public_encode(entry)


async def _sees_every_sitting(record: Any, user: Any) -> bool:
    return (
        record.ownerId == user.id
        or is_content_admin(user)
        or await _works_on_this_questionnaires_workshop(record, user)
    )


@router.put("/{questionnaire_id}/entries/{entry_id}/answers")
async def record_answers(
    questionnaire_id: str,
    entry_id: str,
    payload: CustomAnswerBatch,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    await _require_recordable_questionnaire(record, current_user)
    if await shims_dr.form_is_unified(questionnaire_id):
        return public_encode(await shims_dr.record_answers(questionnaire_id, entry_id, payload, current_user))
    entry = await _entry_in(
        questionnaire_id, entry_id, user=current_user, questionnaire=record, rewriting=False
    )
    if not payload.answers:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="There are no answers to save."
        )
    try:
        result = await save_answers(
            entry, payload.answers, user_id=current_user.id, is_admin=is_content_admin(current_user)
        )
    except QuestionnaireEditError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc
    where: dict[str, Any] = {"entryId": entry.id}
    if entry.createdById != current_user.id and not await _sees_every_sitting(
        record, current_user
    ):
        where["answeredById"] = current_user.id
    answers = await db.questionnaireformanswer.find_many(where=where)
    return public_encode({"saved": result, "answers": answers})


@router.delete("/{questionnaire_id}/entries/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_entry(
    questionnaire_id: str,
    entry_id: str,
    current_user: Any = Depends(get_current_user),
) -> Response:
    assert_can_delete(current_user)
    record = await _require_questionnaire(questionnaire_id, current_user)
    if await shims_dr.form_is_unified(questionnaire_id):
        await _refuse_its_workshops_holder(record, current_user)
        await shims_dr.delete_entry(entry_id, current_user)
        return Response(status_code=status.HTTP_204_NO_CONTENT)
    entry = await _entry_in(
        questionnaire_id, entry_id, user=current_user, questionnaire=record, rewriting=False
    )
    await _refuse_its_workshops_holder(record, current_user)
    await db.questionnaireformentry.update(
        where={"id": entry.id},
        data={"deletedAt": datetime.now(UTC), "deletedById": current_user.id},
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{questionnaire_id}/deleted-entries")
async def list_deleted_entries(
    questionnaire_id: str,
    current_user: Any = Depends(require_content_admin),
) -> list[dict[str, Any]]:
    await _require_questionnaire(questionnaire_id, current_user)
    if await shims_dr.form_is_unified(questionnaire_id):
        return public_encode(await shims_dr.entries_for_form(questionnaire_id, deleted=True))
    rows = await db.questionnaireformentry.find_many(
        where={"questionnaireId": questionnaire_id, "deletedAt": {"not": None}},
        order=[{"deletedAt": "desc"}, {"id": "asc"}],
        include={"createdBy": True},
    )
    deleters = await _deleted_by_names(rows)
    return public_encode(
        [
            {
                "id": row.id,
                "title": row.title,
                "respondentName": row.respondentName,
                "source": row.source,
                "createdAt": row.createdAt,
                "createdByName": getattr(row.createdBy, "name", None),
                "deletedAt": row.deletedAt,
                "deletedByName": deleters.get(row.deletedById or ""),
            }
            for row in rows
        ]
    )


@router.post("/{questionnaire_id}/entries/{entry_id}/restore")
async def restore_entry(
    questionnaire_id: str,
    entry_id: str,
    current_user: Any = Depends(require_content_admin),
) -> dict[str, Any]:
    record = await _require_questionnaire(questionnaire_id, current_user)
    if await shims_dr.form_is_unified(questionnaire_id):
        await _refuse_its_workshops_holder(record, current_user)
        return public_encode(await shims_dr.restore_entry(entry_id, current_user))
    entry = await db.questionnaireformentry.find_unique(where={"id": entry_id})
    if entry is None or entry.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    await _refuse_its_workshops_holder(record, current_user)
    restored = await db.questionnaireformentry.update(
        where={"id": entry.id}, data={"deletedAt": None, "deletedById": None}
    )
    return public_encode({"id": restored.id, "title": restored.title, "deletedAt": None})


@router.delete("/{questionnaire_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_questionnaire(
    questionnaire_id: str,
    current_user: Any = Depends(get_current_user),
) -> Response:
    record = await _require_questionnaire(questionnaire_id, current_user)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    await db.questionnaire.update(
        where={"id": questionnaire_id},
        data={"deletedAt": datetime.now(UTC), "deletedById": current_user.id},
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{questionnaire_id}/restore")
async def restore_questionnaire(
    questionnaire_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    record = await require_record(db.questionnaire, questionnaire_id)
    _require_owner(record, current_user)
    await _refuse_its_workshops_holder(record, current_user)
    restored = await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"deletedAt": None, "deletedById": None}
    )
    return public_encode({"id": restored.id, "title": restored.title, "deletedAt": None})


@router.put("/{questionnaire_id}/default")
async def set_default_questionnaire(
    questionnaire_id: str,
    payload: QuestionnaireDefaultUpdate,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    record = await require_record(db.questionnaire, questionnaire_id)
    if payload.isDefault and (record.deletedAt is not None or not record.isActive):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A deleted or retired questionnaire cannot be the default. Restore or reactivate it first.",
        )
    async with db.tx() as tx:
        if payload.isDefault:
            await tx.questionnaire.update_many(
                where={"isDefault": True, "NOT": {"id": questionnaire_id}}, data={"isDefault": False}
            )
        await tx.questionnaire.update(where={"id": questionnaire_id}, data={"isDefault": payload.isDefault})
    return public_encode(await load_form(questionnaire_id, include_retired=True))


async def _entry_in(
    questionnaire_id: str,
    entry_id: str,
    *,
    user: Any,
    questionnaire: Any,
    rewriting: bool,
) -> Any:
    entry = await require_record(db.questionnaireformentry, entry_id)
    if entry.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if entry.questionnaireId != questionnaire_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if rewriting and not _may_rewrite_entry(entry, questionnaire, user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only the designer who recorded this sitting, the owner of the questionnaire, "
                "or an admin can change whose answers these are."
            ),
        )
    return entry


def _may_rewrite_entry(entry: Any, questionnaire: Any, user: Any) -> bool:
    return (
        entry.createdById == user.id
        or getattr(questionnaire, "ownerId", None) == user.id
        or is_content_admin(user)
    )
