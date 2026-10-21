"""THE MONDAY SUMMARY: one message a week to every reviewer and official, in-app and by e-mail.

What waits for them, what is overdue, what came in this week, and one coverage figure. Sent by the
queue worker (``app/worker.py``) on Monday from 08:00 India Standard Time; a worker that was down at
08:00 catches up the same day, and after Monday the week is skipped, never sent late.

── ONCE PER PERSON PER WEEK ─────────────────────────────────────────────────────────────────────

``WeeklyDigest`` has a unique ``(userId, weekStart)``. For each person: insert the row PENDING (a
unique violation means somebody already has it: skip), build the payload, call
:func:`app.services.notifications.notify`, mark it DELIVERED with the ids it wrote. A worker that
dies in between leaves PENDING; the next pass reclaims a PENDING row untouched for 15 minutes with a
compare-and-set on ``updatedAt`` and delivers it. An empty week (nothing waiting, overdue or new) is
SKIPPED_EMPTY and sends nothing. An account that turned the summary off is SKIPPED_OPTOUT, checked
at send time, with no payload kept.

── WHO GETS ONE ─────────────────────────────────────────────────────────────────────────────────

:func:`is_authority`: Inspectors, the three ministry tiers and the two admin tiers, plus anybody
holding an inspection post. Designers, researchers and the rest of the people who upload data do
not. Every count is read through the same scope the person's own screens use, so a summary never
reveals a workshop outside it.

For an inspection-post holder that scope is ``inspectable_by_clause``, read here and nowhere else
outside the inspection feature. It is a READ: it counts and lists, it writes nothing to a workshop,
and the links it builds open the inspection screens, which check the scope again
(``tests/test_dw_inspector_scope_gate.py`` lists this file in ``THE_FEATURE`` for that reason).

── WHAT IS KEPT, AND WHAT NEVER IS ──────────────────────────────────────────────────────────────

The payload holds counts, workshop titles and app paths: what the e-mail shows, nothing more. Never
an artisan, a phone number, Aadhaar data, a designer's contact or a review note. Rows older than 26
weeks are purged and go with the account. Log lines carry the digest id, the account id and counts.

The documentation portal has the same module by name, the same table and the same routes, with its
own content rules. The two portals never call each other for this.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, date, datetime, time, timedelta
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.config import Settings, get_settings
from app.core.db import db
from app.core.deps import (
    can_access_review,
    can_approve_design_workshops,
    is_admin,
    role_value,
)
from app.services import mailer, notifications, review_queue
from app.services.concurrency import gather_reads
from prisma import Json

logger = logging.getLogger(__name__)

# ── The schedule ─────────────────────────────────────────────────────────────────────────────────
DIGEST_WEEKDAY = 0  # Monday
DIGEST_HOUR_IST = 8
SCHEDULE_LABEL = "Mondays, 08:00 IST"
#: The worker asks at most this often inside the window; outside it the question costs no query.
CHECK_EVERY = timedelta(minutes=10)
#: A PENDING row untouched this long belongs to a worker that died; the next pass takes it over.
STALE_PENDING_AFTER = timedelta(minutes=15)
BATCH_SIZE = 50
RETENTION_WEEKS = 26

# ── The content ──────────────────────────────────────────────────────────────────────────────────
REVIEW_OVERDUE_DAYS = 7
SANCTION_OVERDUE_DAYS = 14
ITEMS_PER_SECTION = 5

PENDING = "PENDING"
DELIVERED = "DELIVERED"
SKIPPED_EMPTY = "SKIPPED_EMPTY"
SKIPPED_OPTOUT = "SKIPPED_OPTOUT"

PREFERENCE = "weeklyDigest"
NOT_ELIGIBLE = "The weekly summary is for reviewers and officials."

#: The roles that get a summary by role alone. Anybody else holding an inspection post gets one too.
AUTHORITY_ROLES = frozenset(
    {
        "INSPECTOR",
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
        "ADMIN",
        "MASTER_ADMIN",
    }
)
POSTED_OFFICER_ROLES = frozenset({"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"})

#: The statuses a workshop is still being worked in: ministry_dashboard's registered + ongoing.
OPEN_STATUSES: tuple[str, ...] = ("DRAFT", "IN_PROGRESS", "NEEDS_REVISION", "PRE_SUBMISSION")

_last_check: datetime | None = None


# --------------------------------------------------------------------------------------
# Time
# --------------------------------------------------------------------------------------


def _aware(now: datetime | None) -> datetime:
    moment = now or datetime.now(UTC)
    return moment if moment.tzinfo else moment.replace(tzinfo=UTC)


def week_start_for(now: datetime | None = None) -> date:
    """The Monday, as an India Standard Time calendar date, of the week ``now`` falls in."""
    local = _aware(now).astimezone(mailer.IST)
    return local.date() - timedelta(days=local.weekday())


def in_send_window(now: datetime | None = None) -> bool:
    """Monday 08:00 to 23:59 IST."""
    local = _aware(now).astimezone(mailer.IST)
    return local.weekday() == DIGEST_WEEKDAY and local.hour >= DIGEST_HOUR_IST


@dataclass(frozen=True)
class Window:
    """ "This week": ``[weekStart − 7 days 08:00 IST, weekStart 08:00 IST)``, as UTC instants."""

    week_start: date
    start: datetime
    end: datetime

    @property
    def today(self) -> datetime:
        """Midnight of the Monday, the way a stored calendar date (``endDate``) reads."""
        return datetime.combine(self.week_start, time(), tzinfo=UTC)


def window_for(week_start: date) -> Window:
    end = datetime.combine(week_start, time(DIGEST_HOUR_IST), tzinfo=mailer.IST).astimezone(UTC)
    return Window(week_start=week_start, start=end - timedelta(days=7), end=end)


def _week_value(week_start: date) -> datetime:
    """A ``@db.Date`` column is written as midnight UTC of that date."""
    return datetime.combine(week_start, time(), tzinfo=UTC)


def _iso_date(value: Any) -> str | None:
    if isinstance(value, datetime):
        moment = value if value.tzinfo else value.replace(tzinfo=UTC)
        return moment.astimezone(mailer.IST).date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return None


def _week_iso(value: Any) -> str | None:
    """A stored ``weekStart`` back to "2026-10-19". It is a date, so it is read in UTC, not IST."""
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return None


# --------------------------------------------------------------------------------------
# Who
# --------------------------------------------------------------------------------------


async def holds_inspection_post(user_id: str) -> bool:
    if not user_id:
        return False
    return await db.designworkshopinspector.find_first(where={"userId": user_id}) is not None


async def is_authority(user: Any) -> bool:
    """May this account receive the summary? See the module docstring."""
    if user is None:
        return False
    if role_value(user) in AUTHORITY_ROLES:
        return True
    return await holds_inspection_post(str(getattr(user, "id", "") or ""))


async def inspection_holders() -> set[str]:
    """Every account that holds an inspection post on any workshop. One read."""
    posts = await db.designworkshopinspector.find_many(distinct=["userId"])
    return {row.userId for row in posts}


async def eligible_users(*, after: str | None = None, take: int = BATCH_SIZE) -> list[Any]:
    """One page of eligible accounts, by id. E-mail is optional: the in-app half always goes."""
    holders = sorted(await inspection_holders())
    who: list[dict[str, Any]] = [{"role": {"in": sorted(AUTHORITY_ROLES)}}]
    if holders:
        who.append({"id": {"in": holders}})
    where: dict[str, Any] = {"OR": who}
    if after:
        where = {"AND": [where, {"id": {"gt": after}}]}
    return await db.user.find_many(where=where, order={"id": "asc"}, take=take)


# --------------------------------------------------------------------------------------
# What
# --------------------------------------------------------------------------------------


@dataclass
class _Scope:
    """Which workshops this person's own screens show them, and where a workshop link goes."""

    clause: dict[str, Any] | None  # None = the whole estate
    empty: bool  # True = no workshops at all (an inspector with no posts)
    label: str
    queue: str  # the list a workshop line opens
    item_base: str  # "/design-workshops/" and the like


async def _scope(user: Any, holds_posts: bool) -> _Scope:
    from app.services.design_workshop_inspectors import inspectable_by_clause
    from app.services.design_workshop_oversight import oversight_by_clause

    role = role_value(user)
    user_id = str(getattr(user, "id", "") or "")
    if is_admin(user) or role == "MINISTRY_ADMIN":
        queue = (
            "/ministry-dashboard"
            if role in {"MINISTRY_ADMIN", "MASTER_ADMIN"}
            else "/design-workshops"
        )
        return _Scope(None, False, "Every workshop", queue, "/design-workshops/")
    clauses: list[dict[str, Any]] = []
    if role in POSTED_OFFICER_ROLES:
        clauses.append(oversight_by_clause(user_id))
    if holds_posts:
        clauses.append(inspectable_by_clause(user_id))
    if not clauses:
        return _Scope(
            None,
            True,
            "Workshops you oversee",
            "/design-workshop-inspections",
            "/design-workshop-inspections/",
        )
    if role in POSTED_OFFICER_ROLES:
        return _Scope(
            clauses[0] if len(clauses) == 1 else {"OR": clauses},
            False,
            "Workshops you oversee",
            "/officers/monitored",
            "/officers/monitored/",
        )
    return _Scope(
        clauses[0],
        False,
        "Workshops you inspect",
        "/design-workshop-inspections",
        "/design-workshop-inspections/",
    )


def _in_scope(scope: _Scope, *parts: dict[str, Any]) -> dict[str, Any]:
    clauses = [{"deletedAt": None}, *parts]
    if scope.clause is not None:
        clauses.append(scope.clause)
    return {"AND": clauses}


async def _count_or_zero(
    scope: _Scope, *parts: dict[str, Any], shared: dict[str, Any] | None = None, key: str = ""
) -> int:
    if scope.empty:
        return 0
    if scope.clause is None:
        # The whole estate is the same answer for every administrator in the pass: read it once.
        return await _memo(
            shared, f"estate:{key}", lambda: db.designworkshop.count(where=_in_scope(scope, *parts))
        )
    return await db.designworkshop.count(where=_in_scope(scope, *parts))


async def _rows_or_none(
    scope: _Scope,
    order: dict[str, str],
    *parts: dict[str, Any],
    shared: dict[str, Any] | None = None,
    key: str = "",
) -> list[Any]:
    if scope.empty:
        return []

    def read() -> Any:
        return db.designworkshop.find_many(
            where=_in_scope(scope, *parts), order=order, take=ITEMS_PER_SECTION
        )

    if scope.clause is None:
        return await _memo(shared, f"estate:{key}", read)
    return await read()


async def _inspection_waiting(user_id: str) -> list[Any]:
    """The inspector's queue: assigned, handed in, not yet reviewed by them this round.

    The same two reads as ``GET /design-workshop-inspections/awaiting-count`` (feature/s-dp-notify):
    the waiting workshops, then this inspector's suggestions for them.
    """
    from app.services.design_workshop_inspectors import inspectable_by_clause

    waiting = await db.designworkshop.find_many(
        where={
            "deletedAt": None,
            "submissionRound": {"gt": 0},
            "status": "PRE_SUBMISSION",
            "AND": [inspectable_by_clause(user_id)],
        },
        order={"lastHandedInAt": "asc"},
    )
    if not waiting:
        return []
    rounds = {row.id: int(row.submissionRound or 0) for row in waiting}
    mine = await db.dwinspectionfeedback.find_many(
        where={"actorId": user_id, "designWorkshopId": {"in": list(rounds)}},
    )
    reviewed = {
        row.designWorkshopId for row in mine if int(row.round) == rounds.get(row.designWorkshopId)
    }
    return [row for row in waiting if row.id not in reviewed]


async def _sanction_stalls(
    older_than: datetime | None = None, since: datetime | None = None
) -> int:
    """Recorded orders still missing their stage-1 details, scored by the one scorer — the same
    arithmetic as ``sanction_orders.awaiting_count``, narrowed by the order's date."""
    from app.services import sanction_orders
    from app.services.stage_schema import stage, stage_completeness

    where: dict[str, Any] = {}
    if older_than is not None:
        where["sanctionOrderDate"] = {"lt": older_than}
    rows = await db.sanctionorder.find_many(where=where)
    singletons = await sanction_orders._stage_one_singletons([row.designWorkshopId for row in rows])
    spec = stage(sanction_orders.STAGE_ONE_KEY)
    return sum(
        1
        for row in rows
        if not stage_completeness(spec, singletons.get(row.designWorkshopId) or {}, {}).is_complete
    )


def _item(row: Any, base: str, since: Any) -> dict[str, Any]:
    return {
        "title": str(getattr(row, "title", "") or "Untitled workshop"),
        "link": f"{base}{row.id}",
        "since": _iso_date(since),
    }


def _line(key: str, label: str, count: int, link: str) -> dict[str, Any] | None:
    count = int(count or 0)
    return {"key": key, "label": label, "count": count, "link": link} if count > 0 else None


def _section(
    key: str,
    label: str,
    lines: list[dict[str, Any] | None],
    items: list[dict[str, Any]],
    *,
    with_items: bool = True,
) -> dict[str, Any]:
    kept = [line for line in lines if line]
    seen: set[str] = set()
    unique: list[dict[str, Any]] = []
    for item in items:
        if item["link"] in seen:
            continue
        seen.add(item["link"])
        unique.append(item)
    section: dict[str, Any] = {
        "key": key,
        "label": label,
        "total": sum(line["count"] for line in kept),
        "lines": kept,
    }
    if with_items:
        section["items"] = unique[:ITEMS_PER_SECTION]
    return section


async def _memo(shared: dict[str, Any] | None, key: str, factory: Any) -> Any:
    """One pass reads an estate-wide figure once, however many officials it is for. The box is
    small; a sanction-order score repeated per official was most of a pass's queries."""
    if shared is None:
        return await factory()
    if key not in shared:
        shared[key] = asyncio.ensure_future(factory())
    return await shared[key]


async def build_payload(
    user: Any,
    window: Window,
    *,
    now: datetime | None = None,
    shared: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Everything one summary says, read through the person's own scope. Counts, titles, app paths.

    ``shared`` is a per-pass cache for the figures that are the same for everybody who sees them
    (the approval queue, the sanction register, a rank's review queue)."""
    from app.services import design_workshop_approvals
    from app.services.sanction_orders import (
        awaiting_count as sanction_awaiting,
        can_record_sanction_orders,
    )

    now = _aware(now)
    user_id = str(user.id)
    # Who holds a post is one read per pass, not one per person (``eligible_users`` reads the same set).
    holds_posts = (
        user_id in await _memo(shared, "inspectionHolders", inspection_holders)
        if shared is not None and user_id
        else await holds_inspection_post(user_id)
    )
    scope = await _scope(user, holds_posts)
    approver = can_approve_design_workshops(user)
    recorder = can_record_sanction_orders(user)
    reviewer = can_access_review(user)
    review_cutoff = now - timedelta(days=REVIEW_OVERDUE_DAYS)
    sanction_cutoff = now - timedelta(days=SANCTION_OVERDUE_DAYS)
    week = {"gte": window.start, "lt": window.end}
    ended = {"endDate": {"lt": window.today}}
    open_overdue = {"status": {"in": list(OPEN_STATUSES)}, **ended}
    handed_in = {"submissionRound": {"gt": 0}, "status": "PRE_SUBMISSION"}

    async def none() -> Any:
        return None

    (
        approvals,
        approval_items,
        approvals_late,
        approvals_late_items,
        inspections,
        sanctions,
        sanctions_late,
        sanctions_new,
        records,
        stages_late,
        stages_late_items,
        new_handed_in,
        new_registered,
        coverage_in,
        coverage_of,
    ) = await gather_reads(
        _memo(shared, "approvals", design_workshop_approvals.awaiting_counts)
        if approver
        else none(),
        _memo(
            shared,
            "approvalItems",
            lambda: db.designworkshop.find_many(
                where={"deletedAt": None, **handed_in},
                order={"lastHandedInAt": "asc"},
                take=ITEMS_PER_SECTION,
            ),
        )
        if approver
        else none(),
        _memo(
            shared,
            "approvalsLate",
            lambda: db.designworkshop.count(
                where={"deletedAt": None, **handed_in, "lastHandedInAt": {"lt": review_cutoff}}
            ),
        )
        if approver
        else none(),
        _memo(
            shared,
            "approvalsLateItems",
            lambda: db.designworkshop.find_many(
                where={"deletedAt": None, **handed_in, "lastHandedInAt": {"lt": review_cutoff}},
                order={"lastHandedInAt": "asc"},
                take=ITEMS_PER_SECTION,
            ),
        )
        if approver
        else none(),
        _inspection_waiting(user_id) if holds_posts else none(),
        _memo(shared, "sanctions", sanction_awaiting) if recorder else none(),
        _memo(shared, "sanctionsLate", lambda: _sanction_stalls(older_than=sanction_cutoff))
        if recorder
        else none(),
        _memo(shared, "sanctionsNew", lambda: db.sanctionorder.count(where={"createdAt": week}))
        if recorder
        else none(),
        _memo(
            shared,
            f"records:{review_queue.reviewable_creator_roles(user)}",
            lambda: review_queue.pending_count(user),
        )
        if reviewer
        else none(),
        # Scoped to the person, except that the whole-estate scope is shared by every administrator
        # and so is read once per pass (the ``key``); see ``_count_or_zero``.
        _count_or_zero(scope, open_overdue, shared=shared, key="stagesLate"),
        _rows_or_none(scope, {"endDate": "asc"}, open_overdue, shared=shared, key="stagesLateItems"),
        _count_or_zero(scope, {"lastHandedInAt": week}, shared=shared, key="newHandedIn"),
        _count_or_zero(scope, {"createdAt": week}, shared=shared, key="newRegistered"),
        _count_or_zero(scope, ended, {"submissionRound": {"gt": 0}}, shared=shared, key="coverageIn"),
        _count_or_zero(scope, ended, shared=shared, key="coverageOf"),
    )

    inspections = inspections or []
    inspections_late = [
        row for row in inspections if row.lastHandedInAt and row.lastHandedInAt < review_cutoff
    ]
    approval_base = "/design-workshop-approvals/"
    inspection_base = "/design-workshop-inspections/"

    waiting = _section(
        "waiting",
        "Waiting for you",
        [
            _line(
                "approvals",
                "Reports to approve",
                (approvals or {}).get("awaiting", 0),
                "/design-workshop-approvals",
            ),
            _line(
                "toHandOn",
                "Approved, to hand on",
                (approvals or {}).get("toHandOn", 0),
                "/design-workshop-approvals?state=approved",
            ),
            _line(
                "inspections",
                "Reports to inspect",
                len(inspections),
                "/design-workshop-inspections",
            ),
            _line("sanctions", "Sanction orders missing setup", sanctions or 0, "/sanction-orders"),
            _line("records", "Records to review", records or 0, "/review"),
        ],
        [_item(row, approval_base, row.lastHandedInAt) for row in approval_items or []]
        + [_item(row, inspection_base, row.lastHandedInAt) for row in inspections],
    )
    # An approver's late count is estate-wide and already holds every report an inspector could be
    # late on, so the two are never added together.
    late_review_count = int(approvals_late or 0) if approver else len(inspections_late)
    overdue = _section(
        "overdue",
        "Overdue",
        [
            _line("stages", "Workshops past their end date", stages_late, scope.queue),
            _line(
                "reviews",
                f"Reports waiting over {REVIEW_OVERDUE_DAYS} days",
                late_review_count,
                "/design-workshop-approvals" if approver else "/design-workshop-inspections",
            ),
            _line(
                "sanctions",
                f"Sanction orders stalled over {SANCTION_OVERDUE_DAYS} days",
                sanctions_late or 0,
                "/sanction-orders",
            ),
        ],
        [_item(row, scope.item_base, row.endDate) for row in stages_late_items]
        + [_item(row, approval_base, row.lastHandedInAt) for row in approvals_late_items or []]
        + [_item(row, inspection_base, row.lastHandedInAt) for row in inspections_late],
    )
    new = _section(
        "new",
        "New this week",
        [
            _line(
                "handedIn",
                "Reports handed in",
                new_handed_in,
                "/design-workshop-approvals" if approver else scope.queue,
            ),
            _line("sanctions", "Sanction orders recorded", sanctions_new or 0, "/sanction-orders"),
            _line("registered", "Workshops registered", new_registered, scope.queue),
        ],
        [],
    )
    of = int(coverage_of or 0)
    value = int(coverage_in or 0)
    coverage = {
        "label": "Reports in",
        "value": value,
        "of": of,
        "percent": round(100 * value / of) if of else None,
        "scope": scope.label,
    }
    return {
        "weekStart": window.week_start.isoformat(),
        "generatedAt": now.isoformat(),
        "waiting": waiting["total"],
        "overdue": overdue["total"],
        "newThisWeek": new["total"],
        "sections": [waiting, overdue, new],
        "coverage": coverage,
    }


def summary_line(payload: dict[str, Any]) -> str:
    """ "4 waiting · 2 overdue · 11 new · Reports in 71%": the in-app body, the handset's one line."""
    label, figure, _note = mailer.coverage_words(payload.get("coverage"))
    parts = [
        f"{payload.get('waiting', 0)} waiting",
        f"{payload.get('overdue', 0)} overdue",
        f"{payload.get('newThisWeek', 0)} new",
    ]
    if (payload.get("coverage") or {}).get("of"):
        parts.append(f"{label} {figure}")
    return " · ".join(parts)


# --------------------------------------------------------------------------------------
# Sending
# --------------------------------------------------------------------------------------


async def _send(
    row: Any,
    user: Any,
    window: Window,
    now: datetime,
    settings: Settings,
    shared: dict[str, Any] | None = None,
) -> str:
    """Build and deliver one claimed PENDING row. Returns the status it ended in."""
    if not await notifications.wants_email(str(user.id), PREFERENCE):
        await db.weeklydigest.update(where={"id": row.id}, data={"status": SKIPPED_OPTOUT})
        return SKIPPED_OPTOUT
    payload = await build_payload(user, window, now=now, shared=shared)
    if not (payload["waiting"] or payload["overdue"] or payload["newThisWeek"]):
        await db.weeklydigest.update(where={"id": row.id}, data={"status": SKIPPED_EMPTY})
        return SKIPPED_EMPTY
    delivery = await notifications.notify(
        user,
        "WEEKLY_DIGEST",
        title=mailer.digest_title(payload["waiting"], payload["overdue"]),
        body=summary_line(payload),
        link=f"/digest/{row.id}",
        email_kind=mailer.WEEKLY_DIGEST,
        email_params={"digestId": row.id, **payload},
        # The opt-out was read at the top of this function and this person wants the e-mail; naming
        # the preference again would read the same row a second time inside ``notify``.
        preference=None,
        settings=settings,
    )
    if delivery.notification is None and delivery.email is None:
        # notify never raises; nothing written means a failed write. Leave it PENDING for repair.
        logger.warning(
            "digest %s for account %s wrote nothing; left for the next pass", row.id, user.id
        )
        return PENDING
    await db.weeklydigest.update(
        where={"id": row.id},
        data={
            "status": DELIVERED,
            "payload": Json(payload),
            "notificationId": getattr(delivery.notification, "id", None),
            "emailMessageId": getattr(delivery.email, "id", None),
        },
    )
    logger.info(
        "digest %s delivered to account %s: %s waiting, %s overdue, %s new",
        row.id,
        user.id,
        payload["waiting"],
        payload["overdue"],
        payload["newThisWeek"],
    )
    return DELIVERED


async def deliver_one(
    user: Any,
    week_start: date,
    *,
    now: datetime | None = None,
    settings: Settings | None = None,
    shared: dict[str, Any] | None = None,
) -> str:
    """Insert, build, notify, mark — for one person and one week. Returns the status, or "EXISTS"
    when another pass already holds the row."""
    now = _aware(now)
    settings = settings or get_settings()
    try:
        row = await db.weeklydigest.create(
            data={"userId": str(user.id), "weekStart": _week_value(week_start), "status": PENDING}
        )
    except UniqueViolationError:
        return "EXISTS"
    return await _send(row, user, window_for(week_start), now, settings, shared)


async def _repair(
    week_start: date,
    now: datetime,
    settings: Settings,
    tally: dict[str, Any],
    shared: dict[str, Any],
) -> None:
    # Wall-clock, not ``now``: staleness is about when a worker last touched the row, whatever week
    # the pass is reporting on.
    cutoff = datetime.now(UTC) - STALE_PENDING_AFTER
    stale = await db.weeklydigest.find_many(
        where={
            "weekStart": _week_value(week_start),
            "status": PENDING,
            "updatedAt": {"lt": cutoff},
        },
        include={"user": True},
        take=BATCH_SIZE,
    )
    for row in stale:
        claimed = await db.weeklydigest.update_many(
            where={"id": row.id, "status": PENDING, "updatedAt": {"lt": cutoff}},
            data={"updatedAt": datetime.now(UTC)},
        )
        if not claimed or row.user is None:
            continue
        try:
            status = await _send(row, row.user, window_for(week_start), now, settings, shared)
        except Exception:
            logger.exception("digest %s for account %s failed on repair", row.id, row.userId)
            tally["failed"] += 1
            continue
        tally["repaired"] += 1
        tally[_TALLY_KEY.get(status, "failed")] += 1


_TALLY_KEY = {
    DELIVERED: "delivered",
    SKIPPED_EMPTY: "empty",
    SKIPPED_OPTOUT: "optedOut",
    PENDING: "failed",
}


async def run_weekly_digests(
    now: datetime | None = None, *, force: bool = False, settings: Settings | None = None
) -> dict[str, Any]:
    """One scheduler pass. Called by the queue worker every loop; cheap outside Monday's window.

    ``force`` skips the window and the ten-minute throttle (tests); the kill switch still holds.
    """
    global _last_check
    settings = settings or get_settings()
    if not settings.weekly_digest_enabled:
        return {"ran": False, "reason": "disabled"}
    now = _aware(now)
    if not force:
        if not in_send_window(now):
            return {"ran": False, "reason": "outside window"}
        if _last_check is not None and now - _last_check < CHECK_EVERY:
            return {"ran": False, "reason": "throttled"}
    _last_check = now
    week_start = week_start_for(now)
    tally: dict[str, Any] = {
        "ran": True,
        "weekStart": week_start.isoformat(),
        "delivered": 0,
        "empty": 0,
        "optedOut": 0,
        "existing": 0,
        "failed": 0,
        "repaired": 0,
        "purged": 0,
    }

    shared: dict[str, Any] = {}
    await _repair(week_start, now, settings, tally, shared)
    done = {
        row.userId
        for row in await db.weeklydigest.find_many(where={"weekStart": _week_value(week_start)})
    }
    after: str | None = None
    while True:
        users = await eligible_users(after=after)
        if not users:
            break
        after = users[-1].id
        for user in users:
            if user.id in done:
                tally["existing"] += 1
                continue
            try:
                status = await deliver_one(
                    user, week_start, now=now, settings=settings, shared=shared
                )
            except Exception:
                logger.exception("digest for account %s failed; repaired after 15 minutes", user.id)
                tally["failed"] += 1
                continue
            if status == "EXISTS":
                tally["existing"] += 1
            else:
                tally[_TALLY_KEY.get(status, "failed")] += 1
        if len(users) < BATCH_SIZE:
            break

    purged = await db.weeklydigest.delete_many(
        where={"weekStart": {"lt": _week_value(week_start - timedelta(weeks=RETENTION_WEEKS))}}
    )
    tally["purged"] = int(purged or 0)
    if tally["delivered"] or tally["failed"] or tally["repaired"]:
        logger.info("weekly digest pass %s", tally)
    return tally


# --------------------------------------------------------------------------------------
# Reading
# --------------------------------------------------------------------------------------


def _payload(row: Any) -> dict[str, Any]:
    value = getattr(row, "payload", None)
    return dict(value) if isinstance(value, dict) else {}


def serialize_digest(row: Any) -> dict[str, Any]:
    """``GET /notifications/digests/{id}``: the page's whole content."""
    payload = _payload(row)
    created = getattr(row, "createdAt", None)
    return {
        "id": row.id,
        "weekStart": _week_iso(row.weekStart),
        "generatedAt": payload.get("generatedAt")
        or (created.isoformat() if isinstance(created, datetime) else None),
        "sections": payload.get("sections") or [],
        "coverage": payload.get("coverage"),
    }


def serialize_latest(row: Any | None) -> dict[str, Any] | None:
    """The ``latest`` block of ``GET /notifications/digest``."""
    if row is None:
        return None
    payload = _payload(row)
    coverage = payload.get("coverage") or None
    if isinstance(coverage, dict):
        coverage = {key: coverage.get(key) for key in ("label", "value", "of", "percent")}
    return {
        "id": row.id,
        "weekStart": _week_iso(row.weekStart),
        "waiting": int(payload.get("waiting") or 0),
        "overdue": int(payload.get("overdue") or 0),
        "newThisWeek": int(payload.get("newThisWeek") or 0),
        "coverage": coverage,
    }


async def preference_state(user: Any, settings: Settings | None = None) -> dict[str, Any]:
    """``GET /notifications/digest``."""
    settings = settings or get_settings()
    email_available = mailer.mail_configured(settings)
    if not await is_authority(user):
        return {
            "eligible": False,
            "enabled": False,
            "emailAvailable": email_available,
            "schedule": SCHEDULE_LABEL,
            "latest": None,
        }
    enabled = await notifications.wants_email(str(user.id), PREFERENCE)
    latest = await db.weeklydigest.find_first(
        where={"userId": str(user.id), "status": DELIVERED}, order={"weekStart": "desc"}
    )
    return {
        "eligible": True,
        "enabled": enabled,
        "emailAvailable": email_available,
        "schedule": SCHEDULE_LABEL,
        "latest": serialize_latest(latest),
    }


async def set_preference(user: Any, enabled: bool) -> None:
    await db.userpreference.upsert(
        where={"userId": str(user.id)},
        data={
            "create": {"userId": str(user.id), PREFERENCE: bool(enabled)},
            "update": {PREFERENCE: bool(enabled)},
        },
    )
