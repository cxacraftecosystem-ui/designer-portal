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
    assert annual_plan.can_manage_annual_plan(_user(role)) is True


@pytest.mark.parametrize(
    "role", sorted(r for r, rank in deps.ROLE_RANK.items() if rank < FLOOR)
)
def test_every_tier_below_the_ministry_administrator_is_refused(role: str):
    assert annual_plan.can_manage_annual_plan(_user(role)) is False


def test_a_regional_director_is_refused_and_that_is_the_decision():
    assert annual_plan.can_manage_annual_plan(_user("REGIONAL_DIRECTOR")) is False
    assert annual_plan.can_manage_annual_plan(_user("ASSISTANT_DIRECTOR")) is False
    assert deps.ROLE_RANK["REGIONAL_DIRECTOR"] < FLOOR <= deps.ROLE_RANK["MINISTRY_ADMIN"]


def test_the_gate_is_a_rank_floor_and_not_the_admin_set():
    ministry = _user("MINISTRY_ADMIN")
    assert annual_plan.can_manage_annual_plan(ministry) is True
    assert deps.is_admin(ministry) is False


def test_a_user_with_no_role_at_all_is_refused():
    assert annual_plan.can_manage_annual_plan(None) is False
    assert annual_plan.can_manage_annual_plan(_user("NOT_A_ROLE")) is False


def test_the_refusal_is_one_sentence_that_names_the_tier():
    detail = annual_plan.ANNUAL_PLAN_REFUSAL
    assert len(detail) < 200
    assert detail.count(".") <= 2
    assert "\n" not in detail
    assert "ministry administrator" in detail.lower()


def test_the_dependency_is_async_like_every_other_require_in_this_product():
    assert inspect.iscoroutinefunction(annual_plan_routes.require_annual_plan_manager)


def test_the_refusal_sentence_is_imported_and_not_retyped_at_the_door():
    source = inspect.getsource(annual_plan_routes.require_annual_plan_manager)
    assert "ANNUAL_PLAN_REFUSAL" in source
    assert "ministry administrator" not in source.split('"""')[-1]


def test_the_gate_lives_in_the_service_and_the_route_only_raises_it():
    assert annual_plan.can_manage_annual_plan.__module__ == "app.services.annual_plan"
    assert not hasattr(deps, "can_manage_annual_plan")
