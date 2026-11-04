from __future__ import annotations

import logging
from collections.abc import Awaitable

from fastapi import HTTPException, status

from app.services import dictation_consent
from app.services.dictation_consent import SendVerdict

logger = logging.getLogger(__name__)


async def verdict_or_503(verdict: Awaitable[SendVerdict]) -> SendVerdict:
    try:
        return await verdict
    except HTTPException:
        raise
    except Exception as exc:
        logger.warning("consent: unreadable for a waiting request (%s)", type(exc).__name__)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=dictation_consent.UNREADABLE
        ) from exc


async def require_send(verdict: Awaitable[SendVerdict]) -> SendVerdict:
    answered = await verdict_or_503(verdict)
    if not answered.may_send:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=answered.refusal)
    return answered
