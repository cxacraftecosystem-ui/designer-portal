"""The designer portal's half of C27: find an artisan in the documentation portal, link, refresh.

The flow a designer sees:

1. On the artisan form they type the Aadhaar number (or the Pehchan card number), tick that the
   artisan agreed to share their details between the portals, and press Find. ``POST
   /cross-portal/artisans/lookup`` sends the documentation portal the Pehchan number or an HMAC of the
   Aadhaar number — never the number — and returns what it shares, mapped onto this portal's form.
2. They check the filled form and save it as usual (``POST /artisans``, unchanged for every client).
3. The client then calls ``POST /cross-portal/artisans/{id}/link``. The server does NOT take the
   client's word for which record matched: it looks the artisan up again from the numbers now STORED
   on this artisan, links only to what that lookup returns, and tells the documentation portal.
4. Later, ``POST .../link/refresh`` copies the identity fields from the documentation portal, which is
   their source of truth, through the ordinary edit guard and audit ledger.

Every route answers 404 while the feature is not active here (``services/cross_portal.active`` with
the documentation portal's URL), so nothing changes for anybody until both servers are configured.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    get_current_user,
    get_value,
    is_admin,
    may_edit_lower_ranked_record,
    require_record_creator,
)
from app.services import cross_portal
from app.services.access import effective_tier_for_record, guard_record_edit
from app.services.artisan_identity import normalize_pehchan, validate_aadhaar
from app.services.cross_portal_client import PeerUnavailable, peer_call
from app.services.record_design_workshop import assert_may_write_a_record_filed_under
from app.services.records import merge_field_provenance, public_encode, require_record

router = APIRouter(prefix="/cross-portal", tags=["cross-portal"])

NO_MATCH = "No shared record in the Field Repository for this artisan."
CONSENT_NEEDED = "Confirm the artisan agreed to share their details between the portals."


def _settings_or_404() -> Any:
    settings = get_settings()
    if not cross_portal.active(settings, needs_url=True):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return settings


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


def _peer_query(aadhaar: str | None, pehchan: str | None, settings: Any) -> dict[str, Any]:
    body: dict[str, Any] = {}
    if pehchan:
        body["pehchanCardNumber"] = pehchan
    if aadhaar:
        body["aadhaarCode"] = cross_portal.aadhaar_code(aadhaar, settings.cross_portal_aadhaar_hmac_key)
    return body


async def _lookup(settings: Any, aadhaar: str | None, pehchan: str | None) -> dict[str, Any] | None:
    body = _peer_query(aadhaar, pehchan, settings)
    if not body:
        return None
    try:
        code, payload = await peer_call(settings, "POST", "/artisans/lookup", body=body)
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
    if is_admin(user) or (owner_id is not None and get_value(user, "id") == owner_id):
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
        "identityFields": list(cross_portal.IDENTITY_FIELDS),
    }


@router.get("/status")
async def cross_portal_status(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Whether the clients should draw the Field Repository lookup at all."""
    return {"enabled": cross_portal.active(get_settings(), needs_url=True)}


@router.post("/artisans/lookup")
async def lookup_artisan(
    payload: LookupIn, _: Any = Depends(require_record_creator)
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
    match = await _lookup(settings, aadhaar, pehchan)
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
    if not payload.consent:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=CONSENT_NEEDED)
    artisan = await require_record(db.artisan, artisan_id)
    await _assert_may_edit(artisan, current_user)
    match = await _lookup(settings, artisan.aadhaarNumber, artisan.pehchanCardNumber)
    if match is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=NO_MATCH)
    remote_id = match["artisanId"]
    if payload.expectedRemoteArtisanId and payload.expectedRemoteArtisanId != remote_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="The saved numbers match a different Field Repository record. Check them and try again.",
        )
    matched_by = match.get("matchedBy") if match.get("matchedBy") in cross_portal.MATCHED_BY else cross_portal.MATCHED_BY_AADHAAR
    data = {
        "remoteArtisanId": remote_id,
        "matchedBy": matched_by,
        "consentRecordedById": get_value(current_user, "id"),
        "consentRecordedAt": datetime.now(UTC),
    }
    link = await db.crossportalartisanlink.upsert(
        where={"artisanId": artisan_id},
        data={"create": {"artisanId": artisan_id, **data}, "update": data},
    )
    try:
        code, _ = await peer_call(
            settings,
            "POST",
            f"/artisans/{remote_id}/links",
            body={"remoteArtisanId": artisan_id, "matchedBy": matched_by},
        )
        if code == 200:
            link = await db.crossportalartisanlink.update(
                where={"artisanId": artisan_id}, data={"remoteAcknowledgedAt": datetime.now(UTC)}
            )
    except PeerUnavailable:
        # The link stands here; the other side learns of it on the next link or refresh.
        pass
    return _link_payload(link)


def _as_column(key: str, value: Any) -> Any:
    if key == "dateOfBirth" and isinstance(value, str) and value:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    return value


@router.post("/artisans/{artisan_id}/link/refresh")
async def refresh_artisan(
    artisan_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """Copy the identity fields from the documentation portal. Audited like any other edit."""
    settings = _settings_or_404()
    artisan = await require_record(db.artisan, artisan_id)
    link = await db.crossportalartisanlink.find_unique(where={"artisanId": artisan_id})
    if link is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="This artisan isn't linked.")
    await _assert_may_edit(artisan, current_user)
    try:
        code, payload = await peer_call(
            settings, "GET", f"/artisans/{link.remoteArtisanId}", params={"remoteArtisanId": artisan_id}
        )
    except PeerUnavailable as exc:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(exc)) from None
    match = payload.get("match") if isinstance(payload, dict) else None
    if code != 200 or not isinstance(match, dict):
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="The Field Repository no longer shares this artisan.",
        )
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
    if data:
        async with db.tx() as tx:
            await guard_record_edit(artisan, current_user, data, "artisan", client=tx)
            merge_field_provenance(data, current_user, previous=artisan)
            try:
                await tx.artisan.update(where={"id": artisan_id}, data=data)
            except Exception as exc:  # noqa: BLE001 - a unique clash on the Pehchan number
                if "pehchanCardNumber" in str(exc) or "Unique" in type(exc).__name__:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="Another artisan here already holds that Pehchan card number.",
                    ) from None
                raise
    link = await db.crossportalartisanlink.update(
        where={"artisanId": artisan_id}, data={"lastRefreshedAt": datetime.now(UTC)}
    )
    updated = await db.artisan.find_unique(where={"id": artisan_id})
    return {
        "link": _link_payload(link),
        "changed": sorted(k for k in data if k in cross_portal.IDENTITY_FIELDS),
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
            await peer_call(settings, "DELETE", f"/artisans/{link.remoteArtisanId}/links/{artisan_id}")
        except PeerUnavailable:
            pass
        await db.crossportalartisanlink.delete(where={"artisanId": artisan_id})
    return {"linked": False}
