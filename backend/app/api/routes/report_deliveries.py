"""``/api/report-deliveries`` — the office opening an approved report it was e-mailed (2026-10-18).

THE ONLY CALLER HAS NO ACCOUNT. The Development Commissioner (Handicrafts) office is sent a link by
"Approve and hand on" (``services/report_delivery``); the link's secret is the credential, so both
routes are anonymous and listed in ``tests/test_anonymous_route_sweep.py``. The token travels in the
JSON body, never on the request line, so it stays out of access logs (the rule the set-password check
follows). A link that is unknown answers 404, an expired or re-issued one 410, and nothing about the
workshop is said beyond what the e-mail already said.
"""

from __future__ import annotations

import asyncio
from typing import Any

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from app.services import report_delivery, s3

router = APIRouter(prefix="/report-deliveries", tags=["design-workshops"])

UNKNOWN = "This link is not valid. Ask the sender for a new one."
EXPIRED = "This link has expired. Ask the sender to send it again."
REVOKED = "A newer link was sent for this report. Use the latest e-mail, or ask the sender."

_MIME = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}


class DeliveryTokenIn(BaseModel):
    token: str = Field(min_length=1, max_length=200)


async def _live_or_refuse(token: str) -> Any:
    row = await report_delivery.resolve(token)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=UNKNOWN)
    state = report_delivery.link_state(row)
    if state == "EXPIRED":
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=EXPIRED)
    if state == "REVOKED":
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=REVOKED)
    return row


@router.post("/lookup")
async def lookup_delivery(payload: DeliveryTokenIn) -> dict[str, Any]:
    """What the download page shows before the file: the report's title, the file name, the office
    it was sent to and when the link expires."""
    row = await _live_or_refuse(payload.token)
    workshop = getattr(row, "designWorkshop", None)
    return {
        "workshopTitle": getattr(workshop, "title", None),
        "fileName": row.fileName,
        "officeName": row.officeName,
        "expiresAt": row.expiresAt.isoformat(),
    }


@router.post("/download")
async def download_delivery(payload: DeliveryTokenIn) -> dict[str, Any]:
    """A short-lived signed URL for the file, and the open recorded (the Ministry dashboard's
    "Received")."""
    row = await _live_or_refuse(payload.token)
    extension = row.fileName.rsplit(".", 1)[-1].lower() if "." in row.fileName else ""
    url = await asyncio.to_thread(
        s3.presign_get_url,
        row.storageKey,
        filename=row.fileName,
        mime_type=_MIME.get(extension, "application/octet-stream"),
        expires_in=300,
    )
    await report_delivery.record_open(row)
    return {"url": url, "fileName": row.fileName}
