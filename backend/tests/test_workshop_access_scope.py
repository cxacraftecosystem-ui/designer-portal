"""A workshop picker must offer only the workshops the caller may actually file against.

THE OWNER'S REQUIREMENT, AND WHY IT NEEDED A NEW PREDICATE RATHER THAN A WIDER FILTER. "The designers
should only be able to see the workshops in the dropdown that they have access to" was not true of any
list route: ``records.viewable_where`` returns ``{}`` for every signed-in account on purpose — reading
the repository is open — so ``GET /workshops`` served the whole table to a DESIGNER and the record form
learned which rows were somebody else's only from a warning printed AFTER one was picked. Worse, that
warning is silent for the ordinary case: on an uncurated workshop everybody implicitly holds
CONTRIBUTE, so ``canSubmit`` is true and there is nothing to warn about.

WHAT THE FIX MAY NOT DO IS INVERT THE OPEN-READ POLICY, which is why the narrowing is an opt-in
parameter on the route and lives in ``workshop_access`` rather than in ``viewable_where``. The tests
below are all about the EDGE OF THE SET, because that is where this can be wrong in the direction that
locks people out of workshops that were always open to them — the failure
``services/workshop_access``'s module docstring is written against.

No database. ``workshop_access`` binds ``db`` at import, so the fake assignment table is patched onto
the module itself; every rule under test is pure logic over the rows it reads.
"""

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
    """One WorkshopAssignment row, with only the five columns the rule reads.

    ``level`` DEFAULTS TO THE DATABASE'S OWN DEFAULT ("CONTRIBUTE", ``schema.prisma``), which is also
    what the pre-levels ``PUT /assignments`` effectively granted and what ``DEFAULT_GRANT_LEVEL``
    hands out when an admin names no level. A fixture defaulting to anything else would test rows the
    table cannot hold, and a fixture OMITTING the column would test something worse: ``get_value``
    answers None for a missing attribute and ``level_at_least(None, …)`` is False, so every row would
    silently read as unusable and every assertion here would pass for the wrong reason.
    """
    return SimpleNamespace(
        workshopId=workshop_id, userId=user_id, status=status, assignedById=assigned_by, accessLevel=level
    )


class _Assignments:
    """``find_many`` over a fixed list, honouring the one equality filter the caller passes."""

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
            # Curated — an admin put somebody on it — and that somebody is not the designer.
            row("someone-elses", "other", assigned_by="boss"),
            # Curated, and the designer IS on it.
            row("mine", "designer", assigned_by="boss"),
            row("mine", "other", assigned_by="boss"),
        ]
    )
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["someone-elses"]


@pytest.mark.anyio
async def test_a_view_only_grant_on_a_curated_workshop_is_not_reachable_for_a_save(table):
    """THE 403 THIS WHOLE NARROWING EXISTS TO REMOVE, in the one shape membership cannot see.

    A designer with ``status="GRANTED", accessLevel="VIEW"`` on a curated workshop is ON the roster,
    so the first version of this rule kept the workshop in the list — and
    ``enforce_workshop_submission(..., need="CONTRIBUTE")`` then refused the save with "your access to
    this workshop is view-only, which does not allow this", after the researcher had filled in a whole
    record. The list and the write gate have to name the same level or the picker is offering rows the
    API will refuse, which is the exact thing it was built to stop offering.
    """
    table(
        [
            row("view-only", "designer", assigned_by="boss", level="VIEW"),
            row("view-only", "other", assigned_by="boss"),
        ]
    )
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["view-only"]
    # And the default is CONTRIBUTE rather than something the caller must remember to pass: the
    # picker is the caller that exists, and a default of VIEW would have been the old bug with a
    # parameter in front of it.
    assert await workshop_access.unreachable_workshop_ids(DESIGNER, "CONTRIBUTE") == ["view-only"]
    assert await workshop_access.accessible_workshops_where(DESIGNER) == {"id": {"not_in": ["view-only"]}}


@pytest.mark.anyio
async def test_a_view_only_grant_on_an_UNCURATED_workshop_is_still_reachable(table):
    """The other direction, and the one that would lock people out if the level were read first.

    On an uncurated workshop every signed-in account implicitly holds CONTRIBUTE and an explicit
    grant can only RAISE that (``resolve_workshop_access``): "somebody granted VIEW on a workshop the
    whole team can already write to is not demoted for having asked". So a VIEW row here must not
    exclude anything — ``workshop_is_curated`` decides first and the level is only consulted on the
    curated rosters.
    """
    table([row("open", "designer", assigned_by=None, level="VIEW")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == []


@pytest.mark.anyio
async def test_a_level_above_the_minimum_satisfies_it(table):
    """EDIT is CONTRIBUTE and more. The check is a rank comparison, not equality — pinned because
    ``accessLevel == minimum`` is the shape this would most plausibly be rewritten into."""
    table([row("edit-grant", "designer", assigned_by="boss", level="EDIT"), row("edit-grant", "other", assigned_by="boss")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == []


@pytest.mark.anyio
async def test_a_caller_that_needs_edit_says_so_and_gets_the_narrower_set(table):
    """The parameter is what keeps this one function honest for more than one question.

    A control offering "change somebody else's record in this workshop" needs EDIT, and a CONTRIBUTE
    grant does not satisfy it. Asking for VIEW gives back the plain membership answer, which is the
    right answer to "may I read it" and the wrong one for a picker.
    """
    table([row("contribute", "designer", assigned_by="boss", level="CONTRIBUTE"), row("contribute", "other", assigned_by="boss")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER, "EDIT") == ["contribute"]
    assert await workshop_access.unreachable_workshop_ids(DESIGNER, "VIEW") == []


@pytest.mark.anyio
async def test_an_admin_is_not_narrowed_by_a_level_either(table):
    """They resolve to EDIT on every workshop, so no ``minimum`` may reintroduce a narrowing for them
    — including one they hold a deliberately low row on, which is a real shape: an admin can be put
    on a roster at VIEW like anybody else."""
    table([row("curated", "boss", assigned_by="boss", level="VIEW")])
    assert await workshop_access.unreachable_workshop_ids(ADMIN, "EDIT") == []


@pytest.mark.anyio
async def test_a_workshop_with_no_assignment_rows_at_all_is_never_excluded(table):
    """Every legacy workshop. It has no rows, so it cannot appear in a set derived from rows.

    This is the reason the function returns an EXCLUSION list rather than an inclusion list, and it is
    the assertion that pins it: an inclusion list assembled from assignment rows would leave every
    uncurated workshop out and lock the whole repository behind a roster nobody has written.
    """
    table([row("curated", "other", assigned_by="boss")])
    blocked = await workshop_access.unreachable_workshop_ids(DESIGNER)
    assert "legacy-with-no-rows" not in blocked
    assert blocked == ["curated"]


@pytest.mark.anyio
async def test_the_four_shapes_of_row_that_do_not_curate_a_workshop_leave_it_open(table):
    """PENDING, DENIED, REVOKED, and an approved self-request. All four stay open to everybody.

    ``workshop_is_curated`` owns this rule and the module docstring enumerates the exceptions; what is
    tested here is that the scope goes through that one implementation rather than restating it. A
    second copy expressed as a Prisma predicate is what would drift.
    """
    table(
        [
            row("asked-only", "other", status="PENDING"),
            row("refused", "other", status="DENIED"),
            row("withdrawn", "other", status="REVOKED"),
            # `decide()` records `decidedById` and deliberately NOT `assignedById`, so approving one
            # person's request must not 403 a team that was already working in that workshop.
            row("self-approved", "other", status="GRANTED", assigned_by=None),
        ]
    )
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == []


@pytest.mark.anyio
async def test_a_pending_request_of_my_own_does_not_open_a_curated_workshop(table):
    """The other direction of the same rule: asking is not access.

    A PENDING row is the shape a user creates for themselves, so if it counted the scope could be
    widened by the very account it is meant to narrow.
    """
    table([row("curated", "other", assigned_by="boss"), row("curated", "designer", status="PENDING")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["curated"]


@pytest.mark.anyio
async def test_a_revoked_row_of_my_own_does_not_keep_a_curated_workshop_open(table):
    """Revoked rows are KEPT rather than deleted, so the status has to be what decides."""
    table([row("curated", "other", assigned_by="boss"), row("curated", "designer", status="REVOKED")])
    assert await workshop_access.unreachable_workshop_ids(DESIGNER) == ["curated"]


@pytest.mark.anyio
async def test_an_admin_is_never_narrowed(table):
    """They are the approval authority. A list that hid a workshop from the person who grants access
    to it would make that workshop un-administrable — and it would do it silently, from a picker."""
    assignments = table([row("curated", "other", assigned_by="boss")])
    assert await workshop_access.unreachable_workshop_ids(ADMIN) == []
    assert await workshop_access.accessible_workshops_where(ADMIN) is None
    # And it costs no query at all: the admin branch returns before the table is read.
    assert assignments.queries == []


@pytest.mark.anyio
async def test_only_granted_rows_are_fetched(table):
    """The one query is over GRANTED rows, because nothing else can change either answer.

    Pinned because widening it is the tempting "just in case" edit, and the volume of this table is the
    only reason a whole-table read is acceptable here at all.
    """
    assignments = table([row("curated", "other", assigned_by="boss")])
    await workshop_access.unreachable_workshop_ids(DESIGNER)
    assert assignments.queries == [{"status": "GRANTED"}]


@pytest.mark.anyio
async def test_nothing_to_exclude_composes_as_no_clause_at_all(table):
    """``None`` and not ``{"id": {"not_in": []}}``.

    The route composes with ``if scope:``, so a falsy answer leaves the query byte-for-byte what it was
    before this existed — and an empty ``not_in`` is a clause a future Prisma version is entitled to
    read either way.
    """
    table([])
    assert await workshop_access.accessible_workshops_where(DESIGNER) is None


@pytest.mark.anyio
async def test_the_clause_excludes_by_id_and_is_sorted(table):
    """Sorted so the emitted SQL is stable — a query that differs only in the order of an IN list is a
    query no cache and no log reader can recognise as the same one."""
    table(
        [
            row("zeta", "other", assigned_by="boss"),
            row("alpha", "other", assigned_by="boss"),
        ]
    )
    assert await workshop_access.accessible_workshops_where(DESIGNER) == {"id": {"not_in": ["alpha", "zeta"]}}
