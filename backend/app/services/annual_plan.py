"""The ministry's annual directory of planned workshops — the rules, the write, and the boundary.

# WHAT THE DIRECTORY DOES, true as of 2026-09-13 — check
# `grep -rn "annual-plan" backend/app/api/routes/ frontend/lib/permissions.ts android/`:
#
#   Ships:  the pro-forma download; the upload and its report; the idempotent re-upload; the year
#           picker; the filtered, paged, server-sorted list; the .xlsx export of the current
#           directory; promotion of one row into a real workshop through `open_design_workshop`;
#           withdraw and reinstate; a remarks-only edit. Web client only.
#
#   Not built, and each is a decision rather than an omission:
#     · No Android screen. The two clients share no route here — Android calls none of the ten — so
#       there are no Kotlin DTOs either: a `@Serializable` class with no caller is dead code that
#       the next reader has to prove is dead. The handset's `Json` decodes with
#       `ignoreUnknownKeys = true` and `coerceInputValues = true` (android/.../data/ApiClient.kt),
#       so an added server key never crashes an older APK — and equally, a Kotlin field nobody
#       declared is silently absent rather than loud. The day a screen is wanted the whole change
#       is Kotlin-side.
#     · No sanctioned AMOUNT on a plan row. There is no such field anywhere in stage 1 today, and
#       adding one is a registry change: it moves `registry_version()`, stales every handset's
#       bundled schema asset, forces an Android release, and moves every existing workshop's
#       completeness score. The sanction REGISTER (`services/sanction_orders.py`) is where an
#       amount is recorded; a plan row is not an instrument and carries no money.
#     · No per-region scope. REGIONAL_DIRECTOR and ASSISTANT_DIRECTOR are below this feature's gate
#       because this table has no column that could narrow an edit to one region, and a rank change
#       without one would hand a regional director the whole national plan.
#     · No sanction-order DOCUMENT on a plan row. The stage-1 `sanctionDocument` FieldSpec exists
#       and is marked, in the source document's own words, “Phase 2 work”
#       (app/services/stage_definitions.py) — a quotation, and evidence rather than a claim.
#     · No upload history table. The report is the RESPONSE and is not persisted, which is what the
#       questionnaire upload does too. `revision`, `sourceFilename` and `sheetRow` on each row are
#       the per-row provenance that answers "did this change, and where did it come from".
#     · No bulk promote. Promotion names a designer, and naming the wrong one is the defect
#       `seed_designer_prefill` documents at length — the field is filled with the WRONG PERSON
#       rather than left missing, so completeness reads 100% and nothing warns. One row, one
#       decision.

══ A ROW HERE IS A LINE IN A DOCUMENT, AND MUST NEVER BE COUNTED AS A WORKSHOP ═══════════════════

`AnnualPlanEntry` is a PLAN: somebody at the ministry intends that a workshop happen. `DesignWorkshop`
is the container a fortnight of fieldwork lives in. Conflating the two is how a ministry is shown
"287 workshops held this year" when 284 of them have not started.

A planned row must never appear in, or be counted by, ANY of these — and each line is asserted,
surface by surface, in `backend/tests/test_annual_plan_is_not_a_workshop.py`:

  · `GET /api/design-workshops` and its `visible_to_clause` — a designer would see 300 workshops
    they cannot open, each answering 404 on the read.
  · `design_workshops.load_workshop_or_404` — 404 vs 200 is the whole access model here.
  · the dashboard's workshop counts — the ministry is told work happened.
  · `api/routes/datasets.DATASETS` — a plan row would stream as fieldwork.
  · `api/routes/data_browser`'s sheets and `DW_SHEET_GROUP` — it would land in a downloaded .xlsx
    labelled as records.
  · `api/routes/analytics` — adoption and cost averages over workshops that never ran.
  · `services/design_workshop_data.tables()` — that registry is the entity list; this is not an
    entity.
  · `services/report_builder.build_report` and `design_workshops.workshop_summary` — both would
    have to invent stages and a completeness score for a row that has neither.

THE ONE LEGITIMATE JOIN IS `AnnualPlanEntry.designWorkshopId`. Nothing else joins the two tables, in
either direction.

══ THE NATURAL KEY, AND WHY THE RE-UPLOAD IS THE FEATURE ═════════════════════════════════════════

`(planYear, workshopNoKey)`. A ministry directory is corrected two or three times a season and the
correction arrives as the WHOLE SHEET again, because that is how a spreadsheet is corrected. So the
write is an upsert on that key, and uploading the same file twice is indistinguishable from
uploading it once: the second run finds every row, compares every field, finds nothing different and
WRITES NOTHING AT ALL — not a column, not `updatedAt`, not `revision`.

══ A ROW THAT HAS ALREADY BECOME A WORKSHOP IS STILL UPDATED HERE, AND THE WORKSHOP IS NOT ═══════

This is the rule most likely to be got wrong, in either direction. The plan row goes on being
corrected: the ministry moved the venue, and the directory should say so. THE `DesignWorkshop` IS
NEVER TOUCHED BY AN UPLOAD. Its promoted columns have exactly one writer — the stage-1 entry,
through `stage_schema.promoted_values` — and a second writer reaching in from a spreadsheet would be
overwritten by the designer's next stage save under a 200 reading "Stage saved". Worse, it would
silently rewrite a fortnight of somebody's fieldwork header from a planning document.

The report names every such row under `updatedAfterPromotion`, with a `reason` sentence the screen
shows VERBATIM, so an administrator who moved a venue knows the workshop still says the old one.

══ THE GATE'S HOME ═══════════════════════════════════════════════════════════════════════════════

`can_manage_annual_plan` and `ANNUAL_PLAN_REFUSAL` live here rather than in `app/core/deps.py`,
where every other `can_*` predicate in this product lives. That is not a design opinion —
`deps.py` was owned by another change in flight when this landed, exactly as it was when
`services/sanction_orders.can_record_sanction_orders` landed beside it — and moving both into
`deps.py` is a welcome follow-up. The predicate is spelled once, here, so that the three surfaces
that state the rule (this 403, the web route guard, the absent nav entry) cannot drift.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from typing import Any

from fastapi import HTTPException, status

from app.core.db import db
from app.core.deps import has_rank
from app.services.annual_plan_xlsx import (
    MAX_PLAN_ROWS,
    ParsedAnnualPlan,
    ParsedPlanRow,
    plan_year_label,
)
from app.services.design_workshops import named_designer_team, open_design_workshop
from app.services.stage_schema import enum_label

__all__ = [
    "ANNUAL_PLAN_REFUSAL",
    "COMPARED_FIELDS",
    # Re-exported so a caller that already holds `annual_plan` need not learn a second module name
    # for the one bound the export route's own cap quotes.
    "MAX_PLAN_ROWS",
    "MAX_REPORTED_CHANGES",
    "apply_parsed_plan",
    "can_manage_annual_plan",
    "entry_payload",
    "plan_year_label",
    "promote_entry",
    "promotion_title",
    "reinstate_entry",
    "standing_of",
    "withdraw_entry",
]


# --------------------------------------------------------------------------------------
# The gate
# --------------------------------------------------------------------------------------

ANNUAL_PLAN_REFUSAL = (
    "The annual plan of workshops is managed by the ministry administrator and above. Ask them to "
    "upload or correct the directory."
)


def can_manage_annual_plan(user: Any) -> bool:
    """Read and write the ministry's annual directory of planned workshops.

    A RANK FLOOR AT MINISTRY_ADMIN (48), AND NOT `is_admin`. Read this before "simplifying" it to
    `require_admin`, because the two are not the same SHAPE and the difference decides who can do
    the job. `is_admin` is SET membership — `{"ADMIN", "MASTER_ADMIN"}` — so a MINISTRY_ADMIN at
    rank 48 is NOT an admin by it, and gating the directory that way would leave the one tier this
    feature exists for unable to open their own ministry's plan without an admin escort, behind a
    403 that reads as a bug.

    A FLOOR AND NOT A SET, which is the other half of the choice. The tiers immediately below —
    REGIONAL_DIRECTOR (45) and ASSISTANT_DIRECTOR (42) — are deliberately OUTSIDE it. The annual
    plan is a national instrument issued once a year; a regional director correcting the row for
    their own state would be correcting a document they did not issue, and this table has no
    per-region column an edit could be narrowed to. If regional editing is ever wanted it is a
    SCOPE TABLE and not a rank change — the same distinction `DesignWorkshopViewer` draws against
    `DESIGN_WORKSHOP_ROLES`.

    READ IS THE SAME GATE AS WRITE, for the reason the access roster's is: the directory is a list
    of named places and dates the ministry has not announced yet, so reading it is administrative
    work as much as correcting it is. A read gate one tier looser than the write gate would make
    the unannounced plan browsable by people who cannot be told apart from those who may change it.
    """
    return has_rank(user, "MINISTRY_ADMIN")


# --------------------------------------------------------------------------------------
# Derived standing, and the printable row
# --------------------------------------------------------------------------------------


def standing_of(row: Any) -> str:
    """PLANNED / PROMOTED / WITHDRAWN — DERIVED, NEVER STORED.

    There is deliberately no `status` column and no Postgres enum for it. A stored status beside
    `withdrawnAt` and `designWorkshopId` would be a second source for a fact those two columns
    already carry, and the two would disagree the first time a promotion failed halfway — the "two
    places deciding one rule" this repository names as how things drift.

    WITHDRAWN IS TESTED FIRST although a promoted row can never be withdrawn (`withdraw_entry`
    refuses one). That ordering is not redundancy: if a data repair ever produced a row that is
    both, it shows up as WITHDRAWN — visible and wrong — rather than being quietly labelled
    PROMOTED, which is the label that would send somebody to a workshop nobody is running.
    """
    if getattr(row, "withdrawnAt", None):
        return "WITHDRAWN"
    if getattr(row, "designWorkshopId", None):
        return "PROMOTED"
    return "PLANNED"


def _iso(value: Any) -> str | None:
    if value is None:
        return None
    return value.isoformat()


def entry_payload(row: Any) -> dict[str, Any]:
    """One directory row as the wire sees it.

    `workshopKindLabel` COMES FROM THE REGISTRY AND NOT FROM THE CLIENT. `stage_schema.enum_label`
    owns the six printable words, so the web client and any future handset cannot spell one value
    two ways — and a token a newer build stores that this one has never heard of falls back to the
    token rather than raising, which is that function's own stated rule.
    """
    return {
        "id": row.id,
        "planYear": row.planYear,
        "planYearLabel": plan_year_label(row.planYear),
        "workshopNo": row.workshopNo,
        "plannedTitle": row.plannedTitle,
        "workshopKind": row.workshopKind,
        "workshopKindLabel": (
            enum_label("WORKSHOP_KIND", row.workshopKind) if row.workshopKind else None
        ),
        "craftName": row.craftName,
        "clusterName": row.clusterName,
        "state": row.state,
        "district": row.district,
        "venue": row.venue,
        "plannedStartDate": _iso(row.plannedStartDate),
        "plannedEndDate": _iso(row.plannedEndDate),
        "implementingAgency": row.implementingAgency,
        "sponsor": row.sponsor,
        "notes": row.notes,
        "standing": standing_of(row),
        "revision": row.revision,
        "sheetRow": row.sheetRow,
        "sourceFilename": row.sourceFilename,
        "withdrawnAt": _iso(row.withdrawnAt),
        "designWorkshopId": row.designWorkshopId,
        "designWorkshopTitle": getattr(getattr(row, "designWorkshop", None), "title", None),
        "promotedAt": _iso(row.promotedAt),
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
    }


# --------------------------------------------------------------------------------------
# The upload
# --------------------------------------------------------------------------------------

#: THE FIELDS AN UPLOAD OWNS. Declared once and consumed by the diff, by the update payload and by
#: the change report, so a column cannot become writable-but-invisible or reportable-but-unwritten —
#: the rule `designers.PROFILE_FIELDS` states one service over.
#:
#: `workshopNo` IS ON THIS LIST AND `workshopNoKey` IS NOT. The key is IDENTITY: a row whose key
#: changed is a DIFFERENT row and is created rather than updated. The spelling is DATA, so re-typing
#: "dpw/2026/017" as "DPW/2026/017" updates the row it already wrote and is reported as the change
#: it is.
#:
#: `sheetRow` and `sourceFilename` are deliberately ABSENT. They are provenance of the last WRITE,
#: not facts about the planned workshop, and comparing them would make a re-sorted sheet that
#: changed nothing look like three hundred changes — which would destroy the one property that makes
#: re-uploading safe to do casually.
COMPARED_FIELDS: tuple[str, ...] = (
    "workshopNo",
    "plannedTitle",
    "workshopKind",
    "craftName",
    "clusterName",
    "state",
    "district",
    "venue",
    "plannedStartDate",
    "plannedEndDate",
    "implementingAgency",
    "sponsor",
    "notes",
)

#: The printable name of each compared field, for the change report. Kept beside `COMPARED_FIELDS`
#: so a field cannot be added to one without the other being obviously wrong.
FIELD_LABELS: dict[str, str] = {
    "workshopNo": "Workshop No.",
    "plannedTitle": "Workshop Title",
    "workshopKind": "Type of Workshop",
    "craftName": "Craft",
    "clusterName": "Cluster",
    "state": "State",
    "district": "District",
    "venue": "Venue",
    "plannedStartDate": "Start Date",
    "plannedEndDate": "End Date",
    "implementingAgency": "Implementing Agency",
    "sponsor": "Sponsoring Body",
    "notes": "Remarks",
}

#: How many individual field changes the report will name before it stops AND SAYS SO. The report is
#: read on a screen and printed into no artefact, so the cap is about legibility rather than memory —
#: but the truncation is STATED in the report, because a list that silently stops is a list that lies.
MAX_REPORTED_CHANGES = 500

#: The sentence a promoted row's change carries, shown to the administrator verbatim. Written on the
#: SERVER because it is the only place anybody is told that correcting the plan did not correct the
#: workshop, and a client that paraphrased it would cost an administrator their understanding of who
#: owns what.
PROMOTED_CHANGE_REASON = (
    "This workshop has already been opened as a design & prototype workshop. The plan has been "
    "corrected; the workshop itself has not been touched. Correct it on the workshop's own screen."
)

ABSENT_KEPT_REASON = (
    "Not in this sheet. Left in the plan — tick 'withdraw the workshops missing from this sheet' "
    "to withdraw it."
)

ABSENT_PROMOTED_REASON = (
    "Not in this sheet, and it has already been opened as a workshop, so it was left in the plan "
    "whatever the withdraw box said. Cancel the workshop itself if it is not going ahead."
)

CONCURRENT_UPLOAD_REFUSAL = (
    "Somebody else was uploading this year's plan at the same moment, so nothing was written. "
    "Nothing has been half-saved. Upload the same file again — a directory that is already correct "
    "is left exactly as it is."
)


def _as_date(value: Any) -> date | None:
    """Compare dates as DATES. A stored `datetime` and a parsed `date` are the same planned day."""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    return None


def _midnight(value: date | None) -> datetime | None:
    """A planned day as the instant Prisma stores. Midnight UTC, matching every other DATE column."""
    if value is None:
        return None
    return datetime(value.year, value.month, value.day, tzinfo=UTC)


def _differs(field: str, stored: Any, incoming: Any) -> bool:
    if field in ("plannedStartDate", "plannedEndDate"):
        return _as_date(stored) != _as_date(incoming)
    return (stored or None) != (incoming or None)


def _printable(field: str, value: Any) -> str | None:
    if value is None:
        return None
    if field in ("plannedStartDate", "plannedEndDate"):
        parsed = _as_date(value)
        return parsed.isoformat() if parsed else None
    if field == "workshopKind":
        return enum_label("WORKSHOP_KIND", str(value))
    return str(value)


def _create_data(row: ParsedPlanRow, *, plan_year: int, actor_id: str, filename: str | None):
    return {
        "planYear": plan_year,
        "workshopNo": row.workshopNo,
        "workshopNoKey": row.workshopNoKey,
        "plannedTitle": row.plannedTitle,
        "workshopKind": row.workshopKind,
        "craftName": row.craftName,
        "clusterName": row.clusterName,
        "state": row.state,
        "district": row.district,
        "venue": row.venue,
        "plannedStartDate": _midnight(row.plannedStartDate),
        "plannedEndDate": _midnight(row.plannedEndDate),
        "implementingAgency": row.implementingAgency,
        "sponsor": row.sponsor,
        "notes": row.notes,
        "sheetRow": row.sheetRow,
        "sourceFilename": filename,
        "createdById": actor_id,
        "updatedById": actor_id,
    }


async def _write_entry_update(tx: Any, entry_id: str, data: dict[str, Any]) -> None:
    """One row's update, through the TRANSACTION CLIENT, as its own seam.

    TWO REASONS THIS IS A FUNCTION AND NOT A LINE.

    `tx` IS NOT `db` AND THE DIFFERENCE IS INVISIBLE IF YOU GET IT WRONG. `db.tx()` hands back a
    DIFFERENT client, so `await db.annualplanentry.update(...)` written inside `async with db.tx()
    as tx:` executes OUTSIDE the transaction — and every count in the report still adds up, every
    test that asserts counts still passes, and the all-or-nothing property this whole write is built
    for simply does not exist. Funnelling the per-row write through one named function makes the
    mistake a one-line diff rather than a habit, and
    `tests/test_annual_plan_routes.py::test_the_service_writes_through_the_transaction_client_and_not_the_singleton`
    reads the source to catch it if somebody re-introduces the singleton anyway.

    AND IT IS THE SEAM THE TRANSACTION TEST NEEDS. `test_a_failure_after_the_creates_leaves_nothing_
    written` monkeypatches this to raise on its second call, which is the only way to provoke a
    failure AFTER the `create_many` has landed. The previous shape of that test patched a duplicate
    into `create_many` — a single statement that is atomic on its own — so it passed with the
    transaction deleted, which is the one thing a transaction test must not do.
    """
    await tx.annualplanentry.update(where={"id": entry_id}, data=data)


async def apply_parsed_plan(
    parsed: ParsedAnnualPlan,
    *,
    plan_year: int,
    actor_id: str,
    source_filename: str | None,
    withdraw_absent: bool,
) -> dict[str, Any]:
    """Write a parsed directory into the table, and say exactly what changed.

    ══ ALL-OR-NOTHING, IN ONE `db.tx()`, AND THE ARGUMENT IS NOT TIDINESS ═════════════════════════

    1. THE ONLY CONSTRAINT AN UPLOAD CAN FIRE IS `@@unique([planYear, workshopNoKey])`, AND THE
       PARSER ALREADY OWNS IT. Two rows in one sheet with the same folded number are caught while
       reading, reported against BOTH Excel rows, and the second is never handed here. So "row 250
       of 300 violates a constraint" is a state the sheet cannot reach on its own.

    2. WHAT REMAINS IS A RACE, AND A RACE IS EXACTLY WHAT A TRANSACTION IS FOR. Two administrators
       uploading the same corrected sheet in the same minute is realistic: there is no lock on the
       directory and no notification channel to coordinate through — this product has no mailer and
       no push transport. Without a transaction the loser's partial write leaves a directory that is
       neither sheet. With one, the create fails on the unique index, the whole upload rolls back,
       and the administrator is answered 409 with `CONCURRENT_UPLOAD_REFUSAL` telling them to upload
       again. THE RETRY IS SAFE PRECISELY BECAUSE THE OPERATION IS IDEMPOTENT — which is why those
       two properties had to be designed together rather than one after the other.

    3. A DIRECTORY IS A DOCUMENT, NOT A STREAM OF INDEPENDENT RECORDS. "Rows 1-249 of your ministry's
       plan are loaded and 250-300 are not" is a state nobody can act on: the administrator cannot
       tell whether to re-upload or to edit the tail, and nothing on the screen distinguishes a
       half-loaded plan from a complete one. That is the argument `create_questionnaire` makes for
       its own transaction — "a half-written instrument is worse than none, because it is
       indistinguishable from a complete one".

    4. PARTIAL-WITH-REPORT IS STILL WHAT HAPPENS TO BAD ROWS, and the two must not be confused. A row
       with no workshop number, an unreadable date, an unknown state: reported, and the other 299
       import. That is the parser's contract and it is unchanged. ALL-OR-NOTHING GOVERNS THE DATABASE
       WRITE, which either lands whole or does not land.

    STATEMENT COUNT IS THE COST AND IT IS BOUNDED. `MAX_PLAN_ROWS = 600` bounds the worst case at one
    `create_many`, 600 `update`s and one `update_many` inside a 60-second transaction. A first upload
    is one statement; a realistic correction is one plus a handful. That ceiling is 600 and not 5000
    for exactly this reason.

    REJECTED: `INSERT … ON CONFLICT DO UPDATE`. It is the right tool in Postgres and one statement
    for the whole sheet. It is rejected because this repository has NO WRITE-SIDE RAW SQL AT ALL —
    `query_raw` appears only in reads and `execute_raw` appears nowhere — and because a hand-written
    upsert would have to restate the per-field diff in SQL to keep `revision` honest, which is the
    two-places-deciding-one-rule this repository names as how things drift.

    REJECTED: per-row `upsert` with no transaction. Simplest to write; it is the partial-write
    failure above with 600 round trips.
    """
    incoming: dict[str, ParsedPlanRow] = {row.workshopNoKey: row for row in parsed.rows}

    # ONE QUERY, AND NO DATABASE INSIDE THE PARTITION LOOP. At most `MAX_PLAN_ROWS` rows for a year,
    # which is why the ceiling exists at all.
    existing = await db.annualplanentry.find_many(where={"planYear": plan_year})
    by_key = {row.workshopNoKey: row for row in existing}

    to_create: list[ParsedPlanRow] = []
    updates: list[tuple[Any, dict[str, Any], bool]] = []  # (stored row, data, reinstating)
    changes: list[dict[str, Any]] = []
    changes_truncated = False
    unchanged = 0
    reinstated = 0
    updated_after_promotion = 0

    for key, row in incoming.items():
        stored = by_key.get(key)
        if stored is None:
            to_create.append(row)
            continue

        promoted = bool(stored.designWorkshopId)
        data: dict[str, Any] = {}
        row_changed = False
        for field_name in COMPARED_FIELDS:
            new_value = getattr(row, field_name)
            old_value = getattr(stored, field_name)
            if not _differs(field_name, old_value, new_value):
                continue
            row_changed = True
            data[field_name] = (
                _midnight(new_value)
                if field_name in ("plannedStartDate", "plannedEndDate")
                else new_value
            )
            if len(changes) >= MAX_REPORTED_CHANGES:
                changes_truncated = True
            else:
                changes.append(
                    {
                        "workshopNo": row.workshopNo,
                        "sheetRow": row.sheetRow,
                        "field": field_name,
                        "fieldLabel": FIELD_LABELS[field_name],
                        "from": _printable(field_name, old_value),
                        "to": _printable(field_name, new_value),
                        # THE ONLY PLACE AN ADMINISTRATOR IS TOLD THAT CORRECTING THE PLAN DID NOT
                        # CORRECT THE WORKSHOP. Shown verbatim; never paraphrased on any client.
                        "reason": PROMOTED_CHANGE_REASON if promoted else None,
                    }
                )

        reinstating = stored.withdrawnAt is not None
        if not row_changed and not reinstating:
            # THE NO-WRITE. Not a column, not `updatedAt`, not `revision`, not even `sheetRow` — see
            # the schema's own comment on that column for why the staleness is the point.
            unchanged += 1
            continue

        if reinstating:
            reinstated += 1
            data["withdrawnAt"] = None
            data["withdrawnById"] = None
        if row_changed and promoted:
            updated_after_promotion += 1

        data["sheetRow"] = row.sheetRow
        data["sourceFilename"] = source_filename
        data["updatedById"] = actor_id
        data["revision"] = {"increment": 1}
        updates.append((stored, data, reinstating))

    absent_rows = [row for key, row in by_key.items() if key not in incoming]
    absent_promoted = [row for row in absent_rows if row.designWorkshopId]
    absent_plain = [row for row in absent_rows if not row.designWorkshopId and not row.withdrawnAt]

    now = datetime.now(UTC)
    try:
        async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
            if to_create:
                # ONE STATEMENT WHATEVER THE SIZE OF THE SHEET, which is why a first upload of three
                # hundred rows is a single round trip.
                await tx.annualplanentry.create_many(
                    data=[
                        _create_data(
                            row,
                            plan_year=plan_year,
                            actor_id=actor_id,
                            filename=source_filename,
                        )
                        for row in to_create
                    ]
                )
            for stored, data, _reinstating in updates:
                await _write_entry_update(tx, stored.id, data)
            if withdraw_absent and absent_plain:
                # ONE STATEMENT, and `absent_promoted` is not in it at any price.
                await tx.annualplanentry.update_many(
                    where={"id": {"in": [row.id for row in absent_plain]}},
                    data={"withdrawnAt": now, "withdrawnById": actor_id},
                )
    except Exception as exc:
        if _is_unique_violation(exc):
            # THE RACE OF PARAGRAPH 2, GIVEN AN HTTP ANSWER. A 500 here would tell an administrator
            # nothing and would leave them unsure whether half their plan had landed; the whole point
            # of the transaction is that it has not, and the recovery is one press.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT, detail=CONCURRENT_UPLOAD_REFUSAL
            ) from exc
        raise

    return {
        "planYear": plan_year,
        "planYearLabel": plan_year_label(plan_year),
        "sheet": parsed.sheet,
        "sourceFilename": source_filename,
        "rowsRead": len(incoming),
        "created": len(to_create),
        "updated": len(updates),
        "unchanged": unchanged,
        "reinstated": reinstated,
        "withdrawn": len(absent_plain) if withdraw_absent else 0,
        "absent": len(absent_rows),
        "absentPromoted": len(absent_promoted),
        "updatedAfterPromotion": updated_after_promotion,
        "withdrawAbsentRequested": withdraw_absent,
        "changes": changes,
        "changesTruncated": changes_truncated,
        "absentRows": [
            {
                "workshopNo": row.workshopNo,
                "promoted": bool(row.designWorkshopId),
                "reason": ABSENT_PROMOTED_REASON if row.designWorkshopId else ABSENT_KEPT_REASON,
            }
            for row in absent_rows
        ],
        "problems": [problem.payload() for problem in parsed.problems],
    }


def _is_unique_violation(exc: BaseException) -> bool:
    """Is this the `(planYear, workshopNoKey)` index, whatever driver wrapper carries it here?

    ASKED BY NAME RATHER THAN BY CLASS, deliberately. `prisma.errors.UniqueViolationError` is the
    class the ordinary path raises, but a violation inside a `db.tx()` can surface wrapped by the
    transaction's own exit and — the case that actually bit the questionnaire's transaction — as a
    `PrismaError` whose message carries the constraint name. Catching the class alone turns the one
    recoverable race this write has into a 500.
    """
    from prisma.errors import PrismaError, UniqueViolationError

    if isinstance(exc, UniqueViolationError):
        return True
    return isinstance(exc, PrismaError) and "unique" in str(exc).lower()


# --------------------------------------------------------------------------------------
# Single-row acts
# --------------------------------------------------------------------------------------


async def withdraw_entry(entry: Any, *, actor_id: str) -> Any:
    """Mark one plan row withdrawn. REFUSES A ROW THAT HAS ALREADY BECOME A WORKSHOP.

    A promoted row is no longer only a plan: a designer may be standing in the courtyard.
    Withdrawing it here would take the line out of the directory while the workshop, its viewers,
    its media and its report went on existing — and nothing anywhere would say why the two disagree.
    The refusal names the workshop and says to cancel that instead.

    NOTHING IS DELETED, EVER. A withdrawal is a stamp and the account that made it; the row and its
    whole history stay, because the directory is the record of what WAS intended as much as of what
    is intended now.
    """
    if entry.designWorkshopId:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' has already been opened as a workshop, so it cannot be "
                "withdrawn from the plan. Cancel the workshop itself if it is not going ahead."
            ),
        )
    if entry.withdrawnAt:
        return entry
    return await db.annualplanentry.update(
        where={"id": entry.id},
        data={"withdrawnAt": datetime.now(UTC), "withdrawnById": actor_id, "updatedById": actor_id},
    )


async def reinstate_entry(entry: Any, *, actor_id: str) -> Any:
    """Put a withdrawn row back in the standing plan. Clears the stamp; deletes nothing."""
    if not entry.withdrawnAt:
        return entry
    return await db.annualplanentry.update(
        where={"id": entry.id},
        data={"withdrawnAt": None, "withdrawnById": None, "updatedById": actor_id},
    )


def promotion_title(entry: Any) -> str:
    """A title for the workshop this plan row becomes.

    `DesignWorkshop.title` is NOT NULL and is the one promoted column `_coerce_promoted` refuses to
    blank, so a plan row with no title still has to produce one. Built from what the DIRECTORY
    actually says, in this order, and never left as the bare number: a workshop list of three
    hundred rows reading "DPW/2026/017" is a list nobody can scan.
    """
    if entry.plannedTitle:
        return str(entry.plannedTitle)[:220]
    where = " · ".join(
        part for part in (entry.craftName, entry.district or entry.state) if part
    )
    title = f"{entry.workshopNo} — {where}" if where else str(entry.workshopNo)
    return title[:220]


async def promote_entry(
    entry: Any,
    *,
    actor: Any,
    designer_id: str | None,
    designer_ids: list[str],
    template_id: str = "DCH_STANDARD",
) -> Any:
    """Open the real workshop this plan row was always for, and record the promotion.

    ONE WORKSHOP-CREATION PATH, AND THIS IS NOT A SECOND ONE. Every row below
    `design_workshops.open_design_workshop` is shared with `POST /api/design-workshops`, which is
    the point: a workshop typed by hand and a workshop promoted off the directory must converge, or
    the promoted one is the one that quietly skips `seed_designer_prefill` and has its promoted
    columns nulled by the designer's first stage-1 save.

    THE TWO DESIGNER FIELDS GO THROUGH `named_designer_team` FIRST, exactly as
    `POST /api/design-workshops` sends them through it. That is not tidying: `open_design_workshop`
    validates and grants `designer_ids` and uses `designer_id` for NOTHING but the prefill, so a
    body naming only `designerUserId` used to reach it unfolded — `wanted` came out empty, so
    `assert_every_designer_may_be_named` never ran (no empanelment roster read, no platform
    allow-list read, no `DESIGN_WORKSHOP_ROLES` test), `attach_the_named_designers` was skipped so
    no viewer row was written, and `seed_designer_prefill` nevertheless copied that account's
    displayName, biography, designation, qualification, phone, email and postal address into stage
    1 and stage 3 of a report bound for a ministry. The identical body sent to the create door was
    a 422 naming the six valid roles before anything existed. Two doors onto one creation path
    answering differently is the whole thing this function's first paragraph exists to prevent, and
    `named_designer_team(lead, [])` returning `(lead, [lead])` is what folds the lead back into the
    set that gets checked and granted.

    ALREADY PROMOTED IS A 409 AND NOT A SECOND WORKSHOP, AND THE STAMP IS A COMPARE-AND-SET.

    ⚠ **THE DATABASE DOES NOT BACK THIS REFUSAL, AND THIS PARAGRAPH USED TO SAY IT DID.** The old
    sentence read "`designWorkshopId` is `@unique`, so the database refuses it too", which is true
    of the wrong thing: that index stops two PLAN ROWS pointing at one workshop — which is what
    `tests/test_annual_plan_promotion.py::test_the_database_also_refuses_a_second_workshop_on_one_row`
    actually exercises — and says nothing whatever about ONE plan row being promoted twice, because
    each promotion mints a fresh workshop id and no other row holds either value. Two managers
    pressing "Open the workshop" on one row inside the second `open_design_workshop` takes (or one
    browser retrying the POST) both read `designWorkshopId = None`, both pass the check above, and
    two complete `DesignWorkshop` rows are created — same title, same seeded stage-1 header, the
    same promoted `workshopCode` — each with viewer rows for the named designers. The last `update`
    won, and the other workshop was a permanent orphan the plan could never reach, delete or
    explain, while its designers saw two byte-identical drafts.

    So the stamp below is `update_many` with `designWorkshopId: None` in its WHERE, and a write of
    zero rows means somebody else got there first. The workshop this call created is soft-deleted
    on that branch and the 409 names the workshop the row actually points at. That also covers the
    variant with no concurrency in it at all: if the stamp fails for any reason after the create,
    the workshop is already committed and the next press would otherwise make a second one.

    WHY NOT A TRANSACTION ROUND THE PAIR. `open_design_workshop` states in its own docstring that
    it is deliberately not transactional — `attach_the_named_designers` explains why the viewer
    rows are not transactional with the create and `seed_designer_prefill` swallows its own failure
    on purpose — so a `db.tx()` wrapped round both would change two decisions silently in order to
    fix one. A compare-and-set changes nothing about the callee and is what the upload arm of this
    same module already uses.
    """
    if entry.designWorkshopId:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' has already been opened as a workshop. Open that workshop "
                "instead of creating a second one for the same planned entry."
            ),
        )
    if entry.withdrawnAt:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' was withdrawn from the "
                f"{plan_year_label(entry.planYear)} plan. Reinstate it first if the workshop is "
                "going ahead after all."
            ),
        )

    start = _as_date(entry.plannedStartDate)
    end = _as_date(entry.plannedEndDate)

    # ── THE SAME VALUES IN TWO PLACES, DELIBERATELY, AND NEITHER IS DERIVED FROM THE OTHER ───────
    #
    # Every key of `seeded` is declared in `stage_schema.PROMOTED_COLUMNS` under `workshopSetup.*`,
    # so writing any of them as a COLUMN without also writing the stage entry behind it gets it
    # nulled by the first stage-1 save under a 200 reading "Stage saved". `seed_designer_prefill`
    # writes BOTH halves out of `seeded`, which is why the stage values are handed through
    # `open_design_workshop` rather than being created here.
    #
    # DATES GO IN AS ISO STRINGS, not datetimes: the registry's DATE type coerces and stores an ISO
    # string, and `_coerce_promoted` is what turns it back into a column value on the stage-save
    # path. That is the note `create_design_workshop` carries beside its own `seeded` literal.
    #
    # `title`/`workshopTitle` IS DELIBERATELY NOT SEEDED, for the reason that literal gives: it is
    # the one promoted column `DesignWorkshop` declares NOT NULL and the one `_coerce_promoted`
    # refuses to blank, so it was never at risk — and seeding it would freeze the promoted title
    # into stage 1 where a later PATCH of the workshop title could not reach it.
    seeded = {
        key: value
        for key, value in (
            ("workshopCode", entry.workshopNo),
            ("workshopKind", entry.workshopKind),
            ("craftName", entry.craftName),
            ("clusterName", entry.clusterName),
            ("state", entry.state),
            ("district", entry.district),
            ("venue", entry.venue),
            ("implementingAgency", entry.implementingAgency),
            ("sponsor", entry.sponsor),
            ("startDate", start.isoformat() if start else None),
            ("endDate", end.isoformat() if end else None),
        )
        if value
    }

    # ⚠ `venue`, `implementingAgency`, `sponsor` AND `workshopCode` ARE SEEDED AND ARE **NOT** PASSED
    # AS COLUMNS. They are not in `design_workshops._HEADER_TEXT_COLUMNS`; they belong to
    # `_STAGE_ONE_OWNS`. `seed_designer_prefill` applies `promoted_values` and updates the columns
    # itself — "returns the workshop header, updated if a promoted column was seeded" — so passing
    # them here as well would be the second writer this whole paragraph exists to prevent.
    columns: dict[str, Any] = {
        "title": promotion_title(entry),
        "templateId": template_id,
        **{
            key: value
            for key, value in (
                ("workshopKind", entry.workshopKind),
                ("craftName", entry.craftName),
                ("clusterName", entry.clusterName),
                ("state", entry.state),
                ("district", entry.district),
                ("startDate", entry.plannedStartDate),
                ("endDate", entry.plannedEndDate),
            )
            if value
        },
    }

    # THE SAME NORMALISATION THE CREATE DOOR APPLIES, AND THROUGH THE SAME FUNCTION. See the
    # docstring: without it a body naming only `designerUserId` skipped every eligibility read and
    # wrote no viewer row while still copying that person's profile into the report.
    lead_id, granted_ids = named_designer_team(designer_id, designer_ids)

    record = await open_design_workshop(
        actor=actor,
        columns=columns,
        designer_id=lead_id,
        designer_ids=granted_ids,
        seeded=seeded,
    )

    # ── THE COMPARE-AND-SET. `designWorkshopId: None` IS THE WHOLE GUARD. ─────────────────────────
    #
    # `update_many` rather than `update` because only `update_many` takes a predicate beyond the
    # primary key and answers with a COUNT. Zero rows means the row was promoted between the check
    # at the top of this function and this line, which is the race the docstring describes.
    stamped = await db.annualplanentry.update_many(
        where={"id": entry.id, "designWorkshopId": None},
        data={
            "designWorkshopId": record.id,
            "promotedAt": datetime.now(UTC),
            "promotedById": actor.id,
            "updatedById": actor.id,
        },
    )
    if not stamped:
        # WE LOST. Take the workshop this call created back out rather than leaving it in the
        # directory: it is a byte-identical twin of the one that won — same title, same seeded
        # stage-1 header, the same promoted `workshopCode` — and the two are indistinguishable to
        # anybody who later has to decide which is the real one. A soft delete is the repository's
        # only removal for this table (`deletedAt`, every read filtered on it), so the losing
        # workshop simply never appears; nothing is destroyed and the winner is untouched.
        await db.designworkshop.update(
            where={"id": record.id}, data={"deletedAt": datetime.now(UTC)}
        )
        # THE REFUSAL NAMES THE WORKSHOP THE ROW ACTUALLY POINTS AT, which is the only useful thing
        # to tell the administrator and the one thing a constraint violation could never have said.
        # Re-read rather than guessed: the winner may not be this request's own workshop.
        winner = await db.annualplanentry.find_unique(where={"id": entry.id})
        opened = getattr(winner, "designWorkshopId", None)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"'{entry.workshopNo}' was opened as a workshop a moment ago — by somebody else, or "
                f"by a retry of this same request — so a second one was not created. Open "
                + (f"workshop {opened}" if opened else "the workshop it became")
                + " instead."
            ),
        )
    return record
