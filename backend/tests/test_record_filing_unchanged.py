"""Saving a record filed under a design workshop the editor is not on (2026-10-19).

Both clients send ``designWorkshopId`` on every save. The PATCH used to re-check that unchanged id
with the workshop loader, so anybody who may edit the artisan but is not on its workshop — an admin,
a professor, the designer who filed it and was later removed — was answered 404 "Record not found"
for an artisan that was on their screen. An unchanged id is not a filing and is no longer asked
about, except for one question: a soft-deleted workshop still answers 409. A real move to a
workshop the caller is not on is still refused, still 404 (the offline queues' "pick another" cue),
now in words.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace
from typing import Any

import pytest
from fastapi import HTTPException

from app.services import (
    design_workshop_posts as posts,
    design_workshops,
    record_design_workshop as filing,
)


def _account(role: str) -> SimpleNamespace:
    return SimpleNamespace(
        id=f"u-{role}", name=role.title(), email=f"{role.lower()}@x.test", role=role
    )


@pytest.fixture
def loader(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    """The destination loader answering from ``state["refuse"]``: {workshop id: HTTPException}."""
    state: dict[str, Any] = {"asked": [], "refuse": {}, "deleted": set(), "looked_up": []}

    async def _load(workshop_id, _user, **_kwargs):
        state["asked"].append(workshop_id)
        refusal = state["refuse"].get(workshop_id)
        if refusal is not None:
            raise refusal

    async def _holds_nothing(*_args, **_kwargs):
        return {}

    class _Workshops:
        async def find_unique(self, *, where):
            state["looked_up"].append(where["id"])
            deleted = "2026-10-18T00:00:00Z" if where["id"] in state["deleted"] else None
            return SimpleNamespace(id=where["id"], deletedAt=deleted)

    monkeypatch.setattr(design_workshops, "load_workshop_or_404", _load)
    monkeypatch.setattr(posts, "supervisory_posts_among", _holds_nothing)
    monkeypatch.setattr(filing, "db", SimpleNamespace(designworkshop=_Workshops()))
    return state


def _patch(data: dict[str, Any], user: Any, filed_under: str | None) -> HTTPException | None:
    try:
        asyncio.run(filing.assert_payload_workshop(data, user, filed_under=filed_under))
    except HTTPException as refusal:
        return refusal
    return None


NOT_FOUND = HTTPException(status_code=404, detail="Record not found")


@pytest.mark.parametrize("role", ["DESIGNER", "PROFESSOR", "ADMIN", "MASTER_ADMIN"])
def test_resending_the_workshop_a_record_is_filed_under_asks_nothing(loader, role):
    loader["refuse"]["w-other"] = NOT_FOUND
    body = {"designWorkshopId": "w-other", "phone": "9876543210"}
    assert _patch(body, _account(role), filed_under="w-other") is None
    assert loader["asked"] == []


def test_the_unchanged_id_is_compared_after_trimming(loader):
    loader["refuse"]["w-other"] = NOT_FOUND
    assert _patch({"designWorkshopId": " w-other "}, _account("DESIGNER"), "w-other") is None
    assert loader["asked"] == []


def test_resending_the_id_of_a_deleted_workshop_is_still_a_409(loader):
    """The loader used to answer this; the unchanged-id path asks it itself, in the same words."""
    loader["deleted"].add("w-gone")
    refusal = _patch({"designWorkshopId": "w-gone"}, _account("ADMIN"), "w-gone")
    assert refusal is not None and refusal.status_code == 409
    assert refusal.detail == filing.WORKSHOP_DELETED
    assert loader["asked"] == [] and loader["looked_up"] == ["w-gone"]


def test_moving_a_record_to_a_workshop_the_caller_is_not_on_says_so(loader):
    loader["refuse"]["w-hidden"] = NOT_FOUND
    refusal = _patch({"designWorkshopId": "w-hidden"}, _account("DESIGNER"), "w-mine")
    # 404, not 403: both offline queues read a 404 on a replayed save as a missing linked record and
    # offer to pick another workshop. A 403 would park the queued record behind a useless retry.
    assert refusal is not None and refusal.status_code == 404
    assert refusal.detail == filing.NOT_ON_WORKSHOP
    assert "not found" not in refusal.detail.lower()
    assert loader["asked"] == ["w-hidden"]


def test_a_missing_workshop_and_a_hidden_one_get_the_same_answer(loader):
    """Nothing to enumerate: the loader's two 404s come out as one 404 with one sentence."""
    loader["refuse"]["w-hidden"] = NOT_FOUND
    loader["refuse"]["w-none"] = HTTPException(status_code=404, detail="Record not found")
    hidden = _patch({"designWorkshopId": "w-hidden"}, _account("DESIGNER"), None)
    missing = _patch({"designWorkshopId": "w-none"}, _account("DESIGNER"), None)
    assert hidden is not None and missing is not None
    assert (hidden.status_code, hidden.detail) == (missing.status_code, missing.detail)


@pytest.mark.parametrize(
    "refusal",
    [
        HTTPException(
            status_code=409, detail="This workshop is deleted. Restore it before editing."
        ),
        HTTPException(status_code=403, detail="You inspect this workshop."),
    ],
)
def test_the_loaders_other_refusals_reach_the_caller_unchanged(loader, refusal):
    loader["refuse"]["w-x"] = refusal
    answer = _patch({"designWorkshopId": "w-x"}, _account("DESIGNER"), "w-mine")
    assert answer is refusal


def test_a_create_naming_a_workshop_is_still_asked(loader):
    assert _patch({"designWorkshopId": "w-new"}, _account("DESIGNER"), None) is None
    assert loader["asked"] == ["w-new"]


def test_the_refusal_is_a_sentence_a_designer_can_act_on():
    text = filing.NOT_ON_WORKSHOP
    assert text[0].isupper() and text.endswith(".")
    assert "Choose" in text
