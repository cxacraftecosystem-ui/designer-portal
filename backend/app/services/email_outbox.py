"""THE E-MAIL OUTBOX: queueing a message, draining the queue, and the send log.

A request never talks to SES. It writes one ``EmailMessage`` row (:func:`enqueue`) and answers;
the queue worker — ``app/worker.py``'s ``fieldrepo-queue.service``, the same process and the same
loop that drains the media queue — calls :func:`process_next_email_jobs` on every pass, which
claims due rows with a compare-and-set, renders them, hands them to :mod:`app.services.mailer`,
and records what happened on the row. That row IS the send log: status, attempts, ``sentAt``,
SES's message id, or the error code that stopped it.

RETRIES. A transient refusal (throttling, an SES fault, the network) puts the row back to QUEUED
with ``runAfter`` pushed out 1, 2, 4, 8 … minutes (capped at an hour) until ``maxAttempts`` is
spent; a permanent one (a rejected message, an unverified sender) fails it at once. A worker that
dies holding a row leaves it SENDING; :func:`recover_stale` hands it back after
:data:`STALE_SENDING_AFTER` — a message may then go twice, which for a notice is the right side to
err on and for a password link is harmless (the first redemption spends both copies).

SECRETS. ``params`` holds only what may be kept. The set-password link travels Fernet-sealed in
``sealed`` (``managed_secrets.encrypt``) and is cleared when the row reaches SENT or FAILED. Log
lines carry the row id, the kind and the recipient's ACCOUNT id — never an address, never a body,
never a link.

PREFERENCES. A notification (:data:`mailer.REVIEW_SUGGESTION`, :data:`mailer.REVIEW_SENT_BACK`)
is queued only for somebody whose ``UserPreference.emailReviewNotes`` is not false — opt-out, so
an account that never saved a preference is e-mailed. A password link an administrator chose to
e-mail is not a notification and is not governed by it.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import mailer, managed_secrets
from prisma import Json

logger = logging.getLogger(__name__)

QUEUED = "QUEUED"
SENDING = "SENDING"
SENT = "SENT"
FAILED = "FAILED"

STALE_SENDING_AFTER = timedelta(minutes=15)
MAX_BACKOFF_MINUTES = 60
#: Stored error text is clipped: it is an SES error code plus a short sentence, never a body.
MAX_ERROR_LENGTH = 300


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


async def wants_review_notes(user_id: str) -> bool:
    """The opt-out. No preference row (never saved one) reads as yes."""
    row = await db.userpreference.find_unique(where={"userId": user_id})
    if row is None:
        return True
    return bool(getattr(row, "emailReviewNotes", True))


async def enqueue(
    kind: str,
    *,
    to_address: str,
    recipient_id: str | None,
    params: dict[str, Any],
    secret: str | None = None,
    settings: Settings | None = None,
) -> Any | None:
    """Queue one message. Returns the row, or ``None`` when mail is not configured."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return None
    if kind not in mailer.KINDS:
        raise ValueError(f"Unknown e-mail kind {kind!r}")
    address = (to_address or "").strip()
    if not address:
        return None
    row = await db.emailmessage.create(
        data={
            "kind": kind,
            "toAddress": address,
            "recipientId": recipient_id,
            "subject": mailer.subject_for(kind, params),
            "params": Json(params),
            "sealed": managed_secrets.encrypt(secret) if secret else None,
            "status": QUEUED,
            "maxAttempts": max(int(settings.mail_max_attempts or 1), 1),
        }
    )
    logger.info("mail: queued %s message %s for account %s", kind, row.id, recipient_id or "-")
    return row


async def recover_stale(now: datetime | None = None) -> int:
    """Hand back rows a dead worker left SENDING. Exhausted ones fail instead (and lose the seal)."""
    cutoff = (now or datetime.now(UTC)) - STALE_SENDING_AFTER
    stale = await db.emailmessage.find_many(
        where={"status": SENDING, "lockedAt": {"lt": cutoff}}, take=100
    )
    recovered = 0
    for row in stale:
        if int(row.attempts or 0) >= max(int(row.maxAttempts or 1), 1):
            await _finish(row.id, FAILED, error="The worker stopped while sending, on the last attempt.")
        else:
            await db.emailmessage.update_many(
                where={"id": row.id, "status": SENDING},
                data={"status": QUEUED, "lockedAt": None, "lockedBy": None},
            )
        recovered += 1
    return recovered


async def _claim(row: Any, worker_id: str) -> bool:
    claimed = await db.emailmessage.update_many(
        where={"id": row.id, "status": QUEUED},
        data={
            "status": SENDING,
            "lockedAt": datetime.now(UTC),
            "lockedBy": worker_id,
            "attempts": int(row.attempts or 0) + 1,
        },
    )
    return bool(claimed)


async def _finish(
    row_id: str, status: str, *, error: str | None = None, message_id: str | None = None
) -> None:
    data: dict[str, Any] = {
        "status": status,
        "lockedAt": None,
        "lockedBy": None,
        "sealed": None,
        "error": (error or None) and error[:MAX_ERROR_LENGTH],
    }
    if status == SENT:
        data["sentAt"] = datetime.now(UTC)
        data["providerMessageId"] = message_id or None
    await db.emailmessage.update(where={"id": row_id}, data=data)


async def _retry_later(row: Any, attempts: int, error: str) -> None:
    delay = min(MAX_BACKOFF_MINUTES, 2 ** max(attempts - 1, 0))
    await db.emailmessage.update(
        where={"id": row.id},
        data={
            "status": QUEUED,
            "lockedAt": None,
            "lockedBy": None,
            "runAfter": datetime.now(UTC) + timedelta(minutes=delay),
            "error": error[:MAX_ERROR_LENGTH],
        },
    )


def _expired(params: dict[str, Any]) -> bool:
    raw = params.get("expiresAt")
    if not isinstance(raw, str):
        return False
    try:
        moment = datetime.fromisoformat(raw)
    except ValueError:
        return False
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return moment <= datetime.now(UTC)


async def _send_one(row: Any, settings: Settings) -> str:
    """Render and send one claimed row; returns the status it was left in."""
    attempts = int(row.attempts or 0) + 1
    params = dict(row.params or {}) if isinstance(row.params, dict) else {}
    secret: str | None = None
    if row.sealed:
        secret = managed_secrets.decrypt(row.sealed)
        if secret is None:
            await _finish(row.id, FAILED, error="The sealed link could not be opened (the key changed).")
            return FAILED
    if row.kind == mailer.PASSWORD_LINK and _expired(params):
        await _finish(row.id, FAILED, error="The link expired before it could be sent.")
        return FAILED
    try:
        rendered = mailer.render(row.kind, params, secret=secret, settings=settings)
    except ValueError as exc:
        await _finish(row.id, FAILED, error=f"Could not be rendered: {exc}")
        return FAILED
    try:
        message_id = await asyncio.to_thread(
            mailer.send,
            to=row.toAddress,
            subject=rendered.subject,
            text=rendered.text,
            html_body=rendered.html,
            settings=settings,
        )
    except mailer.SendFailed as exc:
        exhausted = attempts >= max(int(row.maxAttempts or 1), 1)
        if exc.permanent or exhausted:
            await _finish(row.id, FAILED, error=f"SES refused it: {exc.code}")
            logger.warning(
                "mail: %s message %s for account %s failed (%s, attempt %s)",
                row.kind, row.id, row.recipientId or "-", exc.code, attempts,
            )
            return FAILED
        await _retry_later(row, attempts, f"SES did not take it yet: {exc.code}")
        logger.info(
            "mail: %s message %s will be retried (%s, attempt %s)", row.kind, row.id, exc.code, attempts
        )
        return QUEUED
    await _finish(row.id, SENT, message_id=message_id)
    logger.info("mail: sent %s message %s to account %s", row.kind, row.id, row.recipientId or "-")
    return SENT


async def process_next_email_jobs(
    limit: int | None = None,
    worker_id: str = "queue-service",
    settings: Settings | None = None,
    *,
    recover: bool = True,
) -> dict[str, int]:
    """Drain one batch of due messages. Does nothing at all when mail is not configured."""
    settings = settings or get_settings()
    tally = {"processed": 0, "sent": 0, "retrying": 0, "failed": 0}
    if not mailer.mail_configured(settings):
        return tally
    if recover:
        await recover_stale()
    rows = await db.emailmessage.find_many(
        where={"status": QUEUED, "runAfter": {"lte": datetime.now(UTC)}},
        order={"createdAt": "asc"},
        take=max(1, limit or settings.mail_batch_size),
    )
    for row in rows:
        if not await _claim(row, worker_id):
            continue
        tally["processed"] += 1
        try:
            outcome = await _send_one(row, settings)
        except Exception:  # one bad row must not stop the batch; it is retried
            logger.exception("mail: message %s could not be processed", row.id)
            await _retry_later(row, int(row.attempts or 0) + 1, "Unexpected error while sending.")
            outcome = QUEUED
        if outcome == SENT:
            tally["sent"] += 1
        elif outcome == FAILED:
            tally["failed"] += 1
        else:
            tally["retrying"] += 1
    return tally


# --------------------------------------------------------------------------------------
# Notifications
# --------------------------------------------------------------------------------------


async def workshop_designers(record: Any) -> list[Any]:
    """The accounts that work on a workshop: its designer-access (viewer) rows, plus its creator
    when the creator is a designer. An administrator who only staged it is not one of them."""
    workshop_id = str(_value(record, "id"))
    rows = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": workshop_id}, include={"user": True}
    )
    people: dict[str, Any] = {}
    for row in rows:
        if row.user is not None:
            people[row.user.id] = row.user
    creator_id = _value(record, "createdById")
    if creator_id and creator_id not in people:
        creator = await db.user.find_unique(where={"id": str(creator_id)})
        if creator is not None and str(getattr(creator.role, "value", creator.role)) == "DESIGNER":
            people[creator.id] = creator
    return list(people.values())


def _stage_title(stage_key: str | None) -> str | None:
    if not stage_key:
        return None
    try:
        from app.services.stage_schema import stages

        spec = next((s for s in stages() if s.key == stage_key), None)
    except Exception:  # noqa: BLE001 - a missing title only shortens the sentence
        return None
    return spec.title if spec is not None else None


async def notify_review_note(
    record: Any,
    *,
    actor: Any,
    note: str | None,
    stage_key: str | None,
    sent_back: bool,
    settings: Settings | None = None,
) -> int:
    """E-mail a workshop's designers that an inspector filed a suggestion or sent it back.

    NEVER RAISES. The suggestion or the send-back has already been written; failing to queue a
    notice about it must not turn the inspector's 201 into an error. Returns how many were queued.
    """
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    queued = 0
    try:
        kind = mailer.REVIEW_SENT_BACK if sent_back else mailer.REVIEW_SUGGESTION
        for person in await workshop_designers(record):
            if person.id == _value(actor, "id") or not person.email:
                continue
            if not await wants_review_notes(person.id):
                continue
            params = {
                "recipientName": person.name,
                "workshopId": str(_value(record, "id")),
                "workshopTitle": _value(record, "title"),
                "inspectorName": _value(actor, "name"),
                "note": (note or "").strip(),
                "stageTitle": _stage_title(stage_key),
            }
            if await enqueue(
                kind, to_address=person.email, recipient_id=person.id, params=params, settings=settings
            ):
                queued += 1
    except Exception:  # see the docstring: the decision stands either way
        logger.exception("mail: could not queue review notices for workshop %s", _value(record, "id"))
    return queued
