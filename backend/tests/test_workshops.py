from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from app.api.routes import workshops as routes

CALLER = SimpleNamespace(id="u-caller")


def _workshop(wid: str) -> Any:
    return SimpleNamespace(
        id=wid,
        title=f"Workshop {wid}",
        place="Bhubaneswar",
        date=None,
        startDate=None,
        endDate=None,
    )


class _WorkshopTable:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.finds: list[dict[str, Any]] = []

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.finds.append(kwargs)
        take = kwargs.get("take")
        return self.rows[:take] if take else list(self.rows)


class _EmptyAssignmentTable:
    async def find_many(self, **kwargs: Any) -> list[Any]:
        return []


def _install(monkeypatch: Any, rows: list[Any]) -> _WorkshopTable:
    table = _WorkshopTable(rows)
    monkeypatch.setattr(
        routes, "db", SimpleNamespace(workshop=table, workshopassignment=_EmptyAssignmentTable())
    )
    return table



async def test_truncated_is_false_when_the_repository_holds_exactly_the_cap(monkeypatch):
    table = _install(monkeypatch, [_workshop("w1"), _workshop("w2")])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=2)
    assert payload["truncated"] is False
    assert [item["id"] for item in payload["items"]] == ["w1", "w2"]
    assert table.finds[0]["take"] == 3, (
        "the cap is 2 and only 2 rows exist, but the read must still ask for 3 — the extra row is "
        "what turns the flag from a guess into a fact, and asking for fewer defeats it before it runs"
    )


async def test_truncated_is_true_the_moment_the_repository_holds_one_more_than_the_cap(monkeypatch):
    table = _install(monkeypatch, [_workshop("w1"), _workshop("w2"), _workshop("w3")])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=2)
    assert payload["truncated"] is True
    assert [item["id"] for item in payload["items"]] == ["w1", "w2"], (
        "the one extra row read to detect the cut must never leak into what the picker draws or the "
        "caller could select an option the server never meant to offer"
    )
    assert table.finds[0]["take"] == 3


async def test_an_empty_repository_is_not_truncated(monkeypatch):
    _install(monkeypatch, [])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=2)
    assert payload == {"items": [], "truncated": False, "nextOffset": None}



async def test_the_response_is_an_items_truncated_envelope_not_the_old_bare_array(monkeypatch):
    _install(monkeypatch, [_workshop("w1")])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=5)
    assert isinstance(payload, dict), "a bare list has no key for `truncated` to live on"
    assert set(payload) == {"items", "truncated", "nextOffset"}
    assert isinstance(payload["items"], list)
    assert payload["items"][0]["id"] == "w1"
