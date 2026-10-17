"""``GET /media/batch`` — several single reads in one request, answering exactly what they answer.

The stage form of a design workshop resolves every photograph on it to a media row, and did so one
``GET /media/{id}`` at a time: forty requests queued six-wide behind one origin. The batch route
exists to make that one request, and the web client (``frontend/lib/mediaRows.ts``) depends on four
promises it makes, each pinned here without a database:

1. **The same encoder, once.** Rows go through ``_public`` — the function the single read uses — so a
   row's ``url`` travels to exactly the callers the single read hands it to. Handing the whole list
   over in one call is the point: the entitlement is computed once, not forty times.
2. **The caller's order, de-duplicated.** A gallery draws in the order its ids are stored.
3. **A missing row is listed, not fatal.** The single read answers it with a 404; the client turns
   ``missing`` back into that 404 for the one field that asked.
4. **A bound.** More than ``MEDIA_BATCH_MAX`` ids is refused with a sentence, and the route is
   registered before ``/{media_id}`` — otherwise ``batch`` would be read as a media id.
"""

from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.api.routes import media as media_routes

VIEWER = SimpleNamespace(id="user-designer", role="DESIGNER", canDownloadDataset=False)


class _Table:
    def __init__(self, rows):
        self.rows = rows
        self.asked: list[dict] = []

    async def find_many(self, where=None, include=None, **_kwargs):
        self.asked.append({"where": where, "include": include})
        wanted = set(where["id"]["in"])
        return [row for row in self.rows if row.id in wanted]


def _install(monkeypatch, rows):
    table = _Table(rows)
    monkeypatch.setattr(media_routes, "db", SimpleNamespace(mediafile=table))
    calls: list[tuple[list, object]] = []

    async def fake_public(found, viewer=None, **_kwargs):
        calls.append((list(found), viewer))
        return [{"id": row.id, "encodedFor": getattr(viewer, "id", None)} for row in found]

    monkeypatch.setattr(media_routes, "_public", fake_public)
    return table, calls


def _row(media_id: str):
    return SimpleNamespace(id=media_id, objectKey=f"media/{media_id}.jpg")


async def test_rows_go_through_the_single_reads_encoder_once_in_the_order_asked(monkeypatch):
    table, calls = _install(monkeypatch, [_row("a"), _row("b"), _row("c")])
    answer = await media_routes.get_media_batch(ids="c,a,b,a", current_user=VIEWER)
    assert [item["id"] for item in answer["items"]] == ["c", "a", "b"]
    assert answer["missing"] == []
    # ONE encoder call for the whole list, with the viewer — the single read's entitlement, once.
    assert len(calls) == 1
    assert [row.id for row in calls[0][0]] == ["c", "a", "b"]
    assert calls[0][1] is VIEWER
    # The same relations the single read includes, so a row is encoded from the same shape.
    assert table.asked[0]["include"] == media_routes.INCLUDE


async def test_an_id_with_no_row_is_listed_as_missing_and_the_rest_still_answer(monkeypatch):
    _install(monkeypatch, [_row("a")])
    answer = await media_routes.get_media_batch(ids="a, gone ,", current_user=VIEWER)
    assert [item["id"] for item in answer["items"]] == ["a"]
    assert answer["missing"] == ["gone"]


async def test_nothing_asked_answers_nothing_without_a_query(monkeypatch):
    table, calls = _install(monkeypatch, [])
    assert await media_routes.get_media_batch(ids=" , ", current_user=VIEWER) == {
        "items": [],
        "missing": [],
    }
    assert table.asked == [] and calls == []


async def test_more_than_the_bound_is_refused_with_a_sentence(monkeypatch):
    _install(monkeypatch, [])
    too_many = ",".join(f"m{i}" for i in range(media_routes.MEDIA_BATCH_MAX + 1))
    with pytest.raises(HTTPException) as refused:
        await media_routes.get_media_batch(ids=too_many, current_user=VIEWER)
    assert refused.value.status_code == 422
    assert str(media_routes.MEDIA_BATCH_MAX) in str(refused.value.detail)


def test_the_batch_route_is_matched_before_the_single_read():
    paths = [
        getattr(route, "path", "")
        for route in media_routes.router.routes
        if "GET" in getattr(route, "methods", set())
    ]
    batch = next(i for i, path in enumerate(paths) if path.endswith("/batch"))
    single = next(i for i, path in enumerate(paths) if path.endswith("/{media_id}"))
    assert batch < single, (
        "GET /media/batch would be answered by GET /media/{media_id} with id 'batch'"
    )
