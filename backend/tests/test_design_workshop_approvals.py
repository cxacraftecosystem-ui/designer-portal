"""THE APPROVING AUTHORITY'S DECISIONS, ASSERTED WITHOUT A DATABASE.

The companion to ``test_design_workshop_approvals_db.py``, divided the way the review loop's two test
modules are: everything here is true of the SOURCE — who the authority is, what each decision writes,
which state each may be taken from, what each refusal says, what the migration carries, and the shape
of the router — so it runs in CI's Backend tests job, where there is no Postgres.
"""

from __future__ import annotations

import inspect
import re
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.core import deps
from app.schemas import design_workshop_approvals as plans, design_workshop_review_loop as loop
from app.services import design_workshop_approvals as approvals, design_workshop_posts as posts
from app.services.report_builder import sign_off_lines

BACKEND = Path(__file__).resolve().parents[1]
SCHEMA = BACKEND / "prisma" / "schema.prisma"
MIGRATION = BACKEND / "prisma" / "migrations" / "20261009120000_design_workshop_approvals" / "migration.sql"
AT = datetime(2026, 10, 10, 9, 30, tzinfo=UTC)


def _model_columns(name: str) -> set[str]:
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(rf"^model {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    assert block, name
    return {
        line.split()[0]
        for line in block.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("/", "@", "}"))
    }


def _enum(name: str) -> set[str]:
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(rf"^enum {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    return {
        line.strip()
        for line in block.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("/", "#"))
    }


# --------------------------------------------------------------------------------------
# 1. Who decides
# --------------------------------------------------------------------------------------


def test_the_approving_authority_is_the_ministry_admin_and_the_master_admin_and_nobody_else():
    assert frozenset({"MINISTRY_ADMIN", "MASTER_ADMIN"}) == deps.APPROVAL_AUTHORITY_ROLES
    for role in deps.ROLE_RANK:
        user = SimpleNamespace(role=role)
        assert deps.can_approve_design_workshops(user) is (role in deps.APPROVAL_AUTHORITY_ROLES)
    # ADMIN outranks MINISTRY_ADMIN and is out: a SET, not a floor.
    assert not deps.can_approve_design_workshops(SimpleNamespace(role="ADMIN"))
    assert not deps.can_approve_design_workshops(None)


def test_rule_seven_bars_authors_and_inspectors_and_nothing_else():
    assert posts.approval_refusals(posts=frozenset(), authored=frozenset()) == []
    by_access = posts.approval_refusals(posts=frozenset(), authored={posts.DESIGNER_ACCESS})
    assert len(by_access) == 1 and "nobody approves their own work" in by_access[0]
    by_writes = posts.approval_refusals(posts=frozenset(), authored={posts.STAGE_WRITES})
    assert "written its stages" in by_writes[0]
    inspecting = posts.approval_refusals(posts={posts.INSPECTOR}, authored=frozenset())
    assert len(inspecting) == 1 and "inspects" in inspecting[0]
    # 7c: a director post neither grants nor bars.
    for post in (posts.ASSISTANT_DIRECTOR, posts.REGIONAL_DIRECTOR):
        assert posts.approval_refusals(posts={post}, authored=frozenset()) == []


def test_the_rule_seven_module_does_not_name_the_inspection_scope():
    for path in (
        BACKEND / "app" / "services" / "design_workshop_approvals.py",
        BACKEND / "app" / "schemas" / "design_workshop_approvals.py",
        BACKEND / "app" / "api" / "routes" / "design_workshop_approvals.py",
    ):
        lowered = path.read_text(encoding="utf-8").lower()
        for name in (
            "has_inspection_scope",
            "inspectable_by_clause",
            "load_inspectable_workshop_or_404",
            "designworkshopinspector",
        ):
            assert name not in lowered, f"{path.name} names {name}"


# --------------------------------------------------------------------------------------
# 2. The graph
# --------------------------------------------------------------------------------------


def test_five_decision_edges_and_each_names_its_route():
    assert len(loop.DECISION_EDGES) == 5
    assert (loop.SUBMITTED, loop.NEEDS_REVISION) in loop.DECISION_EDGES
    assert set(loop._DECISION_ROUTES) == set(loop.DECISION_EDGES)
    assert loop.NEEDS_REVISION not in loop.patchable_from(loop.SUBMITTED)


def test_a_handed_on_report_loses_every_header_edge_and_a_legacy_one_keeps_them():
    for nxt in loop.LEGAL_TRANSITIONS[loop.SUBMITTED]:
        assert loop.handed_on_refusal(loop.SUBMITTED, nxt, handed_on=True) == loop.HANDED_ON_REFUSAL
        assert loop.handed_on_refusal(loop.SUBMITTED, nxt, handed_on=False) is None
    assert loop.handed_on_refusal(loop.SUBMITTED, loop.SUBMITTED, handed_on=True) is None
    assert loop.handed_on_refusal(loop.APPROVED, loop.NEEDS_REVISION, handed_on=True) is None
    assert loop.transition_refusal(loop.SUBMITTED, loop.IN_PROGRESS) is None


def test_presubmission_clears_the_sign_off_and_stamps_the_hand_in():
    header = loop.presubmission_header(loop.NEEDS_REVISION, at=AT)
    for key in (*loop.APPROVAL_CACHE_KEYS, *loop.HAND_ON_CACHE_KEYS):
        assert key in header and header[key] is None
    assert header["lastHandedInAt"] == AT
    assert loop.presubmission_header(loop.PRE_SUBMISSION) == {}


def test_the_closed_inspection_sentence_is_true_of_each_state():
    assert loop.review_closed_refusal(loop.APPROVED) == loop.REVIEW_CLOSED_APPROVED
    assert loop.review_closed_refusal(loop.SUBMITTED, handed_on=True) == loop.REVIEW_CLOSED_HANDED_ON
    assert loop.review_closed_refusal(loop.SUBMITTED) == loop.NOT_UNDER_REVIEW_REFUSAL
    assert loop.review_closed_refusal(loop.IN_PROGRESS) == loop.NOT_UNDER_REVIEW_REFUSAL


# --------------------------------------------------------------------------------------
# 3. The freeze
# --------------------------------------------------------------------------------------


def test_approved_and_handed_on_are_frozen_and_a_legacy_submitted_is_not():
    assert plans.frozen_refusal("APPROVED", None) == plans.FROZEN_APPROVED
    assert plans.frozen_refusal("SUBMITTED", AT) == plans.FROZEN_HANDED_ON
    assert plans.frozen_refusal("SUBMITTED", None) is None
    for status_value in ("DRAFT", "IN_PROGRESS", "PRE_SUBMISSION", "NEEDS_REVISION", "ARCHIVED"):
        assert not plans.is_frozen(status_value, None)


def test_the_loader_and_the_two_exempt_doors_carry_the_freeze():
    from app.api.routes import design_workshops as routes
    from app.services import design_workshops as service

    assert "allow_when_frozen" in inspect.signature(service.load_workshop_or_404).parameters
    exempt = [
        name
        for name, fn in vars(routes).items()
        if inspect.iscoroutinefunction(fn)
        and getattr(fn, "__module__", None) == routes.__name__
        and "allow_when_frozen=True" in inspect.getsource(fn)
    ]
    assert sorted(exempt) == ["record_device_export", "record_dictation_consent"]
    assert "FROZEN_WHERE" in inspect.getsource(routes.update_design_workshop)


# --------------------------------------------------------------------------------------
# 4. The plans
# --------------------------------------------------------------------------------------


def test_an_approval_is_two_writes_guarded_on_status_round_and_what_was_read():
    from app.services.records import review_update

    decision = plans.approve_plans(
        workshop_id="dw_1", round=2, actor_id="ma_1", at=AT, note=" Fine ", read_at=AT
    )
    assert [p.table for p in decision] == ["DesignWorkshop", "ReviewLog"]
    assert decision.workshop.where == {
        "id": "dw_1",
        "status": "PRE_SUBMISSION",
        "submissionRound": 2,
        "deletedAt": None,
        "updatedAt": AT,
    }
    data = decision.workshop.data
    assert set(review_update("X", None, "y")) <= set(data)
    assert data["approvedAt"] == data["reviewedAt"] and data["approvedRound"] == 2
    assert decision.log.data["status"] == "APPROVED" and decision.log.data["notes"] == "Fine"


def test_a_withdrawal_needs_a_sentence_and_clears_every_sign_off_key():
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        plans.revise_plans(workshop_id="dw_1", round=2, actor_id="ma_1", at=AT, note="  ")
    assert str(raised.value) == plans.REVISE_NOTE_REQUIRED
    decision = plans.revise_plans(
        workshop_id="dw_1", round=2, actor_id="ma_1", at=AT, note="Fix the costs."
    )
    assert [p.table for p in decision] == ["DesignWorkshop", "DwInspectionFeedback", "ReviewLog"]
    for key in (*loop.APPROVAL_CACHE_KEYS, *loop.HAND_ON_CACHE_KEYS):
        assert decision.workshop.data[key] is None
    assert decision.workshop.data["status"] == "NEEDS_REVISION"
    assert decision.feedback.data["round"] == 2
    assert decision.feedback.data["sentBack"] and decision.feedback.data["byApprovingAuthority"]
    assert decision.log.data["notes"].startswith("APPROVAL_WITHDRAWN: ")


def test_a_return_from_the_office_is_guarded_on_the_hand_on():
    decision = plans.revise_plans(
        workshop_id="dw_1",
        round=3,
        actor_id="ma_1",
        at=AT,
        note="The office asked for the cost sheet.",
        current_status="SUBMITTED",
        handed_on=True,
    )
    assert decision.workshop.where["handedOnAt"] == {"not": None}
    with pytest.raises(loop.InspectionRuleViolation):
        plans.revise_plans(
            workshop_id="dw_1", round=3, actor_id="ma_1", at=AT, note="x", current_status="SUBMITTED"
        )


def test_a_return_without_approval_is_the_send_back_marked_as_the_authoritys():
    decision = plans.revise_plans(
        workshop_id="dw_1", round=1, actor_id="ma_1", at=AT, note="Not yet.", current_status="PRE_SUBMISSION"
    )
    assert decision.workshop.where["status"] == {"in": sorted(loop.UNDER_REVIEW)}
    assert decision.feedback.data["byApprovingAuthority"] is True
    assert decision.log.data["notes"] == "RETURNED_BY_APPROVER: Not yet."


def test_a_hand_on_logs_approved_with_its_prefix_and_defaults_the_office():
    decision = plans.hand_on_plans(workshop_id="dw_1", round=2, actor_id="ma_2", at=AT)
    assert decision.log.data["status"] == "APPROVED"
    assert decision.log.data["notes"] == f"HANDED_ON: To {plans.DEFAULT_OFFICE}."
    assert decision.workshop.data["status"] == "SUBMITTED"
    assert decision.workshop.data["handedOnTo"] == plans.DEFAULT_OFFICE
    assert decision.workshop.where["status"] == "APPROVED"


def test_a_round_of_zero_is_refused_by_every_decision():
    for builder in (plans.approve_plans, plans.hand_on_plans):
        with pytest.raises(loop.InspectionRuleViolation):
            builder(workshop_id="dw_1", round=0, actor_id="a", at=AT)
    with pytest.raises(loop.InspectionRuleViolation):
        plans.revise_plans(workshop_id="dw_1", round=0, actor_id="a", at=AT, note="x")


def test_no_plan_may_write_a_review_log_status_record_status_lacks():
    assert _enum("RecordStatus") == loop.RECORD_STATUSES
    with pytest.raises(loop.InspectionRuleViolation):
        loop.InspectionWritePlan(
            table="ReviewLog", operation=loop.Operation.CREATE, data={"status": "SUBMITTED"}
        )


def test_every_column_the_plans_name_exists():
    workshop = _model_columns("DesignWorkshop")
    for decision in (
        plans.approve_plans(workshop_id="d", round=1, actor_id="a", at=AT),
        plans.revise_plans(workshop_id="d", round=1, actor_id="a", at=AT, note="x"),
        plans.hand_on_plans(workshop_id="d", round=1, actor_id="a", at=AT),
    ):
        assert set(decision.workshop.data) <= workshop
        assert set(decision.log.data) <= _model_columns("ReviewLog")
        if decision.feedback is not None:
            assert set(decision.feedback.data) <= _model_columns("DwInspectionFeedback")
    assert {*loop.APPROVAL_CACHE_KEYS, *loop.HAND_ON_CACHE_KEYS, "lastHandedInAt"} <= workshop


# --------------------------------------------------------------------------------------
# 5. What a reader is told
# --------------------------------------------------------------------------------------


def test_the_decision_history_decodes_every_kind():
    def row(status_value, notes):
        return SimpleNamespace(
            id="r", status=status_value, notes=notes, reviewerId="u",
            reviewer=SimpleNamespace(name="M. Admin"), createdAt=AT,
        )

    kinds = [
        item["kind"]
        for item in plans.decision_history_payload(
            [
                row("APPROVED", "ok"),
                row("APPROVED", "HANDED_ON: To DCH."),
                row("NEEDS_REVISION", "APPROVAL_WITHDRAWN: costs"),
                row("NEEDS_REVISION", "RETURNED_BY_APPROVER: costs"),
                row("NEEDS_REVISION", "an inspector's note"),
                row("PENDING", "not a design-workshop decision"),
            ]
        )
    ]
    assert kinds == ["APPROVED", "HANDED_ON", "APPROVAL_WITHDRAWN", "RETURNED", "SENT_BACK"]


@pytest.mark.parametrize(
    ("status_value", "handed_on", "allowed"),
    [
        ("PRE_SUBMISSION", None, {"approve", "revise"}),
        ("NEEDS_REVISION", None, {"revise"}),
        ("APPROVED", None, {"revise", "handOn"}),
        ("SUBMITTED", AT, {"revise"}),
        ("SUBMITTED", None, set()),
        ("IN_PROGRESS", None, set()),
    ],
)
def test_the_decision_view_offers_exactly_what_the_state_allows(status_value, handed_on, allowed):
    record = SimpleNamespace(status=status_value, handedOnAt=handed_on)
    view = approvals.decision_view(record, refusal=None)
    assert {verb for verb, ok in view["mayDecide"].items() if ok} == allowed
    for verb, ok in view["mayDecide"].items():
        assert ok is (view["refusals"][verb] is None)
    barred = approvals.decision_view(record, refusal="You inspect this workshop.")
    assert not any(barred["mayDecide"].values())


def test_the_certification_lines():
    assert sign_off_lines(
        approved_at=None, approved_by_name="X", approver_role_label="Y", handed_on_at=None, handed_on_to=None
    ) == ()
    assert sign_off_lines(
        approved_at=AT, approved_by_name="R. Sen", approver_role_label="Ministry Admin",
        handed_on_at=AT, handed_on_to=None,
    ) == ("Approved by R. Sen (Ministry Admin) on 10 Oct 2026.", "Handed on to the office on 10 Oct 2026.")
    assert plans.role_label("MASTER_ADMIN") == "Master Admin"


# --------------------------------------------------------------------------------------
# 6. The migration and the router
# --------------------------------------------------------------------------------------


def test_the_migration_carries_its_checks_keys_and_indexes():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert '"status" <> \'APPROVED\' OR "approvedAt" IS NOT NULL' in sql
    assert '"handedOnAt" IS NULL OR "approvedAt" IS NOT NULL' in sql
    for column in ("approvedById", "approvedAt", "approvedRound", "handedOnById", "handedOnAt",
                   "handedOnTo", "handedOnExportId", "lastHandedInAt", "byApprovingAuthority"):
        assert f'ADD COLUMN IF NOT EXISTS "{column}"' in sql
    for name in ("DesignWorkshop_approvedById_idx", "DesignWorkshop_handedOnById_idx",
                 "DesignWorkshop_handedOnExportId_idx"):
        assert name in sql
    assert sql.count("ON DELETE SET NULL") == 3
    assert "ALTER TYPE" not in sql


def test_every_approvals_route_stands_behind_the_authority_and_the_literal_comes_first():
    from app.api.routes import design_workshop_approvals as routes

    seen = []
    for route in routes.router.routes:
        names = {dep.call.__name__ for dep in route.dependant.dependencies}
        assert "require_approving_authority" in names, route.path
        seen.append((min(route.methods), route.path))
    assert seen.index(("GET", "/design-workshop-approvals/awaiting-count")) < seen.index(
        ("GET", "/design-workshop-approvals/{workshop_id}")
    )
    assert {p for m, p in seen if m == "POST"} == {
        "/design-workshop-approvals/{workshop_id}/report",
        "/design-workshop-approvals/{workshop_id}/approve",
        "/design-workshop-approvals/{workshop_id}/revise",
        "/design-workshop-approvals/{workshop_id}/hand-on",
    }
