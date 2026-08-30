"""Find the roster rows two spellings of one Gmail mailbox have collided on. IT NEVER MERGES THEM.

WHY THIS EXISTS. ``app/services/designers.canonical_email`` made both rosters read one Gmail mailbox
under every spelling Google delivers to it — dots, ``+tags`` and ``googlemail.com`` folded together —
because ``normalise_email`` was only ``.strip().lower()``, so ``sandy.craft3@gmail.com`` was a
different key from ``sandycraft3@gmail.com``: one person, two rows, and a row on the admin screen
that looked correct and could never match. New rows are written under the canonical form, so the
tables cannot grow another pair. ``IMPLEMENTATION_PLAN.md`` §1 Fix 2 step 4 is this script.

WHAT THAT DID NOT DO IS TIDY THE PAIRS ALREADY THERE, AND TWO OF THOSE SHAPES ARE LIVE DEFECTS.

  1. **A pair that disagrees refuses somebody right now.** Where one spelling carries an active
     empanelment and the other an administrator's revocation, the gate answers with the refusal.
     That direction is deliberate and is argued at ``access_roster._the_row_that_decides``:
     admitting somebody an admin revoked is silent and permanent, while refusing somebody entitled
     to be here is visible and fixed in five minutes on the screen showing both rows. But it is
     only fixed in five minutes BY SOMEBODY WHO KNOWS THE PAIR EXISTS, and nothing on either screen
     groups the two rows or hints that one is deciding the other.
  2. **A lone row stored under an alias still locks its own person out — in one direction.** This
     is the half it is easy to get wrong, and an earlier revision of this script called these rows
     "FINE". They are not. ``email_match_keys`` puts the LITERAL spelling first and adds the
     canonical one, so a person signing in as ``sandy.craft3@`` matches a row stored either way.
     A person whose Google address is the DOTLESS ``sandycraft3@`` produces exactly one key — the
     canonical form is the literal form — and that key does not match a row stored as
     ``sandy.craft3@``. Which is the reported outage, still open, for every alias-stored row whose
     owner signs in with the plain spelling.

**IT NEVER MERGES ANYTHING, AND ``--fix`` CANNOT BE MADE TO.** Two rows that canonicalise to one
address are two records somebody made: different notes, different ``addedById``, different joining
dates, and sometimes a deliberate revocation on one of them. Merging them in a script means choosing
which admin's record survives, and getting that wrong destroys the only written trace of a decision
— including, in the worst case, the revocation itself, which is the one row in this product that
must never be undone by a machine. So collisions are PRINTED, in the order somebody should work
through them, with every field a human needs to choose between the rows, and the fix is an admin's
act on ``/admin/access`` or ``/admin/designers`` where it is recorded as one.

WHAT ``--fix`` DOES DO is defect 2 and only defect 2: a canonical group holding **exactly one** row
has nothing to choose between, so its ``email`` is rewritten to the canonical key. No row is
created, none is deleted, no status is touched, and the rewrite is a strict WIDENING of who that row
matches — before it, the row answered to one spelling; after it, to both. That it is nonetheless an
access change is the whole reason it sits behind a flag.

**AND IT REFUSES THE REWRITE WHERE A ``User`` ROW IS STORED UNDER THE OLD SPELLING**, which is not
caution, it is a fail-open bug avoided by exactly one query. Two services still compare
``User.email`` to a roster email on the LITERAL form, because their callers hold user rows rather
than an address:

  * ``access_roster.barred_among`` (via ``design_workshop_viewers.set_viewers`` and
    ``design_workshop_inspectors``) asks which of these accounts the allow-list bars. Canonicalise a
    REJECTED or SUSPENDED allow-list row whose account is spelled the old way and that account stops
    being found — **a barred person silently becomes eligible for a workshop viewer row.**
  * ``design_workshop_viewers.active_roster_emails`` asks which accounts the designer roster still
    admits. Canonicalise an ACTIVE empanelment whose account is spelled the old way and that
    designer is refused a viewer row with "not on the ACTIVE designer roster" while
    ``/admin/designers`` shows them plainly active — the wrong refusal, aimed at the wrong screen.

Both are one bug — a rewrite that moves one side of a comparison the other side does not follow —
and both are reported under NOT REWRITTEN with the account's stored spelling printed beside the row,
because the remedy is to settle the account's address first and neither service is this script's to
change. The residual it does NOT cover is stated rather than hidden: an admin who afterwards
hand-creates a ``User`` at the old spelling through ``POST /api/users`` reintroduces the same
mismatch, and nothing here can see the future.

**EXIT CODE, so this is usable as a pre-deploy gate:**

    0   no collisions, and every table was read in full.
    1   at least one canonical group holds more than one row. Somebody has to decide.
    2   the answer is not known — a read hit its cap, so a clean report would only mean "clean in
        the part I looked at". Deliberately non-zero: a gate that goes green on a truncated read is
        worse than no gate, because it is believed.

A ``--fix`` run exits the same way. ``--fix`` never merges, so it never clears a collision, and the
gate stays red until a person has settled every one of them. **A read that hit its cap also revokes
``--fix`` for that run** — "alone in its canonical group" is a claim about rows this run has seen,
and a twin past the cut turns the row it was about to rewrite into a collision.

Usage (from the repository root):

    python scripts/backfill_email_canonicalisation.py           # REPORT ONLY — writes nothing
    python scripts/backfill_email_canonicalisation.py --fix     # also canonicalise the lone rows

``DATABASE_URL`` comes from ``backend/.env`` the same way the application reads it.

**ON WINDOWS**, run it with UTF-8 mode on — ``set PYTHONUTF8=1`` in the shell, or
``py -X utf8 scripts/backfill_email_canonicalisation.py``. Prisma's own config loader opens
``backend/pyproject.toml`` with the interpreter's default codec, that file contains a rupee sign and
several em dashes, and on a cp1252 box the read dies inside ``db.connect()`` with a
``UnicodeDecodeError`` naming a byte offset in a file this script never mentions — which reads as a
corrupt install rather than as a locale setting. :func:`_connect` catches exactly that and says so.
"""

import argparse
import asyncio
import pathlib
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.services import access_roster  # noqa: E402
from app.services.designers import canonical_email, normalise_email  # noqa: E402

#: A ceiling on each table read, so a script pointed at a production database cannot pull an
#: unbounded set into memory. ``access_roster.BARRED_EMAIL_READ_LIMIT``'s number and its reasoning:
#: far above any plausible programme, and a run that hits it says so rather than quietly reporting a
#: clean bill of health from half the table — which, for THIS script, is the failure that matters,
#: because the rows it did not read are exactly the ones nobody else will go looking for either.
#: Hitting it is also what turns the exit code into 2; see the module docstring.
READ_LIMIT = 50_000

#: The width of the section rules below. A constant because the headings share it and a report whose
#: rules are three different lengths reads as three different reports.
RULE = "=" * 98

# The three answers this script gives its caller, named rather than written as bare integers at four
# return sites. A pre-deploy gate is read by a shell script somebody else writes, so the meaning of
# each number is part of this file's contract and belongs where it can be pointed at.
EXIT_CLEAN = 0
EXIT_COLLISIONS = 1
EXIT_UNKNOWN = 2


def _console_that_cannot_kill_the_run() -> None:
    """Make an unencodable character degrade to ``?`` instead of ending the run.

    THE REPORT BELOW IS FULL OF EM DASHES AND ARROWS, and on Windows ``sys.stdout`` falls back to
    the ANSI codepage the moment it is redirected — and a pre-deploy gate is redirected by
    definition, into a log somebody reads afterwards. U+2014 survives cp1252; the ``->`` below is
    ASCII for that reason, but the failure this guards is not a mangled character, it is a
    ``UnicodeEncodeError`` raised out of ``print`` PART-WAY THROUGH THE REPORT. A gate that dies
    mid-list exits non-zero for the wrong reason, and the operator reads a traceback where the
    collision they were being warned about should have been.

    Reconfiguring the ERROR HANDLER and not the encoding is the deliberate half of this. Forcing
    UTF-8 onto a console still running codepage 437 replaces a crash with mojibake in an email
    address, which is worse here than a ``?``: every line of this report is an address somebody has
    to search for on an admin screen.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(errors="replace")


async def _connect() -> None:
    """``connect_db`` plus the one Windows failure that does not name itself.

    Prisma's config loader reads ``backend/pyproject.toml`` with the interpreter's default codec.
    That file carries a rupee sign and em dashes, so on a Windows box that has not been put into
    UTF-8 mode the connection dies with a ``UnicodeDecodeError`` about a byte position in a TOML
    file this script never mentions — which every reader takes for a broken install and none take
    for a locale setting. The remedy is one environment variable and it is printed here rather than
    left in a runbook, because the person seeing this is mid-deploy.

    It cannot be fixed from inside the process: ``PYTHONUTF8`` is read once at interpreter start-up,
    so setting it here would change nothing and re-launching the interpreter to apply it would mean
    a gate that silently spawns a second copy of itself.
    """
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: Prisma's config loader could not decode backend/pyproject.toml "
            f"with this interpreter's default codec ({exc.encoding}). This is a locale setting, "
            "not a broken install. Re-run with UTF-8 mode on:\n"
            "    set PYTHONUTF8=1  &&  python scripts/backfill_email_canonicalisation.py\n"
            "or:\n"
            "    py -X utf8 scripts/backfill_email_canonicalisation.py"
        ) from exc


async def _read(table: Any, label: str) -> tuple[list[Any], bool]:
    """Every row of one roster, and whether that read was cut short.

    The flag is RETURNED rather than only printed, because it decides the exit code. A gate whose
    answer came from half a table has not answered, and the one dishonest thing available here would
    be to report those rows as absent — which is indistinguishable, in the log, from a clean table.
    """
    rows = await table.find_many(take=READ_LIMIT + 1)
    truncated = len(rows) > READ_LIMIT
    if truncated:
        # LOUD, and phrased for what the cut actually costs. A truncated read here does not report a
        # smaller problem; it reports NO problem for every row past the cut.
        print(
            f"WARNING: {label} holds more than {READ_LIMIT} rows, so this run read only part of it "
            "and any collision past the cut is NOT listed below. Raise READ_LIMIT and run again "
            "before believing a clean report. Exit code 2 says the same thing to a script."
        )
    return rows[:READ_LIMIT], truncated


def _group_by_mailbox(rows: list[Any]) -> dict[str, list[Any]]:
    """Every row of ONE table, filed under the mailbox it reaches rather than the string it holds.

    **PER TABLE, NEVER ACROSS BOTH, and that is what makes "more than one member" mean "collision".**
    A person on the allow-list who is also empanelled has one row in each table for one mailbox, and
    that is the ordinary, correct state of every designer in the product — grouping the two tables
    together would report the entire roster as colliding with itself. The unique index that a
    canonicalising rewrite has to respect is per table too, so the grouping the report uses and the
    grouping the write is safe under are the same grouping.
    """
    grouped: dict[str, list[Any]] = {}
    for row in rows:
        address = normalise_email(getattr(row, "email", None))
        if address:
            grouped.setdefault(canonical_email(address), []).append(row)
    return grouped


def _designer_status(row: Any) -> str:
    """A ``DesignerRoster`` row's standing, in the column the report calls ``status``.

    THIS TABLE HAS NO ``status`` COLUMN — it has ``isActive`` and ``revokedAt``, which is the shape
    ``schema.prisma`` chose so that a suspension leaves the history of the empanelment behind
    instead of deleting it. The word is borrowed from the allow-list so the two halves of the report
    line up under one heading, and the revocation DATE is folded in because it is the first thing an
    operator needs when choosing between two rows for one person: the later revocation is almost
    always the decision that still stands.
    """
    if row.isActive:
        return "active"
    revoked = getattr(row, "revokedAt", None)
    return f"SUSPENDED since {revoked.date().isoformat()}" if revoked else "SUSPENDED"


def _describe(label: str, row: Any, status: str, width: int) -> str:
    """One row as the five fields a human needs to decide between it and its twin.

    ``id``, ``email``, ``status``, ``createdAt`` and ``addedById`` — the id to act on it, the email
    to see which spelling this is, the status to see what it does at the gate, the date to see which
    row came first, and the administrator to ask. Padded to a common width because the point of
    printing two rows together is that a reader compares them column by column, and ragged fields
    make that a reading exercise instead of a glance.

    ``email`` is printed with ``repr`` deliberately. The difference between the two rows in a
    colliding pair can be a single dot, and a bare address at the end of a sentence gives a reader
    nowhere to see it; quotes put a boundary on both ends of the string.
    """
    stored = normalise_email(getattr(row, "email", None))
    added = row.addedById or "(nobody recorded)"
    return (
        f"      {label:<15}  id={row.id}  {stored!r:<{width}}  {status:<26}  "
        f"created={row.createdAt.date().isoformat()}  addedBy={added}"
    )


def _designer_verdict(rows: list[Any]) -> str | None:
    """What a group of ``DesignerRoster`` rows does to the person at the gate, or None if nothing.

    ``roster_allows`` requires EVERY matched row to be active, so a group holding one revocation
    refuses the person no matter how many active rows sit beside it.
    """
    if len(rows) < 2:
        return None
    active = [row for row in rows if row.isActive]
    if active and len(active) < len(rows):
        return "REFUSING: a revocation and an active empanelment share this mailbox"
    if not active:
        return "duplicate revocations — no admission is being lost, but two rows record one act"
    return "duplicate active empanelments — one person appears twice on /admin/designers"


def _access_verdict(rows: list[Any]) -> str | None:
    """The same question for the allow-list, whose four states make it a little wider.

    ``access_row`` answers with the BARRED row where one exists, so a rejection or a suspension
    under either spelling decides the whole mailbox.
    """
    if len(rows) < 2:
        return None
    states = sorted({access_roster.status_of(row) for row in rows})
    barred = [state for state in states if state in access_roster.BARRED]
    others = [state for state in states if state not in access_roster.BARRED]
    if barred and others:
        return f"REFUSING: {'/'.join(barred)} shares this mailbox with {'/'.join(others)}"
    if barred:
        return "duplicate barred rows — nobody is wrongly refused, but two rows record one act"
    return f"duplicate rows ({'/'.join(states)}) — one person occupies two entries in the queue"


def _section(heading: list[str], blocks: list[str]) -> None:
    """One titled section, printed EVEN WHEN EMPTY.

    An operator runs this about a named person. If that person is absent from every section, that is
    an answer — and it is only an answer when the reader can see the section they are absent from
    was printed. A section suppressed for being empty is indistinguishable from a section the script
    forgot, and the guess a reader makes about a missing "COLLIDING" heading is the wrong one.
    """
    print("\n" + RULE)
    for line in heading:
        print(line)
    print(RULE)
    print("\n".join(blocks) if blocks else "  (none)")


async def _accounts_spelled_the_old_way(aliases: list[str]) -> dict[str, str]:
    """Which of these alias spellings a ``User`` row is actually stored under, and how it is cased.

    THE GUARD ON EVERY REWRITE, and the whole reason ``--fix`` needs a query of its own. See the
    module docstring for the two services this protects and the fail-open one of them has.

    **CASE-INSENSITIVE, BECAUSE ``User.email`` IS THE ONE ADDRESS COLUMN THIS SCHEMA DOES NOT
    LOWER-CASE.** ``schema.prisma`` says so on ``AccessRoster.email`` — "joined to ``User.email``,
    which is NOT lower-cased on that side — every comparison against it therefore lower-cases both".
    A plain ``in`` over lower-cased strings would miss an account stored as ``Sandy.Craft3@Gmail.com``
    and report the rewrite as safe, which is the exact failure this function exists to prevent,
    arrived at from the wrong end. The ``{"in": …, "mode": "insensitive"}`` shape is the one
    ``routes/designers.designer_directory`` already uses against this column.

    Returns a map from the lower-cased alias to the spelling the account is really stored under, so
    the report can print the account's own casing beside the row an admin is being asked about.
    """
    if not aliases:
        return {}
    rows = await db.user.find_many(where={"email": {"in": aliases, "mode": "insensitive"}})
    return {normalise_email(row.email): row.email for row in rows}


async def report(*, fix: bool) -> int:
    """Print everything worth a human's attention, optionally canonicalise the lone rows, and
    return the exit code the module docstring defines."""
    designer_rows, designer_cut = await _read(db.designerroster, "DesignerRoster")
    access_rows, access_cut = await _read(db.accessroster, "AccessRoster")
    designers = _group_by_mailbox(designer_rows)
    access = _group_by_mailbox(access_rows)

    # The widest stored address in either table, so both halves of a pair line up under one another
    # even when only one of them is long. Computed over everything rather than per group: a reader
    # comparing the COLLIDING section against the ALIAS section below it is comparing addresses, and
    # two sections indented differently look like two unrelated reports.
    width = max(
        (len(repr(normalise_email(row.email))) for row in designer_rows + access_rows),
        default=8,
    )

    # THE TABLE OBJECT TRAVELS WITH ITS LABEL rather than being picked back out of the label by a
    # string comparison further down. `--fix` writes through whatever this tuple hands it, and a
    # `label == "designer roster"` test would mean that re-wording one heading in this report
    # silently redirects every write to the OTHER roster — a typo whose only symptom is an
    # allow-list row's email appearing on the designer roster.
    tables = (
        ("designer roster", db.designerroster, designers, _designer_verdict, _designer_status),
        ("allow-list", db.accessroster, access, _access_verdict, access_roster.status_of),
    )

    refusing: list[str] = []
    duplicated: list[str] = []
    for mailbox in sorted(set(designers) | set(access)):
        lines: list[str] = []
        verdicts: list[str] = []
        for label, _table, grouped, verdict, standing in tables:
            group = grouped.get(mailbox, [])
            answer = verdict(group)
            if answer is None:
                continue
            verdicts.append(answer)
            lines.append(f"    {label}: {answer}")
            # OLDEST FIRST INSIDE A GROUP. The question an operator is answering is "which of these
            # did somebody mean", and the order the rows were made in is the first evidence for it.
            lines.extend(
                _describe(label, row, standing(row), width)
                for row in sorted(group, key=lambda row: row.createdAt)
            )
        if not lines:
            continue
        block = f"\n  {mailbox}\n" + "\n".join(lines)
        (refusing if any(a.startswith("REFUSING") for a in verdicts) else duplicated).append(block)

    # ORDERED WORST FIRST, because the operator running this by hand is usually running it about
    # somebody who cannot sign in, and the answer to that question is in the first section or
    # nowhere.
    _section(
        [
            "COLLIDING, AND REFUSING SOMEBODY RIGHT NOW — two rows for one mailbox disagree and the",
            "gate fails closed. Decide which record stands and act on it in the admin screen.",
            "NOTHING IN THIS SECTION IS EVER WRITTEN BY THIS SCRIPT, with or without --fix.",
        ],
        refusing,
    )
    _section(
        [
            "COLLIDING, BUT AGREEING — nobody is refused, yet one person holds two rows, so the next",
            "suspension can be applied to the wrong one and appear to do nothing. Also never",
            "written by this script.",
        ],
        duplicated,
    )

    # ---------------------------------------------------------------------------------------
    # The lone alias rows — the only thing --fix touches.
    # ---------------------------------------------------------------------------------------
    # A group of exactly one has nothing to choose between, so rewriting its key destroys no
    # record. It is also the only case where the rewrite is SAFE against the unique index: any row
    # already holding the canonical spelling would canonicalise to this same key and would therefore
    # be in this group, making it a collision instead. That is not a happy accident — it is why the
    # grouping is per table and why the size test is the write's precondition rather than a
    # separate lookup that could disagree with the report printed above.
    candidates: list[tuple[str, Any, Any, str]] = []
    for label, table, grouped, _verdict, standing in tables:
        for mailbox, group in grouped.items():
            if len(group) != 1:
                continue
            row = group[0]
            stored = normalise_email(row.email)
            if stored != mailbox:
                candidates.append((label, table, row, standing(row)))

    aliases = sorted({normalise_email(row.email) for _label, _table, row, _st in candidates})
    accounts = await _accounts_spelled_the_old_way(aliases) if candidates else {}

    rewritable: list[tuple[str, Any, Any, str]] = []
    blocked: list[str] = []
    for label, table, row, standing in candidates:
        stored = normalise_email(row.email)
        account = accounts.get(stored)
        if account is None:
            rewritable.append((label, table, row, standing))
            continue
        blocked.append(
            _describe(label, row, standing, width)
            + f"\n        NOT REWRITTEN: an account is stored as {account!r}. Settle the address on"
            + "\n        the account first — see this file's docstring for the two services that"
            + "\n        still compare User.email to a roster email on the literal spelling."
        )

    _section(
        [
            "STORED UNDER AN ALIAS, ALONE — one row, nothing to choose between, and the person whose",
            "Google address is the PLAIN spelling cannot sign in against it today. --fix rewrites",
            "the email to the canonical key, which widens the row to answer to both spellings.",
        ],
        [
            _describe(label, row, standing, width) + f"\n        -> {canonical_email(row.email)!r}"
            for label, _table, row, standing in rewritable
        ],
    )
    _section(
        [
            "ALIASED BUT NOT REWRITTEN — an account exists under the old spelling, so canonicalising",
            "the roster row alone would move one side of a comparison the other side does not",
            "follow. Reported for a human; --fix skips these deliberately.",
        ],
        blocked,
    )

    written = 0
    truncated = designer_cut or access_cut
    if fix and truncated:
        # **A TRUNCATED READ REVOKES ``--fix``, AND IT IS THE SAME ARGUMENT THE EXIT CODE MAKES.**
        # Exit 2 exists because a gate that goes green on half a table is worse than no gate, since
        # it is believed. A WRITE decided from half a table is the same mistake with a row attached
        # to it: "alone in its canonical group" is the entire precondition for a rewrite being safe,
        # and it is a claim about rows this run has SEEN. A twin sitting past the cut makes the
        # group a collision — the one thing this script must never write to — and the rewrite then
        # either lands on the unique index and raises ``UniqueViolationError`` part-way through the
        # loop, leaving a half-applied run whose printed list stops mid-way, or, where the twin is
        # in the other table, silently produces exactly the colliding pair this file exists to
        # report. Refusing every write is the only answer that does not depend on which of those it
        # would have been.
        print(
            "\n--fix WAS IGNORED: a read hit its cap above, so this run cannot tell a row that is "
            "alone in its canonical group from one whose twin it simply did not read, and being "
            "alone is the whole precondition for the rewrite being safe. Nothing was written. "
            "Raise READ_LIMIT and run again."
        )
    elif fix:
        print()
        for label, table, row, _standing in rewritable:
            address = canonical_email(row.email)
            # A DIRECT UPDATE, AND NOT ``access_roster.follow_email_change``, WHICH LOOKS LIKE THE
            # RIGHT FUNCTION AND IS NOT. Its early return is ``canonical_email(old) == new`` — "the
            # admin only re-spelled the same mailbox, so there is nothing to follow" — which is
            # precisely and always true here, so calling it would be a no-op with a comment claiming
            # otherwise. That function moves an admission BETWEEN mailboxes; this moves one row's
            # key WITHIN one mailbox, and only the two of them together cover the column.
            await table.update(where={"id": row.id}, data={"email": address})
            written += 1
            print(f"  canonicalised {label} row {row.id} -> {address!r}")

    collisions = len(refusing) + len(duplicated)
    print(
        f"\nCOUNT — {len(designer_rows)} designer roster row(s) and {len(access_rows)} allow-list "
        f"row(s) read; {collisions} colliding mailbox(es) ({len(refusing)} refusing somebody), "
        # ``written`` AND NOT ``fix``, so the word matches what the run actually did. A ``--fix``
        # run that a truncated read revoked above has written nothing, and calling those rows
        # "rewritten" on the count line — the one line an operator pastes into a ticket — would be
        # this report's only outright false sentence.
        f"{len(rewritable)} lone alias row(s) {'rewritten' if written else 'rewritable'}, "
        f"{len(blocked)} left for a human."
    )
    if fix and not truncated:
        print(f"{written} row(s) rewritten. No row was created, deleted, merged or re-statused.")
    elif not fix and rewritable:
        print("Nothing was written. Re-run with --fix to canonicalise the lone rows.")
    # The remaining case is ``--fix`` revoked by a truncated read, which says what happened and what
    # to do about it in full further up. Telling that operator to "re-run with --fix" would be
    # advising them to repeat the run that was just refused.

    if truncated:
        # AHEAD OF THE COLLISION CODE ON PURPOSE. "I could not see the whole table" outranks
        # "I found nothing in the part I saw", and a caller that only tests for zero must not read
        # an incomplete run as a pass.
        print("EXIT 2 — a read hit its cap, so this report is NOT a clean bill of health.")
        return EXIT_UNKNOWN
    if collisions:
        print(f"EXIT 1 — {collisions} mailbox(es) need a person to decide. Nothing was merged.")
        return EXIT_COLLISIONS
    print("EXIT 0 — no mailbox is held by more than one row in either roster.")
    return EXIT_CLEAN


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--fix",
        action="store_true",
        help=(
            "rewrite the email of every roster row that is ALONE in its canonical group and stored "
            "under an alias. Never merges, never touches a collision. Without it this run writes "
            "nothing at all."
        ),
    )
    args = parser.parse_args()

    _console_that_cannot_kill_the_run()
    await _connect()
    try:
        print(f"[{'FIX' if args.fix else 'REPORT ONLY'}] roster email canonicalisation")
        return await report(fix=args.fix)
    finally:
        await disconnect_db()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
