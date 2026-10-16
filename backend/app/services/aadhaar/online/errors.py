"""UIDAI's error codes, as the person in front of the screen should read them.

The codes are from Aadhaar Authentication API 2.5 Revision 1 (January 2022), OTP Request API 2.5,
e-KYC API 2.5 and Circular 3/2026 (pre-production quotas). Every message names the next action and
never repeats a number. Codes this table does not list fall back to ``GENERIC``, and the code itself
is kept for the audit line (``AadhaarError.code`` = ``uidai_<code>``).

Two audiences: ``RESIDENT`` messages are shown to the person verifying; ``OPERATOR`` codes mean the
deployment is misconfigured, so the person is told to try another method and the operator's log
carries the code.
"""

from __future__ import annotations

GENERIC = "UIDAI could not verify this Aadhaar just now. Try again, or verify with the QR code or the offline e-KYC file."
OPERATOR_PROBLEM = (
    "Online Aadhaar verification is not working on this server at the moment. Verify with the QR "
    "code or the offline e-KYC file instead."
)

RESIDENT: dict[str, str] = {
    # OTP request
    "110": "This Aadhaar has no e-mail address registered with UIDAI. Use the SMS code instead.",
    "111": "This Aadhaar has no mobile number registered with UIDAI, so no code can be sent. Use the QR code or the offline e-KYC file.",
    "112": "This Aadhaar has neither a mobile number nor an e-mail address registered with UIDAI. Use the QR code or the offline e-KYC file.",
    "113": "The e-mail address registered with this Aadhaar is not verified, so no code can be sent there.",
    "114": "The mobile number registered with this Aadhaar is not verified, so no code can be sent. Use the QR code or the offline e-KYC file.",
    "115": "The mobile number and e-mail registered with this Aadhaar are not verified, so no code can be sent.",
    "950": "UIDAI could not send the code. Wait a minute and ask for a new one.",
    "952": "A code was asked for moments ago. Wait 30 seconds before asking again.",
    "953": "Too many codes have been asked for this Aadhaar. UIDAI has paused it for 30 minutes.",
    # Auth
    "100": "UIDAI says the name on this record does not match the name on the Aadhaar. Check the name, or verify with the QR code or the offline e-KYC file.",
    "330": "This Aadhaar holder has locked their biometrics. That does not affect the code — try again.",
    "331": "This Aadhaar number is locked by its holder. Use the Virtual ID (VID) instead.",
    "332": "This Aadhaar number cannot be used for verification. Use the Virtual ID (VID) instead.",
    "400": "That code is not correct. Check the SMS and type the six digits again.",
    "402": "That code belongs to a different request. Ask for a new code.",
    "403": "That code has expired or has been tried too many times. Ask for a new code.",
    "515": "That Virtual ID (VID) is not valid. Check the 16 digits, or use the Aadhaar number.",
    "517": "That Virtual ID (VID) has expired. Generate a new VID from UIDAI, or use the Aadhaar number.",
    "561": "The request took too long to reach UIDAI. Ask for a new code.",
    "740": "Type the code from the SMS before continuing.",
    "902": "UIDAI refused the date of birth sent with this request.",
    "995": "This Aadhaar has been suspended by a competent authority and cannot be verified.",
    "996": "This Aadhaar has been cancelled and cannot be verified.",
    "997": "This Aadhaar is suspended and cannot be verified.",
    "998": "That number is not a valid Aadhaar or Virtual ID. Check the digits.",
    # e-KYC
    "K-100": "UIDAI could not authenticate this Aadhaar for e-KYC. Check the code and try again.",
    "K-955": "UIDAI's e-KYC service had a problem. Try again in a few minutes.",
}

#: Codes that mean THIS SERVER is set up wrongly — licence keys, AUA code, certificates, signing.
OPERATOR: frozenset[str] = frozenset(
    {
        "500", "501", "502", "503", "504", "505", "510", "511", "512", "513", "520", "521",
        "522", "523", "530", "531", "540", "541", "542", "543", "550", "552", "563", "564",
        "565", "566", "569", "570", "573", "579", "581", "940", "941", "980", "1605", "1606",
        "K-540", "K-541", "K-542", "K-544", "K-545", "K-546", "K-547", "K-550", "K-551",
        "K-552", "K-553", "K-569", "K-570", "K-571", "K-600", "K-601", "K-602", "K-603",
        "K-604", "K-605",
    }
)


def message_for(code: str | None) -> tuple[str, bool]:
    """(message, is_operator_problem) for a UIDAI error code."""
    key = (code or "").strip()
    if key in OPERATOR:
        return OPERATOR_PROBLEM, True
    if key in RESIDENT:
        return RESIDENT[key], False
    if key.isdigit() and 930 <= int(key) <= 939:
        return "UIDAI had a technical problem. Try again in a few minutes.", False
    return GENERIC, False
