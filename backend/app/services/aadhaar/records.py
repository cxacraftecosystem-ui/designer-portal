"""Writing a verification onto a User or an Artisan, the admin's decision, and the audit trail.

THE ONLY WRITER OF THE ``aadhaarVerif*`` COLUMNS. Every method in this package ends in a
``VerifiedIdentity``; this module turns it into the minimal row update the Aadhaar Act and its
regulations permit and nothing more (see ``identity.VerifiedIdentity`` for what is excluded).

EVERY ATTEMPT IS AUDITED, success or refusal, in the one ledger this product has for "who changed
what on a record": ``RecordRevision``, recordType ``user`` or ``artisan``. The entry carries the
method, the outcome, a refusal code and the last four digits — never a name, a date of birth, a
number, or anything from the document — under one key, ``aadhaarVerification``, in the
``{old, new}`` shape both clients' history panels already render. A ``logger.info`` line says the
same for the operator's log.
"""

from __future__ import annotations

import logging
from datetime import UTC, date, datetime
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.services.aadhaar.identity import AadhaarError, VerifiedIdentity
from app.services.aadhaar.names import match_names
from prisma import Json

logger = logging.getLogger("app.aadhaar")

STATUS_VERIFIED = "VERIFIED"
STATUS_NAME_MISMATCH = "NAME_MISMATCH"
STATUS_REJECTED = "REJECTED"

RECORD_USER = "user"
RECORD_ARTISAN = "artisan"

#: Every column the verification writes. A test holds the schema to exactly this set, so a new
#: column is a decision recorded in two places rather than a side effect.
USER_COLUMNS = (
    "aadhaarVerifyStatus",
    "aadhaarVerifyMethod",
    "aadhaarVerifiedAt",
    "aadhaarVerifyLast4",
    "aadhaarVerifyName",
    "aadhaarVerifyBirthYear",
    "aadhaarVerifyGender",
    "aadhaarVerifySignedAt",
    "aadhaarVerifyTxn",
    "aadhaarVerifyNameMatch",
    "aadhaarVerifyById",
    "aadhaarVerifyDecidedById",
    "aadhaarVerifyDecidedAt",
)
ARTISAN_COLUMNS = (*USER_COLUMNS, "aadhaarVerifyDob")


def _now() -> datetime:
    return datetime.now(UTC)


def _enum(value: Any) -> Any:
    return getattr(value, "value", value)


def _dob_datetime(value: date | None) -> datetime | None:
    if value is None:
        return None
    return datetime(value.year, value.month, value.day, tzinfo=UTC)


def _status_word(row: Any) -> str | None:
    value = _enum(getattr(row, "aadhaarVerifyStatus", None))
    return str(value) if value else None


async def audit_attempt(
    record_type: str,
    record_id: str,
    actor: Any,
    *,
    method: str,
    outcome: str,
    previous: str | None,
    code: str | None = None,
    last4: str | None = None,
    client: Any = None,
) -> None:
    """One ledger row per attempt. ``outcome`` is VERIFIED / NAME_MISMATCH / REFUSED / ACCEPTED /
    REJECTED / WITHDRAWN / OTP_SENT; ``code`` is the refusal code (never a sentence with data in it).
    """
    entry: dict[str, Any] = {
        "old": previous,
        "new": outcome,
        "method": method,
        "outcome": outcome,
    }
    if code:
        entry["code"] = code
    if last4:
        entry["last4"] = last4
    writer = db if client is None else client
    actor_id = getattr(actor, "id", None)
    await writer.recordrevision.create(
        data={
            "recordType": record_type,
            "recordId": record_id,
            "editedById": actor_id,
            "changes": Json({"aadhaarVerification": entry}),
        }
    )
    logger.info(
        "aadhaar: %s %s attempt by %s via %s -> %s%s",
        record_type,
        record_id,
        actor_id,
        method,
        outcome,
        f" ({code})" if code else "",
    )


def _result_columns(
    identity: VerifiedIdentity,
    recorded_name: str | None,
    actor: Any,
    *,
    keep_dob: bool,
) -> tuple[dict[str, Any], str]:
    if identity.uidai_name_match is not None:
        name_matched = identity.uidai_name_match
    else:
        name_matched = match_names(identity.name, recorded_name).matched
    state = STATUS_VERIFIED if name_matched else STATUS_NAME_MISMATCH
    birth_year = identity.birth_year or (identity.dob.year if identity.dob else None)
    data: dict[str, Any] = {
        "aadhaarVerifyStatus": state,
        "aadhaarVerifyMethod": identity.method,
        "aadhaarVerifiedAt": _now(),
        "aadhaarVerifyLast4": identity.last4,
        "aadhaarVerifyName": identity.name,
        "aadhaarVerifyBirthYear": birth_year,
        "aadhaarVerifyGender": identity.gender,
        "aadhaarVerifySignedAt": identity.signed_at,
        "aadhaarVerifyTxn": identity.txn,
        "aadhaarVerifyNameMatch": name_matched,
        "aadhaarVerifyById": getattr(actor, "id", None),
        "aadhaarVerifyDecidedById": None,
        "aadhaarVerifyDecidedAt": None,
    }
    if keep_dob:
        data["aadhaarVerifyDob"] = _dob_datetime(identity.dob)
    return data, state


async def apply_to_user(user: Any, identity: VerifiedIdentity, actor: Any) -> Any:
    """Write a verified identity onto an account. Returns the updated row."""
    data, state = _result_columns(identity, getattr(user, "name", None), actor, keep_dob=False)
    async with db.tx() as tx:
        updated = await tx.user.update(where={"id": user.id}, data=data)
        await audit_attempt(
            RECORD_USER,
            user.id,
            actor,
            method=identity.method,
            outcome=state,
            previous=_status_word(user),
            last4=identity.last4,
            client=tx,
        )
    from app.core.deps import invalidate_cached_user

    invalidate_cached_user(user.id)
    return updated


def stored_aadhaar_last4(artisan: Any) -> str | None:
    number = getattr(artisan, "aadhaarNumber", None)
    digits = "".join(ch for ch in str(number or "") if ch.isdigit())
    return digits[-4:] if len(digits) == 12 else None


async def apply_to_artisan(artisan: Any, identity: VerifiedIdentity, actor: Any) -> Any:
    """Write a verified identity onto an artisan record. Returns the updated row.

    REFUSES a document for a different Aadhaar: where the record already holds a number and UIDAI's
    last four digits disagree with it, the card in the field worker's hand is somebody else's.
    """
    stored = stored_aadhaar_last4(artisan)
    if stored and identity.last4 and stored != identity.last4:
        raise AadhaarError(
            "last4_mismatch",
            "This Aadhaar does not end in the same four digits as the Aadhaar number on this "
            "artisan's record. Check that it is the right person's card.",
        )
    data, state = _result_columns(identity, getattr(artisan, "name", None), actor, keep_dob=True)
    async with db.tx() as tx:
        updated = await tx.artisan.update(where={"id": artisan.id}, data=data)
        await audit_attempt(
            RECORD_ARTISAN,
            artisan.id,
            actor,
            method=identity.method,
            outcome=state,
            previous=_status_word(artisan),
            last4=identity.last4,
            client=tx,
        )
    return updated


async def decide(record_type: str, row: Any, admin: Any, accept: bool) -> Any:
    """An admin's decision on a NAME_MISMATCH: accept it (VERIFIED) or reject it (REJECTED)."""
    if _status_word(row) != STATUS_NAME_MISMATCH:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="There is no name mismatch waiting for a decision on this record.",
        )
    state = STATUS_VERIFIED if accept else STATUS_REJECTED
    data = {
        "aadhaarVerifyStatus": state,
        "aadhaarVerifyDecidedById": admin.id,
        "aadhaarVerifyDecidedAt": _now(),
    }
    async with db.tx() as tx:
        writer = tx.user if record_type == RECORD_USER else tx.artisan
        updated = await writer.update(where={"id": row.id}, data=data)
        await audit_attempt(
            record_type,
            row.id,
            admin,
            method=str(_enum(getattr(row, "aadhaarVerifyMethod", None)) or "UNKNOWN"),
            outcome="ACCEPTED" if accept else "REJECTED",
            previous=STATUS_NAME_MISMATCH,
            last4=getattr(row, "aadhaarVerifyLast4", None),
            client=tx,
        )
    if record_type == RECORD_USER:
        from app.core.deps import invalidate_cached_user

        invalidate_cached_user(row.id)
    return updated


async def withdraw(record_type: str, row: Any, actor: Any) -> Any:
    """Clear every verification column — the account holder withdrawing, or a field worker undoing
    a verification made against the wrong record."""
    columns = USER_COLUMNS if record_type == RECORD_USER else ARTISAN_COLUMNS
    data = dict.fromkeys(columns)
    async with db.tx() as tx:
        writer = tx.user if record_type == RECORD_USER else tx.artisan
        updated = await writer.update(where={"id": row.id}, data=data)
        await audit_attempt(
            record_type,
            row.id,
            actor,
            method=str(_enum(getattr(row, "aadhaarVerifyMethod", None)) or "UNKNOWN"),
            outcome="WITHDRAWN",
            previous=_status_word(row),
            client=tx,
        )
    if record_type == RECORD_USER:
        from app.core.deps import invalidate_cached_user

        invalidate_cached_user(row.id)
    return updated


def summary(row: Any) -> dict[str, Any]:
    """The verification as a client reads it — the same keys on both record types."""
    signed_at = getattr(row, "aadhaarVerifySignedAt", None)
    verified_at = getattr(row, "aadhaarVerifiedAt", None)
    decided_at = getattr(row, "aadhaarVerifyDecidedAt", None)
    dob = getattr(row, "aadhaarVerifyDob", None)
    state = _status_word(row)
    return {
        "status": state,
        "verified": state == STATUS_VERIFIED,
        "method": _enum(getattr(row, "aadhaarVerifyMethod", None)),
        "verifiedAt": verified_at.isoformat() if verified_at else None,
        "last4": getattr(row, "aadhaarVerifyLast4", None),
        "name": getattr(row, "aadhaarVerifyName", None),
        "recordName": getattr(row, "name", None),
        "nameMatch": getattr(row, "aadhaarVerifyNameMatch", None),
        "birthYear": getattr(row, "aadhaarVerifyBirthYear", None),
        "dateOfBirth": dob.date().isoformat() if dob else None,
        "gender": getattr(row, "aadhaarVerifyGender", None),
        "signedAt": signed_at.isoformat() if signed_at else None,
        "decidedAt": decided_at.isoformat() if decided_at else None,
    }
