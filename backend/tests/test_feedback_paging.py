import asyncio
import logging
import sys
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

import app.core.db as core_db
from app.api.router import api_router
from app.api.routes.feedback import FEEDBACK_TAKE
from app.core import deps


def _master_admin() -> SimpleNamespace:
    return SimpleNamespace(
        id="root",
        email="root@example.test",
        name="Master Admin",
        role="MASTER_ADMIN",
        canReview=True,
        canDownloadDataset=True,
        canViewProvenance=True,
    )


def _row(index: int) -> SimpleNamespace:
    return SimpleNamespace(
        id=f"f{index}",
        userId=f"u{index}",
        createdAt="2026-08-01T09:00:00",
        updatedAt=f"2026-08-15T09:{59 - index % 60:02d}:00",
        rating=5,
        comment=f"Feedback {index}",
        user=SimpleNamespace(id=f"u{index}", name=f"Author {index}",
                             email=f"author{index}@example.test", role="DESIGNER"),
    )


class _Feedback:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.windows: list[tuple[int | None, int | None]] = []
        self.orders: list[Any] = []

    async def find_many(self, order: Any = None, include: dict | None = None,
                        skip: int | None = None, take: int | None = None, **_: Any) -> list[Any]:
        self.windows.append((skip, take))
        self.orders.append(order)
        start = skip or 0
        return self.rows[start:] if take is None else self.rows[start:start + take]

    async def count(self, where: dict | None = None, **_: Any) -> int:
        return len(self.rows)


_CURRENT: dict[str, Any] = {"user": None}


def _build_app() -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: _CURRENT["user"]
    return application


_APP = _build_app()


@pytest.fixture
def table(monkeypatch: pytest.MonkeyPatch):
    delegate = _Feedback([_row(i) for i in range(5)])
    fake_db = SimpleNamespace(feedback=delegate, user=SimpleNamespace())
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", fake_db)
    for module in list(sys.modules.values()):
        if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
            monkeypatch.setattr(module, "db", fake_db)
    _CURRENT["user"] = _master_admin()
    yield delegate
    _CURRENT["user"] = None


def _get(**params: Any) -> httpx.Response:
    async def run() -> httpx.Response:
        transport = httpx.ASGITransport(app=_APP)
        async with httpx.AsyncClient(transport=transport, base_url="http://feedback.test") as client:
            return await client.get("/api/feedback", params=params)

    return asyncio.run(run())


def test_the_read_is_bounded_even_when_nobody_asks_for_a_page(table: _Feedback) -> None:
    response = _get()

    assert response.status_code == 200, response.text
    assert table.windows == [(0, FEEDBACK_TAKE)]
    assert table.orders == [[{"updatedAt": "desc"}, {"id": "desc"}]]


def test_the_body_is_still_a_bare_array(table: _Feedback) -> None:
    body = _get().json()

    assert isinstance(body, list)
    assert body[0]["comment"] == "Feedback 0"
    assert body[0]["user"]["email"] == "author0@example.test"


def test_a_page_that_leaves_rows_behind_says_so(table: _Feedback) -> None:
    response = _get(pageSize=2)

    assert len(response.json()) == 2
    assert response.headers["x-total-count"] == "5"
    assert response.headers["x-truncated"] == "true"


def test_the_last_page_is_not_reported_as_truncated(table: _Feedback) -> None:
    response = _get(page=3, pageSize=2)

    body = response.json()
    assert [row["id"] for row in body] == ["f4"]
    assert response.headers["x-truncated"] == "false"
    assert response.headers["x-total-count"] == "5"


def test_a_page_beyond_the_end_is_empty_and_honest(table: _Feedback) -> None:
    response = _get(page=9, pageSize=2)

    assert response.json() == []
    assert response.headers["x-total-count"] == "5"
    assert response.headers["x-truncated"] == "false"


def test_a_short_answer_to_a_parameterless_caller_is_logged_at_error(
    table: _Feedback, caplog: pytest.LogCaptureFixture
) -> None:
    table.rows = [_row(i) for i in range(FEEDBACK_TAKE + 1)]

    with caplog.at_level(logging.ERROR, logger="app.api.routes.feedback"):
        response = _get()

    assert len(response.json()) == FEEDBACK_TAKE
    assert response.headers["x-truncated"] == "true"
    assert [r for r in caplog.records if "incomplete list" in r.getMessage()], caplog.text


def test_a_caller_that_asked_for_a_page_is_not_logged_at_error(
    table: _Feedback, caplog: pytest.LogCaptureFixture
) -> None:
    table.rows = [_row(i) for i in range(FEEDBACK_TAKE + 1)]

    with caplog.at_level(logging.ERROR, logger="app.api.routes.feedback"):
        response = _get(page=1, pageSize=10)

    assert len(response.json()) == 10
    assert response.headers["x-truncated"] == "true"
    assert [r for r in caplog.records if "incomplete list" in r.getMessage()] == []


def test_the_ceiling_cannot_be_argued_away_by_the_caller(table: _Feedback) -> None:
    refused = _get(pageSize=FEEDBACK_TAKE + 1)

    assert refused.status_code == 422, refused.text
    assert table.windows == [], "the query must not run before the parameter is refused"
