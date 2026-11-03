from pydantic import BaseModel, ConfigDict, Field

from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS


class DesignWorkshopOversightIn(BaseModel):
    assistantDirectorId: str | None = Field(default=None, max_length=64)
    regionalDirectorId: str | None = Field(default=None, max_length=64)

    model_config = ConfigDict(extra="forbid")


class DesignWorkshopDesignerIn(BaseModel):
    designerId: str = Field(min_length=1, max_length=64)

    model_config = ConfigDict(extra="forbid")


class DesignWorkshopDesignersIn(BaseModel):
    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
    leadUserId: str | None = Field(default=None, max_length=64)

    model_config = ConfigDict(extra="forbid")


class DesignWorkshopOversightCreateIn(BaseModel):
    title: str = Field(min_length=1, max_length=220)
    workshopKind: str | None = Field(default=None, max_length=48)
    designerUserId: str | None = Field(default=None, max_length=64)
    designerUserIds: list[str] | None = Field(default=None, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
    craftId: str | None = Field(default=None, max_length=64)
    craftName: str | None = Field(default=None, max_length=160)
    clusterName: str | None = Field(default=None, max_length=160)
    state: str | None = Field(default=None, max_length=80)
    district: str | None = Field(default=None, max_length=80)
    startDate: str | None = Field(default=None, max_length=32)
    endDate: str | None = Field(default=None, max_length=32)

    model_config = ConfigDict(extra="forbid")
