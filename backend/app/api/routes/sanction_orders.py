from __future__ import annotations

import asyncio
from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile, status
from fastapi.responses import Response

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user
from app.schemas.sanction_orders import (
    SanctionImportConfirm,
    SanctionOrderCreate,
    SanctionOrderUpdate,
)
from app.services import annual_plan, designers, sanction_import, sanction_orders
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import add_date_range, contains, with_id_tiebreak
from app.services.sanction_orders import (
    SANCTION_INCLUDE,
    SANCTION_ORDER_REFUSAL,
    can_record_sanction_orders,
)
from app.services.sanction_orders_xlsx import (
    PRO_FORMA_FILENAME,
    SanctionXlsxError,
    build_sanction_pro_forma,
    parse_sanction_workbook,
)
from app.services.uploads import read_workbook_upload
from app.services.xlsx_report import xlsx_response

_WRONG_TYPE_DETAIL = (
    "That is not an Excel workbook. Download the sanction order pro-forma, type the orders into "
    "it, and upload that — or use File > Save As in Excel and choose 'Excel Workbook (.xlsx)'."
)
_EMPTY_DETAIL = "The upload was empty. Attach the filled-in sanction order pro-forma."

router = APIRouter(prefix="/sanction-orders", tags=["sanction-orders"])


async def require_sanction_recorder(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_record_sanction_orders(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=SANCTION_ORDER_REFUSAL)
    return current_user



@router.post("", status_code=status.HTTP_201_CREATED)
async def record_sanction_order(
    payload: SanctionOrderCreate,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    return await sanction_orders.create_from_sanction(payload, officer)


@router.post("/upload")
async def upload_sanction_orders(
    request: Request,
    file: UploadFile = File(...),
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    content = await read_workbook_upload(
        file,
        sanction_import.MAX_UPLOAD_BYTES,
        request=request,
        purpose="sanction order workbook",
        wrong_type_detail=_WRONG_TYPE_DETAIL,
        empty_detail=_EMPTY_DETAIL,
    )
    try:
        parsed = await asyncio.to_thread(
            parse_sanction_workbook, content, filename=file.filename
        )
    except SanctionXlsxError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(exc)
        ) from exc

    return await sanction_import.review_sheet(parsed, officer=officer)


@router.post("/upload/confirm", status_code=status.HTTP_201_CREATED)
async def confirm_sanction_orders(
    payload: SanctionImportConfirm,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    return await sanction_import.apply_confirmed_rows(payload, officer)


@router.patch("/{sanction_id}")
async def update_sanction_order(
    sanction_id: str,
    payload: SanctionOrderUpdate,
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    row = await _load(sanction_id)
    updated = await db.sanctionorder.update(
        where={"id": row.id},
        data={"notes": payload.notes},
        include=SANCTION_INCLUDE,
    )
    return await sanction_orders.sanction_payload(updated)


@router.delete("/{sanction_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_sanction_order(
    sanction_id: str,
    officer: Any = Depends(require_sanction_recorder),
) -> Response:
    assert_can_delete(officer)
    row = await _load(sanction_id)
    linked = await db.designworkshop.count(where={"id": row.designWorkshopId, "deletedAt": None})
    if linked:
        noun = "workshop" if linked == 1 else "workshops"
        pronoun = "it" if linked == 1 else "them"
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Linked to {linked} {noun}. Unlink {pronoun} first.",
        )
    await db.sanctionorder.delete(where={"id": row.id})
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{sanction_id}/credential-link")
async def reissue_credential_link(
    sanction_id: str,
    designer_user_id: str | None = Query(default=None, alias="designerUserId", max_length=64),
    deliver_by: str = Query(default="COPY_LINK", alias="deliverBy", pattern="^(COPY_LINK|EMAIL)$"),
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    row = await _load(sanction_id)
    return await sanction_orders.reissue_credential_link(
        row, officer, designer_user_id=designer_user_id, deliver_by=deliver_by
    )


@router.post("/{sanction_id}/remind")
async def remind_designers(
    sanction_id: str, officer: Any = Depends(require_sanction_recorder)
) -> dict[str, Any]:
    row = await _load(sanction_id)
    return await sanction_orders.remind_designers(row, officer)


@router.post("/{sanction_id}/credential-link/revoke")
async def revoke_credential_link(
    sanction_id: str,
    token_id: str = Query(..., alias="tokenId"),
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    from app.services import credential_links

    row = await _load(sanction_id)
    token = (
        await db.passwordresettoken.find_unique(where={"id": token_id})
        if token_id.isprintable()
        else None
    )
    named = {row.designerUserId} | {
        member.designerUserId for member in (getattr(row, "designers", None) or [])
    }
    if token is None or token.userId not in named:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No sign-in link with that id belongs to a designer on this sanction order.",
        )
    await credential_links.revoke_link(token_id)
    return {"ok": True}



@router.get("/awaiting-count")
async def awaiting_sanction_count(
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    return {"awaiting": await sanction_orders.awaiting_count()}


@router.get("")
async def list_sanction_orders(
    page: int = Query(1, ge=1),
    pageSize: int = Query(50, ge=1, le=200),
    search: str | None = None,
    mine: bool = Query(default=False),
    dateFrom: datetime | None = Query(default=None),
    dateTo: datetime | None = Query(default=None),
    sort: str | None = Query(default=None),
    dir: str | None = Query(default=None),
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    clean_page, clean_size, skip = normalize_pagination(page, pageSize)

    where: dict[str, Any] = {}
    if mine:
        where["createdById"] = getattr(officer, "id", None)
    if search:
        term = search.strip()
        if term:
            where["OR"] = [
                {"sanctionOrderNo": contains(term)},
                {"sanctionOrderKey": contains(sanction_orders.normalise_sanction_order_no(term))},
                {"designerEmail": contains(term)},
                {"designerUser": {"is": {"name": contains(term)}}},
            ]
    add_date_range(where, "sanctionOrderDate", dateFrom, dateTo)

    direction = "asc" if str(dir or "").lower() == "asc" else "desc"
    column = sort if sort in _SORTABLE else "sanctionOrderDate"
    total, rows = await gather_reads(
        db.sanctionorder.count(where=where),
        db.sanctionorder.find_many(
            where=where,
            skip=skip,
            take=clean_size,
            order=with_id_tiebreak({column: direction}),
            include=SANCTION_INCLUDE,
        ),
    )
    items = await sanction_orders.sanction_payloads(rows)
    return page_payload(items, total, clean_page, clean_size)


_SORTABLE = frozenset({"sanctionOrderDate", "sanctionAmount", "createdAt", "sanctionOrderNo"})


@router.get("/designers")
async def list_sanction_designers(
    search: str | None = Query(None, max_length=120),
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    users = await designers.workshop_capable_accounts(
        search=search, include_suspended=False, include_admins=False
    )
    return {
        "users": designers.assignable_designers_payload(users),
        "truncated": len(users) >= designers.DIRECTORY_TAKE,
    }


@router.get("/planned-workshops")
async def find_planned_workshops(
    workshopNo: str = Query(..., min_length=1, max_length=120),
    officer: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    return await annual_plan.sanction_candidates(
        workshopNo, scope=await annual_plan.sanction_pick_scope(officer)
    )


@router.get("/pro-forma.xlsx")
async def download_sanction_pro_forma(
    _: Any = Depends(require_sanction_recorder),
) -> Response:
    return xlsx_response(build_sanction_pro_forma(), PRO_FORMA_FILENAME)


@router.get("/{sanction_id}")
async def get_sanction_order(
    sanction_id: str,
    _: Any = Depends(require_sanction_recorder),
) -> dict[str, Any]:
    return await sanction_orders.sanction_payload(await _load(sanction_id))


async def _load(sanction_id: str) -> Any:
    row = await db.sanctionorder.find_unique(where={"id": sanction_id}, include=SANCTION_INCLUDE)
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Sanction order not found"
        )
    return row


__all__ = ["require_sanction_recorder", "router"]
