"""Designer-portal's form authoring once the forms are unified (UNIFY U5/U10; stream U1).

On a database of its own, migrated and with the per-form section keys the migration's apply puts in
place (the deferred ``section_keys`` folder), ``QUESTIONNAIRE_V2`` on: every authoring path writes
the instrument tables (``QuestionnaireSection``/``QuestionnaireQuestion``) and none writes the old
form tables, which are read-only legacy from then on.

* a form made by hand, its sections and questions (positions unique per form; a move shifts the
  others; a question carries its section's code and title, and renaming the section renames them);
* the reword rule on the shared evidence: unanswered wording is edited in place, an answered one is
  superseded keeping its lineage and display code, an answered one taken out is retired, an
  unanswered one deleted;
* a hand-filled workbook: the form, and its answers as WORKBOOK sessions (one per column);
* reuse, the handoff bundle (import idempotent per importer, and the download), and the annexure in
  the one shape both portals serve;
* the retired global form: a create that resolves to no form is refused, never keyed by artisan set.

CI runs it in the "Questionnaire unify migration" job with ``QUESTIONNAIRE_UNIFIED_DATABASE_URL``
naming an empty database (it cannot share the integration job's, whose global keys it would drop).
"""

from __future__ import annotations

import asyncio
import gzip
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlsplit

import pytest

BACKEND = Path(__file__).resolve().parents[1]
URL = os.environ.get("QUESTIONNAIRE_UNIFIED_DATABASE_URL", "")

pytestmark = pytest.mark.skipif(
    not URL or urlsplit(URL).hostname not in ("127.0.0.1", "localhost"),
    reason="QUESTIONNAIRE_UNIFIED_DATABASE_URL is not set to an empty LOCAL database of its own (CI: the unify job)",
)

SECTION_KEYS = "20261108091000_questionnaire_unify_section_keys"


@pytest.fixture(scope="module")
def migrated(tmp_path_factory: pytest.TempPathFactory) -> None:
    root = tmp_path_factory.mktemp("unified") / "prisma"
    (root / "migrations").mkdir(parents=True)
    shutil.copyfile(BACKEND / "prisma" / "schema.prisma", root / "schema.prisma")
    shutil.copyfile(BACKEND / "prisma" / "migrations" / "migration_lock.toml", root / "migrations" / "migration_lock.toml")
    names = sorted(p.name for p in (BACKEND / "prisma" / "migrations").iterdir() if p.is_dir())
    for name in names:
        shutil.copytree(BACKEND / "prisma" / "migrations" / name, root / "migrations" / name)
    deferred = BACKEND / "prisma" / "deferred" / SECTION_KEYS
    if deferred.is_dir():  # until the switch-on commit moves it into prisma/migrations
        shutil.copytree(deferred, root / "migrations" / SECTION_KEYS)
    env = {**os.environ, "DATABASE_URL": URL}
    out = subprocess.run([sys.executable, "-m", "prisma", "migrate", "deploy", "--schema", str(root / "schema.prisma")],
                         cwd=BACKEND, env=env, capture_output=True, text=True, timeout=900, check=False)
    assert out.returncode == 0, out.stdout[-3000:] + out.stderr[-3000:]


SEED = [
    "INSERT INTO \"User\" (\"id\", \"email\", \"name\", \"role\", \"updatedAt\") VALUES "
    "('d1', 'd1@x.test', 'Nandini', 'DESIGNER', now()), ('p1', 'p1@x.test', 'Priya', 'PROFESSOR', now()), "
    "('admin', 'admin@x.test', 'Admin', 'ADMIN', now()) ON CONFLICT DO NOTHING",
    "INSERT INTO \"DesignWorkshop\" (\"id\", \"title\", \"createdById\", \"updatedAt\") VALUES ('dw1', 'Bagru block printing', 'd1', now()) "
    "ON CONFLICT DO NOTHING",
    "INSERT INTO \"Artisan\" (\"id\", \"name\", \"place\", \"createdById\", \"updatedAt\") VALUES ('a1', 'Giriraj', 'Bagru', 'd1', now()) "
    "ON CONFLICT DO NOTHING",
]


def _user(uid: str, role: str) -> SimpleNamespace:
    return SimpleNamespace(id=uid, role=role, name=uid, isActive=True, canManageQuestionnaire=False)


def _run(monkeypatch: pytest.MonkeyPatch, body) -> None:
    from app.api.routes import questionnaire_forms as routes
    from app.core import db as core_db
    from app.core.config import get_settings
    from app.questionnaire import forms
    from app.services import questionnaire_forms as service, records
    from prisma import Prisma

    async def go() -> None:
        client = Prisma(datasource={"url": URL})
        await client.connect()
        for module in (core_db, routes, service, records):
            monkeypatch.setattr(module, "db", client)
        monkeypatch.setattr(get_settings(), "questionnaire_v2", True)
        forms._unified_cache.clear()
        try:
            for sql in SEED:
                await client.execute_raw(sql)
            await body(client)
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_authoring_writes_the_instrument_tables_and_never_the_old_ones(migrated, monkeypatch: pytest.MonkeyPatch) -> None:
    from app.api.routes import questionnaire_forms as routes
    from app.questionnaire import answers, forms, schemas as S, sessions
    from app.schemas.questionnaire import (
        CustomQuestionCreate,
        CustomQuestionUpdate,
        CustomSectionCreate,
        CustomSectionUpdate,
        QuestionnaireCreate,
    )

    designer = _user("d1", "DESIGNER")

    async def body(client) -> None:
        assert await forms.authoring_unified(client)
        old_sections = await client.questionnaireformsection.count()
        old_questions = await client.questionnaireformquestion.count()

        made = await routes.create_questionnaire(QuestionnaireCreate(title="Dyers of Bagru", sections=[
            CustomSectionCreate(title="Dyes", code="A", questions=[CustomQuestionCreate(prompt="Which dyes?"), CustomQuestionCreate(prompt="Who mixes them?")]),
        ]), designer)
        fid = made["id"]
        assert [s["code"] for s in made["sections"]] == ["A"] and len(made["sections"][0]["questions"]) == 2
        rows = await client.query_raw('SELECT "sectionCode", "sectionTitle", "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1 ORDER BY "sortOrder"', fid)
        assert [(r["sectionCode"], r["sectionTitle"]) for r in rows] == [("A", "Dyes"), ("A", "Dyes")]
        assert all(r["lineageId"] and r["displayCode"] for r in rows), "the U0 trigger gave each its lineage and display code"

        # A second section, then one inserted at position 1: the others move down, one per form.
        form = await routes.create_section(fid, CustomSectionCreate(title="Tools", code="B"), designer)
        tools = next(s for s in form["sections"] if s["code"] == "B")
        form = await routes.create_section(fid, CustomSectionCreate(title="Intro", code="C", sortOrder=1), designer)
        assert [s["code"] for s in form["sections"]] == ["C", "A", "B"]
        renamed = await routes.update_section(fid, tools["id"], CustomSectionUpdate(title="Blocks"), designer)
        assert next(s["title"] for s in renamed["sections"] if s["id"] == tools["id"]) == "Blocks"
        form = await routes.create_question(fid, tools["id"], CustomQuestionCreate(prompt="Who carves the blocks?"), designer)
        carve = next(q for s in form["sections"] for q in s["questions"] if q["prompt"] == "Who carves the blocks?")
        q = (await client.query_raw('SELECT "sectionTitle" FROM "QuestionnaireQuestion" WHERE "id" = $1', carve["id"]))[0]
        assert q["sectionTitle"] == "Blocks", "a question carries its section's current title"

        # The reword rule on the shared evidence.
        dyes = next(s for s in form["sections"] if s["code"] == "A")
        q1, q2 = dyes["questions"]
        edited = await routes.update_question(fid, q2["id"], CustomQuestionUpdate(prompt="Who mixes the dyes?"), designer)
        assert edited["action"] == "updated", "nobody answered it: edited in place"
        create = S.SessionCreate(clientKey="ua-1", questionnaireId=fid, designWorkshopId="dw1", sessionDate="2026-10-22",
                                 participants=[S.ParticipantInput(artisanId="a1")])
        session, _ = await sessions.create_session(create, designer, check_instrument=False)
        lineage = (await client.query_raw('SELECT "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "id" = $1', q1["id"]))[0]
        put = await answers.put_answers(S.AnswersPut(items=[S.AnswerItem(artisanId="a1", questionLineageId=lineage["lineageId"],
                                                                          sessionId=session["id"], text="Indigo")]), designer)
        assert put["results"][0]["status"] == "SAVED"
        reworded = await routes.update_question(fid, q1["id"], CustomQuestionUpdate(prompt="Which natural dyes?"), designer)
        assert reworded["action"] == "superseded"
        new = (await client.query_raw('SELECT "lineageId", "displayCode", "isActive" FROM "QuestionnaireQuestion" WHERE "id" = $1', reworded["replacementId"]))[0]
        assert (new["lineageId"], new["displayCode"], new["isActive"]) == (lineage["lineageId"], lineage["displayCode"], True)
        retired = await routes.remove_question(fid, q1["id"], designer)
        assert retired["action"] == "retired", "the wording the answer was given under is kept"
        deleted = await routes.remove_question(fid, carve["id"], designer)
        assert deleted["action"] == "deleted"

        assert await client.questionnaireformsection.count() == old_sections, "the old form tables are not written"
        assert await client.questionnaireformquestion.count() == old_questions

    _run(monkeypatch, body)


def test_a_workbook_reuse_bundle_and_annexure_on_the_unified_tables(migrated, monkeypatch: pytest.MonkeyPatch) -> None:
    from fastapi import HTTPException

    from app.questionnaire import forms, library
    from app.services import questionnaire_forms as service
    from app.services.questionnaire_xlsx import ParsedQuestion, ParsedQuestionnaire, ParsedSection

    designer, professor = _user("d1", "DESIGNER"), _user("p1", "PROFESSOR")

    async def body(client) -> None:
        parsed = ParsedQuestionnaire(
            sections=[ParsedSection(code="A", title="Dyes", questions=[
                ParsedQuestion(prompt="Which dyes?", row=5, answers={"Meena": "Indigo", "Ramesh": ""}),
                ParsedQuestion(prompt="Who mixes them?", row=6, answers={"Meena": "Her mother", "Ramesh": "He does"}),
            ])],
            title="Hand-filled", entryLabels=["Meena", "Ramesh"],
        )
        fid, report = await service.create_from_parsed(parsed, owner_id="d1", design_workshop_id="dw1", user=designer)
        assert (report["entriesCreated"], report["answersImported"]) == (2, 3)
        sessions_rows = await client.query_raw(
            'SELECT i."source"::text AS source, count(rv.*) AS n FROM "QuestionnaireInterview" i '
            'JOIN "QuestionnaireAnswerRevision" rv ON rv."sessionId" = i."id" WHERE i."questionnaireId" = $1 GROUP BY 1', fid
        )
        assert [(r["source"], int(r["n"])) for r in sessions_rows] == [("UPLOAD", 3)]
        origins = await client.query_raw(
            'SELECT DISTINCT rv."origin"::text AS o FROM "QuestionnaireAnswerRevision" rv JOIN "QuestionnaireInterview" i ON i."id" = rv."sessionId" '
            'WHERE i."questionnaireId" = $1', fid
        )
        assert [r["o"] for r in origins] == ["WORKBOOK"]

        copy_id, copy_report = await service.reuse_questionnaire(fid, owner_id="d1", title="Copy of hand-filled")
        assert copy_report["created"] == 2
        assert await client.questionnairequestion.count(where={"questionnaireId": copy_id}) == 2

        document = await library.bundle_document(fid)
        raw = library.bundle_bytes(document)
        assert json.loads(gzip.decompress(raw))["portal"] == "designer-portal"
        tables = forms.tables(client, True)
        first, created = await library.import_bundle(document, professor, tables=tables)
        again, created_again = await library.import_bundle(document, professor, tables=tables)
        assert created and not created_again and again.id == first.id
        assert await client.questionnairequestion.count(where={"questionnaireId": first.id}) == 2
        assert library.bundle_filename("2nd Craft Toolkit", at="2026-10-22T04:30:00Z") == "DesignWorkshop_Questionnaire_2026-10-22_2nd-Craft-Toolkit_Handoff.dpwq"
        with pytest.raises(HTTPException):
            library.normalise_bundle({**document, "v": 3})

        doc = await library.annexure(design_workshop_id="dw1")
        form = next(f for g in doc["groups"] for f in g["forms"] if f["id"] == fid)
        assert form["sessionCount"] == 2 and {s["label"] for s in form["sittings"]} == {"Meena", "Ramesh"}
        assert form["sections"][0]["questions"][1]["answers"] == [{"subject": "Meena", "text": "Her mother"}, {"subject": "Ramesh", "text": "He does"}]
        assert "Questionnaire annexure: Bagru block printing" in library.annexure_text(doc)

        # The retired global form: with nothing bound and no default, an old build's create resolves
        # to no form, and the route then refuses it (it never writes a row keyed by artisan set).
        from app.questionnaire import shims_dr

        await client.execute_raw('DELETE FROM "QuestionnaireBinding"')
        await client.execute_raw('UPDATE "Questionnaire" SET "isDefault" = false')
        payload = SimpleNamespace(designWorkshopId="dw1", artisanIds=["a1"], title=None, notes=None, place=None,
                                  interviewDate=None, recordedAt=None)
        assert await shims_dr.create_global_interview(payload, designer) is None
        assert await forms.authoring_unified(client)
        source = (BACKEND / "app" / "api" / "routes" / "questionnaire.py").read_text(encoding="utf-8")
        assert "if await unified_forms.authoring_unified():" in source

    _run(monkeypatch, body)
