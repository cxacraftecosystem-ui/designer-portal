"""Request bodies for /api/aadhaar. See docs/AADHAAR.md."""

from __future__ import annotations

from pydantic import Field

from app.schemas.common import APIModel

#: A Paperless Offline e-KYC ZIP is a few tens of kilobytes (the XML plus a small JPEG). Four
#: megabytes of base64 is generous and still bounds what a hostile client can make us decompress.
MAX_ZIP_BASE64_CHARS = 4 * 1024 * 1024
#: A Secure QR is a decimal integer of a few thousand digits.
MAX_QR_TEXT_CHARS = 20_000


class ConsentBody(APIModel):
    #: Must be ``true``: the person has read the consent notice on the screen and agreed. Anything
    #: else is refused before a byte of the document is looked at.
    consent: bool = False


class SecureQrBody(ConsentBody):
    qrText: str = Field(min_length=1, max_length=MAX_QR_TEXT_CHARS)


class OfflineXmlBody(ConsentBody):
    zipBase64: str = Field(min_length=1, max_length=MAX_ZIP_BASE64_CHARS)
    #: UIDAI's pages show four digits and also call it a "share phrase" with letters in it, so the
    #: length is not hard-coded beyond a sanity bound. Never stored, never logged.
    shareCode: str = Field(min_length=1, max_length=32)
    #: Optional. Checked against the hashes UIDAI put in the XML ONLY when supplied; never stored.
    mobile: str | None = Field(default=None, max_length=20)
    email: str | None = Field(default=None, max_length=254)


class DecisionBody(APIModel):
    accept: bool


class OnlineOtpBody(ConsentBody):
    #: The 12-digit Aadhaar number or the 16-digit VID. Never stored, never logged.
    uid: str = Field(min_length=12, max_length=19)


class OnlineVerifyBody(ConsentBody):
    uid: str = Field(min_length=12, max_length=19)
    otp: str = Field(min_length=6, max_length=8)
    txn: str = Field(min_length=1, max_length=64)
    #: The server's own token from the OTP step, binding this txn to this caller and this number.
    token: str = Field(min_length=1, max_length=512)
