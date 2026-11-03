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


@pytest.mark.parametrize(
    ("path", "predicate"),
    [(PERMISSIONS, "canReadAnnualPlan"), (NAV, "canReadAnnualPlan"), (HUB, "canManageAnnualPlan")],
)
def test_the_route_guard_the_nav_entry_and_the_hub_tile_name_one_predicate(path: Path, predicate: str):
    assert predicate in _read(path)


def test_the_annual_plan_route_is_not_nested_under_admin():
    source = _read(PERMISSIONS)
    assert 'path: "/annual-plan"' in source
    assert 'path: "/admin/annual-plan"' not in source


def test_the_nav_entry_is_not_flagged_as_admin_chrome():
    source = _read(NAV)
    start = source.index('href: "/annual-plan"')
    entry = source[start : source.index("}", start)]
    assert "adminSurface" not in entry, entry


def test_the_route_guard_names_the_server_dependency_it_mirrors():
    source = _read(PERMISSIONS)
    start = source.index('path: "/annual-plan"')
    row = source[start : source.index("},", start)]
    assert 'gate: "require_annual_plan_reader"' in row, row
    assert "can: canReadAnnualPlan" in row, row


def test_the_page_exists_and_is_a_client_component():
    source = _read(PAGE)
    assert source.lstrip().startswith('"use client"')


def test_the_dashboard_tile_is_still_owed():
    assert "canManageAnnualPlan" not in _read(DASHBOARD), (
        "The dashboard tile has landed — good. Delete this test and add the positive one to the "
        "parametrised list above."
    )
