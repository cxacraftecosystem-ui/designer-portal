"""The Ministry dashboard's stage-data export (owner decision C15, 2026-10-17).

One spreadsheet, a sheet per record type (the entity registry ``design_workshop_data.tables()``),
across the workshops the requester's dashboard filters and scope select — with every identity
number masked — built in the BACKGROUND by the queue worker, because a few hundred workshops of
stage rows is minutes of openpyxl on a box the API shares.

WHO. The Ministry dashboard's readers (``deps.MINISTRY_DASHBOARD_ROLES``): Assistant and Regional
Directors over the workshops they were posted to, the Ministry Admin and the master admin over the
estate. The scope is re-read from the requester's account when the job RUNS, so a posting removed
between the request and the run narrows the file rather than leaking it.

WHAT IS LEFT OUT, AND SAID ON THE README SHEET. Media are cells of ids (never bytes or URLs, the
registry's own export rule). A designer's own custom questions are withheld: their columns are
defined per workshop and nothing here can tell which of them holds an identity number. Every field
the registry marks as an Aadhaar or Pehchan number, or whose key names one, is masked to its last
four characters — including values a designer typed in full.

WHERE THE FILE LIVES. In the ``MinistryDataExport`` row, for :data:`EXPORT_TTL_DAYS`, then purged by
the worker. Only the account that asked may download it.
"""

from __future__ import annotations

import logging
import re
from datetime import UTC, datetime, timedelta
from io import BytesIO
from typing import Any

from app.core.db import db
from app.services import design_workshop_data as dw
from app.services.records import mask_identity_number
from app.services.stage_schema import TextFormat, stages

log = logging.getLogger(__name__)

#: How long a finished file is kept before the worker purges it.
EXPORT_TTL_DAYS = 7
#: How many workshops one export reads. Past it the README says the file stopped.
MAX_EXPORT_WORKSHOPS = 1000
#: How many stage rows one export writes, across every sheet.
MAX_EXPORT_ROWS = 200_000
#: How many exports one account may have waiting or running at once.
MAX_PENDING_PER_USER = 3
#: A RUNNING job older than this is assumed to belong to a worker that died, and is re-queued.
STALE_AFTER = timedelta(minutes=30)
MAX_ATTEMPTS = 3

QUEUED, RUNNING, READY, FAILED, EXPIRED = "QUEUED", "RUNNING", "READY", "FAILED", "EXPIRED"

_IDENTITY_KEY = re.compile(r"aadhaar|pehchan|cardno|identitynumber", re.IGNORECASE)


def identity_columns() -> frozenset[str]:
    """Every ``entityKey.fieldKey`` column whose value is an identity number, to be masked."""
    keys: set[str] = set()
    for stage in stages():
        for entity in stage.entities:
            for spec in entity.fields:
                fmt = getattr(spec, "text_format", None)
                if (
                    getattr(spec, "store_masked", False)
                    or fmt in (TextFormat.AADHAAR, TextFormat.PEHCHAN)
                    or _IDENTITY_KEY.search(spec.key)
                ):
                    keys.add(f"{entity.key}.{spec.key}")
    return frozenset(keys)


def mask_row(row: dict[str, str], masked: frozenset[str]) -> dict[str, str]:
    """The row with every identity cell masked. An empty cell stays empty."""
    out = dict(row)
    for key in masked.intersection(out):
        if out[key]:
            out[key] = str(mask_identity_number(out[key]) or "")
    return out


def _sheet_title(title: str, used: set[str]) -> str:
    clean = re.sub(r"[\[\]\*\?/\\:]", " ", title).strip()[:31] or "Sheet"
    candidate, n = clean, 2
    while candidate.lower() in used:
        suffix = f" ({n})"
        candidate = clean[: 31 - len(suffix)] + suffix
        n += 1
    used.add(candidate.lower())
    return candidate


def build_workbook(
    workshops: list[tuple[Any, list[Any]]],
    *,
    filters: dict[str, Any],
    scope_label: str,
    workshops_total: int,
    generated_at: datetime,
) -> tuple[bytes, int]:
    """The .xlsx and the number of stage rows it holds. Pure: no database.

    ``workshops`` is ``[(workshop record, its live stage entries)]``. One sheet per registry entity
    that has at least one row, in registry order, each row carrying the workshop identity columns.
    """
    from openpyxl import Workbook

    masked = identity_columns()
    per_entity: dict[str, list[dict[str, str]]] = {}
    withheld_custom = 0
    unknown = 0
    rows_written = 0
    stopped = False
    for record, entries in workshops:
        # No custom-section definition is passed, so a designer's own questions come back from
        # `flatten` as unreadable rows — counted below as withheld, never as "newer form versions".
        grouped, unknown_entities = dw.flatten(record, entries)
        unknown += sum(
            item.rows for item in unknown_entities if item.entity_key != dw.CUSTOM_ENTITY_KEY
        )
        for key, rows in grouped.items():
            room = MAX_EXPORT_ROWS - rows_written
            if room <= 0:
                stopped = True
                break
            kept = rows[:room]
            stopped = stopped or len(kept) < len(rows)
            per_entity.setdefault(key, []).extend(mask_row(row, masked) for row in kept)
            rows_written += len(kept)
        withheld_custom += sum(
            1 for entry in entries if getattr(entry, "entityKey", None) == dw.CUSTOM_ENTITY_KEY
        )

    wb = Workbook(write_only=True)
    used: set[str] = set()
    readme = wb.create_sheet(_sheet_title("About this file", used))
    readme.append(["Ministry stage-data export"])
    readme.append(["Generated", generated_at.strftime("%d %b %Y %H:%M UTC")])
    readme.append(["Scope", scope_label])
    narrowing = ", ".join(f"{key}: {value}" for key, value in filters.items() if value) or "None"
    readme.append(["Filters", narrowing])
    readme.append(["Workshops", f"{len(workshops)} of {workshops_total}"])
    readme.append(["Stage rows", rows_written])
    readme.append(["Identity numbers", "Masked to the last four characters."])
    readme.append(["Photographs and files", "Listed by id only."])
    if withheld_custom:
        readme.append(["Designers' own questions", f"{withheld_custom} rows not included."])
    if unknown:
        readme.append(["Newer form versions", f"{unknown} rows not shown."])
    if len(workshops) < workshops_total:
        readme.append(["Stopped", f"Only the first {len(workshops)} workshops are included."])
    if stopped:
        readme.append(["Stopped", f"Only the first {MAX_EXPORT_ROWS} stage rows are included."])

    identity_cols = [column.key for column in dw.WORKSHOP_IDENTITY_COLUMNS]
    identity_labels = [column.label for column in dw.WORKSHOP_IDENTITY_COLUMNS]
    for table in dw.tables():
        rows = per_entity.get(table.entity_key)
        if not rows:
            continue
        sheet = wb.create_sheet(_sheet_title(table.title, used))
        columns = [column.key for column in table.columns]
        labels = [
            f"{column.label} ({column.unit})" if column.unit else column.label
            for column in table.columns
        ]
        sheet.append([*identity_labels, "Row", *labels])
        for row in rows:
            sheet.append(
                [
                    *(row.get(key, "") for key in identity_cols),
                    row.get("entry.ordinal", ""),
                    *(row.get(key, "") for key in columns),
                ]
            )
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue(), rows_written


def export_filename(at: datetime) -> str:
    return f"ministry-stage-data-{at.strftime('%Y%m%d-%H%M')}.xlsx"


def job_payload(row: Any) -> dict[str, Any]:
    """One export as the dashboard lists it. Never the file itself."""

    def iso(value: Any) -> str | None:
        return value.isoformat() if isinstance(value, datetime) else None

    return {
        "id": row.id,
        "status": row.status,
        "filters": row.filters or {},
        "workshopCount": row.workshopCount,
        "rowCount": row.rowCount,
        "fileName": row.fileName,
        "fileSizeBytes": row.fileSizeBytes,
        "error": row.error,
        "createdAt": iso(row.createdAt),
        "finishedAt": iso(row.finishedAt),
        "expiresAt": iso(row.expiresAt),
    }


async def _claim(row: Any, worker_id: str) -> bool:
    now = datetime.now(UTC)
    claimed = await db.ministrydataexport.update_many(
        where={"id": row.id, "status": QUEUED},
        data={
            "status": RUNNING,
            "lockedAt": now,
            "lockedBy": worker_id,
            "startedAt": now,
            "attempts": {"increment": 1},
        },
    )
    return bool(claimed)


async def run_export(row: Any) -> None:
    """Build one claimed export and store the file, or record why it failed."""
    from app.api.routes.ministry_dashboard import _design_where
    from app.core.deps import can_see_ministry_dashboard
    from app.services import ministry_dashboard as register

    user = await db.user.find_unique(where={"id": row.requestedById}) if row.requestedById else None
    if user is None or not can_see_ministry_dashboard(user):
        await db.ministrydataexport.update(
            where={"id": row.id},
            data={
                "status": FAILED,
                "error": "The account that asked for this export can no longer read the dashboard.",
                "finishedAt": datetime.now(UTC),
                "lockedAt": None,
            },
        )
        return
    filters = dict(row.filters or {})
    where = _design_where(
        user,
        filters.get("search"),
        filters.get("standing"),
        filters.get("attention"),
        filters.get("state"),
    )
    if filters.get("attention") == "setupIncomplete":
        ids, _capped = await register.setup_incomplete_ids(
            _design_where(
                user, filters.get("search"), filters.get("standing"), None, filters.get("state")
            )
        )
        where.setdefault("AND", []).append({"id": {"in": ids}})
    total = await db.designworkshop.count(where=where)
    records = await db.designworkshop.find_many(
        where=where, order=[{"updatedAt": "desc"}, {"id": "asc"}], take=MAX_EXPORT_WORKSHOPS
    )
    ids = [str(record.id) for record in records]
    entries = (
        await db.dwstageentry.find_many(
            where={"designWorkshopId": {"in": ids}, "deletedAt": None},
            order=[{"designWorkshopId": "asc"}, {"ordinal": "asc"}],
        )
        if ids
        else []
    )
    by_workshop: dict[str, list[Any]] = {}
    for entry in entries:
        by_workshop.setdefault(str(entry.designWorkshopId), []).append(entry)
    now = datetime.now(UTC)
    payload, rows = build_workbook(
        [(record, by_workshop.get(str(record.id), [])) for record in records],
        filters=filters,
        scope_label=register.scope_label(user),
        workshops_total=total,
        generated_at=now,
    )
    from prisma.fields import Base64

    await db.ministrydataexport.update(
        where={"id": row.id},
        data={
            "status": READY,
            "content": Base64.encode(payload),
            "fileName": export_filename(now),
            "fileSizeBytes": len(payload),
            "workshopCount": len(records),
            "rowCount": rows,
            "finishedAt": now,
            "expiresAt": now + timedelta(days=EXPORT_TTL_DAYS),
            "lockedAt": None,
            "error": None,
        },
    )


async def recover_and_purge(now: datetime | None = None) -> None:
    """Re-queue exports a dead worker left RUNNING; drop files past their keep-until date."""
    now = now or datetime.now(UTC)
    await db.ministrydataexport.update_many(
        where={
            "status": RUNNING,
            "lockedAt": {"lt": now - STALE_AFTER},
            "attempts": {"lt": MAX_ATTEMPTS},
        },
        data={"status": QUEUED, "lockedAt": None, "lockedBy": None},
    )
    await db.ministrydataexport.update_many(
        where={
            "status": RUNNING,
            "lockedAt": {"lt": now - STALE_AFTER},
            "attempts": {"gte": MAX_ATTEMPTS},
        },
        data={
            "status": FAILED,
            "error": "The export did not finish. Ask for it again.",
            "lockedAt": None,
        },
    )
    await db.ministrydataexport.update_many(
        where={"status": READY, "expiresAt": {"lt": now}},
        data={"content": None, "status": EXPIRED},
    )


async def process_next_ministry_exports(*, worker_id: str, limit: int = 1) -> int:
    """The worker's turn: recover, purge, then build up to ``limit`` queued exports. Returns built."""
    await recover_and_purge()
    queued = await db.ministrydataexport.find_many(
        where={"status": QUEUED}, order={"createdAt": "asc"}, take=limit
    )
    built = 0
    for row in queued:
        if not await _claim(row, worker_id):
            continue
        try:
            await run_export(row)
            built += 1
        except Exception as exc:  # one bad export must never stop the worker
            log.exception("ministry export %s failed", row.id)
            await db.ministrydataexport.update(
                where={"id": row.id},
                data={
                    "status": FAILED,
                    "error": f"The export could not be built ({type(exc).__name__}). Ask for it again.",
                    "finishedAt": datetime.now(UTC),
                    "lockedAt": None,
                },
            )
    return built
