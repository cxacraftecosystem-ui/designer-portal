"""Asking again for access you were refused must not erase the refusal.

``WorkshopAssignment.decidedById`` / ``decidedAt`` / ``decisionNote`` are the ONLY record a
workshop-access decision has — there is no history table beside the row — and three places in this
codebase promise they survive: ``schema.prisma`` on ``status`` ("DENIED and REVOKED are kept rather
than deleted so the decision stays auditable"), the ``services/workshop_access`` module docstring, and
``docs/PERMISSIONS.md``. ``POST /workshops/access-requests`` nulled all three whenever the person who
had been refused selected the workshop again, so an admin's decision and its reason were destroyed by
its own subject, and ``statusFilter=ALL`` — the view the route's docstring calls "the full history for
auditing" — no longer held it. The row survived; the decision did not.

The clearing was deliberate, and the worry behind it (a stale "denied by X" being read as THIS
request's outcome) is answered by a column the same statement writes: ``status`` is PENDING, and the
admin queue is already built that way — ``decidable = row.status === "PENDING"`` in
``WorkshopAccessQueuePanel`` shows the decide form for a pending row and the "Decided by …" line only
for a settled one.

THE SECOND HALF IS THE ORDERING, and it is the one a reviewer skips. The queue is
``order={"createdAt": "asc"}`` and calls itself "oldest first". ``createdAt`` was left untouched by a
statement that turns the row into what the route's own docstring calls "a NEW request", so a
re-request made this morning sorted ABOVE requests that had genuinely been waiting a week.
``test_the_rerequest_no_longer_jumps_the_queue`` is that failure, reproduced with two rows and one
GET; without the ``createdAt`` refresh it fails on the order alone even though every decision column
is intact.

Needs Postgres — every assertion is about columns on a row after a request — so the module skips
itself when ``DATABASE_URL`` does not point at a local database, exactly as
``test_task_option_pickers`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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

#: The moment the refusal was made. Well in the past, so "was `createdAt` refreshed?" is answered by
#: a comparison that cannot be satisfied by clock jitter.
DENIED_AT = datetime(2026, 3, 3, 9, 30, tzinfo=UTC)
#: An unrelated request that has genuinely been waiting since the day after the refusal. It is what
#: makes the ordering assertion mean something: the re-request must sort BELOW it.
WAITING_SINCE = datetime(2026, 3, 4, 9, 30, tzinfo=UTC)
DECISION_NOTE = "Embargoed until the ministry review closes."


@pytest.fixture(scope="module")
async def world():
    """An admin who refuses, the researcher refused, a bystander who has been waiting, one workshop.

    Rows are created before the app starts, for the reason ``test_task_option_pickers`` records: the
    Prisma client is shared with the running app and bound to the TestClient's event loop, and
    touching it from a test's own loop is the kind of cross-loop use that fails intermittently
    rather than honestly.
    """
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
        # THE REFUSAL. Written as a settled DENIED row with every decision column populated, which is
        # exactly the shape ``POST /access-requests/{id}/decide`` leaves behind.
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
    """Ask again, ONCE for the whole module.

    The route is idempotent per workshop but not per outcome: a second call would find the row it has
    just made PENDING and answer ALREADY_PENDING, leaving ``createdAt`` alone — so re-requesting per
    test would make every assertion after the first a statement about a different code path.
    """
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
    """The row as the ADMIN QUEUE serves it, with ``statusFilter=ALL`` — the view the route's own
    docstring calls "the full history for auditing", which is the claim under test."""
    response = world["client"].get(
        "/api/workshops/access-requests", params={"statusFilter": "ALL"}, headers=world["admin"]
    )
    assert response.status_code == 200, response.text
    matches = [row for row in response.json() if row["id"] == row_id]
    assert len(matches) == 1, f"expected exactly one row {row_id} in the queue"
    return matches[0]


# --------------------------------------------------------------------------------------
# 1. The decision survives
# --------------------------------------------------------------------------------------


def test_the_refusal_and_its_reason_survive_the_rerequest(world, rerequested):
    """THE DEFECT IN ONE REQUEST. All three of these read ``None`` before the fix, and nothing else in
    the schema held the information, so the admin's decision was gone for good."""
    row = _row(world, world["deniedId"])
    assert row["decidedById"] == world["adminId"]
    assert row["decisionNote"] == DECISION_NOTE
    assert row["decidedAt"] is not None


def test_the_row_is_pending_again_so_the_old_decision_cannot_be_read_as_this_answer(world, rerequested):
    """The worry that motivated the clearing, answered by the column that was always being written.
    A PENDING row is undecided on its face, and the queue renders it with the decide form."""
    row = _row(world, world["deniedId"])
    assert row["status"] == "PENDING"
    assert row["requestNote"] == "Asking again."


def test_the_admin_grant_history_is_still_cleared(world, rerequested):
    """``assignedById`` clearing is CORRECT and must stay: the row's history as an admin roster entry
    ended when it was revoked, and leaving it set lets one approval silently re-close a workshop that
    had reopened. This is the half of the old behaviour that was not a defect."""
    row = _row(world, world["deniedId"])
    assert row["assignedById"] is None


# --------------------------------------------------------------------------------------
# 2. The queue ranks it as what it is — a request made today
# --------------------------------------------------------------------------------------


def test_the_rerequest_no_longer_jumps_the_queue(world, rerequested):
    """``createdAt`` was frozen at the ORIGINAL request's date, so a "NEW request" made this morning
    sorted above one that had genuinely been waiting since. The queue is ``createdAt: asc`` and calls
    itself oldest first, so this is the queue lying about which admin owes whom an answer."""
    response = world["client"].get(
        "/api/workshops/access-requests", params={"statusFilter": "PENDING"}, headers=world["admin"]
    )
    assert response.status_code == 200, response.text
    order = [row["id"] for row in response.json() if row["id"] in {world["deniedId"], world["waitingId"]}]
    assert order == [world["waitingId"], world["deniedId"]], "oldest first means oldest REQUEST first"


def test_the_pair_of_dates_reads_as_denied_then_asked_again(world, rerequested):
    """What the fix buys the admin, and the reason no schema change was needed for it: with the
    decision columns kept and ``createdAt`` refreshed, ``decidedAt`` < ``createdAt`` on one row says
    "denied on the 3rd — asked again today" without a ``reRequestedAt`` column existing."""
    row = _row(world, world["deniedId"])
    decided = datetime.fromisoformat(row["decidedAt"].replace("Z", "+00:00"))
    asked_again = datetime.fromisoformat(row["createdAt"].replace("Z", "+00:00"))
    assert decided == DENIED_AT
    assert asked_again > decided
