from pydantic import Field, model_validator

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

    @model_validator(mode="after")
    def validate_login_mode(self) -> "LoginRequest":
        has_password_login = bool(self.email and self.password)
        has_google_login = bool(self.googleIdToken)
        if has_password_login == has_google_login:
            raise ValueError("Provide either email/password or a Google ID token")
        return self


class TokenResponse(APIModel):
    accessToken: str
    tokenType: str = "bearer"
    user: dict


class IssuePasswordLinkRequest(APIModel):
    """An administrator asking for a password link for somebody else's account.

    No ``purpose``: it is derived from whether the account has ever had a password, because the two
    purposes differ only in a lifetime and an admin choosing "invite" for an account that already
    has a password would mint a three-day credential for a live account.
    """

    userId: str = Field(min_length=1, max_length=64)


class SetPasswordRequest(APIModel):
    """Redeeming a link. Unauthenticated by necessity — the whole point is that the person cannot
    sign in — which is why the token is the entire authority and is checked four ways."""

    token: str = Field(min_length=1, max_length=1024)
    password: str = Field(min_length=8, max_length=200)


class ChangePasswordRequest(APIModel):
    """The signed-in account changing its own password.

    ``currentPassword`` is required even for an account carrying ``mustChangePassword``: that flag
    means "the password you were given was not chosen by you", not "anybody at this keyboard may
    replace it". An account with NO password at all (Google-provisioned) cannot use this route —
    there is nothing to prove — and is told so.
    """

    currentPassword: str = Field(min_length=1, max_length=200)
    newPassword: str = Field(min_length=8, max_length=200)
