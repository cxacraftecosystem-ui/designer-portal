"""THE MINISTRY DASHBOARD'S DOOR, held to the client's and to the register it hands over.

No database, no browser. Every assertion here either calls a pure predicate or reads a file off
disk, so this runs in the gating ``Backend tests`` job on a machine whose DSN is ``ci.invalid``.

═══════════════════════════════════════════════════════════════════════════════════════════════
WHAT THIS FILE IS FOR, WHICH IS THREE DIFFERENT PROMISES
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

3. **THE PEOPLE REGISTERS NAME PEOPLE AND JUDGE NONE OF THEM.** ``/designers``, ``/officers`` and
   ``/inspectors`` answer the owner's ask that this dashboard stop knowing nothing about the people
   running the programme, and every one of them is a list of NAMED INDIVIDUALS handed to a tier as
   junior as rank 42. Three rules hold them, and the last section of this file asserts all three:

   * **Four identity keys and no roster judgement** — ``assignable_designers_payload``'s ``id``,
     ``name``, ``email``, ``role``. No ``rosterActive``, ``canSignIn``, ``firstSeenAt``,
     ``institution``, ``rosterId`` or ``hasProfile``: those are facts about the EMPANELMENT table,
     which ``can_manage_designer_roster`` (``is_admin``) stands in front of.
   * **No gate was widened to feed the page.** The privileged tiers are named by nobody
     (``include_admins=False`` as a disclosure boundary), and the two account directories are
     offered only where ``OVERSIGHT_ASSIGNER_ROLES`` already admits the caller. That equality is
     asserted rather than assumed, because if the two sets ever part, this page becomes a URL that
     gate has never heard of.
   * **Never a zero nobody measured.** These lists are an AGGREGATE over a capped scan of workshops,
     so a silent cap is not a shorter list — it is every number in it meaning something else. The
     scan report, the three progress reasons and the withheld count are all pinned here.

   Same structural style as promise 2, and for its reason: a name appearing in a file is not the
   same fact as a name reaching a payload, so the sweeps read the parsed body with the DOCSTRING
   REMOVED — these routes document the keys they refuse BY NAME, and a raw text search would fail on
   the very paragraph promising the thing it is checking for.
"""

from __future__ import annotations

import ast
import inspect
import re
import textwrap
from pathlib import Path
from types import SimpleNamespace

from app.api.routes import ministry_dashboard as route
from app.core.deps import (
    MINISTRY_DASHBOARD_REFUSAL,
    MINISTRY_DASHBOARD_ROLES,
    ROLE_RANK,
    can_see_ministry_dashboard,
)
from app.services import designers, ministry_dashboard as register
from app.services.design_workshop_oversight import OVERSIGHT_ASSIGNER_ROLES
from app.services.sanction_orders import can_record_sanction_orders

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


# ────────────────────────────────────────────────────────────────────────────────────────────────
# THE THREE PEOPLE REGISTERS — designers, officers, inspectors
#
# The router-wide sweeps above already cover these three routes, and that is deliberate: a sweep is
# what makes a route added next year visible. What the sweeps CANNOT catch is the three routes being
# deleted, renamed or moved to another prefix, at which point every sweep passes over what is left.
# So the paths are pinned here as well, and each route gets a test of the one promise a sweep has no
# opinion about — the SHAPE of the rows it hands over.
# ────────────────────────────────────────────────────────────────────────────────────────────────

#: The three reads this section is about, by their full path.
_PEOPLE_PATHS = (
    "/ministry-dashboard/designers",
    "/ministry-dashboard/officers",
    "/ministry-dashboard/inspectors",
)

#: Keys that must never reach a people row. Every one is a fact about ``DesignerRoster`` — the
#: EMPANELMENT table — which ``can_manage_designer_roster`` (``is_admin``) stands in front of, and an
#: officer reading this page is rank 42. ``assignable_designers_payload``'s own docstring gives the
#: rule in one line: *"suspended" is a judgement the institution made about a person, and it is not
#: an officer's business which designers have one.*
_ROSTER_JUDGEMENTS = (
    "rosterActive",
    "canSignIn",
    "firstSeenAt",
    "institution",
    "rosterId",
    "hasProfile",
)


def _account(user_id: str, name: str, email: str, role: str) -> SimpleNamespace:
    """A ``User`` row as the relation reads hand it over."""
    return SimpleNamespace(id=user_id, name=name, email=email, role=role)


def _function_body(function: object) -> ast.AST:
    """One function's parsed body with its DOCSTRING REMOVED.

    ⚠ **THE DOCSTRING HAS TO GO OR THE SWEEPS BELOW ARE BACKWARDS.** These routes document the keys
    they refuse BY NAME — "NO ``rosterActive``, NO ``canSignIn`` …" — which is exactly the prose this
    file exists to hold the code to. A raw ``inspect.getsource`` sweep would therefore fail on the
    paragraph promising the thing it is checking for, and the obvious fix (delete the paragraph)
    would delete the rule. Parsing and dropping the docstring keeps both.

    Comments go too, because ``ast`` never sees them — which is the same protection for the ⚠ notes
    in the bodies.
    """
    tree = ast.parse(textwrap.dedent(inspect.getsource(function)))
    node = tree.body[0]
    body = list(getattr(node, "body", []))
    if (
        body
        and isinstance(body[0], ast.Expr)
        and isinstance(body[0].value, ast.Constant)
        and isinstance(body[0].value.value, str)
    ):
        body = body[1:]
    return ast.Module(body=body, type_ignores=[])


def _code_of(function: object) -> str:
    """The function's code as text, docstring and comments gone. See :func:`_function_body`."""
    return ast.unparse(_function_body(function))


def _calls_guarded_by_try(function: object) -> set[str]:
    """Every dotted call name that appears INSIDE a ``try`` in this function.

    This is the other half of "degrade per read": the optional reads must each be guarded, and the
    PRIMARY ones must not be. A register whose link read was quietly swallowed answers a cheerful
    200 carrying an empty list, which on this screen says the programme has nobody in it — the
    failure a shim exists to prevent, arriving through the shim.
    """
    guarded: set[str] = set()
    for node in ast.walk(_function_body(function)):
        if not isinstance(node, ast.Try):
            continue
        for statement in node.body:
            for inner in ast.walk(statement):
                if isinstance(inner, ast.Call):
                    guarded.add(ast.unparse(inner.func))
    return guarded


def _keys_written_to(function: object, name: str) -> set[str]:
    """Every literal key this function ASSIGNS into ``<name>[...]``.

    STRUCTURAL AND NOT A STRING SEARCH, for the reason
    ``test_the_register_export_reads_no_stage_content`` records about its own first attempt: a name
    appearing in a file is not the same fact as a name reaching a payload. ``ctx`` is what separates
    the two — a read of ``row["id"]`` is a ``Load`` and never puts a key on the wire.
    """
    written: set[str] = set()
    for node in ast.walk(_function_body(function)):
        if (
            isinstance(node, ast.Subscript)
            and isinstance(node.ctx, ast.Store)
            and isinstance(node.value, ast.Name)
            and node.value.id == name
        ):
            for inner in ast.walk(node.slice):
                if isinstance(inner, ast.Constant) and isinstance(inner.value, str):
                    written.add(inner.value)
    return written


# ── The door, the method and the shape of the path ──────────────────────────────────────────────


def test_the_three_people_registers_are_declared_on_this_prefix() -> None:
    """Pinned by path, because the sweeps above pass vacuously over a router they were deleted from.

    The owner's ask was that this dashboard stop knowing nothing about people, and the three reads
    are the answer to it: who runs the workshops, who oversees them, who inspects them.
    """
    paths = {api_route.path for api_route in route.router.routes}
    for path in _PEOPLE_PATHS:
        assert path in paths, f"{path} is gone from the ministry dashboard"


def test_the_people_registers_are_reads_behind_the_gate_with_no_id_segment() -> None:
    """The three router-wide promises, asserted again against these three routes BY NAME.

    The sweeps above are the general rule and this is the specific one, and the duplication is the
    point: a sweep reports "some route on this prefix is wrong" while this reports WHICH, and these
    are the three routes most likely to grow a ``/{user_id}`` — "what is this one officer
    overseeing" is the obvious next request and is exactly the second door onto one person's
    postings the module docstring refuses.
    """
    for api_route in route.router.routes:
        if api_route.path not in _PEOPLE_PATHS:
            continue
        assert set(api_route.methods) == {"GET"}, f"{api_route.path} is not a read"
        assert "{" not in api_route.path, f"{api_route.path} is parameterised"
        names = {
            dependency.call.__name__
            for dependency in api_route.dependant.dependencies
            if getattr(dependency, "call", None) is not None
        }
        assert "require_ministry_dashboard_reader" in names, (
            f"{api_route.path} is on the ministry prefix without the gate"
        )


def test_the_people_registers_widen_no_gate_and_grow_no_rank_floor() -> None:
    """**THE ONE MISTAKE IN THIS FEATURE THAT WOULD BE SILENT**, and it has three shapes.

    ``routes/sanction_orders.list_sanction_designers`` records the standing ruling: a tier that needs
    a list gets **a fifth door with a narrower payload, never a widened gate**. The three names below
    are the three gates a people register is tempted to reach for, and every one of them would be
    granting something this page has no business granting:

    * ``can_manage_designer_roster`` is ``is_admin`` and stands in front of the EMPANELMENT table —
      an account that could reach it could end a designer's sign-in.
    * ``require_workshop_assigner`` is ``OVERSIGHT_ASSIGNER_ROLES``, which excludes a
      REGIONAL_DIRECTOR on purpose: the supervised must not choose the supervisor.
    * ``has_rank`` is the shape ``MINISTRY_DASHBOARD_ROLES`` cannot be written in at all — the set
      has a hole at ADMIN, and every floor fails (see the two tests at the top of this file).
    """
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        code = _code_of(function)
        for gate in (
            "can_manage_designer_roster",
            "require_workshop_assigner",
            "require_admin",
            "has_rank",
            "designerroster",
        ):
            assert gate not in code, (
                f"{function.__name__} reaches {gate!r}. The answer to a tier needing a list is a "
                "fifth door with a narrower payload, never a widened gate — see "
                "routes/sanction_orders.list_sanction_designers."
            )


def test_the_people_registers_compose_the_scope_under_and_and_never_beside_the_search() -> None:
    """``where["OR"]`` IS THE SEARCH BOX'S AND ASSIGNING A SCOPE TO IT HANDS OVER THE COUNTRY.

    Two assignments to one dict key: the later silently wins. Written onto ``OR``, the scope vanishes
    and an Assistant Director reads every workshop on the platform — with nothing on screen to say
    so, because the page looks exactly as healthy either way. The three routes therefore do not build
    a ``where`` at all: they call ``_design_where``, which is the one place the composition order is
    written down.
    """
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        code = _code_of(function)
        assert "_design_where(" in code, (
            f"{function.__name__} builds its own where clause instead of using the composer that "
            "puts the scope on AND"
        )
        assert "'OR'" not in code and '"OR"' not in code, (
            f"{function.__name__} names where['OR'] — the search box owns that key"
        )


# ── The scope, per list, in words ───────────────────────────────────────────────────────────────


def test_the_people_scope_sentence_never_claims_the_estate_to_an_officer() -> None:
    """The sentence that stops three colleagues reading as the whole directorate.

    It is a SEPARATE sentence from ``scope_label`` and not a reuse of it, and that is this router's
    hardest-won rule: ``register_summary`` shipped one caption over two differently-scoped counts.
    "The workshops you were named on" printed over a list of PEOPLE does not tell an Assistant
    Director that a colleague running twenty workshops elsewhere is missing from it — and the counts
    beside each name being counts within their own postings is a second fact the sentence has to
    carry, which the workshop-list sentence has no reason to.
    """
    for noun in ("designer", "officer", "inspector"):
        for folded in (True, False):
            estate = register.people_scope_label(
                _user("MINISTRY_ADMIN"), noun=noun, includes_unposted=folded
            )
            posted = register.people_scope_label(
                _user("ASSISTANT_DIRECTOR"), noun=noun, includes_unposted=folded
            )
            assert estate != posted
            assert noun in estate and noun in posted
            assert "on the platform" in estate
            assert "named on as Assistant Director or Regional Director" in posted
            assert "WITHIN your postings" in posted, (
                "the officer's sentence does not say the counts are narrowed, only the list"
            )
            # And it is not the workshop register's sentence wearing a new name.
            assert estate != register.scope_label(_user("MINISTRY_ADMIN"))
            assert posted != register.scope_label(_user("ASSISTANT_DIRECTOR"))


def test_the_scope_sentence_changes_when_an_account_directory_is_folded_in() -> None:
    """⚠ **A CAPTION THAT CONTRADICTS A ROW THE READER CAN SEE IS HOW A CAPTION STOPS BEING
    BELIEVED**, and this page has nothing else standing between four rows and the size of a
    directorate.

    Two of these registers fold an account directory in, and when they do, "a designer working solely
    on workshops you were not posted to is absent from this list" is no longer true — that designer
    is on screen with a measured zero. The sentence therefore takes the fold as an argument rather
    than describing the relation it was written for.
    """
    for noun in ("designer", "officer", "inspector"):
        for role in ("MINISTRY_ADMIN", "ASSISTANT_DIRECTOR"):
            folded = register.people_scope_label(
                _user(role), noun=noun, includes_unposted=True
            )
            relation_only = register.people_scope_label(
                _user(role), noun=noun, includes_unposted=False
            )
            assert folded != relation_only, (role, noun)
            assert "measured zero" in folded
            assert "absent" in relation_only or "does not appear here" in relation_only
            # The claim that would be FALSE beside a folded row is not made in the folded sentence.
            assert "absent from this list entirely" not in folded


def test_every_people_list_carries_its_own_scope_and_scope_label() -> None:
    """Both keys, on every one of the three, because the two halves of this screen are scoped
    differently and one caption over both was a shipped bug.

    ⚠ ASKED OF THE PARSED BODY AND NOT OF THE SOURCE TEXT. ``ast.unparse`` normalises every string
    literal to single quotes, so a search for ``'"scope"'`` is a search for a spelling the code has
    already lost — which is how the first version of this test failed against a payload that was
    perfectly correct. :func:`_keys_written_to` asks what reaches the dict instead.
    """
    keys = _keys_written_to(route._people_envelope, "payload")
    assert {"scope", "scopeLabel"} <= keys
    assert "people_scope_label" in _code_of(route._people_envelope)
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        assert "_people_envelope(" in _code_of(function), (
            f"{function.__name__} builds its own envelope and can lose the scope sentence"
        )


# ── The payload: four identity keys, measurements, and no judgement about a person ──────────────


def test_a_people_row_is_four_identity_keys_and_measurements_and_nothing_about_the_roster() -> None:
    """``assignable_designers_payload``'s four keys, and the ABSENCE OF THE REST IS THE POINT.

    Every name in ``_ROSTER_JUDGEMENTS`` is a fact about the empanelment table, which is the admin's
    and not the officer's. The suspended are already gone before the roster fold runs — the
    ``WHERE`` in ``workshop_capable_accounts`` drops them — so the payload does not need a flag to be
    SAFE, and must not EXPLAIN one to be honest.
    """
    row = register.new_person_row(_account("d1", "Rekha", "rekha@example.org", "DESIGNER"))
    assert row["id"] == "d1"
    assert row["name"] == "Rekha"
    assert row["email"] == "rekha@example.org"
    assert row["role"] == "DESIGNER"
    for forbidden in _ROSTER_JUDGEMENTS:
        assert forbidden not in row, f"a people row carries {forbidden}, which is a roster judgement"

    # The identity half is BORROWED and not retyped, so the four keys cannot drift from the picker.
    assert "assignable_designers_payload" in _code_of(register.new_person_row)

    # NEVER A ZERO WE DID NOT MEASURE. The counters are measured by construction — a person is in
    # the register because a relation put them there — and the progress figures are not.
    assert row["workshops"] == 0
    assert row["registered"] == row["ongoing"] == row["completed"] == 0
    assert row["percent"] is None
    assert row["requiredTotal"] is None
    assert row["stagesComplete"] is None


def test_the_designer_register_adds_only_the_two_link_counts_it_documents() -> None:
    """ONE TEST PER ROUTE THAT THE PAYLOAD CARRIES NOTHING THE DISCIPLINE FORBIDS, and this is the
    designers'.

    The two extra keys are the two ARMS of the designer relation. ``DesignWorkshop`` has no designer
    foreign key, so "who runs this workshop" is ``DesignWorkshopViewer`` UNION ``createdById`` — and
    ``named_designer_rows`` records in capitals that the creator holds no viewer row, which is why
    the second arm exists and why it will look redundant to the next reader. Reporting them apart
    matters: "she opened nine and was added to two" and "she was added to eleven" are different facts
    about who is running a workshop.
    """
    assert _keys_written_to(route.list_designers, "row") == {
        "workshopsCreated",
        "workshopsNamedOn",
    }
    assert _keys_written_to(route.list_designers, "payload") == {
        "unpostedAccountsTruncated",
        # Added 2026-09-20. Not a column on a person — a CAVEAT about who the register cannot
        # contain at all. See the test below, and `register.roster_representation`.
        "rosterRepresentation",
        "rosterRepresentationNote",
    }
    code = _code_of(route.list_designers)
    for forbidden in _ROSTER_JUDGEMENTS:
        assert forbidden not in code
    # Counted over the RELATIONS and never over the denormalised string.
    assert "designer_links" in code and "creator_accounts" in code
    assert "designerName" not in code, (
        "the designer register groups on the promoted stage-1 string, which is free-typed, "
        "unindexed and not an account — see the services module's people-register header"
    )


# ── The roster this register cannot contain, counted rather than guessed ────────────────────────


def test_the_designer_register_says_how_much_of_the_roster_it_cannot_show() -> None:
    """**THE ANSWER TO "35 ON THE ROSTER PAGE, 9 HERE, WHY?"**, asserted so it cannot go quiet again.

    Measured against production on 2026-09-20. The nine was arithmetically CORRECT — 24 of the
    thirty-five empanelled addresses had no ``User`` row at all and 2 held an account under another
    role, one of them an administrator this register withholds by design. Every number on the
    payload was right and the screen still could not be believed, because nothing accounted for the
    other twenty-six.

    ``empanelled_designer_accounts`` reads ``User`` and must: every column this register prints
    hangs off an account. ``DesignerRoster`` is keyed by EMAIL and is an invitation. So the gap is
    structural, it can never be closed by listing harder, and the only honest repair is to COUNT it
    and say so.
    """
    assert hasattr(register, "roster_representation")
    assert hasattr(register, "roster_representation_note")
    code = _code_of(route.list_designers)
    assert "roster_representation" in code, (
        "the designers route stopped measuring how much of the roster it cannot show"
    )
    # Its own shim, like every other optional read here: the caveat may fail without costing the
    # register, and the client is told which.
    assert "_representation_or_none" in code


def test_the_roster_gap_derives_its_role_set_and_names_no_role() -> None:
    """**NOTHING IS HARDCODED**, which the owner asked for by name.

    A literal ``"DESIGNER"`` in the gap calculation would be a second opinion about who this
    register lists, and it would stop agreeing with the fold the day a second workshop-capable role
    is added — the gap would then report people as missing who are on screen. The eligible set is
    recomputed from ``designers.workshop_capable_roles()`` minus the never-roster-gated tiers, which
    is the SAME expression ``empanelled_designer_accounts`` produces by passing
    ``include_admins=False``.
    """
    code = _code_of(register.roster_representation)
    assert "workshop_capable_roles()" in code
    assert "NEVER_ROSTER_GATED_ROLES" in code
    for role in ROLE_RANK:
        assert f'"{role}"' not in code, (
            f"{role!r} is written out in roster_representation. The eligible set is derived, not "
            "named — see this test's docstring."
        )
    # And it reads the same roster the fold reads, rather than a second opinion about who is
    # empanelled.
    assert "active_roster_emails" in code


def test_the_roster_gap_discloses_counts_and_never_an_address() -> None:
    """The whole point of ``include_admins=False`` is that this router never names a privileged
    account. An "empanelled but absent" LIST would hand over exactly the roster addresses that flag
    exists to keep back, to a tier refused every other designer directory. A number discloses
    nothing and is the entire answer to "why nine"."""
    code = _code_of(register.roster_representation)
    assert "assignable_designers_payload" not in code, (
        "the gap started shaping people ROWS; it reports counts only — see this test's docstring"
    )
    # The report is a returned dict literal rather than a mutated one, so it is read as source:
    # every value it carries is a count or a flag, and there is nowhere for a name to travel.
    for name in ("rosterAdmitted", "rosterWithoutAccount", "rosterOtherRole", "rosterReadTruncated"):
        assert name in code, f"{name} is gone from the representation report"
    for leak in ("email", "name", "fullName"):
        assert f'"{leak}"' not in code, f"{leak!r} is being put on the wire by the gap report"


def test_a_failed_gap_measurement_never_reads_as_nothing_missing() -> None:
    """NULL IS NOT ZERO, one surface further out than ``_roll_up`` argues it.

    A measurement that failed must not render as "every empanelled designer is on screen" — that is
    the one answer this caption must never give by accident, and a zero would give it. The route
    writes the figures as ``None`` and swaps in a sentence that says the measurement failed.
    """
    code = _code_of(route.list_designers)
    assert "if representation is None:" in code
    assert "could not be measured" in code


def test_the_gap_sentence_is_absent_when_nothing_is_missing() -> None:
    """A caveat that is always on screen is a caveat nobody reads, so the note is ``None`` when the
    register accounts for the whole roster — and the client renders nothing rather than an empty
    box."""
    assert register.roster_representation_note(
        {"rosterAdmitted": 9, "rosterWithoutAccount": 0, "rosterOtherRole": 0, "rosterReadTruncated": False},
        9,
    ) is None

    note = register.roster_representation_note(
        {"rosterAdmitted": 35, "rosterWithoutAccount": 24, "rosterOtherRole": 2, "rosterReadTruncated": False},
        9,
    )
    assert note is not None
    # The production numbers, in the sentence, derived from the arguments and not from a literal.
    assert "24" in note and "2" in note and "9" in note and "35" in note
    # It names the REASON rather than only the number: "24 are missing" invites a bug report.
    assert "have not created an account yet" in note


def test_a_cut_roster_read_says_the_gap_is_a_floor() -> None:
    """A gap computed from a roster that was cut at its own ceiling UNDERSTATES itself, which is the
    one failure mode that would make this number worse than no number at all."""
    note = register.roster_representation_note(
        {"rosterAdmitted": 500, "rosterWithoutAccount": 400, "rosterOtherRole": 0, "rosterReadTruncated": True},
        100,
    )
    assert note is not None and "floor" in note


def test_the_officer_register_adds_only_the_capacity_breakdown_it_documents() -> None:
    """The officers' half of the same promise.

    ``byCapacity`` is seeded from ``CAPACITIES`` rather than written out, so a third capacity — an
    ``ALTER TYPE`` plus an entry in ``OVERSIGHT_CAPACITY_ROLES`` — appears here without a code
    change; ``unknownCapacity`` catches one a server a release ahead can legitimately store, the same
    reasoning ``group_of`` carries for a ninth status.
    """
    assert _keys_written_to(route.list_officers, "row") == {"byCapacity", "unknownCapacity"}
    assert _keys_written_to(route.list_officers, "payload") == {
        "unpostedAccountsTruncated",
        "capacities",
    }
    code = _code_of(route.list_officers)
    for forbidden in _ROSTER_JUDGEMENTS:
        assert forbidden not in code
    assert "oversight_links" in code
    assert "officers.CAPACITIES" in code, (
        "the capacity keys are written out rather than seeded from the enum's own tuple"
    )


def test_the_inspector_register_adds_only_the_two_feedback_counts_it_documents() -> None:
    """The inspectors' half, and the two numbers are two facts.

    ``DwInspectionFeedback.sentBack`` is true only on the suggestion that moved a workshop to
    NEEDS_REVISION; the rest are suggestions on an open round. Forty notes with no send-back and
    forty with thirty are different jobs, and one "feedback" number cannot tell them apart.
    """
    assert _keys_written_to(route.list_inspectors, "row") == {"feedbackFiled", "sendBacks"}
    assert _keys_written_to(route.list_inspectors, "payload") == {
        "unpostedAccountsTruncated",
        "feedbackRead",
        "feedbackFiledTotal",
        "feedbackAttributed",
        "feedbackByAccountsNotListed",
        "feedbackNote",
    }
    code = _code_of(route.list_inspectors)
    for forbidden in _ROSTER_JUDGEMENTS:
        assert forbidden not in code
    assert "inspector_links" in code and "inspection_feedback_counts" in code


def test_the_envelope_keys_are_pinned_so_a_people_list_cannot_lose_its_disclosures() -> None:
    """The shared half of all three payloads, pinned as a literal.

    Each of these keys is a sentence-or-number the screen owes its reader, and every one of them is
    the kind that disappears silently: a scan report nobody prints, a ``progressRead`` nobody checks,
    a withheld count nobody adds up. Pinning the set is what makes deleting one a decision.
    """
    assert _keys_written_to(route._people_envelope, "payload") == {
        "scope",
        "scopeLabel",
        "standingVocabulary",
        "standingGroups",
        "scan",
        "progressScoreCap",
        "progressRead",
        "progressNote",
        "withheldAccounts",
        "withheldAccountsNote",
        "includesUnpostedAccounts",
        "unpostedAccountsNote",
    }


# ── The privileged tiers are named by nobody, and the withholding is counted ────────────────────


def test_no_people_register_names_a_privileged_account() -> None:
    """``include_admins=False`` AS A DISCLOSURE BOUNDARY, applied to a register keyed on relations.

    ``list_sanction_designers`` records what it is for: a door that opened at a rank floor of
    ASSISTANT_DIRECTOR would otherwise hand *"the complete privileged-account directory of the
    installation"* to that tier. This router's floor is the same 42, and an ADMIN who opened one
    workshop would appear here by name, address and role.

    Read off ``designers.NEVER_ROSTER_GATED_ROLES`` so there is one list of "the privileged tiers"
    rather than two that can disagree.
    """
    assert set(register.WITHHELD_PERSON_ROLES) == set(designers.NEVER_ROSTER_GATED_ROLES)
    assert set(register.WITHHELD_PERSON_ROLES) == {"ADMIN", "MASTER_ADMIN"}
    for role in ROLE_RANK:
        expected = role in {"ADMIN", "MASTER_ADMIN"}
        assert register.is_withheld_person(_user(role)) is expected, role


def test_the_withheld_accounts_are_counted_and_the_count_is_on_the_wire() -> None:
    """A person dropped without a word is the truncation bug wearing a permissions hat.

    Their workshops are still inside ``workshopsRead``, so a reader adding the rows up finds them
    short with nothing to explain the difference. The COUNT names nobody, which is the whole reason
    it can be reported at all.
    """
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        code = _code_of(function)
        assert "is_withheld_person" in code, f"{function.__name__} does not apply the boundary"
        assert "withheld=len(withheld)" in code, (
            f"{function.__name__} withholds accounts without reporting how many"
        )


# ── Never a zero we did not measure ─────────────────────────────────────────────────────────────


def test_a_person_with_no_workshop_says_so_rather_than_reporting_nought_per_cent() -> None:
    """THREE ABSENCES, THREE FACTS, AND THE THIRD ONE IS WHY THE ROSTER IS FOLDED IN.

    An empanelled designer who has been given nothing is the most actionable row on a ministry's
    chasing screen. A register built only from workshop links cannot contain them at all — they would
    be indistinguishable from somebody who does not exist — and a register that contained them
    showing 0% would be accusing them of having done none of the work they were never given.
    """
    idle = register.new_person_row(_account("d0", "Nobody Yet", "n@x.y", "DESIGNER"))
    register.close_person_progress(idle, scoring_read=True)
    assert idle["percent"] is None
    assert idle["progressReason"] == "noWorkshops"

    capped = register.new_person_row(_account("d1", "Busy", "b@x.y", "DESIGNER"))
    capped["workshops"] = 4
    register.close_person_progress(capped, scoring_read=True)
    assert capped["percent"] is None
    assert capped["progressReason"] == "capped"

    unread = register.new_person_row(_account("d2", "Busy Too", "b2@x.y", "DESIGNER"))
    unread["workshops"] = 4
    register.close_person_progress(unread, scoring_read=False)
    assert unread["percent"] is None
    assert unread["progressReason"] == "unreadable"

    # All three are distinct words, because they suggest three different next moves.
    assert len({idle["progressReason"], capped["progressReason"], unread["progressReason"]}) == 3


def test_the_person_roll_up_is_the_registers_arithmetic_to_the_rounding() -> None:
    """Sum filled over sum total across a person's SCORED workshops — ``_roll_up``'s rule, one level
    up — and a zero denominator reads as complete rather than as 0%.

    Averaging the per-workshop percentages instead would weight a workshop with four required fields
    the same as one with two hundred, and would disagree with the register's own rows on the tab
    beside this one.
    """
    row = register.new_person_row(_account("d1", "Rekha", "r@x.y", "DESIGNER"))
    row["workshops"] = 3
    register.add_score(row, {"requiredTotal": 10, "requiredFilled": 5, "stagesComplete": 1, "stagesTotal": 6})
    register.add_score(row, {"requiredTotal": 30, "requiredFilled": 30, "stagesComplete": 6, "stagesTotal": 6})
    # A workshop the request did not score contributes NOTHING and is not counted as scored, which is
    # what keeps `workshopsScored` an honest denominator beside the percentage.
    register.add_score(row, None)
    register.add_score(row, register.unscored("capped"))
    register.close_person_progress(row, scoring_read=True)

    assert row["workshopsScored"] == 2
    assert row["requiredTotal"] == 40
    assert row["requiredFilled"] == 35
    assert row["percent"] == round(100 * 35 / 40)
    assert row["stagesComplete"] == 7

    nothing_required = register.new_person_row(_account("d2", "B", "b@x.y", "DESIGNER"))
    nothing_required["workshops"] = 1
    register.add_score(
        nothing_required,
        {"requiredTotal": 0, "requiredFilled": 0, "stagesComplete": 0, "stagesTotal": 6},
    )
    register.close_person_progress(nothing_required, scoring_read=True)
    assert nothing_required["percent"] == 100


def test_a_capped_scan_says_so_in_a_sentence_and_a_complete_one_says_nothing() -> None:
    """These lists are an AGGREGATE over the workshops one request read, so a silent cap is not a
    shorter list — it is every number in it meaning something else.

    The note is a sentence rather than a boolean for the reason the summary's ⚠ gives: a caption
    composed in the browser drifts from the decision the server took, and this caption has the harder
    job of saying that the COUNTS are partial and not merely that the LIST is.
    """
    whole = register.scan_report(12, 12)
    assert whole["truncated"] is False
    assert whole["truncationNote"] is None
    assert whole["workshopsInScope"] == 12 and whole["workshopsRead"] == 12

    cut = register.scan_report(900, register.PEOPLE_WORKSHOP_SCAN)
    assert cut["truncated"] is True
    assert "900" in cut["truncationNote"]
    assert str(register.PEOPLE_WORKSHOP_SCAN) in cut["truncationNote"]
    assert "count within the workshops that were read" in cut["truncationNote"], (
        "the note says the LIST was cut and not that the COUNTS are partial, which is the half a "
        "reader cannot infer"
    )


# ── Ordering is total ───────────────────────────────────────────────────────────────────────────


def test_the_people_scan_order_is_the_registers_order_tiebreak_included() -> None:
    """The duplicated literal, held equal, because a service must not import its own router.

    On a CAPPED SCAN the ``id`` tiebreak is load-bearing in a way it is not on a page: without it,
    WHICH five hundred workshops fall inside the cap is Postgres's choice among rows sharing an
    ``updatedAt``, so two identical requests scan two different sets and a designer's workshop count
    changes on refresh with nothing on screen to say why.
    """
    assert register.PEOPLE_SCAN_ORDER == route._DESIGN_ORDER
    assert register.PEOPLE_SCAN_ORDER[-1] == {"id": "asc"}


def test_the_people_order_is_total_so_a_paged_register_serves_nobody_twice() -> None:
    """Busiest first, then the alphabet, then the account id — and the id is the one that matters.

    Display names in ``User`` are not unique. Two people with the same name and the same workshop
    count would otherwise sort in whatever order the accumulator happened to be built in, which is
    insertion order over a relation read; a row that changes side of the page cut between two
    requests is handed over twice or never, and either way the response looks perfectly healthy.
    """
    def _row(user_id: str, name: str, workshops: int) -> dict:
        row = register.new_person_row(_account(user_id, name, f"{user_id}@x.y", "DESIGNER"))
        row["workshops"] = workshops
        return row

    rows = [_row("b", "Same Name", 2), _row("a", "Same Name", 2), _row("c", "Busier", 9)]
    ordered = [row["id"] for row in register.order_people(rows)]
    assert ordered == ["c", "a", "b"]
    # Total means the INPUT order cannot change the answer.
    assert [row["id"] for row in register.order_people(list(reversed(rows)))] == ordered


# ── The two account directories, and the gate that already governs them ─────────────────────────


def test_the_unposted_directories_are_offered_exactly_where_their_own_gate_already_admits() -> None:
    """**NOTHING WAS WIDENED TO MAKE AN UNPOSTED OFFICER VISIBLE**, and this is the proof.

    ``officer_directory`` and ``eligible_inspectors`` are each served today behind
    ``require_workshop_assigner`` — ``OVERSIGHT_ASSIGNER_ROLES`` — and that set is, member for
    member, the one ``sees_whole_estate`` already answers True for. So an estate reader is handed a
    list they can already open elsewhere, and the two directorate tiers are refused it exactly as
    that gate refuses them: ``OVERSIGHT_ASSIGNER_ROLES`` excludes a REGIONAL_DIRECTOR on purpose,
    because the supervised must not choose the supervisor.

    ⚠ IF THIS TEST EVER FAILS, THE FIX IS NOT TO EDIT THE EXPECTATION. It means the two sets have
    parted, and the people registers would then be reading a national directory to a tier its own
    gate refuses — through a URL that gate has never heard of.
    """
    assert set(OVERSIGHT_ASSIGNER_ROLES) == {"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}
    for role in ROLE_RANK:
        assert register.may_read_account_directories(_user(role)) is (
            role in OVERSIGHT_ASSIGNER_ROLES
        ), role
    # And it is the same predicate the workshop registers scope by, not a second answer to one
    # question.
    for role in ROLE_RANK:
        assert register.may_read_account_directories(_user(role)) is register.sees_whole_estate(
            _user(role)
        )


def test_the_designer_roster_fold_reuses_a_door_every_tier_here_already_clears() -> None:
    """The ONE directory offered to all four tiers, and it widens nothing either.

    ``GET /sanction-orders/designers`` is ``require_sanction_recorder`` —
    ``can_record_sanction_orders``, a RANK FLOOR at ASSISTANT_DIRECTOR (42) — and it answers this
    exact call with these exact arguments. Every tier this router admits sits at or above that floor,
    so folding the same row set in here discloses nothing new to anybody.

    ``include_admins=False`` is the half that keeps it true: without it the answer is every empanelled
    designer PLUS every privileged account in the installation, which is the defect
    ``list_sanction_designers`` records having been fixed on 2026-09-16.
    """
    for role in MINISTRY_DASHBOARD_ROLES:
        assert can_record_sanction_orders(_user(role)) is True, role
    code = _code_of(register.empanelled_designer_accounts)
    assert "include_admins=False" in code, (
        "the roster fold would hand the privileged-account directory to rank 42"
    )
    assert "include_suspended=False" in code


def test_a_directory_that_could_not_be_read_is_not_reported_as_an_empty_one() -> None:
    """THREE STATES AND THREE SENTENCES: not offered, offered-and-read, offered-and-FAILED.

    "This tier does not get the directory", "nobody is unposted" and "we could not ask" are three
    different facts, and a boolean would collapse the first and the third into the second. The route
    keeps them apart by deciding ``offered`` BEFORE the shim, so a ``None`` coming back from a caller
    who was offered the read means the read failed and nothing else.
    """
    for function in (route.list_officers, route.list_inspectors):
        code = _code_of(function)
        assert "may_read_account_directories" in code
        assert "if not offered:" in code, (
            f"{function.__name__} cannot tell a refused directory from a failed one"
        )
        assert "could not be read for this request" in code, (
            f"{function.__name__} has no sentence for the directory read that failed"
        )


# ── Degrading per read, never per page ──────────────────────────────────────────────────────────


def test_every_optional_people_read_has_its_own_shim_and_the_primary_reads_have_none() -> None:
    """``_design_rows`` records what the first attempt at this got wrong — one guard around a
    ``gather_reads`` of two coroutines, whose ``except`` then RE-RAN one of them, so a roster read
    that was down stayed down and the whole register 500'd through the handler written to stop
    exactly that.

    The shape copied here is one shim per optional read, gathered: the round trips still overlap and
    none of them can take another down. The workshop scan and the link read are deliberately
    UNGUARDED — without either there is no register, and a 200 carrying an empty list would say the
    programme has no designers in it.
    """
    for function, shims in (
        (route.list_designers, ("_people_progress_or_none", "_roster_or_none")),
        (route.list_officers, ("_people_progress_or_none", "_directory_or_none")),
        (
            route.list_inspectors,
            ("_people_progress_or_none", "_feedback_or_none", "_directory_or_none"),
        ),
    ):
        code = _code_of(function)
        for shim in shims:
            assert shim in code, f"{function.__name__} does not degrade {shim} on its own"
        # ⚠ AND THE PRIMARY READS ARE NOT GUARDED. A link read swallowed by a shim answers a
        # cheerful 200 carrying an empty list, which on this screen says the programme has nobody
        # in it — the failure the shims exist to prevent, arriving through a shim.
        guarded = _calls_guarded_by_try(function)
        for primary in (
            "register.people_spine",
            "register.designer_links",
            "register.oversight_links",
            "register.inspector_links",
        ):
            assert primary not in guarded, (
                f"{function.__name__} swallows {primary}, so a failed read becomes an empty list"
            )

    # And the shim answers None rather than an empty map, so the caller can tell the two apart.
    shim = _code_of(route._people_progress_or_none)
    assert "return None" in shim
    assert "logger.exception" in shim


def test_the_feedback_columns_are_none_when_the_aggregate_could_not_be_read() -> None:
    """0 suggestions against a working inspector is an accusation, not a blank.

    An inspector who has filed nothing is an ordinary state on the day they are assigned and IS a
    measured zero; an inspector whose filings this request failed to count is a fact about the
    request. The route keeps them apart with ``feedback_read`` and the payload says which.
    """
    code = _code_of(route.list_inspectors)
    assert "if feedback_read else None" in code, (
        "the inspector rows report an unread aggregate as zero"
    )
    assert "feedbackRead" in _keys_written_to(route.list_inspectors, "payload")
    assert "not because nothing was filed" in code


def test_the_feedback_nobody_on_this_list_filed_is_counted_rather_than_dropped() -> None:
    """Attributing only what this list can attribute and printing that as "the feedback" would be a
    number that silently excludes an unknown amount.

    Feedback is filed by whoever is entitled to file it, and holding an inspector row on one of the
    scanned workshops is not the same set. The remainder is derived from the same two aggregates
    rather than from a third query, and is usually a measured zero.
    """
    code = _code_of(route.list_inspectors)
    for key in ("feedbackFiledTotal", "feedbackAttributed", "feedbackByAccountsNotListed"):
        assert key in _keys_written_to(route.list_inspectors, "payload"), key
    assert "max(0," in code, (
        "the remainder can go negative if the two aggregates disagree, and a negative count on a "
        "ministry screen is worse than a clamped one"
    )


def test_the_group_by_count_shim_is_reused_rather_than_re_derived() -> None:
    """``_count`` IS A DICT AND NOT AN INT, and the module already has the one reader for it.

    ``_group_count`` names ``_all`` rather than taking "the first value", which is right by accident
    for a one-key dict and becomes wrong the day a per-field count is added to the query. The people
    aggregates go through it.
    """
    assert "_group_count(row)" in _code_of(register._counts_by_key)
    assert "_counts_by_key" in _code_of(register.inspection_feedback_counts)


def test_the_people_reads_use_the_relations_and_not_the_promoted_string() -> None:
    """``DesignWorkshop`` HAS NO DESIGNER FOREIGN KEY, and ``designerName`` is not a substitute.

    It is promoted off stage 1 by ``promoted_values()``: free-typed, unindexed, not unique, not a
    key, and absent entirely for a designer who never opened stage 1. Two spellings would be two
    designers and one shared spelling one designer. ``_the_lead_among`` exists precisely because
    matching it back to an account is a guess that answers ``None`` whenever it is not certain — the
    honest shape for a guess and the wrong shape for a register.
    """
    for reader, model in (
        (register.designer_links, "designworkshopviewer"),
        (register.oversight_links, "designworkshopoversight"),
        (register.inspector_links, "designworkshopinspector"),
        (register.inspection_feedback_counts, "dwinspectionfeedback"),
    ):
        assert f"db.{model}." in _code_of(reader), reader.__name__

    for reader in (register.designer_links, register.oversight_links, register.inspector_links):
        code = _code_of(reader)
        assert "designerName" not in code
        # READ AND ONLY EVER READ. A viewer row confers STAGE WRITES — `load_workshop_or_404(
        # for_edit=True)` reads the same relation — and nothing on this prefix writes.
        for write in (".create(", ".create_many(", ".delete(", ".delete_many(", ".update("):
            assert write not in code, f"{reader.__name__} writes to a relation this prefix only reads"
