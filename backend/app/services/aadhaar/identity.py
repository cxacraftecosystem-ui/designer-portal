from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

METHOD_SECURE_QR = "SECURE_QR"
METHOD_OFFLINE_XML = "OFFLINE_XML"
METHOD_ONLINE_OTP = "ONLINE_OTP"
METHOD_ONLINE_EKYC = "ONLINE_EKYC"


@dataclass(frozen=True)
class VerifiedIdentity:
    method: str
    last4: str | None
    name: str | None
    dob: date | None
    birth_year: int | None
    gender: str | None
    signed_at: datetime | None
    txn: str | None = None
    uidai_name_match: bool | None = None


class AadhaarError(Exception):
    def __init__(self, code: str, message: str, status_code: int = 422) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
