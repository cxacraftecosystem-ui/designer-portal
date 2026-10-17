"""What makes a handed-in report quicker to read for the people who receive it (2026-10-17).

Five small aids, each additive to an existing read or list:

* **Note freshness (A19).** Each correction note says whether the stage it names was edited after the
  note was filed. Compared against the stage rows' own timestamps — no copy of the data is needed.
* **"Checked, no corrections" (C5).** One verdict per inspector per round, shown to the approving
  authority and usable as a queue filter. Never required for approval (owner decision). Filing a
  note in the same round removes it, and a verdict is refused while the inspector has notes on the
  round — "no corrections" and a correction cannot both stand.
* **Round snapshots (C13).** The stage data as handed in, per round, so "Changed since round N" can
  say what moved. Kept for the life of the workshop plus one year after it is deleted (owner
  decision); :func:`purge_expired_snapshots` runs from the worker loops.
* **Queue readiness (B4).** Per row: how complete, how many notes this round, how many inspectors
  have cleared it. Three reads for a whole page.
* **Report reuse (A22).** The fingerprint an unchanged report is recognised by, so the approving
  authority's download serves the stored file again instead of rebuilding all 22 stages.

Nothing here writes a stage, and nothing here is reachable without the caller's own loader having
admitted the reader first.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.services.concurrency import gather_reads
from prisma import Json

logger = logging.getLogger(__name__)


def _moment(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, str) and value.strip():
        try:
            parsed = datetime.fromisoformat(value.strip())
        except ValueError:
            return None
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)
    return None


# ── NOTE FRESHNESS (A19) ─────────────────────────────────────────────────────────────────────────


async def stage_edit_times(workshop_id: str, entries: list[Any]) -> dict[str, datetime]:
    """The last moment each stage was written: a live row's ``updatedAt`` or a removed row's
    ``deletedAt``. ``entries`` are the live rows the caller already holds."""
    out: dict[str, datetime] = {}

    def note(stage_key: Any, moment: Any) -> None:
        at = _moment(moment)
        if not stage_key or at is None:
            return
        key = str(stage_key)
        if key not in out or at > out[key]:
            out[key] = at

    for row in entries:
        note(getattr(row, "stageKey", None), getattr(row, "updatedAt", None))
    removed = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "deletedAt": {"not": None}}
    )
    for row in removed:
        note(getattr(row, "stageKey", None), getattr(row, "deletedAt", None))
    return out


def annotate_freshness(
    rows: list[dict[str, Any]], edits: dict[str, datetime]
) -> list[dict[str, Any]]:
    """Add ``editedSince`` to each note: true when the stage it names (or, for a note about the whole
    report, any stage) was written after the note was filed; false when it was not; null when the
    note's own time is unknown."""
    latest_any = max(edits.values()) if edits else None
    for row in rows:
        filed = _moment(row.get("createdAt"))
        stage_key = row.get("stageKey")
        edited = edits.get(stage_key) if stage_key else latest_any
        if filed is None:
            row["editedSince"] = None
        else:
            row["editedSince"] = edited is not None and edited > filed
    return rows


async def annotated_register(
    workshop_id: str, entries: list[Any] | None = None
) -> tuple[list[dict[str, Any]], bool]:
    """The correction register (newest first, and whether it was cut), each note marked fresh or not."""
    from app.api.routes.design_workshops import _inspection_feedback_payload
    from app.services.design_workshops import entry_rows

    rows, cut = await _inspection_feedback_payload(workshop_id)
    if entries is None:
        entries = await entry_rows(workshop_id)
    edits = await stage_edit_times(workshop_id, entries)
    return annotate_freshness(rows, edits), cut


# ── "CHECKED, NO CORRECTIONS" (C5)───────────────────────────────────────────────────────────────

CLEARANCE_HAS_NOTES = "You've filed notes on this round, so it can't be marked as having none."


async def clearance_rows(workshop_id: str) -> list[dict[str, Any]]:
    """Every verdict on this workshop, newest round first."""
    rows = await db.dwinspectionclearance.find_many(
        where={"designWorkshopId": workshop_id},
        include={"inspector": True},
        order={"round": "desc"},
    )
    return [
        {
            "inspectorId": row.inspectorId,
            "inspectorName": getattr(getattr(row, "inspector", None), "name", None),
            "round": row.round,
            "clearedAt": row.clearedAt.isoformat() if row.clearedAt else None,
        }
        for row in rows
    ]


def with_verdicts(
    inspectors: list[dict[str, Any]], clearances: list[dict[str, Any]], round: int
) -> list[dict[str, Any]]:
    """The inspector list with each person's verdict on the CURRENT round (``clearedAt`` or null)."""
    current = {c["inspectorId"]: c["clearedAt"] for c in clearances if c["round"] == round}
    return [{**person, "clearedAt": current.get(person.get("userId"))} for person in inspectors]


def my_clearance(clearances: list[dict[str, Any]], user_id: str, round: int) -> str | None:
    return next(
        (c["clearedAt"] for c in clearances if c["inspectorId"] == user_id and c["round"] == round),
        None,
    )


async def clear_round(record: Any, user: Any) -> None:
    """Record "Checked, no corrections" for the report's current round. Idempotent."""
    workshop_id = record.id
    cycle = int(getattr(record, "submissionRound", 0) or 0)
    filed = await db.dwinspectionfeedback.count(
        where={"designWorkshopId": workshop_id, "round": cycle, "actorId": user.id}
    )
    if filed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=CLEARANCE_HAS_NOTES,
        )
    existing = await db.dwinspectionclearance.find_first(
        where={"designWorkshopId": workshop_id, "inspectorId": user.id, "round": cycle}
    )
    if existing is not None:
        return
    try:
        await db.dwinspectionclearance.create(
            data={"designWorkshopId": workshop_id, "inspectorId": user.id, "round": cycle}
        )
    except UniqueViolationError:
        return  # a second press arriving with the first: the verdict stands either way
    # A note filed between the count above and this insert ran `drop_clearance_after_note` before
    # this row existed, so it removed nothing. Counting again closes that gap: a note on the round
    # always wins, and the two never stand together.
    if await db.dwinspectionfeedback.count(
        where={"designWorkshopId": workshop_id, "round": cycle, "actorId": user.id}
    ):
        await withdraw_clearance(record, user)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=CLEARANCE_HAS_NOTES,
        )


async def withdraw_clearance(record: Any, user: Any) -> None:
    await db.dwinspectionclearance.delete_many(
        where={
            "designWorkshopId": record.id,
            "inspectorId": user.id,
            "round": int(getattr(record, "submissionRound", 0) or 0),
        }
    )


async def drop_clearance_after_note(workshop_id: str, user_id: str, round: int) -> None:
    """A note filed on a round ends that person's "no corrections" for it. Never raises."""
    try:
        await db.dwinspectionclearance.delete_many(
            where={"designWorkshopId": workshop_id, "inspectorId": user_id, "round": round}
        )
    except Exception:  # the note is already filed
        logger.exception("could not withdraw a clearance on %s", workshop_id)


# ── QUEUE READINESS (B4) ─────────────────────────────────────────────────────────────────────────

#: How many rows a sorted-by-completeness or filtered-by-verdict queue considers. Past it the page
#: says so (``candidatesTruncated``) rather than quietly sorting a part of the queue.
CANDIDATE_CAP = 300

SORTS = ("default", "oldest", "newest", "complete")
CLEARED_FILTERS = ("all", "some", "none")


async def _scores(ids: list[str]) -> dict[str, dict[str, Any]]:
    from app.services.ministry_dashboard import PROGRESS_SCORE_CAP, progress_for

    out: dict[str, dict[str, Any]] = {}
    for start in range(0, len(ids), PROGRESS_SCORE_CAP):
        out.update(await progress_for(ids[start : start + PROGRESS_SCORE_CAP]))
    return out


def _round_pairs(records: list[Any]) -> list[dict[str, Any]]:
    return [
        {"designWorkshopId": r.id, "round": int(getattr(r, "submissionRound", 0) or 0)}
        for r in records
    ]


async def readiness_for(records: list[Any]) -> dict[str, dict[str, Any]]:
    """Per workshop: completeness, this round's notes and how many inspectors have cleared it."""
    if not records:
        return {}
    ids = [r.id for r in records]
    pairs = _round_pairs(records)
    scores, notes, inspectors, cleared = await gather_reads(
        _scores(ids),
        db.dwinspectionfeedback.find_many(where={"OR": pairs}),
        db.designworkshopinspector.find_many(where={"designWorkshopId": {"in": ids}}),
        db.dwinspectionclearance.find_many(where={"OR": pairs}),
    )
    out: dict[str, dict[str, Any]] = {}
    for record in records:
        score = scores.get(record.id)
        out[record.id] = {
            "percent": score.get("percent") if score else None,
            "stagesComplete": score.get("stagesComplete") if score else None,
            "stagesTotal": score.get("stagesTotal") if score else None,
            "notesThisRound": sum(1 for n in notes if n.designWorkshopId == record.id),
            "inspectors": sum(1 for i in inspectors if i.designWorkshopId == record.id),
            "cleared": sum(1 for c in cleared if c.designWorkshopId == record.id),
        }
    return out


def _matches_cleared(readiness: dict[str, Any], wanted: str | None) -> bool:
    if not wanted:
        return True
    assigned, done = readiness["inspectors"], readiness["cleared"]
    if wanted == "all":
        return assigned > 0 and done >= assigned
    if wanted == "some":
        return done > 0
    return done == 0


def check_sort(sort: str | None) -> str:
    value = (sort or "default").strip() or "default"
    if value not in SORTS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"sort must be one of {', '.join(SORTS)}.",
        )
    return value


def check_cleared(cleared: str | None) -> str | None:
    value = (cleared or "").strip() or None
    if value is not None and value not in CLEARED_FILTERS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"cleared must be one of {', '.join(CLEARED_FILTERS)}.",
        )
    return value


def dated_order(field: str, sort: str) -> list[dict[str, str]] | None:
    """``oldest``/``newest`` by one date column, ties by creation then id. None for other sorts."""
    if sort not in ("oldest", "newest"):
        return None
    direction = "asc" if sort == "oldest" else "desc"
    return [{field: direction}, {"createdAt": direction}, {"id": "asc"}]


async def ranked_page(
    where: dict[str, Any],
    order: Any,
    *,
    page: int,
    page_size: int,
    sort: str,
    cleared: str | None,
) -> tuple[list[Any], int, dict[str, dict[str, Any]], bool]:
    """One page of a queue that is sorted by completeness or filtered by inspectors' verdicts.

    Both need every row's readiness before paging, so up to :data:`CANDIDATE_CAP` rows are read in
    the queue's own order and ranked here. Returns ``(rows, total, readiness, truncated)``.
    """
    candidates = await db.designworkshop.find_many(where=where, order=order, take=CANDIDATE_CAP + 1)
    truncated = len(candidates) > CANDIDATE_CAP
    candidates = candidates[:CANDIDATE_CAP]
    readiness = await readiness_for(candidates)
    kept = [r for r in candidates if _matches_cleared(readiness[r.id], cleared)]
    if sort == "complete":
        kept.sort(
            key=lambda r: (
                readiness[r.id]["percent"] is None,
                -(readiness[r.id]["percent"] or 0),
            )
        )
    start = max(page - 1, 0) * page_size
    window = kept[start : start + page_size]
    return window, len(kept), {r.id: readiness[r.id] for r in window}, truncated


# ── ROUND SNAPSHOTS (C13) ────────────────────────────────────────────────────────────────────────

#: How long a deleted workshop's snapshots are kept (owner decision: the life of the workshop + 1 year).
SNAPSHOT_RETENTION_AFTER_DELETE = timedelta(days=365)
#: How often a process runs the purge, at most.
PURGE_INTERVAL_SECONDS = 6 * 60 * 60
_last_purge = 0.0


def _snapshot_stages(entries: list[Any]) -> dict[str, Any]:
    """The stages payload without provenance, made JSON-safe."""
    from app.api.routes.design_workshops import _stages_payload

    stages = _stages_payload(entries)
    for bucket in stages.values():
        bucket.pop("provenance", None)
    return json.loads(json.dumps(stages, default=str))


async def capture_round_snapshot(workshop_id: str) -> bool:
    """Keep the stage data as it stands for the workshop's current round, once. Never raises.

    Called right after a hand-in (the header PATCH to Pre-submission, or the stage save that resubmits
    a sent-back report). A round that already has its snapshot keeps the first one.
    """
    try:
        record = await db.designworkshop.find_unique(where={"id": workshop_id})
        cycle = int(getattr(record, "submissionRound", 0) or 0) if record else 0
        if cycle < 1:
            return False
        existing = await db.dwroundsnapshot.find_first(
            where={"designWorkshopId": workshop_id, "round": cycle}
        )
        if existing is not None:
            return False
        from app.services.design_workshops import entry_rows

        stages = _snapshot_stages(await entry_rows(workshop_id))
        try:
            await db.dwroundsnapshot.create(
                data={"designWorkshopId": workshop_id, "round": cycle, "stages": Json(stages)}
            )
        except UniqueViolationError:
            return False
        return True
    except Exception:  # the hand-in stands whether or not the copy was kept
        logger.exception("could not keep the round snapshot of %s", workshop_id)
        return False


async def snapshot_list(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.dwroundsnapshot.find_many(
        where={"designWorkshopId": workshop_id}, order={"round": "asc"}
    )
    return [
        {"round": row.round, "takenAt": row.takenAt.isoformat() if row.takenAt else None}
        for row in rows
    ]


async def snapshot_payload(workshop_id: str, round: int) -> dict[str, Any]:
    row = await db.dwroundsnapshot.find_first(
        where={"designWorkshopId": workshop_id, "round": round}
    )
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No copy of round {round} was kept.",
        )
    return {
        "round": row.round,
        "takenAt": row.takenAt.isoformat() if row.takenAt else None,
        "stages": row.stages if isinstance(row.stages, dict) else json.loads(str(row.stages)),
    }


async def purge_expired_snapshots(*, now: datetime | None = None, force: bool = False) -> int:
    """Delete the snapshots of workshops deleted more than a year ago. Throttled; never raises."""
    global _last_purge
    clock = time.monotonic()
    if not force and clock - _last_purge < PURGE_INTERVAL_SECONDS:
        return 0
    _last_purge = clock
    try:
        cutoff = (now or datetime.now(UTC)) - SNAPSHOT_RETENTION_AFTER_DELETE
        # Through the relation, in one statement: no batch of workshop ids to page through, so a
        # pass can never re-read workshops whose copies are already gone and stall on them.
        purged = await db.dwroundsnapshot.delete_many(
            where={"designWorkshop": {"is": {"deletedAt": {"lt": cutoff}}}}
        )
    except Exception:  # a missed purge is retried on the next pass
        logger.exception("round snapshot purge failed")
        purged = 0
    await purge_stored_reports(now=now)
    return purged


# ── REPORT REUSE (A22) ───────────────────────────────────────────────────────────────────────────

#: A stored file older than this is rebuilt even when nothing in the workshop moved — records the
#: report only points at (an artisan, a product) carry their own timestamps.
REPORT_REUSE_MAX_AGE = timedelta(days=14)
#: Bump when the report builder changes what an unchanged workshop renders to.
REPORT_FINGERPRINT_VERSION = "2"
#: The largest stored report this process reads back into memory to serve again.
REUSE_MAX_BYTES = 80 * 1024 * 1024

_MIME_SUFFIX = {"DOCX": "docx", "PDF": "pdf"}


async def report_fingerprint(
    record: Any, *, fmt: str, options: dict[str, Any], viewer: Any = None
) -> str:
    """What a report file is built from, as one digest: the header, every stage row (live or removed),
    the workshop's files, the questions it added, the registry, the request's options, WHO is asking
    and the records the report only points at.

    WHO IS ASKING: the report reads media and transcripts through ``owned_or_granted_where(viewer)``,
    which is unrestricted from professor up — every approving role today. Below that the answer
    depends on the person, so the digest carries their id and a file is only reused for them.

    THE REFERENCED RECORDS: an artisan's renamed entry or new portrait, a product, a questionnaire
    sitting, a re-run transcript. Their newest ``updatedAt`` goes in, the same way ``lastMedia`` does.
    """
    from app.core.deps import get_value, has_rank
    from app.services.design_workshops import REFERENCE_MODELS, reference_ids, workshop_media_ids
    from app.services.dictation_consent import MEDIA_TAG
    from app.services.stage_schema import registry_version

    workshop_id = record.id
    latest_entry, entry_count, latest_media, definition, rows = await gather_reads(
        db.dwstageentry.find_first(
            where={"designWorkshopId": workshop_id}, order={"updatedAt": "desc"}
        ),
        db.dwstageentry.count(where={"designWorkshopId": workshop_id, "deletedAt": None}),
        db.mediafile.find_first(
            where={"linkedRecordType": MEDIA_TAG, "linkedRecordId": workshop_id},
            order={"updatedAt": "desc"},
        ),
        _definition_version(workshop_id),
        db.dwstageentry.find_many(where={"designWorkshopId": workshop_id, "deletedAt": None}),
    )
    removed = await db.dwstageentry.find_first(
        where={"designWorkshopId": workshop_id, "deletedAt": {"not": None}},
        order={"deletedAt": "desc"},
    )
    referenced = reference_ids(rows)
    every_ref = sorted({ref for ids in referenced.values() for ref in ids})
    media_ids = sorted(workshop_media_ids(rows))
    reads: list[Any] = [
        getattr(db, REFERENCE_MODELS[model].delegate).find_first(
            where={"id": {"in": sorted(ids)}}, order={"updatedAt": "desc"}
        )
        for model, ids in sorted(referenced.items())
        if ids and model in REFERENCE_MODELS
    ]
    if every_ref:  # the referenced records' own photographs
        reads.append(
            db.mediafile.find_first(
                where={"linkedRecordId": {"in": every_ref}}, order={"updatedAt": "desc"}
            )
        )
    if media_ids:  # recordings named on a stage: their transcripts live on the media row
        reads.append(
            db.mediafile.find_first(where={"id": {"in": media_ids}}, order={"updatedAt": "desc"})
        )
    reads.append(  # the questionnaires attached to this workshop, and their sittings
        db.questionnaire.find_first(
            where={"designWorkshopId": workshop_id}, order={"updatedAt": "desc"}
        )
    )
    reads.append(
        db.questionnaireformentry.find_first(
            where={"questionnaire": {"is": {"designWorkshopId": workshop_id}}},
            order={"updatedAt": "desc"},
        )
    )
    linked = await gather_reads(*reads)
    pointed_at = max(
        (str(getattr(row, "updatedAt", "")) for row in linked if row is not None), default=""
    )
    scope = "all" if viewer is None or has_rank(viewer, "PROFESSOR") else get_value(viewer, "id")
    basis = {
        "v": REPORT_FINGERPRINT_VERSION,
        "format": fmt,
        "options": options,
        "viewer": scope,
        "registry": registry_version(),
        "custom": definition,
        "header": str(getattr(record, "updatedAt", None)),
        "status": str(getattr(record, "status", None)),
        "approvedAt": str(getattr(record, "approvedAt", None)),
        "handedOnAt": str(getattr(record, "handedOnAt", None)),
        "entries": entry_count,
        "lastEntry": str(getattr(latest_entry, "updatedAt", None)),
        "lastRemoved": str(getattr(removed, "deletedAt", None)),
        "lastMedia": str(getattr(latest_media, "updatedAt", None)),
        "pointedAt": pointed_at,
        "refs": every_ref,
    }
    return hashlib.sha256(json.dumps(basis, sort_keys=True, default=str).encode()).hexdigest()


async def _definition_version(workshop_id: str) -> Any:
    from app.services.custom_sections import load_definition_or_empty

    return getattr(await load_definition_or_empty(workshop_id), "version", None)


async def reusable_export(
    workshop_id: str, fingerprint: str, *, now: datetime | None = None
) -> Any:
    """The newest stored file built from exactly this fingerprint, recently enough to serve again."""
    since = (now or datetime.now(UTC)) - REPORT_REUSE_MAX_AGE
    return await db.dwreportexport.find_first(
        where={
            "designWorkshopId": workshop_id,
            "inputFingerprint": fingerprint,
            "storageKey": {"not": None},
            "generatedAt": {"gte": since},
        },
        order={"generatedAt": "desc"},
    )


def report_object_key(workshop_id: str, export_id: str, fmt: str) -> str:
    return f"reports/{workshop_id}/{export_id}.{_MIME_SUFFIX.get(fmt, 'bin')}"


#: How many stored report files one purge pass removes. Each removed row loses its ``storageKey``,
#: so the next pass starts on rows this one did not reach.
REPORT_PURGE_BATCH = 200


async def _delete_stored(rows: list[Any]) -> int:
    """Delete each row's stored file and clear its ``storageKey``. The ledger rows stay."""
    from app.services import s3

    removed = 0
    for row in rows:
        try:
            await asyncio.to_thread(s3.delete_object, row.storageKey)
            await db.dwreportexport.update(where={"id": row.id}, data={"storageKey": None})
            removed += 1
        except Exception:  # retried on the next pass
            logger.warning("could not delete stored report %s", row.id, exc_info=True)
    return removed


async def drop_superseded_reports(workshop_id: str, fmt: str, keep_export_id: str) -> int:
    """Keep only the newest stored file per workshop and format (A22): an older one can never be
    served again once a newer build of the same format exists. Never raises."""
    try:
        older = await db.dwreportexport.find_many(
            where={
                "designWorkshopId": workshop_id,
                "format": fmt,
                "storageKey": {"not": None},
                "id": {"not": keep_export_id},
            }
        )
    except Exception:
        logger.warning("could not list stored reports of %s", workshop_id, exc_info=True)
        return 0
    return await _delete_stored(older)


async def purge_stored_reports(*, now: datetime | None = None) -> int:
    """Delete stored report files that can no longer be reused: older than the reuse window, or
    belonging to a deleted workshop. They hold artisans' names and photographs. Never raises."""
    try:
        since = (now or datetime.now(UTC)) - REPORT_REUSE_MAX_AGE
        rows = await db.dwreportexport.find_many(
            where={
                "storageKey": {"not": None},
                "OR": [
                    {"generatedAt": {"lt": since}},
                    {"designWorkshop": {"is": {"deletedAt": {"not": None}}}},
                ],
            },
            order={"generatedAt": "asc"},
            take=REPORT_PURGE_BATCH,
        )
    except Exception:
        logger.exception("stored report purge failed")
        return 0
    return await _delete_stored(rows)
