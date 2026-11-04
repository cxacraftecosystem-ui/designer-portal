from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

from app.api.routes import design_workshops
from app.services import dictation_cap, records


def test_the_allowance_answer_may_be_kept_privately_for_a_minute(monkeypatch):
    async def load(user_id, *, now=None):
        return dictation_cap.Allowance(day="2026-11-05", limit=20, used=3)

    monkeypatch.setattr(design_workshops, "_require_designer", lambda user: None)
    monkeypatch.setattr(design_workshops.dictation_cap, "load_allowance", load)
    response = asyncio.run(design_workshops.get_dictation_allowance(current_user=SimpleNamespace(id="u1")))
    assert response.headers["Cache-Control"] == "private, max-age=60"
    body = json.loads(response.body)
    assert body == dictation_cap.allowance_payload(dictation_cap.Allowance(day="2026-11-05", limit=20, used=3))


def test_the_ceiling_and_the_count_are_read_together(monkeypatch):
    started: list[str] = []
    both = asyncio.Event()

    async def settings_row() -> None:
        started.append("settings")
        if len(started) == 2:
            both.set()
        await asyncio.wait_for(both.wait(), 1)

    async def used_today(user_id, day):
        started.append("used")
        if len(started) == 2:
            both.set()
        await asyncio.wait_for(both.wait(), 1)
        return 4

    monkeypatch.setattr(dictation_cap, "_settings_row", settings_row)
    monkeypatch.setattr(dictation_cap, "_used_today", used_today)
    allowance = asyncio.run(dictation_cap.load_allowance("u1"))
    assert allowance.used == 4
    assert sorted(started) == ["settings", "used"]


def test_working_out_access_reads_design_workshop_ids_only(monkeypatch):
    seen: dict = {}

    async def group_by(**kwargs):
        seen.update(kwargs)
        return [{"id": "dw1"}, {"id": "dw2"}]

    monkeypatch.setattr(records, "db", SimpleNamespace(designworkshop=SimpleNamespace(group_by=group_by)))
    assert asyncio.run(records._design_workshop_media_ids("u1")) == ["dw1", "dw2"]
    assert seen["by"] == ["id"]
    assert seen["where"]["deletedAt"] is None
    assert asyncio.run(records._design_workshop_media_ids("")) == []
