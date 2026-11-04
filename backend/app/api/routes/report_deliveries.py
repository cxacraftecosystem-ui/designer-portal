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
