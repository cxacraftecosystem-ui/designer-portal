import logging
from datetime import UTC, datetime, timedelta
from typing import Any

import bcrypt
import jwt

from app.core.config import get_settings

logger = logging.getLogger(__name__)

BCRYPT_ROUNDS = 12

BCRYPT_MAX_SECRET_BYTES = 72

BCRYPT_HASH_LENGTH = 60

MAX_CHECKED_PASSWORD_BYTES = 4096

MAX_PASSWORD_LENGTH = 200

MIN_JWT_SIGNING_KEY_LENGTH = 32

_PLACEHOLDER_JWT_SECRETS = frozenset(
    {
        "change-this-to-a-long-random-secret",
        "change-me",
        "changeme",
        "secret",
        "supersecret",
        "your-secret-key",
        "test",
    }
)


def _bcrypt_secret(password: str) -> bytes:
    return password.encode("utf-8")[:BCRYPT_MAX_SECRET_BYTES]


def _uncheckable(password: str) -> str | None:
    if "\x00" in password:
        return "a password may not contain a NUL character"
    if (
        len(password) > MAX_CHECKED_PASSWORD_BYTES
        or len(password.encode("utf-8")) > MAX_CHECKED_PASSWORD_BYTES
    ):
        return f"a password may not be longer than {MAX_CHECKED_PASSWORD_BYTES} bytes of UTF-8"
    return None


def hash_password(password: str) -> str:
    reason = _uncheckable(password)
    if reason:
        raise ValueError(reason)
    salt = bcrypt.gensalt(rounds=BCRYPT_ROUNDS, prefix=b"2b")
    return bcrypt.hashpw(_bcrypt_secret(password), salt).decode("ascii")


def verify_password(password: str, password_hash: str | None) -> bool:
    if not password_hash:
        return False
    if _uncheckable(password):
        return False
    if len(password_hash) != BCRYPT_HASH_LENGTH:
        raise ValueError(f"the stored password hash is malformed: not {BCRYPT_HASH_LENGTH} characters")
    return bcrypt.checkpw(_bcrypt_secret(password), password_hash.encode("ascii"))


_TIMING_DECOY_HASH = "$2b$12$KDeRXn/0PBzAVdesZIsHqeGE0RqAMGDRZzZMn8dRtW6b2lZ6KKkty"


def verify_password_or_decoy(password: str, password_hash: str | None) -> bool:
    if password_hash:
        return verify_password(password, password_hash)
    verify_password(password, _TIMING_DECOY_HASH)
    return False


def _jwt_signing_weakness(secret: str) -> str | None:
    candidate = secret.strip()
    lowered = candidate.lower()
    if not candidate:
        return "JWT_SECRET is empty"
    if lowered in _PLACEHOLDER_JWT_SECRETS or ("change" in lowered and "secret" in lowered):
        return "JWT_SECRET is still the example placeholder, which is public knowledge"
    if len(candidate) < MIN_JWT_SIGNING_KEY_LENGTH:
        return (
            f"JWT_SECRET is {len(candidate)} characters; at least {MIN_JWT_SIGNING_KEY_LENGTH} "
            "are required for the HMAC signing key"
        )
    return None


def verify_jwt_configuration() -> None:
    settings = get_settings()
    weakness = _jwt_signing_weakness(settings.jwt_secret)
    if not weakness:
        return
    message = (
        f"Insecure JWT signing configuration: {weakness}. Anyone who guesses it can mint a token "
        "for any account, including the master admin. Generate one with "
        '`python -c "import secrets; print(secrets.token_urlsafe(48))"` and set JWT_SECRET.'
    )
    if settings.allow_weak_jwt_secret:
        logger.critical("%s (ALLOW_WEAK_JWT_SECRET is set — continuing anyway)", message)
        return
    raise RuntimeError(f"{message} (set ALLOW_WEAK_JWT_SECRET=true to override in development)")


CREDENTIAL_CLAIM = "cred"


def create_access_token(
    subject: str,
    extra_claims: dict[str, Any] | None = None,
    *,
    expires_minutes: int | None = None,
    credential: str | None = None,
) -> str:
    settings = get_settings()
    now = datetime.now(UTC)
    minutes = settings.jwt_expires_minutes if expires_minutes is None else expires_minutes
    payload: dict[str, Any] = {
        "sub": subject,
        "iat": int(now.timestamp()),
        "exp": now + timedelta(minutes=minutes),
    }
    if extra_claims:
        reserved = {"sub", "iat", "exp", CREDENTIAL_CLAIM} & extra_claims.keys()
        if reserved:
            raise ValueError(
                f"extra_claims may not override reserved claims: {', '.join(sorted(reserved))}"
            )
        payload.update(extra_claims)
    if credential is not None:
        payload[CREDENTIAL_CLAIM] = credential
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


_DECODE_OPTIONS: dict[str, Any] = {
    "verify_signature": True,
    "verify_exp": True,
    "verify_iat": False,
    "require": ["exp", "sub"],
}


def _require_integral_iat(claims: dict[str, Any]) -> None:
    if "iat" not in claims:
        return
    try:
        int(claims["iat"])
    except (ValueError, TypeError, OverflowError) as exc:
        raise ValueError("Invalid or expired token") from exc


def decode_access_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    try:
        claims = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options=_DECODE_OPTIONS,
        )
    except jwt.PyJWTError as exc:
        raise ValueError("Invalid or expired token") from exc
    _require_integral_iat(claims)
    return claims
