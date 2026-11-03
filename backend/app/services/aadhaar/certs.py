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

PURPOSE_OFFLINE_SIGNATURE = "offline-signature"
PURPOSE_AUTH_ENCRYPTION_PREPROD = "auth-encryption-preprod"
PURPOSE_AUTH_ENCRYPTION_PROD = "auth-encryption-prod"
PURPOSE_AUTH_SIGNATURE_PREPROD = "auth-signature-preprod"
PURPOSE_AUTH_SIGNATURE_PROD = "auth-signature-prod"


class CertificateError(Exception):
    pass


def load_certificate(data: bytes) -> x509.Certificate:
    stripped = data.strip()
    if stripped.startswith(b"-----BEGIN"):
        return x509.load_pem_x509_certificate(stripped)
    return x509.load_der_x509_certificate(data)


def fingerprint(certificate: x509.Certificate) -> str:
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
    pinned = [certificate for pinned_purpose, certificate in _pinned() if pinned_purpose == purpose]
    if purpose == PURPOSE_OFFLINE_SIGNATURE:
        pinned.extend(_extra())
    return pinned


def clear_cache() -> None:
    _pinned.cache_clear()
