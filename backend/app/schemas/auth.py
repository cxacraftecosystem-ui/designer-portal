from typing import Literal

from pydantic import EmailStr, Field, model_validator

from app.core.security import MAX_PASSWORD_LENGTH
from app.schemas.common import APIModel


class LoginRequest(APIModel):
    email: str | None = Field(default=None, max_length=320)
    password: str | None = Field(default=None, min_length=8)
    googleIdToken: str | None = None
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
    def validate_login_mode(self) -> LoginRequest:
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
    userId: str = Field(min_length=1, max_length=64)
    delivery: Literal["COPY_LINK", "EMAIL"] = "COPY_LINK"


class PasswordLinkCheckRequest(APIModel):
    token: str | None = None


class SetPasswordRequest(APIModel):
    token: str = Field(min_length=1, max_length=1024)
    password: str = Field(min_length=8, max_length=MAX_PASSWORD_LENGTH)


class ChangePasswordRequest(APIModel):
    currentPassword: str = Field(default="", max_length=MAX_PASSWORD_LENGTH)
    newPassword: str = Field(min_length=8, max_length=MAX_PASSWORD_LENGTH)
    twoFactorCode: str | None = Field(default=None, max_length=64)
    googleIdToken: str | None = Field(default=None, max_length=8192)
    oidcProvider: Literal["MICROSOFT", "YAHOO"] | None = None
    oidcCode: str | None = Field(default=None, min_length=1, max_length=4096)
    oidcCodeVerifier: str | None = Field(
        default=None, min_length=43, max_length=128, pattern=r"^[A-Za-z0-9\-._~]+$"
    )
    oidcRedirectUri: str | None = Field(default=None, min_length=1, max_length=2048)
    oidcNonce: str | None = Field(default=None, min_length=16, max_length=256)

    @property
    def has_oidc_proof(self) -> bool:
        return bool(
            self.oidcProvider
            and self.oidcCode
            and self.oidcCodeVerifier
            and self.oidcRedirectUri
            and self.oidcNonce
        )


class ForgotPasswordRequest(APIModel):
    email: EmailStr = Field(max_length=320)



class TwoFactorChallengeResponse(APIModel):
    two_factor_required: Literal[True] = True
    challenge_token: str


class TwoFactorCodeRequest(APIModel):
    code: str = Field(min_length=1, max_length=64)


class DatasetTokenRequest(LoginRequest):
    twoFactorCode: str | None = Field(default=None, max_length=64)


class TwoFactorVerifyRequest(TwoFactorCodeRequest):
    challenge_token: str = Field(min_length=1, max_length=2048)
