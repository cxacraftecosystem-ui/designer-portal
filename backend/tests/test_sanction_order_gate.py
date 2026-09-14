"""WHO MAY RECORD A MINISTRY SANCTION ORDER — and the three surfaces that have to say it alike.

NO DATABASE. Everything here is a predicate, a constant or a file read as text, which is the point:
the failures this file prevents are all failures of AGREEMENT, and agreement can be checked without
a row anywhere.

══ THE THREE INCIDENTS THIS FILE EXISTS FOR ═══════════════════════════════════════════════════

1. **A rank floor quietly becoming a set, or a set quietly becoming a rank floor.** Every other
   design-workshop gate in this product is SET MEMBERSHIP; this one alone is
   ``rank >= ASSISTANT_DIRECTOR``. A reader who assumes the family rule applies here will "fix" it
   into a set and lock out the two tiers above the floor, whose accounts will simply stop being
   offered the screen with nothing to explain why.

2. **A refusal reworded on one surface.** The sentence is said by the API's 403, by the web route
   guard's lock panel and by ``lib/sanctionOrders.ts``. A refusal that names a different next move
   depending on where you met it is not a rule, it is three rumours — and nothing about a reworded
   copy fails a build. ``test_the_refusal_sentence_is_identical_on_every_surface`` reads both
   frontend files off disk for exactly that reason.

3. **A second sanctioned amount appearing in the field registry.** The whole argument for
   ``SanctionOrder`` being a table is that the money has ONE home and no report copy. A FieldSpec
   called ``sanctionedAmount`` added to stage 1 in good faith would create a second answer that a
   designer can type over, and nothing else in this product would notice.
"""

from __future__ import annotations

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
    """The smallest thing ``role_value`` can read a role off. No database, no Prisma model."""

    def __init__(self, role: str) -> None:
        self.role = role


# --------------------------------------------------------------------------------------
# The floor
# --------------------------------------------------------------------------------------


def test_the_floor_is_assistant_director_and_every_tier_below_it_is_refused() -> None:
    """All eleven tokens, by name, so a new tier cannot be added without deciding about this gate.

    The table is written out rather than derived from ``ROLE_RANK`` because a derivation would
    re-state the implementation and agree with it by construction. These are the ANSWERS, and the
    three below the floor are the ones worth reading: a PROFESSOR at 40 and an INSPECTOR at 37 are
    senior people who are nevertheless not ministry officers, and a DESIGNER at 35 being refused is
    the entire requirement — the person who does the work does not authorise their own budget.
    """
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
    """Fail CLOSED. ``role_rank`` answers 0 for an unknown token, which must not clear a floor of 42."""
    assert can_record_sanction_orders(Fake("")) is False
    assert can_record_sanction_orders(None) is False
    assert can_record_sanction_orders({"role": "NOT_A_TIER"}) is False


def test_recording_a_sanction_is_strictly_wider_than_creating_a_workshop() -> None:
    """The two gates are related and are NOT the same gate, in this direction and no other.

    Everyone who may open a workshop by hand may also record the instrument that opens one; three
    tiers may record the instrument and may NOT open an ad-hoc workshop. That is not an
    inconsistency — it is the difference between spending authorised money and creating a container.
    """
    creators = {role for role in ROLE_RANK if can_create_design_workshops(Fake(role))}
    recorders = {role for role in ROLE_RANK if can_record_sanction_orders(Fake(role))}
    assert creators < recorders, (creators, recorders)


def test_the_create_workshop_gate_is_unchanged_by_this_feature() -> None:
    """A NEGATIVE assertion, and it is the one that catches the tempting shortcut.

    The obvious way to let a ministry officer open a workshop is to add the three tiers to
    ``DESIGN_WORKSHOP_CREATOR_ROLES``. It would work, and it would hand them the UNTRACKED create as
    well — a workshop with no instrument behind it, no number, no amount and no named designer.
    """
    assert frozenset({"ADMIN", "MASTER_ADMIN"}) == DESIGN_WORKSHOP_CREATOR_ROLES


# --------------------------------------------------------------------------------------
# Every arm of the prefix
# --------------------------------------------------------------------------------------


def _dependency_calls(dependant: Any) -> set[Any]:
    """Every callable in one route's dependency tree, however deeply nested."""
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
    """READ IS GATED WITH WRITE, and the test walks the real dependency tree to prove it.

    The register is a list of named designers, their personal addresses and the public money
    attached to them. A read gate one tier looser than the write gate would make it browsable by
    people who cannot be told apart from those who may add to it — which is the argument
    ``require_access_manager`` makes about its own queue.

    Walking ``route.dependant`` rather than reading the source is what makes this survive a
    refactor: a route that acquired its gate through a shared ``dependencies=[...]`` on the router,
    or lost it to a renamed decorator, is caught either way.
    """
    routes = [route for route in sanction_routes.router.routes if hasattr(route, "dependant")]
    assert len(routes) == 7, [getattr(r, "path", None) for r in routes]
    for route in routes:
        calls = _dependency_calls(route.dependant)
        assert sanction_routes.require_sanction_recorder in calls, route.path


def test_the_register_offers_no_way_to_delete_a_sanction_order() -> None:
    """A financial instrument of record has no delete, and the absence is asserted rather than assumed.

    Nothing in this product that records a person's standing or an institution's decision is ever
    deleted. A DELETE arm added here would be one method call away from erasing the only row that
    proves money was authorised, and it would look entirely ordinary in a diff.
    """
    methods = {method for route in sanction_routes.router.routes for method in route.methods}
    assert "DELETE" not in methods


def test_the_awaiting_count_is_declared_before_the_id_route() -> None:
    """FastAPI matches in DECLARATION ORDER, and this repository has lost an endpoint to that once.

    ``GET /sanction-orders/{sanction_id}`` matches ``/sanction-orders/awaiting-count`` perfectly well
    and would answer 404 "Sanction order not found" — a badge that silently reads zero for ever on a
    server where the endpoint exists.

    **THE COMPARISON IS PER METHOD, AND THE FIRST DRAFT OF THIS TEST WAS WRONG FOR NOT BEING.** It
    compared against the first route whose path is ``/{sanction_id}``, which is the PATCH — a route
    that cannot shadow a GET at all, because the method is part of the match. So it failed against a
    router that was correct, which is the worse kind of failing test: the obvious way to quiet it is
    to shuffle the declarations, and shuffling them would break the module's Writing/Reading shape to
    satisfy a rule that was never about writes. Only GET can shadow GET; this asserts that and
    nothing more.
    """
    paths = [
        route.path for route in sanction_routes.router.routes if "GET" in (route.methods or set())
    ]
    assert paths.index("/sanction-orders/awaiting-count") < paths.index(
        "/sanction-orders/{sanction_id}"
    ), paths


# --------------------------------------------------------------------------------------
# The three surfaces that say one sentence
# --------------------------------------------------------------------------------------


def _joined_strings(fragment: str) -> str:
    """Every double-quoted piece of a TypeScript expression, concatenated in source order."""
    return "".join(re.findall(r'"([^"]*)"', fragment))


def _web_refusal_constant() -> str:
    """The `+`-joined pieces of ``SANCTION_ORDER_REFUSAL`` in `frontend/lib/sanctionOrders.ts`.

    THE TERMINATOR IS ``";`` AND NOT ``;``, AND THE FIRST DRAFT OF THIS HELPER GOT IT WRONG IN THE
    one way that matters: the refusal's own last sentence contains a semicolon ("ask the officer who
    holds the order to record it; the workshop will appear …"), so a non-greedy match up to the first
    ``;`` stopped in the MIDDLE of the sentence and the comparison failed against two files that
    agreed. A parser that cannot read the string it is checking reports the author as wrong.
    """
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
    """Byte-for-byte, across Python and two TypeScript files, read off disk.

    THIS IS THE ASSERTION THAT MAKES THE "ONE PLACE" CLAIM TRUE RATHER THAN MERELY WRITTEN DOWN.
    A comment saying "its twin is over there" is worth nothing on the day somebody rewords one of
    them: no build, no type check and no lint has an opinion about a sentence, and the two halves
    then tell a ministry officer two different next moves depending on whether they met the wall at
    the URL or at the API.
    """
    web_constant = _web_refusal_constant()
    guard_message = _joined_strings(_route_guard_row().split("message:", 1)[1])
    assert web_constant == SANCTION_ORDER_REFUSAL
    assert guard_message == SANCTION_ORDER_REFUSAL


def test_the_web_and_the_server_agree_on_the_threshold() -> None:
    """Both web copies compare against ASSISTANT_DIRECTOR, and neither invents a set.

    Two copies exist on the web on purpose — ``lib/sanctionOrders.ts`` cannot be imported from
    ``lib/permissions.ts`` without an import cycle, so the ROUTE_GUARDS row writes the rank test
    inline. This is what stops the duplication from becoming a divergence.
    """
    assert 'hasRank(user, "ASSISTANT_DIRECTOR")' in _route_guard_row()
    assert 'hasRank(user, "ASSISTANT_DIRECTOR")' in SANCTION_TS.read_text(encoding="utf-8")
    assert ROLE_RANK["ASSISTANT_DIRECTOR"] == 42


def test_the_route_guard_row_is_not_nested_under_admin() -> None:
    """The path is ``/sanction-orders`` and not ``/admin/...``, and the difference is a real trap.

    ``/admin`` gates on ``isAdmin``, the SET {ADMIN, MASTER_ADMIN}. This rule is rank >= 42, which is
    WIDER. ``routeGuardFor`` picks the longest matching path, so a nested rule would win — and the
    hub page itself would still refuse a ministry officer, leaving them a route they may open and no
    way to reach it.
    """
    assert 'path: "/sanction-orders"' in PERMISSIONS_TS.read_text(encoding="utf-8")
    assert 'path: "/admin/sanction-orders"' not in PERMISSIONS_TS.read_text(encoding="utf-8")


# --------------------------------------------------------------------------------------
# The number, and the money's one home
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "spelling",
    ["SO/2026/42", "SO-2026-42", "so 2026 42", "  so/2026/42  ", "So.2026.42"],
)
def test_every_house_style_of_one_number_normalises_to_one_key(spelling: str) -> None:
    """Five spellings, one key. This is the constraint that actually stops a duplicate.

    ``sanctionOrderNo`` stores what the officer typed, because that is what an auditor holding the
    paper will search for, and Postgres calls these five different values. ``sanctionOrderKey`` is
    what the unique index is really on.
    """
    assert normalise_sanction_order_no(spelling) == "SO202642"


def test_the_sanction_key_normalisation_matches_the_empanelment_one() -> None:
    """The two rules are the same rule TODAY, and a divergence has to be typed on purpose.

    ``normalise_sanction_order_no`` is a copy of ``identity.normalise_empanelment_no`` rather than a
    call to it, because the two vocabularies must be free to diverge — a ministry could start
    issuing numbers with a meaningful suffix tomorrow, and a shared function would silently re-key
    the other table. What must not happen is a divergence nobody chose, and this is what refuses it.

    The one deliberate difference is the BLANK case: the empanelment twin answers ``None`` and this
    one answers ``""``, because this column is NOT NULL and the schema refuses a blank number at the
    door — a ``str | None`` here would push a None check into every call site for a case that cannot
    occur. So the comparison folds None to the empty string rather than pretending they are equal.
    """
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
    """Trimmed, internal whitespace collapsed, and NOTHING else — not the case, not the separators."""
    assert tidy_sanction_order_no("  SO/2026/42  ") == "SO/2026/42"
    assert tidy_sanction_order_no("SO  2026   42") == "SO 2026 42"
    assert tidy_sanction_order_no("so-2026-042") == "so-2026-042"


def test_the_registry_never_grows_a_second_sanction_amount() -> None:
    """THE CHEAPEST GUARD IN THIS WORKSTREAM, AND THE ONLY THING STANDING BETWEEN THE OWNER AND TWO
    BUDGET FIGURES.

    ``SanctionOrder.sanctionAmount`` is the register and it has no report copy, deliberately: a
    promoted column's single writer is the DESIGNER saving stage 1, and an officer's sanction figure
    that a designer can overwrite by typing in a box is not a register, it is a rumour with an index
    on it. A FieldSpec named for a sanctioned amount, added in good faith on any stage, creates the
    second answer — and nothing else in this product would notice.
    """
    from app.services.stage_schema import stages

    pattern = re.compile(r"sanction.*amount|amount.*sanction", re.IGNORECASE)
    offenders = [
        f"{spec.key}.{entity.key}.{field.key}"
        for spec in stages()
        for entity in spec.entities
        for field in entity.fields
        if pattern.search(field.key)
    ]
    assert offenders == [], offenders


def test_the_sanction_facts_are_not_promoted_columns() -> None:
    """``sanctionOrderNo``/``sanctionOrderDate`` stay inside stage JSON, and the count is pinned.

    Promoting them would put the register's own facts under ``promoted_values``, whose single writer
    is the designer saving stage 1 — and ``_coerce_promoted`` NULLs a promoted column whose entity
    was touched with a blank value, which would silently delete a ministry fact under a 200 reading
    "Stage saved".
    """
    from app.services.stage_schema import PROMOTED_COLUMNS

    assert "workshopSetup.sanctionOrderNo" not in PROMOTED_COLUMNS
    assert "workshopSetup.sanctionOrderDate" not in PROMOTED_COLUMNS
    assert len(PROMOTED_COLUMNS) == 14, sorted(PROMOTED_COLUMNS)
