from __future__ import annotations

import asyncio
import uuid
from datetime import date

import pytest
from conftest import needs_db
from fastapi import HTTPException
from test_questionnaire_dr_v2_db import _client, _seed, _user

pytestmark = needs_db


@pytest.fixture
def v2_on(monkeypatch: pytest.MonkeyPatch):
    from app.core.config import get_settings

    monkeypatch.setattr(get_settings(), "questionnaire_v2", True)


def _world(monkeypatch: pytest.MonkeyPatch, body) -> None:
    from app.core import db as core_db
    from app.questionnaire import schemas as S, sessions
    from app.services import questionnaire_forms as forms_service, records

    async def go() -> None:
        client = await _client()
        for module in (core_db, forms_service, records):
            monkeypatch.setattr(module, "db", client)
        tag = uuid.uuid4().hex[:10]
        try:
            ids = await _seed(client, tag)
            create = S.SessionCreate(
                clientKey=f"dv{tag}", questionnaireId=ids["form"], designWorkshopId=ids["dw"], sessionDate=date(2026, 11, 9),
                place="Bagru, Jaipur", note="First visit", respondents=[S.RespondentInput(name="Visitor", kind="GUEST")],
            )
            session, _ = await sessions.create_session(create, _user(ids["lead"]))
            await body(client, ids, session, create)
        finally:
            await client.disconnect()

    asyncio.run(go())


async def _version(client, session_id: str) -> int:
    rows = await client.query_raw('SELECT "version" FROM "QuestionnaireInterview" WHERE "id" = $1', session_id)
    return int(rows[0]["version"])


def test_a_join_and_a_recording_leave_the_version_so_an_edit_loaded_before_them_lands(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    from app.questionnaire import schemas as S, sessions

    async def body(client, ids, session, create) -> None:
        loaded = session["version"]
        joining = create.model_copy(update={"researchers": [S.ResearcherInput(userId=ids["other"], role="CONTRIBUTOR")]})
        _, created = await sessions.create_session(joining, _user(ids["other"]))
        assert created is False
        await sessions.touch_session(client, session["id"])
        assert await _version(client, session["id"]) == loaded
        edited = await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, place="Sanganer, Jaipur"), _user(ids["lead"]))
        assert edited["place"] == "Sanganer, Jaipur"
        assert edited["version"] == loaded + 1

    _world(monkeypatch, body)


def test_two_edits_of_the_same_field_from_one_version_refuse_the_second_with_the_field_diff(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    from app.questionnaire import schemas as S, sessions

    async def body(client, ids, session, create) -> None:
        loaded = session["version"]
        lead = _user(ids["lead"])
        await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, place="Sanganer, Jaipur"), lead)
        with pytest.raises(HTTPException) as stale:
            await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, place="Kaladera, Jaipur"), lead)
        assert stale.value.detail["code"] == "STALE_VERSION"
        assert stale.value.detail["diff"] == {"place": {"current": "Sanganer, Jaipur", "yours": "Kaladera, Jaipur"}}
        assert stale.value.detail["current"]["place"] == "Sanganer, Jaipur"

    _world(monkeypatch, body)


def test_a_stale_edit_of_another_field_is_refused_only_on_the_field_it_changes(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    from app.questionnaire import schemas as S, sessions

    async def body(client, ids, session, create) -> None:
        loaded = session["version"]
        lead = _user(ids["lead"])
        await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, note="Second visit"), lead)
        with pytest.raises(HTTPException) as stale:
            await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, place="Kaladera, Jaipur"), lead)
        assert stale.value.detail["code"] == "STALE_VERSION"
        assert stale.value.detail["diff"] == {"place": {"current": "Bagru, Jaipur", "yours": "Kaladera, Jaipur"}}

    _world(monkeypatch, body)


def test_a_stale_edit_whose_every_field_already_matches_is_applied(monkeypatch: pytest.MonkeyPatch, v2_on) -> None:
    from app.questionnaire import schemas as S, sessions

    async def body(client, ids, session, create) -> None:
        loaded = session["version"]
        lead = _user(ids["lead"])
        first = await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, place="Sanganer, Jaipur"), lead)
        again = await sessions.patch_session(session["id"], S.SessionPatch(version=loaded, place="Sanganer, Jaipur", note="First visit"), lead)
        assert again["place"] == "Sanganer, Jaipur" and again["note"] == "First visit"
        assert again["version"] == first["version"] + 1

    _world(monkeypatch, body)
