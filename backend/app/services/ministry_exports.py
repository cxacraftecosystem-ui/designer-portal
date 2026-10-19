"""MINISTRY EXPORT PACKS: the monthly schedule, the work queue, building a pack, and the digest.

On the 1st of every month (India time, at or after ``MINISTRY_EXPORTS_RUN_HOUR_IST``) the queue
worker queues one pack per scheme for the month before, builds them one per loop pass, stores the
files under ``MINISTRY_EXPORTS_PREFIX`` in the media bucket, and — once every pack of that month has
finished — e-mails each subscribed recipient one digest. A Ministry Admin, admin or the master admin
can also generate any past month, or the current month to date, on ``/ministry-exports``; that never
sends mail.

── IDEMPOTENT PER MONTH ────────────────────────────────────────────────────────────────────────

``MinistryExportPack @@unique([period, scheme])``. The scheduler inserts what is missing and treats a
unique violation as "already there"; regenerating bumps ``revision`` on the same row and storage keys
carry the revision, so an earlier file is never overwritten. The digest is deduplicated by outbox
topic ``ministry-packs:{period}``: a recipient with any message on that topic is not sent another.

── ONE PACK PER PASS ───────────────────────────────────────────────────────────────────────────

The worker box is small and a build stops the media queue while it runs, so :func:`maybe_run` claims
at most one pack per call (a compare-and-set on ``status``, as ``media_queue._lock_job``), renders
off the event loop, and caps rows at ``MINISTRY_EXPORTS_MAX_WORKSHOPS`` with a note in both files.

── STORAGE ─────────────────────────────────────────────────────────────────────────────────────

``exports/ministry/{period}/{scheme}/r{revision}/ministry-pack-{scheme-slug}-{period}.xlsx|.pdf``.
Not ``media/`` (public-read) and not ``backups/`` (put-only for the API key). Terraform gives the key
Put/Get on ``exports/*`` and expires the prefix after ``ministry_export_days`` (400); there is no
delete permission and none is needed.

── PERMISSIONS ─────────────────────────────────────────────────────────────────────────────────

Checked when a recipient is added (:func:`eligibility`), when mail is sent (again), and when a link
is minted (the route's guard). A pack is built at whole-estate scope, so only whole-estate roles —
:data:`design_workshop_oversight.OVERSIGHT_ASSIGNER_ROLES` — may receive or open one.
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
from datetime import UTC, datetime, timedelta
from typing import Any

from prisma.errors import UniqueViolationError

from app.core.config import Settings, get_settings
from app.core.db import db
from app.services import annual_plan, email_outbox, mailer, ministry_pack_sheets as sheets_mod, naming
from app.services.design_workshop_oversight import OVERSIGHT_ASSIGNER_ROLES
from app.services.ministry_pack_sheets import (
    UNSTATED,
    files_expire_on,
    parse_period,
    period_bounds,
    period_label,
    plan_year_for,
    scheme_keys,
    scheme_label,
    scheme_slug,
)
from prisma import Json

logger = logging.getLogger(__name__)

IST = mailer.IST

QUEUED = "QUEUED"
BUILDING = "BUILDING"
READY = "READY"
EMPTY = "EMPTY"
FAILED = "FAILED"
TERMINAL = (READY, EMPTY, FAILED)
ACTIVE = (QUEUED, BUILDING)

SCHEDULED = "SCHEDULED"
ON_DEMAND = "ON_DEMAND"

FILES = ("xlsx", "pdf")
MIME = {
    "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pdf": "application/pdf",
}

#: How long the bucket keeps a pack's files: Terraform's ``ministry_export_days`` default. The page
#: shows "files removed" after it; the row stays.
FILES_RETAINED_DAYS = 400
#: A pack BUILDING longer than this was abandoned by a dead worker.
STALE_BUILDING_AFTER = timedelta(minutes=30)
#: Retry backoff after the 1st, 2nd and 3rd failed attempt.
BACKOFF = (timedelta(minutes=5), timedelta(minutes=20), timedelta(minutes=60))
#: The scheduler's own checks (queue the month, recover stale rows, send the digest) run this often.
CHECK_EVERY = timedelta(minutes=10)
#: The scheduled digest goes out only in the first week of the month that owes it. A worker that was
#: down on the 1st still catches up, but someone subscribed or reactivated on the 20th is not sent
#: last month's "packs are ready" e-mail; their first digest is next month's.
DIGEST_WINDOW = timedelta(days=7)
MAX_ERROR_LENGTH = 200

#: The refusal, word for word on the web guard as well (``frontend/lib/permissions.ts``).
REFUSAL = "For Ministry Admins, admins and the master admin."
#: When a file's object is gone because the lifecycle rule removed it.
FILES_REMOVED = "This pack's files have been removed after 13 months. Generate it again."
STORAGE_UNREACHABLE = "Storage did not answer. Try again in a minute."

_next_check: datetime | None = None


def _now() -> datetime:
    return datetime.now(UTC)


def topic_for(period: str) -> str:
    return f"ministry-packs:{period}"


# --------------------------------------------------------------------------------------
# Periods
# --------------------------------------------------------------------------------------


def _shift(year: int, month: int, by: int) -> tuple[int, int]:
    index = year * 12 + (month - 1) + by
    return index // 12, index % 12 + 1


def current_period(now: datetime | None = None) -> str:
    local = (now or _now()).astimezone(IST)
    return f"{local.year:04d}-{local.month:02d}"


def previous_period(now: datetime | None = None) -> str:
    local = (now or _now()).astimezone(IST)
    year, month = _shift(local.year, local.month, -1)
    return f"{year:04d}-{month:02d}"


def period_for(now_ist: datetime, run_hour: int = 6) -> str | None:
    """The month the scheduler owes at this moment, or ``None`` before the run hour on the 1st.

    Always the month BEFORE the current one; from the run hour on the 1st through the rest of the
    month (so a worker that was down on the 1st catches up when it comes back).
    """
    local = now_ist.astimezone(IST)
    if local.day == 1 and local.hour < max(0, min(int(run_hour), 23)):
        return None
    return previous_period(local)


def digest_open(now_ist: datetime, run_hour: int = 6) -> bool:
    """Whether the scheduled digest for the month ``period_for`` owes may still be sent."""
    local = now_ist.astimezone(IST)
    hour = max(0, min(int(run_hour), 23))
    opens = local.replace(day=1, hour=hour, minute=0, second=0, microsecond=0)
    return opens <= local < opens + DIGEST_WINDOW


def is_future(period: str, now: datetime | None = None) -> bool:
    return period > current_period(now)


# --------------------------------------------------------------------------------------
# Eligibility
# --------------------------------------------------------------------------------------


def role_of(user: Any) -> str:
    role = getattr(user, "role", None)
    return str(getattr(role, "value", role) or "")


async def eligibility(users: list[Any]) -> dict[str, str | None]:
    """``{user id: None when eligible, else a short reason}`` — role, address and platform access."""
    from app.services.access_roster import barred_among

    barred = await barred_among([str(u.email) for u in users if getattr(u, "email", None)])
    out: dict[str, str | None] = {}
    for user in users:
        email = str(getattr(user, "email", "") or "").strip()
        if role_of(user) not in OVERSIGHT_ASSIGNER_ROLES:
            out[str(user.id)] = "Role no longer qualifies"
        elif not email:
            out[str(user.id)] = "No e-mail address"
        elif email.lower() in barred:
            out[str(user.id)] = "Platform access suspended"
        else:
            out[str(user.id)] = None
    return out


# --------------------------------------------------------------------------------------
# Queue
# --------------------------------------------------------------------------------------


async def ensure_scheduled(period: str, now: datetime | None = None) -> int:
    """Queue a SCHEDULED pack for every scheme of ``period`` that has none. Returns how many.

    A row that already exists is left alone — that is the idempotency — with one exception: a pack
    built while the month was still running (``monthToDate``) is regenerated once the month is over,
    so the scheduled pack is always the whole month.
    """
    moment = now or _now()
    existing = {
        row.scheme: row for row in await db.ministryexportpack.find_many(where={"period": period})
    }
    created = 0
    for scheme in scheme_keys():
        row = existing.get(scheme)
        if row is None:
            try:
                await db.ministryexportpack.create(
                    data={
                        "period": period,
                        "scheme": scheme,
                        "trigger": SCHEDULED,
                        "status": QUEUED,
                        "runAfter": moment,
                    }
                )
                created += 1
            except UniqueViolationError:
                pass  # another worker or an on-demand request got there first: already there
        elif row.monthToDate and row.status in (READY, EMPTY):
            # Not FAILED: a failed month-to-date row keeps its old flag, and requeuing it here would
            # retry it every ten minutes for the rest of the month.
            await _requeue(row, trigger=SCHEDULED, requested_by=None, now=moment)
    return created


async def _requeue(
    row: Any, *, trigger: str, requested_by: str | None, now: datetime | None = None
) -> bool:
    """Regenerate a finished pack: same row, next revision, files cleared until the new ones land."""
    updated = await db.ministryexportpack.update_many(
        where={"id": row.id, "revision": row.revision, "status": {"in": list(TERMINAL)}},
        data={
            "revision": int(row.revision) + 1,
            "status": QUEUED,
            "trigger": trigger,
            "requestedById": requested_by,
            "attempts": 0,
            "runAfter": now or _now(),
            "lockedAt": None,
            "lockedBy": None,
            "error": None,
            "finishedAt": None,
            "xlsxKey": None,
            "xlsxBytes": None,
            "xlsxSha256": None,
            "pdfKey": None,
            "pdfBytes": None,
            "pdfSha256": None,
        },
    )
    return bool(updated)


async def request_packs(period: str, schemes: list[str], user_id: str | None) -> list[Any]:
    """On-demand generation for ``period``. Never sends mail. Returns the rows, in scheme order.

    No row: one is created. QUEUED or BUILDING: returned unchanged. READY, EMPTY or FAILED:
    regenerated (revision bumped).
    """
    wanted = [key for key in scheme_keys() if not schemes or key in schemes]
    out: list[Any] = []
    for scheme in wanted:
        row = await db.ministryexportpack.find_unique(
            where={"period_scheme": {"period": period, "scheme": scheme}}
        )
        if row is None:
            try:
                row = await db.ministryexportpack.create(
                    data={
                        "period": period,
                        "scheme": scheme,
                        "trigger": ON_DEMAND,
                        "status": QUEUED,
                        "requestedById": user_id,
                    }
                )
            except UniqueViolationError:
                row = await db.ministryexportpack.find_unique(
                    where={"period_scheme": {"period": period, "scheme": scheme}}
                )
        elif row.status in TERMINAL:
            await _requeue(row, trigger=ON_DEMAND, requested_by=user_id)
            row = await db.ministryexportpack.find_unique(where={"id": row.id})
        if row is not None:
            out.append(row)
    return out


async def recover_stale(now: datetime | None = None) -> int:
    """A pack BUILDING for over 30 minutes goes back to QUEUED, or to FAILED once attempts are spent."""
    moment = now or _now()
    stale = await db.ministryexportpack.find_many(
        where={"status": BUILDING, "lockedAt": {"lt": moment - STALE_BUILDING_AFTER}}, take=50
    )
    recovered = 0
    for row in stale:
        attempts = int(row.attempts or 0)
        if attempts >= max(int(row.maxAttempts or 1), 1):
            data: dict[str, Any] = {
                "status": FAILED,
                "error": "The worker stopped while building, on the last attempt.",
                "finishedAt": moment,
                "lockedAt": None,
                "lockedBy": None,
            }
        else:
            data = {
                "status": QUEUED,
                "runAfter": moment + BACKOFF[min(max(attempts - 1, 0), len(BACKOFF) - 1)],
                "lockedAt": None,
                "lockedBy": None,
            }
        if await db.ministryexportpack.update_many(
            where={"id": row.id, "status": BUILDING, "revision": row.revision}, data=data
        ):
            recovered += 1
    return recovered


async def claim(row: Any, worker_id: str, now: datetime | None = None) -> Any | None:
    """Take one QUEUED pack, atomically. Of two claimers exactly one gets the row."""
    moment = now or _now()
    claimed = await db.ministryexportpack.update_many(
        where={"id": row.id, "status": QUEUED, "revision": row.revision},
        data={
            "status": BUILDING,
            "lockedAt": moment,
            "lockedBy": worker_id,
            "attempts": int(row.attempts or 0) + 1,
        },
    )
    if claimed != 1:
        return None
    return await db.ministryexportpack.find_unique(where={"id": row.id})


async def process_next_pack(
    settings: Settings | None = None,
    *,
    worker_id: str = "queue-service",
    now: datetime | None = None,
) -> Any | None:
    """Claim and build at most ONE due pack. Returns the row as it was left, or ``None``."""
    settings = settings or get_settings()
    moment = now or _now()
    row = await db.ministryexportpack.find_first(
        where={"status": QUEUED, "runAfter": {"lte": moment}}, order={"runAfter": "asc"}
    )
    if row is None:
        return None
    pack = await claim(row, worker_id, moment)
    if pack is None:
        return None
    try:
        await build_pack(pack, settings)
    except Exception as exc:  # one bad pack must never take the worker down
        logger.exception(
            "ministry exports: pack %s (%s %s) failed", pack.id, pack.period, pack.scheme
        )
        await _fail_or_retry(pack, f"{type(exc).__name__}: could not build the pack", moment)
    return await db.ministryexportpack.find_unique(where={"id": pack.id})


async def _fail_or_retry(pack: Any, error: str, now: datetime) -> None:
    attempts = int(pack.attempts or 0)
    message = error[:MAX_ERROR_LENGTH]
    if attempts >= max(int(pack.maxAttempts or 1), 1):
        data: dict[str, Any] = {
            "status": FAILED,
            "error": message,
            "finishedAt": now,
            "lockedAt": None,
            "lockedBy": None,
        }
    else:
        data = {
            "status": QUEUED,
            "error": message,
            "runAfter": now + BACKOFF[min(max(attempts - 1, 0), len(BACKOFF) - 1)],
            "lockedAt": None,
            "lockedBy": None,
        }
    await db.ministryexportpack.update_many(
        where={"id": pack.id, "status": BUILDING, "revision": pack.revision}, data=data
    )


# --------------------------------------------------------------------------------------
# Building
# --------------------------------------------------------------------------------------


def storage_key(settings: Settings, period: str, scheme: str, revision: int, file: str) -> str:
    prefix = (settings.ministry_exports_prefix or "exports/ministry/").strip("/") + "/"
    return f"{prefix}{period}/{scheme}/r{int(revision)}/{file_name(period, scheme, file)}"


def file_name(period: str, scheme: str, file: str) -> str:
    """``DesignWorkshop_Ministry_2026-09_Skill-Upgradation_Monthly-Report.xlsx`` (services/naming.py)."""
    return naming.export_filename(
        "Ministry", "Monthly-Report", file, period=period, subject=scheme_slug(scheme).replace("-", " ")
    )


def _workshop_dict(record: Any) -> dict[str, Any]:
    order = getattr(record, "sanctionOrder", None)
    fields = (
        "id",
        "title",
        "status",
        "workshopCode",
        "workshopKind",
        "scheme",
        "craftName",
        "clusterName",
        "state",
        "district",
        "venue",
        "startDate",
        "endDate",
        "designerName",
        "implementingAgency",
        "sponsor",
        "createdAt",
        "updatedAt",
        "deletedAt",
        "lastHandedInAt",
        "approvedAt",
        "handedOnAt",
        "handedOnTo",
        "handedOnExportId",
        "submissionRound",
    )
    row = {name: getattr(record, name, None) for name in fields}
    row["status"] = str(getattr(row["status"], "value", row["status"]) or "")
    if order is not None:
        row["sanctionOrderNo"] = order.sanctionOrderNo
        row["sanctionOrderDate"] = order.sanctionOrderDate
        row["sanctionAmount"] = order.sanctionAmount
    return row


def _group_value(row: Any, aggregate: str, field: str) -> Any:
    block = row.get(aggregate) if isinstance(row, dict) else None
    if isinstance(block, dict):
        return block.get(field)
    return None


async def _load(pack: Any, settings: Settings) -> dict[str, Any]:
    """Read everything one pack needs. Whole estate; soft-deleted workshops excluded."""
    from app.services.annual_plan_xlsx import plan_year_label
    from app.services.ministry_dashboard import PROGRESS_SCORE_CAP, beneficiary_counts, progress_for

    cap = max(int(settings.ministry_exports_max_workshops or 1), 1)
    kind = None if pack.scheme == UNSTATED else pack.scheme
    records = await db.designworkshop.find_many(
        where={"deletedAt": None, "workshopKind": kind},
        include={"sanctionOrder": True},
        order=[{"state": "asc"}, {"createdAt": "asc"}],
        take=cap + 1,
    )
    truncated = len(records) > cap
    workshops = [_workshop_dict(record) for record in records[:cap]]
    ids = [str(row["id"]) for row in workshops]

    participants = await beneficiary_counts(ids) if ids else {}
    for row in workshops:
        row["participants"] = participants.get(str(row["id"]), 0)

    progress: dict[str, dict[str, Any]] = {}
    for start in range(0, len(ids), PROGRESS_SCORE_CAP):
        progress.update(await progress_for(ids[start : start + PROGRESS_SCORE_CAP]))

    feedback: dict[str, dict[str, Any]] = {}
    if ids:
        groups = await db.dwinspectionfeedback.group_by(
            by=["designWorkshopId", "sentBack"],
            where={"designWorkshopId": {"in": ids}},
            count=True,
            max={"createdAt": True},
        )
        for group in groups:
            workshop_id = str(group.get("designWorkshopId"))
            tally = feedback.setdefault(workshop_id, {"notes": 0, "sentBack": 0, "lastAt": None})
            count = group.get("_count")
            number = int(count.get("_all") or 0) if isinstance(count, dict) else int(count or 0)
            tally["notes"] += number
            if group.get("sentBack"):
                tally["sentBack"] += number
            last = _group_value(group, "_max", "createdAt")
            if isinstance(last, str):
                last = datetime.fromisoformat(last)
            if last is not None and (tally["lastAt"] is None or last > tally["lastAt"]):
                tally["lastAt"] = last

    export_ids = sorted(
        {str(row["handedOnExportId"]) for row in workshops if row.get("handedOnExportId")}
    )
    exports: dict[str, dict[str, Any]] = {}
    if export_ids:
        for export in await db.dwreportexport.find_many(where={"id": {"in": export_ids}}):
            exports[str(export.id)] = {
                "fileName": export.fileName,
                "format": export.format,
                "generatedAt": export.generatedAt,
                "checksumSha256": export.checksumSha256,
            }

    plan_year = plan_year_for(pack.period)
    plan = [
        {
            "workshopNo": entry.workshopNo,
            "plannedTitle": entry.plannedTitle,
            "state": entry.state,
            "district": entry.district,
            "plannedStartDate": entry.plannedStartDate,
            "plannedEndDate": entry.plannedEndDate,
            "implementingAgency": entry.implementingAgency,
            "designWorkshopId": entry.designWorkshopId,
        }
        for entry in await annual_plan.live_entries_for_year(plan_year, kind, take=cap)
    ]
    start, end = period_bounds(pack.period, IST)
    return {
        "period": pack.period,
        "periodLabel": period_label(pack.period),
        "scheme": pack.scheme,
        "schemeLabel": scheme_label(pack.scheme),
        "revision": pack.revision,
        "periodStart": start,
        "periodEnd": end,
        "truncated": truncated,
        "cap": cap,
        "workshops": workshops,
        "progress": progress,
        "feedback": feedback,
        "exports": exports,
        "annualPlan": plan,
        "planYearLabel": plan_year_label(plan_year),
    }


def _render(data: dict[str, Any]) -> tuple[bytes, bytes, dict[str, Any]]:
    """Both files and the counts. Synchronous; called in a thread."""
    from app.services.ministry_pack_pdf import build_pack_pdf
    from app.services.xlsx_report import build_report_workbook

    sheets, counts, states = sheets_mod.build_sheets(data)
    title = f"Ministry pack — {data['schemeLabel']} — {data['periodLabel']}"
    xlsx = build_report_workbook(sheets, title, overview=False)
    plan = data.get("annualPlan") or []
    pdf = build_pack_pdf(
        {
            "periodLabel": data["periodLabel"],
            "schemeLabel": data["schemeLabel"],
            "snapshotAt": data["snapshotAt"],
            "monthToDate": data["monthToDate"],
            "revision": data["revision"],
            "counts": counts,
            "byState": states,
            "cap": data["cap"],
            "planYearLabel": data.get("planYearLabel"),
            "planned": len(plan),
            "promoted": sum(1 for entry in plan if entry.get("designWorkshopId")),
        }
    )
    return xlsx, pdf, counts


async def build_pack(pack: Any, settings: Settings | None = None) -> None:
    """Build one claimed pack and mark it READY (or EMPTY). Raises on failure; the caller retries.

    The files are stored BEFORE the row says READY, and the READY write is a compare-and-set on the
    claim, so no READY row ever points at a file that was not written.
    """
    from app.services import s3
    from app.services.stage_schema import registry_version

    settings = settings or get_settings()
    snapshot = _now()
    data = await _load(pack, settings)
    data["snapshotAt"] = snapshot
    data["monthToDate"] = pack.period >= current_period(snapshot)
    claim_where = {"id": pack.id, "status": BUILDING, "revision": pack.revision}

    if not data["workshops"]:
        counts = sheets_mod.compute_counts(
            [], data["periodStart"], data["periodEnd"], truncated=False
        )
        await db.ministryexportpack.update_many(
            where=claim_where,
            data={
                "status": EMPTY,
                "counts": Json(counts),
                "snapshotAt": snapshot,
                "monthToDate": data["monthToDate"],
                "schemaVersion": registry_version(),
                "finishedAt": _now(),
                "lockedAt": None,
                "lockedBy": None,
                "error": None,
            },
        )
        return

    xlsx, pdf, counts = await asyncio.to_thread(_render, data)
    stored: dict[str, Any] = {}
    for file, body in (("xlsx", xlsx), ("pdf", pdf)):
        key = storage_key(settings, pack.period, pack.scheme, pack.revision, file)
        await asyncio.to_thread(s3.put_object_bytes, key, body, mime_type=MIME[file])
        stored[f"{file}Key"] = key
        stored[f"{file}Bytes"] = len(body)
        stored[f"{file}Sha256"] = hashlib.sha256(body).hexdigest()
    await db.ministryexportpack.update_many(
        where=claim_where,
        data={
            **stored,
            "status": READY,
            "counts": Json(counts),
            "snapshotAt": snapshot,
            "monthToDate": data["monthToDate"],
            "schemaVersion": registry_version(),
            "finishedAt": _now(),
            "lockedAt": None,
            "lockedBy": None,
            "error": None,
        },
    )
    logger.info(
        "ministry exports: pack %s (%s %s r%s) ready",
        pack.id,
        pack.period,
        pack.scheme,
        pack.revision,
    )


# --------------------------------------------------------------------------------------
# Digest
# --------------------------------------------------------------------------------------


async def send_period_digest(period: str, settings: Settings | None = None) -> int:
    """One e-mail per eligible recipient, once every pack of ``period`` has finished. Returns how many
    were queued. Recipients with any message on the period's topic already are skipped."""
    settings = settings or get_settings()
    if not mailer.mail_configured(settings):
        return 0
    packs = await db.ministryexportpack.find_many(where={"period": period})
    if len(packs) < len(scheme_keys()) or any(row.status not in TERMINAL for row in packs):
        return 0
    ready = {
        row.scheme: int((row.counts or {}).get("workshops") or 0)
        if isinstance(row.counts, dict)
        else 0
        for row in packs
        if row.status == READY
    }
    ready = {scheme: count for scheme, count in ready.items() if count > 0}
    if not ready:
        return 0

    recipients = await db.ministryexportrecipient.find_many(
        where={"active": True}, include={"user": True}
    )
    users = [row.user for row in recipients if row.user is not None]
    if not users:
        return 0
    verdicts = await eligibility(users)
    topic = topic_for(period)
    already = await email_outbox.latest_by_recipient(topic, [str(u.id) for u in users])
    queued = 0
    for row in recipients:
        user = row.user
        if user is None or verdicts.get(str(user.id)) is not None or str(user.id) in already:
            continue
        chosen = [
            key for key in scheme_keys() if key in ready and (not row.schemes or key in row.schemes)
        ]
        if not chosen:
            continue
        params = {
            "recipientName": user.name,
            "periodLabel": period_label(period),
            "schemes": [{"label": scheme_label(key), "workshops": ready[key]} for key in chosen],
            "url": mailer.app_url(f"/ministry-exports?period={period}", settings),
        }
        if await email_outbox.enqueue(
            mailer.MINISTRY_PACKS_READY,
            to_address=str(user.email),
            recipient_id=str(user.id),
            params=params,
            settings=settings,
            topic=topic,
        ):
            queued += 1
    return queued


# --------------------------------------------------------------------------------------
# The worker's entry point
# --------------------------------------------------------------------------------------


async def maybe_run(
    settings: Settings | None = None, now: datetime | None = None, *, force: bool = False
) -> None:
    """Called on every pass of the queue worker. A no-op while ``MINISTRY_EXPORTS_ENABLED`` is off.

    Every pass: build at most one due pack (so an on-demand request is picked up within a pass).
    Every ten minutes (or with ``force``): recover stale builds, queue the month the schedule owes,
    and send its digest when it is complete, if still inside ``DIGEST_WINDOW``.
    """
    global _next_check
    settings = settings or get_settings()
    if not settings.ministry_exports_enabled:
        return
    moment = now or _now()
    owed: str | None = None
    if force or _next_check is None or moment >= _next_check:
        _next_check = moment + CHECK_EVERY
        await recover_stale(moment)
        owed = period_for(moment, settings.ministry_exports_run_hour_ist)
        if owed is not None:
            await ensure_scheduled(owed, moment)
    built = await process_next_pack(settings, now=moment)
    if owed is None and built is not None and built.status in TERMINAL:
        candidate = period_for(moment, settings.ministry_exports_run_hour_ist)
        if candidate == built.period:
            owed = candidate
    if owed is not None and digest_open(moment, settings.ministry_exports_run_hour_ist):
        await send_period_digest(owed, settings)


def reset_schedule_gate() -> None:
    """For tests: forget when the scheduler last checked."""
    global _next_check
    _next_check = None


# --------------------------------------------------------------------------------------
# Payloads
# --------------------------------------------------------------------------------------


def _iso(value: Any) -> str | None:
    if not isinstance(value, datetime):
        return None
    return (value if value.tzinfo else value.replace(tzinfo=UTC)).isoformat()


def pack_payload(
    row: Any, *, mail: dict[str, int] | None = None, requested_by: Any = None
) -> dict[str, Any]:
    counts = row.counts if isinstance(row.counts, dict) else None
    files = None
    if row.status == READY and row.xlsxKey and row.pdfKey:
        files = {
            "xlsx": {"name": file_name(row.period, row.scheme, "xlsx"), "sizeBytes": row.xlsxBytes},
            "pdf": {"name": file_name(row.period, row.scheme, "pdf"), "sizeBytes": row.pdfBytes},
        }
    return {
        "id": row.id,
        "period": row.period,
        "periodLabel": period_label(row.period),
        "scheme": row.scheme,
        "schemeLabel": scheme_label(row.scheme),
        "revision": row.revision,
        "trigger": row.trigger,
        "status": row.status,
        "monthToDate": bool(row.monthToDate),
        "snapshotAt": _iso(row.snapshotAt),
        "finishedAt": _iso(row.finishedAt),
        "counts": counts,
        "files": files,
        "filesExpireOn": files_expire_on(row.finishedAt, FILES_RETAINED_DAYS) if files else None,
        "requestedBy": (
            {"id": requested_by.id, "name": requested_by.name} if requested_by is not None else None
        ),
        "error": row.error if row.status == FAILED else None,
        "mail": mail or {"queued": 0, "sent": 0, "failed": 0},
    }


async def mail_tallies(periods: list[str]) -> dict[str, dict[str, int]]:
    """``{period: {queued, sent, failed}}`` from the digest topic, one query for the page."""
    by_topic = await email_outbox.latest_by_topics([topic_for(p) for p in periods])
    out: dict[str, dict[str, int]] = {}
    for period in periods:
        tally = {"queued": 0, "sent": 0, "failed": 0}
        for status in (by_topic.get(topic_for(period)) or {}).values():
            key = str(status.get("status") or "").lower()
            if key in tally:
                tally[key] += 1
        out[period] = tally
    return out


async def payloads(rows: list[Any]) -> list[dict[str, Any]]:
    periods = sorted({row.period for row in rows})
    tallies = await mail_tallies(periods) if periods else {}
    requester_ids = sorted({row.requestedById for row in rows if row.requestedById})
    requesters = (
        {u.id: u for u in await db.user.find_many(where={"id": {"in": requester_ids}})}
        if requester_ids
        else {}
    )
    return [
        pack_payload(
            row, mail=tallies.get(row.period), requested_by=requesters.get(row.requestedById)
        )
        for row in rows
    ]


def scheme_order(row: Any) -> int:
    keys = scheme_keys()
    return keys.index(row.scheme) if row.scheme in keys else len(keys)


def is_period(value: str) -> bool:
    return parse_period(value) is not None
