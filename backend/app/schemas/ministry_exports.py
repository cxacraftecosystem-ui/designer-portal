from __future__ import annotations

from typing import Literal

from pydantic import Field

from app.schemas.common import APIModel


class RecipientUpdate(APIModel):
    schemes: list[str] = Field(default_factory=list, max_length=16)
    active: bool = True


class PackRequest(APIModel):
    period: str = Field(min_length=1, max_length=16)
    schemes: list[str] = Field(default_factory=list, max_length=16)


class LinkRequest(APIModel):
    file: Literal["xlsx", "pdf"]
