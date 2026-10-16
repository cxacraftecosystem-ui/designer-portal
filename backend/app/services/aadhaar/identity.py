"""The one result shape and the one error shape every Aadhaar method shares."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

METHOD_SECURE_QR = "SECURE_QR"
METHOD_OFFLINE_XML = "OFFLINE_XML"
METHOD_ONLINE_OTP = "ONLINE_OTP"
METHOD_ONLINE_EKYC = "ONLINE_EKYC"


@dataclass(frozen=True)
class VerifiedIdentity:
    """What a verification established, and the ONLY values that may be stored.

    Built after the signature (or UIDAI's online answer) has been checked; a value that was not
    signed by UIDAI never reaches this object. There is deliberately no field for the Aadhaar
    number, the photograph, the address, the share code or the payload.
    """

    method: str
    #: The last four digits of the Aadhaar number, as UIDAI prints them in the reference id. None
    #: where UIDAI did not say (an online authentication made with a VID).
    last4: str | None
    #: The resident's name exactly as UIDAI signed it. None for an online OTP authentication, which
    #: answers yes/no and returns no name.
    name: str | None
    dob: date | None
    birth_year: int | None
    #: "M", "F" or "T", as UIDAI writes it.
    gender: str | None
    #: When UIDAI signed the document (the reference id's timestamp) or answered online.
    signed_at: datetime | None
    #: UIDAI's transaction id, online methods only.
    txn: str | None = None
    #: For an online authentication that sent the record's name as a demographic match: UIDAI's
    #: own verdict on the name. None where UIDAI was not asked.
    uidai_name_match: bool | None = None


class AadhaarError(Exception):
    """A refusal with a code for the audit line and a sentence for the person.

    ``message`` is shown to the resident or the field worker as written: plain words, the next
    action, no jargon, and never a value from the document.
    """

    def __init__(self, code: str, message: str, status_code: int = 422) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
