import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Response, status

from app.api.routes.auth import admit_signed_in_account, assert_access_admits, open_session
from app.core.db import db
from app.core.deps import get_current_user, invalidate_cached_user, is_break_glass_master
from app.scale.rate_limit import account_credential_attempt, account_credential_refund
from app.schemas.auth import TokenResponse, TwoFactorCodeRequest, TwoFactorVerifyRequest
from app.services import two_factor

router = APIRouter(prefix="/auth/two-factor", tags=["auth"])
logger = logging.getLogger(__name__)

CODE_THROTTLED_DETAIL = "Too many codes were tried. Wait a few minutes, then try again."

_STATUS = {
    "invalid": status.HTTP_400_BAD_REQUEST,
    "conflict": status.HTTP_409_CONFLICT,
    "dead": status.HTTP_401_UNAUTHORIZED,
    "locked": status.HTTP_429_TOO_MANY_REQUESTS,
}


def _refuse(error: two_factor.TwoFactorError, *, wrong_code_status: int | None = None) -> HTTPException:
    code = _STATUS[error.kind]
    if error.kind == "invalid" and wrong_code_status is not None:
        code = wrong_code_status
    return HTTPException(status_code=code, detail=error.detail)


def _spend_budget(user_id: str) -> None:
    allowed, _retry = account_credential_attempt(user_id)
    if not allowed:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=CODE_THROTTLED_DETAIL)


async def _fresh(user_id: str) -> Any:
    row = await db.user.find_unique(where={"id": user_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in to continue.")
    return row


@router.get("")
async def two_factor_status(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    row = await _fresh(current_user.id)
    if two_factor.has_stale_pending(row):
        await two_factor.clear_pending(current_user.id)
        row = await _fresh(current_user.id)
    return two_factor.status_of(row)


@router.post("/setup/cancel", status_code=status.HTTP_204_NO_CONTENT)
async def two_factor_setup_cancel(current_user: Any = Depends(get_current_user)) -> Response:
    if await two_factor.clear_pending(current_user.id):
        logger.info("two-factor: account %s left setup without confirming; pending key discarded", current_user.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/setup")
async def two_factor_setup(current_user: Any = Depends(get_current_user)) -> dict[str, str]:
    try:
        return await two_factor.begin_setup(await _fresh(current_user.id))
    except two_factor.TwoFactorError as error:
        raise _refuse(error) from error


@router.post("/enable")
async def two_factor_enable(
    payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)
) -> dict[str, list[str]]:
    _spend_budget(current_user.id)
    try:
        codes = await two_factor.enable(await _fresh(current_user.id), payload.code)
    except two_factor.TwoFactorError as error:
        raise _refuse(error) from error
    account_credential_refund(current_user.id)
    invalidate_cached_user(current_user.id)
    logger.info("two-factor: account %s turned two-step verification on", current_user.id)
    return {"recovery_codes": codes}


@router.post("/disable", status_code=status.HTTP_204_NO_CONTENT)
async def two_factor_disable(
    payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)
) -> Response:
    _spend_budget(current_user.id)
    try:
        used = await two_factor.disable(await _fresh(current_user.id), payload.code)
    except two_factor.TwoFactorError as error:
        raise _refuse(error) from error
    account_credential_refund(current_user.id)
    invalidate_cached_user(current_user.id)
    logger.info(
        "two-factor: account %s turned two-step verification off (proved with a %s code)",
        current_user.id,
        used,
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/recovery-codes")
async def two_factor_recovery_codes(
    payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)
) -> dict[str, list[str]]:
    _spend_budget(current_user.id)
    try:
        codes = await two_factor.regenerate_recovery_codes(await _fresh(current_user.id), payload.code)
    except two_factor.TwoFactorError as error:
        raise _refuse(error) from error
    account_credential_refund(current_user.id)
    invalidate_cached_user(current_user.id)
    logger.info("two-factor: account %s made new recovery codes", current_user.id)
    return {"recovery_codes": codes}


@router.post("/verify", response_model=TokenResponse)
async def two_factor_verify(payload: TwoFactorVerifyRequest) -> dict[str, Any]:
    try:
        challenge = two_factor.read_challenge(payload.challenge_token)
    except two_factor.TwoFactorError as error:
        raise _refuse(error) from error
    dead = HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.CHALLENGE_DEAD_DETAIL)
    _spend_budget(challenge.user_id)
    if not await two_factor.spend_challenge_attempt(challenge):
        raise dead
    user = await db.user.find_unique(where={"id": challenge.user_id})
    if user is None or not user.twoFactorEnabled:
        raise dead
    try:
        accepted = await two_factor.check_second_factor(user, payload.code)
    except two_factor.SecondFactorLocked as locked:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL
        ) from locked
    if not accepted:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.WRONG_CODE_DETAIL)
    used = two_factor.code_kind(payload.code)
    account_credential_refund(user.id)
    if not await two_factor.close_challenge(challenge):
        raise dead
    if used == two_factor.RECOVERY:
        logger.info("two-factor: account %s signed in with a recovery code", user.id)
    user = await db.user.find_unique(where={"id": user.id})
    if user is None:
        raise dead
    access = await assert_access_admits(user.email, is_master=is_break_glass_master(user))
    await admit_signed_in_account(user, access)
    return await open_session(user, access)
