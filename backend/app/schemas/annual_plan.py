from __future__ import annotations

from typing import Annotated

from pydantic import Field, model_validator

from app.schemas.common import APIModel
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.schemas.design_workshops import REPORT_TEMPLATE_IDS


class AnnualPlanPromoteRequest(APIModel):
    designerUserId: str | None = Field(default=None, max_length=64)
    designerUserIds: list[Annotated[str, Field(max_length=64)]] = Field(
        default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS
    )
    templateId: str = Field(default="DCH_STANDARD", max_length=48)
    craftId: str | None = Field(default=None, max_length=64)

    @model_validator(mode="after")
    def _known_template(self) -> AnnualPlanPromoteRequest:
        if self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}")
        return self


class AnnualPlanEntryUpdate(APIModel):
    notes: str | None = Field(default=None, max_length=2000)


class RegionalDirectorStatesIn(APIModel):
    states: list[Annotated[str, Field(max_length=80)]] = Field(default_factory=list, max_length=40)
