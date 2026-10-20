"""The designer portal's half of C27: find an artisan in the documentation portal, link, refresh.

The flow a designer sees:

1. On the artisan form they type the Aadhaar number (or the Pehchan card number), tick that the
   artisan agreed to share their details between the portals, and press Find. ``POST
   /cross-portal/artisans/lookup`` sends the documentation portal the Pehchan number or the Aadhaar
   number's fingerprint (``services/aadhaar_fingerprint``, the key both portals share) — never the
   number — and returns what it shares, mapped onto this portal's form.
2. They check the filled form and save it as usual (``POST /artisans``, unchanged for every client).
3. The client then calls ``POST /cross-portal/artisans/{id}/link``. The server does NOT take the
   client's word for which record matched: it looks the artisan up again from what is now STORED on
   this artisan (the Aadhaar fingerprint and the Pehchan number), asks the documentation portal to
   record the link, and writes its own link row only once that portal has not refused it (a refusal
   means the artisan withdrew consent).
4. Later, ``POST .../link/refresh`` copies the identity fields from the documentation portal, which is
   their source of truth, through the same helpers as the ordinary artisan edit: the edit guard and
   audit ledger, the Pehchan pair rule, the identity-number clash guard and the resubmission rule.

Every lookup, link, refresh and unlink is written to ``CrossPortalAccessLog`` (who, what, which
record, or no match) — never the number or the code. Lookups are limited per account per hour.

Every route answers 404 while the feature is not active here (``services/cross_portal.active`` with
the documentation portal's URL), so nothing changes for anybody until both servers are configured.
"""

import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from app.api.routes.artisans import (
    _guard_identity_conflicts,
    _identity_conflict,
    _violated_identity_field,
    assert_pehchan_pair,
)
from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    get_current_user,
    get_value,
    is_content_admin,
    may_edit_lower_ranked_record,
    require_record_creator,
)
from app.services import (
    artisan_card,
    artisan_card_store as card_store,
    cross_portal,
)
from app.services.access import effective_tier_for_record, guard_record_edit
from app.services.artisan_identity import normalize_pehchan, validate_aadhaar
from app.services.coverage import coverage_peer_active
from app.services.cross_portal_client import PeerUnavailable, peer_call
from app.services.record_design_workshop import assert_may_write_a_record_filed_under
from app.services.records import (
    merge_field_provenance,
    public_encode,
    require_record,
    resubmit_status,
)

logger = logging.getLogger("app.cross_portal")

router = APIRouter(prefix="/cross-portal", tags=["cross-portal"])

NO_MATCH = "No shared record in the Field Repository for this artisan."
CONSENT_NEEDED = "Confirm the artisan agreed to share their details between the portals."
CONSENT_WITHDRAWN = "The artisan no longer shares their details from the Field Repository."
NO_LONGER_SHARED = "The Field Repository no longer shares this artisan."
TOO_MANY = "Too many lookups in the last hour. Try again later, or fill the form yourself."
CARD_BAD_CHECK = "Check the code. One character is wrong."
CARD_OWN = "That card is from this portal. Scan the Field Repository card."
REFRESH_PEHCHAN = (
    "The Field Repository says this artisan holds a Pehchan card but has no number for it. "
    "Ask them to add it there, then refresh."
)

#: Lookups one account may make in an hour. A designer records a few artisans a day; this is a
#: ceiling on someone walking the Pehchan number space, not on ordinary work.
LOOKUPS_PER_HOUR = 60

ACTION_LOOKUP = "LOOKUP"
ACTION_LINK = "LINK"
ACTION_REFRESH = "REFRESH"
ACTION_UNLINK = "UNLINK"
ACTION_RELINK = "RELINK"


def _settings_or_404() -> Any:
    settings = get_settings()
    if not cross_portal.active(settings, needs_url=True):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


async def _log(
    user: Any,
    action: str,
    outcome: str,
    *,
    artisan_id: str | None = None,
    remote_artisan_id: str | None = None,
    matched_by: str | None = None,
) -> None:
    """One access-log row. Never the Aadhaar number, its code, or the Pehchan number."""
    await db.crossportalaccesslog.create(
        data={
            "userId": get_value(user, "id"),
            "action": action,
            "outcome": outcome,
            "artisanId": artisan_id,
            "remoteArtisanId": remote_artisan_id,
            "matchedBy": matched_by,
        }
    )


async def _assert_under_lookup_limit(user: Any) -> None:
    since = datetime.now(UTC) - timedelta(hours=1)
    made = await db.crossportalaccesslog.count(
        where={"userId": get_value(user, "id"), "action": ACTION_LOOKUP, "createdAt": {"gte": since}}
    )
    if made >= LOOKUPS_PER_HOUR:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=TOO_MANY)


class LookupIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    aadhaarNumber: str | None = Field(default=None, max_length=20)
    pehchanCardNumber: str | None = Field(default=None, max_length=40)
    #: The designer's statement that the artisan agreed. Required to be true.
    consent: bool = False


class LinkIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    consent: bool = False
    #: What the lookup on the form returned. When given, the server's own lookup must agree.
    expectedRemoteArtisanId: str | None = Field(default=None, max_length=64)
    #: A Field Repository card scanned or typed on the form (``services/artisan_card``). When given,
    #: the link is made from the card instead of the stored numbers, and ``matchedBy`` is CARD.
    cardInput: str | None = Field(default=None, max_length=64)


def _peer_query(aadhaar_code: str | None, pehchan: str | None) -> dict[str, Any]:
    body: dict[str, Any] = {}
    if pehchan:
        body["pehchanCardNumber"] = pehchan
    if aadhaar_code:
        body["aadhaarCode"] = aadhaar_code
    return body


async def _lookup(
    settings: Any, aadhaar_code: str | None, pehchan: str | None, user: Any
) -> dict[str, Any] | None:
    """Ask the documentation portal. ``aadhaar_code`` is the HMAC code, never the number."""
    body = _peer_query(aadhaar_code, pehchan)
    if not body:
        return None
    try:
        code, payload = await peer_call(
            settings, "POST", "/artisans/lookup", body=body, requested_by=get_value(user, "id")
        )
    except PeerUnavailable as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None
    if code != 200 or not isinstance(payload, dict):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="The Field Repository did not answer."
        )
    match = payload.get("match")
    return match if isinstance(match, dict) and match.get("artisanId") else None


async def _local_craft(craft: Any) -> dict[str, Any] | None:
    """The remote craft mapped onto this portal's: by shared code first (C26), then by name."""
    if not isinstance(craft, dict) or not craft.get("name"):
        return None
    local = None
    if craft.get("code"):
        local = await db.craft.find_first(where={"craftCode": craft["code"]}, order={"createdAt": "asc"})
    if local is None:
        local = await db.craft.find_unique(where={"name": craft["name"]})
    return {
        "code": craft.get("code"),
        "name": craft["name"],
        "craftId": getattr(local, "id", None),
        "craftName": getattr(local, "name", None) or craft["name"],
    }


async def _prefill(match: dict[str, Any]) -> dict[str, Any]:
    fields = match.get("fields") if isinstance(match.get("fields"), dict) else {}
    return {
        "remoteArtisanId": match["artisanId"],
        "matchedBy": match.get("matchedBy"),
        "fields": {k: fields.get(k) for k in cross_portal.SHARED_FIELDS},
        "identityFields": list(cross_portal.IDENTITY_FIELDS),
        "craft": await _local_craft(match.get("craft")),
        "statedAddress": match.get("statedAddress"),
    }


async def _assert_may_edit(artisan: Any, user: Any) -> None:
    """The artisan's privileged editors only: admin, its author, a professor+ above the author, or
    an EDIT grantee — the same answer ``access.guard_record_edit`` reaches — and never a holder of a
    post on the workshop it is filed under."""
    await assert_may_write_a_record_filed_under(getattr(artisan, "designWorkshopId", None), user)
    owner_id = get_value(artisan, "createdById")
    if is_content_admin(user) or (owner_id is not None and get_value(user, "id") == owner_id):
        return
    if await may_edit_lower_ranked_record(user, owner_id):
        return
    if await effective_tier_for_record(user, owner_id, "artisan", artisan.id) == "EDIT":
        return
    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Only someone who may edit this artisan can link it.",
    )


def _link_payload(link: Any | None) -> dict[str, Any]:
    if link is None:
        return {"linked": False}
    return {
        "linked": True,
        "remoteArtisanId": link.remoteArtisanId,
        "matchedBy": link.matchedBy,
        "consentRecordedAt": link.consentRecordedAt,
        "remoteAcknowledged": link.remoteAcknowledgedAt is not None,
        "lastRefreshedAt": link.lastRefreshedAt,
        "remoteCardCode": getattr(link, "remoteCardCode", None),
        "identityFields": list(cross_portal.IDENTITY_FIELDS),
    }


async def _own_card_code(artisan_id: str, user: Any) -> str | None:
    """This artisan's live card code here, issued now if it has none; None while cards are off."""
    if not artisan_card.active(get_settings()):
        return None
    try:
        card = await card_store.ensure_card(
            artisan_id, user_id=get_value(user, "id"), source=card_store.SOURCE_WEB
        )
    except Exception:  # noqa: BLE001 - a link never fails for want of a card
        return None
    return card.code


def _card_code_of(payload: Any) -> str | None:
    """The Field Repository's card code in a link or refresh answer: top level, or in ``match``."""
    if not isinstance(payload, dict):
        return None
    value = payload.get("cardCode")
    if not value and isinstance(payload.get("match"), dict):
        value = payload["match"].get("cardCode")
    return value if isinstance(value, str) and value else None


async def store_remote_card_code(artisan_id: str, code: str | None) -> None:
    """Remember the Field Repository's card code on this artisan's link.

    The column is unique, and two artisans here may be linked to one artisan there, so any other
    link holding the code gives it up first: the latest link answers for that card.
    """
    if not code:
        return
    await db.crossportalartisanlink.update_many(
        where={"remoteCardCode": code, "NOT": [{"artisanId": artisan_id}]},
        data={"remoteCardCode": None},
    )
    await db.crossportalartisanlink.update_many(
        where={"artisanId": artisan_id}, data={"remoteCardCode": code}
    )


async def _tell_peer_about_link(
    settings: Any, artisan_id: str, remote_id: str, matched_by: str, user: Any
) -> tuple[int | None, Any]:
    """Ask the documentation portal to record the link: ``(status, answer)``, or ``(None, None)``
    if it can't be reached. Carries this artisan's card code when cards are on here."""
    body: dict[str, Any] = {"remoteArtisanId": artisan_id, "matchedBy": matched_by}
    own = await _own_card_code(artisan_id, user)
    if own:
        body["remoteCardCode"] = own
    try:
        code, payload = await peer_call(
            settings,
            "POST",
            f"/artisans/{remote_id}/links",
            body=body,
            requested_by=get_value(user, "id"),
        )
    except PeerUnavailable:
        return None, None
    return code, payload


async def push_card_code(artisan_id: str, code: str, user: Any) -> bool:
    """Tell the documentation portal this artisan's new card code (after a reissue, and on every
    refresh). True when it recorded it. Never raises: a missed push is re-sent by the next refresh."""
    settings = get_settings()
    if not cross_portal.active(settings, needs_url=True):
        return False
    link = await db.crossportalartisanlink.find_unique(where={"artisanId": artisan_id})
    if link is None:
        return False
    try:
        status_code, _ = await peer_call(
            settings,
            "PUT",
            f"/artisans/{link.remoteArtisanId}/links/{artisan_id}/card",
            body={"remoteCardCode": code},
            requested_by=get_value(user, "id"),
        )
    except PeerUnavailable:
        return False
    return status_code == 200


async def peer_card_match(parsed: Any, user: Any) -> dict[str, Any] | None:
    """Ask the documentation portal which shared artisan a card of theirs belongs to.

    ``match`` (the lookup answer plus ``cardCode``) or None: the same None whatever the reason
    (unknown, revoked, not shared). 503 with nothing written when it can't be reached.
    """
    settings = _settings_or_404()
    try:
        code, payload = await peer_call(
            settings,
            "POST",
            "/cards/resolve",
            body={"cardCode": parsed.code, "tag": parsed.tag, "keyVersion": parsed.keyVersion},
            requested_by=get_value(user, "id"),
        )
    except PeerUnavailable as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None
    if code != 200 or not isinstance(payload, dict):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="The Field Repository did not answer."
        )
    match = payload.get("match")
    return match if isinstance(match, dict) and match.get("artisanId") else None


async def card_prefill(match: dict[str, Any], user: Any) -> dict[str, Any]:
    """A card's match as the lookup prefill, counted and logged as a lookup (matched by CARD)."""
    await _assert_under_lookup_limit(user)
    await _log(
        user,
        ACTION_LOOKUP,
        "MATCH",
        remote_artisan_id=match["artisanId"],
        matched_by=cross_portal.MATCHED_BY_CARD,
    )
    prefill = await _prefill(match)
    prefill["matchedBy"] = cross_portal.MATCHED_BY_CARD
    prefill["cardCode"] = match.get("cardCode")
    return prefill


@router.get("/status")
async def cross_portal_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Whether the clients should draw the Field Repository lookup at all, and what it shares."""
    return {
        "enabled": cross_portal.active(get_settings(), needs_url=True),
        # The consent tick's list, from the one list the lookup answer is built from.
        "sharedFields": cross_portal.consent_fields_phrase(),
        # The coverage map's "Field Repository" toggle: aggregate counts only, behind its own flag.
        "coverageEnabled": coverage_peer_active(get_settings()),
    }


@router.post("/artisans/lookup")
async def lookup_artisan(
    payload: LookupIn, current_user: Any = Depends(require_record_creator)
) -> dict[str, Any]:
    settings = _settings_or_404()
    if not payload.consent:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=CONSENT_NEEDED)
    aadhaar = None
    if payload.aadhaarNumber and payload.aadhaarNumber.strip():
        try:
            aadhaar = validate_aadhaar(payload.aadhaarNumber)
        except ValueError as exc:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)
            ) from None
    pehchan = normalize_pehchan(payload.pehchanCardNumber)
    if not aadhaar and not pehchan:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Enter the Aadhaar number or the Pehchan card number first.",
        )
    await _assert_under_lookup_limit(current_user)
    key = cross_portal.aadhaar_key(settings)
    aadhaar_code = cross_portal.aadhaar_code(aadhaar, key) if aadhaar and key else None
    try:
        match = await _lookup(settings, aadhaar_code, pehchan, current_user)
    except HTTPException:
        await _log(current_user, ACTION_LOOKUP, "UNAVAILABLE")
        raise
    await _log(
        current_user,
        ACTION_LOOKUP,
        "MATCH" if match else "NO_MATCH",
        remote_artisan_id=match["artisanId"] if match else None,
        matched_by=match.get("matchedBy") if match else None,
    )
    return {"match": await _prefill(match) if match else None}


@router.get("/artisans/{artisan_id}/link")
async def get_link(artisan_id: str, _: Any = Depends(get_current_user)) -> dict[str, Any]:
    _settings_or_404()
    await require_record(db.artisan, artisan_id)
    link = await db.crossportalartisanlink.find_unique(where={"artisanId": artisan_id})
    return _link_payload(link)


@router.post("/artisans/{artisan_id}/link")
async def link_artisan(
    artisan_id: str, payload: LinkIn, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    settings = _settings_or_404()
    return await link_artisan_record(
        settings,
        artisan_id,
        consent=payload.consent,
        expected_remote_id=payload.expectedRemoteArtisanId,
        user=current_user,
        card_input=payload.cardInput,
    )


async def link_artisan_record(
    settings: Any,
    artisan_id: str,
    *,
    consent: bool,
    expected_remote_id: str | None,
    user: Any,
    card_input: str | None = None,
) -> dict[str, Any]:
    """The link itself, shared by the route above and the duplicate finder's Link action
    (``routes/artisan_duplicates.link_pair``). Looks the artisan up again from what it stores, asks
    the documentation portal first, then writes the local row. ``card_input`` is a Field Repository
    card scanned or typed on the form: the link is then made from the card. Raises the route's errors."""
    current_user = user
    if not consent:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=CONSENT_NEEDED)
    artisan = await require_record(db.artisan, artisan_id)
    await _assert_may_edit(artisan, current_user)
    from_card = bool(card_input and card_input.strip())
    if from_card:
        # FROM A CARD: the card is asked about again here, never taken on the client's word.
        try:
            parsed = artisan_card.parse(card_input)
        except artisan_card.CardParseError:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=CARD_BAD_CHECK
            ) from None
        if parsed.issuer == card_store.issuer(settings):
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=CARD_OWN)
        match = await peer_card_match(parsed, current_user)
    else:
        # The saved artisan's code is its stored aadhaarFingerprint (the legacy number's code before
        # the backfill): the number itself is not kept, so there is nothing to recompute it from.
        stored_code = cross_portal.stored_aadhaar_code(artisan, cross_portal.aadhaar_key(settings))
        match = await _lookup(settings, stored_code, artisan.pehchanCardNumber, current_user)
    if match is None:
        await _log(current_user, ACTION_LINK, "NO_MATCH", artisan_id=artisan_id)
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NO_MATCH)
    remote_id = match["artisanId"]
    if expected_remote_id and expected_remote_id != remote_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="The saved numbers match a different Field Repository record. Check them and try again.",
        )
    if from_card:
        matched_by = cross_portal.MATCHED_BY_CARD
    else:
        matched_by = match.get("matchedBy") if match.get("matchedBy") in cross_portal.MATCHED_BY else cross_portal.MATCHED_BY_AADHAAR
    # THE PEER FIRST. A 409 there means consent was withdrawn between the lookup and now, and a
    # link row written before asking would show "Linked" for an artisan who said no.
    code, answer = await _tell_peer_about_link(
        settings, artisan_id, remote_id, matched_by, current_user
    )
    if code == 409:
        await db.crossportalartisanlink.delete_many(where={"artisanId": artisan_id})
        await _log(
            current_user, ACTION_LINK, "REFUSED", artisan_id=artisan_id, remote_artisan_id=remote_id
        )
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=CONSENT_WITHDRAWN)
    now = datetime.now(UTC)
    data: dict[str, Any] = {
        "remoteArtisanId": remote_id,
        "matchedBy": matched_by,
        "consentRecordedById": get_value(current_user, "id"),
        "consentRecordedAt": now,
        # Unreachable: the link stands here and the next refresh re-sends it (``refresh_artisan``).
        "remoteAcknowledgedAt": now if code == 200 else None,
    }
    link = await db.crossportalartisanlink.upsert(
        where={"artisanId": artisan_id},
        data={"create": {"artisanId": artisan_id, **data}, "update": data},
    )
    remote_card = _card_code_of(answer) or _card_code_of({"match": match})
    if remote_card:
        await store_remote_card_code(artisan_id, remote_card)
        link = await db.crossportalartisanlink.find_unique(where={"artisanId": artisan_id})
    await _log(
        current_user,
        ACTION_LINK,
        "LINKED" if code == 200 else "UNACKNOWLEDGED",
        artisan_id=artisan_id,
        remote_artisan_id=remote_id,
        matched_by=matched_by,
    )
    return _link_payload(link)


def _as_column(key: str, value: Any) -> Any:
    if key == "dateOfBirth" and isinstance(value, str) and value:
        return datetime.fromisoformat(value)
    if key == "pehchanCardNumber":
        return normalize_pehchan(value) if value else None
    return value


async def _withdrawn(artisan_id: str, link: Any, user: Any) -> HTTPException:
    """The documentation portal refused the link: drop ours too, log it, and say so plainly."""
    await db.crossportalartisanlink.delete_many(where={"artisanId": artisan_id})
    await _log(
        user, ACTION_REFRESH, "REFUSED", artisan_id=artisan_id, remote_artisan_id=link.remoteArtisanId
    )
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=CONSENT_WITHDRAWN)


@router.post("/artisans/{artisan_id}/link/refresh")
async def refresh_artisan(
    artisan_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Copy the identity fields from the documentation portal, through the ordinary edit's helpers."""
    settings = _settings_or_404()
    artisan = await require_record(db.artisan, artisan_id)
    link = await db.crossportalartisanlink.find_unique(where={"artisanId": artisan_id})
    if link is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="This artisan isn't linked.")
    await _assert_may_edit(artisan, current_user)
    if link.remoteAcknowledgedAt is None:
        # A link the other side never recorded cannot be refreshed there; send it again first.
        code, answer = await _tell_peer_about_link(
            settings, artisan_id, link.remoteArtisanId, link.matchedBy, current_user
        )
        if code == 409:
            raise await _withdrawn(artisan_id, link, current_user)
        if code != 200:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="The Field Repository can't be reached right now. Try again.",
            )
        link = await db.crossportalartisanlink.update(
            where={"artisanId": artisan_id}, data={"remoteAcknowledgedAt": datetime.now(UTC)}
        )
        await store_remote_card_code(artisan_id, _card_code_of(answer))
    try:
        code, payload = await peer_call(
            settings,
            "GET",
            f"/artisans/{link.remoteArtisanId}",
            params={"remoteArtisanId": artisan_id},
            requested_by=get_value(current_user, "id"),
        )
    except PeerUnavailable as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None
    match = payload.get("match") if isinstance(payload, dict) else None
    if code != 200 or not isinstance(match, dict):
        await _log(
            current_user,
            ACTION_REFRESH,
            "NO_MATCH",
            artisan_id=artisan_id,
            remote_artisan_id=link.remoteArtisanId,
        )
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=NO_LONGER_SHARED)
    # THE FIELD REPOSITORY MERGED (OR UN-MERGED) ITS RECORD. It answers with the id the link now
    # lives on there; follow it, so a merge or undo on that side reaches this one without the
    # documentation portal ever calling this server.
    current_remote = match.get("artisanId")
    if isinstance(current_remote, str) and current_remote and current_remote != link.remoteArtisanId:
        previous_remote = link.remoteArtisanId
        link = await db.crossportalartisanlink.update(
            where={"artisanId": artisan_id}, data={"remoteArtisanId": current_remote}
        )
        await _log(
            current_user,
            ACTION_RELINK,
            "RELINKED",
            artisan_id=artisan_id,
            remote_artisan_id=current_remote,
            matched_by=link.matchedBy,
        )
        logger.info("cross-portal link followed a Field Repository merge (%s -> %s)", previous_remote, current_remote)
    fields = match.get("fields") if isinstance(match.get("fields"), dict) else {}
    data: dict[str, Any] = {}
    for key in cross_portal.IDENTITY_FIELDS:
        if key not in fields:
            continue
        value = _as_column(key, fields[key])
        if key == "name" and not value:
            continue
        current = getattr(artisan, key, None)
        if value != current:
            data[key] = value
    changed = sorted(k for k in data if k in cross_portal.IDENTITY_FIELDS)
    if data:
        # THE ORDINARY EDIT'S HELPERS, in update_artisan's order: the pair rule and the clash guard
        # refuse before the ledger row commits (one transaction), and a creator's refresh of a
        # record sent back for revision resubmits it, exactly as their own save would.
        async with db.tx() as tx:
            assert_pehchan_pair(artisan, data, detail=REFRESH_PEHCHAN)
            await guard_record_edit(artisan, current_user, data, "artisan", client=tx)
            merge_field_provenance(data, current_user, previous=artisan)
            resubmit_status(artisan, current_user, data)
            await _guard_identity_conflicts(data, exclude_id=artisan_id)
            try:
                await tx.artisan.update(where={"id": artisan_id}, data=data)
            except Exception as exc:
                field = _violated_identity_field(exc)
                if field is None:
                    raise
                raise await _identity_conflict(field, data[field], exclude_id=artisan_id) from exc
    await store_remote_card_code(artisan_id, _card_code_of(payload))
    link = await db.crossportalartisanlink.update(
        where={"artisanId": artisan_id}, data={"lastRefreshedAt": datetime.now(UTC)}
    )
    # Our card code again, on every refresh: a push after a reissue that didn't get through lands here.
    own = await _own_card_code(artisan_id, current_user)
    if own:
        await push_card_code(artisan_id, own, current_user)
    await _log(
        current_user,
        ACTION_REFRESH,
        "CHANGED" if changed else "UNCHANGED",
        artisan_id=artisan_id,
        remote_artisan_id=link.remoteArtisanId,
        matched_by=link.matchedBy,
    )
    updated = await db.artisan.find_unique(where={"id": artisan_id})
    return {
        "link": _link_payload(link),
        "changed": changed,
        "artisan": public_encode(updated, current_user),
    }


@router.delete("/artisans/{artisan_id}/link")
async def unlink_artisan(
    artisan_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    settings = _settings_or_404()
    artisan = await require_record(db.artisan, artisan_id)
    await _assert_may_edit(artisan, current_user)
    link = await db.crossportalartisanlink.find_unique(where={"artisanId": artisan_id})
    if link is not None:
        try:
            await peer_call(
                settings,
                "DELETE",
                f"/artisans/{link.remoteArtisanId}/links/{artisan_id}",
                requested_by=get_value(current_user, "id"),
            )
        except PeerUnavailable:
            pass
        await db.crossportalartisanlink.delete(where={"artisanId": artisan_id})
        await _log(
            current_user,
            ACTION_UNLINK,
            "UNLINKED",
            artisan_id=artisan_id,
            remote_artisan_id=link.remoteArtisanId,
        )
    return {"linked": False}
