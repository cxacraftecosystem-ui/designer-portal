from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

import httpx

from app.core.db import db

STATEMENTS_COUNTER = "prisma_datasource_queries_total"


async def datasource_statements() -> int:
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
    box = StatementCount()
    before = await datasource_statements()
    yield box
    box.value = await datasource_statements() - before


def asgi_client() -> httpx.AsyncClient:
    from app.main import app

    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://query-count.test")
