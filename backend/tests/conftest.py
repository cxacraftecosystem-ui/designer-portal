"""Three jobs, all of which have to happen before any test in this directory can do harm.

1. Put the backend root on ``sys.path`` so ``from app...`` resolves however pytest was invoked.
   The app is not pip-installed into the venv, so without this a bare ``pytest`` from any
   directory other than ``backend/`` cannot import the package under test.

2. RESOLVE THE DATABASE GATE ONCE, HERE, AND PUBLISH THE ANSWER. Twenty-eight test modules decide
   whether to skip themselves by reading ``os.environ.get("DATABASE_URL", "")`` at module scope,
   and until this file did it that read was correct only by accident — see the long note above
   ``resolve_database_url`` for the accident, which module already documented it, and why "the
   tests skipped" and "the tests were never written" look identical from the outside.

3. REFUSE THE CONNECTION OUTRIGHT WHEN THE GATE IS CLOSED, because job 2 is advice and those
   twenty-eight modules do not take it. Each of them still asks the old question — is the substring
   "localhost" anywhere in the DSN — which a password or a database name can answer yes. So
   ``is_local_dsn`` being right is not the same as the suite being safe: a module can decide to run
   against a remote database no matter what this file resolved. ``pytest_collection_finish`` makes
   that decision unenforceable by taking away the connection itself. Jobs 2 and 3 fail in opposite
   directions on purpose — 2 stops a machine with a database reporting green skips, 3 stops a
   machine without one writing to somebody else's rows — and 3 is the one that must never be
   removed to make a run go green.
"""

import os
import sys
from ipaddress import ip_address
from pathlib import Path
from urllib.parse import urlsplit

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

#: Whether the SHELL exported a DSN, captured before anything here could have loaded ``.env``.
#: Recorded rather than inferred because it is the difference between "the publish below is what
#: makes twenty-eight modules work" (a developer machine, where the DSN exists only in ``backend/.env``)
#: and "the publish below is a no-op" (CI, which exports a deliberately unusable placeholder). A test
#: reads it: without this flag, ``test_conftest_database_gate`` cannot tell a guard that is holding
#: from a guard that merely happens to agree with the environment.
DATABASE_URL_WAS_EXPORTED = bool(os.environ.get("DATABASE_URL"))


# --------------------------------------------------------------------------------------
# The database gate
# --------------------------------------------------------------------------------------


def resolve_database_url() -> str:
    """The DSN the app itself would connect with, not merely what is exported in this shell.

    ``os.environ["DATABASE_URL"]`` ALONE IS NOT ENOUGH, and reading it that way is how this guard
    first shipped silently disabled: ``backend/.env`` is loaded by :mod:`app.core.config` (and,
    separately, by the Prisma client's own dotenv load when ``app.core.db`` constructs it), so a
    module that computes its skip condition BEFORE anything has imported the settings sees an
    empty string, marks itself "no local database", and reports a screenful of green skips on a
    machine with Postgres running — tests that never ran, dressed as tests that passed.

    ``test_controlled_vocabularies`` fixed that for itself with a private ``_local_database_url``
    helper and blamed the ordering on one sibling module. It is not one sibling. Twenty-eight
    modules under ``backend/tests/`` compute their gate from ``os.environ`` at module scope, and
    every one of them is correct today only because its own ``from app.core.db import db`` (or
    another ``app.…`` import that reaches it) happens to sit ABOVE the check and populate
    ``os.environ`` as a side effect. Reordering imports in any of them — the most innocent edit
    there is, and one an autoformatter will do unasked — turns that module's database tests into
    skips without turning anything red.

    A conftest is imported before every test module in its directory, so doing the resolution here
    and writing the answer back into ``os.environ`` makes those twenty-eight reads deterministic
    regardless of import order, without touching twenty-eight files. Modules should still migrate to
    the ``needs_db`` marker below — asking the environment at all is the smell — but until they do,
    the ordering no longer decides anything.

    NEITHER FALLBACK IS COSMETIC; both are paths something real takes.

    * ``os.environ`` — in CI there is no ``.env`` at all, so ``Settings()`` refuses to build
      (``DATABASE_URL``, ``JWT_SECRET``, the three AWS values and ``MASTER_ADMIN_EMAIL`` are all
      required fields). That is the branch CI takes, and it must answer "whatever the environment
      says" rather than raise.
    * ``backend/.env``, read here by absolute path — because the loaders above were BOTH
      CWD-RELATIVE, so ``pytest backend/tests`` typed at the repository root found neither, and the
      gate then reported "no local database" on a laptop that has one. That is the same
      green-skip-that-looks-like-a-pass this whole function exists to stop, arrived at from a
      different direction, and it is not hypothetical — it is what the first version of this code
      did when it was run from anywhere but ``backend/``.

      HALF OF THAT IS NOW FIXED AT THE SOURCE, 2026-08-23: ``Settings`` anchors its ``env_file`` on
      an absolute path (see the header of ``app/core/config.py``), so the first branch above answers
      correctly from any directory. This fallback stays all the same, because the OTHER loader —
      the Prisma client's own dotenv, which walks up from the current directory — still is not
      fixed, and because a run where ``Settings`` cannot be built at all (a missing JWT_SECRET, say)
      must still be able to tell a local database from a remote one before it decides to write to it.

    Only DATABASE_URL is read out of the file, and nothing else about that invocation is repaired:
    a suite run from the wrong directory still cannot build ``Settings`` and still fails to import
    the modules that need it. The point is narrower — that when this file cannot answer the
    question it says so, instead of answering "no".
    """
    try:
        from app.core.config import get_settings

        resolved = str(get_settings().database_url or "")
        if resolved:
            return resolved
    except Exception:  # noqa: BLE001 - no settings at all is not an error here, just less evidence
        pass
    exported = os.environ.get("DATABASE_URL", "")
    if exported:
        return exported
    return _dotenv_database_url()


def _dotenv_database_url() -> str:
    """``DATABASE_URL`` as written in ``backend/.env``, found by absolute path rather than by CWD.

    Deliberately a hand parse rather than a dotenv dependency: this runs before any test
    module and must not be able to fail. Quotes are stripped because ``.env`` files are written
    both ways and a DSN wrapped in quotation marks would not match ``127.0.0.1`` any more. And the
    LAST assignment wins, not the first, because that is what python-dotenv (which the Prisma
    client loads through) and pydantic-settings both do: on the ``.env`` this project keeps
    running into — a deployed DSN with a compose DSN commented in or out below it — taking the
    first match would make this file resolve a DIFFERENT database from the one the app connects
    to, then publish that answer into ``os.environ`` and announce it in ``HAS_LOCAL_DATABASE``.
    That is the same silent disagreement between the tests and the app that the whole gate exists
    to stop, reached from a third direction. Latent rather than live today: ``backend/.env`` and
    ``backend/.env.example`` each declare ``DATABASE_URL`` exactly once.
    """
    dotenv = BACKEND_ROOT / ".env"
    try:
        lines = dotenv.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return ""
    found = ""
    for raw in lines:
        line = raw.strip()
        if line.startswith("DATABASE_URL="):
            found = line.split("=", 1)[1].strip().strip("'\"")
    return found


#: The DSN this run would talk to, resolved once, before any test module has been imported.
DATABASE_URL = resolve_database_url()

# Publish it, so the twenty-eight modules that still read ``os.environ`` at module scope get the same
# answer this file did whatever order their imports are in. NEVER an unconditional assignment: an
# explicitly exported DATABASE_URL outranks ``.env`` for pydantic-settings too, so overwriting here
# would make the tests disagree with the app about which database they are pointed at.
#
# But the test is "does the environment already hold a USABLE DSN", not ``os.environ.setdefault``,
# which is what this line said first and which is wrong in a way that reinstates the exact bug the
# file exists to stop. ``setdefault`` looks at key PRESENCE, so a shell that exports DATABASE_URL as
# an EMPTY STRING — `export DATABASE_URL=` to "clear" it, a compose env file with a bare
# `DATABASE_URL=` line, a CI step that blanks a job-level value because YAML cannot unset one —
# leaves the key present, the publish a no-op, and all twenty-eight gated modules reading "" at module
# scope while this file has the ``.env`` DSN in hand. Found by running
# ``DATABASE_URL="" pytest backend/tests/test_conftest_database_gate.py`` from the repository root:
# the conftest resolved the loopback DSN and a test module still saw ''. An empty export is not a
# DSN anybody is pointed at, so it cannot be the thing that outranks ``.env``.
#
# One cosmetic difference to know about before it surprises somebody comparing strings: when the
# value came from Settings it has been through ``_with_explicit_sslmode``, which appends
# ``sslmode=require`` to a REMOTE DSN. A local one is returned untouched — that function leaves
# loopback and private hosts alone precisely so docker-compose Postgres, which serves no
# certificate, keeps working — so the DSN this publishes on a developer machine is verbatim. The
# augmented form can only appear for a host the gate below is about to refuse anyway.
if DATABASE_URL and not os.environ.get("DATABASE_URL"):
    os.environ["DATABASE_URL"] = DATABASE_URL


def is_local_dsn(url: str) -> bool:
    """True only for a database on this machine.

    REMOTE IS DELIBERATELY NOT ENOUGH, and this is the rule the twenty-eight modules each spell out
    by hand today. These tests create users, workshops and stage entries and delete them again;
    the DSN in a deployed environment points at the ministry's real data. "Needs a database" and
    "may write to THIS database" are different questions and only the second one is safe to answer
    yes to by default — so an unreachable placeholder, a managed database and an empty string all
    read the same way here: no.

    IT DECIDES BY SHAPE AND IT FAILS CLOSED. It asks one question of the parsed HOST — is this
    loopback? — and every other answer, including "I could not parse that", is remote. That is the
    property that has to survive a change of database provider: a guard written the other way
    round, listing the hostnames it knows to be dangerous, is a guard that stops guarding on the
    day the deployment moves, and it stops guarding SILENTLY, by letting destructive fixtures run.
    Nothing here may ever name a vendor.

    IT USED TO BE A SUBSTRING TEST over the whole DSN — ``"localhost" in url or "127.0.0.1" in
    url`` — matching what those twenty-eight modules still do character for character. That is
    fail-OPEN in one direction nobody looks at: the substring can be satisfied by any part of the
    string, so ``postgresql://api:localhost@db.example.net/app`` (a password, a database name, a
    query parameter) reads as this machine's scratch database and the suite starts writing to a
    remote one. Parsing costs a line and removes that class of mistake FOR EVERY CONSUMER OF THIS
    PREDICATE — which is not the same as removing it from the suite, and the difference matters
    enough to have its own section below: the modules that derive their own gate are not consumers,
    so parsing here cannot fix them. ``pytest_collection_finish`` is what covers them.

    STILL DELIBERATELY LOOPBACK-ONLY. Widening it to ``_is_local_db_host``'s notion of private,
    which admits 10.x and 192.168.x, would be a real change of policy: somebody's compose stack on
    another machine on the LAN is not this machine's scratch database. The two predicates answer
    different questions — that one asks "may this link go unencrypted?", this one asks "may I
    delete rows here?" — and they are allowed to disagree. Do not "tidy" them together.
    """
    try:
        host = (urlsplit(url).hostname or "").strip().strip("[]").lower()
    except ValueError:  # a DSN this malformed is not one anybody is safely pointed at
        return False
    if not host:
        return False
    if host == "localhost" or host.endswith(".localhost"):
        return True
    try:
        return ip_address(host).is_loopback
    except ValueError:  # a real DNS name, and not one of ours -> remote
        return False


#: Whether this run may create and destroy rows.
HAS_LOCAL_DATABASE = is_local_dsn(DATABASE_URL)

#: Mark a test (or a module, via ``pytestmark``) that cannot run without Postgres.
#: Prefer this over a hand-rolled ``os.environ`` read: ``from conftest import needs_db``.
needs_db = pytest.mark.skipif(
    not HAS_LOCAL_DATABASE,
    reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
)


# --------------------------------------------------------------------------------------
# The fail-closed half: a module's private predicate must not be able to outvote this one
# --------------------------------------------------------------------------------------
#
# ``is_local_dsn`` above only decides for code that ASKS IT, and almost nothing does yet. Measured
# on this tree, ``grep -l 'any(host in _URL for host in ("localhost", "127.0.0.1"))' tests/*.py``
# lists 27 modules, and ``test_controlled_vocabularies`` makes 28 with the same test spelled through
# a private ``_local_database_url()`` helper. Every one of them computes the OLD substring gate for
# itself. So given ``DATABASE_URL=postgresql://api:localhost@db.example.net:5432/app``,
# ``HAS_LOCAL_DATABASE`` is correctly False while all 28 modules evaluate True and run their
# destructive fixtures — ``test_stage_sync`` alone issues four ``client.delete(...)`` calls — against
# db.example.net. Fixing the shared predicate did nothing for them; it could not.
#
# The proper fix is 28 one-line edits to ``from conftest import needs_db``, and it is still the fix.
# Those files belong to other units and are not this one's to rewrite, and a repair that has to
# touch 28 files is a repair that lands in pieces — so this is the floor underneath it: whatever a
# module decided about itself, an actual connection to a database this run may not write to is
# REFUSED. It stays useful after the migration too, as the thing that catches the twenty-ninth
# module somebody writes by copying one of the others.
#
# WHY THE CHOKEPOINT IS ``Prisma.connect`` AND NOT A FIXTURE. There is no list of "db-touching
# fixtures" to guard: these modules reach the database through a FastAPI TestClient, through
# module-scope helpers and through app startup, and a guard keyed on fixture names would have to
# know all of them and would go quietly out of date. Opening the connection is the one thing every
# route must pass through, it is where the harm begins, and it is a single attribute.


class RemoteDatabaseRefused(BaseException):
    """Raised in place of opening a database connection this run is not allowed to write to.

    DELIBERATELY NOT AN ``Exception``, and this is the whole reason the class exists rather than a
    ``RuntimeError``. Every layer between a fixture and ``Prisma.connect`` catches ``Exception`` and
    recovers by design: ``app.core.db.connect_db`` retries six times with backoff,
    ``app.main.lifespan`` logs a failed initial connect and starts the app anyway,
    ``app.scale.replica`` demotes the replica and carries on. A refusal any of those could swallow
    would be re-reported as "the database was briefly unavailable" — and two of them would make it
    slow as well as misleading. Deriving from ``BaseException`` walks it straight out to pytest,
    which is the only reader who should ever see it.

    ONE COSMETIC LIMIT, MEASURED, SO NOBODY DEBUGS IT TWICE. Verified 2026-08-23 by running
    ``DATABASE_URL=postgresql://api:localhost@db.example.net:5432/app pytest
    tests/test_craft_rename_conflict.py`` — a module whose private substring gate says LOCAL about
    that DSN, so it ran rather than skipping. The FIRST test's error carries this whole message and
    names the host, which is the report that matters, and the run exits 1 with no row created or
    deleted anywhere. The module's other two tests report a bare ``AssertionError`` from
    ``pytest_asyncio/plugin.py`` instead: their MODULE-scoped fixture already failed, and pytest's
    cached-failure replay does not re-raise a ``BaseException`` cleanly. That is a diagnostic wart in
    the cascade, not a hole in the guard — nothing connected — and it is not worth trading away
    ``BaseException`` for, because the three ``except Exception`` layers above are the thing that
    would swallow the refusal entirely. Read the first error in the run; the rest are echoes.
    """


def _refused_host() -> str:
    """The HOST of the resolved DSN, and nothing else of it.

    The refusal message has to name something or it is unactionable, and it must not name the DSN:
    when this guard fires the DSN is by definition a remote one, which means a live password, and a
    pytest failure ends up in a CI log that is far more widely readable than the secret store it
    came from. The host is the whole of what the reader needs — it is the fact the predicate
    disagreed about.
    """
    try:
        return urlsplit(DATABASE_URL).hostname or "(no host)"
    except ValueError:
        return "(unparseable)"


def _refuse_remote_database_connection(*_args: object, **_kwargs: object) -> None:
    """Stand in for ``Prisma.connect`` while the resolved DSN is not a database on this machine."""
    raise RemoteDatabaseRefused(
        "refusing to open a database connection: DATABASE_URL resolves to host "
        f"{_refused_host()!r}, which is not a database on this machine, and these tests create and "
        "delete rows. If this test was meant to skip, its own skip predicate disagrees with "
        "conftest.is_local_dsn — replace it with `from conftest import needs_db`."
    )


def _arm_remote_database_refusal() -> bool:
    """Point ``prisma.Prisma.connect`` at the refusal above. Idempotent; returns whether it is armed.

    DELIBERATELY DOES NOT IMPORT ``prisma``, only looks for it in ``sys.modules``. Importing it
    costs 108 seconds on this machine (measured, and recorded in the docstring of
    ``test_conftest_database_gate``), which would be added to every run of every non-database test
    in CI — where the gate is closed and this function therefore does its work. A cost like that is
    how a guard comes to be deleted. And it loses nothing: a process that has not imported
    ``prisma`` cannot open a connection, so there is nothing to refuse yet, and every module that
    can open one imports ``app.core.db`` at module scope, which is during collection.
    """
    prisma_module = sys.modules.get("prisma")
    client_class = getattr(prisma_module, "Prisma", None)
    if client_class is None:
        return False
    if getattr(client_class.connect, "__name__", "") != "_refuse_remote_database_connection":
        client_class.connect = _refuse_remote_database_connection
    return True


def pytest_collection_finish(session: pytest.Session) -> None:
    """Arm the refusal once, after every test module is imported and before any test runs.

    THIS HOOK AND NOT A FIXTURE, because of ordering. pytest sets higher-scoped fixtures up first,
    so a module with a session- or module-scoped fixture that connects would connect BEFORE any
    autouse function-scoped guard could run — precisely the fixtures worth stopping. Collection
    finishing is the last moment that is unambiguously before all of them.
    """
    if HAS_LOCAL_DATABASE:
        return
    _arm_remote_database_refusal()


@pytest.fixture(autouse=True)
def _remote_database_refusal_stays_armed() -> None:
    """Re-arm before each test, for the case ``pytest_collection_finish`` could not cover.

    A module that imports ``prisma`` lazily — inside a test body or a fixture rather than at module
    scope — is not in ``sys.modules`` when the hook above runs, and the hook does not import it (see
    ``_arm_remote_database_refusal`` for why not). This costs a dict lookup and an attribute
    comparison per test and closes that gap. It is the belt, not the braces: the hook is what
    catches a session-scoped fixture, and this cannot.
    """
    if not HAS_LOCAL_DATABASE:
        _arm_remote_database_refusal()


def _gate_sentence() -> str:
    """The one sentence both reporting hooks below print. Written once so they cannot drift apart."""
    if HAS_LOCAL_DATABASE:
        return "database: local DSN resolved — database-backed tests WILL run"
    if DATABASE_URL:
        return "database: DSN is not local — database-backed tests will SKIP (by design)"
    return "database: none configured — database-backed tests will SKIP (by design)"


def pytest_report_header() -> str:
    """Say out loud, at the top of a run, whether the database-backed tests can run.

    This is the whole difference between a suite that skipped its Craft round trip and a suite that
    never had one. CI has no ``DATABASE_URL`` pointing anywhere local and is MEANT not to — the
    deployed database is not a scratch pad — so the skips there are correct and permanent, and the
    log should say so in a sentence rather than leaving the next reader to infer it from a count.

    THIS HOOK IS NOT ENOUGH ON ITS OWN, and the first version of this file claimed it was. Under
    ``-q`` pytest drops verbosity to -1 and ``TerminalReporter.pytest_sessionstart`` returns before
    ``_write_report_lines_from_hooks``, so NOTHING a ``pytest_report_header`` returns is printed at
    all. Measured 2026-08-20 on this tree: ``pytest tests/test_conftest_database_gate.py -rf
    --durations=15`` prints the ``database:`` sentence TWICE — once here, once from
    ``pytest_terminal_summary`` below — and the identical command with ``-q`` prints it ONCE, the
    surviving copy being the one below. Before that second hook existed, ``-q`` left the log as dots
    and a bare skip count, which is precisely the number this sentence was written to replace; and
    the CI step the sentence was written for ran with ``-q``, so for its whole life it printed
    nothing. Keep both hooks. This one is what a developer reading a local run sees first, at the
    top, before the wait, and the count of TWO is what the "Run the suite" step in
    .github/workflows/checks.yml greps for — one hook deleted, or ``-q`` quietly re-added, and the
    count falls to one and the step goes red.
    """
    return _gate_sentence()


def pytest_terminal_summary(terminalreporter) -> None:
    """The same sentence, plus a skip tally, at the FOOT of the run — where ``-q`` cannot swallow it.

    ``terminalreporter.write_line`` is unconditional, so unlike ``pytest_report_header`` this
    prints at every verbosity including ``-q``. That matters because the CI log is read from the
    bottom, and because the guard has to survive somebody re-adding ``-q`` to the pytest invocation
    for log volume — which is exactly how the sentence went missing the first time.

    The per-reason tally is the second half of the same argument. ``-rs`` would also print skip
    reasons, but one line per skip LOCATION — measured 2026-08-20 under the CI environment, 360
    near-identical lines carrying 381 skips, a wall rather than a fact. Grouping them says what a
    reader needs in one line per reason: not "381 skipped" but "381 skipped because this run has no
    LOCAL database", so a green skip stays distinguishable from a test that was never written. If
    that count ever collapses toward zero without the sentence above changing, a gate has been
    inverted.

    "AT THE FOOT" IS ONLY TRUE WHILE NOBODY ASKS PYTEST FOR THE SAME THING, and the ordering is the
    opposite way round from what you would guess. Pluggy calls the LAST-registered
    ``pytest_terminal_summary`` first, and a conftest is registered after pytest's own
    TerminalReporter — so everything a ``-r`` flag summarises prints BELOW this, not above it. Add
    ``s`` to the CI flag and the sentence and the tally end up sitting on top of that wall: measured
    2026-08-20, the 360 SKIPPED lines run unbroken from the summary section down to the final status
    line and are over half the log, in a file that is read from the bottom. No absolute line number is
    given, deliberately — this paragraph carried one ("line 209 of a 595-line log"), and the same
    command re-measured the same day put it at 245 of 633, because every test added and every
    failure traceback shifts it. That is why the "Run the suite" step in
    .github/workflows/checks.yml spells the flag ``-rf`` and says so; the two decisions are one
    decision and should not be changed apart.
    """
    terminalreporter.write_line("")
    terminalreporter.write_line(_gate_sentence())

    skipped = terminalreporter.stats.get("skipped", [])
    if not skipped:
        return

    # ``longrepr`` on a skip report is the ``(file, lineno, reason)`` triple pytest itself formats
    # for ``-rs``; anything else (a collect-time skip, a future pytest) falls back to ``str`` rather
    # than raising. A reporting hook that can throw would turn a passing suite red for no reason.
    tally: dict[str, int] = {}
    for report in skipped:
        longrepr = getattr(report, "longrepr", None)
        if isinstance(longrepr, tuple) and len(longrepr) == 3:
            reason = str(longrepr[2])
        else:
            reason = str(longrepr)
        reason = reason.removeprefix("Skipped: ").strip() or "(no reason given)"
        tally[reason] = tally.get(reason, 0) + 1

    terminalreporter.write_line(f"skipped {len(skipped)} tests, by reason:")
    for reason, count in sorted(tally.items(), key=lambda item: (-item[1], item[0])):
        terminalreporter.write_line(f"  {count:>5} × {reason}")
