import os
import uuid
from datetime import UTC, datetime

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

STAMP = uuid.uuid4().hex[:8]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    await db.connect()
    try:
        user = await db.user.create(data={
            "email": f"iv-filter-{STAMP}@example.org",
            "name": f"Interviewer {STAMP}",
            "role": "DESIGNER",
            "passwordHash": hash_password("unused"),
        })
        here = await db.workshop.create(data={
            "title": f"Bagru workshop {STAMP}",
            "place": f"Bagru {STAMP}",
            "date": datetime(2026, 4, 1, tzinfo=UTC),
            "createdById": user.id,
        })
        there = await db.workshop.create(data={
            "title": f"Sanganer workshop {STAMP}",
            "place": f"Sanganer {STAMP}",
            "date": datetime(2026, 4, 2, tzinfo=UTC),
            "createdById": user.id,
        })
        mine = await db.questionnaireinterview.create(data={
            "title": f"Sitting {STAMP} here",
            "createdById": user.id,
            "workshopId": here.id,
        })
        theirs = await db.questionnaireinterview.create(data={
            "title": f"Sitting {STAMP} there",
            "createdById": user.id,
            "workshopId": there.id,
        })
        loose = await db.questionnaireinterview.create(data={
            "title": f"Sitting {STAMP} nowhere",
            "createdById": user.id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=user.id)}"})
        yield {
            "client": client,
            "here": here.id,
            "there": there.id,
            "mine": mine.id,
            "theirs": theirs.id,
            "loose": loose.id,
        }


def _ids(client, **params) -> list[str]:
    response = client.get("/api/questionnaire/interviews", params={"pageSize": 100, **params})
    assert response.status_code == 200, response.text
    return [item["id"] for item in response.json()["items"]]


def test_the_workshop_parameter_narrows_the_list(env):
    ids = _ids(env["client"], search=f"Sitting {STAMP}", workshopId=env["here"])
    assert ids == [env["mine"]]


def test_the_other_workshops_sittings_are_excluded_not_merely_reordered(env):
    ids = _ids(env["client"], search=f"Sitting {STAMP}", workshopId=env["there"])
    assert ids == [env["theirs"]]
    assert env["mine"] not in ids
    assert env["loose"] not in ids


def test_without_the_parameter_the_list_is_the_superset_it_always_was(env):
    ids = _ids(env["client"], search=f"Sitting {STAMP}")
    assert set(ids) == {env["mine"], env["theirs"], env["loose"]}


def test_the_workshop_filter_is_not_eaten_by_the_search_or_the_other_way_round(env):
    ids = _ids(env["client"], search=f"{STAMP} here", workshopId=env["here"])
    assert ids == [env["mine"]]
    assert _ids(env["client"], search=f"{STAMP} there", workshopId=env["here"]) == []
