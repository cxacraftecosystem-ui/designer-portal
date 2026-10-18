"""Request bodies for /api/ministry-exports. Answers are built by hand in the route module (the
pack's ``sanctionedAmount`` travels as a string, never a float)."""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from app.schemas.common import APIModel


class RecipientUpdate(APIModel):
    """``schemes`` empty means every scheme."""

    schemes: list[str] = Field(default_factory=list, max_length=16)
    active: bool = True


class PackRequest(APIModel):
    """``schemes`` empty means every scheme."""

    period: str = Field(min_length=1, max_length=16)
    schemes: list[str] = Field(default_factory=list, max_length=16)


class LinkRequest(APIModel):
    file: Literal["xlsx", "pdf"]
