"""What happens to a subset grant's scope rows when the grant is edited.

THE FAILURE THIS PINS. ``_upsert_grant`` used to rebuild the scope on EVERY call: ``delete_many``
every row, then one ``create`` per item, with no transaction around any of it. Two consequences,
both invisible from the owner's screen.

* A colleague pressing Download inside that window got a zip that silently omitted the records
  whose rows had not been re-inserted yet, presented as complete. A process that died inside the
  loop left the grant GRANTED at the tier the owner had just set while covering NO records at all.
* ``update_grant`` and ``decide_request`` re-send the grant's EXISTING items whenever the payload
  omits them, so a bare tier change, a revoke or a reinstate tore down and rebuilt an unchanged
  subset — twenty sequential round trips on a cross-region link to write nothing new.

The window itself cannot be observed from a test (it is a race), so what is pinned here is the
reconciliation that removed it: an unchanged scope writes nothing, a changed scope ends up exactly
as asked, and ``allData`` clears the subset rather than leaving rows behind to reappear the day
somebody widens the read.

Needs Postgres, and skips itself when ``DATABASE_URL`` does not point at a local database.
"""

import os
import uuid

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
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


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        owner = await db.user.create(data={
            "email": f"scope-owner-{stamp}@example.org", "name": "Scope Owner",
            "role": "RESEARCHER", "passwordHash": hash_password("unused"),
        })
        grantee = await db.user.create(data={
            "email": f"scope-grantee-{stamp}@example.org", "name": "Scope Grantee",
            "role": "RESEARCHER", "passwordHash": hash_password("unused"),
        })
        artisans = [
            await db.artisan.create(data={
                "name": f"Scope artisan {index} {stamp}", "place": "Barpali",
                "createdById": owner.id,
            })
            for index in range(3)
        ]
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        client.headers.update({"Authorization": f"Bearer {create_access_token(subject=owner.id)}"})
        yield {"client": client, "grantee": grantee.id, "artisans": [a.id for a in artisans]}


@pytest.fixture
def client(env):
    return env["client"]


def _items(env, count: int) -> list[dict[str, str]]:
    return [
        {"recordType": "artisan", "recordId": rid} for rid in env["artisans"][:count]
    ]


def _scope_ids(payload: dict) -> set[str]:
    return {item["recordId"] for item in payload["scopeItems"]}


@pytest.fixture
def grant(env, client):
    """A fresh DOWNLOAD grant over two of the owner's three artisans."""
    response = client.post("/api/data-access/grants", json={
        "granteeId": env["grantee"],
        "tier": "DOWNLOAD",
        "allData": False,
        "scopeItems": _items(env, 2),
    })
    assert response.status_code == 201, response.text
    payload = response.json()
    assert _scope_ids(payload) == set(env["artisans"][:2])
    return payload


def test_a_tier_only_edit_leaves_the_scope_rows_exactly_where_they_were(env, client, grant):
    """THE COMMON CASE, and the one that used to rebuild everything.

    ``update_grant`` re-sends the grant's existing items when the payload omits them, so this call
    reaches ``_upsert_grant`` with the full subset. Nothing about it changed, so nothing may be
    written — and the row ids proving that are the same ids, not merely the same count: a rebuilt
    scope would have new cuids and a new ``createdAt``.
    """
    before = {item["id"] for item in grant["scopeItems"]}

    response = client.patch(f"/api/data-access/grants/{grant['id']}", json={"tier": "COMMENT"})
    assert response.status_code == 200, response.text
    after = response.json()

    assert after["tier"] == "COMMENT"
    assert {item["id"] for item in after["scopeItems"]} == before, (
        "an unchanged subset was torn down and re-inserted"
    )


def test_a_revoke_and_a_reinstate_do_not_touch_the_scope(env, client, grant):
    """A revoked grant keeps its scope, or reinstating it would hand back a grant covering nothing."""
    before = {item["id"] for item in grant["scopeItems"]}

    revoked = client.patch(f"/api/data-access/grants/{grant['id']}", json={"status": "REVOKED"})
    assert revoked.status_code == 200, revoked.text
    assert {item["id"] for item in revoked.json()["scopeItems"]} == before

    back = client.patch(f"/api/data-access/grants/{grant['id']}", json={"status": "GRANTED"})
    assert back.status_code == 200, back.text
    assert {item["id"] for item in back.json()["scopeItems"]} == before


def test_a_changed_subset_ends_up_exactly_as_asked(env, client, grant):
    """One record dropped and one added, in a single edit."""
    response = client.patch(f"/api/data-access/grants/{grant['id']}", json={
        "allData": False,
        "scopeItems": [
            {"recordType": "artisan", "recordId": env["artisans"][1]},
            {"recordType": "artisan", "recordId": env["artisans"][2]},
        ],
    })
    assert response.status_code == 200, response.text
    assert _scope_ids(response.json()) == {env["artisans"][1], env["artisans"][2]}


def test_widening_to_all_data_clears_the_subset(env, client, grant):
    """Rows left behind would come back the day somebody narrowed the grant again — a scope the
    owner never re-chose, restored silently."""
    response = client.patch(f"/api/data-access/grants/{grant['id']}", json={"allData": True})
    assert response.status_code == 200, response.text
    assert response.json()["allData"] is True
    assert response.json()["scopeItems"] == []


def test_a_repeated_record_is_stored_once(env, client):
    """The table carries ``@@unique([grantId, recordType, recordId])``, so a payload naming the same
    record twice used to raise on the second ``create`` and 500 the request."""
    grantee = env["grantee"]
    duplicate = {"recordType": "artisan", "recordId": env["artisans"][0]}
    response = client.post("/api/data-access/grants", json={
        "granteeId": grantee,
        "tier": "DOWNLOAD",
        "allData": False,
        "scopeItems": [duplicate, duplicate],
    })
    assert response.status_code == 201, response.text
    assert len(response.json()["scopeItems"]) == 1
