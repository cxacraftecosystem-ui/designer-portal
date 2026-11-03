from __future__ import annotations

import ast
import inspect
import textwrap
from datetime import UTC, datetime
from typing import Any

import pytest

from app.api.routes import design_workshop_inspections as routes
from app.schemas import design_workshop_review_loop as loop

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


def _body_without_docstring(func: Any) -> str:
    tree = ast.parse(textwrap.dedent(inspect.getsource(func)))
    definition = tree.body[0]
    body = definition.body
    if (
        body
        and isinstance(body[0], ast.Expr)
        and isinstance(body[0].value, ast.Constant)
        and isinstance(body[0].value.value, str)
    ):
        body = body[1:]
    return chr(10).join(ast.unparse(node) for node in body)


class _Recorder:
    def __init__(self, written: int) -> None:
        self._written = written
        self.calls: list[dict[str, Any]] = []

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        self.calls.append({"where": where, "data": data})
        return self._written


class _Client:
    def __init__(self, written: int) -> None:
        self.designworkshop = _Recorder(written)


def _plans():
    return loop.send_back_plans(
        workshop_id="ws_x",
        round=3,
        actor_id="officer-1",
        at=datetime.now(UTC),
        note="The weave notes on stage 7 need the loom width.",
    )


async def test_the_send_back_write_carries_the_status_in_its_where_clause() -> None:
    client = _Client(written=1)
    assert await routes._apply_while_under_review(client, _plans().workshop) == 1

    [issued] = client.designworkshop.calls
    assert issued["where"]["id"] == "ws_x"
    assert set(issued["where"]["status"]["in"]) == set(loop.UNDER_REVIEW), (
        f"the write's predicate is {issued['where']}; a send-back can land on a workshop that has "
        "left review since the handler read it"
    )
    assert issued["data"]["status"] == loop.NEEDS_REVISION


async def test_the_guard_still_lets_a_second_officer_send_back_an_already_sent_back_report() -> None:
    client = _Client(written=1)
    await routes._apply_while_under_review(client, _plans().workshop)
    [issued] = client.designworkshop.calls
    assert loop.NEEDS_REVISION in issued["where"]["status"]["in"]
    assert loop.PRE_SUBMISSION in issued["where"]["status"]["in"]


async def test_a_workshop_that_left_review_writes_nothing_and_is_answered_as_such() -> None:
    client = _Client(written=0)
    assert await routes._apply_while_under_review(client, _plans().workshop) == 0
    assert len(client.designworkshop.calls) == 1


def test_the_status_predicate_is_derived_from_the_review_loop_and_not_typed_out() -> None:
    body = _body_without_docstring(routes._apply_while_under_review)
    assert "UNDER_REVIEW" in body
    assert '"PRE_SUBMISSION"' not in body, body
    assert '"NEEDS_REVISION"' not in body, body


def test_the_send_back_refuses_inside_the_transaction_so_the_suggestion_rolls_back_too() -> None:
    source = inspect.getsource(routes.send_workshop_back_for_revision)
    tx = source.index("async with db.tx()")
    feedback = source.index("_apply(tx, plans.feedback)")
    guarded = source.index("_apply_while_under_review(tx, plans.workshop)")
    refusal = source.index("review_closed_refusal", guarded)
    log = source.index("_apply(tx, plans.log)")

    assert tx < feedback < guarded < refusal < log, (
        "the refusal is not raised between the guarded write and the audit row, inside the "
        "transaction that also holds the suggestion"
    )
    assert "_apply(tx, plans.workshop)" not in source, (
        "the unguarded write plan is still applied; the status predicate has been bypassed"
    )
