"""Aadhaar Paperless Offline e-KYC — the password-protected ZIP a resident downloads from UIDAI.

THE FILE. A ZIP holding one XML, protected with the "share code" (UIDAI also calls it the share
phrase) the resident chose when downloading. UIDAI names no ZIP algorithm ("any standard unzipping
utility"); files in the wild use both the legacy ZipCrypto scheme, which ``zipfile`` reads, and
WinZip AES, which it cannot — so AES is decrypted here (``_aes_member``) with ``cryptography``.

THE XML::

    <OfflinePaperlessKyc referenceId="<last 4 of Aadhaar><YYYYMMDDHHMMSSmmm>">
      <UidData>
        <Poi name dob gender e m/>      e, m: hashes of the e-mail and mobile (see below)
        <Poa careof country dist house landmark loc pc po state street subdist vtc/>
        <Pht>base64 JPEG 2000</Pht>
      </UidData>
      <Signature>…enveloped XMLDSig over the whole document…</Signature>
    </OfflinePaperlessKyc>

Real files are signed RSA-SHA1 over a SHA-256 digest with inclusive C14N (verified against three
genuine files on 2026-10-16); ``xmldsig.verify_enveloped`` accepts that and the SHA-256 variants.

THE MOBILE / E-MAIL HASH, UIDAI's wording: "Sha256(Sha256(Mobile+SharePhrase))*number of times last
digit of Aadhaar number … ends with Zero hashed one time". Read, as every published reader reads it,
as: hash ``value + share code`` to lower-case hex, then hash that hex string again, ``max(1, digit)``
rounds in all. Checked ONLY when the person types a mobile number or e-mail address, and never stored.

AGE. ``AADHAAR_OFFLINE_XML_MAX_AGE_DAYS`` (default 3). UIDAI itself publishes no maximum; 3 days is
the RBI KYC Master Direction's rule for an XML or QR, which the owner adopted for this deployment.

NOTHING IS KEPT. The ZIP, the XML, the share code, the photo and the address are discarded when this
function returns.
"""

from __future__ import annotations

import hashlib
import hmac
import io
import struct
import zipfile
import zlib
from datetime import datetime, timedelta

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from app.core.config import get_settings
from app.services.aadhaar import certs, xmldsig
from app.services.aadhaar.identity import METHOD_OFFLINE_XML, AadhaarError, VerifiedIdentity
from app.services.aadhaar.secure_qr import normalise_gender, parse_dob, parse_reference_id

MAX_MEMBERS = 4
MAX_XML_BYTES = 5 * 1024 * 1024
_AES_EXTRA_ID = 0x9901
_AES_KEY_BYTES = {1: 16, 2: 24, 3: 32}

NOT_A_KYC_ZIP = (
    "This is not an Aadhaar offline e-KYC file. Upload the ZIP file downloaded from UIDAI's "
    "website, without unzipping or renaming what is inside it."
)
WRONG_SHARE_CODE = "That share code does not open this file. Type the share code you chose when downloading it."
NOT_SIGNED = (
    "This file is not signed by UIDAI, or it was changed after UIDAI signed it, so it cannot be "
    "trusted. Download a fresh offline e-KYC file from UIDAI."
)


# ── the ZIP ──────────────────────────────────────────────────────────────────────────────────


def _aes_member(archive: bytes, info: zipfile.ZipInfo, password: bytes) -> bytes:
    """Decrypt one WinZip-AES (AE-1/AE-2) member: PBKDF2-HMAC-SHA1 x1000, AES-CTR with a
    little-endian counter from 1, HMAC-SHA1 truncated to 10 bytes over the ciphertext."""
    extra = info.extra
    strength = method = None
    position = 0
    while position + 4 <= len(extra):
        header_id, size = struct.unpack("<HH", extra[position : position + 4])
        body = extra[position + 4 : position + 4 + size]
        if header_id == _AES_EXTRA_ID and len(body) >= 7:
            strength = body[4]
            method = struct.unpack("<H", body[5:7])[0]
        position += 4 + size
    if strength not in _AES_KEY_BYTES or method not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
        raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
    key_bytes = _AES_KEY_BYTES[strength]
    salt_bytes = key_bytes // 2
    offset = info.header_offset
    if archive[offset : offset + 4] != b"PK\x03\x04":
        raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
    name_length, extra_length = struct.unpack("<HH", archive[offset + 26 : offset + 30])
    start = offset + 30 + name_length + extra_length
    blob = archive[start : start + info.compress_size]
    if len(blob) != info.compress_size or len(blob) < salt_bytes + 2 + 10:
        raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
    salt, verifier = blob[:salt_bytes], blob[salt_bytes : salt_bytes + 2]
    ciphertext, tag = blob[salt_bytes + 2 : -10], blob[-10:]
    derived = PBKDF2HMAC(
        algorithm=hashes.SHA1(), length=2 * key_bytes + 2, salt=salt, iterations=1000
    ).derive(password)
    aes_key, mac_key, check = derived[:key_bytes], derived[key_bytes : 2 * key_bytes], derived[-2:]
    if not hmac.compare_digest(check, verifier):
        raise AadhaarError("share_code_wrong", WRONG_SHARE_CODE)
    expected = hmac.new(mac_key, ciphertext, hashlib.sha1).digest()[:10]
    if not hmac.compare_digest(expected, tag):
        raise AadhaarError("zip_tampered", NOT_SIGNED)
    blocks = (len(ciphertext) + 15) // 16
    counters = b"".join((index + 1).to_bytes(16, "little") for index in range(blocks))
    encryptor = Cipher(algorithms.AES(aes_key), modes.ECB()).encryptor()
    stream = encryptor.update(counters) + encryptor.finalize()
    plain = bytes(a ^ b for a, b in zip(ciphertext, stream, strict=False))
    if method == zipfile.ZIP_DEFLATED:
        inflater = zlib.decompressobj(-zlib.MAX_WBITS)
        plain = inflater.decompress(plain, MAX_XML_BYTES)
        if inflater.unconsumed_tail:
            raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
    return plain


def extract_xml(archive: bytes, share_code: str) -> bytes:
    """The one XML inside the ZIP, decrypted with the share code. Raises AadhaarError."""
    password = share_code.strip().encode("utf-8")
    try:
        bundle = zipfile.ZipFile(io.BytesIO(archive))
    except (zipfile.BadZipFile, ValueError):
        raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP) from None
    with bundle:
        members = [info for info in bundle.infolist() if not info.is_dir()]
        xmls = [info for info in members if info.filename.lower().endswith(".xml")]
        if len(members) > MAX_MEMBERS or len(xmls) != 1:
            raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
        info = xmls[0]
        if info.file_size > MAX_XML_BYTES:
            raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
        if not info.flag_bits & 0x1:
            # An unencrypted offline e-KYC ZIP is not something UIDAI produces.
            raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
        if info.compress_type == 99:
            return _aes_member(archive, info, password)
        try:
            with bundle.open(info, pwd=password) as handle:
                data = handle.read(MAX_XML_BYTES + 1)
        except RuntimeError:
            raise AadhaarError("share_code_wrong", WRONG_SHARE_CODE) from None
        except (zipfile.BadZipFile, zlib.error, EOFError):
            # ZipCrypto's one-byte password check passes a wrong password 1 time in 256; the
            # CRC or the inflater then fails instead.
            raise AadhaarError("share_code_wrong", WRONG_SHARE_CODE) from None
        except NotImplementedError:
            raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP) from None
        if len(data) > MAX_XML_BYTES:
            raise AadhaarError("zip_unreadable", NOT_A_KYC_ZIP)
        return data


# ── the hashes ───────────────────────────────────────────────────────────────────────────────


def contact_hash(value: str, share_code: str, last_digit: int) -> str:
    current = (value + share_code).encode("utf-8")
    digest = ""
    for _ in range(max(1, last_digit)):
        digest = hashlib.sha256(current).hexdigest()
        current = digest.encode("ascii")
    return digest


def _mobile_digits(mobile: str) -> str:
    digits = "".join(ch for ch in mobile if ch.isdigit())
    return digits[-10:]


def _check_contact(
    kind: str, offered: str | None, stored_hash: str | None, share_code: str, last_digit: int
) -> None:
    if not offered or not offered.strip():
        return
    if not stored_hash:
        raise AadhaarError(
            f"{kind}_absent",
            f"This file carries no {kind} to check against. Leave the {kind} box empty, or "
            "download the file again from UIDAI.",
        )
    candidates = (
        [_mobile_digits(offered)]
        if kind == "mobile"
        else list(dict.fromkeys([offered.strip(), offered.strip().lower()]))
    )
    for candidate in candidates:
        if hmac.compare_digest(
            contact_hash(candidate, share_code, last_digit), stored_hash.strip().lower()
        ):
            return
    raise AadhaarError(
        f"{kind}_mismatch",
        f"The {kind} you typed is not the one registered with this Aadhaar.",
    )


# ── the whole check ──────────────────────────────────────────────────────────────────────────


def verify_offline_xml(
    xml: bytes,
    share_code: str,
    *,
    mobile: str | None = None,
    email: str | None = None,
    now: datetime,
) -> VerifiedIdentity:
    try:
        root = xmldsig.parse(xml)
    except xmldsig.SignatureError:
        raise AadhaarError("xml_format", NOT_A_KYC_ZIP) from None
    if root.tag != "OfflinePaperlessKyc":
        raise AadhaarError("xml_format", NOT_A_KYC_ZIP)
    try:
        xmldsig.verify_enveloped(root, certs.trusted_certificates(certs.PURPOSE_OFFLINE_SIGNATURE))
    except xmldsig.SignatureError as error:
        code = "unsigned" if error.code == "unsigned" else "signature_invalid"
        raise AadhaarError(code, NOT_SIGNED) from None
    poi = root.find("UidData/Poi")
    reference_id = root.get("referenceId") or ""
    if poi is None:
        raise AadhaarError("xml_format", NOT_A_KYC_ZIP)
    last4, generated_at = parse_reference_id(reference_id)
    max_age = float(get_settings().aadhaar_offline_xml_max_age_days or 0)
    if max_age > 0 and now - generated_at > timedelta(days=max_age):
        days = f"{max_age:g}"
        raise AadhaarError(
            "expired",
            f"This file was downloaded from UIDAI more than {days} day"
            f"{'' if days == '1' else 's'} ago. Download a fresh offline e-KYC file and upload "
            "that one.",
        )
    last_digit = int(last4[-1])
    _check_contact("mobile", mobile, poi.get("m"), share_code.strip(), last_digit)
    _check_contact("email", email, poi.get("e"), share_code.strip(), last_digit)
    dob, birth_year = parse_dob(poi.get("dob") or "")
    return VerifiedIdentity(
        method=METHOD_OFFLINE_XML,
        last4=last4,
        name=(poi.get("name") or "").strip() or None,
        dob=dob,
        birth_year=birth_year,
        gender=normalise_gender(poi.get("gender") or ""),
        signed_at=generated_at,
    )


def verify_offline_zip(
    archive: bytes,
    share_code: str,
    *,
    mobile: str | None = None,
    email: str | None = None,
    now: datetime,
) -> VerifiedIdentity:
    """Open, verify, and return only what may be stored. Raises AadhaarError."""
    xml = extract_xml(archive, share_code)
    return verify_offline_xml(xml, share_code, mobile=mobile, email=email, now=now)
