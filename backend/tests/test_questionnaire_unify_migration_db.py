"""Designer-portal's half of the questionnaire unification's additive migration (U0), on a real,
migrated Postgres: the shared tables exist with the shared package's columns, today's "attach a form
to a design workshop" is mirrored into bindings, the start sheet's form resolves through them and a
design workshop kind's default, new global questions get a lineage and a frozen code, and the
section override keeps its three states. Nothing here is switched on; the forms work as before.
"""

from __future__ import annotations

import asyncio
import uuid

import pytest
from conftest import DATABASE_URL, needs_db

pytestmark = needs_db


async def _client():
    from prisma import Prisma

    client = Prisma(datasource={"url": DATABASE_URL})
    await client.connect()
    return client


def test_the_shared_tables_and_the_view_exist() -> None:
    async def go() -> None:
        client = await _client()
        try:
            for table in ("QuestionnaireRecording", "QuestionnaireRecordingTarget", "QuestionnaireRecordingSubject",
                          "QuestionnaireRecordingEvent", "QuestionnaireAnswer", "QuestionnaireAnswerRevision", "QuestionnaireTakeDraft",
                          "MediaUploadIntent", "QuestionnaireInterviewResearcher", "QuestionnaireRespondent",
                          "QuestionnaireRecordingRespondent", "QuestionnaireBinding", "QuestionnaireTypeDefault", "AppClientVersion"):
                rows = await client.query_raw(f'SELECT count(*) AS n FROM "{table}"')
                assert int(rows[0]["n"]) >= 0
            view = await client.query_raw('SELECT "subjectKey", "designWorkshopId", "position" FROM questionnaire_take_v LIMIT 1')
            assert isinstance(view, list)
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_attaching_a_form_binds_it_and_the_form_resolves(monkeypatch) -> None:
    from app.core import db as core_db
    from app.questionnaire import bindings

    async def go() -> None:
        client = await _client()
        monkeypatch.setattr(core_db, "db", client)
        tag = uuid.uuid4().hex[:10]
        try:
            await client.execute_raw('INSERT INTO "User" ("id", "email", "name", "updatedAt") VALUES ($1, $2, $3, now())',
                                     f"u{tag}", f"u{tag}@example.test", "Designer")
            for n, kind in ((1, "BASELINE"), (2, "BASELINE")):
                await client.execute_raw(
                    'INSERT INTO "DesignWorkshop" ("id", "title", "createdById", "workshopKind", "updatedAt") VALUES ($1, $2, $3, $4, now())',
                    f"dw{n}{tag}", f"Workshop {n} {tag}", f"u{tag}", f"{kind}{tag}",
                )
            await client.execute_raw(
                'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "designWorkshopId", "kind", "updatedAt") '
                "VALUES ($1, $2, $3, $4, 'WORKSHOP_INTERVIEW', now())",
                f"f{tag}", f"Form {tag}", f"u{tag}", f"dw1{tag}",
            )
            bound = await client.query_raw('SELECT "designWorkshopId", "isPrimary" FROM "QuestionnaireBinding" WHERE "questionnaireId" = $1', f"f{tag}")
            assert [(b["designWorkshopId"], b["isPrimary"]) for b in bound] == [(f"dw1{tag}", False)]
            out = await bindings.resolve_for_workshop(design_workshop_id=f"dw1{tag}", client=client)
            assert (out["questionnaireId"], out["reason"]) == (f"f{tag}", "ONLY_BINDING")
            # Moving the attachment moves the binding.
            await client.execute_raw('UPDATE "Questionnaire" SET "designWorkshopId" = $2 WHERE "id" = $1', f"f{tag}", f"dw2{tag}")
            bound = await client.query_raw('SELECT "designWorkshopId" FROM "QuestionnaireBinding" WHERE "questionnaireId" = $1', f"f{tag}")
            assert [b["designWorkshopId"] for b in bound] == [f"dw2{tag}"]
            # Unbound workshop: the design workshop kind's default form.
            admin = type("U", (), {"id": f"u{tag}", "role": "ADMIN"})()
            await bindings.set_type_default(f"f{tag}", admin, design_workshop_kind=f"BASELINE{tag}", client=client)
            out = await bindings.resolve_for_workshop(design_workshop_id=f"dw1{tag}", client=client)
            assert (out["questionnaireId"], out["reason"]) == (f"f{tag}", "TYPE_DEFAULT")
            await bindings.set_type_default(None, admin, design_workshop_kind=f"BASELINE{tag}", client=client)
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_a_new_global_question_gets_a_lineage_and_the_override_keeps_three_states() -> None:
    from prisma.errors import PrismaError

    async def go() -> None:
        client = await _client()
        tag = uuid.uuid4().hex[:8].upper()
        try:
            await client.execute_raw(
                'INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "updatedAt") VALUES ($1, $2, $3, $4, now())',
                f"s{tag}", f"Z{tag}", "Section", 900000 + int(tag[:4], 16),
            )
            await client.execute_raw(
                'INSERT INTO "QuestionnaireQuestion" ("id", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", "updatedAt") '
                "VALUES ($1, $2, 'Section', 'What do you make?', 1, $3, now())",
                f"q{tag}", f"Z{tag}", f"s{tag}",
            )
            row = (await client.query_raw('SELECT "lineageId", "displayCode" FROM "QuestionnaireQuestion" WHERE "id" = $1', f"q{tag}"))[0]
            assert row["lineageId"] == f"q{tag}" and row["displayCode"] == f"Z{tag}1"
            with pytest.raises(PrismaError, match="three_states|invalid input value for enum"):
                await client.execute_raw(
                    'INSERT INTO "QuestionnaireSectionStatus" ("id", "artisanId", "sectionId", "status", "setById", "updatedAt") '
                    "VALUES ($1, 'nobody', $2, 'DONE', 'nobody', now())",
                    f"x{tag}", f"s{tag}",
                )
            await client.execute_raw('DELETE FROM "QuestionnaireQuestion" WHERE "id" = $1', f"q{tag}")
            await client.execute_raw('DELETE FROM "QuestionnaireSection" WHERE "id" = $1', f"s{tag}")
        finally:
            await client.disconnect()

    asyncio.run(go())
