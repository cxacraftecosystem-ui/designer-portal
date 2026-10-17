"""Regulation 18(3): Aadhaar log entries are live for 2 years, archived for 5 more, then deleted.

WHAT COUNTS AS AN AADHAAR LOG ENTRY. Every verification attempt is a ``RecordRevision`` whose
``changes`` holds one key, ``aadhaarVerification`` (``records.audit_attempt``), on a "user" or an
"artisan" record. Those rows are the authentication log this portal keeps; they carry the method,
the outcome, a refusal code and at most the last four digits, never the number.

WHAT THE JOB DOES, once a day from the queue worker (``app/worker.py``):

1. ARCHIVE. Entries older than :data:`LIVE_YEARS` are written, a calendar month at a time, as one
   gzipped JSON-lines object to the encrypted backup bucket (``AADHAAR_LOG_ARCHIVE_BUCKET``, default
   the media bucket, under ``aadhaar-log-archive/``; SSE-KMS when ``AADHAAR_LOG_ARCHIVE_KMS_KEY_ID``
   is set, SSE-S3 otherwise). The object is read back (size and SHA-256) BEFORE anything is
   deleted; only then are the archived entries removed from the live table, in the same
   transaction that records the archive in ``AadhaarLogArchive``. A failed upload deletes nothing.
2. DELETE. An archive whose newest entry is older than LIVE_YEARS + ``AADHAAR_LOG_ARCHIVE_YEARS``
   (default 5) is deleted from the bucket and its ledger row stripped of record ids.
3. LEGAL HOLD. A record with an open ``AadhaarLegalHold`` (a court order or a pending dispute,
   placed with ``scripts/aadhaar_legal_hold.py``) has its entries neither archived nor deleted, and
   an archive holding any of its entries is kept past its date until the hold is released.

The job is idempotent: an entry is deleted from the live table only by the transaction that
records the archive that holds it, and an archive is deleted only once. A crash between the upload
and that transaction leaves an orphan object and the entries still live; the next run archives
them again under a new key, and the orphan is listed in the run's report for an operator.
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import logging
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from app.core.db import db

logger = logging.getLogger("app.aadhaar.retention")

#: Regulation 18(3)(a): authentication logs are kept "online" for two years.
LIVE_YEARS = 2
#: Regulation 18(3)(b): then archived for five years (longer only where another law requires it).
DEFAULT_ARCHIVE_YEARS = 5
#: The ``RecordRevision.changes`` key every Aadhaar attempt is written under.
ENTRY_KEY = "aadhaarVerification"
RECORD_TYPES = ("user", "artisan")
DEFAULT_PREFIX = "aadhaar-log-archive/"
#: Entries read per pass, so one run never holds an unbounded table in memory.
BATCH = 2000


def years_before(moment: datetime, years: int) -> datetime:
    """``moment`` minus whole calendar years; 29 February becomes 28 February."""
    try:
        return moment.replace(year=moment.year - years)
    except ValueError:
        return moment.replace(year=moment.year - years, day=28)


def years_after(moment: datetime, years: int) -> datetime:
    return years_before(moment, -years)


class ArchiveStore(Protocol):
    def put(self, key: str, body: bytes, sha256: str) -> None:
        """Write ``body`` encrypted at rest and confirm it reads back with this size and digest."""

    def delete(self, key: str) -> None: ...


class S3ArchiveStore:
    """The encrypted backup bucket. The API's key needs Put/Get/Delete on the prefix (docs/AADHAAR.md)."""

    def __init__(self, settings: Any) -> None:
        self.settings = settings
        self.bucket = getattr(settings, "aadhaar_log_archive_bucket", None) or settings.aws_s3_bucket

    def _client(self) -> Any:
        from app.services import s3

        return s3._client()

    def put(self, key: str, body: bytes, sha256: str) -> None:
        kms = (getattr(self.settings, "aadhaar_log_archive_kms_key_id", None) or "").strip()
        extra: dict[str, Any] = (
            {"ServerSideEncryption": "aws:kms", "SSEKMSKeyId": kms}
            if kms
            else {"ServerSideEncryption": "AES256"}
        )
        client = self._client()
        client.put_object(
            Bucket=self.bucket,
            Key=key,
            Body=body,
            ContentType="application/gzip",
            Metadata={"sha256": sha256},
            **extra,
        )
        head = client.head_object(Bucket=self.bucket, Key=key)
        if int(head.get("ContentLength", -1)) != len(body) or (
            head.get("Metadata", {}).get("sha256") != sha256
        ):
            raise RuntimeError(f"archive {key} did not read back as written")
        if not head.get("ServerSideEncryption"):
            raise RuntimeError(f"archive {key} is not encrypted at rest")

    def delete(self, key: str) -> None:
        self._client().delete_object(Bucket=self.bucket, Key=key)


@dataclass
class RetentionReport:
    archived_entries: int = 0
    archives_written: int = 0
    held_entries_kept: int = 0
    archives_deleted: int = 0
    held_archives_kept: int = 0
    failures: list[str] = field(default_factory=list)

    def line(self) -> str:
        return (
            f"archived {self.archived_entries} entries in {self.archives_written} object(s), "
            f"kept {self.held_entries_kept} live under legal hold, deleted {self.archives_deleted} "
            f"expired archive(s), kept {self.held_archives_kept} under legal hold, "
            f"{len(self.failures)} failure(s)"
        )


def _record_key(record_type: str, record_id: str) -> str:
    return f"{record_type}:{record_id}"


async def held_record_keys() -> set[str]:
    holds = await db.aadhaarlegalhold.find_many(where={"releasedAt": None})
    return {_record_key(h.recordType, h.recordId) for h in holds}


def _as_utc(value: Any) -> datetime:
    if isinstance(value, str):
        value = datetime.fromisoformat(value)
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def _entry(row: Any) -> dict[str, Any]:
    changes = row.changes
    return {
        "id": row.id,
        "recordType": row.recordType,
        "recordId": row.recordId,
        "editedById": row.editedById,
        "createdAt": _as_utc(row.createdAt).isoformat(),
        ENTRY_KEY: changes.get(ENTRY_KEY) if isinstance(changes, dict) else None,
    }


def _bundle(entries: list[dict[str, Any]]) -> bytes:
    text = "".join(json.dumps(entry, sort_keys=True, ensure_ascii=False) + "\n" for entry in entries)
    buffer = io.BytesIO()
    # mtime=0: the same entries always gzip to the same bytes, so a digest identifies the content.
    with gzip.GzipFile(fileobj=buffer, mode="wb", mtime=0) as handle:
        handle.write(text.encode("utf-8"))
    return buffer.getvalue()


async def _due_entries(cutoff: datetime, limit: int) -> list[Any]:
    ids = await db.query_raw(
        """SELECT "id" FROM "RecordRevision"
           WHERE "recordType" IN ('user', 'artisan')
             AND jsonb_typeof("changes") = 'object' AND "changes" ? 'aadhaarVerification'
             AND "createdAt" < $1::timestamp
           ORDER BY "createdAt" ASC, "id" ASC
           LIMIT $2""",
        cutoff.astimezone(UTC).replace(tzinfo=None).isoformat(),
        limit,
    )
    if not ids:
        return []
    rows = await db.recordrevision.find_many(where={"id": {"in": [r["id"] for r in ids]}})
    return sorted(rows, key=lambda r: (_as_utc(r.createdAt), r.id))


async def archive_due(
    now: datetime,
    store: ArchiveStore,
    *,
    prefix: str = DEFAULT_PREFIX,
    archive_years: int = DEFAULT_ARCHIVE_YEARS,
    report: RetentionReport | None = None,
    batch: int = BATCH,
) -> RetentionReport:
    report = report or RetentionReport()
    cutoff = years_before(now, LIVE_YEARS)
    held = await held_record_keys()
    skipped: set[str] = set()
    while True:
        rows = [r for r in await _due_entries(cutoff, batch + len(skipped)) if r.id not in skipped]
        if not rows:
            return report
        months: dict[str, list[Any]] = defaultdict(list)
        for row in rows:
            if _record_key(row.recordType, row.recordId) in held:
                skipped.add(row.id)
                report.held_entries_kept += 1
                continue
            months[_as_utc(row.createdAt).strftime("%Y/%m")].append(row)
        if not months:
            return report
        for month, group in sorted(months.items()):
            entries = [_entry(r) for r in group]
            body = _bundle(entries)
            digest = hashlib.sha256(body).hexdigest()
            key = f"{prefix}{month}/{uuid.uuid4().hex}.jsonl.gz"
            oldest = _as_utc(group[0].createdAt)
            newest = _as_utc(group[-1].createdAt)
            try:
                store.put(key, body, digest)
            except Exception as exc:  # noqa: BLE001 - nothing is deleted when the archive did not land
                logger.error("aadhaar log archive %s failed: %s", month, type(exc).__name__)
                report.failures.append(f"upload {month}")
                skipped.update(r.id for r in group)
                continue
            async with db.tx() as tx:
                await tx.aadhaarlogarchive.create(
                    data={
                        "objectKey": key,
                        "sha256": digest,
                        "entryCount": len(group),
                        "oldestAt": oldest,
                        "newestAt": newest,
                        "deleteAfter": years_after(newest, LIVE_YEARS + archive_years),
                        "recordKeys": sorted({_record_key(r.recordType, r.recordId) for r in group}),
                    }
                )
                await tx.recordrevision.delete_many(where={"id": {"in": [r.id for r in group]}})
            report.archived_entries += len(group)
            report.archives_written += 1


async def delete_expired(
    now: datetime, store: ArchiveStore, *, report: RetentionReport | None = None
) -> RetentionReport:
    report = report or RetentionReport()
    held = await held_record_keys()
    due = await db.aadhaarlogarchive.find_many(
        where={"deleteAfter": {"lte": now}, "deletedAt": None}, order={"deleteAfter": "asc"}
    )
    for archive in due:
        if held.intersection(archive.recordKeys or []):
            report.held_archives_kept += 1
            continue
        try:
            store.delete(archive.objectKey)
        except Exception as exc:  # noqa: BLE001 - one undeletable object must not stop the rest
            logger.error("aadhaar log archive delete failed: %s", type(exc).__name__)
            report.failures.append("delete")
            continue
        await db.aadhaarlogarchive.update(
            where={"id": archive.id}, data={"deletedAt": now, "recordKeys": []}
        )
        report.archives_deleted += 1
    return report


async def run_retention(
    settings: Any, *, now: datetime | None = None, store: ArchiveStore | None = None
) -> RetentionReport:
    now = now or datetime.now(UTC)
    store = store or S3ArchiveStore(settings)
    prefix = getattr(settings, "aadhaar_log_archive_prefix", None) or DEFAULT_PREFIX
    years = max(int(getattr(settings, "aadhaar_log_archive_years", DEFAULT_ARCHIVE_YEARS)), 5)
    report = await archive_due(now, store, prefix=prefix, archive_years=years)
    await delete_expired(now, store, report=report)
    logger.info("aadhaar log retention: %s", report.line())
    return report


_DAY = timedelta(days=1)
_next_run: datetime | None = None


async def maybe_run(settings: Any, *, now: datetime | None = None) -> RetentionReport | None:
    """At most once a day per process. The worker calls this every loop; it is cheap when not due."""
    global _next_run
    if not getattr(settings, "aadhaar_log_retention_enabled", True):
        return None
    now = now or datetime.now(UTC)
    if _next_run is not None and now < _next_run:
        return None
    _next_run = now.replace(microsecond=0) + _DAY
    return await run_retention(settings, now=now)
