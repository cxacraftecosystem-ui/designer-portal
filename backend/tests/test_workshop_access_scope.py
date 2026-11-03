from types import SimpleNamespace
from typing import Any

import pytest

from app.services import workshop_access


@pytest.fixture
def anyio_backend():
    return "asyncio"


def row(
    workshop_id: str,
    user_id: str,
    status: str = "GRANTED",
    assigned_by: str | None = None,
    level: str = "CONTRIBUTE",
) -> Any:
    return SimpleNamespace(
        workshopId=workshop_id, userId=user_id, status=status, assignedById=assigned_by, accessLevel=level
    )


class _Assignments:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.queries: list[dict[str, Any]] = []

    async def find_many(self, where: dict[str, Any] | None = None) -> list[Any]:
        self.queries.append(where or {})
        if not where:
            return list(self.rows)
        return [r for r in self.rows if all(getattr(r, key) == value for key, value in where.items())]


@pytest.fixture
def table(monkeypatch):
    def install(rows: list[Any]) -> _Assignments:
        assignments = _Assignments(rows)
        monkeypatch.setattr(workshop_access, "db", SimpleNamespace(workshopassignment=assignments))
        return assignments

    return install


DESIGNER = SimpleNamespace(id="designer", role="DESIGNER")
ADMIN = SimpleNamespace(id="boss", role="ADMIN")


@pytest.mark.anyio
async def test_a_curated_roster_this_account_is_not_on_is_the_only_thing_excluded(table):
    table(
        [
            row("someone-elses", "other", assigned_by="boss"),
            row("mine", "designer", assigned_by="boss"),
            row("mine", "other", assigned_by="boss"),
        ]
    )
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["someone-elses"]


@pytest.mark.anyio
async def test_a_view_only_grant_on_a_curated_workshop_is_not_reachable_for_a_save(table):
    table(
        [
            row("view-only", "designer", assigned_by="boss", level="VIEW"),
            row("view-only", "other", assigned_by="boss"),
        ]
    )
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["view-only"]
    assert await workshop_access.unreachable_workshop_ids(DESIGNER, "CONTRIBUTE") == ["view-only"]
    assert await workshop_access.accessible_workshops_where(DESIGNER) == {"id": {"not_in": ["view-only"]}}


@pytest.mark.anyio
async def test_a_view_only_grant_on_an_UNCURATED_workshop_is_still_reachable(table):
    table([row("open", "designer", assigned_by=None, level="VIEW")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == []


@pytest.mark.anyio
async def test_a_level_above_the_minimum_satisfies_it(table):
    table([row("edit-grant", "designer", assigned_by="boss", level="EDIT"), row("edit-grant", "other", assigned_by="boss")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == []


@pytest.mark.anyio
async def test_a_caller_that_needs_edit_says_so_and_gets_the_narrower_set(table):
    table([row("contribute", "designer", assigned_by="boss", level="CONTRIBUTE"), row("contribute", "other", assigned_by="boss")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER, "EDIT") == ["contribute"]
    assert await workshop_access.unreachable_workshop_ids(DESIGNER, "VIEW") == []


@pytest.mark.anyio
async def test_an_admin_is_not_narrowed_by_a_level_either(table):
    table([row("curated", "boss", assigned_by="boss", level="VIEW")])
    assert await workshop_access.unreachable_workshop_ids(ADMIN, "EDIT") == []


@pytest.mark.anyio
async def test_a_workshop_with_no_assignment_rows_at_all_is_never_excluded(table):
    table([row("curated", "other", assigned_by="boss")])
    blocked = await workshop_access.unreachable_workshop_ids(DESIGNER)
    assert "legacy-with-no-rows" not in blocked
    assert blocked == ["curated"]


@pytest.mark.anyio
async def test_the_four_shapes_of_row_that_do_not_curate_a_workshop_leave_it_open(table):
    table(
        [
            row("asked-only", "other", status="PENDING"),
            row("refused", "other", status="DENIED"),
            row("withdrawn", "other", status="REVOKED"),
            row("self-approved", "other", status="GRANTED", assigned_by=None),
        ]
    )
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == []


@pytest.mark.anyio
async def test_a_pending_request_of_my_own_does_not_open_a_curated_workshop(table):
    table([row("curated", "other", assigned_by="boss"), row("curated", "designer", status="PENDING")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["curated"]


@pytest.mark.anyio
async def test_a_revoked_row_of_my_own_does_not_keep_a_curated_workshop_open(table):
    table([row("curated", "other", assigned_by="boss"), row("curated", "designer", status="REVOKED")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["curated"]


@pytest.mark.anyio
async def test_an_admin_is_never_narrowed(table):
    assignments = table([row("curated", "other", assigned_by="boss")])
    assert await workshop_access.unreachable_workshop_ids(ADMIN) == []
    assert await workshop_access.accessible_workshops_where(ADMIN) is None
    assert assignments.queries == []


@pytest.mark.anyio
async def test_only_granted_rows_are_fetched(table):
    assignments = table([row("curated", "other", assigned_by="boss")])
    await workshop_access.unreachable_workshop_ids(DESIGNER)
    assert assignments.queries == [{"status": "GRANTED"}]


@pytest.mark.anyio
async def test_nothing_to_exclude_composes_as_no_clause_at_all(table):
    table([])
    assert await workshop_access.accessible_workshops_where(DESIGNER) is None


@pytest.mark.anyio
async def test_the_clause_excludes_by_id_and_is_sorted(table):
    table(
        [
            row("zeta", "other", assigned_by="boss"),
            row("alpha", "other", assigned_by="boss"),
        ]
    )
    assert await workshop_access.accessible_workshops_where(DESIGNER) == {"id": {"not_in": ["alpha", "zeta"]}}
