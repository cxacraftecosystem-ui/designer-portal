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

ONE_QUESTION = [
    {"code": "A", "title": "About the loom", "questions": [{"prompt": "How many looms?"}]}
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
            ("owner", "DESIGNER", "Form Owner Designer"),
            ("stranger", "DESIGNER", "Another Designer"),
            ("ministry", "MINISTRY_ADMIN", "Ministry Admin Restorer"),
            ("admin", "ADMIN", "Questionnaire Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qsoft-{slug}-{stamp}@example.org",
                    "name": name,
                    "role": role,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {"client": client, "people": people}


@pytest.fixture
def client(env):
    return env["client"]


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


def _form_with_an_answer(client, env, title: str) -> tuple[dict, str]:
    created = client.post(
        "/api/questionnaires",
        json={"title": title, "sections": ONE_QUESTION},
        headers=_as(env, "owner"),
    )
    assert created.status_code == 201, created.text
    form = created.json()
    question_id = form["sections"][0]["questions"][0]["id"]
    started = client.post(
        f"/api/questionnaires/{form['id']}/entries",
        json={"respondentName": "Kamala Devi"},
        headers=_as(env, "owner"),
    )
    assert started.status_code == 201, started.text
    entry_id = started.json()["id"]
    answered = client.put(
        f"/api/questionnaires/{form['id']}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": question_id, "answerText": "Twelve"}]},
        headers=_as(env, "owner"),
    )
    assert answered.status_code == 200, answered.text
    return form, entry_id


def _listed_ids(client, env, slug: str, **params: Any) -> list[str]:
    response = client.get(
        "/api/questionnaires", params={"pageSize": 100, **params}, headers=_as(env, slug)
    )
    assert response.status_code == 200, response.text
    return [row["id"] for row in response.json()["items"]]


def test_the_author_deletes_lists_and_restores_their_own_form_and_nobody_else_does(client, env):
    form, entry_id = _form_with_an_answer(client, env, "The author deletes this one")
    form_id = form["id"]

    assert client.delete(f"/api/questionnaires/{form_id}", headers=_as(env, "stranger")).status_code == 403
    assert client.get(f"/api/questionnaires/{form_id}", headers=_as(env, "owner")).status_code == 200

    assert client.delete(f"/api/questionnaires/{form_id}", headers=_as(env, "owner")).status_code == 204
    assert form_id in _listed_ids(client, env, "owner", deletedOnly=True)
    assert form_id not in _listed_ids(client, env, "stranger", deletedOnly=True)
    assert form_id not in _listed_ids(client, env, "owner")

    assert client.post(f"/api/questionnaires/{form_id}/restore", headers=_as(env, "stranger")).status_code == 403
    restored = client.post(f"/api/questionnaires/{form_id}/restore", headers=_as(env, "owner"))
    assert restored.status_code == 200, restored.text
    assert form_id in _listed_ids(client, env, "owner")

    assert (
        client.delete(
            f"/api/questionnaires/{form_id}/entries/{entry_id}", headers=_as(env, "owner")
        ).status_code
        == 403
    )
    assert (
        client.get(
            f"/api/questionnaires/{form_id}/deleted-entries", headers=_as(env, "owner")
        ).status_code
        == 403
    )
    assert (
        client.post(
            f"/api/questionnaires/{form_id}/entries/{entry_id}/restore", headers=_as(env, "owner")
        ).status_code
        == 403
    )


def test_a_ministry_admin_deletes_a_form_softly_and_an_admin_restores_it(client, env):
    form, entry_id = _form_with_an_answer(client, env, "Soft deleted form")
    form_id = form["id"]

    deleted = client.delete(f"/api/questionnaires/{form_id}", headers=_as(env, "ministry"))
    assert deleted.status_code == 204, deleted.text

    assert (
        client.get(f"/api/questionnaires/{form_id}", headers=_as(env, "owner")).status_code == 404
    )
    assert (
        client.get(f"/api/questionnaires/{form_id}", headers=_as(env, "admin")).status_code == 404
    )
    assert form_id not in _listed_ids(client, env, "owner")
    assert form_id not in _listed_ids(client, env, "admin", activeOnly=False)
    options = client.get("/api/questionnaires/options", headers=_as(env, "admin"))
    assert form_id not in [row["id"] for row in options.json()]

    trash = client.get(
        "/api/questionnaires",
        params={"deletedOnly": True, "pageSize": 100},
        headers=_as(env, "admin"),
    )
    assert trash.status_code == 200, trash.text
    row = next(item for item in trash.json()["items"] if item["id"] == form_id)
    assert row["deletedAt"]
    assert row["deletedByName"] == "Ministry Admin Restorer"

    restored = client.post(f"/api/questionnaires/{form_id}/restore", headers=_as(env, "admin"))
    assert restored.status_code == 200, restored.text
    back = client.get(f"/api/questionnaires/{form_id}", headers=_as(env, "owner"))
    assert back.status_code == 200, back.text
    entries = back.json()["entries"]
    assert [e["id"] for e in entries] == [entry_id]
    assert [a["answerText"] for a in entries[0]["answers"]] == ["Twelve"]
    assert form_id in _listed_ids(client, env, "owner")

    assert (
        client.post(f"/api/questionnaires/{form_id}/restore", headers=_as(env, "admin")).status_code
        == 200
    )


def test_a_deleted_sitting_is_hidden_kept_and_restorable(client, env):
    form, entry_id = _form_with_an_answer(client, env, "Soft deleted sitting")
    form_id = form["id"]
    question_id = form["sections"][0]["questions"][0]["id"]

    deleted = client.delete(
        f"/api/questionnaires/{form_id}/entries/{entry_id}", headers=_as(env, "ministry")
    )
    assert deleted.status_code == 204, deleted.text

    read = client.get(f"/api/questionnaires/{form_id}", headers=_as(env, "owner")).json()
    assert read["entries"] == []
    assert read["sections"][0]["questions"][0]["hasAnswers"] is True
    refused = client.put(
        f"/api/questionnaires/{form_id}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": question_id, "answerText": "Thirteen"}]},
        headers=_as(env, "owner"),
    )
    assert refused.status_code == 404, refused.text

    trash = client.get(
        f"/api/questionnaires/{form_id}/deleted-entries", headers=_as(env, "ministry")
    )
    assert trash.status_code == 200, trash.text
    assert [(e["id"], e["respondentName"], e["deletedByName"]) for e in trash.json()] == [
        (entry_id, "Kamala Devi", "Ministry Admin Restorer")
    ]

    assert (
        client.post(
            f"/api/questionnaires/{form_id}/entries/{entry_id}/restore", headers=_as(env, "owner")
        ).status_code
        == 403
    )
    restored = client.post(
        f"/api/questionnaires/{form_id}/entries/{entry_id}/restore", headers=_as(env, "ministry")
    )
    assert restored.status_code == 200, restored.text
    entries = client.get(f"/api/questionnaires/{form_id}", headers=_as(env, "owner")).json()[
        "entries"
    ]
    assert [a["answerText"] for a in entries[0]["answers"]] == ["Twelve"]
    assert (
        client.get(
            f"/api/questionnaires/{form_id}/deleted-entries", headers=_as(env, "ministry")
        ).json()
        == []
    )
