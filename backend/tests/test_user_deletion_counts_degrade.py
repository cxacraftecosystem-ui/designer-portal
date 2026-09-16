"""A RELATION THE CLIENT CANNOT READ COSTS A VAGUER 409, NEVER A 500.

NO DATABASE. Everything here is a property of ``routes/users.py``'s counting helpers, and the
failure it is written against is a failure of DEGRADATION — the kind that only shows up in an
environment nobody is looking at, which is exactly why it wants a test that needs no environment.

══ THE REGRESSION ═════════════════════════════════════════════════════════════════════════════

On 2026-09-16, ``DELETE /api/users/{id}`` answered **500** with ``'Prisma' object has no attribute
'sanctionorderdesigner'`` — raised from inside the ``except ForeignKeyViolationError`` handler whose
entire job is to replace a 500 with a sentence an admin can act on. ``SanctionOrderDesigner`` was in
``schema.prisma``, its migration was in ``prisma/migrations``, and ``_NAMED_ON_RELATIONS`` named it
correctly. What was wrong was STATE, not code: ``prisma generate`` had not been re-run against the
new model, and the migration had not been applied to the local database.

``_counts_over`` resolves each model BY NAME at REQUEST time, so that is not a one-off. A stale
generated client in any checkout or image, a model renamed in ``schema.prisma`` before this file
catches up, a migration written but not deployed, a migration applied in part — each of them takes
out an administrator's endpoint, and none of them is a reason to.

AND THE FIRST OF THOSE IS THE DEFAULT ON WINDOWS, WHICH IS WHY THIS FILE EXISTS RATHER THAN A NOTE
IN THE RUNBOOK. ``backend/scripts/regenerate-client.md`` already documents it, diagnosed 2026-09-14:
``python -m prisma generate`` on Windows dies with ``Error: spawn prisma-client-py ENOENT`` — the
Node CLI spawns the provider by bare name with no ``shell: true``, ``PATHEXT`` never applies, and
putting the venv on ``PATH`` does not help because the NAME is wrong, not the directory. **And the
command exits 0.** Re-verified 2026-09-16. The supported repair is a Docker round trip through Linux
that copies twelve generated modules plus ``site-packages/prisma/schema.prisma`` back into the venv
by hand. So the one command that keeps the client in step fails silently, reports success, and is
expensive enough to postpone. "The client will have the model" is not a guarantee anything
downstream may rely on — which is the whole reason the helpers under test degrade instead of raise.

══ WHY THE 409 IS STILL THE RIGHT ANSWER WITH NOTHING COUNTED ═════════════════════════════════

Postgres refused the delete BEFORE any of this code ran. That refusal is the verdict; the counts are
decoration on it. They make the message more useful, they cannot make it more correct — so failing
to compute them may change the message and must not change the status.
"""

from __future__ import annotations

import asyncio
import logging

import pytest

from app.api.routes import users as users_route
from app.api.routes.users import _counts_over, _undeletable_detail


class _ClientWithoutTheModel:
    """A generated Prisma client from before the model was added: the attribute is simply absent.

    A plain object rather than a mock, because the real client declares its models in ``__slots__``
    with no ``__getattr__`` and the behaviour under test is precisely what ``getattr`` does when the
    name is missing. A mock would auto-create the attribute and pass a test that proves nothing.
    """


class _CountThatRaises:
    """A client that HAS the model, against a database whose migration has not been applied.

    This is the second half of the 2026-09-16 failure and it is a different exception from a
    different layer: the delegate resolves, the query goes out, and Postgres answers that the
    relation does not exist. A fix that guarded only ``getattr`` would still have 500'd here.
    """

    class _Delegate:
        async def count(self, where: dict[str, str]) -> int:
            raise RuntimeError('relation "SanctionOrderDesigner" does not exist')

    class _Works:
        async def count(self, where: dict[str, str]) -> int:
            return 4

    sanctionorderdesigner = _Delegate()
    questionnaire = _Works()


def test_a_model_the_client_does_not_carry_is_unknown_rather_than_an_exception(monkeypatch) -> None:
    """The exact 2026-09-16 shape: the tuple names a model the generated client has never heard of.

    The old code did ``getattr(db, model)`` with no default, so this raised ``AttributeError`` out
    of the handler. The assertion is that ``_counts_over`` RETURNS — the status code of the whole
    endpoint hangs on that single fact.
    """
    monkeypatch.setattr(users_route, "db", _ClientWithoutTheModel())

    tally = asyncio.run(
        _counts_over("user-1", (("sanctionorderdesigner", "designerUserId", "sanction order"),))
    )

    assert tally.named == []
    assert tally.uncounted == ["sanction order"]


def test_a_count_that_raises_is_unknown_rather_than_an_exception(monkeypatch) -> None:
    """A pending migration, which ``getattr`` alone would not have caught — and the siblings live.

    The second assertion is the point of counting one relation at a time rather than wrapping both
    tallies in one ``try``: the questionnaire count is perfectly readable and the admin still gets
    its number, alongside an honest admission that something else could not be read.
    """
    monkeypatch.setattr(users_route, "db", _CountThatRaises())

    tally = asyncio.run(
        _counts_over(
            "user-1",
            (
                ("questionnaire", "ownerId", "questionnaire"),
                ("sanctionorderdesigner", "designerUserId", "sanction order"),
            ),
        )
    )

    assert tally.named == [("questionnaire", 4)]
    assert tally.uncounted == ["sanction order"]


def test_the_drift_is_logged_even_though_the_message_is_quiet_about_it(monkeypatch, caplog) -> None:
    """Degrading is not the same as swallowing.

    The case against catching here is that a silent failure is a silent failure, and it is a fair
    case. The answer is that the fact goes to the channel built for facts a DEVELOPER needs, naming
    the model, instead of to an admin's error toast where it displaces the one sentence that would
    have helped them. If this assertion ever goes, the objection stops being answered.
    """
    monkeypatch.setattr(users_route, "db", _ClientWithoutTheModel())

    with caplog.at_level(logging.ERROR, logger=users_route.__name__):
        asyncio.run(
            _counts_over("user-1", (("sanctionorderdesigner", "designerUserId", "sanction order"),))
        )

    assert "sanctionorderdesigner" in caplog.text
    # The fix, named, because a log line that does not say what to do is a log line nobody acts on —
    # and the runbook rather than the bare command, which on Windows exits 0 having done nothing.
    assert "regenerate-client.md" in caplog.text


def test_an_unreadable_relation_admits_itself_without_inventing_a_number() -> None:
    """The hedge names the NOUN and claims nothing about how many — because it does not know.

    "It is also named on sanction orders" would assert that some exist. In the failure this was
    written against there were none: the departing designer owned one questionnaire and nothing
    else, so that sentence would have sent an admin hunting the sanction register for a row that is
    not there. What the message may honestly say is that it could not look.
    """
    detail = _undeletable_detail([("questionnaire", 1)], [], uncounted=["sanction order"])

    # Everything that COULD be counted is still counted, with its number: degrading one relation
    # must not cost the others.
    assert "This account created 1 questionnaire." in detail
    assert "deactivate" in detail.lower()
    assert "This list may be incomplete: sanction orders could not be counted." in detail
    # No number claimed for the relation that could not be read, and no assertion that it has rows.
    assert "named on" not in detail


def test_the_same_noun_from_both_tuples_is_said_once() -> None:
    """``_CREATOR_RELATIONS`` and ``_NAMED_ON_RELATIONS`` both spell the register "sanction order".

    A stale client resolves neither, ``delete_user`` concatenates the two ``uncounted`` lists, and
    without de-duplication the sentence reads "sanction orders and sanction orders".
    """
    detail = _undeletable_detail(
        [("questionnaire", 1)], [], uncounted=["sanction order", "sanction order"]
    )

    assert detail.count("sanction orders") == 1


def test_several_unreadable_nouns_are_listed_as_english() -> None:
    detail = _undeletable_detail(
        [("questionnaire", 1)],
        [],
        uncounted=["artisan record", "workshop", "sanction order"],
    )

    assert "artisan records, workshops and sanction orders could not be counted." in detail


def test_the_no_number_branch_is_not_hedged() -> None:
    """With nothing counted at all, the generic sentence already says everything a hedge would.

    It names no number, no noun and no relation, and offers the same remedy. Appending "this list
    may be incomplete" to a message that presents no list is noise dressed as information — and this
    is the branch a wholly stale client lands on, i.e. the one an admin is most likely to meet in
    the drifted environment.
    """
    detail = _undeletable_detail([], [], uncounted=["sanction order"])

    assert detail == (
        "This account is referenced by records that are kept for research, so it cannot be "
        "deleted. Deactivate it instead, or ask a master admin to reassign what it owns."
    )


def test_an_undrifted_deployment_reads_exactly_as_it_did() -> None:
    """The default is ``()``, so the sentence an admin normally meets is byte-for-byte unchanged.

    ``uncounted`` is keyword-only for the same reason: ``tests/test_sanction_order_undeletable.py``
    is the specification for this function's wording and calls it with two positional lists, and a
    third positional parameter would sit where a mistaken call site would still look valid.
    """
    assert _undeletable_detail([("questionnaire", 1)], []) == _undeletable_detail(
        [("questionnaire", 1)], [], uncounted=[]
    )
    assert "may be incomplete" not in _undeletable_detail([("questionnaire", 1)], [])

    with pytest.raises(TypeError):
        _undeletable_detail([("questionnaire", 1)], [], ["sanction order"])  # type: ignore[misc]
