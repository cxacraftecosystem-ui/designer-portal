"""The designer-portal form migration (UNIFY_2026-10-21.md section 3.2 and acceptance 12).

Two halves:

* PURE: the lineage and display-code rule (the same as the U0 migration's SQL), the statement
  splitter for the deferred migrations, and the plan on the production-shaped fixture (2 shared
  forms, 46 sections, 365 questions, the global 1 + 2, nothing answered).
* DATABASE: on a throwaway database of its own (``QUESTIONNAIRE_UNIFY_DATABASE_URL``, an EMPTY local
  database; CI's "Questionnaire unify migration" job creates it). The test migrates it to the schema
  production runs today (everything before the U0 release), seeds realistic designer-portal data
  (shared and private forms, a deleted form, a reworded question, sittings typed and uploaded and
  deleted, global interviews alone, in a group and over several days, clips with and without a
  target, byte-identical copies, an AI draft, the three section overrides, a workshop consent), and
  then proves: the dry run on that schema writes nothing and counts what it would create; after the
  U0 migrations the counts are the same; ``--apply`` refuses without an approved report and when the
  choices differ; the apply keeps every id and writes the right rows; a second dry run shows 0 and a
  second apply writes nothing; ``--undo`` restores every affected table EXACTLY and puts the keys
  back, and refuses once native v2 content exists; and the deferred migrations then apply on top.
"""

from __future__ import annotations

import asyncio
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

import pytest

BACKEND = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("questionnaire_unify", BACKEND / "scripts" / "questionnaire_unify.py")
unify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(unify)

URL = os.environ.get("QUESTIONNAIRE_UNIFY_DATABASE_URL", "").strip()
U0 = "20261107090000_questionnaire_unify_additive"
APPROVAL = "Approved-by: owner@x.test 2026-10-23"


# ── pure ───────────────────────────────────────────────────────────────────────────────────────
def _q(qid, sec, order, *, sup=None, created="2026-08-01T00:00:00+00:00", form="f"):
    return {"id": qid, "sectionCode": sec, "sortOrder": order, "supersededById": sup, "createdAt": created, "questionnaireId": form}


def test_lineage_is_the_root_of_the_supersede_chain_and_the_code_is_frozen() -> None:
    rows = [_q("q1", "a", 1), _q("q2", "a", 2, sup="q2b"), _q("q2b", "a", 2, sup="q2c", created="2026-08-02T00:00:00+00:00"),
            _q("q2c", "a", 2, created="2026-08-03T00:00:00+00:00"), _q("q3", "b", 1)]
    lineage, conflicts = unify.lineages(rows)
    assert conflicts == 0
    assert lineage == {"q1": "q1", "q2": "q2", "q2b": "q2", "q2c": "q2", "q3": "q3"}
    codes = unify.display_codes(rows, lineage, {r["id"]: "f" for r in rows})
    assert codes == {"q1": "A1", "q2": "A2", "q2b": "A2", "q2c": "A2", "q3": "B1"}


def test_a_question_two_chains_claim_or_a_loop_is_a_conflict() -> None:
    _, conflicts = unify.lineages([_q("x", "a", 1, sup="z"), _q("y", "a", 2, sup="z"), _q("z", "a", 3)])
    assert conflicts == 1
    _, loop = unify.lineages([_q("p", "a", 1, sup="r"), _q("r", "a", 2, sup="p")])
    assert loop == 2


def test_the_deferred_migrations_split_into_statements() -> None:
    sql = "-- c\nDROP INDEX IF EXISTS \"a\";\nDO $$\nBEGIN\n  IF true THEN\n    SELECT 1;\n  END IF;\nEND $$;\nCREATE INDEX \"b\" ON \"t\"(\"c\");\n"
    out = unify.statements(sql)
    assert len(out) == 3 and out[1].startswith("DO $$") and out[1].endswith("END $$")
    for name in unify.PREREQUISITES:
        assert unify.statements((unify.DEFERRED / name / "migration.sql").read_text(encoding="utf-8"))
    folders = sorted(p.name for p in unify.DEFERRED.iterdir() if p.is_dir())
    assert folders[: len(unify.PREREQUISITES)] == list(unify.PREREQUISITES)
    migrations = {p.name for p in (BACKEND / "prisma" / "migrations").iterdir()}
    assert not set(folders) & migrations, "a deferred migration is already in prisma/migrations"


def _empty() -> dict:
    return {
        "state": {"u0": False, "columns": {}, "workbook": False, "indexes": set()}, "forms": [], "form_sections": [], "form_questions": [],
        "sections_now": [], "global_sections": [], "questions_now": [], "global_questions": [], "section_statuses": [], "entries": [],
        "form_answers": [], "all_session_ids": set(), "users": {"admin"}, "foreign_collisions": 0, "interviews": [], "native_sessions": 0, "participants": [],
        "responses": [], "drafts": [], "media": [], "linked_media": 0, "assigned_tasks": 0, "admins": [{"id": "admin", "email": "a@x.test"}],
        "consent_log": [], "consent_cache": [],
        "v2": {"bindings": [], "respondents": set(), "answers": {}, "revisions": set(), "recordings": set(), "drafts": set(),
               "researchers": set(), "reconstructed": set(), "retired": False},
    }


def test_the_plan_reproduces_the_production_figures() -> None:
    data = _empty()
    per_form = {"f1": 24, "f2": 22}
    questions = {"f1": 190, "f2": 175}
    for n, fid in enumerate(per_form, start=1):
        data["forms"].append({"id": fid, "title": f"Toolkit {n}", "ownerId": "admin", "designWorkshopId": None, "isShared": True,
                              "kind": "WORKSHOP_INTERVIEW", "deletedAt": None, "createdAt": f"2026-08-0{n}T00:00:00+00:00"})
        for s in range(per_form[fid]):
            data["form_sections"].append({"id": f"{fid}s{s}", "questionnaireId": fid, "code": chr(65 + s), "title": f"S{s}", "sortOrder": s + 1,
                                          "isActive": True, "createdAt": "2026-08-01T00:00:00+00:00"})
        for q in range(questions[fid]):
            sec = q % per_form[fid]
            data["form_questions"].append({"id": f"{fid}q{q}", "sectionId": f"{fid}s{sec}", "questionnaireId": fid, "sectionCode": chr(65 + sec),
                                           "sortOrder": q, "isActive": True, "retiredAt": None, "supersededById": None, "questionCode": None,
                                           "createdAt": "2026-08-01T00:00:00+00:00"})
    data["global_sections"] = data["sections_now"] = [{"id": "g", "code": "A", "title": "General", "sortOrder": 1, "isActive": False, "questionnaireId": None}]
    data["global_questions"] = data["questions_now"] = [
        {"id": f"g{i}", "sectionId": "g", "sectionCode": "A", "prompt": f"P{i}", "sortOrder": i, "isActive": False, "retiredAt": None,
         "supersededById": None, "createdAt": "2026-08-01T00:00:00+00:00", "lineageId": None, "displayCode": None} for i in (1, 2)
    ]
    plan = unify.build_plan(data, default_form="f1")
    c = plan.counts
    assert (c["forms"], c["formsShared"], c["formSections"], c["formQuestions"]) == (2, 2, 46, 365)
    assert (c["globalSections"], c["globalQuestions"]) == (1, 2)
    assert (c["sittings"], c["sittingAnswers"], c["globalInterviews"], c["responses"], c["drafts"]) == (0, 0, 0, 0, 0)
    assert plan.pending["sections"] == 46 and plan.pending["questions"] == 365 and plan.pending["retiredInstrument"] == 1
    assert not plan.blockers
    codes = [(q["id"], q["code"]) for q in plan.questions]
    assert len({(qid[:2], code) for qid, code in codes}) == 365, "two live questions of one form share a display code"
    assert [q["code"] for q in plan.global_questions] == ["A1", "A2"]
    md = unify.report_md(plan, unify.report_csv(plan), default_form="f1")
    assert "| total |" in md and "Default form: f1" in md and "- none" in md


def test_blockers_stop_the_apply() -> None:
    data = _empty()
    for fid in ("a", "b"):
        data["forms"].append({"id": fid, "title": "Same", "ownerId": "admin", "designWorkshopId": None, "isShared": True, "kind": None,
                              "deletedAt": None, "createdAt": "2026-08-01T00:00:00+00:00"})
    data["section_statuses"] = [{"id": "x", "sectionId": "s", "status": "DONE"}]
    plan = unify.build_plan(data, default_form="missing", primary_choice={"dw": "a"})
    text = " | ".join(plan.blockers)
    assert "titled 'Same'" in text and "--default-form missing" in text and "--primary dw=a" in text and "outside the three states" in text


# ── database ───────────────────────────────────────────────────────────────────────────────────
needs_unify_db = pytest.mark.skipif(
    not URL or urlsplit(URL).hostname not in ("127.0.0.1", "localhost"),
    reason="QUESTIONNAIRE_UNIFY_DATABASE_URL is not set to an empty LOCAL database of its own (CI: the 'Questionnaire unify migration' job)",
)


def _deploy(tmp: Path, names: list[str]) -> None:
    """``prisma migrate deploy`` of exactly these migration folders (from prisma/migrations or prisma/deferred)."""
    root = tmp / "prisma"
    (root / "migrations").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(BACKEND / "prisma" / "schema.prisma", root / "schema.prisma")
    shutil.copyfile(BACKEND / "prisma" / "migrations" / "migration_lock.toml", root / "migrations" / "migration_lock.toml")
    for name in names:
        src = BACKEND / "prisma" / "migrations" / name
        if not src.exists():
            src = BACKEND / "prisma" / "deferred" / name
        if not (root / "migrations" / name).exists():
            shutil.copytree(src, root / "migrations" / name)
    env = {**os.environ, "DATABASE_URL": URL}
    out = subprocess.run([sys.executable, "-m", "prisma", "migrate", "deploy", "--schema", str(root / "schema.prisma")],
                         cwd=BACKEND, env=env, capture_output=True, text=True, timeout=900, check=False)
    assert out.returncode == 0, out.stdout[-3000:] + out.stderr[-3000:]


def _migrations(before: str | None = None) -> list[str]:
    names = sorted(p.name for p in (BACKEND / "prisma" / "migrations").iterdir() if p.is_dir())
    return [n for n in names if before is None or n < before]


async def _client():
    from prisma import Prisma

    client = Prisma(datasource={"url": URL})
    await client.connect()
    return client


def _db(fn):
    async def go():
        client = await _client()
        try:
            return await fn(client)
        finally:
            await client.disconnect()

    return asyncio.run(go())


SEED = [
    "INSERT INTO \"User\" (\"id\", \"email\", \"name\", \"role\", \"updatedAt\") VALUES "
    "('owner', 'owner@x.test', 'Owner', 'MASTER_ADMIN', now()), ('d1', 'd1@x.test', 'Nandini', 'DESIGNER', now()), "
    "('d2', 'd2@x.test', 'Ankur', 'DESIGNER', now())",
    "INSERT INTO \"Workshop\" (\"id\", \"title\", \"date\", \"place\", \"createdById\", \"updatedAt\") VALUES ('w1', 'Bagru', '2026-07-01', 'Bagru', 'd1', now())",
    "INSERT INTO \"DesignWorkshop\" (\"id\", \"title\", \"createdById\", \"dictationConsent\", \"dictationConsentAt\", \"dictationConsentById\", \"updatedAt\") VALUES "
    "('dw1', 'Bagru block printing', 'd1', 'GRANTED', '2026-07-01 05:00', 'd1', now()), ('dw2', 'Sanganer', 'd2', 'NOT_RECORDED', NULL, NULL, now())",
    "INSERT INTO \"Artisan\" (\"id\", \"name\", \"place\", \"createdById\", \"updatedAt\") VALUES "
    "('a1', 'Giriraj', 'Bagru', 'd1', now()), ('a2', 'Meena', 'Bagru', 'd1', now()), ('a3', 'Ramesh', 'Bagru', 'd1', now())",
    # forms: a shared interview and a private market survey on dw1, a deleted one on dw2
    "INSERT INTO \"Questionnaire\" (\"id\", \"title\", \"ownerId\", \"designWorkshopId\", \"isShared\", \"kind\", \"deletedAt\", \"deletedById\", \"createdAt\", \"updatedAt\") VALUES "
    "('f1', '2nd Craft Toolkit', 'owner', 'dw1', true, 'WORKSHOP_INTERVIEW', NULL, NULL, '2026-08-01', '2026-08-05'), "
    "('f2', 'Market survey', 'd1', 'dw1', false, 'MARKET_SURVEY', NULL, NULL, '2026-08-02', '2026-08-02'), "
    "('f3', 'Old draft', 'd2', 'dw2', false, NULL, '2026-09-01', 'owner', '2026-08-03', '2026-09-01')",
    "INSERT INTO \"QuestionnaireFormSection\" (\"id\", \"questionnaireId\", \"code\", \"title\", \"sortOrder\", \"isActive\", \"createdAt\", \"updatedAt\") VALUES "
    "('f1sA', 'f1', 'A', 'The craft', 1, true, '2026-08-01', '2026-08-01'), ('f1sB', 'f1', 'B', 'Tools', 2, true, '2026-08-01', '2026-08-01'), "
    "('f2sA', 'f2', 'A', 'Buyers', 1, true, '2026-08-02', '2026-08-02'), ('f2sB', 'f2', 'B', 'Prices', 1, true, '2026-08-02 01:00', '2026-08-02'), "
    "('f3sA', 'f3', 'A', 'Draft', 1, true, '2026-08-03', '2026-08-03')",
    "INSERT INTO \"QuestionnaireFormQuestion\" (\"id\", \"sectionId\", \"prompt\", \"helpText\", \"isRequired\", \"sortOrder\", \"isActive\", \"retiredAt\", "
    "\"supersededById\", \"questionCode\", \"createdAt\", \"updatedAt\") VALUES "
    "('f1q1', 'f1sA', 'What do you make?', 'Name the products', true, 1, true, NULL, NULL, 'CTW2.A.01', '2026-08-01', '2026-08-01'), "
    "('f1q2', 'f1sA', 'How many looms?', NULL, false, 2, false, '2026-08-05', 'f1q2b', NULL, '2026-08-01', '2026-08-05'), "
    "('f1q2b', 'f1sA', 'How many weavers?', NULL, false, 2, true, NULL, NULL, NULL, '2026-08-05', '2026-08-05'), "
    "('f1q3', 'f1sA', 'Retired one', NULL, false, 3, false, '2026-08-04', NULL, NULL, '2026-08-01', '2026-08-04'), "
    "('f1q4', 'f1sB', 'Which blocks?', NULL, false, 1, true, NULL, NULL, NULL, '2026-08-01', '2026-08-01'), "
    "('f2q1', 'f2sA', 'Who buys?', NULL, false, 1, true, NULL, NULL, NULL, '2026-08-02', '2026-08-02'), "
    "('f2q2', 'f2sB', 'At what price?', NULL, false, 1, true, NULL, NULL, NULL, '2026-08-02', '2026-08-02'), "
    "('f3q1', 'f3sA', 'Draft question', NULL, false, 1, true, NULL, NULL, NULL, '2026-08-03', '2026-08-03')",
    # sittings: a guest typed in the app (answered by two designers, before and after a reword), a
    # self-answered upload with long notes, and a deleted one
    "INSERT INTO \"QuestionnaireFormEntry\" (\"id\", \"questionnaireId\", \"title\", \"respondentName\", \"source\", \"notes\", \"createdById\", \"deletedAt\", "
    "\"deletedById\", \"createdAt\", \"updatedAt\") VALUES "
    "('e1', 'f1', 'Lakshmi at the dye pit', 'Lakshmi', 'APP', 'Short note', 'd1', NULL, NULL, '2026-08-04 06:00', '2026-08-06 06:00'), "
    "('e2', 'f1', 'My own answers', NULL, 'UPLOAD', '" + ("x" * 120) + "', 'd2', NULL, NULL, '2026-08-07 06:00', '2026-08-07 06:00'), "
    "('e3', 'f2', 'Deleted sitting', 'Buyer', 'APP', NULL, 'd1', '2026-09-02', 'owner', '2026-08-08 06:00', '2026-09-02 06:00')",
    "INSERT INTO \"QuestionnaireFormAnswer\" (\"id\", \"entryId\", \"questionId\", \"answerText\", \"notes\", \"answeredById\", \"createdAt\", \"updatedAt\") VALUES "
    "('fa1', 'e1', 'f1q1', 'Dabu sarees', 'said twice', 'd1', '2026-08-04 06:00', '2026-08-04 06:05'), "
    "('fa2', 'e1', 'f1q2', '12', NULL, 'd1', '2026-08-04 06:00', '2026-08-04 06:06'), "
    "('fa3', 'e1', 'f1q2b', '4', NULL, 'd2', '2026-08-06 06:00', '2026-08-06 06:00'), "
    "('fa4', 'e1', 'f1q4', '   ', NULL, 'd1', '2026-08-04 06:00', '2026-08-04 06:07'), "
    "('fa5', 'e2', 'f1q1', 'Block prints', NULL, 'd2', '2026-08-07 06:00', '2026-08-07 06:00'), "
    "('fa6', 'e3', 'f2q1', 'Exporters', NULL, 'd1', '2026-08-08 06:00', '2026-08-08 06:00')",
    # the global instrument: one inactive section, three inactive questions (one reworded)
    "INSERT INTO \"QuestionnaireSection\" (\"id\", \"code\", \"title\", \"sortOrder\", \"isActive\", \"updatedAt\") VALUES ('gA', 'A', 'General', 1, false, now())",
    "INSERT INTO \"QuestionnaireQuestion\" (\"id\", \"sectionCode\", \"sectionTitle\", \"prompt\", \"sortOrder\", \"isActive\", \"sectionId\", \"supersededById\", \"createdAt\", \"updatedAt\") VALUES "
    "('g1', 'A', 'General', 'Tell us about your craft', 1, false, 'gA', NULL, '2026-06-01', '2026-06-01'), "
    "('g2', 'A', 'General', 'How long have you worked?', 2, false, 'gA', 'g2b', '2026-06-01', '2026-06-01'), "
    "('g2b', 'A', 'General', 'How many years have you worked?', 2, false, 'gA', NULL, '2026-06-02', '2026-06-02')",
    # global interviews: alone over two days, a group at two workshops, nobody, deleted
    "INSERT INTO \"QuestionnaireInterview\" (\"id\", \"title\", \"interviewDate\", \"notes\", \"language\", \"createdById\", \"workshopId\", \"designWorkshopId\", "
    "\"artisanSetKey\", \"deletedAt\", \"createdAt\", \"updatedAt\") VALUES "
    "('i1', 'section F', '2026-07-01 04:00', 'Over two mornings', 'Hindi', 'd1', NULL, 'dw1', '||a1', NULL, '2026-07-01 04:00', '2026-07-02 08:00'), "
    "('i2', 'Group at the block yard', NULL, NULL, 'Marwari', 'd1', 'w1', 'dw2', '|dw2|a2,a3', NULL, '2026-07-03 04:00', '2026-07-03 08:00'), "
    "('i3', 'Nobody named', NULL, NULL, NULL, 'd2', NULL, 'dw1', NULL, NULL, '2026-07-04 04:00', '2026-07-04 08:00'), "
    "('i4', 'Deleted interview', NULL, NULL, NULL, 'd2', NULL, NULL, NULL, '2026-09-03', '2026-07-05 04:00', '2026-09-03 08:00')",
    "INSERT INTO \"QuestionnaireInterviewArtisan\" (\"interviewId\", \"artisanId\", \"createdAt\") VALUES "
    "('i1', 'a1', '2026-07-01 04:00'), ('i2', 'a2', '2026-07-03 04:00'), ('i2', 'a3', '2026-07-03 04:01'), ('i4', 'a1', '2026-07-05 04:00')",
    "INSERT INTO \"QuestionnaireResponse\" (\"id\", \"interviewId\", \"questionId\", \"answerText\", \"notes\", \"answeredById\", \"answerOrigin\", \"updatedAt\") VALUES "
    "('r1', 'i1', 'g1', 'Indigo dabu', NULL, 'd1', NULL, '2026-07-01 05:00'), ('r2', 'i1', 'g2', 'Twenty years', NULL, 'd2', 'MACHINE_SUGGESTED', '2026-07-02 05:00'), "
    "('r3', 'i2', 'g1', 'We print together', 'both said it', 'd1', NULL, '2026-07-03 05:00'), ('r4', 'i3', 'g1', 'Unknown speaker', NULL, 'd2', NULL, '2026-07-04 05:00'), "
    "('r5', 'i4', 'g2b', 'Thirty', NULL, 'd2', NULL, '2026-07-05 05:00')",
    "INSERT INTO \"QuestionnaireSectionStatus\" (\"id\", \"artisanId\", \"sectionId\", \"status\", \"setById\", \"updatedAt\") VALUES "
    "('ss1', 'a1', 'gA', 'COMPLETED', 'owner', now()), ('ss2', 'a2', 'gA', 'NEEDS_REVIEW', 'owner', now()), ('ss3', 'a3', 'gA', 'NEEDS_REDO', 'owner', now())",
]

SHA = "ab" * 32
CLIPS = [
    # id, interview, caption, extraMetadata, checksum, recordedAt, createdAt, uploader
    ("m1", "i1", "Question audio: A1 Tell us about your craft", {"questionId": "g1"}, SHA, "2026-07-01 04:30", "2026-07-01 04:31", "d1"),
    ("m2", "i1", "Section audio: A", None, None, "2026-07-02 05:00", "2026-07-02 05:01", "d2"),
    ("m3", "i1", None, None, SHA, "2026-07-02 05:30", "2026-07-02 05:30", "d1"),
    ("m4", "i2", "Section audio: A", {"sectionId": "gA"}, None, "2026-07-03 04:30", "2026-07-03 04:30", "d1"),
    ("m5", "i3", "free talk", None, None, "2026-07-04 04:30", "2026-07-04 04:30", "d2"),
]


async def seed(client) -> None:
    for sql in SEED:
        await client.execute_raw(sql)
    for mid, iid, caption, meta, checksum, recorded, created, uploader in CLIPS:
        await client.execute_raw(
            'INSERT INTO "MediaFile" ("id", "originalFilename", "mediaType", "mimeType", "sizeBytes", "bucket", "objectKey", "caption", "extraMetadata", '
            '"checksum", "questionnaireInterviewId", "linkedRecordType", "linkedRecordId", "uploadedById", "recordedAt", "createdAt", "updatedAt") '
            "VALUES ($1, $1 || '.m4a', 'AUDIO', 'audio/mp4', 10, 'b', 'media/' || $1, $2, $3::jsonb, $4, $5, 'questionnaireinterview', $5, $6, "
            "$7::timestamp, $8::timestamp, $8::timestamp)",
            mid, caption, json.dumps(meta) if meta else None, checksum, iid, uploader, recorded, created,
        )
    await client.execute_raw(
        'INSERT INTO "QuestionnaireAnswerDraft" ("id", "interviewId", "questionId", "sectionId", "draftText", "status", "provider", "model", '
        "\"sourceMediaId\", \"createdById\", \"createdAt\") VALUES ('dr1', 'i1', 'g2b', 'gA', 'About twenty years', 'PENDING', 'p', 'm-1', 'm1', 'd1', '2026-07-02'), "
        "('dr2', 'i1', 'g1', 'gA', 'Accepted already', 'ACCEPTED', 'p', 'm-1', 'm1', 'd1', '2026-07-02')"
    )


async def fingerprint(client) -> dict[str, str]:
    out = {}
    for table in unify.TABLES:
        rows = await client.query_raw(f"SELECT md5(COALESCE(string_agg(t::text, '|' ORDER BY t::text), '')) AS h, count(*) AS n FROM \"{table}\" t")
        out[table] = f"{rows[0]['n']}:{rows[0]['h']}"
    return out


async def indexes(client) -> set[str]:
    rows = await client.query_raw("SELECT indexname AS n FROM pg_indexes WHERE schemaname = current_schema()")
    return {str(r["n"]) for r in rows}


def _run(args: list[str], capsys) -> tuple[int, str]:
    try:
        code = unify.main(["--database-url", URL, "--allow-host", urlsplit(URL).hostname, *args])
    except SystemExit as exc:
        code = int(exc.code or 0)
    captured = capsys.readouterr()
    return code, captured.out + captured.err


def _counts(out: str) -> dict:
    match = re.search(r"^\{\n.*?^\}$", out, re.MULTILINE | re.DOTALL)
    assert match, out
    return json.loads(match.group(0))


def _dry(tmp: Path, capsys, *choices: str) -> tuple[dict, Path]:
    for old in tmp.glob("*Unify-Dry-Run-Report*"):
        old.unlink()
    code, out = _run(["--out", str(tmp), *choices], capsys)
    assert code == 0, out
    assert "DRY RUN: nothing was written." in out
    report = next(tmp.glob("DesignWorkshop_Questionnaire_*_Unify-Dry-Run-Report.md"))
    return _counts(out), report


def _approve(report: Path) -> Path:
    approved = report.with_name("approved.md")
    approved.write_text(report.read_text(encoding="utf-8") + APPROVAL + "\n", encoding="utf-8", newline="\n")
    return approved


CHOICES = ("--default-form", "f1")


@needs_unify_db
def test_the_migration_dry_runs_applies_repeats_and_undoes_exactly(tmp_path: Path, capsys) -> None:
    migrations_dir = tmp_path / "m"
    reports = tmp_path / "r"
    reports.mkdir()
    # 1. the schema production runs today (no U0 tables), and realistic data on it
    _deploy(migrations_dir, _migrations(before=U0))
    _db(seed)
    before_dry = _db(fingerprint)
    pre, pre_report = _dry(reports, capsys, *CHOICES)
    assert _db(fingerprint) == before_dry, "the dry run wrote"
    c = pre["counts"]
    assert (c["forms"], c["formsDeleted"], c["formSections"], c["formQuestions"]) == (3, 1, 5, 8)
    assert (c["globalSections"], c["globalQuestions"], c["sittings"], c["sittingsDeleted"], c["sittingAnswers"]) == (1, 3, 3, 1, 6)
    assert (c["globalInterviews"], c["groupSittings"], c["multiDaySessions"], c["interviewsWithoutParticipants"]) == (4, 1, 1, 1)
    assert (c["interviewsWithBothWorkshops"], c["responses"], c["responsesWithoutParticipants"], c["clips"]) == (1, 5, 1, 5)
    assert (c["recordingsFiled"], c["recordingsNeedsDecision"], c["takeDrafts"], c["sectionStatuses"]) == (3, 2, 1, 3)
    assert (c["respondentsGuest"], c["respondentsSelf"], c["notesMovedToRespondent"], c["formSectionOrdersRenumbered"]) == (2, 1, 1, 1)
    assert (c["revisionsWorkbook"], c["workshopConsentsToLedger"], c["sectionStatusesOutsideThreeStates"]) == (1, 1, 0)
    assert pre["blockers"] == 0
    assert pre["toWrite"]["sections"] == 5 and pre["toWrite"]["questions"] == 8 and pre["toWrite"]["primaryBindings"] == 1
    pre_csv = pre_report.with_suffix(".csv").read_text(encoding="utf-8")

    # --apply refuses on that schema
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "early", "--approved-report", str(_approve(pre_report)), *CHOICES], capsys)
    assert code == 2 and "no U0 questionnaire tables" in out

    # 2. the U0 release: the same plan, the global codes the SQL computed equal the dry run's
    _deploy(migrations_dir, _migrations())
    post, report = _dry(reports, capsys, *CHOICES)
    assert post["counts"] == pre["counts"]
    assert report.with_suffix(".csv").read_text(encoding="utf-8") == pre_csv
    assert post["toWrite"]["primaryBindings"] == 1

    # 3. refusals: no report, no approval line, different choices
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "one", *CHOICES], capsys)
    assert code == 2 and "--approved-report" in out
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "one", "--approved-report", str(report), *CHOICES], capsys)
    assert code == 2 and "Approved-by" in out
    approved = _approve(report)
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "one", "--approved-report", str(approved), "--default-form", "f2"], capsys)
    assert code == 2 and "REFUSED" in out
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "Bad_Id", "--approved-report", str(approved), *CHOICES], capsys)
    assert code == 2 and "--run-id" in out

    before = _db(fingerprint)
    idx_before = _db(indexes)
    assert {"QuestionnaireSection_code_key", "QuestionnaireSection_sortOrder_key"} <= idx_before

    # 4. apply
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "one", "--approved-report", str(approved), *CHOICES], capsys)
    assert code == 0 and "APPLIED run one" in out, out
    _db(check_applied)
    idx = _db(indexes)
    assert "QuestionnaireSection_code_key" not in idx and "QuestionnaireSection_sortOrder_key" not in idx
    assert {"QuestionnaireSection_questionnaireId_sortOrder_key", "Questionnaire_shared_title_key"} <= idx

    # 5. idempotent: the dry run shows nothing to write, a second apply writes nothing
    again, report2 = _dry(reports, capsys, *CHOICES)
    assert again["changes"] == 0, again["toWrite"]
    assert again["counts"]["sessions"] == 7
    after = _db(fingerprint)
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "two", "--approved-report", str(_approve(report2)), *CHOICES], capsys)
    assert code == 0 and "0 changes" in out
    assert _db(fingerprint) == after

    # 6. undo restores every affected table exactly, and the keys
    code, out = _run(["--undo", "--run-id", "one"], capsys)
    assert code == 0 and "UNDONE" in out, out
    assert _db(fingerprint) == before
    assert _db(indexes) == idx_before
    code, out = _run(["--undo", "--run-id", "one"], capsys)
    assert code == 2 and "undone already" in out

    # 7. undo refuses once native v2 content exists
    fresh, report3 = _dry(reports, capsys, *CHOICES)
    assert fresh["changes"] == post["changes"]
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "three", "--approved-report", str(_approve(report3)), *CHOICES], capsys)
    assert code == 0, out

    async def native(client):
        await client.execute_raw("INSERT INTO \"QuestionnaireInterviewResearcher\" (\"interviewId\", \"userId\", \"role\") VALUES ('i3', 'owner', 'VOLUNTEER')")

    _db(native)
    code, out = _run(["--undo", "--run-id", "three"], capsys)
    assert code == 2 and "native v2 content" in out

    async def unnative(client):
        await client.execute_raw("DELETE FROM \"QuestionnaireInterviewResearcher\" WHERE \"interviewId\" = 'i3' AND \"userId\" = 'owner'")

    _db(unnative)
    code, out = _run(["--undo", "--run-id", "three"], capsys)
    assert code == 0, out
    assert _db(fingerprint) == before

    # 8. the switch-on and the later deferred migrations apply on top of the migrated data
    _final, report4 = _dry(reports, capsys, *CHOICES)
    code, out = _run(["--out", str(reports), "--apply", "--run-id", "four", "--approved-report", str(_approve(report4)), *CHOICES], capsys)
    assert code == 0, out
    deferred = sorted(p.name for p in (BACKEND / "prisma" / "deferred").iterdir() if p.is_dir())
    _deploy(migrations_dir, _migrations() + deferred)
    _db(check_deferred)


async def check_applied(client) -> None:
    q = client.query_raw
    # ids kept, sections and questions per form, the renumbered duplicate position
    secs = {r["id"]: r for r in await q('SELECT "id", "questionnaireId", "code", "sortOrder" FROM "QuestionnaireSection"')}
    assert {k: (v["questionnaireId"], v["sortOrder"]) for k, v in secs.items() if k.startswith("f")} == {
        "f1sA": ("f1", 1), "f1sB": ("f1", 2), "f2sA": ("f2", 1), "f2sB": ("f2", 2), "f3sA": ("f3", 1)}
    assert secs["gA"]["questionnaireId"] == unify.RETIRED_ID
    qs = {r["id"]: r for r in await q('SELECT "id", "questionnaireId", "lineageId", "displayCode", "sectionCode", "isRequired", "helpText", "questionCode" FROM "QuestionnaireQuestion"')}
    assert (qs["f1q2"]["lineageId"], qs["f1q2b"]["lineageId"], qs["f1q2"]["displayCode"], qs["f1q2b"]["displayCode"]) == ("f1q2", "f1q2", "A2", "A2")
    assert (qs["f1q1"]["displayCode"], qs["f1q3"]["displayCode"], qs["f1q4"]["displayCode"], qs["f2q2"]["displayCode"]) == ("A1", "A3", "B1", "B1")
    assert qs["f1q1"]["isRequired"] is True and qs["f1q1"]["helpText"] == "Name the products" and qs["f1q1"]["questionCode"] == "CTW2.A.01"
    assert (qs["g2b"]["lineageId"], qs["g2b"]["displayCode"], qs["g1"]["questionnaireId"]) == ("g2", "A2", unify.RETIRED_ID)
    dupes = await q('SELECT count(*) AS n FROM (SELECT 1 FROM "QuestionnaireQuestion" WHERE "retiredAt" IS NULL AND "supersededById" IS NULL '
                    'GROUP BY "questionnaireId", "displayCode" HAVING count(*) > 1) d')
    assert int(dupes[0]["n"]) == 0
    retired = (await q('SELECT "isActive", "deletedAt", "ownerId", "isShared" FROM "Questionnaire" WHERE "id" = $1', unify.RETIRED_ID))[0]
    assert retired["isActive"] is False and retired["deletedAt"] and retired["ownerId"] == "owner" and retired["isShared"] is False
    forms = {r["id"]: r for r in await q('SELECT "id", "isDefault", "sortOrder" FROM "Questionnaire"')}
    assert forms["f1"]["isDefault"] is True and forms["f2"]["isDefault"] is False and [forms[f]["sortOrder"] for f in ("f1", "f2", "f3")] == [1, 2, 3]
    bindings = {(r["questionnaireId"], r["designWorkshopId"]): r["isPrimary"] for r in await q('SELECT "questionnaireId", "designWorkshopId", "isPrimary" FROM "QuestionnaireBinding"')}
    assert bindings == {("f1", "dw1"): True, ("f2", "dw1"): False, ("f3", "dw2"): False}
    # sittings -> sessions
    s = {r["id"]: r for r in await q('SELECT "id", "questionnaireId", "designWorkshopId", "origin"::text AS origin, "source"::text AS source, "legacyTitle", '
                                     '"title", "note", "deletedAt", "sessionDate", "sessionDateEstimated", "state"::text AS state, "workshopId", "extraMetadata" FROM "QuestionnaireInterview"')}
    assert (s["e1"]["questionnaireId"], s["e1"]["designWorkshopId"], s["e1"]["origin"], s["e1"]["source"]) == ("f1", "dw1", "LEGACY_RECONSTRUCTED", "APP")
    assert s["e1"]["legacyTitle"] == "Lakshmi at the dye pit" and s["e1"]["note"] == "Short note" and s["e1"]["state"] == "CLOSED"
    assert s["e2"]["source"] == "UPLOAD" and s["e2"]["note"] is None and s["e3"]["deletedAt"]
    resp = {r["id"]: r for r in await q('SELECT "id", "sessionId", "name", "kind"::text AS kind, "userId", "notes" FROM "QuestionnaireRespondent"')}
    assert (resp["u5-resp-e1"]["name"], resp["u5-resp-e1"]["kind"]) == ("Lakshmi", "GUEST")
    assert (resp["u5-resp-e2"]["name"], resp["u5-resp-e2"]["kind"], resp["u5-resp-e2"]["userId"], len(resp["u5-resp-e2"]["notes"])) == ("Ankur", "SELF", "d2", 120)
    revs = await q('SELECT v."id", v."origin"::text AS origin, v."attribution"::text AS attribution, v."askedQuestionId", v."sessionId", v."actorId", v."note", '
                   'a."respondentId", a."artisanId", a."questionLineageId", a."currentRevisionId", a."questionnaireId" '
                   'FROM "QuestionnaireAnswerRevision" v JOIN "QuestionnaireAnswer" a ON a."id" = v."answerId" ORDER BY v."createdAt", v."id"')
    by = {r["id"]: r for r in revs}
    assert by["u5-rev-fa1"]["note"] == "said twice" and by["u5-rev-fa1"]["origin"] == "TYPED"
    assert by["u5-rev-fa5"]["origin"] == "WORKBOOK" and by["u5-rev-fa5"]["respondentId"] == "u5-resp-e2"
    assert by["u5-rev-fa2"]["questionLineageId"] == by["u5-rev-fa3"]["questionLineageId"] == "f1q2"
    assert by["u5-rev-fa2"]["currentRevisionId"] == "u5-rev-fa3" and by["u5-rev-fa3"]["actorId"] == "d2"
    assert "u5-rev-fa4" not in by, "a blank answer became a revision"
    group = [r for r in revs if r["sessionId"] == "i2"]
    assert sorted(r["artisanId"] for r in group) == ["a2", "a3"] and {r["attribution"] for r in group} == {"GROUP_SHARED"}
    alone = [r for r in revs if r["artisanId"] == "a1"]
    assert {r["questionLineageId"] for r in alone} == {"g1", "g2"} and {r["attribution"] for r in alone} == {"INDIVIDUAL"}
    assert {r["origin"] for r in alone if r["questionLineageId"] == "g2"} == {"MACHINE_SUGGESTED", "TYPED"}
    assert not [r for r in revs if r["sessionId"] == "i3"], "a response with nobody to own it became an answer"
    # global interviews in place, one session each, never split
    assert sum(1 for k in s if k.startswith("i")) == 4
    assert str(s["i1"]["sessionDate"])[:10] == "2026-07-01" and s["i1"]["sessionDateEstimated"] is False and s["i1"]["legacyTitle"] == "section F"
    assert s["i2"]["workshopId"] is None and s["i2"]["sessionDateEstimated"] is True
    meta = s["i2"]["extraMetadata"]
    meta = json.loads(meta) if isinstance(meta, str) else meta
    assert meta["questionnaireV2"]["unify"]["droppedWorkshopId"] == "w1"
    recs = {r["mediaFileId"]: r for r in await q('SELECT "id", "mediaFileId", "filing"::text AS filing, "decisionKind", "rosterCount", "provenance"::text AS provenance FROM "QuestionnaireRecording"')}
    assert set(recs) == {"m1", "m2", "m3", "m4", "m5"}
    assert (recs["m1"]["filing"], recs["m3"]["filing"], recs["m3"]["decisionKind"]) == ("FILED", "NEEDS_DECISION", "CONCURRENT")
    assert (recs["m5"]["filing"], recs["m5"]["decisionKind"], recs["m4"]["rosterCount"]) == ("NEEDS_DECISION", "NO_PARTICIPANTS", 2)
    targets = {(r["recordingId"], r["targetKey"]) for r in await q('SELECT "recordingId", "targetKey" FROM "QuestionnaireRecordingTarget"')}
    assert ("u5-rec-m1", "Q:g1") in targets and ("u5-rec-m4", "S:gA") in targets and ("u5-rec-m2", "S:gA") in targets
    subjects = await q('SELECT "recordingId", "artisanId" FROM "QuestionnaireRecordingSubject" ORDER BY 1, 2')
    assert [(r["recordingId"], r["artisanId"]) for r in subjects if r["recordingId"] == "u5-rec-m4"] == [("u5-rec-m4", "a2"), ("u5-rec-m4", "a3")]
    drafts = await q('SELECT "id", "recordingId", "questionLineageId" FROM "QuestionnaireTakeDraft"')
    assert [(d["id"], d["recordingId"], d["questionLineageId"]) for d in drafts] == [("u5-draft-dr1", "u5-rec-m1", "g2")]
    researchers = {(r["interviewId"], r["userId"]): r["role"] for r in await q('SELECT "interviewId", "userId", "role"::text AS role FROM "QuestionnaireInterviewResearcher"')}
    assert researchers[("e1", "d1")] == "LEAD" and researchers[("e1", "d2")] == "CONTRIBUTOR" and researchers[("i1", "d2")] == "CONTRIBUTOR"
    # the section overrides are kept as they are, the workshop consent is on the ledger
    statuses = {r["id"]: r["status"] for r in await q('SELECT "id", "status"::text AS status FROM "QuestionnaireSectionStatus"')}
    assert statuses == {"ss1": "COMPLETED", "ss2": "NEEDS_REVIEW", "ss3": "NEEDS_REDO"}
    consent = await q('SELECT "subjectId", "state"::text AS state, "actorId" FROM "Consent" WHERE "migratedFrom" = \'DesignWorkshop.dictationConsent:dw1\'')
    assert [(c["subjectId"], c["state"], c["actorId"]) for c in consent] == [("dw1", "GRANTED", "d1")]
    # the coverage view reads the migrated takes
    view = await q('SELECT count(*) AS n FROM questionnaire_take_v WHERE "sessionId" = \'i2\'')
    assert int(view[0]["n"]) == 2
    rows = await q("SELECT \"title\" FROM \"QuestionnaireInterview\" WHERE \"id\" = 'e1'")
    assert rows[0]["title"] and rows[0]["title"] != "Lakshmi at the dye pit"


async def check_deferred(client) -> None:
    q = client.query_raw
    kind = await q("SELECT udt_name AS t FROM information_schema.columns WHERE table_name = 'QuestionnaireSectionStatus' AND column_name = 'status'")
    assert kind[0]["t"] == "SectionStatus"
    idx = await indexes(client)
    assert "QuestionnaireInterview_artisanSetKey_key" not in idx and "QuestionnaireSection_code_key" not in idx
    rule = await q("SELECT confdeltype AS d FROM pg_constraint WHERE conname = 'MediaFile_questionnaireInterviewId_fkey'")
    assert rule[0]["d"] == "r"
    gone = await q("SELECT count(*) AS n FROM information_schema.tables WHERE table_schema = current_schema() AND table_name LIKE 'QuestionnaireForm%'")
    assert int(gone[0]["n"]) == 0
    kept = await q('SELECT count(*) AS n FROM "QuestionnaireQuestion" WHERE "questionnaireId" IN (\'f1\', \'f2\', \'f3\')')
    assert int(kept[0]["n"]) == 8
