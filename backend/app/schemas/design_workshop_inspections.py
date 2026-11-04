from pydantic import BaseModel, Field

from app.schemas.design_workshop_review_loop import (
    MAX_BATCH_NOTES,
    MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS,
)
from app.services.design_workshop_inspectors import MAX_DESIGN_WORKSHOP_INSPECTORS


class DesignWorkshopInspectorsIn(BaseModel):
    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_INSPECTORS)


class DwInspectionFeedbackIn(BaseModel):
    note: str = Field(min_length=1, max_length=MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS)
    stageKey: str | None = Field(default=None, max_length=120)
    fieldKey: str | None = Field(default=None, max_length=120)
    recordedAt: str | None = Field(default=None, max_length=40)


class DwInspectionSendBackIn(DwInspectionFeedbackIn):
    pass


class DwNoteItem(BaseModel):
    note: str = Field(min_length=1, max_length=MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS)
    stageKey: str | None = Field(default=None, max_length=120)
    fieldKey: str | None = Field(default=None, max_length=120)


class DwInspectionNotesIn(BaseModel):
    notes: list[DwNoteItem] = Field(min_length=1, max_length=MAX_BATCH_NOTES)
    note: str | None = Field(default=None, max_length=MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS)
    recordedAt: str | None = Field(default=None, max_length=40)
