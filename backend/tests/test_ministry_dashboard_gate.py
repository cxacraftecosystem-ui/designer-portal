"""THE MINISTRY DASHBOARD'S DOOR, held to the client's and to the register it hands over.

No database, no browser. Every assertion here either calls a pure predicate or reads a file off
disk, so this runs in the gating ``Backend tests`` job on a machine whose DSN is ``ci.invalid``.

═══════════════════════════════════════════════════════════════════════════════════════════════
WHAT THIS FILE IS FOR, WHICH IS TWO DIFFERENT PROMISES
═══════════════════════════════════════════════════════════════════════════════════════════════

1. **THE GATE IS ONE RULE ON BOTH SIDES OF THE WIRE.** ``frontend/lib/permissions.ts``' own standing
   rule is that the UI never invents a permission and never offers what the API refuses, and a
   comment saying "its twin is over there" is worth nothing on the day somebody edits one of them.
   ``test_sanction_order_gate.py`` is the template and its docstring is the argument: *"no build, no
   type check and no lint has an opinion about a sentence, and the two halves then tell a ministry
   officer two different next moves depending on whether they met the wall at the URL or at the
   API."*

2. **THE REGISTER EXPORT CARRIES NO STAGE DATA.** ``deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES`` is
   ``{ADMIN, MASTER_ADMIN}`` and REFUSES every tier the ministry dashboard is for — deliberately, and
   the ruling survived the 2026-09-13 widening that put those tiers into the VIEW set. What it gates
   is a fortnight of a named designer's fieldwork: answers, photographs, recordings, dictation,
   consent decisions. The ministry's own download is the REGISTER — the header columns the same
   callers already read on screen, plus the completeness roll-up — which is the same kind of file
   ``/api/annual-plan/export.xlsx`` already hands a MINISTRY_ADMIN.

   That distinction is the whole justification for the route existing at a lower gate, so it is
   asserted rather than asserted-in-prose — and STRUCTURALLY rather than by comparing column names.
   The column list is pinned, and the export function is held to reading nothing but the promoted
   header, the roll-up and the artisan count. ``test_the_register_export_reads_no_stage_content``
   records why the name-comparison version of this test was wrong. If a future change wants an
   answer or a photograph in that file, this is what makes them go and get the export role first.
"""

from __future__ import annotations

import re
from pathlib import Path
from types import SimpleNamespace

from app.api.routes import ministry_dashboard as route
from app.core.deps import (
    MINISTRY_DASHBOARD_REFUSAL,
    MINISTRY_DASHBOARD_ROLES,
    ROLE_RANK,
    can_see_ministry_dashboard,
)
from app.services import ministry_dashboard as register

REPO = Path(__file__).resolve().parents[2]
PERMISSIONS_TS = REPO / "frontend/lib/permissions.ts"
PAGE_TSX = REPO / "frontend/app/(protected)/ministry-dashboard/page.tsx"


def _user(role: str) -> SimpleNamespace:
    return SimpleNamespace(id="u1", role=role, email="a@b.c", name="A")


def _route_guard_row() -> str:
    """The `/ministry-dashboard` row of ROUTE_GUARDS, as source text."""
    source = PERMISSIONS_TS.read_text(encoding="utf-8")
    start = source.find('path: "/ministry-dashboard"')
    assert start != -1, (
        "the ministry dashboard has no ROUTE_GUARDS row, so the URL is open in the browser to "
        "anyone who has been sent the link"
    )
    end = source.find("\n  },", start)
    assert end != -1
    return source[start:end]


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The door
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_the_audience_is_the_four_posts_and_nobody_else() -> None:
    """Written out tier by tier rather than derived from the frozenset.

    Deriving it would assert the set equal to itself and a widening would sail through — the same
    reason ``dashboard-tile-parity-unit.spec.ts`` types its own role table by hand. ADMIN is the row
    that carries the point: it is REFUSED although it outranks three of the four members, so no rank
    floor anywhere on the ladder produces this column.
    """
    offered = {
        "CROWDSOURCE_VOLUNTEER": False,
        "FIELD_CONTRIBUTOR": False,
        "RESEARCHER": False,
        "DESIGNER": False,
        "INSPECTOR": False,
        "PROFESSOR": False,
        "ASSISTANT_DIRECTOR": True,
        "REGIONAL_DIRECTOR": True,
        "MINISTRY_ADMIN": True,
        "ADMIN": False,
        "MASTER_ADMIN": True,
    }
    assert set(offered) == set(ROLE_RANK), "a tier was added to the ladder and not to this table"
    for role, expected in offered.items():
        assert can_see_ministry_dashboard(_user(role)) is expected, role
    assert can_see_ministry_dashboard(None) is False


def test_the_set_has_a_hole_no_rank_floor_can_express() -> None:
    """ADMIN (50) is out while MINISTRY_ADMIN (48) below it and MASTER_ADMIN (60) above it are in.

    This is the proof that the set is a SET. A reader "simplifying" it to ``has_rank(user, …)`` has
    to pick a floor, and every floor fails: 42 admits ADMIN, 48 loses the two directorate tiers the
    page is about.
    """
    assert ROLE_RANK["MINISTRY_ADMIN"] < ROLE_RANK["ADMIN"] < ROLE_RANK["MASTER_ADMIN"]
    assert "MINISTRY_ADMIN" in MINISTRY_DASHBOARD_ROLES
    assert "ADMIN" not in MINISTRY_DASHBOARD_ROLES
    assert "MASTER_ADMIN" in MINISTRY_DASHBOARD_ROLES


def test_the_web_mirrors_the_server_gate() -> None:
    """The client's literal is the server's, member for member, and the guard row reads it."""
    source = PERMISSIONS_TS.read_text(encoding="utf-8")
    match = re.search(
        r"export const MINISTRY_DASHBOARD_ROLES: readonly UserRole\[\] = \[([\s\S]*?)\n\];", source
    )
    assert match, "frontend/lib/permissions.ts no longer declares MINISTRY_DASHBOARD_ROLES"
    named = set(re.findall(r'"([A-Z_]+)"', match.group(1)))
    assert named == set(MINISTRY_DASHBOARD_ROLES)

    guard = _route_guard_row()
    assert "can: canSeeMinistryDashboard" in guard, (
        "the ministry dashboard's guard row does not read the predicate this module gates on"
    )


def test_the_gate_is_not_the_desk_cards_audience() -> None:
    """Two literals with identical membership, and the duplication is load-bearing.

    ``MINISTRY_DESK_ROLES``' own docstring promises that widening it *"widens no capability at
    all"* and that it is *"mirrored nowhere"*. Both sentences stop being true the moment a card
    audience is used as a route gate, so the guard row must not borrow it — a later widening of a
    dashboard CARD would silently open a page holding the whole national programme.
    """
    guard = _route_guard_row()
    assert "canSeeMinistryDesk" not in guard, (
        "the ministry dashboard route is gated on the DESK CARD's audience; read both docstrings"
    )


def test_the_refusal_sentence_is_identical_on_both_surfaces() -> None:
    """Byte-for-byte, the Python constant against the client's ROUTE_GUARDS `message:`.

    A refusal that names a different next move depending on whether you met the wall at the URL or at
    the API is not a rule, it is two rumours.
    """
    guard = _route_guard_row()
    body = guard.split("message:", 1)[1]
    joined = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', body)).replace('\\"', '"')
    assert joined == MINISTRY_DASHBOARD_REFUSAL


def test_the_route_guard_row_is_not_nested_under_a_narrower_prefix() -> None:
    """The path is top-level, and the two tempting nestings each refuse most of the audience.

    ``routeMatches`` compares whole SEGMENTS and ``routeGuardFor`` picks the LONGEST match, so a rule
    at ``/officers/dashboard`` would inherit ``/officers``' ``canAssignWorkshopOversight`` — a set
    that refuses an ASSISTANT DIRECTOR and a REGIONAL DIRECTOR — and one at ``/admin/dashboard``
    would inherit ``isAdmin``, which refuses all three ministry posts and is re-checked inside the hub
    page besides. Either leaves an officer a route they may open and no way to reach it.
    """
    guard = _route_guard_row()
    assert 'path: "/ministry-dashboard"' in guard
    for nested in ("/admin/", "/officers/", "/design-workshops/", "/dashboard/"):
        assert f'path: "{nested}' not in guard


def test_every_route_on_the_prefix_carries_the_gate() -> None:
    """A sweep rather than a list, so a route added later without a ``Depends`` is visible.

    The dependency is written as a ``Depends`` precisely so this sweep can walk the tree — the reason
    ``require_officer`` gives one scope over. And every route here is a GET: nothing on this prefix
    writes, and a POST appearing on it is a design change rather than an oversight.
    """
    assert route.router.routes, "the ministry dashboard router declares no routes"
    for api_route in route.router.routes:
        names = {
            dependency.call.__name__
            for dependency in getattr(api_route, "dependant", SimpleNamespace(dependencies=[])).dependencies
            if getattr(dependency, "call", None) is not None
        }
        # The gate may be the route's own dependency or reached through it; both appear in the
        # dependant tree, which is why this reads names rather than the signature.
        assert "require_ministry_dashboard_reader" in names or any(
            "require_ministry_dashboard_reader" in n for n in names
        ), f"{api_route.path} is on the ministry prefix without the gate"
        assert set(api_route.methods) == {"GET"}, f"{api_route.path} is not a read"


def test_the_prefix_declares_no_parameterised_path() -> None:
    """No ``/{id}`` here, which is what makes the router's declaration order unable to matter.

    Every per-workshop read the ministry has lives on ``/design-workshop-oversight``; a second door
    onto one workshop would be a second place to keep the read-only promise. It also means the ⚠
    ordering hazard that module spends a paragraph on cannot arise in this one.
    """
    for api_route in route.router.routes:
        assert "{" not in api_route.path, f"{api_route.path} is parameterised"


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The scope, and the sentence that describes it
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_the_estate_is_read_by_the_ministry_admin_and_the_master_admin_only() -> None:
    """And the two directorate posts are narrowed to the workshops they were named on."""
    assert register.sees_whole_estate(_user("MINISTRY_ADMIN")) is True
    assert register.sees_whole_estate(_user("MASTER_ADMIN")) is True
    assert register.sees_whole_estate(_user("ADMIN")) is True
    assert register.sees_whole_estate(_user("ASSISTANT_DIRECTOR")) is False
    assert register.sees_whole_estate(_user("REGIONAL_DIRECTOR")) is False


def test_the_narrowed_scope_is_the_oversight_relation_and_not_a_new_one() -> None:
    """It is ``oversight_by_clause``'s shape exactly — the clause that already scopes
    ``/officers/monitored``. A second definition of "the workshops this officer supervises" is two
    answers to one question, and only one of them would move the day a posting changes shape.
    """
    clause = register.scope_clause(_user("ASSISTANT_DIRECTOR"))
    assert clause == {"oversight": {"some": {"userId": "u1"}}}
    assert register.scope_clause(_user("MINISTRY_ADMIN")) is None


def test_the_scope_sentence_never_claims_the_estate_to_an_officer() -> None:
    """The one string on the page that stops four workshops reading as the whole programme."""
    estate = register.scope_label(_user("MINISTRY_ADMIN"))
    posted = register.scope_label(_user("REGIONAL_DIRECTOR"))
    assert estate != posted
    assert "Every workshop on the platform" in estate
    assert "Every workshop on the platform" not in posted
    assert "named on" in posted


def test_the_page_prints_the_servers_sentence_and_composes_none_of_its_own() -> None:
    """A paraphrase on the client would be a second description of one decision."""
    page = PAGE_TSX.read_text(encoding="utf-8")
    assert "{data.scopeLabel}" in page, "the page no longer prints the server's scope sentence"


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The standing groups
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_the_standing_groups_partition_the_enum() -> None:
    """Every ``DesignWorkshopStatus`` is in exactly one group.

    A status in NONE of them is a workshop that exists, is counted in the total, and is unreachable
    from every filter on the screen — absence reading as non-existence, wearing a filter control. A
    status in TWO double-counts the summary. The module asserts this at import time; this is the
    assertion that reports it as a test failure rather than as an import error in production.
    """
    grouped = [status for members in register.STANDING_GROUPS.values() for status in members]
    assert sorted(grouped) == sorted(register.ALL_STANDINGS)
    assert len(grouped) == len(set(grouped))


def test_a_draft_is_newly_registered_and_not_ongoing() -> None:
    """The distinction the ministry actually chases.

    A workshop is created at DRAFT by the act that registers it — a promoted plan row, or a sanction
    order — and stays there until somebody saves a stage. "Registered and not yet started" is exactly
    the queue an officer works through; folding it into "ongoing" would hide it inside the workshops
    that are moving.
    """
    assert register.group_of("DRAFT") == "registered"
    assert register.group_of("IN_PROGRESS") == "ongoing"
    assert register.group_of("APPROVED") == "completed"


def test_an_unknown_status_is_in_no_group_rather_than_the_first_one() -> None:
    """A server one release ahead can store a ninth status, and the summary says so."""
    assert register.group_of("SOMETHING_NEW") is None
    assert register.group_of(None) is None


def test_an_unrecognised_standing_is_ignored_rather_than_refused() -> None:
    """This is a filter, not a lookup — the rule every other narrowing on this API follows."""
    assert register.standing_clause(None) is None
    assert register.standing_clause("") is None
    assert register.standing_clause("nonsense") is None
    assert register.standing_clause("ongoing") == {
        "status": {"in": list(register.STANDING_GROUPS["ongoing"])}
    }
    # Case-folded, because the word travels through a query string a person can edit.
    assert register.standing_clause("ONGOING") == register.standing_clause("ongoing")


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Progress: never a zero it did not measure
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_an_unscored_workshop_carries_a_reason_and_never_a_zero() -> None:
    """THE SINGLE MOST DAMAGING FALSE STATEMENT THIS SCREEN COULD MAKE is 0% against a workshop it
    failed to read. ``overallPercent`` on the client returns 0 for an empty map; this payload returns
    ``None`` and says which of the two absences it is.
    """
    for reason in ("capped", "unreadable"):
        block = register.unscored(reason)
        assert block["percent"] is None
        assert block["stagesComplete"] is None
        assert block["requiredTotal"] is None
        assert block["unscoredReason"] == reason
        assert block["definitionRead"] is False


def test_the_roll_up_is_the_clients_arithmetic_to_the_rounding() -> None:
    """Sum filled over sum total, and a zero denominator reads as complete rather than as 0%.

    ``overallPercent`` in ``frontend/lib/designWorkshops.ts`` is the twin, and its own comment states
    the second half: *"Same rule as the server's `percent`: nothing required means nothing
    outstanding, not 0%."* A roll-up that AVERAGED the per-stage percentages instead would weight a
    two-field stage the same as a hundred-field one and disagree with the designer's own header on
    almost every workshop.
    """
    completeness = {
        "a": {"requiredTotal": 10, "requiredFilled": 5, "isComplete": False},
        "b": {"requiredTotal": 30, "requiredFilled": 30, "isComplete": True},
    }
    rolled = register._roll_up(completeness)
    assert rolled["requiredTotal"] == 40
    assert rolled["requiredFilled"] == 35
    assert rolled["percent"] == round(100 * 35 / 40)
    assert rolled["stagesComplete"] == 1
    assert rolled["stagesTotal"] == 2

    assert register._roll_up({"z": {"requiredTotal": 0, "requiredFilled": 0, "isComplete": True}})["percent"] == 100
    assert register._roll_up({})["percent"] is None


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The download carries the register and not the fieldwork
# ────────────────────────────────────────────────────────────────────────────────────────────────


def test_the_register_export_columns_are_pinned() -> None:
    """The literal, in file order. This is the claim the route's lower gate rests on."""
    assert route._DESIGN_EXPORT_COLUMNS == (
        "Workshop code",
        "Title",
        "Kind",
        "Craft",
        "Cluster",
        "State",
        "District",
        "Venue",
        "Start date",
        "End date",
        "Designer",
        "Standing",
        "Standing group",
        "Artisans",
        "Stages complete",
        "Stages total",
        "Required fields answered",
        "Required fields asked",
        "Progress %",
        "Last updated",
    )


def test_the_register_export_reads_no_stage_content() -> None:
    """THE ASSERTION THAT KEEPS THE ROUTE HONEST ABOUT `DESIGN_WORKSHOP_DATA_EXPORT_ROLES`.

    That set is ``{ADMIN, MASTER_ADMIN}`` and refuses every tier this download is for. What it gates
    is STAGE DATA — answers, photographs, recordings, dictation, consent decisions. This file carries
    the header columns the same callers already read on screen plus the completeness roll-up, which
    is the same kind of register ``/api/annual-plan/export.xlsx`` already hands a MINISTRY_ADMIN.

    ⚠ **IT IS ASSERTED STRUCTURALLY AND NOT BY COMPARING COLUMN NAMES**, and the first attempt at
    this test got that wrong in a way worth recording: it read every field label out of the stage
    registry and forbade any of them as a column. That fails on "State", "Venue", "Cluster", "Start
    date" and "Kind" — and those are false positives BY CONSTRUCTION. Every one of them is a stage-1
    field that ``promoted_values()`` DENORMALISES onto the workshop row, which is why
    ``workshop_summary`` carries it and why the officer already reads it on the oversight list. A
    label collision is what promotion MEANS; it is not a leak.

    What actually distinguishes the register from the fieldwork is whether the export READS STAGE
    ENTRIES AT ALL. It does not, and cannot without one of the names below appearing in its body.
    """
    import inspect

    source = inspect.getsource(route.export_design_workshops_csv)
    for reader in (
        "entry_rows",
        "_stages_payload",
        "dwstageentry",
        "load_transcript",
        "transcripts",
        "workshop_data",
        "assemble_workshop_data",
    ):
        assert reader not in source, (
            f"the ministry register export reaches stage content through `{reader}` — taking stage "
            "data out of the product is DESIGN_WORKSHOP_DATA_EXPORT_ROLES, which refuses every tier "
            "this route admits. Get the export role and a decision record, not a new read."
        )

    # And every cell it writes comes from one of exactly three places: the promoted header
    # (`workshop_summary`), the completeness roll-up, and the artisan count. Nothing else is in
    # scope in that function, so nothing else can reach a column.
    assert "workshop_summary(record)" in source
    assert "progress.get(workshop_id)" in source
    assert "beneficiaries.get(workshop_id" in source


def test_the_beneficiary_export_is_the_shared_one_and_masks_by_construction() -> None:
    """It is ``/api/export/artisans.csv`` — the third sibling of products and tools — and not a
    ministry-specific door.

    ``can_download_dataset`` is Professor and above or the grant, and the three ministry posts sit at
    42, 45 and 48. So the gate that already governs taking records out of this product admits the
    whole audience without being touched, and there is one answer to "who may take the artisan list"
    rather than two. Its columns come from ``record_fields.ARTISAN``, where both regulated numbers go
    through ``mask_aadhaar``.
    """
    from app.api.routes import export
    from app.core.deps import can_download_dataset
    from app.services.record_fields import ARTISAN

    paths = {r.path for r in export.router.routes}
    assert "/export/artisans.csv" in paths, "the beneficiary export is gone"

    for role in ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "MASTER_ADMIN"):
        assert can_download_dataset(_user(role)) is True, role

    identity_labels = {
        field.label for field in ARTISAN.fields if "aadhaar" in field.label.lower() or "pehchan" in field.label.lower()
    }
    assert identity_labels, "the artisan registry no longer declares the regulated identity columns"
    source = (REPO / "backend/app/services/record_fields.py").read_text(encoding="utf-8")
    assert "mask_aadhaar(a.aadhaarNumber)" in source
    assert "mask_aadhaar(a.pehchanCardNumber)" in source


def test_the_page_says_what_the_download_does_and_does_not_hold() -> None:
    """SAY IT ON SCREEN, NEVER 403 A BUTTON — ``can_export_design_workshop_data``'s standing rule.

    The sentence beside the buttons is what stops a reader sending a file believing it contains
    something it does not, and what stops a refusal arriving after the click.
    """
    page = PAGE_TSX.read_text(encoding="utf-8")
    assert "no stage content" in page
    assert "masked" in page
    assert "beneficiariesRefusal" in page
