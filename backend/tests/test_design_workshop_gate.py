import pytest
from fastapi import HTTPException

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes.design_workshops import _require_designer
from app.core.deps import (
    DESIGN_WORKSHOP_CREATE_REFUSAL,
    DESIGN_WORKSHOP_CREATOR_ROLES,
    DESIGN_WORKSHOP_ROLES,
    assert_can_create_design_workshops,
    can_create_design_workshops,
    can_run_design_workshops,
)


class _User:
    def __init__(self, role: str) -> None:
        self.role = role
        self.id = "u1"


REFUSED = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR")
ALLOWED = ("DESIGNER", "ADMIN", "MASTER_ADMIN")

MAY_CREATE = ("ADMIN", "MASTER_ADMIN")
MAY_NOT_CREATE = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "PROFESSOR")



@pytest.mark.parametrize("role", ALLOWED)
def test_the_designer_set_may_run_a_workshop(role):
    assert can_run_design_workshops(_User(role)) is True
    _require_designer(_User(role))


@pytest.mark.parametrize("role", REFUSED)
def test_everyone_else_is_refused_by_the_ROUTE_and_not_only_by_the_menu(role):
    assert can_run_design_workshops(_User(role)) is False
    with pytest.raises(HTTPException) as raised:
        _require_designer(_User(role))
    assert raised.value.status_code == 403



@pytest.mark.parametrize("role", MAY_CREATE)
def test_an_admin_and_a_master_admin_may_start_a_workshop(role):
    assert can_create_design_workshops(_User(role)) is True
    assert_can_create_design_workshops(_User(role))


@pytest.mark.parametrize("role", MAY_NOT_CREATE)
def test_nobody_else_may_start_one_and_a_DESIGNER_is_the_point(role):
    assert can_create_design_workshops(_User(role)) is False
    with pytest.raises(HTTPException) as raised:
        assert_can_create_design_workshops(_User(role))
    assert raised.value.status_code == 403


def test_the_refusal_names_who_can_create_one_and_what_to_do_instead():
    with pytest.raises(HTTPException) as raised:
        assert_can_create_design_workshops(_User("DESIGNER"))
    detail = raised.value.detail
    assert detail == DESIGN_WORKSHOP_CREATE_REFUSAL
    lowered = detail.lower()
    assert "admin" in lowered, "the refusal must name WHO can create a workshop"
    assert "ask an admin" in lowered, "the refusal must name the next move, not just the rule"
    assert "22 stages" in lowered or "22 stages" in detail
    assert "report" in lowered


def test_creating_is_strictly_narrower_than_running():
    assert DESIGN_WORKSHOP_CREATOR_ROLES < DESIGN_WORKSHOP_ROLES
    assert "DESIGNER" in DESIGN_WORKSHOP_ROLES
    assert "DESIGNER" not in DESIGN_WORKSHOP_CREATOR_ROLES


def test_the_create_route_carries_the_create_gate_and_only_that_one():
    import inspect

    from app.api.routes import design_workshops as module

    source = inspect.getsource(module.create_design_workshop)
    assert "assert_can_create_design_workshops(current_user)" in source, (
        "POST /design-workshops does not enforce who may START a workshop, so the browser is the "
        "only thing stopping a designer — and the API and the Android client ignore the browser"
    )
    assert "_require_designer(current_user)" not in source, (
        "the create route has had the designer gate put back on it; a designer passes that gate, "
        "so the rule that only admins may start a workshop is no longer enforced"
    )



UNGATED_WRITES = {
    ("POST", ""),
    ("DELETE", "/{workshop_id}"),
    ("POST", "/{workshop_id}/restore"),
    ("POST", "/{workshop_id}/report"),
    ("POST", "/dictate"),
    ("POST", "/{workshop_id}/exports"),
}


def _router_routes():
    import re
    from pathlib import Path

    source = (
        Path(__file__).resolve().parents[1] / "app/api/routes/design_workshops.py"
    ).read_text(encoding="utf-8")
    lines = source.splitlines()
    found = [
        (i, m.group(1).upper(), m.group(2))
        for i, line in enumerate(lines)
        if (m := re.match(r'@router\.(get|post|put|patch|delete)\("([^"]*)"', line.strip()))
    ]
    bounds = [i for i, _, _ in found] + [len(lines)]
    return [
        (verb, path, "\n".join(lines[start:bounds[n + 1]]))
        for n, (start, verb, path) in enumerate(found)
    ], source


def test_the_designer_gate_still_stands_where_this_docstring_says_it_does():
    routes, source = _router_routes()
    writes = [(verb, path, body) for verb, path, body in routes if verb != "GET"]

    direct = source.count("_require_designer(current_user)")
    assert direct == 15, (
        f"_require_designer has {direct} direct call sites, not the fifteen its docstring "
        f"enumerates — add or remove the route there too"
    )

    gated = {(v, p) for v, p, body in writes if "_require_designer(current_user)" in body}
    via_verb_gate = {(v, p) for v, p, body in writes if "_verb_gate(" in body} - gated
    ungated = {(v, p) for v, p, _ in writes} - gated - via_verb_gate

    assert (len(writes), len(gated), len(via_verb_gate)) == (23, 12, 5), (
        f"{len(writes)} non-GET routes, {len(gated)} gated directly and {len(via_verb_gate)} behind "
        f"_verb_gate — _require_designer's docstring says 23, 12 and 5"
    )
    assert ungated == UNGATED_WRITES, (
        "the set of writes OUTSIDE the designer set changed. A new one is a permission decision, "
        "not a refactor: name it in _require_designer's 'WHAT IT IS NOT' section and here"
    )



def test_a_designer_keeps_every_other_capability_inside_a_workshop():
    designer = _User("DESIGNER")
    assert can_run_design_workshops(designer) is True
    _require_designer(designer)

    import inspect

    from app.api.routes import design_workshops as module

    for name in ("update_design_workshop", "save_stage_data"):
        source = inspect.getsource(getattr(module, name))
        assert "_require_designer(current_user)" in source, (
            f"{name} no longer enforces who may run a design workshop"
        )
        assert "assert_can_create_design_workshops" not in source, (
            f"{name} has picked up the create-only gate, so a designer can no longer do the work "
            f"the requirement explicitly left them"
        )


def test_the_two_surfaces_declare_the_same_set():
    import re
    from pathlib import Path

    web = Path(__file__).resolve().parents[2] / "frontend/lib/permissions.ts"
    if not web.is_file():
        pytest.skip("the frontend is not present in this checkout")
    text = web.read_text(encoding="utf-8")

    running = re.search(r"DESIGN_WORKSHOP_ROLES\s*:[^=]*=\s*\[([^\]]*)\]", text)
    assert running, (
        "frontend/lib/permissions.ts has no `DESIGN_WORKSHOP_ROLES = [...]` declaration, so there is "
        "nothing to compare the server's running set against"
    )
    members = set(re.findall(r'"([A-Z_]+)"', re.sub(r"//[^\n]*", "", running.group(1))))
    assert members == set(DESIGN_WORKSHOP_ROLES), (
        f"the web runs a design workshop for {sorted(members)} and the server for "
        f"{sorted(DESIGN_WORKSHOP_ROLES)} — one of them offers what the other refuses"
    )

    assert "DESIGN_WORKSHOP_CREATOR_ROLES" in text, (
        "the web does not declare who may CREATE a workshop, so its create control is guessing"
    )
    declaration = re.search(
        r"DESIGN_WORKSHOP_CREATOR_ROLES\s*:[^=]*=\s*\[([^\]]*)\]", text
    )
    assert declaration, (
        "frontend/lib/permissions.ts has no `DESIGN_WORKSHOP_CREATOR_ROLES = [...]` declaration, so "
        "there is nothing to compare the server's create set against"
    )
    creator_decl = declaration.group(1)
    for role in DESIGN_WORKSHOP_CREATOR_ROLES:
        assert f'"{role}"' in creator_decl, f"{role} may create on the server but not on the web"
    assert '"DESIGNER"' not in creator_decl, (
        "the web admits DESIGNER to the create set where the server does not — a designer would be "
        "shown a create form, fill it in, and be refused by the API"
    )
