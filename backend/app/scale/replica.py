"""Optional read-replica routing. Unset means every query goes where it goes today.

WHAT THIS COSTS WHEN IT IS ON, stated first because it is the thing most easily overlooked:
prisma-client-py runs a Rust query engine as a CHILD PROCESS per client. A second client is a second
engine — tens of megabytes of resident memory on a box that has 1 GiB in total and already runs
uvicorn, the primary engine and the media queue's engine in a separate service. So this is not a
free "might as well". Unset (the default) constructs nothing and imports nothing; set it only when a
real replica exists, and watch the memory after the first deploy that has it on.

WHAT IT BUYS. The read path and the write path stop competing for the same connection pool. Today
both share ONE Prisma pool to the primary — ten connections where a pooler is declared, otherwise
whatever the engine sizes for the box — so a burst of slow list queries (``/search`` measured 8.9s,
``/dashboard/stats`` 10.5s) can leave a save waiting for a connection. Sending the slow reads
elsewhere means a write never queues behind a dashboard.

A REPLICA IS JUST A SECOND DSN. Nothing here knows or asks who provides it — a managed provider's
read endpoint, a streaming standby somebody runs themselves, a copy for a one-off migration. It is
absent by default and everything works without it; see ``DATABASE_READ_REPLICA_URL`` in config.py.
It is also SHAPED independently of the primary, which is why it has its own
``DATABASE_READ_REPLICA_USE_TRANSACTION_POOLER`` rather than inheriting a single global answer.

WHERE IT MUST NOT BE USED. Anything that reads back what it just wrote. Replication lag is real and
unbounded during a spike, so a create that returns the row it just made, a form that re-reads after
save, a "check completion" that must see the record the user submitted a second ago — all of those
stay on the primary. The safe adopters are the wide read-only list and aggregate endpoints, and they
are also the ones that are slow, which is convenient rather than coincidental.
"""

import asyncio
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from app.scale.flags import log_once, read_replica_url

T = TypeVar("T")

# Consecutive replica failures after which this process stops using it. Same reasoning as the Redis
# breaker in redis_backend.py: absorb a blip, but never flap. A demoted replica is one restart away
# from being tried again, and everything works from the primary meanwhile.
_FAILURES_BEFORE_DEMOTION = 3

_client: Any | None = None
_build_lock = asyncio.Lock()
_demoted = False
_failures = 0


def replica_configured() -> bool:
    """Whether a read-only URL is set at all. Presence of the URL IS the flag."""
    return read_replica_url() is not None


async def reader() -> Any:
    """The Prisma client read paths should use: the replica when it is available, else the primary.

    Returns the primary — never None, never raises — so a call site is a one-word change from what
    it does now and behaves identically when nothing is configured.
    """
    from app.core.db import db  # imported here: importing this module must not build a client

    url = read_replica_url()
    if url is None or _demoted:
        return db

    if _client is not None:
        return _client
    async with _build_lock:
        if _client is not None:  # another coroutine built it while we waited
            return _client
        built = await _build(url)
        # Explicit `is None` rather than `built or db`: a client object's truthiness is not this
        # module's business, and one day some library will define __bool__ or __len__ and quietly
        # send every read to the primary.
        return db if built is None else built


async def _build(url: str) -> Any | None:
    """Construct and connect the replica client once, or demote permanently and return None."""
    global _client, _demoted
    try:
        from datetime import timedelta

        from app.core.config import get_settings
        from app.core.db import build_runtime_database_url
        from prisma import Prisma

        # The same treatment the primary gets, and for the same reasons: the pooler parameters on
        # the URL, the cold-start allowance on the client. A replica sharing the primary's pooler
        # with an unbounded pool would spend the client-connection budget the primary needs (the
        # EMAXCONN failure this deployment has already lived through twice), and a replica that
        # idle-suspends is demoted permanently on the first wake if it is given a timeout meant for
        # a warm server — see app/core/db.py.
        #
        # BUT THE POOLING QUESTION IS ASKED OF THIS DSN, not of the primary's. These are two
        # endpoints and either may be pooled without the other: a replica is often the pooled one,
        # since it carries the wide read queries, while the primary stays direct so migrations can
        # take session-mode advisory locks. Passing the primary's flag here — which is what this
        # call used to do implicitly, by letting build_runtime_database_url read settings itself —
        # makes an honest answer about the primary into a wrong one about the replica, and the cost
        # of wrong-in-that-direction is correctness, not speed. ``None`` means "same as the
        # primary", so the common case still needs no second variable.
        settings = get_settings()
        pooled = settings.database_read_replica_use_transaction_pooler
        if pooled is None:
            pooled = settings.database_use_transaction_pooler
        client = Prisma(
            datasource={"url": build_runtime_database_url(url, pooled=pooled)},
            connect_timeout=timedelta(seconds=settings.database_connect_timeout),
        )
        await client.connect()
        await client.query_raw("SELECT 1")
    except Exception as exc:  # noqa: BLE001 - a missing replica is a degradation, not an outage
        _demoted = True
        log_once(
            f"replica.connect.{type(exc).__name__}",
            "Could not connect to DATABASE_READ_REPLICA_URL (%s: %s). Every query will use the "
            "primary database, exactly as it does with the variable unset.",
            type(exc).__name__,
            exc,
        )
        return None
    _client = client
    return client


async def read_via_replica(run: Callable[[Any], Awaitable[T]]) -> T:
    """Run a read on the replica, falling back to the primary if the replica cannot serve it.

        rows = await read_via_replica(lambda client: client.artisan.find_many(where=where, take=20))

    The retry on the primary is safe because ``run`` must be a READ — replaying it costs a query and
    changes nothing. That is the whole contract of this function, and the reason it is not named
    ``run_query``: a caller who passes a write here would have it executed twice.
    """
    from app.core.db import db

    client = await reader()
    if client is db:
        return await run(client)
    global _failures, _demoted
    try:
        result = await run(client)
    except asyncio.CancelledError:
        raise  # the request went away; the replica is not at fault and must not be blamed for it
    except Exception as exc:  # noqa: BLE001 - fall back rather than fail a read that CAN be served
        _failures += 1
        log_once(
            f"replica.query.{type(exc).__name__}",
            "A read against the replica failed (%s: %s); retrying on the primary. After %s "
            "consecutive failures this process stops using the replica until it restarts.",
            type(exc).__name__,
            exc,
            _FAILURES_BEFORE_DEMOTION,
        )
        if _failures >= _FAILURES_BEFORE_DEMOTION:
            _demoted = True
        return await run(db)
    _failures = 0
    return result


async def replica_status() -> dict[str, object]:
    """State for an admin/debug endpoint. Carries no URL — that string holds a password."""
    return {
        "configured": replica_configured(),
        "connected": _client is not None,
        "demoted": _demoted,
        "consecutiveFailures": _failures,
    }


async def close_replica() -> None:
    """Disconnect the replica client, if one was ever built.

    Belongs in the ``finally`` block of ``app.main.lifespan``, next to ``disconnect_db()``. Skipping
    it leaks a query-engine child process across a reload; it is a no-op when no replica is
    configured, which is why it is safe to call unconditionally.
    """
    global _client, _demoted, _failures
    client, _client = _client, None
    _demoted = False
    _failures = 0
    if client is not None:
        try:
            await client.disconnect()
        except Exception:  # noqa: BLE001 - shutdown must not fail on a client that will not close
            pass
