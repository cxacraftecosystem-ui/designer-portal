import hashlib
import logging
import re
import secrets
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import can_run_design_workshops, is_admin, roster_barred
from app.services import design_workshop_posts as posts
from app.services.design_workshop_access import ScannedCodeRefused, add_one_viewer, code_check
from app.services.design_workshop_viewers import (
    _assert_every_id_may_be_granted,
    has_viewer_grant,
)
from app.services.records import plain

logger = logging.getLogger(__name__)


CODE_NAMESPACE = "DPW"

JOIN_CODE_VERSION = 2

JOIN_LETTER = "J"

_SECRET_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

_SECRET_LENGTH = 22

_CONFUSABLES = {"I": "1", "L": "1", "O": "0"}

_SECRET_PATTERN = re.compile(f"^[{_SECRET_ALPHABET}]{{{_SECRET_LENGTH}}}$")

_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{7,63}$")

_DEVICE_LOCAL_ID_PREFIXES = ("dwlocal-", "local-")

_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")

_ASCII_DIGITS = re.compile(r"^[0-9]+$")

DEFAULT_GRANT_DAYS = 14

MAX_GRANT_DAYS = 120

GRANT_SYNC_GRACE_DAYS = 30

VIEWER_OUTSTANDING_GRANT_LIMIT = 3

RECORD_TYPE_LETTERS: dict[str, str] = {
    "ARTISAN": "A",
    "CRAFT": "C",
    "WORKSHOP": "W",
    "PRODUCT": "D",
    "PROCESS": "S",
    "TOOL": "T",
    "QUESTIONNAIRE": "Q",
    "MEDIA": "M",
    "DESIGN_WORKSHOP": "G",
    "PROTOTYPE": "P",
}

MEMBERSHIP_RECORD_TYPES = frozenset({"DESIGN_WORKSHOP"})


def secret_hash(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def mint_secret() -> str:
    return "".join(secrets.choice(_SECRET_ALPHABET) for _ in range(_SECRET_LENGTH))


def encode_join_code(record_id: str, secret: str) -> str:
    prefix = f"{CODE_NAMESPACE}{JOIN_CODE_VERSION}:{JOIN_LETTER}:{record_id.upper()}.{secret}"
    return f"{prefix}:{code_check(prefix)}"


def decode_join_code(raw: str) -> tuple[str, str, str]:
    text = re.sub(r"\s+", "", raw or "").upper()
    if not text:
        raise ScannedCodeRefused("Nothing was scanned or typed.")

    parts = text.split(":")
    if len(parts) != 4 or not parts[0].startswith(CODE_NAMESPACE):
        raise ScannedCodeRefused(
            "That is not a workshop join card. Join cards begin “DPW”; a shop barcode, a payment "
            "code or a web address will not let you into a workshop."
        )

    version_text = parts[0][len(CODE_NAMESPACE) :]
    if not _ASCII_DIGITS.match(version_text):
        raise ScannedCodeRefused(
            "That is not a workshop join card. Join cards begin “DPW” followed by a version number."
        )
    version = int(version_text)
    if version != JOIN_CODE_VERSION:
        if version < JOIN_CODE_VERSION:
            raise ScannedCodeRefused(
                "That is a workshop's own tag, not a join card — it names the workshop but does not "
                "let anybody in. Ask whoever runs the workshop to print you a join card, or send "
                "them the tag and ask to be added."
            )
        raise ScannedCodeRefused(
            f"That card uses a newer code format ({version}). Update the app, or ask an "
            f"administrator to add you to the workshop."
        )

    if parts[1] != JOIN_LETTER:
        raise ScannedCodeRefused(
            "That code is not a join card — it names a record. Scan "
            "the join card you were handed, the one printed to let somebody in."
        )

    body = parts[2]
    if body.count(".") != 1:
        raise ScannedCodeRefused(
            "This join card is damaged or was typed incompletely. Check it against the card, "
            "character by character."
        )
    id_text, secret_text = body.split(".", 1)

    record_id = id_text.lower()
    if record_id.startswith(_DEVICE_LOCAL_ID_PREFIXES):
        raise ScannedCodeRefused(
            "That card names a workshop that had not been shared yet when it was printed — it only "
            "ever meant anything on the device that made it. Ask whoever created the workshop to "
            "sync their device and print a fresh card."
        )
    if not _ID_PATTERN.match(record_id):
        raise ScannedCodeRefused(
            "This join card is damaged or was typed incompletely — the identifier in it is not a "
            "whole one. Check it against the card."
        )

    secret = "".join(_CONFUSABLES.get(character, character) for character in secret_text)
    if not _SECRET_PATTERN.match(secret):
        raise ScannedCodeRefused(
            "This join card is damaged or was typed incompletely — the part after the full stop is "
            "not a whole one. Read it off the card again, character by character."
        )

    typed_check = "".join(_CONFUSABLES.get(character, character) for character in parts[3])
    prefix = f"{CODE_NAMESPACE}{version}:{JOIN_LETTER}:{id_text}.{secret}"
    if len(typed_check) != 4 or typed_check != code_check(prefix):
        raise ScannedCodeRefused(
            "This join card does not check out, so one of its characters is wrong. Read it off the "
            "card again, character by character."
        )

    return record_id, secret, f"{prefix}:{typed_check}"


def redacted_code(record_id: str, secret: str) -> str:
    prefix = f"{CODE_NAMESPACE}{JOIN_CODE_VERSION}:{JOIN_LETTER}:{record_id.upper()}.…{secret[-4:]}"
    return f"{prefix}:{code_check(prefix)}"



async def provisional_member(workshop_id: str, user_id: str) -> Any | None:
    if not workshop_id or not user_id:
        return None
    return await db.designworkshopprovisionalmember.find_unique(
        where={"designWorkshopId_userId": {"designWorkshopId": workshop_id, "userId": user_id}}
    )


async def may_capture(workshop_id: str, user: Any) -> bool:
    user_id = getattr(user, "id", "")
    if not user_id:
        return False
    if is_admin(user):
        return True
    if await has_viewer_grant(workshop_id, user_id):
        return True
    return await provisional_member(workshop_id, user_id) is not None



async def _workshop_for_issuer_or_404(workshop_id: str, issuer: Any) -> Any:
    wanted = plain(workshop_id).strip().lower()
    not_found = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not wanted or _UNSTORABLE_IN_AN_ID.search(workshop_id):
        raise not_found

    workshop = await db.designworkshop.find_unique(where={"id": wanted})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        raise not_found

    issuer_id = getattr(issuer, "id", "")
    if not issuer_id:
        raise not_found
    if is_admin(issuer) or workshop.createdById == issuer_id:
        return workshop
    if can_run_design_workshops(issuer) and await has_viewer_grant(wanted, issuer_id):
        return workshop
    raise not_found


async def mint_grant(
    issuer: Any,
    *,
    record_type: str,
    record_id: str,
    max_uses: int | None,
    days_valid: int | None,
    label: str | None,
) -> dict[str, Any]:
    wanted_type = (record_type or "").strip().upper()
    if wanted_type not in RECORD_TYPE_LETTERS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A record type is one of {', '.join(sorted(RECORD_TYPE_LETTERS))}.",
        )
    if wanted_type not in MEMBERSHIP_RECORD_TYPES:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "A join card can only be printed for a design workshop. Cards for other kinds of "
                "record would not let anybody in: an artisan, a tool or a product is not something "
                "a person is a member of, and access to those is decided per account rather than "
                "per record."
            ),
        )

    issuer_is_admin = is_admin(issuer)
    if max_uses is not None and max_uses < 1:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A join card is good for at least one person.",
        )
    if not issuer_is_admin and max_uses != 1:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "Only an administrator can print a card that lets more than one person in. You can "
                "print a card for one person as many times as you need; ask an administrator if you "
                "want a card for a whole group."
            ),
        )

    workshop = await _workshop_for_issuer_or_404(record_id, issuer)
    await posts.refuse_a_holders_write(workshop.id, issuer)
    issuer_id = getattr(issuer, "id", "")

    wanted_days = DEFAULT_GRANT_DAYS if days_valid is None else days_valid
    if wanted_days < 1 or wanted_days > MAX_GRANT_DAYS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A join card is valid for between 1 and {MAX_GRANT_DAYS} days.",
        )

    now = datetime.now(UTC)
    record_key = plain(record_id).strip().lower()

    if not issuer_is_admin:
        outstanding = await db.recordaccesstoken.count(
            where={
                "recordType": wanted_type,
                "recordId": record_key,
                "issuedById": issuer_id,
                "revokedAt": None,
                "expiresAt": {"gt": now},
                "usesConsumed": 0,
            }
        )
        if outstanding >= VIEWER_OUTSTANDING_GRANT_LIMIT:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=(
                    f"You already have {VIEWER_OUTSTANDING_GRANT_LIMIT} unused join cards printed "
                    f"for this workshop. Let somebody use one, or ask an administrator to cancel "
                    f"them, before printing another."
                ),
            )

    secret = mint_secret()
    token = await db.recordaccesstoken.create(
        data={
            "recordType": wanted_type,
            "recordId": record_key,
            "secretHash": secret_hash(secret),
            "secretLast4": secret[-4:],
            "issuedById": issuer_id or None,
            "maxUses": max_uses,
            "expiresAt": now + timedelta(days=wanted_days),
            "label": plain(label) if label else None,
        }
    )

    logger.info(
        "join card minted: token=%s recordType=%s record=%s maxUses=%s issuer=%s",
        token.id,
        wanted_type,
        record_key,
        "unlimited" if max_uses is None else max_uses,
        issuer_id or "unknown",
    )
    return {**grant_payload(token), "code": encode_join_code(record_key, secret)}


def grant_payload(row: Any, *, issuer: Any = None) -> dict[str, Any]:
    return {
        "id": row.id,
        "recordType": str(getattr(row.recordType, "value", row.recordType)),
        "recordId": row.recordId,
        "secretLast4": row.secretLast4,
        "maxUses": row.maxUses,
        "usesConsumed": row.usesConsumed,
        "expiresAt": row.expiresAt.isoformat() if row.expiresAt else None,
        "revokedAt": row.revokedAt.isoformat() if row.revokedAt else None,
        "label": row.label,
        "createdAt": row.createdAt.isoformat() if row.createdAt else None,
        "issuedBy": (
            None
            if issuer is None
            else {
                "id": row.issuedById,
                "name": getattr(issuer, "name", "") or "",
                "email": getattr(issuer, "email", "") or "",
            }
        ),
    }


GRANT_LIST_LIMIT = 200


async def list_grants(record_id: str, viewer: Any) -> dict[str, Any]:
    await _workshop_for_issuer_or_404(record_id, viewer)
    rows = await db.recordaccesstoken.find_many(
        where={"recordType": "DESIGN_WORKSHOP", "recordId": plain(record_id).strip().lower()},
        include={"issuedBy": True},
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=GRANT_LIST_LIMIT + 1,
    )
    truncated = len(rows) > GRANT_LIST_LIMIT
    if truncated:
        rows = rows[:GRANT_LIST_LIMIT]
        logger.warning(
            "the join-card list hit its ceiling of %s rows for record %s; the answer is truncated "
            "and says so",
            GRANT_LIST_LIMIT,
            record_id,
        )
    return {
        "grants": [grant_payload(row, issuer=getattr(row, "issuedBy", None)) for row in rows],
        "truncated": truncated,
    }


async def revoke_grant(token_id: str, revoker: Any) -> dict[str, Any]:
    wanted = plain(token_id).strip()
    not_found = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not wanted:
        raise not_found
    token = await db.recordaccesstoken.find_unique(where={"id": wanted})
    if token is None:
        raise not_found

    await _workshop_for_issuer_or_404(token.recordId, revoker)

    if token.revokedAt is None:
        await db.recordaccesstoken.update(
            where={"id": token.id},
            data={"revokedAt": datetime.now(UTC), "revokedById": getattr(revoker, "id", None)},
        )
        logger.info("join card revoked: token=%s by=%s", token.id, getattr(revoker, "id", "?"))
    fresh = await db.recordaccesstoken.find_unique(
        where={"id": token.id}, include={"issuedBy": True}
    )
    if fresh is None:
        raise not_found
    return grant_payload(fresh, issuer=getattr(fresh, "issuedBy", None))


CARD_REFUSED_DETAIL = (
    "This join card cannot be used. It may have been cancelled, or it may have run out of date — a "
    "card is only good for a few weeks. Ask whoever runs the workshop for a fresh card, or ask an "
    "administrator to add you from the workshop's viewers screen. Everything you have already "
    "recorded on this device stays where it is."
)


class CardRefused(Exception):
    pass


async def _consume_a_seat(token: Any, *, now: datetime) -> int | None:
    if token.revokedAt is not None:
        return None
    if token.expiresAt is not None and token.expiresAt <= now:
        return None
    seats_before = token.usesConsumed
    if token.maxUses is not None and seats_before >= token.maxUses:
        return None
    updated = await db.recordaccesstoken.update_many(
        where={
            "id": token.id,
            "usesConsumed": seats_before,
            "revokedAt": None,
        },
        data={"usesConsumed": seats_before + 1},
    )
    return seats_before + 1 if updated == 1 else None


async def _give_the_seat_back(token_id: str, *, seats_taken: int) -> None:
    restored = await db.recordaccesstoken.update_many(
        where={"id": token_id, "usesConsumed": seats_taken},
        data={"usesConsumed": seats_taken - 1},
    )
    if not restored:
        logger.warning(
            "a join card's seat could not be given back because the count had already moved "
            "(token=%s); the card will admit one fewer person than it says, which is the safe "
            "direction",
            token_id,
        )


async def _try_to_give_the_seat_back(token_id: str, *, seats_taken: int) -> None:
    try:
        await _give_the_seat_back(token_id, seats_taken=seats_taken)
    except Exception:
        logger.exception(
            "a join card's seat could not be given back after the grant failed (token=%s); the card "
            "will admit one fewer person than it says, which is the safe direction",
            token_id,
        )


async def _why_the_redeemer_cannot_be_a_viewer(user_id: str, workshop_id: str) -> str | None:
    try:
        await _assert_every_id_may_be_granted({user_id}, workshop_id=workshop_id)
    except HTTPException as refusal:
        if refusal.status_code not in (
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            status.HTTP_409_CONFLICT,
        ):
            raise
        return str(refusal.detail)
    return None


async def _write_the_viewer_row(
    tx: Any,
    *,
    workshop_id: str,
    user_id: str,
    token_id: str,
    granted_by_id: str | None,
) -> None:
    await add_one_viewer(
        tx,
        workshop_id=workshop_id,
        user_id=user_id,
        granted_by_id=granted_by_id,
        token_id=token_id,
    )


async def _file_or_refresh_the_queue_row(
    tx: Any,
    *,
    workshop_id: str,
    user_id: str,
    token_id: str,
    stored_code: str,
    scanned_at: datetime | None,
    now: datetime,
) -> None:
    created = await tx.designworkshopaccessrequest.create_many(
        data=[
            {
                "designWorkshopId": workshop_id,
                "requestedById": user_id,
                "status": "PENDING",
                "source": "SCAN",
                "scannedCode": stored_code,
                "tokenId": token_id,
                "scannedAt": scanned_at,
            }
        ],
        skip_duplicates=True,
    )
    if created:
        return
    await tx.designworkshopaccessrequest.update_many(
        where={
            "designWorkshopId": workshop_id,
            "requestedById": user_id,
            "status": "PENDING",
        },
        data={
            "source": "SCAN",
            "scannedCode": stored_code,
            "tokenId": token_id,
            "scannedAt": scanned_at,
        },
    )


async def _issuer_still_admits(token: Any, workshop: Any) -> bool:
    issuer_id = getattr(token, "issuedById", None)
    if not issuer_id:
        return False
    issuer = await db.user.find_unique(where={"id": issuer_id})
    if issuer is None or await roster_barred(issuer):
        return False
    if is_admin(issuer) or workshop.createdById == issuer_id:
        return True
    return can_run_design_workshops(issuer) and await has_viewer_grant(workshop.id, issuer_id)


async def redeem(
    user: Any,
    *,
    code: str,
    scanned_at_client: datetime | None = None,
    scanned_at_elapsed_sec: int | None = None,
    synced_at_elapsed_sec: int | None = None,
    boot_id: str | None = None,
    clock_jump_observed: bool = False,
) -> dict[str, Any]:
    record_id, secret, _canonical = decode_join_code(code)

    now = datetime.now(UTC)

    user_id = getattr(user, "id", "")
    if not user_id:
        raise CardRefused()

    token = await db.recordaccesstoken.find_unique(where={"secretHash": secret_hash(secret)})
    if token is None:
        raise CardRefused()
    if str(getattr(token.recordType, "value", token.recordType)) != "DESIGN_WORKSHOP":
        raise CardRefused()
    if token.recordId != record_id:
        raise CardRefused()
    if token.revokedAt is not None:
        raise CardRefused()

    expired = token.expiresAt is not None and token.expiresAt <= now
    if expired and token.expiresAt is not None:
        beyond_grace = now - token.expiresAt > timedelta(days=GRANT_SYNC_GRACE_DAYS)
        if beyond_grace:
            raise CardRefused()

    workshop = await db.designworkshop.find_unique(where={"id": token.recordId})
    if workshop is None or getattr(workshop, "deletedAt", None) is not None:
        raise CardRefused()
    if not await _issuer_still_admits(token, workshop):
        raise CardRefused()

    if (
        workshop.createdById == user_id
        or is_admin(user)
        or await has_viewer_grant(token.recordId, user_id)
    ):
        return {
            "outcome": "ALREADY_A_MEMBER",
            "workshopId": token.recordId,
            "detail": (
                "You are already on this workshop, so the card was not used up. Somebody else can "
                "still use it."
            ),
        }

    prior = await db.recordaccesstokenredemption.find_unique(
        where={"tokenId_userId": {"tokenId": token.id, "userId": user_id}}
    )
    if prior is not None:
        outcome = str(getattr(prior.outcome, "value", prior.outcome))
        prior_reason = str(getattr(prior.reason, "value", prior.reason))
        return {
            "outcome": outcome,
            "reason": prior_reason,
            "workshopId": token.recordId,
            "detail": (_FULL_DETAIL if outcome == "FULL" else _provisional_detail(prior_reason)),
        }

    prior_request = await db.designworkshopaccessrequest.find_unique(
        where={
            "designWorkshopId_requestedById": {
                "designWorkshopId": token.recordId,
                "requestedById": user_id,
            }
        }
    )
    superseded_a_refusal = (
        prior_request is not None
        and str(getattr(prior_request.status, "value", prior_request.status)) == "DENIED"
    )

    stored_code = redacted_code(token.recordId, secret)
    evidence = {
        "scannedAtClient": scanned_at_client,
        "scannedAtElapsedSec": scanned_at_elapsed_sec,
        "syncedAtElapsedSec": synced_at_elapsed_sec,
        "bootId": plain(boot_id)[:200] if boot_id else None,
        "clockJumpObserved": bool(clock_jump_observed),
        "serverArrivedAt": now,
    }

    if expired:
        reason = "EXPIRED"
    elif (
        ineligible := await _why_the_redeemer_cannot_be_a_viewer(user_id, token.recordId)
    ) is not None:
        logger.warning(
            "join card could not grant a viewer row because the redeemer's own account is not "
            "eligible (token=%s workshop=%s user=%s): %s",
            token.id,
            token.recordId,
            user_id,
            ineligible,
        )
        reason = "INELIGIBLE"
    elif (seats_taken := await _consume_a_seat(token, now=now)) is None:
        reason = "ALREADY_SPENT"
    else:
        try:
            async with db.tx() as tx:
                await _write_the_viewer_row(
                    tx,
                    workshop_id=token.recordId,
                    user_id=user_id,
                    token_id=token.id,
                    granted_by_id=token.issuedById,
                )
                await tx.designworkshopprovisionalmember.delete_many(
                    where={"designWorkshopId": token.recordId, "userId": user_id}
                )
                await tx.recordaccesstokenredemption.create(
                    data={
                        "tokenId": token.id,
                        "userId": user_id,
                        "outcome": "FULL",
                        "reason": "OK",
                        **evidence,
                    }
                )
                await _file_or_refresh_the_queue_row(
                    tx,
                    workshop_id=token.recordId,
                    user_id=user_id,
                    token_id=token.id,
                    stored_code=stored_code,
                    scanned_at=scanned_at_client,
                    now=now,
                )
                if superseded_a_refusal:
                    logger.warning(
                        "a join card admitted somebody an administrator had previously REFUSED for "
                        "this workshop (workshop=%s user=%s token=%s issuer=%s); the earlier "
                        "decision's columns are kept on the row",
                        token.recordId,
                        user_id,
                        token.id,
                        token.issuedById or "unknown",
                    )
                await tx.designworkshopaccessrequest.update_many(
                    where={"designWorkshopId": token.recordId, "requestedById": user_id},
                    data={
                        "status": "GRANTED",
                        "source": "SCAN",
                        "scannedCode": stored_code,
                        "tokenId": token.id,
                        "scannedAt": scanned_at_client,
                    },
                )
        except BaseException:
            await _try_to_give_the_seat_back(token.id, seats_taken=seats_taken)
            raise
        logger.info(
            "join card redeemed in full: token=%s user=%s workshop=%s",
            token.id,
            user_id,
            token.recordId,
        )
        return {
            "outcome": "FULL",
            "reason": "OK",
            "workshopId": token.recordId,
            "detail": _FULL_DETAIL,
        }

    async with db.tx() as tx:
        await tx.designworkshopprovisionalmember.create_many(
            data=[
                {
                    "designWorkshopId": token.recordId,
                    "userId": user_id,
                    "viaTokenId": token.id,
                    "reason": reason,
                    "scannedAtClient": scanned_at_client,
                    "serverArrivedAt": now,
                }
            ],
            skip_duplicates=True,
        )
        await tx.recordaccesstokenredemption.create(
            data={
                "tokenId": token.id,
                "userId": user_id,
                "outcome": "PROVISIONAL",
                "reason": reason,
                **evidence,
            }
        )
        await _file_or_refresh_the_queue_row(
            tx,
            workshop_id=token.recordId,
            user_id=user_id,
            token_id=token.id,
            stored_code=stored_code,
            scanned_at=scanned_at_client,
            now=now,
        )
    logger.info(
        "join card redeemed provisionally (%s): token=%s user=%s workshop=%s",
        reason,
        token.id,
        user_id,
        token.recordId,
    )
    return {
        "outcome": "PROVISIONAL",
        "reason": reason,
        "workshopId": token.recordId,
        "detail": _provisional_detail(reason),
    }


_FULL_DETAIL = (
    "You are on this workshop. The card has been used up, so it will not let anybody else in."
)

_PROVISIONAL_DETAIL = (
    "That card had already been used, so you are not on the workshop yet — but nothing you record "
    "is lost. You can keep capturing your own work here and an administrator can see that you "
    "scanned the card; once they confirm you, everything you have recorded is already in place. "
    "Until then you will not see anybody else's stages."
)

_INELIGIBLE_DETAIL = (
    "That card cannot put your account on this workshop by itself, so you are not on it yet — but "
    "nothing you record is lost, and the card was not used up. You can keep capturing your own "
    "work here and an administrator can see that you scanned the card; if they add you, "
    "everything you have recorded is already in place. Until then you will not see anybody else's "
    "stages."
)


_EXPIRED_DETAIL = (
    "That card's date had passed by the time your scan reached us, so you are not on the workshop "
    "yet — but nothing you record is lost, and the card was not used up. You can keep capturing "
    "your own work here and an administrator can see that you scanned the card; once they confirm "
    "you, everything you have recorded is already in place. Until then you will not see anybody "
    "else's stages."
)


def _provisional_detail(reason: str) -> str:
    if reason == "INELIGIBLE":
        return _INELIGIBLE_DETAIL
    if reason == "EXPIRED":
        return _EXPIRED_DETAIL
    return _PROVISIONAL_DETAIL
