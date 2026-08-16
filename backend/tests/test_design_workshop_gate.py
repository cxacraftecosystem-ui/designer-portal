"""Who may run a design & prototype workshop, and — separately — who may START one.

TWO RULES LIVE IN THIS FILE AND THEY ARE NOT THE SAME RULE. Reading them as one is the mistake
these tests exist to make impossible.

1. RUNNING one — opening it, filling its 22 stages, creating records inside it, capturing
   photographs and dictation, generating the report — is `can_run_design_workshops`, the SET
   {DESIGNER, ADMIN, MASTER_ADMIN}. Unchanged, and half of this file is here to keep it that way.

   THE GAP THAT RULE CLOSED was found by an audit, not by a test. `frontend/lib/permissions.ts`
   hid the design-workshop pages from everyone outside the set, but the write routes were gated
   only by `assert_can_create_records`, which is Researcher-and-above — so a RESEARCHER or a
   PROFESSOR could edit a workshop, write its 22 stages and generate the report submitted to a
   ministry, while the app showed them no way in. A UI guard over an open route hides the link and
   leaves the URL, the API and the Android client wide open.

2. STARTING a new one is `can_create_design_workshops`, {ADMIN, MASTER_ADMIN} — STRICTLY NARROWER,
   and the only gate on this surface that refuses a designer anything.

   THE REQUIREMENT, VERBATIM: "designers cannot create workshops (only admins/master admins can) —
   designers create records under existing workshops." A workshop is not a record, it is the
   container a fortnight of records lives in and the unit the ministry indexes and funds, so
   opening one is an administrative act performed by whoever holds the sanction order.

   THE FAILURE MODE THIS RULE COULD EASILY HAVE CAUSED, and the reason
   `test_a_designer_keeps_every_other_capability_inside_a_workshop` is written the way it is: a
   narrowing applied one function too widely takes a designer's stage edits with it. That would
   cost a designer their fortnight of fieldwork, which is far worse than this rule is worth. Any
   change here that makes that test fail is wrong, whatever else it fixes.

These tests are unit-level on the predicates and on the route source, so they need no database.
"""

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

#: Who may bring a NEW workshop into existence, and who may not. DESIGNER is in the second list and
#: that is the whole point of this change; PROFESSOR is there for the older reason (the running set
#: is a SET, not a ladder, so a professor was never inside it either).
MAY_CREATE = ("ADMIN", "MASTER_ADMIN")
MAY_NOT_CREATE = ("CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "PROFESSOR")


# ── 1. Running a workshop: unchanged ──────────────────────────────────────────────────────────────


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


# ── 2. Starting a workshop: admins and the master admin only ─────────────────────────────────────


@pytest.mark.parametrize("role", MAY_CREATE)
def test_an_admin_and_a_master_admin_may_start_a_workshop(role):
    assert can_create_design_workshops(_User(role)) is True
    assert_can_create_design_workshops(_User(role))   # must not raise


@pytest.mark.parametrize("role", MAY_NOT_CREATE)
def test_nobody_else_may_start_one_and_a_DESIGNER_is_the_point(role):
    """A designer is refused at create — by the SERVER, not by a hidden button.

    DESIGNER is the role this test was written for. It is inside `DESIGN_WORKSHOP_ROLES`, so every
    other gate in `design_workshops.py` lets it through; this is the one that does not, and if it
    ever stops refusing, the requirement has been silently reverted while every other test in this
    file still passes.
    """
    assert can_create_design_workshops(_User(role)) is False
    with pytest.raises(HTTPException) as raised:
        assert_can_create_design_workshops(_User(role))
    assert raised.value.status_code == 403


def test_the_refusal_names_who_can_create_one_and_what_to_do_instead():
    """A refusal that only says "forbidden" tells a designer in a courtyard to stop working.

    The truth is far narrower than that — everything they came to do still works the moment an
    admin has opened the workshop — so the sentence has to carry three facts: who can create one,
    what to ask them for, and that the rest of the job is untouched. Asserted rather than trusted
    because refusal copy is the first thing to rot when a message is reworded in a hurry.
    """
    with pytest.raises(HTTPException) as raised:
        assert_can_create_design_workshops(_User("DESIGNER"))
    detail = raised.value.detail
    assert detail == DESIGN_WORKSHOP_CREATE_REFUSAL
    lowered = detail.lower()
    assert "admin" in lowered, "the refusal must name WHO can create a workshop"
    assert "ask an admin" in lowered, "the refusal must name the next move, not just the rule"
    # And it must not read as though the designer has lost the job itself.
    assert "22 stages" in lowered or "22 stages" in detail
    assert "report" in lowered


def test_creating_is_strictly_narrower_than_running():
    """The two sets are related by containment, and the containment is the design.

    If they ever became equal, or crossed, somebody would be able to create a workshop they cannot
    then open — or the create gate would have quietly been widened back to the designer set.
    """
    assert DESIGN_WORKSHOP_CREATOR_ROLES < DESIGN_WORKSHOP_ROLES
    assert "DESIGNER" in DESIGN_WORKSHOP_ROLES
    assert "DESIGNER" not in DESIGN_WORKSHOP_CREATOR_ROLES


def test_the_create_route_carries_the_create_gate_and_only_that_one():
    """The gate has to be ON the route, and the OLD gates have to be OFF it.

    Read from the SOURCE rather than by calling it, because calling it needs a database; what this
    defends against is somebody restoring `_require_designer` here "for symmetry" with the rest of
    the file, which would let a designer create a workshop again while every other assertion in
    this file still passed.

    THIS ASSERTION REPLACED ONE THAT DEMANDED THE OPPOSITE. The previous version of this test
    required `_require_designer(current_user)` in `create_design_workshop`, which pinned the rule
    that has now deliberately changed. It is rewritten rather than deleted so the file still says
    what the create route must carry — and says, here, why the old assertion went.
    """
    import inspect

    from app.api.routes import design_workshops as module

    source = inspect.getsource(module.create_design_workshop)
    assert "assert_can_create_design_workshops(current_user)" in source, (
        "POST /design-workshops does not enforce who may START a workshop, so the browser is the "
        "only thing stopping a designer — and the API and the Android client ignore the browser"
    )
    # `_require_designer` would be a WIDER gate reached first, and its 403 says "requires Designer
    # access or above" — which a designer HAS. Present, it would either admit them or refuse them
    # with a sentence that is untrue of them.
    assert "_require_designer(current_user)" not in source, (
        "the create route has had the designer gate put back on it; a designer passes that gate, "
        "so the rule that only admins may start a workshop is no longer enforced"
    )


# ── 3. The half that must NOT have moved ─────────────────────────────────────────────────────────


def test_a_designer_keeps_every_other_capability_inside_a_workshop():
    """A designer may still do everything except bring a new workshop into existence.

    THE WHOLE POINT OF THIS TEST. Narrowing "create" is one line; narrowing it in the wrong place
    silently takes a designer's stage saves, their photographs or their report with it, and the
    first person to find out is somebody two weeks into fieldwork. So the capability itself is
    asserted, and then every write route in the module is read to confirm it still asks the SET and
    not the admin gate.
    """
    designer = _User("DESIGNER")
    assert can_run_design_workshops(designer) is True
    _require_designer(designer)   # opening, editing, stages, capture, report — all this gate

    import inspect

    from app.api.routes import design_workshops as module

    # Every write route that is NOT the create. Each must still carry `_require_designer` and must
    # NOT have picked up the admin-only create gate.
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
    """`frontend/lib/permissions.ts` carries the identical sets and must keep carrying them.

    BOTH sets now, not one. The web enforces the create rule as well — early, so a designer finds
    out before filling 22 stages rather than at sync — and a web copy that admitted DESIGNER would
    hand a designer a create form the API refuses.
    """
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

    assert "DESIGN_WORKSHOP_CREATOR_ROLES" in text, (
        "the web does not declare who may CREATE a workshop, so its create control is guessing"
    )
    # THE **DECLARATION**, matched by regex rather than by splitting on the name. Splitting takes the
    # LAST occurrence, which is the reference inside `canCreateDesignWorkshops`, whose next 200
    # characters are the end of a function and contain no role at all — so the naive version of this
    # assertion failed against a perfectly correct file and would have "passed" again the moment
    # somebody reordered the module. The array literal is what has to be read, so it is what is
    # matched.
    import re

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
