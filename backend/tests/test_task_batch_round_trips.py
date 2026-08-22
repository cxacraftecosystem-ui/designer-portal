"""``POST /tasks/batch`` costs the same whether it assigns work to one person or a hundred.

THE DEFECT THIS PINS. The route used to validate assignees in a ``for`` loop of
``db.user.find_unique`` and then write the rows in a comprehension of ``db.assignedtask.create``:
two sequential cross-region round trips PER ASSIGNEE, for a cohort ``TaskBatchCreate`` sizes at up
to ``MAX_ASSIGNEES`` (100). The database is in another region and this repository measures a round
trip at 756 ms, so those two hops alone reach CloudFront's thirty-second origin timeout at roughly
twenty assignees, and the serializer then added a COUNT per (assignee, record type) on top because
``_record_key`` keys derivation by ``assigneeId`` and so a batch de-duplicates to nothing. THE
assignment endpoint could not finish the assignment it exists to make, and because each create was
its own transaction the admin got a 504 over a batch that had half landed.

WHY THIS MODULE NEEDS NO DATABASE, when every other task-route test does. What is being asserted is
HOW MANY queries the route issues and in what order it puts the answer — both of which are properties
of the route, not of Postgres. A fake client that records every call answers those questions exactly,
runs in the time an import takes, and keeps working on a machine where Docker is down; a real
database could only ever show that the route still worked, which was never in doubt. The one thing a
fake cannot check is that ``create_many`` really inserts — that is covered by the same call in
``workshops.request_workshop_access``, which has been in production against this schema.

THE BUDGET IS ASSERTED AS A CEILING THAT DOES NOT MOVE WITH N, not as an exact number, so a future
change that adds one necessary query fails loudly at a hundred assignees rather than silently
restoring the per-assignee shape. The one case that actually sits AT the ceiling — a scope naming a
workshop, artisans and sections, which is three round trips inside ``resolve_scope`` before the
route has written anything — is pinned to an exact call list rather than a ceiling, because that is
also the only case in which the ``lookups=`` hand-over does any work: without it the response's
artisan and section names cost two more reads of rows the route is already holding.

THE THIRD ASSERTION IS THAT DERIVATION STAYS OFF, and it is made by replacing ``derive_progress``
itself, not by trapping a ``count`` on the fake client. ``derive_progress`` swallows any exception
its counts raise; see :func:`_derivation_is_back` for why nothing below that function can guard it.

TEST ORDER FOLLOWS THE FIX. First that the bulk assignability check reproduces all three refusals of
the single-assignee guard — which must not change, because two other routes depend on it — and only
then the write, because a cheaper route that admits somebody it should have refused is not a fix.
"""

import types
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import tasks as tasks_route
from app.schemas.tasks import TaskBatchCreate

pytestmark = pytest.mark.anyio


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


def user(uid: str, role: str = "RESEARCHER") -> Any:
    """Just enough of a ``User`` row for the rank rule and ``user_brief`` to read it."""
    return types.SimpleNamespace(
        id=uid, name=f"Name {uid}", email=f"{uid}@example.org", role=role, passwordHash="x"
    )


class FakeModel:
    """One Prisma model namespace, recording every call it is asked to make."""

    def __init__(self, log: list[str], name: str, rows: list[Any] | None = None) -> None:
        self._log = log
        self._name = name
        self.rows: list[Any] = list(rows or [])
        #: The user table, so a written task can carry the ``assignee`` relation ``include=INCLUDE``
        #: would have loaded. Set by :class:`FakeDb`; empty for every other model.
        self.users: list[Any] = []

    async def find_unique(self, where: dict, **_: Any) -> Any:
        self._log.append(f"{self._name}.find_unique")
        return next((row for row in self.rows if row.id == where.get("id")), None)

    async def find_many(self, where: dict | None = None, **_: Any) -> list[Any]:
        self._log.append(f"{self._name}.find_many")
        return [row for row in self.rows if _matches(row, where or {})]

    async def create_many(self, data: list[dict], **_: Any) -> int:
        self._log.append(f"{self._name}.create_many")
        for index, item in enumerate(data):
            row = types.SimpleNamespace(
                id=f"{self._name}-{len(self.rows) + index}",
                status="OPEN",
                progressCount=0,
                completedAt=None,
                createdAt=None,
                workshop=None,
                assignee=None,
                createdBy=None,
                **item,
            )
            row.assignee = next((u for u in self.users if u.id == row.assigneeId), None)
            self.rows.append(row)
        # The read-back must not be able to answer from insertion order by accident: an admin's
        # typed order is what the response has to restore, so hand the rows back reversed.
        self.rows = self.rows[::-1]
        return len(data)

    async def create(self, data: dict, **_: Any) -> Any:  # pragma: no cover - must never be called
        self._log.append(f"{self._name}.create")
        raise AssertionError("per-assignee create: the O(N) write is back")


async def _derivation_is_back(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
    """Stands in for :func:`tasks.derive_progress`, which the batch create must never reach.

    THIS GUARD CANNOT LIVE ON THE MODEL, which is why it lives at the entry point. A ``count`` on
    the fake client that raised would be caught twice over and never seen: ``derive_progress`` runs
    every count through ``asyncio.gather(..., return_exceptions=True)`` and turns ANY
    ``BaseException`` into ``counts[key] = None`` plus a ``logger.warning``, so the assertion would
    be swallowed and the response would come back looking exactly like the derivation-off one it is
    supposed to distinguish from — and in any case this fake has no ``tooldocumentation`` /
    ``process`` / ``mediafile`` / ``questionnaireresponse`` namespace, so ``_count_records`` dies on
    attribute access before a ``count`` is ever recorded. Refusing at the function the route calls
    is the only place the refusal survives to fail a test.
    """
    raise AssertionError("derivation is back on the batch response")


def _matches(row: Any, where: dict) -> bool:
    for key, value in where.items():
        actual = getattr(row, key, None)
        if isinstance(value, dict) and "in" in value:
            if actual not in value["in"]:
                return False
        elif actual != value:
            return False
    return True


class FakeDb:
    def __init__(self, log: list[str], users: list[Any]) -> None:
        self.user = FakeModel(log, "user", users)
        self.workshop = FakeModel(log, "workshop")
        self.artisan = FakeModel(log, "artisan")
        self.questionnairesection = FakeModel(log, "questionnairesection")
        self.assignedtask = FakeModel(log, "assignedtask")
        self.assignedtask.users = self.user.rows


@pytest.fixture
def wired(monkeypatch):
    """The route with a recording client behind it. Returns ``(admin, assignees, log)``."""
    admin = user("admin", "ADMIN")
    assignees = [user(f"w{index}") for index in range(100)]
    log: list[str] = []
    monkeypatch.setattr(tasks_route, "db", FakeDb(log, [admin, *assignees]))
    monkeypatch.setattr(tasks_route, "derive_progress", _derivation_is_back)
    return admin, assignees, log


# ---------------------------------------------------------------------------------------------
# The rule must not have moved
# ---------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("ids", "code", "detail"),
    [
        (["w0", "w1"], None, None),
        (["ghost"], 404, "Assignee not found"),
        (["w0", "ghost"], 404, "Assignee not found"),
        (["admin"], 422, "You cannot assign a task to yourself"),
        (["w0", "admin"], 422, "You cannot assign a task to yourself"),
        (["peer"], 403, "You can only assign tasks to users below your own tier"),
        # ORDER DECIDES, exactly as the per-assignee loop's did: the first id with a problem is the
        # one reported, even when a later id would have raised a different status.
        (["ghost", "admin"], 404, "Assignee not found"),
        (["admin", "ghost"], 422, "You cannot assign a task to yourself"),
        (["peer", "ghost"], 403, "You can only assign tasks to users below your own tier"),
    ],
)
async def test_the_bulk_check_answers_exactly_as_the_per_assignee_one(wired, ids, code, detail):
    admin, _, log = wired
    tasks_route.db.user.rows.append(user("peer", "ADMIN"))

    if code is None:
        await tasks_route.assert_all_assignable(admin, ids)
    else:
        with pytest.raises(HTTPException) as raised:
            await tasks_route.assert_all_assignable(admin, ids)
        assert raised.value.status_code == code
        assert raised.value.detail == detail

    # And it did so in ONE query, however many ids were handed to it.
    assert log.count("user.find_many") == 1
    assert log.count("user.find_unique") == 0


async def test_the_master_admin_may_still_assign_to_an_equal_but_not_to_themselves(wired):
    master = user("master", "MASTER_ADMIN")
    tasks_route.db.user.rows.extend([master, user("peer", "ADMIN")])

    await tasks_route.assert_all_assignable(master, ["peer", "w0"])
    with pytest.raises(HTTPException) as raised:
        await tasks_route.assert_all_assignable(master, ["master"])
    assert raised.value.status_code == 422


# ---------------------------------------------------------------------------------------------
# The write
# ---------------------------------------------------------------------------------------------


def payload(assignee_ids: list[str]) -> TaskBatchCreate:
    return TaskBatchCreate(assigneeIds=assignee_ids, recordTypes=["tool"], targetCount=3)


@pytest.mark.parametrize("count", [1, 5, 100])
async def test_the_round_trip_count_does_not_grow_with_the_cohort(wired, count):
    admin, assignees, log = wired
    ids = [person.id for person in assignees[:count]]

    result = await tasks_route.create_task_batch(payload(ids), current_user=admin)

    assert result["created"] == count
    # Six is the target the fix was written to. Asserting a ceiling rather than equality leaves room
    # for a query a later feature genuinely needs; what it does not leave room for is anything that
    # scales with ``count``, which is the whole defect.
    assert len(log) <= 6, log
    assert log.count("assignedtask.create_many") == 1
    assert log.count("assignedtask.find_many") == 1
    assert "assignedtask.create" not in log


async def test_a_full_scope_costs_six_and_echoes_rows_resolve_scope_had_already_loaded(wired):
    """The only case that reaches the ceiling, and the only one that exercises ``lookups=``.

    ``payload()`` above names no workshop, artisans or sections, so ``resolve_scope`` issues nothing
    and the hand-over has nothing to hand over — the whole scope half of the fix is invisible to
    every other test in this module. Here all three dimensions are filled, which is both the real
    six-query budget and the shape in which deleting the ``lookups=`` argument would cost two more
    reads (``load_scope_lookups`` re-fetching artisans and sections the route already holds).
    """
    admin, _, log = wired
    tasks_route.db.workshop.rows.append(types.SimpleNamespace(id="ws1", title="Kutch weaving"))
    tasks_route.db.artisan.rows.extend(
        [
            types.SimpleNamespace(id="a1", name="Ramesh Bhai", place="Bhujodi"),
            types.SimpleNamespace(id="a2", name="Sita Ben", place="Nirona"),
        ]
    )
    # Seeded out of questionnaire order, so the sorted() in serialize_task has something to do.
    tasks_route.db.questionnairesection.rows.extend(
        [
            types.SimpleNamespace(id="s2", code="D", title="Materials", sortOrder=4),
            types.SimpleNamespace(id="s1", code="C", title="Tools", sortOrder=3),
        ]
    )
    body = TaskBatchCreate(
        assigneeIds=["w0", "w1"],
        recordTypes=["tool"],
        targetCount=3,
        workshopId="ws1",
        artisanIds=["a1", "a2"],
        sectionIds=["s2", "s1"],
    )

    result = await tasks_route.create_task_batch(body, current_user=admin)

    # Exact, not a ceiling: three scope reads, one bulk assignability read, the write, the read-back.
    # Dropping ``lookups=`` puts artisan.find_many and questionnairesection.find_many back on the
    # end of this list, so THIS is the assertion that pins the hand-over exists at all.
    assert log == [
        "workshop.find_unique",
        "artisan.find_many",
        "questionnairesection.find_many",
        "user.find_many",
        "assignedtask.create_many",
        "assignedtask.find_many",
    ]

    task = result["tasks"][0]
    assert task["workshopId"] == "ws1"
    # And the hand-over carries the RIGHT rows: a swapped or half-built tuple would still cost six
    # queries and would still be caught here.
    assert task["artisans"] == [
        {"id": "a1", "name": "Ramesh Bhai", "place": "Bhujodi"},
        {"id": "a2", "name": "Sita Ben", "place": "Nirona"},
    ]
    # Questionnaire order, not the order the admin's payload listed them in.
    assert task["sections"] == [
        {"id": "s1", "code": "C", "title": "Tools", "sortOrder": 3},
        {"id": "s2", "code": "D", "title": "Materials", "sortOrder": 4},
    ]


async def test_the_rows_come_back_in_the_order_the_admin_typed_them(wired):
    admin, _, _ = wired
    # Deliberately not sorted, and the fake client hands the read-back out reversed.
    ids = ["w7", "w2", "w9", "w0"]

    result = await tasks_route.create_task_batch(payload(ids), current_user=admin)

    assert [task["assigneeId"] for task in result["tasks"]] == ids
    assert [line["user"]["id"] for line in result["batch"]["assignees"]] == ids
    assert len({task["batchId"] for task in result["tasks"]}) == 1
    assert result["batchId"] == result["tasks"][0]["batchId"]


async def test_the_response_still_carries_every_key_it_did_with_derivation_on(wired):
    admin, _, _ = wired

    result = await tasks_route.create_task_batch(payload(["w0", "w1"]), current_user=admin)

    assert set(result) == {"batchId", "title", "created", "batch", "tasks"}
    for task in result["tasks"]:
        # Present and typed as before; null is what any page over DERIVED_TASK_LIMIT already returns.
        assert task["derivedCount"] is None
        assert task["derivedBreakdown"] == {}
        # derivedTarget is computed from the row itself, so it survives derivation being off:
        # targetCount=3 with record types and no sections.
        assert task["derivedTarget"] == 3
        assert task["percentComplete"] == 0
    assert result["batch"]["derivedTotal"] is None
    assert result["batch"]["assigneeCount"] == 2


async def test_a_duplicate_assignee_is_still_one_row(wired):
    admin, _, _ = wired

    result = await tasks_route.create_task_batch(payload(["w0", "w1", "w0"]), current_user=admin)

    assert result["created"] == 2
    assert [task["assigneeId"] for task in result["tasks"]] == ["w0", "w1"]
