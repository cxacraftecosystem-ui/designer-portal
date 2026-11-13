from datetime import datetime

from pydantic import Field

from app.schemas.blank_text import BlankTextModel

MAX_SCANNED_CODE = 200

MAX_NOTE = 2000


class DesignWorkshopAccessRequestIn(BlankTextModel):
    workshopId: str = Field(min_length=1, max_length=200)
    scannedCode: str | None = Field(default=None, max_length=MAX_SCANNED_CODE)
    note: str | None = Field(default=None, max_length=MAX_NOTE)
    scannedAt: datetime | None = None


class DesignWorkshopAccessDecisionIn(BlankTextModel):
    status: str = Field(min_length=1, max_length=40)
    note: str | None = Field(default=None, max_length=MAX_NOTE)
