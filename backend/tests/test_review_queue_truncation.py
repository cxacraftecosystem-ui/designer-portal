"""What ``GET /review/pending`` says when the per-record-type cap bites.

The queue reads at most ``PENDING_TAKE`` rows of each of six record types and used to answer
``{"items": items, "total": len(items)}`` — the length of the already-capped list, under the name
of a count, with nothing anywhere saying the list had been cut. A reviewer with 340 pending
artisans and a reviewer with exactly 200 received byte-identical responses. Because each source is
read ``createdAt desc``, the rows behind the cap are the OLDEST — the most overdue work is what
disappears — and there is no page 2 to ask for, so the only way to discover the backlog was that
the number refused to go down as the queue was worked.

NOTHING HERE TOUCHES A DATABASE, for the same reason ``test_review_edit_authority.py`` does not:
``db`` is replaced by delegates that answer with canned rows and remember what they were asked,
which is what lets a test assert that the extra ``count`` query happens for the cut record type and
for no other. The cap is moved rather than the data — 201 artisans would prove the same thing in
twenty minutes of inserts, and a test that expensive is a test that gets skipped.
"""

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

# The six sources the queue walks, named here so a seventh added to ``_PENDING_SOURCES`` without a
# thought for this file fails loudly rather than silently going uncounted.
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


def _pending(index: int) -> SimpleNamespace:
    """One PENDING artisan, old enough to be behind the cap. ``createdAt`` descends with the index
    so the newest rows are first, exactly as the route's ``order`` produces them."""
    return SimpleNamespace(
        id=f"a{index}",
        name=f"Pending Artisan {index}",
        place="Bagru",
        status="PENDING",
        createdAt=f"2026-08-{30 - index:02d}T09:00:00",
        createdById="author",
        createdBy=_user("RESEARCHER", user_id="author"),
        extraMetadata={},
    )


class _Rows:
    """One Prisma model holding a fixed set of rows, honouring ``take`` the way Postgres would."""

    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []
        self.takes: list[int | None] = []
        self.counts = 0

    async def find_many(self, where: dict, include: dict | None = None,
                        order: Any = None, take: int | None = None, **_: Any) -> list[Any]:
        self.takes.append(take)
        return self.rows if take is None else self.rows[:take]

    async def count(self, where: dict | None = None, **_: Any) -> int:
        self.counts += 1
        return len(self.rows)


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
        fake_db = SimpleNamespace(mediafile=_Rows(), reviewlog=_Rows(), user=_Rows(),
                                  recordrevision=_Rows(), **self.sources)
        real_db = core_db.db
        monkeypatch.setattr(core_db, "db", fake_db)
        for module in list(sys.modules.values()):
            if getattr(module, "__name__", "").startswith("app.") and getattr(module, "db", None) is real_db:
                monkeypatch.setattr(module, "db", fake_db)
        # ``_PENDING_SOURCES`` captured the REAL delegates at import time, so replacing ``db`` is not
        # enough on its own — the list holds direct references and the route would keep reading the
        # live database through them. Rebuilt here against the fakes, preserving each entry's label
        # fields so the route's own labelling still runs.
        monkeypatch.setattr(review_route, "_PENDING_SOURCES", [
            (record_type, self.sources[delegate_name], label_fields)
            for (record_type, _delegate, label_fields), delegate_name in zip(
                review_route._PENDING_SOURCES, SOURCES, strict=True
            )
        ])

    def holding(self, count: int, source: str = "artisan") -> "_Queue":
        self.sources[source].rows = [_pending(i) for i in range(count)]
        return self

    def capped_at(self, cap: int) -> "_Queue":
        self.monkeypatch.setattr(review_route, "PENDING_TAKE", cap)
        return self

    def as_(self, role: str, can_review: bool = False) -> "_Queue":
        _CURRENT["user"] = _user(role, can_review=can_review)
        return self

    def pending(self) -> dict[str, Any]:
        async def run() -> httpx.Response:
            transport = httpx.ASGITransport(app=_APP)
            async with httpx.AsyncClient(transport=transport, base_url="http://review.test") as client:
                return await client.get("/api/review/pending")

        response = asyncio.run(run())
        assert response.status_code == 200, response.text
        return response.json()


@pytest.fixture
def queue(monkeypatch: pytest.MonkeyPatch):
    q = _Queue(monkeypatch)
    yield q
    _CURRENT["user"] = None


def test_a_cut_queue_says_it_was_cut_and_reports_the_real_total(queue: _Queue) -> None:
    """THE REGRESSION. Three pending artisans, a cap of two.

    Before the fix this answered ``{"items": [two rows], "total": 2}``: the third — the OLDEST, and
    therefore the most overdue — was gone, ``total`` named the capped length, and no field on the
    response distinguished this from a queue holding exactly two.
    """
    body = queue.holding(3).capped_at(2).as_("PROFESSOR").pending()

    assert len(body["items"]) == 2
    assert body["shown"] == 2
    assert body["total"] == 3, "total is the count of matching rows, not the length of the page"
    assert body["truncated"] is True
    assert body["cap"] == 2


def test_a_queue_inside_the_cap_is_not_reported_as_cut_and_pays_for_no_count(queue: _Queue) -> None:
    """The other half, and the reason the read asks for ``PENDING_TAKE + 1``.

    Reporting ``truncated = len(rows) == cap`` would claim a shortfall on the queue holding exactly
    the cap, and a reviewer told rows are hidden when they are not cannot check. The extra row makes
    the cut a fact rather than an inference — and where it does not appear, the rows read ARE the
    whole matching set, so their length is the exact total and the ``count`` query is not run at all.
    """
    queue.holding(3).capped_at(3).as_("PROFESSOR")
    body = queue.pending()

    assert body["truncated"] is False
    assert body["shown"] == body["total"] == 3
    assert queue.sources["artisan"].takes == [4], "the read must ask for one row beyond the cap"
    assert queue.sources["artisan"].counts == 0, (
        "the ordinary, uncut case must not pay for a second query per record type"
    )


def test_only_the_record_type_that_overflowed_is_counted(queue: _Queue) -> None:
    """Six sources, one of them over the cap. The count is the price of an exact total and it is
    charged only where the cap actually bit; billing all six would double the query load of the
    busiest admin page to buy numbers already in hand."""
    queue.holding(3, "artisan").holding(1, "process").capped_at(2).as_("PROFESSOR")
    body = queue.pending()

    assert body["total"] == 4  # 3 artisans + 1 process
    assert body["shown"] == 3  # 2 artisans (capped) + 1 process
    assert queue.sources["artisan"].counts == 1
    assert [queue.sources[name].counts for name in SOURCES if name != "artisan"] == [0, 0, 0, 0, 0]


def test_a_reviewer_with_nobody_beneath_them_gets_the_same_shape(queue: _Queue) -> None:
    """The short-circuit for an account with review access and no ladder below it returns before
    any query runs. It must still carry every key the full answer carries: a client that reads
    ``truncated`` off one shape and finds it missing on the other has to defend against this branch
    existing, and will not.

    A volunteer with an explicit ``canReview`` grant is the only way to reach it: page access starts
    at FIELD_CONTRIBUTOR by rank (``can_access_review``), so the bottom tier can open the queue only
    when it has been granted, and it is exactly that account which has nobody beneath it.
    """
    body = queue.holding(3).capped_at(2).as_("CROWDSOURCE_VOLUNTEER", can_review=True).pending()

    assert body == {"items": [], "shown": 0, "total": 0, "cap": 2, "truncated": False}
    assert queue.sources["artisan"].takes == []
