"""Give every empanelment the name the allow-list already knows for it.

WHAT WENT WRONG, IN THE WORDS IT WAS REPORTED IN
------------------------------------------------
*"The names of the designers did not go through, even though they have been added."*

``/admin/designers`` was listing bare email addresses for fifteen people whose names were sitting,
correctly typed, one screen away on ``/admin/access``. The roster reads as though those designers
are half-registered, and the only repair available to an administrator was to retype — on a second
screen — a name they had already given the product once.

THE CAUSE, WHICH WAS A DELIBERATE DECISION APPLIED ONE TABLE TOO WIDELY
----------------------------------------------------------------------
``app.services.designers.ensure_empanelled`` creates the empanelment for an allow-listed designer,
and it used to leave ``fullName`` NULL on purpose. Its docstring gave the reason: the column holds
what an ADMINISTRATOR typed, and a display name lifted off a Google profile is chosen by whoever
owns that account — a roster showing a name nobody at the institution entered is not a record of
what an administrator decided.

That argument is right, and it does not reach ``AccessRoster.fullName``. That column is admin-typed
under exactly the same rule, on the neighbouring screen, and its schema comment says in so many
words that it is never written from a login attempt. Copying it across moves an administrator's own
words from one of their screens to the other. So the rule stayed and the source changed:
``ensure_empanelled`` now carries the name at the moment it creates the row, and
``adopt_allow_list_name`` fills it in afterwards for a name that arrives late.

WHAT THIS SCRIPT IS FOR
-----------------------
The two functions above fix every empanelment from here on. They cannot fix the rows that already
exist, because ``adopt_allow_list_name`` is only reached from an administrator's edit — nobody is
going to open and re-save fifteen allow-list rows to trigger it. This walks the designer roster,
finds every row with no name of its own, and asks the allow-list for one.

IT FILLS AND IT NEVER OVERWRITES, WHICH IS THE ONE RULE THAT MATTERS
-------------------------------------------------------------------
A name already on the designer roster was typed there by an administrator, on that screen, about
that empanelment. The allow-list's copy may be older, may be a different transliteration, may be
the initials somebody used to get an invitation out of the door. Nothing here replaces one: the
write goes through :func:`app.services.designers.adopt_allow_list_name`, which refuses a row that
already carries a name, so that rule has ONE implementation and this script cannot drift from the
application's.

Nothing else is touched. ``isActive``, ``revokedAt``, ``notes``, ``institution``, ``addedById`` and
``firstSeenAt`` are not written by any path in this file. **No standing is granted, ended or
revived here** — a name is not a standing, and a run of this script cannot let anybody sign in who
could not sign in before it, nor stop anybody who could.

SUSPENDED ROWS ARE FILLED TOO, DELIBERATELY
-------------------------------------------
``isActive`` is not consulted. An administrator reading back the record of an empanelment that
ENDED is exactly the reader who most needs it to say who it was about, and skipping those rows
would leave the suspended half of the roster permanently bare while the active half filled in.
Writing a name to a suspended row changes nothing about the suspension; see the note above.

IDEMPOTENT. A second run reports zero to fill, because every row it wrote now has a name and is
excluded by the same test that selected it.

USAGE
-----
    python scripts/backfill_roster_names.py            # DRY RUN — reports, writes nothing
    python scripts/backfill_roster_names.py --fix      # apply

**THE SAFE INVOCATION IS THE ONE WITH NO ARGUMENTS**, which is why the flag is named rather than
the dry run: an operator handed this script who types its name gets a report. ``--execute`` is
accepted as a second spelling of ``--fix``, matching the two backfills beside this one, because a
flag that silently becomes unrecognised turns "apply the fix" into ``error: unrecognized
arguments`` at the moment somebody is following instructions under pressure.

``DATABASE_URL`` comes from ``backend/.env`` the same way the application reads it.

**ON WINDOWS**, run it with UTF-8 mode on — ``set PYTHONUTF8=1``, or ``py -X utf8 scripts/…``.
Prisma's config loader opens ``backend/pyproject.toml`` with the interpreter's default codec, that
file contains a rupee sign and several em dashes, and on a cp1252 box the read dies inside
``db.connect()`` with a ``UnicodeDecodeError`` naming a byte offset in a file this script never
mentions — which reads as a corrupt install rather than as a locale setting. :func:`_connect`
catches exactly that and says so.
"""

import argparse
import asyncio
import pathlib
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.services.designers import (  # noqa: E402
    adopt_allow_list_name,
    canonical_email,
    email_match_keys,
)

#: How many roster rows are read per round trip. The designer roster is a table an administrator
#: types into by hand — hundreds of rows at the outside — but an unbounded ``find_many`` on a table
#: whose size this script does not control is the kind of thing that is fine until the day it is
#: not, and paging costs one loop.
PAGE = 200


def _console_that_cannot_kill_the_run() -> None:
    """Make an unencodable character degrade to ``?`` instead of ending the run.

    THE REPORT BELOW IS FULL OF EM DASHES AND OF NAMES, and the names are the part that matters
    here: this backfill's whole subject is people's names, many of which are not ASCII. On Windows
    ``sys.stdout`` falls back to the ANSI codepage the moment it is redirected, so
    ``python scripts/backfill_roster_names.py > run.txt`` is a cp1252 stream — and a Devanagari or
    accented name in that stream raises ``UnicodeEncodeError`` out of ``print``, **part-way through
    the report**. In a dry run that costs a re-run. In a ``--fix`` run it aborts the loop between
    two writes and leaves the operator with a traceback where the list of what was applied should
    have been.

    Reconfiguring the ERROR HANDLER and not the encoding is the deliberate half of it. Forcing
    UTF-8 onto a console still running codepage 437 replaces a crash with mojibake in somebody's
    name, which on this report is worse than a ``?``: a mangled name looks like data corruption in
    the table, which is the exact thing an operator running this is trying to rule out.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(errors="replace")


async def _connect() -> None:
    """``connect_db`` plus the one Windows failure that does not name itself.

    See the module docstring. The remedy is one environment variable and it is printed here rather
    than left in a runbook, because the person seeing it is mid-incident. It cannot be fixed from
    inside the process: ``PYTHONUTF8`` is read once at interpreter start-up, so setting it here
    would change nothing, and re-launching the interpreter to apply it would mean a script that
    writes to a permissions table silently spawning a second copy of itself.
    """
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: Prisma's config loader could not decode backend/pyproject.toml "
            "with this interpreter's default codec. Re-run with UTF-8 mode on:\n"
            "    Windows :  set PYTHONUTF8=1  &&  python scripts/backfill_roster_names.py\n"
            "    or      :  py -X utf8 scripts/backfill_roster_names.py\n"
            f"(the underlying error was: {exc})"
        ) from exc


def _name_of(row: Any) -> str:
    """The row's name as a trimmed string, with NULL and whitespace-only both answering ``''``.

    ONE HELPER BECAUSE THE TWO CASES MUST NOT BE ALLOWED TO DIVERGE. A column holding ``""`` and a
    column holding NULL are the same fact to every reader of the roster screen — both fall back to
    the email address — so a script that treats one as "has a name" would report a row as done while
    the screen still shows an address, which is the exact complaint this backfill exists to answer.
    """
    return str(getattr(row, "fullName", "") or "").strip()


def _the_name_the_allow_list_holds(access_rows: list[Any], address: str) -> tuple[str, str] | None:
    """The admin-typed name for this mailbox and the spelling it was filed under, or None.

    THIS IS :func:`app.services.designers.name_on_the_allow_list`'S CHOOSING RULE, APPLIED TO ROWS
    ALREADY IN HAND. It is written out a second time here rather than called, and that is worth
    justifying because duplicating a rule is normally the mistake: the application's version issues
    one query per address, which is right for a sign-in and wrong for a backfill that would then
    make one round trip per roster row. This reads the whole allow-list once. The rule itself —
    discard rows with no name, prefer the canonical spelling, trim — is four lines, and
    ``tests/test_designer_roster_names.py`` pins it on the application's copy.

    **WHAT REACHES THIS FUNCTION IS DELIBERATELY WIDER THAN WHAT REACHES THE APPLICATION'S, AND
    THAT DIFFERENCE IS THE WHOLE REASON A BACKFILL IS NEEDED RATHER THAN A REDEPLOY.**
    :func:`email_match_keys` returns the literal spelling and adds the canonical one, so it can walk
    from a DOTTED address to a row stored either way — and cannot walk the other direction. An
    address that already IS the mailbox yields exactly one key and can never reach a row somebody
    typed with dots in it. That is not an oversight; it is what an INDEXED lookup can do, and
    inverting it would mean a scan on every sign-in.

    It is also exactly the shape the live data is in. ``tanyavanvari.nift@gmail.com`` sits on the
    allow-list with the dots an administrator typed, while the empanelment was written under the
    mailbox, ``tanyavanvarinift@gmail.com``, because ``ensure_empanelled`` canonicalises. Walking
    from the roster row — which is what this script does — starts from the mailbox and would find
    nothing, reporting "no name on the allow-list" about a row whose name is right there.

    :func:`plan` therefore indexes the allow-list under BOTH its stored spelling and its canonical
    form, which a bulk pass can afford and a per-request lookup cannot, so every pairing of
    spellings resolves. The rows this closes are the legacy ones ``scripts/backfill_email_
    canonicalisation.py`` exists to normalise; until that has been run everywhere, this is what
    stands between them and a permanently nameless roster line.

    RETURNS THE SPELLING AS WELL AS THE NAME because the report needs it. Half the interest in this
    backfill's output is which rows matched across a Gmail dot — ``tanyavanvari.nift@gmail.com``
    against a roster row filed as ``tanyavanvarinift@gmail.com`` — and a report that printed only
    the name would hide the one thing an operator would want to check by hand.
    """
    named = [row for row in access_rows if _name_of(row)]
    if not named:
        return None

    def _order(row: Any) -> tuple[int, str]:
        spelling = str(getattr(row, "email", "") or "")
        return (0 if spelling == address else 1, spelling)

    named.sort(key=_order)
    winner = named[0]
    return _name_of(winner), str(getattr(winner, "email", "") or "")


async def plan() -> dict[str, list[Any]]:
    """Every designer roster row with no name, sorted into what can be done about it.

    THE SELECTION IS "HAS NO NAME", NOT "WAS DERIVED FROM THE ALLOW-LIST", and the difference is
    deliberate. ``notes`` carries ``DERIVED_EMPANELMENT_NOTE`` on the rows this product created
    automatically, so filtering on it would be more precise about the fifteen rows that prompted
    this. It would also skip a row an administrator typed by hand in a hurry, entering an address
    and nothing else — which is the same bare-email-address roster line, with the same repair
    available, and no reason to be treated differently. What decides is the state of the column, not
    the history of the row.

    EVERY MATCHING ROW LANDS IN EXACTLY ONE BUCKET so the count line adds up to the number of
    unnamed rows found, and an operator who expected fifteen and reads three knows to look at the
    query rather than at the buckets.
    """
    # THE WHOLE ALLOW-LIST, ONCE, INDEXED UNDER BOTH ITS STORED SPELLING AND ITS CANONICAL FORM.
    #
    # The second key is the one that matters and it is the whole reason this is a bulk read rather
    # than a query per address: ``email_match_keys`` can walk from a dotted address to a row stored
    # either way and CANNOT walk back, so a roster row filed under the mailbox — which is every row
    # ``ensure_empanelled`` ever wrote — can never reach an allow-list row an administrator typed
    # with dots. That pairing is not hypothetical; it is what ``tanyavanvari.nift@gmail.com`` looks
    # like on the live table today. Indexing both sides makes every combination resolve.
    #
    # A LIST PER KEY, NOT A ROW. Two spellings of one mailbox are two allow-list rows (``email`` is
    # unique, and the strings differ), so both can land on the same canonical key. Keeping both and
    # letting ``_the_name_the_allow_list_holds`` choose is what keeps the choice deterministic and
    # in ONE place; overwriting here would make it depend on the order Postgres returned the rows.
    access_by_key: dict[str, list[Any]] = {}
    skip = 0
    while True:
        rows = await db.accessroster.find_many(take=PAGE, skip=skip, order={"id": "asc"})
        if not rows:
            break
        for row in rows:
            spelling = str(getattr(row, "email", "") or "")
            for key in {spelling, canonical_email(spelling)}:
                access_by_key.setdefault(key, []).append(row)
        skip += len(rows)
        if len(rows) < PAGE:
            break

    buckets: dict[str, list[Any]] = {"fill": [], "no_source": [], "already": []}
    skip = 0
    while True:
        rows = await db.designerroster.find_many(take=PAGE, skip=skip, order={"id": "asc"})
        if not rows:
            break
        for row in rows:
            address = str(getattr(row, "email", "") or "")
            if _name_of(row):
                buckets["already"].append((address, _name_of(row)))
                continue
            # EVERY SPELLING OF THE MAILBOX, ON BOTH SIDES. ``email_match_keys`` widens the
            # roster address; the canonical entries in ``access_by_key`` widen the allow-list. An
            # admin typed ``tanyavanvari.nift@gmail.com``; the empanelment was created under
            # ``tanyavanvarinift@gmail.com``. They are one person, and a lookup that could not see
            # across the dot would report "no name on the allow-list" while the administrator is
            # looking straight at the name.
            #
            # DE-DUPLICATED BY ROW ID because one row is reachable under two keys — its own spelling
            # and its canonical form — and a duplicate would be harmless for choosing a name but
            # would make the ``no_source`` report's "allow-list row carries no name" count wrong.
            seen: set[str] = set()
            matches: list[Any] = []
            for key in email_match_keys(address):
                for candidate in access_by_key.get(key, ()):
                    ident = str(getattr(candidate, "id", "") or "")
                    if ident in seen:
                        continue
                    seen.add(ident)
                    matches.append(candidate)
            found = _the_name_the_allow_list_holds(matches, canonical_email(address))
            if found is None:
                buckets["no_source"].append((address, bool(matches)))
            else:
                name, spelling = found
                buckets["fill"].append((address, name, spelling))
        skip += len(rows)
        if len(rows) < PAGE:
            break
    return buckets


def report(buckets: dict[str, list[Any]], *, fix: bool) -> None:
    """Print the plan. The verb changes with the mode; the content does not."""
    verb = "FILLING" if fix else "WOULD FILL"
    if buckets["fill"]:
        print(f"{verb} — {len(buckets['fill'])} empanelment(s) will take the allow-list's name:")
        for address, name, spelling in buckets["fill"]:
            # The allow-list's spelling is printed ONLY when it differs from the roster's, because
            # on most rows the two are identical and repeating the address on every line would bury
            # the handful of Gmail-alias matches that are actually worth an operator's attention.
            alias = "" if spelling == address else f"  [allow-list row is filed as {spelling}]"
            print(f"  {address}  ->  {name!r}{alias}")
        print()

    if buckets["no_source"]:
        print(
            f"NO NAME AVAILABLE — {len(buckets['no_source'])} empanelment(s) have no name here and "
            "none on the allow-list either. Nothing to copy; an administrator has to type one."
        )
        for address, had_row in buckets["no_source"]:
            why = "allow-list row carries no name" if had_row else "no allow-list row at all"
            print(f"  {address}  ({why})")
        print()

    if buckets["already"]:
        # A COUNT AND NOT A LIST. These rows are the ordinary case and there may be hundreds of
        # them; printing every one would push the two sections that need reading off the screen.
        print(f"ALREADY NAMED — {len(buckets['already'])} empanelment(s), untouched.")
        print()


def _counts(buckets: dict[str, list[Any]]) -> str:
    """One line, and a total that has to add up. See the backfill beside this one for the argument."""
    total = sum(len(rows) for rows in buckets.values())
    return (
        f"{total} empanelment(s): {len(buckets['fill'])} to fill, "
        f"{len(buckets['no_source'])} with no name available, "
        f"{len(buckets['already'])} already named"
    )


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--fix",
        action="store_true",
        help="actually write the names. Without it this is a DRY RUN and writes nothing.",
    )
    parser.add_argument(
        # The spelling the two backfills beside this one already accept. Same ``dest``, so the two
        # cannot disagree about what was asked for.
        "--execute",
        dest="fix",
        action="store_true",
        help="the alternative spelling of --fix; identical in effect.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="write at most N names this run (0 = no cap). The plan is printed in full either way.",
    )
    args = parser.parse_args()

    _console_that_cannot_kill_the_run()
    await _connect()
    try:
        buckets = await plan()
        mode = "FIX" if args.fix else "DRY RUN"
        print(f"[{mode}] designer roster name backfill\n")
        report(buckets, fix=args.fix)

        if not args.fix:
            # THE LAST LINE OF A DRY RUN SAYS THAT NOTHING HAPPENED, in those words, at the bottom
            # where somebody who scrolled past a list of addresses is looking. A report that lists
            # fifteen names under "WOULD FILL" and then stops reads, to a tired operator, like a
            # report of fifteen names written.
            print(f"\nCOUNT — {_counts(buckets)}. NOTHING WAS WRITTEN; re-run with --fix to apply.")
            return

        wanted = buckets["fill"][: args.limit] if args.limit > 0 else buckets["fill"]
        written = 0
        declined = 0
        for address, name, _spelling in wanted:
            # ``adopt_allow_list_name`` and not an update of this script's own: the
            # fill-never-overwrite rule, the both-spellings lookup and the blank-is-a-no-op rule all
            # live in that one function, and a second implementation here is a second place for them
            # to be got wrong.
            if await adopt_allow_list_name(address, name):
                written += 1
                print(f"  named {address}  {name!r}")
            else:
                # Not an error, and worth printing: between the plan above and this write somebody
                # typed a name on this row, and the fill was correctly declined rather than
                # overwriting it.
                declined += 1
                print(f"  skipped {address} (a name appeared between the plan and the write)")
        if args.limit > 0 and len(buckets["fill"]) > args.limit:
            print(
                f"CAPPED at --limit {args.limit}; {len(buckets['fill']) - args.limit} row(s) "
                "remain. Run again to continue."
            )
        # ``written`` AND NOT ``len(wanted)``: the two differ by every row that gained a name between
        # the plan and the write, and the count line is what an operator pastes into a ticket.
        print(
            f"\nCOUNT — {_counts(buckets)}. {written} name(s) WRITTEN, "
            f"{declined} declined at the write."
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
