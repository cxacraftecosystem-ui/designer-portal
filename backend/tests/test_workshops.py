"""``GET /workshops/requestable`` — the ``truncated`` envelope added for W-B2.

WHAT THIS DEFENDS
==================

``WorkshopAccessRequestPanel`` is the one picker in either client that could not state its own
ceiling: this route answered a bare array with no ``total`` and no flag, so a repository of exactly
``WORKSHOP_REQUEST_MAX`` workshops and one of ten times that size rendered identically — the picker
had nothing to read to tell "this is everything" from "this is only the first slice". That is the
non-negotiable rule this repository states everywhere else a list is capped
(``GET /tasks/options``, ``design_workshop_viewers.active_roster_emails``): a truncation has to be
stated with a number or a flag, never left for the reader to infer from a list that merely looks
plausible.

The fix copies ``GET /tasks/options``'s own trick rather than inventing a new one: read one row past
``limit``, trim it back off, and let the length comparison — not a second COUNT — decide
``truncated``. What is pinned here is that arithmetic (exact at the boundary, in both directions) and
the new envelope shape, ``{"items": [...], "truncated": bool}`` in place of the old bare array.

No database. ``list_requestable_workshops`` is called directly against a fake ``db.workshop`` /
``db.workshopassignment``, the same way ``test_workshop_trash_listing.py`` exercises the sibling list
route on ``design_workshops.py``. The caller's per-row standing (``accessStatus``/``accessLevel``/
``restricted``) already has its own coverage in ``test_workshop_access_scope.py`` and is untouched by
this change, so every fixture here hands back an empty assignment table on purpose.
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from app.api.routes import workshops as routes

#: The caller these tests file every request as. Only ``id`` is read (``get_value(current_user,
#: "id")``), so nothing else is worth carrying.
CALLER = SimpleNamespace(id="u-caller")


def _workshop(wid: str) -> Any:
    """A ``Workshop`` row carrying exactly what ``list_requestable_workshops`` projects, and no more."""
    return SimpleNamespace(
        id=wid,
        title=f"Workshop {wid}",
        place="Bhubaneswar",
        date=None,
        startDate=None,
        endDate=None,
    )


class _WorkshopTable:
    """``db.workshop`` — records every ``find_many`` call so a test can check what was actually asked
    for, the way ``test_workshop_trash_listing.py``'s ``_Table`` does for the sibling route."""

    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.finds: list[dict[str, Any]] = []

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.finds.append(kwargs)
        take = kwargs.get("take")
        return self.rows[:take] if take else list(self.rows)


class _EmptyAssignmentTable:
    """``db.workshopassignment`` — no caller in this module has ever asked about a workshop, so every
    row's ``accessStatus``/``accessLevel`` resolves null and ``restricted`` resolves False. That half
    of the route is exercised elsewhere; these tests are only about the envelope and the cap."""

    async def find_many(self, **kwargs: Any) -> list[Any]:
        return []


def _install(monkeypatch: Any, rows: list[Any]) -> _WorkshopTable:
    table = _WorkshopTable(rows)
    monkeypatch.setattr(
        routes, "db", SimpleNamespace(workshop=table, workshopassignment=_EmptyAssignmentTable())
    )
    return table


# --------------------------------------------------------------------------------------
# The arithmetic: one row read past the cap, trimmed, never guessed
# --------------------------------------------------------------------------------------


async def test_truncated_is_false_when_the_repository_holds_exactly_the_cap(monkeypatch):
    """``len(rows) == limit`` is the whole truth, not a cut, and has to read as ``False`` HONESTLY —
    this is the boundary the one-row-over trick exists to get right rather than guess at.
    """
    table = _install(monkeypatch, [_workshop("w1"), _workshop("w2")])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=2)
    assert payload["truncated"] is False
    assert [item["id"] for item in payload["items"]] == ["w1", "w2"]
    assert table.finds[0]["take"] == 3, (
        "the cap is 2 and only 2 rows exist, but the read must still ask for 3 — the extra row is "
        "what turns the flag from a guess into a fact, and asking for fewer defeats it before it runs"
    )


async def test_truncated_is_true_the_moment_the_repository_holds_one_more_than_the_cap(monkeypatch):
    """The other side of the same boundary: exactly ``limit + 1`` rows, not a thousand — the smallest
    input that must flip the flag, so a fencepost in either direction is caught here rather than only
    showing up once the real workshop table outgrows the cap by a wide, forgiving margin."""
    table = _install(monkeypatch, [_workshop("w1"), _workshop("w2"), _workshop("w3")])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=2)
    assert payload["truncated"] is True
    assert [item["id"] for item in payload["items"]] == ["w1", "w2"], (
        "the one extra row read to detect the cut must never leak into what the picker draws or the "
        "caller could select an option the server never meant to offer"
    )
    assert table.finds[0]["take"] == 3


async def test_an_empty_repository_is_not_truncated(monkeypatch):
    """Zero rows is the other honest ``False`` — not a special case, just the general rule holding at
    the bottom of the range as well as at the cap."""
    _install(monkeypatch, [])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=2)
    assert payload == {"items": [], "truncated": False}


# --------------------------------------------------------------------------------------
# The envelope: the one deliberate shape change in this route
# --------------------------------------------------------------------------------------


async def test_the_response_is_an_items_truncated_envelope_not_the_old_bare_array(monkeypatch):
    """Pinned so a well-meaning revert back to ``return public_encode(items)`` — restoring the bare
    array this route used to answer — is a failing test here, not a silent wire change discovered by
    ``WorkshopAccessRequestPanel`` throwing on the client instead.
    """
    _install(monkeypatch, [_workshop("w1")])
    payload = await routes.list_requestable_workshops(current_user=CALLER, limit=5)
    assert isinstance(payload, dict), "a bare list has no key for `truncated` to live on"
    assert set(payload) == {"items", "truncated"}
    assert isinstance(payload["items"], list)
    assert payload["items"][0]["id"] == "w1"
