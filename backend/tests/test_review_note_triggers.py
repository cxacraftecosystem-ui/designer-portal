"""F14: filing a correction suggestion, and sending a report back, each tell the designers by e-mail.

Drives the two route functions directly with their database seams replaced — the loader, the plan
writer and the answer builder — so what is asserted is the one thing this module is about: that
each door calls ``email_outbox.notify_review_note`` once, with the right kind, AFTER its write,
and that a send-back the transaction refuses notifies nobody. The notice itself (recipients,
opt-out, templates) is ``test_email_outbox.py``.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any, Self

import pytest
from fastapi import HTTPException

from app.api.routes import design_workshop_inspections as routes
from app.schemas.design_workshop_inspections import DwInspectionFeedbackIn, DwInspectionSendBackIn

NOTE = "Stage 7's cost table does not add up."


class _Tx:
    def __init__(self, record: Any) -> None:
        self.designworkshop = SimpleNamespace(find_unique=self._find)
        self.record = record

    async def _find(self, where: dict[str, Any]) -> Any:
        return self.record

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False


@pytest.fixture
def seams(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    record = SimpleNamespace(
        id="ws_1", title="Kantha, Bolpur", status="PRE_SUBMISSION", submissionRound=1, createdById="d1"
    )
    events: list[str] = []
    calls: list[dict[str, Any]] = []
    still_under_review = {"value": True}

    async def loader(workshop_id: str, user: Any) -> Any:
        return record

    async def apply(client: Any, plan: Any) -> None:
        events.append(f"write:{plan.table}")

    async def apply_guarded(client: Any, plan: Any) -> int:
        events.append("write:guarded")
        return 1 if still_under_review["value"] else 0

    async def answer(row: Any, **kwargs: Any) -> dict[str, Any]:
        return {"id": row.id}

    async def notify(row: Any, **kwargs: Any) -> int:
        events.append("notify")
        calls.append({"record": row, **kwargs})
        return 1

    monkeypatch.setattr(routes, "load_inspectable_workshop_or_404", loader)
    monkeypatch.setattr(routes, "_apply", apply)
    monkeypatch.setattr(routes, "_apply_while_under_review", apply_guarded)
    monkeypatch.setattr(routes, "_feedback_answer", answer)
    monkeypatch.setattr(routes.email_outbox, "notify_review_note", notify)
    monkeypatch.setattr(routes, "db", SimpleNamespace(tx=lambda: _Tx(record)))
    return SimpleNamespace(
        record=record, events=events, calls=calls, still_under_review=still_under_review
    )


INSPECTOR = SimpleNamespace(id="insp_1", name="Ravi Nair", role="INSPECTOR")


def test_filing_a_suggestion_notifies_the_designers_after_the_row_is_written(seams: Any) -> None:
    asyncio.run(
        routes.record_inspection_feedback(
            "ws_1", DwInspectionFeedbackIn(note=NOTE, stageKey=None), INSPECTOR
        )
    )
    assert seams.events[-1] == "notify" and seams.events.count("notify") == 1
    (call,) = seams.calls
    assert call["sent_back"] is False and call["note"] == NOTE and call["actor"] is INSPECTOR


def test_sending_back_notifies_the_designers_once_the_transaction_has_committed(seams: Any) -> None:
    asyncio.run(
        routes.send_workshop_back_for_revision(
            "ws_1", DwInspectionSendBackIn(note=NOTE, stageKey=None), INSPECTOR
        )
    )
    assert seams.events[-1] == "notify"
    (call,) = seams.calls
    assert call["sent_back"] is True and call["note"] == NOTE


def test_a_send_back_the_write_refuses_notifies_nobody(seams: Any) -> None:
    seams.still_under_review["value"] = False
    with pytest.raises(HTTPException):
        asyncio.run(
            routes.send_workshop_back_for_revision(
                "ws_1", DwInspectionSendBackIn(note=NOTE, stageKey=None), INSPECTOR
            )
        )
    assert seams.calls == []
