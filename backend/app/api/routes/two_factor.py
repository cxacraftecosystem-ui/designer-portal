"""Two-step verification routes: ``/api/auth/two-factor``.

The mechanism — the TOTP arithmetic, the encryption at rest, the recovery codes, the challenge token
and every conditional update that makes a code single-use — is in ``app/services/two_factor.py``.
This module is the HTTP surface and the audit lines, nothing else.

Seven routes. ``POST /verify`` is the only unauthenticated one: it is the second half of a sign-in,
and its answer is exactly ``POST /auth/login``'s (it ends in ``auth.open_session``). The other six are
the account's own controls, available to every role and forced on none.

── A SETUP IS NOT "ON" UNTIL A CODE CONFIRMS IT ──────────────────────────────────────────────────────

``POST /setup`` stores a PENDING secret and switches nothing on; only ``/enable`` does, after a code
from that secret checks out. ``GET`` reports ``pending`` beside ``enabled`` so a client that was closed
half-way through (back, reload, app backgrounded) comes back to a switch reading Off with an offer to
continue, and ``POST /setup/cancel`` discards the pending secret the moment a person leaves setup. A
pending secret older than ``two_factor.PENDING_SETUP_MINUTES`` is ignored by ``/enable`` and cleared
by the next ``GET``. Sign-in asks for a code only when ``twoFactorEnabled`` is true — a pending secret
never makes anybody's sign-in ask for one.

── THE GUESSING BUDGETS ─────────────────────────────────────────────────────────────────────────────

A six-digit code is one in a million per guess with a ±1-step window making it three, so what keeps
it a second factor is how few guesses anybody gets. Four budgets, each closing a different hole:

* **Five per challenge** (``two_factor.MAX_CHALLENGE_ATTEMPTS``), counted in the database so every
  API worker shares one count. After that the challenge is dead and the person signs in again.
* **The account's own failure budgets** (``two_factor.check_second_factor``), which signing in again
  does NOT reset: ten six-digit codes, or five recovery codes, per fifteen minutes, each claimed
  atomically before it is checked; the spent budget locks for 15 minutes doubling to an hour and the
  owner is e-mailed. Answered 429 without the code being looked at. A right code clears its budget.
* **The per-account credential bucket** (``rate_limit.account_credential_attempt``) — the SAME bucket
  the password doors spend, so a password holder minting challenge after challenge still meets ten
  wrong answers in five minutes across every door, not five per challenge for ever. Refunded when the
  code is right, on the sign-in door's rule.
* **The per-network budget** on ``/verify``, by listing it in ``rate_limit._CREDENTIAL_PREFIXES``,
  because it is reached anonymously like the sign-in itself.

The self-service routes that take a code (``/disable``, ``/recovery-codes``) spend both per-account
budgets too: a stolen session must not become an unlimited oracle for the code that would let it switch
the second factor off.
"""

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

#: The per-account budget is spent. The sign-in door's sentence would name a password, which is not
#: what was being guessed here.
CODE_THROTTLED_DETAIL = "Too many codes were tried. Wait a few minutes, then try again."

#: Status per ``TwoFactorError.kind``. A wrong code is 400 on the signed-in routes and NOT 401: the
#: web client ends the whole session on any 401 sent with a token, so a typo here would sign the
#: person out of the screen they were using (the change-password route's reason, verbatim).
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
    """The row as it is NOW. ``get_current_user`` may hand back a cached row, and every decision here
    turns on columns this module itself writes."""
    row = await db.user.find_unique(where={"id": user_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Sign in to continue.")
    return row


@router.get("")
async def two_factor_status(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    """``{enabled, pending, recovery_codes_remaining, enabled_at}`` — what the account screen's switch
    draws. A stale pending secret (an abandoned setup) is cleared here, on the way past."""
    row = await _fresh(current_user.id)
    if two_factor.has_stale_pending(row):
        await two_factor.clear_pending(current_user.id)
        row = await _fresh(current_user.id)
    return two_factor.status_of(row)


@router.post("/setup/cancel", status_code=status.HTTP_204_NO_CONTENT)
async def two_factor_setup_cancel(current_user: Any = Depends(get_current_user)) -> Response:
    """Discard a setup that was started and not confirmed. Idempotent: no pending secret is a 204 too.
    Never touches a switched-on second factor — the write is conditioned on the account being off."""
    if await two_factor.clear_pending(current_user.id):
        logger.info("two-factor: account %s left setup without confirming; pending key discarded", current_user.id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/setup")
async def two_factor_setup(current_user: Any = Depends(get_current_user)) -> dict[str, str]:
    """A new key, its ``otpauth://`` URI and the QR square for it. Nothing is switched on until
    ``/enable`` is sent a code from it."""
    try:
        return await two_factor.begin_setup(await _fresh(current_user.id))
    except two_factor.TwoFactorError as error:
        raise _refuse(error) from error


@router.post("/enable")
async def two_factor_enable(
    payload: TwoFactorCodeRequest, current_user: Any = Depends(get_current_user)
) -> dict[str, list[str]]:
    """Confirm the key with a code from it, switch on, and answer the ten recovery codes — the only
    time they are ever shown. Existing sessions carry on: switching this on suspects no device."""
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
    """Switch off, with an authenticator code or a recovery code. The recovery codes go with it."""
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
    """Ten new recovery codes for a current authenticator code; every older one stops working."""
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
    """The second half of a sign-in: a challenge token from ``POST /auth/login`` and a code.

    ONE SENTENCE FOR EVERY WRONG CODE, and one for a challenge that is finished — expired, forged,
    used up, already redeemed, or for an account that has since switched two-step verification off.
    Each refusal is a 401: nobody is signed in yet, and there is no session for it to end.

    THE ADMISSION GATES RUN AGAIN after the code is proved (``assert_access_admits``,
    ``admit_signed_in_account``), because up to five minutes have passed since ``/login`` ran them and
    an administrator may have suspended the account in between. Then ``open_session`` — the same tail
    ``/login`` ends in, so the body is the same body.
    """
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
        # The budget this code's shape spends is locked: 429, whatever the code was.
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL
        ) from locked
    if not accepted:
        # A 401 here, not the self-service 400: nobody is signed in yet.
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=two_factor.WRONG_CODE_DETAIL)
    used = two_factor.code_kind(payload.code)
    account_credential_refund(user.id)
    if not await two_factor.close_challenge(challenge):
        raise dead
    if used == two_factor.RECOVERY:
        logger.info("two-factor: account %s signed in with a recovery code", user.id)
    # Re-read: the code just written ``twoFactorLastStep`` or spent a recovery code, and the session
    # token is bound to the row it is minted from.
    user = await db.user.find_unique(where={"id": user.id})
    if user is None:
        raise dead
    access = await assert_access_admits(user.email, is_master=is_break_glass_master(user))
    await admit_signed_in_account(user, access)
    return await open_session(user, access)
