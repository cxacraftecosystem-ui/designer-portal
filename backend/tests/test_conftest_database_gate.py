import os
import sys
from pathlib import Path

import conftest
import pytest

SEEN_AT_IMPORT = os.environ.get("DATABASE_URL", "")

BACKEND_ROOT = Path(__file__).resolve().parents[1]


def test_the_gate_is_resolved_before_any_test_module_is_imported():
    if not conftest.DATABASE_URL:
        pytest.skip("no DSN configured at all — there is nothing for the conftest to publish")
    assert SEEN_AT_IMPORT, (
        "a test module read DATABASE_URL at module scope and got nothing, while the conftest had "
        f"already resolved {conftest.DATABASE_URL!r}. Every gated module would skip on this machine."
    )
    assert conftest.is_local_dsn(SEEN_AT_IMPORT) is conftest.HAS_LOCAL_DATABASE, (
        f"the gate disagrees with itself: conftest resolved {conftest.DATABASE_URL!r} but a module "
        f"reading os.environ at import time sees {SEEN_AT_IMPORT!r}"
    )


def test_the_gap_this_closes_is_real_and_not_merely_agreed_with_by_the_environment():
    if conftest.DATABASE_URL_WAS_EXPORTED:
        pytest.skip(
            "this shell exports DATABASE_URL, so a module-scope read would have found it anyway; "
            "the accident this guards lives on machines where the DSN is only in backend/.env"
        )
    dotenv = BACKEND_ROOT / ".env"
    if not dotenv.exists():
        pytest.skip("no DSN anywhere — nothing is gated on this machine and nothing is at risk")
    declared = "DATABASE_URL" in dotenv.read_text(encoding="utf-8", errors="replace")
    if not declared:
        pytest.skip("backend/.env declares no DATABASE_URL — this machine has nothing to resolve")
    assert conftest.DATABASE_URL, (
        "the shell exported no DSN and backend/.env declares one, but the conftest resolved "
        "nothing — the twenty-eight gated modules are now skipping on a machine that has a database. "
        "The usual cause is that both loaders are CWD-relative and pytest was invoked from outside "
        "backend/, which is exactly what _dotenv_database_url is there to catch."
    )


def test_a_remote_dsn_is_never_treated_as_a_local_one():
    assert conftest.HAS_LOCAL_DATABASE is conftest.is_local_dsn(conftest.DATABASE_URL)

    assert not conftest.is_local_dsn("postgresql://u:p@db.example.net:5432/app")
    assert not conftest.is_local_dsn("postgresql://u:p@managed-pooler.example.net:6543/app")
    assert not conftest.is_local_dsn("postgresql://ci:ci@ci.invalid:5432/no_such_database")
    assert not conftest.is_local_dsn("")

    assert conftest.is_local_dsn("postgresql://postgres:postgres@127.0.0.1:55442/design_workshop")
    assert conftest.is_local_dsn("postgresql://postgres:postgres@localhost:55442/design_workshop")


def test_only_the_host_decides_and_an_unparseable_dsn_is_remote():
    assert not conftest.is_local_dsn("postgresql://api:localhost@db.example.net:5432/app")
    assert not conftest.is_local_dsn("postgresql://api:p@db.example.net:5432/localhost")
    assert not conftest.is_local_dsn("postgresql://api:127.0.0.1@db.example.net:5432/app")
    assert not conftest.is_local_dsn("postgresql://api:p@db.example.net:5432/app?opt=localhost")

    assert not conftest.is_local_dsn("not a dsn at all")
    assert not conftest.is_local_dsn("postgresql://")
    assert not conftest.is_local_dsn("postgresql://u:p@[not-an-address]:5432/app")

    assert conftest.is_local_dsn("postgresql://postgres:postgres@[::1]:55442/design_workshop")
    assert conftest.is_local_dsn("postgresql://postgres:postgres@127.1.2.3:5432/design_workshop")

    assert not conftest.is_local_dsn("postgresql://postgres:postgres@192.168.1.40:5432/design_workshop")


def test_needs_db_is_a_skip_marker_that_agrees_with_the_resolved_gate():
    marker = conftest.needs_db.mark
    assert marker.name == "skipif"
    assert marker.args == (not conftest.DATABASE_REACHABLE,)
    assert "LOCAL database" in marker.kwargs["reason"]
    assert conftest.DATABASE_REACHABLE is False or conftest.HAS_LOCAL_DATABASE is True


def test_the_refusal_cannot_be_swallowed_by_the_layers_that_catch_exception():
    assert issubclass(conftest.RemoteDatabaseRefused, BaseException)
    assert not issubclass(conftest.RemoteDatabaseRefused, Exception)

    with pytest.raises(conftest.RemoteDatabaseRefused) as caught:
        try:
            conftest._refuse_remote_database_connection()
        except Exception as exc:  # noqa: BLE001 - the point of the test is that this cannot fire
            pytest.fail(f"a bare `except Exception` swallowed the refusal: {exc!r}")

    message = str(caught.value)
    assert "needs_db" in message
    assert not conftest.DATABASE_URL or conftest.DATABASE_URL not in message


def test_the_refusal_is_armed_exactly_when_the_gate_is_closed():
    prisma_module = sys.modules.get("prisma")
    if conftest.HAS_LOCAL_DATABASE:
        if prisma_module is not None:
            assert (
                getattr(prisma_module.Prisma.connect, "__name__", "")
                != "_refuse_remote_database_connection"
            ), "the gate is OPEN (a local DSN) and connections are being refused anyway"
        return
    armed = conftest._arm_remote_database_refusal()
    assert armed is (prisma_module is not None and hasattr(prisma_module, "Prisma"))
    if armed:
        assert (
            prisma_module.Prisma.connect.__name__ == "_refuse_remote_database_connection"
        ), "the gate is CLOSED and a real Prisma.connect is still reachable"
