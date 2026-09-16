"""A SANCTION ORDER MAKES TWO PEOPLE UNDELETABLE, AND THE 409 HAS TO SAY SO ABOUT BOTH.

NO DATABASE. ``_undeletable_detail`` is a pure function over two lists of ``(noun, count)`` pairs,
and every failure this module is written against is a failure of WORDING — which is exactly the kind
of defect no type checker, no lint and no integration test has an opinion about.

══ THE VERB IS THE WHOLE POINT ════════════════════════════════════════════════════════════════

``SanctionOrder`` has two ``onDelete: Restrict`` foreign keys onto ``User``: ``createdById``, the
ministry officer who recorded the order, and ``designerUserId``, the designer it was issued to. Both
refuse the delete, so an admin can meet the same 409 for either reason — and only ONE of them is
authorship.

``_undeletable_detail``'s sentence is *"This account created …"*. Folding the designer relation into
``_CREATOR_RELATIONS`` would print **"This account created 1 sanction order"**, which is false about
the one account it is describing, on the one screen where an admin is deciding what to do with
somebody's record. So there are two lists and two sentences.

══ AND THE GENERIC BRANCH IS THE HALF THAT WAS EASY TO MISS ═══════════════════════════════════

Before this change the ``if not owned:`` branch answered *"referenced by records that are kept for
research"* — no number, no noun — for anything ``_CREATOR_RELATIONS`` did not name. A designer who
has created nothing and is named on one sanction order is exactly that case, so adding the second
list WITHOUT guarding that branch on both would have left the commonest new 409 answering the
vaguest possible sentence. ``test_a_designer_who_created_nothing_is_still_told_what_is_in_the_way``
is what refuses it.
"""

from __future__ import annotations

from app.api.routes.users import (
    _CREATOR_RELATIONS,
    _NAMED_ON_RELATIONS,
    _undeletable_detail,
)


def test_the_officer_who_recorded_an_order_is_told_which_records_are_in_the_way() -> None:
    """Authorship, so the first sentence, with the number an admin's next move depends on.

    Three records is a reassignment; four hundred is a deactivation. An admin who is not told the
    number has to go and count it themselves.
    """
    detail = _undeletable_detail([("sanction order", 3)], [])
    assert "This account created 3 sanction orders." in detail
    assert "Deactivate it instead" in detail


def test_a_designer_who_created_nothing_is_still_told_what_is_in_the_way() -> None:
    """THE GENERIC SENTENCE MUST NOT ANSWER HERE, and this is the assertion that stops it.

    A designer named on a sanction order has created nothing, so ``owned`` is empty — which is the
    exact condition the old code read as "a relation this list does not name" and answered with no
    number and no noun. The admin would have been told their colleague is "referenced by records that
    are kept for research", which is true of half the database and actionable about none of it.
    """
    detail = _undeletable_detail([], [("sanction order", 1)])
    assert "referenced by records that are kept for research" not in detail
    assert "named on 1 sanction order" in detail
    assert "which record who the ministry issued them to" in detail


def test_an_account_that_is_both_author_and_subject_gets_both_sentences() -> None:
    """An officer who recorded an order and was later named on one of their own is one account.

    Both relations refuse the delete, so an admin told only about the half that happens to be
    authorship has been sent on the first of two trips: they reassign the workshops, press delete
    again, and meet the same 409 for a reason nobody mentioned.
    """
    detail = _undeletable_detail([("design workshop", 2)], [("sanction order", 1)])
    assert detail.startswith("This account created 2 design workshops.")
    assert "It is also named on 1 sanction order" in detail


def test_a_relation_neither_list_names_still_gets_an_honest_sentence() -> None:
    """The fallback survives, and it must: the lists are hand-kept and can only UNDER-state a tally.

    The database refuses the delete whether or not either list is complete, so drift costs a vaguer
    message and never a lost record. Inventing a number here would be worse than saying nothing.
    """
    detail = _undeletable_detail([], [])
    assert "referenced by records that are kept for research" in detail
    assert "Deactivate it instead" in detail


def test_both_halves_of_the_sanction_relation_are_registered() -> None:
    """One row in each list, and NOT two rows in one.

    The pairing is the whole design: two verbs, two sentences. A future edit that moved the designer
    relation into ``_CREATOR_RELATIONS`` for tidiness would compile, pass every other test, and
    start telling admins that designers authored the orders issued to them.

    ⚠ **THE NAMED-ON HALF MOVED TABLE IN 0.0.12 AND THIS ASSERTION MOVED WITH IT.** It read
    ``("sanctionorder", "designerUserId", "sanction order")`` until multi-designer sanction orders
    landed. ``SanctionOrderDesigner`` now carries a row for every designer an order names INCLUDING
    the lead, so the two are no longer interchangeable and the join is the one to count: the scalar
    knows only leads, and a co-designer — the second and third names, which is the whole point of
    the release — has no row on ``SanctionOrder`` at all and would have been answered "0".

    The authorship half is UNCHANGED and still reads ``SanctionOrder.createdById``: the officer who
    recorded an order is on the order itself and has no row in the join, which is correct — they are
    not one of the designers it names.
    """
    assert ("sanctionorder", "createdById", "sanction order") in _CREATOR_RELATIONS
    assert ("sanctionorderdesigner", "designerUserId", "sanction order") in _NAMED_ON_RELATIONS
    assert all(column != "designerUserId" for _model, column, _noun in _CREATOR_RELATIONS)
    # AND NOT BOTH. The lead has a row in each table, so a tuple for each would report a designer
    # who leads one order as named on two — see the block above `_NAMED_ON_RELATIONS` itself.
    assert ("sanctionorder", "designerUserId", "sanction order") not in _NAMED_ON_RELATIONS
    assert len(_NAMED_ON_RELATIONS) == 1, _NAMED_ON_RELATIONS


def test_the_plural_is_right_at_one_and_at_more_than_one() -> None:
    """"1 sanction orders" on a permission refusal reads as a machine talking, on the screen where an
    admin is deciding what to do with a colleague's record."""
    assert "named on 1 sanction order," in _undeletable_detail([], [("sanction order", 1)])
    assert "named on 2 sanction orders," in _undeletable_detail([], [("sanction order", 2)])
