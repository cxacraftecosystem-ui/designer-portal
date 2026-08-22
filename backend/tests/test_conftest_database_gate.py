"""The skip guard on every database-backed module, and the accident it used to depend on.

WHAT IS BEING GUARDED. Twenty-eight modules under ``backend/tests/`` decide whether to run at all by
reading ``os.environ.get("DATABASE_URL", "")`` at module scope and looking for ``localhost`` or
``127.0.0.1``. Among them is ``test_reference_resolver``, the only place in this repository where
the Craft carry is round-tripped through a real database, and the only place hydration-before-
promotion for ``craftName`` is pinned at all.

Nothing exports ``DATABASE_URL`` on a developer machine: it lives in ``backend/.env``. Those reads
were correct only because each of those modules happens to import ``app.core.db`` (or something
that reaches it) on a line ABOVE its own check, and constructing the Prisma client there loads the
dotenv file into ``os.environ`` as a side effect. ``test_controlled_vocabularies`` documented that
in its ``_local_database_url`` docstring and fixed it for itself; the fix was never propagated, and
the docstring blames one sibling module when the answer is twenty-eight.

The failure mode is the ugly one. Move an import — the single most innocent edit there is, and one
an autoformatter will make unasked — and thirty-odd database tests become green SKIPS. The suite
does not go red. Nobody is told. The guards on the cover page of every report stop existing.

``conftest.resolve_database_url`` closes it by resolving the DSN once, before any test module is
imported, and writing the answer back into ``os.environ``. These tests are what stop that from
being quietly deleted or reordered into uselessness.

AND THE OPPOSITE FAILURE, which is the worse one. Those twenty-eight predicates are substring tests
over the WHOLE DSN, so they can also answer YES about a remote database — a password containing
"localhost" is enough — and then the destructive fixtures run against it. Resolving the shared
predicate correctly does nothing about that, because none of the twenty-eight consult the shared
predicate. ``conftest.pytest_collection_finish`` is the part that does: with the gate closed, the
database connection is refused rather than opened, so a module's private opinion of itself cannot
be acted on. The last two tests in this file pin that guard's polarity and pin the one property it
depends on — that the refusal is not an ``Exception`` and therefore cannot be retried into silence
by the three layers that catch ``Exception`` and recover.

HONEST ABOUT WHAT RUNS WHERE. The first test is vacuous in CI, where ``DATABASE_URL`` is exported
into the job (a deliberately unresolvable ``.invalid`` placeholder — see
``.github/workflows/checks.yml``) and would therefore be visible at module scope with or without
the conftest. It has teeth exactly where the accident lived: a developer machine with a ``.env``
and nothing exported — verified by disabling the publish and watching it fail with
``DATABASE_URL was ''``. The second test is what keeps the first from being believed for the wrong
reason: it refuses to pass quietly in the environment where the first one cannot fail.
"""

import os
import sys
from pathlib import Path

import conftest
import pytest

# READ AT MODULE SCOPE, and that is the whole measurement rather than a stylistic choice: this line
# reproduces exactly what the twenty-eight gated modules do, on a module that imports nothing from
# ``app``. If the conftest stops publishing the DSN, this sees "" just as they would.
#
# The ``import conftest`` above does not disturb it and does not need to be moved below. pytest
# imports a directory's conftest as a plugin BEFORE it imports any test module in that directory,
# so by the time this file's body runs the resolution has already happened; naming the module here
# only binds it. That ordering is the property the whole fix rests on.
SEEN_AT_IMPORT = os.environ.get("DATABASE_URL", "")

BACKEND_ROOT = Path(__file__).resolve().parents[1]


def test_the_gate_is_resolved_before_any_test_module_is_imported():
    """A module that imports no application code sees the same DSN the conftest resolved.

    Fails — verified by doing it — the moment the publish step in conftest is removed or moved
    above the resolution, which is the edit this exists to catch. The message it fails with names
    the DSN the conftest had in hand while a test module was seeing nothing.
    """
    if not conftest.DATABASE_URL:
        pytest.skip("no DSN configured at all — there is nothing for the conftest to publish")
    assert SEEN_AT_IMPORT, (
        "a test module read DATABASE_URL at module scope and got nothing, while the conftest had "
        f"already resolved {conftest.DATABASE_URL!r}. Every gated module would skip on this machine."
    )
    # The VERDICT, not the string. Where the DSN came from Settings it has been through
    # ``_with_explicit_sslmode`` and a remote one carries an extra ``?sslmode=require`` that the
    # exported value does not; comparing the two texts would fail in CI for a reason that has
    # nothing to do with this guard. What has to agree is the only thing anything reads them for.
    assert conftest.is_local_dsn(SEEN_AT_IMPORT) is conftest.HAS_LOCAL_DATABASE, (
        f"the gate disagrees with itself: conftest resolved {conftest.DATABASE_URL!r} but a module "
        f"reading os.environ at import time sees {SEEN_AT_IMPORT!r}"
    )


def test_the_gap_this_closes_is_real_and_not_merely_agreed_with_by_the_environment():
    """On a developer machine the DSN exists ONLY in ``backend/.env`` — so the publish is load-bearing.

    This is the half that stops the test above being believed for the wrong reason. Where the shell
    exports nothing (a laptop with ``.env``), it asserts the two facts that together mean a
    module-scope ``os.environ`` read would come back empty without the conftest: the shell had no
    DSN, and ``.env`` has one. Where the shell DOES export a DSN — CI does, a placeholder — there is
    nothing here to measure and it says so instead of passing quietly.

    Deliberately NOT a subprocess re-measuring the original side effect (a scrubbed interpreter
    that imports ``app.core.db`` and watches ``os.environ`` fill in). That works and it is what
    proved the diagnosis, but ``import prisma`` alone costs 108 seconds on this machine, measured,
    and putting nearly two minutes into every CI run to re-derive a fact this file already asserts
    from cheaper evidence is how a check gets deleted.
    """
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
    """``HAS_LOCAL_DATABASE`` means "safe to create and delete rows in", not "reachable".

    The database-backed tests write users, workshops and stage entries and delete them again. The
    DSN in any deployed environment points at the ministry's real data, and CI's points at an
    unresolvable placeholder. Both must read as "not local", and the distinction is the only thing
    standing between `pytest` on a misconfigured shell and a live table.
    """
    assert conftest.HAS_LOCAL_DATABASE is conftest.is_local_dsn(conftest.DATABASE_URL)

    # The shapes a deployed DSN takes, plus the "nothing configured" case. A predicate that
    # admitted any of these would run destructive fixtures against the ministry's rows or against
    # whatever CI's placeholder resolved to. Deliberately NOT written as a named provider's
    # hostname: the guard must not know who runs the database, and a test that pins one vendor
    # string is how the guard would come to.
    assert not conftest.is_local_dsn("postgresql://u:p@db.example.net:5432/app")
    assert not conftest.is_local_dsn("postgresql://u:p@managed-pooler.example.net:6543/app")
    assert not conftest.is_local_dsn("postgresql://ci:ci@ci.invalid:5432/no_such_database")
    assert not conftest.is_local_dsn("")

    # And the two spellings a developer's compose stack uses — both must be admitted, or the
    # database half of the suite goes dark on the machines that can run it.
    assert conftest.is_local_dsn("postgresql://postgres:postgres@127.0.0.1:55442/design_workshop")
    assert conftest.is_local_dsn("postgresql://postgres:postgres@localhost:55442/design_workshop")


def test_only_the_host_decides_and_an_unparseable_dsn_is_remote():
    """The word "localhost" somewhere in the string is not a database on this machine.

    THE HOLE THIS PINS WAS REAL AND SHIPPED. ``is_local_dsn`` was ``any(host in url for host in
    ("localhost", "127.0.0.1"))`` — a substring test over the WHOLE DSN, which the password, the
    database name and the query string are all part of. Every case below satisfies that test while
    pointing at somebody else's server, and each one would have handed the destructive fixtures a
    remote database with the suite reporting nothing unusual. Verified against the old predicate
    before the change: all four read as local.

    WHAT THIS FIXES AND WHAT IT DOES NOT. It removes that class of mistake for every caller of
    ``is_local_dsn`` — and the twenty-eight modules described at the top of this file are not
    callers. They still compute the substring test for themselves, so on the very first DSN below
    ``HAS_LOCAL_DATABASE`` is correctly False while all twenty-eight evaluate True and would run
    their destructive fixtures. Parsing here could not reach them; ``conftest``'s
    ``pytest_collection_finish`` is what does, by refusing the connection itself, and the two tests
    after this one are what pin that. The honest summary is: this predicate is now right, the suite
    is now fail-closed, and twenty-eight modules are still wrong.

    The rest of the file is about a gate that skips when it should run. This is the opposite and
    much worse direction, and it is the one a change of database provider makes likelier, because
    the credentials and hostnames all change at once and nobody re-reads a predicate that has been
    passing for months.
    """
    assert not conftest.is_local_dsn("postgresql://api:localhost@db.example.net:5432/app")
    assert not conftest.is_local_dsn("postgresql://api:p@db.example.net:5432/localhost")
    assert not conftest.is_local_dsn("postgresql://api:127.0.0.1@db.example.net:5432/app")
    assert not conftest.is_local_dsn("postgresql://api:p@db.example.net:5432/app?opt=localhost")

    # FAILING CLOSED IS THE WHOLE DESIGN. Anything the parser cannot turn into a loopback host is
    # remote — a bare word, a scheme-less string, a DSN mangled by a broken secret substitution.
    # The alternative (unknown means local) is a guard that stops guarding the day something
    # upstream changes shape, silently, by permitting writes.
    assert not conftest.is_local_dsn("not a dsn at all")
    assert not conftest.is_local_dsn("postgresql://")
    assert not conftest.is_local_dsn("postgresql://u:p@[not-an-address]:5432/app")

    # IPv6 loopback IS this machine, and the old substring test refused it. Admitting it costs
    # nothing and stops a developer whose stack resolves ::1 from watching the database half of the
    # suite skip with no explanation.
    assert conftest.is_local_dsn("postgresql://postgres:postgres@[::1]:55442/design_workshop")
    assert conftest.is_local_dsn("postgresql://postgres:postgres@127.1.2.3:5432/design_workshop")

    # And the deliberate narrowness is pinned too, so nobody "tidies" this into
    # ``_is_local_db_host``: a compose stack on another machine on the LAN is not a scratch
    # database. This assertion is a POLICY, not an oversight — see the docstring in conftest.
    assert not conftest.is_local_dsn("postgresql://postgres:postgres@192.168.1.40:5432/design_workshop")


def test_needs_db_is_a_skip_marker_that_agrees_with_the_resolved_gate():
    """The shared marker modules are meant to migrate onto, rather than re-deriving the gate."""
    marker = conftest.needs_db.mark
    assert marker.name == "skipif"
    assert marker.args == (not conftest.HAS_LOCAL_DATABASE,)
    assert "LOCAL database" in marker.kwargs["reason"]


def test_the_refusal_cannot_be_swallowed_by_the_layers_that_catch_exception():
    """``RemoteDatabaseRefused`` must reach pytest, not a retry loop.

    This is the assertion that makes the guard worth having. Three separate layers between a
    fixture and ``Prisma.connect`` catch bare ``Exception`` and recover: ``connect_db`` retries six
    times with backoff, ``lifespan`` starts the app anyway, ``replica._build`` demotes and returns
    None. If the refusal were a ``RuntimeError`` every one of them would convert "this run may not
    write to that database" into "the database was briefly unavailable" — the same silent
    substitution the whole gate exists to stop, with the added insult of taking a minute to do it.
    So the class deriving from ``BaseException`` is load-bearing, and this pins it.
    """
    assert issubclass(conftest.RemoteDatabaseRefused, BaseException)
    assert not issubclass(conftest.RemoteDatabaseRefused, Exception)

    with pytest.raises(conftest.RemoteDatabaseRefused) as caught:
        try:
            conftest._refuse_remote_database_connection()
        except Exception as exc:  # noqa: BLE001 - the point of the test is that this cannot fire
            pytest.fail(f"a bare `except Exception` swallowed the refusal: {exc!r}")

    # The message has to be actionable and must NOT be a DSN: when this fires, the DSN is remote by
    # definition, so it holds a live password and pytest output goes into CI logs.
    message = str(caught.value)
    assert "needs_db" in message
    assert not conftest.DATABASE_URL or conftest.DATABASE_URL not in message


def test_the_refusal_is_armed_exactly_when_the_gate_is_closed():
    """A module's private substring predicate cannot outvote the parsed one any more.

    ``_arm_remote_database_refusal`` is idempotent and reports whether it took effect. What is
    asserted here is the POLARITY, which is the part that could be inverted by a careless edit and
    would then be invisible: on a machine with a local database nothing is patched (or the
    database half of the suite could not run at all), and on any other machine the connection is
    refused however a module gated itself.

    It returns False when ``prisma`` has never been imported, which is not a failure — a process
    that has not imported it cannot open a connection. That is why the assertion below is written
    against ``sys.modules`` rather than against the return value alone.
    """
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
