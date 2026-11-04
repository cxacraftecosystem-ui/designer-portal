from typing import Literal

from pydantic import EmailStr, Field

from app.core.security import MAX_PASSWORD_LENGTH
from app.schemas.common import APIModel


class AccessRosterCreate(APIModel):
    email: EmailStr
    fullName: str | None = Field(default=None, max_length=180)
    role: str | None = None
    notes: str | None = Field(default=None, max_length=4000)
    password: str | None = Field(default=None, min_length=8, max_length=MAX_PASSWORD_LENGTH)
    mustChangePassword: bool = True


class AccessRosterUpdate(APIModel):
    fullName: str | None = Field(default=None, max_length=180)
    role: str | None = None
    notes: str | None = Field(default=None, max_length=4000)


class AccessDecision(APIModel):
    decision: Literal["APPROVE", "REJECT"]
    role: str | None = None
    notes: str | None = Field(default=None, max_length=4000)
