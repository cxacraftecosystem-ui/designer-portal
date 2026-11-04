from __future__ import annotations

import asyncio
import logging

import pytest

from app.api.routes import users as users_route
from app.api.routes.users import _counts_over, _undeletable_detail


class _ClientWithoutTheModel:
    pass


class _CountThatRaises:
    class _Delegate:
        async def count(self, where: dict[str, str]) -> int:
            raise RuntimeError('relation "SanctionOrderDesigner" does not exist')

    class _Works:
        async def count(self, where: dict[str, str]) -> int:
            return 4

    sanctionorderdesigner = _Delegate()
    questionnaire = _Works()


def test_a_model_the_client_does_not_carry_is_unknown_rather_than_an_exception(monkeypatch) -> None:
    monkeypatch.setattr(users_route, "db", _ClientWithoutTheModel())

    tally = asyncio.run(
        _counts_over("user-1", (("sanctionorderdesigner", "designerUserId", "sanction order"),))
    )

    assert tally.named == []
    assert tally.uncounted == ["sanction order"]


def test_a_count_that_raises_is_unknown_rather_than_an_exception(monkeypatch) -> None:
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
    monkeypatch.setattr(users_route, "db", _ClientWithoutTheModel())

    with caplog.at_level(logging.ERROR, logger=users_route.__name__):
        asyncio.run(
            _counts_over("user-1", (("sanctionorderdesigner", "designerUserId", "sanction order"),))
        )

    assert "sanctionorderdesigner" in caplog.text
    assert "regenerate-client.md" in caplog.text


def test_an_unreadable_relation_admits_itself_without_inventing_a_number() -> None:
    detail = _undeletable_detail([("questionnaire", 1)], [], uncounted=["sanction order"])

    assert "This account created 1 questionnaire." in detail
    assert "deactivate" in detail.lower()
    assert "This list may be incomplete: sanction orders could not be counted." in detail
    assert "named on" not in detail


def test_the_same_noun_from_both_tuples_is_said_once() -> None:
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
    detail = _undeletable_detail([], [], uncounted=["sanction order"])

    assert detail == (
        "This account is referenced by records that are kept for research, so it cannot be "
        "deleted. Deactivate it instead, or ask a master admin to reassign what it owns."
    )


def test_an_undrifted_deployment_reads_exactly_as_it_did() -> None:
    assert _undeletable_detail([("questionnaire", 1)], []) == _undeletable_detail(
        [("questionnaire", 1)], [], uncounted=[]
    )
    assert "may be incomplete" not in _undeletable_detail([("questionnaire", 1)], [])

    with pytest.raises(TypeError):
        _undeletable_detail([("questionnaire", 1)], [], ["sanction order"])  # type: ignore[misc]
