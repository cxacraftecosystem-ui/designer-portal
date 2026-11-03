from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.deps import get_current_user, require_admin
from app.schemas.design_workshop_access import (
    MAX_SCANNED_CODE,
    DesignWorkshopAccessDecisionIn,
    DesignWorkshopAccessRequestIn,
)
from app.schemas.design_workshop_grants import JoinCardMintIn, JoinCardRedeemIn
from app.services import design_workshop_access, design_workshop_grants
from app.services.design_workshop_access import STATUS_FILTERS, ScannedCodeRefused
from app.services.design_workshop_grants import CARD_REFUSED_DETAIL, CardRefused

router = APIRouter(prefix="/design-workshop-access", tags=["design-workshops"])

RECEIVED_DETAIL = (
    "If that workshop exists and you are not already on it, an administrator can now see that you "
    "have asked to join. Asking again will not send a second request."
)


@router.post("/requests", status_code=status.HTTP_202_ACCEPTED)
async def request_access(
    payload: DesignWorkshopAccessRequestIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    try:
        await design_workshop_access.file_request(
            current_user,
            workshop_id=payload.workshopId,
            scanned_code=payload.scannedCode,
            note=payload.note,
            scanned_at=payload.scannedAt,
        )
    except ScannedCodeRefused as refusal:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal.detail
        ) from refusal
    return {"received": True, "detail": RECEIVED_DETAIL}


@router.get("/preview")
async def preview_scanned_workshop(
    code: str = Query(..., min_length=1, max_length=MAX_SCANNED_CODE),
    current_user: Any = Depends(get_current_user),
) -> dict[str, str]:
    try:
        answer = await design_workshop_access.scanned_workshop_title(code)
    except ScannedCodeRefused as refusal:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal.detail
        ) from refusal
    if answer is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return answer


@router.get("/requests")
async def list_requests(
    statusFilter: str = Query("PENDING"),
    _: Any = Depends(require_admin),
) -> dict[str, Any]:
    wanted = (statusFilter or "PENDING").strip().upper()
    if wanted not in STATUS_FILTERS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"statusFilter is one of {', '.join(STATUS_FILTERS)}.",
        )
    return await design_workshop_access.queue(wanted)


@router.post("/requests/{request_id}/decide")
async def decide_request(
    request_id: str,
    payload: DesignWorkshopAccessDecisionIn,
    current_user: Any = Depends(require_admin),
) -> dict[str, Any]:
    return await design_workshop_access.decide(
        request_id,
        decision=(payload.status or "").strip().upper(),
        note=payload.note,
        admin=current_user,
    )



@router.post("/grants", status_code=status.HTTP_201_CREATED)
async def mint_grant(
    payload: JoinCardMintIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await design_workshop_grants.mint_grant(
        current_user,
        record_type=payload.recordType,
        record_id=payload.recordId,
        max_uses=payload.maxUses,
        days_valid=payload.daysValid,
        label=payload.label,
    )


@router.get("/grants/{record_id}")
async def list_grants(
    record_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await design_workshop_grants.list_grants(record_id, current_user)


@router.post("/grants/{token_id}/revoke")
async def revoke_grant(
    token_id: str,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    return await design_workshop_grants.revoke_grant(token_id, current_user)


@router.post("/redemptions")
async def redeem_grant(
    payload: JoinCardRedeemIn,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    try:
        return await design_workshop_grants.redeem(
            current_user,
            code=payload.code,
            scanned_at_client=payload.scannedAt,
            scanned_at_elapsed_sec=payload.scannedAtElapsedSec,
            synced_at_elapsed_sec=payload.syncedAtElapsedSec,
            boot_id=payload.bootId,
            clock_jump_observed=payload.clockJumpObserved,
        )
    except ScannedCodeRefused as refusal:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=refusal.detail
        ) from refusal
    except CardRefused as refusal:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=CARD_REFUSED_DETAIL
        ) from refusal
