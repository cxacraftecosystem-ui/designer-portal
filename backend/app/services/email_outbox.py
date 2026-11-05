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
SKIPPED = "SKIPPED"
HELD = "HELD"
DIGESTED = "DIGESTED"

STALE_SENDING_AFTER = timedelta(minutes=15)
MAX_BACKOFF_MINUTES = 60
MAX_ERROR_LENGTH = 300


def _value(record: Any, key: str) -> Any:
    if isinstance(record, dict):
        return record.get(key)
    return getattr(record, key, None)


async def wants_review_notes(user_id: str) -> bool:
    row = await db.userpreference.find_unique(where={"userId": user_id})
    if row is None:
        return True
    return bool(getattr(row, "emailReviewNotes", True))


PREFERENCE_FOR_KIND: dict[str, str] = {
    **dict.fromkeys(mailer.WORKSHOP_UPDATES, "emailWorkshopUpdates"),
    **dict.fromkeys(mailer.ACCESS_NOTICES, "emailAccessNotices"),
    mailer.REVIEW_SUGGESTION: "emailReviewNotes",
    mailer.REVIEW_SENT_BACK: "emailReviewNotes",
}

HAND_IN_EACH = "EACH"
HAND_IN_DAILY = "DAILY"
HAND_IN_OFF = "OFF"
HAND_IN_MODES = (HAND_IN_EACH, HAND_IN_DAILY, HAND_IN_OFF)


async def wants(user_id: str, kind: str) -> bool:
    column = PREFERENCE_FOR_KIND.get(kind)
    if column is None:
        return True
    row = await db.userpreference.find_unique(where={"userId": user_id})
    return wants_by(row, kind)


def wants_by(row: Any | None, kind: str) -> bool:
    column = PREFERENCE_FOR_KIND.get(kind)
    if column is None or row is None:
        return True
    return bool(getattr(row, column, True) is not False)


async def preferences_of(user_ids: list[str]) -> dict[str, Any]:
    ids = sorted({str(uid) for uid in user_ids if uid})
    if not ids:
        return {}
    rows = await db.userpreference.find_many(where={"userId": {"in": ids}})
    return {str(row.userId): row for row in rows}


def hand_in_mode(row: Any) -> str:
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
    attempts = int(row.attempts or 0) + 1
    params = dict(row.params or {}) if isinstance(row.params, dict) else {}
    domain = mailer.reserved_email_domain(row.toAddress)
    if domain is not None:
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
    extra: dict[str, Any] = {}
    if rendered.headers:
        extra["headers"] = rendered.headers
    attachment_key = params.get("attachmentKey")
    if isinstance(attachment_key, str) and attachment_key:
        from app.services import s3

        try:
            blob = await asyncio.to_thread(s3.get_object_bytes, attachment_key)
        except Exception as exc:  # noqa: BLE001 - any storage fault retries the message
            error = f"The attachment could not be read: {type(exc).__name__}"
            if attempts >= max(int(row.maxAttempts or 1), 1):
                await _finish(row.id, FAILED, error=error)
                return FAILED
            await _retry_later(row, attempts, error)
            return QUEUED
        extra["attachments"] = (
            mailer.Attachment(
                file_name=str(params.get("fileName") or "Report.xlsx"),
                mime_type=str(params.get("attachmentMime") or "application/octet-stream"),
                data=blob,
            ),
        )
    try:
        message_id = await asyncio.to_thread(
            mailer.send,
            to=row.toAddress,
            subject=rendered.subject,
            text=rendered.text,
            html_body=rendered.html,
            settings=settings,
            **extra,
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
    settings = settings or get_settings()
    tally = {"processed": 0, "sent": 0, "retrying": 0, "failed": 0, "skipped": 0}
    if not mailer.mail_configured(settings):
        return tally
    if recover:
        await recover_stale()
    try:
        await flush_digests(settings=settings)
    except Exception:
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
        except Exception:
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



async def workshop_designers(record: Any) -> list[Any]:
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
    except Exception:
        logger.exception("mail: could not queue review notices for workshop %s", _value(record, "id"))
    return queued

async def notify_password_event(
    user: Any, kind: str, *, by: str = "self", to_address: str | None = None
) -> bool:
    if kind not in mailer.PASSWORD_NOTICES:
        raise ValueError(f"Not a password notice: {kind!r}")
    from app.services.notifications import notify

    params = {"recipientName": _value(user, "name"), "at": datetime.now(UTC).isoformat(), "by": by}
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
    audience: list[Any] = []
    for person in people[:MAX_FANOUT]:
        person_id = _value(person, "id")
        address = _value(person, "email")
        if not person_id or not address or person_id == actor_id or person_id in seen:
            continue
        seen.add(person_id)
        audience.append(person)
    preferences = await preferences_of(list(seen)) if PREFERENCE_FOR_KIND.get(kind) else {}
    for person in audience:
        person_id = _value(person, "id")
        address = _value(person, "email")
        if not wants_by(preferences.get(str(person_id)), kind):
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
    settings = settings or get_settings()
    topic = f"remind:{_value(order, 'id')}"
    tally: dict[str, Any] = {"queued": 0, "recent": 0, "optedOut": 0, "noAddress": 0}
    if not mailer.mail_configured(settings):
        tally["lastRemindedAt"] = None
        return tally
    since = datetime.now(UTC) - REMINDER_COOLDOWN
    recent_rows = await db.emailmessage.find_many(where={"topic": topic, "createdAt": {"gte": since}})
    recently = {row.recipientId for row in recent_rows}
    preferences = (
        await preferences_of([_value(person, "id") for person in designers[:MAX_FANOUT]])
        if PREFERENCE_FOR_KIND.get(mailer.SETUP_REMINDER)
        else {}
    )
    for person in designers[:MAX_FANOUT]:
        person_id = _value(person, "id")
        address = _value(person, "email")
        if not person_id or not address:
            tally["noAddress"] += 1
            continue
        if person_id in recently:
            tally["recent"] += 1
            continue
        if not wants_by(preferences.get(str(person_id)), mailer.SETUP_REMINDER):
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
    row = await db.emailmessage.find_first(where={"topic": topic}, order={"createdAt": "desc"})
    moment = getattr(row, "createdAt", None) if row is not None else None
    return moment.isoformat() if moment else None


def delivery_status(row: Any) -> dict[str, Any] | None:
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
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    queued = 0
    try:
        workshop_id = str(_value(record, "id"))
        actor_id = _value(actor, "id")
        audience = [
            (person, approver)
            for person, approver in (await hand_in_audience(workshop_id))[: MAX_FANOUT * 2]
            if person.email and person.id != actor_id
        ]
        preferences = await preferences_of([person.id for person, _ in audience])
        for person, approver in audience:
            mode = hand_in_mode(preferences.get(str(person.id)))
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


DIGEST_HOUR_IST = 9
DIGEST_CHECK_EVERY = timedelta(minutes=10)
_last_digest_check: datetime | None = None


def digest_cutoff(now: datetime) -> datetime:
    local = now.astimezone(mailer.IST)
    boundary = local.replace(hour=DIGEST_HOUR_IST, minute=0, second=0, microsecond=0)
    if boundary > local:
        boundary -= timedelta(days=1)
    return boundary.astimezone(UTC)


async def flush_digests(
    *, now: datetime | None = None, settings: Settings | None = None, force: bool = False
) -> int:
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
