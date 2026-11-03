import os
import uuid
from datetime import UTC, datetime

import pytest

from app.api.routes import tasks as tasks_route
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


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


STAMP = uuid.uuid4().hex[:8]


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    await db.connect()
    try:
        caller = await db.user.create(data={
            "email": f"opt-caller-{STAMP}@example.org",
            "name": f"Caller {STAMP}",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        early = []
        for index in range(6):
            early.append(await db.user.create(data={
                "email": f"opt-early-{index}-{STAMP}@example.org",
                "name": f"Aaa {STAMP} Early {index}",
                "role": "RESEARCHER",
                "passwordHash": hash_password("unused"),
            }))
        late = await db.user.create(data={
            "email": f"opt-late-{STAMP}@example.org",
            "name": f"Zzz {STAMP} Late",
            "role": "RESEARCHER",
            "passwordHash": hash_password("unused"),
        })
        peer = await db.user.create(data={
            "email": f"opt-peer-{STAMP}@example.org",
            "name": f"Aaa {STAMP} Peer",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        twins = []
        for index in range(3):
            twins.append(await db.user.create(data={
                "email": f"opt-twin-{index}-{STAMP}@example.org",
                "name": f"Twin {STAMP}",
                "role": "RESEARCHER",
                "passwordHash": hash_password("unused"),
            }))

        workshops = []
        for index in range(2):
            workshops.append(await db.workshop.create(data={
                "title": f"Workshop {STAMP} {index}",
                "place": f"Place {STAMP}",
                "date": datetime(2026, 3, 1 + index, tzinfo=UTC),
                "createdById": caller.id,
            }))
        artisans = []
        for index in range(3):
            artisans.append(await db.artisan.create(data={
                "name": f"Artisan {STAMP} {index}",
                "place": f"Village {STAMP}",
                "createdById": caller.id,
            }))
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=caller.id)}"})
        yield {
            "client": client,
            "caller": caller.id,
            "early": [u.id for u in early],
            "late": late.id,
            "peer": peer.id,
            "twins": sorted(u.id for u in twins),
            "workshops": [w.id for w in workshops],
            "artisans": [a.id for a in artisans],
        }


@pytest.fixture
def client(env):
    return env["client"]


def _options(client, **params):
    response = client.get("/api/tasks/options", params=params)
    assert response.status_code == 200, response.text
    return response.json()



def test_a_colleague_past_the_cap_can_be_reached_by_name(client, env, monkeypatch):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_USER_LIMIT", 5)
    payload = _options(client, search=f"Zzz {STAMP} Late")
    assert [a["id"] for a in payload["assignees"]] == [env["late"]]
    assert payload["assigneesTruncated"] is False


def test_the_search_narrows_the_artisan_and_workshop_pickers_too(client, env):
    payload = _options(client, search=f"Artisan {STAMP} 1")
    assert [a["id"] for a in payload["artisans"]] == [env["artisans"][1]]

    payload = _options(client, search=f"Workshop {STAMP} 0")
    assert [w["id"] for w in payload["workshops"]] == [env["workshops"][0]]


def test_the_workshop_search_also_matches_the_place(client, env):
    payload = _options(client, search=f"Place {STAMP}")
    assert {w["id"] for w in payload["workshops"]} == set(env["workshops"])



def test_a_cut_artisan_list_says_it_was_cut(client, env, monkeypatch):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_ARTISAN_LIMIT", 2)
    payload = _options(client, search=f"Artisan {STAMP}")
    assert len(payload["artisans"]) == 2
    assert payload["artisansTruncated"] is True


def test_a_list_exactly_the_size_of_the_cap_is_not_called_truncated(client, env, monkeypatch):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_ARTISAN_LIMIT", 3)
    payload = _options(client, search=f"Artisan {STAMP}")
    assert len(payload["artisans"]) == 3
    assert payload["artisansTruncated"] is False


def test_a_cut_workshop_list_says_it_was_cut(client, env, monkeypatch):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_WORKSHOP_LIMIT", 1)
    payload = _options(client, search=f"Place {STAMP}")
    assert len(payload["workshops"]) == 1
    assert payload["workshopsTruncated"] is True



def test_a_higher_ranked_account_no_longer_eats_a_slot(client, env, monkeypatch):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_USER_LIMIT", 1)
    payload = _options(client, search=f"Aaa {STAMP}")
    assert [a["id"] for a in payload["assignees"]] == [env["early"][0]]
    assert env["peer"] not in {a["id"] for a in payload["assignees"]}
    assert payload["assigneesTruncated"] is True


def test_the_caller_is_never_offered_themselves(client, env):
    payload = _options(client, search=f"Caller {STAMP}")
    assert payload["assignees"] == []



def test_which_of_three_identically_named_accounts_survives_the_cut_is_not_a_coin_toss(
    client, env, monkeypatch
):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_USER_LIMIT", 2)
    first = _options(client, search=f"Twin {STAMP}")
    second = _options(client, search=f"Twin {STAMP}")
    ids = [a["id"] for a in first["assignees"]]
    assert ids == [a["id"] for a in second["assignees"]]
    assert ids == env["twins"][:2], "the tiebreaker is the id, ascending"
    assert first["assigneesTruncated"] is True
