"""Authority over an assignment belongs to the admin who handed it out — on EVERY route, not one.

``DELETE /tasks/batch/{batch_id}`` has always enforced that: it refuses unless the caller is the
master admin or created every row in the batch, and its docstring says why — "otherwise one admin
could quietly delete another's assignments". ``DELETE /tasks/{task_id}`` and ``PATCH /tasks/{task_id}``,
over the SAME rows, tested ``task.createdById != current_user.id and not is_admin(current_user)``, a
comparison every admin short-circuits. So the batch rule was two calls away from meaningless:
``GET /tasks?view=all&batchId=…`` is admin-readable and hands over every member id, and N single
deletes finish what one batch delete had just refused, with a 204 each. ``AssignedTask`` rows are hard
deleted, so the assignees' to-do lists simply empty and nothing anywhere records that they existed.
``PATCH`` had the same shape and was worse in kind: a non-creating admin could REASSIGN another
admin's task to somebody else, or set it CANCELLED, which leaves a row that looks decided.

THE TESTS ARE ORDERED AS THE BYPASS IS PERFORMED, because that is the only way to show that this is
one defect and not two: the batch refusal (which always worked), then the same rows deleted one at a
time (which is what the fix closes). A test that only asserted the single delete's 403 would pass
against a build where somebody had "simplified" the batch guard away.

The permissive half of the rule is asserted just as hard. Every one of these routes must stay OPEN to
the creator and to the master admin, and the assignee must keep the narrow arm they have always had
(status and progressCount), or this becomes a fix that closes a MINOR audit finding by breaking the
feature. ``test_the_assignee_can_still_report_progress`` is the guard on that.

THE JOURNAL LINE IS ASSERTED ON THE PERMITTED DELETES, not the refused ones, and that is where the
remaining half of the audit finding lives: ``AssignedTask`` rows are hard deleted, so once the caller
is allowed through there is nothing left anywhere that says the assignment existed or who withdrew
it. ``test_the_creator_can_still_delete_their_own_task`` and
``test_the_master_admin_can_still_delete_a_batch`` therefore check the record as well as the 204 —
including that it is emitted at WARNING, because the API process configures no root handler and an
INFO record would vanish in production while still passing a test that captured it.

Needs Postgres — the whole point is which rows survive a request — so the module skips itself when
``DATABASE_URL`` does not point at a local database, exactly as ``test_task_option_pickers`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import logging
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


STAMP = uuid.uuid4().hex[:8]

#: One task per test that mutates, plus the three that share a batch. Each test edits or deletes the
#: row it is handed, so a shared one would make the module order-dependent — a suite that passes
#: alone and fails in CI.
SOLO_CASES: tuple[str, ...] = ("stranger", "creator", "master", "patch", "cancel", "assignee")


@pytest.fixture(scope="module")
async def world():
    """Two admins, a master admin, a researcher to be assigned the work, and the tasks.

    Rows are created before the app starts, for the reason ``test_task_option_pickers`` records: the
    Prisma client is shared with the running app and bound to the TestClient's event loop, and
    touching it from a test's own loop is the kind of cross-loop use that fails intermittently
    rather than honestly.
    """
    from fastapi.testclient import TestClient

    from app.main import app

    await db.connect()
    try:
        author = await db.user.create(data={
            "email": f"auth-author-{STAMP}@example.org",
            "name": f"Author {STAMP}",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        # THE OTHER ADMIN. Not a lesser account — an equal, which is exactly why the old guard let
        # them through: ``is_admin`` is true for them and the creator comparison was never reached.
        stranger = await db.user.create(data={
            "email": f"auth-stranger-{STAMP}@example.org",
            "name": f"Stranger {STAMP}",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        master = await db.user.create(data={
            "email": f"auth-master-{STAMP}@example.org",
            "name": f"Master {STAMP}",
            "role": "MASTER_ADMIN",
            "passwordHash": hash_password("unused"),
        })
        worker = await db.user.create(data={
            "email": f"auth-worker-{STAMP}@example.org",
            "name": f"Worker {STAMP}",
            "role": "RESEARCHER",
            "passwordHash": hash_password("unused"),
        })

        solo: dict[str, str] = {}
        for case in SOLO_CASES:
            row = await db.assignedtask.create(data={
                "title": f"Task {case} {STAMP}",
                "recordTypes": ["product"],
                "assigneeId": worker.id,
                "createdById": author.id,
            })
            solo[case] = row.id

        batch_id = f"batch-{STAMP}"
        batch: list[str] = []
        for index in range(3):
            row = await db.assignedtask.create(data={
                "title": f"Batch task {index} {STAMP}",
                "recordTypes": ["product"],
                "assigneeId": worker.id,
                "createdById": author.id,
                "batchId": batch_id,
            })
            batch.append(row.id)
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "tokens": {
                "author": create_access_token(subject=author.id),
                "stranger": create_access_token(subject=stranger.id),
                "master": create_access_token(subject=master.id),
                "worker": create_access_token(subject=worker.id),
            },
            "solo": solo,
            "batchId": batch_id,
            "batch": batch,
            "worker": worker.id,
            "stranger": stranger.id,
        }


def _as(world, who: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {world['tokens'][who]}"}


def _exists(world, task_id: str) -> bool:
    """Is the row still there? Asked as the MASTER ADMIN through ``GET /tasks/{id}``, which 404s on a
    row that is gone and 200s on one that is not.

    Deliberately not asked of ``db`` directly: the Prisma client is a module-level singleton SHARED
    with the running app, so a test that connects and disconnects it around its own query drops the
    connection the TestClient is using and turns every later request in the module into a failure
    that looks nothing like the thing being tested.
    """
    response = world["client"].get(f"/api/tasks/{task_id}", headers=_as(world, "master"))
    assert response.status_code in {200, 404}, response.text
    return response.status_code == 200


# --------------------------------------------------------------------------------------
# 1. The bypass, performed in order
# --------------------------------------------------------------------------------------


async def test_the_batch_route_refuses_another_admins_batch(world):
    """The half that always worked, asserted so that the next two tests mean something."""
    response = world["client"].delete(
        f"/api/tasks/batch/{world['batchId']}", headers=_as(world, "stranger")
    )
    assert response.status_code == 403, response.text
    assert "created this batch" in response.json()["detail"]


async def test_the_same_rows_cannot_be_deleted_one_at_a_time(world):
    """THE DEFECT IN ONE REQUEST. The batch was refused a line above; every member id is readable
    from ``GET /tasks?view=all&batchId=…`` by the same admin, and each single delete used to answer
    204. The batch rule was a ``for`` loop away from meaningless."""
    listed = world["client"].get(
        "/api/tasks", params={"view": "all", "batchId": world["batchId"]}, headers=_as(world, "stranger")
    )
    assert listed.status_code == 200, listed.text
    member_ids = {item["id"] for item in listed.json()["items"]}
    assert member_ids == set(world["batch"]), "the enumeration path this bypass needs still works"

    for task_id in world["batch"]:
        response = world["client"].delete(f"/api/tasks/{task_id}", headers=_as(world, "stranger"))
        assert response.status_code == 403, response.text
        assert "created this task" in response.json()["detail"]
        assert _exists(world, task_id), "the row must still be there, not merely reported as kept"


async def test_a_stranger_admin_cannot_delete_a_single_task(world):
    response = world["client"].delete(
        f"/api/tasks/{world['solo']['stranger']}", headers=_as(world, "stranger")
    )
    assert response.status_code == 403, response.text
    assert _exists(world, world["solo"]["stranger"])


# --------------------------------------------------------------------------------------
# 2. PATCH is the same rule, and the arms that matter are reassignment and CANCELLED
# --------------------------------------------------------------------------------------


async def test_a_stranger_admin_cannot_reassign_another_admins_task(world):
    """Worse in kind than the delete: the work moves to somebody else's list and the row still looks
    like the author's assignment."""
    response = world["client"].patch(
        f"/api/tasks/{world['solo']['patch']}",
        json={"assigneeId": world["stranger"]},
        headers=_as(world, "stranger"),
    )
    assert response.status_code == 403, response.text
    assert "created this task" in response.json()["detail"]


async def test_a_stranger_admin_cannot_cancel_another_admins_task(world):
    response = world["client"].patch(
        f"/api/tasks/{world['solo']['cancel']}",
        json={"status": "CANCELLED"},
        headers=_as(world, "stranger"),
    )
    assert response.status_code == 403, response.text


# --------------------------------------------------------------------------------------
# 3. The permissive half — the fix must not break the feature
# --------------------------------------------------------------------------------------


async def test_the_creator_can_still_delete_their_own_task(world, caplog):
    with caplog.at_level(logging.WARNING, logger="app.api.routes.tasks"):
        response = world["client"].delete(
            f"/api/tasks/{world['solo']['creator']}", headers=_as(world, "author")
        )
    assert response.status_code == 204, response.text
    assert not _exists(world, world["solo"]["creator"])
    # THE ROW IS HARD DELETED — no ``deletedAt``, no tombstone, no audit table — so this journal line
    # is the ONLY thing that survives to say the assignment ever existed or who withdrew it. The
    # authority rule above decides who may delete; it does nothing about reconstructing the deletion
    # afterwards, which is the other half the audit finding asked for. Asserted at WARNING on purpose:
    # under uvicorn an app logger has no handler and an INFO record would be dropped in production
    # while still passing a test that captured it, which is the failure this assertion has to exclude.
    audit = [r for r in caplog.records if r.getMessage().startswith("AUDIT task withdrawal")]
    assert len(audit) == 1, caplog.text
    assert audit[0].levelno == logging.WARNING
    assert world["solo"]["creator"] in audit[0].getMessage()
    assert world["worker"] in audit[0].getMessage(), "the assignee whose list just emptied is named"


async def test_the_master_admin_can_still_delete_anybodys_task(world):
    """The account that has to be able to unstick anything. Removing this override would turn a
    departed admin's tasks into rows nobody on the deployment can withdraw."""
    response = world["client"].delete(
        f"/api/tasks/{world['solo']['master']}", headers=_as(world, "master")
    )
    assert response.status_code == 204, response.text
    assert not _exists(world, world["solo"]["master"])


async def test_the_assignee_can_still_report_progress(world):
    """The narrow arm the assignee has always had. It is reached through the ELSE of the authority
    test, so tightening that test is exactly how it would have been lost."""
    response = world["client"].patch(
        f"/api/tasks/{world['solo']['assignee']}",
        json={"status": "IN_PROGRESS", "progressCount": 2},
        headers=_as(world, "worker"),
    )
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "IN_PROGRESS"
    assert response.json()["progressCount"] == 2


async def test_the_master_admin_can_still_delete_a_batch(world, caplog):
    with caplog.at_level(logging.WARNING, logger="app.api.routes.tasks"):
        response = world["client"].delete(
            f"/api/tasks/batch/{world['batchId']}", headers=_as(world, "master")
        )
    assert response.status_code == 204, response.text
    for task_id in world["batch"]:
        assert not _exists(world, task_id)
    # ONE line for the whole batch, carrying the count and every assignee — not N lines and not a
    # bare "a batch was deleted". A withdrawal that emptied three people's lists has to be legible as
    # that from the journal alone, because there is no row left to join back to.
    audit = [r for r in caplog.records if r.getMessage().startswith("AUDIT task withdrawal")]
    assert len(audit) == 1, caplog.text
    message = audit[0].getMessage()
    assert world["batchId"] in message
    assert f"count={len(world['batch'])}" in message
