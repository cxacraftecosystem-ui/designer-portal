"""Test-only builders for Aadhaar documents, signed with TEST keys made here — never real data.

UIDAI publishes no sample offline e-KYC file and its only sample Secure QR (the 2019 specification's
V1 number) is not signed by any published certificate, so every document a test verifies is built
by this module, in UIDAI's layout, and signed with a key generated for the run. The name, the date
of birth and every digit below are invented.

Also here: the two ZIP encryptions UIDAI's files use (Python's ``zipfile`` can read ZipCrypto but
not write it, and can do neither for WinZip AES), and a fake ASA for the online tests.
"""

from __future__ import annotations

import base64
import gzip
import hashlib
import hmac
import os
import struct
import zlib
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

import httpx
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.x509.oid import NameOID
from lxml import etree

from app.services.aadhaar import xmldsig

IST = timezone(timedelta(hours=5, minutes=30))

# Invented values. 2345 is not anybody's Aadhaar tail in particular; the names are placeholders.
TEST_NAME = "Kamla Devi Prajapati"
TEST_DOB = "07-05-1987"
TEST_LAST4 = "2345"


@dataclass(frozen=True)
class TestIdentity:
    key: rsa.RSAPrivateKey
    certificate: x509.Certificate

    def pem(self) -> bytes:
        return self.certificate.public_bytes(serialization.Encoding.PEM)

    def write_pem(self, directory: Path, name: str) -> str:
        path = directory / f"{name}.pem"
        path.write_bytes(self.pem())
        return str(path)


def make_identity(common_name: str) -> TestIdentity:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])
    now = datetime.now(UTC)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=365))
        .sign(key, hashes.SHA256())
    )
    return TestIdentity(key, certificate)


def reference_id(last4: str = TEST_LAST4, generated: datetime | None = None) -> str:
    moment = (generated or datetime.now(UTC)).astimezone(IST)
    return last4 + moment.strftime("%Y%m%d%H%M%S") + f"{moment.microsecond // 1000:03d}"


# ── Secure QR ────────────────────────────────────────────────────────────────────────────────


def secure_qr_payload(
    *,
    version: str | None = "V2",
    ref: str | None = None,
    name: str = TEST_NAME,
    dob: str = TEST_DOB,
    gender: str = "F",
    photo: bytes | None = None,
) -> bytes:
    """The bytes UIDAI signs: fields, 0xFF-separated, then a stand-in photo containing 0xFF."""
    fields = [
        "3",  # indicator: e-mail and mobile hashes present (unversioned layout)
        ref or reference_id(),
        name,
        dob,
        gender,
        "W/O Ramesh Prajapati",
        "Jaipur",
        "Near the well",
        "12",
        "Bagru",
        "303007",
        "Bagru",
        "Rajasthan",
        "Main road",
        "Sanganer",
        "Bagru",
    ]
    if version:
        fields = [version, *fields, "6789"]  # V2 onwards: last four of the mobile
    text = b"\xff".join(value.encode("iso-8859-1") for value in fields) + b"\xff"
    if photo is None:
        photo = b"\x00\x00\x00\x0cjP  \r\n\x87\n" + bytes(range(256)) * 3  # contains 0xFF bytes
    tail = b"" if version else hashlib.sha256(b"e").digest() + hashlib.sha256(b"m").digest()
    return text + photo + tail


def sign_qr(payload: bytes, key: rsa.RSAPrivateKey) -> bytes:
    return payload + key.sign(payload, padding.PKCS1v15(), hashes.SHA256())


def qr_text(signed: bytes) -> str:
    compressed = gzip.compress(signed)
    value = int.from_bytes(compressed, "big")
    # str(int) refuses past 4,300 digits; a real QR's number is longer, so convert in chunks.
    chunks: list[str] = []
    while value:
        value, chunk = divmod(value, 10**1000)
        chunks.append(str(chunk))
    if not chunks:
        return "0"
    return chunks[-1] + "".join(chunk.zfill(1000) for chunk in reversed(chunks[:-1]))


def build_secure_qr(key: rsa.RSAPrivateKey, **kwargs: object) -> str:
    return qr_text(sign_qr(secure_qr_payload(**kwargs), key))  # type: ignore[arg-type]


# ── Offline e-KYC XML ────────────────────────────────────────────────────────────────────────


def contact_hash(value: str, share_code: str, last_digit: int) -> str:
    current = (value + share_code).encode()
    digest = ""
    for _ in range(max(1, last_digit)):
        digest = hashlib.sha256(current).hexdigest()
        current = digest.encode()
    return digest


def offline_xml(
    identity: TestIdentity,
    *,
    share_code: str = "1234",
    ref: str | None = None,
    name: str = TEST_NAME,
    dob: str = TEST_DOB,
    gender: str = "F",
    mobile: str | None = "9876543210",
    email: str | None = "kamla@example.org",
    signature_method: str = xmldsig.RSA_SHA1,
    digest_method: str = xmldsig.DIGEST_SHA256,
    sign: bool = True,
) -> bytes:
    ref = ref or reference_id()
    digit = int(ref[3])
    root = etree.Element("OfflinePaperlessKyc", referenceId=ref)
    uid = etree.SubElement(root, "UidData")
    etree.SubElement(
        uid,
        "Poi",
        dob=dob,
        e=contact_hash(email, share_code, digit) if email else "",
        gender=gender,
        m=contact_hash(mobile, share_code, digit) if mobile else "",
        name=name,
    )
    etree.SubElement(
        uid, "Poa", careof="W/O Ramesh", country="India", dist="Jaipur", house="12",
        landmark="", loc="Bagru", pc="303007", po="Bagru", state="Rajasthan", street="",
        subdist="Sanganer", vtc="Bagru",
    )
    etree.SubElement(uid, "Pht").text = base64.b64encode(b"not a real photograph").decode()
    if sign:
        xmldsig.sign_enveloped(
            root,
            identity.key,
            identity.certificate,
            signature_method=signature_method,
            digest_method=digest_method,
        )
    return etree.tostring(root, xml_declaration=True, encoding="UTF-8")


# ── ZIP encryption: ZipCrypto and WinZip AES ─────────────────────────────────────────────────

_CRC_TABLE = []
for _n in range(256):
    _c = _n
    for _ in range(8):
        _c = (_c >> 1) ^ 0xEDB88320 if _c & 1 else _c >> 1
    _CRC_TABLE.append(_c)


def _crc_byte(old: int, byte: int) -> int:
    return (old >> 8) ^ _CRC_TABLE[(old ^ byte) & 0xFF]


class _ZipCrypto:
    def __init__(self, password: bytes) -> None:
        self.k0, self.k1, self.k2 = 0x12345678, 0x23456789, 0x34567890
        for byte in password:
            self._update(byte)

    def _update(self, byte: int) -> None:
        self.k0 = _crc_byte(self.k0, byte)
        self.k1 = (self.k1 + (self.k0 & 0xFF)) & 0xFFFFFFFF
        self.k1 = (self.k1 * 134775813 + 1) & 0xFFFFFFFF
        self.k2 = _crc_byte(self.k2, (self.k1 >> 24) & 0xFF)

    def encrypt(self, data: bytes) -> bytes:
        out = bytearray()
        for byte in data:
            temp = (self.k2 | 2) & 0xFFFF
            out.append(byte ^ (((temp * (temp ^ 1)) >> 8) & 0xFF))
            self._update(byte)
        return bytes(out)


def _zip(name: str, body: bytes, method: int, crc: int, size: int, flags: int, extra: bytes) -> bytes:
    encoded = name.encode()
    local = struct.pack(
        "<IHHHHHIIIHH", 0x04034B50, 51, flags, method, 0, 0x21, crc, len(body), size,
        len(encoded), len(extra),
    ) + encoded + extra
    central = struct.pack(
        "<IHHHHHHIIIHHHHHII", 0x02014B50, 51, 51, flags, method, 0, 0x21, crc, len(body), size,
        len(encoded), len(extra), 0, 0, 0, 0, 0,
    ) + encoded + extra
    end = struct.pack(
        "<IHHHHIIH", 0x06054B50, 0, 0, 1, 1, len(central), len(local) + len(body), 0
    )
    return local + body + central + end


def zipcrypto_zip(name: str, data: bytes, password: str) -> bytes:
    compressor = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    deflated = compressor.compress(data) + compressor.flush()
    crc = zlib.crc32(data) & 0xFFFFFFFF
    header = bytearray(os.urandom(11)) + bytes([crc >> 24])
    cipher = _ZipCrypto(password.encode())
    body = cipher.encrypt(bytes(header)) + cipher.encrypt(deflated)
    return _zip(name, body, 8, crc, len(data), 0x1, b"")


def aes_zip(name: str, data: bytes, password: str) -> bytes:
    compressor = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    deflated = compressor.compress(data) + compressor.flush()
    salt = os.urandom(16)
    derived = PBKDF2HMAC(algorithm=hashes.SHA1(), length=66, salt=salt, iterations=1000).derive(
        password.encode()
    )
    aes_key, mac_key, verifier = derived[:32], derived[32:64], derived[64:]
    blocks = (len(deflated) + 15) // 16
    counters = b"".join((i + 1).to_bytes(16, "little") for i in range(blocks))
    encryptor = Cipher(algorithms.AES(aes_key), modes.ECB()).encryptor()
    stream = encryptor.update(counters) + encryptor.finalize()
    ciphertext = bytes(a ^ b for a, b in zip(deflated, stream, strict=False))
    tag = hmac.new(mac_key, ciphertext, hashlib.sha1).digest()[:10]
    extra = struct.pack("<HHH2sBH", 0x9901, 7, 2, b"AE", 3, 8)
    return _zip(name, salt + verifier + ciphertext + tag, 99, 0, len(data), 0x1, extra)


# ── a fake ASA ───────────────────────────────────────────────────────────────────────────────


@dataclass
class FakeAsa:
    """Plays the ASA and UIDAI: checks the AUA's signature, decrypts the PID with the TEST UIDAI
    encryption key, checks the OTP, and answers signed with the TEST UIDAI signing key."""

    aua: TestIdentity
    uidai_encryption: TestIdentity
    uidai_signing: TestIdentity
    otp: str = "123456"
    #: When set, every answer is ret="n" with this UIDAI error code.
    error: str | None = None
    timeout: bool = False
    #: Every request the fake saw, for assertions. Bodies only — they contain no plaintext PID.
    seen: list[tuple[str, bytes]] = field(default_factory=list)
    issued_txns: set[str] = field(default_factory=set)
    #: The last decrypted PID, so a test can prove what was inside the encryption.
    last_pid: bytes | None = None
    kyc_name: str = TEST_NAME

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._handle)

    def _now(self) -> str:
        return datetime.now(IST).isoformat(timespec="milliseconds")

    def _signed(self, root: etree._Element) -> bytes:
        xmldsig.sign_enveloped(root, self.uidai_signing.key, self.uidai_signing.certificate)
        return etree.tostring(root, xml_declaration=True, encoding="UTF-8")

    def _handle(self, request: httpx.Request) -> httpx.Response:
        if self.timeout:
            raise httpx.ReadTimeout("fake ASA timed out", request=request)
        body = request.content
        self.seen.append((request.url.raw_path.decode(), body))
        root = xmldsig.parse(body)
        if request.url.path.startswith("/otp/"):
            xmldsig.verify_enveloped(root, [self.aua.certificate])
            txn = root.get("txn") or ""
            self.issued_txns.add(txn)
            answer = etree.Element(
                "OtpRes", ret="n" if self.error else "y", code="c0de", txn=txn, ts=self._now(),
                err=self.error or "", info="01{}",
            )
            return httpx.Response(200, content=etree.tostring(answer))
        if request.url.path.startswith("/auth/"):
            return httpx.Response(200, content=self._auth(root))
        if request.url.path.startswith("/kyc/"):
            rad = base64.b64decode(root.find("Rad").text or "")
            auth_root = xmldsig.parse(rad)
            ok, err, txn = self._check_auth(auth_root)
            kyc = etree.Element(
                "KycRes", ret="Y" if ok else "N", code="c0de", txn=txn, ts=self._now(),
                err=err or "",
            )
            if ok:
                data = etree.SubElement(kyc, "UidData", uid="xxxxxxxx" + TEST_LAST4, tkn="t")
                etree.SubElement(data, "Poi", name=self.kyc_name, dob=TEST_DOB, gender="F")
                etree.SubElement(data, "Poa", state="Rajasthan")
                etree.SubElement(data, "Pht").text = "AAAA"
            payload = base64.b64encode(self._signed(kyc)).decode()
            resp = etree.Element(
                "Resp", status="0" if ok else "-1", ret="Y" if ok else "N", code="c0de", txn=txn,
                ts=self._now(), err=err or "",
            )
            resp.text = payload
            return httpx.Response(200, content=etree.tostring(resp))
        return httpx.Response(404)

    def decrypt_pid(self, auth_root: etree._Element) -> tuple[bytes, bytes]:
        """(pid xml, hmac plaintext) recovered with the TEST UIDAI private key."""
        session_key = self.uidai_encryption.key.decrypt(
            base64.b64decode(auth_root.find("Skey").text or ""), padding.PKCS1v15()
        )
        data = base64.b64decode(auth_root.find("Data").text or "")
        ts, ciphertext = data[:19], data[19:]
        aes = AESGCM(session_key)
        pid = aes.decrypt(ts[-12:], ciphertext, ts[-16:])
        mac = aes.decrypt(ts[-12:], base64.b64decode(auth_root.find("Hmac").text or ""), ts[-16:])
        return pid, mac

    def _check_auth(self, auth_root: etree._Element) -> tuple[bool, str | None, str]:
        xmldsig.verify_enveloped(auth_root, [self.aua.certificate])
        txn = auth_root.get("txn") or ""
        if self.error:
            return False, self.error, txn
        if txn not in self.issued_txns:
            return False, "402", txn
        pid, mac = self.decrypt_pid(auth_root)
        self.last_pid = pid
        if hashlib.sha256(pid).digest() != mac:
            return False, "564", txn
        pv = etree.fromstring(pid).find("Pv")
        if pv is None or pv.get("otp") != self.otp:
            return False, "400", txn
        return True, None, txn

    def _auth(self, auth_root: etree._Element) -> bytes:
        ok, err, txn = self._check_auth(auth_root)
        answer = etree.Element(
            "AuthRes", ret="y" if ok else "n", code="c0de", txn=txn, ts=self._now(),
            err=err or "", info="04{}",
        )
        return self._signed(answer)
