"""``build_runtime_database_url``: what it appends, to which DSNs, and what it must never rewrite.

WHY THIS FILE EXISTS. The function had no test at all, and it is the single piece of code in this
repository that decides what the runtime query engine's connection actually looks like. Its whole
previous behaviour was gated on one vendor's hostname suffix, so when the deployment moved provider
the gate stopped firing and nothing anywhere went red — the settings simply stopped being applied.
That is not a bug a reviewer finds by reading; it is one a test finds by asserting the shape.

Everything here is a pure string transformation with a stubbed Settings, so nothing connects to
anything. The module does pay for ``import prisma`` (via ``app.core.db``), which costs about two
minutes on a cold interpreter — but 28 other modules in this directory already import it, so in any
run of more than this one file the cost is already paid.
"""

from types import SimpleNamespace

import pytest

from app.core.db import build_runtime_database_url

LOCAL = "postgresql://postgres:postgres@127.0.0.1:55442/design_workshop"
REMOTE = "postgresql://api:secret@db.example.net/app"


def _settings(*, limit: int = 10, pool_timeout: int | None = None, limit_was_set: bool = False):
    """A stand-in for the fields this function reads, and only those.

    ``model_fields_set`` is how the function tells "the operator chose this number" from "this is
    our default", so the stub has to carry it or the test would be measuring something else.
    """
    return SimpleNamespace(
        database_connection_limit=limit,
        database_pool_timeout=pool_timeout,
        model_fields_set={"database_connection_limit"} if limit_was_set else set(),
    )


@pytest.fixture
def stub_settings(monkeypatch):
    def use(**kwargs):
        monkeypatch.setattr("app.core.db.get_settings", lambda: _settings(**kwargs))

    return use


def test_a_local_dsn_is_returned_character_for_character(stub_settings):
    """The docker-compose database has no pooler and no shared client budget: nothing to add.

    Asserted as string identity rather than "the parameters are absent", because identity is the
    promise the docstring makes and it is the one a re-encoding change would break invisibly.
    """
    stub_settings(pool_timeout=5)
    for url in (
        LOCAL,
        "postgresql://postgres:postgres@localhost:5432/db",
        "postgresql://postgres:postgres@[::1]:5432/db",
        "postgresql://postgres:postgres@db:5432/db",  # a compose service name
        "postgresql://postgres:postgres@10.1.2.3:5432/db",  # private network
        LOCAL + "?options=-c%20statement_timeout%3D5000",
    ):
        assert build_runtime_database_url(url, pooled=True) == url
        assert build_runtime_database_url(url, pooled=False) == url


def test_a_pooled_remote_dsn_gets_the_cap_and_the_pgbouncer_flag(stub_settings):
    """Both settings exist for a transaction pooler, so both are written for one."""
    stub_settings(limit=10, pool_timeout=20)
    result = build_runtime_database_url(REMOTE, pooled=True)
    assert result.startswith(REMOTE + "?")
    assert "connection_limit=10" in result
    assert "pool_timeout=20" in result
    assert "pgbouncer=true" in result


def test_a_direct_remote_dsn_is_left_to_size_its_own_pool(stub_settings):
    """The number ten was measured as a REDUCTION against a pooler; it is not a default pool size.

    Against a direct endpoint there is no shared client budget, and Prisma's engine derives its pool
    from the CPU count. Writing ten there would RAISE the pool on a small box while the header
    claimed to be capping it — which is exactly what this deployment shipped for months after the
    provider moved. So with no pooler declared and no operator opinion, nothing is written.
    """
    stub_settings(limit=10)
    assert build_runtime_database_url(REMOTE, pooled=False) == REMOTE


def test_an_operator_who_names_a_limit_gets_it_whatever_the_shape(stub_settings):
    """Gating the cap on pooling must not turn DATABASE_CONNECTION_LIMIT into a dead setting."""
    stub_settings(limit=3, limit_was_set=True)
    result = build_runtime_database_url(REMOTE, pooled=False)
    assert "connection_limit=3" in result
    assert "pgbouncer" not in result


def test_pooling_is_asked_per_dsn_and_not_read_from_settings(stub_settings):
    """The primary and the read replica are different endpoints and may differ in shape.

    One global flag describing both is how a pooled replica loses ``pgbouncer=true`` on the day
    somebody tells the truth about a direct primary — a correctness failure that only appears under
    load. The argument being required is the fix, so the same settings must produce both answers.
    """
    stub_settings(limit=10)
    assert "pgbouncer=true" in build_runtime_database_url(REMOTE, pooled=True)
    assert "pgbouncer" not in build_runtime_database_url(REMOTE, pooled=False)


def test_a_parameter_already_in_the_dsn_always_wins(stub_settings):
    """A setting that silently overrides what the operator wrote is a setting that cannot be set."""
    stub_settings(limit=10, pool_timeout=20)
    url = REMOTE + "?connection_limit=3&pool_timeout=1&pgbouncer=false"
    assert build_runtime_database_url(url, pooled=True) == url


def test_the_existing_query_survives_byte_for_byte(stub_settings):
    """THE REGRESSION THIS PINS WAS REAL AND MEASURED, not a hypothetical about encoders.

    The function used to rebuild the query with ``urlencode(dict(parse_qsl(...)))``. Measured on
    that code: ``options=-c%20statement_timeout%3D5000`` came back as ``-c+statement_timeout``, a
    valueless ``&foo`` came back as ``foo=``, and a repeated key was reduced to its last value by
    the dict. libpq accepts ``+`` in ``options`` as a literal plus, not a space, so that first one
    is a server-side setting silently changed into a syntax error. It only ever ran for one vendor's
    pooler host, which is why it survived; it now runs for every remote DSN.

    So the rule is APPEND ONLY, the same rule ``_with_explicit_sslmode`` follows, and the parse is
    used only to answer "is this key already present?".
    """
    stub_settings(limit=10)
    original = "options=-c%20statement_timeout%3D5000&sslmode=require&foo&opt=a&opt=b"
    result = build_runtime_database_url(f"{REMOTE}?{original}", pooled=True)
    assert result == f"{REMOTE}?{original}&connection_limit=10&pgbouncer=true"


def test_the_netloc_is_never_rebuilt(stub_settings):
    """A password containing reserved characters must arrive exactly as the operator wrote it."""
    stub_settings(limit=10)
    url = "postgresql://api:p%40ss%2Fword%25@db.example.net:6543/app"
    result = build_runtime_database_url(url, pooled=True)
    assert result.startswith(url + "?")


def test_something_that_is_not_a_postgres_dsn_is_left_alone(stub_settings):
    """Nothing else is expected here, and guessing at an unknown scheme's query syntax is worse."""
    stub_settings(limit=10)
    for url in ("", "not a dsn at all", "redis://cache.example.net:6379/0"):
        assert build_runtime_database_url(url, pooled=True) == url
