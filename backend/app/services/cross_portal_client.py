"""The designer portal's calls to the documentation portal (C27), and nothing else.

ONE function, :func:`peer_call`, and it refuses to hand back a body the other server did not sign
(``services/cross_portal.signature_matches``). Every failure — not configured, unreachable, a timeout,
a bad signature, a body that is not JSON — becomes :class:`PeerUnavailable` with a sentence a
designer can read; the route turns it into a 503 and nothing on this side is written.

``requests`` in a thread, with a short timeout: a designer is waiting with the artisan in front of
them, and a slow answer is no answer.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import requests

from app.services import cross_portal

logger = logging.getLogger(__name__)

TIMEOUT_SECONDS = 10.0

UNAVAILABLE = "The Field Repository can't be reached right now. Try again, or fill the form yourself."


class PeerUnavailable(RuntimeError):
    """The documentation portal could not be asked, or its answer could not be trusted."""


def _url(settings: Any, path: str) -> str:
    base = (settings.field_repository_api_url or "").strip().rstrip("/")
    return f"{base}/cross-portal/peer{path}"


def _call_sync(
    settings: Any, method: str, path: str, body: Any | None, params: dict[str, str] | None
) -> tuple[int, Any]:
    token = settings.cross_portal_token
    try:
        response = requests.request(
            method,
            _url(settings, path),
            headers={"Authorization": f"Bearer {token.strip()}", "Content-Type": "application/json"},
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            params=params,
            timeout=TIMEOUT_SECONDS,
        )
    except requests.RequestException as exc:
        # The exception CLASS only: a requests message carries the prepared request, headers included.
        logger.warning("cross-portal %s %s failed: %s", method, path, type(exc).__name__)
        raise PeerUnavailable(UNAVAILABLE) from None
    if response.status_code in (401, 403):
        logger.error("cross-portal %s %s refused our token (%s)", method, path, response.status_code)
        raise PeerUnavailable(UNAVAILABLE)
    if not cross_portal.signature_matches(
        token, response.content, response.headers.get(cross_portal.SIGNATURE_HEADER)
    ):
        logger.error("cross-portal %s %s: answer not signed by the peer (%s)", method, path, response.status_code)
        raise PeerUnavailable(UNAVAILABLE)
    try:
        payload = json.loads(response.content.decode("utf-8") or "null")
    except ValueError:
        raise PeerUnavailable(UNAVAILABLE) from None
    return response.status_code, payload


async def peer_call(
    settings: Any,
    method: str,
    path: str,
    *,
    body: Any | None = None,
    params: dict[str, str] | None = None,
) -> tuple[int, Any]:
    """``(status, signed JSON body)`` from the documentation portal, or :class:`PeerUnavailable`."""
    if not cross_portal.active(settings, needs_url=True):
        raise PeerUnavailable(UNAVAILABLE)
    return await asyncio.to_thread(_call_sync, settings, method, path, body, params)
