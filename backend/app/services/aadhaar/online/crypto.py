"""The PID block's encryption, exactly as Aadhaar Authentication API 2.5 §4.1 describes it.

    "PID block data should be encrypted with a dynamic session key using AES-256 symmetric
    algorithm (AES/GCM/NoPadding). Session key, in turn, is encrypted with 2048-bit UIDAI public
    key using asymmetric algorithm (RSA/ECB/PKCS1Padding) … Last 12 bytes of the ts … is used as
    the IV … Last 16 bytes of the ts … is used as the AAD … encrypt and append the authentication
    tag to the end of the cipher text … the ts is appended to the final encrypted pid and then it's
    base64 encoded."

So, with ``ts`` the Pid's own ``ts`` attribute as UTF-8 bytes:

* a FRESH 32-byte session key per transaction, held only in this function's frame;
* ``Skey``  = base64(RSA-PKCS#1 v1.5(UIDAI encryption certificate, session key));
* ``ci``    = that certificate's expiry as YYYYMMDD;
* ``Data``  = base64(ts ‖ AES-256-GCM(key, iv=ts[-12:], aad=ts[-16:], pid) ‖ tag);
* ``Hmac``  = base64(AES-256-GCM(key, same iv, same aad, SHA-256(pid)) ‖ tag).

"Appended" in the spec's sentence is read as "joined", with ``ts`` FIRST — the layout UIDAI's
reference clients use and the only one under which the server can recover the IV before decrypting.
That reading, and the Hmac reusing the ts-derived IV/AAD, are not spelled out by UIDAI and are
recorded in docs/AADHAAR.md as the two things a pre-production smoke test settles.
"""

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
