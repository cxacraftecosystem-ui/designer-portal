"""Reconcile the two rosters where one of them still admits somebody the other revoked. Dry run.

WHY THIS EXISTS. This repository keeps two independent standings for one person: ``AccessRoster``
says whether an address may sign in at all, and ``DesignerRoster`` says whether the institution
still recognises that person as a designer. Until the cross-roster mirror shipped, ending one of
those standings wrote one table and returned — so an administrator who barred somebody from the
application left an ACTIVE empanelment sitting on ``/admin/designers``, and an administrator who
ended an empanelment left an ACTIVE allow-list row sitting on ``/admin/access``. Two screens, one
person, contradictory answers, and the sign-in refusal correct but unexplainable from either screen.

``app.services.access_roster.mirror_suspension`` stops it happening again. IT DOES NOT REPAIR THE
PAIRS ALREADY LEFT DISAGREEING — nothing revisits a row that was written before the rule existed,
and the two paths that would notice (a sign-in, an admin re-clicking a button) both deliberately do
nothing: the endpoints mirror on the ACT of revoking somebody and never on a standing that has not
moved. Two of the four say that by returning early where there is nothing to write; the two on
``/admin/access`` say it by skipping the mirror when the row was ALREADY barred, which is the same
rule for the case an early return cannot reach — a REJECTED row being recorded as SUSPENDED is a
real write about somebody whose access ended days ago. All four exist so that a stray second click
cannot undo a restoration an administrator made on purpose. This script is the other half.

THE RULE IS DELIBERATELY IDENTICAL TO THE ONE ON THE ADMIN WRITE PATHS, because it calls the same
function. Every write below goes through ``mirror_suspension`` — the create-only asymmetry, the
Gmail-alias key list, the idempotent WHERE clauses, the note that records WHY, and the guard that
keeps a professor's access alive all live in that one function, and a second implementation here is
a second place for every one of them to be got wrong. The script decides only WHICH addresses to
offer it.

**IT ONLY EVER SUSPENDS. IT NEVER RESTORES ANYTHING, IN EITHER DIRECTION.** That is not a limitation
of the script; it is the rule. Where an allow-list row is ACTIVE and the empanelment is suspended,
the repair is to end the admission — never to revive the empanelment, which would undo an
administrator's revocation in bulk, silently, in one run. Where the allow-list bars somebody and the
empanelment is active, the repair is to end the empanelment — never to re-admit them. A reader
looking for the symmetric restore should read ``mirror_suspension``'s docstring, and then
``app.services.designers.ensure_empanelled``'s, which is where the argument is made in full.

**IT NEVER TOUCHES A PAIR THE TWO SIDES ALREADY AGREE ON.** A person barred on both rosters, and a
person active on both, are consistent records and are not this script's business — the first is
listed as ALREADY CONSISTENT so an operator can see it was found and skipped, and the second is not
a candidate at all (a pair of live standings is what the product is supposed to look like, and
printing every designer in the institution would bury the four lines that matter).

**AND IT REFUSES THE ONE REPAIR THAT WOULD BE AN ADMISSION DECISION IN DISGUISE**, exactly as
``scripts/backfill_designer_empanelment.py`` refuses its own: an ended empanelment whose ACTIVE
allow-list row admits the person at some other tier, or whose account is not a designer, is NOT
barred here. ``auth.assert_roster_admits`` argues at length that ending an empanelment must not lock
a professor or an admin out of the whole product — they are on the designer roster because they run
workshops too, and their account has nothing to do with the empanelment being ended. Those pairs are
REPORTED under "needs an administrator" so that a human decides them on the screen built for it.

IDEMPOTENT. Every write is conditional on the target row still being in the standing the plan saw,
so a second run reports zero changes and performs none. A row that is already suspended is not
matched at all, which is what keeps a re-run from moving the date somebody actually lost access.

Usage (from the repository root):

    python scripts/backfill_roster_suspension_mirror.py            # DRY RUN — prints the plan
    python scripts/backfill_roster_suspension_mirror.py --fix      # apply it
    python scripts/backfill_roster_suspension_mirror.py --limit 20 # cap what a first run writes

**THE SAFE INVOCATION IS THE ONE WITH NO ARGUMENTS**, which is why the flag is named rather than the
dry run. An operator handed this script and typing its name reads a report; nothing in this
repository is written by a command somebody ran to find out what it does. ``--execute`` is accepted
as a second spelling of ``--fix`` for the reason its sibling accepts it: there are hand-written
runbook notes carrying that spelling, and an unrecognised flag at the moment somebody is applying a
fix under pressure costs more than a second line in ``--help``.

``DATABASE_URL`` comes from ``backend/.env`` the same way the application reads it.

**ON WINDOWS**, run it with UTF-8 mode on — ``set PYTHONUTF8=1`` in the shell, or
``py -X utf8 scripts/backfill_roster_suspension_mirror.py``. Prisma's own config loader opens
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
from app.services.designers import (  # noqa: E402
    canonical_email,
    email_match_keys,
    normalise_email,
)


def _console_that_cannot_kill_the_run() -> None:
    """Make an unencodable character degrade to ``?`` instead of ending the run.

    ``scripts/backfill_designer_empanelment.py``'s rule, for its reason, which applies here word for
    word: this report is full of em dashes, Windows falls back to the ANSI codepage the moment the
    stream is redirected, and the failure is not a mangled character but a ``UnicodeEncodeError``
    raised out of ``print`` PART-WAY THROUGH. In a dry run that costs a re-run; in a ``--fix`` run it
    aborts the loop between two writes and leaves the operator with a half-applied repair and a
    traceback where the list of what was applied should be.

    The ERROR HANDLER is reconfigured and not the encoding, deliberately: forcing UTF-8 onto a
    console still running codepage 437 replaces a crash with mojibake in an email address, which is
    worse here than a ``?``, because this report exists to be read as a list of addresses.
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

    It cannot be fixed from inside the process: ``PYTHONUTF8`` is read once at interpreter start-up.
    """
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: Prisma's config loader could not decode backend/pyproject.toml "
            f"with this interpreter's default codec ({exc.encoding}). This is a locale setting, "
            "not a broken install. Re-run with UTF-8 mode on:\n"
            "    set PYTHONUTF8=1  &&  python scripts/backfill_roster_suspension_mirror.py\n"
            "or:\n"
            "    py -X utf8 scripts/backfill_roster_suspension_mirror.py"
        ) from exc


#: A ceiling on each of the four candidate reads, so a script pointed at a large production
#: database cannot pull a whole table into memory. Its own number rather than a borrowed one, and
#: far above any plausible programme; if a run reports that it was hit, the answer is to raise it
#: deliberately rather than to trust a cut list — the same reasoning as
#: ``access_roster.BARRED_EMAIL_READ_LIMIT``, and the same failure direction, because a cut
#: candidate list is an inconsistent pair this run silently did not repair.
CANDIDATE_READ_LIMIT = 50_000

#: The four buckets, in the order the report prints them. Named here so the plan, the report, the
#: count line and the write loop cannot end up disagreeing about what exists — the four-way split IS
#: the contract between this script and the operator reading it.
BUCKETS: tuple[str, ...] = ("empanelment", "access", "needs_admin", "consistent")


def _by_mailbox(rows: list[Any]) -> dict[str, Any]:
    """Index rows by MAILBOX rather than by the string they are stored under.

    ``canonical_email`` and not ``normalise_email``, because the two tables are joined on the mailbox
    and not on the spelling: a designer roster row typed ``sandy.craft3@gmail.com`` and an allow-list
    row stored as ``sandycraft3@gmail.com`` are one person, and a plan keyed on the literal strings
    would report them as two people with no counterpart each — the exact miss that makes a mirror
    worse than no mirror, because it suspends one side and leaves the other live.

    **WHERE TWO SPELLINGS OF ONE MAILBOX BOTH HAVE ROWS, THE ONE WITH THE ENDED STANDING WINS THE
    SLOT**, which matches ``roster_allows`` (every row must be active for the gate to admit) and
    ``access_roster._the_row_that_decides`` (the refusal beats the admission). Reporting the
    admitting twin instead would tell an operator somebody is still admitted when the door is in
    fact already shut on them, and would file a person who needs nothing under a bucket that writes.
    """
    indexed: dict[str, Any] = {}
    for row in rows:
        key = canonical_email(row.email)
        current = indexed.get(key)
        if current is None or (_ended(row) and not _ended(current)):
            indexed[key] = row
    return indexed


def _ended(row: Any) -> bool:
    """Has this row's standing been ended? One question, both tables.

    ``AccessRoster`` says so with a status in ``BARRED`` and ``DesignerRoster`` with
    ``isActive == False``; there is no shared column and there is not going to be one, so the two
    spellings of one question are collapsed here rather than at four call sites. ``status_of`` for
    an allow-list row because Prisma hands back an enum member on a live row and a bare string on a
    hand-built one, and ``==`` answers False for the first of those.
    """
    if hasattr(row, "isActive"):
        return not row.isActive
    return access_roster.status_of(row) in access_roster.BARRED


def _stored_as(address: str, row: Any) -> str:
    """Name a row's own spelling, but only where it is not the address being reported.

    ``scripts/backfill_designer_empanelment.py``'s helper, for its reason. Every line of this report
    prints the MAILBOX, and since canonicalisation shipped that need not be the string either row is
    filed under. The failure is what the operator does next: they read the line, type that address
    into the search box on ``/admin/designers`` or ``/admin/access``, are shown no row at all —
    those screens search the stored string — and reasonably conclude the script is describing rows
    that do not exist. Printing the string they will actually find is the whole of the remedy.

    Returns the empty string where the spellings agree, which is every address that is not a Gmail
    alias, so the ordinary line keeps its ordinary shape.
    """
    stored = normalise_email(row.email)
    if stored == address:
        return ""
    return f"; filed under {stored!r}, the same mailbox spelled differently"


async def _read(label: str, model: Any, where: dict[str, Any]) -> list[Any]:
    """One capped read, with the cut reported rather than swallowed.

    A cut list here is not merely a short report: every address past the cut is an inconsistent pair
    this run does not know about and will not repair, and the operator's reasonable reading of a
    clean report is that there is nothing left to fix.
    """
    rows = await model.find_many(where=where, take=CANDIDATE_READ_LIMIT + 1)
    if len(rows) > CANDIDATE_READ_LIMIT:
        print(
            f"WARNING: more than {CANDIDATE_READ_LIMIT} {label} rows matched, so this run is "
            "working from a CUT list and will silently leave some inconsistent pairs unrepaired. "
            "Raise CANDIDATE_READ_LIMIT and run again before believing the totals below."
        )
        return rows[:CANDIDATE_READ_LIMIT]
    return rows


async def plan() -> dict[str, list[tuple[str, str]]]:
    """Sort every inconsistent pair into one of four buckets, without writing anything.

    Four narrow indexed reads for the whole database rather than two queries per address: this runs
    against a production database on a box with ``DATABASE_CONNECTION_LIMIT = 10``, and a per-row
    loop over a few hundred people is the pattern that turns a repair into an incident. The reads
    are narrow on purpose — only the standings that can be half of a DISAGREEMENT are fetched, so
    the healthy majority of both tables is never read at all.

    THE ONE PER-ADDRESS QUERY IS THE GUARD, and it is worth its cost. Bucketing the
    empanelment-to-allow-list direction requires ``admissions_an_empanelment_carries``' answer, and
    that function is the one the WRITE will consult; reproducing its two tests here would be a
    second copy of an access-control rule, and the version of this script that had one would print a
    plan the write then disagreed with — "would suspend" followed by nothing happening, which reads
    as a broken script rather than as a guard working. The set it runs over is the set of ended
    empanelments that STILL hold a live designer-tier admission, which is the inconsistency itself
    and is therefore small; on a healthy database it is empty.
    """
    barred_access = await _read(
        "barred allow-list",
        db.accessroster,
        {"status": {"in": list(access_roster.BARRED)}},
    )
    ended_empanelments = await _read("suspended empanelment", db.designerroster, {"isActive": False})
    live_empanelments = await _read("active empanelment", db.designerroster, {"isActive": True})
    designer_admissions = await _read(
        "designer-tier admission",
        db.accessroster,
        {"status": access_roster.ACTIVE, "admitRole": "DESIGNER"},
    )

    barred_by_mailbox = _by_mailbox(barred_access)
    ended_by_mailbox = _by_mailbox(ended_empanelments)
    live_by_mailbox = _by_mailbox(live_empanelments)
    admitted_by_mailbox = _by_mailbox(designer_admissions)

    buckets: dict[str, list[tuple[str, str]]] = {name: [] for name in BUCKETS}

    # ── DIRECTION 1: the allow-list bars them, the designer roster still says they are empanelled ─
    for address, access in sorted(barred_by_mailbox.items()):
        standing = access_roster.status_of(access)
        empanelment = live_by_mailbox.get(address)
        if empanelment is not None:
            buckets["empanelment"].append(
                (
                    address,
                    f"allow-list says {standing} and the empanelment is still ACTIVE"
                    f"{_stored_as(address, empanelment)}",
                )
            )
            continue
        if address in ended_by_mailbox:
            buckets["consistent"].append(
                (address, f"allow-list says {standing} and the empanelment is already suspended")
            )
    # ── DIRECTION 2: the empanelment ended, the allow-list still admits them AS A DESIGNER ────────
    #
    # The intersection is computed against ``admitted_by_mailbox`` — ACTIVE rows admitting at
    # DESIGNER — and not against every ACTIVE row, because an admission at any other tier is not
    # carried by the empanelment and is therefore not an inconsistency at all. That is the guard's
    # first test, and it is applied here as a NARROWING of the candidate set rather than as a
    # decision: the authoritative answer still comes from the guard itself, two lines down.
    for address, _empanelment in sorted(ended_by_mailbox.items()):
        access = admitted_by_mailbox.get(address)
        if access is None:
            continue
        carried = await access_roster.admissions_an_empanelment_carries(address)
        if carried:
            buckets["access"].append(
                (
                    address,
                    "the empanelment is suspended and the allow-list still admits them as a "
                    f"DESIGNER{_stored_as(address, access)}",
                )
            )
            continue
        # THE GUARD DECLINED, AND THE REPORT HAS TO SAY WHY OR IT READS AS THE SCRIPT MISSING
        # SOMEBODY. The usual reason is the one the guard exists for: an account on this mailbox is
        # not a designer, so their place in the product does not rest on the empanelment.
        #
        # ASKED THROUGH THE GUARD'S OWN LOOKUP, AND NOT THROUGH A SECOND ONE WRITTEN HERE. This line
        # used to be its own ``find_many`` on ``email == address``, which is the mistake this
        # script's docstring warns about in the large: ``User.email`` is not canonicalised while
        # both rosters are, so for a Gmail mailbox that query answers "none" about an account that
        # exists under a dotted spelling — and the report then told an operator the account holds
        # "none rather than DESIGNER" about the very administrator the guard had just protected.
        # ``access_roster._accounts_on_the_mailbox`` is the function the guard consults, so the plan
        # and the write now describe the same set for the same reason they call the same code.
        accounts = await access_roster._accounts_on_the_mailbox(  # noqa: SLF001
            email_match_keys(address), address
        )
        if accounts is None:
            # THE SWEEP WAS CUT, so nobody knows who is on this mailbox — including the guard, which
            # is why it declined. Reporting a role here would be inventing one.
            buckets["needs_admin"].append(
                (
                    address,
                    "the empanelment is suspended and the allow-list still admits them, but the "
                    "account sweep was CUT (see the ERROR line above and "
                    "access_roster.GMAIL_ACCOUNT_SWEEP_LIMIT), so nothing here knows whether this "
                    "mailbox belongs to a designer — raise that limit and run again before "
                    "deciding anything about this address",
                )
            )
            continue
        held = "/".join(sorted({str(getattr(a.role, "value", a.role)) for a in accounts})) or "none"
        if held == "none":
            # NO ACCOUNT AND THE GUARD STILL DECLINED, which the guard's own rules say cannot happen
            # on the account test — an absent account is a mirror. So the decline came from the
            # OTHER direction: the allow-list row is filed under a spelling this mailbox cannot
            # reach. ``email_match_keys`` adds the canonical form to what it is given and derives one
            # key from an address that already IS the mailbox, while ``_by_mailbox`` above found the
            # row by canonicalising it — so a dotted allow-list row is visible to the plan and
            # invisible to the guard. Saying "the account holds none rather than DESIGNER" about
            # that pair would send an operator to look at a role that has nothing to do with it.
            buckets["needs_admin"].append(
                (
                    address,
                    "the empanelment is suspended and the allow-list still admits them, but the "
                    "guard could not reach that row from this mailbox — it is filed under a "
                    f"spelling{_stored_as(address, access) or ' of its own'} that "
                    "email_match_keys cannot derive from the canonical form. Canonicalise the rows "
                    "at rest with scripts/backfill_email_canonicalisation.py and run this again, "
                    "or settle the pair by hand on the two admin screens",
                )
            )
            continue
        buckets["needs_admin"].append(
            (
                address,
                "the empanelment is suspended and the allow-list still admits them, but the "
                f"account holds {held} rather than DESIGNER, so their access to the application "
                "does not rest on the empanelment — ending it here would lock them out of the "
                "whole product",
            )
        )
    return buckets


def report(buckets: dict[str, list[tuple[str, str]]], *, fix: bool) -> None:
    """Print all four buckets, in full, INCLUDING THE EMPTY ONES.

    ``scripts/backfill_designer_empanelment.py``'s rule and its reason: an operator runs this
    because two screens disagree about one person, and if that person is absent from every list,
    that is an answer — but only when the reader can see that the list they are absent from was
    actually printed. A section suppressed for being empty is indistinguishable from a section the
    script forgot, and the guess a reader makes about a missing "needs an administrator" section is
    that the script barred somebody it should not have.

    Only the first two headings change with the mode, and they say which way round it is: in a dry
    run these are pairs nothing has been done about, and under ``--fix`` the same lists are followed
    line by line by what the writes actually did — which is not the same list, because a row can
    change between the plan and the write.
    """
    headings = (
        (
            "empanelment",
            (
                "TO SUSPEND — the EMPANELMENT, because the allow-list already bars this address"
                if fix
                else "WOULD SUSPEND THE EMPANELMENT — nothing has been written"
            ),
        ),
        (
            "access",
            (
                "TO SUSPEND — the ADMISSION, because the empanelment it rested on has ended"
                if fix
                else "WOULD SUSPEND THE ADMISSION — nothing has been written"
            ),
        ),
        (
            "needs_admin",
            "NEEDS AN ADMINISTRATOR — the empanelment ended but this person's place in the "
            "application does not rest on it, so barring them here would be an outage rather than "
            "a repair. Nothing written; decide it on /admin/access.",
        ),
        (
            "consistent",
            "SKIPPED: ALREADY CONSISTENT — both rosters already show this standing ended, so there "
            "is nothing to mirror. (A pair that is ACTIVE on both sides is not a candidate at all "
            "and is deliberately not listed: that is what a healthy record looks like.)",
        ),
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

    A COUNT LINE THAT DOES NOT ADD UP IS THE POINT OF IT: every pair this run found lands in exactly
    one bucket, so an operator who expected forty and reads twelve knows to look at the reads rather
    than at the buckets. Split across four lines that arithmetic is something a reader has to do; on
    one line they cannot avoid doing it.
    """
    total = sum(len(rows) for rows in buckets.values())
    return (
        f"{total} pair(s): {len(buckets['empanelment'])} empanelment(s) to suspend, "
        f"{len(buckets['access'])} admission(s) to suspend, "
        f"{len(buckets['needs_admin'])} needing an administrator, "
        f"{len(buckets['consistent'])} already consistent"
    )


def _writes(buckets: dict[str, list[tuple[str, str]]]) -> list[tuple[str, str]]:
    """Every address this run would write, paired with the cause to mirror it under.

    ONE LIST FOR ``--limit`` TO CUT, so the cap means "N writes this run" rather than "N of each
    kind", which is what an operator running a first pass against production actually asked for.

    The allow-list direction carries :data:`access_roster.MIRROR_ACCESS_SUSPENDED` even where the
    row is REJECTED, and that is a deliberate simplification WITH A COST, so it is stated: the two
    causes differ only in the sentence written onto the empanelment, and re-reading each row to pick
    between them would be a second query per address for a shade of wording. A rejected person's
    mirrored note will therefore say "suspended this address on the platform allow-list" where the
    endpoint would have said "rejected this address's request". Both are true of somebody the
    allow-list bars; if that distinction ever matters more than the query does, the plan already
    holds the status and can carry it here.
    """
    plan_rows = [
        (address, access_roster.MIRROR_ACCESS_SUSPENDED) for address, _why in buckets["empanelment"]
    ]
    plan_rows += [
        (address, access_roster.MIRROR_EMPANELMENT_ENDED) for address, _why in buckets["access"]
    ]
    return plan_rows


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--fix",
        action="store_true",
        help="actually mirror the suspensions. Without it this is a DRY RUN and writes nothing.",
    )
    parser.add_argument(
        # The older spelling, kept working on purpose and documented in the module docstring, for
        # the reason its sibling keeps it: an unrecognised flag at the moment somebody is applying a
        # fix costs more than a second entry in --help. Same ``dest``, so the two cannot disagree.
        "--execute",
        dest="fix",
        action="store_true",
        help="the earlier spelling of --fix; identical in effect.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="mirror at most N pairs this run (0 = no cap). The plan is printed in full either way.",
    )
    args = parser.parse_args()

    _console_that_cannot_kill_the_run()
    await _connect()
    try:
        buckets = await plan()
        mode = "FIX" if args.fix else "DRY RUN"
        print(f"[{mode}] cross-roster suspension mirror backfill\n")
        report(buckets, fix=args.fix)

        if not args.fix:
            # THE FINAL LINE OF A DRY RUN SAYS THAT NOTHING HAPPENED, in those words, at the bottom
            # where somebody who scrolled past four sections of addresses is looking. A report that
            # lists thirty people under "WOULD SUSPEND" and then stops reads, to a tired operator,
            # like a report of thirty rows suspended.
            print(f"\nCOUNT — {_counts(buckets)}. NOTHING WAS WRITTEN; re-run with --fix to apply.")
            return

        wanted = _writes(buckets)
        wanted = wanted[: args.limit] if args.limit > 0 else wanted
        mirrored = 0
        declined = 0
        for address, cause in wanted:
            # ``mirror_suspension`` and not a write of this script's own: the asymmetry, the alias
            # keys, the idempotent WHERE clauses, the note and the guard all live in that one
            # function, and a second implementation here is a second place for them to be got
            # wrong. ``actor_id`` stays None — no administrator ran this at anybody.
            changed = await access_roster.mirror_suspension(address, cause, actor_id=None)
            if changed:
                mirrored += changed
                print(f"  mirrored {cause} onto {changed} row(s) for {address}")
            else:
                # TWO DIFFERENT THINGS, AND THE LINE SAYS SO BECAUSE THE SCRIPT CANNOT TELL THEM
                # APART. Either the target row changed between the plan and this write — somebody
                # suspended it by hand, or restored the other side — or the mirror FAILED and
                # swallowed the failure, which is what it is built to do rather than break an
                # administrator's request. The failure is logged at ERROR by the mirror itself, so
                # a run reporting declines is a run whose log is worth reading.
                declined += 1
                print(
                    f"  skipped {address} (nothing left to change, or the mirror failed and "
                    "logged it — check the log before assuming the former)"
                )
        if args.limit > 0 and len(_writes(buckets)) > args.limit:
            print(
                f"CAPPED at --limit {args.limit}; {len(_writes(buckets)) - args.limit} pair(s) "
                "remain. Run again to continue."
            )
        # ``mirrored`` counts ROWS and ``declined`` counts ADDRESSES, deliberately: one address can
        # carry two rows where a Gmail mailbox is spelled two ways, and a count line that quietly
        # equated the two would under-report exactly the case this whole family of functions was
        # written for.
        print(
            f"\nCOUNT — {_counts(buckets)}. {mirrored} row(s) suspended across "
            f"{len(wanted) - declined} address(es), {declined} address(es) declined at the write."
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
