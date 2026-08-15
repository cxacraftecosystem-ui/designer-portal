"""What the AUDIT LOG holds after ``PATCH /api/processes/{id}`` is refused.

THE FLAW IN A FIX, WHICH IS WHY THIS IS ITS OWN FILE. The BLOCKER fix of 2026-08-15 stopped a
stranger deleting another person's process steps by planning the step change and calling
``assert_can_contribute_relation`` BEFORE the row update, on the rule ``tools.assign_tool_artisans``
states out loud: a rejected request must leave no partial state behind. It put the guard above the
``db.process.update`` and no higher — and ``guard_record_edit``, which sits above both, ENDS in
``record_revision``. So the refused request left exactly the trace the rule forbids, one layer up:

    403 to the caller, the row untouched, and a committed RecordRevision saying
    ``notes: {old: null, new: "This note must not survive the refusal."}`` authored by the
    account that was just turned away.

An audit trail that records edits which did not happen is worse than one with a gap in it. It is
read by an admin reconstructing who did what to a record — ``GET /api/data-access/revisions`` is
that surface — and it would name a value that was never stored as the newest one, and a refused
caller as its author. The remedy is the same argument one step further along: the relation is now
planned and refused ABOVE ``guard_record_edit``, so nothing that can answer 403 runs after the
ledger is written.

THE OTHER HALF IS EQUALLY LOAD-BEARING and half this file is spent on it: a fix that stopped
writing the revision on the legitimate path would trade a spurious audit row for a MISSING one,
which is strictly worse — the step revision is the only thing an admin can restore deleted steps
from, including the ids the orphaned step photographs are linked to. So the legitimate edits are
pinned here as tightly as the refusal: one revision, the right fields, the right author.

THE STRANGER IS A DESIGNER ON PURPOSE. DESIGNER is rank 35 — above RESEARCHER, so
``require_record_creator`` admits them and they are an ordinary colleague; below PROFESSOR, so they
hold no rank privilege over somebody else's record. That is the account the hole was reachable from.

These need Postgres: what is under test is which rows a refused request leaves behind. The module
skips itself when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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
    """An owning designer, a stranger designer, and a product to hang processes off.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly.
    """
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

    # ONE TestClient, two tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
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
    """A fresh three-step process owned by the OWNER account. One per test, never shared: what is
    under test is what a request leaves behind, so a shared row would make one test's result depend
    on another's."""
    response = env["client"].post("/api/processes", json={
        "name": "Bagru printing sequence",
        "productId": env["product_id"],
        "notes": notes,
        "steps": THREE_STEPS,
    }, headers=env["owner"])
    assert response.status_code == 201, response.text
    return response.json()


def _resend(process: dict) -> list[dict]:
    """The step list exactly as ``ProcessForm`` re-sends it on an ordinary save."""
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
    """The record's whole edit history, read through the endpoint an admin actually uses.

    Read through the API rather than through ``db`` directly, deliberately: the Prisma client is
    shared with the running app and bound to the TestClient's event loop, so a query issued from a
    test's own loop is the kind of cross-loop use that fails intermittently instead of honestly. It
    also asserts the stronger thing — that the spurious row is (or is not) VISIBLE on the surface
    built for reconstructing a record, not merely present in a table.
    """
    response = env["client"].get(
        f"/api/data-access/revisions?recordType=process&recordId={process_id}",
        headers=env["owner"],
    )
    assert response.status_code == 200, response.text
    return response.json()


# --------------------------------------------------------------------------------------
# 1. The refusal, which must leave nothing at all — not the row, and not the ledger
# --------------------------------------------------------------------------------------


def test_a_refused_step_deletion_writes_no_revision_for_the_field_it_also_carried(env):
    """THE FLAW, in the shape the verifier found it. One PATCH carrying one legal field fill and one
    illegal step deletion: before this change it answered 403 with a RecordRevision already
    committed for ``notes``.

    All three assertions matter and the first two are the ORIGINAL BLOCKER, re-asserted here so this
    file fails if a future rearrangement fixes the ledger by moving the guard back below the write.
    """
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
    """The same refusal read from the other end: not merely "no notes revision", but no revision
    AUTHORED BY the account that was turned away, whatever fields it carried. Written separately
    because the harm is the attribution — an admin reading the history would see the stranger's name
    against an edit to a record they were refused."""
    process = _process(env, notes=None)

    refused = env["client"].patch(f"/api/processes/{process['id']}", json={
        "name": "Renamed by somebody with no right to",
        "notes": "And a note with it.",
        "steps": [],
    }, headers=env["stranger"])
    assert refused.status_code == 403, refused.text

    authors = {row["editedById"] for row in _revisions(env, process["id"])}
    assert env["stranger_id"] not in authors, "a refused caller is recorded as having edited"


def test_a_refused_locked_field_edit_leaves_no_revision_either(env):
    """The OTHER refusal on this route, pinned so the reordering cannot have moved the problem.
    ``assert_can_contribute_fields`` raises inside ``guard_record_edit`` BEFORE
    ``record_revision`` runs, so overwriting a populated field somebody else recorded must also
    leave the ledger empty. No steps in this payload at all, so only the field guard can answer."""
    process = _process(env, notes="The note the owner recorded.")

    refused = env["client"].patch(f"/api/processes/{process['id']}", json={
        "notes": "Overwritten by a contributor.",
    }, headers=env["stranger"])
    assert refused.status_code == 403, refused.text

    assert _revisions(env, process["id"]) == []
    detail = env["client"].get(f"/api/processes/{process['id']}", headers=env["owner"]).json()
    assert detail["notes"] == "The note the owner recorded."


# --------------------------------------------------------------------------------------
# 2. The audit rows that MUST still be written
#
# A lost revision is worse than a spurious one: the step revision is the only thing an admin can
# reconstruct deleted steps from. Every assertion below fails if the revision were simply moved
# after the point of no return and dropped on the way.
# --------------------------------------------------------------------------------------


def test_a_legitimate_field_edit_still_writes_exactly_one_revision_with_the_right_author(env):
    """One PATCH, one row, the right fields, the right editor. ``guard_record_edit`` is now called
    twice on a steps-carrying PATCH — once with ``{}`` purely for the privilege verdict and once
    with the real ``data`` — and the empty call must write NOTHING; if it ever started writing, this
    would see two rows for one edit."""
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
    """The row an admin restores from. Without it a process that lost three steps has nothing at all
    to reconstruct from — not the names, and not the ids the orphaned step photographs hang off."""
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
    """The legitimate contribution the whole relation guard was written not to break, plus its
    audit row. The step list is re-sent untouched, exactly as ``ProcessForm`` sends it, so the
    privilege probe runs and the relation guard passes — and the revision is still written, under
    the CONTRIBUTOR's name."""
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
