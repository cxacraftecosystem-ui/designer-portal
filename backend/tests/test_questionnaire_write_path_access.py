import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in (
            ("lead", "DESIGNER", "Workshop Lead Designer"),
            ("outsider", "DESIGNER", "Unrelated Designer"),
            ("colleague", "DESIGNER", "Co-designer To Be Granted"),
            ("admin", "ADMIN", "Questionnaire Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qwrite-{slug}-{stamp}@example.org",
                    "name": name,
                    "role": role,
                }
            )
        for slug in ("lead", "outsider", "colleague"):
            await db.designerroster.create(
                data={
                    "email": f"qwrite-{slug}-{stamp}@example.org",
                    "fullName": people[slug].name,
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


@pytest.fixture
def client(env):
    return env["client"]


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


ONE_QUESTION = [
    {"code": "A", "title": "About the loom", "questions": [{"prompt": "How many looms?"}]}
]


def _workshop(client, env, title: str, slug: str = "lead") -> str:
    response = client.post(
        "/api/design-workshops", json={"title": title}, headers=_as(env, "admin")
    )
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [env["people"][slug].id]},
        headers=_as(env, "admin"),
    )
    assert granted.status_code == 200, granted.text
    return workshop_id


def _questionnaire(client, env, title: str, *, workshop_id: str | None, slug: str = "lead") -> dict:
    body: dict[str, Any] = {"title": title, "sections": ONE_QUESTION}
    if workshop_id is not None:
        body["designWorkshopId"] = workshop_id
    response = client.post("/api/questionnaires", json=body, headers=_as(env, slug))
    assert response.status_code == 201, response.text
    form = response.json()
    assert form.get("designWorkshopId") == workshop_id
    return form


def _question_id(form: dict) -> str:
    return form["sections"][0]["questions"][0]["id"]


def _entries(client, env, questionnaire_id: str, slug: str = "lead") -> list[dict]:
    response = client.get(f"/api/questionnaires/{questionnaire_id}", headers=_as(env, slug))
    assert response.status_code == 200, response.text
    return response.json()["entries"]



def test_a_stranger_cannot_start_a_sitting_on_a_form_attached_to_another_workshop(client, env):
    workshop_id = _workshop(client, env, f"Lead's workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Attached form {env['stamp']}", workshop_id=workshop_id
    )

    assert (
        client.get(
            f"/api/design-workshops/{workshop_id}", headers=_as(env, "outsider")
        ).status_code
        == 404
    )

    response = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": f"Fabricated respondent {env['stamp']}"},
        headers=_as(env, "outsider"),
    )

    assert response.status_code == 404, response.text
    assert _entries(client, env, form["id"]) == [], "a sitting survived the refusal"


def test_a_stranger_cannot_record_answers_into_a_sitting_of_an_attached_form(client, env):
    workshop_id = _workshop(client, env, f"Answers workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Answerable form {env['stamp']}", workshop_id=workshop_id
    )
    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Ramesh"},
        headers=_as(env, "lead"),
    )
    assert entry.status_code == 201, entry.text
    entry_id = entry.json()["id"]

    response = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Ninety"}]},
        headers=_as(env, "outsider"),
    )

    assert response.status_code == 404, response.text
    sittings = _entries(client, env, form["id"])
    assert len(sittings) == 1
    assert sittings[0]["answers"] == [], "an answer survived the refusal"


def test_nothing_a_stranger_sends_reaches_the_workshops_report(client, env):
    workshop_id = _workshop(client, env, f"Report workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Report form {env['stamp']}", workshop_id=workshop_id
    )
    marker = f"Injected respondent {env['stamp']}"

    refused = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": marker, "title": marker},
        headers=_as(env, "outsider"),
    )
    assert refused.status_code == 404, refused.text

    preview = client.get(
        f"/api/design-workshops/{workshop_id}/report/preview", headers=_as(env, "lead")
    )
    assert preview.status_code == 200, preview.text
    assert marker not in preview.text


def test_removing_the_write_gate_brings_the_injection_straight_back(client, env, monkeypatch):
    import app.api.routes.questionnaire_forms as route

    async def _no_gate(record, user):  # noqa: ANN001 - mirrors the real signature
        return None

    monkeypatch.setattr(route, "_require_recordable_questionnaire", _no_gate)

    workshop_id = _workshop(client, env, f"Neutralised workshop {env['stamp']}")
    form = _questionnaire(
        client, env, f"Neutralised form {env['stamp']}", workshop_id=workshop_id
    )

    response = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Straight back in"},
        headers=_as(env, "outsider"),
    )

    assert response.status_code == 201, "the gate is not what is answering 404 above"
    assert [e["respondentName"] for e in _entries(client, env, form["id"])] == ["Straight back in"]



def test_the_owner_still_records_sittings_and_answers_on_their_own_attached_form(client, env):
    workshop_id = _workshop(client, env, f"Own workshop {env['stamp']}")
    form = _questionnaire(client, env, f"Own form {env['stamp']}", workshop_id=workshop_id)

    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Sita Devi"},
        headers=_as(env, "lead"),
    )
    assert entry.status_code == 201, entry.text

    answers = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Twelve"}]},
        headers=_as(env, "lead"),
    )
    assert answers.status_code == 200, answers.text
    assert answers.json()["saved"] == {"created": 1, "updated": 0, "unchanged": 0, "rejected": []}


def test_any_designer_still_answers_an_unattached_form_handed_to_them(client, env):
    form = _questionnaire(client, env, f"Unattached form {env['stamp']}", workshop_id=None)

    entry = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "A respondent of the colleague's"},
        headers=_as(env, "outsider"),
    )
    assert entry.status_code == 201, entry.text

    answers = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Four"}]},
        headers=_as(env, "outsider"),
    )
    assert answers.status_code == 200, answers.text
    assert answers.json()["saved"] == {"created": 1, "updated": 0, "unchanged": 0, "rejected": []}


def test_the_gate_is_the_grant_and_revoking_it_stops_the_writes_too(client, env):
    workshop_id = _workshop(client, env, f"Granted workshop {env['stamp']}")
    form = _questionnaire(client, env, f"Granted form {env['stamp']}", workshop_id=workshop_id)

    before = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Too early"},
        headers=_as(env, "colleague"),
    )
    assert before.status_code == 404, before.text

    grant = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [env["people"]["colleague"].id]},
        headers=_as(env, "admin"),
    )
    assert grant.status_code == 200, grant.text

    allowed = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Recorded by the co-designer"},
        headers=_as(env, "colleague"),
    )
    assert allowed.status_code == 201, allowed.text
    entry_id = allowed.json()["id"]

    answered = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Nine"}]},
        headers=_as(env, "colleague"),
    )
    assert answered.status_code == 200, answered.text

    revoked = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": []},
        headers=_as(env, "admin"),
    )
    assert revoked.status_code == 200, revoked.text

    after = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "After the grant was taken away"},
        headers=_as(env, "colleague"),
    )
    assert after.status_code == 404, after.text
    still = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": _question_id(form), "answerText": "Ninety"}]},
        headers=_as(env, "colleague"),
    )
    assert still.status_code == 404, still.text

    sittings = _entries(client, env, form["id"])
    assert [e["respondentName"] for e in sittings] == ["Recorded by the co-designer"]
    assert [a["answerText"] for a in sittings[0]["answers"]] == ["Nine"]
