"""**THE STATUS CHECK ON A SEND-BACK IS ADVISORY; THE ONE ON THE WRITE IS NOT.**

``POST /api/design-workshop-inspections/{id}/send-back`` reads the workshop OUTSIDE any transaction,
validates its status with ``_under_review_or_422`` and ``transition_refusal`` — and then applied a
write plan whose WHERE was the primary key alone. Between the read and the commit there are three
round trips (building the plans, BEGIN, the feedback INSERT), and the workshop can leave the set the
check validated against inside them.

It is not a hypothetical window. The ONLY header edit ``patchable_from("PRE_SUBMISSION")`` offers is
``-> IN_PROGRESS``; the web record page ships it as a literal button labelled "Withdraw from
inspection"; and the review graph's own commentary instructs designers to take it ("Withdraw it
first (-> IN_PROGRESS)"). So:

    officer presses "Send the report back"  ─┐
                                             ├─ the designer's PATCH commits first
    designer presses "Withdraw from …"  ─────┘
    → the officer's UPDATE re-evaluates a predicate that is the id, and writes NEEDS_REVISION
      over IN_PROGRESS

``IN_PROGRESS -> NEEDS_REVISION`` is not an edge ``LEGAL_TRANSITIONS`` contains at all — this very
module's ``transition_refusal`` rejects it even with ``by_decision_route=True`` — so the workshop
lands in a state the graph declares unreachable. Three consequences follow, and the third is the
expensive one:

* a ``ReviewLog`` row asserts a decision on a report that had already left review;
* the designer's deliberate withdrawal is silently reverted with nothing saying so;
* their NEXT content-changing stage save trips ``design_workshops``'
  ``NEEDS_REVISION and _content_changed`` arm, which applies ``presubmission_header`` — silently
  RESUBMITTING the report to the officers and spending a submission round, which permanently
  mis-stamps every later ``DwInspectionFeedback.round`` (that column is copied at write time and
  never recomputed).

``save_stage``'s twin write closes exactly this shape with ``update_many(where={"id": …, "status":
"NEEDS_REVISION"})``; this path did not.

**NO DATABASE.** The whole defect is the shape of one WHERE clause, so the delegate is a recorder
and the assertion is over what was issued. A DB-backed version would have to interleave two
transactions to prove the same sentence.
"""

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
    """A function's statements without its prose, so a docstring cannot answer a source assertion.

    The same device ``tests/test_workshop_oversight_unit.py`` uses. Without it, a paragraph
    EXPLAINING that the predicate must not be a literal satisfies a test looking for the absence of
    that literal — the assertion passes on the words rather than on the code.
    """
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
    """A Prisma delegate that records the one call and answers a chosen count.

    NOT A MOCK LIBRARY AND NOT A FAKE THAT ACCEPTS ANY METHOD, for the reason
    ``test_designer_prefill_contract._StubProfileTable`` gives: a stub this small fails loudly if
    the function under test starts doing something else — a second query, a different table, an
    ``update`` where an ``update_many`` was meant — rather than absorbing it silently.
    """

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
    """The fix, stated as the statement that is issued.

    Under READ COMMITTED an ``UPDATE … WHERE id = ?`` waits on the designer's row lock and then
    re-evaluates its predicate. With the id alone that predicate is still true and the write lands
    on whatever status the designer just committed; with the status term in it, the row no longer
    matches and nothing is written.
    """
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
    """**THE ``NEEDS_REVISION`` MEMBER OF THE SET IS DELIBERATE AND MUST STAY.**

    The route documents this case as a 200 and a row rather than a refusal — "Two officers reading
    one report at the same time is the ordinary case, and refusing the second would lose their
    correction to a race" — and ``UNDER_REVIEW`` is ``{PRE_SUBMISSION, NEEDS_REVISION}``, so a
    predicate derived from it keeps that promise. A narrower guard spelled ``"status":
    "PRE_SUBMISSION"`` would look like a tightening and would silently drop the second officer's
    decision.
    """
    client = _Client(written=1)
    await routes._apply_while_under_review(client, _plans().workshop)
    [issued] = client.designworkshop.calls
    assert loop.NEEDS_REVISION in issued["where"]["status"]["in"]
    assert loop.PRE_SUBMISSION in issued["where"]["status"]["in"]


async def test_a_workshop_that_left_review_writes_nothing_and_is_answered_as_such() -> None:
    """Zero rows means somebody moved it first, and the count is the only signal there is.

    ``update_many`` answers a count rather than a record, which is precisely why the caller cannot
    simply carry on: a handler that ignored it would pass every test, write the feedback row and the
    audit row, and answer 200 about a decision that never landed.
    """
    client = _Client(written=0)
    assert await routes._apply_while_under_review(client, _plans().workshop) == 0
    assert len(client.designworkshop.calls) == 1


def test_the_status_predicate_is_derived_from_the_review_loop_and_not_typed_out() -> None:
    """A literal here is a guard that a ninth status silently walks past.

    ``_CLOSED_STATUSES`` in the oversight service was a literal for exactly one wave and was wrong
    by the end of it; the same mistake in this predicate would let a send-back land on a state the
    graph had since taken out of review.
    """
    body = _body_without_docstring(routes._apply_while_under_review)
    assert "UNDER_REVIEW" in body
    assert '"PRE_SUBMISSION"' not in body, body
    assert '"NEEDS_REVISION"' not in body, body


def test_the_send_back_refuses_inside_the_transaction_so_the_suggestion_rolls_back_too() -> None:
    """**THE ORDER OF THE THREE WRITES IS WHAT MAKES THE REFUSAL SAFE.**

    The feedback row is written first, so a refusal raised after it has to roll it back — a
    correction filed against a round that is no longer open would sit in the register naming a cycle
    nobody can answer, because ``round`` is copied at write time and never recomputed. Raising
    inside ``async with db.tx()`` does that; raising after the block would not, and neither would
    answering 200 and skipping the log.

    Read at the source, because what is asserted is where a ``raise`` sits relative to a ``with``.
    """
    source = inspect.getsource(routes.send_workshop_back_for_revision)
    tx = source.index("async with db.tx()")
    feedback = source.index("_apply(tx, plans.feedback)")
    guarded = source.index("_apply_while_under_review(tx, plans.workshop)")
    refusal = source.index("NOT_UNDER_REVIEW_REFUSAL", guarded)
    log = source.index("_apply(tx, plans.log)")

    assert tx < feedback < guarded < refusal < log, (
        "the refusal is not raised between the guarded write and the audit row, inside the "
        "transaction that also holds the suggestion"
    )
    assert "_apply(tx, plans.workshop)" not in source, (
        "the unguarded write plan is still applied; the status predicate has been bypassed"
    )
