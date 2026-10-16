"""Two-step verification: TOTP (RFC 6238), recovery codes, and the sign-in challenge.

PORTED FROM ``C:/dev/IIT/cxa-cms/lib/auth/totp.ts`` and ``lib/auth/totp-qr.ts``, with one deliberate
improvement that file's own header asks for and could not have: **a code is spent when it is
accepted.** cxa-cms verifies a code as a pure function of (secret, code, clock), so the same six
digits work for their whole ±1-step window — up to ninety seconds — and a code read over somebody's
shoulder or relayed by a phishing page can be replayed by whoever already holds the password. Here
:func:`match_totp_step` returns the STEP it matched, and :func:`consume_totp_step` records it with a
conditional update that succeeds only for a step strictly above the last one the account accepted.
Two concurrent submissions of one code cannot both win: Postgres serialises the two UPDATEs and the
second finds the column already at that step.

── WHAT IS STORED, AND WHAT NEVER LEAVES THE SERVER ─────────────────────────────────────────────────

* The shared secret is ENCRYPTED AT REST (AES-256-GCM) under ``TWO_FACTOR_ENCRYPTION_KEY`` — its own
  key, independent of ``JWT_SECRET`` since 2026-10-17, versioned by a key id in the stored value so
  it can be rotated (see "Encryption at rest" below and ``docs/SECURITY.md``). Values written before
  that, or while the key is unset, use the legacy key derived from ``JWT_SECRET`` by scrypt with a
  fixed context label — cxa-cms's derivation, label renamed. A plaintext TOTP secret in the
  database makes one database read a complete second-factor bypass, the one thing the feature is
  bought to prevent. The encrypted value is returned by no route, and neither is the pending one:
  :data:`SECRET_USER_FIELDS` is stripped by both ``serialize_user`` functions and by
  ``records._SENSITIVE_KEYS``.
* Recovery codes are stored as bcrypt hashes, at the same cost as a password
  (``security.hash_password``): a recovery code IS a password that skips the second factor, and
  storing it any cheaper would make the weakest credential on the account the one nobody thinks
  about. The plain codes exist exactly twice — in the answer to ``/enable`` and to
  ``/recovery-codes`` — and are never recoverable afterwards.
* The QR square is drawn HERE, as SVG geometry (:func:`qr_svg`), so neither client needs a QR library
  and nothing ever posts the ``otpauth://`` URI — which contains the secret — to a third-party
  image service.

── THE CHALLENGE TOKEN, AND WHY IT IS SIGNED WITH A DIFFERENT KEY ───────────────────────────────────

After the first factor succeeds on an account with this switched on, ``POST /auth/login`` mints no
session. It answers ``{"two_factor_required": true, "challenge_token": …}``: a JWT with
``purpose: "2fa"``, the account as ``sub``, a ``TwoFactorChallenge`` row id as ``jti`` and a five
minute life. It is signed with a key DERIVED from ``JWT_SECRET`` under its own label rather than with
``JWT_SECRET`` itself, so ``security.decode_access_token`` cannot verify one — a challenge token
presented as a bearer token fails its signature before ``deps`` reads a single claim. The ``purpose``
claim is checked as well, so the two keys colliding would still not turn one into the other.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import logging
import re
import secrets
import struct
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from functools import lru_cache
from typing import Any
from urllib.parse import quote, urlencode

import jwt
import segno
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import invalidate_cached_user
from app.core.security import hash_password, verify_password
from app.core.user_fields import SECRET_USER_FIELDS as _SECRET_USER_FIELDS
from app.services import email_outbox, mailer

logger = logging.getLogger(__name__)

# ── The algorithm's parameters: RFC 6238's defaults, which is what every authenticator assumes ──
DIGITS = 6
PERIOD_SECONDS = 30
#: ±1 step (±30 s). Zero tolerance refuses a correct code from a phone whose clock is two seconds out;
#: a wider window turns a thirty-second code into a several-minute one.
WINDOW_STEPS = 1
#: 160 bits — RFC 4226's recommendation, and exactly 32 base32 characters with no padding.
SECRET_BYTES = 20

#: What an authenticator app lists the account under. The label beside it is the account's email.
TOTP_ISSUER = "Design Prototype Workshop"

RECOVERY_CODE_COUNT = 10
#: Lower-case letters and digits with the look-alikes taken out (0/o, 1/l/i), because a recovery code
#: is read off paper by somebody who is already locked out. 31 symbols, 8 of them: ~39.6 bits, behind
#: a bcrypt hash, five attempts per challenge and the per-account credential budget.
RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
RECOVERY_GROUP = 4

CHALLENGE_PURPOSE = "2fa"
CHALLENGE_MINUTES = 5
#: Wrong codes one challenge survives. The sixth attempt finds the challenge dead and the person signs
#: in again, which costs the first factor — and the per-account budget — once more.
MAX_CHALLENGE_ATTEMPTS = 5

#: The ``User`` columns no response may carry. ONE tuple, in ``app/core/user_fields.py``; re-exported
#: here because this module is where they are written.
SECRET_USER_FIELDS = _SECRET_USER_FIELDS

#: THE PER-ACCOUNT FAILURE BUDGET. The five-code cap on one challenge is not a cap on the account:
#: signing in again mints a fresh challenge. So every wrong second-factor code — at sign-in, turning
#: it off, making new recovery codes, minting a dataset token — is also counted against the ACCOUNT,
#: and ten inside a rolling fifteen minutes lock the second factor: 15 minutes, doubling on each
#: lockout, capped at ONE hour — somebody who only holds the password must not be able to keep the
#: owner out for a day at a time. The lockout count decays to zero once a day has passed since the
#: last lock ended. A correct code resets the budget; an administrator's reset clears everything.
FAILURE_BUDGET = 10
#: Recovery codes have their OWN budget, smaller, and its lock is independent of the six-digit one.
RECOVERY_FAILURE_BUDGET = 5
FAILURE_WINDOW = timedelta(minutes=15)
FIRST_LOCKOUT = timedelta(minutes=15)
MAX_LOCKOUT = timedelta(hours=1)
LOCKOUT_DECAY = timedelta(hours=24)

#: The scrypt salt for the at-rest key, and the HMAC label for the challenge-signing key. Fixed
#: strings, versioned so either scheme can be rotated without guessing what wrote a value.
_ENCRYPTION_CONTEXT = b"designer-portal-totp-v1"
_CHALLENGE_CONTEXT = b"designer-portal-two-factor-challenge-v1"


class TwoFactorError(Exception):
    """A refusal a route turns into an HTTP answer. ``kind`` picks the status, ``detail`` is the
    sentence the person reads."""

    def __init__(self, kind: str, detail: str) -> None:
        super().__init__(detail)
        self.kind = kind
        self.detail = detail


# The sentences. Plain, and the same one for every wrong code whatever made it wrong — a stale
# code, a replayed one, a recovery code already spent and a typo all have the same next action.
WRONG_CODE_DETAIL = "That code didn't work. Check your authenticator app and try again."
CHALLENGE_DEAD_DETAIL = "This sign-in has timed out. Sign in again."
ALREADY_ON_DETAIL = "Two-step verification is already on for this account."
NOT_ON_DETAIL = "Two-step verification is not on for this account."
NO_SETUP_DETAIL = "Start the setup again to get a new key."
#: Every locked budget, everywhere — `/verify`, the self-service routes, the dataset token, and the
#: sign-in itself once every way in is locked. Plain: only somebody holding the password meets it.
TOO_MANY_ATTEMPTS_DETAIL = "Too many attempts. Try again later."


# ==================================================================================================
# TOTP
# ==================================================================================================


def generate_secret() -> str:
    """A fresh 160-bit secret as 32 unpadded base32 characters."""
    return base64.b32encode(secrets.token_bytes(SECRET_BYTES)).decode("ascii").rstrip("=")


def _b32decode(secret: str) -> bytes:
    cleaned = re.sub(r"\s", "", secret).upper().rstrip("=")
    return base64.b32decode(cleaned + "=" * (-len(cleaned) % 8))


def hotp(key: bytes, counter: int) -> str:
    """RFC 4226 HOTP: HMAC-SHA-1 over the 64-bit big-endian counter, dynamic truncation, 6 digits."""
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(binary % 10**DIGITS).zfill(DIGITS)


def time_step(at: float | None = None) -> int:
    return int((time.time() if at is None else at) // PERIOD_SECONDS)


def current_totp(secret: str, at: float | None = None) -> str:
    """The code for ``at`` (now by default). For tests and nothing else."""
    return hotp(_b32decode(secret), time_step(at))


def match_totp_step(secret: str, code: str, at: float | None = None) -> int | None:
    """The time step ``code`` is valid for within ±:data:`WINDOW_STEPS`, or None.

    THE STEP, NOT A BOOLEAN, because the step is the only thing that can make a code single-use
    (:func:`consume_totp_step`). Every candidate in the window is computed and compared in constant
    time even after a match, so the work done does not say which way the phone's clock is skewed;
    the LAST match wins, which records the higher step and so refuses more later, never less.
    """
    normalised = re.sub(r"\s", "", code or "")
    # ASCII digits only: `str.isdigit` says yes to "１２３４５６", which no authenticator shows.
    if not re.fullmatch(r"[0-9]{6}", normalised):
        return None
    try:
        key = _b32decode(secret)
    except (ValueError, TypeError):
        return None
    counter = time_step(at)
    matched: int | None = None
    for offset in range(-WINDOW_STEPS, WINDOW_STEPS + 1):
        if hmac.compare_digest(hotp(key, counter + offset), normalised):
            matched = counter + offset
    return matched


def totp_uri(secret: str, account_name: str, issuer: str = TOTP_ISSUER) -> str:
    """The ``otpauth://`` URI an authenticator scans or opens."""
    label = quote(f"{issuer}:{account_name}", safe="")
    params = urlencode(
        {
            "secret": secret,
            "issuer": issuer,
            "algorithm": "SHA1",
            "digits": DIGITS,
            "period": PERIOD_SECONDS,
        },
        quote_via=quote,
    )
    return f"otpauth://totp/{label}?{params}"


# ==================================================================================================
# The QR square
# ==================================================================================================

#: ISO/IEC 18004 asks for four modules of quiet zone. The square may sit on a dark theme, where a
#: thinner one is eaten by the surround, so it carries the full four on its own white background.
QR_QUIET_ZONE_MODULES = 4


def qr_matrix(text: str) -> list[list[bool]]:
    """The module matrix INCLUDING the quiet zone; True is dark. Error correction M: a screen
    photographed at an angle with a glare stripe is the ordinary case, and H would push a
    ~150-character URI to a version too dense to focus on."""
    code = segno.make(text, error="m", micro=False, boost_error=False)
    border = QR_QUIET_ZONE_MODULES
    rows = [[bool(cell) for cell in row] for row in code.matrix]
    width = len(rows[0]) + 2 * border
    blank = [False] * width
    framed = [list(blank) for _ in range(border)]
    framed += [[False] * border + row + [False] * border for row in rows]
    framed += [list(blank) for _ in range(border)]
    return framed


def qr_path(matrix: list[list[bool]]) -> str:
    """One SVG path: each horizontal run of dark modules is one ``M x y h n v 1 h -n z`` rectangle.

    Runs are merged so the path stays a few kilobytes and leaves no anti-aliasing seam between
    neighbouring modules — a seam is a light line across a dark run, and a scanner reads it as one.
    ONLY ``M``, ``h``, ``v`` and ``z``, which is what lets the Android client draw this exact string
    with Compose's ``PathParser`` instead of shipping an SVG renderer.
    """
    commands: list[str] = []
    for y, row in enumerate(matrix):
        x = 0
        while x < len(row):
            if not row[x]:
                x += 1
                continue
            start = x
            while x < len(row) and row[x]:
                x += 1
            run = x - start
            commands.append(f"M{start} {y}h{run}v1h-{run}z")
    return "".join(commands)


def qr_svg(text: str) -> str:
    """A self-contained SVG: a white ``size``×``size`` square and one black path on it.

    The shape is fixed and both clients rely on it: one ``viewBox="0 0 N N"`` and one ``<path d=…>``.
    The web shows it as an image; Android reads the two attributes and draws the path itself.
    """
    matrix = qr_matrix(text)
    size = len(matrix)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
        f'shape-rendering="crispEdges">'
        f'<rect width="{size}" height="{size}" fill="#ffffff"/>'
        f'<path fill="#000000" d="{qr_path(matrix)}"/>'
        "</svg>"
    )


# ==================================================================================================
# Encryption at rest
# ==================================================================================================


#
# TWO FORMATS, ONE READER (2026-10-17).
#
#   v1.<iv>.<tag>.<ciphertext>          LEGACY. Key = scrypt(JWT_SECRET). Rotating JWT_SECRET used to
#                                       make every one of these unreadable — every account with two-step
#                                       on was locked out of its authenticator at once.
#   v2:<kid>.<iv>.<tag>.<ciphertext>    Key = TWO_FACTOR_ENCRYPTION_KEY (32 random bytes, base64), or
#                                       one of TWO_FACTOR_PREVIOUS_KEYS. ``kid`` names which, so a read
#                                       never has to guess. The ``v2:<kid>`` prefix is also the AES-GCM
#                                       associated data: a value cannot be relabelled to another key.
#
# A WRITE uses the current key when one is set, and the legacy key (with a WARNING at boot) when it
# is not — so deploying this code before the secret exists locks nobody out. A READ opens either
# format with whichever configured key the value names. A value under anything but the current key
# is rewritten under it the next time it is used successfully (:func:`refresh_encryption`), and
# ``scripts/reencrypt_two_factor_secrets.py`` rewrites the rest in one pass. The rotation runbook is
# ``docs/SECURITY.md`` §"Rotating the two-step encryption key".

LEGACY_PREFIX = "v1"
VERSIONED_PREFIX = "v2:"
KEY_BYTES = 32
#: HMAC label for a key's id. The id is derived FROM the key, so nothing has to be configured twice
#: and two operators cannot give one key two names; it is a keyed digest, so it says nothing about the
#: key to somebody holding only the database.
_KEY_ID_CONTEXT = b"designer-portal-totp-key-id-v2"


@lru_cache(maxsize=4)
def _legacy_key(jwt_secret: str) -> bytes:
    """scrypt (N=2^14, r=8, p=1 — Node's ``scryptSync`` defaults, as cxa-cms uses them) rather than a
    fast hash: the key derives from ``JWT_SECRET``, and a fast KDF would let somebody holding the
    database brute-force a weak secret offline at hashing speed. Cached per secret, because scrypt
    is deliberately slow and a sign-in decrypts once."""
    return hashlib.scrypt(
        jwt_secret.encode("utf-8"), salt=_ENCRYPTION_CONTEXT, n=2**14, r=8, p=1, dklen=32
    )


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class KeyConfigurationError(ValueError):
    """A configured two-step key is not 32 bytes of base64. Raised at boot, never at sign-in."""


def parse_key(text: str, *, name: str = "TWO_FACTOR_ENCRYPTION_KEY") -> bytes:
    """32 bytes from standard or URL-safe base64, padding optional. Anything else is refused LOUDLY:
    a truncated paste would otherwise become a valid, different key and every value written under it
    would be readable by nobody once the typo is fixed."""
    cleaned = re.sub(r"\s", "", text or "")
    try:
        raw = base64.b64decode(
            cleaned.replace("-", "+").replace("_", "/") + "=" * (-len(cleaned) % 4), validate=True
        )
    except ValueError as exc:
        raise KeyConfigurationError(f"{name} is not base64") from exc
    if len(raw) != KEY_BYTES:
        raise KeyConfigurationError(f"{name} must be {KEY_BYTES} random bytes, base64-encoded")
    return raw


def key_id(key: bytes) -> str:
    """Eight base64url characters naming ``key``."""
    return _b64(hmac.new(key, _KEY_ID_CONTEXT, hashlib.sha256).digest()[:6])


@dataclass(frozen=True)
class Keyring:
    #: (kid, key) for TWO_FACTOR_ENCRYPTION_KEY, or None while it is unset (the transition).
    current: tuple[str, bytes] | None
    #: kid -> key, for the current key AND every previous one.
    readable: dict[str, bytes]


@lru_cache(maxsize=8)
def _keyring(current: str | None, previous: str | None) -> Keyring:
    readable: dict[str, bytes] = {}
    head: tuple[str, bytes] | None = None
    if current and current.strip():
        key = parse_key(current)
        head = (key_id(key), key)
        readable[head[0]] = key
    for index, text in enumerate(part for part in (previous or "").split(",") if part.strip()):
        key = parse_key(text, name=f"TWO_FACTOR_PREVIOUS_KEYS[{index}]")
        readable.setdefault(key_id(key), key)
    return Keyring(current=head, readable=readable)


def keyring() -> Keyring:
    """The configured keys. Cached on the two settings' text, so a test that changes them sees it."""
    settings = get_settings()
    return _keyring(
        getattr(settings, "two_factor_encryption_key", None),
        getattr(settings, "two_factor_previous_keys", None),
    )


def _seal(key: bytes, plain: str, aad: bytes | None) -> tuple[str, str, str]:
    iv = secrets.token_bytes(12)
    sealed = AESGCM(key).encrypt(iv, plain.encode("utf-8"), aad)
    return _b64(iv), _b64(sealed[-16:]), _b64(sealed[:-16])


def encrypt_secret(plain: str) -> str:
    """``v2:<kid>.<iv>.<tag>.<ciphertext>`` under the current key; while none is configured, the
    legacy ``v1.<iv>.<tag>.<ciphertext>`` (cxa-cms's format) under the ``JWT_SECRET``-derived key."""
    ring = keyring()
    if ring.current is None:
        return ".".join((LEGACY_PREFIX, *_seal(_legacy_key(get_settings().jwt_secret), plain, None)))
    kid, key = ring.current
    label = f"{VERSIONED_PREFIX}{kid}"
    return ".".join((label, *_seal(key, plain, label.encode("ascii"))))


def _opened(stored: str | None) -> tuple[str, str | None] | None:
    """``(plain, kid)`` — kid None for a legacy value — or None when nothing configured opens it."""
    if not stored:
        return None
    parts = stored.split(".")
    if len(parts) != 4:
        return None
    label = parts[0]
    kid: str | None
    aad: bytes | None
    if label == LEGACY_PREFIX:
        kid, aad, key = None, None, _legacy_key(get_settings().jwt_secret)
    elif label.startswith(VERSIONED_PREFIX):
        kid = label[len(VERSIONED_PREFIX) :]
        found = keyring().readable.get(kid)
        if found is None:
            return None
        key, aad = found, label.encode("ascii")
    else:
        return None
    try:
        iv, tag, ciphertext = _unb64(parts[1]), _unb64(parts[2]), _unb64(parts[3])
        plain = AESGCM(key).decrypt(iv, ciphertext + tag, aad)
    except (InvalidTag, ValueError):
        return None
    return plain.decode("utf-8"), kid


def decrypt_secret(stored: str | None) -> str | None:
    """None rather than an exception: an undecryptable secret (a key dropped from
    ``TWO_FACTOR_PREVIOUS_KEYS`` too early, a hand-edited row) means the authenticator no longer works
    for that account, and the way back is a recovery code or an administrator's reset — never a 500
    on the sign-in screen."""
    opened = _opened(stored)
    return opened[0] if opened else None


def is_current(stored: str | None) -> bool:
    """Is ``stored`` already under the current key? True while no key is configured: then there is
    nothing better to move it to."""
    ring = keyring()
    if ring.current is None or not stored:
        return True
    return stored.startswith(f"{VERSIONED_PREFIX}{ring.current[0]}.")


def reencrypted(stored: str | None) -> str | None:
    """``stored`` sealed again under the current key, or None when it already is or cannot be read."""
    if is_current(stored):
        return None
    opened = _opened(stored)
    return encrypt_secret(opened[0]) if opened else None


async def refresh_encryption(user_id: str, column: str, stored: str | None) -> bool:
    """Rewrite one column under the current key after a successful use. Conditional on the column
    still holding what was read, so it can never overwrite a newer setup or a switch-off. Never
    raises: the code was right and the person is signed in whatever this write does."""
    fresh = reencrypted(stored)
    if fresh is None:
        return False
    try:
        written = await db.user.update_many(
            where={"id": user_id, column: stored}, data={column: fresh}
        )
    except Exception:  # see the docstring
        logger.exception("two-factor: could not move a secret to the current key for %s", user_id)
        return False
    invalidate_cached_user(user_id)
    return written == 1


def verify_key_configuration() -> None:
    """Called from ``create_app``: a malformed key stops the boot, with the setting's name."""
    try:
        keyring()
    except KeyConfigurationError as exc:
        raise RuntimeError(
            f"{exc}. Generate one with "
            '`python -c "import base64, secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"`.'
        ) from exc


@dataclass(frozen=True)
class EncryptionCensus:
    enabled: int
    current: int
    previous: int
    legacy: int
    unreadable: int


async def encryption_census() -> EncryptionCensus:
    """Counts only — which key every stored secret (live and pending) is under."""
    rows = await db.user.find_many(
        where={"OR": [{"twoFactorSecret": {"not": None}}, {"twoFactorPendingSecret": {"not": None}}]}
    )
    counts = {"current": 0, "previous": 0, "legacy": 0, "unreadable": 0}
    enabled = 0
    for row in rows:
        enabled += 1 if getattr(row, "twoFactorEnabled", False) else 0
        for stored in (row.twoFactorSecret, row.twoFactorPendingSecret):
            if not stored:
                continue
            opened = _opened(stored)
            if opened is None:
                counts["unreadable"] += 1
            elif opened[1] is None:
                counts["legacy"] += 1
            elif is_current(stored):
                counts["current"] += 1
            else:
                counts["previous"] += 1
    return EncryptionCensus(enabled=enabled, **counts)


async def check_keys_at_boot() -> None:
    """After the database connects. RAISES (the boot fails) only when ``TWO_FACTOR_KEY_REQUIRED`` is
    on, the key is unset, and somebody has two-step verification on. Otherwise every finding is a log
    line: during the transition the legacy key is used and a WARNING says so, so deploying the code
    before the secret is set never locks anybody out."""
    settings = get_settings()
    census = await encryption_census()
    if keyring().current is None:
        if census.enabled and getattr(settings, "two_factor_key_required", False):
            raise RuntimeError(
                f"TWO_FACTOR_ENCRYPTION_KEY is not set and {census.enabled} account(s) have two-step "
                "verification on (TWO_FACTOR_KEY_REQUIRED is on). Restore the key; see docs/SECURITY.md."
            )
        if census.enabled or census.legacy:
            logger.warning(
                "two-factor: TWO_FACTOR_ENCRYPTION_KEY is not set; %d account(s) with two-step on are "
                "using the legacy key derived from JWT_SECRET, so rotating JWT_SECRET would lock them "
                "out. Set the key (docs/SECURITY.md).",
                census.enabled,
            )
    elif census.legacy or census.previous:
        logger.warning(
            "two-factor: %d stored secret(s) are under the legacy key and %d under a previous key; "
            "run scripts/reencrypt_two_factor_secrets.py --apply before dropping either.",
            census.legacy,
            census.previous,
        )
    if census.unreadable:
        logger.error(
            "two-factor: %d stored secret(s) cannot be opened with any configured key; those accounts "
            "must use a recovery code or an administrator's reset.",
            census.unreadable,
        )


# ==================================================================================================
# Recovery codes
# ==================================================================================================


def generate_recovery_codes(count: int = RECOVERY_CODE_COUNT) -> list[str]:
    """``count`` codes shaped ``xxxx-xxxx``."""
    codes = []
    for _ in range(count):
        raw = "".join(secrets.choice(RECOVERY_ALPHABET) for _ in range(RECOVERY_GROUP * 2))
        codes.append(f"{raw[:RECOVERY_GROUP]}-{raw[RECOVERY_GROUP:]}")
    return codes


def canonical_recovery_code(text: str) -> str:
    """Lower case, punctuation and spaces dropped, re-hyphenated when it is the right length.

    ONE function for issuing and for checking, because the two must agree character for character or
    every recovery code fails at exactly the moment somebody has lost their phone. Anything that is
    not eight characters comes back stripped and unhyphenated, so it compares as itself.
    """
    cleaned = re.sub(r"[^a-z0-9]", "", (text or "").lower())
    if len(cleaned) == RECOVERY_GROUP * 2:
        return f"{cleaned[:RECOVERY_GROUP]}-{cleaned[RECOVERY_GROUP:]}"
    return cleaned


#: The two shapes :func:`code_kind` tells apart.
TOTP = "totp"
RECOVERY = "recovery"


def code_kind(text: str) -> str | None:
    """Which kind of code this is, by SHAPE: six digits is an authenticator code, eight letters and
    digits (hyphen and spaces optional) a recovery code, anything else neither. The clients run the
    same rule (``codeKind`` on the web, ``TwoFactorCode.kindOf`` on Android)."""
    compact = re.sub(r"[\s-]", "", text or "")
    if re.fullmatch(r"[0-9]{6}", compact):
        return TOTP
    if re.fullmatch(r"[A-Za-z0-9]{8}", compact):
        return RECOVERY
    return None


async def hash_recovery_codes(codes: list[str]) -> list[str]:
    """bcrypt every code, on worker threads: ten hashes at cost 12 is a couple of seconds of CPU
    that must not be spent on the event loop every other request is waiting on."""
    return list(
        await asyncio.gather(
            *(asyncio.to_thread(hash_password, canonical_recovery_code(code)) for code in codes)
        )
    )


async def _matching_hash(stored: list[str], submitted: str) -> str | None:
    canonical = canonical_recovery_code(submitted)
    verdicts = await asyncio.gather(
        *(asyncio.to_thread(verify_password, canonical, hashed) for hashed in stored)
    )
    match = None
    for hashed, ok in zip(stored, verdicts, strict=True):
        if ok:
            match = hashed
    return match


# ==================================================================================================
# Spending a code — the conditional updates
# ==================================================================================================


async def consume_totp_step(user_id: str, step: int) -> bool:
    """Record ``step`` as accepted, only if it is above the last accepted one. True when it was.

    ONE conditional UPDATE, not a read-then-write: two requests carrying one code both pass a read,
    and only one of them can match ``twoFactorLastStep < step`` after the other has written it.
    """
    written = await db.user.update_many(
        where={
            "id": user_id,
            "OR": [{"twoFactorLastStep": None}, {"twoFactorLastStep": {"lt": step}}],
        },
        data={"twoFactorLastStep": step},
    )
    return written == 1


async def consume_recovery_code(user: Any, submitted: str) -> bool:
    """Spend one recovery code. True when ``submitted`` was a live code and THIS call spent it.

    The hash is removed by an update conditioned on the array still being the one that was read, so
    two sign-ins presenting the same code cannot both succeed, and a concurrent spend of a DIFFERENT
    code makes this one fail rather than resurrect the other — the person types it again.
    """
    stored = list(getattr(user, "twoFactorRecoveryCodes", None) or [])
    if not stored:
        return False
    match = await _matching_hash(stored, submitted)
    if match is None:
        return False
    remaining = [hashed for hashed in stored if hashed != match]
    written = await db.user.update_many(
        where={"id": user.id, "twoFactorRecoveryCodes": {"equals": stored}},
        data={"twoFactorRecoveryCodes": {"set": remaining}},
    )
    return written == 1


async def verify_second_factor(user: Any, code: str, *, allow_recovery: bool = True) -> str | None:
    """Check ``code`` against ``user`` and SPEND it. Returns :data:`TOTP`, :data:`RECOVERY` or None."""
    kind = code_kind(code)
    if kind == TOTP:
        secret = decrypt_secret(getattr(user, "twoFactorSecret", None))
        if secret is None:
            return None
        step = match_totp_step(secret, code)
        if step is None or not await consume_totp_step(user.id, step):
            return None
        # A right code proves the stored value opened: move it to the current key while we hold it.
        await refresh_encryption(user.id, "twoFactorSecret", getattr(user, "twoFactorSecret", None))
        return TOTP
    if kind == RECOVERY and allow_recovery:
        return RECOVERY if await consume_recovery_code(user, code) else None
    return None


def _user_field(user: Any, name: str) -> Any:
    return user.get(name) if isinstance(user, dict) else getattr(user, name, None)


# ── The per-account failure budgets ─────────────────────────────────────────────────────────────
#
# TWO INDEPENDENT BUDGETS, ONE MECHANISM — the documentation portal's, copied. Six-digit codes spend
# `CODE_BUDGET` (ten per window); recovery codes spend `RECOVERY_BUDGET` (five per window). A lock on
# one never blocks the other, so somebody who holds only the password and burns the six-digit budget
# cannot shut the owner out: the owner's recovery codes still work. Each budget is four columns, named
# in its `AttemptBudget`.
#
# EVERY STEP IS ONE CONDITIONAL WRITE, because every read here is stale by the time it is acted on: a
# burst of concurrent wrong codes must not each see "not locked" and each get a check. So a code is
# checked only after it has CLAIMED a slot with an atomic, conditional increment; the lock is written
# by exactly one request (a compare-and-swap on the window it ends); and a success clears the counter
# only if nobody else has claimed a slot since it read it.
#
# THE COLUMNS, per budget. `attempts` counts slots claimed in the live window, which started at
# `window_started` (None = no live window). `locked_until` is when the latest lock ends — kept after
# it lapses, as the record of when it ended, which is what the decay reads. `lockouts` is how many
# locks in a row, which sets the next one's length.


@dataclass(frozen=True)
class AttemptBudget:
    name: str
    limit: int
    attempts: str
    window_started: str
    locked_until: str
    lockouts: str


CODE_BUDGET = AttemptBudget(
    name="code",
    limit=FAILURE_BUDGET,
    attempts="twoFactorFailedAttempts",
    window_started="twoFactorFailureWindowStartedAt",
    locked_until="twoFactorLockedUntil",
    lockouts="twoFactorLockouts",
)
RECOVERY_BUDGET = AttemptBudget(
    name="recovery",
    limit=RECOVERY_FAILURE_BUDGET,
    attempts="twoFactorRecoveryFailedAttempts",
    window_started="twoFactorRecoveryWindowStartedAt",
    locked_until="twoFactorRecoveryLockedUntil",
    lockouts="twoFactorRecoveryLockouts",
)
BUDGETS = (CODE_BUDGET, RECOVERY_BUDGET)


class SecondFactorLocked(Exception):
    """No slot left in this budget: it is locked, or spent and the lock is being set."""


def lockout_duration(previous_lockouts: int) -> timedelta:
    """15 minutes, doubling with each earlier lockout, never more than an hour."""
    return min(FIRST_LOCKOUT * (2 ** min(max(previous_lockouts, 0), 16)), MAX_LOCKOUT)


def _aware(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value


def is_locked(user: Any, budget: AttemptBudget = CODE_BUDGET, now: datetime | None = None) -> bool:
    until = _aware(_user_field(user, budget.locked_until))
    return until is not None and until > (now or datetime.now(UTC))


def fully_locked(user: Any, now: datetime | None = None) -> bool:
    """Every way in is locked: no code of any kind would be checked. What sign-in refuses on."""
    return all(is_locked(user, budget, now) for budget in BUDGETS)


#: What a switch-off or an administrator's reset puts back, for both budgets.
CLEAR_FAILURES: dict[str, Any] = {
    column: value
    for budget in BUDGETS
    for column, value in (
        (budget.attempts, 0),
        (budget.window_started, None),
        (budget.locked_until, None),
        (budget.lockouts, 0),
    )
}


def budget_for(code: str, *, allow_recovery: bool = True) -> AttemptBudget:
    """Which budget a submitted code spends: a recovery code's own, and everything else (six digits,
    or something that is neither shape) the six-digit one."""
    return RECOVERY_BUDGET if allow_recovery and code_kind(code) == RECOVERY else CODE_BUDGET


async def _start_window_if_lapsed(row: Any, budget: AttemptBudget, now: datetime) -> None:
    """Begin a fresh window when there is none live (never started, older than fifteen minutes, or
    ended by a lock that has since lapsed). Conditional on the window and the lock being what this
    request read, so only one of a burst restarts it. The lockout count DECAYS to zero here when the
    last lock ended more than a day ago: somebody who merely knows the password cannot ratchet the
    owner's lock up for ever."""
    started = _aware(_user_field(row, budget.window_started))
    if started is not None and now - started <= FAILURE_WINDOW:
        return
    last_lock_end = _aware(_user_field(row, budget.locked_until))
    data: dict[str, Any] = {budget.attempts: 0, budget.window_started: now}
    if last_lock_end is not None and now - last_lock_end > LOCKOUT_DECAY:
        data[budget.lockouts] = 0
    await db.user.update_many(
        where={
            "id": _user_field(row, "id"),
            budget.window_started: _user_field(row, budget.window_started),
            budget.locked_until: _user_field(row, budget.locked_until),
        },
        data=data,
    )


async def claim_attempt_slot(user_id: str, budget: AttemptBudget = CODE_BUDGET) -> int:
    """Claim one of the budget's slots BEFORE any code is checked; the count after claiming.

    One `update_many` whose condition IS the rule — not locked, and fewer than the limit used — with
    an atomic increment. Zero rows means there is no slot: SecondFactorLocked.
    """
    now = datetime.now(UTC)
    row = await db.user.find_unique(where={"id": user_id})
    if row is None or is_locked(row, budget, now):
        raise SecondFactorLocked()
    await _start_window_if_lapsed(row, budget, now)
    claimed = await db.user.update_many(
        where={
            "id": user_id,
            budget.attempts: {"lt": budget.limit},
            "OR": [{budget.locked_until: None}, {budget.locked_until: {"lt": now}}],
        },
        data={budget.attempts: {"increment": 1}},
    )
    if not claimed:
        raise SecondFactorLocked()
    invalidate_cached_user(user_id)
    after = await db.user.find_unique(where={"id": user_id})
    return int(_user_field(after, budget.attempts) or 0)


async def settle_success(user_id: str, claimed_count: int, budget: AttemptBudget = CODE_BUDGET) -> None:
    """A correct code clears its budget — but only if nobody has claimed a slot since this request
    did. If somebody has, only this request's own slot is handed back, so concurrent wrong codes stay
    counted. A success also ends that budget's lockout run."""
    cleared = await db.user.update_many(
        where={"id": user_id, budget.attempts: claimed_count},
        data={budget.attempts: 0, budget.window_started: None, budget.lockouts: 0},
    )
    if not cleared:
        await db.user.update_many(
            where={"id": user_id, budget.attempts: {"gt": 0}},
            data={budget.attempts: {"decrement": 1}},
        )
    invalidate_cached_user(user_id)


async def lock_if_spent(user_id: str, budget: AttemptBudget = CODE_BUDGET) -> bool:
    """After a wrong code: when the budget is spent, lock — written by exactly ONE request, by a
    compare-and-swap on the window the lock ends, and the lockout count escalated by an atomic
    increment. The owner is told by e-mail. True when this request wrote the lock."""
    row = await db.user.find_unique(where={"id": user_id})
    if row is None or (_user_field(row, budget.attempts) or 0) < budget.limit:
        return False
    window = _user_field(row, budget.window_started)
    if window is None:
        return False  # another request has already written this window's lock
    now = datetime.now(UTC)
    lockouts = int(_user_field(row, budget.lockouts) or 0)
    until = now + lockout_duration(lockouts)
    won = await db.user.update_many(
        where={
            "id": user_id,
            budget.attempts: {"gte": budget.limit},
            budget.window_started: window,
        },
        data={
            budget.locked_until: until,
            budget.window_started: None,
            budget.lockouts: {"increment": 1},
        },
    )
    if not won:
        return False
    invalidate_cached_user(user_id)
    # THE AUDIT LINE, at WARNING so it reaches journald on the box, where an administrator looking
    # into a lock-out finds it beside the other `two-factor:` lines.
    logger.warning(
        "AUDIT two-factor locked after %d wrong %s codes: user=%s lockout=%d until=%s",
        budget.limit,
        budget.name,
        user_id,
        lockouts + 1,
        until.isoformat(),
    )
    await notify_second_factor_locked(row, until, budget)
    return True


async def notify_second_factor_locked(user: Any, until: datetime, budget: AttemptBudget = CODE_BUDGET) -> None:
    """Tell the owner. Only somebody holding the password reaches a second-factor lock, so this is the
    owner's one signal that the password is known. Through the existing outbox (a no-op when mail is
    off); never raises — the lock is already written and must stand whatever the mail does."""
    try:
        await email_outbox.enqueue(
            mailer.SECOND_FACTOR_LOCKED,
            to_address=_user_field(user, "email") or "",
            recipient_id=_user_field(user, "id"),
            params={
                "recipientName": _user_field(user, "name"),
                "lockedUntil": until.isoformat(),
                "codeKind": budget.name,
            },
        )
    except Exception:  # see the docstring
        logger.exception("mail: could not queue a second-factor lock notice for %s", _user_field(user, "id"))


async def clear_failures(user_id: str) -> None:
    await db.user.update(where={"id": user_id}, data=dict(CLEAR_FAILURES))
    invalidate_cached_user(user_id)


async def check_second_factor(user: Any, code: str, *, allow_recovery: bool = True) -> bool:
    """THE one way a second-factor code is checked outside enrolment: claim a slot in the budget the
    code's shape belongs to (or SecondFactorLocked, with nothing checked), check the code — spent if
    right — then settle.

    EVERY door that accepts a second factor goes through this: the sign-in's ``/verify``, the dataset
    token, ``/disable`` and ``/recovery-codes``.
    """
    user_id = _user_field(user, "id")
    budget = budget_for(code, allow_recovery=allow_recovery)
    claimed = await claim_attempt_slot(user_id, budget)
    if budget is RECOVERY_BUDGET:
        accepted = await consume_recovery_code(user, code)
    else:
        accepted = await verify_second_factor(user, code, allow_recovery=False) is not None
    if accepted:
        await settle_success(user_id, claimed, budget)
        return True
    await lock_if_spent(user_id, budget)
    return False


# ==================================================================================================
# The sign-in challenge
# ==================================================================================================


def _challenge_key() -> bytes:
    return hmac.new(
        get_settings().jwt_secret.encode("utf-8"), _CHALLENGE_CONTEXT, hashlib.sha256
    ).digest()


async def issue_challenge(user: Any) -> str:
    """Write a challenge row and return its signed token. Sweeps rows that have expired first."""
    now = datetime.now(UTC)
    await db.twofactorchallenge.delete_many(where={"expiresAt": {"lt": now}})
    expires = now + timedelta(minutes=CHALLENGE_MINUTES)
    row = await db.twofactorchallenge.create(data={"userId": user.id, "expiresAt": expires})
    return jwt.encode(
        {
            "sub": user.id,
            "jti": row.id,
            "purpose": CHALLENGE_PURPOSE,
            "iat": int(now.timestamp()),
            "exp": expires,
        },
        _challenge_key(),
        algorithm="HS256",
    )


@dataclass(frozen=True)
class Challenge:
    user_id: str
    challenge_id: str


def read_challenge(token: str) -> Challenge:
    """Verify a challenge token's signature, expiry and purpose. Raises :class:`TwoFactorError`."""
    try:
        claims = jwt.decode(
            token or "",
            _challenge_key(),
            algorithms=["HS256"],
            options={"require": ["exp", "sub", "jti"]},
        )
    except jwt.PyJWTError as exc:
        raise TwoFactorError("dead", CHALLENGE_DEAD_DETAIL) from exc
    if claims.get("purpose") != CHALLENGE_PURPOSE:
        raise TwoFactorError("dead", CHALLENGE_DEAD_DETAIL)
    return Challenge(user_id=str(claims["sub"]), challenge_id=str(claims["jti"]))


async def spend_challenge_attempt(challenge: Challenge) -> bool:
    """Take one attempt from the challenge. False when it is used up, already redeemed or gone.

    Charged BEFORE the code is checked — take-then-check, the shape ``rate_limit`` uses — so a
    hundred parallel guesses cannot all pass a count that none of them has incremented yet.
    """
    taken = await db.twofactorchallenge.update_many(
        where={
            "id": challenge.challenge_id,
            "userId": challenge.user_id,
            "usedAt": None,
            "attempts": {"lt": MAX_CHALLENGE_ATTEMPTS},
            "expiresAt": {"gt": datetime.now(UTC)},
        },
        data={"attempts": {"increment": 1}},
    )
    return taken == 1


async def close_challenge(challenge: Challenge) -> bool:
    """Mark a challenge redeemed. True for the one call that did, so one challenge opens one session."""
    closed = await db.twofactorchallenge.update_many(
        where={"id": challenge.challenge_id, "usedAt": None},
        data={"usedAt": datetime.now(UTC)},
    )
    return closed == 1


# ==================================================================================================
# Self-service: status, setup, enable, disable, new recovery codes — and the administrator's reset
# ==================================================================================================


def status_of(user: Any) -> dict[str, Any]:
    enabled = bool(getattr(user, "twoFactorEnabled", False))
    return {
        "enabled": enabled,
        "recovery_codes_remaining": len(getattr(user, "twoFactorRecoveryCodes", None) or [])
        if enabled
        else 0,
        "enabled_at": getattr(user, "twoFactorEnabledAt", None) if enabled else None,
    }


async def begin_setup(user: Any) -> dict[str, str]:
    """A new secret, stored ENCRYPTED as the pending one; nothing is switched on yet.

    Stored rather than handed to the client to send back (cxa-cms's shape), so ``/enable`` takes only
    a code and the key never has to travel twice. A second setup simply replaces the pending secret.
    """
    if getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", ALREADY_ON_DETAIL)
    secret = generate_secret()
    await db.user.update(
        where={"id": user.id}, data={"twoFactorPendingSecret": encrypt_secret(secret)}
    )
    uri = totp_uri(secret, user.email)
    return {"secret": secret, "otpauth_uri": uri, "qr_svg": qr_svg(uri)}


async def enable(user: Any, code: str) -> list[str]:
    """Confirm the pending secret with a code from it, switch on, and return ten fresh recovery codes.

    The step the confirming code matched is recorded as the last one accepted, so the very code typed
    here cannot also complete a sign-in a moment later. The final write is conditioned on the pending
    secret still being the one this request checked against, so a setup restarted in another tab
    between the check and the write cannot switch on a secret nobody confirmed.
    """
    if getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", ALREADY_ON_DETAIL)
    pending = getattr(user, "twoFactorPendingSecret", None)
    secret = decrypt_secret(pending)
    if secret is None:
        raise TwoFactorError("invalid", NO_SETUP_DETAIL)
    step = match_totp_step(secret, code)
    if step is None:
        raise TwoFactorError("invalid", WRONG_CODE_DETAIL)
    codes = generate_recovery_codes()
    hashes = await hash_recovery_codes(codes)
    written = await db.user.update_many(
        where={"id": user.id, "twoFactorEnabled": False, "twoFactorPendingSecret": pending},
        data={
            "twoFactorEnabled": True,
            # Sealed again if the key changed between setup and this confirmation.
            "twoFactorSecret": reencrypted(pending) or pending,
            "twoFactorPendingSecret": None,
            "twoFactorRecoveryCodes": {"set": hashes},
            "twoFactorLastStep": step,
            "twoFactorEnabledAt": datetime.now(UTC),
        },
    )
    if written != 1:
        raise TwoFactorError("conflict", NO_SETUP_DETAIL)
    return codes


async def _checked(user: Any, code: str, *, allow_recovery: bool = True) -> bool:
    """:func:`check_second_factor`, with a locked budget turned into this module's refusal."""
    try:
        return await check_second_factor(user, code, allow_recovery=allow_recovery)
    except SecondFactorLocked as exc:
        raise TwoFactorError("locked", TOO_MANY_ATTEMPTS_DETAIL) from exc


_OFF: dict[str, Any] = {
    "twoFactorEnabled": False,
    "twoFactorSecret": None,
    "twoFactorPendingSecret": None,
    # Cleared WITH the secret: codes left behind would still open the account, so a switched-off
    # second factor would go on holding ten live credentials nobody remembers exist.
    "twoFactorRecoveryCodes": {"set": []},
    "twoFactorLastStep": None,
    "twoFactorEnabledAt": None,
    **CLEAR_FAILURES,
}


async def disable(user: Any, code: str) -> str:
    """Switch off, proved by an authenticator code or a recovery code. Returns which one was used.

    A session alone is not proof of presence: somebody at an unlocked laptop must not be able to
    remove the control that exists to stop them, so a code is required.
    """
    if not getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", NOT_ON_DETAIL)
    if not await _checked(user, code):
        raise TwoFactorError("invalid", WRONG_CODE_DETAIL)
    await db.user.update(where={"id": user.id}, data=_OFF)
    return code_kind(code) or TOTP


async def regenerate_recovery_codes(user: Any, code: str) -> list[str]:
    """Replace every recovery code, proved by a CURRENT authenticator code — not a recovery code,
    since a person holding only those is better served by using one to sign in and turning this off."""
    if not getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", NOT_ON_DETAIL)
    if not await _checked(user, code, allow_recovery=False):
        raise TwoFactorError("invalid", WRONG_CODE_DETAIL)
    codes = generate_recovery_codes()
    hashes = await hash_recovery_codes(codes)
    await db.user.update(
        where={"id": user.id}, data={"twoFactorRecoveryCodes": {"set": hashes}}
    )
    return codes


async def reset_by_administrator(target: Any) -> int:
    """Switch somebody else's second factor off. Returns how many recovery codes that destroyed —
    the figure the audit line records. The secret is never read, let alone returned."""
    destroyed = len(getattr(target, "twoFactorRecoveryCodes", None) or [])
    await db.user.update(
        where={"id": target.id}, data={**_OFF, "sessionsValidFrom": datetime.now(UTC)}
    )
    return destroyed
