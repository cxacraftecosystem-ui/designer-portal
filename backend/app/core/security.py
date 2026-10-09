"""Authentication primitives: password hashing and JWT issue/verify.

Hardening properties this module is responsible for (documented in docs/SECURITY.md):

* **The signing algorithm is pinned on decode.** ``jose`` is given exactly one algorithm — the
  configured HMAC one — so a token whose header claims ``alg: none`` (unsigned) or ``alg: RS256``
  (signature verified against our shared secret used as a "public key") is rejected outright
  instead of being trusted. Leaving ``algorithms`` unset, or passing the token's own header value,
  is the classic algorithm-confusion hole. ``Settings._normalise_jwt_algorithm`` guarantees the
  configured value is one of HS256/384/512, so the environment cannot widen this either.
* **Expiry is mandatory, not optional.** ``require_exp`` makes a token without an ``exp`` claim
  invalid rather than eternal, and ``verify_exp`` enforces it. Same for ``sub``, which every caller
  (``deps.get_current_user``) relies on to identify the account.
* **The secret is validated at startup, not at first login.** ``verify_jwt_configuration`` refuses
  to boot on the ``.env.example`` placeholder or a secret too short for the algorithm, because a
  guessable HMAC secret lets anyone forge a master-admin token, and a hole like that must fail
  visibly on deploy rather than silently in production.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from jose import JWTError, jwt
from passlib.context import CryptContext
from passlib.exc import PasswordValueError

from app.core.config import get_settings

logger = logging.getLogger(__name__)

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

#: The longest password this product will STORE, on every field that sets one: ``UserCreate``,
#: ``UserUpdate``, ``ChangePasswordRequest`` and ``SetPasswordRequest``. ONE NUMBER, because they
#: used to disagree (256 on the two admin forms, 200 on the two self-service ones), and an admin could
#: then hand somebody a 201-character temporary password that signed in and could never be typed
#: into ``currentPassword`` — the forced change could only be finished through a link.
#:
#: THE EFFECTIVE LENGTH IS SHORTER AND THAT IS bcrypt's, NOT OURS: it uses only the first 72 BYTES of
#: a password (passlib truncates silently at this pin), so two passwords that agree on their first 72
#: bytes are one password. 200 characters is a typing ceiling, not a strength claim.
#:
#: The sign-in body (``LoginRequest``) deliberately carries no such ceiling — a person types what
#: they type — and :func:`verify_password` answers False rather than raising for anything too long
#: to check.
MAX_PASSWORD_LENGTH = 200

# HS256 signs with a 256-bit key; a secret shorter than 32 characters has less entropy than the
# algorithm assumes and is brute-forceable offline from a single captured token.
MIN_JWT_SECRET_LENGTH = 32

# Values that ship in .env.example / tutorials and therefore are public knowledge. Compared
# case-insensitively; any secret merely *containing* "change" and "secret" is caught by the
# substring rule in _jwt_secret_weakness.
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


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(password: str, password_hash: str | None) -> bool:
    if not password_hash:
        return False
    try:
        return pwd_context.verify(password, password_hash)
    except PasswordValueError:
        # passlib refuses to hash a secret over its own size cap (4096 characters) with
        # ``PasswordSizeError`` rather than answering False, and the sign-in and dataset-token
        # bodies are unbounded on purpose — so without this a pasted megabyte was a 500 at the front
        # door. Something that long is not this account's password, which is all a sign-in asks. A
        # MALFORMED STORED HASH is a different ValueError and still raises: that is a broken row an
        # operator must hear about, not a wrong password.
        return False


def _jwt_secret_weakness(secret: str) -> str | None:
    """Describe why ``secret`` is unsafe for signing tokens, or None when it is acceptable."""
    candidate = secret.strip()
    lowered = candidate.lower()
    if not candidate:
        return "JWT_SECRET is empty"
    if lowered in _PLACEHOLDER_JWT_SECRETS or ("change" in lowered and "secret" in lowered):
        return "JWT_SECRET is still the example placeholder, which is public knowledge"
    if len(candidate) < MIN_JWT_SECRET_LENGTH:
        return (
            f"JWT_SECRET is {len(candidate)} characters; at least {MIN_JWT_SECRET_LENGTH} are "
            "required for the HMAC signing key"
        )
    return None


def verify_jwt_configuration() -> None:
    """Fail fast (and loudly) when the token-signing secret is guessable.

    Called from ``create_app`` so ``uvicorn app.main:app`` refuses to start rather than serving an
    API whose tokens anyone can forge. Set ``ALLOW_WEAK_JWT_SECRET=true`` to downgrade the refusal
    to a CRITICAL log line — intended for local development only; see docs/SECURITY.md.
    """
    settings = get_settings()
    weakness = _jwt_secret_weakness(settings.jwt_secret)
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


#: The claim that binds a bearer token to the password its account held when it was minted: the
#: ``credential_links.credential_fingerprint`` of that ``passwordHash`` (a fixed sentinel's digest for
#: an account with none). ``deps._user_from_bearer`` refuses a token whose claim no longer matches
#: the row, so ANY password change retires every session opened before it. See the block there.
CREDENTIAL_CLAIM = "cred"


def create_access_token(
    subject: str,
    extra_claims: dict[str, Any] | None = None,
    *,
    expires_minutes: int | None = None,
    credential: str | None = None,
) -> str:
    """Mint a signed bearer token for *subject*.

    ``expires_minutes`` overrides the configured session lifetime, and is keyword-only so a caller
    cannot lengthen a token's life by accident when it meant to pass claims. The one caller that
    uses it is ``POST /api/datasets/token``, whose credential is deliberately longer-lived than a
    browser session because it is deliberately narrower (see ``deps.DATASET_READ_SCOPE``).

    ``credential`` is the account's password fingerprint, written as :data:`CREDENTIAL_CLAIM`. EVERY
    token the application hands out carries one (2026-10-09) — the sign-in, the change-password
    answer and the dataset mint — and ``tests/test_password_change_enforcement.py`` holds every call
    site in ``app/`` to passing it. It is optional here only because a token WITHOUT the claim is
    what every build before that date minted, and those stay valid exactly as they were until they
    expire: a test minting one is minting a pre-release session on purpose.
    """
    settings = get_settings()
    now = datetime.now(UTC)
    minutes = settings.jwt_expires_minutes if expires_minutes is None else expires_minutes
    payload: dict[str, Any] = {
        "sub": subject,
        "iat": int(now.timestamp()),
        "exp": now + timedelta(minutes=minutes),
    }
    if extra_claims:
        # ``extra_claims`` may not overwrite the claims that decide WHO the token is for, HOW LONG it
        # lasts, and WHICH PASSWORD it was opened with. Both callers today pass a dict this module
        # built, so nothing is exploitable — but a blind ``update`` means the day someone forwards a
        # claim influenced by request data, that caller can mint a token for another subject or one
        # that never expires, and ``decode_access_token``'s mandatory-``sub``/``exp`` hardening would
        # wave it through because both claims are present. Refused loudly rather than silently
        # dropped: a caller passing ``sub`` has misunderstood the signature, and quietly ignoring it
        # would leave them believing they had changed the subject. ``cred`` has its own keyword so
        # that the one way to bind a token is the one that is checked for.
        #
        # ``scope`` is deliberately NOT reserved — POST /api/datasets/token sets it, and it can only
        # ever NARROW what the token may reach (see deps._user_from_bearer).
        reserved = {"sub", "iat", "exp", CREDENTIAL_CLAIM} & extra_claims.keys()
        if reserved:
            raise ValueError(
                f"extra_claims may not override reserved claims: {', '.join(sorted(reserved))}"
            )
        payload.update(extra_claims)
    if credential is not None:
        payload[CREDENTIAL_CLAIM] = credential
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_access_token(token: str) -> dict[str, Any]:
    """Verify a bearer token and return its claims, raising ValueError on anything suspect.

    ``algorithms`` is the single configured HMAC algorithm (never the token's own ``alg`` header),
    and the options below make ``exp``/``sub`` mandatory rather than optional — a token missing
    either is rejected instead of being treated as a non-expiring or subject-less credential.
    """
    settings = get_settings()
    try:
        return jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            options={
                "verify_signature": True,
                "verify_exp": True,
                "require_exp": True,
                "require_sub": True,
            },
        )
    except JWTError as exc:
        raise ValueError("Invalid or expired token") from exc
