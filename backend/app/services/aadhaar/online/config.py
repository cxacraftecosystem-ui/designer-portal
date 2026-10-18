"""Everything online authentication needs, loaded and checked ONCE, at boot.

``Settings._require_complete_online_aadhaar_config`` has already refused a missing value by the time
this runs; this adds what a string check cannot see — that the PFX opens with its password and holds
an RSA key with a certificate, that the UIDAI encryption certificate for AADHAAR_ENV loads and has
not expired, and that at least one UIDAI response-signing certificate is available. ``app/main.py``
calls :func:`load_online_config` in its lifespan while AADHAAR_ONLINE_AUTH_ENABLED is on, so a
deployment that cannot authenticate does not start.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path
from typing import Any

from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12

from app.core.config import get_settings
from app.services.aadhaar import certs


class OnlineConfigError(RuntimeError):
    pass


@dataclass(frozen=True)
class OnlineConfig:
    env: str
    aua_code: str
    sub_aua_code: str
    aua_license_key: str
    asa_license_key: str
    kua_license_key: str | None
    otp_url: str
    auth_url: str
    kyc_url: str | None
    #: An ``rsa.RSAPrivateKey`` from the PFX, or a ``KmsRsaSigner`` whose key lives in AWS KMS.
    signing_key: Any
    signing_certificate: x509.Certificate
    encryption_certificate: x509.Certificate
    response_certificates: tuple[x509.Certificate, ...]
    timeout_seconds: float
    ekyc: bool
    name_check: bool
    otp_channel: str
    throttle_salt: bytes
    limit_per_user: int
    limit_per_uid: int
    window_minutes: int


def _read(path: str, what: str) -> bytes:
    try:
        return Path(path).read_bytes()
    except OSError as exc:
        raise OnlineConfigError(f"{what} could not be read from {path}: {exc.strerror}") from exc


def _encryption_certificate(env: str, override: str | None) -> x509.Certificate:
    if override:
        return certs.load_certificate(_read(override, "AADHAAR_UIDAI_ENCRYPTION_CERT_PATH"))
    purpose = (
        certs.PURPOSE_AUTH_ENCRYPTION_PROD if env == "prod" else certs.PURPOSE_AUTH_ENCRYPTION_PREPROD
    )
    found = certs.trusted_certificates(purpose)
    if not found:
        raise OnlineConfigError(f"no pinned UIDAI encryption certificate for AADHAAR_ENV={env}")
    # The latest-expiring one: UIDAI publishes a successor before the current one lapses.
    return max(found, key=lambda certificate: certificate.not_valid_after_utc)


def _response_certificates(env: str, override: str | None) -> tuple[x509.Certificate, ...]:
    purpose = certs.PURPOSE_AUTH_SIGNATURE_PROD if env == "prod" else certs.PURPOSE_AUTH_SIGNATURE_PREPROD
    found = list(certs.trusted_certificates(purpose))
    if override:
        found.insert(0, certs.load_certificate(_read(override, "AADHAAR_UIDAI_SIGNATURE_CERT_PATH")))
    if not found:
        raise OnlineConfigError(f"no UIDAI response-signing certificate for AADHAAR_ENV={env}")
    return tuple(found)


def _signing_key(settings: Any, kms: Any = None) -> tuple[Any, x509.Certificate]:
    """The AUA signing key and its certificate: from AWS KMS when AADHAAR_SIGNING_KMS_KEY_ID is set
    (the HSM option, Blocker B4), otherwise from the password-protected PFX."""
    key_id = (getattr(settings, "aadhaar_signing_kms_key_id", None) or "").strip()
    if key_id:
        from app.services.aadhaar.online import kms_signer

        certificate = certs.load_certificate(
            _read(settings.aadhaar_aua_signing_cert_path or "", "AADHAAR_AUA_SIGNING_CERT_PATH")
        )
        signer = kms_signer.KmsRsaSigner(key_id, kms or kms_signer.kms_client(settings))
        try:
            kms_signer.check(signer, certificate)
        except kms_signer.KmsSigningError as exc:
            raise OnlineConfigError(str(exc)) from exc
        except Exception as exc:  # KMS unreachable, access denied, unknown key
            raise OnlineConfigError(
                f"AADHAAR_SIGNING_KMS_KEY_ID could not be used: {type(exc).__name__}"
            ) from exc
        return signer, certificate
    pfx = _read(settings.aadhaar_aua_signing_pfx_path or "", "AADHAAR_AUA_SIGNING_PFX_PATH")
    try:
        key, certificate, _chain = pkcs12.load_key_and_certificates(
            pfx, (settings.aadhaar_aua_signing_pfx_password or "").encode("utf-8")
        )
    except ValueError as exc:
        raise OnlineConfigError(
            "AADHAAR_AUA_SIGNING_PFX_PATH does not open with AADHAAR_AUA_SIGNING_PFX_PASSWORD"
        ) from exc
    if not isinstance(key, rsa.RSAPrivateKey) or certificate is None:
        raise OnlineConfigError("the AUA signing PFX must hold an RSA private key and its certificate")
    return key, certificate


def build_online_config() -> OnlineConfig:
    settings = get_settings()
    if not settings.aadhaar_online_auth_enabled:
        raise OnlineConfigError("AADHAAR_ONLINE_AUTH_ENABLED is off")
    env = settings.aadhaar_env
    key, certificate = _signing_key(settings)
    encryption = _encryption_certificate(env, settings.aadhaar_uidai_encryption_cert_path)
    if encryption.not_valid_after_utc < datetime.now(UTC):
        raise OnlineConfigError(
            "the UIDAI encryption certificate for AADHAAR_ENV has expired; download its successor "
            "and set AADHAAR_UIDAI_ENCRYPTION_CERT_PATH"
        )
    channel = (settings.aadhaar_otp_channel or "00").strip()
    if channel not in ("00", "01", "02"):
        raise OnlineConfigError("AADHAAR_OTP_CHANNEL must be 00, 01 or 02")
    return OnlineConfig(
        env=env,
        aua_code=str(settings.aadhaar_aua_code),
        sub_aua_code=str(settings.aadhaar_sub_aua_code),
        aua_license_key=str(settings.aadhaar_aua_license_key),
        asa_license_key=str(settings.aadhaar_asa_license_key),
        kua_license_key=settings.aadhaar_kua_license_key,
        otp_url=str(settings.aadhaar_asa_otp_url).rstrip("/"),
        auth_url=str(settings.aadhaar_asa_auth_url).rstrip("/"),
        kyc_url=(settings.aadhaar_asa_kyc_url or "").rstrip("/") or None,
        signing_key=key,
        signing_certificate=certificate,
        encryption_certificate=encryption,
        response_certificates=_response_certificates(env, settings.aadhaar_uidai_signature_cert_path),
        timeout_seconds=float(settings.aadhaar_asa_timeout_seconds),
        ekyc=bool(settings.aadhaar_ekyc_enabled),
        name_check=bool(settings.aadhaar_online_name_check),
        otp_channel=channel,
        throttle_salt=str(settings.aadhaar_throttle_salt).encode("utf-8"),
        limit_per_user=int(settings.aadhaar_otp_limit_per_user),
        limit_per_uid=int(settings.aadhaar_otp_limit_per_uid),
        window_minutes=int(settings.aadhaar_otp_window_minutes),
    )


@lru_cache(maxsize=1)
def load_online_config() -> OnlineConfig:
    return build_online_config()


def clear_cache() -> None:
    load_online_config.cache_clear()
