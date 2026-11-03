import inspect
import re
from pathlib import Path

from app.api.routes import design_workshops as dw_routes
from app.services import annual_plan, design_workshops, sanction_orders

APP = Path(design_workshops.__file__).resolve().parents[1]

KNOWN_CREATE_SITES = {
    "services/design_workshops.py": "the shared opener, `open_design_workshop`",
    "api/routes/design_workshops.py": "the ordinary create route — OWED: re-point at the opener",
    "services/sanction_orders.py": "deliberate: the create is inside its own seven-row transaction",
}


def _create_sites() -> dict[str, int]:
    found: dict[str, int] = {}
    for path in APP.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        hits = len(re.findall(r"\bdesignworkshop\.create\(", text))
        if hits:
            found[path.relative_to(APP).as_posix()] = hits
    return found


def test_every_design_workshop_creation_site_is_one_this_file_knows_about():
    sites = _create_sites()
    assert set(sites) == set(KNOWN_CREATE_SITES), (
        f"unexpected DesignWorkshop creation sites: {sorted(set(sites) - set(KNOWN_CREATE_SITES))}; "
        f"missing: {sorted(set(KNOWN_CREATE_SITES) - set(sites))}"
    )


def test_the_shared_opener_is_the_only_creation_site_in_the_services_layer_that_is_not_argued():
    assert "tx.designworkshop.create(" in inspect.getsource(sanction_orders.create_from_sanction)
    assert "sanction_orders" in inspect.getsource(design_workshops.open_design_workshop)


def test_the_promote_door_calls_the_shared_opener_rather_than_creating_a_workshop_itself():
    source = inspect.getsource(annual_plan.promote_entry)
    assert "open_design_workshop(" in source
    assert "designworkshop.create(" not in source


def test_the_shared_opener_seeds_before_it_returns():
    source = inspect.getsource(design_workshops.open_design_workshop)
    assert "assert_every_designer_may_be_named(" in source
    assert "db.designworkshop.create(" in source
    assert "attach_the_named_designers(" in source
    assert "seed_designer_prefill(" in source


def test_the_shared_opener_asks_eligibility_above_the_create():
    source = inspect.getsource(design_workshops.open_design_workshop)
    assert source.index("assert_every_designer_may_be_named(") < source.index(
        "db.designworkshop.create("
    )


def test_the_shared_opener_does_not_gate():
    body = inspect.getsource(design_workshops.open_design_workshop).split('"""')[-1]
    assert "assert_can_create_design_workshops" not in body
    assert "require_annual_plan_manager" not in body
    assert "can_manage_annual_plan" not in body


def test_the_shared_opener_is_not_wrapped_in_a_transaction():
    body = inspect.getsource(design_workshops.open_design_workshop).split('"""')[-1]
    assert "db.tx(" not in body


def test_the_create_route_still_carries_its_own_gate_spelled_out_in_its_own_body():
    source = inspect.getsource(dw_routes.create_design_workshop)
    assert "assert_can_create_design_workshops(current_user)" in source
    assert "_require_designer(current_user)" not in source
