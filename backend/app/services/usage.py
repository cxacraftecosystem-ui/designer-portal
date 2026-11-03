from __future__ import annotations

import asyncio
import logging
import re
from collections import deque
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
from typing import Any

from app.core.db import db
from app.services.concurrency import gather_reads

logger = logging.getLogger(__name__)


USAGE_USER_ID_KEY = "usage_user_id"

USAGE_CONSENT_KEY = "usage_consent_state"

CLIENT_APP_HEADER = "x-client-app"

CLIENT_APPS: frozenset[str] = frozenset({"web", "android", "api"})

DEFAULT_CLIENT_APP = "api"

UNMATCHED_ROUTE = "<unmatched>"

UNSAFE_ROUTE = "<unsafe>"


FLUSH_ROWS = 200

FLUSH_INTERVAL_SECONDS = 5.0

FLUSH_POLL_SECONDS = 0.25

BUFFER_CEILING = 5_000

FLUSH_MAX_ATTEMPTS = 2

MAX_TEMPLATE_LENGTH = 255

MAX_DURATION_MS = 2_147_483_647


MIN_IDENTIFIED_USERS_FOR_ROUTE = 5

MAX_RANGE_DAYS = 366

MAX_TEMPLATES_PER_QUERY = 50



class UsageRuleViolation(ValueError):
    pass



class UsageConsent(str, Enum):
    NOT_RECORDED = "NOT_RECORDED"
    GRANTED = "GRANTED"
    REFUSED = "REFUSED"


class UnaskedCollection(str, Enum):
    NOTHING = "NOTHING"

    ANONYMOUS = "ANONYMOUS"

    ATTRIBUTED = "ATTRIBUTED"


DEFAULT_UNASKED_COLLECTION = UnaskedCollection.ANONYMOUS

CONSENT_ATTRIBUTE = "usageConsent"

CONSENT_AT_ATTRIBUTE = "usageConsentAt"
CONSENT_BASIS_ATTRIBUTE = "usageConsentBasis"
CONSENT_VERSION_ATTRIBUTE = "usageConsentVersion"


class UsageConsentBasis(str, Enum):
    REQUIRED_AT_SIGN_IN = "REQUIRED_AT_SIGN_IN"

    OFFERED_IN_SETTINGS = "OFFERED_IN_SETTINGS"


NOTICE_VERSION = "2026-08-30.1"

MAX_DEVICE_CLOCK_SKEW = timedelta(minutes=15)


@dataclass(frozen=True, slots=True)
class CollectionPlan:
    record: bool
    attribute: bool
    consent_state: str | None
    reason: str


def resolve_consent(user: Any) -> UsageConsent:
    raw = getattr(user, CONSENT_ATTRIBUTE, None)
    token = str(getattr(raw, "value", raw) or "")
    if not token:
        return UsageConsent.NOT_RECORDED
    try:
        return UsageConsent(token)
    except ValueError:
        logger.warning(
            "usage: account %s carries the unreadable consent %r; treating it as NOT_RECORDED",
            getattr(user, "id", "?"),
            token,
        )
        return UsageConsent.NOT_RECORDED


def collection_plan(
    consent: UsageConsent | None,
    *,
    unasked: UnaskedCollection | None = None,
) -> CollectionPlan:
    policy = unasked if unasked is not None else DEFAULT_UNASKED_COLLECTION
    state = consent if consent is not None else UsageConsent.NOT_RECORDED

    if state is UsageConsent.GRANTED:
        return CollectionPlan(
            record=True,
            attribute=True,
            consent_state=UsageConsent.GRANTED.value,
            reason="This account agreed to have its use of the platform recorded.",
        )
    if state is UsageConsent.REFUSED:
        return CollectionPlan(
            record=False,
            attribute=False,
            consent_state=None,
            reason=(
                "This account declined to have its use of the platform recorded, so nothing about "
                "this request is kept, not even without a name."
            ),
        )
    if policy is UnaskedCollection.NOTHING:
        return CollectionPlan(
            record=False,
            attribute=False,
            consent_state=None,
            reason=(
                "Nothing is recorded from accounts that haven't answered yet."
            ),
        )
    if policy is UnaskedCollection.ATTRIBUTED:
        return CollectionPlan(
            record=True,
            attribute=True,
            consent_state=None,
            reason=(
                "Requests from accounts that haven't answered yet are recorded with their name, "
                "and marked as unanswered."
            ),
        )
    return CollectionPlan(
        record=True,
        attribute=False,
        consent_state=None,
        reason=(
            "Requests from accounts that haven't answered yet are recorded without a name: which "
            "screen, whether it worked and how long it took."
        ),
    )


@dataclass(frozen=True, slots=True)
class Withdrawal:
    buffered_dropped: int
    stored_deleted: int
    stored_delete_ran: bool


async def withdraw(user_id: str) -> Withdrawal:
    _WITHDRAWN.add(user_id)

    before = len(_BUFFER)
    kept = [row for row in _BUFFER if row.get("userId") != user_id]
    _BUFFER.clear()
    _BUFFER.extend(kept)
    dropped = before - len(kept)

    global _RETRY_BATCH
    if _RETRY_BATCH:
        held = len(_RETRY_BATCH)
        _RETRY_BATCH = [row for row in _RETRY_BATCH if row.get("userId") != user_id]
        dropped += held - len(_RETRY_BATCH)

    deleted = 0
    ran = False
    try:
        deleted = await db.usageevent.delete_many(where={"userId": user_id})
        ran = True
    except Exception as exc:  # noqa: BLE001 - see the docstring: the answer outranks the cleanup
        logger.warning(
            "usage: withdrew %s from recording and dropped %s buffered row(s), but could not "
            "delete the rows already stored (%s); collection has stopped, the deletion has not "
            "happened and re-running withdraw() is safe",
            user_id,
            dropped,
            exc,
        )

    if ran:
        logger.info(
            "usage: withdrew %s from recording; dropped %s buffered row(s) and deleted %s stored "
            "row(s)",
            user_id,
            dropped,
            deleted,
        )
    return Withdrawal(buffered_dropped=dropped, stored_deleted=deleted, stored_delete_ran=ran)


def is_withdrawn(user_id: str | None) -> bool:
    return bool(user_id) and user_id in _WITHDRAWN


def resume(user_id: str) -> bool:
    was_withdrawn = user_id in _WITHDRAWN
    _WITHDRAWN.discard(user_id)
    if was_withdrawn:
        logger.info(
            "usage: %s has agreed again; this process will record its requests from now on. The "
            "rows deleted by the earlier withdrawal are gone and are not restored",
            user_id,
        )
    return was_withdrawn



def collects() -> list[str]:
    plan = collection_plan(UsageConsent.NOT_RECORDED)
    if not plan.record:
        return [
            "Nothing until you agree. Once you agree, we record which screen you opened, when, "
            "whether it worked and how long it took, with your name attached.",
        ]

    if plan.attribute:
        account = "Your name, on everything recorded — including before you have answered."
    else:
        account = "Your name, only if you agree."

    return [
        "Which screen you opened, and when.",
        "Whether it worked, and how long it took.",
        account,
    ]


def does_not_collect() -> list[str]:
    return [
        "What you type, search or upload.",
        "Which record you opened — only the screen it was on.",
        "Your IP address, or details of your browser or phone.",
    ]


def readable_by() -> dict[str, str]:
    return {
        READER_YOU: "can see your own record, in Settings.",
        READER_ADMINS: "see totals only, never anyone's name.",
        READER_MASTER_ADMIN: "can see one person's history, and only if that person agreed.",
    }


READER_YOU = "You"
READER_ADMINS = "Admins"
READER_MASTER_ADMIN = "The master admin"

READ_ROUTE_READERS: dict[str, str] = {
    "/usage/me": READER_YOU,
    "/usage/me/trail": READER_YOU,
    "/usage/routes": READER_ADMINS,
    "/usage/timeline": READER_ADMINS,
    "/usage/latency": READER_ADMINS,
    "/usage/clients": READER_ADMINS,
    "/usage/screens": READER_ADMINS,
    "/usage/collection": READER_ADMINS,
    "/usage/accounts/{user_id}/trail": READER_MASTER_ADMIN,
}


def retention_note() -> str:
    return "Kept until you withdraw or your account is deleted."


def consent_notice() -> dict[str, Any]:
    return {
        "version": NOTICE_VERSION,
        "title": "Recording how you use this platform",
        "required": True,
        "requiredSentence": (
            "You need to agree to this to sign in. Your answer is saved as one given at sign-in."
        ),
        "collects": collects(),
        "doesNotCollect": does_not_collect(),
        "durationCaveat": (
            "It is not how long you waited: it is measured from when your request reaches us until "
            "we answer, and leaves out your connection and your device."
        ),
        "readableBy": readable_by(),
        "withdrawal": {
            "where": "Withdraw any time in Settings.",
            "costsNothing": "Withdrawing does not sign you out or stop you using anything.",
            "does": [
                "Recording stops straight away.",
                "Everything already recorded about you is deleted.",
            ],
            "doesNot": [
                "Your earlier answers stay listed in Settings, with their dates.",
            ],
        },
        "retention": retention_note(),
        "document": "",
    }



def consent_record(user: Any) -> dict[str, Any]:
    at = getattr(user, CONSENT_AT_ATTRIBUTE, None)
    basis = getattr(user, CONSENT_BASIS_ATTRIBUTE, None)
    version = getattr(user, CONSENT_VERSION_ATTRIBUTE, None)
    return {
        "state": resolve_consent(user).value,
        "at": at.isoformat() if isinstance(at, datetime) else None,
        "basis": str(getattr(basis, "value", basis)) if basis else None,
        "version": str(version) if version else None,
    }


def consent_gate(user: Any) -> dict[str, Any]:
    state = resolve_consent(user)
    stored = consent_record(user)
    agreed_version = stored["version"]

    if state is UsageConsent.GRANTED and agreed_version == NOTICE_VERSION:
        required = False
        reason = "You have agreed to the current recording notice."
    elif state is UsageConsent.GRANTED:
        required = True
        reason = (
            "You agreed to an earlier version of the recording notice"
            + (f" ({agreed_version})" if agreed_version else "")
            + ". It has changed, so please read it and answer again. Until you do, your earlier "
            "answer still applies."
        )
    elif state is UsageConsent.REFUSED:
        required = False
        reason = (
            "You have declined, so nothing about how you use the platform is recorded. Everything "
            "else works as normal, and you can change your answer in Settings at any time."
        )
    else:
        required = True
        reason = (
            "You haven't answered yet. Agreeing is required to use the platform. Until you answer, "
            "nothing recorded carries your name."
        )

    return {
        "state": state.value,
        "required": required,
        "reason": reason,
        "noticeVersion": NOTICE_VERSION,
        "agreedVersion": agreed_version,
        "agreedAt": stored["at"],
        "basis": stored["basis"],
        "answerAt": "POST /api/usage/consent",
        "noticeAt": "GET /api/usage/consent/notice",
    }


CONSENT_WRITABLE_TABLES: frozenset[str] = frozenset({"User", "UsageConsentDecision"})

OBSERVATION_TABLE = "UsageEvent"


class ConsentOperation(str, Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"


@dataclass(frozen=True, slots=True)
class ConsentWritePlan:
    table: str
    operation: ConsentOperation
    data: Mapping[str, Any]
    where: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        if self.table not in CONSENT_WRITABLE_TABLES:
            raise UsageRuleViolation(
                f"A usage consent may not be written into {self.table}. It is four columns on the "
                f"account and a row in its decision log, and nowhere else — writing it onto "
                f"{OBSERVATION_TABLE} would make the rows collected BEFORE the answer claim it, "
                f"which is the one distinction consentState exists for. Write it to one of "
                f"{', '.join(sorted(CONSENT_WRITABLE_TABLES))}."
            )
        if self.operation is ConsentOperation.UPDATE and not self.where:
            raise UsageRuleViolation(
                "An update must name the single row it changes. Pass where={'id': user_id}."
            )
        if self.operation is ConsentOperation.CREATE and self.where:
            raise UsageRuleViolation("A create names no existing row. Drop the where clause.")


@dataclass(frozen=True, slots=True)
class ConsentDecisionPlans:
    account: ConsentWritePlan
    decision: ConsentWritePlan

    def __iter__(self):
        yield self.account
        yield self.decision


def consent_decision_plans(
    *,
    user_id: str,
    decision: UsageConsent,
    basis: UsageConsentBasis,
    notice_version: str,
    at: datetime,
    recorded_at: datetime | None = None,
    note: str | None = None,
) -> ConsentDecisionPlans:
    account = str(user_id or "").strip()
    if not account:
        raise UsageRuleViolation(
            "Sign in to give your own answer. Nobody can answer for someone else."
        )
    if decision is UsageConsent.NOT_RECORDED:
        raise UsageRuleViolation(
            "NOT_RECORDED is what an account says before anybody has asked, not an answer somebody "
            "can record. Send GRANTED to agree, or REFUSED to decline — REFUSED is also how an "
            "agreement is withdrawn, and it deletes what has already been collected."
        )
    version = str(notice_version or "").strip()[:64]
    if not version:
        raise UsageRuleViolation(
            "A consent must say which version of the notice was on screen. Send the 'version' the "
            "notice endpoint returned — a record that cannot say what was agreed to is worse than "
            "none, because it invites the one claim it cannot support."
        )

    at = _as_utc_moment(at)
    recorded_at = _as_utc_moment(recorded_at)
    if recorded_at is not None and at is not None and recorded_at > at + MAX_DEVICE_CLOCK_SKEW:
        raise UsageRuleViolation(
            f"Your answer couldn't be saved because your device's clock is set in the future "
            f"({recorded_at.isoformat()}). Set the correct date and time on your device, then try "
            f"again."
        )

    answered_at = recorded_at or at
    return ConsentDecisionPlans(
        account=ConsentWritePlan(
            table="User",
            operation=ConsentOperation.UPDATE,
            where={"id": account},
            data={
                CONSENT_ATTRIBUTE: decision.value,
                CONSENT_AT_ATTRIBUTE: answered_at,
                CONSENT_BASIS_ATTRIBUTE: basis.value,
                CONSENT_VERSION_ATTRIBUTE: version,
            },
        ),
        decision=ConsentWritePlan(
            table="UsageConsentDecision",
            operation=ConsentOperation.CREATE,
            data={
                "userId": account,
                "decision": decision.value,
                "basis": basis.value,
                "noticeVersion": version,
                "note": (note or "").strip()[:500] or None,
                "recordedAt": recorded_at,
            },
        ),
    )


def _as_utc_moment(moment: datetime | None) -> datetime | None:
    if moment is None or moment.tzinfo is not None:
        return moment
    return moment.replace(tzinfo=UTC)


def _consent_model(table: str) -> Any:
    if table not in CONSENT_WRITABLE_TABLES:
        raise UsageRuleViolation(
            f"There is no usage-consent writer for {table}. A consent is four columns on the "
            f"account and a row in its decision log: {', '.join(sorted(CONSENT_WRITABLE_TABLES))}."
        )
    return {"User": db.user, "UsageConsentDecision": db.usageconsentdecision}[table]


async def apply_consent_plan(plan: ConsentWritePlan) -> Any:
    model = _consent_model(plan.table)
    if plan.operation is ConsentOperation.CREATE:
        return await model.create(data=dict(plan.data))
    return await model.update(where=dict(plan.where or {}), data=dict(plan.data))


@dataclass(frozen=True, slots=True)
class ConsentOutcome:
    account: Any
    decision: UsageConsent
    withdrawal: Withdrawal | None


async def record_consent(
    *,
    user_id: str,
    decision: UsageConsent,
    basis: UsageConsentBasis,
    notice_version: str,
    at: datetime | None = None,
    recorded_at: datetime | None = None,
    note: str | None = None,
) -> ConsentOutcome:
    plans = consent_decision_plans(
        user_id=user_id,
        decision=decision,
        basis=basis,
        notice_version=notice_version,
        at=at if at is not None else datetime.now(UTC),
        recorded_at=recorded_at,
        note=note,
    )
    account = await apply_consent_plan(plans.account)
    await apply_consent_plan(plans.decision)

    withdrawal: Withdrawal | None = None
    if decision is UsageConsent.REFUSED:
        withdrawal = await withdraw(user_id)
    else:
        resume(user_id)
    return ConsentOutcome(account=account, decision=decision, withdrawal=withdrawal)


async def consent_history(user_id: str, *, limit: int = 50) -> list[Any]:
    if not user_id:
        return []
    return await db.usageconsentdecision.find_many(
        where={"userId": user_id},
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=max(1, min(int(limit), 200)),
    )


def consent_decision_payload(row: Any) -> dict[str, Any]:
    return {
        "id": getattr(row, "id", None),
        "decision": _enum_token(getattr(row, "decision", None)),
        "basis": _enum_token(getattr(row, "basis", None)),
        "noticeVersion": getattr(row, "noticeVersion", None),
        "note": getattr(row, "note", None),
        "recordedAt": _iso_or_none(getattr(row, "recordedAt", None)),
        "createdAt": _iso_or_none(getattr(row, "createdAt", None)),
    }


def _enum_token(value: Any) -> str | None:
    if value is None:
        return None
    return str(getattr(value, "value", value))


def _iso_or_none(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


_PLACEHOLDER = r"\{[A-Za-z_][A-Za-z0-9_]*(?::[A-Za-z_]+)?\}"

_HEAD_LITERAL = r"[A-Za-z][A-Za-z0-9._-]*"

_TAIL_LITERAL = r"[A-Za-z0-9._-]+"

_HEAD_TOKEN_RE = re.compile(f"{_PLACEHOLDER}|{_HEAD_LITERAL}")
_TAIL_TOKEN_RE = re.compile(f"{_PLACEHOLDER}|{_TAIL_LITERAL}")

_CUID_RE = re.compile(r"^c[a-z0-9]{20,}$")
_UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
_LONG_HEX_RE = re.compile(r"^[0-9a-fA-F]{16,}$")

_MAX_LITERAL_RUN = 48

_RESERVED_TEMPLATES: frozenset[str] = frozenset({UNMATCHED_ROUTE, UNSAFE_ROUTE})

_KNOWN_TEMPLATES: frozenset[str] = frozenset()

_WARNED_TEMPLATES: set[str] = set()
_MAX_WARNED_TEMPLATES = 200


def register_known_templates(templates: Iterable[str]) -> int:
    global _KNOWN_TEMPLATES
    accepted: set[str] = set()
    for raw in templates:
        try:
            accepted.add(_shape_checked(raw))
        except UsageRuleViolation as exc:
            logger.warning("usage: refused %r from the route allow-list (%s)", raw, exc)
    _KNOWN_TEMPLATES = frozenset(accepted)
    logger.info("usage: route allow-list installed with %s template(s)", len(accepted))
    return len(accepted)


def known_templates() -> frozenset[str]:
    return _KNOWN_TEMPLATES


def _shape_checked(value: str) -> str:
    if not isinstance(value, str):
        raise UsageRuleViolation("A route template must be a string.")
    template = value.strip()
    if not template:
        raise UsageRuleViolation("A route template cannot be empty.")
    if len(template) > MAX_TEMPLATE_LENGTH:
        raise UsageRuleViolation(
            f"A route template may be at most {MAX_TEMPLATE_LENGTH} characters; this one is "
            f"{len(template)}."
        )
    if not template.startswith("/"):
        raise UsageRuleViolation(f"A route template must start with '/'; {template!r} does not.")
    for banned, what in (("?", "a query string"), ("#", "a fragment"), ("%", "percent-encoding")):
        if banned in template:
            raise UsageRuleViolation(
                f"A route template may not contain {what}; {template!r} does. Pass the matched "
                f"route's path_format, never the request path."
            )
    if template == "/":
        return template

    for segment in template.split("/")[1:]:
        if not segment:
            raise UsageRuleViolation(
                f"{template!r} has an empty path segment; a template has no '//' and no trailing "
                f"slash."
            )
        _check_segment(segment, template)
    return template


def _check_segment(segment: str, template: str) -> None:
    pos = 0
    while pos < len(segment):
        pattern = _HEAD_TOKEN_RE if pos == 0 else _TAIL_TOKEN_RE
        match = pattern.match(segment, pos)
        if match is None:
            raise UsageRuleViolation(
                f"{template!r} is not a route template: the segment {segment!r} is or contains "
                f"something that is not a path word or a {{placeholder}}. Pass the matched route's "
                f"path_format, never the request path."
            )
        token = match.group(0)
        if not token.startswith("{"):
            _check_literal(token, segment, template)
        pos = match.end()


def _check_literal(token: str, segment: str, template: str) -> None:
    if len(token) > _MAX_LITERAL_RUN:
        raise UsageRuleViolation(
            f"{template!r} is not a route template: {token!r} is {len(token)} characters, which is "
            f"an identifier and not a path word."
        )
    if _CUID_RE.match(token) or _UUID_RE.match(token) or _LONG_HEX_RE.match(token):
        raise UsageRuleViolation(
            f"{template!r} is not a route template: the segment {segment!r} contains {token!r}, "
            f"which is a record id. Record ids must never be stored here — a table of raw paths is "
            f"a per-designer reading list of other people's fieldwork."
        )


def ensure_route_template(value: str) -> str:
    if isinstance(value, str) and value.strip() in _RESERVED_TEMPLATES:
        return value.strip()
    template = _shape_checked(value)
    if _KNOWN_TEMPLATES and template not in _KNOWN_TEMPLATES:
        raise UsageRuleViolation(
            f"{template!r} is not one of this application's {len(_KNOWN_TEMPLATES)} routes. Only "
            f"templates from the mounted route table may be recorded."
        )
    return template


def is_route_template(value: str) -> bool:
    try:
        ensure_route_template(value)
    except UsageRuleViolation:
        return False
    return True


def normalise_client_app(raw: str | None) -> str:
    if not raw:
        return DEFAULT_CLIENT_APP
    token = raw.strip().lower()
    return token if token in CLIENT_APPS else DEFAULT_CLIENT_APP


def _normalise_method(raw: str | None) -> str:
    if not raw:
        return "?"
    return raw.strip().upper()[:16] or "?"


_BUFFER: deque[dict[str, Any]] = deque(maxlen=BUFFER_CEILING)

_RETRY_BATCH: list[dict[str, Any]] = []
_RETRY_ATTEMPTS = 0

_WITHDRAWN: set[str] = set()

_FLUSH_IN_FLIGHT = False

_DROPPED_TOTAL = 0
_DROPPED_SINCE_REPORT = 0
_WRITTEN_TOTAL = 0
_FAILED_FLUSHES = 0
_ABANDONED_TOTAL = 0


def record_event(
    *,
    route_template: str,
    method: str,
    status_code: int,
    duration_ms: float,
    client_app: str | None = None,
    user_id: str | None = None,
    consent: UsageConsent | None = None,
    at: datetime | None = None,
) -> bool:
    global _DROPPED_TOTAL, _DROPPED_SINCE_REPORT

    try:
        if is_withdrawn(user_id):
            return False

        plan = collection_plan(consent)
        if not plan.record:
            return False

        try:
            template = ensure_route_template(route_template)
        except UsageRuleViolation as exc:
            _warn_once(route_template, exc)
            template = UNSAFE_ROUTE

        row: dict[str, Any] = {
            "routeTemplate": template,
            "method": _normalise_method(method),
            "statusCode": int(status_code),
            "durationMs": max(0, min(int(duration_ms), MAX_DURATION_MS)),
            "clientApp": normalise_client_app(client_app),
            "userId": user_id if plan.attribute else None,
            "consentState": plan.consent_state,
            "createdAt": at if at is not None else datetime.now(UTC),
        }

        if len(_BUFFER) == BUFFER_CEILING:
            _DROPPED_TOTAL += 1
            _DROPPED_SINCE_REPORT += 1
            if _DROPPED_SINCE_REPORT == 1:
                logger.warning(
                    "usage: the event buffer is full at %s rows and is now dropping the oldest — "
                    "the database is not accepting writes, or is far behind. Rows lost from here "
                    "on are counted and reported at each flush",
                    BUFFER_CEILING,
                )
        _BUFFER.append(row)
        return True
    except Exception as exc:  # noqa: BLE001 - the response is already on the wire; see the docstring
        logger.warning("usage: could not record a %s on %r (%s)", method, route_template, exc)
        return False


def _warn_once(template: Any, exc: Exception) -> None:
    key = str(template)[:MAX_TEMPLATE_LENGTH]
    if key in _WARNED_TEMPLATES:
        return
    if len(_WARNED_TEMPLATES) < _MAX_WARNED_TEMPLATES:
        _WARNED_TEMPLATES.add(key)
    logger.warning(
        "usage: refused %r as a route template and recorded it as %s instead (%s). The caller "
        "should be passing the matched route's path_format",
        key,
        UNSAFE_ROUTE,
        exc,
    )


def buffer_stats() -> dict[str, Any]:
    return {
        "buffered": len(_BUFFER),
        "ceiling": BUFFER_CEILING,
        "retryPending": len(_RETRY_BATCH),
        "written": _WRITTEN_TOTAL,
        "dropped": _DROPPED_TOTAL,
        "abandoned": _ABANDONED_TOTAL,
        "failedFlushes": _FAILED_FLUSHES,
        "withdrawnAccounts": len(_WITHDRAWN),
    }


def reset_buffer() -> None:
    global _RETRY_BATCH, _RETRY_ATTEMPTS, _DROPPED_TOTAL, _DROPPED_SINCE_REPORT
    global _WRITTEN_TOTAL, _FAILED_FLUSHES, _ABANDONED_TOTAL, _FLUSH_IN_FLIGHT
    _BUFFER.clear()
    _RETRY_BATCH = []
    _RETRY_ATTEMPTS = 0
    _WITHDRAWN.clear()
    _WARNED_TEMPLATES.clear()
    _DROPPED_TOTAL = 0
    _DROPPED_SINCE_REPORT = 0
    _WRITTEN_TOTAL = 0
    _FAILED_FLUSHES = 0
    _ABANDONED_TOTAL = 0
    _FLUSH_IN_FLIGHT = False



async def flush() -> int:
    global _FLUSH_IN_FLIGHT, _RETRY_BATCH, _RETRY_ATTEMPTS
    global _WRITTEN_TOTAL, _FAILED_FLUSHES, _ABANDONED_TOTAL, _DROPPED_SINCE_REPORT

    if _FLUSH_IN_FLIGHT:
        return 0

    if _DROPPED_SINCE_REPORT:
        logger.warning(
            "usage: dropped %s event(s) since the last flush because the buffer was at its %s-row "
            "ceiling (%s dropped in this process so far)",
            _DROPPED_SINCE_REPORT,
            BUFFER_CEILING,
            _DROPPED_TOTAL,
        )
        _DROPPED_SINCE_REPORT = 0

    if _RETRY_BATCH:
        batch = _RETRY_BATCH
        _RETRY_BATCH = []
    else:
        _RETRY_ATTEMPTS = 0
        batch = [_BUFFER.popleft() for _ in range(min(FLUSH_ROWS, len(_BUFFER)))]

    if not batch:
        return 0

    if _WITHDRAWN:
        batch = [row for row in batch if row.get("userId") not in _WITHDRAWN]
        if not batch:
            return 0

    _FLUSH_IN_FLIGHT = True
    try:
        written = await db.usageevent.create_many(data=batch)
        _WRITTEN_TOTAL += written
        _RETRY_ATTEMPTS = 0
        return written
    except Exception as exc:  # noqa: BLE001 - see the docstring: the response is long gone
        _FAILED_FLUSHES += 1
        _RETRY_ATTEMPTS += 1
        if _RETRY_ATTEMPTS >= FLUSH_MAX_ATTEMPTS:
            _ABANDONED_TOTAL += len(batch)
            _RETRY_ATTEMPTS = 0
            logger.warning(
                "usage: could not write %s event(s) after %s attempt(s) (%s); abandoning them so "
                "the buffer keeps draining. %s event(s) abandoned in this process so far",
                len(batch),
                FLUSH_MAX_ATTEMPTS,
                exc,
                _ABANDONED_TOTAL,
            )
        else:
            _RETRY_BATCH = batch
            logger.warning(
                "usage: could not write %s event(s) (%s); holding them for one more attempt",
                len(batch),
                exc,
            )
        return 0
    finally:
        _FLUSH_IN_FLIGHT = False


async def flush_all() -> int:
    remaining = len(_BUFFER) + len(_RETRY_BATCH)
    statements = remaining // FLUSH_ROWS + 2
    written = 0
    for _ in range(statements):
        if not _BUFFER and not _RETRY_BATCH:
            break
        before = len(_BUFFER) + len(_RETRY_BATCH)
        wrote = await flush()
        written += wrote
        if wrote == 0 and len(_BUFFER) + len(_RETRY_BATCH) >= before:
            break
    if written:
        logger.info("usage: flushed %s event(s) on shutdown", written)
    return written


async def run_flush_worker() -> None:
    logger.info(
        "usage: flusher started — up to %s rows per statement, at least every %.1fs, ceiling %s",
        FLUSH_ROWS,
        FLUSH_INTERVAL_SECONDS,
        BUFFER_CEILING,
    )
    elapsed = 0.0
    while True:
        await asyncio.sleep(FLUSH_POLL_SECONDS)
        elapsed += FLUSH_POLL_SECONDS
        due = elapsed >= FLUSH_INTERVAL_SECONDS
        if not due and len(_BUFFER) < FLUSH_ROWS and not _RETRY_BATCH:
            continue
        elapsed = 0.0
        try:
            await flush()
        except Exception:
            logger.exception("Usage event flusher failed")



def _ensure_window(since: datetime, until: datetime) -> tuple[datetime, datetime]:
    for label, value in (("since", since), ("until", until)):
        if not isinstance(value, datetime):
            raise UsageRuleViolation(f"{label} must be a datetime.")
        if value.tzinfo is None or value.utcoffset() is None:
            raise UsageRuleViolation(
                f"{label} must carry a timezone; a naive datetime is read against the server's own "
                f"clock and silently shifts the window."
            )
    if since >= until:
        raise UsageRuleViolation("since must be earlier than until.")
    if until - since > timedelta(days=MAX_RANGE_DAYS):
        raise UsageRuleViolation(
            f"A usage window may be at most {MAX_RANGE_DAYS} days; this one is "
            f"{(until - since).days}. A longer report should be built from a rollup."
        )
    return since, until


def _window_where(since: datetime, until: datetime) -> dict[str, Any]:
    return {"createdAt": {"gte": since, "lt": until}}


def _fold_status(groups: Sequence[Any], key: str) -> dict[str, dict[str, Any]]:
    folded: dict[str, dict[str, Any]] = {}
    for group in groups:
        name = str(group[key])
        entry = folded.setdefault(
            name,
            {
                "requests": 0,
                "ok": 0,
                "clientErrors": 0,
                "serverErrors": 0,
                "_durationTotal": 0.0,
                "maxDurationMs": 0,
            },
        )
        count = int(group["_count"]["_all"])
        status = int(group["statusCode"])
        entry["requests"] += count
        if status >= 500:
            entry["serverErrors"] += count
        elif status >= 400:
            entry["clientErrors"] += count
        else:
            entry["ok"] += count
        avg = (group.get("_avg") or {}).get("durationMs") or 0
        entry["_durationTotal"] += float(avg) * count
        top = (group.get("_max") or {}).get("durationMs") or 0
        entry["maxDurationMs"] = max(entry["maxDurationMs"], int(top))
    for entry in folded.values():
        total = entry.pop("_durationTotal")
        entry["avgDurationMs"] = round(total / entry["requests"]) if entry["requests"] else None
    return folded


async def usage_for_user(user_id: str, since: datetime, until: datetime) -> dict[str, Any]:
    since, until = _ensure_window(since, until)
    if not user_id:
        raise UsageRuleViolation(
            "usage_for_user needs an account id; there is no 'everybody' form."
        )

    groups = await db.usageevent.group_by(
        by=["routeTemplate", "statusCode"],
        count=True,
        avg={"durationMs": True},
        max={"durationMs": True},
        where={"userId": user_id, **_window_where(since, until)},
    )
    folded = _fold_status(groups, "routeTemplate")
    routes = [
        {"routeTemplate": name, **entry}
        for name, entry in sorted(folded.items(), key=lambda kv: (-kv[1]["requests"], kv[0]))
    ]
    return {
        "userId": user_id,
        "from": since,
        "to": until,
        "requests": sum(entry["requests"] for entry in folded.values()),
        "routes": routes,
    }


async def usage_for_routes(
    templates: Sequence[str], since: datetime, until: datetime
) -> dict[str, Any]:
    since, until = _ensure_window(since, until)
    wanted = [ensure_route_template(value) for value in templates]
    if not wanted:
        raise UsageRuleViolation(
            "usage_for_routes needs at least one route template; there is no 'every route' form, "
            "because that is a scan of the whole window and no index serves it."
        )
    if len(wanted) > MAX_TEMPLATES_PER_QUERY:
        raise UsageRuleViolation(
            f"usage_for_routes takes at most {MAX_TEMPLATES_PER_QUERY} templates at a time; this "
            f"call named {len(wanted)}."
        )

    where = {"routeTemplate": {"in": wanted}, **_window_where(since, until)}
    status_groups, identity_groups = await gather_reads(
        db.usageevent.group_by(
            by=["routeTemplate", "statusCode"],
            count=True,
            avg={"durationMs": True},
            max={"durationMs": True},
            where=where,
        ),
        db.usageevent.group_by(by=["routeTemplate", "userId"], count=True, where=where),
    )

    folded = _fold_status(status_groups, "routeTemplate")
    identified: dict[str, int] = dict.fromkeys(wanted, 0)
    for group in identity_groups:
        if group.get("userId"):
            identified[str(group["routeTemplate"])] = (
                identified.get(str(group["routeTemplate"]), 0) + 1
            )

    routes: list[dict[str, Any]] = []
    for name in wanted:
        entry = folded.get(name)
        people = identified.get(name, 0)
        if entry is None:
            routes.append(
                {
                    "routeTemplate": name,
                    "requests": 0,
                    "identifiedUsers": 0,
                    "withheld": False,
                    "ok": 0,
                    "clientErrors": 0,
                    "serverErrors": 0,
                    "avgDurationMs": None,
                    "maxDurationMs": None,
                }
            )
            continue
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            routes.append(
                {
                    "routeTemplate": name,
                    "requests": None,
                    "identifiedUsers": None,
                    "withheld": True,
                    "withheldBecause": (
                        f"Fewer than {MIN_IDENTIFIED_USERS_FOR_ROUTE} people used this screen in "
                        f"this period, so it is hidden to keep anyone from being singled out."
                    ),
                    "ok": None,
                    "clientErrors": None,
                    "serverErrors": None,
                    "avgDurationMs": None,
                    "maxDurationMs": None,
                }
            )
            continue
        routes.append(
            {"routeTemplate": name, "identifiedUsers": people, "withheld": False, **entry}
        )

    return {
        "from": since,
        "to": until,
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "routes": routes,
    }


TIMELINE_BUCKETS: tuple[str, ...] = ("hour", "day")

MAX_TIMELINE_BUCKETS = 750

LATENCY_PERCENTILES: tuple[float, ...] = (0.5, 0.95, 0.99)

MAX_TRAIL_ROWS = 200


def _sql_window(since: datetime, until: datetime) -> tuple[str, str]:
    return (
        since.astimezone(UTC).replace(tzinfo=None).isoformat(sep=" ", timespec="milliseconds"),
        until.astimezone(UTC).replace(tzinfo=None).isoformat(sep=" ", timespec="milliseconds"),
    )


def _checked_templates(templates: Sequence[str]) -> list[str]:
    wanted = [ensure_route_template(value) for value in templates]
    if not wanted:
        raise UsageRuleViolation(
            "This report needs at least one route template; there is no 'every route' form, "
            "because that is a scan of the whole window and no index serves it."
        )
    if len(wanted) > MAX_TEMPLATES_PER_QUERY:
        raise UsageRuleViolation(
            f"At most {MAX_TEMPLATES_PER_QUERY} templates may be named at a time; this call named "
            f"{len(wanted)}."
        )
    return wanted


def _withheld_because(what: str) -> str:
    return (
        f"Fewer than {MIN_IDENTIFIED_USERS_FOR_ROUTE} people are behind {what}, so it is hidden to "
        f"keep anyone from being singled out."
    )


def _withhold(entry: dict[str, Any], keys: Sequence[str], *, what: str) -> dict[str, Any]:
    blanked = dict(entry)
    for key in keys:
        blanked[key] = None
    blanked["withheld"] = True
    blanked["withheldBecause"] = _withheld_because(what)
    return blanked


def _error_rate(requests: Any, client_errors: Any, server_errors: Any) -> float | None:
    total = int(requests or 0)
    if total <= 0:
        return None
    return round((int(client_errors or 0) + int(server_errors or 0)) / total, 4)


async def usage_timeline(
    templates: Sequence[str],
    since: datetime,
    until: datetime,
    *,
    bucket: str = "day",
) -> dict[str, Any]:
    since, until = _ensure_window(since, until)
    wanted = _checked_templates(templates)
    unit = str(bucket or "").strip().lower()
    if unit not in TIMELINE_BUCKETS:
        raise UsageRuleViolation(
            f"A timeline is bucketed by one of {', '.join(TIMELINE_BUCKETS)}; {bucket!r} is "
            f"neither. There is deliberately no finer bucket: below an hour, a series over a screen "
            f"a few people use is one person's afternoon on a page labelled aggregates."
        )
    expected = _expected_buckets(since, until, unit)
    if expected > MAX_TIMELINE_BUCKETS:
        raise UsageRuleViolation(
            f"That period would need {expected} points at one per {unit}, and a chart can show at "
            f"most {MAX_TIMELINE_BUCKETS}. Choose a shorter period, or choose One per day."
        )

    start, end = _sql_window(since, until)
    rows = await db.query_raw(
        """
        SELECT
          to_char(date_trunc($1::text, "createdAt"), 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS bucket,
          COUNT(*)::int                                                        AS requests,
          COUNT(*) FILTER (WHERE "statusCode" < 400)::int                      AS ok,
          COUNT(*) FILTER (WHERE "statusCode" >= 400 AND "statusCode" < 500)::int AS client_errors,
          COUNT(*) FILTER (WHERE "statusCode" >= 500)::int                     AS server_errors,
          COUNT(DISTINCT "userId")::int                                        AS identified_users
        FROM "UsageEvent"
        WHERE "routeTemplate" = ANY($2::text[])
          AND "createdAt" >= $3::timestamp
          AND "createdAt" <  $4::timestamp
        GROUP BY 1
        ORDER BY 1
        """,
        unit,
        wanted,
        start,
        end,
    )

    by_bucket = {str(row["bucket"]): row for row in rows}
    metrics = ("requests", "ok", "clientErrors", "serverErrors", "errorRate", "identifiedUsers")
    series: list[dict[str, Any]] = []
    for label in _bucket_labels(since, until, unit):
        row = by_bucket.get(label)
        if row is None:
            series.append(
                {
                    "bucket": label,
                    "requests": 0,
                    "ok": 0,
                    "clientErrors": 0,
                    "serverErrors": 0,
                    "errorRate": None,
                    "identifiedUsers": 0,
                    "withheld": False,
                }
            )
            continue
        entry = {
            "bucket": label,
            "requests": int(row["requests"] or 0),
            "ok": int(row["ok"] or 0),
            "clientErrors": int(row["client_errors"] or 0),
            "serverErrors": int(row["server_errors"] or 0),
            "errorRate": _error_rate(
                row["requests"], row["client_errors"], row["server_errors"]
            ),
            "identifiedUsers": int(row["identified_users"] or 0),
            "withheld": False,
        }
        people = entry["identifiedUsers"]
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            entry = _withhold(entry, metrics, what=f"this {unit}")
        series.append(entry)

    return {
        "from": since,
        "to": until,
        "bucket": unit,
        "templates": wanted,
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "maxBuckets": MAX_TIMELINE_BUCKETS,
        "series": series,
    }


def _expected_buckets(since: datetime, until: datetime, unit: str) -> int:
    step = timedelta(hours=1) if unit == "hour" else timedelta(days=1)
    first = _floor_to(since, unit)
    span = until - first
    return max(1, -(-int(span.total_seconds()) // int(step.total_seconds())))


def _floor_to(moment: datetime, unit: str) -> datetime:
    at_utc = moment.astimezone(UTC)
    if unit == "hour":
        return at_utc.replace(minute=0, second=0, microsecond=0)
    return at_utc.replace(hour=0, minute=0, second=0, microsecond=0)


def _bucket_labels(since: datetime, until: datetime, unit: str) -> list[str]:
    step = timedelta(hours=1) if unit == "hour" else timedelta(days=1)
    labels: list[str] = []
    cursor = _floor_to(since, unit)
    while cursor < until and len(labels) <= MAX_TIMELINE_BUCKETS:
        labels.append(cursor.strftime("%Y-%m-%dT%H:%M:%SZ"))
        cursor += step
    return labels


async def usage_latency(
    templates: Sequence[str], since: datetime, until: datetime
) -> dict[str, Any]:
    since, until = _ensure_window(since, until)
    wanted = _checked_templates(templates)
    start, end = _sql_window(since, until)

    rows = await db.query_raw(
        """
        SELECT
          "routeTemplate"                                                       AS template,
          COUNT(*)::int                                                         AS requests,
          COUNT(DISTINCT "userId")::int                                         AS identified_users,
          percentile_cont(0.5)  WITHIN GROUP (ORDER BY "durationMs")            AS p50,
          percentile_cont(0.95) WITHIN GROUP (ORDER BY "durationMs")            AS p95,
          percentile_cont(0.99) WITHIN GROUP (ORDER BY "durationMs")            AS p99,
          MAX("durationMs")::int                                                AS max_ms
        FROM "UsageEvent"
        WHERE "routeTemplate" = ANY($1::text[])
          AND "createdAt" >= $2::timestamp
          AND "createdAt" <  $3::timestamp
        GROUP BY 1
        """,
        wanted,
        start,
        end,
    )

    by_template = {str(row["template"]): row for row in rows}
    metrics = ("requests", "identifiedUsers", "p50Ms", "p95Ms", "p99Ms", "maxDurationMs")
    out: list[dict[str, Any]] = []
    for name in wanted:
        row = by_template.get(name)
        if row is None:
            out.append(
                {
                    "routeTemplate": name,
                    "requests": 0,
                    "identifiedUsers": 0,
                    "withheld": False,
                    "p50Ms": None,
                    "p95Ms": None,
                    "p99Ms": None,
                    "maxDurationMs": None,
                }
            )
            continue
        entry = {
            "routeTemplate": name,
            "requests": int(row["requests"] or 0),
            "identifiedUsers": int(row["identified_users"] or 0),
            "withheld": False,
            "p50Ms": _round_ms(row["p50"]),
            "p95Ms": _round_ms(row["p95"]),
            "p99Ms": _round_ms(row["p99"]),
            "maxDurationMs": int(row["max_ms"] or 0),
        }
        people = entry["identifiedUsers"]
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            entry = _withhold(entry, metrics, what="this screen in this period")
        out.append(entry)

    return {
        "from": since,
        "to": until,
        "percentiles": [f"p{int(value * 100)}" for value in LATENCY_PERCENTILES],
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "routes": out,
    }


def _round_ms(value: Any) -> int | None:
    if value is None:
        return None
    return round(float(value))


async def usage_clients(
    templates: Sequence[str], since: datetime, until: datetime
) -> dict[str, Any]:
    since, until = _ensure_window(since, until)
    wanted = _checked_templates(templates)
    start, end = _sql_window(since, until)

    rows = await db.query_raw(
        """
        SELECT
          "clientApp"                                                           AS client,
          COUNT(*)::int                                                         AS requests,
          COUNT(*) FILTER (WHERE "statusCode" < 400)::int                       AS ok,
          COUNT(*) FILTER (WHERE "statusCode" >= 400 AND "statusCode" < 500)::int AS client_errors,
          COUNT(*) FILTER (WHERE "statusCode" >= 500)::int                      AS server_errors,
          COUNT(DISTINCT "userId")::int                                         AS identified_users,
          AVG("durationMs")                                                     AS avg_ms
        FROM "UsageEvent"
        WHERE "routeTemplate" = ANY($1::text[])
          AND "createdAt" >= $2::timestamp
          AND "createdAt" <  $3::timestamp
        GROUP BY 1
        """,
        wanted,
        start,
        end,
    )

    by_client = {str(row["client"]): row for row in rows}
    metrics = (
        "requests",
        "ok",
        "clientErrors",
        "serverErrors",
        "errorRate",
        "identifiedUsers",
        "avgDurationMs",
    )
    out: list[dict[str, Any]] = []
    known = sorted(CLIENT_APPS)
    for name in known + sorted(set(by_client) - set(known)):
        row = by_client.get(name)
        if row is None:
            out.append(
                {
                    "clientApp": name,
                    "requests": 0,
                    "ok": 0,
                    "clientErrors": 0,
                    "serverErrors": 0,
                    "errorRate": None,
                    "identifiedUsers": 0,
                    "avgDurationMs": None,
                    "withheld": False,
                }
            )
            continue
        entry = {
            "clientApp": name,
            "requests": int(row["requests"] or 0),
            "ok": int(row["ok"] or 0),
            "clientErrors": int(row["client_errors"] or 0),
            "serverErrors": int(row["server_errors"] or 0),
            "errorRate": _error_rate(
                row["requests"], row["client_errors"], row["server_errors"]
            ),
            "identifiedUsers": int(row["identified_users"] or 0),
            "avgDurationMs": _round_ms(row["avg_ms"]),
            "withheld": False,
        }
        people = entry["identifiedUsers"]
        if 0 < people < MIN_IDENTIFIED_USERS_FOR_ROUTE:
            entry = _withhold(entry, metrics, what="this client in this period")
        out.append(entry)

    return {
        "from": since,
        "to": until,
        "templates": wanted,
        "minimumIdentifiedUsers": MIN_IDENTIFIED_USERS_FOR_ROUTE,
        "clients": out,
    }



async def trail_for_user(
    user_id: str,
    since: datetime,
    until: datetime,
    *,
    limit: int = MAX_TRAIL_ROWS,
    offset: int = 0,
) -> dict[str, Any]:
    since, until = _ensure_window(since, until)
    if not user_id:
        raise UsageRuleViolation(
            "trail_for_user needs an account id; there is no 'everybody' form, and there is no "
            "filter that would make one."
        )
    take = max(1, min(int(limit), MAX_TRAIL_ROWS))
    skip = max(0, int(offset))

    rows = await db.usageevent.find_many(
        where={"userId": user_id, **_window_where(since, until)},
        order=[{"createdAt": "desc"}, {"id": "desc"}],
        take=take,
        skip=skip,
    )
    return {
        "userId": user_id,
        "from": since,
        "to": until,
        "limit": take,
        "offset": skip,
        "maxRows": MAX_TRAIL_ROWS,
        "events": [
            {
                "id": getattr(row, "id", None),
                "routeTemplate": getattr(row, "routeTemplate", None),
                "method": getattr(row, "method", None),
                "statusCode": getattr(row, "statusCode", None),
                "durationMs": getattr(row, "durationMs", None),
                "clientApp": getattr(row, "clientApp", None),
                "consentState": getattr(row, "consentState", None),
                "at": _iso_or_none(getattr(row, "createdAt", None)),
            }
            for row in rows
        ],
    }
