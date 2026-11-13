from datetime import datetime

from pydantic import Field

from app.schemas.blank_text import BlankTextModel

MAX_JOIN_CODE = 300

MAX_LABEL = 200

MAX_BOOT_ID = 200


class JoinCardMintIn(BlankTextModel):
    recordType: str = Field(default="DESIGN_WORKSHOP", min_length=1, max_length=40)
    recordId: str = Field(min_length=1, max_length=200)
    maxUses: int | None = Field(default=1, ge=1, le=1000)
    daysValid: int | None = Field(default=None, ge=1, le=365)
    label: str | None = Field(default=None, max_length=MAX_LABEL)


class JoinCardRedeemIn(BlankTextModel):
    code: str = Field(min_length=1, max_length=MAX_JOIN_CODE)
    scannedAt: datetime | None = Field(default=None)
    scannedAtElapsedSec: int | None = Field(default=None, ge=0)
    syncedAtElapsedSec: int | None = Field(default=None, ge=0)
    bootId: str | None = Field(default=None, max_length=MAX_BOOT_ID)
    clockJumpObserved: bool = Field(default=False)
