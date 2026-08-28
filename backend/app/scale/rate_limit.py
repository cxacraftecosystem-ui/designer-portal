"""Request rate limiting. Flag-gated, and when the flag is off the middleware is never added.

"NOT INSTALLED" IS THE POINT. A disabled middleware that checks a boolean and calls the next app is
still a coroutine, a stack frame and a wrapper around every request the server ever handles,
forever, for nothing. ``install_rate_limit`` returns without touching the app when the flag is off,
so the disabled configuration has the middleware stack it has today — one fewer layer, not one more
that does nothing.

WHAT THIS IS FOR, AND WHAT IT IS NOT. It is a courtesy backstop for a 1 GiB box: a phone stuck in a
sync loop, a script left running overnight, an accidental ``while true`` against ``/search`` (8.9s
per call — twenty of those in parallel is the whole machine). It is NOT a security control. The
identity it limits by is derived from headers the origin cannot fully verify, and an attacker who
can reach the origin directly can vary them freely. Abuse defence belongs at CloudFront/WAF, where
the traffic can be dropped before it costs anything. Writing that down here so nobody later mistakes
a 429 in the logs for a boundary that is holding.

IDENTITY. A bearer token, when present, is hashed and used as the key, so one signed-in user gets one
allowance across their phone and their laptop, and so a whole office behind one NAT is not one
bucket. Anonymous callers fall back to the client IP. The token is never stored or logged — only a
truncated digest of it, which cannot be replayed.

THE CREDENTIAL DOORS ARE A SECOND, MUCH TIGHTER ALLOWANCE, AND THEY ARE COUNTED DIFFERENTLY.
``POST /api/auth/login`` and ``POST /api/datasets/token`` both turn an email and a password into a
token, and both answer 401 for "no such account or wrong password". The general allowance is far too
loose to call brute-force protection — at the shipped default of 120 requests a minute it would
still permit 172,800 password guesses a day — so those two paths carry their own budget of
FAILED ATTEMPTS on top of it. Two properties make that budget safe to set as low as it is:

* **It charges failures, not attempts.** A token is taken up front (so a burst of parallel guesses
  cannot all slip through the same check) and given straight back unless the response is a 401.
  A designer who knows their password can sign in as often as they like and never spend anything,
  which is what stops a whole village office — or a carrier-grade NAT shared by thousands of
  subscribers — from locking itself out of an app they are typing the right password into. A 403
  ("awaiting approval", "suspended") is refunded too: that answer is reached only AFTER the bcrypt
  check has passed, so charging it would punish exactly the designer who is waiting on an admin.
* **It is keyed by ADDRESS ONLY, never by the bearer token**, unlike everything else here. An
  attacker who was charged per Authorization header would simply send a fresh random one with every
  guess and mint themselves an unlimited number of empty buckets.

Neither property makes this a boundary either. It is a speed limit on guessing, sitting in front of
bcrypt; the real defence is still the password's own entropy and, above this box, the WAF.
"""

import hashlib
import time
from collections import OrderedDict
from typing import Any

from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.scale import keys
from app.scale.cache import shared_window_hit
from app.scale.flags import rate_limit_enabled, settings

# Distinct callers tracked in-process. Each entry is a key string and two floats; 4096 of them is a
# few hundred kilobytes. The bound is what stops a caller who varies their identity every request
# from turning the limiter into a memory leak — the very shape of abuse it exists to survive.
_MAX_TRACKED_IDENTITIES = 4096

# Never limited. Health probes come from CloudFront and an uptime monitor on a fixed cadence, and a
# 429 to either reads as an outage. OPTIONS is exempt at the method level below: a rate-limited CORS
# preflight breaks the web app completely — the browser reports a CORS failure, not a 429, and no
# request that would have been within the limit ever gets sent.
_EXEMPT_PREFIXES = ("/health",)

# The two doors that turn a password into a token. Prefix-matched, like the exemptions above, which
# also covers the trailing-slash form Starlette answers with a 307 before the route ever runs.
#
# THIS LIST IS THE FEATURE. Adding a third credential endpoint without adding it here gives that
# endpoint the general 120-a-minute allowance and nothing else, and nothing will say so.
# `/api/design-workshop-access/...` grant redemption is deliberately NOT here: its refusal rests on
# 110 bits of `secrets` output, and `services/design_workshop_grants.py` argues at length that
# it must keep resting on the entropy alone rather than on a limiter. Listing it would invite the
# next reader to weaken that argument.
_CREDENTIAL_PREFIXES = ("/api/auth/login", "/api/datasets/token")

# Failed sign-ins allowed per address before the door closes, and the window they refill over.
#
# WHERE 20 AND 300 COME FROM. The budget refills continuously, so once it is spent an attacker gets
# one further guess every 15 seconds — 4 a minute, 5,760 a day, against a bcrypt hash. That is the
# number that matters and it is negligible; anything faster is not. Twenty rather than five because
# the key is an ADDRESS and this product's designers work from village offices and mobile networks,
# where dozens of people share one public IP: five would be an afternoon's worth of ordinary typos
# for a whole office. The refund above is what lets the ceiling be this low at all — only WRONG
# passwords are counted, so the number to compare against is "how many typos does one address
# produce in five minutes", not "how many sign-ins".
#
# Constants rather than SCALE_* settings on purpose. Every other knob in this package answers "is
# this optional layer on"; this one is part of the limiter itself and is meaningless without it, and
# two more environment variables would be two more things a deployment could set to a number nobody
# reasoned about. A backend change ships in one push — change them here, in the diff, with a reason.
_CREDENTIAL_FAILURES = 20
_CREDENTIAL_WINDOW_SECONDS = 300.0

# The only status that spends a credential token. Everything else — a 200, a 403 from the admission
# gate, a 422 from a malformed body, a 500 — is refunded. See the module docstring.
_CREDENTIAL_REFUSED_STATUS = 401

_GENERIC_REFUSAL = (
    "Too many requests. Wait a moment and try again — this limit exists to keep "
    "the server responsive for everyone."
)
# Says out loud that a correct password costs nothing, because it is true (the refund) and because
# the person most likely to read this sentence is a designer who mistyped, not an attacker.
_CREDENTIAL_REFUSAL = (
    "Too many failed sign-in attempts from this network. Wait a few minutes and try again — "
    "a sign-in that succeeds does not count against this limit."
)


class _TokenBuckets:
    """One token bucket per identity, bounded in number, evicting the least recently seen.

    A token bucket rather than a fixed window because it allows a short burst — opening the
    dashboard fires several requests at once, and that is normal behaviour, not abuse — while still
    capping the sustained rate. No lock: every caller is the event loop.
    """

    def __init__(self, *, capacity: float, refill_per_second: float) -> None:
        self._capacity = max(1.0, capacity)
        self._refill = max(0.001, refill_per_second)
        self._buckets: OrderedDict[str, tuple[float, float]] = OrderedDict()

    def take(self, identity: str) -> tuple[bool, float]:
        """Spend one token. Returns ``(allowed, retry_after_seconds)``."""
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
        """Give one token back to a bucket that has already spent it.

        Only the credential path uses this, and only for a request the server did not answer with a
        401 — see the module docstring for why "charge the attempt, refund everything that was not a
        wrong password" is the shape rather than "charge only the failures". The check and the
        charge would otherwise be two awaits apart, and a hundred parallel guesses would all pass
        the check before any of them was counted.

        A bucket that has been EVICTED is not resurrected: an identity that fell out of the bounded
        table has no debt to forgive, and re-inserting it here would let a refund grow the table the
        eviction in ``take`` exists to bound. The timestamp is left alone deliberately — moving it
        would silently forfeit the refill accrued since the take.
        """
        entry = self._buckets.get(identity)
        if entry is None:
            return
        tokens, last = entry
        self._buckets[identity] = (min(self._capacity, tokens + 1.0), last)

    def tracked(self) -> int:
        return len(self._buckets)


def _identity(scope: Scope, *, ignore_bearer: bool = False) -> str:
    """Who to charge this request to: the bearer token's digest, else the client address.

    The forwarded address is read left-most-first because that is where the original client sits in
    an ``X-Forwarded-For`` chain (browser -> CloudFront -> nginx -> here). It is also the only entry
    a client can set themselves, which is fine for a courtesy limit and would not be for anything
    else — see the module docstring.

    ``ignore_bearer`` is what the credential paths pass, and it is not a tidiness flag: an
    Authorization header on a sign-in request is attacker-controlled input, so honouring it there
    would let one guess per randomly generated header sail through a fresh, full bucket.
    """
    forwarded = b""
    for name, value in scope.get("headers", []):
        if name == b"authorization" and not ignore_bearer:
            token = value.strip()
            if token[:7].lower() == b"bearer " and len(token) > 7:
                return "t:" + hashlib.sha256(token[7:]).hexdigest()[:16]
        elif name == b"x-forwarded-for" and not forwarded:
            forwarded = value
    if forwarded:
        first = forwarded.split(b",")[0].strip()
        if first:
            return "ip:" + first.decode("latin-1")
    client = scope.get("client")
    return f"ip:{client[0]}" if client else "ip:unknown"


class RateLimitMiddleware:
    """Pure-ASGI token-bucket limiter. Installed only by ``install_rate_limit``.

    Written against raw ASGI, like the other middleware in this app, so it adds no task group and no
    body buffering — it either passes the request straight through or answers it, and for every
    request but a sign-in it does nothing to the response at all. The two credential paths are the
    exception: they wrap ``send`` to read the response status, because the whole point of that
    budget is that it counts refusals rather than attempts.
    """

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
        # On a credential path the GENERAL allowance is charged to the address too, not just the
        # tighter one below. Otherwise the bearer-rotation escape the credential budget was made
        # immune to would still be open on the general bucket, and an attacker could hammer the
        # sign-in route with requests that never reach a 401 (a malformed body, a Google token) for
        # nothing.
        identity = _identity(scope, ignore_bearer=credential)
        # A shared counter when one exists, so two processes cannot each grant the full allowance.
        # None means there is no shared store (see cache.shared_window_hit) and the in-process
        # bucket is both the correct and the only answer.
        count = await shared_window_hit(keys.rate_limit_key(identity), self.window)
        if count is None:
            allowed, retry_after = self._buckets.take(identity)
        else:
            allowed = count <= self.limit
            retry_after = self.window

        if not allowed:
            await self._refuse(
                scope, receive, send, limit=self.limit, retry_after=retry_after,
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
        """Run a sign-in through the failure budget: take a token, refund all but a 401.

        DELIBERATELY IN-PROCESS, with no ``shared_window_hit`` call, and that is not an oversight.
        A shared window is a Redis ``INCR``, which has no honest refund — decrementing it would race
        every other process's expiry and could hand back a token the window had already retired. The
        deployment runs ONE uvicorn worker per box on purpose (``infra/terraform/user_data.sh`` and
        ``infra/k8s/base/deployment-api.yaml`` both say so, at length), so the in-process bucket IS
        the whole deployment's bucket and there is nothing to share. If that ever stops being true,
        the fix is a Lua script that increments only on a 401 — not a decrement.

        A client that disconnects before the response starts leaves its token spent. That is the
        safe direction to fail and it is unreachable from a scripted attack, which has no reason to
        hang up on the answer it came for.
        """
        allowed, retry_after = self._credential_buckets.take(identity)
        if not allowed:
            await self._refuse(
                scope, receive, send, limit=self.credential_limit, retry_after=retry_after,
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
                # A 429 is about this instant, not about this URL. Anything that cached it would go
                # on serving the refusal after the allowance had refilled.
                "cache-control": "no-store",
            },
        )
        await response(scope, receive, send)


def install_rate_limit(app: Any) -> bool:
    """Add the limiter to ``app`` if it is enabled. Returns whether it was installed.

    CALLED FROM ``app.main.create_app``, immediately AFTER ``app.add_middleware(
    UnhandledErrorMiddleware)`` and BEFORE ``app.add_middleware(CORSMiddleware, ...)``. It was
    dormant until 2026-08-27 — the package was complete and the settings existed, but nothing ever
    called this function, so ``SCALE_RATE_LIMIT_ENABLED=true`` did nothing at all.
    (Check: ``grep -n install_rate_limit backend/app/main.py``.)

    That position is load-bearing, not cosmetic. Starlette runs the most recently added middleware
    outermost, so adding it before CORS puts the limiter INSIDE the CORS layer, and a 429 travels
    back out through CORS and picks up ``access-control-allow-origin``. Installed outside CORS, the
    same 429 reaches the browser without that header, the fetch rejects, and the web app reports
    "Failed to fetch" — the exact confusion ``UnhandledErrorMiddleware`` was written to end. It also
    sits outside the router, so a refused request costs no route resolution and no database work.
    ``tests/test_rate_limit_install.py`` pins both halves of that.
    """
    if not rate_limit_enabled():
        return False
    current = settings()
    app.add_middleware(
        RateLimitMiddleware,
        requests=current.scale_rate_limit_requests,
        window_seconds=current.scale_rate_limit_window_seconds,
    )
    return True
