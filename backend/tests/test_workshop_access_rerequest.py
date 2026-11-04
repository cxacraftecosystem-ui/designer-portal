import os
import uuid
from datetime import UTC, datetime, timedelta

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

DENIED_AT = datetime(2026, 3, 3, 9, 30, tzinfo=UTC)
WAITING_SINCE = datetime(2026, 3, 4, 9, 30, tzinfo=UTC)
DECISION_NOTE = "Embargoed until the ministry review closes."


@pytest.fixture(scope="module")
async def world():
    from fastapi.testclient import TestClient

    from app.main import app

    await db.connect()
    try:
        admin = await db.user.create(data={
            "email": f"rr-admin-{STAMP}@example.org",
            "name": f"Decider {STAMP}",
            "role": "ADMIN",
            "passwordHash": hash_password("unused"),
        })
        asker = await db.user.create(data={
            "email": f"rr-asker-{STAMP}@example.org",
            "name": f"Asker {STAMP}",
            "role": "RESEARCHER",
            "passwordHash": hash_password("unused"),
        })
        bystander = await db.user.create(data={
            "email": f"rr-bystander-{STAMP}@example.org",
            "name": f"Bystander {STAMP}",
            "role": "RESEARCHER",
            "passwordHash": hash_password("unused"),
        })
        workshop = await db.workshop.create(data={
            "title": f"Workshop {STAMP}",
            "place": f"Place {STAMP}",
            "date": datetime(2026, 2, 1, tzinfo=UTC),
            "createdById": admin.id,
        })
        denied = await db.workshopassignment.create(data={
            "workshopId": workshop.id,
            "userId": asker.id,
            "accessLevel": "CONTRIBUTE",
            "status": "DENIED",
            "requestedById": asker.id,
            "requestNote": "Need to add records for the March season.",
            "decidedById": admin.id,
            "decidedAt": DENIED_AT,
            "decisionNote": DECISION_NOTE,
            "createdAt": DENIED_AT - timedelta(days=1),
        })
        waiting = await db.workshopassignment.create(data={
            "workshopId": workshop.id,
            "userId": bystander.id,
            "accessLevel": "VIEW",
            "status": "PENDING",
            "requestedById": bystander.id,
            "createdAt": WAITING_SINCE,
        })
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "admin": {"Authorization": f"Bearer {create_access_token(subject=admin.id)}"},
            "asker": {"Authorization": f"Bearer {create_access_token(subject=asker.id)}"},
            "adminId": admin.id,
            "workshopId": workshop.id,
            "deniedId": denied.id,
            "waitingId": waiting.id,
        }


@pytest.fixture(scope="module")
def rerequested(world):
    response = world["client"].post(
        "/api/workshops/access-requests",
        json={"workshopIds": [world["workshopId"]], "accessLevel": "CONTRIBUTE", "note": "Asking again."},
        headers=world["asker"],
    )
    assert response.status_code == 201, response.text
    body = response.json()
    assert [o["outcome"] for o in body["outcomes"]] == ["RE_REQUESTED"]
    return body


def _row(world, row_id: str) -> dict:
    response = world["client"].get(
        "/api/workshops/access-requests", params={"statusFilter": "ALL"}, headers=world["admin"]
    )
    assert response.status_code == 200, response.text
    matches = [row for row in response.json() if row["id"] == row_id]
    assert len(matches) == 1, f"expected exactly one row {row_id} in the queue"
    return matches[0]



def test_the_refusal_and_its_reason_survive_the_rerequest(world, rerequested):
    row = _row(world, world["deniedId"])
    assert row["decidedById"] == world["adminId"]
    assert row["decisionNote"] == DECISION_NOTE
    assert row["decidedAt"] is not None


def test_the_row_is_pending_again_so_the_old_decision_cannot_be_read_as_this_answer(world, rerequested):
    row = _row(world, world["deniedId"])
    assert row["status"] == "PENDING"
    assert row["requestNote"] == "Asking again."


def test_the_admin_grant_history_is_still_cleared(world, rerequested):
    row = _row(world, world["deniedId"])
    assert row["assignedById"] is None



def test_the_rerequest_no_longer_jumps_the_queue(world, rerequested):
    response = world["client"].get(
        "/api/workshops/access-requests", params={"statusFilter": "PENDING"}, headers=world["admin"]
    )
    assert response.status_code == 200, response.text
    order = [row["id"] for row in response.json() if row["id"] in {world["deniedId"], world["waitingId"]}]
    assert order == [world["waitingId"], world["deniedId"]], "oldest first means oldest REQUEST first"


def test_the_pair_of_dates_reads_as_denied_then_asked_again(world, rerequested):
    row = _row(world, world["deniedId"])
    decided = datetime.fromisoformat(row["decidedAt"].replace("Z", "+00:00"))
    asked_again = datetime.fromisoformat(row["createdAt"].replace("Z", "+00:00"))
    assert decided == DENIED_AT
    assert asked_again > decided
