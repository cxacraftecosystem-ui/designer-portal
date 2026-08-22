import asyncio
import gzip
import logging
import os
import tempfile
import time
from contextlib import asynccontextmanager, suppress
from typing import Any

from fastapi import FastAPI, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api.router import api_router
from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.security import verify_jwt_configuration
from app.services.media_queue import process_next_media_jobs

logger = logging.getLogger(__name__)

# A single, host-wide lock file used to elect ONE media-queue worker across all uvicorn worker
# processes. The transcription/measurement jobs run ffmpeg + AI calls and read whole media files into
# memory; letting every uvicorn worker drain the queue in parallel saturated the small EC2 box's CPU
# and RAM, which made ordinary API requests (presign, complete, …) slow enough that CloudFront's
# origin-response timeout fired and clients saw HTTP 504. Electing one worker keeps the others free to
# serve requests promptly.
_QUEUE_LOCK_PATH = os.path.join(tempfile.gettempdir(), "design-workshop-media-queue.lock")


def _acquire_queue_worker_lock() -> Any | None:
    """Try to become THE media-queue worker for this host. Returns a held lock handle on success, or
    None if another process already holds it. Uses an OS advisory file lock (fcntl) where available;
    on platforms without fcntl (e.g. local Windows dev, which runs a single worker anyway) it simply
    grants the lock so the queue still runs."""
    try:
        import fcntl  # POSIX only (the EC2 host); absent on Windows dev boxes.
    except ImportError:
        return object()  # No multi-worker contention to arbitrate — run the queue here.
    try:
        handle = open(_QUEUE_LOCK_PATH, "w")  # noqa: SIM115 - kept open for the process lifetime
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        handle.write(str(os.getpid()))
        handle.flush()
        return handle
    except OSError:
        return None


# How often the watchdog probes a healthy connection. Cheap (one SELECT 1 on the existing database
# link) and fast enough that a dead connection is noticed well before users hit repeated 500s.
_DB_PROBE_INTERVAL_SECONDS = 15.0


async def _keep_db_connected() -> None:
    """Background DB watchdog: probe the connection forever, and reconnect whenever it is broken.

    It runs for the app's WHOLE lifetime — not just after a failed startup connect — because a
    connection that was healthy at startup can still die later (pooler restart, network blip), and
    without a watchdog the app would serve HTTP 500s until systemd restarted it. While the probe
    succeeds it does nothing but sleep, so the healthy path costs one SELECT 1 per interval.

    The reconnect path is the recovery for a database momentarily refusing new connections — the
    case this deployment has actually lived through is a connection pooler at its client-connection
    ceiling, whatever that ceiling happens to be (it is the pooler's number, not PostgreSQL's, and
    an earlier version of this paragraph kept the figure after deleting the vendor's name that made
    it true). It must NEVER let the process exit: systemd restarts a dead uvicorn in seconds, and
    each restart spawns fresh query-engine connections, which amplifies a brief spike into a
    self-sustaining storm that keeps the far end full (the exact failure that took the API down
    twice). Staying alive and retrying gently — one connection attempt at a time — lets the
    connections drain and the app self-heal with no restart. ``/health`` keeps returning 200
    throughout (it does not touch the DB), so the box stays a healthy CloudFront origin while it
    waits.

    Why it disconnects first and probes with ``SELECT 1``: the Prisma client keeps its engine
    reference even when ``connect()`` *raised*, so ``is_connected()`` can read ``True`` while the
    engine is actually unusable. A naive ``while not db.is_connected()`` loop would then exit
    immediately and declare success without ever reconnecting. Disconnecting clears any such
    half-initialized engine, and the probe proves the link really works before we stop retrying.
    """
    delay = 2.0
    while True:
        try:
            await db.query_raw("SELECT 1")  # prove the link works; is_connected() alone can lie
            delay = 2.0
            await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
            continue
        except Exception as exc:  # noqa: BLE001 - a failed probe MAY mean the connection is broken
            # Never tear down a live engine on a false positive. P2024 means OUR pool is
            # momentarily saturated by real load — the engine is fine; reconnecting would kill
            # every in-flight query. Anything else gets one confirming probe before the
            # destructive disconnect, so a single transient blip can't cause a teardown.
            if getattr(exc, "code", None) == "P2024":
                logger.warning("DB probe pool-timeout (P2024); pool saturated by load, not reconnecting")
                await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
                continue
            await asyncio.sleep(2.0)
            try:
                await db.query_raw("SELECT 1")
                continue  # transient blip — the connection is actually fine
            except Exception as exc2:  # noqa: BLE001
                logger.warning("DB health probe failed twice: %s — reconnecting in the background", exc2)
        try:
            with suppress(Exception):
                await db.disconnect()  # tear down any half-initialized engine before reconnecting
            await db.connect()
            await db.query_raw("SELECT 1")
            logger.info("Database connected (background reconnect succeeded)")
            delay = 2.0
        except Exception as exc:  # noqa: BLE001 - any connect failure should back off, not crash
            logger.warning("Background DB reconnect failed: %s — retrying in %.0fs", exc, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        await connect_db()
    except Exception as exc:  # noqa: BLE001 - never crash-loop on a full pooler; recover in background
        logger.error(
            "Initial DB connect failed (%s); starting anyway and reconnecting in the background "
            "so a saturated pooler cannot crash-loop the service",
            exc,
        )
    # The watchdog runs for the app's whole life (its probe only ACTS when the connection is broken):
    # it both finishes a failed startup connect and heals a connection that dies later.
    db_reconnect_task: asyncio.Task[None] = asyncio.create_task(_keep_db_connected())
    settings = get_settings()
    queue_task: asyncio.Task[None] | None = None
    queue_lock: Any | None = None
    if settings.media_queue_worker_enabled:
        queue_lock = _acquire_queue_worker_lock()
        if queue_lock is not None:
            logger.info("Media queue worker elected in pid %s", os.getpid())
            queue_task = asyncio.create_task(_media_queue_worker())
            app.state.media_queue_task = queue_task
            app.state.media_queue_lock = queue_lock
        else:
            logger.info("Media queue worker already running elsewhere; pid %s serves requests only", os.getpid())
    try:
        yield
    finally:
        if db_reconnect_task:
            db_reconnect_task.cancel()
            with suppress(asyncio.CancelledError):
                await db_reconnect_task
        if queue_task:
            queue_task.cancel()
            with suppress(asyncio.CancelledError):
                await queue_task
        if queue_lock is not None and hasattr(queue_lock, "close"):
            with suppress(Exception):
                queue_lock.close()
        await disconnect_db()


async def _media_queue_worker() -> None:
    settings = get_settings()
    interval = max(settings.media_queue_interval_seconds, 1.0)
    while True:
        try:
            await process_next_media_jobs(
                limit=settings.media_queue_batch_size,
                worker_id="fastapi-background",
                settings=settings,
            )
        except Exception:
            logger.exception("Media processing queue worker failed")
        await asyncio.sleep(interval)


# --- Security response headers ------------------------------------------------------------------
# Stamped on every response. Header names are lower-case bytes because that is exactly the shape the
# ASGI `http.response.start` message carries — no per-request encoding work.
#
# X-Content-Type-Options   stops a browser from sniffing a JSON error body into HTML/JS and running it.
# X-Frame-Options          legacy clickjacking defence (CSP frame-ancestors below is the modern one,
#                          but older browsers only understand this).
# Referrer-Policy          keeps record ids / query strings out of the Referer header sent to third
#                          parties (S3, MapTiler, Google) when a link is followed.
# Permissions-Policy       a JSON API never needs camera/mic/geolocation, so every powerful feature is
#                          denied for any document that somehow ends up scoped to this origin.
# X-Permitted-…            blocks Adobe/Flash-era cross-domain policy files being honoured on the host.
_BASE_SECURITY_HEADERS: tuple[tuple[bytes, bytes], ...] = (
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"strict-origin-when-cross-origin"),
    (
        b"permissions-policy",
        b"accelerometer=(), autoplay=(), camera=(), display-capture=(), encrypted-media=(), "
        b"fullscreen=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), midi=(), "
        b"payment=(), picture-in-picture=(), screen-wake-lock=(), usb=(), xr-spatial-tracking=()",
    ),
    (b"x-permitted-cross-domain-policies", b"none"),
)

# CSP for the API itself: it returns JSON, so nothing may load, and nothing may frame it. This is
# the header that actually protects the *browser* if a response is ever rendered as a document
# (e.g. a stored-XSS attempt inside a transcript that a browser is tricked into treating as HTML).
_API_CSP = b"default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

# /docs and /redoc ARE real HTML pages, and FastAPI loads Swagger-UI / ReDoc from jsdelivr with an
# inline bootstrap script. They get a policy that permits exactly those assets and nothing else, so
# the strict API policy above doesn't silently break the interactive documentation. ReDoc's page
# additionally pulls Montserrat/Roboto from Google Fonts (a stylesheet on fonts.googleapis.com whose
# @font-face rules fetch from fonts.gstatic.com), so both hosts are allowed for styles/fonts only —
# without them /redoc renders in fallback fonts and logs a CSP violation on every load.
_DOCS_CSP = (
    b"default-src 'none'; "
    b"script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; "
    b"style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'; "
    b"img-src 'self' https://fastapi.tiangolo.com data:; "
    b"font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com; "
    b"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
)
_DOCS_PATHS = frozenset({"/docs", "/docs/oauth2-redirect", "/redoc"})

# Proxy headers that report the protocol the *viewer* used. CloudFront sets its own
# CloudFront-Forwarded-Proto; nginx sets X-Forwarded-Proto (to its own scheme, which is why
# SECURITY_FORCE_HSTS exists for the CloudFront -> nginx shape).
_FORWARDED_PROTO_HEADERS = frozenset({b"x-forwarded-proto", b"cloudfront-forwarded-proto", b"x-forwarded-scheme"})


def _request_is_https(scope: Scope) -> bool:
    """Whether this request reached the user over TLS, directly or through a terminating proxy.

    Only used to decide whether to emit HSTS. Trusting a forwarded header is safe *for this
    purpose*: the worst a spoofed header can do is add an HSTS header to a plaintext response, and
    browsers ignore HSTS delivered over plain HTTP.
    """
    if scope.get("scheme") == "https":
        return True
    for name, value in scope.get("headers", []):
        if name in _FORWARDED_PROTO_HEADERS:
            # A chain of proxies produces "https, http" — the left-most entry is the viewer's.
            if value.decode("latin-1").split(",")[0].strip().lower() == "https":
                return True
        elif name == b"x-forwarded-ssl" and value.strip().lower() == b"on":
            return True
    return False


class UnhandledErrorMiddleware:
    """Turn any unhandled exception into a readable JSON 500 — from *inside* the CORS layer.

    THE FAILURE THIS EXISTS TO PREVENT, observed in production: approving a pending questionnaire
    raised ``FieldNotFoundError`` (the table was missing ``reviewNotes``). Starlette's built-in
    ``ServerErrorMiddleware`` caught it and returned a bare ``text/plain`` 500 — but that middleware
    sits OUTSIDE every middleware the app adds, so the response carried no
    ``access-control-allow-origin``. A browser cannot read a cross-origin response without that
    header, so the fetch simply rejected and the web UI said **"Failed to fetch"**, while Android
    (no CORS) showed the honest **HTTP 500**. One schema gap presented as a network fault on one
    client and a server fault on the other, and neither message named the real problem.

    Registering ``@app.exception_handler(Exception)`` does NOT fix that: Starlette special-cases the
    ``Exception`` key onto ``ServerErrorMiddleware``, so the response is still produced outside CORS.
    Verified — that approach yielded JSON but still no CORS header.

    So this is ASGI middleware installed BELOW ``CORSMiddleware``. Catching here means the error
    becomes an ordinary response that then travels back out through CORS and the security-header
    layer, arriving at the client fully readable.

    It never swallows anything: the traceback is logged at exception level exactly as before. Only
    the *shape* of the reply changes. ``HTTPException`` and friends are untouched — they are handled
    upstream by the router and never reach here.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        started = False

        async def wrapped_send(message: Message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, receive, wrapped_send)
        except Exception as exc:  # deliberate catch-all; re-raised when unusable
            method = scope.get("method", "?")
            path = scope.get("path", "?")
            logger.exception("Unhandled error on %s %s: %s", method, path, exc)
            if started:
                # The response is already on the wire; anything we send now would corrupt it. Let it
                # surface so the server closes the connection rather than emitting a half-response.
                raise
            payload = {
                "detail": "Something went wrong on the server. The error has been logged.",
                # The exception TYPE is safe and genuinely useful to whoever is debugging; the
                # message may carry internals, so it stays in the log only.
                "error": type(exc).__name__,
                "path": path,
            }
            response = JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=payload
            )
            await response(scope, receive, send)



def _header(scope: Scope, name: bytes) -> str:
    """One request header off a raw ASGI scope, lowercased, or "".

    Read straight from `scope["headers"]` rather than by building a `Headers` object, because this
    runs on EVERY request including the ones that will not be compressed, and the allocation is the
    only cost the pass-through path would otherwise pay.
    """
    for key, value in scope.get("headers", ()):
        if key == name:
            return value.decode("latin-1").lower()
    return ""

#: Content types worth compressing, matched against the part of `content-type` before any `;`.
#:
#: AN ALLOWLIST, NOT A DENYLIST, and that is the whole design. Starlette's own `GZipMiddleware`
#: compresses anything above its size floor, which on this API means spending CPU re-compressing
#: every generated `.docx` and `.pdf` — both already ZIP/Flate containers, both yielding roughly
#: nothing, and both produced by the one endpoint a measurement showed is ALREADY CPU-bound
#: (`report/preview`: ~780 ms in the builder against ~12 ms of query time). A denylist would also
#: have to be extended every time a new binary type is served, and the failure mode of forgetting is
#: silent waste. Adding a type here is a deliberate act; missing one costs only bytes.
_COMPRESSIBLE_TYPES = frozenset({
    "application/json",
    "application/problem+json",
    "application/javascript",
    "application/xml",
    "image/svg+xml",
    "text/css",
    "text/csv",
    "text/html",
    "text/javascript",
    "text/plain",
    "text/xml",
})

#: Below this many bytes, compression costs more than it saves — the gzip header alone is 18 bytes,
#: and a small JSON body can come out LARGER. 1 KiB is Starlette's own default and is well under the
#: smallest payload that matters here (a 20-row workshop list is ~14 KB).
_COMPRESS_MIN_BYTES = 1024


class SelectiveGZipMiddleware:
    """Compress JSON and text responses, and only those.

    WHY THIS EXISTS AT ALL. This API served no compressed responses of any kind: it ignored
    `Accept-Encoding`, and the production nginx in `infra/terraform/user_data.sh` has no `gzip`
    directive (Ubuntu's default `gzip_types` is `text/html` alone, so JSON would not have compressed
    even by accident). Measured over ten real responses: **2,548 KB -> 412 KB, 6.2x**. The workshop
    list compresses 13x, `/artisans?pageSize=100` 21.7x — that endpoint is 58% keys whose value is
    `null`, because the routes return raw Prisma rows carrying unloaded relation placeholders.

    THE NUMBER THAT JUSTIFIES IT is not the ratio, it is the link. This application is used in
    villages on a mobile connection; at 40 kB/s the same ten responses take **63.7 s uncompressed
    and 10.3 s compressed**. That is the difference between a designer working and a designer
    waiting, and no amount of query tuning reaches it — the database was measured at 2-25 ms per
    request and is not the bottleneck.

    ON BREACH. Compressing a response that mixes a secret with attacker-influenced text can leak
    the secret by length. This API is bearer-token authenticated: the token is sent in a header and
    is never present in a response body, and there is no CSRF token to steal — which is the classic
    target. The residual risk is ordinary record data, which the caller is already authorised to
    read in full. Anything genuinely secret must not be in a response body regardless of encoding.
    """

    def __init__(self, app: ASGIApp, *, minimum_size: int = _COMPRESS_MIN_BYTES) -> None:
        self.app = app
        self.minimum_size = minimum_size

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or "gzip" not in _header(scope, b"accept-encoding"):
            await self.app(scope, receive, send)
            return

        start: Message | None = None
        chunks: list[bytes] = []
        passthrough = False

        async def capture(message: Message) -> None:
            nonlocal start, passthrough

            if message["type"] == "http.response.start":
                start = message
                headers = MutableHeaders(raw=message["headers"])
                media = headers.get("content-type", "").split(";")[0].strip().lower()
                # Already encoded by something upstream, a streamed body we must not buffer, or a
                # type not worth the CPU. `content-encoding` is checked because double-encoding
                # produces a body no client can read.
                passthrough = (
                    media not in _COMPRESSIBLE_TYPES
                    or "content-encoding" in headers
                    # 204/304 carry no body.
                    #
                    # A ROUTE NOW DEPENDS ON THIS CLAUSE, so it is no longer only an optimisation.
                    # `GET /design-workshops/schema` answers a conditional GET, and because a 304
                    # never reaches the compression branch below it also never receives the
                    # `Vary: Accept-Encoding` this middleware appends there — which is why that
                    # route sets `Vary` on its 304 itself and NOT on its 200. Teaching this
                    # middleware to touch a 304 would duplicate the header on every revalidation;
                    # teaching it to compress one would put a body on a response defined to have
                    # none. See `get_stage_schema` for the argument in full.
                    #
                    # WHAT THAT ROUTE MAY NOT ASSUME, and no route should: that the 200 always
                    # carries `Vary`. This middleware appends it only where it compresses, so the
                    # early return at the top of `__call__` (no gzip offered) and the
                    # `minimum_size` branch below both answer 200 with no Vary at all. The schema
                    # route's 304 is unconditional and its 200 is not, which is fine for a body
                    # that does not actually vary by request header, and would not be fine for one
                    # that did. `Vary` is a property of the RESOURCE, so a route in that position
                    # must set it itself rather than inherit it from here.
                    or message["status"] in (204, 304)
                    # A PARTIAL RESPONSE MUST KEEP ITS BYTE OFFSETS. This clause used to be part of
                    # the comment above and not part of the condition: 206 was absent from the tuple,
                    # so a range response with a compressible type — `text/plain` is on the allowlist
                    # — would have been gzipped with its `Content-Range` header left intact,
                    # describing offsets into bytes the client never receives. It was unreachable
                    # while no route in this API served a range at all; `/api/asr-models/…/files/…`
                    # is the first that does. Both the status and the header are checked so a 200
                    # that carries `Content-Range` for any other reason is covered too.
                    or message["status"] == 206
                    or "content-range" in headers
                )
                if passthrough:
                    await send(message)
                return

            if message["type"] != "http.response.body":
                await send(message)
                return

            if passthrough:
                await send(message)
                return

            chunks.append(message.get("body", b""))
            if message.get("more_body", False):
                return

            # The whole body is in hand. Buffering is acceptable here precisely BECAUSE the
            # allowlist admits only JSON and text: the largest such response measured is an 839 KB
            # report preview, while the multi-megabyte payloads on this API are .docx and .pdf and
            # never reach this branch.
            body = b"".join(chunks)
            assert start is not None
            if len(body) < self.minimum_size:
                await send(start)
                await send({"type": "http.response.body", "body": body, "more_body": False})
                return

            packed = gzip.compress(body, compresslevel=6)
            headers = MutableHeaders(raw=start["headers"])
            headers["content-encoding"] = "gzip"
            headers["content-length"] = str(len(packed))
            # WITHOUT `Vary`, A SHARED CACHE SERVES THE GZIPPED BODY TO A CLIENT THAT DID NOT ASK
            # FOR IT. This API sits behind CloudFront; the header is what keeps the two variants
            # apart. `add_vary_header` appends rather than replacing, so an existing Vary survives.
            headers.append("vary", "Accept-Encoding")
            await send(start)
            await send({"type": "http.response.body", "body": packed, "more_body": False})

        await self.app(scope, receive, capture)


class SecurityHeadersMiddleware:
    """Pure-ASGI middleware that adds the standard security headers to every response.

    Written against the raw ASGI interface rather than ``BaseHTTPMiddleware`` on purpose: it only
    needs to append a few headers to the response-start message, so it adds no task groups, no
    buffering and nothing that could interfere with the streamed CSV/media responses or with request
    cancellation. Existing headers are never overwritten, so a route may still set its own CSP.

    It is registered LAST, which makes it the OUTERMOST user middleware, so the headers also land on
    CORS preflight responses and on responses produced by exception handlers. (A response generated
    by Starlette's ServerErrorMiddleware — the last-resort 500 — sits outside every user middleware
    and therefore cannot be stamped; that response carries no data.)
    """

    def __init__(self, app: ASGIApp, *, hsts_value: bytes | None = None, force_hsts: bool = False) -> None:
        self.app = app
        self.hsts_value = hsts_value
        self.force_hsts = force_hsts

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        csp = _DOCS_CSP if scope.get("path") in _DOCS_PATHS else _API_CSP
        hsts = self.hsts_value if (self.hsts_value and (self.force_hsts or _request_is_https(scope))) else None

        async def send_with_security_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = list(message.get("headers") or [])
                present = {name.lower() for name, _ in headers}
                for name, value in _BASE_SECURITY_HEADERS:
                    if name not in present:
                        headers.append((name, value))
                if b"content-security-policy" not in present:
                    headers.append((b"content-security-policy", csp))
                if hsts and b"strict-transport-security" not in present:
                    headers.append((b"strict-transport-security", hsts))
                message["headers"] = headers
            await send(message)

        await self.app(scope, receive, send_with_security_headers)


# --- Readiness ------------------------------------------------------------------------------------
# The readiness probe's own deadline. Shorter than an uptime monitor's request timeout on purpose, so
# a stalled database comes back as an explicit 503 the monitor can quote rather than as a client-side
# timeout, which says only that *something* did not answer. Also far below CloudFront's origin-response
# timeout, so the probe can never be the request that holds a connection open. The database endpoint lives in
# a different region from this box, so a healthy round trip is a couple of hundred milliseconds and
# the ceiling sits about ten times above that: high enough that ordinary cross-region latency is never
# mistaken for an outage, low enough to cut short a pool that has stopped handing out connections.
_READINESS_TIMEOUT_SECONDS = 3.0


def create_app() -> FastAPI:
    settings = get_settings()
    # Refuse to serve with a guessable token-signing secret. Done before anything else so the
    # failure is the first thing in the log rather than a subtle weakness nobody notices.
    verify_jwt_configuration()
    # Passing None for the three doc URLs is what actually unregisters the routes; leaving them at
    # their defaults and trying to block the paths in nginx would only move the problem, because the
    # origin is reachable directly as well as through CloudFront.
    expose_docs = settings.backend_expose_docs
    app = FastAPI(
        title="Design Prototype Workshop API",
        version="0.1.0",
        description="API-first backend for design & prototype workshop capture, review, media and report generation.",
        lifespan=lifespan,
        docs_url="/docs" if expose_docs else None,
        redoc_url="/redoc" if expose_docs else None,
        openapi_url="/openapi.json" if expose_docs else None,
    )
    cors_origins = settings.cors_origins
    allow_credentials = settings.cors_allow_credentials
    if not allow_credentials:
        # settings.cors_allow_credentials is False only when BACKEND_CORS_ORIGINS contains "*".
        logger.error(
            "BACKEND_CORS_ORIGINS contains a wildcard (%s); credentialed CORS is DISABLED because "
            "'*' plus credentials would let any website call this API as a signed-in user. Set "
            "BACKEND_CORS_ORIGINS to the explicit frontend origin(s).",
            ", ".join(cors_origins),
        )
    # Added BEFORE CORS, which makes it the INNERMOST of the two — load-bearing, see the class
    # docstring. An unhandled error must become a normal response *below* CORS so the CORS layer can
    # still stamp `access-control-allow-origin` on the way out.
    app.add_middleware(UnhandledErrorMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=allow_credentials,
        allow_methods=["*"],
        allow_headers=["*"],
        # WITHOUT THIS LINE THE BROWSER CANNOT READ THE HEADER AND THE PHONE CAN, which is the worst
        # shape a cross-origin bug takes: every server test passes, the Android sign-in screen shows
        # the right panel, and the web sign-in screen silently falls back to neutral chrome with
        # nothing anywhere naming the cause. A cross-origin response only exposes a handful of
        # "simple" headers to JavaScript; anything else has to be named here.
        #
        # `X-Access-Status` classifies a refused sign-in — awaiting approval, rejected, suspended,
        # queue full — so the sign-in page can tell a person waiting on an administrator apart from
        # a person who mistyped a password. It carries no information the response's own `detail`
        # sentence does not already say in English; see app/api/routes/auth.py.
        expose_headers=["X-Access-Status"],
    )
    # Added AFTER CORS so it wraps it (Starlette runs the most recently added middleware outermost),
    # which is what puts the security headers on preflight responses too.
    hsts_value = (
        f"max-age={settings.security_hsts_max_age}; includeSubDomains".encode()
        if settings.security_hsts_enabled
        else None
    )
    app.add_middleware(
        SecurityHeadersMiddleware,
        hsts_value=hsts_value,
        force_hsts=settings.security_force_hsts,
    )
    # Added LAST, so Starlette runs it OUTERMOST — which is the only position that works. It has to
    # see the finished body, after CORS and the security headers have stamped their own, and it must
    # be the last thing to touch `content-length`. Placed inside CORS it would compress a body whose
    # length header was then overwritten, and the client would hang waiting for bytes that never
    # come.
    app.add_middleware(SelectiveGZipMiddleware)

    @app.get("/health", tags=["health"])
    async def health() -> dict[str, str]:
        """Liveness for the CloudFront origin. It must stay dumb — do not make it touch the database.

        The background watchdog can spend minutes reconnecting to a saturated pooler (see
        ``_keep_db_connected``). If this check failed during that window CloudFront would drop the box
        as an unhealthy origin, and a database that was busy recovering on its own would become a
        total outage instead. So a 200 here means only "the process is serving requests".

        Which is exactly why it is the wrong thing to alert on: point uptime alerting at
        ``/health/ready`` below, which answers the question this one deliberately refuses to.
        """
        return {"status": "ok"}

    @app.get("/health/ready", tags=["health"])
    async def health_ready() -> JSONResponse:
        """Readiness: does the database actually answer? This is what uptime alerting should watch.

        200 means one trivial query completed inside the deadline; 503 means it did not. ``latencyMs``
        is reported on both paths deliberately — this box has a documented history of connection-pool
        exhaustion whose first symptom is a probe that still succeeds but takes seconds, so an alert
        on rising latency fires while there is still time to act, where an alert on outright failure
        only fires once researchers are already locked out.

        Unauthenticated, because an uptime monitor carries no token — so the body is a boolean and a
        duration and nothing more. No host, no connection string, no driver text. Whatever actually
        broke goes to the server log, which is the place it is safe to be specific.

        It never raises: a readiness probe that 500s is an outage signal of its own, and it would sit
        on top of the one being reported.
        """
        started = time.perf_counter()
        reachable = True
        try:
            async with asyncio.timeout(_READINESS_TIMEOUT_SECONDS):
                # Observe, never repair. ``ensure_db_connected`` would be the tempting call here, but
                # reconnecting means disconnecting first, which would kill in-flight queries and race
                # the watchdog that already owns recovery. A probe that heals what it measures cannot
                # tell you how often it was broken.
                await db.query_raw("SELECT 1")
        except TimeoutError:
            reachable = False
            logger.warning(
                "Readiness probe: no answer from the database within %.1fs", _READINESS_TIMEOUT_SECONDS
            )
        except Exception as exc:  # noqa: BLE001 - deliberate catch-all; this endpoint must never 500
            reachable = False
            logger.warning("Readiness probe failed: %s", exc)
        return JSONResponse(
            status_code=status.HTTP_200_OK if reachable else status.HTTP_503_SERVICE_UNAVAILABLE,
            content={
                "status": "ready" if reachable else "unavailable",
                "database": reachable,
                "latencyMs": round((time.perf_counter() - started) * 1000, 1),
            },
            # A remembered "ready" is precisely the failure-reporting-success shape this endpoint
            # exists to break, so nothing between here and the monitor may cache the verdict.
            headers={"cache-control": "no-store"},
        )

    app.include_router(api_router)
    return app


app = create_app()
