"""Move designer-portal's questionnaires onto the one v2 model (UNIFY_2026-10-21.md section 3.2, U-M1).

WHAT IT MIGRATES (ids are kept everywhere an old row has one):

* every custom form keeps its ``Questionnaire`` row; it gets a library ``sortOrder`` and, when the
  owner names one with ``--default-form``, ``isDefault``. Its ``designWorkshopId`` attachment (mirrored
  into ``QuestionnaireBinding`` by the U0 release) becomes that design workshop's PRIMARY binding; a
  workshop with several forms gets the proposed one (a Workshop interview first, then the oldest),
  which ``--primary <designWorkshopId>=<formId>`` overrides. Deleted forms are copied too (they are
  restorable) and are never primary;
* each form's sections and questions are copied into ``QuestionnaireSection``/``QuestionnaireQuestion``
  with their ids, the question's ``lineageId`` (the root of its supersede chain) and its frozen
  ``displayCode`` (section code + the root's position), exactly as the U0 migration computes them;
* the global instrument (its inactive section and questions) becomes the retired instrument
  "Interview form (retired)": inactive, deleted, never offered, ids kept so ``AssignedTask.sectionIds``
  still resolve;
* every sitting (``QuestionnaireFormEntry``) becomes a session with the same id: origin
  LEGACY_RECONSTRUCTED, ``source`` APP/UPLOAD/HANDOFF, the typed title kept as ``legacyTitle`` and the
  title generated, dated (estimated) the day it was created, closed, soft delete carried. Its
  respondent becomes a ``QuestionnaireRespondent`` (GUEST with the typed name, else SELF: the designer
  who answered their own form); notes up to 80 characters become the session note, longer ones the
  respondent's notes. The creator is the LEAD researcher and every other answerer a CONTRIBUTOR;
* every sitting answer becomes the respondent's canonical answer per question lineage plus one
  revision per old row (origin TYPED, or WORKBOOK for an UPLOAD sitting; the old notes become the
  revision's note; an answer and a reworded answer of one lineage are two revisions of one answer);
* every global interview is reconstructed in place, honestly: ONE session per old row, never split
  into invented days. ``sessionDate`` is its interview date, else the earliest clip's day, else the day
  it was created (estimated unless an interview date was typed); a multi-day interview stays one
  session and its clips keep their own times. Its participants stay; its responses become one answer
  per participant per lineage (GROUP_SHARED when several sat together); its clips become recordings
  with targets from the ONE legacy resolver and every participant as an ANSWERED subject. A clip with
  no participants, an unresolved target or a byte-identical copy waits in the inbox (NEEDS_DECISION);
  nothing is removed, re-filed or transcribed. Its pending AI drafts become take drafts;
* the design workshops' dictation consent is copied onto the consent ledger where it is not there yet
  (the same rows and ``migratedFrom`` keys as ``scripts/migrate_workshop_consent_to_ledger.py``);
* ``QuestionnaireSectionStatus`` rows are KEPT AS THEY ARE (three states); the dry run counts any row
  outside them, which would stop the later enum cast.

MODES (all refuse a host not named in ``--allow-host``; ``scripts/qv2_guard.py``, shared):

    python scripts/questionnaire_unify.py --allow-host H [--default-form F] [--primary DW=F ...]
        DRY RUN (default). A verified READ ONLY transaction; prints the counts as JSON and writes
        docs/ops/DesignWorkshop_Questionnaire_<date>_Unify-Dry-Run-Report.md and .csv. Works on the
        schema before the U0 release too (it then reports what it would create).
    python scripts/questionnaire_unify.py --allow-host H --apply --run-id R --approved-report P [same choices]
        Refuses unless the U0 tables exist, the fresh dry run's report equals P byte for byte (approval
        lines aside) and P carries ``Approved-by: <owner email> <YYYY-MM-DD>``, and no blocker is listed.
        Then: (1) SNAPSHOTS every affected table into the schema ``questionnaire_unify_<R>``
        (``CREATE TABLE ... AS SELECT``) with the index state, which is the undo record; (2) applies
        the three prerequisite deferred migrations (prisma/deferred: WORKBOOK, the per-form section
        keys, the shared-title unique); (3) writes everything in ONE transaction. Idempotent: a second
        dry run shows 0 to write and a second apply writes nothing.
    python scripts/questionnaire_unify.py --allow-host H --undo --run-id R
        Restores every affected table EXACTLY to the snapshot and puts the dropped keys back (the
        WORKBOOK enum value stays; Postgres cannot drop one, and nothing uses it). Refuses once rows
        exist that the run did not write (native v2 content), because undoing would orphan them.

ORDER (section 3.2): the nightly ``backup-db.yml`` dump run on demand first (pg_dump to S3), then the
dry run, the owner's approval line, the apply, a second dry run showing 0, then the switch-on commit.
NEVER run --apply or --undo against production without the owner's approval of the dry-run report.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import hashlib
import io
import json
import os
import re
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

from scripts import qv2_guard  # noqa: E402

IST = timezone(timedelta(hours=5, minutes=30))
RETIRED_ID = "questionnaire-retired-global-form"
RETIRED_TITLE = "Interview form (retired)"
DEFERRED = BACKEND / "prisma" / "deferred"
#: Applied by --apply before it writes (each idempotent); the rest wait for the switch-on commit.
PREREQUISITES = (
    "20261108090000_questionnaire_unify_workbook_origin",
    "20261108091000_questionnaire_unify_section_keys",
    "20261108092000_questionnaire_unify_form_title_unique",
)
#: Index names the prerequisites drop or create, recorded in the snapshot for the undo.
INDEX_DDL = {
    "QuestionnaireSection_code_key": 'CREATE UNIQUE INDEX "QuestionnaireSection_code_key" ON "QuestionnaireSection"("code")',
    "QuestionnaireSection_sortOrder_key": 'CREATE UNIQUE INDEX "QuestionnaireSection_sortOrder_key" ON "QuestionnaireSection"("sortOrder")',
    "QuestionnaireSection_questionnaireId_sortOrder_key": None,
    "Questionnaire_shared_title_key": None,
}
#: Every table the apply may write, parents first. The snapshot copies each whole; undo restores each.
TABLES = (
    "Questionnaire", "QuestionnaireBinding", "QuestionnaireSection", "QuestionnaireQuestion", "QuestionnaireSectionStatus",
    "QuestionnaireInterview", "QuestionnaireInterviewArtisan", "QuestionnaireInterviewResearcher", "QuestionnaireRespondent",
    "QuestionnaireAnswer", "QuestionnaireAnswerRevision", "QuestionnaireRecording", "QuestionnaireRecordingTarget",
    "QuestionnaireRecordingSubject", "QuestionnaireRecordingRespondent", "QuestionnaireRecordingEvent", "QuestionnaireTakeDraft", "Consent",
)
RUN_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,23}$")
EXPECTED = (
    "Production assessment, for comparison only (nothing is hard-coded; the accepted values are whatever the "
    "owner signs): 2 custom forms, both shared and Workshop interview, with 46 sections and 365 questions; "
    "the global instrument with 1 inactive section and 2 inactive questions; 0 sittings, 0 answers, "
    "0 global interviews, 0 responses, 0 drafts."
)


def _overlay() -> None:
    """``QUESTIONNAIRE_UNIFY_OVERLAY=<dir>``: a directory holding ``app/questionnaire`` and
    ``app/shared_vocab.py`` from this branch, for a dry run on a box whose release predates them. It
    is appended to ``app.__path__``, so nothing of the box's own release is replaced or written."""
    extra = os.environ.get("QUESTIONNAIRE_UNIFY_OVERLAY", "").strip()
    if extra:
        import app

        path = str(Path(extra) / "app")
        if path not in app.__path__:
            app.__path__.append(path)


# ── small helpers ──────────────────────────────────────────────────────────────────────────────
def moment(value: Any) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, str):
        value = datetime.fromisoformat(value)
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day, tzinfo=UTC)
    return None


def ist_day(value: Any) -> date | None:
    m = moment(value)
    return m.astimezone(IST).date() if m else None


def _sort_key(row: dict[str, Any]) -> tuple:
    return (int(row.get("sortOrder") or 0), moment(row.get("createdAt")) or datetime.min.replace(tzinfo=UTC), str(row["id"]))


def stable_id(prefix: str, *parts: str) -> str:
    return f"{prefix}-" + hashlib.sha256("|".join(parts).encode()).hexdigest()[:24]


def sha_of(media: dict[str, Any]) -> str | None:
    for key in ("contentSha256", "checksum"):
        value = str(media.get(key) or "").strip().lower()
        if re.fullmatch(r"[0-9a-f]{64}", value):
            return value
    return None


def language(value: str | None) -> tuple[str | None, str | None]:
    text = (value or "").strip()
    if not text:
        return None, None
    _overlay()
    from app import shared_vocab

    alias = shared_vocab.LANGUAGE_ALIASES.get(text) or shared_vocab.LANGUAGE_ALIASES.get(text.lower())
    if alias:
        return alias, None
    for code, label in shared_vocab.LANGUAGE_LABELS.items():
        if text.casefold() in (code, label.casefold()):
            return code, None
    return "other", text[:40]


# ── lineage and display codes (the U0 migration's rule, in Python for the pre-U0 dry run) ────────
def lineages(rows: list[dict[str, Any]]) -> tuple[dict[str, str], int]:
    """``{id: lineage root}`` and the number of rows the chain does not place cleanly (a row two
    chains claim, or a loop): those block the apply."""
    by_id = {str(r["id"]): r for r in rows}
    succ = {str(r["id"]): str(r["supersededById"]) for r in rows if r.get("supersededById")}
    targets = set(succ.values())
    out: dict[str, str] = {}
    conflicts = 0
    for root in sorted(i for i in by_id if i not in targets):
        cur: str | None = root
        while cur and cur in by_id:
            if cur in out:
                conflicts += out[cur] != root
                break
            out[cur] = root
            cur = succ.get(cur)
    for rid in by_id:
        if rid not in out:
            out[rid] = rid
            conflicts += 1
    return out, conflicts


def display_codes(rows: list[dict[str, Any]], lineage: dict[str, str], form_of: dict[str, str]) -> dict[str, str]:
    """The section code (upper case) + the lineage root's position among the roots of its section in
    its form, by (sortOrder, createdAt, id); every wording of a lineage carries its root's code."""
    by_id = {str(r["id"]): r for r in rows}
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for r in rows:
        if lineage[str(r["id"])] == str(r["id"]):
            groups[(form_of.get(str(r["id"]), ""), str(r.get("sectionCode") or "").upper())].append(r)
    root_code: dict[str, str] = {}
    for (_form, sec), roots in groups.items():
        for n, r in enumerate(sorted(roots, key=_sort_key), start=1):
            root_code[str(r["id"])] = f"{sec}{n}"
    return {rid: root_code.get(lineage[rid], root_code.get(rid, "")) for rid in by_id}


# ── loading (read-only) ────────────────────────────────────────────────────────────────────────
async def schema_state(client: Any) -> dict[str, Any]:
    cols = await client.query_raw(
        "SELECT table_name AS t, column_name AS c FROM information_schema.columns WHERE table_schema = current_schema()"
    )
    columns: dict[str, set[str]] = defaultdict(set)
    for r in cols:
        columns[str(r["t"])].add(str(r["c"]))
    origins = await client.query_raw(
        "SELECT e.enumlabel AS v FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'AnswerOrigin'"
    )
    indexes = await client.query_raw(
        "SELECT indexname AS n FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ANY($1::text[])", list(INDEX_DDL),
    )
    return {
        "columns": columns,
        "u0": "QuestionnaireBinding" in columns and "QuestionnaireRespondent" in columns and "lineageId" in columns["QuestionnaireQuestion"],
        "workbook": any(str(r["v"]) == "WORKBOOK" for r in origins),
        "indexes": {str(r["n"]) for r in indexes},
    }


def _has(state: dict[str, Any], table: str, column: str) -> bool:
    return column in state["columns"].get(table, set())


def _opt(state: dict[str, Any], table: str, column: str, alias: str = "", cast: str = "") -> str:
    name = alias or column
    return f'"{column}"{cast} AS "{name}"' if _has(state, table, column) else f'NULL AS "{name}"'


async def load(client: Any, state: dict[str, Any]) -> dict[str, Any]:
    q = client.query_raw
    data: dict[str, Any] = {"state": state}
    data["forms"] = await q(
        'SELECT "id", "title", "ownerId", "designWorkshopId", "isActive", "isShared", "kind", "version", "deletedAt", "createdAt", '
        f'{_opt(state, "Questionnaire", "sortOrder")}, {_opt(state, "Questionnaire", "isDefault")} '
        'FROM "Questionnaire" WHERE "id" <> $1 ORDER BY "createdAt", "id"', RETIRED_ID,
    )
    data["form_sections"] = await q(
        'SELECT "id", "questionnaireId", "code", "title", "sortOrder", "isActive", "createdAt" FROM "QuestionnaireFormSection" '
        'ORDER BY "questionnaireId", "sortOrder", "createdAt", "id"'
    )
    data["form_questions"] = await q(
        'SELECT q."id", q."sectionId", s."questionnaireId", s."code" AS "sectionCode", q."sortOrder", q."isActive", q."retiredAt", '
        'q."supersededById", q."questionCode", q."createdAt", q."prompt" FROM "QuestionnaireFormQuestion" q '
        'JOIN "QuestionnaireFormSection" s ON s."id" = q."sectionId" ORDER BY s."questionnaireId", q."sortOrder", q."createdAt", q."id"'
    )
    form_section_ids = [str(r["id"]) for r in data["form_sections"]]
    form_question_ids = [str(r["id"]) for r in data["form_questions"]]
    data["sections_now"] = await q(
        f'SELECT "id", "code", "title", "sortOrder", "isActive", {_opt(state, "QuestionnaireSection", "questionnaireId")} '
        'FROM "QuestionnaireSection" ORDER BY "sortOrder", "id"'
    )
    data["global_sections"] = [r for r in data["sections_now"] if str(r["id"]) not in set(form_section_ids)]
    data["questions_now"] = await q(
        'SELECT "id", "sectionId", "sectionCode", "prompt", "sortOrder", "isActive", "retiredAt", "supersededById", "createdAt", "updatedAt", '
        f'{_opt(state, "QuestionnaireQuestion", "questionnaireId")}, {_opt(state, "QuestionnaireQuestion", "lineageId")}, '
        f'{_opt(state, "QuestionnaireQuestion", "displayCode")} FROM "QuestionnaireQuestion" ORDER BY "id"'
    )
    data["global_questions"] = [r for r in data["questions_now"] if str(r["id"]) not in set(form_question_ids)]
    data["section_statuses"] = await q('SELECT "id", "sectionId", "status"::text AS status FROM "QuestionnaireSectionStatus"')
    data["entries"] = await q(
        'SELECT e."id", e."questionnaireId", e."title", e."respondentName", e."source", e."notes", e."createdById", e."deletedAt", '
        'e."createdAt", u."name" AS "creatorName" FROM "QuestionnaireFormEntry" e LEFT JOIN "User" u ON u."id" = e."createdById" '
        'ORDER BY e."createdAt", e."id"'
    )
    data["form_answers"] = await q(
        'SELECT "id", "entryId", "questionId", "answerText", "notes", "answeredById", "createdAt", "updatedAt" FROM "QuestionnaireFormAnswer" '
        'ORDER BY "entryId", "updatedAt", "id"'
    )
    entry_ids = {str(e["id"]) for e in data["entries"]}
    interviews = await q(
        'SELECT "id", "title", "interviewDate", "notes", "language", "createdById", "createdAt", "workshopId", "designWorkshopId", '
        f'"deletedAt", {_opt(state, "QuestionnaireInterview", "origin", cast="::text")}, '
        f'{_opt(state, "QuestionnaireInterview", "questionnaireId")}, {_opt(state, "QuestionnaireInterview", "extraMetadata")} '
        'FROM "QuestionnaireInterview" ORDER BY "createdAt", "id"'
    )
    data["all_session_ids"] = {str(i["id"]) for i in interviews}

    def _from_sitting(row: dict[str, Any]) -> bool:
        meta = row.get("extraMetadata")
        meta = json.loads(meta) if isinstance(meta, str) else (meta or {})
        return bool(isinstance(meta, dict) and ((meta.get("questionnaireV2") or {}).get("unify") or {}).get("fromSitting"))

    data["foreign_collisions"] = sum(1 for i in interviews if str(i["id"]) in entry_ids and not _from_sitting(i))
    # A global interview: any interview that is not a migrated sitting and that no v2 route wrote
    # (U0 writes nothing while the switch is off; a session with a form other than the retired one is native).
    data["interviews"] = [
        i for i in interviews if str(i["id"]) not in entry_ids and i.get("questionnaireId") in (None, RETIRED_ID)
    ]
    native = [i for i in interviews if str(i["id"]) not in entry_ids and i.get("questionnaireId") not in (None, RETIRED_ID)]
    data["native_sessions"] = len(native)
    iids = [str(i["id"]) for i in data["interviews"]]
    data["participants"] = await q(
        'SELECT "interviewId", "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = ANY($1::text[]) '
        'ORDER BY "interviewId", "createdAt", "artisanId"', iids,
    )
    data["responses"] = await q(
        'SELECT "id", "interviewId", "questionId", "answerText", "notes", "answeredById", "answerOrigin", "updatedAt" '
        'FROM "QuestionnaireResponse" WHERE "interviewId" = ANY($1::text[]) ORDER BY "interviewId", "updatedAt", "id"', iids,
    )
    data["drafts"] = await q(
        'SELECT "id", "interviewId", "questionId", "status", "sourceMediaId" FROM "QuestionnaireAnswerDraft" '
        'WHERE "interviewId" = ANY($1::text[]) ORDER BY "id"', iids,
    )
    removed = 'AND m."removedAt" IS NULL ' if _has(state, "MediaFile", "removedAt") else ""
    data["media"] = await q(
        'SELECT m."id", m."questionnaireInterviewId" AS "interviewId", m."originalFilename", m."caption", m."extraMetadata", '
        'm."recordedAt", m."createdAt", m."uploadedById", m."checksum", '
        f'{_opt(state, "MediaFile", "contentSha256")}, m."transcriptStatus", m."transcriptText" FROM "MediaFile" m '
        "WHERE m.\"questionnaireInterviewId\" = ANY($1::text[]) AND m.\"mediaType\" IN ('AUDIO', 'VIDEO') "
        f'{removed}ORDER BY m."createdAt", m."id"', iids,
    )
    for m in data["media"]:  # the resolver reads the metadata as a mapping
        if isinstance(m.get("extraMetadata"), str):
            try:
                m["extraMetadata"] = json.loads(m["extraMetadata"])
            except ValueError:
                m["extraMetadata"] = None
    data["linked_media"] = int((await q('SELECT count(*) AS n FROM "MediaFile" WHERE "questionnaireInterviewId" IS NOT NULL'))[0]["n"])
    global_section_ids = [str(s["id"]) for s in data["global_sections"]]
    data["assigned_tasks"] = int((await q(
        'SELECT count(*) AS n FROM "AssignedTask" WHERE "sectionIds" && $1::text[]', global_section_ids,
    ))[0]["n"]) if global_section_ids else 0
    data["users"] = {str(r["id"]) for r in await q('SELECT "id" FROM "User"')}
    data["admins"] = await q(
        "SELECT \"id\", \"email\" FROM \"User\" WHERE \"role\"::text IN ('MASTER_ADMIN', 'ADMIN') ORDER BY (\"role\"::text = 'MASTER_ADMIN') DESC, \"createdAt\", \"id\""
    )
    dw_ids = sorted({str(f["designWorkshopId"]) for f in data["forms"] if f.get("designWorkshopId")}
                    | {str(i["designWorkshopId"]) for i in data["interviews"] if i.get("designWorkshopId")})
    data["consent_log"] = await q(
        'SELECT d."id", d."designWorkshopId" AS "subjectId", d."decision"::text AS state, d."source", d."actorId", '
        'COALESCE(d."recordedAt", d."createdAt") AS "recordedAt", d."note", (u."id" IS NOT NULL) AS "actorExists", '
        "EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"migratedFrom\" = 'DwWorkshopConsentDecision:' || d.\"id\") AS copied "
        'FROM "DwWorkshopConsentDecision" d LEFT JOIN "User" u ON u."id" = d."actorId" WHERE d."designWorkshopId" = ANY($1::text[]) '
        'ORDER BY COALESCE(d."recordedAt", d."createdAt"), d."createdAt", d."id"', dw_ids,
    )
    data["consent_cache"] = await q(
        'SELECT w."id", w."id" AS "subjectId", w."dictationConsent"::text AS state, NULL AS "source", w."dictationConsentById" AS "actorId", '
        'COALESCE(w."dictationConsentAt", w."updatedAt") AS "recordedAt", NULL AS "note", (u."id" IS NOT NULL) AS "actorExists", '
        "EXISTS (SELECT 1 FROM \"Consent\" c WHERE c.\"migratedFrom\" = 'DesignWorkshop.dictationConsent:' || w.\"id\") AS copied "
        'FROM "DesignWorkshop" w LEFT JOIN "User" u ON u."id" = w."dictationConsentById" '
        "WHERE w.\"id\" = ANY($1::text[]) AND w.\"dictationConsent\" <> 'NOT_RECORDED' "
        'AND NOT EXISTS (SELECT 1 FROM "DwWorkshopConsentDecision" d WHERE d."designWorkshopId" = w."id") ORDER BY w."id"', dw_ids,
    )
    # what the v2 tables hold already (nothing before the U0 release)
    data["v2"] = {"bindings": [], "respondents": set(), "answers": {}, "revisions": set(), "recordings": set(), "drafts": set(),
                  "researchers": set(), "reconstructed": set(), "retired": False, "global_q_form": {}, "global_s_form": {}}
    if state["u0"]:
        v2 = data["v2"]
        v2["bindings"] = await q('SELECT "questionnaireId", "designWorkshopId", "isPrimary" FROM "QuestionnaireBinding" WHERE "designWorkshopId" IS NOT NULL')
        v2["respondents"] = {str(r["id"]) for r in await q('SELECT "id" FROM "QuestionnaireRespondent"')}
        v2["answers"] = {
            (f"A:{r['artisanId']}" if r.get("artisanId") else f"R:{r['respondentId']}", str(r["questionLineageId"])): str(r["id"])
            for r in await q('SELECT "id", "artisanId", "respondentId", "questionLineageId" FROM "QuestionnaireAnswer"')
        }
        v2["revisions"] = {str(r["id"]) for r in await q('SELECT "id" FROM "QuestionnaireAnswerRevision"')}
        v2["recordings"] = {str(r["mediaFileId"]) for r in await q('SELECT "mediaFileId" FROM "QuestionnaireRecording"')}
        v2["drafts"] = {str(r["id"]) for r in await q('SELECT "id" FROM "QuestionnaireTakeDraft"')}
        v2["researchers"] = {(str(r["interviewId"]), str(r["userId"])) for r in await q('SELECT "interviewId", "userId" FROM "QuestionnaireInterviewResearcher"')}
        v2["reconstructed"] = {str(i["id"]) for i in interviews if i.get("origin") == "LEGACY_RECONSTRUCTED"}
        v2["retired"] = bool(await q('SELECT 1 FROM "Questionnaire" WHERE "id" = $1', RETIRED_ID))
    return data


# ── planning (pure) ────────────────────────────────────────────────────────────────────────────
@dataclass
class Plan:
    counts: dict[str, int] = field(default_factory=dict)
    pending: dict[str, int] = field(default_factory=dict)
    blockers: list[str] = field(default_factory=list)
    forms: list[dict[str, Any]] = field(default_factory=list)
    form_updates: list[dict[str, Any]] = field(default_factory=list)
    retired: dict[str, Any] | None = None
    sections: list[dict[str, Any]] = field(default_factory=list)       # form sections to insert
    global_sections: list[str] = field(default_factory=list)           # ids to move onto the retired instrument
    questions: list[dict[str, Any]] = field(default_factory=list)      # form questions to insert
    global_questions: list[dict[str, Any]] = field(default_factory=list)
    primary: dict[str, str] = field(default_factory=dict)              # designWorkshopId -> formId
    sessions: list[dict[str, Any]] = field(default_factory=list)       # from sittings
    reconstruct: list[dict[str, Any]] = field(default_factory=list)    # global interviews
    respondents: list[dict[str, Any]] = field(default_factory=list)
    researchers: list[tuple[str, str, str]] = field(default_factory=list)
    answers: list[dict[str, Any]] = field(default_factory=list)
    recordings: list[dict[str, Any]] = field(default_factory=list)
    drafts: list[dict[str, Any]] = field(default_factory=list)
    consents: list[dict[str, Any]] = field(default_factory=list)
    rows: list[list[str]] = field(default_factory=list)                # the CSV


def _resolver():
    _overlay()
    from app.questionnaire import legacy_resolver

    return legacy_resolver


def build_plan(data: dict[str, Any], *, default_form: str | None = None, primary_choice: dict[str, str] | None = None) -> Plan:
    plan = Plan()
    v2 = data["v2"]
    state = data["state"]
    forms = data["forms"]
    form_by_id = {str(f["id"]): f for f in forms}
    sections_now = {str(s["id"]): s for s in data["sections_now"]}
    questions_now = {str(q["id"]): q for q in data["questions_now"]}

    # 1. forms: library order, the default form
    live_forms = [f for f in forms if not f.get("deletedAt")]
    if default_form and (default_form not in form_by_id or form_by_id[default_form].get("deletedAt")):
        plan.blockers.append(f"--default-form {default_form} is not a live form")
    if default_form and default_form in form_by_id and not form_by_id[default_form].get("isShared"):
        plan.blockers.append(f"--default-form {default_form} is not shared with everyone; share it first")
    for n, f in enumerate(forms, start=1):
        want = {"id": str(f["id"]), "sortOrder": n, "isDefault": str(f["id"]) == default_form}
        plan.form_updates.append(want)
    plan.pending["formOrderAndDefault"] = sum(
        1 for w in plan.form_updates
        if int(form_by_id[w["id"]].get("sortOrder") or 0) != w["sortOrder"] or bool(form_by_id[w["id"]].get("isDefault")) != w["isDefault"]
    )
    titles = Counter(str(f["title"]).strip() for f in live_forms if f.get("isShared"))
    for title, n in sorted(titles.items()):
        if n > 1:
            plan.blockers.append(f"{n} live shared forms are titled {title!r}; the shared-title unique needs one")

    # 2. primary bindings: one per design workshop
    by_dw: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for f in live_forms:
        if f.get("designWorkshopId"):
            by_dw[str(f["designWorkshopId"])].append(f)
    choice = dict(primary_choice or {})
    for dw, chosen in sorted(choice.items()):
        if chosen not in {str(f["id"]) for f in by_dw.get(dw, [])}:
            plan.blockers.append(f"--primary {dw}={chosen}: that live form is not attached to that design workshop")
    for dw, attached in sorted(by_dw.items()):
        proposed = min(attached, key=lambda f: (f.get("kind") != "WORKSHOP_INTERVIEW", moment(f["createdAt"]), str(f["id"])))
        plan.primary[dw] = choice.get(dw, str(proposed["id"]))
    existing = {(str(b["questionnaireId"]), str(b["designWorkshopId"])): bool(b["isPrimary"]) for b in v2["bindings"]}
    if state["u0"]:
        missing = [f for f in forms if f.get("designWorkshopId") and (str(f["id"]), str(f["designWorkshopId"])) not in existing]
        if missing:
            plan.blockers.append(f"{len(missing)} attached forms have no binding row (the U0 mirror trigger should have made them)")
    plan.pending["primaryBindings"] = sum(1 for dw, fid in plan.primary.items() if not existing.get((fid, dw)))
    plan.pending["bindingsToUnmark"] = sum(
        1 for (fid, dw), primary in existing.items() if primary and plan.primary.get(dw) != fid
    )

    # 3. sections and questions of the forms, ids kept
    secs_by_form: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for s in data["form_sections"]:
        secs_by_form[str(s["questionnaireId"])].append(s)
    renumbered = 0
    for secs in secs_by_form.values():
        orders = [int(s["sortOrder"]) for s in secs]
        dense = len(set(orders)) != len(orders)
        renumbered += dense
        for n, s in enumerate(sorted(secs, key=_sort_key), start=1):
            plan.sections.append({"id": str(s["id"]), "sortOrder": n if dense else int(s["sortOrder"])})
    fq = data["form_questions"]
    form_lineage, form_conflicts = lineages(fq)
    form_codes = display_codes(fq, form_lineage, {str(q["id"]): str(q["questionnaireId"]) for q in fq})
    for q in fq:
        qid = str(q["id"])
        plan.questions.append({"id": qid, "lineage": form_lineage[qid], "code": form_codes[qid]})
    plan.pending["sections"] = sum(1 for s in plan.sections if s["id"] not in sections_now)
    plan.pending["questions"] = sum(1 for q in plan.questions if q["id"] not in questions_now)

    # 4. the global instrument becomes the retired one
    gq = data["global_questions"]
    g_lineage, g_conflicts = lineages(gq)
    computed = display_codes(gq, g_lineage, {})
    for q in gq:
        qid = str(q["id"])
        plan.global_questions.append({
            "id": qid, "lineage": str(q.get("lineageId") or g_lineage[qid]), "code": str(q.get("displayCode") or computed[qid]),
        })
    plan.global_sections = [str(s["id"]) for s in data["global_sections"]]
    if data["global_sections"] or gq or data["interviews"]:
        owner = data["admins"][0]["id"] if data["admins"] else None
        if not owner:
            plan.blockers.append("no Admin or Master Admin account to own the retired instrument (the column is required)")
        plan.retired = {"ownerId": owner}
    plan.pending["retiredInstrument"] = int(plan.retired is not None and not v2["retired"])
    plan.pending["globalSectionsMoved"] = sum(1 for s in data["global_sections"] if s.get("questionnaireId") != RETIRED_ID)
    plan.pending["globalQuestionsMoved"] = sum(1 for q in gq if q.get("questionnaireId") != RETIRED_ID)
    if form_conflicts or g_conflicts:
        plan.blockers.append(f"{form_conflicts + g_conflicts} questions sit in a broken supersede chain (claimed twice, or a loop)")
    live = Counter((str(q["questionnaireId"]), c["code"]) for q, c in zip(fq, plan.questions) if not q.get("retiredAt") and not q.get("supersededById"))
    live.update(("retired", c["code"]) for q, c in zip(gq, plan.global_questions) if not q.get("retiredAt") and not q.get("supersededById"))
    dupes = sum(n - 1 for n in live.values() if n > 1)
    if dupes:
        plan.blockers.append(f"{dupes} live questions would share a display code with another in their form")

    lineage_of = {q["id"]: q["lineage"] for q in plan.questions} | {q["id"]: q["lineage"] for q in plan.global_questions}

    # 5. section overrides: kept as they are
    statuses = Counter(str(s["status"]) for s in data["section_statuses"])
    outside = sum(n for s, n in statuses.items() if s not in ("COMPLETED", "NEEDS_REVIEW", "NEEDS_REDO"))

    # 6. sittings -> sessions, respondents, answers
    answers_by_entry: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for a in data["form_answers"]:
        answers_by_entry[str(a["entryId"])].append(a)
    sources = Counter()
    kinds = Counter()
    long_notes = 0
    for e in data["entries"]:
        eid = str(e["id"])
        form = form_by_id.get(str(e["questionnaireId"]), {})
        source = str(e.get("source") or "APP").upper()
        if source not in ("APP", "UPLOAD", "HANDOFF"):
            source = "APP"
        sources[source] += 1
        notes = (e.get("notes") or "").strip() or None
        note = notes if notes and len(notes) <= 80 else None
        long_notes += bool(notes and not note)
        name = (e.get("respondentName") or "").strip()
        kind = "GUEST" if name else "SELF"
        kinds[kind] += 1
        rid = f"u5-resp-{eid}"
        plan.sessions.append({
            "id": eid, "questionnaireId": str(e["questionnaireId"]), "designWorkshopId": form.get("designWorkshopId"),
            "source": source, "note": note, "sessionDate": ist_day(e["createdAt"]),
        })
        plan.respondents.append({
            "id": rid, "sessionId": eid, "name": (name or str(e.get("creatorName") or "Self").strip() or "Self")[:120],
            "kind": kind, "userId": None if name else str(e["createdById"]), "notes": notes if notes and not note else None,
            "createdById": str(e["createdById"]),
        })
        plan.researchers.append((eid, str(e["createdById"]), "LEAD"))
        for a in answers_by_entry.get(eid, []):
            if str(a["answeredById"]) != str(e["createdById"]):
                plan.researchers.append((eid, str(a["answeredById"]), "CONTRIBUTOR"))
        groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for a in answers_by_entry.get(eid, []):
            if (a.get("answerText") or "").strip() or (a.get("notes") or "").strip():
                groups[lineage_of.get(str(a["questionId"]), str(a["questionId"]))].append(a)
        for lineage_id, rows in groups.items():
            rows.sort(key=lambda a: (moment(a["updatedAt"]), str(a["id"])))
            plan.answers.append({
                "id": stable_id("u5-ans", f"R:{rid}", lineage_id), "owner": ("respondentId", rid), "questionnaireId": str(e["questionnaireId"]),
                "lineage": lineage_id, "source": "form",
                "revisions": [{"id": f"u5-rev-{a['id']}", "sourceId": str(a["id"]), "origin": "WORKBOOK" if source == "UPLOAD" else "TYPED",
                               "attribution": "INDIVIDUAL"} for a in rows],
            })
    if data["foreign_collisions"]:
        plan.blockers.append(f"{data['foreign_collisions']} sittings share an id with an interview that is not theirs")

    # 7. global interviews, reconstructed in place
    parts: dict[str, list[str]] = defaultdict(list)
    for p in data["participants"]:
        parts[str(p["interviewId"])].append(str(p["artisanId"]))
    clips: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for m in data["media"]:
        clips[str(m["interviewId"])].append(m)
    multi_day = group = artisanless = both_kinds = unmapped = 0
    for i in data["interviews"]:
        iid = str(i["id"])
        own = clips.get(iid, [])
        days = sorted({d for d in (ist_day(m.get("recordedAt") or m.get("createdAt")) for m in own) if d})
        multi_day += len(days) > 1
        typed = ist_day(i.get("interviewDate"))
        code, other = language(i.get("language"))
        unmapped += code == "other"
        notes = (i.get("notes") or "").strip()
        dropped = i.get("workshopId") if i.get("workshopId") and i.get("designWorkshopId") else None
        both_kinds += bool(dropped)
        group += len(parts.get(iid, [])) > 1
        artisanless += not parts.get(iid)
        plan.reconstruct.append({
            "id": iid, "sessionDate": typed or (days[0] if days else ist_day(i["createdAt"])), "estimated": typed is None,
            "languageCode": code, "languageOther": other, "note": notes if notes and len(notes) <= 80 else None,
            "workshopId": None if dropped else i.get("workshopId"), "droppedWorkshopId": dropped,
        })
        plan.researchers.append((iid, str(i["createdById"]), "LEAD"))
        for m in own:
            if str(m["uploadedById"]) != str(i["createdById"]):
                plan.researchers.append((iid, str(m["uploadedById"]), "CONTRIBUTOR"))
    responses_by_interview: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in data["responses"]:
        if (r.get("answerText") or "").strip() or (r.get("notes") or "").strip():
            responses_by_interview[str(r["interviewId"])].append(r)
    creator = {str(i["id"]): str(i["createdById"]) for i in data["interviews"]}
    for r in data["responses"]:
        if str(r["answeredById"]) != creator.get(str(r["interviewId"])):
            plan.researchers.append((str(r["interviewId"]), str(r["answeredById"]), "CONTRIBUTOR"))
    unattached = 0
    per_owner: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for iid, rows in responses_by_interview.items():
        people = parts.get(iid, [])
        if not people:
            unattached += len(rows)
            continue
        attribution = "GROUP_SHARED" if len(people) > 1 else "INDIVIDUAL"
        for r in rows:
            lineage_id = lineage_of.get(str(r["questionId"]), str(r["questionId"]))
            for a in people:
                origin = str(r.get("answerOrigin") or "")
                origin = origin if origin in ("MACHINE_SUGGESTED", "MACHINE_SUGGESTED_EDITED") else "TYPED"
                per_owner[(a, lineage_id)].append({"id": stable_id("u5-rev", str(r["id"]), a), "sourceId": str(r["id"]), "origin": origin,
                                                   "attribution": attribution, "at": moment(r["updatedAt"])})
    for (artisan, lineage_id), revs in sorted(per_owner.items()):
        revs.sort(key=lambda v: (v["at"], v["id"]))
        plan.answers.append({"id": stable_id("u5-ans", f"A:{artisan}", lineage_id), "owner": ("artisanId", artisan), "questionnaireId": RETIRED_ID,
                             "lineage": lineage_id, "source": "response", "revisions": revs})
    elsewhere = sum(1 for a in plan.answers if v2["answers"].get((f"{'A' if a['owner'][0] == 'artisanId' else 'R'}:{a['owner'][1]}", a["lineage"]), a["id"]) != a["id"])
    if elsewhere:
        plan.blockers.append(f"{elsewhere} answers already exist in v2 for the same person and question (native content)")
    if data["native_sessions"]:
        plan.blockers.append(f"{data['native_sessions']} native v2 sessions exist already; the switch must still be off when this runs")

    # 8. clips -> recordings
    legacy = _resolver() if data["media"] else None
    gsections = [{"id": str(s["id"]), "code": str(s["code"])} for s in data["global_sections"]]
    gquestions = [{**q, "lineageId": lineage_of.get(str(q["id"]))} for q in gq]
    seen_sha: dict[str, str] = {}
    for i in data["interviews"]:
        iid = str(i["id"])
        for m in clips.get(iid, []):
            res = legacy.resolve(m, gquestions, gsections)
            filing, decision = ("NEEDS_DECISION", "UNRESOLVED_LEGACY") if res.needs_decision else ("FILED", None)
            sha = sha_of(m)
            if sha and sha in seen_sha:
                filing, decision = "NEEDS_DECISION", ("CONCURRENT" if seen_sha[sha] == iid else "SAME_FILE_OTHER_SESSION")
            elif sha:
                seen_sha[sha] = iid
            if not parts.get(iid):
                filing, decision = "NEEDS_DECISION", "NO_PARTICIPANTS"
            rec, created = moment(m.get("recordedAt")), moment(m.get("createdAt"))
            device = bool(rec and created and abs((rec - created).total_seconds()) > 60)
            plan.recordings.append({
                "id": f"u5-rec-{m['id']}", "mediaId": str(m["id"]), "sessionId": iid, "subjects": parts.get(iid, []),
                "targets": [{"key": f"S:{t['sectionId']}" if t.get("sectionId") else f"Q:{t['questionLineageId']}", **t} for t in res.targets],
                "filing": filing, "decision": decision, "provenance": res.provenance, "step": res.step,
                "source": "DEVICE" if device else "UPLOAD_TIME", "uploader": str(m["uploadedById"]),
            })
    rec_of_media = {r["mediaId"]: r["id"] for r in plan.recordings}
    pending_drafts = [d for d in data["drafts"] if str(d.get("status") or "") == "PENDING"]
    for d in pending_drafts:
        rid = rec_of_media.get(str(d.get("sourceMediaId") or ""))
        if rid:
            plan.drafts.append({"id": f"u5-draft-{d['id']}", "sourceId": str(d["id"]), "recordingId": rid,
                                "lineage": lineage_of.get(str(d["questionId"]), str(d["questionId"]))})

    # 9. consent onto the ledger
    for origin, rows in (("DwWorkshopConsentDecision", data["consent_log"]), ("DesignWorkshop.dictationConsent", data["consent_cache"])):
        for r in rows:
            if r.get("copied") or str(r.get("state")) not in ("GRANTED", "REFUSED"):
                continue
            plan.consents.append({"origin": origin, "id": str(r["id"]), "subjectId": str(r["subjectId"]), "state": str(r["state"]),
                                  "source": r.get("source"), "actorId": r.get("actorId") if r.get("actorExists") else None,
                                  "recordedAt": moment(r.get("recordedAt")), "note": r.get("note")})

    # a researcher must still have an account (the table refuses anyone else)
    plan.researchers = [r for r in dict.fromkeys(plan.researchers) if r[1] in data["users"]]

    # counts
    sessions_ids = {s["id"] for s in plan.sessions}
    have_answers = set(v2["answers"].values())
    plan.pending.update({
        "sessionsFromSittings": sum(1 for s in plan.sessions if s["id"] not in data["all_session_ids"]),
        "sessionsReconstructed": sum(1 for r in plan.reconstruct if r["id"] not in v2["reconstructed"]),
        "respondents": sum(1 for r in plan.respondents if r["id"] not in v2["respondents"]),
        "researchers": len({(s, u) for s, u, _ in plan.researchers} - v2["researchers"]),
        "answers": sum(1 for a in plan.answers if a["id"] not in have_answers),
        "revisions": sum(1 for a in plan.answers for r in a["revisions"] if r["id"] not in v2["revisions"]),
        "recordings": sum(1 for r in plan.recordings if r["mediaId"] not in v2["recordings"]),
        "takeDrafts": sum(1 for d in plan.drafts if d["id"] not in v2["drafts"]),
        "consentRows": len(plan.consents),
    })
    revisions = [r for a in plan.answers for r in a["revisions"]]
    plan.counts = {
        "forms": len(forms), "formsShared": sum(1 for f in forms if f.get("isShared")), "formsDeleted": sum(1 for f in forms if f.get("deletedAt")),
        "formSections": len(data["form_sections"]), "formQuestions": len(fq),
        "formQuestionsSuperseded": sum(1 for q in fq if q.get("supersededById")), "formQuestionsRetired": sum(1 for q in fq if q.get("retiredAt")),
        "formSectionOrdersRenumbered": renumbered,
        "globalSections": len(data["global_sections"]), "globalQuestions": len(gq),
        "designWorkshopsWithForms": len(by_dw), "primaryBindings": len(plan.primary),
        "sittings": len(data["entries"]), "sittingsDeleted": sum(1 for e in data["entries"] if e.get("deletedAt")),
        "sittingsApp": sources["APP"], "sittingsUpload": sources["UPLOAD"], "sittingsHandoff": sources["HANDOFF"],
        "respondentsGuest": kinds["GUEST"], "respondentsSelf": kinds["SELF"], "notesMovedToRespondent": long_notes,
        "sittingAnswers": len(data["form_answers"]),
        "globalInterviews": len(data["interviews"]), "globalInterviewsDeleted": sum(1 for i in data["interviews"] if i.get("deletedAt")),
        "groupSittings": group, "multiDaySessions": multi_day, "interviewsWithoutParticipants": artisanless,
        "interviewsWithBothWorkshops": both_kinds, "languagesMappedToOther": unmapped,
        "responses": len(data["responses"]), "responsesWithoutParticipants": unattached,
        "drafts": len(data["drafts"]), "draftsPending": len(pending_drafts), "takeDrafts": len(plan.drafts),
        "clips": len(data["media"]), "mediaFilesLinkedToInterviews": data["linked_media"],
        "recordingsFiled": sum(1 for r in plan.recordings if r["filing"] == "FILED"),
        "recordingsNeedsDecision": sum(1 for r in plan.recordings if r["filing"] == "NEEDS_DECISION"),
        "recordingTargets": sum(len(r["targets"]) for r in plan.recordings), "recordingSubjects": sum(len(r["subjects"]) for r in plan.recordings),
        "sessions": len(sessions_ids) + len(plan.reconstruct), "answers": len(plan.answers), "revisions": len(revisions),
        "revisionsTyped": sum(1 for r in revisions if r["origin"] == "TYPED"), "revisionsWorkbook": sum(1 for r in revisions if r["origin"] == "WORKBOOK"),
        "revisionsMachine": sum(1 for r in revisions if r["origin"].startswith("MACHINE")),
        "sectionStatuses": len(data["section_statuses"]), **{f"sectionStatus{s.title().replace('_', '')}": n for s, n in sorted(statuses.items())},
        "sectionStatusesOutsideThreeStates": outside,
        "assignedTasksOnGlobalSections": data["assigned_tasks"], "workshopConsentsToLedger": len(plan.consents),
    }
    plan.forms = [
        {"id": str(f["id"]), "title": str(f["title"]), "owner": str(f["ownerId"]), "kind": f.get("kind") or "", "isShared": bool(f.get("isShared")),
         "deleted": bool(f.get("deletedAt")), "designWorkshopId": f.get("designWorkshopId") or "",
         "primary": bool(f.get("designWorkshopId") and plan.primary.get(str(f["designWorkshopId"])) == str(f["id"])),
         "isDefault": str(f["id"]) == default_form,
         "sections": len(secs_by_form.get(str(f["id"]), [])), "questions": sum(1 for q in fq if str(q["questionnaireId"]) == str(f["id"]))}
        for f in forms
    ]
    sec_state = {str(s["id"]): s for s in data["form_sections"]}
    for s in sorted(plan.sections, key=lambda s: (str(sec_state[s["id"]]["questionnaireId"]), s["sortOrder"])):
        row = sec_state[s["id"]]
        plan.rows.append(["SECTION", str(row["questionnaireId"]), str(row["code"]), s["id"], "", "", "active" if row.get("isActive") else "retired", "", str(s["sortOrder"])])
    for q, c in zip(fq, plan.questions):
        state_q = "superseded" if q.get("supersededById") else ("retired" if q.get("retiredAt") else ("active" if q.get("isActive") else "inactive"))
        plan.rows.append(["QUESTION", str(q["questionnaireId"]), str(q["sectionCode"]), c["id"], c["lineage"], c["code"], state_q, q.get("questionCode") or "", str(q["sortOrder"])])
    for s in data["global_sections"]:
        plan.rows.append(["GLOBAL_SECTION", RETIRED_ID, str(s["code"]), str(s["id"]), "", "", "active" if s.get("isActive") else "inactive", "", str(s["sortOrder"])])
    for q, c in zip(gq, plan.global_questions):
        plan.rows.append(["GLOBAL_QUESTION", RETIRED_ID, str(q["sectionCode"]), c["id"], c["lineage"], c["code"], "active" if q.get("isActive") else "inactive", "", str(q["sortOrder"])])
    if outside:
        plan.blockers.append(f"{outside} section overrides are outside the three states; the switch-on enum cast would fail")
    return plan


def changes(plan: Plan) -> int:
    return sum(v for k, v in plan.pending.items())


# ── reports ────────────────────────────────────────────────────────────────────────────────────
def report_csv(plan: Plan) -> str:
    buffer = io.StringIO()
    w = csv.writer(buffer, lineterminator="\n")
    w.writerow(["kind", "formId", "sectionCode", "id", "lineageId", "displayCode", "state", "questionCode", "sortOrder"])
    for row in plan.rows:
        w.writerow(row)
    return buffer.getvalue()


def report_md(plan: Plan, csv_text: str, *, default_form: str | None) -> str:
    lines = [
        "# Questionnaire unification (designer-portal): dry-run report",
        "",
        "Nothing has been written. The owner approves this report by adding a line",
        "`Approved-by: <owner email> <YYYY-MM-DD>` before `--apply`; apply refuses if the data, or the",
        "choices below, have changed since. No transcription is queued, retried or resumed.",
        "",
        "## Choices",
        "",
        f"- Default form: {default_form or 'none chosen (the owner names one with --default-form)'}",
        *[f"- Primary form of design workshop {dw}: {fid}" for dw, fid in sorted(plan.primary.items())],
        "",
        "## What is there",
        "",
        "| What | Count |",
        "|---|---|",
        *[f"| {k} | {v} |" for k, v in plan.counts.items()],
        "",
        EXPECTED,
        "",
        "## Still to write",
        "",
        "| What | Rows |",
        "|---|---|",
        *[f"| {k} | {v} |" for k, v in plan.pending.items()],
        f"| total | {changes(plan)} |",
        "",
        "## Forms",
        "",
        "| Form | Title | Owner | Kind | Shared | Deleted | Design workshop | Primary | Default | Sections | Questions |",
        "|---|---|---|---|---|---|---|---|---|---|---|",
        *[f"| {f['id']} | {f['title'].replace('|', '/')} | {f['owner']} | {f['kind'] or 'not stated'} | {'yes' if f['isShared'] else 'no'} | "
          f"{'yes' if f['deleted'] else 'no'} | {f['designWorkshopId'] or '-'} | {'yes' if f['primary'] else 'no'} | "
          f"{'yes' if f['isDefault'] else 'no'} | {f['sections']} | {f['questions']} |" for f in plan.forms],
        "",
        "## Blockers",
        "",
        *([f"- {b}" for b in plan.blockers] or ["- none"]),
        "",
        f"Sections and questions (ids kept, lineage, display code): the CSV beside this file (sha256 {hashlib.sha256(csv_text.encode()).hexdigest()}).",
        "",
    ]
    return "\n".join(lines)


def report_names() -> tuple[str, str]:
    from app.services import naming

    md = naming.export_filename(entity="Questionnaire", what="Unify-Dry-Run-Report", ext="md")
    return md, md[:-3] + ".csv"


# ── the deferred prerequisites ─────────────────────────────────────────────────────────────────
def statements(sql: str) -> list[str]:
    """The statements of a migration file: comments dropped, split on ``;`` outside ``$$`` bodies."""
    out, buf, quoted = [], [], False
    for line in sql.splitlines():
        if not quoted and line.strip().startswith("--"):
            continue
        buf.append(line)
        quoted ^= line.count("$$") % 2 == 1
        if not quoted and line.rstrip().endswith(";"):
            text = "\n".join(buf).strip().rstrip(";").strip()
            if text:
                out.append(text)
            buf = []
    tail = "\n".join(buf).strip().rstrip(";").strip()
    if tail:
        out.append(tail)
    return out


async def apply_prerequisites(client: Any) -> list[str]:
    done = []
    for name in PREREQUISITES:
        for stmt in statements((DEFERRED / name / "migration.sql").read_text(encoding="utf-8")):
            await client.execute_raw(stmt)
        done.append(name)
    return done


# ── snapshot, apply, undo ──────────────────────────────────────────────────────────────────────
def snapshot_schema(run_id: str) -> str:
    return "questionnaire_unify_" + run_id.replace("-", "_")


async def _schema_exists(client: Any, name: str) -> bool:
    return bool(await client.query_raw("SELECT 1 FROM information_schema.schemata WHERE schema_name = $1", name))


async def _meta(client: Any, snap: str) -> dict[str, Any]:
    rows = await client.query_raw(f'SELECT "key", "value"::text AS "value" FROM "{snap}"."_meta"')
    return {str(r["key"]): json.loads(r["value"]) for r in rows}


async def _set_meta(client: Any, snap: str, key: str, value: Any) -> None:
    await client.execute_raw(
        f'INSERT INTO "{snap}"."_meta" ("key", "value") VALUES ($1, $2::jsonb) ON CONFLICT ("key") DO UPDATE SET "value" = EXCLUDED."value"',
        key, json.dumps(value, default=str),
    )


async def take_snapshot(client: Any, run_id: str, *, approver: str, state: dict[str, Any]) -> str:
    snap = snapshot_schema(run_id)
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=600)) as tx:
        await tx.execute_raw(f'CREATE SCHEMA "{snap}"')
        await tx.execute_raw(f'CREATE TABLE "{snap}"."_meta" ("key" TEXT PRIMARY KEY, "value" JSONB NOT NULL)')
        for table in TABLES:
            await tx.execute_raw(f'CREATE TABLE "{snap}"."{table}" AS TABLE "{table}"')
        counts = {t: int((await tx.query_raw(f'SELECT count(*) AS n FROM "{snap}"."{t}"'))[0]["n"]) for t in TABLES}
        await _set_meta(tx, snap, "run", {"runId": run_id, "approvedBy": approver, "takenAt": datetime.now(UTC).isoformat(), "rows": counts})
        await _set_meta(tx, snap, "indexes", sorted(state["indexes"]))
        await _set_meta(tx, snap, "workbookBefore", state["workbook"])
    return snap


async def _pk(client: Any, table: str) -> list[str]:
    rows = await client.query_raw(
        "SELECT a.attname AS c FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
        "WHERE i.indrelid = format('%I.%I', current_schema(), $1::text)::regclass AND i.indisprimary ORDER BY array_position(i.indkey, a.attnum)",
        table,
    )
    return [str(r["c"]) for r in rows]


async def _cols(client: Any, schema: str, table: str) -> list[str]:
    rows = await client.query_raw(
        "SELECT column_name AS c FROM information_schema.columns WHERE table_schema = $1 AND table_name = $2 ORDER BY ordinal_position", schema, table,
    )
    return [str(r["c"]) for r in rows]


def _on(pk: list[str], a: str, b: str) -> str:
    return " AND ".join(f'{a}."{c}" = {b}."{c}"' for c in pk)


async def write_plan(tx: Any, plan: Plan, run_id: str, approver_id: str | None) -> int:
    from app.questionnaire import titles
    from app.transcription import ledger

    n = 0
    ex = tx.execute_raw
    # forms
    # the old default is cleared before the new one is set (one default, a partial unique)
    for f in sorted(plan.form_updates, key=lambda f: f["isDefault"]):
        n += int(await ex('UPDATE "Questionnaire" SET "sortOrder" = $2, "isDefault" = $3 WHERE "id" = $1 AND ("sortOrder" <> $2 OR "isDefault" <> $3)',
                          f["id"], f["sortOrder"], f["isDefault"]) or 0)
    if plan.retired is not None:
        n += int(await ex(
            'INSERT INTO "Questionnaire" ("id", "title", "description", "ownerId", "isActive", "isShared", "deletedAt", "updatedAt") '
            "VALUES ($1, $2, 'The global interview form, retired when the forms were unified. Kept so old tasks and answers still resolve.', "
            "$3, false, false, now(), now()) ON CONFLICT (\"id\") DO NOTHING",
            RETIRED_ID, RETIRED_TITLE, plan.retired["ownerId"],
        ) or 0)
    # bindings: one primary per design workshop (unmark first, the partial unique allows one)
    for dw, fid in plan.primary.items():
        n += int(await ex('UPDATE "QuestionnaireBinding" SET "isPrimary" = false WHERE "designWorkshopId" = $1 AND "questionnaireId" <> $2 AND "isPrimary"', dw, fid) or 0)
    for dw, fid in plan.primary.items():
        n += int(await ex('UPDATE "QuestionnaireBinding" SET "isPrimary" = true WHERE "designWorkshopId" = $1 AND "questionnaireId" = $2 AND NOT "isPrimary"', dw, fid) or 0)
    # sections and questions
    if plan.sections:
        n += int(await ex(
            'INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "isActive", "createdAt", "updatedAt", "questionnaireId") '
            'SELECT s."id", s."code", s."title", p."sortOrder", s."isActive", s."createdAt", s."updatedAt", s."questionnaireId" '
            'FROM jsonb_to_recordset($1::jsonb) AS p("id" text, "sortOrder" int) JOIN "QuestionnaireFormSection" s ON s."id" = p."id" '
            'ON CONFLICT ("id") DO NOTHING', json.dumps(plan.sections),
        ) or 0)
    if plan.global_sections:
        n += int(await ex('UPDATE "QuestionnaireSection" SET "questionnaireId" = $2 WHERE "id" = ANY($1::text[]) AND "questionnaireId" IS DISTINCT FROM $2',
                          plan.global_sections, RETIRED_ID) or 0)
    if plan.questions:
        n += int(await ex(
            'INSERT INTO "QuestionnaireQuestion" ("id", "sectionCode", "sectionTitle", "prompt", "sortOrder", "isActive", "retiredAt", "supersededById", '
            '"createdAt", "updatedAt", "sectionId", "questionCode", "questionnaireId", "helpText", "isRequired", "lineageId", "displayCode") '
            'SELECT q."id", s."code", s."title", q."prompt", q."sortOrder", q."isActive", q."retiredAt", q."supersededById", q."createdAt", q."updatedAt", '
            'q."sectionId", q."questionCode", s."questionnaireId", q."helpText", q."isRequired", p."lineage", p."code" '
            'FROM jsonb_to_recordset($1::jsonb) AS p("id" text, "lineage" text, "code" text) JOIN "QuestionnaireFormQuestion" q ON q."id" = p."id" '
            'JOIN "QuestionnaireFormSection" s ON s."id" = q."sectionId" ON CONFLICT ("id") DO NOTHING', json.dumps(plan.questions),
        ) or 0)
    if plan.global_questions:
        n += int(await ex(
            'UPDATE "QuestionnaireQuestion" q SET "questionnaireId" = $2, "lineageId" = COALESCE(q."lineageId", p."lineage"), '
            '"displayCode" = COALESCE(q."displayCode", p."code") FROM jsonb_to_recordset($1::jsonb) AS p("id" text, "lineage" text, "code" text) '
            'WHERE q."id" = p."id" AND q."questionnaireId" IS DISTINCT FROM $2', json.dumps(plan.global_questions), RETIRED_ID,
        ) or 0)
    # sittings -> sessions
    for s in plan.sessions:
        n += int(await ex(
            'INSERT INTO "QuestionnaireInterview" ("id", "title", "legacyTitle", "questionnaireId", "designWorkshopId", "createdById", "createdAt", '
            '"updatedAt", "recordedAt", "submittedAt", "sessionDate", "sessionDateEstimated", "state", "closedAt", "origin", "source", "note", '
            '"deletedAt", "deletedById", "extraMetadata") '
            'SELECT e."id", e."title", e."title", e."questionnaireId", $2, e."createdById", e."createdAt", e."updatedAt", e."createdAt", e."createdAt", '
            "$3::date, true, 'CLOSED', e.\"updatedAt\", 'LEGACY_RECONSTRUCTED', $4::\"SessionSource\", $5, e.\"deletedAt\", e.\"deletedById\", $6::jsonb "
            'FROM "QuestionnaireFormEntry" e WHERE e."id" = $1 ON CONFLICT ("id") DO NOTHING',
            s["id"], s["designWorkshopId"], s["sessionDate"].isoformat() if s["sessionDate"] else None, s["source"], s["note"],
            json.dumps({"questionnaireV2": {"unify": {"runId": run_id, "fromSitting": True}}}),
        ) or 0)
    for r in plan.respondents:
        n += int(await ex(
            'INSERT INTO "QuestionnaireRespondent" ("id", "sessionId", "name", "kind", "userId", "notes", "createdById", "createdAt", "updatedAt") '
            'SELECT $1, e."id", $3, $4::"RespondentKind", $5, $6, $7, e."createdAt", e."updatedAt" FROM "QuestionnaireFormEntry" e WHERE e."id" = $2 '
            'ON CONFLICT ("id") DO NOTHING',
            r["id"], r["sessionId"], r["name"], r["kind"], r["userId"], r["notes"], r["createdById"],
        ) or 0)
    # global interviews, in place
    for r in plan.reconstruct:
        extra = {"questionnaireV2": {"unify": {"runId": run_id, "reconstructed": True, **({"droppedWorkshopId": r["droppedWorkshopId"]} if r["droppedWorkshopId"] else {})}}}
        n += int(await ex(
            'UPDATE "QuestionnaireInterview" SET "questionnaireId" = $2, "origin" = \'LEGACY_RECONSTRUCTED\', "legacyTitle" = COALESCE("legacyTitle", "title"), '
            '"sessionDate" = $3::date, "sessionDateEstimated" = $4, "state" = \'CLOSED\', "closedAt" = COALESCE("closedAt", "updatedAt"), '
            '"languageCode" = COALESCE("languageCode", $5), "languageOther" = COALESCE("languageOther", $6), "note" = COALESCE("note", $7), '
            '"workshopId" = $8, "extraMetadata" = COALESCE("extraMetadata", \'{}\'::jsonb) || $9::jsonb '
            "WHERE \"id\" = $1 AND \"origin\" <> 'LEGACY_RECONSTRUCTED'",
            r["id"], RETIRED_ID, r["sessionDate"].isoformat() if r["sessionDate"] else None, r["estimated"], r["languageCode"],
            r["languageOther"], r["note"], r["workshopId"], json.dumps(extra),
        ) or 0)
    for sid, uid, role in dict.fromkeys(plan.researchers):
        n += int(await ex(
            'INSERT INTO "QuestionnaireInterviewResearcher" ("interviewId", "userId", "role") SELECT $1, $2, $3::"ResearcherRole" '
            'WHERE EXISTS (SELECT 1 FROM "User" WHERE "id" = $2) ON CONFLICT ("interviewId", "userId") DO NOTHING', sid, uid, role,
        ) or 0)
    # answers and their revisions
    for a in plan.answers:
        src = "QuestionnaireFormAnswer" if a["source"] == "form" else "QuestionnaireResponse"
        ids = [r["sourceId"] for r in a["revisions"]]
        n += int(await ex(
            f'INSERT INTO "QuestionnaireAnswer" ("id", "{a["owner"][0]}", "questionnaireId", "questionLineageId", "currentRevisionId", "version", "createdAt", "updatedAt") '
            f'SELECT $1, $2, $3, $4, $5, $6, min(x."createdAt"), max(x."updatedAt") FROM "{src}" x WHERE x."id" = ANY($7::text[]) '
            'ON CONFLICT ("id") DO NOTHING',
            a["id"], a["owner"][1], a["questionnaireId"], a["lineage"], a["revisions"][-1]["id"], len(a["revisions"]), ids,
        ) or 0)
        for r in a["revisions"]:
            session = 'x."entryId"' if a["source"] == "form" else 'x."interviewId"'
            n += int(await ex(
                'INSERT INTO "QuestionnaireAnswerRevision" ("id", "answerId", "text", "origin", "attribution", "askedQuestionId", "sessionId", "actorId", "createdAt", "note") '
                f'SELECT $1, $2, COALESCE(x."answerText", \'\'), $3::"AnswerOrigin", $4::"AnswerAttribution", x."questionId", {session}, x."answeredById", '
                f'x."updatedAt", x."notes" FROM "{src}" x WHERE x."id" = $5 ON CONFLICT ("id") DO NOTHING',
                r["id"], a["id"], r["origin"], r["attribution"], r["sourceId"],
            ) or 0)
    # clips -> recordings
    for r in plan.recordings:
        written = int(await ex(
            'INSERT INTO "QuestionnaireRecording" ("id", "mediaFileId", "clientClipKey", "capturedAt", "capturedAtSource", "rosterCount", "recordedById", '
            '"filing", "decisionKind", "provenance", "createdAt", "updatedAt") '
            'SELECT $1, m."id", \'legacy:\' || m."id", COALESCE(m."recordedAt", m."createdAt"), $3::"CapturedAtSource", $4, m."uploadedById", '
            '$5::"RecordingFiling", $6, $7::"RecordingProvenance", m."createdAt", m."updatedAt" FROM "MediaFile" m WHERE m."id" = $2 '
            'AND NOT EXISTS (SELECT 1 FROM "QuestionnaireRecording" x WHERE x."mediaFileId" = m."id") ON CONFLICT DO NOTHING',
            r["id"], r["mediaId"], r["source"], len(r["subjects"]), r["filing"], r["decision"], r["provenance"],
        ) or 0)
        if not written:
            continue
        n += written
        for t in r["targets"]:
            lineage = t.get("questionLineageId")
            await ex('INSERT INTO "QuestionnaireRecordingTarget" ("recordingId", "targetKey", "sectionId", "questionLineageId", "askedQuestionId") '
                     "VALUES ($1, $2, $3, $4, $4) ON CONFLICT DO NOTHING", r["id"], t["key"], t.get("sectionId"), lineage)
        for a in r["subjects"]:
            await ex('INSERT INTO "QuestionnaireRecordingSubject" ("recordingId", "artisanId", "role", "named") VALUES ($1, $2, \'ANSWERED\', false) '
                     "ON CONFLICT DO NOTHING", r["id"], a)
        await ex('INSERT INTO "QuestionnaireRecordingEvent" ("id", "recordingId", "kind", "after", "actorId") VALUES ($1, $2, \'BACKFILL\', $3::jsonb, $4) '
                 "ON CONFLICT DO NOTHING", f"u5-ev-{r['mediaId']}", r["id"], json.dumps({"runId": run_id, "unify": True, "step": r["step"]}), r["uploader"])
    for d in plan.drafts:
        n += int(await ex(
            'INSERT INTO "QuestionnaireTakeDraft" ("id", "recordingId", "questionLineageId", "text", "origin", "model", "createdAt") '
            'SELECT $1, $2, $3, x."draftText", \'MACHINE_SUGGESTED\', x."model", x."createdAt" FROM "QuestionnaireAnswerDraft" x WHERE x."id" = $4 '
            'ON CONFLICT DO NOTHING', d["id"], d["recordingId"], d["lineage"], d["sourceId"],
        ) or 0)
    # consent onto the ledger (the same rows and keys as scripts/migrate_workshop_consent_to_ledger.py)
    for c in plan.consents:
        owner = c["source"] == ledger.SOURCE_OWNER
        actor = c["actorId"] or approver_id
        if not actor:
            raise qv2_guard.Refused("a workshop consent names no account that still exists, and the approver has no account here")
        note = c["note"] if c["actorId"] else " ".join(p for p in ((c["note"] or "").strip(),
                                                                    "Copied into the consent ledger; the account that recorded it is no longer on record.") if p)
        if await ledger.append(
            tx, subject_type=ledger.WORKSHOP, subject_id=c["subjectId"], purpose=ledger.EXTERNAL_PROCESSING, state=c["state"],
            basis=ledger.OWNER_ATTESTATION if owner else ledger.VERBAL_RECORDED, source=ledger.SOURCE_OWNER if owner else ledger.SOURCE_RESEARCHER,
            note=note, actor_id=actor, recorded_at=c["recordedAt"], migrated_from=ledger.legacy_key(c["origin"], c["id"]),
        ) is not None:
            n += 1
    for sid in [s["id"] for s in plan.sessions] + [r["id"] for r in plan.reconstruct]:
        await titles.regenerate_title(tx, sid)
    return n


#: Tables whose new rows can only be v2 content (the switch is off while this runs). A row in one of
#: them that neither the snapshot nor the run holds is native content, and the undo refuses.
V2_ONLY = (
    "QuestionnaireInterviewResearcher", "QuestionnaireRespondent", "QuestionnaireAnswer", "QuestionnaireAnswerRevision",
    "QuestionnaireRecording", "QuestionnaireRecordingTarget", "QuestionnaireRecordingSubject", "QuestionnaireRecordingRespondent",
    "QuestionnaireRecordingEvent", "QuestionnaireTakeDraft",
)


def _row(alias: str, cols: list[str]) -> str:
    return "ROW(" + ", ".join(f'{alias}."{c}"' for c in cols) + ")"


async def record_after_images(tx: Any, snap: str) -> dict[str, dict[str, int]]:
    """Inside the apply's transaction: every row the run created or changed, as it is now
    (``<snapshot>."_after_<Table>"``). The undo restores exactly those rows, and only while each is
    still as the run left it."""
    out: dict[str, dict[str, int]] = {}
    for table in TABLES:
        pk = await _pk(tx, table)
        cols = await _cols(tx, snap, table)
        await tx.execute_raw(f'DROP TABLE IF EXISTS "{snap}"."_after_{table}"')
        await tx.execute_raw(
            f'CREATE TABLE "{snap}"."_after_{table}" AS SELECT t.* FROM "{table}" t LEFT JOIN "{snap}"."{table}" s ON {_on(pk, "s", "t")} '
            f'WHERE s."{pk[0]}" IS NULL OR {_row("t", cols)} IS DISTINCT FROM {_row("s", cols)}'
        )
        rows = await tx.query_raw(
            f'SELECT count(*) FILTER (WHERE s."{pk[0]}" IS NULL) AS created, count(*) FILTER (WHERE s."{pk[0]}" IS NOT NULL) AS changed '
            f'FROM "{snap}"."_after_{table}" a LEFT JOIN "{snap}"."{table}" s ON {_on(pk, "s", "a")}'
        )
        out[table] = {"created": int(rows[0]["created"]), "changed": int(rows[0]["changed"])}
    return out


async def run_apply(client: Any, plan: Plan, run_id: str, approver: str, state: dict[str, Any]) -> int:
    snap = snapshot_schema(run_id)
    if await _schema_exists(client, snap):
        meta = await _meta(client, snap)
        if meta.get("undoneAt"):
            raise qv2_guard.Refused(f"run {run_id} was undone; use a new --run-id")
        print(f"Snapshot {snap} exists from this run's first apply; it is kept as the undo record.")
    else:
        await take_snapshot(client, run_id, approver=approver, state=state)
        print(f"Snapshot: schema {snap} ({len(TABLES)} tables copied).")
    applied = await apply_prerequisites(client)
    print(f"Prerequisite migrations applied (idempotent): {', '.join(applied)}")
    users = await client.query_raw('SELECT "id" FROM "User" WHERE lower("email") = $1', approver.strip().lower())
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=900)) as tx:
        n = await write_plan(tx, plan, run_id, str(users[0]["id"]) if users else None)
        await _set_meta(tx, snap, "touched", await record_after_images(tx, snap))
        await _set_meta(tx, snap, "appliedAt", datetime.now(UTC).isoformat())
    return n


class UndoRefused(Exception):
    pass


async def undo(client: Any, run_id: str) -> int:
    snap = snapshot_schema(run_id)
    if not await _schema_exists(client, snap):
        raise UndoRefused(f"no snapshot schema {snap}: nothing to undo for run {run_id}")
    meta = await _meta(client, snap)
    if meta.get("undoneAt"):
        raise UndoRefused(f"run {run_id} was undone already")
    if "touched" not in meta:
        raise UndoRefused(f"run {run_id} took its snapshot but wrote nothing; drop schema {snap} if it is not wanted")
    info: dict[str, tuple[list[str], list[str]]] = {}
    moved, native = {}, {}
    for table in TABLES:
        pk = await _pk(client, table)
        cols = await _cols(client, snap, table)
        info[table] = (pk, cols)
        n = int((await client.query_raw(
            f'SELECT count(*) AS n FROM "{snap}"."_after_{table}" a LEFT JOIN "{table}" t ON {_on(pk, "t", "a")} '
            f'WHERE t."{pk[0]}" IS NULL OR {_row("t", cols)} IS DISTINCT FROM {_row("a", cols)}'
        ))[0]["n"])
        if n:
            moved[table] = n
        if table in V2_ONLY:
            n = int((await client.query_raw(
                f'SELECT count(*) AS n FROM "{table}" t WHERE NOT EXISTS (SELECT 1 FROM "{snap}"."{table}" s WHERE {_on(pk, "s", "t")}) '
                f'AND NOT EXISTS (SELECT 1 FROM "{snap}"."_after_{table}" a WHERE {_on(pk, "a", "t")})'
            ))[0]["n"])
            if n:
                native[table] = n
    if native:
        raise UndoRefused(f"native v2 content exists since the apply ({native}); undoing would orphan it")
    if moved:
        raise UndoRefused(f"rows the run wrote have been changed since ({moved}); undoing would discard those changes")
    restored = 0
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=900)) as tx:
        # The U0 CHECK holds every row written since (NOT VALID); an old interview may name both workshops.
        await tx.execute_raw('ALTER TABLE "QuestionnaireInterview" DROP CONSTRAINT IF EXISTS "QuestionnaireInterview_one_workshop_kind"')
        for table in TABLES:  # rows the run changed: back to the snapshot
            pk, cols = info[table]
            rest = [c for c in cols if c not in pk]
            if not rest:
                continue
            quoted = ", ".join(f'"{c}"' for c in rest)
            restored += int(await tx.execute_raw(
                f'UPDATE "{table}" t SET ({quoted}) = {_row("s", rest)} FROM "{snap}"."{table}" s, "{snap}"."_after_{table}" a '
                f'WHERE {_on(pk, "t", "s")} AND {_on(pk, "a", "s")}'
            ) or 0)
        for table in reversed(TABLES):  # rows the run created: gone, children first
            pk, _ = info[table]
            restored += int(await tx.execute_raw(
                f'DELETE FROM "{table}" t USING "{snap}"."_after_{table}" a WHERE {_on(pk, "t", "a")} '
                f'AND NOT EXISTS (SELECT 1 FROM "{snap}"."{table}" s WHERE {_on(pk, "s", "a")})'
            ) or 0)
        await tx.execute_raw('ALTER TABLE "QuestionnaireInterview" ADD CONSTRAINT "QuestionnaireInterview_one_workshop_kind" '
                             'CHECK (num_nonnulls("workshopId", "designWorkshopId") <= 1) NOT VALID')
        before = set(meta.get("indexes") or [])
        now_idx = {str(r["n"]) for r in await tx.query_raw(
            "SELECT indexname AS n FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ANY($1::text[])", list(INDEX_DDL))}
        for name, ddl in INDEX_DDL.items():
            if name in now_idx and name not in before:
                await tx.execute_raw(f'DROP INDEX "{name}"')
            elif name in before and name not in now_idx and ddl:
                await tx.execute_raw(ddl)
        await _set_meta(tx, snap, "undoneAt", datetime.now(UTC).isoformat())
    return restored


# ── the command ────────────────────────────────────────────────────────────────────────────────
async def dry_plan(client: Any, args: argparse.Namespace) -> tuple[Plan, dict[str, Any]]:
    async with client.tx(max_wait=timedelta(seconds=15), timeout=timedelta(seconds=300)) as tx:
        await qv2_guard.assert_read_only(tx)
        state = await schema_state(tx)
        data = await load(tx, state)
    return build_plan(data, default_form=args.default_form, primary_choice=dict(args.primary)), state


def _primary(value: str) -> tuple[str, str]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("--primary takes <designWorkshopId>=<formId>")
    dw, fid = value.split("=", 1)
    return dw.strip(), fid.strip()


async def run(args: argparse.Namespace) -> int:
    url = qv2_guard.database_url(args)
    qv2_guard.check_host(url, args.allow_host)
    if (args.apply or args.undo) and not (args.run_id and RUN_ID.match(args.run_id)):
        raise qv2_guard.Refused("--apply and --undo need --run-id (lower case letters, digits and '-', at most 24)")
    client = await qv2_guard.connect(url)
    try:
        if args.undo:
            try:
                restored = await undo(client, args.run_id)
            except UndoRefused as exc:
                raise qv2_guard.Refused(str(exc)) from None
            print(f"UNDONE: run {args.run_id}, {restored} rows restored to the snapshot. The AnswerOrigin value WORKBOOK stays (unused).")
            return 0
        plan, state = await dry_plan(client, args)
        csv_text = report_csv(plan)
        md_text = report_md(plan, csv_text, default_form=args.default_form)
        print(f"Schema: {'with' if state['u0'] else 'WITHOUT'} the U0 questionnaire tables"
              f"{'' if state['u0'] else ' (deploy that release before --apply; counts below are what the apply would create)'}.")
        print(json.dumps({"counts": plan.counts, "toWrite": plan.pending, "changes": changes(plan), "blockers": len(plan.blockers)}, indent=2))
        if not args.apply:
            md_name, csv_name = report_names()
            qv2_guard.write_report(args.out, md_name, md_text)
            qv2_guard.write_report(args.out, csv_name, csv_text)
            print("DRY RUN: nothing was written.")
            return 0
        if not state["u0"]:
            raise qv2_guard.Refused("this database has no U0 questionnaire tables; deploy the U0 release first")
        approver, day = qv2_guard.check_approval(args.approved_report, md_text)
        if plan.blockers:
            raise qv2_guard.Refused("the dry run lists blockers: " + "; ".join(plan.blockers))
        if not changes(plan):
            print(f"APPLIED run {args.run_id} (approved by {approver} on {day}): 0 changes; everything is migrated already.")
            return 0
        n = await run_apply(client, plan, args.run_id, approver, state)
        print(f"APPLIED run {args.run_id} (approved by {approver} on {day}): {n} changes. Undo: --undo --run-id {args.run_id}")
        return 0
    finally:
        await client.disconnect()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    qv2_guard.add_common_arguments(parser)
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--undo", action="store_true")
    parser.add_argument("--default-form", default=None, help="the form every unbound workshop falls back to (owner's choice)")
    parser.add_argument("--primary", action="append", type=_primary, default=[], help="<designWorkshopId>=<formId> (repeatable)")
    return asyncio.run(run(parser.parse_args(argv)))


if __name__ == "__main__":
    raise SystemExit(main())
