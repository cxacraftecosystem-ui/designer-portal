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

# `APIRoute` and not `starlette.routing.Route`: it is the class that populates `scope["route"]`, and
# `_mounted_route_templates` uses that distinction to keep the usage allow-list to templates the
# recorder can actually produce. Read that function's docstring before widening this.
from fastapi.routing import APIRoute
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api.router import api_router
from app.api.routes.usage import UNRECORDED_TEMPLATES
from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.security import verify_jwt_configuration
from app.scale import install_rate_limit
from app.services import usage
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
                logger.warning(
                    "DB probe pool-timeout (P2024); pool saturated by load, not reconnecting"
                )
                await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
                continue
            await asyncio.sleep(2.0)
            try:
                await db.query_raw("SELECT 1")
                continue  # transient blip — the connection is actually fine
            except Exception as exc2:  # noqa: BLE001
                logger.warning(
                    "DB health probe failed twice: %s — reconnecting in the background", exc2
                )
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
    # The usage buffer's writer. NO ADVISORY LOCK, and the difference from the media queue below is
    # the whole reason this is a separate task rather than another branch of that one: that lock
    # elects ONE process to drain a SHARED job queue, because two claimants would run the same
    # ffmpeg job twice. This buffer is per-process and in memory, so a worker that lost such an
    # election would sit on rows nobody would ever write. Every process flushes its own or its own
    # are lost. Deployment runs --workers 1 today, so this is future-proofing rather than a live
    # concern — the kind that costs a paragraph now and a silent data gap later.
    usage_flush_task: asyncio.Task[None] = asyncio.create_task(usage.run_flush_worker())
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
            logger.info(
                "Media queue worker already running elsewhere; pid %s serves requests only",
                os.getpid(),
            )
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
        # THE SHUTDOWN DRAIN, AND BOTH HALVES OF ITS POSITION ARE LOAD-BEARING. After the worker is
        # cancelled, so nothing is mid-flush and the drain cannot collide with it; BEFORE
        # `disconnect_db()`, because a `create_many` against a disconnected client writes nothing and
        # those rows then leave with the process. Without this, every deploy would lose up to
        # `FLUSH_INTERVAL_SECONDS` of traffic — small, but silent, and a dataset that loses rows
        # without saying so is a dataset nobody can check. `flush_all` never raises and is bounded by
        # what was buffered when it started, so a shutdown cannot be held open by traffic still
        # arriving.
        usage_flush_task.cancel()
        with suppress(asyncio.CancelledError):
            await usage_flush_task
        await usage.flush_all()
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
_FORWARDED_PROTO_HEADERS = frozenset(
    {b"x-forwarded-proto", b"cloudfront-forwarded-proto", b"x-forwarded-scheme"}
)


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
_COMPRESSIBLE_TYPES = frozenset(
    {
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
    }
)

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

    def __init__(
        self, app: ASGIApp, *, hsts_value: bytes | None = None, force_hsts: bool = False
    ) -> None:
        self.app = app
        self.hsts_value = hsts_value
        self.force_hsts = force_hsts

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        csp = _DOCS_CSP if scope.get("path") in _DOCS_PATHS else _API_CSP
        hsts = (
            self.hsts_value
            if (self.hsts_value and (self.force_hsts or _request_is_https(scope)))
            else None
        )

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


# --- Usage instrumentation --------------------------------------------------------------------
#
# ONE ROW PER SERVED REQUEST: which ROUTE was reached, what it answered, and how long the server
# took. Requirement 22-25 asks how designers move through the platform and where it is slow, and
# before this middleware existed nothing in this system could answer either half — `RecordRevision`
# and `ReviewLog` audit WRITES, the two daily meters count AI spend, and `/activity` is a live
# re-query of six list endpoints. Not one of them records anybody LOOKING at anything.
#
# The buffering, the consent rules and the route-template validation all live in
# `app/services/usage.py`; that module's docstring carries the arithmetic. What lives HERE is the
# only part that has to be a middleware: the status code and the duration, neither of which any
# dependency can see.

#: Recorded when the handler raised and no response was ever started. The client does receive a 500
#: — Starlette's `ServerErrorMiddleware` sits outside every middleware here and produces one — so
#: this is the status that was actually served and not a stand-in for "something went wrong".
_STATUS_WHEN_THE_HANDLER_RAISED = 500

#: Recorded when nothing was sent and nothing was raised either: the request was cancelled, which in
#: practice means the client hung up before the answer was ready.
#:
#: A SEPARATE NUMBER FROM THE ONE ABOVE, DELIBERATELY. Both could have been filed as 500 and it would
#: have been one line shorter and wrong in the way this schema refuses everywhere else: one column
#: would then mean two things, and every error rate computed from this table would count a designer
#: closing a tab on a village connection as a server fault. 499 is nginx's convention for exactly
#: this case rather than an IANA status, which is worth knowing before somebody looks it up in the
#: RFC and finds nothing; it is used because log tooling already reads it that way.
_STATUS_WHEN_NOBODY_ANSWERED = 499


class UsageEventMiddleware:
    """Record one `UsageEvent` per served request: the route TEMPLATE, the status, the duration.

    WHY IT IS PURE ASGI AND NOT `BaseHTTPMiddleware`, the same reason `SecurityHeadersMiddleware`
    gives one class up: it needs the status off the response-start message and nothing else, so it
    adds no task group, buffers no body and cannot interfere with the streamed CSV, NDJSON and media
    responses this API serves or with request cancellation. A `BaseHTTPMiddleware` here would put a
    queue between every streamed export and its client in order to count it.

    ── WHERE IT SITS, AND WHY EACH NEIGHBOUR MATTERS ──────────────────────────────────────────
    Registered between `install_rate_limit(app)` and `CORSMiddleware`, which Starlette's
    reverse-of-add-order rule turns into: outside the router, inside CORS, outside the limiter and
    outside `UnhandledErrorMiddleware`. Three consequences, all load-bearing:

    * **Outside the router**, because `scope["route"]` does not exist until the router has matched.
      FastAPI writes it during `await self.app(...)`, into the very dict this middleware is holding,
      so the template is absent before the await and present after it. Reading it before would
      record every request as unmatched.
    * **Inside CORS**, so nothing this class does can come between a response and its
      `access-control-allow-origin`. It touches no header at all, but position is what makes that a
      structural fact rather than a claim about the current body of the class.
    * **Outside `UnhandledErrorMiddleware`**, so a crashed handler is observed as the 500 the client
      received rather than as an exception. That middleware turns an unhandled error into an ordinary
      JSON response through the `send` it was given — which is this class's `send` — so the status is
      captured on the normal path. It re-raises when a response had already started, which is why the
      recording below is in a `finally` and not after the await.

    It is also outside the rate limiter, so a 429 is recorded. That is deliberate: a refused request
    is the clearest evidence there is that something is hammering one route, and the schema names "a
    401 storm on one route is visible at all" as a thing this table should be able to show. The cost
    is that a rate-limited request never reaches the router, so `scope["route"]` is never set and
    every 429 lands under the unmatched placeholder. Moving one line up to sit inside the limiter
    would make those requests invisible instead, which is worse. Academic today —
    `SCALE_RATE_LIMIT_ENABLED` is false by default.

    ── WHAT IT WILL NOT DO ────────────────────────────────────────────────────────────────────
    **It never alters a response and never delays one.** `record_event` is deliberately not a
    coroutine (see its docstring): it appends a dict to an in-process buffer, and a background task
    started in `lifespan` writes those in batches of two hundred. A write per request would take one
    of ten pooled connections and add most of a second to every response, in order to record that the
    response was slow.

    **It never raises.** `record_event` catches everything by contract; the `try` below is a second
    layer over that, because an exception escaping this middleware's `finally` would arrive after the
    response had already started and would drop a connection whose body the client had already begun
    reading. Instrumentation that can break a designer's sketch upload is worse than no
    instrumentation.

    **It records the route template, never the path.** `scope["path"]` is one keystroke away and
    carries every record id this API mints; `app/services/usage.py` refuses the value rather than
    trusting this class to pass the right one, and validates it again on the way in.

    ── THE OTHER HALF OF THE STITCH ───────────────────────────────────────────────────────────
    A pure-ASGI middleware never decodes a bearer token, so it cannot know who is calling. The
    identity is written into `scope["state"]` by `get_current_user` in `app/core/deps.py` — read that
    function's stitch comment, it names this class — and picked up below under the keys
    `usage.USAGE_USER_ID_KEY` / `usage.USAGE_CONSENT_KEY`, which are declared once in the service so
    the two halves cannot drift apart.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        # BEFORE the await, and it is not optional. `scope["state"]` is supplied per request by
        # uvicorn and by Starlette's TestClient, but NOT by `httpx.ASGITransport`, which builds its
        # scope by hand and omits the key — and that transport is what most of this suite uses. A
        # dependency that touches `request.state` creates the dict itself (Starlette's `Request.state`
        # does `scope.setdefault("state", {})`), so the key would be present on authenticated
        # requests and absent on 404s: a middleware reading `scope["state"]` directly would pass every
        # test that signs in and KeyError on every test that does not.
        scope.setdefault("state", {})
        # perf_counter, never `time.time`. A wall clock steps — NTP corrections, a VM resuming from a
        # snapshot, a daylight change on a box in local time — and a duration measured across a step
        # is a negative number or an hour, filed as a fact about how slow this API is. perf_counter is
        # monotonic by definition and is what `health_ready` above already uses for the same reason.
        started = time.perf_counter()
        status_code: int | None = None

        async def watch_status(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
            # Straight through, unmodified, including every `http.response.body` chunk. A streamed
            # export must reach the client exactly as it left the route.
            await send(message)

        failure: BaseException | None = None
        try:
            await self.app(scope, receive, watch_status)
        except BaseException as exc:
            # BaseException, not Exception, and it is re-raised on the very next line — nothing is
            # swallowed. The width is what lets the `finally` tell a crashed handler from a cancelled
            # request: `asyncio.CancelledError` is a BaseException, and a middleware that only caught
            # `Exception` would file every client disconnect as a server fault.
            failure = exc
            raise
        finally:
            self._record(
                scope,
                status_code=status_code,
                duration_ms=(time.perf_counter() - started) * 1000.0,
                failure=failure,
            )

    def _record(
        self,
        scope: Scope,
        *,
        status_code: int | None,
        duration_ms: float,
        failure: BaseException | None,
    ) -> None:
        """Buffer the finished request. Called from a `finally`, so it must not raise or block."""
        try:
            # AFTER the await and only after it. `Router.app` does `scope.update(child_scope)` on the
            # same dict object this middleware holds, so the key appears while the inner app is still
            # running. A 404 and a trailing-slash 307 both leave it absent — the redirect matches
            # against a COPY of the scope — and both must record the fixed placeholder rather than the
            # path that was wanted, or a scanner sweeping /artisans/<id> would write thousands of
            # distinct record ids into this table one 404 at a time.
            #
            # A THIRD CASE LANDS HERE AND IT IS NOT A 404: a route Starlette served but FastAPI never
            # named. Only `APIRoute` writes `scope["route"]`, so FastAPI's own documentation routes
            # (`/docs`, `/openapi.json`, `/redoc`, `/docs/oauth2-redirect`, mounted whenever
            # `BACKEND_EXPOSE_DOCS` is on) answer 200 and still arrive here with the key absent. The
            # placeholder is the honest answer for them — this middleware has no name to write and
            # must not invent one from `scope["path"]` — but they must not be in the allow-list
            # either, or `GET /usage/routes` would advertise four screens that can only ever read
            # zero. `_mounted_route_templates` is where that is enforced; read its docstring.
            template = getattr(scope.get("route"), "path_format", None) or usage.UNMATCHED_ROUTE
            # The skip list and the argument for every entry in it live in
            # `app/api/routes/usage.py`, beside the endpoint that PUBLISHES it as part of the
            # collection method — so what is not measured is a stated fact rather than something a
            # reader has to infer from a suspiciously flat graph, and so the recorder and the
            # published method cannot describe the same deployment differently.
            if template in UNRECORDED_TEMPLATES:
                return

            if status_code is None:
                # Nothing was ever sent. Which of the two that is depends on what came out of the
                # await, and the distinction is kept rather than flattened — see the constants.
                status_code = (
                    _STATUS_WHEN_THE_HANDLER_RAISED
                    if isinstance(failure, Exception)
                    else _STATUS_WHEN_NOBODY_ANSWERED
                )

            state = scope.get("state") or {}
            consent = state.get(usage.USAGE_CONSENT_KEY)
            usage.record_event(
                route_template=template,
                method=scope.get("method") or "?",
                status_code=status_code,
                duration_ms=duration_ms,
                # `_header` lower-cases and returns "" when absent, which `normalise_client_app` reads
                # as "a client that did not say" — today that is every client, because neither web nor
                # Android sends this header yet.
                client_app=_header(scope, b"x-client-app"),
                user_id=state.get(usage.USAGE_USER_ID_KEY),
                # Anything that is not a `UsageConsent` is dropped rather than coerced, which resolves
                # to "nobody has been asked" — the same direction `resolve_consent` fails in, and the
                # one that claims the least.
                consent=consent if isinstance(consent, usage.UsageConsent) else None,
            )
        except Exception as exc:  # noqa: BLE001 - the response is already on the wire
            logger.warning("Usage instrumentation could not record a request: %s", exc)


def _mounted_route_templates(app: FastAPI) -> list[str]:
    """Every route this application actually mounted, as templates, for the usage allow-list.

    WHY THIS IS A RECURSIVE WALK AND NOT `[r.path_format for r in app.routes]`. FastAPI 0.141 stopped
    flattening included routers: `include_router` keeps the ORIGINAL route objects and mounts one
    opaque `_IncludedRouter` entry that carries the prefix in a context. So `app.routes` on this
    application is a handful of its own routes plus a single entry standing for the whole API, and the
    flat comprehension — which is what every example on the internet shows — would register four
    templates and silently leave the other two hundred out. `original_router.routes` is the recursion
    step, and it degrades correctly on a FastAPI that flattens: there is simply nothing to recurse
    into.

    The templates are UNPREFIXED here — "/design-workshops/{workshop_id}", not
    "/api/design-workshops/{workshop_id}" — because that is the form the original route objects carry
    and therefore the form `scope["route"].path_format` reports at request time. The two agree because
    they are the same objects. A FastAPI that went back to minting prefixed copies would move both
    sides together.

    ONLY `APIRoute`s ARE COLLECTED, AND THAT FILTER IS A BUG FIX RATHER THAN TIDINESS. `scope["route"]`
    is set by FastAPI and by nothing else — `APIRoute.matches` writes it into the child scope, and
    Starlette's own `Route` does not. The four routes FastAPI mounts for its documentation
    (`/openapi.json`, `/docs`, `/docs/oauth2-redirect`, `/redoc`, present whenever
    `BACKEND_EXPOSE_DOCS` is on, which is the dev overlay and every laptop) are plain Starlette
    `Route`s, so a request that reaches one is SERVED, is a 200, and still leaves `scope["route"]`
    absent — the recorder can only file it under `<unmatched>`.
    Registering their templates anyway put both halves of a contradiction into the same table: their
    real traffic in the 404 bucket, and their names in `GET /usage/routes` as four screens reporting
    zero for ever. That is the exact failure the default listing's own comment refuses two files away
    — "a row that is structurally always zero reads as 'nobody uses this screen' rather than as 'this
    screen is not measured', and the two are opposite facts". The allow-list must therefore hold
    exactly the templates the recorder can actually produce, and no others.

    IT REFUSES TO REGISTER A LIST IT CANNOT VOUCH FOR, and that check is the point of the last four
    lines. A partial allow-list is the one way this feature makes things worse rather than better:
    real routes outside it are recorded as `<unsafe>` and their traffic disappears from every
    aggregate. So if the walk came back with nothing beyond the application's own directly-mounted
    routes — which is exactly the shape a future FastAPI change would produce — it registers nothing
    and says so at ERROR level, leaving `usage.ensure_route_template`'s shape rules as the defence.
    Named no route to make that check, deliberately: a self-test that hard-codes "/feedback/me" starts
    failing on the day somebody renames a route rather than on the day the walk breaks.
    """
    found: list[str] = []
    seen: set[int] = set()

    def walk(routes: Any) -> None:
        for route in routes or ():
            if id(route) in seen:  # a router included twice, or (defensively) a cycle
                continue
            seen.add(id(route))
            included = getattr(route, "original_router", None)
            if included is not None:
                walk(getattr(included, "routes", ()))
                continue
            # See the docstring: a route that does not populate `scope["route"]` can never be the
            # value the recorder writes, so its template has no business in the recorder's vocabulary.
            if not isinstance(route, APIRoute):
                continue
            template = getattr(route, "path_format", None)
            if template:
                found.append(template)

    walk(app.routes)
    own = {
        getattr(route, "path_format", None) for route in app.routes if isinstance(route, APIRoute)
    }
    if not set(found) - own:
        logger.error(
            "Usage instrumentation could not enumerate the mounted routes (%s found, none of them "
            "from an included router). The route allow-list is NOT installed; templates will be "
            "checked by shape only. This means the router layout changed shape — see "
            "_mounted_route_templates",
            len(found),
        )
        return []
    return found


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
    # THE RATE LIMITER, AND ITS POSITION IS THE SAME ARGUMENT THE LINE ABOVE MAKES.
    #
    # Between UnhandledErrorMiddleware and CORS, which puts it OUTSIDE the router and INSIDE CORS,
    # and both halves of that are load-bearing:
    #
    #  * Inside CORS, a 429 travels back out through the CORS layer and picks up
    #    `access-control-allow-origin`. Outside it, the same 429 reaches the browser bare, `fetch`
    #    rejects, and the web app reports "Failed to fetch" with nothing anywhere naming a rate
    #    limit — the exact confusion UnhandledErrorMiddleware exists to end, reintroduced by a
    #    middleware that was meant to protect the box. Pinned by
    #    `tests/test_rate_limit_install.py::test_a_refused_request_still_carries_the_cors_header`.
    #  * Outside the router, a refused request costs no route resolution, no dependency, and no
    #    database work. A limiter that only refuses AFTER the query it was protecting the box from
    #    has already run protects nothing.
    #
    # It could not go outside SecurityHeaders/GZip either, for a smaller reason worth knowing: those
    # two stamp and compress every response, and a 429 that skipped them would be the one response
    # this API serves without a CSP.
    #
    # ADDS NOTHING WHEN THE FLAG IS OFF — not a middleware that returns early, no middleware at all.
    # `SCALE_RATE_LIMIT_ENABLED` is false by default, so a fresh clone's stack is unchanged; see
    # app/scale/rate_limit.py, which also documents the separate, much tighter budget the two
    # credential endpoints get on top of the general allowance.
    install_rate_limit(app)
    # THE USAGE RECORDER, AND ITS POSITION IS THE SAME ARGUMENT AGAIN — one line above CORS in this
    # file, which is one layer INSIDE it at run time, because Starlette runs the most recently added
    # middleware outermost. Read the class docstring for the full reading; the three things this line
    # buys, in the order they matter:
    #
    #  * OUTSIDE THE ROUTER, which is the whole feature. `scope["route"]` does not exist until the
    #    router has matched, and the router runs inside everything registered here. A recorder
    #    mounted as a dependency, or anywhere below the router, would have to read `scope["path"]` —
    #    the interpolated one, carrying every artisan and workshop id in the product straight into an
    #    append-only table. See `app/services/usage.py`, which refuses that value rather than trusting
    #    this position to be right.
    #  * Inside CORS, so nothing it does can land between a response and its
    #    `access-control-allow-origin`. It adds no header today; the position is what keeps that true
    #    if it ever does.
    #  * Outside `UnhandledErrorMiddleware`, so a crashed handler is recorded as the 500 the client
    #    actually received. Below it the same request would arrive as an exception with no status at
    #    all, and the one table that could show which screens are breaking would be blind to exactly
    #    those requests.
    #
    # It could not go outside SecurityHeaders/GZip either, and the reason is those two layers' own
    # and not this one's: they run outermost so that EVERY response is stamped and compressed —
    # SelectiveGZipMiddleware in particular "must stay outermost because it is the last thing to
    # touch `content-length`" — so a class inserted above them is a class that can come between a
    # response and either treatment.
    #
    # WHAT THAT POSITION DOES *NOT* BUY, stated because an earlier version of this comment claimed it
    # did and a reader would otherwise trust the number more than it deserves: it does not keep gzip
    # out of the measurement. The timer stops when `await self.app(...)` returns, and every
    # `http.response.body` chunk travels back up through this class's `send` into CORS, the security
    # headers and `SelectiveGZipMiddleware.capture`, which compresses inside that call. Compression
    # and header-stamping are therefore INSIDE `durationMs` wherever this line sits, and moving it
    # would change that by microseconds. `durationMs` is the server's whole answer-producing time,
    # not handler time — which is the honest reading and the one `docs/METHODOLOGY-usage-
    # instrumentation.md` §7.1 is written against.
    app.add_middleware(UsageEventMiddleware)
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
                "Readiness probe: no answer from the database within %.1fs",
                _READINESS_TIMEOUT_SECONDS,
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
    # AFTER the include, because it reads what was included. This installs the application's own
    # route table as an ALLOW-LIST inside `app/services/usage.py`, which is what turns "a raw path
    # cannot be recorded" from a regex that catches every id shape this API mints into a property of
    # the system: while the list is populated, nothing outside it is storable at all, including the
    # one case shape rules provably cannot catch — a record id that happens to read like a word, say
    # a craft slug. It is optional by design (the shape rules stand on their own), so a walk that
    # comes back empty logs and installs nothing rather than half a list.
    usage.register_known_templates(_mounted_route_templates(app))
    return app


app = create_app()
