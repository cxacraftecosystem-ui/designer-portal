"""THE WEB CLIENT'S THREE ENTRY POINTS TO THE ANNUAL PLAN, READ OFF DISK. No database, no browser.

══ WHY A PYTHON TEST READS TYPESCRIPT ═══════════════════════════════════════════════════════════

The natural home for this is `frontend/e2e/annual-plan-guard-unit.spec.ts`, beside
`sketches-hub-guard-unit.spec.ts`, and that is where it should end up. It is here because
`frontend/e2e/` was owned by another change in flight when this landed, and the assertions are worth
more than their filename: `backend/tests/test_role_ladder_parity.py` already reads both client trees
off disk for exactly this reason, so the shape is not novel here.

**When it moves, it is a move and not a rewrite.**

══ WHY `/annual-plan` IS NOT IN `feature-entry-points.spec.ts`'s `DESTINATIONS` ══════════════════

That array's own header says why it cannot be: *"Both are open to any signed-in user, so both must
appear for every account that can sign in."* `/map` and `/questionnaire/consolidated` are ungated.
`/annual-plan` is a RANK FLOOR at 48, so the signed-in `E2E_EMAIL` account may legitimately not see
its nav entry or its tile — and the spec would then fail for a perfectly correct build. Adding it
there is not a fix; this file is the substitute, and it needs no session at all.

══ WHAT IS ASSERTED, AND WHAT IS DELIBERATELY NOT ═══════════════════════════════════════════════

Three surfaces name ONE predicate: the route guard, the nav entry and the admin hub tile. **The
DASHBOARD tile is missing and that is a gap rather than a decision** —
`frontend/e2e/feature-entry-points.spec.ts` names "the dashboard tiles (which is where Android puts
everything) and the navigation sheet" as the two places a researcher looks, so the dashboard is one
of the two. `app/(protected)/dashboard/page.tsx` was owned by another change in flight; the test
below therefore asserts its ABSENCE, with the sentence to write when it lands, so that adding the
tile turns this file red and whoever adds it finds the argument.
"""

from pathlib import Path

import pytest

FRONTEND = Path(__file__).resolve().parents[2] / "frontend"

PERMISSIONS = FRONTEND / "lib" / "permissions.ts"
NAV = FRONTEND / "components" / "DynamicIslandNav.tsx"
HUB = FRONTEND / "app" / "(protected)" / "admin" / "page.tsx"
DASHBOARD = FRONTEND / "app" / "(protected)" / "dashboard" / "page.tsx"
PAGE = FRONTEND / "app" / "(protected)" / "annual-plan" / "page.tsx"


def _read(path: Path) -> str:
    assert path.exists(), f"{path} is missing"
    return path.read_text(encoding="utf-8")


@pytest.mark.parametrize("path", [PERMISSIONS, NAV, HUB])
def test_the_route_guard_the_nav_entry_and_the_hub_tile_name_one_predicate(path: Path):
    """ONE PREDICATE, THREE SURFACES, so they cannot answer differently.

    Three hand-written copies of "rank >= 48" would be three places to get it wrong, and the way
    they get it wrong is asymmetric: a nav entry that is too generous shows a link that 403s, while
    a route guard that is too generous shows the page's own empty state over an API that refused —
    which reads as "the ministry has not uploaded the plan".
    """
    assert "canManageAnnualPlan" in _read(path)


def test_the_annual_plan_route_is_not_nested_under_admin():
    """`/admin` is `isAdmin` — SET membership — and a rule WIDER than it must not sit beneath it.

    `routeGuardFor` picks the LONGEST matching path, so a nested rule would win; and the hub page
    re-checks `isAdmin` in the component, so a MINISTRY_ADMIN reaching a page inside that shell is
    refused a second time by the shell itself. Two refusals, for the one tier the page exists for.
    """
    source = _read(PERMISSIONS)
    assert 'path: "/annual-plan"' in source
    assert 'path: "/admin/annual-plan"' not in source


def test_the_nav_entry_is_not_flagged_as_admin_chrome():
    """`adminSurface` would hide the directory from the ADMIN half of its audience.

    That flag means "admin chrome the admin-view toggle may hide", and the toggle only exists for an
    account `isAdmin` admits. A MINISTRY_ADMIN has no toggle — so flagging this row would hide it
    from every admin browsing with admin view off while leaving it for the tier below them. A rule
    that fires for exactly the wrong half of its audience.
    """
    source = _read(NAV)
    start = source.index('href: "/annual-plan"')
    entry = source[start : source.index("}", start)]
    assert "adminSurface" not in entry, entry


def test_the_route_guard_names_the_server_dependency_it_mirrors():
    """The `gate:` field is what keeps the two halves of one rule findable from either side."""
    source = _read(PERMISSIONS)
    start = source.index('path: "/annual-plan"')
    row = source[start : source.index("},", start)]
    assert 'gate: "require_annual_plan_manager"' in row, row


def test_the_page_exists_and_is_a_client_component():
    """Every page in this app that reads the API is a client component — the token lives in
    localStorage, so there is no server-side data fetching anywhere in this tree."""
    source = _read(PAGE)
    assert source.lstrip().startswith('"use client"')


def test_the_dashboard_tile_is_still_owed():
    """⚠ A GAP, NOT A DECISION — and this test is the note that says so.

    `frontend/e2e/feature-entry-points.spec.ts` names the dashboard tiles and the navigation sheet as
    "the two places a researcher looks". The nav entry landed; the dashboard tile did not, because
    `app/(protected)/dashboard/page.tsx` was owned by another change in flight.

    **When you add it**, it goes in the administrative group beside "Craft" and "Workshops", with
    `visible: canManageAnnualPlan(user)` — a PLAIN PREDICATE and not `adminSurface(...)`, which is
    `allowed && (!isAdmin(user) || adminMode)` and would invert its own intent twice over: a
    MINISTRY_ADMIN is not `isAdmin`, so the tile would show for them unconditionally, while an ADMIN
    browsing with the admin-view toggle OFF would lose it. Then delete this test.
    """
    assert "canManageAnnualPlan" not in _read(DASHBOARD), (
        "The dashboard tile has landed — good. Delete this test and add the positive one to the "
        "parametrised list above."
    )
