from __future__ import annotations

from pydantic import Field

from app.schemas.common import APIModel

MAX_ZIP_BASE64_CHARS = 4 * 1024 * 1024
MAX_QR_TEXT_CHARS = 20_000


class ConsentBody(APIModel):
    consent: bool = False


class SecureQrBody(ConsentBody):
    qrText: str = Field(min_length=1, max_length=MAX_QR_TEXT_CHARS)


class OfflineXmlBody(ConsentBody):
    zipBase64: str = Field(min_length=1, max_length=MAX_ZIP_BASE64_CHARS)
    shareCode: str = Field(min_length=1, max_length=32)
    mobile: str | None = Field(default=None, max_length=20)
    email: str | None = Field(default=None, max_length=254)


class DecisionBody(APIModel):
    accept: bool


class OnlineOtpBody(ConsentBody):
    uid: str = Field(min_length=12, max_length=19)


class OnlineVerifyBody(ConsentBody):
    uid: str = Field(min_length=12, max_length=19)
    otp: str = Field(min_length=6, max_length=8)
    txn: str = Field(min_length=1, max_length=64)
    token: str = Field(min_length=1, max_length=512)
