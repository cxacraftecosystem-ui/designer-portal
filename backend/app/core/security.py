"""Authentication primitives: password hashing and JWT issue/verify.

Hardening properties this module is responsible for (documented in docs/SECURITY.md):

* **The signing algorithm is pinned on decode.** PyJWT is given exactly one algorithm — the
  configured HMAC one — so a token whose header claims ``alg: none`` (unsigned) or ``alg: RS256``
  (signature verified against our shared secret used as a "public key") is rejected outright
  instead of being trusted. Leaving ``algorithms`` unset, or passing the token's own header value,
  is the classic algorithm-confusion hole. ``Settings._normalise_jwt_algorithm`` guarantees the
  configured value is one of HS256/384/512, so the environment cannot widen this either.
* **Expiry is mandatory, not optional.** ``require: ["exp", "sub"]`` makes a token without an
  ``exp`` claim invalid rather than eternal, and ``verify_exp`` enforces it. Same for ``sub``, which
  every caller (``deps.get_current_user``) relies on to identify the account.
* **The secret is validated at startup, not at first login.** ``verify_jwt_configuration`` refuses
  to boot on the ``.env.example`` placeholder or a secret too short for the algorithm, because a
  guessable HMAC secret lets anyone forge a master-admin token, and a hole like that must fail
  visibly on deploy rather than silently in production.

── TWO LIBRARIES WERE REPLACED HERE ON 2026-10-09, AND NEITHER CHANGED A STORED BYTE ──────────────

``passlib`` (unmaintained since 2020) gave way to ``bcrypt`` itself. passlib could not run on bcrypt 5
at all — its backend self-test hashes a password longer than 72 bytes, which bcrypt 4 truncated
silently and bcrypt 5 refuses — so it was holding bcrypt at 4.0.1. The functions below reproduce what
passlib did with that backend, rule for rule: the same ``$2b$`` hash at cost 12, the same 72-byte
truncation (now written out instead of left to the library), the same refusal of a NUL character and
of anything over passlib's 4096-byte cap (UTF-8 bytes, not characters: see
:data:`MAX_CHECKED_PASSWORD_BYTES`). Every hash already in the database verifies exactly as it did;
``tests/test_password_hash_compat.py`` holds hashes passlib wrote and checks them.

``python-jose`` gave way to PyJWT. jose's last release depends on ``ecdsa``, which carries an unfixed
timing weakness (CVE-2024-23342); this module only ever used HMAC, which PyJWT does with the standard
library. The tokens are byte-for-byte the ones jose produced — same header, same claim order, same
HMAC — and the decode checks the same claims the same way (see :func:`decode_access_token` for the
one sub-second difference at the expiry boundary). ``tests/test_jwt_compat.py`` holds a token jose
minted and checks both directions.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

import bcrypt
import jwt

from app.core.config import get_settings

logger = logging.getLogger(__name__)

#: The bcrypt cost factor: passlib's default for its ``bcrypt`` scheme, so a hash written today costs
#: exactly what one written before 2026-10-09 did. ``scripts/`` and the load-test seeder hash through
#: :func:`hash_password` too, so this is the only place the number lives.
BCRYPT_ROUNDS = 12

#: bcrypt keys on the first 72 BYTES of a secret and ignores the rest. Version 4 truncated silently;
#: version 5 raises on anything longer. Truncating here, explicitly, is what passlib's bcrypt backend
#: did on our behalf — so every hash it wrote verifies against exactly the bytes it was made from.
BCRYPT_MAX_SECRET_BYTES = 72

#: The length of every bcrypt hash this application, and passlib before it, ever stored: ``$2b$``, two
#: cost digits, ``$``, 22 characters of salt and 31 of checksum. See :func:`verify_password` for why it
#: is checked rather than left to bcrypt.
BCRYPT_HASH_LENGTH = 60

#: The longest password :func:`verify_password` will check at all, in UTF-8 BYTES. It is passlib's
#: ``MAX_PASSWORD_SIZE``: passlib refused anything longer, and the sign-in answered "wrong password".
#: Kept, rather than dropped with passlib, because without it a 5,000-byte paste whose first 72 bytes
#: happened to be the password would now sign in where it used to be refused — a change to what a
#: credential check answers, which is not a library upgrade's to make.
#:
#: BYTES AND NOT CHARACTERS, BECAUSE THAT IS WHERE passlib MEASURED IT. Its bcrypt handler encodes the
#: secret to UTF-8 first and then applies the cap (``_norm_digest_args`` in passlib/handlers/bcrypt.py,
#: 1.7.4), so 2,085 characters of which 2,012 are ``é`` — 4,097 bytes — were refused. Measured against
#: passlib 1.7.4 with bcrypt 4.0.1 on 2026-10-09: a character count here accepted that paste, and any
#: other multi-byte paste of 4,097 to 16,384 bytes, whenever its first 72 bytes were the password.
MAX_CHECKED_PASSWORD_BYTES = 4096

#: The longest password this product will STORE, on every field that sets one: ``UserCreate``,
#: ``UserUpdate``, ``ChangePasswordRequest`` and ``SetPasswordRequest``. ONE NUMBER, because they
#: used to disagree (256 on the two admin forms, 200 on the two self-service ones), and an admin could
#: then hand somebody a 201-character temporary password that signed in and could never be typed
#: into ``currentPassword`` — the forced change could only be finished through a link.
#:
#: THE EFFECTIVE LENGTH IS SHORTER AND THAT IS bcrypt's, NOT OURS: it uses only the first 72 BYTES of
#: a password (:data:`BCRYPT_MAX_SECRET_BYTES`, truncated explicitly below), so two passwords that
#: agree on their first 72 bytes are one password. 200 characters is a typing ceiling, not a strength
#: claim.
#:
#: The sign-in body (``LoginRequest``) deliberately carries no such ceiling — a person types what
#: they type — and :func:`verify_password` answers False rather than raising for anything too long
#: to check.
MAX_PASSWORD_LENGTH = 200

# HS256 signs with a 256-bit key; a secret shorter than 32 characters has less entropy than the
# algorithm assumes and is brute-forceable offline from a single captured token.
MIN_JWT_SIGNING_KEY_LENGTH = 32

# Values that ship in .env.example / tutorials and therefore are public knowledge. Compared
# case-insensitively; any secret merely *containing* "change" and "secret" is caught by the
# substring rule in _jwt_signing_weakness.
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
    """The bytes bcrypt is keyed with: UTF-8, cut at :data:`BCRYPT_MAX_SECRET_BYTES`.

    THE CUT IS ON BYTES, NOT CHARACTERS, exactly where bcrypt 4 cut it — so a password whose 72nd byte
    falls inside a multi-byte character is cut through that character, as it always was, and the
    hash passlib wrote for it still matches.
    """
    return password.encode("utf-8")[:BCRYPT_MAX_SECRET_BYTES]


def _uncheckable(password: str) -> str | None:
    """Why ``password`` cannot be hashed or checked, or None when it can — passlib's two refusals.

    A NUL character (``NullPasswordError``): bcrypt implementations disagree about the bytes after
    one — the OpenBSD C original keys on a C string and stops there, while pyca/bcrypt (4 and 5,
    measured) keys on all of them — so passlib refused it rather than write a hash whose meaning
    depends on the library. Refusing it still is what keeps every verdict what it was. And more than
    :data:`MAX_CHECKED_PASSWORD_BYTES` bytes of UTF-8 (``PasswordSizeError``). The character count
    is tested first only because it is free and implies the byte count (a character is at least one
    byte), so a pasted megabyte is refused without being encoded. The answer never quotes the
    password.
    """
    if "\x00" in password:
        return "a password may not contain a NUL character"
    if (
        len(password) > MAX_CHECKED_PASSWORD_BYTES
        or len(password.encode("utf-8")) > MAX_CHECKED_PASSWORD_BYTES
    ):
        return f"a password may not be longer than {MAX_CHECKED_PASSWORD_BYTES} bytes of UTF-8"
    return None


def hash_password(password: str) -> str:
    """A ``$2b$`` bcrypt hash at cost :data:`BCRYPT_ROUNDS` — the format and cost passlib wrote.

    Raises ValueError for a password :func:`_uncheckable` refuses, as passlib did (its refusals
    were ValueErrors too). Every field that sets a password is bounded at :data:`MAX_PASSWORD_LENGTH`
    first, so that is reachable only through a NUL character.
    """
    reason = _uncheckable(password)
    if reason:
        raise ValueError(reason)
    salt = bcrypt.gensalt(rounds=BCRYPT_ROUNDS, prefix=b"2b")
    return bcrypt.hashpw(_bcrypt_secret(password), salt).decode("ascii")


def verify_password(password: str, password_hash: str | None) -> bool:
    if not password_hash:
        return False
    if _uncheckable(password):
        # The sign-in and dataset-token bodies are unbounded on purpose, so a pasted megabyte — or a
        # NUL — arrives here. Something that long, or that cannot be a bcrypt key, is not this
        # account's password, which is all a sign-in asks: False, never a 500, and never a check of
        # its first 72 bytes (see MAX_CHECKED_PASSWORD_BYTES).
        return False
    # A MALFORMED STORED HASH still raises, because that is a broken row an operator must hear about,
    # not a wrong password; passlib raised a ValueError for it too. bcrypt 5 raises on its own only
    # for a bad prefix or salt (ValueError "Invalid salt"). A hash of the wrong LENGTH — one character
    # short, or a trailing space or newline from a hand-written UPDATE — it simply fails to match, so
    # that row would have read as "wrong password" for ever. passlib refused every such shape
    # ("malformed bcrypt hash", measured against passlib 1.7.4 on 2026-10-09), hence the check here.
    if len(password_hash) != BCRYPT_HASH_LENGTH:
        raise ValueError(f"the stored password hash is malformed: not {BCRYPT_HASH_LENGTH} characters")
    return bcrypt.checkpw(_bcrypt_secret(password), password_hash.encode("ascii"))


def _jwt_signing_weakness(secret: str) -> str | None:
    """Describe why ``secret`` is unsafe for signing tokens, or None when it is acceptable.

    The answer never quotes the value — its length at most — because
    :func:`verify_jwt_configuration` logs it. NAMED FOR WHAT IT JUDGES, NOT FOR WHAT IT READS
    (2026-10-09): as ``_jwt_secret_weakness``, with ``MIN_JWT_SECRET_LENGTH`` in its sentence, every
    answer was the secret itself to a scanner that judges a value by its identifier (CodeQL's
    py/clear-text-logging-sensitive-data), and the CRITICAL line reporting one was a leak to it.
    """
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
    """Fail fast (and loudly) when the token-signing secret is guessable.

    Called from ``create_app`` so ``uvicorn app.main:app`` refuses to start rather than serving an
    API whose tokens anyone can forge. Set ``ALLOW_WEAK_JWT_SECRET=true`` to downgrade the refusal
    to a CRITICAL log line — intended for local development only; see docs/SECURITY.md.
    """
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
    # PyJWT turns the `exp` datetime into whole seconds with `timegm(utctimetuple())`, exactly as
    # jose did, and serialises the header with sorted keys and the claims in insertion order, as jose
    # did: a token minted here is byte-for-byte the token jose would have minted in the same second.
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


#: What :func:`decode_access_token` asks PyJWT to enforce. `require` is the presence check jose spelt
#: `require_exp`/`require_sub` (PyJWT also counts a claim whose value is null as absent, which jose
#: then refused one step later as "not a string"). `verify_iat` is OFF ON PURPOSE and replaced by
#: :func:`_require_integral_iat`: PyJWT's own check ALSO refuses an `iat` in the future, a rule jose
#: never had, and on one box minting and checking its own tokens the only way to meet it is a clock
#: that stepped backwards after a sign-in — which should not sign that person out. `aud`, `iss`,
#: `nbf`, `jti` and the string-only `sub` are verified as jose verified them (only when present, and
#: an `aud` with no audience expected is refused), and none of them is in a token this module mints.
_DECODE_OPTIONS: dict[str, Any] = {
    "verify_signature": True,
    "verify_exp": True,
    "verify_iat": False,
    "require": ["exp", "sub"],
}


def _require_integral_iat(claims: dict[str, Any]) -> None:
    """jose's whole check on ``iat``: when present it must read as an integer. Nothing else."""
    if "iat" not in claims:
        return
    try:
        int(claims["iat"])
    except (ValueError, TypeError, OverflowError) as exc:
        raise ValueError("Invalid or expired token") from exc


def decode_access_token(token: str) -> dict[str, Any]:
    """Verify a bearer token and return its claims, raising ValueError on anything suspect.

    ``algorithms`` is the single configured HMAC algorithm (never the token's own ``alg`` header),
    and the options below make ``exp``/``sub`` mandatory rather than optional — a token missing
    either is rejected instead of being treated as a non-expiring or subject-less credential.

    THE ONE DIFFERENCE FROM jose, MEASURED RATHER THAN ASSUMED: at the expiry boundary PyJWT refuses
    once ``exp <= now`` with ``now`` in fractional seconds, where jose refused once ``exp`` fell
    below the current WHOLE second. So a token now ends up to one second sooner than it did. Nothing
    that is valid for its lifetime notices, and nothing that has expired is accepted.
    """
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
