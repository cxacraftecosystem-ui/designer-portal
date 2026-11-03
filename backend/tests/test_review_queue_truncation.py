import asyncio
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.api.routes import review as review_route
from app.core import deps

SOURCES = ("artisan", "workshop", "productdocumentation", "tooldocumentation", "process",
           "questionnaireinterview")


def _user(role: str, user_id: str = "reviewer", can_review: bool = False) -> SimpleNamespace:
    return SimpleNamespace(
        id=user_id,
        email=f"{user_id}@example.test",
        name=role.title(),
        role=role,
        canReview=can_review,
        canDownloadDataset=False,
        canViewProvenance=False,
    )


def _pending(index: int, prefix: str = "a") -> SimpleNamespace:
    return SimpleNamespace(
        id=f"{prefix}{index}",
        name=f"Pending Artisan {index}",
        place="Bagru",
        status="PENDING",
        createdAt=f"2026-08-{30 - index:02d}T09:00:00",
        createdById="author",
        createdBy=_user("RESEARCHER", user_id="author"),
        extraMetadata={},
    )


class _Rows:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []
        self.takes: list[int | None] = []
        self.counts = 0

    async def find_many(self, where: dict, include: dict | None = None,
                        order: Any = None, take: int | None = None, **_: Any) -> list[Any]:
        self.takes.append(take)
        rows = self.rows
        cursor = next((part["OR"] for part in where.get("AND", []) if "OR" in part), None)
        if cursor is not None:
            stamp = cursor[0]["createdAt"]["lt"].isoformat()
            last_id = cursor[1]["id"]["lt"]
            rows = [r for r in rows if (r.createdAt, r.id) < (stamp, last_id)]
        return rows if take is None else rows[:take]

    async def count(self, where: dict | None = None, **_: Any) -> int:
        self.counts += 1
        return len(self.rows)


class _Client(SimpleNamespace):
    def tx(self) -> Any:
        client = self

        class _Tx:
            async def __aenter__(self) -> Any:
                return client

            async def __aexit__(self, *_exc: object) -> bool:
                return False

        return _Tx()


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


class _Queue:
    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.monkeypatch = monkeypatch
        self.sources = {name: _Rows() for name in SOURCES}
        fake_db = _Client(mediafile=_Rows(), reviewlog=_Rows(), user=_Rows(),
                          recordrevision=_Rows(), **self.sources)
        real_db = core_db.db
        monkeypatch.setattr(core_db, "db", fake_db)
        for module in list(sys.modules.values()):
            if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
                monkeypatch.setattr(module, "db", fake_db)
        monkeypatch.setattr(review_route, "_PENDING_SOURCES", [
            (record_type, self.sources[delegate_name], label_fields)
            for (record_type, _delegate, label_fields), delegate_name in zip(
                review_route._PENDING_SOURCES, SOURCES, strict=True
            )
        ])

    def holding(self, count: int, source: str = "artisan") -> _Queue:
        self.sources[source].rows = [_pending(i, source[:3]) for i in range(count)]
        return self

    def capped_at(self, cap: int) -> _Queue:
        self.monkeypatch.setattr(review_route, "PENDING_TAKE", cap)
        return self

    def as_(self, role: str, can_review: bool = False) -> _Queue:
        _CURRENT["user"] = _user(role, can_review=can_review)
        return self

    def pending(self, **params: Any) -> dict[str, Any]:
        response = self.raw(**params)
        assert response.status_code == 200, response.text
        return response.json()

    def raw(self, **params: Any) -> httpx.Response:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://review.test") as client:
                return await client.get("/api/review/pending", params=params)

        return asyncio.run(run())


@pytest.fixture
def queue(monkeypatch: pytest.MonkeyPatch):
    q = _Queue(monkeypatch)
    yield q
    _CURRENT["user"] = None


def test_a_cut_queue_says_it_was_cut_and_reports_the_real_total(queue: _Queue) -> None:
    body = queue.holding(3).capped_at(2).as_("PROFESSOR").pending()

    assert len(body["items"]) == 2
    assert body["shown"] == 2
    assert body["total"] == 3, "total is the count of matching rows, not the length of the page"
    assert body["truncated"] is True
    assert body["cap"] == 2


def test_a_queue_inside_the_cap_is_not_reported_as_cut_and_pays_for_no_count(queue: _Queue) -> None:
    queue.holding(3).capped_at(3).as_("PROFESSOR")
    body = queue.pending()

    assert body["truncated"] is False
    assert body["shown"] == body["total"] == 3
    assert queue.sources["artisan"].takes == [4], "the read must ask for one row beyond the cap"
    assert queue.sources["artisan"].counts == 0, (
        "the ordinary, uncut case must not pay for a second query per record type"
    )


def test_only_the_record_type_that_overflowed_is_counted(queue: _Queue) -> None:
    queue.holding(3, "artisan").holding(1, "process").capped_at(2).as_("PROFESSOR")
    body = queue.pending()

    assert body["total"] == 4
    assert body["shown"] == 3
    assert queue.sources["artisan"].counts == 1
    assert [queue.sources[name].counts for name in SOURCES if name != "artisan"] == [0, 0, 0, 0, 0]


def test_a_reviewer_with_nobody_beneath_them_gets_the_same_shape(queue: _Queue) -> None:
    body = queue.holding(3).capped_at(2).as_("CROWDSOURCE_VOLUNTEER", can_review=True).pending()

    assert body == {"items": [], "shown": 0, "total": 0, "cap": 2, "truncated": False}
    assert queue.sources["artisan"].takes == []


def test_without_a_page_size_the_answer_is_the_old_capped_shape(queue: _Queue) -> None:
    body = queue.holding(3).capped_at(2).as_("PROFESSOR").pending()

    assert "nextBefore" not in body
    assert body["cap"] == 2 and body["truncated"] is True


def test_paging_walks_the_whole_queue_with_no_record_out_of_reach(queue: _Queue) -> None:
    queue.holding(5, "artisan").holding(2, "process").as_("PROFESSOR")
    seen: list[str] = []
    before: str | None = None
    pages = 0
    while True:
        params: dict[str, Any] = {"pageSize": 3}
        if before:
            params["before"] = before
        body = queue.pending(**params)
        pages += 1
        assert body["total"] == 7
        assert body["cap"] == 3
        seen.extend(item["id"] for item in body["items"])
        before = body["nextBefore"]
        assert body["truncated"] is (before is not None)
        if before is None:
            break
        assert pages < 10, "the cursor must make progress"
    assert len(seen) == 7 and len(set(seen)) == 7
    assert pages == 3


def test_a_page_is_newest_first_across_record_types(queue: _Queue) -> None:
    queue.holding(2, "artisan").holding(2, "process").as_("PROFESSOR")
    body = queue.pending(pageSize=10)

    stamps = [item["createdAt"] for item in body["items"]]
    assert stamps == sorted(stamps, reverse=True)
    assert body["truncated"] is False and body["nextBefore"] is None


def test_a_mangled_cursor_is_a_plain_sentence_not_a_crash(queue: _Queue) -> None:
    response = queue.holding(2).as_("PROFESSOR").raw(pageSize=2, before="not-a-cursor")

    assert response.status_code == 422
    assert "Reload the queue" in response.text
