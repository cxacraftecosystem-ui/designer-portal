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
    return types.SimpleNamespace(
        id=uid, name=f"Name {uid}", email=f"{uid}@example.org", role=role, passwordHash="x"
    )


class FakeModel:
    def __init__(self, log: list[str], name: str, rows: list[Any] | None = None) -> None:
        self._log = log
        self._name = name
        self.rows: list[Any] = list(rows or [])
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
        self.rows = self.rows[::-1]
        return len(data)

    async def create(self, data: dict, **_: Any) -> Any:  # pragma: no cover - must never be called
        self._log.append(f"{self._name}.create")
        raise AssertionError("per-assignee create: the O(N) write is back")


async def _derivation_is_back(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
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
    admin = user("admin", "ADMIN")
    assignees = [user(f"w{index}") for index in range(100)]
    log: list[str] = []
    monkeypatch.setattr(tasks_route, "db", FakeDb(log, [admin, *assignees]))
    monkeypatch.setattr(tasks_route, "derive_progress", _derivation_is_back)
    return admin, assignees, log



@pytest.mark.parametrize(
    ("ids", "code", "detail"),
    [
        (["w0", "w1"], None, None),
        (["ghost"], 404, "Assignee not found"),
        (["w0", "ghost"], 404, "Assignee not found"),
        (["admin"], 422, "You cannot assign a task to yourself"),
        (["w0", "admin"], 422, "You cannot assign a task to yourself"),
        (["peer"], 403, "You can only assign tasks to people with a role below your own"),
        (["ghost", "admin"], 404, "Assignee not found"),
        (["admin", "ghost"], 422, "You cannot assign a task to yourself"),
        (["peer", "ghost"], 403, "You can only assign tasks to people with a role below your own"),
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

    assert log.count("user.find_many") == 1
    assert log.count("user.find_unique") == 0


async def test_the_master_admin_may_still_assign_to_an_equal_but_not_to_themselves(wired):
    master = user("master", "MASTER_ADMIN")
    tasks_route.db.user.rows.extend([master, user("peer", "ADMIN")])

    await tasks_route.assert_all_assignable(master, ["peer", "w0"])
    with pytest.raises(HTTPException) as raised:
        await tasks_route.assert_all_assignable(master, ["master"])
    assert raised.value.status_code == 422



def payload(assignee_ids: list[str]) -> TaskBatchCreate:
    return TaskBatchCreate(assigneeIds=assignee_ids, recordTypes=["tool"], targetCount=3)


@pytest.mark.parametrize("count", [1, 5, 100])
async def test_the_round_trip_count_does_not_grow_with_the_cohort(wired, count):
    admin, assignees, log = wired
    ids = [person.id for person in assignees[:count]]

    result = await tasks_route.create_task_batch(payload(ids), current_user=admin)

    assert result["created"] == count
    assert len(log) <= 6, log
    assert log.count("assignedtask.create_many") == 1
    assert log.count("assignedtask.find_many") == 1
    assert "assignedtask.create" not in log


async def test_a_full_scope_costs_six_and_echoes_rows_resolve_scope_had_already_loaded(wired):
    admin, _, log = wired
    tasks_route.db.workshop.rows.append(types.SimpleNamespace(id="ws1", title="Kutch weaving"))
    tasks_route.db.artisan.rows.extend(
        [
            types.SimpleNamespace(id="a1", name="Ramesh Bhai", place="Bhujodi"),
            types.SimpleNamespace(id="a2", name="Sita Ben", place="Nirona"),
        ]
    )
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
    assert task["artisans"] == [
        {"id": "a1", "name": "Ramesh Bhai", "place": "Bhujodi"},
        {"id": "a2", "name": "Sita Ben", "place": "Nirona"},
    ]
    assert task["sections"] == [
        {"id": "s1", "code": "C", "title": "Tools", "sortOrder": 3},
        {"id": "s2", "code": "D", "title": "Materials", "sortOrder": 4},
    ]


async def test_the_rows_come_back_in_the_order_the_admin_typed_them(wired):
    admin, _, _ = wired
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
        assert task["derivedCount"] is None
        assert task["derivedBreakdown"] == {}
        assert task["derivedTarget"] == 3
        assert task["percentComplete"] == 0
    assert result["batch"]["derivedTotal"] is None
    assert result["batch"]["assigneeCount"] == 2


async def test_a_duplicate_assignee_is_still_one_row(wired):
    admin, _, _ = wired

    result = await tasks_route.create_task_batch(payload(["w0", "w1", "w0"]), current_user=admin)

    assert result["created"] == 2
    assert [task["assigneeId"] for task in result["tasks"]] == ["w0", "w1"]
