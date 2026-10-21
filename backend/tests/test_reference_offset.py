"""``offset`` pages a reference list, so a picker can hold a register longer than one page.

The handset's craft picker showed 50 of 459 crafts with "Partly loaded": the references endpoint
answered one page (default 50, ceiling 200) and had no way to ask for the next. ``offset`` is that
way. Driven through the REAL ``reference_options`` over a fake client, like
``test_process_product_cascade.py``.
"""

from __future__ import annotations

from types import SimpleNamespace

import app.services.stage_definitions  # noqa: F401
from app.services import design_workshops as dw
from app.services.stage_schema import REF_SCOPE_ALL

RECORD = SimpleNamespace(id="dw_1", workshopId="wsp_1")

WORLD = [
    SimpleNamespace(
        id=f"prc_{i:02d}", name=f"Process {i:02d}", notes=None, preProcessAvailable=False,
        recordedAt=None, status="APPROVED", workshopId="wsp_1", productId=None, product=None, steps=[],
    )
    for i in range(7)
]


class _Delegate:
    def __init__(self, calls):
        self._calls = calls

    async def find_many(self, where=None, order=None, take=None, include=None, skip=0):
        self._calls.append({"take": take, "skip": skip})
        rows = sorted(WORLD, key=lambda r: r.name)[skip:]
        return rows[:take] if take else rows


class _Db:
    def __init__(self, calls):
        self._calls = calls

    def __getattr__(self, name):
        return _Delegate(self._calls)


def _install(monkeypatch):
    calls: list[dict] = []
    monkeypatch.setattr(dw, "db", _Db(calls))

    async def _all_readable(_viewer):
        return {}

    monkeypatch.setattr(dw, "viewable_where", _all_readable)
    return calls


async def _page(offset: int):
    return await dw.reference_options(RECORD, "Process", scope=REF_SCOPE_ALL, limit=3, offset=offset)


async def test_pages_walk_the_whole_list_and_the_last_says_it_is_the_end(monkeypatch):
    calls = _install(monkeypatch)
    seen: list[str] = []
    offset = 0
    while True:
        page = await _page(offset)
        seen += [o["id"] for o in page["options"]]
        if not page["truncated"]:
            break
        offset += len(page["options"])
    assert seen == [r.id for r in WORLD]
    assert [c["skip"] for c in calls] == [0, 3, 6]


async def test_the_first_page_is_the_call_it_always_was(monkeypatch):
    calls = _install(monkeypatch)
    page = await _page(0)
    assert page["truncated"] is True
    assert len(page["options"]) == 3
    # No `skip` keyword at all on the first page, so a client that never pages is unchanged.
    assert calls == [{"take": 4, "skip": 0}]


async def test_the_offset_is_bounded(monkeypatch):
    calls = _install(monkeypatch)
    await dw.reference_options(RECORD, "Process", scope=REF_SCOPE_ALL, limit=3, offset=10**9)
    assert calls[0]["skip"] == dw.REFERENCE_OFFSET_MAX
