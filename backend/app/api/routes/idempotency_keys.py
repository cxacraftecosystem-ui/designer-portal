"""``GET /api/idempotency-keys/{key}``: what the caller's own create under this key answered.

WHY IT EXISTS. The Android outbox replays a create under the Idempotency-Key its online attempt sent.
If the online attempt landed and its answer was lost, and the replayed body differs in any byte (an
older build, a field the form normalised), the middleware answers 422 ``IDEMPOTENCY_KEY_REUSED``. The
record exists; the phone just does not know its id, and without the id its photographs can never be
attached. This route hands back the stored answer so the outbox can carry on, instead of parking the
entry as refused with the photographs on the phone.

SCOPED TO THE CALLER: keys are stored per account (``app/core/idempotency.py``), so one account can
never read another's answer, and only record creates are ever stored, so no credential can come back
through here. A key that was never stored, is still running, or has expired is a 404.
"""

from __future__ import annotations

import json
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.deps import get_current_user
from app.core.idempotency import _KEY_RE, stored_answer_for

router = APIRouter(prefix="/idempotency-keys", tags=["idempotency"])


@router.get("/{key}")
async def idempotency_answer(key: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    if not _KEY_RE.match(key):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="That is not an Idempotency-Key.")
    answer = await stored_answer_for(str(current_user.id), key)
    if answer is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No stored answer for that key.")
    try:
        body: Any = json.loads((answer.body or b"").decode("utf-8") or "null")
    except ValueError:
        body = None
    return {"statusCode": answer.status_code, "body": body}
