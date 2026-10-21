"""Interview fixes from the 21 Oct 2026 audit (AUDIT_DR_2026-10-21.md), against a database.

* **H4** a second researcher adding to a shared interview re-sends the first one's answers word for
  word; that is no longer refused. Changing somebody else's answer is still refused, now with a code
  and the question ids, and the fold's scalar fill is rolled back with it.
* **H5** deleting an interview is a SOFT delete by a content admin: it disappears from every read,
  keeps its answers, frees its artisan set, and a content admin restores it (409 when another live
  interview took the set meanwhile).

Needs a LOCAL Postgres (``checks.yml``'s on-demand ``backend-db`` job provides one); skips otherwise.
"""

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

LOCATION = {
    "latitude": 26.8,
    "longitude": 75.6,
    "state": "Rajasthan",
    "district": "Jaipur",
    "village": "Bagru",
}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    rows: dict[str, Any] = {"stamp": stamp}
    await db.connect()
    try:
        people = {}
        for slug, role in (("first", "DESIGNER"), ("second", "DESIGNER"), ("admin", "ADMIN")):
            people[slug] = await db.user.create(
                data={
                    "email": f"qint-{slug}-{stamp}@example.org",
                    "name": f"Interview {slug} {stamp}",
                    "role": role,
                }
            )
        rows["people"] = people
        # Unparented global questions: ``QuestionnaireSection`` is unique on code and sortOrder and
        # shared with the seed, and nothing under test reads the section.
        rows["questions"] = [
            await db.questionnairequestion.create(
                data={
                    "sectionCode": f"LIVE-{stamp}",
                    "sectionTitle": "Live fixes",
                    "prompt": f"{prompt} ({stamp})",
                    "sortOrder": 9100 + index,
                }
            )
            for index, prompt in enumerate(("How many looms?", "Who taught you?"))
        ]
        rows["artisans"] = [
            await db.artisan.create(
                data={
                    "name": f"Artisan {tag} {stamp}",
                    "place": "Bagru",
                    "createdById": people["first"].id,
                }
            )
            for tag in ("A", "B")
        ]
    finally:
        await db.disconnect()
    with TestClient(app) as client:
        rows["client"] = client
        yield rows


def _as(env: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}


def _create(env, slug: str, artisan_index: int, answers: list[tuple[int, str]], **extra: Any):
    return env["client"].post(
        "/api/questionnaire/interviews",
        json={
            "title": f"Sitting {env['stamp']}",
            "location": dict(LOCATION),
            "artisanIds": [env["artisans"][artisan_index].id],
            "responses": [
                {"questionId": env["questions"][q].id, "answerText": text} for q, text in answers
            ],
            **extra,
        },
        headers=_as(env, slug),
    )


def _answers(body: dict[str, Any]) -> dict[str, str]:
    return {r["questionId"]: r["answerText"] for r in body.get("responses") or []}


# ── H4 ───────────────────────────────────────────────────────────────────────────────────────


def test_a_second_researcher_can_add_to_a_shared_interview_and_cannot_overwrite_it(env):
    q1, q2 = (q.id for q in env["questions"])
    first = _create(env, "first", 0, [(0, "Twelve")])
    assert first.status_code == 201, first.text
    interview_id = first.json()["id"]

    # The second researcher's client re-sends the first answer unchanged, and adds its own.
    added = _create(env, "second", 0, [(0, "Twelve"), (1, "My mother")])
    assert added.status_code == 201, added.text
    assert added.json()["id"] == interview_id
    assert _answers(added.json()) == {q1: "Twelve", q2: "My mother"}

    # Changing the first researcher's answer is refused, named, and takes the fold's fill with it.
    refused = _create(env, "second", 0, [(0, "Thirteen")], language="Hindi")
    assert refused.status_code == 403, refused.text
    detail = refused.json()["detail"]
    assert detail["code"] == "ANSWER_OWNED_BY_OTHER"
    assert detail["questionIds"] == [q1]
    assert detail["message"]
    stored = env["client"].get(
        f"/api/questionnaire/interviews/{interview_id}", headers=_as(env, "first")
    ).json()
    assert stored["language"] in (None, "")
    assert _answers(stored)[q1] == "Twelve"

    # The same rule on PATCH: an identical answer passes, a changed one is refused by code.
    same = env["client"].patch(
        f"/api/questionnaire/interviews/{interview_id}",
        json={"responses": [{"questionId": q1, "answerText": "Twelve"}]},
        headers=_as(env, "admin"),
    )
    assert same.status_code == 200, same.text


# ── H5 ───────────────────────────────────────────────────────────────────────────────────────


def test_an_interview_is_soft_deleted_by_an_admin_and_restored_whole(env):
    client = env["client"]
    q1 = env["questions"][0].id
    made = _create(env, "first", 1, [(0, "Eight")])
    assert made.status_code == 201, made.text
    old_id = made.json()["id"]
    path = f"/api/questionnaire/interviews/{old_id}"

    assert client.delete(path, headers=_as(env, "first")).status_code == 403
    assert client.delete(path, headers=_as(env, "admin")).status_code == 204
    assert client.get(path, headers=_as(env, "first")).status_code == 404
    listed = client.get(
        "/api/questionnaire/interviews", params={"pageSize": 100}, headers=_as(env, "admin")
    ).json()["items"]
    assert old_id not in {row["id"] for row in listed}

    assert (
        client.get(
            "/api/questionnaire/interviews", params={"deletedOnly": True}, headers=_as(env, "first")
        ).status_code
        == 403
    )
    trash = client.get(
        "/api/questionnaire/interviews",
        params={"deletedOnly": True, "pageSize": 100},
        headers=_as(env, "admin"),
    ).json()["items"]
    row = next(r for r in trash if r["id"] == old_id)
    assert row["deletedAt"] and row["deletedByName"] == env["people"]["admin"].name

    # The set is free: a new interview for the same artisan is a new row, not a fold into the trash.
    fresh = _create(env, "second", 1, [(0, "Nine")])
    assert fresh.status_code == 201, fresh.text
    assert fresh.json()["id"] != old_id

    restore = f"{path}/restore"
    assert client.post(restore, headers=_as(env, "first")).status_code == 403
    taken = client.post(restore, headers=_as(env, "admin"))
    assert taken.status_code == 409, taken.text
    assert taken.json()["detail"]["code"] == "INTERVIEW_SET_TAKEN"

    assert (
        client.delete(
            f"/api/questionnaire/interviews/{fresh.json()['id']}", headers=_as(env, "admin")
        ).status_code
        == 204
    )
    back = client.post(restore, headers=_as(env, "admin"))
    assert back.status_code == 200, back.text
    assert _answers(back.json())[q1] == "Eight"
    assert client.get(path, headers=_as(env, "first")).status_code == 200
