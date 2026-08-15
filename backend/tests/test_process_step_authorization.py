"""Who may rewrite a process's step list, and what the repository keeps when somebody does.

THE HOLE THIS PINS. ``PATCH /api/processes/{id}`` is gated by ``Depends(get_current_user)`` alone,
and it builds its update payload as ``clean_data(payload.model_dump(exclude_unset=True,
exclude={"steps"}))`` — the step list is deliberately kept OUT of ``data`` so it never reaches the
Prisma row update. Every guard on that route reads ``data``. So a body of exactly ``{"steps": []}``
arrived with ``data == {}``: ``guard_record_edit`` handed it to ``assert_can_contribute_fields``,
whose locked-field comprehension iterates ``data.items()`` and therefore found nothing to refuse;
``record_revision`` iterated the same empty dict and wrote no RecordRevision; and control fell
through to the step sync, which computes ``removed = [step.id for step in existing if step.id not in
keep]`` and issues one ``delete_many``. Any account that could sign in could empty the step list of
any process in the repository, answered 200, with nothing written down to reconstruct it from.

IT DID NOT NEED A CRAFTED REQUEST. ``ProcessForm`` re-sends the whole payload on save, so a
researcher who did not create the process could open it, delete one step, and press Save: every
scalar field still matched the stored row, so ``values_match`` was true for all of them, the
locked-field list was still empty, and the guard passed on the only thing the request changed.

That second sentence is also why the fix is NOT ``count() > 0``. Because the form re-sends the step
list on every save, "does this process have steps" would answer 403 to an ordinary contributor
filling one empty box on a process whose steps they never touched. The guard asks the narrower
question — does this write CHANGE or DELETE a step that already exists — and the tests below assert
both halves, because a guard that refuses everything passes the security test and breaks the product.

These need Postgres: what is under test is which rows survive a request and which audit rows it
leaves behind, neither of which can be asserted in Python. The module skips itself when
``DATABASE_URL`` does not point at a local database, exactly as ``test_media_entitlement`` does.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma

THE STRANGER IS A DESIGNER ON PURPOSE, not a volunteer. DESIGNER is rank 35 — above RESEARCHER, so
``require_record_creator`` admits them and they are an ordinary, fully legitimate colleague; below
PROFESSOR, so they hold no rank-based privilege over somebody else's record. That is precisely the
account the hole was reachable from, and a test written with the ADMIN account most of this suite
uses would pass against the unfixed code.
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
    """An owning designer, a stranger designer, an admin, and a product to hang processes off.

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
            "email": f"step-owner-{stamp}@example.org", "name": "Step Owner",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        stranger = await db.user.create(data={
            "email": f"step-stranger-{stamp}@example.org", "name": "Step Stranger",
            "role": "DESIGNER", "passwordHash": hash_password("unused"),
        })
        admin = await db.user.create(data={
            "email": f"step-admin-{stamp}@example.org", "name": "Step Admin",
            "role": "ADMIN", "passwordHash": hash_password("unused"),
        })
        product = await db.productdocumentation.create(data={
            "craftName": "Block printing", "place": "Bagru", "artisanName": "Ramesh Kumar",
            "productName": "Printed cotton yardage", "createdById": owner.id,
        })
    finally:
        await db.disconnect()

    # ONE TestClient, three tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
    with TestClient(app) as client:
        yield {
            "client": client,
            "owner": create_access_token(subject=owner.id),
            "stranger": create_access_token(subject=stranger.id),
            "admin": create_access_token(subject=admin.id),
            "owner_id": owner.id,
            "stranger_id": stranger.id,
            "product_id": product.id,
        }


class _As:
    """The TestClient bound to one account's token, so a test reads as "as the stranger"."""

    def __init__(self, client, token: str) -> None:
        self._client = client
        self._headers = {"Authorization": f"Bearer {token}"}

    def get(self, url: str):
        return self._client.get(url, headers=self._headers)

    def post(self, url: str, json: dict):
        return self._client.post(url, json=json, headers=self._headers)

    def patch(self, url: str, json: dict):
        return self._client.patch(url, json=json, headers=self._headers)


@pytest.fixture
def owner_client(env):
    return _As(env["client"], env["owner"])


@pytest.fixture
def stranger_client(env):
    return _As(env["client"], env["stranger"])


@pytest.fixture
def admin_client(env):
    return _As(env["client"], env["admin"])


THREE_STEPS = [
    {"name": "Wash the cloth", "stepType": "SEQUENTIAL", "sortOrder": 1},
    {"name": "Print the border", "stepType": "SEQUENTIAL", "sortOrder": 2},
    {"name": "Sun-dry", "stepType": "SEQUENTIAL", "sortOrder": 3},
]


def _process(env, client, *, notes: str | None = None) -> dict:
    """A fresh three-step process owned by the OWNER account. One per test, never shared.

    Each test gets its own because the defect under test is destructive: a shared fixture whose steps
    one test deletes would make the next test's result depend on collection order.
    """
    response = client.post("/api/processes", json={
        "name": "Bagru printing sequence",
        "productId": env["product_id"],
        "notes": notes,
        "steps": THREE_STEPS,
    })
    assert response.status_code == 201, response.text
    body = response.json()
    assert [s["name"] for s in body["steps"]] == [s["name"] for s in THREE_STEPS]
    return body


def _step_names(client, process_id: str) -> list[str]:
    response = client.get(f"/api/processes/{process_id}")
    assert response.status_code == 200, response.text
    return [s["name"] for s in response.json()["steps"]]


def _resend(process: dict) -> list[dict]:
    """The step list exactly as ``ProcessForm`` re-sends it on an ordinary save: every stored step,
    with its server id, unchanged."""
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


# --------------------------------------------------------------------------------------
# 1. The hole itself
# --------------------------------------------------------------------------------------


def test_a_stranger_cannot_empty_the_step_list_of_a_process_they_did_not_create(
    env, owner_client, stranger_client
):
    """THE BLOCKER, in its shortest form: one signed-in account, one body, every step gone.

    Before the fix this answered 200 with ``"steps": []`` and the rows were deleted.
    """
    process = _process(env, owner_client)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": []})

    assert response.status_code == 403, response.text
    assert "steps" in response.json()["detail"]
    assert _step_names(owner_client, process["id"]) == [s["name"] for s in THREE_STEPS], (
        "the steps were deleted by an account that may not touch them"
    )


def test_removing_the_guard_brings_the_hole_straight_back(env, owner_client, stranger_client, monkeypatch):
    """THE OLD BEHAVIOUR, REPRODUCED IN PROCESS, so this file cannot quietly stop testing anything.

    A test that only asserts 403 passes just as well against a route that 403s for the wrong reason,
    or against a route somebody has since gated with a blunter dependency. Neutralising the ONE call
    this fix added must put the defect back exactly as the audit described it — 200, an empty step
    list in the response, and the rows gone — and if it does not, the assertions above have stopped
    measuring the guard.

    Reproduced by patching the module global rather than by editing the file: the working tree is
    shared, and a route with its authorization check commented out is not a thing to leave lying
    around for even one test run.
    """
    import app.api.routes.processes as processes_route

    monkeypatch.setattr(
        processes_route, "assert_can_contribute_relation", lambda *args, **kwargs: None
    )
    process = _process(env, owner_client)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": []})

    assert response.status_code == 200, "the guard is not what is answering 403 above"
    assert response.json()["steps"] == []
    assert _step_names(owner_client, process["id"]) == []


def test_a_stranger_cannot_delete_one_step_through_the_form_either(
    env, owner_client, stranger_client
):
    """The path a designer actually walks. No crafting: open the process, remove one step, save.

    ``ProcessForm`` sends the whole payload, so every scalar matches the stored row and the field
    guard has nothing to refuse — which is exactly why the relation needs a guard of its own.
    """
    process = _process(env, owner_client)
    remaining = _resend(process)[:-1]

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={
        "name": process["name"],
        "productId": env["product_id"],
        "preProcessAvailable": process["preProcessAvailable"],
        "notes": process["notes"],
        "status": process["status"],
        "steps": remaining,
    })

    assert response.status_code == 403, response.text
    assert _step_names(owner_client, process["id"]) == [s["name"] for s in THREE_STEPS]


def test_a_stranger_cannot_rename_a_step_somebody_else_recorded(env, owner_client, stranger_client):
    """Rewriting is the other half of the same authority. Deleting was the loud consequence;
    silently replacing a documented step with different words is the quiet one."""
    process = _process(env, owner_client)
    edited = _resend(process)
    edited[1]["name"] = "Print the WHOLE cloth"

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": edited})

    assert response.status_code == 403, response.text
    assert _step_names(owner_client, process["id"])[1] == "Print the border"


# --------------------------------------------------------------------------------------
# 2. The half a blanket refusal would break
#
# Every assertion below FAILS if the guard is written as "does this process have steps", which is
# the obvious reading of assert_can_contribute_relation and the one the workshop roster uses. The
# difference is that a workshop roster is only sent when it is being changed, while ProcessForm
# re-sends the step list on every single save.
# --------------------------------------------------------------------------------------


def test_a_stranger_may_still_fill_an_empty_field_while_resending_the_step_list_untouched(
    env, owner_client, stranger_client
):
    """The ordinary contribution the repository is built to allow, on a process WITH steps.

    A contributor may fill a field nobody has populated. The form sends the step list alongside it
    because the form always does; that must not turn a legal edit into a 403.
    """
    process = _process(env, owner_client, notes=None)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={
        "notes": "Observed again during the second sitting.",
        "steps": _resend(process),
    })

    assert response.status_code == 200, response.text
    assert response.json()["notes"] == "Observed again during the second sitting."
    assert _step_names(owner_client, process["id"]) == [s["name"] for s in THREE_STEPS]


def test_a_stranger_may_append_a_step_because_a_step_that_does_not_exist_is_an_empty_field(
    env, owner_client, stranger_client
):
    """Contribution, not overwriting. Nothing already recorded moves, so nothing is taken from
    anyone — the same rule ``assert_can_contribute_fields`` applies to a blank column."""
    process = _process(env, owner_client)
    added = _resend(process) + [
        {"name": "Fold and stack", "stepType": "SEQUENTIAL", "sortOrder": 4, "notes": None}
    ]

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={"steps": added})

    assert response.status_code == 200, response.text
    assert _step_names(owner_client, process["id"])[-1] == "Fold and stack"


def test_the_owner_may_still_delete_their_own_steps(env, owner_client):
    """The guard must not lock the author out of their own record — which is the failure mode of
    every over-tight fix to an authorization hole."""
    process = _process(env, owner_client)

    response = owner_client.patch(f"/api/processes/{process['id']}", json={"steps": []})

    assert response.status_code == 200, response.text
    assert response.json()["steps"] == []
    assert _step_names(owner_client, process["id"]) == []


def test_an_admin_may_still_rewrite_anybody_s_steps(env, owner_client, admin_client):
    """``assert_can_contribute_relation`` short-circuits for an admin, and ``guard_record_edit``
    marks them privileged before it is even reached. Asserted so a future tightening of the
    predicate cannot quietly take the curation power admins are supposed to have."""
    process = _process(env, owner_client)

    response = admin_client.patch(f"/api/processes/{process['id']}", json={
        "steps": [{"name": "Corrected single step", "stepType": "SEQUENTIAL", "sortOrder": 1}],
    })

    assert response.status_code == 200, response.text
    assert _step_names(owner_client, process["id"]) == ["Corrected single step"]


# --------------------------------------------------------------------------------------
# 3. The audit row, which is what makes the loss reconstructable
# --------------------------------------------------------------------------------------


def _step_revisions(client, process_id: str) -> list[dict]:
    """The record's edit history, read through the endpoint an admin actually uses.

    Read through the API rather than through ``db`` directly, deliberately: the Prisma client is
    shared with the running app and bound to the TestClient's event loop, so a query issued from a
    test's own loop is the kind of cross-loop use that fails intermittently instead of honestly. It
    also asserts the stronger thing — that the revision is VISIBLE on the surface built for
    reconstructing a record, not merely present in a table.
    """
    response = client.get(
        f"/api/data-access/revisions?recordType=process&recordId={process_id}"
    )
    assert response.status_code == 200, response.text
    return [row for row in response.json() if "steps" in row["changes"]]


def test_deleting_a_step_writes_a_revision_naming_what_was_there(env, owner_client):
    """``REVISION_SKIP_FIELDS`` never skipped ``steps``; the route simply never handed it one,
    because the step list travels outside ``data``. Without this row an admin looking at a process
    that lost three steps has nothing at all to restore from — not the names, and not the ids the
    orphaned step photographs are linked to.
    """
    process = _process(env, owner_client)
    kept = _resend(process)[:1]

    response = owner_client.patch(f"/api/processes/{process['id']}", json={"steps": kept})
    assert response.status_code == 200, response.text

    revisions = _step_revisions(owner_client, process["id"])
    assert revisions, "no RecordRevision names the step list"
    change = revisions[-1]["changes"]["steps"]
    assert [s["name"] for s in change["old"]] == [s["name"] for s in THREE_STEPS]
    assert [s["name"] for s in change["new"]] == ["Wash the cloth"]
    # The ids are what tie the revision back to the ``processstep`` rows the media hung off.
    assert {s["id"] for s in change["old"]} == {s["id"] for s in process["steps"]}
    assert revisions[-1]["editedById"] == env["owner_id"]


def test_a_save_that_leaves_the_steps_alone_writes_no_step_revision(env, owner_client):
    """The audit has to stay readable. ``ProcessForm`` re-sends the step list on every save, so a
    revision written on every PATCH would bury the one edit that mattered under a hundred that
    changed nothing — which is the same reason ``record_revision`` no-ops on an empty diff."""
    process = _process(env, owner_client)

    response = owner_client.patch(f"/api/processes/{process['id']}", json={
        "notes": "A note, and the step list exactly as it came.",
        "steps": _resend(process),
    })
    assert response.status_code == 200, response.text

    assert not _step_revisions(owner_client, process["id"])


# --------------------------------------------------------------------------------------
# 4. Nothing is written when the request is refused
# --------------------------------------------------------------------------------------


def test_a_refused_step_deletion_does_not_leave_the_field_half_saved(
    env, owner_client, stranger_client
):
    """``tools.assign_tool_artisans`` states the rule this asserts: a rejected request must leave no
    partial state behind. The step plan is therefore built and refused BEFORE the row update runs, so
    a payload mixing one legal field fill with one illegal deletion cannot answer 403 and still
    persist half of itself."""
    process = _process(env, owner_client, notes=None)

    response = stranger_client.patch(f"/api/processes/{process['id']}", json={
        "notes": "This note must not survive the refusal.",
        "steps": _resend(process)[:-1],
    })
    assert response.status_code == 403, response.text

    detail = owner_client.get(f"/api/processes/{process['id']}").json()
    assert detail["notes"] is None, "a refused request persisted the field it also carried"
    assert [s["name"] for s in detail["steps"]] == [s["name"] for s in THREE_STEPS]
