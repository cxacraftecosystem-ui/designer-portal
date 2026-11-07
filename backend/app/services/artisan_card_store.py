from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.services import artisan_card

ACTION_ISSUE = "ISSUE"
ACTION_REISSUE = "REISSUE"
ACTION_PRINT = "PRINT"
ACTION_RESOLVE = "RESOLVE"
ACTION_RESOLVE_MISS = "RESOLVE_MISS"
ACTION_PEER_RESOLVE = "PEER_RESOLVE"

SOURCE_WEB = "WEB"
SOURCE_ANDROID = "ANDROID"
SOURCE_PEER = "PEER"
SOURCES = (SOURCE_WEB, SOURCE_ANDROID, SOURCE_PEER)

MISS_WINDOW = timedelta(minutes=10)

TOO_MANY_MISSES = "Too many card codes that matched nobody. Wait a few minutes and try again."

_ISSUE_ATTEMPTS = 4


def settings_or_404() -> Any:
    settings = get_settings()
    if not artisan_card.active(settings):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


def issuer(settings: Any) -> str:
    value = artisan_card.issuer_of(settings)
    return value if value in artisan_card.ISSUERS else "D"


def key_version(settings: Any) -> int:
    return artisan_card.key_version(settings)


def card_wire(card: Any, settings: Any) -> dict[str, str]:
    mine = issuer(settings)
    version = int(getattr(card, "keyVersion", None) or key_version(settings))
    key = artisan_card.key_for_version(settings, version)
    if key is None:
        version, key = key_version(settings), settings.artisan_card_key
    return {
        "code": card.code,
        "shortCode": artisan_card.short_code(mine, card.code),
        "token": artisan_card.payload(key, mine, version, card.code),
    }


def _is_unique_violation(error: Exception) -> bool:
    name = type(error).__name__
    return name == "UniqueViolationError" or "unique" in str(error).lower()


async def log_event(
    action: str,
    source: str,
    *,
    user_id: str | None,
    artisan_id: str | None = None,
    card_code: str | None = None,
    count: int = 1,
) -> None:
    await db.artisancardevent.create(
        data={
            "action": action,
            "source": source if source in SOURCES else SOURCE_WEB,
            "userId": user_id,
            "artisanId": artisan_id,
            "cardCode": card_code,
            "count": max(1, int(count)),
        }
    )


async def live_card(artisan_id: str) -> Any | None:
    return await db.artisancard.find_first(where={"artisanId": artisan_id, "revokedAt": None})


async def replaced_count(artisan_id: str) -> int:
    return await db.artisancard.count(where={"artisanId": artisan_id, "revokedAt": {"not": None}})


async def ensure_card(
    artisan_id: str, *, user_id: str | None, source: str, settings: Any | None = None
) -> Any:
    settings = settings or get_settings()
    card = await live_card(artisan_id)
    if card is not None:
        return card
    for _ in range(_ISSUE_ATTEMPTS):
        try:
            card = await db.artisancard.create(
                data={
                    "artisanId": artisan_id,
                    "code": artisan_card.new_code(),
                    "keyVersion": key_version(settings),
                    "issuedById": user_id,
                }
            )
        except Exception as exc:
            if not _is_unique_violation(exc):
                raise
            existing = await live_card(artisan_id)
            if existing is not None:
                return existing
            continue
        await log_event(
            ACTION_ISSUE, source, user_id=user_id, artisan_id=artisan_id, card_code=card.code
        )
        return card
    raise HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="The card couldn't be issued. Try again."
    )


async def ensure_cards(
    artisan_ids: list[str], *, user_id: str | None, source: str, settings: Any | None = None
) -> dict[str, Any]:
    settings = settings or get_settings()
    ids = list(dict.fromkeys(artisan_ids))
    if not ids:
        return {}
    for _ in range(_ISSUE_ATTEMPTS):
        rows = await db.artisancard.find_many(where={"artisanId": {"in": ids}, "revokedAt": None})
        cards = {row.artisanId: row for row in rows}
        missing = [artisan_id for artisan_id in ids if artisan_id not in cards]
        if not missing:
            return cards
        created = await db.artisancard.create_many(
            data=[
                {
                    "artisanId": artisan_id,
                    "code": artisan_card.new_code(),
                    "keyVersion": key_version(settings),
                    "issuedById": user_id,
                }
                for artisan_id in missing
            ],
            skip_duplicates=True,
        )
        if created:
            await log_event(ACTION_ISSUE, source, user_id=user_id, count=created)
    rows = await db.artisancard.find_many(where={"artisanId": {"in": ids}, "revokedAt": None})
    return {row.artisanId: row for row in rows}


async def reissue(
    artisan_id: str, reason: str, *, user_id: str | None, source: str, settings: Any | None = None
) -> Any:
    settings = settings or get_settings()
    now = datetime.now(UTC)
    for _ in range(_ISSUE_ATTEMPTS):
        try:
            async with db.tx() as tx:
                await tx.artisancard.update_many(
                    where={"artisanId": artisan_id, "revokedAt": None},
                    data={"revokedAt": now, "revokedById": user_id, "revokedReason": reason},
                )
                card = await tx.artisancard.create(
                    data={
                        "artisanId": artisan_id,
                        "code": artisan_card.new_code(),
                        "keyVersion": key_version(settings),
                        "issuedById": user_id,
                    }
                )
        except Exception as exc:
            if not _is_unique_violation(exc):
                raise
            continue
        await log_event(
            ACTION_REISSUE, source, user_id=user_id, artisan_id=artisan_id, card_code=card.code
        )
        return card
    raise HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="The card couldn't be issued. Try again."
    )


async def assert_under_miss_limit(user_id: str | None, settings: Any) -> None:
    limit = int(getattr(settings, "artisan_card_resolve_miss_limit", 20) or 20)
    since = datetime.now(UTC) - MISS_WINDOW
    misses = await db.artisancardevent.count(
        where={"userId": user_id, "action": ACTION_RESOLVE_MISS, "createdAt": {"gte": since}}
    )
    if misses >= limit:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=TOO_MANY_MISSES)



def cluster_of(artisan: Any) -> str | None:
    workshop = getattr(artisan, "designWorkshop", None)
    location = getattr(artisan, "location", None)
    for value in (
        getattr(workshop, "clusterName", None) if workshop is not None else None,
        getattr(location, "village", None) if location is not None else None,
        getattr(location, "district", None) if location is not None else None,
        getattr(artisan, "place", None),
    ):
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


async def photo_of(artisan_id: str, viewer_owners: set[str] | None) -> Any | None:
    return (await photos_of([artisan_id], viewer_owners)).get(artisan_id)


async def photos_of(artisan_ids: list[str], viewer_owners: set[str] | None) -> dict[str, Any]:
    from app.services.records import media_url_allows

    uploaders = None if viewer_owners is None else {*viewer_owners, *getattr(viewer_owners, "scoped", {})}
    if not artisan_ids or (uploaders is not None and not uploaders):
        return {}
    where: dict[str, Any] = {"artisanId": {"in": artisan_ids}, "mediaType": "IMAGE", "removedAt": None}
    if uploaders is not None:
        where["uploadedById"] = {"in": sorted(uploaders)}
    rows = await db.mediafile.find_many(where=where, order={"createdAt": "asc"})
    out: dict[str, Any] = {}
    for row in rows:
        if media_url_allows(viewer_owners, row):
            out.setdefault(row.artisanId, row)
    return out


def face(artisan: Any, photo: Any | None) -> dict[str, Any]:
    from app.services import aadhaar_fingerprint

    craft = getattr(artisan, "craft", None)
    last4 = aadhaar_fingerprint.stored_last4(artisan)
    return {
        "name": artisan.name,
        "localName": getattr(artisan, "localName", None) or None,
        "craft": getattr(craft, "name", None) if craft is not None else None,
        "cluster": cluster_of(artisan),
        "aadhaarLast4": last4 if last4 and len(last4) == 4 and last4.isdigit() else None,
        "photoMediaId": getattr(photo, "id", None) if photo is not None else None,
    }


FACE_INCLUDE = {"craft": True, "location": True, "designWorkshop": True}
