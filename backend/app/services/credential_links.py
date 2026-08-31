"""THE PASSWORD LINK: a one-off, single-use address that lets somebody set their own password.

Ported from ``C:/dev/cxa-cms/lib/auth/credential-token.ts``, which is the canonical minting of this
construction. Read that file before changing anything here; the four properties it argues for are
kept intact and three of them are the reason this is not a JWT.

── WHAT WAS PORTED, VERBATIM IN SUBSTANCE ────────────────────────────────────────────────────────

1. **NOT A SESSION TOKEN.** ``core.security.create_access_token`` mints the credential the whole API
   authorises against. Anything shaped like one risks being accepted as one by a call site that
   checks only a signature, so this is a different construction — ``base64url(payload) "."
   base64url(HMAC-SHA256(payload))`` — that no bearer-token path can parse.
2. **THE FINGERPRINT, WHICH IS WHAT MAKES IT SINGLE-USE.** The payload carries 16 hex characters of
   a SHA-256 of the account's password hash at minting time. Setting a password changes the hash,
   so every link minted before it stops verifying — immediately, with nothing having to remember.
   An account with NO password digests a fixed sentinel, so an invitation is bound to "still has no
   password". The digest is never enough to attack the hash and is enough to notice a change.
3. **EXPIRY IS INSIDE THE SIGNED PAYLOAD**, so a stale link is refused without a database read, and
   the reason can be reported honestly to somebody holding a link that has simply gone cold.
4. **SESSION REVOCATION ON RESET.** Redeeming a link writes ``User.sessionsValidFrom``, which
   ``deps._user_from_bearer`` compares every token's ``iat`` against. The reason somebody is
   resetting is usually that a session they no longer control exists somewhere.

── WHAT WAS DELIBERATELY NOT PORTED ──────────────────────────────────────────────────────────────

**cxa-cms HAS NO THROTTLE ON ISSUING, AND THAT IS A HOLE THIS DOES NOT COPY.** Because minting is
free and redeeming revokes sessions, an administrator there can sign a colleague out of their own
laptop as often as they can click. :data:`ISSUE_BUDGET` and :data:`ISSUE_WINDOW_HOURS` close it:
per-SUBJECT, not per-admin, because the person being harmed is the subject and two admins taking
turns is the same harm.

**THE TOKEN IS NEVER RETURNED AS A SEPARATE FIELD BESIDE THE LINK.** cxa-cms's canonical route
refuses to, on the stated grounds that a credential appearing twice in one answer is a credential in
two places to keep out of logs. Honoured: :func:`issue_link` hands back the LINK, and the token
exists inside it.

── THE ONE THING THAT IS NEW HERE: A TABLE ───────────────────────────────────────────────────────

cxa-cms is deliberately tableless. ``PasswordResetToken`` exists here because the owner asked for
revocation, a throttle and an audit trail, and a stateless token can express none of the three — see
that model's docstring. **It does not replace the fingerprint**; both checks run, and the
fingerprint is still what makes a redeemed link dead even if the row is never read again.

── DELIVERY ──────────────────────────────────────────────────────────────────────────────────────

Owner, 2026-08-30: *"implement all the measures, we will use just admin copies the link for now
though."* So the whole mechanism is built and the transport is one implementation of
:class:`CredentialDelivery` that hands the link back to the administrator to copy. **No mail
dependency was added** — ``pyproject.toml`` declares no mailer and this change does not give it one.
Adding SES or SMTP later is a new class here and a settings value; nothing outside this module and
its one settings read needs to change.
"""

import base64
import hashlib
import hmac
import json
import logging
import secrets
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from app.core.config import get_settings
from app.core.db import db

logger = logging.getLogger(__name__)

#: What a link is for. The two differ in how long they last and in what the screen says.
INVITE = "INVITE"
RESET = "RESET"

#: An invitation is generous: it is passed on by hand, read on a Monday and acted on after a
#: conference. A reset link is short, because it answers "I am locked out NOW" and a link that
#: outlives that conversation is a spare key left under the mat. Both numbers are cxa-cms's.
INVITE_TTL_HOURS = 72
RESET_TTL_HOURS = 2

#: THE THROTTLE cxa-cms LACKS. Four links for one account in an hour is generous for the real case
#: (an admin issues one, the designer loses it, the admin issues another) and cheap enough that
#: nobody can use this route to keep somebody signed out.
ISSUE_BUDGET = 4
ISSUE_WINDOW_HOURS = 1

#: A cap on how much text is treated as a token at all, so a megabyte pasted into the address bar is
#: refused before any HMAC is computed over it. cxa-cms's number.
MAX_TOKEN_LENGTH = 1024

#: The screen a link points at. Changing this changes every link already in somebody's hands.
SET_PASSWORD_PATH = "/set-password"

#: The shortest password this product will store. Matches ``LoginRequest.password``'s own floor and
#: ``UserCreate.password``'s, so a password that can be set can always be used to sign in.
MIN_PASSWORD_LENGTH = 8


def ttl_hours(purpose: str) -> int:
    return INVITE_TTL_HOURS if purpose == INVITE else RESET_TTL_HOURS


# --------------------------------------------------------------------------------------
# The token
# --------------------------------------------------------------------------------------


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _unb64(raw: str) -> bytes:
    return base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))


def credential_fingerprint(password_hash: str | None) -> str:
    """A short digest of "what the password is now".

    ⚠ THE HASH IS NEVER PUT IN THE TOKEN — only 16 hex characters of a SHA-256 of it, which is not
    enough to attack and is enough to notice a change. A row with no password digests a fixed
    sentinel, so an invitation is bound to "still has no password".
    """
    return hashlib.sha256((password_hash or "no-password").encode("utf-8")).hexdigest()[:16]


def fingerprint_matches(password_hash: str | None, presented: str) -> bool:
    """Constant-time comparison.

    Not because a timing attack is reachable here — a caller only gets this far by presenting a
    VALID signature, which needs the signing key — but because a credential comparison written the
    short way is the one that gets copied somewhere it does matter.
    """
    return hmac.compare_digest(credential_fingerprint(password_hash), presented)


def _sign(body: str) -> str:
    secret = get_settings().jwt_secret.encode("utf-8")
    return _b64(hmac.new(secret, body.encode("ascii"), hashlib.sha256).digest())


def mint_token(*, user_id: str, purpose: str, expires_at: datetime, password_hash: str | None) -> str:
    payload = {
        # Bumped if the shape ever changes, so an old token is refused rather than misread.
        "v": 1,
        "sub": user_id,
        "purpose": purpose,
        "exp": int(expires_at.timestamp()),
        "cred": credential_fingerprint(password_hash),
        # ── THE NONCE, AND THE BUG IT CLOSES ──────────────────────────────────────────────────
        #
        # Every other claim is a FUNCTION OF THE ACCOUNT: the id, the purpose, the fingerprint of
        # the current password, and an expiry stamped in whole seconds. So two links issued for one
        # account inside the same second were byte-for-byte identical — measured 2026-08-30, and it
        # surfaced as `UniqueViolationError` on `PasswordResetToken.tokenHash` and a 500 at
        # `POST /auth/password-links`, which is the visible half.
        #
        # THE INVISIBLE HALF IS WORSE AND IS THE REASON THIS IS A NONCE RATHER THAN A RETRY. Two
        # issues that produce one token are one credential: revoking the row an admin thinks they
        # just issued would leave the other row live, and the link in the wrong chat window would go
        # on working. cxa-cms mints the same deterministic shape and never noticed, because it keeps
        # no rows and therefore has nothing that has to be one-to-one with a link.
        #
        # 96 bits of `secrets` output. It is not a secret in its own right — the HMAC is what makes
        # the token unforgeable — it exists so that two mints are two tokens.
        "n": secrets.token_urlsafe(12),
    }
    body = _b64(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    return f"{body}.{_sign(body)}"


def token_digest(token: str) -> str:
    """What the table stores. A table of live credentials is worth stealing; a table of digests is
    not, and a redemption can still find its row in one indexed read."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


#: Why a token was refused. ``malformed`` deliberately covers a FORGED signature as well as a
#: mangled one: the two are indistinguishable to a reader — both mean "this is not a link this site
#: issued" — and separating them would tell somebody probing the endpoint which half of their guess
#: was wrong.
MISSING = "missing"
MALFORMED = "malformed"
EXPIRED = "expired"
REVOKED = "revoked"
SPENT = "spent"
UNKNOWN_ACCOUNT = "unknown-account"


@dataclass(frozen=True)
class TokenVerdict:
    ok: bool
    reason: str | None = None
    user_id: str | None = None
    purpose: str | None = None


def verify_token(raw: str | None, *, now: datetime | None = None) -> TokenVerdict:
    """Signature, shape and expiry. **It does not check the fingerprint** — that needs the account's
    current hash and this function reads no database.

    :func:`redeem` is the only caller and it follows a good verdict with the fingerprint check and
    the row check. A caller that skips the fingerprint has built a link that works for ever.

    THE ORDER IS DELIBERATE: signature first, then shape, then expiry. Everything after the
    signature is only reachable by somebody who already holds a link this installation issued, so
    the more specific answers below cannot be used to learn anything.
    """
    moment = now or datetime.now(UTC)
    if not isinstance(raw, str):
        return TokenVerdict(False, MISSING)
    token = raw.strip()
    if not token:
        return TokenVerdict(False, MISSING)
    if len(token) > MAX_TOKEN_LENGTH:
        return TokenVerdict(False, MALFORMED)

    # EXACTLY ONE separator. A token with two would let the body be chosen after the signature was
    # computed over a prefix of it.
    if token.count(".") != 1:
        return TokenVerdict(False, MALFORMED)
    body, presented = token.split(".", 1)
    if not body or not presented:
        return TokenVerdict(False, MALFORMED)
    if not hmac.compare_digest(presented, _sign(body)):
        return TokenVerdict(False, MALFORMED)

    try:
        decoded = json.loads(_unb64(body).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return TokenVerdict(False, MALFORMED)

    # The signature proves the bytes came from this installation; it does not prove they parse into
    # what this version of the code expects.
    if not isinstance(decoded, dict) or decoded.get("v") != 1:
        return TokenVerdict(False, MALFORMED)
    subject = decoded.get("sub")
    purpose = decoded.get("purpose")
    expiry = decoded.get("exp")
    cred = decoded.get("cred")
    if not isinstance(subject, str) or not 0 < len(subject) <= 64:
        return TokenVerdict(False, MALFORMED)
    if purpose not in (INVITE, RESET):
        return TokenVerdict(False, MALFORMED)
    if not isinstance(expiry, int):
        return TokenVerdict(False, MALFORMED)
    if not isinstance(cred, str) or len(cred) != 16:
        return TokenVerdict(False, MALFORMED)
    # Bounded rather than merely present: the signature proves the bytes came from this
    # installation, not that they parse into what this version expects, and an unbounded string here
    # would be a field a future bug could grow without anything noticing.
    nonce = decoded.get("n")
    if not isinstance(nonce, str) or not 0 < len(nonce) <= 64:
        return TokenVerdict(False, MALFORMED)
    if expiry <= int(moment.timestamp()):
        return TokenVerdict(False, EXPIRED)

    return TokenVerdict(True, None, subject, purpose)


# --------------------------------------------------------------------------------------
# Delivery — the interface the owner asked for, and its one implementation
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class DeliveredLink:
    """What an administrator gets back. ``link`` is the whole credential; there is no separate
    ``token`` field, on cxa-cms's stated grounds."""

    id: str
    link: str
    expiresAt: str
    purpose: str
    #: How it reached the person. ``COPY_LINK`` means "it did not — you copy it and hand it over",
    #: which is what the clients print. A future SES transport answers ``EMAIL`` and the same screen
    #: stops showing the box.
    deliveredBy: str


class CredentialDelivery(Protocol):
    """How a minted link reaches the person it is for.

    ONE METHOD, AND IT RETURNS THE ANSWER RATHER THAN RAISING ON THE COMMON CASE, because the
    caller has already written the row: a transport failure must not leave a live credential with
    nothing recording it.
    """

    name: str

    async def deliver(self, *, user: Any, link: str, purpose: str, expires_at: datetime) -> str:
        """Return the ``deliveredBy`` value the clients render."""


class CopyLinkDelivery:
    """THE ONE SHIPPED TODAY. It sends nothing and says so.

    The administrator copies the link out of the screen and hands it over by whatever channel they
    already use. That is the owner's decision, taken 2026-08-30, and it is why no mail dependency
    was added to ``pyproject.toml``.
    """

    name = "COPY_LINK"

    async def deliver(self, *, user: Any, link: str, purpose: str, expires_at: datetime) -> str:
        # At INFO and WITHOUT THE LINK. The link is a credential; the fact that one was issued is
        # an operational event worth having in a log, and the credential itself is not.
        logger.info(
            "auth: %s link issued for %s, expires %s (delivery: copy-link)",
            purpose,
            getattr(user, "email", "?"),
            expires_at.isoformat(),
        )
        return self.name


def delivery() -> CredentialDelivery:
    """The configured transport. One implementation today; a settings branch tomorrow."""
    return CopyLinkDelivery()


# --------------------------------------------------------------------------------------
# Issuing, revoking and redeeming
# --------------------------------------------------------------------------------------


def link_for(token: str) -> str:
    base = str(get_settings().next_public_app_url).rstrip("/")
    from urllib.parse import quote

    return f"{base}{SET_PASSWORD_PATH}?token={quote(token, safe='')}"


class IssueThrottled(Exception):
    """Raised when :data:`ISSUE_BUDGET` links have already been minted for one account."""

    def __init__(self, retry_after_minutes: int) -> None:
        super().__init__("Too many password links issued for this account")
        self.retry_after_minutes = retry_after_minutes


async def issue_link(
    *, user: Any, purpose: str | None = None, issued_by_id: str | None = None
) -> DeliveredLink:
    """Mint, record, deliver.

    ``purpose`` defaults to INVITE for an account that has never had a password and RESET for one
    that has — which is the only difference a reader can see, and it decides the lifetime.

    The hash is read off the row the caller just loaded: binding the token to a stale hash would
    mint a link that was already spent, and binding it to none would mint one that stays valid after
    a password is set.
    """
    now = datetime.now(UTC)
    since = now - timedelta(hours=ISSUE_WINDOW_HOURS)
    recent = await db.passwordresettoken.count(
        where={"userId": user.id, "createdAt": {"gte": since}}
    )
    if recent >= ISSUE_BUDGET:
        raise IssueThrottled(retry_after_minutes=ISSUE_WINDOW_HOURS * 60)

    kind = purpose or (INVITE if getattr(user, "passwordHash", None) is None else RESET)
    expires_at = now + timedelta(hours=ttl_hours(kind))
    token = mint_token(
        user_id=user.id,
        purpose=kind,
        expires_at=expires_at,
        password_hash=getattr(user, "passwordHash", None),
    )
    row = await db.passwordresettoken.create(
        data={
            "userId": user.id,
            "tokenHash": token_digest(token),
            "purpose": kind,
            "expiresAt": expires_at,
            "issuedById": issued_by_id,
        }
    )
    delivered = await delivery().deliver(
        user=user, link=link_for(token), purpose=kind, expires_at=expires_at
    )
    return DeliveredLink(
        id=row.id,
        link=link_for(token),
        expiresAt=expires_at.isoformat(),
        purpose=kind,
        deliveredBy=delivered,
    )


async def revoke_link(row_id: str) -> bool:
    """Withdraw an outstanding link. Returns False when there was nothing outstanding to withdraw.

    Idempotent on a row already revoked or already used: an admin pressing the button twice has not
    made a mistake, and answering the second press with an error would suggest they had.
    """
    row = await db.passwordresettoken.find_unique(where={"id": row_id})
    if row is None:
        return False
    if row.revokedAt is not None or row.usedAt is not None:
        return True
    await db.passwordresettoken.update(
        where={"id": row_id}, data={"revokedAt": datetime.now(UTC)}
    )
    return True


async def describe_token(raw: str | None) -> TokenVerdict:
    """Everything :func:`verify_token` checks, PLUS the row and the fingerprint.

    The set-password screen calls this before drawing a password box, so somebody holding a dead
    link is told why instead of typing a new password into a form that will refuse it.
    """
    verdict = verify_token(raw)
    if not verdict.ok:
        return verdict
    row = await db.passwordresettoken.find_unique(where={"tokenHash": token_digest(str(raw).strip())})
    if row is None:
        # A signature that verifies with no row behind it means the row was deleted with the
        # account. Answering "malformed" would be a lie; the account is what is gone.
        return TokenVerdict(False, UNKNOWN_ACCOUNT)
    if row.revokedAt is not None:
        return TokenVerdict(False, REVOKED)
    if row.usedAt is not None:
        return TokenVerdict(False, SPENT)
    user = await db.user.find_unique(where={"id": verdict.user_id})
    if user is None:
        return TokenVerdict(False, UNKNOWN_ACCOUNT)
    # THE FINGERPRINT, LAST AND NEVER SKIPPED. Everything above can be satisfied by a link that has
    # already been used through some other row; this is what cannot.
    token_payload = json.loads(_unb64(str(raw).strip().split(".", 1)[0]).decode("utf-8"))
    if not fingerprint_matches(user.passwordHash, str(token_payload.get("cred"))):
        return TokenVerdict(False, SPENT)
    return TokenVerdict(True, None, user.id, verdict.purpose)


async def mark_used(raw: str) -> None:
    """Stamp the row for the link just redeemed. Belt to the fingerprint's braces — see the model."""
    digest = token_digest(raw.strip())
    row = await db.passwordresettoken.find_unique(where={"tokenHash": digest})
    if row is not None and row.usedAt is None:
        await db.passwordresettoken.update(
            where={"id": row.id}, data={"usedAt": datetime.now(UTC)}
        )
