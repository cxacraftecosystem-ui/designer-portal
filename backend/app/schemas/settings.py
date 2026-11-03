from datetime import datetime

from pydantic import Field

from app.schemas.common import APIModel

MAX_DICTATION_DAILY_CAP = 10_000


class AppSettingDto(APIModel):
    transcriptionMode: str
    batchWindowEnabled: bool
    batchWindowStart: str
    batchWindowEnd: str
    batchTimezone: str
    sttProviderOrder: list[str]
    dwDictationDailyCap: int | None = None
    dwAiVerbDailyCap: int | None = None


class ReportDeliverySettingsDto(APIModel):
    addresses: list[str]
    officeName: str
    defaultOfficeName: str
    mailAvailable: bool


class ReportDeliverySettingsUpdate(APIModel):
    addresses: list[str] = Field(default_factory=list, max_length=50)
    officeName: str | None = None


class AppSettingUpdate(APIModel):
    transcriptionMode: str | None = None
    batchWindowEnabled: bool | None = None
    batchWindowStart: str | None = None
    batchWindowEnd: str | None = None
    batchTimezone: str | None = None
    sttProviderOrder: list[str] | None = None
    dwDictationDailyCap: int | None = Field(default=None, ge=0, le=MAX_DICTATION_DAILY_CAP)
    dwAiVerbDailyCap: int | None = Field(default=None, ge=0, le=MAX_DICTATION_DAILY_CAP)


class TranscriptionProviderDto(APIModel):
    id: str
    name: str
    keyName: str
    keyLabel: str
    configured: bool
    keyState: str
    rankable: bool
    frozenReason: str | None = None
    testedAt: datetime | None = None
    testError: str | None = None


class TranscriptionProviderOrderDto(APIModel):
    providers: list[TranscriptionProviderDto]
    effectiveChain: list[str]
    verifiedChain: list[str]
    normalised: bool = False
    normalisedNote: str | None = None


class TranscriptionProviderOrderUpdate(APIModel):
    order: list[str]
