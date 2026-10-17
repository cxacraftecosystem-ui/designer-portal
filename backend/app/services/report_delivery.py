"""DELIVERING AN APPROVED REPORT TO THE OFFICE (2026-10-18, owner decision C4).

"Approve and hand on" makes the report file, stores it, and e-mails the Development Commissioner
(Handicrafts) office a download link that expires after :data:`LINK_DAYS` days and can be re-issued.
The addresses come from an administrator's setting (``AppSetting.dcOfficeEmails``) and are never
written into code: with none set, the approver hands the report on by hand exactly as before and is
told where to set them.

THE LINK IS A CREDENTIAL FOR ONE FILE. Only the SHA-256 of its secret is stored
(``DwReportDelivery.tokenHash``); the link itself travels sealed on the outbox row, as a password
link does, and is cleared when the message is sent. Re-issuing revokes the previous link and writes a
new row over the same stored file. Opening it records when the office first and last opened it,
which is what the Ministry dashboard shows as "Received".
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import secrets
from datetime import UTC, datetime, timedelta
from typing import Any
from urllib.parse import quote

from app.core.config import Settings
from app.core.db import db
from app.schemas import design_workshop_approvals as plans
from app.services import app_settings, email_outbox, mailer, s3

logger = logging.getLogger(__name__)

LINK_DAYS = 7
#: The public page the e-mailed link opens. The token rides the query string, like a password link.
DOWNLOAD_PATH = "/report-download"
MAX_OFFICE_ADDRESSES = 10
_ADDRESS = re.compile(r"^[^@\s,;]+@[^@\s,;]+\.[^@\s,;]+$")

NOT_CONFIGURED = (
    "No office e-mail addresses are set, so the report was not sent. Hand it on yourself, or ask an "
    "administrator to add the addresses in Settings."
)


def clean_addresses(values: list[str]) -> tuple[list[str], list[str]]:
    """``(kept, refused)``: trimmed, lower-cased, de-duplicated addresses, and the entries that are
    not addresses at all."""
    kept: list[str] = []
    refused: list[str] = []
    for raw in values:
        for part in re.split(r"[,;\s]+", str(raw or "")):
            address = part.strip().lower()
            if not address:
                continue
            if not _ADDRESS.match(address):
                refused.append(part.strip())
            elif address not in kept:
                kept.append(address)
    return kept, refused


async def office_settings() -> dict[str, Any]:
    """``{addresses, officeName}`` as an administrator set them (no row means none)."""
    row = await app_settings.load_app_settings()
    addresses = list(getattr(row, "dcOfficeEmails", None) or []) if row is not None else []
    name = str(getattr(row, "dcOfficeName", None) or "").strip() if row is not None else ""
    return {"addresses": addresses, "officeName": name or plans.DEFAULT_OFFICE}


def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def link_for(token: str, settings: Settings | None = None) -> str:
    return mailer.app_url(f"{DOWNLOAD_PATH}?token={quote(token, safe='')}", settings)


def storage_key_for(workshop_id: str, file_name: str) -> str:
    stamp = datetime.now(UTC).strftime("%Y%m%d%H%M%S")
    return f"report-deliveries/{workshop_id}/{stamp}-{secrets.token_hex(4)}/{s3.safe_filename(file_name)}"


async def store_file(workshop_id: str, file_name: str, blob: bytes, mime_type: str) -> str:
    """Put the report file in the media bucket and return its key. Raises on a storage failure."""
    key = storage_key_for(workshop_id, file_name)
    await asyncio.to_thread(s3.put_object_bytes, key, blob, mime_type=mime_type)
    return key


async def send(
    record: Any,
    *,
    storage_key: str,
    file_name: str,
    export_id: str | None,
    actor: Any,
    addresses: list[str],
    office_name: str,
    sanction_order_no: str | None = None,
) -> Any:
    """Write one delivery row and e-mail its link to every address. Returns the row."""
    token = secrets.token_urlsafe(32)
    expires_at = datetime.now(UTC) + timedelta(days=LINK_DAYS)
    row = await db.dwreportdelivery.create(
        data={
            "designWorkshopId": record.id,
            "exportId": export_id,
            "storageKey": storage_key,
            "fileName": file_name,
            "tokenHash": token_digest(token),
            "toAddresses": list(addresses),
            "officeName": office_name,
            "expiresAt": expires_at,
            "sentById": getattr(actor, "id", None),
        }
    )
    link = link_for(token)
    for address in addresses[:MAX_OFFICE_ADDRESSES]:
        await email_outbox.enqueue(
            mailer.REPORT_DELIVERY,
            to_address=address,
            recipient_id=None,
            params={
                "workshopId": record.id,
                "workshopTitle": getattr(record, "title", None),
                "officeName": office_name,
                "approverName": getattr(actor, "name", None),
                "expiresAt": expires_at.isoformat(),
                "fileName": file_name,
                "sanctionOrderNo": sanction_order_no,
            },
            secret=link,
            topic=f"delivery:{row.id}",
        )
    logger.info("report delivery %s for workshop %s: %d address(es)", row.id, record.id, len(addresses))
    return row


async def latest(workshop_id: str) -> Any | None:
    return await db.dwreportdelivery.find_first(
        where={"designWorkshopId": workshop_id}, order={"createdAt": "desc"}
    )


async def reissue(record: Any, *, actor: Any) -> Any | None:
    """Revoke the live link and e-mail a new one over the same file, to the addresses set NOW.
    ``None`` when this report was never delivered or no addresses are set."""
    previous = await latest(record.id)
    office = await office_settings()
    if previous is None or not office["addresses"]:
        return None
    await db.dwreportdelivery.update_many(
        where={"designWorkshopId": record.id, "revokedAt": None},
        data={"revokedAt": datetime.now(UTC)},
    )
    return await send(
        record,
        storage_key=previous.storageKey,
        file_name=previous.fileName,
        export_id=previous.exportId,
        actor=actor,
        addresses=office["addresses"],
        office_name=previous.officeName or office["officeName"],
        sanction_order_no=await sanction_number(record.id),
    )


async def sanction_number(workshop_id: str) -> str | None:
    order = await db.sanctionorder.find_first(where={"designWorkshopId": workshop_id})
    return getattr(order, "sanctionOrderNo", None) if order is not None else None


def link_state(row: Any, now: datetime | None = None) -> str:
    """LIVE, EXPIRED or REVOKED."""
    moment = now or datetime.now(UTC)
    if getattr(row, "revokedAt", None) is not None:
        return "REVOKED"
    expires = row.expiresAt if row.expiresAt.tzinfo else row.expiresAt.replace(tzinfo=UTC)
    return "EXPIRED" if expires <= moment else "LIVE"


async def resolve(token: str | None) -> Any | None:
    """The delivery a presented link names, whatever its state, or ``None``."""
    clean = (token or "").strip()
    if not clean or len(clean) > 200 or not clean.isprintable():
        return None
    return await db.dwreportdelivery.find_unique(
        where={"tokenHash": token_digest(clean)}, include={"designWorkshop": True}
    )


async def record_open(row: Any) -> None:
    now = datetime.now(UTC)
    data: dict[str, Any] = {"lastOpenedAt": now, "openCount": {"increment": 1}}
    if getattr(row, "firstOpenedAt", None) is None:
        data["firstOpenedAt"] = now
    await db.dwreportdelivery.update(where={"id": row.id}, data=data)


def _iso(value: Any) -> str | None:
    return value.isoformat() if hasattr(value, "isoformat") else None


async def delivery_view(workshop_id: str) -> dict[str, Any]:
    """What the approver's screen shows: whether delivery is set up, and the latest delivery with
    the state of its link and of each e-mail."""
    office, row = await asyncio.gather(office_settings(), latest(workshop_id))
    view: dict[str, Any] = {
        "configured": bool(office["addresses"]) and mailer.mail_configured(),
        "addressCount": len(office["addresses"]),
        "officeName": office["officeName"],
        "linkDays": LINK_DAYS,
        "latest": None,
    }
    if row is None:
        return view
    messages = await db.emailmessage.find_many(
        where={"topic": f"delivery:{row.id}"}, order={"createdAt": "desc"}
    )
    emails = []
    seen: set[str] = set()
    for message in messages:
        if message.toAddress in seen:
            continue
        seen.add(message.toAddress)
        status = email_outbox.delivery_status(message) or {}
        emails.append({"address": message.toAddress, **status})
    view["latest"] = {
        "id": row.id,
        "sentAt": _iso(row.createdAt),
        "expiresAt": _iso(row.expiresAt),
        "state": link_state(row),
        "officeName": row.officeName,
        "fileName": row.fileName,
        "firstOpenedAt": _iso(row.firstOpenedAt),
        "lastOpenedAt": _iso(row.lastOpenedAt),
        "openCount": int(row.openCount or 0),
        "emails": emails,
    }
    return view


async def received_for(workshop_ids: list[str]) -> dict[str, dict[str, Any]]:
    """``{workshop id: {sentAt, firstOpenedAt, officeName}}`` — the Ministry dashboard's "Received"
    line, from each workshop's newest delivery. One query."""
    if not workshop_ids:
        return {}
    rows = await db.dwreportdelivery.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, order={"createdAt": "desc"}
    )
    found: dict[str, dict[str, Any]] = {}
    for row in rows:
        if row.designWorkshopId in found:
            continue
        first_opened = row.firstOpenedAt
        for other in rows:
            if (
                other.designWorkshopId == row.designWorkshopId
                and other.firstOpenedAt is not None
                and (first_opened is None or other.firstOpenedAt < first_opened)
            ):
                first_opened = other.firstOpenedAt
        found[row.designWorkshopId] = {
            "sentAt": _iso(row.createdAt),
            "firstOpenedAt": _iso(first_opened),
            "officeName": row.officeName,
        }
    return found
