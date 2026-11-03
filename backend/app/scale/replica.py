import asyncio
from collections.abc import Awaitable, Callable
from typing import Any

from app.scale.flags import log_once, read_replica_url

_FAILURES_BEFORE_DEMOTION = 3

_client: Any | None = None
_build_lock = asyncio.Lock()
_demoted = False
_failures = 0


def replica_configured() -> bool:
    return read_replica_url() is not None


async def reader() -> Any:
    from app.core.db import db

    url = read_replica_url()
    if url is None or _demoted:
        return db

    if _client is not None:
        return _client
    async with _build_lock:
        if _client is not None:
            return _client
        built = await _build(url)
        return db if built is None else built


async def _build(url: str) -> Any | None:
    global _client, _demoted
    try:
        from datetime import timedelta

        from app.core.config import get_settings
        from app.core.db import build_runtime_database_url
        from prisma import Prisma

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


async def read_via_replica[T](run: Callable[[Any], Awaitable[T]]) -> T:
    from app.core.db import db

    client = await reader()
    if client is db:
        return await run(client)
    global _failures, _demoted
    try:
        result = await run(client)
    except asyncio.CancelledError:
        raise
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
    return {
        "configured": replica_configured(),
        "connected": _client is not None,
        "demoted": _demoted,
        "consecutiveFailures": _failures,
    }


async def close_replica() -> None:
    global _client, _demoted, _failures
    client, _client = _client, None
    _demoted = False
    _failures = 0
    if client is not None:
        try:
            await client.disconnect()
        except Exception:  # noqa: BLE001 - shutdown must not fail on a client that will not close
            pass
