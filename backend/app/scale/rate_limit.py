import hashlib
import hmac
import ipaddress
import logging
import time
from collections import OrderedDict
from typing import Any

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.scale import keys
from app.scale.cache import shared_window_hit
from app.scale.flags import rate_limit_enabled, settings

logger = logging.getLogger(__name__)

_MAX_TRACKED_IDENTITIES = 4096

_EXEMPT_PREFIXES = ("/health",)

_CREDENTIAL_PREFIXES = ("/api/auth/login", "/api/auth/two-factor/verify", "/api/datasets/token")

_CREDENTIAL_FAILURES = 20
_CREDENTIAL_WINDOW_SECONDS = 300.0

_CREDENTIAL_REFUSED_STATUS = 401

_GENERIC_REFUSAL = (
    "Too many requests. Wait a moment and try again."
)
_CREDENTIAL_REFUSAL = (
    "Too many failed sign-in attempts from this network. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)


class _TokenBuckets:
    def __init__(self, *, capacity: float, refill_per_second: float) -> None:
        self._capacity = max(1.0, capacity)
        self._refill = max(0.001, refill_per_second)
        self._buckets: OrderedDict[str, tuple[float, float]] = OrderedDict()

    def take(self, identity: str) -> tuple[bool, float]:
        now = time.monotonic()
        tokens, last = self._buckets.get(identity, (self._capacity, now))
        tokens = min(self._capacity, tokens + (now - last) * self._refill)
        if tokens < 1.0:
            self._buckets[identity] = (tokens, now)
            self._buckets.move_to_end(identity)
            return False, (1.0 - tokens) / self._refill
        self._buckets[identity] = (tokens - 1.0, now)
        self._buckets.move_to_end(identity)
        while len(self._buckets) > _MAX_TRACKED_IDENTITIES:
            self._buckets.popitem(last=False)
        return True, 0.0

    def refund(self, identity: str) -> None:
        entry = self._buckets.get(identity)
        if entry is None:
            return
        tokens, last = entry
        self._buckets[identity] = (min(self._capacity, tokens + 1.0), last)

    def tracked(self) -> int:
        return len(self._buckets)


WEB_PROXY_SECRET_HEADER = b"x-web-proxy-secret"
VIEWER_ADDRESS_HEADER = b"x-viewer-address"


def trusted_viewer_address(scope: Scope) -> str | None:
    from app.core.config import get_settings

    secret = (get_settings().web_proxy_secret or "").strip()
    if not secret:
        return None
    sent = None
    viewer = None
    for name, value in scope.get("headers", []):
        if name == WEB_PROXY_SECRET_HEADER and sent is None:
            sent = value
        elif name == VIEWER_ADDRESS_HEADER and viewer is None:
            viewer = value
    if sent is None or viewer is None:
        return None
    if not hmac.compare_digest(sent.strip(), secret.encode("utf-8")):
        return None
    try:
        return str(ipaddress.ip_address(viewer.strip().decode("latin-1")))
    except ValueError:
        return None


def _bearer_subject(token: bytes) -> str | None:
    from app.core.security import decode_access_token

    try:
        claims = decode_access_token(token.decode("latin-1"))
    except (ValueError, UnicodeDecodeError):
        return None
    subject = claims.get("sub")
    return str(subject) if subject else None


def _identity(scope: Scope, *, ignore_bearer: bool = False) -> str:
    if not ignore_bearer:
        for name, value in scope.get("headers", []):
            if name != b"authorization":
                continue
            token = value.strip()
            if token[:7].lower() == b"bearer " and len(token) > 7:
                subject = _bearer_subject(token[7:].strip())
                if subject:
                    return "u:" + hashlib.sha256(subject.encode("utf-8")).hexdigest()[:16]
            break
    return client_address(scope)


_ACCOUNT_FAILURES = 10
_ACCOUNT_WINDOW_SECONDS = 300.0

_account_buckets = _TokenBuckets(
    capacity=_ACCOUNT_FAILURES,
    refill_per_second=_ACCOUNT_FAILURES / _ACCOUNT_WINDOW_SECONDS,
)


def account_credential_attempt(account_id: str) -> tuple[bool, float]:
    return _account_buckets.take(f"acct:{account_id}")


def address_credential_attempt(identifier_key: str) -> tuple[bool, float]:
    return _account_buckets.take(f"addr:{identifier_key}")


def account_credential_refund(account_id: str) -> None:
    _account_buckets.refund(f"acct:{account_id}")


SOURCE_CREDENTIAL_FAILURES = 30
SOURCE_CREDENTIAL_WINDOW_SECONDS = 300.0

_source_buckets = _TokenBuckets(
    capacity=SOURCE_CREDENTIAL_FAILURES,
    refill_per_second=SOURCE_CREDENTIAL_FAILURES / SOURCE_CREDENTIAL_WINDOW_SECONDS,
)


def source_credential_attempt(scope: Scope) -> tuple[bool, float]:
    return _source_buckets.take(f"src:{client_address(scope)}")


def source_credential_refund(scope: Scope) -> None:
    _source_buckets.refund(f"src:{client_address(scope)}")


FORGOT_PASSWORD_REQUESTS = 5
FORGOT_PASSWORD_WINDOW_SECONDS = 900.0

_forgot_password_buckets = _TokenBuckets(
    capacity=FORGOT_PASSWORD_REQUESTS,
    refill_per_second=FORGOT_PASSWORD_REQUESTS / FORGOT_PASSWORD_WINDOW_SECONDS,
)


FORGOT_PASSWORD_GLOBAL_PER_MINUTE = 30

_forgot_password_global = _TokenBuckets(
    capacity=FORGOT_PASSWORD_GLOBAL_PER_MINUTE,
    refill_per_second=FORGOT_PASSWORD_GLOBAL_PER_MINUTE / 60.0,
)


def _forwarded_entries(scope: Scope) -> list[str]:
    entries: list[str] = []
    for name, value in scope.get("headers", []):
        if name == b"x-forwarded-for":
            entries.extend(
                part.strip().decode("latin-1") for part in value.split(b",") if part.strip()
            )
    return entries


_hop_mismatch_logged = False


def _note_hop_mismatch(scope: Scope, hops: int, entries: list[str]) -> None:
    global _hop_mismatch_logged
    if _hop_mismatch_logged or hops < 2 or not entries:
        return
    if any(name == b"x-amz-cf-id" for name, _value in scope.get("headers", [])):
        return
    _hop_mismatch_logged = True
    logger.warning(
        "rate limits: FORWARDED_CLIENT_HOPS=%d but a forwarded request arrived without passing "
        "through CloudFront; per-address limits may be keyed on a caller-supplied address. Lock the "
        "origin to CloudFront, or set FORWARDED_CLIENT_HOPS=1 on a box with only nginx in front.",
        hops,
    )


def client_address(scope: Scope) -> str:
    from app.core.config import get_settings

    viewer = trusted_viewer_address(scope)
    if viewer:
        return "ip:" + viewer
    hops = max(1, int(get_settings().forwarded_client_hops or 1))
    entries = _forwarded_entries(scope)
    _note_hop_mismatch(scope, hops, entries)
    if len(entries) >= hops:
        return "ip:" + entries[-hops]
    client = scope.get("client")
    return f"ip:{client[0]}" if client else "ip:unknown"


def forgot_password_attempt(scope: Scope) -> tuple[bool, float]:
    allowed, retry = _forgot_password_buckets.take(f"forgot:{client_address(scope)}")
    if not allowed:
        return allowed, retry
    return _forgot_password_global.take("forgot:all")


FIRST_PASSWORD_CODE_ATTEMPTS = 5
FIRST_PASSWORD_CODE_WINDOW_SECONDS = 900.0

_first_password_code_buckets = _TokenBuckets(
    capacity=FIRST_PASSWORD_CODE_ATTEMPTS,
    refill_per_second=FIRST_PASSWORD_CODE_ATTEMPTS / FIRST_PASSWORD_CODE_WINDOW_SECONDS,
)


def first_password_code_attempt(account_id: str) -> tuple[bool, float]:
    return _first_password_code_buckets.take(f"first-password:{account_id}")


def first_password_code_refund(account_id: str) -> None:
    _first_password_code_buckets.refund(f"first-password:{account_id}")


def reset_account_credential_budget() -> None:
    _account_buckets._buckets.clear()  # noqa: SLF001 - the module owns this object
    _forgot_password_buckets._buckets.clear()  # noqa: SLF001 - and this one
    _forgot_password_global._buckets.clear()  # noqa: SLF001 - and the process-wide one
    _first_password_code_buckets._buckets.clear()  # noqa: SLF001 - and the first-password one
    _source_buckets._buckets.clear()  # noqa: SLF001 - and the per-network one


class RateLimitMiddleware:
    def __init__(
        self,
        app: ASGIApp,
        *,
        requests: int,
        window_seconds: float,
        credential_failures: int = _CREDENTIAL_FAILURES,
        credential_window_seconds: float = _CREDENTIAL_WINDOW_SECONDS,
    ) -> None:
        self.app = app
        self.limit = max(1, requests)
        self.window = max(1.0, window_seconds)
        self._buckets = _TokenBuckets(
            capacity=self.limit, refill_per_second=self.limit / self.window
        )
        self.credential_limit = max(1, credential_failures)
        self.credential_window = max(1.0, credential_window_seconds)
        self._credential_buckets = _TokenBuckets(
            capacity=self.credential_limit,
            refill_per_second=self.credential_limit / self.credential_window,
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") == "OPTIONS":
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        if path.startswith(_EXEMPT_PREFIXES):
            await self.app(scope, receive, send)
            return

        credential = path.startswith(_CREDENTIAL_PREFIXES)
        identity = _identity(scope, ignore_bearer=credential)
        count = await shared_window_hit(keys.rate_limit_key(identity), self.window)
        if count is None:
            allowed, retry_after = self._buckets.take(identity)
        else:
            allowed = count <= self.limit
            retry_after = self.window

        if not allowed:
            await self._refuse(
                scope,
                receive,
                send,
                limit=self.limit,
                retry_after=retry_after,
                detail=_GENERIC_REFUSAL,
            )
            return

        if not credential:
            await self.app(scope, receive, send)
            return

        await self._credential_attempt(scope, receive, send, identity)

    async def _credential_attempt(
        self, scope: Scope, receive: Receive, send: Send, identity: str
    ) -> None:
        allowed, retry_after = self._credential_buckets.take(identity)
        if not allowed:
            await self._refuse(
                scope,
                receive,
                send,
                limit=self.credential_limit,
                retry_after=retry_after,
                detail=_CREDENTIAL_REFUSAL,
            )
            return

        settled = False

        async def refund_unless_refused(message: Message) -> None:
            nonlocal settled
            if message["type"] == "http.response.start" and not settled:
                settled = True
                if message.get("status") != _CREDENTIAL_REFUSED_STATUS:
                    self._credential_buckets.refund(identity)
            await send(message)

        await self.app(scope, receive, refund_unless_refused)

    async def _refuse(
        self,
        scope: Scope,
        receive: Receive,
        send: Send,
        *,
        limit: int,
        retry_after: float,
        detail: str,
    ) -> None:
        seconds = int(retry_after) + 1
        response = JSONResponse(
            status_code=429,
            content={"detail": detail, "retryAfterSeconds": seconds},
            headers={
                "retry-after": str(seconds),
                "x-ratelimit-limit": str(limit),
                "x-ratelimit-remaining": "0",
                "cache-control": "no-store",
            },
        )
        await response(scope, receive, send)


def install_rate_limit(app: Any) -> bool:
    if not rate_limit_enabled():
        return False
    current = settings()
    app.add_middleware(
        RateLimitMiddleware,
        requests=current.scale_rate_limit_requests,
        window_seconds=current.scale_rate_limit_window_seconds,
    )
    return True
