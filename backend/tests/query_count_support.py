"""Counting the SQL statements a piece of work sends to Postgres, for the query-count regression tests.

THE NUMBER IS THE QUERY ENGINE'S OWN, NOT A GUESS FROM PYTHON. Every runtime client is started with
``--enable-metrics``, and ``prisma_datasource_queries_total`` is the engine's count of statements it
executed against the database — the ``include`` follow-up reads, the ``BEGIN``/``COMMIT`` around an
``update_many``, raw queries, all of it. ``count_statements`` reads it before and after the work and
answers the difference, so a test can say "this list costs at most N statements however many rows it
has" and mean the statements Postgres actually saw.

ONE THING AT A TIME. The counter is process-wide: run nothing else against ``db`` while counting, and
never count across a ``TestClient`` lifespan, whose background tasks (the connection watchdog, the
usage flush) query on their own clock. ``asgi_client`` drives the app WITHOUT its lifespan for exactly
that reason — the caller connects ``db`` itself.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

import httpx

from app.core.db import db

STATEMENTS_COUNTER = "prisma_datasource_queries_total"


async def datasource_statements() -> int:
    """How many statements this client's engine has sent to the database since it started."""
    metrics = await db.get_metrics()
    for counter in metrics.counters:
        if counter.key == STATEMENTS_COUNTER:
            return int(counter.value)
    raise AssertionError(f"the query engine reported no {STATEMENTS_COUNTER} counter")


@dataclass
class StatementCount:
    value: int = 0


@asynccontextmanager
async def count_statements() -> AsyncIterator[StatementCount]:
    """``async with count_statements() as counted: ...`` — ``counted.value`` once the block exits."""
    box = StatementCount()
    before = await datasource_statements()
    yield box
    box.value = await datasource_statements() - before


def asgi_client() -> httpx.AsyncClient:
    """The real app (every middleware, every dependency) over ASGI, with no lifespan."""
    from app.main import app

    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://query-count.test")
