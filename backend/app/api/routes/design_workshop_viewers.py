from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import require_admin
from app.schemas.design_workshop_viewers import DesignWorkshopViewersIn
from app.services import design_workshop_posts as posts
from app.services.design_workshop_viewers import (
    eligible_viewers,
    replace_viewers,
    viewer_rows,
)

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])


async def _workshop_or_404(workshop_id: str) -> Any:
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


@router.get("/eligible-viewers")
async def list_eligible_viewers(
    search: str | None = Query(None, max_length=120),
    workshopId: str | None = Query(None, max_length=64),
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    return await eligible_viewers(
        search=search,
        exclude_user_id=current_user.id if (workshopId or "").strip() else None,
    )


@router.get("/{workshop_id}/viewers")
async def list_viewers(workshop_id: str, _: Any = Depends(require_admin)) -> dict[str, Any]:
    await _workshop_or_404(workshop_id)
    return {"viewers": await viewer_rows(workshop_id)}


@router.put("/{workshop_id}/viewers")
async def set_viewers(
    workshop_id: str,
    payload: DesignWorkshopViewersIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    record = await _workshop_or_404(workshop_id)
    await posts.refuse_a_holders_write(workshop_id, current_user)
    viewers = await replace_viewers(
        workshop_id,
        payload.userIds,
        creator_id=record.createdById,
        granted_by_id=current_user.id,
    )
    return {"viewers": viewers}
