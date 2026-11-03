"""Empanel the designers who are already stuck behind the two-gate refusal. Dry run by default.

WHY THIS EXISTS. This repository gates a designer's sign-in twice, and passing one gate has never
passed the other: ``AccessRoster`` decides who may sign in at all and can promote the account to
DESIGNER through ``admitRole``, while ``DesignerRoster`` decides whether that DESIGNER is still
empanelled. Until auto-empanelment shipped, being allow-listed as a designer created no empanelment,
so the second gate refused the person with *"Your designer access has been suspended."* — about an
empanelment nobody had ever granted, with ``/admin/designers`` showing no row at all to explain it.
``sandycraft3@gmail.com`` read exactly that in production.

The code fix (``app/services/designers.ensure_empanelled``, called from ``auth.login`` and from the
allow-list approval path) stops it happening again. IT DOES NOT CLEAR THE PEOPLE ALREADY STUCK
BEHIND IT — a designer who has given up trying to sign in is not going to trigger the repair by
signing in. This script is that half, which is why requirement 28 calls the backfill non-optional.

THE BACKFILL IS THE SIGN-INS THAT HAVE NOT HAPPENED YET, AND ITS RULE IS DELIBERATELY IDENTICAL TO
THE ONE ON THE SIGN-IN PATH: an address is empanelled here if and only if ``auth.login`` would
empanel it the next time that person signed in — role DESIGNER, an ACTIVE allow-list row, and no
``DesignerRoster`` row of any kind. So this creates exactly the rows the product would have created
anyway, sooner, and it never makes a decision an administrator has not already made.

That is a deliberate tightening of the wording in ``docs/archive/IMPLEMENTATION_PLAN.md`` §1 Fix 1 step 4, which
says "every ``User`` with ``role == DESIGNER``" without qualifying it by the allow-list, AND THE
REASON IS THAT THE UNQUALIFIED VERSION WOULD BE AN ADMISSION DECISION IN DISGUISE.
``auth.assert_access_admits`` accepts an ACTIVE ``DesignerRoster`` row as an admission for anybody
whose allow-list row is missing or PENDING — that is the empanelment clause, and it exists so an
admin who empanels somebody has not silently created a person the gate then refuses. Empanelling a
DESIGNER account whose allow-list row is PENDING would therefore APPROVE a request nobody has
decided, and empanelling one whose row is REJECTED or SUSPENDED would put an active empanelment on
the roster screen for somebody an administrator barred — which would not let them back in, since
neither state is "waiting", but would be a record contradicting the one next to it. (The
parenthesis here used to end *"and nothing downstream would ever correct the record, because
suspending an allow-list row does not suspend an empanelment"*. That half is no longer true:
``access_roster.mirror_suspension`` now ends the empanelment when the allow-list row is suspended
or rejected. What has NOT changed is this script's answer, because the mirror runs on the ACT of
barring somebody and not on the state of a row afterwards — an empanelment written here, after the
bar, is exactly the row nothing would ever come back to correct. The pairs already left disagreeing
are ``scripts/backfill_roster_suspension_mirror.py``'s subject, not this one's.) Both are refused
here and REPORTED instead, under "needs an administrator", so a human decides them on the screen
built for deciding them.

**IT NEVER REVIVES A SUSPENDED EMPANELMENT.** That rule is not restated here; the script calls
``ensure_empanelled`` itself, exactly as ``scripts/assign_records_to_workshop.py`` calls
``link_workshop_artisan`` rather than restating what it does, so the backfill cannot drift from the
endpoint. Suspension is a deliberate revocation — the roster suspends rather than deletes precisely
so the record survives the ending of it — and a backfill that reactivated suspended rows would undo
every revocation an administrator has ever made, in one run, silently. Suspended rows are counted
and listed as LEFT ALONE so that an operator can see the script found them and chose not to touch
them, rather than wondering whether it missed them.

IDEMPOTENT. Every create is conditional on there being no row at all, so a second run reports zero
changes and performs none.

Usage (from the repository root):

    python scripts/backfill_designer_empanelment.py              # DRY RUN — prints the plan
    python scripts/backfill_designer_empanelment.py --fix        # apply it
    python scripts/backfill_designer_empanelment.py --limit 20   # cap what a first run writes

**THE SAFE INVOCATION IS THE ONE WITH NO ARGUMENTS AT ALL, WHICH IS THE WHOLE POINT OF THE FLAG
BEING NAMED RATHER THAN THE DRY RUN.** An operator who has been handed this script and types its
name reads a report; nothing in this repository is written by a command somebody ran to find out
what it does. ``--execute`` is accepted as a second spelling of ``--fix`` because that is the one an
earlier revision shipped and there are hand-written runbook notes carrying it — a flag that silently
becomes unrecognised turns "apply the backfill" into ``error: unrecognized arguments`` at exactly
the moment somebody is following instructions under pressure. ``--fix`` is the spelling to use and
the one ``scripts/fix-designer-empanelment.py`` beside this file already uses for the same meaning.

``DATABASE_URL`` comes from ``backend/.env`` the same way the application reads it.

**ON WINDOWS**, run it with UTF-8 mode on — ``set PYTHONUTF8=1`` in the shell, or
``py -X utf8 scripts/backfill_designer_empanelment.py``. Prisma's own config loader opens
``backend/pyproject.toml`` with the interpreter's default codec, that file contains a rupee sign and
several em dashes, and on a cp1252 box the read dies inside ``db.connect()`` with a
``UnicodeDecodeError`` naming a byte offset in a file this script never mentions — which reads as a
corrupt install rather than as a locale setting. :func:`_connect` catches exactly that and says so.
"""

import argparse
import asyncio
import pathlib
import sys
from datetime import UTC, datetime
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.core.db import connect_db, db, disconnect_db  # noqa: E402
from app.services import access_roster  # noqa: E402
from app.services.designers import (  # noqa: E402
    DERIVED_EMPANELMENT_NOTE,
    canonical_email,
    email_match_keys,
    ensure_empanelled,
    normalise_email,
)


def _console_that_cannot_kill_the_run() -> None:
    """Make an unencodable character degrade to ``?`` instead of ending the run.

    THE REPORT BELOW IS FULL OF EM DASHES, and on Windows ``sys.stdout`` falls back to the ANSI
    codepage the moment it is redirected — ``python scripts/backfill_designer_empanelment.py > run.txt``
    is a cp1252 stream. U+2014 survives cp1252; the arrow and the quotation marks this file could
    easily acquire later do not, and the failure is not a mangled character, it is a
    ``UnicodeEncodeError`` raised out of ``print`` **part-way through the report**. In a dry run that
    costs a re-run. In a ``--fix`` run it aborts the loop between two writes, leaving the operator
    with a half-applied backfill and a traceback where the list of what was applied should have been
    — and nothing in this script's output tells them which rows made it.

    Reconfiguring the ERROR HANDLER and not the encoding is the deliberate half of this. Forcing
    UTF-8 onto a console still running codepage 437 replaces a crash with mojibake in an email
    address, which is worse here than a ``?``: this report exists to be read as a list of addresses.
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
    left in a runbook, because the person seeing this is mid-incident.

    It cannot be fixed from inside the process: ``PYTHONUTF8`` is read once at interpreter start-up,
    so setting it here would change nothing and re-launching the interpreter to apply it would mean
    a script that repairs a permission table silently spawning a second copy of itself.
    """
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: Prisma's config loader could not decode backend/pyproject.toml "
            f"with this interpreter's default codec ({exc.encoding}). This is a locale setting, "
            "not a broken install. Re-run with UTF-8 mode on:\n"
            "    set PYTHONUTF8=1  &&  python scripts/backfill_designer_empanelment.py\n"
            "or:\n"
            "    py -X utf8 scripts/backfill_designer_empanelment.py"
        ) from exc


CANDIDATE_READ_LIMIT = 50_000


def backfill_note() -> str:
    """What a backfilled row says about itself.

    Built FROM :data:`DERIVED_EMPANELMENT_NOTE` rather than beside it, so there is one sentence in
    the repository saying "this empanelment was not made by an administrator" and this script cannot
    end up carrying a second one that says it differently. The dated clause is the part that is only
    true here: a row an operator can trace back to one run of one script, on one afternoon.
    """
    return (
        f"{DERIVED_EMPANELMENT_NOTE} Created by scripts/backfill_designer_empanelment.py on "
        f"{datetime.now(UTC).date().isoformat()}, for an address that was already admitted as a "
        "designer before auto-empanelment existed."
    )


def _stored_as(address: str, row: Any) -> str:
    """Name the roster row's own spelling, but only when it is not the one being reported.

    THE ADDRESS EVERY LINE OF THIS REPORT PRINTS IS THE ONE THE ALLOW-LIST OR THE ACCOUNT IS KEYED
    ON, AND SINCE FIX 2 THAT NEED NOT BE THE SPELLING THE ROSTER ROW IS FILED UNDER.
    :func:`canonical_email` folds every spelling of one Gmail mailbox together, which is what
    correctly reports ``sandy.craft3@gmail.com`` as already empanelled on the strength of a row
    stored as ``sandycraft3@gmail.com`` — one mailbox, one empanelment, exactly as intended.

    The failure is what the operator does next. They read a line naming the dotted address, type
    that address into the search box on ``/admin/designers``, and are shown NO ROW AT ALL, because
    that screen searches the stored string and not the mailbox. The honest reading of an empty
    result there is "this script told me somebody is empanelled and the roster disagrees", and the
    person who trusts the screen over the script goes on to empanel a second row for a mailbox that
    already has one. Printing the string they will actually find, beside the one they searched for,
    is the whole of the remedy.

    **THIS IS DELIBERATELY NOT A WARNING, AND IT IS ATTACHED TO THE TWO BUCKETS THAT ALREADY HAVE A
    ROW.** An earlier revision of this file put a "WARNING: Gmail-equivalent to an existing roster
    row" clause on the WOULD CREATE bucket instead, which could not fire and never had: reaching
    that bucket requires the canonical lookup to have missed, and a Gmail twin holding a row is
    exactly the case where it hits. Every such person lands in ``already`` or ``suspended``, so the
    note belongs on those lines and nowhere else.

    Returns the empty string where the two spellings agree, which is every address in the table
    that is not a Gmail alias, so the ordinary line keeps its ordinary shape.
    """
    stored = normalise_email(row.email)
    if stored == address:
        return ""
    return f" — the roster row is filed under {stored!r}, the same mailbox spelled differently"


async def candidate_addresses() -> dict[str, list[str]]:
    """Every address the two sources nominate, and which source nominated it.

    Two reads and no join. ``AccessRoster`` and ``User`` have no relation between them — they meet on
    the email column and nothing enforces the join, which is stated at
    ``access_roster.barred_emails`` and is why the addresses are matched in Python on the normalised
    form rather than in SQL on a foreign key that does not exist.
    """
    admitted = await db.accessroster.find_many(
        where={"status": access_roster.ACTIVE, "admitRole": "DESIGNER"},
        take=CANDIDATE_READ_LIMIT + 1,
    )
    designers = await db.user.find_many(where={"role": "DESIGNER"}, take=CANDIDATE_READ_LIMIT + 1)
    for label, rows in (("allow-list", admitted), ("user accounts", designers)):
        if len(rows) > CANDIDATE_READ_LIMIT:
            print(
                f"WARNING: more than {CANDIDATE_READ_LIMIT} {label} rows matched, so this run is "
                "working from a CUT list and will silently leave some designers unfixed. Raise "
                "CANDIDATE_READ_LIMIT and run again before believing the totals below."
            )
    sources: dict[str, list[str]] = {}
    for row in admitted[:CANDIDATE_READ_LIMIT]:
        address = normalise_email(row.email)
        if address:
            sources.setdefault(address, []).append("allow-listed as DESIGNER")
    for row in designers[:CANDIDATE_READ_LIMIT]:
        address = normalise_email(row.email)
        if address:
            sources.setdefault(address, []).append("account role is DESIGNER")
    return sources


async def plan() -> dict[str, list[tuple[str, str]]]:
    """Sort every candidate into one of four buckets, without writing anything.

    Three indexed ``IN`` reads for the whole set rather than two queries per address: this runs
    against a production database on a box with ``DATABASE_CONNECTION_LIMIT = 10``, and a per-row
    loop over a few hundred designers is the pattern that turns a repair into an incident.
    """
    sources = await candidate_addresses()
    wanted = sorted(sources)
    if not wanted:
        return {"create": [], "already": [], "suspended": [], "needs_admin": []}

    all_roster = await db.designerroster.find_many(take=CANDIDATE_READ_LIMIT + 1)
    if len(all_roster) > CANDIDATE_READ_LIMIT:
        print(
            f"WARNING: the designer roster holds more than {CANDIDATE_READ_LIMIT} rows, so this run "
            "read only part of it. Anybody whose existing row fell past the cut is listed below as "
            "WOULD CREATE — including, if it is a suspended row, somebody this script would then "
            "correctly decline to touch at the write. Raise CANDIDATE_READ_LIMIT and run again "
            "before acting on the report."
        )
        all_roster = all_roster[:CANDIDATE_READ_LIMIT]
    empanelment: dict[str, Any] = {}
    for row in all_roster:
        key = canonical_email(row.email)
        current = empanelment.get(key)
        if current is None or (current.isActive and not row.isActive):
            empanelment[key] = row

    keys = sorted({key for address in wanted for key in email_match_keys(address)})
    access_rows = await db.accessroster.find_many(where={"email": {"in": keys}})
    access_by_key = {normalise_email(row.email): row for row in access_rows}

    buckets: dict[str, list[tuple[str, str]]] = {
        "create": [],
        "already": [],
        "suspended": [],
        "needs_admin": [],
    }
    for address in wanted:
        why = " + ".join(sources[address])
        row = empanelment.get(canonical_email(address))
        if row is not None and row.isActive:
            buckets["already"].append((address, why + _stored_as(address, row)))
            continue
        if row is not None:
            buckets["suspended"].append(
                (address, "revoked by an administrator — LEFT ALONE" + _stored_as(address, row))
            )
            continue
        rows = [
            found
            for key in email_match_keys(address)
            if (found := access_by_key.get(key)) is not None
        ]
        if not rows or not all(access_roster.admits(found) for found in rows):
            standing = (
                "/".join(sorted({access_roster.status_of(found) for found in rows}))
                or "no allow-list row"
            )
            buckets["needs_admin"].append((address, f"{why}; allow-list says {standing}"))
            continue
        buckets["create"].append((address, why))
    return buckets


def report(buckets: dict[str, list[tuple[str, str]]], *, fix: bool) -> None:
    """Print all four buckets, in full, INCLUDING THE EMPTY ONES.

    The suspended section is the one that earns this rule and it is why every section obeys it. An
    operator runs this because a designer cannot sign in; if the person they are thinking of is
    absent from every list, that is an answer, and it is only an answer when the reader can see
    that the list they are absent from was actually printed. A section suppressed for being empty
    is indistinguishable from a section the script forgot, and the guess a reader makes about
    "nothing about suspended rows appeared" is that the backfill quietly revived them.

    The heading of the first bucket is the only one that changes with the mode, and it says which
    way round it is: in a dry run these are addresses nothing has been done about, and under
    ``--fix`` the same list is followed line by line by what the writes actually did — which is not
    the same list, because a row can appear between the plan and the write.
    """
    headings = (
        (
            "create",
            "TO CREATE — written below" if fix else "WOULD CREATE — nothing has been written",
        ),
        (
            "suspended",
            "SKIPPED: ALREADY HAS A ROW (SUSPENDED). These people are refused at sign-in and this "
            "script deliberately did NOT revive them — a suspension is an administrator's "
            "revocation, and restoring one is an act for /admin/designers.",
        ),
        (
            "needs_admin",
            "NEEDS AN ADMINISTRATOR — not currently admitted by the allow-list, so empanelling "
            "them here would approve a platform-access request nobody decided. Nothing written.",
        ),
        ("already", "SKIPPED: ALREADY EMPANELLED — nothing to do."),
    )
    for key, heading in headings:
        rows = buckets[key]
        print(f"\n{heading} — {len(rows)}")
        for address, why in rows:
            print(f"  {address}  ({why})")
        if not rows:
            print("  (none)")


def _counts(buckets: dict[str, list[tuple[str, str]]]) -> str:
    """The four bucket sizes and their total, for the one line at the bottom.

    A COUNT LINE THAT DOES NOT ADD UP IS THE POINT OF IT. Every candidate this run found lands in
    exactly one bucket, so the total is what the two source queries returned, and an operator who
    expected forty designers and reads twelve knows to look at the queries rather than at the
    buckets. Split across four lines that arithmetic is something a reader has to do; on one line
    they cannot avoid doing it.
    """
    total = sum(len(rows) for rows in buckets.values())
    return (
        f"{total} candidate(s): {len(buckets['create'])} to empanel, "
        f"{len(buckets['suspended'])} skipped as suspended, "
        f"{len(buckets['needs_admin'])} needing an administrator, "
        f"{len(buckets['already'])} already empanelled"
    )


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--fix",
        action="store_true",
        help="actually create the rows. Without it this is a DRY RUN and writes nothing.",
    )
    parser.add_argument(
        "--execute",
        dest="fix",
        action="store_true",
        help="the earlier spelling of --fix; identical in effect.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="create at most N rows this run (0 = no cap). The plan is printed in full either way.",
    )
    args = parser.parse_args()

    _console_that_cannot_kill_the_run()
    await _connect()
    try:
        buckets = await plan()
        mode = "FIX" if args.fix else "DRY RUN"
        print(f"[{mode}] designer empanelment backfill\n")
        report(buckets, fix=args.fix)

        if not args.fix:
            print(f"\nCOUNT — {_counts(buckets)}. NOTHING WAS WRITTEN; re-run with --fix to apply.")
            return

        wanted = buckets["create"][: args.limit] if args.limit > 0 else buckets["create"]
        created = 0
        declined = 0
        for address, _why in wanted:
            if await ensure_empanelled(address, note=backfill_note()):
                created += 1
                print(f"  empanelled {address}")
            else:
                declined += 1
                print(f"  skipped {address} (a row appeared between the plan and the write)")
        if args.limit > 0 and len(buckets["create"]) > args.limit:
            print(
                f"CAPPED at --limit {args.limit}; {len(buckets['create']) - args.limit} candidate(s) "
                "remain. Run again to continue."
            )
        print(
            f"\nCOUNT — {_counts(buckets)}. {created} empanelment(s) CREATED, "
            f"{declined} declined at the write."
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
