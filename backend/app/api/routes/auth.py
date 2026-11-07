import asyncio
import hashlib
import logging
import time
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Request, Response, status
from fastapi.encoders import jsonable_encoder
from fastapi.security import HTTPAuthorizationCredentials
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    bearer_scheme,
    get_current_user,
    invalidate_cached_user,
    is_break_glass_master,
    password_credential,
    require_account_provisioner,
    role_value,
    roster_barred,
)
from app.core.security import (
    create_access_token,
    hash_password,
    verify_password,
    verify_password_or_decoy,
)
from app.core.user_fields import HAS_PASSWORD_KEY, SECRET_USER_FIELDS
from app.scale.rate_limit import (
    account_credential_attempt,
    account_credential_refund,
    address_credential_attempt,
    client_address,
    first_password_code_attempt,
    first_password_code_refund,
    forgot_password_attempt,
    source_credential_attempt,
    source_credential_refund,
)
from app.schemas.auth import (
    ChangePasswordRequest,
    ForgotPasswordRequest,
    IssuePasswordLinkRequest,
    LoginRequest,
    PasswordLinkCheckRequest,
    SetPasswordRequest,
    TokenResponse,
    TwoFactorChallengeResponse,
)
from app.services import (
    access_roster,
    credential_links,
    email_outbox,
    features as feature_flags,
    identity,
    mailer,
    oidc_sign_in,
    password_reset,
    signin_role,
    two_factor,
    usage,
)
from app.services.aadhaar import flags as aadhaar_flags
from app.services.account_provisioning import (
    GOOGLE_ONLY_LINK_DETAIL,
    assert_may_reset_credentials,
    issuer_still_manages,
)
from app.services.designers import (
    canonical_email,
    ensure_empanelled,
    mark_roster_seen,
    roster_allows,
)
from app.services.sign_in_stamps import stamp_sign_in

router = APIRouter(prefix="/auth", tags=["auth"])
logger = logging.getLogger(__name__)

DESIGNER_SUSPENDED_DETAIL = "Your designer access has been suspended. Contact the administrator."

ACCESS_PENDING_DETAIL = (
    "Your access request is awaiting administrator approval. This is not a password problem — an "
    "administrator has to approve this address before you can sign in."
)
ACCESS_REJECTED_DETAIL = (
    "Your access request was reviewed and not approved. Contact the administrator if you believe "
    "this is a mistake."
)
ACCESS_SUSPENDED_DETAIL = (
    "Your access to this application has been suspended. Contact the administrator."
)
ACCESS_NOT_RECORDED_DETAIL = (
    "Access requests are temporarily closed because the approval queue is full, so this request "
    "could not be recorded. Contact the administrator directly."
)

ACCESS_STATUS_HEADER = "X-Access-Status"
DESIGNER_SUSPENDED_STATUS = "DESIGNER_SUSPENDED"


def _access_headers(access_status: str) -> dict[str, str]:
    return {ACCESS_STATUS_HEADER: access_status}


SIGN_IN_HINT_HEADER = "X-Sign-In-Hint"

AMBIGUOUS_IDENTIFIER_HINT = "AMBIGUOUS_IDENTIFIER"
AMBIGUOUS_IDENTIFIER_DETAIL = (
    "That number is registered to more than one account. Sign in with your email address "
    "instead, and ask an administrator to correct the duplicate."
)

PASSWORD_NOT_SET_HINT = "PASSWORD_NOT_SET"
PASSWORD_NOT_SET_DETAIL = (
    "This account has no password yet. Ask an administrator for a set-password link, or sign "
    "in with Google if that is how the account was created."
)

ACCOUNT_THROTTLED_DETAIL = (
    "Too many failed sign-in attempts for this account. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)


def _hint_headers(hint: str) -> dict[str, str]:
    return {SIGN_IN_HINT_HEADER: hint}


USAGE_CONSENT_GATE_KEY = "usageConsentGate"

FEATURES_KEY = "features"


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    for field in SECRET_USER_FIELDS:
        payload.pop(field, None)
    payload[USAGE_CONSENT_GATE_KEY] = usage.consent_gate(user)
    payload[HAS_PASSWORD_KEY] = getattr(user, "passwordHash", None) is not None
    payload[FEATURES_KEY] = feature_flags.features()
    return aadhaar_flags.hide_columns(payload)


def enum_value(value: Any) -> str:
    return str(getattr(value, "value", value))


def role_for_email(email: str) -> str:
    settings = get_settings()
    if email.lower() == settings.master_admin_email.lower():
        return "MASTER_ADMIN"
    return settings.default_signup_role


async def assert_access_admits(email: str, *, is_master: bool) -> Any | None:
    if is_master:
        return None

    row = await access_roster.access_row(email)
    if access_roster.admits(row):
        return row

    waiting = row is None or access_roster.status_of(row) == access_roster.PENDING
    if waiting and await access_roster.designer_empanelment_admits(email):
        return await access_roster.admit(
            email,
            admit_role="DESIGNER",
            note=(
                "Approved automatically because an administrator empanelled this address as a "
                "designer."
            ),
            decided=False,
        )

    outcome = await access_roster.record_refused_attempt(email, row)
    if outcome == access_roster.REJECTED:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=ACCESS_REJECTED_DETAIL,
            headers=_access_headers(access_roster.REJECTED),
        )
    if outcome == access_roster.SUSPENDED:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=ACCESS_SUSPENDED_DETAIL,
            headers=_access_headers(access_roster.SUSPENDED),
        )
    if outcome == access_roster.NOT_RECORDED:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=ACCESS_NOT_RECORDED_DETAIL,
            headers=_access_headers(access_roster.NOT_RECORDED),
        )
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=ACCESS_PENDING_DETAIL,
        headers=_access_headers(access_roster.PENDING),
    )


async def assert_roster_admits(user: Any) -> None:
    if role_value(user) != "DESIGNER":
        return
    if await roster_allows(getattr(user, "email", None)):
        return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail=DESIGNER_SUSPENDED_DETAIL,
        headers=_access_headers(DESIGNER_SUSPENDED_STATUS),
    )


def verify_google_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    if not settings.google_client_ids:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Google sign-in isn't available right now. Sign in with your email and password.",
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
            logger.info(
                "Google token rejected for configured audience %s: it did not verify (%s)",
                client_id,
                type(exc).__name__,
            )
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED, detail="Google sign-in didn't work. Try again."
    ) from last_error


GOOGLE_AMBIGUOUS_ACCOUNT_DETAIL = (
    "More than one account on this site uses this Google mailbox under different spellings, so "
    "signing in with Google cannot tell which one is yours. Sign in with your email address and "
    "password instead, and ask an administrator to merge or correct the duplicate accounts."
)

MASTER_ADDRESS_HOLDS_A_PASSWORD_ACCOUNT_DETAIL = (
    "This address can't sign in with Google. Contact your administrator."
)


def _auth_provider(account: Any) -> str:
    provider = getattr(account, "authProvider", None)
    return str(getattr(provider, "value", provider) or "").upper()


async def _local_account_on_the_mailbox(email: str) -> Any | None:
    accounts = await access_roster.accounts_on_the_mailbox_for_sign_in(email)
    if accounts is None:
        logger.error(
            "auth: could not check every spelling of a Google mailbox for an existing password "
            "account (the Gmail sweep was cut); a new account may be created beside one. Raise "
            "access_roster.GMAIL_ACCOUNT_SWEEP_LIMIT."
        )
        return None
    local = [account for account in accounts if _auth_provider(account) == "LOCAL"]
    if len(local) > 1:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=GOOGLE_AMBIGUOUS_ACCOUNT_DETAIL
        )
    return local[0] if local else None


def _refuse_to_promote_a_password_account(account: Any) -> None:
    if role_value(account) == "MASTER_ADMIN" or getattr(account, "passwordHash", None) is None:
        return
    logger.error(
        "auth: the configured master admin signed in with Google and found account %s (role %s, "
        "with a password somebody else set) at MASTER_ADMIN_EMAIL. It was NOT promoted. Run "
        "scripts/seed_admin.py to take the account over, then sign in again.",
        getattr(account, "id", None),
        role_value(account),
    )
    raise HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail=MASTER_ADDRESS_HOLDS_A_PASSWORD_ACCOUNT_DETAIL,
    )


async def login_with_google(token: str) -> tuple[Any, Any | None]:
    id_info = verify_google_token(token)

    if not id_info.get("email") or not id_info.get("email_verified"):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="This Google account's email address isn't verified. Verify it with Google, then try again.",
        )

    return await _sign_in_verified_mailbox(
        id_info["email"].lower(),
        display_name=id_info.get("name"),
        avatar_url=id_info.get("picture"),
        provider="GOOGLE",
    )


def _oidc_refused_detail(label: str) -> str:
    return f"Signing in with {label} did not complete. Try again, or use another way to sign in."


def _oidc_unverified_detail(label: str) -> str:
    return (
        f"{label} has not confirmed the email address on this account, so it cannot be used to sign "
        f"in here. Confirm the address with {label}, or use another way to sign in."
    )


async def login_with_oidc(payload: LoginRequest) -> tuple[Any, Any | None]:
    name = payload.oidcProvider or ""
    label = oidc_sign_in.label_of(name)
    if oidc_sign_in.provider(name) is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Signing in with {label} is not available here. Use another way to sign in.",
        )
    if not oidc_sign_in.acceptable_redirect_uri(payload.oidcRedirectUri or ""):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_refused_detail(label)
        )
    try:
        proved = await oidc_sign_in.prove(
            name,
            code=payload.oidcCode or "",
            code_verifier=payload.oidcCodeVerifier or "",
            redirect_uri=payload.oidcRedirectUri or "",
            raw_nonce=payload.oidcNonce or "",
        )
    except oidc_sign_in.EmailNotVerified as exc:
        logger.info("auth: %s sign-in refused (%s)", name, exc.reason)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_unverified_detail(label)
        ) from exc
    except oidc_sign_in.SignInRefused as exc:
        logger.info("auth: %s sign-in refused (%s)", name, exc.reason)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail=_oidc_refused_detail(label)
        ) from exc
    return await _sign_in_verified_mailbox(
        proved.email, display_name=proved.name, avatar_url=None, provider=proved.provider
    )


async def _sign_in_verified_mailbox(
    email: str, *, display_name: str | None, avatar_url: str | None, provider: str
) -> tuple[Any, Any | None]:
    settings = get_settings()
    role = role_for_email(email)
    name = (
        settings.master_admin_name
        if role == "MASTER_ADMIN"
        else display_name or email.split("@", maxsplit=1)[0]
    )

    access = await assert_access_admits(email, is_master=role == "MASTER_ADMIN")
    if role != "MASTER_ADMIN":
        role = signin_role.role_for_new_account(role, access_roster.role_of(access))
        typed = str(getattr(access, "fullName", None) or "").strip()
        if typed:
            name = typed

    rostered = await roster_allows(email)

    existing = await db.user.find_unique(where={"email": email})
    found_literally = existing is not None
    if existing is None and role != "MASTER_ADMIN" and provider == "GOOGLE":
        existing = await _local_account_on_the_mailbox(email)
    if existing and role == "MASTER_ADMIN" and found_literally:
        _refuse_to_promote_a_password_account(existing)
    if existing:
        data: dict[str, Any] = {"avatarUrl": avatar_url} if provider == "GOOGLE" else {}
        if existing.passwordHash is None:
            data["name"] = name
            if _auth_provider(existing) in ("LOCAL", provider):
                data["authProvider"] = provider
        if role == "MASTER_ADMIN" and found_literally:
            data["role"] = "MASTER_ADMIN"
            data["canManageQuestionnaire"] = True
        updated = await db.user.update(
            where={"id": existing.id},
            data=data,
        )
        invalidate_cached_user(updated.id)
        return updated, access
    if rostered and ROLE_RANK.get(role, 0) < ROLE_RANK["DESIGNER"]:
        role = "DESIGNER"
    created = await db.user.create(
        data={
            "email": email,
            "name": name,
            "avatarUrl": avatar_url,
            "authProvider": provider,
            "role": role,
            "canManageQuestionnaire": role == "MASTER_ADMIN",
        }
    )
    invalidate_cached_user(created.id)
    return created, access


@router.post("/login", response_model=TokenResponse | TwoFactorChallengeResponse)
async def login(payload: LoginRequest, request: Request) -> dict[str, Any]:
    if payload.googleIdToken:
        user, access = await login_with_google(payload.googleIdToken)
    elif payload.has_oidc_login:
        user, access = await login_with_oidc(payload)
    else:
        lookup = await identity.resolve_identifier(payload.email)
        if lookup.outcome == identity.AMBIGUOUS:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=AMBIGUOUS_IDENTIFIER_DETAIL,
                headers=_hint_headers(AMBIGUOUS_IDENTIFIER_HINT),
            )
        user = lookup.user
        if user is not None and user.passwordHash is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail=PASSWORD_NOT_SET_DETAIL,
                headers=_hint_headers(PASSWORD_NOT_SET_HINT),
            )
        if user is not None:
            allowed, _retry = account_credential_attempt(user.id)
        else:
            allowed, _retry = address_credential_attempt(identity.identifier_key(payload.email))
        if allowed:
            allowed, _retry = source_credential_attempt(request.scope)
        if not allowed:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=ACCOUNT_THROTTLED_DETAIL,
            )
        matched = await asyncio.to_thread(
            verify_password_or_decoy, payload.password or "", user.passwordHash if user else None
        )
        if not matched or not user:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid email or password",
            )
        account_credential_refund(user.id)
        source_credential_refund(request.scope)
        access = await assert_access_admits(user.email, is_master=is_break_glass_master(user))

    await admit_signed_in_account(user, access)

    if getattr(user, "twoFactorEnabled", False):
        if two_factor.fully_locked(user):
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL
            )
        return {
            "two_factor_required": True,
            "challenge_token": await two_factor.issue_challenge(user),
        }
    return await open_session(user, access)


async def admit_signed_in_account(user: Any, access: Any) -> None:
    if role_value(user) == "DESIGNER" and access_roster.admits(access):
        await ensure_empanelled(user.email)

    await assert_roster_admits(user)


async def open_session(user: Any, access: Any) -> dict[str, Any]:
    await mark_roster_seen(user.email)
    await access_roster.mark_access_seen(access)

    await stamp_sign_in(user)

    payload = serialize_user(user)
    if payload[USAGE_CONSENT_GATE_KEY]["required"]:
        logger.info(
            "auth: %s signed in and still owes an answer on usage recording (%s)",
            user.email,
            payload[USAGE_CONSENT_GATE_KEY]["state"],
        )

    return {"accessToken": _session_token(user), "tokenType": "bearer", "user": payload}


def _session_token(user: Any) -> str:
    return create_access_token(
        subject=user.id,
        extra_claims={"email": user.email, "role": enum_value(user.role)},
        credential=password_credential(user),
    )


@router.post("/logout")
async def logout() -> dict[str, bool]:
    return {"ok": True}


@router.get("/me")
async def me(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return serialize_user(current_user)



def _link_payload(issued: credential_links.DeliveredLink) -> dict[str, Any]:
    return {
        "id": issued.id,
        "link": issued.link,
        "expiresAt": issued.expiresAt,
        "purpose": issued.purpose,
        "deliveredBy": issued.deliveredBy,
    }


def _is_google_only(account: Any) -> bool:
    return account.passwordHash is None and _auth_provider(account) == "GOOGLE"


@router.post("/password-links", status_code=status.HTTP_201_CREATED)
async def issue_password_link(
    payload: IssuePasswordLinkRequest, current_user: Any = Depends(require_account_provisioner)
) -> dict[str, Any]:
    target = await db.user.find_unique(where={"id": payload.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    assert_may_reset_credentials(current_user, target)
    if _is_google_only(target):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=GOOGLE_ONLY_LINK_DETAIL
        )
    try:
        issued = await credential_links.issue_link(
            user=target,
            purpose=credential_links.purpose_for(target),
            issued_by_id=current_user.id,
            deliver_by=payload.delivery,
        )
    except credential_links.DeliveryUnavailable as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="This link cannot be sent by e-mail. Copy it instead and pass it on yourself.",
        ) from exc
    except credential_links.IssueThrottled as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=(
                "Several password links have already been issued for this account. Wait an hour, "
                "or ask the person to use one of them."
            ),
            headers={"retry-after": str(exc.retry_after_minutes * 60)},
        ) from exc
    logger.info(
        "auth: %s issued a %s password link %s for account %s (%s)",
        current_user.id,
        issued.purpose,
        issued.id,
        target.id,
        issued.deliveredBy,
    )
    return _link_payload(issued)


@router.post("/password-links/{link_id}/revoke")
async def revoke_password_link(
    link_id: str, current_user: Any = Depends(require_account_provisioner)
) -> dict[str, bool]:
    row = await credential_links.link_row(link_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    target = await db.user.find_unique(where={"id": row.userId})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    assert_may_reset_credentials(current_user, target)
    found = await credential_links.revoke_link(link_id)
    if not found:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Link not found")
    logger.info(
        "auth: %s revoked password link %s for account %s", current_user.id, link_id, target.id
    )
    return {"ok": True}


@router.get("/set-password")
async def check_set_password_token(token: str = "") -> dict[str, Any]:
    return await _link_check(token)


@router.post("/set-password/check")
async def check_set_password_token_in_body(payload: PasswordLinkCheckRequest) -> dict[str, Any]:
    return await _link_check(payload.token)


async def _link_check(raw: str | None) -> dict[str, Any]:
    verdict = await _link_verdict(raw)
    return {"valid": verdict.ok, "reason": verdict.reason, "purpose": verdict.purpose}


async def _link_verdict(raw: str | None) -> credential_links.TokenVerdict:
    verdict = await credential_links.describe_token(raw)
    if not verdict.ok or verdict.issued_by_id is None:
        return verdict
    issuer = await db.user.find_unique(where={"id": verdict.issued_by_id})
    target = await db.user.find_unique(where={"id": verdict.user_id})
    if issuer is not None and await roster_barred(issuer):
        logger.info(
            "auth: refused password link for account %s: its issuer %s has been barred",
            verdict.user_id,
            verdict.issued_by_id,
        )
        return credential_links.TokenVerdict(False, credential_links.REVOKED)
    if target is not None and not issuer_still_manages(issuer, target):
        logger.info(
            "auth: refused password link for account %s: its issuer %s can no longer manage it",
            verdict.user_id,
            verdict.issued_by_id,
        )
        return credential_links.TokenVerdict(False, credential_links.REVOKED)
    return verdict


@router.post("/set-password")
async def set_password(payload: SetPasswordRequest) -> dict[str, bool]:
    verdict = await _link_verdict(payload.token)
    if not verdict.ok:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=_SET_PASSWORD_REFUSALS.get(
                verdict.reason or "", "This password link is not valid."
            ),
        )
    account = await db.user.find_unique(where={"id": verdict.user_id})
    if (
        account is not None
        and account.mustChangePassword
        and verify_password(payload.password, account.passwordHash)
    ):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=SAME_PASSWORD_DETAIL)
    password_hash = hash_password(payload.password)
    now = datetime.now(UTC)
    await db.user.update(
        where={"id": verdict.user_id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": now,
            "mustChangePassword": False,
            "passwordIssuerKnown": False,
            "sessionsValidFrom": now,
        },
    )
    invalidate_cached_user(verdict.user_id)
    await credential_links.mark_used(payload.token)
    logger.info("auth: password set through a %s link for account %s", verdict.purpose, verdict.user_id)
    if account is not None and account.passwordHash is not None:
        await email_outbox.notify_password_event(account, mailer.PASSWORD_CHANGED)
    return {"ok": True}


@router.post("/forgot-password")
async def forgot_password(
    payload: ForgotPasswordRequest,
    request: Request,
    response: Response,
    background: BackgroundTasks,
) -> dict[str, str]:
    allowed, retry_after = forgot_password_attempt(request.scope)
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=password_reset.TOO_MANY_REQUESTS_DETAIL,
            headers={"Retry-After": str(max(1, int(retry_after + 0.999)))},
        )
    address = client_address(request.scope)
    background.add_task(
        password_reset.request_password_reset,
        str(payload.email),
        ip_address=address.removeprefix("ip:"),
        user_agent=request.headers.get("user-agent"),
    )
    response.headers["Cache-Control"] = "no-store"
    return {"message": password_reset.FORGOT_PASSWORD_MESSAGE}


SAME_PASSWORD_DETAIL = (
    "Choose a password different from the one you have now. A password somebody else gave you has "
    "to be replaced, not typed again."
)

CURRENT_PASSWORD_WRONG_DETAIL = "Current password is incorrect"

_SET_PASSWORD_REFUSALS = {
    credential_links.MISSING: "This link is incomplete. Open the whole link the administrator sent.",
    credential_links.MALFORMED: "This is not a link this site issued. Ask the administrator for another.",
    credential_links.EXPIRED: "This link has expired. Ask the administrator for a new one.",
    credential_links.REVOKED: "This link was withdrawn. Ask the administrator for a new one.",
    credential_links.SPENT: "This link has already been used. Sign in with the password you set.",
    credential_links.UNKNOWN_ACCOUNT: "This link no longer points at an account.",
}


SESSION_TOKEN_HEADER = "X-Session-Token"

PROVIDER_PROOF_MAX_AGE_SECONDS = 300
PROVIDER_PROOF_LEEWAY_SECONDS = 60

CONFIRM_PROVIDER_HEADER = "X-Confirm-Provider"

TWO_FACTOR_REQUIRED_HEADER = "X-Two-Factor-Required"
FIRST_PASSWORD_NEEDS_CODE_DETAIL = "Enter the code from your authenticator app to set a password."

_PROVIDER_LABELS = {"GOOGLE": "Google", "MICROSOFT": "Microsoft", "YAHOO": "Yahoo"}

_CONFIRMED_PROOFS: dict[str, float] = {}
_CONFIRMED_PROOFS_CAP = 10_000


def _proof_marker(account: Any, session: str, provider: str, code: str) -> str:
    raw = "\x1f".join((str(account.id), session, provider, code))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _remember_confirmed_proof(marker: str) -> None:
    now = time.monotonic()
    for key in [k for k, until in _CONFIRMED_PROOFS.items() if until <= now]:
        _CONFIRMED_PROOFS.pop(key, None)
    if len(_CONFIRMED_PROOFS) >= _CONFIRMED_PROOFS_CAP:
        _CONFIRMED_PROOFS.pop(next(iter(_CONFIRMED_PROOFS)))
    _CONFIRMED_PROOFS[marker] = now + PROVIDER_PROOF_MAX_AGE_SECONDS


def _already_confirmed(marker: str) -> bool:
    until = _CONFIRMED_PROOFS.get(marker)
    return until is not None and until > time.monotonic()


def _confirm_detail(provider: str) -> str:
    label = _PROVIDER_LABELS.get(provider, "the account you sign in with")
    return f"To add a password, first confirm it's you by signing in with {label} again."


def _confirm_again_detail(provider: str) -> str:
    label = _PROVIDER_LABELS.get(provider, "the account you sign in with")
    return (
        f"That {label} sign-in didn't match this account or has expired. "
        f"Sign in with {label} again to confirm it's you."
    )


def _watermark_now() -> datetime:
    return datetime.now(UTC).replace(microsecond=0)


@router.post("/change-password")
async def change_password(
    payload: ChangePasswordRequest,
    response: Response,
    current_user: Any = Depends(get_current_user),
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer_scheme),
) -> dict[str, bool]:
    if current_user.passwordHash is None:
        return await _set_first_password(payload, response, current_user, credentials)
    allowed, _retry = account_credential_attempt(current_user.id)
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=ACCOUNT_THROTTLED_DETAIL,
        )
    if not verify_password(payload.currentPassword, current_user.passwordHash):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=CURRENT_PASSWORD_WRONG_DETAIL
        )
    account_credential_refund(current_user.id)
    if verify_password(payload.newPassword, current_user.passwordHash):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=SAME_PASSWORD_DETAIL)
    password_hash = hash_password(payload.newPassword)
    updated = await db.user.update(
        where={"id": current_user.id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": datetime.now(UTC),
            "mustChangePassword": False,
            "passwordIssuerKnown": False,
            "sessionsValidFrom": _watermark_now(),
        },
    )
    invalidate_cached_user(current_user.id)
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="This account no longer exists. Sign in again."
        )
    response.headers[SESSION_TOKEN_HEADER] = _session_token(updated)
    await email_outbox.notify_password_event(updated, mailer.PASSWORD_CHANGED)
    return {"ok": True}


async def _set_first_password(
    payload: ChangePasswordRequest,
    response: Response,
    current_user: Any,
    credentials: HTTPAuthorizationCredentials | None,
) -> dict[str, bool]:
    session = credentials.credentials if credentials is not None else ""
    await _require_fresh_provider_proof(payload, current_user, session)
    if getattr(current_user, "twoFactorEnabled", False):
        await _require_first_password_code(payload, current_user)
    password_hash = hash_password(payload.newPassword)
    updated = await db.user.update(
        where={"id": current_user.id},
        data={
            "passwordHash": password_hash,
            "passwordSetAt": datetime.now(UTC),
            "mustChangePassword": False,
            "passwordIssuerKnown": False,
            "sessionsValidFrom": _watermark_now(),
        },
    )
    invalidate_cached_user(current_user.id)
    if updated is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="This account no longer exists. Sign in again."
        )
    logger.info("auth: account %s set its first password", current_user.id)
    response.headers[SESSION_TOKEN_HEADER] = _session_token(updated)
    await email_outbox.notify_password_event(updated, mailer.PASSWORD_ADDED)
    return {"ok": True}


def _refuse_proof(provider: str, detail: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_400_BAD_REQUEST,
        detail=detail,
        headers={CONFIRM_PROVIDER_HEADER: provider or "GOOGLE"},
    )


def _issued_recently(issued_at: Any, now: float | None = None) -> bool:
    if isinstance(issued_at, bool) or not isinstance(issued_at, int | float):
        return False
    moment = time.time() if now is None else now
    return (
        moment - PROVIDER_PROOF_MAX_AGE_SECONDS <= issued_at <= moment + PROVIDER_PROOF_LEEWAY_SECONDS
    )


async def _require_fresh_provider_proof(
    payload: ChangePasswordRequest, account: Any, session: str = ""
) -> None:
    provider = _auth_provider(account)
    if provider not in _PROVIDER_LABELS:
        provider = "GOOGLE"
    if payload.googleIdToken:
        try:
            claims = verify_google_token(payload.googleIdToken)
        except HTTPException as exc:
            raise _refuse_proof("GOOGLE", _confirm_again_detail("GOOGLE")) from exc
        mailbox = str(claims.get("email") or "")
        if (
            not claims.get("email_verified")
            or not _issued_recently(claims.get("iat"))
            or canonical_email(mailbox) != canonical_email(getattr(account, "email", ""))
        ):
            logger.info("auth: a first-password Google proof was refused for %s", account.id)
            raise _refuse_proof("GOOGLE", _confirm_again_detail("GOOGLE"))
        return
    if payload.has_oidc_proof:
        name = payload.oidcProvider or ""
        if oidc_sign_in.provider(name) is None or not oidc_sign_in.acceptable_redirect_uri(
            payload.oidcRedirectUri or ""
        ):
            raise _refuse_proof(name, _confirm_again_detail(name))
        marker = _proof_marker(account, session, name, payload.oidcCode or "")
        if session and _already_confirmed(marker):
            return
        try:
            proved = await oidc_sign_in.prove(
                name,
                code=payload.oidcCode or "",
                code_verifier=payload.oidcCodeVerifier or "",
                redirect_uri=payload.oidcRedirectUri or "",
                raw_nonce=payload.oidcNonce or "",
            )
        except oidc_sign_in.SignInRefused as exc:
            logger.info("auth: a first-password %s proof was refused (%s)", name, exc.reason)
            raise _refuse_proof(name, _confirm_again_detail(name)) from exc
        if not _issued_recently(proved.issued_at) or proved.email != str(
            getattr(account, "email", "") or ""
        ).strip().lower():
            logger.info("auth: a first-password %s proof was refused for %s", name, account.id)
            raise _refuse_proof(name, _confirm_again_detail(name))
        if session:
            _remember_confirmed_proof(marker)
        return
    raise _refuse_proof(provider, _confirm_detail(provider))


async def _require_first_password_code(payload: ChangePasswordRequest, account: Any) -> None:
    code = (payload.twoFactorCode or "").strip()
    if not code:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=FIRST_PASSWORD_NEEDS_CODE_DETAIL,
            headers={TWO_FACTOR_REQUIRED_HEADER: "1"},
        )
    budget = two_factor.budget_for(code)
    if two_factor.is_locked(account, budget):
        until = getattr(account, budget.locked_until, None)
        if until is not None and until.tzinfo is None:
            until = until.replace(tzinfo=UTC)
        wait = (until - datetime.now(UTC)).total_seconds() if until is not None else 60
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL,
            headers={"Retry-After": str(max(1, int(wait)))},
        )
    allowed, retry = first_password_code_attempt(account.id)
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=two_factor.TOO_MANY_ATTEMPTS_DETAIL,
            headers={"Retry-After": str(max(1, int(retry)))},
        )
    if await two_factor.verify_second_factor(account, code) is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=two_factor.WRONG_CODE_DETAIL,
            headers={TWO_FACTOR_REQUIRED_HEADER: "1"},
        )
    first_password_code_refund(account.id)
