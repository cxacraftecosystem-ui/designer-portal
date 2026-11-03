from __future__ import annotations

import asyncio
import inspect
import re
from pathlib import Path
from typing import Any

import pytest

from app.api.routes import sanction_orders as sanction_routes
from app.core.deps import (
    DESIGN_WORKSHOP_CREATOR_ROLES,
    ROLE_RANK,
    can_create_design_workshops,
)
from app.services.identity import normalise_empanelment_no
from app.services.sanction_orders import (
    SANCTION_ORDER_REFUSAL,
    can_record_sanction_orders,
    normalise_sanction_order_no,
    tidy_sanction_order_no,
)

REPO = Path(__file__).resolve().parents[2]
PERMISSIONS_TS = REPO / "frontend" / "lib" / "permissions.ts"
SANCTION_TS = REPO / "frontend" / "lib" / "sanctionOrders.ts"


class Fake:
    def __init__(self, role: str) -> None:
        self.role = role



def test_the_floor_is_assistant_director_and_every_tier_below_it_is_refused() -> None:
    allowed = {"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}
    refused = {
        "PROFESSOR",
        "INSPECTOR",
        "DESIGNER",
        "RESEARCHER",
        "FIELD_CONTRIBUTOR",
        "CROWDSOURCE_VOLUNTEER",
    }
    assert allowed | refused == set(ROLE_RANK), (
        "a tier exists that this table has not decided about"
    )
    for role in sorted(allowed):
        assert can_record_sanction_orders(Fake(role)) is True, role
    for role in sorted(refused):
        assert can_record_sanction_orders(Fake(role)) is False, role


def test_an_account_with_no_role_at_all_is_refused() -> None:
    assert can_record_sanction_orders(Fake("")) is False
    assert can_record_sanction_orders(None) is False
    assert can_record_sanction_orders({"role": "NOT_A_TIER"}) is False


def test_recording_a_sanction_is_strictly_wider_than_creating_a_workshop() -> None:
    creators = {role for role in ROLE_RANK if can_create_design_workshops(Fake(role))}
    recorders = {role for role in ROLE_RANK if can_record_sanction_orders(Fake(role))}
    assert creators < recorders, (creators, recorders)


def test_the_create_workshop_gate_is_unchanged_by_this_feature() -> None:
    assert frozenset({"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"}) == DESIGN_WORKSHOP_CREATOR_ROLES



def _dependency_calls(dependant: Any) -> set[Any]:
    found: set[Any] = set()
    stack = list(getattr(dependant, "dependencies", []))
    while stack:
        node = stack.pop()
        call = getattr(node, "call", None)
        if call is not None:
            found.add(call)
        stack.extend(getattr(node, "dependencies", []))
    return found


def test_every_route_on_the_prefix_carries_the_sanction_gate_read_included() -> None:
    routes = [route for route in sanction_routes.router.routes if hasattr(route, "dependant")]
    assert len(routes) == 14, [getattr(r, "path", None) for r in routes]
    for route in routes:
        calls = _dependency_calls(route.dependant)
        assert sanction_routes.require_sanction_recorder in calls, route.path


def test_only_a_content_admin_deletes_a_sanction_order_and_never_a_linked_one() -> None:
    import inspect

    deletes = [
        route
        for route in sanction_routes.router.routes
        if "DELETE" in (getattr(route, "methods", None) or set())
    ]
    assert [route.path for route in deletes] == ["/sanction-orders/{sanction_id}"]
    source = inspect.getsource(sanction_routes.delete_sanction_order)
    assert "assert_can_delete(officer)" in source
    assert "status.HTTP_409_CONFLICT" in source
    assert '"deletedAt": None' in source


def test_the_awaiting_count_is_declared_before_the_id_route() -> None:
    paths = [
        route.path for route in sanction_routes.router.routes if "GET" in (route.methods or set())
    ]
    catch_all = paths.index("/sanction-orders/{sanction_id}")
    literals = [path for path in paths if "{" not in path]
    assert "/sanction-orders/designers" in literals, paths
    assert "/sanction-orders/pro-forma.xlsx" in literals, paths
    for literal in literals:
        assert paths.index(literal) < catch_all, (literal, paths)



def _joined_strings(fragment: str) -> str:
    return "".join(re.findall(r'"([^"]*)"', fragment))


def _web_refusal_constant() -> str:
    source = SANCTION_TS.read_text(encoding="utf-8")
    match = re.search(r'export const SANCTION_ORDER_REFUSAL\s*=([\s\S]*?");\s*\n', source)
    assert match, "SANCTION_ORDER_REFUSAL is missing from frontend/lib/sanctionOrders.ts"
    return _joined_strings(match.group(1))


def _route_guard_row() -> str:
    source = PERMISSIONS_TS.read_text(encoding="utf-8")
    start = source.find('path: "/sanction-orders"')
    assert start != -1, "ROUTE_GUARDS has no /sanction-orders row"
    end = source.find("\n  },", start)
    assert end != -1
    return source[start:end]


def test_the_refusal_sentence_is_identical_on_every_surface() -> None:
    web_constant = _web_refusal_constant()
    guard_message = _joined_strings(_route_guard_row().split("message:", 1)[1])
    assert web_constant == SANCTION_ORDER_REFUSAL
    assert guard_message == SANCTION_ORDER_REFUSAL


def test_the_web_and_the_server_agree_on_the_threshold() -> None:
    assert 'hasRank(user, "ASSISTANT_DIRECTOR")' in _route_guard_row()
    assert 'hasRank(user, "ASSISTANT_DIRECTOR")' in SANCTION_TS.read_text(encoding="utf-8")
    assert ROLE_RANK["ASSISTANT_DIRECTOR"] == 42


def test_the_route_guard_row_is_not_nested_under_admin() -> None:
    assert 'path: "/sanction-orders"' in PERMISSIONS_TS.read_text(encoding="utf-8")
    assert 'path: "/admin/sanction-orders"' not in PERMISSIONS_TS.read_text(encoding="utf-8")



@pytest.mark.parametrize(
    "spelling",
    ["SO/2026/42", "SO-2026-42", "so 2026 42", "  so/2026/42  ", "So.2026.42"],
)
def test_every_house_style_of_one_number_normalises_to_one_key(spelling: str) -> None:
    assert normalise_sanction_order_no(spelling) == "SO202642"


def test_the_sanction_key_normalisation_matches_the_empanelment_one() -> None:
    table = [
        "SO/2026/42",
        "so-2026-42",
        "EMP 12/AB",
        "A1",
        "   ",
        "///",
        "",
        "2026",
        "So.2026.042",
    ]
    for value in table:
        assert normalise_sanction_order_no(value) == (normalise_empanelment_no(value) or ""), value


def test_the_stored_number_keeps_the_ministrys_own_spelling() -> None:
    assert tidy_sanction_order_no("  SO/2026/42  ") == "SO/2026/42"
    assert tidy_sanction_order_no("SO  2026   42") == "SO 2026 42"
    assert tidy_sanction_order_no("so-2026-042") == "so-2026-042"


def test_the_registry_never_grows_a_second_sanction_amount() -> None:
    from app.services.stage_schema import stages

    pattern = re.compile(r"sanction.*amount|amount.*sanction", re.IGNORECASE)
    offenders = [
        f"{spec.key}.{entity.key}.{field.key}"
        for spec in stages()
        for entity in spec.entities
        for field in entity.fields
        if pattern.search(field.key)
    ]
    assert offenders == ["WORKSHOP_SETUP.workshopSetup.sanctionAmount"], offenders


def test_the_sanction_facts_are_not_promoted_columns() -> None:
    from app.services.stage_schema import PROMOTED_COLUMNS

    assert "workshopSetup.sanctionOrderNo" not in PROMOTED_COLUMNS
    assert "workshopSetup.sanctionOrderDate" not in PROMOTED_COLUMNS
    assert len(PROMOTED_COLUMNS) == 15, sorted(PROMOTED_COLUMNS)



def test_the_designer_directory_is_the_fifth_door_and_not_a_widened_gate() -> None:
    from app.core.deps import is_admin
    from app.services.design_workshop_oversight import OVERSIGHT_ASSIGNER_ROLES

    paths = [route.path for route in sanction_routes.router.routes]
    assert "/sanction-orders/designers" in paths, paths

    assert is_admin(Fake("ADMIN")) and is_admin(Fake("MASTER_ADMIN"))
    for outsider in ("MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR", "DESIGNER"):
        assert not is_admin(Fake(outsider)), outsider

    assert "REGIONAL_DIRECTOR" not in set(OVERSIGHT_ASSIGNER_ROLES), OVERSIGHT_ASSIGNER_ROLES
    assert "ASSISTANT_DIRECTOR" not in set(OVERSIGHT_ASSIGNER_ROLES), OVERSIGHT_ASSIGNER_ROLES


def test_the_designer_directory_answers_four_keys_and_no_roster_judgement() -> None:
    from app.services.designers import assignable_designers_payload

    class Row:
        id = "u1"
        name = "Ramesh Kumar"
        email = "r.kumar@gmail.com"
        role = "DESIGNER"

    payload = assignable_designers_payload([Row()])
    assert payload == [
        {"id": "u1", "name": "Ramesh Kumar", "email": "r.kumar@gmail.com", "role": "DESIGNER"}
    ]
    for forbidden in ("rosterActive", "canSignIn", "firstSeenAt", "institution", "rosterId"):
        assert forbidden not in payload[0], forbidden


def test_the_designer_directory_offers_no_admin_account_to_an_officer(monkeypatch) -> None:
    from app.services import design_workshop_viewers, designers as designers_service

    captured: dict[str, Any] = {}

    class FakeUsers:
        @staticmethod
        async def find_many(**kwargs: Any) -> list[Any]:
            captured.update(kwargs)
            return []

    class FakeDb:
        user = FakeUsers()

    async def fake_roster() -> tuple[list[str], bool]:
        return ["designer@example.org"], False

    monkeypatch.setattr(designers_service, "db", FakeDb())
    monkeypatch.setattr(design_workshop_viewers, "active_roster_emails", fake_roster)

    def roles_and_arms(where: dict[str, Any]) -> tuple[set[str], list[Any]]:
        clauses = where["AND"]
        roles = set(clauses[0]["role"]["in"])
        arms = next((clause["OR"] for clause in clauses[1:] if "OR" in clause), [])
        return roles, arms

    asyncio.run(designers_service.workshop_capable_accounts(include_admins=False))
    roles, arms = roles_and_arms(captured["where"])
    assert "ADMIN" not in roles and "MASTER_ADMIN" not in roles, roles
    assert "DESIGNER" in roles, roles
    assert arms == [
        {"AND": [{"role": "DESIGNER"}, {"email": {"in": ["designer@example.org"], "mode": "insensitive"}}]}
    ], "the officer's picker must be the empanelled designer roster and nothing else"

    captured.clear()
    asyncio.run(designers_service.workshop_capable_accounts())
    roles, arms = roles_and_arms(captured["where"])
    assert {"ADMIN", "MASTER_ADMIN"} <= roles, (
        "the default must still admit admins — the two admin-adjacent doors depend on it"
    )
    assert {"role": {"in": designers_service.roster_exempt_workshop_roles()}} in arms, arms
    assert set(designers_service.roster_exempt_workshop_roles()) == (
        set(designers_service.workshop_capable_roles()) - {"DESIGNER"}
    )

    source = inspect.getsource(sanction_routes.list_sanction_designers)
    assert "include_admins=False" in source, source


def test_the_upload_pair_is_a_preview_then_a_write_and_never_one_route() -> None:
    by_path = {
        (route.path, tuple(sorted(route.methods))): route
        for route in sanction_routes.router.routes
        if hasattr(route, "methods")
    }
    preview = by_path[("/sanction-orders/upload", ("POST",))]
    confirm = by_path[("/sanction-orders/upload/confirm", ("POST",))]
    assert preview.status_code in (None, 200), preview.status_code
    assert confirm.status_code == 201, confirm.status_code


def test_the_five_standing_refusals_are_never_offered_for_confirmation() -> None:
    import inspect

    from app.services import sanction_import

    source = inspect.getsource(sanction_import._review_one_row).replace("\r\n", "\n")
    assert "designer_standing_verdict(" in source
    assert "return refuse(verdict.refusal.detail)" in source
    whole = inspect.getsource(sanction_import).replace("\r\n", "\n")
    body = whole.split('"""', 2)[2] if whole.count('"""') >= 2 else whole
    for spelled_elsewhere in (
        "has been ended. A sanction order does not restore it",
        "cannot let somebody back in",
        "cannot name the officer recording it",
    ):
        assert spelled_elsewhere not in body, spelled_elsewhere
