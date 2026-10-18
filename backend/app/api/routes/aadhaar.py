"""/api/aadhaar — verify a person against UIDAI, for an account or an artisan record.

EVERY ROUTE HERE IS INVISIBLE WHILE ITS FLAG IS OFF: ``flags.require_offline`` /
``flags.require_online`` answer 404 before anything else runs, including authentication-dependent
permission checks, so an unflagged deployment is indistinguishable from one that never had the
feature. See docs/AADHAAR.md.

WHO MAY DO WHAT
  * Any signed-in account may verify, read and withdraw ITS OWN verification (``/self``).
  * An artisan may be verified by somebody who may create records AND may edit that artisan — its
    recorder, an admin, a professor+ outranking the recorder, or an EDIT grantee — and who does
    not hold an inspecting or directing post on the workshop it is filed under. That is the same
    bar ``PATCH /artisans/{id}`` sets for changing a populated field, because a verification
    changes what the record asserts about a person.
  * Only an ADMIN decides a name mismatch, and never on their own account.

THE PAYLOAD IS NEVER STORED. The QR text, the ZIP, the share code, the mobile number or e-mail
offered for the hash check, the Aadhaar number or VID, and the OTP live in this request's memory and
are gone when it returns. What is written is ``records.apply_to_*``'s handful of columns.
"""

from __future__ import annotations

import base64
import binascii
from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.db import db
from app.core.deps import can_create_records, get_current_user, is_admin
from app.schemas.aadhaar import (
    ConsentBody,
    DecisionBody,
    OfflineXmlBody,
    OnlineOtpBody,
    OnlineVerifyBody,
    SecureQrBody,
)
from app.services import features as feature_flags
from app.services.aadhaar import flags, records
from app.services.aadhaar.identity import (
    METHOD_OFFLINE_XML,
    METHOD_ONLINE_OTP,
    METHOD_SECURE_QR,
    AadhaarError,
    VerifiedIdentity,
)
from app.services.aadhaar.offline_xml import verify_offline_zip
from app.services.aadhaar.secure_qr import verify_secure_qr

router = APIRouter(prefix="/aadhaar", tags=["aadhaar"])
features_router = APIRouter(tags=["features"])

CONSENT_REQUIRED = (
    "Read the consent notice and tick the box to agree before verifying with Aadhaar."
)


@features_router.get("/features")
async def get_features(_: Any = Depends(get_current_user)) -> dict[str, Any]:
    """Which optional features this deployment has switched on. Both clients hide what is off."""
    return feature_flags.features()


def _refusal(error: AadhaarError) -> HTTPException:
    return HTTPException(
        status_code=error.status_code, detail={"code": error.code, "message": error.message}
    )


def _now() -> datetime:
    return datetime.now(UTC)


# ── who the verification is about ───────────────────────────────────────────────────────────


async def _self_row(user: Any) -> Any:
    row = await db.user.find_unique(where={"id": user.id})
    if row is None:  # pragma: no cover - the bearer resolved this account a moment ago
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return row


async def _artisan_for_verification(artisan_id: str, user: Any) -> Any:
    from app.core.deps import may_edit_lower_ranked_record
    from app.services.access import effective_tier_for_record
    from app.services.record_design_workshop import assert_may_write_a_record_filed_under

    artisan = await db.artisan.find_unique(where={"id": artisan_id})
    if artisan is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    allowed = can_create_records(user) and (
        is_admin(user)
        or artisan.createdById == user.id
        or await may_edit_lower_ranked_record(user, artisan.createdById)
        or await effective_tier_for_record(user, artisan.createdById, "artisan", artisan.id)
        == "EDIT"
    )
    if not allowed:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only somebody who may edit this artisan's record may verify their Aadhaar.",
        )
    await assert_may_write_a_record_filed_under(artisan.designWorkshopId, user)
    return artisan


# ── the shared flow: consent, verify in memory, apply, audit ────────────────────────────────


async def _run(
    record_type: str,
    row: Any,
    actor: Any,
    method: str,
    consent: bool,
    verify: Any,
) -> dict[str, Any]:
    previous = records._status_word(row)
    if not consent:
        await records.audit_attempt(
            record_type, row.id, actor, method=method, outcome="REFUSED",
            previous=previous, code="consent_required",
        )
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"code": "consent_required", "message": CONSENT_REQUIRED},
        )
    try:
        identity: VerifiedIdentity = await verify()
        if record_type == records.RECORD_USER:
            updated = await records.apply_to_user(row, identity, actor)
        else:
            updated = await records.apply_to_artisan(row, identity, actor)
    except AadhaarError as error:
        await records.audit_attempt(
            record_type, row.id, actor, method=method, outcome="REFUSED",
            previous=previous, code=error.code,
        )
        raise _refusal(error) from None
    return records.summary(updated)


def _decode_zip(body: OfflineXmlBody) -> bytes:
    try:
        return base64.b64decode(body.zipBase64, validate=True)
    except (binascii.Error, ValueError):
        raise AadhaarError(
            "zip_unreadable", "That file could not be read. Upload the ZIP file UIDAI gave you."
        ) from None


def _secure_qr(body: SecureQrBody) -> Any:
    async def verify() -> VerifiedIdentity:
        return verify_secure_qr(body.qrText, now=_now())

    return verify


def _offline_xml(body: OfflineXmlBody) -> Any:
    async def verify() -> VerifiedIdentity:
        return verify_offline_zip(
            _decode_zip(body), body.shareCode, mobile=body.mobile, email=body.email, now=_now()
        )

    return verify


# ── the account holder's own verification ────────────────────────────────────────────────────


@router.get("/self", dependencies=[Depends(flags.require_any)])
async def read_self(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    return records.summary(await _self_row(current_user))


@router.post("/self/secure-qr", dependencies=[Depends(flags.require_offline)])
async def verify_self_secure_qr(
    body: SecureQrBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    row = await _self_row(current_user)
    return await _run(
        records.RECORD_USER, row, current_user, METHOD_SECURE_QR, body.consent, _secure_qr(body)
    )


@router.post("/self/offline-xml", dependencies=[Depends(flags.require_offline)])
async def verify_self_offline_xml(
    body: OfflineXmlBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    row = await _self_row(current_user)
    return await _run(
        records.RECORD_USER, row, current_user, METHOD_OFFLINE_XML, body.consent, _offline_xml(body)
    )


@router.delete("/self", dependencies=[Depends(flags.require_any)])
async def withdraw_self(current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    row = await _self_row(current_user)
    return records.summary(await records.withdraw(records.RECORD_USER, row, current_user))


# ── an artisan's verification ────────────────────────────────────────────────────────────────


@router.get("/artisans/{artisan_id}", dependencies=[Depends(flags.require_any)])
async def read_artisan(
    artisan_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """The artisan's verification. The fields UIDAI signed (name, date of birth, gender, last four)
    go only to the people who may read the artisan's full Aadhaar number — the recorder, and
    professor-and-above — on ``artisans._may_read_full_aadhaar``'s rule. Everybody else learns
    whether the artisan is verified, how, and when."""
    from app.core.deps import has_rank

    artisan = await db.artisan.find_unique(where={"id": artisan_id})
    if artisan is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    summary = records.summary(artisan)
    if has_rank(current_user, "PROFESSOR") or artisan.createdById == current_user.id:
        return summary
    return {key: summary[key] for key in ("status", "verified", "method", "verifiedAt")}


@router.post("/artisans/{artisan_id}/secure-qr", dependencies=[Depends(flags.require_offline)])
async def verify_artisan_secure_qr(
    artisan_id: str, body: SecureQrBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await _artisan_for_verification(artisan_id, current_user)
    return await _run(
        records.RECORD_ARTISAN, artisan, current_user, METHOD_SECURE_QR, body.consent,
        _secure_qr(body),
    )


@router.post("/artisans/{artisan_id}/offline-xml", dependencies=[Depends(flags.require_offline)])
async def verify_artisan_offline_xml(
    artisan_id: str, body: OfflineXmlBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await _artisan_for_verification(artisan_id, current_user)
    return await _run(
        records.RECORD_ARTISAN, artisan, current_user, METHOD_OFFLINE_XML, body.consent,
        _offline_xml(body),
    )


@router.delete("/artisans/{artisan_id}", dependencies=[Depends(flags.require_any)])
async def withdraw_artisan(
    artisan_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await _artisan_for_verification(artisan_id, current_user)
    return records.summary(await records.withdraw(records.RECORD_ARTISAN, artisan, current_user))


# ── an admin's decision on a name mismatch ───────────────────────────────────────────────────


def _require_admin_decider(current_user: Any) -> None:
    if not is_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only an administrator may decide an Aadhaar name mismatch.",
        )


@router.post("/users/{user_id}/decision", dependencies=[Depends(flags.require_any)])
async def decide_user(
    user_id: str, body: DecisionBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    _require_admin_decider(current_user)
    if user_id == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Another administrator has to decide a mismatch on your own account.",
        )
    row = await db.user.find_unique(where={"id": user_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")
    return records.summary(await records.decide(records.RECORD_USER, row, current_user, body.accept))


@router.post("/artisans/{artisan_id}/decision", dependencies=[Depends(flags.require_any)])
async def decide_artisan(
    artisan_id: str, body: DecisionBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    _require_admin_decider(current_user)
    row = await db.artisan.find_unique(where={"id": artisan_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return records.summary(
        await records.decide(records.RECORD_ARTISAN, row, current_user, body.accept)
    )


# ── online OTP authentication (AUA through an ASA) ───────────────────────────────────────────


async def _online_otp(
    record_type: str, row: Any, actor: Any, body: OnlineOtpBody
) -> dict[str, Any]:
    from app.services.aadhaar.online import service

    previous = records._status_word(row)
    if not body.consent:
        await records.audit_attempt(
            record_type, row.id, actor, method=METHOD_ONLINE_OTP, outcome="REFUSED",
            previous=previous, code="consent_required",
        )
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail={"code": "consent_required", "message": CONSENT_REQUIRED},
        )
    try:
        issued = await service.send_otp(body.uid, actor_id=actor.id, subject=f"{record_type}:{row.id}")
    except AadhaarError as error:
        await records.audit_attempt(
            record_type, row.id, actor, method=METHOD_ONLINE_OTP, outcome="REFUSED",
            previous=previous, code=error.code,
        )
        raise _refusal(error) from None
    await records.audit_attempt(
        record_type, row.id, actor, method=METHOD_ONLINE_OTP, outcome="OTP_SENT",
        previous=previous, last4=issued.last4,
    )
    return issued.as_payload()


async def _online_verify(
    record_type: str, row: Any, actor: Any, body: OnlineVerifyBody
) -> dict[str, Any]:
    from app.services.aadhaar.online import service

    async def verify() -> VerifiedIdentity:
        return await service.verify_otp(
            body.uid,
            body.otp,
            body.txn,
            body.token,
            actor_id=actor.id,
            subject=f"{record_type}:{row.id}",
            recorded_name=getattr(row, "name", None),
        )

    return await _run(record_type, row, actor, METHOD_ONLINE_OTP, body.consent, verify)


@router.post("/self/online/otp", dependencies=[Depends(flags.require_online)])
async def self_online_otp(
    body: OnlineOtpBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await _online_otp(records.RECORD_USER, await _self_row(current_user), current_user, body)


@router.post("/self/online/verify", dependencies=[Depends(flags.require_online)])
async def self_online_verify(
    body: OnlineVerifyBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    return await _online_verify(
        records.RECORD_USER, await _self_row(current_user), current_user, body
    )


@router.post("/artisans/{artisan_id}/online/otp", dependencies=[Depends(flags.require_online)])
async def artisan_online_otp(
    artisan_id: str, body: OnlineOtpBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await _artisan_for_verification(artisan_id, current_user)
    return await _online_otp(records.RECORD_ARTISAN, artisan, current_user, body)


@router.post("/artisans/{artisan_id}/online/verify", dependencies=[Depends(flags.require_online)])
async def artisan_online_verify(
    artisan_id: str, body: OnlineVerifyBody, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    artisan = await _artisan_for_verification(artisan_id, current_user)
    return await _online_verify(records.RECORD_ARTISAN, artisan, current_user, body)


__all__ = ["ConsentBody", "features_router", "router"]
