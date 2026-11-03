from types import SimpleNamespace

import pytest

from app.core.db import build_runtime_database_url

LOCAL = "postgresql://postgres:postgres@127.0.0.1:55442/design_workshop"
REMOTE = "postgresql://api:secret@db.example.net/app"


def _settings(*, limit: int = 10, pool_timeout: int | None = None, limit_was_set: bool = False):
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
    stub_settings(pool_timeout=5)
    for url in (
        LOCAL,
        "postgresql://postgres:postgres@localhost:5432/db",
        "postgresql://postgres:postgres@[::1]:5432/db",
        "postgresql://postgres:postgres@db:5432/db",
        "postgresql://postgres:postgres@10.1.2.3:5432/db",
        LOCAL + "?options=-c%20statement_timeout%3D5000",
    ):
        assert build_runtime_database_url(url, pooled=True) == url
        assert build_runtime_database_url(url, pooled=False) == url


def test_a_pooled_remote_dsn_gets_the_cap_and_the_pgbouncer_flag(stub_settings):
    stub_settings(limit=10, pool_timeout=20)
    result = build_runtime_database_url(REMOTE, pooled=True)
    assert result.startswith(REMOTE + "?")
    assert "connection_limit=10" in result
    assert "pool_timeout=20" in result
    assert "pgbouncer=true" in result


def test_a_direct_remote_dsn_is_left_to_size_its_own_pool(stub_settings):
    stub_settings(limit=10)
    assert build_runtime_database_url(REMOTE, pooled=False) == REMOTE


def test_an_operator_who_names_a_limit_gets_it_whatever_the_shape(stub_settings):
    stub_settings(limit=3, limit_was_set=True)
    result = build_runtime_database_url(REMOTE, pooled=False)
    assert "connection_limit=3" in result
    assert "pgbouncer" not in result


def test_pooling_is_asked_per_dsn_and_not_read_from_settings(stub_settings):
    stub_settings(limit=10)
    assert "pgbouncer=true" in build_runtime_database_url(REMOTE, pooled=True)
    assert "pgbouncer" not in build_runtime_database_url(REMOTE, pooled=False)


def test_a_parameter_already_in_the_dsn_always_wins(stub_settings):
    stub_settings(limit=10, pool_timeout=20)
    url = REMOTE + "?connection_limit=3&pool_timeout=1&pgbouncer=false"
    assert build_runtime_database_url(url, pooled=True) == url


def test_the_existing_query_survives_byte_for_byte(stub_settings):
    stub_settings(limit=10)
    original = "options=-c%20statement_timeout%3D5000&sslmode=require&foo&opt=a&opt=b"
    result = build_runtime_database_url(f"{REMOTE}?{original}", pooled=True)
    assert result == f"{REMOTE}?{original}&connection_limit=10&pgbouncer=true"


def test_the_netloc_is_never_rebuilt(stub_settings):
    stub_settings(limit=10)
    url = "postgresql://api:p%40ss%2Fword%25@db.example.net:6543/app"
    result = build_runtime_database_url(url, pooled=True)
    assert result.startswith(url + "?")


def test_something_that_is_not_a_postgres_dsn_is_left_alone(stub_settings):
    stub_settings(limit=10)
    for url in ("", "not a dsn at all", "redis://cache.example.net:6379/0"):
        assert build_runtime_database_url(url, pooled=True) == url
