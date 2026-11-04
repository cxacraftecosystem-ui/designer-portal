import logging
import re
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import is_admin
from app.services import design_workshop_posts as posts, email_outbox
from app.services.design_workshop_viewers import (
    _assert_every_id_may_be_granted,
    has_viewer_grant,
)
from app.services.records import plain

logger = logging.getLogger(__name__)

DECISIONS = ("GRANTED", "DENIED")

STATUS_FILTERS = ("PENDING", "GRANTED", "DENIED", "ALL")

_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


_CODE_NAMESPACE = "DPW"

_SUPPORTED_CODE_VERSIONS = frozenset({1})

_DESIGN_WORKSHOP_LETTER = "G"

_JOIN_CARD_LETTER = "J"

_CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
_CHECK_LENGTH = 4

_CHECK_CONFUSABLES = {"I": "1", "L": "1", "O": "0"}

_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{7,63}$")

_DEVICE_LOCAL_ID_PREFIXES = ("dwlocal-", "local-")

_ASCII_DIGITS = re.compile(r"^[0-9]+$")


class ScannedCodeRefused(Exception):
    def __init__(self, detail: str) -> None:
        super().__init__(detail)
        self.detail = detail


def code_check(prefix: str) -> str:
    value = 0x811C9DC5
    for character in prefix:
        value ^= ord(character)
        value = (value * 16777619) & 0xFFFFFFFF
    low = value & 0xFFFFF
    return "".join(
        _CHECK_ALPHABET[(low >> shift) & 31] for shift in range((_CHECK_LENGTH - 1) * 5, -1, -5)
    )


def decode_design_workshop_code(raw: str) -> tuple[str, str]:
    text = re.sub(r"\s+", "", raw or "").upper()
    if not text:
        raise ScannedCodeRefused("Nothing was scanned or typed.")

    parts = text.split(":")
    if len(parts) != 4 or not parts[0].startswith(_CODE_NAMESPACE):
        raise ScannedCodeRefused(
            "That is not a workshop card or tag. Workshop codes begin “DPW”; a shop barcode, a "
            "payment code or a web address will not open a record here."
        )

    version_text = parts[0][len(_CODE_NAMESPACE) :]
    if not _ASCII_DIGITS.match(version_text):
        raise ScannedCodeRefused(
            "That is not a workshop card or tag. Workshop codes begin “DPW” followed by a version "
            "number."
        )
    version = int(version_text)

    if parts[1] == _JOIN_CARD_LETTER:
        raise ScannedCodeRefused(
            "That is a join card, not a workshop tag — scanning it lets you straight in rather than "
            "asking somebody to add you. Scan it on the join screen instead. If your app has no "
            "such screen, update it, or send an administrator the workshop's own tag and ask to be "
            "added."
        )

    if version not in _SUPPORTED_CODE_VERSIONS:
        raise ScannedCodeRefused(
            f"That card uses a newer code format ({version}). Update the app, or ask an "
            f"administrator to add you to the workshop."
        )

    if parts[1] != _DESIGN_WORKSHOP_LETTER:
        raise ScannedCodeRefused(
            "That code does not name a design workshop — it points "
            "at a different kind of record. Scan the workshop's own card, the one the person who "
            "created it printed."
        )

    workshop_id = parts[2].lower()
    if workshop_id.startswith(_DEVICE_LOCAL_ID_PREFIXES):
        raise ScannedCodeRefused(
            "That code names a workshop that had not been shared yet when it was written down — it "
            "only ever meant anything on the device that made it. Ask whoever created the workshop "
            "to sync their device and send you the code it prints then."
        )
    if not _ID_PATTERN.match(workshop_id):
        raise ScannedCodeRefused(
            "This code is damaged or was typed incompletely — the identifier in it is not a whole "
            "one. Check it against the card."
        )

    typed_check = "".join(_CHECK_CONFUSABLES.get(character, character) for character in parts[3])
    prefix = f"{_CODE_NAMESPACE}{version}:{parts[1]}:{parts[2]}"
    if len(typed_check) != _CHECK_LENGTH or typed_check != code_check(prefix):
        raise ScannedCodeRefused(
            "This code does not check out, so one of its characters is wrong. Read it off the card "
            "again, character by character — a single wrong character points at a different "
            "workshop, and nothing later would notice."
        )

    return workshop_id, f"{prefix}:{typed_check}"



async def scanned_workshop_title(scanned_code: str) -> dict[str, str] | None:
    workshop_id, _code = decode_design_workshop_code(scanned_code)
    workshop = await db.designworkshop.find_unique(where={"id": workshop_id})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        return None
    title = plain(getattr(workshop, "title", "") or "").strip()
    if not title:
        return None
    return {"workshopId": workshop.id, "title": title}



async def file_request(
    user: Any,
    *,
    workshop_id: str,
    scanned_code: str | None,
    note: str | None,
    scanned_at: datetime | None = None,
) -> None:
    wanted = plain(workshop_id).strip().lower()

    code = None
    if scanned_code is not None and scanned_code.strip():
        decoded_id, code = decode_design_workshop_code(scanned_code)
        if decoded_id != wanted:
            raise ScannedCodeRefused(
                "The code that was scanned names a different workshop from the one this request is "
                "for. Scan the card again from the workshop you mean to join."
            )

    if not wanted or _UNSTORABLE_IN_AN_ID.search(workshop_id):
        return

    workshop = await db.designworkshop.find_unique(where={"id": wanted})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        return

    user_id = getattr(user, "id", "")
    if not user_id:
        return
    if workshop.createdById == user_id or is_admin(user) or await has_viewer_grant(wanted, user_id):
        return

    now = datetime.now(UTC)
    source = "SCAN" if code else "MANUAL"
    clean_note = plain(note) if note else None

    created = await db.designworkshopaccessrequest.create_many(
        data=[
            {
                "designWorkshopId": wanted,
                "requestedById": user_id,
                "status": "PENDING",
                "source": source,
                "scannedCode": code,
                "note": clean_note,
                "scannedAt": scanned_at,
            }
        ],
        skip_duplicates=True,
    )
    if created:
        await email_outbox.notify_workshop_access_requested(workshop, user, clean_note)
        return

    reopened = await db.designworkshopaccessrequest.update_many(
        where={
            "designWorkshopId": wanted,
            "requestedById": user_id,
            "status": "GRANTED",
        },
        data={
            "status": "PENDING",
            "source": source,
            "scannedCode": code,
            "note": clean_note,
            "scannedAt": scanned_at,
            "createdAt": now,
        },
    )
    if reopened:
        await email_outbox.notify_workshop_access_requested(workshop, user, clean_note)
        logger.info(
            "design-workshop access request reopened: a previously granted viewer has asked again "
            "after their grant was removed (workshop=%s)",
            wanted,
        )



def _enum(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso(value: Any) -> str | None:
    return value.isoformat() if value is not None else None


def request_payload(row: Any, *, has_access: bool, is_provisional: bool = False) -> dict[str, Any]:
    workshop = getattr(row, "designWorkshop", None)
    requester = getattr(row, "requestedBy", None)
    decider = getattr(row, "decidedBy", None)
    return {
        "id": row.id,
        "workshop": {
            "id": row.designWorkshopId,
            "title": getattr(workshop, "title", "") or "",
            "workshopCode": getattr(workshop, "workshopCode", None),
        },
        "requestedBy": {
            "id": row.requestedById,
            "name": getattr(requester, "name", "") or "",
            "email": getattr(requester, "email", "") or "",
            "role": _enum(getattr(requester, "role", None)) or "",
        },
        "status": _enum(row.status),
        "source": _enum(row.source),
        "scannedCode": row.scannedCode,
        "tokenId": row.tokenId,
        "scannedAt": _iso(row.scannedAt),
        "note": row.note,
        "createdAt": _iso(row.createdAt),
        "decidedAt": _iso(row.decidedAt),
        "decisionNote": row.decisionNote,
        "decidedBy": (
            None
            if decider is None
            else {
                "id": row.decidedById,
                "name": getattr(decider, "name", "") or "",
                "email": getattr(decider, "email", "") or "",
            }
        ),
        "requesterHasAccess": has_access,
        "requesterIsProvisional": is_provisional,
    }


_QUEUE_INCLUDE = {"designWorkshop": True, "requestedBy": True, "decidedBy": True}

QUEUE_LIMIT = 500


async def queue(status_filter: str = "PENDING") -> dict[str, Any]:
    where: dict[str, Any] = {}
    if status_filter != "ALL":
        where["status"] = status_filter

    rows = await db.designworkshopaccessrequest.find_many(
        where=where,
        include=_QUEUE_INCLUDE,
        order=[{"createdAt": "asc"}, {"id": "asc"}],
        take=QUEUE_LIMIT + 1,
    )
    truncated = len(rows) > QUEUE_LIMIT
    if truncated:
        rows = rows[:QUEUE_LIMIT]
        logger.warning(
            "the design-workshop access queue hit its ceiling of %s rows (statusFilter=%s); the "
            "answer is truncated and says so",
            QUEUE_LIMIT,
            status_filter,
        )

    access = await _access_by_pair(rows)
    provisional = await _provisional_by_pair(rows)
    return {
        "requests": [
            request_payload(
                row,
                has_access=(row.designWorkshopId, row.requestedById) in access,
                is_provisional=(row.designWorkshopId, row.requestedById) in provisional,
            )
            for row in rows
        ],
        "truncated": truncated,
    }


async def _provisional_by_pair(rows: list[Any]) -> set[tuple[str, str]]:
    if not rows:
        return set()
    workshop_ids = sorted({row.designWorkshopId for row in rows})
    footholds = await db.designworkshopprovisionalmember.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}
    )
    return {(row.designWorkshopId, row.userId) for row in footholds}


async def _access_by_pair(rows: list[Any]) -> set[tuple[str, str]]:
    if not rows:
        return set()
    workshop_ids = sorted({row.designWorkshopId for row in rows})
    viewer_rows = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}
    )
    granted = {(row.designWorkshopId, row.userId) for row in viewer_rows}

    for row in rows:
        workshop = getattr(row, "designWorkshop", None)
        requester = getattr(row, "requestedBy", None)
        is_creator = getattr(workshop, "createdById", None) == row.requestedById
        is_administrator = _enum(getattr(requester, "role", None)) in {"ADMIN", "MASTER_ADMIN"}
        if is_creator or is_administrator:
            granted.add((row.designWorkshopId, row.requestedById))
    return granted



async def add_one_viewer(
    tx: Any,
    *,
    workshop_id: str,
    user_id: str,
    granted_by_id: str | None,
    token_id: str | None = None,
) -> None:
    await tx.designworkshopviewer.create_many(
        data=[
            {
                "designWorkshopId": workshop_id,
                "userId": user_id,
                "grantedById": granted_by_id,
                "tokenId": token_id,
            }
        ],
        skip_duplicates=True,
    )



async def decide(request_id: str, *, decision: str, note: str | None, admin: Any) -> dict[str, Any]:
    if decision not in DECISIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A decision is one of {', '.join(DECISIONS)}.",
        )

    row = await db.designworkshopaccessrequest.find_unique(
        where={"id": plain(request_id).strip()}, include=_QUEUE_INCLUDE
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    workshop = getattr(row, "designWorkshop", None)
    if workshop is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")

    await posts.refuse_a_holders_write(row.designWorkshopId, admin)

    if decision == "GRANTED":
        if row.requestedById != workshop.createdById:
            await _assert_every_id_may_be_granted(
                {row.requestedById},
                workshop_id=row.designWorkshopId,
                appointing=getattr(admin, "id", None),
            )
            async with db.tx() as tx:
                await add_one_viewer(
                    tx,
                    workshop_id=row.designWorkshopId,
                    user_id=row.requestedById,
                    granted_by_id=getattr(admin, "id", None),
                )
                await tx.designworkshopprovisionalmember.delete_many(
                    where={"designWorkshopId": row.designWorkshopId, "userId": row.requestedById}
                )
        else:
            await db.designworkshopprovisionalmember.delete_many(
                where={"designWorkshopId": row.designWorkshopId, "userId": row.requestedById}
            )
    elif (row.designWorkshopId, row.requestedById) in await _access_by_pair([row]):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                "This person can already open the workshop, so refusing the request would change "
                "nothing they can see. Take them off the workshop's viewers screen if you mean to "
                "remove their access; that is the only place a grant is undone."
            ),
        )

    if decision == "DENIED":
        await db.designworkshopprovisionalmember.delete_many(
            where={"designWorkshopId": row.designWorkshopId, "userId": row.requestedById}
        )

    await db.designworkshopaccessrequest.update(
        where={"id": row.id},
        data={
            "status": decision,
            "decidedById": getattr(admin, "id", None),
            "decidedAt": datetime.now(UTC),
            "decisionNote": plain(note) if note else None,
        },
    )
    fresh = await db.designworkshopaccessrequest.find_unique(
        where={"id": row.id}, include=_QUEUE_INCLUDE
    )
    if fresh is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    await email_outbox.notify_access_decided(
        row.requestedById,
        scope="workshop",
        decision=decision,
        actor=admin,
        workshop=workshop,
        note=note,
    )
    access = await _access_by_pair([fresh])
    provisional = await _provisional_by_pair([fresh])
    return request_payload(
        fresh,
        has_access=(fresh.designWorkshopId, fresh.requestedById) in access,
        is_provisional=(fresh.designWorkshopId, fresh.requestedById) in provisional,
    )
