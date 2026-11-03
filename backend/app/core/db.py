import asyncio
import logging
from contextlib import suppress
from datetime import timedelta
from urllib.parse import parse_qsl, urlsplit, urlunsplit

from app.core.config import _is_local_db_host, get_settings
from prisma import Prisma

logger = logging.getLogger(__name__)


def build_runtime_database_url(base_url: str, *, pooled: bool) -> str:
    settings = get_settings()
    try:
        parts = urlsplit(base_url)
        host = parts.hostname or ""
    except ValueError:
        return base_url
    if not parts.scheme.startswith("postgres") or _is_local_db_host(host):
        return base_url

    present = {key.lower() for key, _ in parse_qsl(parts.query, keep_blank_values=True)}
    additions: list[str] = []
    limit_was_chosen = "database_connection_limit" in settings.model_fields_set
    if (pooled or limit_was_chosen) and "connection_limit" not in present:
        additions.append(f"connection_limit={settings.database_connection_limit}")
    if settings.database_pool_timeout is not None and "pool_timeout" not in present:
        additions.append(f"pool_timeout={settings.database_pool_timeout}")
    if pooled and "pgbouncer" not in present:
        additions.append("pgbouncer=true")
    if not additions:
        return base_url

    query = "&".join([parts.query, *additions]) if parts.query else "&".join(additions)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


db = Prisma(
    datasource={
        "url": build_runtime_database_url(
            get_settings().database_url,
            pooled=get_settings().database_use_transaction_pooler,
        )
    },
    connect_timeout=timedelta(seconds=get_settings().database_connect_timeout),
)


_RETRY_CONNECT_TIMEOUT_SECONDS = 10.0


async def connect_db() -> None:
    if db.is_connected():
        return
    attempts = 6
    delay = 2.0
    retry_timeout = timedelta(
        seconds=min(float(get_settings().database_connect_timeout), _RETRY_CONNECT_TIMEOUT_SECONDS)
    )
    for attempt in range(1, attempts + 1):
        try:
            if attempt == 1:
                await db.connect()
            else:
                await db.connect(timeout=retry_timeout)
            return
        except Exception as exc:
            if attempt == attempts:
                raise
            logger.warning(
                "Database connect failed (attempt %s/%s): %s — retrying in %.0fs",
                attempt,
                attempts,
                exc,
                delay,
            )
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


async def disconnect_db() -> None:
    if db.is_connected():
        await db.disconnect()


async def ensure_db_connected() -> None:
    try:
        if db.is_connected():
            await db.query_raw("SELECT 1")
            return
    except Exception:  # noqa: BLE001 - a failed probe just means "reconnect below"
        pass
    with suppress(Exception):
        await db.disconnect()
    await connect_db()
    await db.query_raw("SELECT 1")
