"""``Idempotency-Key`` on POST: a create that lands twice writes once.

THE DUPLICATE THIS CLOSES. A client POSTs a create, this server writes the row, and the answer is
lost on the way back (a tunnel, a captive portal, the app killed mid-request). The client learned
nothing, so it sends the same create again and a second record appears. The Android outbox now
moves such a create into its queue and replays it, so the replay has to be safe.

THE CONTRACT. A POST carrying ``Idempotency-Key: <8-128 of [A-Za-z0-9_-]>``:

* first time: runs normally. A 2xx answer is stored for 30 days under (caller, key).
* again within 30 days, same method, path and body: the stored answer is returned as it was, with
  ``Idempotent-Replayed: true``. The route does not run, so nothing is written twice.
* again while the first is still running: ``409`` with ``Idempotency-Status: in-progress`` and
  ``Retry-After``. The client retries later; it must not treat this as a refusal.
* again with a different method, path or body: ``422``. One key names one request.
* a non-2xx answer or an exception is not stored, so a failed create can be retried.

It is ADDITIVE. A request without the header, a request that is not a POST, a request with no
valid bearer token (the route answers 401 itself), and a POST to any route that is not on the
CREATE ALLOW-LIST below pass through untouched. The allow-list is deliberate: a stored answer is a
copy of a response body, and sign-in, token-minting, password, secret and AI-key routes answer with
credentials that must never be written to a table, so only record creates are honoured.

TIMINGS (reviewed 2026-10-16). Keys are kept 30 days, because an outbox on a phone out of coverage for
a fortnight replays a create long after 24 hours. A first request still marked running is taken over
only after 15 minutes, longer than the client's whole call timeout (12 minutes on the storage
client), so a slow first send is never mistaken for a dead one, and only by a conditional update
matched on its ``createdAt``, so exactly one of several concurrent repeats wins and the rest get 409.

Keys are scoped to the caller's user id from the bearer token, so one account can never be answered
with another account's stored response, and a re-issued token (sign out, sign in) still finds its
own keys.

This sits alongside the older ``clientKey`` body field (``app/services/records.py``), which four
record routes still honour. A key in the header covers every create route, including artisans,
crafts, questionnaire interviews, custom questionnaires and feedback reports, which ``clientKey``
never did.

Mirrored from the documentation portal's ``app/core/idempotency.py`` (2026-10-16), with one
difference: a token minted for a narrower SCOPE (the ``dataset:read`` download credential, see
``deps.TOKEN_SCOPE_CLAIM``) is passed through untouched, so a stored answer from a full session is
never replayed to a credential that could not have made the request.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.security import decode_access_token

logger = logging.getLogger(__name__)

HEADER = b"idempotency-key"
TTL = timedelta(days=30)
# A first request still marked running after this long was killed with the process; a repeat may
# take its place instead of waiting forever. Above the Android client's 12-minute call timeout.
STALE_PENDING = timedelta(minutes=15)

#: The only POSTs a key is honoured on: record creates. Everything else passes through untouched,
#: in particular /api/auth/*, password and token routes, secrets and AI keys, whose answers carry
#: credentials that must never be stored.
CREATE_ROUTES = tuple(
    re.compile(pattern)
    for pattern in (
        r"^/api/(artisans|crafts|products|tools|workshops|processes|questionnaires|design-workshops)/?$",
        r"^/api/questionnaire/interviews/?$",
        r"^/api/questionnaires/[^/]+/sections/?$",
        r"^/api/questionnaires/[^/]+/sections/[^/]+/questions/?$",
        r"^/api/questionnaires/[^/]+/entries/?$",
        r"^/api/feedback/reports/?$",
    )
)


def is_create_route(path: str) -> bool:
    return any(pattern.match(path) for pattern in CREATE_ROUTES)
_KEY_RE = re.compile(r"^[A-Za-z0-9_-]{8,128}$")
# Creates answer small JSON. A body larger than this is passed through and not stored.
MAX_STORED_BODY = 256 * 1024


@dataclass
class StoredAnswer:
    fingerprint: str
    status_code: int | None
    body: bytes | None
    content_type: str | None
    created_at: datetime


class IdempotencyStore(Protocol):
    async def get(self, user_id: str, key: str) -> StoredAnswer | None: ...

    async def claim(self, user_id: str, key: str, fingerprint: str) -> bool:
        """Insert a running marker; False when (user_id, key) already exists."""

    async def take_over(self, user_id: str, key: str, fingerprint: str, seen_created_at: datetime) -> bool:
        """Replace a stale running marker; True for exactly one caller (matched on ``createdAt``)."""

    async def complete(self, user_id: str, key: str, status_code: int, body: bytes, content_type: str | None) -> None: ...

    async def release(self, user_id: str, key: str) -> None: ...


class PrismaIdempotencyStore:
    """The production store: the ``IdempotencyKey`` table, unique on (userId, key)."""

    def __init__(self, client: Any = None) -> None:
        self._client = client

    @property
    def _table(self) -> Any:
        if self._client is None:
            from app.core.db import db

            return db.idempotencykey
        return self._client.idempotencykey

    async def get(self, user_id: str, key: str) -> StoredAnswer | None:
        row = await self._table.find_unique(where={"userId_key": {"userId": user_id, "key": key}})
        if row is None:
            return None
        if row.expiresAt <= datetime.now(UTC):
            await self._table.delete_many(where={"userId": user_id, "key": key})
            return None
        return StoredAnswer(
            fingerprint=row.fingerprint,
            status_code=row.statusCode,
            body=row.responseBody.encode() if row.responseBody is not None else None,
            content_type=row.contentType,
            created_at=row.createdAt,
        )

    async def claim(self, user_id: str, key: str, fingerprint: str) -> bool:
        from prisma.errors import UniqueViolationError

        now = datetime.now(UTC)
        # Opportunistic sweep: the table only ever holds TTL (30 days) of keys.
        await self._table.delete_many(where={"expiresAt": {"lt": now}})
        try:
            await self._table.create(
                data={
                    "userId": user_id,
                    "key": key,
                    "fingerprint": fingerprint,
                    "createdAt": now,
                    "expiresAt": now + TTL,
                }
            )
        except UniqueViolationError:
            return False
        return True

    async def take_over(self, user_id: str, key: str, fingerprint: str, seen_created_at: datetime) -> bool:
        now = datetime.now(UTC)
        # CONDITIONAL, so two repeats that both saw the same stale marker cannot both win: the first
        # update moves ``createdAt`` and the second matches nothing.
        count = await self._table.update_many(
            where={"userId": user_id, "key": key, "createdAt": seen_created_at, "statusCode": None},
            data={"fingerprint": fingerprint, "createdAt": now, "expiresAt": now + TTL},
        )
        return bool(count)

    async def complete(self, user_id: str, key: str, status_code: int, body: bytes, content_type: str | None) -> None:
        await self._table.update(
            where={"userId_key": {"userId": user_id, "key": key}},
            data={
                "statusCode": status_code,
                "responseBody": body.decode("utf-8", errors="replace"),
                "contentType": content_type,
            },
        )

    async def release(self, user_id: str, key: str) -> None:
        await self._table.delete_many(where={"userId": user_id, "key": key})


def _header(scope: Scope, name: bytes) -> str | None:
    for header_name, value in scope.get("headers") or []:
        if header_name.lower() == name:
            return value.decode("latin-1")
    return None


def _caller(scope: Scope) -> str | None:
    auth = _header(scope, b"authorization")
    if not auth or not auth.lower().startswith("bearer "):
        return None
    try:
        claims = decode_access_token(auth[7:].strip())
    except ValueError:
        return None
    if claims.get("scope") is not None:
        # A narrower credential (``deps.TOKEN_SCOPE_CLAIM``): the route decides; no key is honoured.
        return None
    sub = claims.get("sub")
    return str(sub) if sub else None


def _fingerprint(method: str, path: str, query: bytes, body: bytes) -> str:
    digest = hashlib.sha256()
    for part in (method.encode(), path.encode(), query, body):
        digest.update(len(part).to_bytes(8, "big"))
        digest.update(part)
    return digest.hexdigest()


async def _send_json(send: Send, status_code: int, payload: dict[str, Any], extra: list[tuple[bytes, bytes]]) -> None:
    body = json.dumps(payload).encode()
    headers = [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode()), *extra]
    await send({"type": "http.response.start", "status": status_code, "headers": headers})
    await send({"type": "http.response.body", "body": body})


class IdempotencyMiddleware:
    """Pure-ASGI, so it adds nothing to requests it does not act on."""

    def __init__(self, app: ASGIApp, store: IdempotencyStore | None = None) -> None:
        self.app = app
        self.store: IdempotencyStore = store or PrismaIdempotencyStore()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope.get("method") != "POST" or not is_create_route(scope.get("path", "")):
            await self.app(scope, receive, send)
            return
        key = _header(scope, HEADER)
        if key is None:
            await self.app(scope, receive, send)
            return
        key = key.strip()
        if not _KEY_RE.match(key):
            await _send_json(
                send, 422, {"detail": "Idempotency-Key must be 8 to 128 letters, digits, '-' or '_'."}, []
            )
            return
        user_id = _caller(scope)
        if user_id is None:
            # No valid session: the route answers 401 itself, and there is nobody to scope a key to.
            await self.app(scope, receive, send)
            return

        # Read the whole body once, so it can be fingerprinted and then handed to the route.
        chunks: list[bytes] = []
        more = True
        while more:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunks.append(message.get("body", b""))
            more = message.get("more_body", False)
        body = b"".join(chunks)
        fingerprint = _fingerprint("POST", scope.get("path", ""), scope.get("query_string", b""), body)

        if not await self.store.claim(user_id, key, fingerprint):
            existing = await self.store.get(user_id, key)
            if existing is None:
                # Expired between the two reads; this request becomes the first.
                if not await self.store.claim(user_id, key, fingerprint):
                    await self._in_progress(send)
                    return
            elif existing.fingerprint != fingerprint:
                await _send_json(
                    send,
                    422,
                    {
                        "detail": "This Idempotency-Key was already used for a different request.",
                        # Machine-readable, so a client replaying its own key can ask what the first
                        # request created (GET /api/idempotency-keys/{key}) instead of giving up.
                        "code": "IDEMPOTENCY_KEY_REUSED",
                    },
                    [],
                )
                return
            elif existing.status_code is not None:
                headers = [
                    (b"content-type", (existing.content_type or "application/json").encode()),
                    (b"content-length", str(len(existing.body or b"")).encode()),
                    (b"idempotent-replayed", b"true"),
                ]
                await send({"type": "http.response.start", "status": existing.status_code, "headers": headers})
                await send({"type": "http.response.body", "body": existing.body or b""})
                return
            elif datetime.now(UTC) - existing.created_at < STALE_PENDING:
                await self._in_progress(send)
                return
            elif not await self.store.take_over(user_id, key, fingerprint, existing.created_at):
                # Another repeat took it over a moment ago.
                await self._in_progress(send)
                return

        delivered = False

        async def replay_body() -> Message:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        status_code = 500
        content_type: str | None = None
        captured: list[bytes] = []
        too_large = False

        async def capture(message: Message) -> None:
            nonlocal status_code, content_type, too_large
            if message["type"] == "http.response.start":
                status_code = message["status"]
                for name, value in message.get("headers") or []:
                    if name.lower() == b"content-type":
                        content_type = value.decode("latin-1")
            elif message["type"] == "http.response.body" and not too_large:
                captured.append(message.get("body", b""))
                if sum(len(c) for c in captured) > MAX_STORED_BODY:
                    too_large = True
            await send(message)

        try:
            await self.app(scope, replay_body, capture)
        except BaseException:
            await self._release_quietly(user_id, key)
            raise
        if 200 <= status_code < 300 and not too_large:
            try:
                await self.store.complete(user_id, key, status_code, b"".join(captured), content_type)
            except Exception:
                logger.exception("Could not store the answer for an Idempotency-Key")
                # RELEASED, not left running: a marker with no answer would hold every repeat at 409
                # for STALE_PENDING and then be taken over anyway. Released, a repeat runs at once.
                await self._release_quietly(user_id, key)
        else:
            await self._release_quietly(user_id, key)

    async def _release_quietly(self, user_id: str, key: str) -> None:
        try:
            await self.store.release(user_id, key)
        except Exception:
            logger.exception("Could not release an Idempotency-Key")

    @staticmethod
    async def _in_progress(send: Send) -> None:
        await _send_json(
            send,
            409,
            {"detail": "The same request is still being processed. Try again shortly.", "code": "IDEMPOTENCY_IN_PROGRESS"},
            [(b"idempotency-status", b"in-progress"), (b"retry-after", b"5")],
        )


async def stored_answer_for(user_id: str, key: str, store: IdempotencyStore | None = None) -> StoredAnswer | None:
    """The answer stored under the caller's own key, or None (never stored, still running, expired)."""
    answer = await (store or PrismaIdempotencyStore()).get(user_id, key)
    if answer is None or answer.status_code is None:
        return None
    return answer
