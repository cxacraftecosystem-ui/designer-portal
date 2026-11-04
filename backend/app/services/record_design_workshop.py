from __future__ import annotations

from typing import Any

from app.core.db import db
from app.services import design_workshop_posts

DESIGN_WORKSHOP_KEY = "designWorkshopId"

NOT_ON_WORKSHOP = (
    "You're not on that design workshop, so records can't be filed under it. Choose one you're on."
)


async def assert_may_file_under(workshop_id: str | None, user: Any) -> None:
    if not workshop_id:
        return

    from fastapi import HTTPException, status

    from app.services.design_workshops import load_workshop_or_404

    try:
        await load_workshop_or_404(workshop_id, user, for_edit=True)
    except HTTPException as refusal:
        if refusal.status_code != status.HTTP_404_NOT_FOUND:
            raise
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NOT_ON_WORKSHOP) from None


WORKSHOP_DELETED = "This workshop is deleted. Restore it before editing."


async def refuse_if_the_workshop_is_deleted(workshop_id: str) -> None:
    row = await db.designworkshop.find_unique(where={"id": workshop_id})
    if row is not None and row.deletedAt is not None:
        from fastapi import HTTPException, status

        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=WORKSHOP_DELETED)


async def assert_may_write_a_record_filed_under(workshop_id: str | None, user: Any) -> None:
    if not workshop_id:
        return
    await design_workshop_posts.refuse_a_holders_write(workshop_id, user)


async def assert_payload_workshop(
    data: dict[str, Any], user: Any, *, filed_under: str | None
) -> None:
    await assert_may_write_a_record_filed_under(filed_under, user)
    if DESIGN_WORKSHOP_KEY not in data:
        return
    destination = data.get(DESIGN_WORKSHOP_KEY)
    if filed_under and (destination or "").strip() == filed_under:
        await refuse_if_the_workshop_is_deleted(filed_under)
        return
    await assert_may_file_under(destination, user)
