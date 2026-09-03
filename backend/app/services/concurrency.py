"""Running several independent database reads at once, without oversubscribing the pool.

WHY THIS EXISTS. The database SAT IN A DIFFERENT REGION from the web box, and a single Prisma round
trip measured 756ms from a warm client — against tables whose server-side execution time is
0.04–0.24ms. Virtually the entire cost of a request was therefore latency, paid once per query, and
the only number that moved a page was HOW MANY QUERIES RAN ONE AFTER ANOTHER. ``/dashboard/stats``
issued fourteen sequential reads and took 10.1s; the same fourteen gathered took 1.6s.

THE LINK IS NO LONGER THE PRICE, AND EVERY FIGURE ABOVE IS HISTORY (2026-09-03). Production moved on
2026-09-02 to a database co-located with the API box (docs/ENVIRONMENT.md, "The database"), where a
round trip is a millisecond or two rather than three quarters of a second — hundreds of times less.
The 756ms, the 10.1s and the 1.6s are kept because they are the measurement that BUILT this module,
not a claim about today; nothing in this repository has been re-timed since the move, so no page's
figure here or in its callers should be quoted as current.

WHAT SURVIVES IS THE COUNTING, WHICH IS THE ONLY THING THIS MODULE EVER CHANGED. Fourteen serial
hops still cost fourteen times whatever one hop costs, and the server-side execution times above did
not move at all — the ratio between "in series" and "together" is untouched. What moved is the size
of the prize: a screen that was ten seconds of waiting is now tens of milliseconds either way. So
gathering is ordinary hygiene here rather than the difference between a usable page and an unusable
one, and it is NOT a reason to convert a route that reads more clearly in sequence.

WHY IT IS BOUNDED, AND THE MOVE STRENGTHENED THIS HALF RATHER THAN RETIRING IT. A bare
``asyncio.gather`` would let one request hold every connection in the pool, and this codebase has
been bitten by pooler exhaustion before — twice. The bound keeps a single request from asking for
more connections than the pool actually has, so surplus reads queue in a semaphore we control
instead of inside the query engine. THE POOL IS SMALLER NOW: ``DATABASE_CONNECTION_LIMIT`` is 10 in
``core/config.py`` and 5 on the deployment, which sets it explicitly against a session pool of about
fifteen slots shared with the queue process. Waves that fitted at ten therefore split at five — the
root report's eight reads, search's thirteen — and that is the semaphore doing precisely its job.
The right response to a split wave is to want fewer reads, never a larger limit: that number is the
deployment's budget and not the application's to spend (``core/config.py``, and the 40 -> 10 cut
recorded there). Measured at 4, 8 and 16 simultaneous dashboard requests, gathering never produced a
connection error and stayed at or ahead of the sequential version, so the bound is insurance rather
than a throttle on the common case.
"""

import asyncio
from collections.abc import Coroutine
from typing import Any, TypeVar

from app.core.config import get_settings

T = TypeVar("T")


def pool_width() -> int:
    """How many reads may be in flight at once: the configured Prisma pool, never below one.

    Read from settings rather than hard-coded because the pool size is the thing that actually
    constrains us — if someone lowers ``DATABASE_CONNECTION_LIMIT`` the gather has to narrow with
    it, or we are back to queueing inside the engine where we cannot see it.
    """
    return max(1, get_settings().database_connection_limit)


async def gather_reads(*coros: Coroutine[Any, Any, T], limit: int | None = None) -> list[T]:
    """Await independent read coroutines concurrently, at most ``limit`` in flight.

    Results come back positionally, so callers can unpack them exactly as they would have read
    them in sequence — which is what keeps a converted route diffable against the one it replaced.

    Only for reads that do not depend on one another. Anything ordered, or anything inside a
    transaction, must stay sequential.
    """
    width = limit if limit is not None else pool_width()
    if width >= len(coros):
        return list(await asyncio.gather(*coros))

    semaphore = asyncio.Semaphore(width)

    async def run(coro: Coroutine[Any, Any, T]) -> T:
        async with semaphore:
            return await coro

    return list(await asyncio.gather(*(run(coro) for coro in coros)))
