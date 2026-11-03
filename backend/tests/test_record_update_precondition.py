from __future__ import annotations

from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.schemas.records import (
    ArtisanUpdate,
    CraftUpdate,
    ProcessUpdate,
    ProductUpdate,
    ToolUpdate,
    WorkshopUpdate,
)
from app.services import records as records_service

UPDATE_SCHEMAS = (
    ArtisanUpdate,
    CraftUpdate,
    WorkshopUpdate,
    ProductUpdate,
    ToolUpdate,
    ProcessUpdate,
)

STORED_AT = datetime(2026, 9, 3, 10, 0, 0, tzinfo=UTC)


def _record(updated_at: Any = STORED_AT) -> Any:
    return SimpleNamespace(id="rec-1", updatedAt=updated_at)



def test_all_six_update_schemas_accept_the_precondition_and_default_it_to_absent():
    for schema in UPDATE_SCHEMAS:
        model = schema()
        assert model.expectedUpdatedAt is None, schema.__name__
        assert "expectedUpdatedAt" not in model.model_dump(exclude_unset=True), schema.__name__


def test_the_precondition_parses_the_timestamp_the_api_itself_emits():
    parsed = ProductUpdate(expectedUpdatedAt="2026-09-03T10:00:00+00:00")
    assert parsed.expectedUpdatedAt == STORED_AT
    assert ProductUpdate(expectedUpdatedAt="2026-09-03T10:00:00Z").expectedUpdatedAt == STORED_AT


def test_an_unparseable_precondition_is_refused_rather_than_ignored():
    with pytest.raises(ValidationError, match="expectedUpdatedAt"):
        ProductUpdate(expectedUpdatedAt="last tuesday")



def test_the_precondition_is_popped_out_of_the_body_before_anything_reads_it():
    data: dict[str, Any] = {"notes": "corrected", "expectedUpdatedAt": STORED_AT}
    taken = records_service.take_expected_updated_at(data)
    assert taken == STORED_AT
    assert data == {"notes": "corrected"}, "the key is gone, and nothing else moved"


def test_taking_an_absent_precondition_answers_none_and_leaves_the_body_alone():
    data: dict[str, Any] = {"notes": "corrected"}
    assert records_service.take_expected_updated_at(data) is None
    assert data == {"notes": "corrected"}


def test_a_non_datetime_that_somehow_reached_the_body_is_dropped_rather_than_compared():
    data: dict[str, Any] = {"expectedUpdatedAt": "2026-09-03T10:00:00Z"}
    assert records_service.take_expected_updated_at(data) is None
    assert "expectedUpdatedAt" not in data, "still popped, so it can never reach Prisma"



def test_no_precondition_passes_which_is_every_client_shipped_to_date():
    records_service.assert_expected_updated_at(_record(), None)


def test_an_exact_match_passes():
    records_service.assert_expected_updated_at(_record(), STORED_AT)


def test_a_precision_loss_a_real_round_trip_produces_still_matches():
    stored = datetime(2026, 9, 3, 10, 0, 0, 123456, tzinfo=UTC)
    truncated_to_milliseconds = datetime(2026, 9, 3, 10, 0, 0, 123000, tzinfo=UTC)
    records_service.assert_expected_updated_at(_record(stored), truncated_to_milliseconds)
    records_service.assert_expected_updated_at(_record(stored), stored - timedelta(seconds=1))
    records_service.assert_expected_updated_at(_record(stored), stored + timedelta(seconds=1))


def test_a_naive_timestamp_is_read_as_utc_rather_than_refused():
    records_service.assert_expected_updated_at(_record(), STORED_AT.replace(tzinfo=None))
    records_service.assert_expected_updated_at(_record(STORED_AT.replace(tzinfo=None)), STORED_AT)


def test_a_stale_precondition_is_a_409_carrying_one_quotable_sentence_and_both_times():
    stale = STORED_AT - timedelta(hours=2)
    with pytest.raises(HTTPException) as caught:
        records_service.assert_expected_updated_at(_record(), stale)
    assert caught.value.status_code == 409
    detail = caught.value.detail
    assert detail["code"] == "record_changed"
    assert detail["message"] == "Someone else changed this record after this edit was composed."
    assert detail["message"].count(".") == 1
    assert detail["expectedUpdatedAt"] == stale.isoformat()
    assert detail["currentUpdatedAt"] == STORED_AT.isoformat()


def test_the_code_tells_this_409_apart_from_the_identity_and_name_clashes():
    with pytest.raises(HTTPException) as caught:
        records_service.assert_expected_updated_at(_record(), STORED_AT - timedelta(days=1))
    assert caught.value.detail["code"] == "record_changed"
    assert caught.value.detail["code"] != "artisan_identity_conflict"


def test_a_row_with_no_stored_timestamp_passes_rather_than_inventing_a_refusal():
    records_service.assert_expected_updated_at(_record(None), STORED_AT)
    records_service.assert_expected_updated_at(SimpleNamespace(id="rec-1"), STORED_AT)


def test_the_tolerance_is_documented_as_a_narrowing_and_not_as_a_promise():
    assert timedelta(seconds=1) == records_service.EXPECTED_UPDATED_AT_TOLERANCE
    inside = STORED_AT - timedelta(milliseconds=900)
    records_service.assert_expected_updated_at(_record(), inside)
    outside = STORED_AT - timedelta(milliseconds=1100)
    with pytest.raises(HTTPException):
        records_service.assert_expected_updated_at(_record(), outside)



def test_every_update_route_asks_before_it_writes_the_audit_row():
    from pathlib import Path

    routes = Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
    for module, guard in (
        ("artisans.py", 'guard_record_edit(artisan'),
        ("crafts.py", 'guard_record_edit(craft'),
        ("products.py", 'guard_record_edit(product'),
        ("tools.py", "guard_record_edit("),
        ("processes.py", 'guard_record_edit(process, current_user, data'),
        ("workshops.py", "privileged = access.at_least"),
    ):
        source = (routes / module).read_text(encoding="utf-8")
        assert "take_expected_updated_at(data)" in source, module
        asked = source.index("assert_expected_updated_at(")
        wrote = source.index(guard)
        assert asked < wrote, f"{module}: the precondition must be asked above the ledger write"


def test_every_update_route_takes_the_key_out_of_the_body():
    from pathlib import Path

    routes = Path(__file__).resolve().parents[1] / "app" / "api" / "routes"
    for module in ("artisans.py", "crafts.py", "products.py", "tools.py", "processes.py", "workshops.py"):
        source = (routes / module).read_text(encoding="utf-8")
        popped = source.index("take_expected_updated_at(data)")
        asked = source.index("assert_expected_updated_at(")
        assert popped < asked, f"{module}: pop it out of the body before asking the question"



def test_the_interview_update_accepts_the_precondition_and_defaults_it_to_absent():
    from app.schemas.questionnaire import QuestionnaireInterviewUpdate

    assert QuestionnaireInterviewUpdate().expectedUpdatedAt is None
    parsed = QuestionnaireInterviewUpdate(expectedUpdatedAt="2026-10-16T08:00:00.123Z")
    assert parsed.expectedUpdatedAt == datetime(2026, 10, 16, 8, 0, 0, 123000, tzinfo=UTC)


def test_the_interview_route_pops_the_key_and_asks_before_the_ledger_write():
    from pathlib import Path

    source = (Path(__file__).resolve().parents[1] / "app" / "api" / "routes" / "questionnaire.py").read_text(
        encoding="utf-8"
    )
    body = source[source.index("async def update_interview(") :]
    popped = body.index("take_expected_updated_at(data)")
    asked = body.index("claim_expected_updated_at(tx.questionnaireinterview, interview")
    wrote = body.index("guard_record_edit(")
    assert popped < asked < wrote
