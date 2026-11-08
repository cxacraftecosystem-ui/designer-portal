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
from app.services import mailer

logger = logging.getLogger(__name__)

DIGITS = 6
PERIOD_SECONDS = 30
WINDOW_STEPS = 1
SECRET_BYTES = 20

TOTP_ISSUER = "Design Prototype Workshop"

RECOVERY_CODE_COUNT = 10
RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
RECOVERY_GROUP = 4

CHALLENGE_PURPOSE = "2fa"
CHALLENGE_MINUTES = 5
MAX_CHALLENGE_ATTEMPTS = 5

SECRET_USER_FIELDS = _SECRET_USER_FIELDS

FAILURE_BUDGET = 10
RECOVERY_FAILURE_BUDGET = 5
FAILURE_WINDOW = timedelta(minutes=15)
FIRST_LOCKOUT = timedelta(minutes=15)
MAX_LOCKOUT = timedelta(hours=1)
LOCKOUT_DECAY = timedelta(hours=24)

_ENCRYPTION_CONTEXT = b"designer-portal-totp-v1"
_CHALLENGE_CONTEXT = b"designer-portal-two-factor-challenge-v1"


class TwoFactorError(Exception):
    def __init__(self, kind: str, detail: str) -> None:
        super().__init__(detail)
        self.kind = kind
        self.detail = detail


WRONG_CODE_DETAIL = "That code didn't work. Check your authenticator app and try again."
CHALLENGE_DEAD_DETAIL = "This sign-in has timed out. Sign in again."
ALREADY_ON_DETAIL = "Two-step verification is already on for this account."
NOT_ON_DETAIL = "Two-step verification is not on for this account."
NO_SETUP_DETAIL = "Start the setup again to get a new key."
TOO_MANY_ATTEMPTS_DETAIL = "Too many attempts. Try again later."



def generate_secret() -> str:
    return base64.b32encode(secrets.token_bytes(SECRET_BYTES)).decode("ascii").rstrip("=")


def _b32decode(secret: str) -> bytes:
    cleaned = re.sub(r"\s", "", secret).upper().rstrip("=")
    return base64.b32decode(cleaned + "=" * (-len(cleaned) % 8))


def hotp(key: bytes, counter: int) -> str:
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    binary = struct.unpack(">I", digest[offset : offset + 4])[0] & 0x7FFFFFFF
    return str(binary % 10**DIGITS).zfill(DIGITS)


def time_step(at: float | None = None) -> int:
    return int((time.time() if at is None else at) // PERIOD_SECONDS)


def current_totp(secret: str, at: float | None = None) -> str:
    return hotp(_b32decode(secret), time_step(at))


def match_totp_step(secret: str, code: str, at: float | None = None) -> int | None:
    normalised = re.sub(r"\s", "", code or "")
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


QR_QUIET_ZONE_MODULES = 4


def qr_matrix(text: str) -> list[list[bool]]:
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
    matrix = qr_matrix(text)
    size = len(matrix)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" '
        f'shape-rendering="crispEdges">'
        f'<rect width="{size}" height="{size}" fill="#ffffff"/>'
        f'<path fill="#000000" d="{qr_path(matrix)}"/>'
        "</svg>"
    )



LEGACY_PREFIX = "v1"
VERSIONED_PREFIX = "v2:"
KEY_BYTES = 32
_KEY_ID_CONTEXT = b"designer-portal-totp-key-id-v2"


@lru_cache(maxsize=4)
def _legacy_key(jwt_secret: str) -> bytes:
    return hashlib.scrypt(
        jwt_secret.encode("utf-8"), salt=_ENCRYPTION_CONTEXT, n=2**14, r=8, p=1, dklen=32
    )


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class KeyConfigurationError(ValueError):
    pass


def parse_key(text: str, *, name: str = "TWO_FACTOR_ENCRYPTION_KEY") -> bytes:
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
    return _b64(hmac.new(key, _KEY_ID_CONTEXT, hashlib.sha256).digest()[:6])


@dataclass(frozen=True)
class Keyring:
    current: tuple[str, bytes] | None
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
    ring = keyring()
    if ring.current is None:
        return ".".join((LEGACY_PREFIX, *_seal(_legacy_key(get_settings().jwt_secret), plain, None)))
    kid, key = ring.current
    label = f"{VERSIONED_PREFIX}{kid}"
    return ".".join((label, *_seal(key, plain, label.encode("ascii"))))


def _opened(stored: str | None) -> tuple[str, str | None] | None:
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
    opened = _opened(stored)
    return opened[0] if opened else None


def is_current(stored: str | None) -> bool:
    ring = keyring()
    if ring.current is None or not stored:
        return True
    return stored.startswith(f"{VERSIONED_PREFIX}{ring.current[0]}.")


def reencrypted(stored: str | None) -> str | None:
    if is_current(stored):
        return None
    opened = _opened(stored)
    return encrypt_secret(opened[0]) if opened else None


async def refresh_encryption(user_id: str, column: str, stored: str | None) -> bool:
    fresh = reencrypted(stored)
    if fresh is None:
        return False
    try:
        written = await db.user.update_many(
            where={"id": user_id, column: stored}, data={column: fresh}
        )
    except Exception:
        logger.exception("two-factor: could not move a secret to the current key for %s", user_id)
        return False
    invalidate_cached_user(user_id)
    return written == 1


def verify_key_configuration() -> None:
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
    versioned: int = 0


async def encryption_census() -> EncryptionCensus:
    rows = await db.user.find_many(
        where={"OR": [{"twoFactorSecret": {"not": None}}, {"twoFactorPendingSecret": {"not": None}}]}
    )
    counts = {"current": 0, "previous": 0, "legacy": 0, "unreadable": 0, "versioned": 0}
    enabled = 0
    for row in rows:
        enabled += 1 if getattr(row, "twoFactorEnabled", False) else 0
        for stored in (row.twoFactorSecret, row.twoFactorPendingSecret):
            if not stored:
                continue
            if stored.startswith(VERSIONED_PREFIX):
                counts["versioned"] += 1
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
    settings = get_settings()
    census = await encryption_census()
    if keyring().current is None:
        if census.versioned:
            raise RuntimeError(
                f"TWO_FACTOR_ENCRYPTION_KEY is not set but {census.versioned} stored two-step "
                "secret(s) were sealed under it, so the key was lost. Restore it; see docs/SECURITY.md."
            )
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



def generate_recovery_codes(count: int = RECOVERY_CODE_COUNT) -> list[str]:
    codes = []
    for _ in range(count):
        raw = "".join(secrets.choice(RECOVERY_ALPHABET) for _ in range(RECOVERY_GROUP * 2))
        codes.append(f"{raw[:RECOVERY_GROUP]}-{raw[RECOVERY_GROUP:]}")
    return codes


def canonical_recovery_code(text: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]", "", (text or "").lower())
    if len(cleaned) == RECOVERY_GROUP * 2:
        return f"{cleaned[:RECOVERY_GROUP]}-{cleaned[RECOVERY_GROUP:]}"
    return cleaned


TOTP = "totp"
RECOVERY = "recovery"


def code_kind(text: str) -> str | None:
    compact = re.sub(r"[\s-]", "", text or "")
    if re.fullmatch(r"[0-9]{6}", compact):
        return TOTP
    if re.fullmatch(r"[A-Za-z0-9]{8}", compact):
        return RECOVERY
    return None


async def hash_recovery_codes(codes: list[str]) -> list[str]:
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



async def consume_totp_step(user_id: str, step: int) -> bool:
    written = await db.user.update_many(
        where={
            "id": user_id,
            "OR": [{"twoFactorLastStep": None}, {"twoFactorLastStep": {"lt": step}}],
        },
        data={"twoFactorLastStep": step},
    )
    return written == 1


async def consume_recovery_code(user: Any, submitted: str) -> bool:
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
    kind = code_kind(code)
    if kind == TOTP:
        secret = decrypt_secret(getattr(user, "twoFactorSecret", None))
        if secret is None:
            return None
        step = match_totp_step(secret, code)
        if step is None or not await consume_totp_step(user.id, step):
            return None
        await refresh_encryption(user.id, "twoFactorSecret", getattr(user, "twoFactorSecret", None))
        return TOTP
    if kind == RECOVERY and allow_recovery:
        return RECOVERY if await consume_recovery_code(user, code) else None
    return None


def _user_field(user: Any, name: str) -> Any:
    return user.get(name) if isinstance(user, dict) else getattr(user, name, None)


def _user_id(user: Any) -> str:
    raw = user.get("id") if isinstance(user, dict) else getattr(user, "id", None)
    return str(raw) if raw is not None else "-"



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
    pass


def lockout_duration(previous_lockouts: int) -> timedelta:
    return min(FIRST_LOCKOUT * (2 ** min(max(previous_lockouts, 0), 16)), MAX_LOCKOUT)


def _aware(value: datetime | None) -> datetime | None:
    if value is not None and value.tzinfo is None:
        return value.replace(tzinfo=UTC)
    return value


def is_locked(user: Any, budget: AttemptBudget = CODE_BUDGET, now: datetime | None = None) -> bool:
    until = _aware(_user_field(user, budget.locked_until))
    return until is not None and until > (now or datetime.now(UTC))


def fully_locked(user: Any, now: datetime | None = None) -> bool:
    return all(is_locked(user, budget, now) for budget in BUDGETS)


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
    return RECOVERY_BUDGET if allow_recovery and code_kind(code) == RECOVERY else CODE_BUDGET


async def _start_window_if_lapsed(row: Any, budget: AttemptBudget, now: datetime) -> None:
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
    row = await db.user.find_unique(where={"id": user_id})
    if row is None or (_user_field(row, budget.attempts) or 0) < budget.limit:
        return False
    window = _user_field(row, budget.window_started)
    if window is None:
        return False
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
    from app.services.notifications import notify

    try:
        params = {
            "recipientName": _user_field(user, "name"),
            "lockedUntil": until.isoformat(),
            "codeKind": budget.name,
        }
        await notify(
            user,
            mailer.SECOND_FACTOR_LOCKED,
            mailer.subject_for(mailer.SECOND_FACTOR_LOCKED, params),
            None,
            "/settings",
            email_kind=mailer.SECOND_FACTOR_LOCKED,
            email_params=params,
        )
    except Exception:
        logger.exception("mail: could not queue a second-factor lock notice for %s", _user_id(user))


async def clear_failures(user_id: str) -> None:
    await db.user.update(where={"id": user_id}, data=dict(CLEAR_FAILURES))
    invalidate_cached_user(user_id)


async def check_second_factor(user: Any, code: str, *, allow_recovery: bool = True) -> bool:
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



def _challenge_key() -> bytes:
    return hmac.new(
        get_settings().jwt_secret.encode("utf-8"), _CHALLENGE_CONTEXT, hashlib.sha256
    ).digest()


async def issue_challenge(user: Any) -> str:
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
    closed = await db.twofactorchallenge.update_many(
        where={"id": challenge.challenge_id, "usedAt": None},
        data={"usedAt": datetime.now(UTC)},
    )
    return closed == 1



PENDING_SETUP_MINUTES = 15


def pending_is_live(user: Any, now: datetime | None = None) -> bool:
    if getattr(user, "twoFactorEnabled", False):
        return False
    if not getattr(user, "twoFactorPendingSecret", None):
        return False
    since = _aware(getattr(user, "twoFactorPendingSince", None))
    if since is None:
        return False
    return (now or datetime.now(UTC)) - since < timedelta(minutes=PENDING_SETUP_MINUTES)


def has_stale_pending(user: Any, now: datetime | None = None) -> bool:
    return (
        not getattr(user, "twoFactorEnabled", False)
        and bool(getattr(user, "twoFactorPendingSecret", None))
        and not pending_is_live(user, now)
    )


def status_of(user: Any, now: datetime | None = None) -> dict[str, Any]:
    enabled = bool(getattr(user, "twoFactorEnabled", False))
    return {
        "enabled": enabled,
        "pending": pending_is_live(user, now),
        "recovery_codes_remaining": len(getattr(user, "twoFactorRecoveryCodes", None) or [])
        if enabled
        else 0,
        "enabled_at": getattr(user, "twoFactorEnabledAt", None) if enabled else None,
    }


async def clear_pending(user_id: str) -> bool:
    cleared = await db.user.update_many(
        where={"id": user_id, "twoFactorEnabled": False, "NOT": [{"twoFactorPendingSecret": None}]},
        data={"twoFactorPendingSecret": None, "twoFactorPendingSince": None},
    )
    return cleared > 0


async def begin_setup(user: Any) -> dict[str, str]:
    if getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", ALREADY_ON_DETAIL)
    secret = generate_secret()
    await db.user.update(
        where={"id": user.id},
        data={"twoFactorPendingSecret": encrypt_secret(secret), "twoFactorPendingSince": datetime.now(UTC)},
    )
    uri = totp_uri(secret, user.email)
    return {"secret": secret, "otpauth_uri": uri, "qr_svg": qr_svg(uri)}


async def enable(user: Any, code: str) -> list[str]:
    if getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", ALREADY_ON_DETAIL)
    pending = getattr(user, "twoFactorPendingSecret", None)
    secret = decrypt_secret(pending) if pending_is_live(user) else None
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
            "twoFactorSecret": reencrypted(pending) or pending,
            "twoFactorPendingSecret": None,
            "twoFactorPendingSince": None,
            "twoFactorRecoveryCodes": {"set": hashes},
            "twoFactorLastStep": step,
            "twoFactorEnabledAt": datetime.now(UTC),
        },
    )
    if written != 1:
        raise TwoFactorError("conflict", NO_SETUP_DETAIL)
    return codes


async def _checked(user: Any, code: str, *, allow_recovery: bool = True) -> bool:
    try:
        return await check_second_factor(user, code, allow_recovery=allow_recovery)
    except SecondFactorLocked as exc:
        raise TwoFactorError("locked", TOO_MANY_ATTEMPTS_DETAIL) from exc


_OFF: dict[str, Any] = {
    "twoFactorEnabled": False,
    "twoFactorSecret": None,
    "twoFactorPendingSecret": None,
    "twoFactorPendingSince": None,
    "twoFactorRecoveryCodes": {"set": []},
    "twoFactorLastStep": None,
    "twoFactorEnabledAt": None,
    **CLEAR_FAILURES,
}


async def disable(user: Any, code: str) -> str:
    if not getattr(user, "twoFactorEnabled", False):
        raise TwoFactorError("conflict", NOT_ON_DETAIL)
    if not await _checked(user, code):
        raise TwoFactorError("invalid", WRONG_CODE_DETAIL)
    await db.user.update(where={"id": user.id}, data=_OFF)
    return code_kind(code) or TOTP


async def regenerate_recovery_codes(user: Any, code: str) -> list[str]:
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
    destroyed = len(getattr(target, "twoFactorRecoveryCodes", None) or [])
    await db.user.update(
        where={"id": target.id}, data={**_OFF, "sessionsValidFrom": datetime.now(UTC)}
    )
    return destroyed
