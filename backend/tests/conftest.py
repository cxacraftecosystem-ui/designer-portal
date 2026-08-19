"""Two jobs, both of which have to happen before any test module is imported.

1. Put the backend root on ``sys.path`` so ``from app...`` resolves however pytest was invoked.
   The app is not pip-installed into the venv, so without this a bare ``pytest`` from any
   directory other than ``backend/`` cannot import the package under test.

2. RESOLVE THE DATABASE GATE ONCE, HERE, AND PUBLISH THE ANSWER. Twenty-six test modules decide
   whether to skip themselves by reading ``os.environ.get("DATABASE_URL", "")`` at module scope,
   and until this file did it that read was correct only by accident — see the long note above
   ``resolve_database_url`` for the accident, which module already documented it, and why "the
   tests skipped" and "the tests were never written" look identical from the outside.
"""

import os
import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

#: Whether the SHELL exported a DSN, captured before anything here could have loaded ``.env``.
#: Recorded rather than inferred because it is the difference between "the publish below is what
#: makes twenty-six modules work" (a developer machine, where the DSN exists only in ``backend/.env``)
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
    helper and blamed the ordering on one sibling module. It is not one sibling. Twenty-six
    modules under ``backend/tests/`` compute their gate from ``os.environ`` at module scope, and
    every one of them is correct today only because its own ``from app.core.db import db`` (or
    another ``app.…`` import that reaches it) happens to sit ABOVE the check and populate
    ``os.environ`` as a side effect. Reordering imports in any of them — the most innocent edit
    there is, and one an autoformatter will do unasked — turns that module's database tests into
    skips without turning anything red.

    A conftest is imported before every test module in its directory, so doing the resolution here
    and writing the answer back into ``os.environ`` makes those twenty-six reads deterministic
    regardless of import order, without touching twenty-six files. Modules should still migrate to
    the ``needs_db`` marker below — asking the environment at all is the smell — but until they do,
    the ordering no longer decides anything.

    NEITHER FALLBACK IS COSMETIC; both are paths something real takes.

    * ``os.environ`` — in CI there is no ``.env`` at all, so ``Settings()`` refuses to build
      (``DATABASE_URL``, ``JWT_SECRET``, the three AWS values and ``MASTER_ADMIN_EMAIL`` are all
      required fields). That is the branch CI takes, and it must answer "whatever the environment
      says" rather than raise.
    * ``backend/.env``, read here by absolute path — because BOTH of the loaders above are
      CWD-RELATIVE. ``Settings`` declares ``env_file=".env"`` and Prisma's dotenv walks up from the
      current directory, so ``pytest backend/tests`` typed at the repository root finds neither,
      and the gate would then report "no local database" on a laptop that has one. That is the
      same green-skip-that-looks-like-a-pass this whole function exists to stop, arrived at from a
      different direction, and it is not hypothetical — it is what the first version of this code
      did when it was run from anywhere but ``backend/``.

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
    running into — a Supabase DSN with a compose DSN commented in or out below it — taking the
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

# Publish it, so the twenty-six modules that still read ``os.environ`` at module scope get the same
# answer this file did whatever order their imports are in. NEVER an unconditional assignment: an
# explicitly exported DATABASE_URL outranks ``.env`` for pydantic-settings too, so overwriting here
# would make the tests disagree with the app about which database they are pointed at.
#
# But the test is "does the environment already hold a USABLE DSN", not ``os.environ.setdefault``,
# which is what this line said first and which is wrong in a way that reinstates the exact bug the
# file exists to stop. ``setdefault`` looks at key PRESENCE, so a shell that exports DATABASE_URL as
# an EMPTY STRING — `export DATABASE_URL=` to "clear" it, a compose env file with a bare
# `DATABASE_URL=` line, a CI step that blanks a job-level value because YAML cannot unset one —
# leaves the key present, the publish a no-op, and all twenty-six gated modules reading "" at module
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

    REMOTE IS DELIBERATELY NOT ENOUGH, and this is the rule the twenty-six modules each spell out
    by hand today. These tests create users, workshops and stage entries and delete them again;
    the DSN in a deployed environment points at the ministry's real data. "Needs a database" and
    "may write to THIS database" are different questions and only the second one is safe to answer
    yes to by default — so an unreachable placeholder, a Supabase pooler and an empty string all
    read the same way here: no.

    A substring test rather than a URL parse, matching what those modules do character for
    character. Widening it (to `_is_local_db_host`'s notion of private, say, which admits 10.x and
    192.168.x) would be a real change of policy: somebody's compose stack on another machine on
    the LAN is not this machine's scratch database. Do not "tidy" it into that.
    """
    return any(host in url for host in ("localhost", "127.0.0.1"))


#: Whether this run may create and destroy rows.
HAS_LOCAL_DATABASE = is_local_dsn(DATABASE_URL)

#: Mark a test (or a module, via ``pytestmark``) that cannot run without Postgres.
#: Prefer this over a hand-rolled ``os.environ`` read: ``from conftest import needs_db``.
needs_db = pytest.mark.skipif(
    not HAS_LOCAL_DATABASE,
    reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
)


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
    all. Measured on this tree: ``pytest tests/test_conftest_database_gate.py`` prints the sentence,
    ``pytest tests/test_conftest_database_gate.py -q`` prints dots and a count and nothing else —
    which is precisely the bare skip count this sentence was written to replace. The CI step that
    the sentence was written for ran with ``-q``, so for its whole life it printed nothing.
    ``pytest_terminal_summary`` below is the copy that survives any verbosity; keep both, because
    this one is what a developer reading a local run sees first, at the top, before the wait.
    """
    return _gate_sentence()


def pytest_terminal_summary(terminalreporter) -> None:
    """The same sentence, plus a skip tally, at the FOOT of the run — where ``-q`` cannot swallow it.

    ``terminalreporter.write_line`` is unconditional, so unlike ``pytest_report_header`` this
    prints at every verbosity including ``-q``. That matters because the CI log is read from the
    bottom, and because the guard has to survive somebody re-adding ``-q`` to the pytest invocation
    for log volume — which is exactly how the sentence went missing the first time.

    The per-reason tally is the second half of the same argument. ``-rs`` would also print skip
    reasons, but as one line per skipped test — roughly four hundred identical lines in CI, which
    is a wall rather than a fact. Grouping them says the thing a reader needs in one line per
    reason: not "381 skipped" but "381 skipped because this run has no LOCAL database", so a green
    skip stays distinguishable from a test that was never written. If that count ever collapses
    toward zero without the sentence above changing, a gate has been inverted.
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
