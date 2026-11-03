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
    source = PERMISSIONS_TS.read_text(encoding="utf-8")
    start = source.find('path: "/ministry-dashboard"')
    assert start != -1, (
        "the ministry dashboard has no ROUTE_GUARDS row, so the URL is open in the browser to "
        "anyone who has been sent the link"
    )
    end = source.find("\n  },", start)
    assert end != -1
    return source[start:end]



def test_the_audience_is_the_four_posts_and_nobody_else() -> None:
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
    assert ROLE_RANK["MINISTRY_ADMIN"] < ROLE_RANK["ADMIN"] < ROLE_RANK["MASTER_ADMIN"]
    assert "MINISTRY_ADMIN" in MINISTRY_DASHBOARD_ROLES
    assert "ADMIN" not in MINISTRY_DASHBOARD_ROLES
    assert "MASTER_ADMIN" in MINISTRY_DASHBOARD_ROLES


def test_the_web_mirrors_the_server_gate() -> None:
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
    guard = _route_guard_row()
    assert "canSeeMinistryDesk" not in guard, (
        "the ministry dashboard route is gated on the DESK CARD's audience; read both docstrings"
    )


def test_the_refusal_sentence_is_identical_on_both_surfaces() -> None:
    guard = _route_guard_row()
    body = guard.split("message:", 1)[1]
    joined = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', body)).replace('\\"', '"')
    assert joined == MINISTRY_DASHBOARD_REFUSAL


def test_the_route_guard_row_is_not_nested_under_a_narrower_prefix() -> None:
    guard = _route_guard_row()
    assert 'path: "/ministry-dashboard"' in guard
    for nested in ("/admin/", "/officers/", "/design-workshops/", "/dashboard/"):
        assert f'path: "{nested}' not in guard


def test_every_route_on_the_prefix_carries_the_gate() -> None:
    assert route.router.routes, "the ministry dashboard router declares no routes"
    for api_route in route.router.routes:
        names = {
            dependency.call.__name__
            for dependency in getattr(
                api_route, "dependant", SimpleNamespace(dependencies=[])
            ).dependencies
            if getattr(dependency, "call", None) is not None
        }
        assert "require_ministry_dashboard_reader" in names or any(
            "require_ministry_dashboard_reader" in n for n in names
        ), f"{api_route.path} is on the ministry prefix without the gate"
        assert set(api_route.methods) == {"GET"}, f"{api_route.path} is not a read"


def test_the_prefix_declares_no_parameterised_path() -> None:
    for api_route in route.router.routes:
        assert "{" not in api_route.path, f"{api_route.path} is parameterised"



def test_the_estate_is_read_by_the_ministry_admin_and_the_master_admin_only() -> None:
    assert register.sees_whole_estate(_user("MINISTRY_ADMIN")) is True
    assert register.sees_whole_estate(_user("MASTER_ADMIN")) is True
    assert register.sees_whole_estate(_user("ADMIN")) is True
    assert register.sees_whole_estate(_user("ASSISTANT_DIRECTOR")) is False
    assert register.sees_whole_estate(_user("REGIONAL_DIRECTOR")) is False


def test_the_narrowed_scope_is_the_oversight_relation_and_not_a_new_one() -> None:
    clause = register.scope_clause(_user("ASSISTANT_DIRECTOR"))
    assert clause == {"oversight": {"some": {"userId": "u1"}}}
    assert register.scope_clause(_user("MINISTRY_ADMIN")) is None


def test_the_scope_sentence_never_claims_the_estate_to_an_officer() -> None:
    estate = register.scope_label(_user("MINISTRY_ADMIN"))
    posted = register.scope_label(_user("REGIONAL_DIRECTOR"))
    assert estate != posted
    assert "Every workshop on the platform" in estate
    assert "Every workshop on the platform" not in posted
    assert "named on" in posted


def test_the_page_prints_the_servers_sentence_and_composes_none_of_its_own() -> None:
    page = PAGE_TSX.read_text(encoding="utf-8")
    assert "{data.scopeLabel}" in page, "the page no longer prints the server's scope sentence"



def test_the_standing_groups_partition_the_enum() -> None:
    grouped = [status for members in register.STANDING_GROUPS.values() for status in members]
    assert sorted(grouped) == sorted(register.ALL_STANDINGS)
    assert len(grouped) == len(set(grouped))


def test_a_draft_is_newly_registered_and_not_ongoing() -> None:
    assert register.group_of("DRAFT") == "registered"
    assert register.group_of("IN_PROGRESS") == "ongoing"
    assert register.group_of("APPROVED") == "completed"


def test_an_unknown_status_is_in_no_group_rather_than_the_first_one() -> None:
    assert register.group_of("SOMETHING_NEW") is None
    assert register.group_of(None) is None


def test_an_unrecognised_standing_is_ignored_rather_than_refused() -> None:
    assert register.standing_clause(None) is None
    assert register.standing_clause("") is None
    assert register.standing_clause("nonsense") is None
    assert register.standing_clause("ongoing") == {
        "status": {"in": list(register.STANDING_GROUPS["ongoing"])}
    }
    assert register.standing_clause("ONGOING") == register.standing_clause("ongoing")



def test_an_unscored_workshop_carries_a_reason_and_never_a_zero() -> None:
    for reason in ("capped", "unreadable"):
        block = register.unscored(reason)
        assert block["percent"] is None
        assert block["stagesComplete"] is None
        assert block["requiredTotal"] is None
        assert block["unscoredReason"] == reason
        assert block["definitionRead"] is False


def test_the_roll_up_is_the_clients_arithmetic_to_the_rounding() -> None:
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

    assert (
        register._roll_up({"z": {"requiredTotal": 0, "requiredFilled": 0, "isComplete": True}})[
            "percent"
        ]
        == 100
    )
    assert register._roll_up({})["percent"] is None



def test_the_register_export_columns_are_pinned() -> None:
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

    assert "workshop_summary(record)" in source
    assert "progress.get(workshop_id)" in source
    assert "beneficiaries.get(workshop_id" in source


def test_the_beneficiary_export_is_the_shared_one_and_masks_by_construction() -> None:
    from app.api.routes import export
    from app.core.deps import can_download_dataset
    from app.services.record_fields import ARTISAN

    paths = {r.path for r in export.router.routes}
    assert "/export/artisans.csv" in paths, "the beneficiary export is gone"

    for role in ("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "MASTER_ADMIN"):
        assert can_download_dataset(_user(role)) is True, role

    identity_labels = {
        field.label
        for field in ARTISAN.fields
        if "aadhaar" in field.label.lower() or "pehchan" in field.label.lower()
    }
    assert identity_labels, "the artisan registry no longer declares the regulated identity columns"
    source = (REPO / "backend/app/services/record_fields.py").read_text(encoding="utf-8")
    assert '_f("Aadhaar number", aadhaar_fingerprint.stored_mask)' in source
    assert "mask_aadhaar(a.pehchanCardNumber)" in source


def test_the_page_says_what_the_download_does_and_does_not_hold() -> None:
    page = PAGE_TSX.read_text(encoding="utf-8")
    assert "no stage content" in page
    assert "masked" in page
    assert "beneficiariesRefusal" in page


_PEOPLE_PATHS = (
    "/ministry-dashboard/designers",
    "/ministry-dashboard/officers",
    "/ministry-dashboard/inspectors",
)

_ROSTER_JUDGEMENTS = (
    "rosterActive",
    "canSignIn",
    "firstSeenAt",
    "institution",
    "rosterId",
    "hasProfile",
)


def _account(user_id: str, name: str, email: str, role: str) -> SimpleNamespace:
    return SimpleNamespace(id=user_id, name=name, email=email, role=role)


def _function_body(function: object) -> ast.AST:
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
    return ast.unparse(_function_body(function))


def _calls_guarded_by_try(function: object) -> set[str]:
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



def test_the_three_people_registers_are_declared_on_this_prefix() -> None:
    paths = {api_route.path for api_route in route.router.routes}
    for path in _PEOPLE_PATHS:
        assert path in paths, f"{path} is gone from the ministry dashboard"


def test_the_people_registers_are_reads_behind_the_gate_with_no_id_segment() -> None:
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
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        code = _code_of(function)
        assert "_design_where(" in code, (
            f"{function.__name__} builds its own where clause instead of using the composer that "
            "puts the scope on AND"
        )
        assert "'OR'" not in code and '"OR"' not in code, (
            f"{function.__name__} names where['OR'] — the search box owns that key"
        )



def test_the_people_scope_sentence_never_claims_the_estate_to_an_officer() -> None:
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
            assert estate != register.scope_label(_user("MINISTRY_ADMIN"))
            assert posted != register.scope_label(_user("ASSISTANT_DIRECTOR"))


def test_the_scope_sentence_changes_when_an_account_directory_is_folded_in() -> None:
    for noun in ("designer", "officer", "inspector"):
        for role in ("MINISTRY_ADMIN", "ASSISTANT_DIRECTOR"):
            folded = register.people_scope_label(_user(role), noun=noun, includes_unposted=True)
            relation_only = register.people_scope_label(
                _user(role), noun=noun, includes_unposted=False
            )
            assert folded != relation_only, (role, noun)
            assert "measured zero" in folded
            assert "absent" in relation_only or "does not appear here" in relation_only
            assert "absent from this list entirely" not in folded


def test_every_people_list_carries_its_own_scope_and_scope_label() -> None:
    keys = _keys_written_to(route._people_envelope, "payload")
    assert {"scope", "scopeLabel"} <= keys
    assert "people_scope_label" in _code_of(route._people_envelope)
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        assert "_people_envelope(" in _code_of(function), (
            f"{function.__name__} builds its own envelope and can lose the scope sentence"
        )



def test_a_people_row_is_four_identity_keys_and_measurements_and_nothing_about_the_roster() -> None:
    row = register.new_person_row(_account("d1", "Rekha", "rekha@example.org", "DESIGNER"))
    assert row["id"] == "d1"
    assert row["name"] == "Rekha"
    assert row["email"] == "rekha@example.org"
    assert row["role"] == "DESIGNER"
    for forbidden in _ROSTER_JUDGEMENTS:
        assert forbidden not in row, (
            f"a people row carries {forbidden}, which is a roster judgement"
        )

    assert "assignable_designers_payload" in _code_of(register.new_person_row)

    assert row["workshops"] == 0
    assert row["registered"] == row["ongoing"] == row["completed"] == 0
    assert row["percent"] is None
    assert row["requiredTotal"] is None
    assert row["stagesComplete"] is None


def test_the_designer_register_adds_only_the_two_link_counts_it_documents() -> None:
    assert _keys_written_to(route.list_designers, "row") == {
        "workshopsCreated",
        "workshopsNamedOn",
    }
    assert _keys_written_to(route.list_designers, "payload") == {
        "unpostedAccountsTruncated",
        "rosterRepresentation",
        "rosterRepresentationNote",
    }
    code = _code_of(route.list_designers)
    for forbidden in _ROSTER_JUDGEMENTS:
        assert forbidden not in code
    assert "designer_links" in code and "creator_accounts" in code
    assert "designerName" not in code, (
        "the designer register groups on the promoted stage-1 string, which is free-typed, "
        "unindexed and not an account — see the services module's people-register header"
    )


def test_a_creator_is_counted_as_a_designer_only_where_they_wrote_the_workshop() -> None:
    code = _code_of(route.list_designers)
    assert "stage_writers(workshop_ids, creator_ids)" in code
    assert "in authored" in code, "the creator fold is no longer conditioned on authorship"
    assert "stage_writers" not in _calls_guarded_by_try(route.list_designers)


def test_the_unposted_folds_list_the_tiers_whose_job_the_post_is_and_no_administrator() -> None:
    from app.services import design_workshop_oversight as officers

    assert frozenset({"ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"}) == officers.DIRECTORATE_POST_ROLES
    assert "officers.DIRECTORATE_POST_ROLES" in _code_of(route.list_officers)
    assert "INSPECTION_ROLES" in _code_of(route.list_inspectors)
    assert "INSPECTION_HOLDER_ROLES" not in _code_of(route.list_inspectors)



def test_the_designer_register_says_how_much_of_the_roster_it_cannot_show() -> None:
    assert hasattr(register, "roster_representation")
    assert hasattr(register, "roster_representation_note")
    code = _code_of(route.list_designers)
    assert "roster_representation" in code, (
        "the designers route stopped measuring how much of the roster it cannot show"
    )
    assert "_representation_or_none" in code


def test_the_roster_gap_derives_its_role_set_and_names_no_role() -> None:
    code = _code_of(register.roster_representation)
    assert "designers.ROSTER_GATED_ROLE" in code
    for role in ROLE_RANK:
        assert f'"{role}"' not in code, (
            f"{role!r} is written out in roster_representation. The eligible set is derived, not "
            "named — see this test's docstring."
        )
    assert "active_roster_emails" in code


def test_the_roster_gap_counts_exactly_the_roles_the_fold_does_not_list(monkeypatch) -> None:
    import asyncio

    from app.services import design_workshop_viewers

    roles = sorted(ROLE_RANK)
    emails = [f"{role.lower()}@roster.test" for role in roles]

    async def _roster() -> tuple[list[str], bool]:
        return [*emails, "invited-never-arrived@roster.test"], False

    sent: list[dict] = []

    class _Users:
        async def find_many(self, where=None, **_kwargs):
            sent.append(where)
            if "AND" in where:
                return []
            return [SimpleNamespace(email=e, role=r) for e, r in zip(emails, roles, strict=True)]

    monkeypatch.setattr(design_workshop_viewers, "active_roster_emails", _roster)
    monkeypatch.setattr(designers, "db", SimpleNamespace(user=_Users()))
    monkeypatch.setattr(register, "db", SimpleNamespace(user=_Users()))

    asyncio.run(
        designers.workshop_capable_accounts(
            search=None, include_suspended=False, include_admins=False
        )
    )
    listed = set(sent[-1]["AND"][0]["role"]["in"])
    assert listed == {designers.ROSTER_GATED_ROLE}, listed

    report = asyncio.run(register.roster_representation())
    assert report["rosterOtherRole"] == len(set(roles) - listed), (
        "the gap counts a different set of roles as listed from the one the fold lists"
    )
    assert report["rosterWithoutAccount"] == 1
    assert report["rosterAdmitted"] == len(emails) + 1
    assert report["rosterReadTruncated"] is False


def test_the_roster_gap_discloses_counts_and_never_an_address() -> None:
    code = _code_of(register.roster_representation)
    assert "assignable_designers_payload" not in code, (
        "the gap started shaping people ROWS; it reports counts only — see this test's docstring"
    )
    for name in (
        "rosterAdmitted",
        "rosterWithoutAccount",
        "rosterOtherRole",
        "rosterReadTruncated",
    ):
        assert name in code, f"{name} is gone from the representation report"
    for leak in ("email", "name", "fullName"):
        assert f'"{leak}"' not in code, f"{leak!r} is being put on the wire by the gap report"


def test_a_failed_gap_measurement_never_reads_as_nothing_missing() -> None:
    code = _code_of(route.list_designers)
    assert "if representation is None:" in code
    assert "could not be measured" in code


def test_the_gap_sentence_is_absent_when_nothing_is_missing() -> None:
    assert (
        register.roster_representation_note(
            {
                "rosterAdmitted": 9,
                "rosterWithoutAccount": 0,
                "rosterOtherRole": 0,
                "rosterReadTruncated": False,
            },
            9,
        )
        is None
    )

    note = register.roster_representation_note(
        {
            "rosterAdmitted": 35,
            "rosterWithoutAccount": 24,
            "rosterOtherRole": 2,
            "rosterReadTruncated": False,
        },
        9,
    )
    assert note is not None
    assert "24" in note and "2" in note and "9" in note and "35" in note
    assert "have not created an account yet" in note


def test_a_cut_roster_read_says_the_gap_is_a_floor() -> None:
    note = register.roster_representation_note(
        {
            "rosterAdmitted": 500,
            "rosterWithoutAccount": 400,
            "rosterOtherRole": 0,
            "rosterReadTruncated": True,
        },
        100,
    )
    assert note is not None and "floor" in note


def test_the_officer_register_adds_only_the_capacity_breakdown_it_documents() -> None:
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



def test_no_people_register_names_a_privileged_account() -> None:
    assert set(register.WITHHELD_PERSON_ROLES) == set(designers.NEVER_ROSTER_GATED_ROLES)
    assert set(register.WITHHELD_PERSON_ROLES) == {"ADMIN", "MASTER_ADMIN"}
    for role in ROLE_RANK:
        expected = role in {"ADMIN", "MASTER_ADMIN"}
        assert register.is_withheld_person(_user(role)) is expected, role


def test_the_withheld_accounts_are_counted_and_the_count_is_on_the_wire() -> None:
    for function in (route.list_designers, route.list_officers, route.list_inspectors):
        code = _code_of(function)
        assert "is_withheld_person" in code, f"{function.__name__} does not apply the boundary"
        assert "withheld=len(withheld)" in code, (
            f"{function.__name__} withholds accounts without reporting how many"
        )



def test_a_person_with_no_workshop_says_so_rather_than_reporting_nought_per_cent() -> None:
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

    assert len({idle["progressReason"], capped["progressReason"], unread["progressReason"]}) == 3


def test_the_person_roll_up_is_the_registers_arithmetic_to_the_rounding() -> None:
    row = register.new_person_row(_account("d1", "Rekha", "r@x.y", "DESIGNER"))
    row["workshops"] = 3
    register.add_score(
        row, {"requiredTotal": 10, "requiredFilled": 5, "stagesComplete": 1, "stagesTotal": 6}
    )
    register.add_score(
        row, {"requiredTotal": 30, "requiredFilled": 30, "stagesComplete": 6, "stagesTotal": 6}
    )
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



def test_the_people_scan_order_is_the_registers_order_tiebreak_included() -> None:
    assert register.PEOPLE_SCAN_ORDER == route._DESIGN_ORDER
    assert register.PEOPLE_SCAN_ORDER[-1] == {"id": "asc"}


def test_the_people_order_is_total_so_a_paged_register_serves_nobody_twice() -> None:
    def _row(user_id: str, name: str, workshops: int) -> dict:
        row = register.new_person_row(_account(user_id, name, f"{user_id}@x.y", "DESIGNER"))
        row["workshops"] = workshops
        return row

    rows = [_row("b", "Same Name", 2), _row("a", "Same Name", 2), _row("c", "Busier", 9)]
    ordered = [row["id"] for row in register.order_people(rows)]
    assert ordered == ["c", "a", "b"]
    assert [row["id"] for row in register.order_people(list(reversed(rows)))] == ordered



def test_the_unposted_directories_are_offered_exactly_where_their_own_gate_already_admits() -> None:
    assert set(OVERSIGHT_ASSIGNER_ROLES) == {"MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"}
    for role in ROLE_RANK:
        assert register.may_read_account_directories(_user(role)) is (
            role in OVERSIGHT_ASSIGNER_ROLES
        ), role
    for role in ROLE_RANK:
        assert register.may_read_account_directories(_user(role)) is register.sees_whole_estate(
            _user(role)
        )


def test_the_designer_roster_fold_reuses_a_door_every_tier_here_already_clears() -> None:
    for role in MINISTRY_DASHBOARD_ROLES:
        assert can_record_sanction_orders(_user(role)) is True, role
    code = _code_of(register.empanelled_designer_accounts)
    assert "include_admins=False" in code, (
        "the roster fold would hand the privileged-account directory to rank 42"
    )
    assert "include_suspended=False" in code


def test_a_directory_that_could_not_be_read_is_not_reported_as_an_empty_one() -> None:
    for function in (route.list_officers, route.list_inspectors):
        code = _code_of(function)
        assert "may_read_account_directories" in code
        assert "if not offered:" in code, (
            f"{function.__name__} cannot tell a refused directory from a failed one"
        )
        assert "couldn't be read just now" in code, (
            f"{function.__name__} has no sentence for the directory read that failed"
        )



def test_every_optional_people_read_has_its_own_shim_and_the_primary_reads_have_none() -> None:
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

    shim = _code_of(route._people_progress_or_none)
    assert "return None" in shim
    assert "logger.exception" in shim


def test_the_feedback_columns_are_none_when_the_aggregate_could_not_be_read() -> None:
    code = _code_of(route.list_inspectors)
    assert "if feedback_read else None" in code, (
        "the inspector rows report an unread aggregate as zero"
    )
    assert "feedbackRead" in _keys_written_to(route.list_inspectors, "payload")
    assert "Suggestions may still have been filed" in code


def test_the_feedback_nobody_on_this_list_filed_is_counted_rather_than_dropped() -> None:
    code = _code_of(route.list_inspectors)
    for key in ("feedbackFiledTotal", "feedbackAttributed", "feedbackByAccountsNotListed"):
        assert key in _keys_written_to(route.list_inspectors, "payload"), key
    assert "max(0," in code, (
        "the remainder can go negative if the two aggregates disagree, and a negative count on a "
        "ministry screen is worse than a clamped one"
    )


def test_the_group_by_count_shim_is_reused_rather_than_re_derived() -> None:
    assert "_group_count(row)" in _code_of(register._counts_by_key)
    assert "_counts_by_key" in _code_of(register.inspection_feedback_counts)


def test_the_people_reads_use_the_relations_and_not_the_promoted_string() -> None:
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
        for write in (".create(", ".create_many(", ".delete(", ".delete_many(", ".update("):
            assert write not in code, (
                f"{reader.__name__} writes to a relation this prefix only reads"
            )
