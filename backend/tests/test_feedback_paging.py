"""``GET /feedback`` reads a bounded page and says how much it left behind.

The route was ``db.feedback.find_many(order=[{"updatedAt": "desc"}], include={"user": True})`` with
no ``take``, no ``skip``, and a signature carrying no query parameters at all: every row the table
has ever accumulated, each joined to its author, serialised into one response. The table holds at
most one row per account (``PUT /feedback/me`` upserts on ``userId``) and the five free-text columns
are bounded at 5000 characters each, so the read is not unbounded in principle — it is unbounded in
the only sense that matters operationally: it grows with the project, has no ceiling, and its first
failure signal would be a timeout on the master admin's page. That is the shape
``ACTIVE_ROSTER_READ_LIMIT`` argues against in ``services/design_workshop_viewers``.

THE RESPONSE BODY IS STILL A BARE ARRAY, and one test here exists purely to keep it that way:
``frontend/app/(protected)/feedback/page.tsx`` does ``apiFetch<Feedback[]>("/feedback")`` and maps
the result, so an envelope would replace the screen with a runtime error. The count and the
shortfall therefore ride in ``X-Total-Count`` and ``X-Truncated`` until both sides can move
together.

NOTHING HERE TOUCHES A DATABASE — ``db`` is replaced by a delegate that answers with canned rows and
records the ``skip``/``take`` it was asked for, which is what lets a test assert that the read is
bounded at all. A live-Postgres test can prove the rows come back; only this can prove which rows
the query ASKED for.
"""

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
        # Descending with the index, so the list this delegate slices is already in the order the
        # real query would have returned. The values only have to be distinct and ordered; the fake
        # honours ``skip``/``take`` positionally and never parses them.
        updatedAt=f"2026-08-15T09:{59 - index % 60:02d}:00",
        rating=5,
        comment=f"Feedback {index}",
        user=SimpleNamespace(id=f"u{index}", name=f"Author {index}",
                             email=f"author{index}@example.test", role="DESIGNER"),
    )


class _Feedback:
    """The feedback model, honouring ``skip``/``take`` the way Postgres would and remembering the
    window it was asked for."""

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
    """THE REGRESSION. The parameterless call used to issue ``find_many`` with no ``take`` at all,
    so the size of the answer was whatever the table had grown to. It must now ask for a window,
    and the window must be the declared ceiling rather than some number typed at the call site."""
    response = _get()

    assert response.status_code == 200, response.text
    assert table.windows == [(0, FEEDBACK_TAKE)]
    # The tiebreaker is not decoration: ``updatedAt`` is not unique, and a non-total order under
    # LIMIT/OFFSET lets one row appear on two pages while another appears on none.
    assert table.orders == [[{"updatedAt": "desc"}, {"id": "desc"}]]


def test_the_body_is_still_a_bare_array(table: _Feedback) -> None:
    """A PIN ON THE WIRE CONTRACT, not a tautology. ``frontend/app/(protected)/feedback/page.tsx``
    fetches ``Feedback[]`` and maps it; the day this becomes ``page_payload`` the master admin's
    feedback screen throws instead of rendering. Whoever migrates it must change both sides in one
    commit, and this assertion is what tells them there IS another side."""
    body = _get().json()

    assert isinstance(body, list)
    assert body[0]["comment"] == "Feedback 0"
    assert body[0]["user"]["email"] == "author0@example.test"


def test_a_page_that_leaves_rows_behind_says_so(table: _Feedback) -> None:
    """Five rows, two per page. A capped array cannot say it was capped from inside the body, so
    the shortfall is on the response instead of being inferred by a client comparing ``length``
    against its own copy of the cap — the inference three closed findings were filed against."""
    response = _get(pageSize=2)

    assert len(response.json()) == 2
    assert response.headers["x-total-count"] == "5"
    assert response.headers["x-truncated"] == "true"


def test_the_last_page_is_not_reported_as_truncated(table: _Feedback) -> None:
    """The other half. ``truncated`` is computed against the real count and the window actually
    served, so the page that ends exactly on the final row says false — and the rows past the first
    page are REACHABLE, which is what keeps the ceiling from being a quieter kind of loss."""
    response = _get(page=3, pageSize=2)

    body = response.json()
    assert [row["id"] for row in body] == ["f4"]
    assert response.headers["x-truncated"] == "false"
    assert response.headers["x-total-count"] == "5"


def test_a_page_beyond_the_end_is_empty_and_honest(table: _Feedback) -> None:
    """An out-of-range page must not read as "the table is empty and complete" to a caller that
    only looks at the body — the count is the thing that tells them where they are."""
    response = _get(page=9, pageSize=2)

    assert response.json() == []
    assert response.headers["x-total-count"] == "5"
    # 5 total, 16 skipped: nothing was left behind by THIS page because this page is past the end.
    assert response.headers["x-truncated"] == "false"


def test_a_short_answer_to_a_parameterless_caller_is_logged_at_error(
    table: _Feedback, caplog: pytest.LogCaptureFixture
) -> None:
    """The signal that works whatever the client can read.

    ``X-Total-Count`` is only readable by same-origin JavaScript until ``expose_headers`` is added
    to the CORS middleware, so the headers alone could leave the shortfall genuinely invisible. The
    log is the backstop, and ERROR rather than WARNING for ``active_roster_emails``' reason: this is
    not a long list somebody can narrow, it is rows missing from the only request the feedback
    screen knows how to make.

    The ceiling is reached by giving the table more rows than it, NOT by monkeypatching the
    constant: ``pageSize``'s default is bound into the ``Query(...)`` at import time, so a patched
    ``FEEDBACK_TAKE`` would move the "did the caller ask for a page" comparison without moving the
    default it is compared against, and the test would be asserting against a state the server can
    never be in.
    """
    table.rows = [_row(i) for i in range(FEEDBACK_TAKE + 1)]

    with caplog.at_level(logging.ERROR, logger="app.api.routes.feedback"):
        response = _get()

    assert len(response.json()) == FEEDBACK_TAKE
    assert response.headers["x-truncated"] == "true"
    assert [r for r in caplog.records if "incomplete list" in r.getMessage()], caplog.text


def test_a_caller_that_asked_for_a_page_is_not_logged_at_error(
    table: _Feedback, caplog: pytest.LogCaptureFixture
) -> None:
    """The other half, and it is what keeps the log usable. A client paging deliberately gets a
    short answer on every page but the last, and an ERROR per page would bury the one line that
    means something under a hundred that do not."""
    table.rows = [_row(i) for i in range(FEEDBACK_TAKE + 1)]

    with caplog.at_level(logging.ERROR, logger="app.api.routes.feedback"):
        response = _get(page=1, pageSize=10)

    assert len(response.json()) == 10
    assert response.headers["x-truncated"] == "true"
    assert [r for r in caplog.records if "incomplete list" in r.getMessage()] == []


def test_the_ceiling_cannot_be_argued_away_by_the_caller(table: _Feedback) -> None:
    """``pageSize`` is bounded at the same constant the default uses. Without ``le`` a caller could
    ask for the whole table back and the bound would be advisory — which is the defect, restored
    through the front door."""
    refused = _get(pageSize=FEEDBACK_TAKE + 1)

    assert refused.status_code == 422, refused.text
    assert table.windows == [], "the query must not run before the parameter is refused"
