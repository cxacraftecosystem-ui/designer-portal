"""An interview's ``updatedAt`` is its version, answers included, and the precondition is race-free.

WHY THIS EXISTS
================

The handset queues an interview correction with the ``updatedAt`` it was composed against, and the
server refuses it with a 409 when the interview has moved. That check was only as good as the
version it read, and it was blind in two ways:

* answers live in ``QuestionnaireResponse``, so a second interviewer adding or changing answers
  never moved the interview's ``updatedAt`` — a correction composed before their answers passed the
  precondition and replaced what they had written;
* the check compared against a row read before the transaction, so two corrections composed against
  one version and replayed together could both pass.

``upsert_responses`` now bumps the interview whenever it writes an answer, and the route claims the
version with a compare-and-set inside its transaction. No database: the delegates are fakes, which
answer the questions of ORDER and of "was a bump written" more directly than Postgres can.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.api.routes import questionnaire as questionnaire_routes
from app.services import records as records_service

STORED_AT = datetime(2026, 1, 5, 9, 0, 0, tzinfo=UTC)


class _Delegate:
    """Records every call; ``update_many`` answers how many rows matched."""

    def __init__(self, *, matched: int = 1, rows: list[Any] | None = None, current: Any = None):
        self.matched = matched
        self.rows = rows or []
        self.current = current
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def update_many(self, **kwargs: Any) -> int:
        self.calls.append(("update_many", kwargs))
        return self.matched

    async def find_unique(self, **kwargs: Any) -> Any:
        self.calls.append(("find_unique", kwargs))
        return self.current

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.calls.append(("find_many", kwargs))
        return self.rows

    async def create_many(self, **kwargs: Any) -> int:
        self.calls.append(("create_many", kwargs))
        return len(kwargs.get("data", []))

    async def update(self, **kwargs: Any) -> Any:
        self.calls.append(("update", kwargs))
        return None


# --------------------------------------------------------------------------------------
# The compare-and-set
# --------------------------------------------------------------------------------------


async def test_no_precondition_claims_nothing():
    delegate = _Delegate()
    await records_service.claim_expected_updated_at(
        delegate, SimpleNamespace(id="iv-1", updatedAt=STORED_AT), None
    )
    assert delegate.calls == []


async def test_a_claim_moves_the_version_only_while_it_still_holds_the_value_read():
    delegate = _Delegate(matched=1)
    await records_service.claim_expected_updated_at(
        delegate, SimpleNamespace(id="iv-1", updatedAt=STORED_AT), STORED_AT
    )
    (name, kwargs), = delegate.calls
    assert name == "update_many"
    assert kwargs["where"] == {"id": "iv-1", "updatedAt": STORED_AT}
    assert kwargs["data"]["updatedAt"] > STORED_AT


async def test_a_lost_claim_is_the_same_409_as_a_stale_precondition():
    """Two corrections composed against one version: the second finds the row already moved."""
    moved = datetime(2026, 1, 5, 9, 0, 5, tzinfo=UTC)
    delegate = _Delegate(matched=0, current=SimpleNamespace(id="iv-1", updatedAt=moved))
    with pytest.raises(HTTPException) as refused:
        await records_service.claim_expected_updated_at(
            delegate, SimpleNamespace(id="iv-1", updatedAt=STORED_AT), STORED_AT
        )
    assert refused.value.status_code == 409
    assert refused.value.detail["code"] == "record_changed"
    assert refused.value.detail["currentUpdatedAt"] == moved.isoformat()


async def test_a_row_without_a_stored_version_is_not_claimed():
    delegate = _Delegate()
    await records_service.claim_expected_updated_at(
        delegate, SimpleNamespace(id="iv-1", updatedAt=None), STORED_AT
    )
    assert delegate.calls == []


def test_the_interview_route_asks_then_claims_before_any_other_write():
    source = (
        Path(__file__).resolve().parents[1] / "app" / "api" / "routes" / "questionnaire.py"
    ).read_text(encoding="utf-8")
    body = source[source.index("async def update_interview(") :]
    asked = body.index("assert_expected_updated_at(interview")
    claimed = body.index("claim_expected_updated_at(tx.questionnaireinterview")
    wrote = body.index("guard_record_edit(")
    assert asked < claimed < wrote


# --------------------------------------------------------------------------------------
# Answers move the interview's version
# --------------------------------------------------------------------------------------


def _client(existing: list[Any], question_ids: list[str]) -> SimpleNamespace:
    return SimpleNamespace(
        questionnaireresponse=_Delegate(rows=existing),
        questionnairequestion=_Delegate(rows=[SimpleNamespace(id=q) for q in question_ids]),
        questionnaireinterview=_Delegate(),
    )


def _answer(question_id: str, text: str) -> Any:
    return SimpleNamespace(questionId=question_id, answerText=text, notes=None)


async def test_a_new_answer_from_another_interviewer_moves_the_interview_version():
    client = _client([], ["q-1"])
    await questionnaire_routes.upsert_responses(
        "iv-1", [_answer("q-1", "Lac turned on a hand lathe")], SimpleNamespace(id="u-2", role="RESEARCHER"),
        client=client,
    )
    bumps = [c for c in client.questionnaireinterview.calls if c[0] == "update"]
    assert len(bumps) == 1
    assert bumps[0][1]["where"] == {"id": "iv-1"}
    assert isinstance(bumps[0][1]["data"]["updatedAt"], datetime)


async def test_a_changed_answer_moves_the_interview_version():
    stored = SimpleNamespace(id="r-1", questionId="q-1", answerText="old", notes=None, answeredById="u-2")
    client = _client([stored], ["q-1"])
    await questionnaire_routes.upsert_responses(
        "iv-1", [_answer("q-1", "new")], SimpleNamespace(id="u-2", role="RESEARCHER"), client=client
    )
    assert [c[0] for c in client.questionnaireinterview.calls] == ["update"]


async def test_resending_identical_answers_leaves_the_version_alone():
    """A save that changed nothing must not make every queued correction on other phones conflict."""
    stored = SimpleNamespace(id="r-1", questionId="q-1", answerText="same", notes=None, answeredById="u-2")
    client = _client([stored], ["q-1"])
    await questionnaire_routes.upsert_responses(
        "iv-1", [_answer("q-1", "same")], SimpleNamespace(id="u-2", role="RESEARCHER"), client=client
    )
    assert client.questionnaireinterview.calls == []
