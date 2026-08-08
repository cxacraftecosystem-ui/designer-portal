"""Who may run a design & prototype workshop — enforced by the API, not only by the browser.

THE GAP THIS CLOSES was found by an audit, not by a test, and it is the same shape as the one it
was supposed to have prevented.

`frontend/lib/permissions.ts` hides the design-workshop pages from everyone outside
`DESIGN_WORKSHOP_ROLES` (Designer, Admin, Master Admin — deliberately excluding Professor). But the
write routes were gated only by `assert_can_create_records`, which is Researcher-and-above. So a
RESEARCHER or a PROFESSOR could create a workshop, edit it, write its 22 stages and generate the
report submitted to a ministry — while the app showed them no way in. A UI guard over an open route
hides the link and leaves the URL, the API and the Android client wide open, which is precisely
what `require_designer` was added to the profile routes to end. The profile got fixed; this did not.

These tests are unit-level on the predicate and the route dependency, so they need no database.
"""

import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes.design_workshops import _require_designer
from app.core.deps import DESIGN_WORKSHOP_ROLES, can_run_design_workshops


class _User:
    def __init__(self, role: str) -> None:
        self.role = role
        self.id = "u1"


REFUSED = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR")
ALLOWED = ("DESIGNER", "ADMIN", "MASTER_ADMIN")


@pytest.mark.parametrize("role", ALLOWED)
def test_the_designer_set_may_run_a_workshop(role):
    assert can_run_design_workshops(_User(role)) is True
    _require_designer(_User(role))   # must not raise


@pytest.mark.parametrize("role", REFUSED)
def test_everyone_else_is_refused_by_the_ROUTE_and_not_only_by_the_menu(role):
    """PROFESSOR is in this list on purpose — see `deps.can_run_design_workshops`.

    It is the one capability in that module which is a SET rather than a rank threshold, so a
    professor cannot run a design workshop even though they outrank a designer. If that is ever
    changed, change the SET; this test follows it.
    """
    assert can_run_design_workshops(_User(role)) is False
    with pytest.raises(HTTPException) as raised:
        _require_designer(_User(role))
    assert raised.value.status_code == 403


def test_every_write_route_carries_the_gate():
    """The gate has to be ON the routes, not merely available to them.

    Read from the SOURCE rather than by calling them, because calling each one needs a database and
    a workshop; what this is defending against is somebody adding a sixth write route and forgetting
    the line, which source inspection catches and a per-route integration test would not.
    """
    import inspect

    from app.api.routes import design_workshops as module

    for name in ("create_design_workshop", "update_design_workshop", "save_stage_data"):
        source = inspect.getsource(getattr(module, name))
        assert "_require_designer(current_user)" in source, (
            f"{name} does not enforce who may run a design workshop, so the browser is the only "
            f"thing stopping a researcher — and the API and the Android client ignore the browser"
        )


def test_the_two_surfaces_declare_the_same_set():
    """`frontend/lib/permissions.ts` carries the identical set and must keep carrying it."""
    from pathlib import Path

    web = Path(__file__).resolve().parents[2] / "frontend/lib/permissions.ts"
    if not web.is_file():
        pytest.skip("the frontend is not present in this checkout")
    text = web.read_text(encoding="utf-8")
    for role in DESIGN_WORKSHOP_ROLES:
        assert f'"{role}"' in text, f"{role} is in the server's set but not the web's"
    assert '"PROFESSOR"' not in text.split("DESIGN_WORKSHOP_ROLES")[1][:200], (
        "the web admits PROFESSOR where the server does not"
    )
