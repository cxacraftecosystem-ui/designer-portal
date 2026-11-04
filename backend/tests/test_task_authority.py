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

SOLO_CASES: tuple[str, ...] = ("stranger", "creator", "master", "patch", "cancel", "assignee")


@pytest.fixture(scope="module")
async def world():
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
    response = world["client"].get(f"/api/tasks/{task_id}", headers=_as(world, "master"))
    assert response.status_code in {200, 404}, response.text
    return response.status_code == 200



async def test_nobody_below_the_content_admins_deletes_a_task_or_a_batch(world):
    batch = world["client"].delete(f"/api/tasks/batch/{world['batchId']}", headers=_as(world, "worker"))
    assert batch.status_code == 403, batch.text
    single = world["client"].delete(f"/api/tasks/{world['solo']['stranger']}", headers=_as(world, "worker"))
    assert single.status_code == 403, single.text
    assert _exists(world, world["solo"]["stranger"])
    for task_id in world["batch"]:
        assert _exists(world, task_id)


async def test_any_content_admin_withdraws_another_admins_task(world):
    response = world["client"].delete(
        f"/api/tasks/{world['solo']['stranger']}", headers=_as(world, "stranger")
    )
    assert response.status_code == 204, response.text
    assert not _exists(world, world["solo"]["stranger"])



async def test_a_stranger_admin_cannot_reassign_another_admins_task(world):
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



async def test_the_creator_can_still_delete_their_own_task(world, caplog):
    with caplog.at_level(logging.WARNING, logger="app.api.routes.tasks"):
        response = world["client"].delete(
            f"/api/tasks/{world['solo']['creator']}", headers=_as(world, "author")
        )
    assert response.status_code == 204, response.text
    assert not _exists(world, world["solo"]["creator"])
    audit = [r for r in caplog.records if r.getMessage().startswith("AUDIT task withdrawal")]
    assert len(audit) == 1, caplog.text
    assert audit[0].levelno == logging.WARNING
    assert world["solo"]["creator"] in audit[0].getMessage()
    assert world["worker"] in audit[0].getMessage(), "the assignee whose list just emptied is named"


async def test_the_master_admin_can_still_delete_anybodys_task(world):
    response = world["client"].delete(
        f"/api/tasks/{world['solo']['master']}", headers=_as(world, "master")
    )
    assert response.status_code == 204, response.text
    assert not _exists(world, world["solo"]["master"])


async def test_the_assignee_can_still_report_progress(world):
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
    audit = [r for r in caplog.records if r.getMessage().startswith("AUDIT task withdrawal")]
    assert len(audit) == 1, caplog.text
    message = audit[0].getMessage()
    assert world["batchId"] in message
    assert f"count={len(world['batch'])}" in message
