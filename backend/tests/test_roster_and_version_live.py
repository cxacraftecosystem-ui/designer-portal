"""The 2026-10-17 limitations, against Postgres: the version claim, roster names, sign-in times.

``test_interview_version`` and ``test_roster_names_and_sign_ins`` pin the same behaviour with fake
delegates, which answer questions of ORDER directly. These answer the other question — does it hold
on the real database:

* two PATCHes carrying the same ``expectedUpdatedAt``: the first lands, the second is the 409
  ``record_changed``, and a PATCH carrying the version the first one ANSWERED with lands (so the exact
  comparison does not refuse a client's own version);
* the access and designer roster lists, and a single-row write, carry ``addedByName`` /
  ``decidedByName``;
* a sign-in moves ``lastLoginAt`` every time and ``firstLoginAt`` once.

Skips itself without a LOCAL ``DATABASE_URL``, as ``test_questionnaire_artisan_set_scope`` does.
"""

import asyncio
import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.anyio,
    pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database; refuses a remote DATABASE_URL"),
]

STAMP = uuid.uuid4().hex[:8]

LOCATION = {
    "latitude": 22.3145,
    "longitude": 87.3101,
    "state": "Rajasthan",
    "district": "Jaipur",
    "village": "Bagru",
}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    """An admin who added and decided one access row and empanelled one designer, and the admin's
    sign-in times read after two sign-ins. Rows are written on this module's own connection BEFORE
    the TestClient opens, for the cross-loop reason ``test_questionnaire_artisan_set_scope`` gives."""
    from fastapi.testclient import TestClient

    from app.main import app
    from app.services.sign_in_stamps import stamp_sign_in

    rows: dict[str, Any] = {}
    await db.connect()
    try:
        admin = await db.user.create(
            data={
                "email": f"live-admin-{STAMP}@example.org",
                "name": f"Meera {STAMP}",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
        rows["admin"] = admin
        rows["access"] = await db.accessroster.create(
            data={
                "email": f"live-access-{STAMP}@example.org",
                "status": "ACTIVE",
                "addedById": admin.id,
                "decidedById": admin.id,
            }
        )
        rows["designer"] = await db.designerroster.create(
            data={"email": f"live-designer-{STAMP}@example.org", "addedById": admin.id}
        )

        await stamp_sign_in(admin)
        first = await db.user.find_unique(where={"id": admin.id})
        await asyncio.sleep(0.01)
        await stamp_sign_in(first)
        second = await db.user.find_unique(where={"id": admin.id})
        rows["signIns"] = (first, second)
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        rows["client"] = client
        yield rows


def _auth(env) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['admin'].id)}"}


async def test_a_version_claimed_once_cannot_be_claimed_again(env):
    client = env["client"]
    created = client.post(
        "/api/questionnaire/interviews",
        json={"title": f"Live version {STAMP}", "location": dict(LOCATION)},
        headers=_auth(env),
    )
    assert created.status_code == 201, created.text
    interview = created.json()
    version = interview["updatedAt"]
    url = f"/api/questionnaire/interviews/{interview['id']}"

    first = client.patch(url, json={"notes": "first", "expectedUpdatedAt": version}, headers=_auth(env))
    assert first.status_code == 200, first.text

    second = client.patch(url, json={"notes": "second", "expectedUpdatedAt": version}, headers=_auth(env))
    assert second.status_code == 409, second.text
    assert second.json()["detail"]["code"] == "record_changed"

    # The version the first PATCH answered with is the stored one, to the millisecond.
    third = client.patch(
        url, json={"notes": "third", "expectedUpdatedAt": first.json()["updatedAt"]}, headers=_auth(env)
    )
    assert third.status_code == 200, third.text
    assert third.json()["notes"] == "third"


async def test_the_access_roster_names_who_added_and_who_decided(env):
    listed = env["client"].get(
        "/api/access/roster", params={"search": f"live-access-{STAMP}"}, headers=_auth(env)
    )
    assert listed.status_code == 200, listed.text
    row = next(r for r in listed.json()["items"] if r["id"] == env["access"].id)
    assert row["addedByName"] == f"Meera {STAMP}"
    assert row["decidedByName"] == f"Meera {STAMP}"

    edited = env["client"].patch(
        f"/api/access/roster/{env['access'].id}", json={"notes": "checked"}, headers=_auth(env)
    )
    assert edited.status_code == 200, edited.text
    assert edited.json()["addedByName"] == f"Meera {STAMP}"
    assert edited.json()["decidedByName"] == f"Meera {STAMP}"


async def test_the_designer_roster_names_who_empanelled(env):
    listed = env["client"].get(
        "/api/designers/roster", params={"search": f"live-designer-{STAMP}"}, headers=_auth(env)
    )
    assert listed.status_code == 200, listed.text
    row = next(r for r in listed.json()["items"] if r["id"] == env["designer"].id)
    assert row["addedByName"] == f"Meera {STAMP}"


async def test_a_sign_in_moves_the_last_time_and_keeps_the_first(env):
    first, second = env["signIns"]
    assert first.lastLoginAt is not None
    assert first.firstLoginAt == first.lastLoginAt
    assert second.firstLoginAt == first.firstLoginAt
    assert second.lastLoginAt > first.lastLoginAt
