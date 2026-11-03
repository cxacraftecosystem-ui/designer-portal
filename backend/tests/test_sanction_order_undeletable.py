from __future__ import annotations

from app.api.routes.users import (
    _CREATOR_RELATIONS,
    _NAMED_ON_RELATIONS,
    _undeletable_detail,
)


def test_the_officer_who_recorded_an_order_is_told_which_records_are_in_the_way() -> None:
    detail = _undeletable_detail([("sanction order", 3)], [])
    assert "This account created 3 sanction orders." in detail
    assert "Deactivate it instead" in detail


def test_a_designer_who_created_nothing_is_still_told_what_is_in_the_way() -> None:
    detail = _undeletable_detail([], [("sanction order", 1)])
    assert "referenced by records that are kept for research" not in detail
    assert "named on 1 sanction order" in detail
    assert "which record who the ministry issued them to" in detail


def test_an_account_that_is_both_author_and_subject_gets_both_sentences() -> None:
    detail = _undeletable_detail([("design workshop", 2)], [("sanction order", 1)])
    assert detail.startswith("This account created 2 design workshops.")
    assert "It is also named on 1 sanction order" in detail


def test_a_relation_neither_list_names_still_gets_an_honest_sentence() -> None:
    detail = _undeletable_detail([], [])
    assert "referenced by records that are kept for research" in detail
    assert "Deactivate it instead" in detail


def test_both_halves_of_the_sanction_relation_are_registered() -> None:
    assert ("sanctionorder", "createdById", "sanction order") in _CREATOR_RELATIONS
    assert ("sanctionorderdesigner", "designerUserId", "sanction order") in _NAMED_ON_RELATIONS
    assert all(column != "designerUserId" for _model, column, _noun in _CREATOR_RELATIONS)
    assert ("sanctionorder", "designerUserId", "sanction order") not in _NAMED_ON_RELATIONS
    assert len(_NAMED_ON_RELATIONS) == 1, _NAMED_ON_RELATIONS


def test_the_plural_is_right_at_one_and_at_more_than_one() -> None:
    assert "named on 1 sanction order," in _undeletable_detail([], [("sanction order", 1)])
    assert "named on 2 sanction orders," in _undeletable_detail([], [("sanction order", 2)])
