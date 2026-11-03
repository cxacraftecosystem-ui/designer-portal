import os
import uuid

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
            "email": f"rev-owner-{stamp}@example.org", "name": "Revision Owner",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        stranger = await db.user.create(data={
            "email": f"rev-stranger-{stamp}@example.org", "name": "Revision Stranger",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        product = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": "Printed cotton yardage", "createdById": owner.id,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "owner": {"Authorization": f"Bearer {create_access_token(subject=owner.id)}"},
            "stranger": {"Authorization": f"Bearer {create_access_token(subject=stranger.id)}"},
            "owner_id": owner.id,
            "stranger_id": stranger.id,
            "product_id": product.id,
        }


THREE_STEPS = [
    {"name": "Wash the cloth", "stepType": "SEQUENTIAL", "sortOrder": 1},
    {"name": "Print the border", "stepType": "SEQUENTIAL", "sortOrder": 2},
    {"name": "Sun-dry", "stepType": "SEQUENTIAL", "sortOrder": 3},
]


def _process(env, *, notes=None) -> dict:
    response = env["client"].post("/api/processes", json={
        "name": "Bagru printing sequence",
        "productId": env["product_id"],
        "notes": notes,
        "steps": THREE_STEPS,
    }, headers=env["owner"])
    assert response.status_code == 201, response.text
    return response.json()


def _resend(process: dict) -> list[dict]:
    return [
        {
            "id": step["id"],
            "name": step["name"],
            "stepType": step["stepType"],
            "sortOrder": step["sortOrder"],
            "notes": step["notes"],
        }
        for step in process["steps"]
    ]


def _revisions(env, process_id: str) -> list[dict]:
    response = env["client"].get(
        f"/api/data-access/revisions?recordType=process&recordId={process_id}",
        headers=env["owner"],
    )
    assert response.status_code == 200, response.text
    return response.json()



def test_a_refused_step_deletion_writes_no_revision_for_the_field_it_also_carried(env):
    process = _process(env, notes=None)

    response = env["client"].patch(f"/api/processes/{process['id']}", json={
        "notes": "This note must not survive the refusal.",
        "steps": _resend(process)[:-1],
    }, headers=env["stranger"])
    assert response.status_code == 403, response.text

    detail = env["client"].get(f"/api/processes/{process['id']}", headers=env["owner"]).json()
    assert detail["notes"] is None, "a refused request persisted the field it also carried"
    assert [s["name"] for s in detail["steps"]] == [s["name"] for s in THREE_STEPS]

    assert _revisions(env, process["id"]) == [], (
        "the refused request left an audit row claiming an edit that never happened"
    )


def test_a_refused_step_deletion_names_nobody_as_an_editor(env):
    process = _process(env, notes=None)

    refused = env["client"].patch(f"/api/processes/{process['id']}", json={
        "notes": "A note carried in alongside a step deletion the caller may not make.",
        "steps": [],
    }, headers=env["stranger"])
    assert refused.status_code == 403, refused.text
    assert "steps" in refused.text, (
        f"expected the relation guard to be what refused; got {refused.text}"
    )

    authors = {row["editedById"] for row in _revisions(env, process["id"])}
    assert env["stranger_id"] not in authors, "a refused caller is recorded as having edited"
    detail = env["client"].get(f"/api/processes/{process['id']}", headers=env["owner"]).json()
    assert [s["name"] for s in detail["steps"]] == [s["name"] for s in THREE_STEPS]


def test_a_refused_locked_field_edit_leaves_no_revision_either(env):
    process = _process(env, notes="The note the owner recorded.")

    refused = env["client"].patch(f"/api/processes/{process['id']}", json={
        "notes": "Overwritten by a contributor.",
    }, headers=env["stranger"])
    assert refused.status_code == 403, refused.text

    assert _revisions(env, process["id"]) == []
    detail = env["client"].get(f"/api/processes/{process['id']}", headers=env["owner"]).json()
    assert detail["notes"] == "The note the owner recorded."



def test_a_legitimate_field_edit_still_writes_exactly_one_revision_with_the_right_author(env):
    process = _process(env, notes=None)

    response = env["client"].patch(f"/api/processes/{process['id']}", json={
        "notes": "Observed again during the second sitting.",
        "steps": _resend(process),
    }, headers=env["owner"])
    assert response.status_code == 200, response.text

    rows = _revisions(env, process["id"])
    assert len(rows) == 1, f"expected exactly one revision, got {[r['changes'] for r in rows]}"
    assert set(rows[0]["changes"]) == {"notes"}
    assert rows[0]["changes"]["notes"]["new"] == "Observed again during the second sitting."
    assert rows[0]["editedById"] == env["owner_id"]


def test_deleting_a_step_still_writes_the_step_revision_that_names_what_was_there(env):
    process = _process(env, notes=None)
    kept = _resend(process)[:1]

    response = env["client"].patch(
        f"/api/processes/{process['id']}", json={"steps": kept}, headers=env["owner"]
    )
    assert response.status_code == 200, response.text

    steps_rows = [row for row in _revisions(env, process["id"]) if "steps" in row["changes"]]
    assert len(steps_rows) == 1, "the step audit row was lost"
    change = steps_rows[0]["changes"]["steps"]
    assert [s["name"] for s in change["old"]] == [s["name"] for s in THREE_STEPS]
    assert [s["name"] for s in change["new"]] == ["Wash the cloth"]
    assert {s["id"] for s in change["old"]} == {s["id"] for s in process["steps"]}
    assert steps_rows[0]["editedById"] == env["owner_id"]


def test_a_contributor_may_still_fill_an_empty_field_and_it_is_audited(env):
    process = _process(env, notes=None)

    response = env["client"].patch(f"/api/processes/{process['id']}", json={
        "notes": "Filled in by a colleague who did not create the record.",
        "steps": _resend(process),
    }, headers=env["stranger"])
    assert response.status_code == 200, response.text

    rows = _revisions(env, process["id"])
    assert len(rows) == 1, f"expected exactly one revision, got {[r['changes'] for r in rows]}"
    assert set(rows[0]["changes"]) == {"notes"}
    assert rows[0]["editedById"] == env["stranger_id"]
