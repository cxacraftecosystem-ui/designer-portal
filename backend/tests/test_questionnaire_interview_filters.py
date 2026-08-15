"""``GET /questionnaire/interviews`` — the list parameters the questionnaire screen filters on.

WHY A SERVER TEST FOR A BROWSER DEFECT. The workshop dropdown above the interviews table displayed a
workshop as the active filter and the table underneath was the whole repository: ``loadInterviews``
sent ``page``, ``pageSize``, ``artisanId`` and ``search``, and no workshop, ever — under a comment
asserting that artisan was "the only list param the interviews endpoint supports". It never was.
``list_interviews`` has declared ``workshopId`` and applied ``where["workshopId"]`` all along, which
is exactly what made the defect so quiet: the parameter existed, the column existed, and nothing on
either side of the wire said the two were not joined up.

The browser half is pinned in ``frontend/e2e/questionnaire-workshop-filter-unit.spec.ts``. This file
pins the half that browser test has to assume — that the parameter it now sends genuinely narrows —
so that deleting it here turns one suite red instead of silently restoring a filter control that
filters nothing. A superset is the nastiest shape of wrong answer this list can produce: nothing
looks deleted, so the reader takes the rows below the control as this workshop's interviews and acts
on rows that are not.

Also pinned: that the narrowing survives composition with the free-text search. ``where["OR"]`` is
assigned outright by the search branch, so a filter written to the same key would be silently
replaced — the failure mode ``list_interviews``'s own comment about AND-composition warns about.

Needs Postgres, because every assertion is about the rows a query returns. Skips itself when
``DATABASE_URL`` does not point at a local database, exactly as ``test_media_processing_jobs`` does.
"""

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
    """Two workshops, one interview at each, and a third attached to neither.

    The unattached one is the control: it is what the filter has to EXCLUDE, and a "filter" that
    returns everything would pass any test written only against the two attached ones.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
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
    """The parameter the screen never sent. Asserted with the search box carrying the module's stamp
    so that other rows in a shared database cannot make a superset look like a narrowing."""
    ids = _ids(env["client"], search=f"Sitting {STAMP}", workshopId=env["here"])
    assert ids == [env["mine"]]


def test_the_other_workshops_sittings_are_excluded_not_merely_reordered(env):
    ids = _ids(env["client"], search=f"Sitting {STAMP}", workshopId=env["there"])
    assert ids == [env["theirs"]]
    assert env["mine"] not in ids
    assert env["loose"] not in ids


def test_without_the_parameter_the_list_is_the_superset_it_always_was(env):
    """The premise, stated so the two tests above cannot pass vacuously: with no workshop on the
    wire the endpoint answers with everything, which is precisely what the dropdown was showing
    while claiming to have narrowed it."""
    ids = _ids(env["client"], search=f"Sitting {STAMP}")
    assert set(ids) == {env["mine"], env["theirs"], env["loose"]}


def test_the_workshop_filter_is_not_eaten_by_the_search_or_the_other_way_round(env):
    """The search branch assigns ``where["OR"]`` outright. A workshop clause written to the same key
    — the obvious way to add an "OR" filter — would be silently replaced by it, and the list would
    widen back to the repository with the control still lit."""
    ids = _ids(env["client"], search=f"{STAMP} here", workshopId=env["here"])
    assert ids == [env["mine"]]
    assert _ids(env["client"], search=f"{STAMP} there", workshopId=env["here"]) == []
