from __future__ import annotations

import asyncio
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
from app.core.deps import assert_can_delete, get_current_user
from app.schemas.annual_plan import (
    AnnualPlanEntryUpdate,
    AnnualPlanPromoteRequest,
    RegionalDirectorStatesIn,
)
from app.services import annual_plan, craft_master
from app.services.annual_plan import (
    ANNUAL_PLAN_REFUSAL,
    apply_parsed_plan,
    can_manage_annual_plan,
    can_read_annual_plan,
    entry_payload,
    in_scope,
    plan_scope,
    plan_year_label,
    promote_entry,
    regional_directors,
    reinstate_entry,
    set_regional_states,
    standing_of,
    withdraw_entry,
)
from app.services.annual_plan_xlsx import (
    MAX_PLAN_ROWS,
    MAX_PLAN_YEAR,
    MIN_PLAN_YEAR,
    PRO_FORMA_FILENAME,
    AnnualPlanXlsxError,
    build_annual_plan_pro_forma,
    build_annual_plan_workbook,
    export_filename,
    parse_annual_plan_workbook,
)
from app.services.design_workshops import workshop_summary
from app.services.pagination import MAX_PAGE_SIZE, normalize_pagination, page_payload
from app.services.records import contains
from app.services.uploads import read_workbook_upload
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/annual-plan", tags=["annual-plan"])

MAX_UPLOAD_BYTES = 4 * 1024 * 1024

_WRONG_TYPE_DETAIL = (
    "That is not an Excel workbook. Download the annual plan pro-forma, type the directory into it, "
    "and upload that — or use File > Save As in Excel and choose 'Excel Workbook (.xlsx)'."
)
_EMPTY_DETAIL = "The upload was empty. Attach the filled-in annual plan pro-forma."

_SORTS: dict[str, str] = {
    "plannedStartDate": "plannedStartDate",
    "workshopNo": "workshopNoKey",
    "district": "district",
    "updatedAt": "updatedAt",
}


async def require_annual_plan_manager(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_manage_annual_plan(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=ANNUAL_PLAN_REFUSAL)
    return current_user


async def require_annual_plan_reader(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_read_annual_plan(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=ANNUAL_PLAN_REFUSAL)
    return current_user


async def _entry_in_scope_or_404(entry_id: str, user: Any) -> Any:
    entry = await _entry_or_404(entry_id)
    if not in_scope(entry, await plan_scope(user)):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return entry


def _flag(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "on"}


def _plan_year_or_422(value: str | None) -> int | None:
    if value is None:
        return None
    text = value.strip()
    if not text:
        return None
    try:
        year = int(text)
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"'{text}' is not a year. Choose the financial year this directory is for, or "
                "leave it blank and let the workbook's own Details sheet say."
            ),
        ) from exc
    if not MIN_PLAN_YEAR <= year <= MAX_PLAN_YEAR:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{year} is not a plan year this directory can hold — it has to be between "
                f"{MIN_PLAN_YEAR} and {MAX_PLAN_YEAR}."
            ),
        )
    return year


async def _entry_or_404(entry_id: str) -> Any:
    row = await db.annualplanentry.find_unique(
        where={"id": entry_id}, include={"designWorkshop": True}
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return row



@router.get("/pro-forma")
async def download_pro_forma(_: Any = Depends(require_annual_plan_manager)) -> Response:
    return xlsx_response(await asyncio.to_thread(build_annual_plan_pro_forma), PRO_FORMA_FILENAME)


@router.get("/years")
async def list_plan_years(
    current_user: Any = Depends(require_annual_plan_reader),
) -> list[dict[str, Any]]:
    scope = await plan_scope(current_user)
    where: dict[str, Any] = {} if scope is None else {"state": {"in": scope}}
    rows = await db.annualplanentry.find_many(where=where, order={"planYear": "desc"})
    years: dict[int, dict[str, Any]] = {}
    for row in rows:
        bucket = years.setdefault(
            row.planYear,
            {
                "planYear": row.planYear,
                "planYearLabel": plan_year_label(row.planYear),
                "total": 0,
                "planned": 0,
                "promoted": 0,
                "withdrawn": 0,
            },
        )
        bucket["total"] += 1
        bucket[standing_of(row).lower()] += 1
    return sorted(years.values(), key=lambda item: item["planYear"], reverse=True)


@router.get("/export.xlsx")
async def export_annual_plan(
    planYear: int = Query(...),
    search: str | None = Query(default=None),
    state: str | None = Query(default=None),
    district: str | None = Query(default=None),
    standing: str = Query(default="all"),
    sort: str = Query(default="plannedStartDate"),
    dir: str = Query(default="asc"),
    _: Any = Depends(require_annual_plan_manager),
) -> Response:
    where = _list_where(planYear, search, state, district, standing)
    rows = await db.annualplanentry.find_many(
        where=where, order=_list_order(sort, dir), take=MAX_PLAN_ROWS
    )
    payload = await asyncio.to_thread(
        build_annual_plan_workbook,
        plan_year=planYear,
        filter_note=_filter_note(search, state, district, standing),
        rows=[
            {
                "workshopNo": row.workshopNo,
                "plannedTitle": row.plannedTitle,
                "workshopKind": row.workshopKind,
                "craftName": row.craftName,
                "clusterName": row.clusterName,
                "state": row.state,
                "district": row.district,
                "venue": row.venue,
                "plannedStartDate": row.plannedStartDate,
                "plannedEndDate": row.plannedEndDate,
                "implementingAgency": row.implementingAgency,
                "sponsor": row.sponsor,
                "notes": row.notes,
            }
            for row in rows
        ],
    )
    return xlsx_response(payload, export_filename(planYear))


@router.post("/upload", status_code=status.HTTP_201_CREATED)
async def upload_annual_plan(
    request: Request,
    file: UploadFile = File(...),
    planYear: str | None = Form(default=None),
    withdrawAbsent: str | None = Form(default=None),
    current_user: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    content = await read_workbook_upload(
        file,
        MAX_UPLOAD_BYTES,
        request=request,
        purpose="annual plan workbook",
        wrong_type_detail=_WRONG_TYPE_DETAIL,
        empty_detail=_EMPTY_DETAIL,
    )
    try:
        parsed = await asyncio.to_thread(
            parse_annual_plan_workbook, content, filename=file.filename
        )
    except AnnualPlanXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    typed = _plan_year_or_422(planYear)
    if typed is not None and parsed.planYear is not None and typed != parsed.planYear:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"This workbook says it is the {plan_year_label(parsed.planYear)} plan and it was "
                f"uploaded as the {plan_year_label(typed)} plan. Correct the 'Plan year' on the "
                "workbook's Details sheet, or choose the year the workbook names."
            ),
        )
    year = typed if typed is not None else parsed.planYear
    if year is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "This workbook does not say which year's plan it is. Type the financial year on "
                "its Details sheet, or choose a year before uploading."
            ),
        )

    withdraw_absent = _flag(withdrawAbsent)
    if withdraw_absent and parsed.filterNote:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"This workbook says it is a filtered view of the "
                f"{plan_year_label(year)} plan ({parsed.filterNote}), so it holds only some of the "
                f"year's rows — and 'withdraw the workshops missing from this sheet' would withdraw "
                f"every row the filter left out. Upload it without that box ticked to correct the "
                f"rows it does name, or clear the filters on the list, export the whole year again "
                f"and upload that if you do mean to withdraw workshops."
            ),
        )

    return await apply_parsed_plan(
        parsed,
        plan_year=year,
        actor_id=current_user.id,
        source_filename=file.filename,
        withdraw_absent=withdraw_absent,
    )


def _filter_note(
    search: str | None, state: str | None, district: str | None, standing: str
) -> str | None:
    parts: list[str] = []
    if term := (search or "").strip():
        parts.append(f"Search: '{term}'")
    if value := (state or "").strip():
        parts.append(f"State: {value}")
    if value := (district or "").strip():
        parts.append(f"District: {value}")
    key = (standing or "all").strip().lower()
    if key in {"planned", "promoted", "withdrawn"}:
        parts.append(f"Standing: {key}")
    return " · ".join(parts) if parts else None


def _list_where(
    plan_year: int, search: str | None, state: str | None, district: str | None, standing: str
) -> dict[str, Any]:
    where: dict[str, Any] = {"planYear": plan_year}
    if state:
        where["state"] = state
    if district:
        where["district"] = district
    if search:
        term = search.strip()
        if term:
            clause = contains(term)
            where["OR"] = [
                {"workshopNo": clause},
                {"plannedTitle": clause},
                {"venue": clause},
            ]
    key = (standing or "all").strip().lower()
    if key == "planned":
        where["withdrawnAt"] = None
        where["designWorkshopId"] = None
    elif key == "promoted":
        where["withdrawnAt"] = None
        where["designWorkshopId"] = {"not": None}
    elif key == "withdrawn":
        where["withdrawnAt"] = {"not": None}
    return where


def _list_order(sort: str, direction: str) -> list[dict[str, str]]:
    column = _SORTS.get(sort, "plannedStartDate")
    way = "desc" if str(direction).lower() == "desc" else "asc"
    return [{column: way}, {"workshopNoKey": "asc"}]


@router.get("")
async def list_annual_plan(
    planYear: int = Query(...),
    page: int = Query(default=1, ge=1),
    pageSize: int = Query(default=50, ge=1, le=MAX_PAGE_SIZE),
    search: str | None = Query(default=None),
    state: str | None = Query(default=None),
    district: str | None = Query(default=None),
    standing: str = Query(default="all"),
    sort: str = Query(default="plannedStartDate"),
    dir: str = Query(default="asc"),
    current_user: Any = Depends(require_annual_plan_reader),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)
    where = _list_where(planYear, search, state, district, standing)
    scope = await plan_scope(current_user)
    if scope is not None:
        where = {"AND": [where, {"state": {"in": scope}}]}
    total = await db.annualplanentry.count(where=where)
    rows = await db.annualplanentry.find_many(
        where=where,
        order=_list_order(sort, dir),
        skip=skip,
        take=clean_size,
        include={"designWorkshop": True},
    )
    sanctions = await annual_plan.sanctions_for_rows(list(rows))
    payload = page_payload(
        [entry_payload(row, sanction=sanctions.get(row.id)) for row in rows],
        total,
        clean_page,
        clean_size,
    )
    payload["regionalStates"] = scope
    return payload


@router.get("/regional-directors")
async def list_regional_directors(
    _: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    return {"items": await regional_directors()}


@router.put("/regional-directors/{user_id}")
async def assign_regional_director_states(
    user_id: str,
    payload: RegionalDirectorStatesIn,
    current_user: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    target = await db.user.find_unique(where={"id": user_id}) if user_id.isprintable() else None
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    states = await set_regional_states(target, list(payload.states), actor=current_user)
    return {"id": target.id, "states": states}



@router.get("/{entry_id}")
async def get_annual_plan_entry(
    entry_id: str, current_user: Any = Depends(require_annual_plan_reader)
) -> dict[str, Any]:
    row = await _entry_in_scope_or_404(entry_id, current_user)
    sanctions = await annual_plan.sanctions_for_rows([row])
    return entry_payload(row, sanction=sanctions.get(row.id))


@router.patch("/{entry_id}")
async def update_annual_plan_entry(
    entry_id: str,
    payload: AnnualPlanEntryUpdate,
    current_user: Any = Depends(require_annual_plan_reader),
) -> dict[str, Any]:
    entry = await _entry_in_scope_or_404(entry_id, current_user)
    values = payload.model_dump(exclude_unset=True)
    if not values:
        return entry_payload(entry)
    updated = await db.annualplanentry.update(
        where={"id": entry.id},
        data={**values, "updatedById": current_user.id},
        include={"designWorkshop": True},
    )
    return entry_payload(updated)


@router.post("/{entry_id}/promote", status_code=status.HTTP_201_CREATED)
async def promote_annual_plan_entry(
    entry_id: str,
    payload: AnnualPlanPromoteRequest,
    current_user: Any = Depends(require_annual_plan_manager),
) -> dict[str, Any]:
    entry = await _entry_or_404(entry_id)
    if payload.craftId and payload.craftId != getattr(entry, "craftId", None):
        craft = await craft_master.assert_active_craft(db, payload.craftId)
        await db.annualplanentry.update(
            where={"id": entry.id},
            data={"craftId": craft.id, "craftName": craft.name, "updatedById": current_user.id},
        )
        entry = await _entry_or_404(entry_id)
    record = await promote_entry(
        entry,
        actor=current_user,
        designer_id=payload.designerUserId,
        designer_ids=list(payload.designerUserIds or []),
        template_id=payload.templateId,
    )
    return {"entry": entry_payload(await _entry_or_404(entry_id)), "workshop": workshop_summary(record)}


@router.delete("/entries/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_annual_plan_entry(
    entry_id: str, current_user: Any = Depends(require_annual_plan_manager)
) -> Response:
    assert_can_delete(current_user)
    entry = await _entry_or_404(entry_id)
    if entry.promotedAt is not None or entry.designWorkshopId:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Promoted to a workshop.")
    await db.annualplanentry.delete(where={"id": entry.id})
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{entry_id}/withdraw")
async def withdraw_annual_plan_entry(
    entry_id: str, current_user: Any = Depends(require_annual_plan_manager)
) -> dict[str, Any]:
    entry = await _entry_or_404(entry_id)
    await withdraw_entry(entry, actor_id=current_user.id)
    return entry_payload(await _entry_or_404(entry_id))


@router.post("/{entry_id}/reinstate")
async def reinstate_annual_plan_entry(
    entry_id: str, current_user: Any = Depends(require_annual_plan_manager)
) -> dict[str, Any]:
    entry = await _entry_or_404(entry_id)
    await reinstate_entry(entry, actor_id=current_user.id)
    return entry_payload(await _entry_or_404(entry_id))


__all__ = ["require_annual_plan_manager", "router"]
