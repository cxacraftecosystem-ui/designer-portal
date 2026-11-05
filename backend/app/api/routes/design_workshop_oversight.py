from datetime import UTC, datetime
from typing import Any

from fastapi import (
    APIRouter,
    Depends,
    File,
    HTTPException,
    Query,
    Request,
    Response,
    UploadFile,
    status,
)

from app.api.routes.design_workshops import _parse_date, _provenance_maps, _stages_payload
from app.core.db import db
from app.core.deps import get_current_user
from app.schemas import design_workshop_review_loop as review_loop
from app.schemas.design_workshop_inspections import DwInspectionFeedbackIn, DwInspectionNotesIn
from app.schemas.design_workshop_oversight import (
    DesignWorkshopDesignerIn,
    DesignWorkshopDesignersIn,
    DesignWorkshopOversightCreateIn,
    DesignWorkshopOversightIn,
)
from app.services import (
    craft_master,
    design_workshop_approvals,
    design_workshop_oversight as oversight,
    design_workshop_posts as posts,
    design_workshop_review_aids as review_aids,
    email_outbox,
)
from app.services.artisan_import import import_artisans
from app.services.artisan_xlsx import (
    ArtisanDefaults,
    ArtisanXlsxError,
    build_artisan_pro_forma,
    parse_artisan_workbook,
    pro_forma_filename,
)
from app.services.concurrency import gather_reads
from app.services.custom_sections import definition_payload, load_definition_or_empty
from app.services.design_workshop_inspectors import inspector_rows
from app.services.design_workshop_reader_media import workshop_media_for_reader
from app.services.design_workshops import (
    entry_rows,
    named_designer_team,
    open_design_workshop,
    workshop_completeness,
    workshop_summary,
)
from app.services.designers import assignable_designers_payload, workshop_capable_accounts
from app.services.entry_provenance import resolve_display_names
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import contains, public_encode
from app.services.stage_schema import registry_version
from app.services.uploads import read_upload_bounded
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/design-workshop-oversight", tags=["design-workshops"])



async def require_workshop_assigner(current_user: Any = Depends(get_current_user)) -> Any:
    oversight.assert_may_assign_oversight(current_user)
    return current_user


async def require_officer(current_user: Any = Depends(get_current_user)) -> Any:
    oversight.assert_oversight_surface(current_user)
    return current_user


MAX_ARTISAN_UPLOAD_BYTES = 4 * 1024 * 1024

_XLSX_SUFFIXES = (".xlsx", ".xlsm", ".xltx")


async def _read_artisan_upload(file: UploadFile, request: Request | None = None) -> bytes:
    name = (file.filename or "").lower()
    if name and not name.endswith(_XLSX_SUFFIXES):
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"'{file.filename}' is not an Excel workbook. Fill in the artisan list pro-forma "
                "and upload that, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
            ),
        )
    content = await read_upload_bounded(
        file, MAX_ARTISAN_UPLOAD_BYTES, request=request, purpose="artisan list"
    )
    if not content:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The upload was empty. Attach the filled-in artisan list pro-forma.",
        )
    return content


async def _workshop_for_assignment_or_404(workshop_id: str) -> Any:
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record



@router.get("/officers")
async def list_officers(
    search: str | None = Query(None, max_length=120),
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    return await oversight.officer_directory(search=search, exclude_user_id=current_user.id)


@router.get("/designers")
async def list_assignable_designers(
    search: str | None = Query(None, max_length=120),
    workshopId: str | None = Query(None, max_length=64),
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    from app.services.designers import DIRECTORY_TAKE

    users = await workshop_capable_accounts(
        search=search,
        include_suspended=False,
        exclude_user_id=current_user.id if (workshopId or "").strip() else None,
    )
    return {
        "users": assignable_designers_payload(users),
        "truncated": len(users) >= DIRECTORY_TAKE,
    }


@router.get("/my-posts")
async def read_my_workshop_posts(current_user: Any = Depends(require_workshop_assigner)) -> dict[str, Any]:
    return {"workshops": await posts.staffing_held_by(str(current_user.id))}


@router.get("/artisan-pro-forma")
async def download_artisan_pro_forma(
    workshopId: str | None = Query(None, max_length=64),
    _: Any = Depends(require_workshop_assigner),
) -> Response:
    workshop = await _workshop_for_assignment_or_404(workshopId) if workshopId else None
    return xlsx_response(build_artisan_pro_forma(workshop=workshop), pro_forma_filename(workshop))


@router.post("/workshops", status_code=status.HTTP_201_CREATED)
async def open_workshop_from_oversight(
    payload: DesignWorkshopOversightCreateIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    lead_id, granted_ids = named_designer_team(payload.designerUserId, payload.designerUserIds)

    columns: dict[str, Any] = {
        "title": payload.title.strip(),
        **{
            key: value
            for key, value in (
                ("workshopKind", payload.workshopKind),
                ("clusterName", payload.clusterName),
                ("state", payload.state),
                ("district", payload.district),
            )
            if value
        },
    }
    for key in ("startDate", "endDate"):
        parsed = _parse_date(getattr(payload, key))
        if parsed:
            columns[key] = parsed
    columns.update(
        await craft_master.craft_columns(
            db, payload.model_dump(include={"craftId", "craftName"}, exclude_none=True)
        )
    )
    columns = {key: value for key, value in columns.items() if value is not None}

    seeded = {
        key: value
        for key, value in (
            ("workshopKind", payload.workshopKind),
            ("craftRef", columns.get("craftId")),
            ("craftName", columns.get("craftName")),
            ("clusterName", payload.clusterName),
            ("state", payload.state),
            ("district", payload.district),
            ("startDate", payload.startDate),
            ("endDate", payload.endDate),
        )
        if value
    }

    record = await open_design_workshop(
        actor=current_user,
        columns=columns,
        designer_id=lead_id,
        designer_ids=granted_ids,
        seeded=seeded,
    )
    return workshop_summary(record)


@router.get("/workshops")
async def list_assignable_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    staffed: str | None = Query(None, max_length=16),
    _: Any = Depends(require_workshop_assigner),
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
    wanted_staffing = (staffed or "").strip().lower()
    if wanted_staffing in {"staffed", "unstaffed"}:
        empty = [{"designerName": None}, {"designerName": ""}]
        where.setdefault("AND", []).append(
            {"OR": empty} if wanted_staffing == "unstaffed" else {"NOT": {"OR": empty}}
        )
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    total, rows = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(
            where=where, skip=skip, take=clean_size, order={"createdAt": "desc"}
        ),
    )
    return page_payload([workshop_summary(r) for r in rows], total, clean_page, clean_size)


@router.get("/assigned")
async def list_overseen_workshops(
    page: int = 1,
    pageSize: int = 20,
    search: str | None = Query(None, max_length=120),
    sort: str | None = Query(None),
    current_user: Any = Depends(require_officer),
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
    where.setdefault("AND", []).append(oversight.oversight_by_clause(current_user.id))

    chosen_sort = review_aids.check_sort(sort)
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    truncated = False
    if chosen_sort == "complete":
        rows, total, readiness, truncated = await review_aids.ranked_page(
            where,
            {"createdAt": "desc"},
            page=clean_page,
            page_size=clean_size,
            sort=chosen_sort,
            cleared=None,
        )
    else:
        order = review_aids.dated_order("lastHandedInAt", chosen_sort) or {"createdAt": "desc"}
        total, rows = await gather_reads(
            db.designworkshop.count(where=where),
            db.designworkshop.find_many(where=where, skip=skip, take=clean_size, order=order),
        )
        readiness = await review_aids.readiness_for(rows)
    answer = page_payload(
        [{**workshop_summary(r), "readiness": readiness.get(r.id)} for r in rows],
        total,
        clean_page,
        clean_size,
    )
    if truncated:
        answer["candidatesTruncated"] = True
    return answer


@router.get("/assigned/{workshop_id}")
async def read_overseen_workshop(
    workshop_id: str, current_user: Any = Depends(require_officer)
) -> dict[str, Any]:
    record = await oversight.load_overseen_workshop_or_404(workshop_id, current_user)
    entries = await entry_rows(workshop_id)
    definition = await load_definition_or_empty(workshop_id)
    summary = workshop_summary(record)
    summary["stages"] = _stages_payload(entries)
    await resolve_display_names(_provenance_maps(summary["stages"]))
    summary["completeness"] = workshop_completeness(entries, definition=definition)
    summary["schemaVersion"] = registry_version()
    summary["customSchemaVersion"] = definition.version
    summary["customSections"] = definition_payload(definition)
    summary["oversight"] = await oversight.oversight_rows(workshop_id)
    summary.update(await design_workshop_approvals.approval_names(record))
    summary["decisions"] = await design_workshop_approvals.decision_history(workshop_id)
    register, inspectors, clearances, snapshots = await gather_reads(
        review_aids.annotated_register(workshop_id, entries),
        inspector_rows(workshop_id),
        review_aids.clearance_rows(workshop_id),
        review_aids.snapshot_list(workshop_id),
    )
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = register
    summary["inspectors"] = review_aids.with_verdicts(
        inspectors, clearances, int(getattr(record, "submissionRound", 0) or 0)
    )
    summary["roundSnapshots"] = snapshots
    summary["mayRecordFeedback"] = True
    summary["readOnly"] = True
    return summary


@router.get("/assigned/{workshop_id}/media")
async def read_overseen_workshop_media(
    workshop_id: str, current_user: Any = Depends(require_officer)
) -> dict[str, Any]:
    await oversight.load_overseen_workshop_or_404(workshop_id, current_user)
    return await workshop_media_for_reader(workshop_id, current_user)


@router.get("/assigned/{workshop_id}/rounds/{round}")
async def read_overseen_round_snapshot(
    workshop_id: str, round: int, current_user: Any = Depends(require_officer)
) -> dict[str, Any]:
    await oversight.load_overseen_workshop_or_404(workshop_id, current_user)
    return await review_aids.snapshot_payload(workshop_id, round)



async def _note_post(workshop_id: str, user: Any) -> str:
    rows = await db.designworkshopoversight.find_many(
        where={"designWorkshopId": workshop_id, "userId": user.id}
    )
    held = {str(getattr(row.capacity, "value", row.capacity)) for row in rows}
    for post in ("REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR"):
        if post in held:
            return post
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Only this workshop's Assistant or Regional Director can file notes here.",
    )


def _under_review_or_422(record: Any) -> None:
    current = str(getattr(record, "status", "") or "")
    if current not in review_loop.UNDER_REVIEW:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=review_loop.review_closed_refusal(
                current, handed_on=getattr(record, "handedOnAt", None) is not None
            ),
        )


async def _file_officer_notes(
    workshop_id: str,
    user: Any,
    notes: list[tuple[str | None, str | None, str | None]],
) -> dict[str, Any]:
    record = await oversight.load_overseen_workshop_or_404(workshop_id, user)
    _under_review_or_422(record)
    post = await _note_post(workshop_id, user)
    try:
        batch = review_loop.note_batch(
            workshop_id=workshop_id,
            round=int(getattr(record, "submissionRound", 0) or 0),
            actor_id=user.id,
            at=datetime.now(UTC),
            notes=notes,
            actor_post=post,
        )
    except review_loop.InspectionRuleViolation as refused:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(refused)
        ) from refused
    async with db.tx() as tx:
        for plan in batch.rows:
            await tx.dwinspectionfeedback.create(data=dict(plan.data))
    await email_outbox.notify_review_note(
        record,
        actor=user,
        note=batch.digest,
        stage_key=notes[0][1] if len(notes) == 1 else None,
        sent_back=False,
    )
    summary = workshop_summary(record)
    summary["inspectionFeedback"], summary["inspectionFeedbackTruncated"] = (
        await review_aids.annotated_register(workshop_id)
    )
    summary["readOnly"] = True
    summary["mayRecordFeedback"] = True
    return summary


@router.post("/assigned/{workshop_id}/feedback", status_code=status.HTTP_201_CREATED)
async def record_officer_note(
    workshop_id: str, payload: DwInspectionFeedbackIn, current_user: Any = Depends(require_officer)
) -> dict[str, Any]:
    return await _file_officer_notes(
        workshop_id, current_user, [(payload.note, payload.stageKey, payload.fieldKey)]
    )


@router.post("/assigned/{workshop_id}/feedback/batch", status_code=status.HTTP_201_CREATED)
async def record_officer_notes(
    workshop_id: str, payload: DwInspectionNotesIn, current_user: Any = Depends(require_officer)
) -> dict[str, Any]:
    return await _file_officer_notes(
        workshop_id, current_user, [(n.note, n.stageKey, n.fieldKey) for n in payload.notes]
    )



@router.get("/{workshop_id}")
async def read_workshop_oversight(
    workshop_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    record = await _workshop_for_assignment_or_404(workshop_id)
    return {
        "workshopId": record.id,
        "title": record.title,
        "status": str(getattr(record.status, "value", record.status)),
        "designerName": getattr(record, "designerName", None),
        "designers": await oversight.named_designer_rows(record),
        "oversight": await oversight.oversight_rows(workshop_id),
    }


@router.put("/{workshop_id}")
async def set_workshop_oversight(
    workshop_id: str,
    payload: DesignWorkshopOversightIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    await _workshop_for_assignment_or_404(workshop_id)
    sent = payload.model_fields_set
    wanted: dict[str, str | None] = {}
    if "assistantDirectorId" in sent:
        wanted["ASSISTANT_DIRECTOR"] = payload.assistantDirectorId
    if "regionalDirectorId" in sent:
        wanted["REGIONAL_DIRECTOR"] = payload.regionalDirectorId

    await oversight.assert_may_hold(workshop_id, wanted, appointing=current_user.id)
    rows = await oversight.apply_oversight(
        workshop_id, wanted=wanted, assigned_by_id=current_user.id
    )
    return {"oversight": rows}


@router.put("/{workshop_id}/designer")
async def set_workshop_designer(
    workshop_id: str,
    payload: DesignWorkshopDesignerIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    record = await _workshop_for_assignment_or_404(workshop_id)
    await posts.refuse_a_holders_write(workshop_id, current_user)
    return await oversight.reassign_designer(record, payload.designerId, actor=current_user)


@router.put("/{workshop_id}/designers")
async def set_workshop_designers(
    workshop_id: str,
    payload: DesignWorkshopDesignersIn,
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    record = await _workshop_for_assignment_or_404(workshop_id)
    await posts.refuse_a_holders_write(workshop_id, current_user)
    return await oversight.set_named_designers(
        record,
        user_ids=list(payload.userIds),
        lead_user_id=payload.leadUserId,
        actor=current_user,
    )


@router.post("/{workshop_id}/artisans/upload", status_code=status.HTTP_201_CREATED)
async def upload_artisan_list(
    request: Request,
    workshop_id: str,
    file: UploadFile = File(...),
    current_user: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    workshop = await _workshop_for_assignment_or_404(workshop_id)
    await posts.refuse_a_holders_write(workshop_id, current_user)
    return await run_artisan_import(workshop, file, request, current_user)


async def run_artisan_import(
    workshop: Any, file: UploadFile, request: Request | None, current_user: Any
) -> dict[str, Any]:
    import asyncio

    workshop_id = str(workshop.id)
    venue_fix = await _venue_fix(workshop_id)
    if venue_fix is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "This workshop has no venue location yet, so an imported artisan would have no "
                "coordinate to record. Capture the venue location on stage 1 (Workshop setup) and "
                "upload the list again."
            ),
        )

    content = await _read_artisan_upload(file, request)
    defaults = ArtisanDefaults(
        state=str(getattr(workshop, "state", "") or ""),
        district=str(getattr(workshop, "district", "") or ""),
        place=str(getattr(workshop, "venue", "") or ""),
        craftName=str(getattr(workshop, "craftName", "") or ""),
    )
    try:
        parsed = await asyncio.to_thread(
            parse_artisan_workbook, content, filename=file.filename, defaults=defaults
        )
    except ArtisanXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    if parsed.designWorkshopId and parsed.designWorkshopId != workshop_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"That workbook was filled in for a different workshop. Its Details sheet names "
                f"{parsed.designWorkshopId} and this upload is going into {workshop_id}. Download "
                f"the pro-forma from the workshop you mean, or correct the Design workshop ID on "
                f"the Details sheet."
            ),
        )

    report = await import_artisans(
        parsed,
        workshop=workshop,
        actor=current_user,
        venue_fix=venue_fix,
        source_filename=file.filename,
    )
    del content
    return public_encode(report, current_user)


@router.get("/{workshop_id}/artisans")
async def list_workshop_artisans(
    workshop_id: str,
    offset: int = Query(0, ge=0),
    _: Any = Depends(require_workshop_assigner),
) -> dict[str, Any]:
    offset = offset if isinstance(offset, int) else 0
    await _workshop_for_assignment_or_404(workshop_id)
    return await oversight.linked_artisan_rows(workshop_id, offset)


@router.delete("/{workshop_id}/artisans/{artisan_id}")
async def unlink_workshop_artisan(
    workshop_id: str, artisan_id: str, current_user: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    await _workshop_for_assignment_or_404(workshop_id)
    await posts.refuse_a_holders_write(workshop_id, current_user)
    unlinked = await oversight.unlink_artisan_from_workshop(workshop_id, artisan_id)
    return {"unlinked": unlinked}


@router.get("/{workshop_id}/artisan-imports")
async def list_artisan_imports(
    workshop_id: str, _: Any = Depends(require_workshop_assigner)
) -> dict[str, Any]:
    await _workshop_for_assignment_or_404(workshop_id)
    return await artisan_import_history(workshop_id)


async def artisan_import_history(workshop_id: str) -> dict[str, Any]:
    rows = await db.dwartisanimport.find_many(
        where={"designWorkshopId": workshop_id},
        include={"uploadedBy": True},
        order={"createdAt": "desc"},
        take=100,
    )
    return {
        "imports": [
            {
                "id": row.id,
                "sourceFilename": row.sourceFilename,
                "sheetName": row.sheetName,
                "rowsRead": row.rowsRead,
                "artisansCreated": row.artisansCreated,
                "artisansLinked": row.artisansLinked,
                "rowsRefused": row.rowsRefused,
                "participantsCreated": row.participantsCreated,
                "problems": row.problems,
                "uploadedBy": getattr(getattr(row, "uploadedBy", None), "name", None),
                "createdAt": row.createdAt.isoformat() if row.createdAt else None,
            }
            for row in rows
        ]
    }


async def _venue_fix(workshop_id: str) -> dict[str, Any] | None:
    row = await db.dwstageentry.find_first(
        where={
            "designWorkshopId": workshop_id,
            "stageKey": "WORKSHOP_SETUP",
            "entityKey": "workshopSetup",
            "deletedAt": None,
        }
    )
    data = getattr(row, "data", None)
    if not isinstance(data, dict):
        return None
    fix = data.get("venueLocation")
    if not isinstance(fix, dict):
        return None
    try:
        lat = float(fix["lat"])
        lon = float(fix["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    out: dict[str, Any] = {"lat": lat, "lon": lon}
    accuracy = fix.get("accuracy")
    if accuracy is not None:
        try:
            out["accuracy"] = float(accuracy)
        except (TypeError, ValueError):
            pass
    return out
