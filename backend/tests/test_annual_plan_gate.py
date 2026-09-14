"""Who may open the ministry's annual plan — and the two ways that rule gets quietly broken.

THE FIRST WAY IS "SIMPLIFYING" IT TO ``require_admin``. That passes every test written against an
ADMIN fixture and locks ``MINISTRY_ADMIN`` — the feature's only real audience — out of their own
ministry's directory, behind a 403 that reads like a bug. ``is_admin`` is SET membership
(``{"ADMIN", "MASTER_ADMIN"}``) and this gate is a RANK FLOOR; the two are different shapes, and
``test_the_gate_is_a_rank_floor_and_not_the_admin_set`` is the assertion that says so.

THE SECOND WAY IS WIDENING IT DOWNWARD. Regional Director (45) and Assistant Director (42) sit just
below the floor and are the obvious "of course they should see the plan" edit. They are outside it
because this table has NO per-region column an edit could be narrowed to: a regional director given
write access has the whole national plan, not their own region of it. If regional editing is ever
wanted it is a scope table, not a rank change.

No database, no network.
"""

import inspect

import pytest

from app.api.routes import annual_plan as annual_plan_routes
from app.core import deps
from app.services import annual_plan

FLOOR = 48


def _user(role: str):
    class _U:
        pass

    u = _U()
    u.role = role
    u.id = f"id-{role}"
    return u


@pytest.mark.parametrize("role", ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"])
def test_the_ministry_administrator_and_above_manage_the_plan(role: str):
    """The floor admits the tier it was written for, and everybody above it."""
    assert annual_plan.can_manage_annual_plan(_user(role)) is True


@pytest.mark.parametrize(
    "role", sorted(r for r, rank in deps.ROLE_RANK.items() if rank < FLOOR)
)
def test_every_tier_below_the_ministry_administrator_is_refused(role: str):
    """DERIVED FROM ``ROLE_RANK``, so a tier added later is covered without anybody remembering.

    A hand-written list of the roles below the floor is a list that goes stale the day the ladder
    grows — and the failure mode of a stale list here is a new tier silently admitted, because a
    predicate nobody asserted about is a predicate nobody notices.
    """
    assert annual_plan.can_manage_annual_plan(_user(role)) is False


def test_a_regional_director_is_refused_and_that_is_the_decision():
    """Not an oversight, and written out here so the next reader does not "fix" it.

    The annual plan is a NATIONAL instrument issued once a year. A regional director correcting the
    row for their own state would be correcting a document they did not issue — and this table
    carries no column that could narrow such an edit to one region, so the rank change that admits
    them admits them to all of it.
    """
    assert annual_plan.can_manage_annual_plan(_user("REGIONAL_DIRECTOR")) is False
    assert annual_plan.can_manage_annual_plan(_user("ASSISTANT_DIRECTOR")) is False
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] < FLOOR <= deps.ROLE_RANK["MINISTRY_ADMIN"]


def test_the_gate_is_a_rank_floor_and_not_the_admin_set():
    """The exact confusion ``require_admin`` would have introduced, stated as one assertion."""
    ministry = _user("MINISTRY_ADMIN")
    assert annual_plan.can_manage_annual_plan(ministry) is True
    assert deps.is_admin(ministry) is False


def test_a_user_with_no_role_at_all_is_refused():
    """``None`` and a missing attribute both fail closed. An unauthenticated or half-built principal
    must never be the one that gets through — ``has_rank`` answers False for an unknown role."""
    assert annual_plan.can_manage_annual_plan(None) is False
    assert annual_plan.can_manage_annual_plan(_user("NOT_A_ROLE")) is False


def test_the_refusal_is_one_sentence_that_names_the_tier():
    """Owner's standing instruction: state the fact, name the action, stay short and professional.

    It also has to name WHO can do it, because the only useful next move for the person reading it
    is to go and ask that person.
    """
    detail = annual_plan.ANNUAL_PLAN_REFUSAL
    assert len(detail) < 200
    assert detail.count(".") <= 2
    assert "\n" not in detail
    assert "ministry administrator" in detail.lower()


def test_the_dependency_is_async_like_every_other_require_in_this_product():
    """``require_admin``, ``require_access_manager`` and ``require_sanction_recorder`` are all
    ``async def``. A sync dependency works — FastAPI runs it in a threadpool — but it would be the
    only one, and a source-reading sweep that greps for ``async def require_`` would miss it."""
    assert inspect.iscoroutinefunction(annual_plan_routes.require_annual_plan_manager)


def test_the_refusal_sentence_is_imported_and_not_retyped_at_the_door():
    """One sentence, three surfaces. The 403, the web route guard's lock panel and the absent nav
    entry all state one rule, and a refusal that names a different next move depending on where you
    met it is not a rule, it is three rumours."""
    source = inspect.getsource(annual_plan_routes.require_annual_plan_manager)
    assert "ANNUAL_PLAN_REFUSAL" in source
    assert "ministry administrator" not in source.split('"""')[-1]


def test_the_gate_lives_in_the_service_and_the_route_only_raises_it():
    """WHERE THE PREDICATE LIVES IS RECORDED, because it is not where the reader will look.

    Every other ``can_*`` in this product is in ``app/core/deps.py``. This one is in
    ``app/services/annual_plan.py`` because ``deps.py`` was owned by another change in flight when
    this landed — the same reason ``sanction_orders.can_record_sanction_orders`` is where it is.
    This test is the marker that the position is known and deliberate; moving both predicates into
    ``deps.py`` should delete it.
    """
    assert annual_plan.can_manage_annual_plan.__module__ == "app.services.annual_plan"
    assert not hasattr(deps, "can_manage_annual_plan")
