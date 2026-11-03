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
