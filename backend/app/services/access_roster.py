import logging
from datetime import UTC, datetime
from typing import Any, NamedTuple

from prisma.errors import UniqueViolationError

from app.core.config import get_settings
from app.core.db import db
from app.services.designers import (
    GMAIL_DOMAINS,
    canonical_email,
    carry_ended_empanelment,
    email_match_keys,
    normalise_email,
    note_recording_a_consequence,
    person_name,
    roster_allows,
    suspend_empanelment,
)

logger = logging.getLogger(__name__)

PENDING_CAP_SETTING = "ACCESS_PENDING_MAX"

MAX_EMAIL_LENGTH = 254

ACTIVE = "ACTIVE"
PENDING = "PENDING"
REJECTED = "REJECTED"
SUSPENDED = "SUSPENDED"

NOT_RECORDED = "NOT_RECORDED"

BARRED = (REJECTED, SUSPENDED)


def status_of(row: Any) -> str:
    if row is None:
        return ""
    status = getattr(row, "status", None)
    return str(getattr(status, "value", status) or "")


def admits(row: Any) -> bool:
    return status_of(row) == ACTIVE


def role_of(row: Any) -> str | None:
    if row is None:
        return None
    role = getattr(row, "admitRole", None)
    if role is None:
        return None
    return str(getattr(role, "value", role)) or None


def pending_cap() -> int:
    return max(1, int(get_settings().access_pending_max))


def _the_row_that_decides(rows: list[Any], literal: str) -> Any:
    return min(
        rows,
        key=lambda row: (
            0 if status_of(row) in BARRED else 1,
            0 if normalise_email(row.email) == literal else 1,
        ),
    )


async def access_row(email: Any) -> Any | None:
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return None
    rows = await db.accessroster.find_many(where={"email": {"in": keys}})
    if not rows:
        return None
    if len(rows) == 1:
        return rows[0]
    return _the_row_that_decides(rows, keys[0])


async def designer_empanelment_admits(email: Any) -> bool:
    return await roster_allows(email)


BARRED_EMAIL_READ_LIMIT = 50_000


async def barred_emails() -> list[str]:
    rows = await db.accessroster.find_many(
        where={"status": {"in": list(BARRED)}}, take=BARRED_EMAIL_READ_LIMIT + 1
    )
    if len(rows) > BARRED_EMAIL_READ_LIMIT:
        rows = rows[:BARRED_EMAIL_READ_LIMIT]
        logger.error(
            "the allow-list holds more than %s barred addresses, so only part of that set was "
            "read; any screen filtering on it may now OFFER an address an administrator has "
            "rejected or suspended, and only the write path will refuse them",
            BARRED_EMAIL_READ_LIMIT,
        )
    return sorted({normalise_email(row.email) for row in rows if normalise_email(row.email)})


async def barred_among(emails: list[str]) -> set[str]:
    wanted = sorted({normalise_email(e) for e in emails if normalise_email(e)})
    if not wanted:
        return set()
    rows = await db.accessroster.find_many(
        where={"AND": [{"email": {"in": wanted}}, {"status": {"in": list(BARRED)}}]}
    )
    return {normalise_email(row.email) for row in rows}


async def pending_count() -> int:
    return await db.accessroster.count(where={"status": PENDING})


async def admit(
    email: Any,
    *,
    admit_role: str | None = None,
    actor_id: str | None = None,
    full_name: str | None = None,
    note: str | None = None,
    decided: bool = True,
    client: Any = None,
) -> Any:
    writer = db if client is None else client
    keys = email_match_keys(email)
    if not keys:
        raise ValueError("an allow-list row needs an email address")
    address = canonical_email(email)
    now = datetime.now(UTC)
    grant: dict[str, Any] = {"status": ACTIVE, "joinedAt": now}
    if admit_role:
        grant["admitRole"] = admit_role
    if full_name is not None:
        grant["fullName"] = full_name
    if note is not None:
        grant["notes"] = note
    if decided:
        grant["decidedAt"] = now
        grant["decidedById"] = actor_id

    matched = await writer.accessroster.find_many(where={"email": {"in": keys}})
    if not matched:
        return await writer.accessroster.create(
            data={**grant, "email": address, "addedById": actor_id}
        )
    existing = _the_row_that_decides(matched, keys[0])
    update = dict(grant)
    update["joinedAt"] = existing.joinedAt or now
    return await writer.accessroster.update(where={"id": existing.id}, data=update)


BAR_CARRIED_BY_EMAIL_MOVE_NOTE = (
    "Barred automatically because an account an administrator had barred at another address was "
    "moved to this one, and the bar moved with the account. Restore this row here if letting the "
    "account back in was intended; moving it again will not."
)

EMPANELMENT_CARRIED_BY_EMAIL_MOVE_NOTE = (
    "Empanelment ended automatically because the account whose empanelment an administrator had "
    "ended at another address was moved to this one, and the ending moved with the account. Restore "
    "it here if that was intended; moving the account again will not."
)


class EmailMove(NamedTuple):
    carried_bar: str | None = None
    carried_ended_empanelment: bool = False


async def follow_email_change(old_email: Any, new_email: Any, *, actor_id: str | None) -> EmailMove:
    new = canonical_email(new_email)
    if not new or canonical_email(old_email) == new:
        return EmailMove()
    old_row = await access_row(old_email)
    destination = await access_row(new_email)
    carried_empanelment = await carry_ended_empanelment(
        old_email, new_email, because=EMPANELMENT_CARRIED_BY_EMAIL_MOVE_NOTE
    )
    bar = status_of(old_row)
    if bar in BARRED:
        now = datetime.now(UTC)
        if destination is None:
            carried: dict[str, Any] = {
                "email": new,
                "status": bar,
                "decidedAt": old_row.decidedAt or now,
                "notes": BAR_CARRIED_BY_EMAIL_MOVE_NOTE,
            }
            for column, value in (
                ("decidedById", old_row.decidedById or actor_id),
                ("admitRole", role_of(old_row)),
                ("fullName", old_row.fullName),
            ):
                if value is not None:
                    carried[column] = value
            try:
                await db.accessroster.create(data=carried)
            except UniqueViolationError:
                destination = await access_row(new_email)
            else:
                return EmailMove(bar, carried_empanelment)
        if destination is not None and status_of(destination) not in BARRED:
            await db.accessroster.update(
                where={"id": destination.id},
                data={
                    "status": bar,
                    "decidedAt": old_row.decidedAt or now,
                    "decidedById": old_row.decidedById or actor_id,
                    "notes": note_recording_a_consequence(
                        destination.notes, BAR_CARRIED_BY_EMAIL_MOVE_NOTE
                    ),
                },
            )
        return EmailMove(bar, carried_empanelment)
    if destination is not None:
        await admit(
            destination.email,
            actor_id=actor_id,
            note="Admitted when an administrator moved an existing account to this address.",
        )
        return EmailMove(None, carried_empanelment)
    if old_row is None:
        await admit(
            new,
            actor_id=actor_id,
            note="Admitted when an administrator set this address on an existing account.",
        )
        return EmailMove(None, carried_empanelment)
    await db.accessroster.update(where={"id": old_row.id}, data={"email": new})
    return EmailMove(None, carried_empanelment)


async def record_refused_attempt(email: Any, row: Any | None) -> str:
    address = canonical_email(email)
    now = datetime.now(UTC)

    if row is not None:
        await db.accessroster.update(
            where={"id": row.id},
            data={"attemptCount": {"increment": 1}, "lastAttemptAt": now},
        )
        return status_of(row)

    if not address or len(address) > MAX_EMAIL_LENGTH:
        logger.warning("refusing to record an access request for an unusable address")
        return NOT_RECORDED

    waiting = await pending_count()
    if waiting >= pending_cap():
        logger.error(
            "the pending access queue holds %s requests, at or past the %s cap, so a new request "
            "from a verified identity was NOT recorded; an administrator must clear the queue "
            "before anybody new can ask to join",
            waiting,
            pending_cap(),
        )
        return NOT_RECORDED

    created = await db.accessroster.upsert(
        where={"email": address},
        data={
            "create": {
                "email": address,
                "status": PENDING,
                "requestedAt": now,
                "attemptCount": 1,
                "lastAttemptAt": now,
            },
            "update": {"attemptCount": {"increment": 1}, "lastAttemptAt": now},
        },
    )
    return status_of(created)


async def mark_access_seen(row: Any) -> None:
    if row is None or getattr(row, "firstSeenAt", None) is not None:
        return
    await db.accessroster.update_many(
        where={"id": row.id, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


MIRROR_ACCESS_SUSPENDED = "ACCESS_SUSPENDED"
MIRROR_ACCESS_REJECTED = "ACCESS_REJECTED"
MIRROR_EMPANELMENT_ENDED = "EMPANELMENT_ENDED"

MIRROR_NOTES: dict[str, str] = {
    MIRROR_ACCESS_SUSPENDED: (
        "Empanelment suspended automatically because an administrator suspended this address on "
        "the platform allow-list, ending its access to the application. No administrator withdrew "
        "this empanelment on the designer roster directly, and restoring the allow-list row will "
        "not restore it — that is a separate decision, taken here."
    ),
    MIRROR_ACCESS_REJECTED: (
        "Empanelment suspended automatically because an administrator rejected this address's "
        "request to access the application on the platform allow-list. No administrator withdrew "
        "this empanelment on the designer roster directly, and approving the allow-list row will "
        "not restore it — that is a separate decision, taken here."
    ),
    MIRROR_EMPANELMENT_ENDED: (
        "Access suspended automatically because an administrator ended this address's designer "
        "empanelment, which is the basis on which it was admitted to the application. No "
        "administrator barred this address on the allow-list directly, and restoring the "
        "empanelment will not restore this — that is a separate decision, taken here."
    ),
}


def _account_role(user: Any) -> str:
    if user is None:
        return ""
    role = getattr(user, "role", None)
    return str(getattr(role, "value", role) or "")


GMAIL_ACCOUNT_SWEEP_LIMIT = 20_000


async def _accounts_on_the_mailbox(keys: list[str], mailbox: str) -> list[Any] | None:
    accounts = await db.user.find_many(where={"email": {"in": keys, "mode": "insensitive"}})
    if mailbox.partition("@")[2] not in GMAIL_DOMAINS:
        return accounts
    swept = await db.user.find_many(
        where={
            "OR": [
                {"email": {"endswith": f"@{domain}", "mode": "insensitive"}}
                for domain in sorted(GMAIL_DOMAINS)
            ]
        },
        take=GMAIL_ACCOUNT_SWEEP_LIMIT + 1,
    )
    if len(swept) > GMAIL_ACCOUNT_SWEEP_LIMIT:
        logger.error(
            "the cross-roster mirror could not check whether %r belongs to somebody who is not a "
            "designer: more than %s accounts sit on the Gmail domains, so the sweep that finds an "
            "account filed under another spelling of one mailbox was CUT and its answer cannot be "
            "trusted. NOTHING WAS MIRRORED for this address, deliberately — barring somebody on a "
            "cut list is how an administrator gets locked out of the product. Raise "
            "GMAIL_ACCOUNT_SWEEP_LIMIT, then settle the pair with "
            "scripts/backfill_roster_suspension_mirror.py",
            mailbox,
            GMAIL_ACCOUNT_SWEEP_LIMIT,
        )
        return None
    found = {account.id: account for account in accounts}
    for account in swept:
        if canonical_email(account.email) == mailbox:
            found[account.id] = account
    return list(found.values())


async def accounts_at_the_address(email: Any) -> list[Any]:
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return []
    return await db.user.find_many(where={"email": {"in": keys, "mode": "insensitive"}})


async def accounts_on_the_mailbox(email: Any) -> list[Any] | None:
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return []
    return await _accounts_on_the_mailbox(keys, canonical_email(email))


_ASCII_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
_ASCII_LOWER = "abcdefghijklmnopqrstuvwxyz"
_PRINTABLE_ASCII = "".join(chr(code) for code in range(0x21, 0x7F))

_GMAIL_MAILBOX_CANDIDATES_SQL = f"""
SELECT "id" FROM "User"
WHERE ("email" ILIKE '%@gmail.com' OR "email" ILIKE '%@googlemail.com')
  AND (
       replace(split_part(split_part(translate("email", $2, $3), '@', 1), '+', 1), '.', '')
         || '@gmail.com' = $1
    OR translate("email", $2, $3) = $1
    OR translate("email", $4, '') <> ''
  )
LIMIT {GMAIL_ACCOUNT_SWEEP_LIMIT + 1}
"""


async def accounts_on_the_mailbox_for_sign_in(email: Any) -> list[Any] | None:
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return []
    mailbox = canonical_email(email)
    accounts = await db.user.find_many(where={"email": {"in": keys, "mode": "insensitive"}})
    if mailbox.partition("@")[2] not in GMAIL_DOMAINS:
        return accounts
    rows = await db.query_raw(
        _GMAIL_MAILBOX_CANDIDATES_SQL, mailbox, _ASCII_UPPER, _ASCII_LOWER, _PRINTABLE_ASCII
    )
    if len(rows) > GMAIL_ACCOUNT_SWEEP_LIMIT:
        logger.error(
            "the sign-in lookup could not check every spelling of %r: more than %s accounts on the "
            "Gmail domains are candidates for it, so its answer was withdrawn. Raise "
            "GMAIL_ACCOUNT_SWEEP_LIMIT.",
            mailbox,
            GMAIL_ACCOUNT_SWEEP_LIMIT,
        )
        return None
    found = {account.id: account for account in accounts}
    candidates = sorted({str(row["id"]) for row in rows} - set(found))
    if candidates:
        for account in await db.user.find_many(where={"id": {"in": candidates}}):
            if canonical_email(account.email) == mailbox:
                found[account.id] = account
    return list(found.values())


async def admissions_an_empanelment_carries(email: Any) -> list[Any] | None:
    keys = [key for key in email_match_keys(email) if len(key) <= MAX_EMAIL_LENGTH]
    if not keys:
        return []
    rows = await db.accessroster.find_many(where={"email": {"in": keys}, "status": ACTIVE})
    admitted_as_a_designer = [row for row in rows if role_of(row) == "DESIGNER"]
    if not admitted_as_a_designer:
        return []
    accounts = await _accounts_on_the_mailbox(keys, canonical_email(email))
    if accounts is None:
        return None
    if any(_account_role(account) != "DESIGNER" for account in accounts):
        return []
    return admitted_as_a_designer


async def _bar_an_ended_empanelment(email: Any, *, actor_id: str | None, because: str) -> int:
    rows = await admissions_an_empanelment_carries(email)
    if not rows:
        return 0
    now = datetime.now(UTC)
    barred = 0
    for row in rows:
        barred += await db.accessroster.update_many(
            where={"id": row.id, "status": ACTIVE},
            data={
                "status": SUSPENDED,
                "decidedAt": row.decidedAt or now,
                "decidedById": row.decidedById or actor_id,
                "notes": note_recording_a_consequence(row.notes, because),
            },
        )
    return barred


async def mirror_suspension(email: Any, cause: str, *, actor_id: str | None = None) -> int:
    if cause not in MIRROR_NOTES:
        raise ValueError(f"unknown cross-roster suspension cause {cause!r}")
    because = MIRROR_NOTES[cause]
    try:
        if cause == MIRROR_EMPANELMENT_ENDED:
            changed = await _bar_an_ended_empanelment(email, actor_id=actor_id, because=because)
        else:
            changed = await suspend_empanelment(email, because=because)
    except Exception:
        logger.exception(
            "the cross-roster suspension mirror FAILED for %r after a %s: the two rosters now "
            "disagree about this address, one of them still showing an active standing for "
            "somebody an administrator has revoked. The administrator's own action DID take "
            "effect. Repair with scripts/backfill_roster_suspension_mirror.py",
            normalise_email(email),
            cause,
        )
        return 0
    if changed:
        logger.info(
            "mirrored a %s onto %s row(s) for %r on the other roster",
            cause,
            changed,
            normalise_email(email),
        )
    return changed


def access_payload(row: Any) -> dict[str, Any]:
    return {
        "id": row.id,
        "email": row.email,
        "fullName": row.fullName,
        "status": status_of(row),
        "admitRole": role_of(row),
        "joinedAt": _iso(row.joinedAt),
        "requestedAt": _iso(row.requestedAt),
        "attemptCount": row.attemptCount,
        "lastAttemptAt": _iso(row.lastAttemptAt),
        "decidedAt": _iso(row.decidedAt),
        "decidedById": row.decidedById,
        "firstSeenAt": _iso(row.firstSeenAt),
        "notes": row.notes,
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
        "addedById": row.addedById,
        "addedByName": person_name(getattr(row, "addedBy", None)),
        "decidedByName": person_name(getattr(row, "decidedBy", None)),
    }


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None
