"""Copy every workshop consent into the ``Consent`` ledger (the one consent store, 19 Oct 2026).

WHY. This portal kept its consent as three columns on ``DesignWorkshop`` plus the append-only
``DwWorkshopConsentDecision`` log. Both portals now keep ONE store, the ``Consent`` ledger, with one
rule (``backend/app/transcription/ledger.py``, identical in documentation-portal). Nothing writes the
old log any more and the gate reads the ledger; until this script has run, the gate falls back to the
cached columns for a workshop with no ledger row, and the history folds in the log rows not copied.

WHAT IT WRITES, one ``WORKSHOP`` ledger row (purpose EXTERNAL_PROCESSING) per:

* ``DwWorkshopConsentDecision`` row not copied before: state = decision, recordedAt = the device's
  ``recordedAt`` else ``createdAt``, actor and note kept, ``migratedFrom`` 'DwWorkshopConsentDecision:<id>';
* workshop whose cached answer is GRANTED or REFUSED but which has no log row at all (an answer
  written before the log existed): state = the cached answer, recordedAt = ``dictationConsentAt``,
  actor = ``dictationConsentById``, ``migratedFrom`` 'DesignWorkshop.dictationConsent:<id>'.

Source 'owner attestation' becomes basis OWNER_ATTESTATION and source 'owner attestation'; anything
else basis VERBAL_RECORDED and source 'researcher'. A row naming no account that still exists is
attributed to ``--fallback-actor-email`` (default: the approver), and its note says so.

IT CHANGES NOTHING ELSE: the old log and the columns are left as they are, no job, status or
transcript is touched, and nothing is queued.

DRY RUN BY DEFAULT, in a READ ONLY transaction; it prints the database host (never the password),
refuses a host not named in ``--allow-host``, and ``--apply`` refuses to start unless
``--approved-report`` is the dry run's report, unchanged, carrying ``Approved-by: <email> <YYYY-MM-DD>``.
Idempotent: ``migratedFrom`` is unique, so a second apply copies nothing. NEVER run it as part of a
test, and never against production without the owner's approval of the dry-run report.

    python scripts/migrate_workshop_consent_to_ledger.py --allow-host <db host>              # dry run + report
    python scripts/migrate_workshop_consent_to_ledger.py --allow-host <db host> --apply \\
        --approved-report docs/ops/<report with an Approved-by line> [--fallback-actor-email <owner>]
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import io
import os
import pathlib
import re
import sys
from collections import Counter
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlsplit

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.services import naming  # noqa: E402
from app.transcription import ledger  # noqa: E402

LOG_TABLE = "DwWorkshopConsentDecision"
CACHE_KEY = "DesignWorkshop.dictationConsent"
FALLBACK_NOTE = "Copied into the consent ledger; the account that recorded it is no longer on record."
APPROVAL = re.compile(r"^Approved-by:\s+(\S+@\S+)\s+(\d{4}-\d{2}-\d{2})\s*$", re.MULTILINE)

LOG_SQL = (
    'SELECT d."id", d."designWorkshopId" AS "subjectId", d."decision"::text AS state, d."source", '
    'd."actorId", COALESCE(d."recordedAt", d."createdAt") AS "recordedAt", d."note", '
    '(u."id" IS NOT NULL) AS "actorExists", '
    "EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"migratedFrom\" = 'DwWorkshopConsentDecision:' || d.\"id\") AS copied "
    'FROM "DwWorkshopConsentDecision" d LEFT JOIN "User" u ON u."id" = d."actorId" '
    'ORDER BY COALESCE(d."recordedAt", d."createdAt"), d."createdAt", d."id"'
)
CACHE_SQL = (
    'SELECT w."id", w."id" AS "subjectId", w."dictationConsent"::text AS state, NULL AS "source", '
    'w."dictationConsentById" AS "actorId", COALESCE(w."dictationConsentAt", w."updatedAt") AS "recordedAt", '
    'NULL AS "note", (u."id" IS NOT NULL) AS "actorExists", '
    "EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"migratedFrom\" = 'DesignWorkshop.dictationConsent:' || w.\"id\") AS copied "
    'FROM "DesignWorkshop" w LEFT JOIN "User" u ON u."id" = w."dictationConsentById" '
    "WHERE w.\"dictationConsent\" <> 'NOT_RECORDED' "
    'AND NOT EXISTS (SELECT 1 FROM "DwWorkshopConsentDecision" d WHERE d."designWorkshopId" = w."id") '
    'ORDER BY w."id"'
)


class Refused(SystemExit):
    def __init__(self, message: str) -> None:
        print(f"REFUSED: {message}", file=sys.stderr)
        super().__init__(2)


def _moment(value: Any) -> datetime | None:
    if isinstance(value, str):
        value = datetime.fromisoformat(value)
    if isinstance(value, datetime) and value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value if isinstance(value, datetime) else None


def mapped(row: dict[str, Any], origin: str) -> dict[str, Any] | None:
    """The ledger row (``ledger.append``'s arguments) one old row becomes, or None if it cannot be."""
    state = str(row.get("state") or "")
    if state not in ledger.DECISIONS or not row.get("subjectId"):
        return None
    owner = row.get("source") == ledger.SOURCE_OWNER
    return {
        "subject_type": ledger.WORKSHOP,
        "subject_id": str(row["subjectId"]),
        "purpose": ledger.EXTERNAL_PROCESSING,
        "state": state,
        "basis": ledger.OWNER_ATTESTATION if owner else ledger.VERBAL_RECORDED,
        "source": ledger.SOURCE_OWNER if owner else ledger.SOURCE_RESEARCHER,
        "note": row.get("note"),
        "actor_id": str(row.get("actorId") or ""),
        "recorded_at": _moment(row.get("recordedAt")),
        "migrated_from": ledger.legacy_key(origin, str(row["id"])),
    }


def plan(log_rows: list[dict[str, Any]], cache_rows: list[dict[str, Any]]) -> dict[str, Any]:
    counts: Counter[tuple[str, str, str, str]] = Counter()
    to_copy: list[tuple[dict[str, Any], bool]] = []
    copied = unreadable = 0
    for origin, rows in ((LOG_TABLE, log_rows), (CACHE_KEY, cache_rows)):
        for row in rows:
            if row.get("copied"):
                copied += 1
                continue
            target = mapped(row, origin)
            if target is None:
                unreadable += 1
                continue
            needs_actor = not (row.get("actorId") and row.get("actorExists"))
            to_copy.append((target, needs_actor))
            counts[(origin, target["state"], target["source"], "fallback actor" if needs_actor else "")] += 1
    return {"copied": copied, "unreadable": unreadable, "to_copy": to_copy, "counts": counts}


def report(p: dict[str, Any]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["from", "state", "source", "actor", "rowsToCopy"])
    for (origin, state, source, actor), n in sorted(p["counts"].items()):
        writer.writerow([origin, state, source, actor or "as recorded", n])
    writer.writerow(["TOTAL to copy", "", "", "", len(p["to_copy"])])
    writer.writerow(["already copied", "", "", "", p["copied"]])
    writer.writerow(["not copyable", "", "", "", p["unreadable"]])
    writer.writerow(["old rows and columns changed or deleted", "none", "", "", ""])
    writer.writerow(["jobs, statuses and transcripts changed", "none", "", "", ""])
    return buffer.getvalue()


async def apply(client: Any, p: dict[str, Any], fallback_actor_id: str | None) -> int:
    written = 0
    for target, needs_actor in p["to_copy"]:
        row = dict(target)
        if needs_actor:
            if not fallback_actor_id:
                raise Refused("rows name no account that still exists; pass --fallback-actor-email")
            row["actor_id"] = fallback_actor_id
            row["note"] = " ".join(part for part in ((row.get("note") or "").strip(), FALLBACK_NOTE) if part)
        if await ledger.append(client, **row) is not None:
            written += 1
    return written


def _digest(text: str) -> str:
    kept = [line for line in text.replace("\r\n", "\n").split("\n") if not APPROVAL.match(line)]
    return hashlib.sha256(("\n".join(kept).rstrip("\n") + "\n").encode("utf-8")).hexdigest()


def check_approval(path: str | None, fresh: str) -> tuple[str, str]:
    if not path or not pathlib.Path(path).exists():
        raise Refused("--apply needs --approved-report <the dry-run report carrying an Approved-by line>")
    text = pathlib.Path(path).read_text(encoding="utf-8")
    match = APPROVAL.search(text)
    if not match:
        raise Refused("the report carries no 'Approved-by: <owner email> <YYYY-MM-DD>' line")
    if _digest(text) != _digest(fresh):
        raise Refused("the data has changed since that report was approved; run the dry run again")
    return match.group(1), match.group(2)


async def _rows(client: Any) -> dict[str, Any]:
    return plan(
        [dict(r) for r in await client.query_raw(LOG_SQL)],
        [dict(r) for r in await client.query_raw(CACHE_SQL)],
    )


async def run(args: argparse.Namespace) -> int:
    url = (args.database_url or os.environ.get("DATABASE_URL") or "").strip()
    if not url:
        raise Refused("no database URL (pass --database-url or set DATABASE_URL)")
    host = urlsplit(url).hostname or ""
    print(f"Target database host: {host or '(none)'}")
    if not host or host not in set(args.allow_host):
        raise Refused(f"host {host!r} is not in --allow-host; name it explicitly to run against it")
    from prisma import Prisma

    client = Prisma(datasource={"url": url})
    await client.connect()
    try:
        async with client.tx() as tx:
            await tx.execute_raw("SET TRANSACTION READ ONLY")
            text = report(await _rows(tx))
        if not args.apply:
            name = naming.export_filename(entity="Workshop", what="Consent-Store-Merge-Dry-Run-Report", ext="csv")
            out = pathlib.Path(args.out) / name
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(text, encoding="utf-8", newline="\n")
            print(text)
            print(f"Report: {out}\nReport sha256 (without approval lines): {_digest(text)}")
            print("DRY RUN: nothing was written. The owner approves the report before --apply.")
            return 0
        approver, day = check_approval(args.approved_report, text)
        email = (args.fallback_actor_email or approver).strip().lower()
        users = await client.query_raw('SELECT "id" FROM "User" WHERE lower("email") = $1', email)
        async with client.tx() as tx:
            written = await apply(tx, await _rows(tx), str(users[0]["id"]) if users else None)
        print(f"APPLIED (approved by {approver} on {day}): {written} consent rows copied into the ledger.")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--database-url", default=None, help="defaults to $DATABASE_URL")
    parser.add_argument("--allow-host", action="append", default=[], help="a database host this run may touch")
    parser.add_argument("--apply", action="store_true", help="write (needs --approved-report)")
    parser.add_argument("--approved-report", default=None, help="the dry-run report with an Approved-by line")
    parser.add_argument("--out", default=str(ROOT / "docs" / "ops"), help="where the dry-run report goes")
    parser.add_argument("--fallback-actor-email", default=None, help="the account for rows naming none")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
