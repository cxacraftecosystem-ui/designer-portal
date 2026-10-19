"""The HTTP face of the one consent store, for routes a person is waiting on.

``services/dictation_consent`` decides every outside send and stays free of the web framework. A route
asks through :func:`require_send`, which answers:

* a refusal with a 409 and the verdict's own sentence (the same status ``POST /{id}/dictate`` has
  always used: the workshop, recording or artisan is not in a state to permit the send);
* a consent store that cannot be read with a 503 and ``dictation_consent.UNREADABLE``, never a 500.
  ``transcription_verdict`` raises on a failed read of the recording's own row so the queue retries
  instead of guessing; a person waiting on a request is told plainly instead. Nothing is sent either way.

The queue does not use this: a failed read there stays an ordinary retry.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable

from fastapi import HTTPException, status

from app.services import dictation_consent
from app.services.dictation_consent import SendVerdict

logger = logging.getLogger(__name__)


async def verdict_or_503(verdict: Awaitable[SendVerdict]) -> SendVerdict:
    """Await a verdict; a failed read becomes a 503 with a plain sentence."""
    try:
        return await verdict
    except HTTPException:
        raise
    except Exception as exc:  # any failed read: nothing is sent, the caller is told
        logger.warning("consent: unreadable for a waiting request (%s)", type(exc).__name__)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=dictation_consent.UNREADABLE
        ) from exc


async def require_send(verdict: Awaitable[SendVerdict]) -> SendVerdict:
    """Await a verdict and refuse with a 409 unless it permits the send."""
    answered = await verdict_or_503(verdict)
    if not answered.may_send:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=answered.refusal)
    return answered
