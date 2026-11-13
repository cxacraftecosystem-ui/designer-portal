from pydantic import Field

from app.schemas.blank_text import BlankTextModel

MAX_DESIGN_WORKSHOP_VIEWERS = 100


class DesignWorkshopViewersIn(BlankTextModel):
    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
