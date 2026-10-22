"""Designer-portal's shared questionnaire end to end, on a real migrated Postgres (stream U1).

With ``QUESTIONNAIRE_V2`` switched on for the test: a form whose sections live in the shared tables,
bound to a design workshop, takes a session with only a guest (A18); the guest's answer is locked
to its author (A11, ANSWER_LOCKED per item, P15); the old sitting routes reach the same rows through
``shims_dr`` with their old shapes (UNIFY 4.5); the readers and the annexure see the session (C10,
A12); and a soft-deleted session disappears from all of them until it is restored (A14).
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import date
from types import SimpleNamespace

import pytest
from conftest import DATABASE_URL, needs_db

pytestmark = needs_db


async def _client():
    from prisma import Prisma

    client = Prisma(datasource={"url": DATABASE_URL})
    await client.connect()
    return client


async def _seed(client, tag: str) -> dict[str, str]:
    ids = {"lead": f"ul{tag}", "other": f"uo{tag}", "admin": f"ua{tag}", "dw": f"dw{tag}", "form": f"f{tag}",
           "sec": f"s{tag}", "q1": f"q1{tag}", "q2": f"q2{tag}"}
    for key, role in (("lead", "RESEARCHER"), ("other", "RESEARCHER"), ("admin", "ADMIN")):
        await client.execute_raw(
            'INSERT INTO "User" ("id", "email", "name", "role", "updatedAt") VALUES ($1, $2, $3, $4::"UserRole", now())',
            ids[key], f"{ids[key]}@example.test", key.title(), role,
        )
    await client.execute_raw(
        'INSERT INTO "DesignWorkshop" ("id", "title", "createdById", "updatedAt") VALUES ($1, $2, $3, now())',
        ids["dw"], f"Bagru {tag}", ids["lead"],
    )
    await client.execute_raw(
        'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "designWorkshopId", "kind", "updatedAt") '
        "VALUES ($1, $2, $3, $4, 'MARKET_SURVEY', now())",
        ids["form"], f"Market survey {tag}", ids["lead"], ids["dw"],
    )
    code = f"M{tag.upper()}"
    await client.execute_raw(
        'INSERT INTO "QuestionnaireSection" ("id", "code", "title", "sortOrder", "questionnaireId", "updatedAt") '
        "VALUES ($1, $2, 'Market', $3, $4, now())",
        ids["sec"], code, 800000 + int(tag[:4], 16), ids["form"],
    )
    for n, key in ((1, "q1"), (2, "q2")):
        await client.execute_raw(
            'INSERT INTO "QuestionnaireQuestion" ("id", "sectionCode", "sectionTitle", "prompt", "sortOrder", "sectionId", '
            '"questionnaireId", "isRequired", "updatedAt") VALUES ($1, $2, \'Market\', $3, $4, $5, $6, $7, now())',
            ids[key], code, f"Question {n}?", n, ids["sec"], ids["form"], n == 1,
        )
    return ids


def _user(uid: str, role: str = "RESEARCHER") -> SimpleNamespace:
    return SimpleNamespace(id=uid, role=role, name=uid)


@pytest.fixture
def v2_on(monkeypatch: pytest.MonkeyPatch):
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "questionnaire_v2", True)


def test_a_guest_session_on_a_bound_form_locks_and_shims_and_reads(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    from app.core import db as core_db
    from app.questionnaire import answers, bindings, schemas as S, sessions, shims_dr
    from app.services import questionnaire_forms as forms_service, questionnaire_readers
    from app.services.questionnaire_forms import report_items

    async def go() -> None:
        client = await _client()
        monkeypatch.setattr(core_db, "db", client)
        monkeypatch.setattr(forms_service, "db", client)
        tag = uuid.uuid4().hex[:10]
        try:
            ids = await _seed(client, tag)
            lead, other, admin = _user(ids["lead"]), _user(ids["other"]), _user(ids["admin"], "ADMIN")
            # The start sheet's form: the only binding (mirrored from the attachment).
            resolved = await bindings.resolve_for_workshop(design_workshop_id=ids["dw"], client=client)
            assert resolved["questionnaireId"] == ids["form"]
            assert await shims_dr.form_is_unified(ids["form"], client=client)

            # A18: a session with only a guest saves; the title is generated.
            create = S.SessionCreate(
                clientKey=f"ck{tag}", questionnaireId=ids["form"], designWorkshopId=ids["dw"], sessionDate=date(2026, 10, 22),
                respondents=[S.RespondentInput(name="Shopper at Johari Bazaar", kind="GUEST")],
            )
            session, created = await sessions.create_session(create, lead)
            assert created and session["respondents"][0]["name"] == "Shopper at Johari Bazaar"
            again, created_again = await sessions.create_session(create, lead)
            assert not created_again and again["id"] == session["id"], "B1: idempotent on clientKey"
            sid, rid = session["id"], session["respondents"][0]["id"]
            lineage = (await client.query_raw('SELECT "lineageId" FROM "QuestionnaireQuestion" WHERE "id" = $1', ids["q1"]))[0]["lineageId"]

            item = {"respondentId": rid, "questionLineageId": lineage, "sessionId": sid, "text": "Block prints sell best", "note": "Asked twice"}
            saved = await answers.put_answers(S.AnswersPut(items=[S.AnswerItem(**item)]), lead)
            assert saved["results"][0]["status"] == "SAVED"
            # A11 / P15: another researcher on the session cannot change it; the lead's answer stands.
            await sessions.add_researcher(client, sid, ids["other"], "CONTRIBUTOR")
            locked = await answers.put_answers(S.AnswersPut(items=[S.AnswerItem(**{**item, "text": "Dyes", "baseVersion": 1})]), other)
            assert locked["results"][0]["status"] == "FORBIDDEN" and locked["results"][0]["code"] == "ANSWER_LOCKED"
            assert (await answers.put_answers(S.AnswersPut(items=[S.AnswerItem(**{**item, "text": "Dyes", "baseVersion": 1})]), admin)
                    )["results"][0]["status"] == "SAVED", "an admin may change it"
            # The lock is shown before an edit is refused: the current answer says who wrote it (P15).
            locked_current = locked["results"][0]["current"]
            assert locked_current["actorId"] == ids["lead"] and locked_current["actorName"] == "Lead"
            # Decision C8: a document sent alone is flattened into the canonical text; both are kept.
            lineage2 = (await client.query_raw('SELECT "lineageId" FROM "QuestionnaireQuestion" WHERE "id" = $1', ids["q2"]))[0]["lineageId"]
            doc = {"blocks": [{"kind": "BULLET_ITEM", "spans": ["Indigo"]}, {"kind": "BULLET_ITEM", "spans": ["Madder"]}]}
            rich = await answers.put_answers(S.AnswersPut(items=[S.AnswerItem(
                respondentId=rid, questionLineageId=lineage2, sessionId=sid, text="", richText=doc)]), lead)
            stored = rich["results"][0]["answer"]
            assert stored["text"] == "• Indigo\n• Madder"
            assert stored["richText"] and "Madder" in stored["richText"]

            # The old sitting routes, through the shim: same shapes, the lock reported per question.
            entry = await shims_dr.create_entry(ids["form"], SimpleNamespace(title="Typed title", respondentName="Visitor", notes="Met at the stall"), lead)
            assert entry["respondentName"] == "Visitor" and entry["title"] == "Typed title" and entry["notes"] == "Met at the stall"
            batch = SimpleNamespace(answers=[SimpleNamespace(questionId=ids["q1"], answerText="Cotton", notes=None)])
            out = await shims_dr.record_answers(ids["form"], entry["id"], batch, lead)
            assert out["saved"]["created"] == 1 and out["answers"][0]["answerText"] == "Cotton"
            await sessions.add_researcher(client, entry["id"], ids["other"], "CONTRIBUTOR")
            batch2 = SimpleNamespace(answers=[SimpleNamespace(questionId=ids["q1"], answerText="Silk", notes=None)])
            refused = await shims_dr.record_answers(ids["form"], entry["id"], batch2, other)
            assert refused["saved"]["rejected"][0]["code"] == "ANSWER_OWNED_BY_OTHER", "the old code, never a new one"
            listed = await shims_dr.entries_for_form(ids["form"])
            assert {e["id"] for e in listed} == {sid, entry["id"]}

            # C10 / A12: the readers and the annexure see the sessions.
            counts = await questionnaire_readers.session_counts([ids["form"]], client=client)
            assert counts[ids["form"]]["count"] == 2
            assert (await questionnaire_readers.answer_counts([sid], client=client))[sid] == 2, "the typed answer and the rich-text one"
            items = await report_items(ids["dw"])
            mine = [i for i in items if i.questionnaire_id == ids["form"]]
            assert mine and {s.respondent_name for s in mine[0].printed_sittings} == {"Shopper at Johari Bazaar", "Visitor"}

            # A14: a soft-deleted session is invisible to every reader until restored (content admin).
            from fastapi import HTTPException

            with pytest.raises(HTTPException):
                await sessions.delete_session(sid, lead)
            await sessions.delete_session(sid, admin)
            assert (await questionnaire_readers.session_counts([ids["form"]], client=client))[ids["form"]]["count"] == 1
            assert sid not in await questionnaire_readers.answer_counts([sid], client=client)
            await sessions.restore_session(sid, admin)
            assert (await questionnaire_readers.session_counts([ids["form"]], client=client))[ids["form"]]["count"] == 2
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_an_old_global_form_create_lands_on_the_primary_form(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    from app.core import db as core_db
    from app.questionnaire import shims_dr

    async def go() -> None:
        client = await _client()
        monkeypatch.setattr(core_db, "db", client)
        tag = uuid.uuid4().hex[:10]
        try:
            ids = await _seed(client, tag)
            payload = SimpleNamespace(designWorkshopId=ids["dw"], artisanIds=[], title="Interview with the dyers",
                                      notes=None, place="Bagru", interviewDate=None, recordedAt=None)
            row = await shims_dr.create_global_interview(payload, _user(ids["lead"]))
            assert row is not None and row.questionnaireId == ids["form"]
            assert row.legacyTitle == "Interview with the dyers" and row.designWorkshopId == ids["dw"]
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_a_session_keeps_the_start_sheet_fix_and_an_admin_sets_the_global_default(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    """C9: the start sheet's GPS fix becomes the session's Location (never asked on an edit). B32:
    ``PUT /questionnaires/{id}/default`` is an admin's; one default at a time; a session whose
    workshop has nothing bound is offered it."""
    from fastapi import HTTPException

    from app.api.routes import questionnaire_forms as forms_routes
    from app.core import db as core_db
    from app.questionnaire import bindings, schemas as S, sessions
    from app.schemas.questionnaire import QuestionnaireDefaultUpdate
    from app.services import questionnaire_forms as forms_service, records

    async def go() -> None:
        client = await _client()
        for module in (core_db, forms_service, records, forms_routes):
            monkeypatch.setattr(module, "db", client)
        tag = uuid.uuid4().hex[:10]
        try:
            ids = await _seed(client, tag)
            lead, admin = _user(ids["lead"]), _user(ids["admin"], "ADMIN")
            create = S.SessionCreate(
                clientKey=f"loc{tag}", questionnaireId=ids["form"], designWorkshopId=ids["dw"], sessionDate=date(2026, 10, 22),
                place="Bagru", respondents=[S.RespondentInput(name="Visitor", kind="GUEST")],
                location=S.SessionLocation(latitude=26.82, longitude=75.55, accuracy=12),
            )
            session, _ = await sessions.create_session(create, lead)
            row = (await client.query_raw(
                'SELECT l."latitude", l."longitude", l."accuracy", i."place" FROM "QuestionnaireInterview" i '
                'JOIN "Location" l ON l."id" = i."locationId" WHERE i."id" = $1', session["id"],
            ))[0]
            assert (row["latitude"], row["longitude"], row["accuracy"], row["place"]) == (26.82, 75.55, 12, "Bagru")
            from pydantic import ValidationError

            with pytest.raises(ValidationError):
                S.SessionCreate(clientKey="x", questionnaireId="f", sessionDate=date(2026, 10, 22),
                                respondents=[S.RespondentInput(name="V", kind="GUEST")], location={"latitude": 120, "longitude": 0})

            # B32: only an admin; the old default is cleared; clearing touches this form only.
            other = f"g{tag}"
            await client.execute_raw(
                'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "isDefault", "updatedAt") VALUES ($1, $2, $3, false, now())',
                other, f"Other {tag}", ids["admin"],
            )
            await client.execute_raw('UPDATE "Questionnaire" SET "isDefault" = false WHERE "isDefault"')
            out = await forms_routes.set_default_questionnaire(other, QuestionnaireDefaultUpdate(isDefault=True), admin)
            assert out["isDefault"] is True
            out = await forms_routes.set_default_questionnaire(ids["form"], QuestionnaireDefaultUpdate(isDefault=True), admin)
            assert out["isDefault"] is True
            defaults = await client.query_raw('SELECT "id" FROM "Questionnaire" WHERE "isDefault"')
            assert [r["id"] for r in defaults] == [ids["form"]], "one default at a time"
            resolved = await bindings.resolve_for_workshop(client=client)
            assert (resolved["questionnaireId"], resolved["reason"]) == (ids["form"], "GLOBAL_DEFAULT")
            await forms_routes.set_default_questionnaire(other, QuestionnaireDefaultUpdate(isDefault=False), admin)
            assert [r["id"] for r in await client.query_raw('SELECT "id" FROM "Questionnaire" WHERE "isDefault"')] == [ids["form"]]
            await client.execute_raw('UPDATE "Questionnaire" SET "deletedAt" = now() WHERE "id" = $1', other)
            with pytest.raises(HTTPException):
                await forms_routes.set_default_questionnaire(other, QuestionnaireDefaultUpdate(isDefault=True), admin)
            await forms_routes.set_default_questionnaire(ids["form"], QuestionnaireDefaultUpdate(isDefault=False), admin)
        finally:
            await client.disconnect()

    asyncio.run(go())


def test_a_researcher_may_use_a_bound_shared_default_or_own_form_on_a_workshop(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    """Decision 4 and owner amendment 2: under a workshop a researcher who does not manage
    questionnaires may start a session on any form bound to it, the type default, the global
    default, or their own or a shared form; anyone else's unbound form is refused."""
    from fastapi import HTTPException

    from app.core import db as core_db
    from app.questionnaire import schemas as S, sessions

    async def go() -> None:
        client = await _client()
        monkeypatch.setattr(core_db, "db", client)
        tag = uuid.uuid4().hex[:10]
        try:
            ids = await _seed(client, tag)
            lead = _user(ids["lead"])
            foreign, own = f"x{tag}", f"o{tag}"
            for fid, owner in ((foreign, ids["admin"]), (own, ids["lead"])):
                await client.execute_raw(
                    'INSERT INTO "Questionnaire" ("id", "title", "ownerId", "updatedAt") VALUES ($1, $2, $3, now())',
                    fid, f"Form {fid}", owner,
                )

            def create(form: str, n: int) -> S.SessionCreate:
                return S.SessionCreate(clientKey=f"ia{n}{tag}", questionnaireId=form, designWorkshopId=ids["dw"],
                                       sessionDate=date(2026, 10, 22), respondents=[S.RespondentInput(name="V", kind="GUEST")])

            await sessions.assert_instrument_allowed(client, ids["form"], None, lead, design_workshop_id=ids["dw"])
            await sessions.assert_instrument_allowed(client, own, None, lead, design_workshop_id=ids["dw"])
            with pytest.raises(HTTPException):
                await sessions.create_session(create(foreign, 1), lead)
            await client.execute_raw('UPDATE "Questionnaire" SET "isShared" = true WHERE "id" = $1', foreign)
            session, created = await sessions.create_session(create(foreign, 2), lead)
            assert created and session["questionnaire"]["id"] == foreign
            await client.execute_raw('UPDATE "Questionnaire" SET "deletedAt" = now() WHERE "id" = $1', foreign)
            with pytest.raises(HTTPException):
                await sessions.assert_instrument_allowed(client, foreign, None, lead, design_workshop_id=ids["dw"])
        finally:
            await client.disconnect()

    asyncio.run(go())
