"""``GET /tasks/options`` — the three capped pickers the Assign work dialog is built from.

THE CAPS WERE NOT THE DEFECT. Being unable to reach past them, and not being told they had bitten,
was. The route read 500 users, 200 workshops and 500 artisans, had no ``search`` parameter of any
kind, and returned no truncation flag on any list; ``AssignmentBuilder`` builds every picker purely
from what arrived and filters it in the browser, so there was no second endpoint to search either. An
admin looking for a colleague whose name sorts late in the alphabet was shown the same nothing they
would be shown for a colleague who has no account. On this repository's own measured population
(3632 accounts, 731 artisans — ``docs/OPEN_FINDINGS.md``, 2026-08-13) two of the three caps are live
today, and that document records the identical failure being closed on the design-workshop viewer
picker — "hidden from you" vs "nobody matched" — five days before this was found in a different
endpoint.

The fourth thing pinned here is subtler and is the one a reviewer would let through: the rank filter
used to run in PYTHON over the already-capped 500 rows, so the window was drawn from every account in
the table and only then reduced to the assignable ones. The assignable list therefore came back
shorter than the cap by however many higher-ranked accounts happened to sort early — and, at the
extreme this file reproduces, EMPTY while assignable colleagues sat in the table.

WHY THE CAPS ARE MONKEYPATCHED DOWN INSTEAD OF FED 501 ACCOUNTS. The behaviour under test is what
happens AT the ceiling, and creating five hundred users to reach the real one would make this module
take minutes and leave that many rows behind on a shared database. The constants are read at request
time, so lowering them is the same code path with a smaller number in it. Every test also narrows to
its own ``stamp`` so that a database with other people's rows in it cannot change an answer.

Needs Postgres — every assertion is about the rows a query returns — so the module skips itself when
``DATABASE_URL`` does not point at a local database, exactly as ``test_media_processing_jobs`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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


# One stamp for the whole module: every row created here carries it, and every request searches for
# it, so nothing in a shared database can widen or narrow an answer.
STAMP = uuid.uuid4().hex[:8]


@pytest.fixture(scope="module")
async def env():
    """An admin, the accounts around them, two workshops and three artisans.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
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
        # SIX ACCOUNTS THAT SORT EARLY, and they are the whole point of the search test: with the cap
        # lowered to five, a list taken by name alone cannot contain the seventh no matter what the
        # caller types, so a request that finds it proves the term reached the WHERE and not the
        # result.
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
        # An ADMIN peer whose name sorts BEFORE the assignable researcher. An admin does not rank
        # below an admin, so they are not assignable — and under the old ordering they were read
        # first and thrown away afterwards, which is exactly how the assignable list came back empty.
        peer = await db.user.create(data={
            "email": f"opt-peer-{STAMP}@example.org",
            "name": f"Aaa {STAMP} Peer",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        # THREE ACCOUNTS SHARING ONE NAME. Not a contrivance: 204 accounts in this repository share
        # the name "Sync Test", and that is where the viewer picker's cut landed.
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


# --------------------------------------------------------------------------------------
# 1. The parameter that did not exist
# --------------------------------------------------------------------------------------


def test_a_colleague_past_the_cap_can_be_reached_by_name(client, env, monkeypatch):
    """THE DEFECT IN ONE REQUEST. Six accounts sort before this researcher and the cap is five, so
    the picker cannot contain her by any ordering — she is reachable only if the term is part of the
    query. Against the old route ``search`` was not a declared parameter at all, FastAPI discarded
    it, and the answer was the first five names of the alphabet."""
    monkeypatch.setattr(tasks_route, "TASK_OPTION_USER_LIMIT", 5)
    payload = _options(client, search=f"Zzz {STAMP} Late")
    assert [a["id"] for a in payload["assignees"]] == [env["late"]]
    assert payload["assigneesTruncated"] is False


def test_the_search_narrows_the_artisan_and_workshop_pickers_too(client, env):
    """The same parameter, on the two lists whose caps bite on the live corpus. An artisan whose name
    sorts late cannot be named in a task's artisan subset at all, and a batch cannot be attached to a
    workshop older than the 200 most recent, unless the term is on the wire."""
    payload = _options(client, search=f"Artisan {STAMP} 1")
    assert [a["id"] for a in payload["artisans"]] == [env["artisans"][1]]

    payload = _options(client, search=f"Workshop {STAMP} 0")
    assert [w["id"] for w in payload["workshops"]] == [env["workshops"][0]]


def test_the_workshop_search_also_matches_the_place(client, env):
    """An admin hunting for a workshop knows the village or the title and should not have to guess
    which one the picker indexes — the same argument ``eligible_viewers`` makes for name OR email."""
    payload = _options(client, search=f"Place {STAMP}")
    assert {w["id"] for w in payload["workshops"]} == set(env["workshops"])


# --------------------------------------------------------------------------------------
# 2. The cut, and saying so
# --------------------------------------------------------------------------------------


def test_a_cut_artisan_list_says_it_was_cut(client, env, monkeypatch):
    """``artisansTruncated`` did not exist, so a picker holding two of three artisans looked exactly
    like a picker holding all of them. That is what an admin was acting on."""
    monkeypatch.setattr(tasks_route, "TASK_OPTION_ARTISAN_LIMIT", 2)
    payload = _options(client, search=f"Artisan {STAMP}")
    assert len(payload["artisans"]) == 2
    assert payload["artisansTruncated"] is True


def test_a_list_exactly_the_size_of_the_cap_is_not_called_truncated(client, env, monkeypatch):
    """The flag is exact rather than guessed: one row more than the cap is read and trimmed, so a set
    of exactly the cap reports False honestly. A flag that cries truncation on a complete list trains
    the reader to ignore it, which costs the same as not having it."""
    monkeypatch.setattr(tasks_route, "TASK_OPTION_ARTISAN_LIMIT", 3)
    payload = _options(client, search=f"Artisan {STAMP}")
    assert len(payload["artisans"]) == 3
    assert payload["artisansTruncated"] is False


def test_a_cut_workshop_list_says_it_was_cut(client, env, monkeypatch):
    monkeypatch.setattr(tasks_route, "TASK_OPTION_WORKSHOP_LIMIT", 1)
    payload = _options(client, search=f"Place {STAMP}")
    assert len(payload["workshops"]) == 1
    assert payload["workshopsTruncated"] is True


# --------------------------------------------------------------------------------------
# 3. The cap applies to rows that are already assignable
# --------------------------------------------------------------------------------------


def test_a_higher_ranked_account_no_longer_eats_a_slot(client, env, monkeypatch):
    """THE HALF A REVIEWER WOULD LET THROUGH. The rank filter used to run in Python AFTER the take, so
    the window was drawn from every account in the table: with the cap at one, the one row read is the
    early-sorting ADMIN peer, the filter then drops them, and the dialog offers NOBODY while an
    assignable researcher sits in the table under the same search term.

    The peer must also still be absent — moving the filter into the query must not have widened it
    into "everyone", which would let an admin assign work to their own peers and superiors."""
    monkeypatch.setattr(tasks_route, "TASK_OPTION_USER_LIMIT", 1)
    payload = _options(client, search=f"Aaa {STAMP}")
    assert [a["id"] for a in payload["assignees"]] == [env["early"][0]]
    assert env["peer"] not in {a["id"] for a in payload["assignees"]}
    assert payload["assigneesTruncated"] is True


def test_the_caller_is_never_offered_themselves(client, env):
    payload = _options(client, search=f"Caller {STAMP}")
    assert payload["assignees"] == []


# --------------------------------------------------------------------------------------
# 4. The order is total
# --------------------------------------------------------------------------------------


def test_which_of_three_identically_named_accounts_survives_the_cut_is_not_a_coin_toss(
    client, env, monkeypatch
):
    """``name`` alone is not a unique sort key on this data, so without the ``id`` tiebreaker WHICH
    accounts fall inside the cut is Postgres's choice and can differ between two identical requests —
    turning "who is hidden from this picker" into something that changes on refresh, which no search
    term can be relied on to reach."""
    monkeypatch.setattr(tasks_route, "TASK_OPTION_USER_LIMIT", 2)
    first = _options(client, search=f"Twin {STAMP}")
    second = _options(client, search=f"Twin {STAMP}")
    ids = [a["id"] for a in first["assignees"]]
    assert ids == [a["id"] for a in second["assignees"]]
    assert ids == env["twins"][:2], "the tiebreaker is the id, ascending"
    assert first["assigneesTruncated"] is True
