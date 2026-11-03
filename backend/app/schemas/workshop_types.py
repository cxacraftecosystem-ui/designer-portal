from __future__ import annotations

import re
from typing import Annotated

from pydantic import Field, field_validator

from app.schemas.common import APIModel

_KEY_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")

KEY_SHAPE_MESSAGE = (
    "A code may only use capital letters, digits and underscores, and must start with a letter — "
    "for example DESIGN_PROTOTYPE_DEVELOPMENT. The code can't be changed once created; the label "
    "can."
)


class WorkshopTypeOptionCreate(APIModel):
    key: Annotated[str, Field(min_length=1, max_length=64)]
    label: Annotated[str, Field(min_length=1, max_length=120)]
    sortOrder: int = Field(default=0, ge=0, le=100000)
    isActive: bool = True
    routesToDesignWorkshop: bool = False

    @field_validator("key")
    @classmethod
    def _key_shape(cls, value: str) -> str:
        token = value.strip()
        if not _KEY_PATTERN.match(token):
            raise ValueError(KEY_SHAPE_MESSAGE)
        return token

    @field_validator("label")
    @classmethod
    def _label_is_not_blank(cls, value: str) -> str:
        label = value.strip()
        if not label:
            raise ValueError(
                "A type needs a label — it is what an administrator reads in the list."
            )
        return label


class WorkshopTypeOptionUpdate(APIModel):
    label: Annotated[str, Field(min_length=1, max_length=120)] | None = None
    sortOrder: Annotated[int, Field(ge=0, le=100000)] | None = None
    isActive: bool | None = None
    routesToDesignWorkshop: bool | None = None

    @field_validator("label")
    @classmethod
    def _label_is_not_blank(cls, value: str | None) -> str | None:
        if value is None:
            return None
        label = value.strip()
        if not label:
            raise ValueError(
                "A type needs a label — it is what an administrator reads in the list."
            )
        return label


class WorkshopTypeOptionReorder(APIModel):
    ids: Annotated[list[Annotated[str, Field(max_length=64)]], Field(min_length=1, max_length=200)]
