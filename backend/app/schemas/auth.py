from typing import Literal

from pydantic import Field, model_validator

from app.core.security import MAX_PASSWORD_LENGTH
from app.schemas.common import APIModel


class LoginRequest(APIModel):
    """The sign-in body. ``email`` is a misnomer since 2026-08-30 and the name was kept anyway.

    ── WHY THE FIELD IS STILL CALLED ``email`` WHEN IT NO LONGER HAS TO BE ONE ────────────────────

    A designer may now sign in with their email address, their phone number **or** their empanelment
    number (``app/services/identity.py`` resolves all three). The obvious change is to rename this
    to ``identifier``. It was not made, and the reason is that this is the front door of a product
    that is live: both shipped clients POST ``{"email": ..., "password": ...}``, the Android build in
    people's hands is not upgraded by a backend deploy, and a rename with a compatibility alias is
    two spellings of one field that must agree for ever. One name, widened, is the smaller thing to
    keep true.

    ``EmailStr`` WAS REMOVED FROM THIS FIELD, WHICH IS THE WHOLE CHANGE ON THIS SIDE. Pydantic would
    have refused ``DES/2024/0142`` at the wire with a 422 that named the ``email`` field, before any
    handler could resolve anything — which is the same failure the web input's ``type="email"`` used
    to produce in the browser. Nothing is lost by the removal: an address that is not an address
    simply fails to resolve to an account, and the answer to that is the same 401 as a wrong
    password. See ``routes/auth.login``, which explains why that answer is deliberately unchanged.

    THE LENGTH BOUND IS NOT VALIDATION, IT IS A BUDGET. Anything past 320 characters (the longest
    legal email address) is refused before it can be normalised, digested or looked up — a megabyte
    posted at the sign-in route should cost a 422 and not a regex pass.
    """

    email: str | None = Field(default=None, max_length=320)
    password: str | None = Field(default=None, min_length=8)
    googleIdToken: str | None = None
    # ── MICROSOFT AND YAHOO: an authorization code, and what proves this caller started the flow ──
    #
    # Not an ID token, unlike Google: see ``app/services/oidc_sign_in.py`` for why these two
    # providers' codes are redeemed here. All five travel together or not at all. The verifier's
    # alphabet and length are RFC 7636's; the nonce is the RAW value whose SHA-256 the client put in
    # the authorization request. The bounds are budgets, like ``email``'s: nothing longer is ever
    # legitimate, so nothing longer is ever forwarded.
    oidcProvider: Literal["MICROSOFT", "YAHOO"] | None = None
    oidcCode: str | None = Field(default=None, min_length=1, max_length=4096)
    oidcCodeVerifier: str | None = Field(
        default=None, min_length=43, max_length=128, pattern=r"^[A-Za-z0-9\-._~]+$"
    )
    oidcRedirectUri: str | None = Field(default=None, min_length=1, max_length=2048)
    oidcNonce: str | None = Field(default=None, min_length=16, max_length=256)

    @property
    def has_oidc_login(self) -> bool:
        return bool(
            self.oidcProvider
            and self.oidcCode
            and self.oidcCodeVerifier
            and self.oidcRedirectUri
            and self.oidcNonce
        )

    @model_validator(mode="after")
    def validate_login_mode(self) -> "LoginRequest":
        has_password_login = bool(self.email and self.password)
        has_google_login = bool(self.googleIdToken)
        partial_oidc = any(
            (self.oidcProvider, self.oidcCode, self.oidcCodeVerifier, self.oidcRedirectUri, self.oidcNonce)
        )
        if partial_oidc and not self.has_oidc_login:
            raise ValueError("A Microsoft or Yahoo sign-in needs all five oidc fields")
        if [has_password_login, has_google_login, self.has_oidc_login].count(True) != 1:
            raise ValueError("Provide exactly one of email/password, a Google ID token, or a Microsoft or Yahoo code")
        return self


class TokenResponse(APIModel):
    accessToken: str
    tokenType: str = "bearer"
    user: dict


class IssuePasswordLinkRequest(APIModel):
    """An account provisioner asking for a password link for somebody else's account.

    No ``purpose``: it is derived (``credential_links.purpose_for``) from whether the account has a
    password and whether it has ever signed in, because the two purposes differ only in a lifetime
    and a provisioner choosing "invite" for an account somebody is already using would mint a
    three-day credential for a live account.
    """

    userId: str = Field(min_length=1, max_length=64)


class PasswordLinkCheckRequest(APIModel):
    """Asking whether a link is still good, with the token in the BODY (2026-10-09).

    ``POST /auth/set-password/check`` answers exactly what ``GET /auth/set-password?token=…``
    answers, and exists because a query string is part of the request line, which anything in front
    of the API may write down (``docs/OPEN_FINDINGS.md``). So the field takes exactly what the GET's
    query parameter takes: any string, the empty one and an absent one answered "missing", and NO
    length bound — a token past ``credential_links.MAX_TOKEN_LENGTH`` is "malformed" from
    ``credential_links.verify_token``, before any HMAC, as it is through the GET. A bound here would
    turn that answer into a 422 the GET never gives.
    """

    token: str | None = None


class SetPasswordRequest(APIModel):
    """Redeeming a link. Unauthenticated by necessity — the whole point is that the person cannot
    sign in — which is why the token is the entire authority and is checked four ways."""

    token: str = Field(min_length=1, max_length=1024)
    password: str = Field(min_length=8, max_length=MAX_PASSWORD_LENGTH)


class ChangePasswordRequest(APIModel):
    """The signed-in account changing its own password.

    ``currentPassword`` is required even for an account carrying ``mustChangePassword``: that flag
    means "the password you were given was not chosen by you", not "anybody at this keyboard may
    replace it". An account with NO password at all (Google-provisioned) cannot use this route —
    there is nothing to prove — and is told so.

    Both fields share the one ceiling (``security.MAX_PASSWORD_LENGTH``) the admin forms use, so any
    temporary password an administrator can set is one this form can accept back.
    """

    currentPassword: str = Field(min_length=1, max_length=MAX_PASSWORD_LENGTH)
    newPassword: str = Field(min_length=8, max_length=MAX_PASSWORD_LENGTH)
