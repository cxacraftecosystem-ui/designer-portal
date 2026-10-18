"""``GET /records/completeness-rules``: the record completeness table, as the clients bundle it.

Both clients ship a copy of ``shared/record-completeness.json`` and swap it for this answer when its
``version`` is higher, so a rule change reaches an installed app without a release. The ETag lets
the web cache it in localStorage and Android's read cache revalidate it for a few bytes.
"""

from typing import Any

from fastapi import APIRouter, Depends, Request, Response

from app.core.deps import get_current_user
from app.services.completeness import RULES_BYTES, RULES_ETAG

router = APIRouter(prefix="/records", tags=["records"])


@router.get("/completeness-rules")
async def completeness_rules(
    request: Request, _: Any = Depends(get_current_user)
) -> Response:
    headers = {"ETag": RULES_ETAG, "Cache-Control": "private, no-cache"}
    sent = request.headers.get("if-none-match") or ""
    if RULES_ETAG in {tag.strip() for tag in sent.split(",")} or sent.strip() == "*":
        return Response(status_code=304, headers=headers)
    return Response(content=RULES_BYTES, media_type="application/json", headers=headers)
