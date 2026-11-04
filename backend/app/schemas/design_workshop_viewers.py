from pydantic import BaseModel, Field

MAX_DESIGN_WORKSHOP_VIEWERS = 100


class DesignWorkshopViewersIn(BaseModel):
    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
