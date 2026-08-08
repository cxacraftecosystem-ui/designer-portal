import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.encoders import jsonable_encoder
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    get_current_user,
    invalidate_cached_user,
    role_rank,
    role_value,
)
from app.core.security import create_access_token, verify_password
from app.schemas.auth import LoginRequest, TokenResponse
from app.services.designers import mark_roster_seen, roster_allows

router = APIRouter(prefix="/auth", tags=["auth"])
logger = logging.getLogger(__name__)

# THE MESSAGE A REVOKED DESIGNER READS, and the reason this is a 403 rather than a 401.
#
# A suspended designer who is told "Invalid email or password" will do the only thing that
# message suggests: reset a password that was never wrong. They will do it two or three times,
# then telephone the wrong person about it, and the actual state of affairs — an admin
# deliberately ended their access — is never mentioned anywhere they can see it. The refusal has
# to say what happened AND what to do about it, because the person reading it cannot fix it
# themselves and there is exactly one action that leads anywhere.
DESIGNER_SUSPENDED_DETAIL = (
    "Your designer access has been suspended. Contact the administrator."
)


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def enum_value(value: Any) -> str:
    return str(getattr(value, "value", value))


def role_for_email(email: str) -> str:
    settings = get_settings()
    if email.lower() == settings.master_admin_email.lower():
        return "MASTER_ADMIN"
    # New self-registered Google accounts start at the configured signup tier (lowest tier by
    # default) and are elevated by an admin — an unknown Google account no longer becomes a
    # full researcher automatically.
    return settings.default_signup_role


async def assert_roster_admits(user: Any) -> None:
    """THE SIGN-IN GATE. A DESIGNER may sign in only while an ACTIVE roster row carries their email.

    APPLIED ON BOTH PATHS — password and Google — from :func:`login`, which is the one place both
    of them converge. Putting the check on each path separately would have meant two chances to
    forget it, and the one that gets forgotten is always the one an attacker uses.

    ADMIN AND ABOVE ARE NEVER GATED, and neither is a professor: the roster is the empanelment
    list of *designers*, and that separation is the entire point of keeping it apart from
    ``User.role``. An admin locked out by a table only an admin can edit is an outage with no
    remedy inside the product — there would be nobody left able to add the row that lets anybody
    back in.

    Ranks BELOW designer are not gated either, and that is not a hole: a researcher gated by the
    designer roster would be refused for not being a designer, which is not a rule anybody wrote.
    The role is what grants the power; this asks only whether the institution still recognises
    the person the role was granted to.
    """
    if role_value(user) != "DESIGNER":
        return
    if await roster_allows(getattr(user, "email", None)):
        return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN, detail=DESIGNER_SUSPENDED_DETAIL
    )


def verify_google_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    if not settings.google_client_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Google OAuth is not configured on this server",
        )
    last_error: ValueError | None = None
    for client_id in settings.google_client_ids:
        try:
            return google_id_token.verify_oauth2_token(
                token,
                google_requests.Request(),
                client_id,
            )
        except ValueError as exc:
            last_error = exc
            logger.info("Google token rejected for configured audience %s: %s", client_id, exc)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid Google ID token") from last_error


async def login_with_google(token: str) -> Any:
    id_info = verify_google_token(token)

    if not id_info.get("email") or not id_info.get("email_verified"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Google account email is not verified",
        )

    email = id_info["email"].lower()
    settings = get_settings()
    role = role_for_email(email)
    name = settings.master_admin_name if role == "MASTER_ADMIN" else id_info.get("name") or email.split("@")[0]
    avatar_url = id_info.get("picture")

    # AN ACTIVE ROSTER ROW IS AN INSTRUCTION TO PROVISION A DESIGNER, and this is where it is
    # carried out. An admin empanels somebody who has never opened the app; there is no account
    # to promote yet, and there is no invitation email in this product to send them. What there
    # is, is the moment they first sign in with Google — so the roster row is read here and the
    # account is created at, or lifted to, DESIGNER on the spot. Without this the admin's only
    # remaining move is to wait for the person to sign in as a volunteer, notice it happened, and
    # promote them by hand, which is exactly the manual step the roster exists to remove.
    rostered = await roster_allows(email)

    existing = await db.user.find_unique(where={"email": email})
    if existing:
        data = {"name": name, "avatarUrl": avatar_url, "authProvider": "GOOGLE"}
        if role == "MASTER_ADMIN":
            data["role"] = "MASTER_ADMIN"
            data["canManageQuestionnaire"] = True
        elif rostered and role_rank(existing) < ROLE_RANK["DESIGNER"]:
            # PROMOTE ONLY, NEVER DEMOTE. The comparison is strictly-below rather than
            # not-equal for one reason: an admin or a professor whose email is also on the
            # roster (they run workshops too) would otherwise be knocked down to DESIGNER by
            # their own next sign-in — losing their admin rights to a row they added to help
            # somebody else, with the demotion invisible in the login response.
            data["role"] = "DESIGNER"
        updated = await db.user.update(
            where={"email": email},
            data=data,
        )
        # Sign-in is a WRITE to the identity the rest of the app authorises against: it can rename
        # the account and, for the master-admin email, hand it MASTER_ADMIN and
        # canManageQuestionnaire. Drop the cached row so the token minted below is never validated
        # against the pre-login one. (Keyed by id, which is why the update's return value is used.)
        invalidate_cached_user(updated.id)
        return updated
    if rostered and ROLE_RANK.get(role, 0) < ROLE_RANK["DESIGNER"]:
        # A brand-new account for an empanelled email. It would otherwise be created at
        # DEFAULT_SIGNUP_ROLE — a crowdsource volunteer — and the designer's first experience of
        # the app an admin invited them to would be a home screen with no way to start a workshop.
        role = "DESIGNER"
    created = await db.user.create(
        data={
            "email": email,
            "name": name,
            "avatarUrl": avatar_url,
            "authProvider": "GOOGLE",
            "role": role,
            "canManageQuestionnaire": role == "MASTER_ADMIN",
        }
    )
    invalidate_cached_user(created.id)
    return created


@router.post("/login", response_model=TokenResponse)
async def login(payload: LoginRequest) -> dict[str, Any]:
    if payload.googleIdToken:
        user = await login_with_google(payload.googleIdToken)
    else:
        user = await db.user.find_unique(where={"email": payload.email.lower()})
        if not user or not verify_password(payload.password or "", user.passwordHash):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid email or password",
            )

    # BOTH PATHS, ONE GATE, AFTER the credential has been proved and after the Google path has had
    # its chance to promote a newly empanelled account. Checking earlier would refuse the very
    # designer the roster was added to admit.
    await assert_roster_admits(user)
    # Only now: "first seen" means the first time somebody actually got IN, so a suspended
    # designer's rejected attempt must not consume the stamp an admin reads as "the invitation
    # was accepted".
    await mark_roster_seen(user.email)

    access_token = create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
    )
    return {"accessToken": access_token, "tokenType": "bearer", "user": serialize_user(user)}


@router.post("/google", response_model=TokenResponse)
async def google_login(payload: LoginRequest) -> dict[str, Any]:
    if not payload.googleIdToken:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Missing Google ID token")
    return await login(payload)


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)
