import asyncio
import gzip
import logging
import os
import time
from contextlib import asynccontextmanager, suppress
from typing import Any
from urllib.parse import unquote_plus

from fastapi import FastAPI, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute
from starlette.datastructures import MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api.router import api_router
from app.api.routes.auth import (
    CONFIRM_PROVIDER_HEADER,
    SESSION_TOKEN_HEADER,
    TWO_FACTOR_REQUIRED_HEADER,
)
from app.api.routes.usage import UNRECORDED_TEMPLATES
from app.core.config import get_settings, verify_app_url_configuration
from app.core.db import connect_db, db, disconnect_db
from app.core.deps import PASSWORD_CHANGE_REQUIRED_HEADER
from app.core.idempotency import IdempotencyMiddleware
from app.core.security import verify_jwt_configuration
from app.core.validation_errors import install_validation_handler
from app.release import live_release
from app.scale import install_rate_limit
from app.services import aadhaar_fingerprint, two_factor, usage
from app.services.account_provisioning import EXISTING_ACCOUNT_HEADER
from app.services.app_versions import AppVersionMiddleware
from app.services.design_workshop_review_aids import purge_expired_snapshots
from app.services.email_outbox import process_next_email_jobs
from app.services.media_queue import acquire_queue_worker_lock, process_next_media_jobs
from app.transcription import workdir

logger = logging.getLogger(__name__)


_DB_PROBE_INTERVAL_SECONDS = 15.0


async def _keep_db_connected() -> None:
    delay = 2.0
    while True:
        try:
            await db.query_raw("SELECT 1")
            delay = 2.0
            await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
            continue
        except Exception as exc:  # noqa: BLE001 - a failed probe MAY mean the connection is broken
            if getattr(exc, "code", None) == "P2024":
                logger.warning(
                    "DB probe pool-timeout (P2024); pool saturated by load, not reconnecting"
                )
                await asyncio.sleep(_DB_PROBE_INTERVAL_SECONDS)
                continue
            await asyncio.sleep(2.0)
            try:
                await db.query_raw("SELECT 1")
                continue
            except Exception as exc2:  # noqa: BLE001
                logger.warning(
                    "DB health probe failed twice: %s — reconnecting in the background", exc2
                )
        try:
            with suppress(Exception):
                await db.disconnect()
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
    if get_settings().aadhaar_online_auth_enabled:
        from app.services.aadhaar.online.config import load_online_config

        load_online_config()
    try:
        await connect_db()
    except Exception as exc:  # noqa: BLE001 - never crash-loop on a full pooler; recover in background
        logger.error(
            "Initial DB connect failed (%s); starting anyway and reconnecting in the background "
            "so a saturated pooler cannot crash-loop the service",
            exc,
        )
    try:
        await two_factor.check_keys_at_boot()
    except RuntimeError:
        raise
    except Exception as exc:  # noqa: BLE001 - the census is advisory when the database is down
        logger.warning("two-factor: key census skipped at boot (%s)", type(exc).__name__)
    db_reconnect_task: asyncio.Task[None] = asyncio.create_task(_keep_db_connected())
    usage_flush_task: asyncio.Task[None] = asyncio.create_task(usage.run_flush_worker())
    settings = get_settings()
    queue_task: asyncio.Task[None] | None = None
    queue_lock: Any | None = None
    if settings.media_queue_worker_enabled:
        queue_lock = acquire_queue_worker_lock()
        if queue_lock is not None:
            logger.info("Media queue worker elected in pid %s", os.getpid())
            try:
                await asyncio.to_thread(workdir.on_worker_start)
            except Exception:
                logger.warning("transcription workdir sweep failed", exc_info=True)
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
        try:
            await process_next_email_jobs(worker_id="fastapi-background", settings=settings)
        except Exception:
            logger.exception("E-mail outbox drain failed")
        await purge_expired_snapshots()
        await asyncio.sleep(interval)


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

_API_CSP = b"default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

_DOCS_CSP = (
    b"default-src 'none'; "
    b"script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; "
    b"style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'; "
    b"img-src 'self' https://fastapi.tiangolo.com data:; "
    b"font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com; "
    b"connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
)
_DOCS_PATHS = frozenset({"/docs", "/docs/oauth2-redirect", "/redoc"})

_FORWARDED_PROTO_HEADERS = frozenset(
    {b"x-forwarded-proto", b"cloudfront-forwarded-proto", b"x-forwarded-scheme"}
)


def _request_is_https(scope: Scope) -> bool:
    if scope.get("scheme") == "https":
        return True
    for name, value in scope.get("headers", []):
        if name in _FORWARDED_PROTO_HEADERS:
            if value.decode("latin-1").split(",")[0].strip().lower() == "https":
                return True
        elif name == b"x-forwarded-ssl" and value.strip().lower() == b"on":
            return True
    return False


class UnhandledErrorMiddleware:
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
        except Exception as exc:
            method = scope.get("method", "?")
            path = scope.get("path", "?")
            logger.exception("Unhandled error on %s %s: %s", method, path, exc)
            if started:
                raise
            payload = {
                "detail": "Something went wrong. Please try again.",
                "error": type(exc).__name__,
                "path": path,
            }
            response = JSONResponse(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, content=payload
            )
            await response(scope, receive, send)


def _header(scope: Scope, name: bytes) -> str:
    for key, value in scope.get("headers", ()):
        if key == name:
            return value.decode("latin-1").lower()
    return ""


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

_COMPRESS_MIN_BYTES = 1024


class SelectiveGZipMiddleware:
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
                passthrough = (
                    media not in _COMPRESSIBLE_TYPES
                    or "content-encoding" in headers
                    or message["status"] in (204, 304)
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
            headers.append("vary", "Accept-Encoding")
            await send(start)
            await send({"type": "http.response.body", "body": packed, "more_body": False})

        await self.app(scope, receive, capture)


class SecurityHeadersMiddleware:
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


_STATUS_WHEN_THE_HANDLER_RAISED = 500

_STATUS_WHEN_NOBODY_ANSWERED = 499


class UsageEventMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        scope.setdefault("state", {})
        started = time.perf_counter()
        status_code: int | None = None

        async def watch_status(message: Message) -> None:
            nonlocal status_code
            if message["type"] == "http.response.start":
                status_code = message["status"]
            await send(message)

        failure: BaseException | None = None
        try:
            await self.app(scope, receive, watch_status)
        except BaseException as exc:
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
        try:
            template = getattr(scope.get("route"), "path_format", None) or usage.UNMATCHED_ROUTE
            if template in UNRECORDED_TEMPLATES:
                return

            if status_code is None:
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
                client_app=_header(scope, b"x-client-app"),
                user_id=state.get(usage.USAGE_USER_ID_KEY),
                consent=consent if isinstance(consent, usage.UsageConsent) else None,
            )
        except Exception as exc:  # noqa: BLE001 - the response is already on the wire
            logger.warning("Usage instrumentation could not record a request: %s", exc)


def _mounted_route_templates(app: FastAPI) -> list[str]:
    found: list[str] = []
    seen: set[int] = set()

    def walk(routes: Any) -> None:
        for route in routes or ():
            if id(route) in seen:
                continue
            seen.add(id(route))
            included = getattr(route, "original_router", None)
            if included is not None:
                walk(getattr(included, "routes", ()))
                continue
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


_CREDENTIAL_QUERY_PARAMETERS = frozenset(
    {"token", "access_token", "id_token", "refresh_token", "code", "key", "password", "secret"}
)


def redact_query_credentials(path: str) -> str:
    head, separator, query = path.partition("?")
    if not separator:
        return path
    fields = query.split("&")
    for index, field in enumerate(fields):
        name, equals, value = field.partition("=")
        if equals and value and unquote_plus(name).lower() in _CREDENTIAL_QUERY_PARAMETERS:
            fields[index] = f"{name}=[redacted]"
    return f"{head}?{'&'.join(fields)}"


class AccessLogRedaction(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        with suppress(Exception):
            args = record.args
            if isinstance(args, tuple) and len(args) == 5 and isinstance(args[2], str):
                client, method, path, http_version, status_code = args
                record.args = (
                    client,
                    method,
                    redact_query_credentials(path),
                    http_version,
                    status_code,
                )
        return True


def install_access_log_redaction() -> None:
    access = logging.getLogger("uvicorn.access")
    if not any(isinstance(existing, AccessLogRedaction) for existing in access.filters):
        access.addFilter(AccessLogRedaction())


_READINESS_TIMEOUT_SECONDS = 3.0


def create_app() -> FastAPI:
    settings = get_settings()
    verify_jwt_configuration()
    verify_app_url_configuration(settings)
    two_factor.verify_key_configuration()
    expose_docs = settings.backend_expose_docs
    app = FastAPI(
        title="Design Prototype Workshop API",
        version="0.0.3",
        description="API-first backend for design & prototype workshop capture, review, media and report generation.",
        lifespan=lifespan,
        docs_url="/docs" if expose_docs else None,
        redoc_url="/redoc" if expose_docs else None,
        openapi_url="/openapi.json" if expose_docs else None,
    )
    install_validation_handler(app)
    cors_origins = settings.cors_origins
    allow_credentials = settings.cors_allow_credentials
    if not allow_credentials:
        logger.error(
            "BACKEND_CORS_ORIGINS contains a wildcard (%s); credentialed CORS is DISABLED because "
            "'*' plus credentials would let any website call this API as a signed-in user. Set "
            "BACKEND_CORS_ORIGINS to the explicit frontend origin(s).",
            ", ".join(cors_origins),
        )
    app.add_middleware(IdempotencyMiddleware)
    app.add_middleware(AppVersionMiddleware)
    app.add_middleware(UnhandledErrorMiddleware)
    install_rate_limit(app)
    app.add_middleware(UsageEventMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=allow_credentials,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=[
            "X-Access-Status",
            "X-Sign-In-Hint",
            PASSWORD_CHANGE_REQUIRED_HEADER,
            SESSION_TOKEN_HEADER,
            CONFIRM_PROVIDER_HEADER,
            TWO_FACTOR_REQUIRED_HEADER,
            EXISTING_ACCOUNT_HEADER,
            "X-Report-Export-Id",
            "X-Report-Reused-From",
            "ETag",
        ],
    )
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
    app.add_middleware(SelectiveGZipMiddleware)

    live_release()

    @app.get("/health", tags=["health"])
    async def health() -> dict[str, str]:
        return {"status": "ok", "release": live_release()}

    @app.get("/health/ready", tags=["health"])
    async def health_ready() -> JSONResponse:
        started = time.perf_counter()
        reachable = True
        try:
            async with asyncio.timeout(_READINESS_TIMEOUT_SECONDS):
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
            headers={"cache-control": "no-store"},
        )

    app.include_router(api_router)
    usage.register_known_templates(_mounted_route_templates(app))
    return app


install_access_log_redaction()
aadhaar_fingerprint.install_access_log_redaction()
app = create_app()
