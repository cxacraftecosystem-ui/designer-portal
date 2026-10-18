"""The review aids without a database: note freshness, verdict bookkeeping, batch planning, sorting.

The database-backed half is ``test_dw_review_aids_db.py``.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.schemas import (
    design_workshop_approvals as approvals_plans,
    design_workshop_review_loop as loop,
)
from app.services import design_workshop_review_aids as aids
from app.services.stage_schema import stages

AT = datetime(2026, 10, 17, 9, 0, tzinfo=UTC)


def _stage_key(index: int = 0) -> str:
    return stages()[index].key


# ── A19: note freshness ────────────────────────────────────────────────────────────────────────────


def test_a_note_is_fresh_only_when_its_stage_was_written_after_it():
    first, second = _stage_key(0), _stage_key(1)
    edits = {first: AT + timedelta(hours=1), second: AT - timedelta(hours=1)}
    rows = [
        {"id": "a", "stageKey": first, "createdAt": AT.isoformat()},
        {"id": "b", "stageKey": second, "createdAt": AT.isoformat()},
        {"id": "c", "stageKey": None, "createdAt": AT.isoformat()},
        {"id": "d", "stageKey": first, "createdAt": None},
    ]
    marked = {row["id"]: row["editedSince"] for row in aids.annotate_freshness(rows, edits)}
    assert marked == {"a": True, "b": False, "c": True, "d": None}


def test_a_whole_report_note_on_an_untouched_report_reads_untouched():
    rows = [{"id": "x", "stageKey": None, "createdAt": AT.isoformat()}]
    assert aids.annotate_freshness(rows, {})[0]["editedSince"] is False


# ── C5: verdicts ───────────────────────────────────────────────────────────────────────────────────


def test_verdicts_name_only_the_current_round():
    inspectors = [{"userId": "u1", "name": "One"}, {"userId": "u2", "name": "Two"}]
    clearances = [
        {"inspectorId": "u1", "round": 2, "clearedAt": "2026-10-17T09:00:00+00:00"},
        {"inspectorId": "u2", "round": 1, "clearedAt": "2026-10-01T09:00:00+00:00"},
    ]
    marked = aids.with_verdicts(inspectors, clearances, 2)
    assert [p["clearedAt"] for p in marked] == ["2026-10-17T09:00:00+00:00", None]
    assert aids.my_clearance(clearances, "u2", 2) is None
    assert aids.my_clearance(clearances, "u2", 1) == "2026-10-01T09:00:00+00:00"


@pytest.mark.parametrize(
    ("wanted", "assigned", "cleared", "expected"),
    [
        ("all", 2, 2, True),
        ("all", 2, 1, False),
        ("all", 0, 0, False),
        ("some", 2, 1, True),
        ("some", 2, 0, False),
        ("none", 2, 0, True),
        ("none", 2, 1, False),
        (None, 2, 1, True),
    ],
)
def test_the_cleared_filter(wanted, assigned, cleared, expected):
    readiness = {"inspectors": assigned, "cleared": cleared}
    assert aids._matches_cleared(readiness, wanted) is expected


def test_sort_and_filter_values_are_checked():
    assert aids.check_sort(None) == "default"
    assert aids.check_sort("complete") == "complete"
    assert aids.check_cleared("") is None
    with pytest.raises(Exception) as refused:
        aids.check_sort("random")
    assert refused.value.status_code == 422
    with pytest.raises(Exception) as refused:
        aids.check_cleared("most")
    assert refused.value.status_code == 422
    assert aids.dated_order("lastHandedInAt", "oldest")[0] == {"lastHandedInAt": "asc"}
    assert aids.dated_order("lastHandedInAt", "newest")[0] == {"lastHandedInAt": "desc"}
    assert aids.dated_order("lastHandedInAt", "complete") is None


# ── B2: batches ────────────────────────────────────────────────────────────────────────────────────


def _batch(**overrides):
    base = {
        "workshop_id": "dw_1",
        "round": 2,
        "actor_id": "officer_1",
        "at": AT,
        "notes": [
            ("Cost sheet total is wrong.", _stage_key(0), "totalCost"),
            ("Add the second photograph.", _stage_key(1), None),
        ],
    }
    base.update(overrides)
    return loop.note_batch(**base)


def test_a_batch_files_every_note_and_moves_nothing():
    batch = _batch()
    assert [row.data["note"] for row in batch.rows] == [
        "Cost sheet total is wrong.",
        "Add the second photograph.",
    ]
    assert batch.rows[0].data["fieldKey"] == "totalCost"
    assert not any(row.data["sentBack"] for row in batch.rows)
    assert all(row.table == "DwInspectionFeedback" for row in batch.rows)
    assert batch.cover == "Cost sheet total is wrong. (and 1 more note)"
    assert batch.digest == "1. Cost sheet total is wrong.\n2. Add the second photograph."


def test_a_send_back_batch_marks_one_row_as_the_send_back():
    batch = _batch(sends_back=True)
    assert [row.data["sentBack"] for row in batch.rows] == [True, False]
    covered = _batch(sends_back=True, cover="Please revise these two.")
    assert next(row.data["note"] for row in covered.rows) == "Please revise these two."
    assert [row.data["sentBack"] for row in covered.rows] == [True, False, False]
    assert covered.rows[0].data["stageKey"] is None
    assert covered.cover.startswith("Please revise these two.")


def test_a_batch_is_refused_whole():
    with pytest.raises(loop.InspectionRuleViolation):
        _batch(notes=[])
    with pytest.raises(loop.InspectionRuleViolation):
        _batch(notes=[("fine", None, None), ("   ", None, None)])
    with pytest.raises(loop.InspectionRuleViolation):
        _batch(notes=[("fine", "NOT_A_STAGE", None)])
    with pytest.raises(loop.InspectionRuleViolation):
        _batch(notes=[("x", None, None)] * (loop.MAX_BATCH_NOTES + 1))


def test_a_directors_note_carries_its_post_and_only_a_directors_post():
    batch = _batch(actor_post="ASSISTANT_DIRECTOR")
    assert {row.data["actorPost"] for row in batch.rows} == {"ASSISTANT_DIRECTOR"}
    assert "actorPost" not in _batch().rows[0].data
    with pytest.raises(loop.InspectionRuleViolation):
        _batch(actor_post="MINISTRY_ADMIN")


def test_the_send_back_batch_plans_write_the_three_tables():
    batch, workshop, log = loop.send_back_batch_plans(
        workshop_id="dw_1",
        round=1,
        actor_id="officer_1",
        at=AT,
        notes=[("One.", None, None), ("Two.", None, None)],
        log_prefix="RETURNED_BY_APPROVER",
        by_approving_authority=True,
    )
    assert workshop.table == "DesignWorkshop" and workshop.data["status"] == "NEEDS_REVISION"
    assert workshop.data["reviewNotes"] == "One. (and 1 more note)"
    assert log.data["notes"] == "RETURNED_BY_APPROVER: One. (and 1 more note)"
    assert all(row.data["byApprovingAuthority"] for row in batch.rows)


def test_the_approvers_revise_takes_a_batch_without_a_sentence():
    decision = approvals_plans.revise_plans(
        workshop_id="dw_1",
        round=1,
        actor_id="ministry_1",
        at=AT,
        note=None,
        current_status="PRE_SUBMISSION",
        notes=[("Fix the venue.", _stage_key(0), "venue"), ("Fix the date.", None, None)],
    )
    writes = list(decision)
    assert [w.table for w in writes] == [
        "DesignWorkshop",
        "DwInspectionFeedback",
        "DwInspectionFeedback",
        "ReviewLog",
    ]
    assert decision.feedback.data["sentBack"] is True
    assert decision.extra[0].data["sentBack"] is False
    assert decision.digest == "1. Fix the venue.\n2. Fix the date."
    assert decision.workshop.where["status"] == {"in": sorted(loop.UNDER_REVIEW)}


def test_the_approvers_withdrawal_with_a_batch_clears_the_approval():
    decision = approvals_plans.revise_plans(
        workshop_id="dw_1",
        round=1,
        actor_id="ministry_1",
        at=AT,
        note="Withdrawn: two corrections.",
        current_status="APPROVED",
        notes=[("Fix the venue.", None, None)],
    )
    assert decision.workshop.data["approvedAt"] is None
    assert decision.log.data["notes"].startswith("APPROVAL_WITHDRAWN: Withdrawn: two corrections.")
    assert len(decision.extra) == 1


def test_a_revise_with_neither_sentence_nor_notes_is_still_refused():
    with pytest.raises(loop.InspectionRuleViolation):
        approvals_plans.revise_plans(
            workshop_id="dw_1",
            round=1,
            actor_id="m",
            at=AT,
            note="  ",
            current_status="PRE_SUBMISSION",
            notes=[],
        )


# ── A22 / C13 helpers ──────────────────────────────────────────────────────────────────────────────


def test_the_report_object_key_is_per_workshop_and_export():
    assert aids.report_object_key("dw_1", "ex_1", "PDF") == "reports/dw_1/ex_1.pdf"
    assert aids.report_object_key("dw_1", "ex_1", "DOCX") == "reports/dw_1/ex_1.docx"


def test_a_snapshot_carries_no_provenance():
    entry = SimpleNamespace(
        id="e1",
        stageKey=_stage_key(0),
        entityKey=stages()[0].entities[0].key,
        ordinal=0,
        clientKey=None,
        data={"title": "A"},
        fieldProvenance=None,
        createdAt=AT,
        updatedAt=AT,
        createdById=None,
    )
    snapshot = aids._snapshot_stages([entry])
    assert "provenance" not in snapshot[_stage_key(0)]


def test_the_purge_keeps_a_year_after_deletion():
    assert timedelta(days=365) == aids.SNAPSHOT_RETENTION_AFTER_DELETE
