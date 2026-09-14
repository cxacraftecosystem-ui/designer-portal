"""How a ``DesignWorkshop`` row comes into existence — and the census that stops a fourth way.

══ THE DEFECT THIS FILE GUARDS, IN ONE PARAGRAPH ═════════════════════════════════════════════════

Opening a workshop is four steps: ask eligibility ABOVE the create, create, write the viewer rows,
then ``seed_designer_prefill``. Forgetting the fourth is not a visible failure. It leaves a workshop
whose state, district, craft, venue and dates are on the ROW with no stage entry behind them — and
the designer's FIRST stage-1 save nulls every one of them under a 200 reading "Stage saved".
Completeness does not move, nothing warns, and the workshop simply loses its header for the
fortnight of capture. ``seed_designer_prefill``'s own docstring sets that out at length.

So the number of places that call ``db.designworkshop.create`` is a number worth asserting.

══ WHAT IS TRUE TODAY, 2026-09-14, AND WHAT IS STILL OWED ════════════════════════════════════════

``services/design_workshops.open_design_workshop`` is the shared opener and does all four steps in
order. ``annual_plan.promote_entry`` calls it — that is the convergence the annual-plan directory was
required to make, and it made it.

THREE CALL SITES REMAIN AND THE CENSUS BELOW NAMES ALL THREE:

1. ``services/design_workshops.open_design_workshop`` — the shared opener itself.
2. ``api/routes/design_workshops.create_design_workshop`` — the ordinary "new workshop" button. It
   still inlines the four steps. Re-pointing it at the opener is a pure deletion (its ``seeded``
   dict and its column loop are already the opener's arguments) and is OWED; it was not done in the
   change that added the opener because that route module was owned by another change in flight.
3. ``services/sanction_orders.create_from_sanction`` — and this one is a DELIBERATE exception, not a
   debt. It writes its workshop with ``tx.designworkshop.create`` inside a seven-row transaction
   (allow-list row, empanelment, account, profile, workshop, viewer row, sanction order), and the
   opener is explicitly not transactional: ``attach_the_named_designers`` argues at length why the
   viewer rows are not in a transaction with the create, and ``seed_designer_prefill`` swallows its
   own failure on purpose. Folding that path into the opener would silently reverse both decisions.
   It does call ``seed_designer_prefill``, outside its transaction, for the stated reason.

THE TEST IS A CENSUS AND NOT A COUNT OF ONE, because a count of one would have to fail today and a
failing test is a test somebody deletes. A FOURTH site — a bulk promote, a template clone, a
"duplicate this workshop" action — fails this file, which is the moment to make it call the opener.
"""

import inspect
import re
from pathlib import Path

from app.api.routes import design_workshops as dw_routes
from app.services import annual_plan, design_workshops, sanction_orders

APP = Path(design_workshops.__file__).resolve().parents[1]

#: Every module allowed to create a ``DesignWorkshop`` row, and why. Adding a row here is a decision
#: to be argued in review, which is the whole point of the file being a census.
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
    """THE CENSUS. A fourth site fails here, which is the moment to call the opener instead.

    A copied create is a copied chance to forget ``seed_designer_prefill``, and the symptom of
    forgetting it appears days later as a workshop that lost its header — with every automatic check
    agreeing the outcome was fine.
    """
    sites = _create_sites()
    assert set(sites) == set(KNOWN_CREATE_SITES), (
        f"unexpected DesignWorkshop creation sites: {sorted(set(sites) - set(KNOWN_CREATE_SITES))}; "
        f"missing: {sorted(set(KNOWN_CREATE_SITES) - set(sites))}"
    )


def test_the_shared_opener_is_the_only_creation_site_in_the_services_layer_that_is_not_argued():
    """The two non-opener sites each carry their reason IN THEIR OWN SOURCE, not only here.

    A back-reference that lives only in a test is a back-reference the person editing the code will
    not meet. The sanction register's create is inside a transaction and says so; the ordinary route
    is the one still owed a rewire and the opener's docstring names it.
    """
    assert "tx.designworkshop.create(" in inspect.getsource(sanction_orders.create_from_sanction)
    assert "sanction_orders" in inspect.getsource(design_workshops.open_design_workshop)


def test_the_promote_door_calls_the_shared_opener_rather_than_creating_a_workshop_itself():
    """THE CONVERGENCE ASSERTION. The annual-plan directory's whole obligation, in one line."""
    source = inspect.getsource(annual_plan.promote_entry)
    assert "open_design_workshop(" in source
    assert "designworkshop.create(" not in source


def test_the_shared_opener_seeds_before_it_returns():
    """All four steps, and the last one is the one that matters. Its ABSENCE is what is invisible."""
    source = inspect.getsource(design_workshops.open_design_workshop)
    assert "assert_every_designer_may_be_named(" in source
    assert "db.designworkshop.create(" in source
    assert "attach_the_named_designers(" in source
    assert "seed_designer_prefill(" in source


def test_the_shared_opener_asks_eligibility_above_the_create():
    """ORDER, NOT PRESENCE. Asked AFTER the create, the same 422 leaves a committed,
    untitled-looking orphan draft behind on every retry — a create that cannot honour the body it
    was given must not half-succeed."""
    source = inspect.getsource(design_workshops.open_design_workshop)
    assert source.index("assert_every_designer_may_be_named(") < source.index(
        "db.designworkshop.create("
    )


def test_the_shared_opener_does_not_gate():
    """Gating stays on the ROUTES, where ``tests/test_design_workshop_gate.py`` can read it.

    Moving ``assert_can_create_design_workshops`` in here would move it out of the place that test
    can see — and would make the annual-plan door's own, different gate invisible to the reader of
    that route. Two doors, two gates, one creation path.
    """
    body = inspect.getsource(design_workshops.open_design_workshop).split('"""')[-1]
    assert "assert_can_create_design_workshops" not in body
    assert "require_annual_plan_manager" not in body
    assert "can_manage_annual_plan" not in body


def test_the_shared_opener_is_not_wrapped_in_a_transaction():
    """Inherited rather than chosen, and stated so a well-meaning ``db.tx()`` cannot land quietly.

    ``attach_the_named_designers`` argues at length why the viewer rows are NOT transactional with
    the create, and ``seed_designer_prefill`` swallows its own failure on purpose. A transaction
    round all four would reverse both decisions without either docstring changing.
    """
    body = inspect.getsource(design_workshops.open_design_workshop).split('"""')[-1]
    assert "db.tx(" not in body


def test_the_create_route_still_carries_its_own_gate_spelled_out_in_its_own_body():
    """The rewire owed on ``create_design_workshop`` must not move this line when it happens.

    ``tests/test_design_workshop_gate.py`` reads that route's source for exactly this, and this
    assertion is the reminder from the other side: whoever re-points the route at the opener has to
    leave the gate where it is.
    """
    source = inspect.getsource(dw_routes.create_design_workshop)
    assert "assert_can_create_design_workshops(current_user)" in source
    assert "_require_designer(current_user)" not in source
