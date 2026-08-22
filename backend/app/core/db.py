"""The one runtime Prisma client, and the connection settings applied to whatever DSN it is given.

THIS MODULE KNOWS THAT THE DATABASE IS POSTGRESQL AND NOTHING ELSE ABOUT IT. It used to know a
provider: ``build_runtime_database_url`` matched the host suffix ``.pooler.supabase.com`` and
rewrote the port from 5432 to 6543, and every pool setting below rode on that match. When the
deployment moved to a different Postgres the match stopped firing and took the pool settings with
it — silently, because the function's contract is "return the URL unchanged if it does not apply".
A provider migration should not be able to switch a connection setting off by accident, so the
rewrite is gone; what is left is decided by SHAPE (is the host remote?) and by an explicit,
PER-DSN statement of whether that DSN is a transaction pooler.

WHAT THE POOL CAP IS, AND WHAT IT IS NOT — this paragraph replaces an earlier one that overstated
it. ``connection_limit`` was chosen as a REDUCTION: at 40 per worker this deployment exhausted a
pooler's SHARED client-connection budget and crash-looped, which is the incident recorded on
``DATABASE_CONNECTION_LIMIT`` in config.py. A shared budget is a property of a pooler, so a cap on
our share of it only means something behind one. Against a DIRECT endpoint there is no shared
budget, and Prisma's engine picks its own pool size from the CPU count rather than using a fixed
number, so writing ``connection_limit=10`` onto a direct DSN on a small box is an INCREASE over
what the engine would have chosen — not crash-loop protection. So it is written only where the
deployment says a pooler is in front, or where an operator chose the number themselves.

FOR WHOEVER OWNS THE DEPLOYMENT: that flag defaults to true (config.py explains why the two
mistakes are not symmetric), so a DIRECT DSN with the flag left alone still receives
``connection_limit=10`` — and 10 has never been evaluated as a pool size for a direct endpoint,
only as a reduction against a pooler. One line in the deployment environment settles it: either
point ``DATABASE_URL`` at the pooled endpoint, or set ``DATABASE_USE_TRANSACTION_POOLER=false`` and
let the engine size its own pool. No code change either way, which is the whole point of this
module knowing nothing about the provider.

POOLED VERSUS DIRECT IS A DEPLOYMENT DECISION, NOT A CODE ONE. Every managed Postgres offers a
connection pooler, each on its own host and port convention, so there is nothing portable to
compute: the operator points a DSN at the pooled endpoint and says so with a flag. And the answer
is PER DSN, not per deployment — ``DATABASE_READ_REPLICA_URL`` is a second, independent endpoint
that may be pooled when the primary is not, which is why ``pooled`` below is an argument rather
than something this function reads for itself. Migrations need the DIRECT endpoint — session-mode
advisory locks and DDL, which a transaction pooler cannot provide — and they get it the same way,
from the environment: ``prisma migrate deploy`` reads ``DATABASE_URL`` raw out of
``schema.prisma``'s ``env("DATABASE_URL")`` and never sees this Settings object or this function.
Prisma also has a first-class ``directUrl`` for the same split, which would let one environment
carry both; adopting it is a one-line change to ``prisma/schema.prisma`` plus a second secret, and
is not made here.

THE COLD-START ALLOWANCE IS NOT ON THE URL. A database that suspends when idle has to be woken by
the first connection, which takes longer than a client library's default allows for; that is
``DATABASE_CONNECT_TIMEOUT``, and it is passed to the Prisma client rather than added to the DSN as
a query parameter — see the comment on the client below for why the distinction matters. It is also
spent ONCE, on the first attempt: nothing serves HTTP until ``connect_db`` returns, and the deploy's
health poll gives that eighty seconds. ``connect_db``'s docstring holds that arithmetic.
"""

import asyncio
import logging
from contextlib import suppress
from datetime import timedelta
from urllib.parse import parse_qsl, urlsplit, urlunsplit

# Imported rather than re-implemented: the loopback/private test is a policy, and a second copy of
# it is a second thing to forget to update. app/core/config.py owns it (and docker's entrypoint
# mirrors it in shell); this module is in the same package and uses that one answer.
from app.core.config import _is_local_db_host, get_settings
from prisma import Prisma

logger = logging.getLogger(__name__)


def build_runtime_database_url(base_url: str, *, pooled: bool) -> str:
    """Return the URL a *runtime* Prisma client should connect with.

    ``pooled`` states whether THIS DSN is a transaction-mode connection pooler. It is a required
    argument and not read from settings, because there is more than one DSN: the primary and
    ``DATABASE_READ_REPLICA_URL`` are separate endpoints that can differ in shape, and one boolean
    describing two of them is how a pooled replica loses ``pgbouncer=true`` the day somebody sets
    the primary's flag to false. Each caller says which endpoint's intent it is passing.

    What gets added, to a REMOTE Postgres DSN only:

    * ``pgbouncer=true`` — when ``pooled``. It tells the query engine not to rely on session-pinned
      named prepared statements, which transaction pooling cannot keep.
    * ``connection_limit`` — when ``pooled``, because a pooler's client budget is shared and our
      share of it has to be bounded; or, whatever the shape, when an operator set
      ``DATABASE_CONNECTION_LIMIT`` themselves. Against a direct endpoint with no operator opinion
      the engine sizes its own pool from the CPU count, which is a better default than a number
      this deployment measured against somebody's pooler. See the module header.
    * ``pool_timeout`` — whenever ``DATABASE_POOL_TIMEOUT`` is set. Unset (the default) leaves
      Prisma's own. It is purely an operator opinion about OUR pool rather than a statement about
      the far end, so unlike the two above it is not gated on ``pooled`` — but it still only
      reaches a remote DSN, because the local early-return above it comes first.

    A LOOPBACK OR PRIVATE HOST IS RETURNED UNTOUCHED — the docker-compose database, a tunnel, a
    developer's laptop. Nothing there sits behind a pooler and nothing there has a shared client
    budget to protect, so there is no problem to solve and local development keeps the DSN it was
    given, character for character. That test is ``_is_local_db_host``, the same shape test that
    decides TLS: no hostname is ever matched against a vendor.

    ANY PARAMETER ALREADY PRESENT IN THE DSN WINS. An operator who wrote ``connection_limit=3``
    into the URL has made a decision, and a default that silently overrode it would be a setting
    that cannot be set.

    THE DSN IS ONLY EVER APPENDED TO, never re-encoded, which is the same discipline
    ``_with_explicit_sslmode`` follows and for the same reason — and it is not a theoretical one.
    This function used to round-trip the query through ``dict(parse_qsl(...))`` + ``urlencode()``,
    which (measured) turns ``options=-c%20statement_timeout%3D5000`` into ``-c+statement_timeout``,
    turns a valueless ``&foo`` into ``foo=``, and silently drops all but the last of a repeated
    key. Harmless while that code ran for exactly one vendor's pooler host; not harmless once it
    runs for every remote DSN. So the parse below is used only to ANSWER "is this key already
    here?", and the returned string is the operator's own text plus whatever we appended.
    """
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
    # ``model_fields_set`` is how "the operator chose 10" is told apart from "10 is our default":
    # pydantic records which fields an env var or a dotenv line actually supplied. Without that
    # distinction, gating on ``pooled`` would make DATABASE_CONNECTION_LIMIT a setting that cannot
    # be set for a direct endpoint.
    limit_was_chosen = "database_connection_limit" in settings.model_fields_set
    if (pooled or limit_was_chosen) and "connection_limit" not in present:
        additions.append(f"connection_limit={settings.database_connection_limit}")
    if settings.database_pool_timeout is not None and "pool_timeout" not in present:
        additions.append(f"pool_timeout={settings.database_pool_timeout}")
    if pooled and "pgbouncer" not in present:
        additions.append("pgbouncer=true")
    if not additions:
        return base_url

    # netloc is carried across verbatim: the credentials in it are already percent-encoded and
    # rebuilding them from the parsed parts is how a password containing "%" or "@" gets corrupted.
    query = "&".join([parts.query, *additions]) if parts.query else "&".join(additions)
    return urlunsplit((parts.scheme, parts.netloc, parts.path, query, parts.fragment))


# ``connect_timeout`` is the cold-start allowance, and it belongs HERE rather than in the URL: it is
# a documented argument of the installed client (prisma/_constants.py defaults it to ten seconds),
# and set on the constructor it also covers every later ``connect()`` — the retry loop below and the
# background watchdog in app.main both inherit it without passing anything. See config.py for why
# thirty, and why a database that has to be woken needs more than a number chosen for a warm one.
db = Prisma(
    datasource={
        "url": build_runtime_database_url(
            get_settings().database_url,
            pooled=get_settings().database_use_transaction_pooler,
        )
    },
    connect_timeout=timedelta(seconds=get_settings().database_connect_timeout),
)


#: Seconds allowed for the SECOND and later connect attempts, and the reason the cold-start
#: allowance is not simply used for all of them. ``DATABASE_CONNECT_TIMEOUT`` is thirty seconds
#: because a suspended database has to be WOKEN, and that is a one-off cost: once the first attempt
#: has paid it the instance is awake, so a later attempt that is still hanging is not waiting for a
#: wake, it is waiting for something that is broken. Spending thirty seconds on each of six
#: attempts turns a bad boot into four minutes of silence inside a deploy gate that allows eighty
#: seconds (see the docstring below). Ten matches prisma-client-py's own default for a warm server.
_RETRY_CONNECT_TIMEOUT_SECONDS = 10.0


async def connect_db() -> None:
    """Connect the runtime Prisma client, retrying a transient connection-refused / engine failure.

    The retry is also what covers a database that was asleep: ``DATABASE_CONNECT_TIMEOUT`` (applied
    on the client above) buys the FIRST attempt enough time to wake a suspended instance, and this
    loop covers the case where one attempt was not enough. Both halves are needed — a timeout with
    no retry turns a cold start into a failed boot, and a retry with too short a timeout never
    lands.

    THE LONG ALLOWANCE IS SPENT ONCE, NOT SIX TIMES, AND THE BUDGET IS NOT OURS TO SPEND. Nothing
    serves HTTP until this returns: ``app.main.lifespan`` awaits it before the app starts. The
    deploy gate on the other end of that — the ``/health`` poll in
    ``.github/workflows/deploy-backend.yml``, forty tries two seconds apart — fails the deploy and
    dumps journalctl after EIGHTY SECONDS. With thirty seconds per attempt plus this backoff, three
    failed attempts (30+2+30+4+30) already exceed that; with ten seconds after the first, four fit.
    These two numbers are one decision: change the timeouts here and re-read that poll count.

    If the database is momentarily at its client-connection ceiling (e.g. overlapping connections during
    a deploy/restart), connecting raises and — without a retry — uvicorn exits and systemd restarts it,
    which opens YET MORE connection attempts and turns a brief spike into a crash-loop that keeps the
    pooler saturated. Retrying in-process instead waits for connections to drain, breaking the spiral.
    """
    if db.is_connected():
        return
    attempts = 6
    delay = 2.0
    # Never longer than the configured allowance: an operator who lowered DATABASE_CONNECT_TIMEOUT
    # wants attempts that end sooner, and a retry that outlasts the first attempt would be absurd.
    retry_timeout = timedelta(
        seconds=min(float(get_settings().database_connect_timeout), _RETRY_CONNECT_TIMEOUT_SECONDS)
    )
    for attempt in range(1, attempts + 1):
        try:
            # Attempt one inherits the client's cold-start allowance by passing no timeout at all.
            if attempt == 1:
                await db.connect()
            else:
                await db.connect(timeout=retry_timeout)
            return
        except Exception as exc:  # any connect failure should back off, not crash-loop
            if attempt == attempts:
                raise
            logger.warning(
                "Database connect failed (attempt %s/%s): %s — retrying in %.0fs",
                attempt, attempts, exc, delay,
            )
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)


async def disconnect_db() -> None:
    if db.is_connected():
        await db.disconnect()


async def ensure_db_connected() -> None:
    """Prove the runtime client is genuinely usable, reconnecting (disconnect-first) if it is not.

    ``is_connected()`` alone can lie: the Prisma client keeps its engine reference even when
    ``connect()`` *raised*, so it can read True while the engine is unusable. So we probe with
    ``SELECT 1`` and, on any failure, tear down the (possibly half-initialized) engine before
    reconnecting — the same recovery the web app's background watchdog uses. Raises only if the
    reconnect itself ultimately fails, so callers can back off and try again.
    """
    try:
        if db.is_connected():
            await db.query_raw("SELECT 1")
            return
    except Exception:  # noqa: BLE001 - a failed probe just means "reconnect below"
        pass
    with suppress(Exception):
        await db.disconnect()  # clear any half-initialized engine before reconnecting
    await connect_db()
    await db.query_raw("SELECT 1")  # prove the new link really works
