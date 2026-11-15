from __future__ import annotations

import asyncio
import sys
import uuid
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import DATABASE_URL, needs_db
from fastapi import HTTPException

pytestmark = needs_db


def _user(role: str, uid: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=uid, email=f"{uid}@example.test", name=uid, role=role, canReview=role == "ADMIN",
        canDownloadDataset=False, canViewProvenance=False, canManageQuestionnaire=False,
    )


async def _connect(monkeypatch: Any) -> Any:
    import app.core.db as core_db
    from prisma import Prisma

    client = Prisma(datasource={"url": DATABASE_URL})
    await client.connect()
    monkeypatch.setattr(core_db, "db", client)
    for name, module in list(sys.modules.items()):
        if name.startswith("app.") and isinstance(getattr(module, "db", None), Prisma):
            monkeypatch.setattr(module, "db", client)
    return client


def _v2(monkeypatch: Any, enabled: bool) -> None:
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "questionnaire_v2", enabled, raising=False)


async def _seed(client: Any) -> SimpleNamespace:
    t = uuid.uuid4().hex[:10]
    ids = SimpleNamespace()
    for key, role in (("admin", "ADMIN"), ("other", "RESEARCHER")):
        uid = f"u{key}{t}"
        await client.execute_raw(
            'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
            uid, f"{uid}@example.test", key.title(), role,
        )
        setattr(ids, key, _user(role, uid))
    ids.q = f"q{t}"
    await client.execute_raw(
        'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "sortOrder", "updatedAt") VALUES ($1, $2, $3, 1, now())',
        ids.q, f"Craft Survey {t}", ids.admin.id,
    )
    section = f"sD{t}"
    await client.execute_raw(
        'INSERT INTO "QuestionnaireSection" ("id", "questionnaireId", "code", "title", "sortOrder", "updatedAt") '
        "VALUES ($1, $2, $3, 'Tools', $4, now())",
        section, ids.q, f"D{t.upper()}", 700000 + int(t[:4], 16),
    )
    ids.questions, ids.lineages = {}, {}
    for n in (1, 2, 3):
        qid = f"qD{n}{t}"
        await client.execute_raw(
            'INSERT INTO "QuestionnaireQuestion" ("id", "questionnaireId", "sectionCode", "sectionTitle", "prompt", "sortOrder", '
            '"sectionId", "updatedAt") VALUES ($1, $2, $3, \'Tools\', $4, $5, $6, now())',
            qid, ids.q, f"D{t.upper()}", f"Question {n} about tools?", n, section,
        )
        ids.questions[n] = qid
    for row in await client.query_raw(
        'SELECT "id", coalesce("lineageId", "id") AS lineage FROM "QuestionnaireQuestion" WHERE "questionnaireId" = $1', ids.q
    ):
        ids.lineages[str(row["id"])] = str(row["lineage"])
    ids.workshop = f"w{t}"
    await client.execute_raw(
        'INSERT INTO "Workshop" ("id", "title", "date", "startDate", "endDate", "place", "createdById", "status", "updatedAt") '
        "VALUES ($1, 'Bagru workshop', now(), now(), now() + interval '3 days', 'Bagru', $2, 'APPROVED', now())",
        ids.workshop, ids.admin.id,
    )
    ids.artisans = []
    for i in range(2):
        aid = f"a{i}{t}"
        await client.execute_raw(
            'INSERT INTO "Artisan" ("id", "name", "place", "status", "createdById", "updatedAt") '
            "VALUES ($1, $2, 'Bagru', 'PENDING', $3, now())",
            aid, f"Artisan {i} {t}", ids.admin.id,
        )
        ids.artisans.append(aid)
    return ids


async def _admins_interview(client: Any) -> tuple[SimpleNamespace, dict[str, Any]]:
    from app.questionnaire import sessions
    from app.questionnaire.schemas import SessionCreate

    ids = await _seed(client)
    day = "2026-06-20"
    session, _ = await sessions.create_session(
        SessionCreate.model_validate({
            "clientKey": f"c-{uuid.uuid4().hex[:12]}", "questionnaireId": ids.q, "workshopId": ids.workshop,
            "sessionDate": day, "startedAt": f"{day}T05:00:00Z", "purpose": "FIRST_SITTING",
            "participants": [{"artisanId": ids.artisans[0]}],
        }),
        ids.admin,
    )
    return ids, session


async def _grant(client: Any, ids: SimpleNamespace, level: str) -> None:
    await client.execute_raw(
        'INSERT INTO "WorkshopAssignment" ("id", "workshopId", "userId", "accessLevel", "status", "updatedAt") '
        "VALUES ($1, $2, $3, $4, 'GRANTED', now())",
        f"wa{uuid.uuid4().hex[:12]}", ids.workshop, ids.other.id, level,
    )


async def _linked(client: Any, session_id: str) -> set[str]:
    rows = await client.query_raw('SELECT "artisanId" FROM "QuestionnaireInterviewArtisan" WHERE "interviewId" = $1', session_id)
    return {str(r["artisanId"]) for r in rows}


def _update(body: dict[str, Any]) -> Any:
    from app.schemas.questionnaire import QuestionnaireInterviewUpdate

    return QuestionnaireInterviewUpdate.model_validate(body)


@pytest.mark.parametrize("v2", [True, False])
def test_view_access_on_a_client_key_interview_removes_nothing(monkeypatch, v2) -> None:
    from app.api.routes import questionnaire

    _v2(monkeypatch, v2)

    async def go() -> None:
        client = await _connect(monkeypatch)
        try:
            ids, session = await _admins_interview(client)
            await _grant(client, ids, "VIEW")
            before = await _linked(client, session["id"])
            assert before == {ids.artisans[0]}
            with pytest.raises(HTTPException) as refused:
                await questionnaire.update_interview(session["id"], _update({"artisanIds": [], "title": "x"}), ids.other)
            assert refused.value.status_code == 403
            assert await _linked(client, session["id"]) == before
        finally:
            await client.disconnect()

    asyncio.run(go())


@pytest.mark.parametrize("v2", [True, False])
def test_a_contributor_adds_an_answer_and_an_artisan_to_an_admins_interview(monkeypatch, v2) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import answers

    _v2(monkeypatch, v2)

    async def go() -> None:
        client = await _connect(monkeypatch)
        try:
            ids, session = await _admins_interview(client)
            await _grant(client, ids, "CONTRIBUTE")
            a0, a1 = ids.artisans
            await questionnaire.update_interview(
                session["id"],
                _update({"artisanIds": [a0, a1], "responses": [{"questionId": ids.questions[2], "answerText": "Sheesham"}]}),
                ids.other,
            )
            assert await _linked(client, session["id"]) == {a0, a1}
            if v2:
                written = await answers.current_answer(client, a0, ids.lineages[ids.questions[2]])
                assert written and written["text"] == "Sheesham"
            else:
                rows = await client.query_raw(
                    'SELECT "answerText" FROM "QuestionnaireResponse" WHERE "interviewId" = $1 AND "questionId" = $2',
                    session["id"], ids.questions[2],
                )
                assert rows and rows[0]["answerText"] == "Sheesham"
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_a_refused_v2_answer_is_an_error_and_not_a_silent_loss(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import answers
    from app.questionnaire.schemas import AnswersPut

    _v2(monkeypatch, True)

    async def go() -> None:
        client = await _connect(monkeypatch)
        try:
            ids, session = await _admins_interview(client)
            await _grant(client, ids, "CONTRIBUTE")
            a0 = ids.artisans[0]
            lineage = ids.lineages[ids.questions[1]]
            await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": lineage, "text": "Teak", "sessionId": session["id"]}]}), ids.admin)
            with pytest.raises(HTTPException) as refused:
                await questionnaire.update_interview(
                    session["id"], _update({"responses": [{"questionId": ids.questions[1], "answerText": "Rosewood"}]}), ids.other
                )
            assert refused.value.status_code == 403
            assert refused.value.detail["refused"] == 1
            assert (await answers.current_answer(client, a0, lineage))["text"] == "Teak"
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_workshop_edit_access_works_in_a_v2_session(monkeypatch) -> None:
    from app.questionnaire import sessions

    _v2(monkeypatch, True)

    async def go() -> None:
        client = await _connect(monkeypatch)
        try:
            ids, session = await _admins_interview(client)
            row = await sessions.load_session_row(client, session["id"])
            assert await sessions.can_work_in(client, ids.other, row) is False
            assert await sessions.can_add_answers_in(client, ids.other, row) is False
            await _grant(client, ids, "EDIT")
            assert await sessions.can_work_in(client, ids.other, row) is True
        finally:
            await client.disconnect()

    asyncio.run(go())


@pytest.mark.parametrize("level", ["CONTRIBUTE", "EDIT"])
def test_legacy_answers_resend_unchanged_and_workshop_edit_may_change_them(monkeypatch, level) -> None:
    from app.api.routes import questionnaire

    _v2(monkeypatch, False)

    async def go() -> None:
        client = await _connect(monkeypatch)
        try:
            ids, session = await _admins_interview(client)
            await _grant(client, ids, level)
            qid = ids.questions[1]
            await questionnaire.update_interview(
                session["id"], _update({"responses": [{"questionId": qid, "answerText": "Teak"}]}), ids.admin
            )
            await questionnaire.update_interview(
                session["id"], _update({"responses": [{"questionId": qid, "answerText": "Teak"}]}), ids.other
            )
            change = _update({"responses": [{"questionId": qid, "answerText": "Rosewood"}]})
            if level == "EDIT":
                await questionnaire.update_interview(session["id"], change, ids.other)
            else:
                with pytest.raises(HTTPException) as refused:
                    await questionnaire.update_interview(session["id"], change, ids.other)
                assert refused.value.status_code == 403
            rows = await client.query_raw(
                'SELECT "answerText" FROM "QuestionnaireResponse" WHERE "interviewId" = $1 AND "questionId" = $2', session["id"], qid
            )
            assert rows[0]["answerText"] == ("Rosewood" if level == "EDIT" else "Teak")
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_workshop_edit_overrides_another_researchers_v2_answer_and_contribute_does_not(monkeypatch) -> None:
    from app.api.routes import questionnaire
    from app.questionnaire import answers, sessions
    from app.questionnaire.schemas import AnswersPut

    _v2(monkeypatch, True)

    async def go() -> None:
        client = await _connect(monkeypatch)
        try:
            ids, session = await _admins_interview(client)
            a0 = ids.artisans[0]
            lineage = ids.lineages[ids.questions[1]]
            await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": lineage, "text": "Teak", "sessionId": session["id"]}]}), ids.admin)
            await _grant(client, ids, "CONTRIBUTE")
            assert (await sessions.session_payload(client, session["id"], ids.other))["mayOverrideAnswers"] is False
            await client.execute_raw('UPDATE "WorkshopAssignment" SET "accessLevel" = \'EDIT\' WHERE "userId" = $1', ids.other.id)
            assert (await sessions.session_payload(client, session["id"], ids.other))["mayOverrideAnswers"] is True
            current = await answers.current_answer(client, a0, lineage)
            out = await answers.put_answers(AnswersPut.model_validate({"items": [
                {"artisanId": a0, "questionLineageId": lineage, "text": "Rosewood", "sessionId": session["id"],
                 "baseVersion": int(current["version"])}]}), ids.other)
            assert out["results"][0]["status"] not in ("FORBIDDEN", "CONFLICT")
            assert (await answers.current_answer(client, a0, lineage))["text"] == "Rosewood"
            await questionnaire.update_interview(
                session["id"], _update({"responses": [{"questionId": ids.questions[1], "answerText": "Sheesham"}]}), ids.other
            )
            assert (await answers.current_answer(client, a0, lineage))["text"] == "Sheesham"
        finally:
            await client.disconnect()

    asyncio.run(go())
