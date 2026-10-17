"""``GET /api/design-workshops/{workshop_id}/stages/{stage_key}/copy-sources`` — C9.

What the designer may start this stage from: their own text from earlier workshops (copyable, the
source marked), and other designers' text from the same cluster or craft as a read-only reference
block. See ``services/workshop_copy.py``. The copy itself is an ordinary stage save naming the
copied keys in ``copiedFrom``.

The door is the stage-write door (``for_edit=True``): only somebody who may write this stage is
offered text to put in it. Its own module so the busy ``design_workshops`` route file is untouched.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import Field

from app.api.routes.design_workshops import _require_designer
from app.core.db import db
from app.core.deps import get_current_user
from app.schemas import design_workshop_approvals
from app.schemas.common import APIModel
from app.services import workshop_copy
from app.services.design_workshops import load_workshop_or_404
from app.services.stage_schema import stages
from prisma import Json

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])


@router.get("/{workshop_id}/stages/{stage_key}/copy-sources")
async def stage_copy_sources(
    workshop_id: str, stage_key: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    _require_designer(current_user)
    workshop = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")
    return await workshop_copy.copy_sources(workshop, spec, current_user)


class ConfirmCopiedIn(APIModel):
    fields: list[str] = Field(min_length=1, max_length=64)


@router.post("/{workshop_id}/stages/{stage_key}/confirm-copied")
async def confirm_copied_fields(
    workshop_id: str,
    stage_key: str,
    payload: ConfirmCopiedIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    """ "Keep as is": the designer has checked copied text and it now counts as their answer.

    Touches the singleton's ``fieldProvenance`` only — never ``data`` — so it is not a stage save,
    moves no version and re-submits nothing. A field that is not (or no longer) marked copied is left
    alone, so a repeat press is harmless. Answers the stamps now held for the stage's singleton.
    """
    _require_designer(current_user)
    await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    spec = next((s for s in stages() if s.key == stage_key), None)
    if spec is None or spec.singleton is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown stage")
    row = await db.dwstageentry.find_first(
        where={
            "designWorkshopId": workshop_id,
            "stageKey": stage_key,
            "entityKey": spec.singleton.key,
            "deletedAt": None,
        },
        order={"ordinal": "asc"},
    )
    if row is None:
        return {"provenance": {}, "confirmed": []}
    before = row.fieldProvenance if isinstance(row.fieldProvenance, dict) else {}
    pending = workshop_copy.unconfirmed_copies(before)
    wanted = [key for key in payload.fields if key in pending]
    if not wanted:
        return {"provenance": before, "confirmed": []}
    after = workshop_copy.mark_provenance(before, {}, wanted, current_user)
    # THE SAME IN-FLIGHT GUARD AS ``save_stage``: confirming moves completeness, so it must not land
    # on a report an approval froze between the read above and this write. The header write carries
    # ``NOT FROZEN_WHERE`` and moves ``updatedAt`` (the approval's own compare-and-set), and the
    # entry write is predicated on the version read, so a save that raced in is not overwritten.
    async with db.tx() as tx:
        open_header = await tx.designworkshop.update_many(
            where={"id": workshop_id, "NOT": design_workshop_approvals.FROZEN_WHERE},
            data={"updatedAt": datetime.now(UTC)},
        )
        if open_header != 1:
            frozen = await tx.designworkshop.find_unique(where={"id": workshop_id})
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=design_workshop_approvals.frozen_refusal(
                    getattr(frozen, "status", None), getattr(frozen, "handedOnAt", None)
                )
                or design_workshop_approvals.FROZEN_APPROVED,
            )
        moved = await tx.dwstageentry.update_many(
            where={"id": row.id, "version": row.version, "deletedAt": None},
            data={"fieldProvenance": Json(after)},
        )
        if moved != 1:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=CONFIRM_RACED)
    return {"provenance": after, "confirmed": wanted}


#: 409 when the stage was saved between this request's read and its write.
CONFIRM_RACED = "This stage changed while the copied text was being kept. Reload it and try again."
