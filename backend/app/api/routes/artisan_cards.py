"""Artisan QR cards: read, reissue, print one or a sheet, and resolve a scanned or typed code.

Every route answers 404 while cards are off here (``artisan_card.active``: the flag and a key), and
``/artisan-cards/status`` says ``enabled:false`` so neither client draws a card element.

Resolution, in order (``POST /artisan-cards/resolve``):

1. Parse (``services/artisan_card.parse``): a mistyped short code is a 422 before anything is asked.
2. A card this portal issued: the QR's tag is verified (a typed code has none), then the code is
   looked up. A live card is FOUND; a revoked one is REPLACED, with the artisan it belonged to.
3. A Field Repository card: the link that stores its code (``CrossPortalArtisanLink.remoteCardCode``)
   answers FOUND via LINK with no network call.
4. Otherwise the Field Repository is asked (``/cross-portal/peer/cards/resolve``, signed). Its
   artisan linked here: FOUND via LINK, and the code is stored for next time. Not linked: REMOTE,
   and with the designer's consent tick, the same prefill ``/cross-portal/artisans/lookup`` returns.

Every kind of miss is the same 404 sentence and counts toward the per-account miss limit; hits
never count. Every issue, reissue, print and lookup is written to ``ArtisanCardEvent``.
"""

from __future__ import annotations

import asyncio
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field

from app.api.routes import cross_portal as cross_portal_routes
from app.core.db import db
from app.core.deps import can_create_records, get_current_user, get_value
from app.services import (
    artisan_card,
    artisan_card_pdf,
    artisan_card_store as store,
    cross_portal,
    naming,
)
from app.services.records import media_url_owners, require_record

router = APIRouter(tags=["artisan-cards"])

PORTAL_NAME = "Design Prototype Workshop"

#: Cards one sheet request may print.
SHEET_LIMIT = 200

NO_MATCH = "No artisan matches this card."
BAD_CHECK = "Check the code. One character is wrong."
TOO_MANY_CARDS = f"Print at most {SHEET_LIMIT} cards at a time."
ONE_SOURCE = "Choose the artisans or the workshop, not both."


def _source(value: str | None) -> str:
    value = (value or "").upper()
    return value if value in (store.SOURCE_WEB, store.SOURCE_ANDROID) else store.SOURCE_WEB


async def _artisan_with_face(artisan_id: str) -> Any:
    await require_record(db.artisan, artisan_id)
    return await db.artisan.find_unique(where={"id": artisan_id}, include=store.FACE_INCLUDE)


async def _card_json(artisan: Any, card: Any, settings: Any, viewer: Any) -> dict[str, Any]:
    wire = store.card_wire(card, settings)
    photo = await store.photo_of(artisan.id, await media_url_owners(viewer))
    return {
        "artisanId": artisan.id,
        "code": wire["code"],
        "shortCode": wire["shortCode"],
        "token": wire["token"],
        "issuedAt": card.issuedAt,
        "replacedCards": await store.replaced_count(artisan.id),
        "face": store.face(artisan, photo),
        "qrSvg": artisan_card_pdf.qr_svg(wire["token"]),
    }


@router.get("/artisan-cards/status")
async def card_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    from app.core.config import get_settings

    settings = get_settings()
    return {"enabled": artisan_card.active(settings), "issuer": store.issuer(settings)}


@router.get("/artisans/{artisan_id}/card")
async def get_card(
    artisan_id: str,
    source: str | None = Query(None, max_length=16),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    settings = store.settings_or_404()
    artisan = await _artisan_with_face(artisan_id)
    card = await store.ensure_card(
        artisan_id, user_id=get_value(current_user, "id"), source=_source(source), settings=settings
    )
    return await _card_json(artisan, card, settings, current_user)


class ReissueIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    reason: Literal["LOST", "DAMAGED", "OTHER"]
    source: str | None = Field(default=None, max_length=16)


@router.post("/artisans/{artisan_id}/card/reissue")
async def reissue_card(
    artisan_id: str, payload: ReissueIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    settings = store.settings_or_404()
    artisan = await _artisan_with_face(artisan_id)
    # The same people who may edit the artisan (cross_portal._assert_may_edit says who).
    await cross_portal_routes._assert_may_edit(artisan, current_user)
    card = await store.reissue(
        artisan_id,
        payload.reason,
        user_id=get_value(current_user, "id"),
        source=_source(payload.source),
        settings=settings,
    )
    # A linked artisan: tell the Field Repository the new code. A failed push is sent again by the
    # next refresh of the link.
    await cross_portal_routes.push_card_code(artisan_id, card.code, current_user)
    return await _card_json(artisan, card, settings, current_user)


def _faces(
    artisans: list[Any], cards: dict[str, Any], photos: dict[str, bytes], settings: Any
) -> list[artisan_card_pdf.CardFace]:
    out: list[artisan_card_pdf.CardFace] = []
    for artisan in artisans:
        wire = store.card_wire(cards[artisan.id], settings)
        face = store.face(artisan, None)
        out.append(
            artisan_card_pdf.CardFace(
                name=face["name"],
                local_name=face["localName"],
                craft=face["craft"],
                cluster=face["cluster"],
                aadhaar_last4=face["aadhaarLast4"],
                token=wire["token"],
                short_code=wire["shortCode"],
                photo=photos.get(artisan.id),
            )
        )
    return out


async def _photo_bytes(artisan_ids: list[str], viewer: Any, wanted: bool) -> dict[str, bytes]:
    if not wanted:
        return {}
    rows = await store.photos_of(artisan_ids, await media_url_owners(viewer))
    keys = {artisan_id: row.objectKey for artisan_id, row in rows.items() if row.objectKey}
    return await asyncio.to_thread(artisan_card_pdf.load_photos, keys)


def _card_file_name(faces: list[Any]) -> str:
    """One card names its artisan; a sheet is the day's artisan cards (services/naming.py)."""
    if len(faces) == 1:
        face = faces[0]
        return naming.export_filename(
            "Artisan", "Artisan-Card", "pdf", place=face.cluster, craft=face.craft, subject=face.name
        )
    return naming.export_filename("Artisan", "Artisan-Cards", "pdf")


def _pdf(content: bytes, filename: str) -> Response:
    return Response(
        content=content,
        media_type="application/pdf",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )


@router.get("/artisans/{artisan_id}/card.pdf")
async def card_pdf(
    artisan_id: str,
    size: Literal["id1", "a6"] = "id1",
    photo: int = Query(1, ge=0, le=1),
    source: str | None = Query(None, max_length=16),
    current_user: Any = Depends(get_current_user),
) -> Response:
    settings = store.settings_or_404()
    artisan = await _artisan_with_face(artisan_id)
    user_id = get_value(current_user, "id")
    card = await store.ensure_card(artisan_id, user_id=user_id, source=_source(source), settings=settings)
    photos = await _photo_bytes([artisan_id], current_user, bool(photo))
    faces = _faces([artisan], {artisan_id: card}, photos, settings)
    content = await asyncio.to_thread(
        artisan_card_pdf.render_cards, faces, size, PORTAL_NAME, photo=bool(photo)
    )
    await store.log_event(
        store.ACTION_PRINT, _source(source), user_id=user_id, artisan_id=artisan_id, card_code=card.code
    )
    return _pdf(content, _card_file_name(faces))


class SheetIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    artisanIds: list[str] | None = Field(default=None, max_length=SHEET_LIMIT)
    designWorkshopId: str | None = Field(default=None, max_length=64)
    size: Literal["id1", "a6"] = "id1"
    photo: bool = True
    source: str | None = Field(default=None, max_length=16)


async def _roster_ids(workshop_id: str, user: Any) -> list[str]:
    """The workshop's participants in roster order, then any other artisan filed under it."""
    from app.services.artisan_import import ENTITY_PARTICIPANT, STAGE_PARTICIPANTS
    from app.services.design_workshops import load_workshop_or_404

    workshop = await load_workshop_or_404(workshop_id, user)
    entries = await db.dwstageentry.find_many(
        where={
            "designWorkshopId": workshop.id,
            "stageKey": STAGE_PARTICIPANTS,
            "entityKey": ENTITY_PARTICIPANT,
            "deletedAt": None,
        },
        order={"ordinal": "asc"},
    )
    ids: list[str] = []
    for entry in entries:
        data = getattr(entry, "data", None)
        ref = data.get("artisanRef") if isinstance(data, dict) else None
        if isinstance(ref, str) and ref and ref not in ids:
            ids.append(ref)
    filed = await db.artisan.find_many(
        where={"designWorkshopId": workshop.id}, order={"name": "asc"}
    )
    ids += [row.id for row in filed if row.id not in ids]
    return ids


@router.post("/artisan-cards/sheet.pdf")
async def sheet_pdf(payload: SheetIn, current_user: Any = Depends(get_current_user)) -> Response:
    settings = store.settings_or_404()
    if (payload.artisanIds is None) == (payload.designWorkshopId is None):
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=ONE_SOURCE)
    if payload.artisanIds is not None:
        ids = list(dict.fromkeys(payload.artisanIds))
        strict = True
    else:
        ids = await _roster_ids(payload.designWorkshopId or "", current_user)
        strict = False
    if not ids:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="There are no artisans to print."
        )
    if len(ids) > SHEET_LIMIT:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=TOO_MANY_CARDS)
    rows = await db.artisan.find_many(where={"id": {"in": ids}}, include=store.FACE_INCLUDE)
    by_id = {row.id: row for row in rows}
    unknown = [artisan_id for artisan_id in ids if artisan_id not in by_id]
    if unknown and strict:
        # All or nothing: a sheet quietly short of two cards is found out at the workshop.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"{len(unknown)} of these artisans can't be printed by you.",
        )
    artisans = [by_id[artisan_id] for artisan_id in ids if artisan_id in by_id]
    if not artisans:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="There are no artisans to print."
        )
    user_id = get_value(current_user, "id")
    source = _source(payload.source)
    cards = await store.ensure_cards(
        [a.id for a in artisans], user_id=user_id, source=source, settings=settings
    )
    photos = await _photo_bytes([a.id for a in artisans], current_user, payload.photo)
    faces = _faces(artisans, cards, photos, settings)
    if payload.size == "id1":
        content = await asyncio.to_thread(
            artisan_card_pdf.render_sheet, faces, PORTAL_NAME, photo=payload.photo
        )
    else:
        content = await asyncio.to_thread(
            artisan_card_pdf.render_cards, faces, "a6", PORTAL_NAME, photo=payload.photo
        )
    await store.log_event(store.ACTION_PRINT, source, user_id=user_id, count=len(faces))
    return _pdf(content, _card_file_name(faces))


# ── Resolve ───────────────────────────────────────────────────────────────────────────────────


class ResolveIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    input: str = Field(max_length=120)
    source: str | None = Field(default=None, max_length=16)
    consent: bool = False


def _summary(artisan: Any) -> dict[str, Any]:
    face = store.face(artisan, None)
    return {
        "id": artisan.id,
        "name": face["name"],
        "craft": face["craft"],
        "cluster": face["cluster"],
        "place": getattr(artisan, "place", None),
        "aadhaarLast4": face["aadhaarLast4"],
    }


async def _miss(user_id: str | None, source: str, code: str | None, detail: str = NO_MATCH) -> HTTPException:
    await store.log_event(store.ACTION_RESOLVE_MISS, source, user_id=user_id, card_code=code)
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=detail)


async def _found(artisan_id: str, via: str, user_id: str | None, source: str, code: str) -> dict[str, Any] | None:
    artisan = await db.artisan.find_unique(where={"id": artisan_id}, include=store.FACE_INCLUDE)
    if artisan is None:
        return None
    await store.log_event(store.ACTION_RESOLVE, source, user_id=user_id, artisan_id=artisan_id, card_code=code)
    return {"status": "FOUND", "via": via, "artisan": _summary(artisan)}


@router.post("/artisan-cards/resolve")
async def resolve_card(payload: ResolveIn, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    settings = store.settings_or_404()
    user_id = get_value(current_user, "id")
    source = _source(payload.source)
    await store.assert_under_miss_limit(user_id, settings)
    try:
        parsed = artisan_card.parse(payload.input)
    except artisan_card.CardParseError:
        # One sentence for every way a code can be wrong, as on the Field Repository.
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=BAD_CHECK) from None

    if parsed.issuer == store.issuer(settings):
        if not artisan_card.verify(settings, parsed):
            raise await _miss(user_id, source, parsed.code)
        card = await db.artisancard.find_unique(where={"code": parsed.code})
        if card is None:
            raise await _miss(user_id, source, parsed.code)
        if card.revokedAt is not None:
            if await db.artisan.find_unique(where={"id": card.artisanId}) is None:
                raise await _miss(user_id, source, parsed.code)
            await store.log_event(
                store.ACTION_RESOLVE, source, user_id=user_id, artisan_id=card.artisanId, card_code=parsed.code
            )
            return {"status": "REPLACED", "artisanId": card.artisanId}
        answer = await _found(card.artisanId, "LOCAL", user_id, source, parsed.code)
        if answer is None:
            raise await _miss(user_id, source, parsed.code)
        return answer

    # A Field Repository card. A link that already holds its code answers with no network call.
    link = await db.crossportalartisanlink.find_unique(where={"remoteCardCode": parsed.code})
    if link is not None:
        answer = await _found(link.artisanId, "LINK", user_id, source, parsed.code)
        if answer is not None:
            return answer
    if not cross_portal.active(settings, needs_url=True):
        raise await _miss(user_id, source, parsed.code)
    match = await cross_portal_routes.peer_card_match(parsed, current_user)
    if match is None:
        raise await _miss(user_id, source, parsed.code)
    remote_id = match["artisanId"]
    remote_code = match.get("cardCode") or parsed.code
    linked = await db.crossportalartisanlink.find_first(
        where={"remoteArtisanId": remote_id}, order={"createdAt": "asc"}
    )
    if linked is not None:
        await cross_portal_routes.store_remote_card_code(linked.artisanId, remote_code)
        answer = await _found(linked.artisanId, "LINK", user_id, source, parsed.code)
        if answer is not None:
            return answer
    if not can_create_records(current_user):
        raise await _miss(user_id, source, parsed.code)
    await store.log_event(store.ACTION_PEER_RESOLVE, source, user_id=user_id, card_code=parsed.code)
    if not payload.consent:
        # Nothing of the answer is handed on until the designer ticks that the artisan agreed.
        return {"status": "REMOTE", "issuer": parsed.issuer, "needsConsent": True}
    prefill = await cross_portal_routes.card_prefill(match, current_user)
    return {"status": "REMOTE", "issuer": parsed.issuer, "needsConsent": False, "match": prefill}
