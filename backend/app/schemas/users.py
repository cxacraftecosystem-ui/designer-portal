from typing import Literal

from pydantic import EmailStr, Field, model_validator

from app.core.security import MAX_PASSWORD_LENGTH
from app.schemas.common import APIModel


class UserCreate(APIModel):
    email: EmailStr
    name: str = Field(min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=MAX_PASSWORD_LENGTH)
    passwordMode: Literal["SET", "GENERATE", "NONE"] | None = None
    sendPasswordLink: bool = False
    role: str = "RESEARCHER"
    mustChangePassword: bool = True
    canManageQuestionnaire: bool = False
    canManageCrafts: bool = False
    canManageWorkshops: bool = False
    canReview: bool = False
    canViewProvenance: bool = False
    canDownloadDataset: bool = False

    @model_validator(mode="after")
    def settle_password_mode(self) -> UserCreate:
        mode = self.password_mode
        if mode is None:
            raise ValueError(
                "Choose how they will sign in: a password you set now, a temporary password made for "
                "you, or Google, Microsoft or Yahoo only."
            )
        if mode == "SET" and self.password is None:
            raise ValueError("Type the password they will sign in with, or choose another way to sign in.")
        if mode != "SET" and self.password is not None:
            raise ValueError("Send a password only with \"Set a password now\".")
        if mode != "NONE" and self.sendPasswordLink:
            raise ValueError("A set-password link is offered only to an account with no password.")
        return self

    @property
    def password_mode(self) -> str | None:
        return self.passwordMode or ("SET" if self.password is not None else None)


class UserUpdate(APIModel):
    email: EmailStr | None = None
    name: str | None = Field(default=None, min_length=1, max_length=160)
    password: str | None = Field(default=None, min_length=8, max_length=MAX_PASSWORD_LENGTH)
    role: str | None = None
    mustChangePassword: bool | None = None
    canManageQuestionnaire: bool | None = None
    canManageCrafts: bool | None = None
    canManageWorkshops: bool | None = None
    canReview: bool | None = None
    canViewProvenance: bool | None = None
    canDownloadDataset: bool | None = None
