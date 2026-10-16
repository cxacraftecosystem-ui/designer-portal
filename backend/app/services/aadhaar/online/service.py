"""Online Aadhaar authentication: ask UIDAI for an OTP, then authenticate with it (or run e-KYC).

THE TWO STEPS ARE STATELESS ON THIS SERVER. Between "send me a code" and "here is the code" nothing
is stored — not the number, not the transaction. The OTP step answers with UIDAI's ``txn`` and a
short-lived token, an HMAC under AADHAAR_THROTTLE_SALT over (caller, record, hash of the number,
txn, expiry); the client sends the number, the code, the txn and the token back, and the token
proves the txn was issued to this caller for this number on this record within ten minutes.

WHAT IS NEVER KEPT OR LOGGED: the Aadhaar number or VID, the OTP, the PID block, the session key,
and UIDAI's raw answer. Log lines name the txn, the outcome and UIDAI's error code only.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote

import httpx

from app.services.aadhaar import xmldsig
from app.services.aadhaar.identity import (
    METHOD_ONLINE_EKYC,
    METHOD_ONLINE_OTP,
    AadhaarError,
    VerifiedIdentity,
)
from app.services.aadhaar.online import messages, throttle
from app.services.aadhaar.online.config import OnlineConfig, load_online_config
from app.services.aadhaar.online.errors import message_for
from app.services.aadhaar.secure_qr import normalise_gender, parse_dob
from app.services.artisan_identity import verhoeff_ok

logger = logging.getLogger("app.aadhaar.online")

TOKEN_TTL_SECONDS = 600

#: The HTTP transport to the ASA. None is httpx's real network transport; the tests put a fake ASA
#: here (``tests/aadhaar_support.py``) and nothing else ever sets it.
transport: httpx.AsyncBaseTransport | None = None

INVALID_UID = (
    "That is not a valid Aadhaar number (12 digits) or Virtual ID (16 digits). Check the digits."
)
UNREACHABLE = (
    "UIDAI could not be reached just now. Try again in a minute, or verify with the QR code or the "
    "offline e-KYC file."
)
SESSION_EXPIRED = "This code request has expired. Ask for a new code."


@dataclass(frozen=True)
class OtpIssued:
    txn: str
    token: str
    expires_at: datetime
    last4: str | None
    uid_type: str

    def as_payload(self) -> dict[str, Any]:
        return {
            "txn": self.txn,
            "token": self.token,
            "expiresAt": self.expires_at.isoformat(),
            "uidType": "VID" if self.uid_type == "V" else "AADHAAR",
        }


def normalise_uid(raw: str) -> str:
    digits = "".join(ch for ch in raw if ch.isdigit())
    if len(digits) == 12 and digits[0] not in "01" and verhoeff_ok(digits):
        return digits
    if len(digits) == 16 and verhoeff_ok(digits):
        return digits
    raise AadhaarError("invalid_uid", INVALID_UID)


def _url(base: str, uid: str, config: OnlineConfig) -> str:
    first, second = (uid[0], uid[1]) if len(uid) == 12 else ("0", "0")
    return f"{base}/{quote(config.aua_code, safe='')}/{first}/{second}/{quote(config.asa_license_key, safe='')}"


def _token(config: OnlineConfig, actor_id: str, subject: str, uid_hash: str, txn: str, expiry: int) -> str:
    message = f"{actor_id}|{subject}|{uid_hash}|{txn}|{expiry}".encode()
    signature = hmac.new(config.throttle_salt, message, hashlib.sha256).hexdigest()
    return f"{expiry}.{signature}"


def _check_token(
    config: OnlineConfig, token: str, actor_id: str, subject: str, uid_hash: str, txn: str
) -> None:
    expiry_text, _, _signature = token.partition(".")
    if not expiry_text.isdigit():
        raise AadhaarError("otp_session", SESSION_EXPIRED)
    expiry = int(expiry_text)
    expected = _token(config, actor_id, subject, uid_hash, txn, expiry)
    if not hmac.compare_digest(expected, token) or expiry < int(time.time()):
        raise AadhaarError("otp_session", SESSION_EXPIRED)


async def _post(url: str, body: bytes, config: OnlineConfig) -> bytes:
    try:
        async with httpx.AsyncClient(timeout=config.timeout_seconds, transport=transport) as client:
            response = await client.post(
                url, content=body, headers={"Content-Type": "application/xml"}
            )
    except httpx.HTTPError:
        raise AadhaarError("asa_unreachable", UNREACHABLE, status_code=503) from None
    if response.status_code != 200:
        logger.warning("aadhaar: ASA answered HTTP %s", response.status_code)
        raise AadhaarError("asa_http_error", UNREACHABLE, status_code=503)
    return response.content


def _refuse_for(err: str | None, txn: str | None, stage: str) -> AadhaarError:
    message, operator = message_for(err)
    if operator:
        logger.error("aadhaar: %s refused for configuration reasons, txn=%s err=%s", stage, txn, err)
    else:
        logger.info("aadhaar: %s refused, txn=%s err=%s", stage, txn, err)
    code = "uidai_name_mismatch" if err == "100" else f"uidai_{err or 'unknown'}"
    return AadhaarError(code, message, status_code=503 if operator else 422)


def _verify_uidai_signature(root: Any, config: OnlineConfig, stage: str) -> None:
    try:
        xmldsig.verify_enveloped(root, config.response_certificates)
    except xmldsig.SignatureError as error:
        logger.error("aadhaar: %s answer failed UIDAI signature check (%s)", stage, error.code)
        raise AadhaarError(
            "response_unverified",
            "UIDAI's answer could not be verified, so it was not accepted. Try again, or verify "
            "with the QR code or the offline e-KYC file.",
            status_code=503,
        ) from None


def _answer_time(ts: str | None) -> datetime:
    if ts:
        try:
            parsed = datetime.fromisoformat(ts)
            return parsed.astimezone(UTC) if parsed.tzinfo else parsed.replace(tzinfo=messages.IST).astimezone(UTC)
        except ValueError:
            pass
    return datetime.now(UTC)


async def send_otp(uid_raw: str, *, actor_id: str, subject: str) -> OtpIssued:
    uid = normalise_uid(uid_raw)
    config = load_online_config()
    hashed = throttle.uid_hash(uid, config.throttle_salt)
    await throttle.admit(
        actor_id,
        hashed,
        per_user=config.limit_per_user,
        per_uid=config.limit_per_uid,
        window_minutes=config.window_minutes,
    )
    txn = ("UKC:" if config.ekyc else "") + f"DPW{uuid.uuid4().hex}"
    ts = messages.ist_timestamp(datetime.now(UTC))
    body = messages.otp_request(uid, txn, ts, config)
    try:
        answer = messages.parse_answer(await _post(_url(config.otp_url, uid, config), body, config), "OtpRes")
    except xmldsig.SignatureError:
        raise AadhaarError("asa_bad_answer", UNREACHABLE, status_code=503) from None
    if not answer.ok:
        raise _refuse_for(answer.err, txn, "OTP")
    expiry = int(time.time()) + TOKEN_TTL_SECONDS
    logger.info("aadhaar: OTP sent, txn=%s", txn)
    return OtpIssued(
        txn=txn,
        token=_token(config, actor_id, subject, hashed, txn, expiry),
        expires_at=datetime.fromtimestamp(expiry, UTC),
        last4=uid[-4:] if len(uid) == 12 else None,
        uid_type=messages.uid_type(uid),
    )


async def verify_otp(
    uid_raw: str,
    otp: str,
    txn: str,
    token: str,
    *,
    actor_id: str,
    subject: str,
    recorded_name: str | None,
) -> VerifiedIdentity:
    uid = normalise_uid(uid_raw)
    config = load_online_config()
    _check_token(config, token, actor_id, subject, throttle.uid_hash(uid, config.throttle_salt), txn)
    code = "".join(ch for ch in otp if ch.isdigit())
    if len(code) != 6:
        raise AadhaarError("uidai_740", "Type the six-digit code from the SMS.")
    ts = messages.ist_timestamp(datetime.now(UTC))
    if config.ekyc:
        return await _ekyc(uid, txn, code, ts, config)
    name = recorded_name if config.name_check else None
    signed = messages.auth_request(uid, txn, code, ts, config, name=name)
    try:
        answer = messages.parse_answer(await _post(_url(config.auth_url, uid, config), signed, config), "AuthRes")
    except xmldsig.SignatureError:
        raise AadhaarError("asa_bad_answer", UNREACHABLE, status_code=503) from None
    _verify_uidai_signature(answer.root, config, "Auth")
    if not answer.ok:
        raise _refuse_for(answer.err, txn, "Auth")
    logger.info("aadhaar: authenticated, txn=%s", txn)
    return VerifiedIdentity(
        method=METHOD_ONLINE_OTP,
        last4=uid[-4:] if len(uid) == 12 else None,
        name=None,
        dob=None,
        birth_year=None,
        gender=None,
        signed_at=_answer_time(answer.ts),
        txn=answer.txn or txn,
        uidai_name_match=True if name else None,
    )


async def _ekyc(uid: str, txn: str, otp: str, ts: str, config: OnlineConfig) -> VerifiedIdentity:
    signed_auth = messages.auth_request(uid, txn, otp, ts, config, name=None)
    body = messages.kyc_request(signed_auth)
    try:
        status, err, kyc = messages.parse_kyc_answer(
            await _post(_url(str(config.kyc_url), uid, config), body, config)
        )
    except xmldsig.SignatureError:
        raise AadhaarError("asa_bad_answer", UNREACHABLE, status_code=503) from None
    if status != "0" or kyc is None:
        if err == "encrypted":
            logger.error("aadhaar: e-KYC answer still encrypted, txn=%s — the ASA must decrypt (de=Y)", txn)
            raise AadhaarError("ekyc_encrypted", message_for("K-600")[0], status_code=503)
        raise _refuse_for(err, txn, "e-KYC")
    _verify_uidai_signature(kyc, config, "e-KYC")
    if (kyc.get("ret") or "").upper() != "Y":
        raise _refuse_for(kyc.get("err"), txn, "e-KYC")
    poi = kyc.find("UidData/Poi")
    if poi is None:
        raise AadhaarError("asa_bad_answer", UNREACHABLE, status_code=503)
    masked = (kyc.find("UidData").get("uid") or "") if kyc.find("UidData") is not None else ""
    tail = "".join(ch for ch in masked if ch.isdigit())[-4:]
    dob, birth_year = parse_dob(poi.get("dob") or "")
    logger.info("aadhaar: e-KYC completed, txn=%s", txn)
    return VerifiedIdentity(
        method=METHOD_ONLINE_EKYC,
        last4=tail if len(tail) == 4 else (uid[-4:] if len(uid) == 12 else None),
        name=(poi.get("name") or "").strip() or None,
        dob=dob,
        birth_year=birth_year,
        gender=normalise_gender(poi.get("gender") or ""),
        signed_at=_answer_time(kyc.get("ts")),
        txn=kyc.get("txn") or txn,
    )
