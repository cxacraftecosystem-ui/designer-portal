from __future__ import annotations

import base64
import hashlib
import os
from dataclasses import dataclass

from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


@dataclass(frozen=True)
class EncryptedPid:
    skey: str
    ci: str
    data: str
    hmac: str


def certificate_ci(certificate: x509.Certificate) -> str:
    return certificate.not_valid_after_utc.strftime("%Y%m%d")


def encrypt_pid(pid_xml: bytes, ts: str, uidai_certificate: x509.Certificate) -> EncryptedPid:
    ts_bytes = ts.encode("utf-8")
    if len(ts_bytes) < 16:
        raise ValueError("the Pid ts must be at least 16 bytes")
    iv, aad = ts_bytes[-12:], ts_bytes[-16:]
    session_key = os.urandom(32)
    aes = AESGCM(session_key)
    data = ts_bytes + aes.encrypt(iv, pid_xml, aad)
    hmac = aes.encrypt(iv, hashlib.sha256(pid_xml).digest(), aad)
    public_key = uidai_certificate.public_key()
    if not isinstance(public_key, rsa.RSAPublicKey):
        raise ValueError("the UIDAI encryption certificate does not hold an RSA key")
    skey = public_key.encrypt(session_key, padding.PKCS1v15())
    del session_key, aes
    return EncryptedPid(
        skey=base64.b64encode(skey).decode(),
        ci=certificate_ci(uidai_certificate),
        data=base64.b64encode(data).decode(),
        hmac=base64.b64encode(hmac).decode(),
    )
