from __future__ import annotations

import zlib
from datetime import UTC, date, datetime, timedelta

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from app.core.config import get_settings
from app.services.aadhaar import certs
from app.services.aadhaar.identity import METHOD_SECURE_QR, AadhaarError, VerifiedIdentity

SIGNATURE_BYTES = 256
DELIMITER = 0xFF
MAX_DECOMPRESSED_BYTES = 64 * 1024

NOT_A_SECURE_QR = (
    "This is not an Aadhaar Secure QR code. Scan the large QR code on the Aadhaar letter, card or "
    "e-Aadhaar."
)
NOT_SIGNED = (
    "This QR code is not signed by UIDAI, so it cannot be trusted. Scan the QR code on the original "
    "Aadhaar letter, card or e-Aadhaar."
)


def _digits_to_bytes(text: str) -> bytes:
    digits = "".join(text.split())
    if not digits or not digits.isdigit():
        raise AadhaarError("qr_format", NOT_A_SECURE_QR)
    value = 0
    for start in range(0, len(digits), 1000):
        chunk = digits[start : start + 1000]
        value = value * (10 ** len(chunk)) + int(chunk)
    if value == 0:
        raise AadhaarError("qr_format", NOT_A_SECURE_QR)
    return value.to_bytes((value.bit_length() + 7) // 8, "big")


def decompress(text: str) -> bytes:
    raw = _digits_to_bytes(text)
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        data = inflater.decompress(raw, MAX_DECOMPRESSED_BYTES)
    except zlib.error:
        raise AadhaarError("qr_format", NOT_A_SECURE_QR) from None
    if inflater.unconsumed_tail:
        raise AadhaarError("qr_format", NOT_A_SECURE_QR)
    if len(data) <= SIGNATURE_BYTES + 16:
        raise AadhaarError("qr_format", NOT_A_SECURE_QR)
    return data


def verify_signature(data: bytes) -> None:
    signature, signed = data[-SIGNATURE_BYTES:], data[:-SIGNATURE_BYTES]
    for certificate in certs.trusted_certificates(certs.PURPOSE_OFFLINE_SIGNATURE):
        key = certificate.public_key()
        if not isinstance(key, rsa.RSAPublicKey):
            continue
        try:
            key.verify(signature, signed, padding.PKCS1v15(), hashes.SHA256())
            return
        except InvalidSignature:
            continue
    raise AadhaarError("signature_invalid", NOT_SIGNED)


def _fields(signed: bytes, count: int) -> list[str]:
    out: list[str] = []
    position = 0
    for _ in range(count):
        end = signed.find(bytes([DELIMITER]), position)
        if end < 0:
            raise AadhaarError("qr_format", NOT_A_SECURE_QR)
        out.append(signed[position:end].decode("iso-8859-1"))
        position = end + 1
    return out


def parse_reference_id(reference_id: str) -> tuple[str, datetime]:
    value = reference_id.strip()
    if len(value) < 18 or not value.isdigit():
        raise AadhaarError("qr_format", NOT_A_SECURE_QR)
    last4, stamp = value[:4], value[4:]
    try:
        moment = datetime.strptime(stamp[:14] + "+0530", "%Y%m%d%H%M%S%z")
    except ValueError:
        raise AadhaarError("qr_format", NOT_A_SECURE_QR) from None
    millis = int(stamp[14:17] or "0") if stamp[14:17].isdigit() else 0
    moment = (moment + timedelta(milliseconds=millis)).astimezone(UTC)
    return last4, moment


def parse_dob(value: str) -> tuple[date | None, int | None]:
    text = (value or "").strip()
    for fmt in ("%d-%m-%Y", "%d/%m/%Y", "%Y-%m-%d"):
        try:
            parsed = datetime.strptime(text, fmt).replace(tzinfo=UTC).date()
            return parsed, parsed.year
        except ValueError:
            continue
    if len(text) == 4 and text.isdigit():
        return None, int(text)
    return None, None


def normalise_gender(value: str) -> str | None:
    text = (value or "").strip().upper()[:1]
    return text if text in ("M", "F", "T") else None


def verify_secure_qr(text: str, *, now: datetime) -> VerifiedIdentity:
    data = decompress(text)
    verify_signature(data)
    signed = data[:-SIGNATURE_BYTES]
    versioned = signed[:1] == b"V" and signed[1:2].isdigit()
    offset = 1 if versioned else 0
    fields = _fields(signed, offset + 5)
    reference_id, name, dob_text, gender = fields[offset + 1 : offset + 5]
    last4, generated_at = parse_reference_id(reference_id)
    max_age = float(get_settings().aadhaar_secure_qr_max_age_days or 0)
    if max_age > 0 and now - generated_at > timedelta(days=max_age):
        raise AadhaarError(
            "expired",
            "This QR code is older than this deployment accepts. Download a fresh e-Aadhaar and "
            "scan the QR code on it.",
        )
    dob, birth_year = parse_dob(dob_text)
    return VerifiedIdentity(
        method=METHOD_SECURE_QR,
        last4=last4,
        name=name.strip() or None,
        dob=dob,
        birth_year=birth_year,
        gender=normalise_gender(gender),
        signed_at=generated_at,
    )
