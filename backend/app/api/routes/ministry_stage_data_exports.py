"""``/api/ministry-dashboard/stage-data-exports`` — the background stage-data export (C15).

Its own module and not a route in ``ministry_dashboard.py``, because that router is read-only by
construction (every route a GET, swept by ``tests/test_ministry_dashboard_gate.py``) and its
register CSV is pinned to carry no stage content. This one is the deliberate exception the owner
ruled for on 2026-10-17: stage data, with identity numbers masked, built in the background. See
``services/ministry_export.py``.

THE SAME DOOR as the dashboard (``require_ministry_dashboard_reader``), and every job belongs to the
account that asked: another reader's job is a 404, byte-identical to one that does not exist.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Response, status
from pydantic import Field

from app.api.routes.ministry_dashboard import require_ministry_dashboard_reader
from app.core.db import db
from app.schemas.common import APIModel
from app.services import ministry_export as exports, naming
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/ministry-dashboard/stage-data-exports", tags=["ministry"])

PENDING_REFUSAL = (
    f"You already have {exports.MAX_PENDING_PER_USER} exports being prepared. "
    "Wait for one to finish."
)


class StageDataExportRequest(APIModel):
    """The dashboard's narrowing at the moment the reader asked. All optional: none is everything."""

    search: str | None = Field(default=None, max_length=120)
    standing: str | None = Field(default=None, max_length=32)
    attention: str | None = Field(default=None, max_length=32)
    state: str | None = Field(default=None, max_length=80)


async def _own_or_404(export_id: str, user: Any) -> Any:
    row = await db.ministrydataexport.find_unique(where={"id": export_id})
    if row is None or row.requestedById != getattr(user, "id", None):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return row


@router.post("", status_code=status.HTTP_202_ACCEPTED)
async def request_stage_data_export(
    payload: StageDataExportRequest,
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """Queue one export. Answered at once; the queue worker builds the file."""
    pending = await db.ministrydataexport.count(
        where={
            "requestedById": current_user.id,
            "status": {"in": [exports.QUEUED, exports.RUNNING]},
        }
    )
    if pending >= exports.MAX_PENDING_PER_USER:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=PENDING_REFUSAL)
    from prisma import Json

    filters = {key: value for key, value in payload.model_dump().items() if value}
    row = await db.ministrydataexport.create(
        data={"requestedById": current_user.id, "filters": Json(filters)}
    )
    return exports.job_payload(row)


@router.get("")
async def list_stage_data_exports(
    current_user: Any = Depends(require_ministry_dashboard_reader),
) -> dict[str, Any]:
    """The caller's own exports, newest first. The file is never in this list — nor read for it."""
    return {
        "items": await exports.list_jobs(str(current_user.id)),
        "keepDays": exports.EXPORT_TTL_DAYS,
    }


@router.get("/{export_id}/download")
async def download_stage_data_export(
    export_id: str, current_user: Any = Depends(require_ministry_dashboard_reader)
) -> Response:
    row = await _own_or_404(export_id, current_user)
    if row.status != exports.READY or row.content is None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This export is not ready to download.",
        )
    return xlsx_response(row.content.decode(), row.fileName or naming.export_filename("Ministry", "Export", "xlsx", subject="Stage Data"))
