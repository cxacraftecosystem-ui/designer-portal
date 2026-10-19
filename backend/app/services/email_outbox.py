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

RESERVED ADDRESSES. A row addressed to a reserved domain (``example.com``, ``*.test`` …) is never
handed to SES: it ends SKIPPED with ``mailer.RESERVED_ADDRESS_REASON``.

IN-APP. Every notice goes through :func:`app.services.notifications.notify`, which writes the
person's in-app activity list as well as calling :func:`enqueue` — so a notice is never lost to mail
being off. New notices go through ``notify`` too; :func:`enqueue` alone is for a password link.

PREFERENCES. A notification e-mail (:data:`mailer.REVIEW_SUGGESTION`, :data:`mailer.REVIEW_SENT_BACK`)
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
#: Never handed to SES: the address is on a reserved domain that takes no mail
#: (``mailer.is_reserved_email``). Final, like FAILED, with ``mailer.RESERVED_ADDRESS_REASON``.
SKIPPED = "SKIPPED"
#: A hand-in notice for somebody who chose the daily digest: kept, never sent on its own, and folded
#: into one HAND_IN_DIGEST message by :func:`flush_digests` (which marks it DIGESTED).
HELD = "HELD"
DIGESTED = "DIGESTED"

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


#: Each preference column and the kinds it governs. Opt-out: a missing row or column reads as yes.
PREFERENCE_FOR_KIND: dict[str, str] = {
    **dict.fromkeys(mailer.WORKSHOP_UPDATES, "emailWorkshopUpdates"),
    **dict.fromkeys(mailer.ACCESS_NOTICES, "emailAccessNotices"),
    mailer.REVIEW_SUGGESTION: "emailReviewNotes",
    mailer.REVIEW_SENT_BACK: "emailReviewNotes",
}

#: How an official hears of a hand-in.
HAND_IN_EACH = "EACH"
HAND_IN_DAILY = "DAILY"
HAND_IN_OFF = "OFF"
HAND_IN_MODES = (HAND_IN_EACH, HAND_IN_DAILY, HAND_IN_OFF)


async def wants(user_id: str, kind: str) -> bool:
    """Whether this person's preferences let a notice of ``kind`` be queued for them."""
    column = PREFERENCE_FOR_KIND.get(kind)
    if column is None:
        return True
    row = await db.userpreference.find_unique(where={"userId": user_id})
    if row is None:
        return True
    return bool(getattr(row, column, True) is not False)


def hand_in_mode(row: Any) -> str:
    """EACH, DAILY or OFF off a preference row; anything unrecognised (or no row) is EACH."""
    value = str(getattr(row, "emailHandInNotices", "") or "").strip().upper() if row is not None else ""
    return value if value in HAND_IN_MODES else HAND_IN_EACH


async def enqueue(
    kind: str,
    *,
    to_address: str,
    recipient_id: str | None,
    params: dict[str, Any],
    secret: str | None = None,
    settings: Settings | None = None,
    topic: str | None = None,
    hold: bool = False,
) -> Any | None:
    """Queue one message. Returns the row, or ``None`` when mail is not configured.

    ``topic`` names what the message is about for the screens that show whether it went
    (``sanction:<id>``, ``remind:<id>``, ``delivery:<id>``). ``hold`` keeps it for the daily digest.
    """
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
            "status": HELD if hold else QUEUED,
            "maxAttempts": max(int(settings.mail_max_attempts or 1), 1),
            "topic": topic,
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
    domain = mailer.reserved_email_domain(row.toAddress)
    if domain is not None:
        # A seeded or test account: SES would bounce it, and bounces cost the sender reputation.
        await _finish(row.id, SKIPPED, error=mailer.RESERVED_ADDRESS_REASON)
        logger.info(
            "mail: skipped %s message %s for account %s (reserved domain %s)",
            row.kind, row.id, row.recipientId or "-", domain,
        )
        return SKIPPED
    secret: str | None = None
    if row.sealed:
        secret = managed_secrets.decrypt(row.sealed)
        if secret is None:
            await _finish(row.id, FAILED, error="The sealed link could not be opened (the key changed).")
            return FAILED
    if row.kind in mailer.EXPIRING_LINK_KINDS and _expired(params):
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
    tally = {"processed": 0, "sent": 0, "retrying": 0, "failed": 0, "skipped": 0}
    if not mailer.mail_configured(settings):
        return tally
    if recover:
        await recover_stale()
    try:
        await flush_digests(settings=settings)
    except Exception:  # a digest that cannot be gathered must not stop the drain
        logger.exception("mail: could not gather the daily digests")
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
        elif outcome == SKIPPED:
            tally["skipped"] += 1
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
    """Tell a workshop's designers that an inspector filed a suggestion or sent it back: in-app
    always, and by e-mail where mail is on and they have not opted out (``emailReviewNotes``).

    Goes through :func:`app.services.notifications.notify`, so the notice lands in each designer's
    activity list even when mail is off. NEVER RAISES. The suggestion or the send-back has already
    been written; failing to tell anybody must not turn the inspector's 201 into an error. Returns
    how many E-MAILS were queued (the in-app rows are not counted).
    """
    from app.services.notifications import notify

    settings = settings or get_settings()
    queued = 0
    try:
        kind = mailer.REVIEW_SENT_BACK if sent_back else mailer.REVIEW_SUGGESTION
        workshop_id = str(_value(record, "id"))
        for person in await workshop_designers(record):
            if person.id == _value(actor, "id"):
                continue
            params = {
                "recipientName": person.name,
                "workshopId": workshop_id,
                "workshopTitle": _value(record, "title"),
                "inspectorName": _value(actor, "name"),
                "note": (note or "").strip(),
                "stageTitle": _stage_title(stage_key),
            }
            delivery = await notify(
                person,
                kind,
                mailer.subject_for(kind, params),
                params["note"] or None,
                f"/design-workshops/{workshop_id}",
                email_kind=kind,
                email_params=params,
                preference="emailReviewNotes",
                settings=settings,
            )
            if delivery.emailed:
                queued += 1
    except Exception:  # see the docstring: the decision stands either way
        logger.exception("mail: could not queue review notices for workshop %s", _value(record, "id"))
    return queued

async def notify_password_event(
    user: Any, kind: str, *, by: str = "self", to_address: str | None = None
) -> bool:
    """Queue a PASSWORD_ADDED / PASSWORD_CHANGED security notice to the account's own address.

    Always sent (no opt-out): the account's owner is the one person who can tell a change they made
    from one they did not, and this is how they hear about the second. No password and no link ride
    in it. ``by`` is "self" or "administrator" (a temporary password set for them).

    ``to_address`` sends the same notice somewhere other than the address the account holds now:
    the address it held BEFORE an administrator changed it in the same request (security review N4),
    so the person who owned the account hears of it at the inbox they still read.

    Also written to the account's in-app list (:func:`app.services.notifications.notify`), mail or
    no mail. NEVER RAISES. The password has already been written; failing to queue a notice about
    it must not turn the change into an error. Returns whether an e-mail was queued.
    """
    if kind not in mailer.PASSWORD_NOTICES:
        raise ValueError(f"Not a password notice: {kind!r}")
    from app.services.notifications import notify

    params = {"recipientName": _value(user, "name"), "at": datetime.now(UTC).isoformat(), "by": by}
    # In-app too, once: the copy sent to a previous address (``to_address``) is the same event for
    # the same account, so it writes no second row.
    delivery = await notify(
        user,
        kind,
        mailer.subject_for(kind, params),
        None,
        "/settings",
        email_kind=kind,
        email_params=params,
        to_address=to_address,
        in_app=to_address is None,
    )
    return delivery.emailed


# --------------------------------------------------------------------------------------
# The notices of 2026-10-18: designers, access, officials, and the daily digest
#
# EVERY FUNCTION BELOW NEVER RAISES (except send_setup_reminders, whose caller answers with its
# tally) and is a no-op when mail is off. Each is called AFTER the act it reports has been written;
# failing to queue a notice must not turn that act into an error. Each respects the recipient's own
# preference (``wants`` / ``hand_in_mode``) and never e-mails the person who did the act.
# --------------------------------------------------------------------------------------

#: The most people one notice fans out to. A larger audience is a configuration problem, and the
#: queue must not be flooded by one click.
MAX_FANOUT = 25


async def _queue_for(
    people: list[Any],
    kind: str,
    params: dict[str, Any],
    *,
    actor_id: str | None,
    settings: Settings,
    topic: str | None = None,
) -> int:
    queued = 0
    seen: set[str] = set()
    for person in people[:MAX_FANOUT]:
        person_id = _value(person, "id")
        address = _value(person, "email")
        if not person_id or not address or person_id == actor_id or person_id in seen:
            continue
        seen.add(person_id)
        if not await wants(person_id, kind):
            continue
        if await enqueue(
            kind,
            to_address=address,
            recipient_id=person_id,
            params={"recipientName": _value(person, "name"), **params},
            settings=settings,
            topic=topic,
        ):
            queued += 1
    return queued


async def notify_workshop_designers(
    record: Any,
    kind: str,
    *,
    actor: Any,
    extra: dict[str, Any] | None = None,
    settings: Settings | None = None,
) -> int:
    """REPORT_APPROVED / REPORT_HANDED_ON to the workshop's designers. Returns how many were queued."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    try:
        params = {
            "workshopId": str(_value(record, "id")),
            "workshopTitle": _value(record, "title"),
            "actorName": _value(actor, "name"),
            **(extra or {}),
        }
        return await _queue_for(
            await workshop_designers(record), kind, params, actor_id=_value(actor, "id"), settings=settings
        )
    except Exception:
        logger.exception("mail: could not queue %s for workshop %s", kind, _value(record, "id"))
        return 0


async def notify_workshop_assigned(
    workshop: Any,
    user_ids: list[str],
    *,
    actor: Any,
    sanction_order_no: str | None = None,
    settings: Settings | None = None,
) -> int:
    """WORKSHOP_ASSIGNED to accounts newly given a workshop: an existing account named on a sanction
    order, or one added to its designer team."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings) or not user_ids:
        return 0
    try:
        people = await db.user.find_many(where={"id": {"in": sorted(set(user_ids))}})
        params = {
            "workshopId": str(_value(workshop, "id")),
            "workshopTitle": _value(workshop, "title"),
            "actorName": _value(actor, "name"),
            "sanctionOrderNo": sanction_order_no,
        }
        return await _queue_for(
            list(people), mailer.WORKSHOP_ASSIGNED, params, actor_id=_value(actor, "id"), settings=settings
        )
    except Exception:
        logger.exception("mail: could not queue assignment notices for %s", _value(workshop, "id"))
        return 0


#: How soon one designer may be reminded again about the same order.
REMINDER_COOLDOWN = timedelta(hours=1)


async def send_setup_reminders(
    order: Any,
    workshop: Any,
    designers: list[Any],
    missing: list[str],
    *,
    actor: Any,
    settings: Settings | None = None,
) -> dict[str, Any]:
    """"Remind designers": one SETUP_REMINDER to each designer the order names.

    Returns ``{queued, recent, optedOut, noAddress, lastRemindedAt}``. A designer reminded about this
    order within :data:`REMINDER_COOLDOWN` is skipped (``recent``), so a second press is harmless.
    """
    settings = settings or get_settings()
    topic = f"remind:{_value(order, 'id')}"
    tally: dict[str, Any] = {"queued": 0, "recent": 0, "optedOut": 0, "noAddress": 0}
    if not mailer.mail_configured(settings):
        tally["lastRemindedAt"] = None
        return tally
    since = datetime.now(UTC) - REMINDER_COOLDOWN
    recent_rows = await db.emailmessage.find_many(where={"topic": topic, "createdAt": {"gte": since}})
    recently = {row.recipientId for row in recent_rows}
    for person in designers[:MAX_FANOUT]:
        person_id = _value(person, "id")
        address = _value(person, "email")
        if not person_id or not address:
            tally["noAddress"] += 1
            continue
        if person_id in recently:
            tally["recent"] += 1
            continue
        if not await wants(person_id, mailer.SETUP_REMINDER):
            tally["optedOut"] += 1
            continue
        row = await enqueue(
            mailer.SETUP_REMINDER,
            to_address=address,
            recipient_id=person_id,
            params={
                "recipientName": _value(person, "name"),
                "workshopId": str(_value(workshop, "id")),
                "workshopTitle": _value(workshop, "title"),
                "actorName": _value(actor, "name"),
                "missing": list(missing),
                "stageKey": "WORKSHOP_SETUP",
            },
            settings=settings,
            topic=topic,
        )
        if row is not None:
            tally["queued"] += 1
    tally["lastRemindedAt"] = await last_topic_moment(topic)
    return tally


async def last_topic_moment(topic: str) -> str | None:
    """When the newest message about ``topic`` was queued, as ISO text, or ``None``."""
    row = await db.emailmessage.find_first(where={"topic": topic}, order={"createdAt": "desc"})
    moment = getattr(row, "createdAt", None) if row is not None else None
    return moment.isoformat() if moment else None


def delivery_status(row: Any) -> dict[str, Any] | None:
    """What a screen says about one outbox row: ``{id, status, at, error}``. ``status`` is QUEUED
    (still going out, retries included), SENT or FAILED — a SKIPPED row (a reserved address) reads
    as FAILED, with its reason. ``None`` for no row."""
    if row is None:
        return None
    state = str(_value(row, "status") or "")
    if state in (SENDING, HELD, DIGESTED):
        state = QUEUED if state != DIGESTED else SENT
    elif state == SKIPPED:
        state = FAILED
    moment = _value(row, "sentAt") if state == SENT else _value(row, "createdAt")
    return {
        "id": _value(row, "id"),
        "status": state,
        "at": moment.isoformat() if hasattr(moment, "isoformat") else None,
        "error": _value(row, "error") if state == FAILED else None,
    }


async def latest_by_recipient(topic: str, recipient_ids: list[str]) -> dict[str, dict[str, Any]]:
    """``{recipient id: delivery_status}`` for the newest message about ``topic`` to each person."""
    if not recipient_ids:
        return {}
    rows = await db.emailmessage.find_many(
        where={"topic": topic, "recipientId": {"in": sorted(set(recipient_ids))}},
        order={"createdAt": "desc"},
        take=200,
    )
    found: dict[str, dict[str, Any]] = {}
    for row in rows:
        if row.recipientId and row.recipientId not in found:
            answer = delivery_status(row)
            if answer is not None:
                found[row.recipientId] = answer
    return found


async def latest_by_topics(topics: list[str]) -> dict[str, dict[str, dict[str, Any]]]:
    """``{topic: {recipient id: delivery_status}}`` for a page of topics, in one query."""
    if not topics:
        return {}
    rows = await db.emailmessage.find_many(
        where={"topic": {"in": sorted(set(topics))}}, order={"createdAt": "desc"}, take=1000
    )
    found: dict[str, dict[str, dict[str, Any]]] = {}
    for row in rows:
        per_topic = found.setdefault(str(row.topic), {})
        if row.recipientId and row.recipientId not in per_topic:
            answer = delivery_status(row)
            if answer is not None:
                per_topic[row.recipientId] = answer
    return found


async def _admin_accounts() -> list[Any]:
    return list(
        await db.user.find_many(
            where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}}, order={"createdAt": "desc"}, take=MAX_FANOUT
        )
    )


async def notify_workshop_access_requested(
    workshop: Any, requester: Any, note: str | None, *, settings: Settings | None = None
) -> int:
    """ACCESS_REQUESTED to the administrators who decide workshop access requests."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    try:
        params = {
            "scope": "workshop",
            "workshopId": str(_value(workshop, "id")),
            "workshopTitle": _value(workshop, "title"),
            "requesterName": _value(requester, "name") or _value(requester, "email"),
            "note": (note or "").strip(),
        }
        return await _queue_for(
            await _admin_accounts(),
            mailer.ACCESS_REQUESTED,
            params,
            actor_id=_value(requester, "id"),
            settings=settings,
        )
    except Exception:
        logger.exception("mail: could not queue access-request notices for %s", _value(workshop, "id"))
        return 0


async def notify_access_decided(
    requester_id: str,
    *,
    scope: str,
    decision: str,
    actor: Any,
    workshop: Any = None,
    tier: str | None = None,
    note: str | None = None,
    settings: Settings | None = None,
) -> int:
    """ACCESS_DECIDED to the person who asked. ``scope`` is ``workshop`` or ``data``."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings) or decision not in ("GRANTED", "DENIED"):
        return 0
    try:
        person = await db.user.find_unique(where={"id": requester_id})
        if person is None:
            return 0
        params = {
            "scope": scope,
            "decision": decision,
            "actorName": _value(actor, "name"),
            "workshopId": str(_value(workshop, "id")) if workshop is not None else None,
            "workshopTitle": _value(workshop, "title") if workshop is not None else None,
            "tier": tier,
            "note": (note or "").strip(),
        }
        return await _queue_for(
            [person], mailer.ACCESS_DECIDED, params, actor_id=_value(actor, "id"), settings=settings
        )
    except Exception:
        logger.exception("mail: could not queue an access decision for %s", requester_id)
        return 0


async def notify_data_access_requested(
    owner_id: str, requester: Any, *, tier: str | None, note: str | None, settings: Settings | None = None
) -> int:
    """ACCESS_REQUESTED to the owner of the data a researcher asked to see."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    try:
        owner = await db.user.find_unique(where={"id": owner_id})
        if owner is None:
            return 0
        params = {
            "scope": "data",
            "requesterName": _value(requester, "name") or _value(requester, "email"),
            "tier": tier,
            "note": (note or "").strip(),
        }
        return await _queue_for(
            [owner], mailer.ACCESS_REQUESTED, params, actor_id=_value(requester, "id"), settings=settings
        )
    except Exception:
        logger.exception("mail: could not queue a data-access request notice for %s", owner_id)
        return 0


async def notify_record_comment(
    owner_id: str | None,
    author: Any,
    *,
    record_type: str,
    record_id: str,
    record_label: str | None,
    body: str,
    settings: Settings | None = None,
) -> int:
    """RECORD_COMMENT to a record's creator when somebody else comments on it."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings) or not owner_id:
        return 0
    try:
        owner = await db.user.find_unique(where={"id": owner_id})
        if owner is None:
            return 0
        params = {
            "authorName": _value(author, "name"),
            "recordType": record_type,
            "recordId": record_id,
            "recordLabel": record_label,
            "body": (body or "").strip()[:1000],
        }
        return await _queue_for(
            [owner], mailer.RECORD_COMMENT, params, actor_id=_value(author, "id"), settings=settings
        )
    except Exception:
        logger.exception("mail: could not queue a comment notice for %s %s", record_type, record_id)
        return 0


async def hand_in_audience(workshop_id: str) -> list[tuple[Any, bool]]:
    """``[(account, is_approver)]``: the workshop's assigned inspectors, then the approving authority
    (every Ministry Admin and master admin whom rule 7 does not bar from deciding THIS workshop).
    An account in both is listed once, as an inspector."""
    from app.services import design_workshop_posts as posts
    from app.services.concurrency import gather_reads
    from app.services.design_workshop_inspectors import inspector_accounts

    inspectors = await inspector_accounts(workshop_id)
    approvers = await db.user.find_many(
        where={"role": {"in": ["MINISTRY_ADMIN", "MASTER_ADMIN"]}}, order={"createdAt": "desc"}, take=MAX_FANOUT
    )
    people: dict[str, tuple[Any, bool]] = {}
    for person in inspectors:
        people.setdefault(person.id, (person, False))
    candidates = [person for person in approvers if person.id not in people]
    if candidates:
        # Rule 7, batched: the same predicate as ``posts.decision_refusal``, two reads for everyone.
        ids = [person.id for person in candidates]
        held, authored = await gather_reads(
            posts.supervisory_posts_among(workshop_id, ids),
            posts.authorship_among(workshop_id, ids),
        )
        for person in candidates:
            if posts.approval_refusals(
                posts=held.get(person.id, frozenset()), authored=authored.get(person.id, frozenset())
            ):
                continue
            people.setdefault(person.id, (person, True))
    return list(people.values())


async def notify_handed_in(
    record: Any, *, actor: Any, resubmission: bool, settings: Settings | None = None
) -> int:
    """REPORT_HANDED_IN to the assigned inspectors and the approving authority, each by their own
    choice: one e-mail now (EACH), held for the daily digest (DAILY), or nothing (OFF)."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    queued = 0
    try:
        workshop_id = str(_value(record, "id"))
        actor_id = _value(actor, "id")
        for person, approver in (await hand_in_audience(workshop_id))[: MAX_FANOUT * 2]:
            if not person.email or person.id == actor_id:
                continue
            preference = await db.userpreference.find_unique(where={"userId": person.id})
            mode = hand_in_mode(preference)
            if mode == HAND_IN_OFF:
                continue
            params = {
                "recipientName": person.name,
                "workshopId": workshop_id,
                "workshopTitle": _value(record, "title"),
                "designerName": _value(record, "designerName") or _value(actor, "name"),
                "round": int(_value(record, "submissionRound") or 0),
                "resubmission": resubmission,
                "approver": approver,
            }
            if await enqueue(
                mailer.REPORT_HANDED_IN,
                to_address=person.email,
                recipient_id=person.id,
                params=params,
                settings=settings,
                topic=f"handin:{workshop_id}",
                hold=mode == HAND_IN_DAILY,
            ):
                queued += 1
    except Exception:
        logger.exception("mail: could not queue hand-in notices for workshop %s", _value(record, "id"))
    return queued


#: The daily digest goes out at this India-time hour, gathering what was held before it.
DIGEST_HOUR_IST = 9
#: The drain asks at most this often whether a digest is due.
DIGEST_CHECK_EVERY = timedelta(minutes=10)
_last_digest_check: datetime | None = None


def digest_cutoff(now: datetime) -> datetime:
    """The most recent DIGEST_HOUR_IST boundary at or before ``now``, in UTC."""
    local = now.astimezone(mailer.IST)
    boundary = local.replace(hour=DIGEST_HOUR_IST, minute=0, second=0, microsecond=0)
    if boundary > local:
        boundary -= timedelta(days=1)
    return boundary.astimezone(UTC)


async def flush_digests(
    *, now: datetime | None = None, settings: Settings | None = None, force: bool = False
) -> int:
    """Fold each person's HELD hand-in notices from before the last 09:00 IST into one digest.

    Idempotent: a held row is claimed (HELD -> DIGESTED) with a compare-and-set before its digest is
    queued, so two workers cannot send one digest twice. Returns how many digests were queued.
    """
    global _last_digest_check
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    moment = now or datetime.now(UTC)
    if not force and _last_digest_check is not None and moment - _last_digest_check < DIGEST_CHECK_EVERY:
        return 0
    _last_digest_check = moment
    cutoff = digest_cutoff(moment)
    held = await db.emailmessage.find_many(where={"status": HELD}, take=1000)
    due: dict[str, list[Any]] = {}
    for row in held:
        created = getattr(row, "createdAt", None)
        if created is not None and created.tzinfo is None:
            created = created.replace(tzinfo=UTC)
        if created is not None and created >= cutoff:
            continue
        due.setdefault(str(row.toAddress), []).append(row)
    # A person who switched to OFF since a row was held is not sent it: the row is set aside
    # (DIGESTED) with no digest. One read for every recipient's current choice.
    recipient_ids = {str(row.recipientId) for rows in due.values() for row in rows if row.recipientId}
    modes: dict[str, str] = {}
    if recipient_ids:
        preferences = await db.userpreference.find_many(where={"userId": {"in": list(recipient_ids)}})
        modes = {str(pref.userId): hand_in_mode(pref) for pref in preferences}
    queued = 0
    for address, rows in due.items():
        silenced = [row for row in rows if row.recipientId and modes.get(str(row.recipientId)) == HAND_IN_OFF]
        for row in silenced:
            await db.emailmessage.update_many(where={"id": row.id, "status": HELD}, data={"status": DIGESTED})
        silenced_ids = {row.id for row in silenced}
        claimed = []
        for row in (row for row in rows if row.id not in silenced_ids):
            if await db.emailmessage.update_many(
                where={"id": row.id, "status": HELD}, data={"status": DIGESTED}
            ):
                claimed.append(row)
        if not claimed:
            continue
        items = []
        approver = False
        for row in claimed:
            params = dict(row.params or {}) if isinstance(row.params, dict) else {}
            approver = approver or bool(params.get("approver"))
            items.append(
                {
                    "workshopId": params.get("workshopId"),
                    "workshopTitle": params.get("workshopTitle"),
                    "round": params.get("round"),
                    "resubmission": bool(params.get("resubmission")),
                }
            )
        first = claimed[0]
        first_params = dict(first.params or {}) if isinstance(first.params, dict) else {}
        if await enqueue(
            mailer.HAND_IN_DIGEST,
            to_address=address,
            recipient_id=first.recipientId,
            params={"recipientName": first_params.get("recipientName"), "items": items, "approver": approver},
            settings=settings,
        ):
            queued += 1
    return queued
