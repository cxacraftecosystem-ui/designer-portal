"""UIDAI's published certificates, pinned in the repository, plus the operator's rotation list.

``app/data/uidai/manifest.json`` lists every certificate shipped with the code: its file, what it is
for, the URL on uidai.gov.in it was downloaded from, and the SHA-256 fingerprint of its DER bytes.
A file whose fingerprint does not match its manifest entry is refused at load — a certificate
swapped on disk is a trust decision nobody made.

ROTATION. Signature checks accept ANY certificate with the right purpose, so when UIDAI publishes a
new signing certificate an operator lists its file in ``AADHAAR_EXTRA_TRUSTED_CERTS`` (no deploy),
and the next release adds it to the manifest. Old certificates stay: a Secure QR printed on a card
in 2019 is still signed with the key UIDAI used in 2019.
"""

from __future__ import annotations

import hashlib
import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives import serialization

from app.core.config import get_settings

DATA_DIR = Path(__file__).resolve().parents[2] / "data" / "uidai"
MANIFEST = DATA_DIR / "manifest.json"

#: Verifies the Aadhaar Secure QR and the Paperless Offline e-KYC XML.
PURPOSE_OFFLINE_SIGNATURE = "offline-signature"
#: Encrypts the session key of an online authentication request (one per environment).
PURPOSE_AUTH_ENCRYPTION_PREPROD = "auth-encryption-preprod"
PURPOSE_AUTH_ENCRYPTION_PROD = "auth-encryption-prod"
#: Verifies UIDAI's signature on an online response (e-KYC), one per environment.
PURPOSE_AUTH_SIGNATURE_PREPROD = "auth-signature-preprod"
PURPOSE_AUTH_SIGNATURE_PROD = "auth-signature-prod"


class CertificateError(Exception):
    pass


def load_certificate(data: bytes) -> x509.Certificate:
    """PEM or DER, whichever the file is."""
    stripped = data.strip()
    if stripped.startswith(b"-----BEGIN"):
        return x509.load_pem_x509_certificate(stripped)
    return x509.load_der_x509_certificate(data)


def fingerprint(certificate: x509.Certificate) -> str:
    """SHA-256 over the DER encoding, lower-case hex with no separators."""
    return hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).hexdigest()


def manifest_entries() -> list[dict[str, Any]]:
    if not MANIFEST.exists():
        return []
    return list(json.loads(MANIFEST.read_text(encoding="utf-8")).get("certificates", []))


@lru_cache(maxsize=1)
def _pinned() -> tuple[tuple[str, x509.Certificate], ...]:
    loaded: list[tuple[str, x509.Certificate]] = []
    for entry in manifest_entries():
        path = DATA_DIR / entry["file"]
        certificate = load_certificate(path.read_bytes())
        actual = fingerprint(certificate)
        if actual != entry["sha256"].lower().replace(":", ""):
            raise CertificateError(
                f"{entry['file']}: fingerprint {actual} does not match the manifest — refusing to "
                "trust a certificate nobody pinned"
            )
        loaded.append((entry["purpose"], certificate))
    return tuple(loaded)


def _extra() -> list[x509.Certificate]:
    raw = get_settings().aadhaar_extra_trusted_certs or ""
    found: list[x509.Certificate] = []
    for chunk in raw.replace("\n", ",").split(","):
        name = chunk.strip()
        if name:
            found.append(load_certificate(Path(name).read_bytes()))
    return found


def trusted_certificates(purpose: str) -> list[x509.Certificate]:
    """Every certificate trusted for ``purpose``: the pinned ones, then the operator's extras.

    The extras are trusted for the OFFLINE signature purpose only. An encryption certificate is
    chosen, not tried, so it comes from the manifest or AADHAAR_UIDAI_ENCRYPTION_CERT_PATH.
    """
    pinned = [certificate for pinned_purpose, certificate in _pinned() if pinned_purpose == purpose]
    if purpose == PURPOSE_OFFLINE_SIGNATURE:
        pinned.extend(_extra())
    return pinned


def clear_cache() -> None:
    _pinned.cache_clear()
