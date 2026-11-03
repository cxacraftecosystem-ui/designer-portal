import ast
import importlib
import inspect
import re
import subprocess
import sys
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.schemas import design_workshop_review_loop as loop
from app.schemas.design_workshops import DESIGN_WORKSHOP_STATUSES

BACKEND = Path(__file__).resolve().parents[1]
APP = BACKEND / "app"
SCHEMA = BACKEND / "prisma" / "schema.prisma"
MIGRATIONS = BACKEND / "prisma" / "migrations"

LOOP_MODULE = APP / "schemas" / "design_workshop_review_loop.py"

STATUSES = (
    "DRAFT",
    "IN_PROGRESS",
    "COMPLETE",
    "PRE_SUBMISSION",
    "NEEDS_REVISION",
    "SUBMITTED",
    "APPROVED",
    "ARCHIVED",
)


def _enum_members(name: str) -> set[str]:
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(rf"^enum {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    assert block, f"enum {name} is not in schema.prisma"
    return {
        line.strip()
        for line in block.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("/", "#", "-"))
    }



def test_the_graph_covers_every_status_the_schema_declares():
    declared = _enum_members("DesignWorkshopStatus")
    assert declared == set(STATUSES)
    assert set(DESIGN_WORKSHOP_STATUSES) == declared
    assert set(loop.LEGAL_TRANSITIONS) == declared
    for token, onward in loop.LEGAL_TRANSITIONS.items():
        assert onward <= declared, f"{token} points at a status the schema does not declare"


def test_draft_is_entry_only():
    for token, onward in loop.LEGAL_TRANSITIONS.items():
        assert loop.DRAFT not in onward, f"{token} -> DRAFT is in the graph"


def test_submitted_is_reachable_only_from_approved():
    sources = {k for k, v in loop.LEGAL_TRANSITIONS.items() if loop.SUBMITTED in v}
    assert sources == {loop.APPROVED}


def test_approved_is_reachable_only_from_pre_submission():
    sources = {k for k, v in loop.LEGAL_TRANSITIONS.items() if loop.APPROVED in v}
    assert sources == {loop.PRE_SUBMISSION}


def test_approved_has_no_edge_a_header_patch_may_make():
    assert loop.patchable_from(loop.APPROVED) == frozenset(), (
        "APPROVED has acquired an outward edge a header edit may make. If that is ARCHIVED, the "
        "two-hop path APPROVED -> ARCHIVED -> PRE_SUBMISSION is open again and an approval can be "
        "laundered away with no audit entry."
    )


def test_archived_is_not_reachable_from_approved():
    assert loop.ARCHIVED not in loop.LEGAL_TRANSITIONS[loop.APPROVED]
    assert (loop.APPROVED, loop.ARCHIVED) not in loop.DECISION_EDGES



def test_the_four_decision_edges_are_refused_to_a_header_patch():
    assert len(loop.DECISION_EDGES) == 5
    for current, nxt in loop.DECISION_EDGES:
        refusal = loop.transition_refusal(current, nxt)
        assert refusal, f"{current} -> {nxt} is not refused to a header patch"
        assert "/design-workshop" in refusal, (
            f"the refusal for {current} -> {nxt} does not name the route that owns it"
        )
        assert loop.transition_refusal(current, nxt, by_decision_route=True) is None


def test_every_refusal_names_the_next_move():
    for current in STATUSES:
        for nxt in STATUSES:
            refusal = loop.transition_refusal(current, nxt)
            if refusal is None:
                continue
            assert refusal.strip(), f"{current} -> {nxt} refuses with an empty sentence"
            assert "/design-workshop" in refusal or "only" in refusal, (
                f"{current} -> {nxt}: {refusal!r} tells the caller what is refused and not what to "
                f"do instead"
            )


def test_a_dead_end_refusal_does_not_print_an_empty_list():
    refusal = loop.transition_refusal(loop.APPROVED, loop.ARCHIVED)
    assert refusal
    assert "can only become ." not in refusal
    assert "can only become ," not in refusal
    assert "/design-workshop-approvals/{id}/hand-on" in refusal
    assert "/design-workshop-approvals/{id}/revise" in refusal


def test_a_no_op_is_never_a_refusal():
    for token in STATUSES:
        assert loop.transition_refusal(token, token) is None


def test_an_unknown_current_status_fails_closed():
    refusal = loop.transition_refusal("SOMETHING_NEW", loop.APPROVED)
    assert refusal and "isn't recognised" in refusal



def test_presubmission_header_spends_exactly_one_round():
    assert loop.presubmission_header(loop.NEEDS_REVISION)["submissionRound"] == {"increment": 1}
    assert loop.presubmission_header(loop.PRE_SUBMISSION) == {}


def test_presubmission_header_clears_the_decision_cache():
    at = datetime(2026, 10, 10, 9, 0, tzinfo=UTC)
    assert loop.presubmission_header(loop.APPROVED, at=at) == {
        "status": "PRE_SUBMISSION",
        "submissionRound": {"increment": 1},
        "reviewNotes": None,
        "reviewedById": None,
        "reviewedAt": None,
        **dict.fromkeys(loop.APPROVAL_CACHE_KEYS),
        **dict.fromkeys(loop.HAND_ON_CACHE_KEYS),
        "lastHandedInAt": at,
    }


def test_the_cleared_keys_are_exactly_the_cached_decision():
    from app.services import records

    cleared = (
        set(loop.presubmission_header(loop.NEEDS_REVISION))
        - {"status", "submissionRound", "lastHandedInAt"}
        - set(loop.APPROVAL_CACHE_KEYS)
        - set(loop.HAND_ON_CACHE_KEYS)
    )
    cached = set(records.review_update("X", None, "y")) - {"status"}
    assert cleared == cached



def _plan_kwargs(**overrides):
    base = {
        "workshop_id": "dw_1",
        "round": 2,
        "actor_id": "officer_1",
        "at": datetime(2026, 9, 13, 10, 0, tzinfo=UTC),
        "note": "Stage 14's cost sheet does not add up.",
    }
    base.update(overrides)
    return base


def test_an_inspection_decision_cannot_be_written_into_a_stage_entry():
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.InspectionWritePlan(
            table="DwStageEntry", operation=loop.Operation.CREATE, data={"data": {}}
        )
    message = str(raised.value)
    for table in loop.WRITABLE_TABLES:
        assert table in message, "the refusal must name where the decision may be written"


def test_an_update_names_one_row_and_a_create_names_none():
    with pytest.raises(loop.InspectionRuleViolation):
        loop.InspectionWritePlan(
            table="DesignWorkshop", operation=loop.Operation.UPDATE, data={"status": "X"}
        )
    with pytest.raises(loop.InspectionRuleViolation):
        loop.InspectionWritePlan(
            table="ReviewLog",
            operation=loop.Operation.CREATE,
            data={"recordId": "dw_1"},
            where={"id": "dw_1"},
        )


def test_a_send_back_makes_exactly_three_writes_and_names_one_row():
    plans = loop.send_back_plans(**_plan_kwargs())
    assert [(p.table, p.operation) for p in plans] == [
        ("DwInspectionFeedback", loop.Operation.CREATE),
        ("DesignWorkshop", loop.Operation.UPDATE),
        ("ReviewLog", loop.Operation.CREATE),
    ]
    assert plans.workshop.where == {"id": "dw_1"}
    assert plans.feedback.data["sentBack"] is True, (
        "the row that moved the status must say so; it is what makes 'which of these four "
        "suggestions sent it back' answerable on the designer's screen"
    )


def test_the_workshop_write_is_records_review_update_verbatim():
    from app.services import records

    plans = loop.send_back_plans(**_plan_kwargs(note="Fix the cost sheet."))
    written = dict(plans.workshop.data)
    theirs = records.review_update("NEEDS_REVISION", "Fix the cost sheet.", "officer_1")
    assert set(written) == {"status", "reviewNotes", "reviewedById", "reviewedAt"}
    assert set(written) == set(theirs)
    written.pop("reviewedAt")
    theirs.pop("reviewedAt")
    assert written == theirs


def test_review_update_is_imported_inside_the_function_and_not_at_module_level():
    for line in LOOP_MODULE.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\s*from app\.services(\.records)? import", line) and (
            "records" in line or "review_update" in line
        ):
            assert line.startswith((" ", "\t")), (
                f"{line.strip()!r} is at module level; it must be imported inside the one function "
                f"that needs it"
            )


def test_a_round_of_zero_is_refused_by_the_plan():
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.feedback_plan(**_plan_kwargs(round=0))
    message = str(raised.value)
    assert "submissionRound" not in message
    assert "DwInspectionFeedback_round_check" not in message
    assert "couldn't be filed" in message


def test_the_review_log_row_uses_a_recordstatus_token_that_exists_in_both_enums():
    record_status = _enum_members("RecordStatus")
    assert {"NEEDS_REVISION", "APPROVED"} <= record_status
    assert "PRE_SUBMISSION" not in record_status
    assert "SUBMITTED" not in record_status


def _plan_builders() -> list:
    return [
        value
        for name, value in vars(loop).items()
        if name.endswith("_plans")
        and callable(value)
        and getattr(value, "__module__", None) == loop.__name__
    ]


def test_every_review_log_status_a_plan_builder_writes_is_a_recordstatus_token():
    record_status = _enum_members("RecordStatus")
    builders = _plan_builders()
    assert builders, (
        "no `*_plans` builder was found in design_workshop_review_loop; if they were renamed, this "
        "sweep must be renamed with them rather than left passing over an empty list"
    )
    assert loop.send_back_plans in builders, "the sweep no longer finds the builder it was written for"

    for builder in builders:
        named = {
            name: param
            for name, param in inspect.signature(builder).parameters.items()
            if param.kind
            not in (inspect.Parameter.VAR_POSITIONAL, inspect.Parameter.VAR_KEYWORD)
        }
        offered = {**_plan_kwargs(), "notes": [("Stage 14's cost sheet does not add up.", None, None)]}
        kwargs = {k: v for k, v in offered.items() if k in named}
        missing = [
            name
            for name, param in named.items()
            if param.default is inspect.Parameter.empty and name not in kwargs
        ]
        assert not missing, (
            f"{builder.__name__} needs {missing}, which `_plan_kwargs` does not supply, so this "
            f"sweep cannot check it. Add those keys to `_plan_kwargs` rather than excluding the "
            f"builder — an unchecked builder is the hole this test exists to close"
        )

        plans = builder(**kwargs)
        log = getattr(plans, "log", None)
        if log is None:
            continue
        assert log.data["status"] in record_status, (
            f"{builder.__name__} writes a ReviewLog row with a status RecordStatus does not have; "
            f"it raises inside the transaction and rolls the decision back with it"
        )
        assert log.data["recordType"] == "DESIGN_WORKSHOP"


def test_a_blank_note_is_refused():
    for blank in ("", "   ", "\n\t "):
        with pytest.raises(loop.InspectionRuleViolation) as raised:
            loop.send_back_plans(**_plan_kwargs(note=blank))
        assert str(raised.value) == (
            "Comments are required when sending a record back for revision"
        )


def test_a_note_over_the_cap_is_refused():
    too_long = "x" * (loop.MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS + 1)
    with pytest.raises(loop.InspectionRuleViolation):
        loop.feedback_plan(**_plan_kwargs(note=too_long))
    fits = "x" * loop.MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS
    assert loop.feedback_plan(**_plan_kwargs(note=fits)).data["note"] == fits


def test_the_wire_cap_and_the_plan_cap_are_the_same_object():
    from app.schemas.design_workshop_inspections import DwInspectionFeedbackIn

    caps = [
        meta.max_length
        for meta in DwInspectionFeedbackIn.model_fields["note"].metadata
        if getattr(meta, "max_length", None) is not None
    ]
    assert caps == [loop.MAX_DESIGN_WORKSHOP_FEEDBACK_CHARS]


def test_an_unknown_stage_key_is_refused_and_an_unknown_field_key_is_not():
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.feedback_plan(**_plan_kwargs(stage_key="notAStage"))
    assert "isn't a stage of this report" in str(raised.value)
    plan = loop.feedback_plan(**_plan_kwargs(field_key="notAField"))
    assert plan.data["fieldKey"] == "notAField"
    assert plan.data["stageKey"] is None, "no stage named means the report as a whole"


def test_a_clock_in_the_future_is_refused_rather_than_corrected():
    at = datetime(2026, 9, 13, 10, 0, tzinfo=UTC)
    with pytest.raises(loop.InspectionRuleViolation) as raised:
        loop.feedback_plan(**_plan_kwargs(at=at, recorded_at=at + timedelta(hours=2)))
    assert "clock set ahead" in str(raised.value)
    inside = at + loop.MAX_DEVICE_CLOCK_SKEW - timedelta(minutes=1)
    assert loop.feedback_plan(**_plan_kwargs(at=at, recorded_at=inside)).data["recordedAt"] == inside


def test_the_skew_matches_the_other_two_ledgers():
    from app.services import design_ratings, dictation_consent

    assert loop.MAX_DEVICE_CLOCK_SKEW == dictation_consent.MAX_DEVICE_CLOCK_SKEW
    assert loop.MAX_DEVICE_CLOCK_SKEW == design_ratings.MAX_DEVICE_CLOCK_SKEW



def _model_columns(name: str) -> set[str]:
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(rf"^model {name} \{{(.*?)^\}}", text, re.DOTALL | re.MULTILINE)
    assert block, f"model {name} is not in schema.prisma"
    return {
        line.split()[0]
        for line in block.group(1).splitlines()
        if line.strip() and not line.strip().startswith(("/", "@", "}"))
    }


def test_every_column_the_plans_name_exists_in_the_schema():
    plans = loop.send_back_plans(**_plan_kwargs())
    feedback_columns = _model_columns("DwInspectionFeedback")
    assert set(plans.feedback.data) <= feedback_columns
    workshop_columns = _model_columns("DesignWorkshop")
    assert set(plans.workshop.data) <= workshop_columns
    assert set(plans.log.data) <= _model_columns("ReviewLog")


def test_the_feedback_table_declares_the_two_clocks_and_the_restrict_actor():
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(r"^model DwInspectionFeedback \{(.*?)^\}", text, re.DOTALL | re.MULTILINE).group(1)
    assert "recordedAt DateTime?" in block, "the device clock must stay nullable"
    assert "createdAt  DateTime  @default(now())" in block
    assert "onDelete: Restrict" in block, "a named officer's instruction must outlive the account"
    assert "onDelete: Cascade" in block
    assert "@@index([designWorkshopId, createdAt])" in block
    assert "@@index([designWorkshopId, round])" in block
    assert "@@index([actorId])" in block


def test_the_workshop_carries_the_cache_and_the_counter():
    text = SCHEMA.read_text(encoding="utf-8")
    block = re.search(r"^model DesignWorkshop \{(.*?)^\}", text, re.DOTALL | re.MULTILINE).group(1)
    assert "reviewNotes String?" in block
    assert "reviewedById String?" in block
    assert "reviewedAt DateTime?" in block
    assert "submissionRound Int @default(0)" in block
    assert "submissionRound DwReviewRound" not in block


def test_the_queue_index_matches_the_query_the_queue_makes():
    text = SCHEMA.read_text(encoding="utf-8")
    assert "@@index([deletedAt, submissionRound])" in text
    for migration in MIGRATIONS.rglob("migration.sql"):
        assert "DesignWorkshop_status_deletedAt_idx" not in migration.read_text(encoding="utf-8")


ENUM_MIGRATIONS = (
    MIGRATIONS / "20260913130000_design_workshop_review_statuses" / "migration.sql",
    MIGRATIONS / "20260913130100_review_record_type_design_workshop" / "migration.sql",
)
TABLE_MIGRATION = MIGRATIONS / "20260913130200_dw_inspection_feedback" / "migration.sql"


def _statements(path: Path) -> list[str]:
    lines = [
        line for line in path.read_text(encoding="utf-8").splitlines() if not line.strip().startswith("--")
    ]
    return [s.strip() for s in "\n".join(lines).split(";") if s.strip()]


def test_the_enum_migrations_are_alter_type_only():
    for path in ENUM_MIGRATIONS:
        for statement in _statements(path):
            assert statement.startswith("ALTER TYPE"), f"{path.name} carries {statement[:60]!r}"
            assert "ADD VALUE IF NOT EXISTS" in statement
    table_sql = TABLE_MIGRATION.read_text(encoding="utf-8")
    body = "\n".join(
        line for line in table_sql.splitlines() if not line.strip().startswith("--")
    )
    for token in ("PRE_SUBMISSION", "NEEDS_REVISION", "APPROVED", "DESIGN_WORKSHOP"):
        assert token not in body, (
            f"{token} is USED in the same migration wave that adds it; the deploy fails with "
            f"'unsafe use of new value of enum type'"
        )


def test_the_migration_matches_the_model():
    sql = TABLE_MIGRATION.read_text(encoding="utf-8")
    assert 'CONSTRAINT "DwInspectionFeedback_round_check" CHECK ("round" >= 1)' in sql
    for index in (
        "DwInspectionFeedback_designWorkshopId_createdAt_idx",
        "DwInspectionFeedback_designWorkshopId_round_idx",
        "DwInspectionFeedback_actorId_idx",
        "DesignWorkshop_deletedAt_submissionRound_idx",
        "DesignWorkshop_reviewedById_idx",
    ):
        assert index in sql, f"{index} is in the model and not in the migration"
    assert "ON DELETE SET NULL" in sql
    assert "ON DELETE CASCADE" in sql
    assert "ON DELETE RESTRICT" in sql
    for column in ("reviewNotes", "reviewedById", "reviewedAt", "submissionRound"):
        assert f'ADD COLUMN IF NOT EXISTS "{column}"' in sql



def _row(**overrides):
    base = {
        "id": "fb_1",
        "designWorkshopId": "dw_1",
        "round": 2,
        "stageKey": None,
        "fieldKey": None,
        "note": "Stage 14's cost sheet does not add up.",
        "sentBack": True,
        "actorId": "officer_1",
        "actor": SimpleNamespace(name="R. Mahapatra"),
        "recordedAt": None,
        "createdAt": datetime(2026, 9, 13, 10, 0, tzinfo=UTC),
    }
    base.update(overrides)
    return SimpleNamespace(**base)


def test_feedback_payload_has_the_eleven_keys_the_clients_decode():
    assert set(loop.feedback_payload(_row())) == {
        "id",
        "designWorkshopId",
        "round",
        "stageKey",
        "fieldKey",
        "note",
        "sentBack",
        "byApprovingAuthority",
        "actorPost",
        "actorId",
        "actorName",
        "recordedAt",
        "createdAt",
    }


def test_feedback_payload_is_synchronous_and_reads_the_name_off_the_row():
    assert not inspect.iscoroutinefunction(loop.feedback_payload)
    assert loop.feedback_payload(_row())["actorName"] == "R. Mahapatra"
    without = loop.feedback_payload(SimpleNamespace(id="fb_2"))
    assert without["actorName"] is None
    assert loop.feedback_payload(_row(actor=SimpleNamespace(name="")))["actorName"] == ""
    both = loop.feedback_payload(
        _row(recordedAt=datetime(2026, 9, 1, 8, 0, tzinfo=UTC))
    )
    assert both["recordedAt"] == "2026-09-01T08:00:00+00:00"
    assert both["createdAt"] == "2026-09-13T10:00:00+00:00"



def test_the_pure_module_touches_no_database():
    for line in LOOP_MODULE.read_text(encoding="utf-8").splitlines():
        assert not re.match(r"^\s*(from|import)\s+app\.core\.db", line), line
        assert not re.match(r"^\s*from app\.core import .*\bdb\b", line), line
        assert not re.match(r"^\s*from app\.services\.design_workshops import", line), line
    proof = subprocess.run(
        [
            sys.executable,
            "-c",
            "import sys; import app.schemas.design_workshop_review_loop as m; "
            "print(int('prisma' in sys.modules), len(m.LEGAL_TRANSITIONS))",
        ],
        cwd=str(BACKEND),
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    assert proof.returncode == 0, proof.stderr[-2000:]
    assert proof.stdout.split() == ["0", "8"], (
        f"importing the loop module pulled in the Prisma client: {proof.stdout!r}"
    )


def test_the_loop_module_does_not_name_the_inspection_predicates():
    from tests.test_dw_inspector_scope_gate import THE_NAMES

    lowered = LOOP_MODULE.read_text(encoding="utf-8").lower()
    for name in THE_NAMES:
        assert name not in lowered, f"{name} is written in {LOOP_MODULE.name}"


def test_the_feedback_ledger_is_not_a_fifth_write_only_table():
    reads = {
        path.name
        for path in APP.rglob("*.py")
        if "dwinspectionfeedback.find_many" in (text := path.read_text(encoding="utf-8"))
        or "dwinspectionfeedback.find_first" in text
    }
    assert reads, (
        "nothing READS the feedback register. It would be the fourth write-only table in this "
        "repository, and the designer's 'what was sent back' panel would have no source."
    )
    assert "DwInspectionFeedback" in loop.WRITABLE_TABLES



def _calls_to(name: str) -> dict[str, list[ast.Call]]:
    out: dict[str, list[ast.Call]] = {}
    for path in sorted(APP.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        found = [
            node
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and (
                (isinstance(node.func, ast.Name) and node.func.id == name)
                or (isinstance(node.func, ast.Attribute) and node.func.attr == name)
            )
        ]
        if found:
            out[str(path.relative_to(BACKEND)).replace("\\", "/")] = found
    return out


def _keyword(call: ast.Call, name: str):
    for kw in call.keywords:
        if kw.arg == name:
            return kw.value
    return None


def _save_stage_def() -> ast.AsyncFunctionDef:
    tree = ast.parse((APP / "services" / "design_workshops.py").read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, ast.AsyncFunctionDef) and node.name == "save_stage":
            return node
    raise AssertionError("services/design_workshops.save_stage is gone")


def test_resubmits_is_keyword_only_and_defaults_to_refusing():
    node = _save_stage_def()
    names = [arg.arg for arg in node.args.kwonlyargs]
    assert "resubmits" in names, (
        "save_stage lost its `resubmits` keyword. Without it the resubmission arm is back to "
        "inferring the actor from the fact that it was reached, which is what let an officer's "
        "artisan upload spend a designer's submission round."
    )
    default = node.args.kw_defaults[names.index("resubmits")]
    assert isinstance(default, ast.Constant) and default.value is False, (
        "`resubmits` must default to False — a call site that forgets it must not resubmit"
    )
    assert not any(arg.arg == "resubmits" for arg in node.args.args), (
        "`resubmits` must stay keyword-only"
    )


def test_the_resubmission_arm_asks_the_caller_and_not_the_room():
    node = _save_stage_def()
    arms = [
        branch
        for branch in ast.walk(node)
        if isinstance(branch, ast.If)
        and any(
            isinstance(call.func, ast.Attribute) and call.func.attr == "presubmission_header"
            for call in ast.walk(branch)
            if isinstance(call, ast.Call)
        )
    ]
    assert arms, "nothing in save_stage calls presubmission_header any more"
    for arm in arms:
        guarded = {n.id for n in ast.walk(arm.test) if isinstance(n, ast.Name)}
        assert "resubmits" in guarded, (
            "the PRE_SUBMISSION arm is reachable without the caller claiming to be the editing "
            "party. See the paragraph above it: two officer-driven callers reach save_stage."
        )


def test_exactly_three_call_sites_and_only_the_designer_route_claims_the_party():
    sites = _calls_to("save_stage")
    assert sorted(sites) == [
        "app/api/routes/design_workshops.py",
        "app/services/artisan_import.py",
        "app/services/design_workshop_oversight.py",
    ], (
        f"the set of save_stage callers changed: {sorted(sites)}. Decide whether the new one is a "
        "designer editing their own sent-back report (resubmits=True) or somebody else's "
        "bookkeeping write (leave it off), say why in this docstring, and then add it here."
    )
    for path, calls in sites.items():
        for call in calls:
            passed = _keyword(call, "resubmits")
            if path == "app/api/routes/design_workshops.py":
                assert isinstance(passed, ast.Constant) and passed.value is True, (
                    "the designer's stage-save route must state resubmits=True: it is the only "
                    "call site that has established the actor is in the editing party"
                )
            else:
                assert passed is None or (
                    isinstance(passed, ast.Constant) and passed.value is False
                ), (
                    f"{path} claims the editing party. An officer's write is not a designer "
                    "answering an inspector — see the census above."
                )


def _where_names(call: ast.Call) -> list[str]:
    where = _keyword(call, "where")
    return [k.value for k in where.keys if isinstance(k, ast.Constant)] if isinstance(where, ast.Dict) else []


def test_the_promoted_columns_are_written_by_id_and_never_under_the_status_predicate():
    node = _save_stage_def()
    writes = [
        call
        for call in ast.walk(node)
        if isinstance(call, ast.Call)
        and isinstance(call.func, ast.Attribute)
        and call.func.attr in {"update", "update_many"}
        and isinstance(call.func.value, ast.Attribute)
        and call.func.value.attr == "designworkshop"
    ]
    plain = [c for c in writes if c.func.attr == "update_many" and "NOT" in _where_names(c)]
    guarded = [c for c in writes if c.func.attr == "update_many" and "status" in _where_names(c)]
    assert len(plain) == 1 and len(guarded) == 1 and len(writes) == 2, (
        f"save_stage should make exactly two header writes; found {len(plain)} content and "
        f"{len(guarded)} guarded. One statement carrying both is the merged form that dropped the "
        "promoted columns whenever the predicate missed."
    )

    def _where_keys(call: ast.Call) -> list[str]:
        where = _keyword(call, "where")
        assert isinstance(where, ast.Dict), "the header write's `where` must be a literal dict"
        return [k.value for k in where.keys if isinstance(k, ast.Constant)]

    assert _where_keys(plain[0]) == ["id", "NOT"], (
        "the content write must be addressed by id and the frozen guard alone. Any other predicate "
        "means a designer's corrections are dropped in silence whenever it misses."
    )
    assert sorted(_where_keys(guarded[0])) == ["id", "status"], (
        "the guarded write must keep its status predicate — it is what stops the round counter "
        "moving twice for one act, and what makes the re-run loop safe"
    )
    content = _keyword(plain[0], "data")
    transition = _keyword(guarded[0], "data")
    assert isinstance(content, ast.Name) and isinstance(transition, ast.Name)
    assert content.id != transition.id, (
        "the two writes must carry different dicts: the header content by id, and the four "
        "transition keys under the predicate"
    )



def test_revision_skip_fields_is_unchanged_by_this_wave():
    from app.services import access

    assert {
        "extraMetadata",
        "location",
        "locationId",
        "updatedAt",
        "createdAt",
        "createdById",
        "recordedAt",
        "recordedTimezone",
        access.MARKER_BODY_KEY,
    } == access.REVISION_SKIP_FIELDS
    assert "reviewNotes" not in access.REVISION_SKIP_FIELDS


def test_records_py_is_unchanged_by_this_wave():
    from app.services import records

    assert list(inspect.signature(records.review_update).parameters) == [
        "status_value",
        "notes",
        "reviewer_id",
    ]
    assert list(inspect.signature(records.resubmit_status).parameters) == ["record", "user", "data"]


def test_can_edit_others_record_is_still_false_for_an_inspector():
    from app.core import deps

    inspector = SimpleNamespace(role="INSPECTOR")
    for creator_role in sorted(deps.ROLE_RANK):
        assert deps.can_edit_others_record(inspector, creator_role) is False, creator_role


def test_the_stage_registry_did_not_move():
    import json

    from app.services.stage_schema import registry_version

    bundled = json.loads(
        (
            BACKEND.parent / "android/app/src/main/assets/design-workshop-schema.json"
        ).read_text(encoding="utf-8")
    )
    assert bundled["version"] == registry_version()


def test_the_loop_module_is_importable_by_name():
    assert importlib.import_module("app.schemas.design_workshop_review_loop") is loop
