"""Stamp ``User.sessionsValidFrom`` for people who were barred BEFORE that stamp existed. Dry run.

WHY THIS EXISTS. Until 2026-09-03 the four doors that end somebody's access to this product wrote a
roster status and nothing else, and every roster status in this repository is read on the SIGN-IN
path. So an administrator who suspended a departing colleague stopped their next sign-in and left
the browser and the phone they were already signed in on working for the rest of the token's life —
``JWT_EXPIRES_MINUTES``, seven days by default. All four doors now stamp
``User.sessionsValidFrom``, which ``deps._user_from_bearer`` compares against the token's ``iat`` on
EVERY authenticated request:

  ``access.suspend_access_entry`` (DELETE)        the allow-list, unguarded on the transition
  ``access.decide_access_request`` (REJECT)       the allow-list's other barring door
  ``designers.suspend_roster_entry`` (DELETE)     the empanelment, guarded (see below)
  ``designers.update_roster_entry`` (isActive off)  the same act through the edit form

**THEY DO NOT REPAIR THE BACKLOG, AND NOTHING ELSE WILL EITHER.** Nothing revisits a row written
before the rule existed, and the two paths that might notice both deliberately decline: the
empanelment doors return early on a bar that already stands (a second click must not re-enact the
cross-roster mirror, which can undo a restoration an administrator made on purpose), and a sign-in
cannot help because the person barred is not signing in — that is the entire point of the defect.
This script is the other half, and it is the same arrangement
``scripts/backfill_roster_suspension_mirror.py`` has with the mirror it backfills.

**ONLY A BAR IN THE LAST ``JWT_EXPIRES_MINUTES`` CAN STILL BE HOLDING A LIVE TOKEN.** A token minted
before somebody was barred in March expired in March; stamping their row today revokes nothing and
never could. So the plan splits every candidate in two and prints both — STILL LIVE, where a stamp
actually ends a session somebody is in right now, and EXPIRED, where the column is merely a
watermark that ought to be true. ``--write`` applies the first list. ``--write --include-expired``
applies both, and is for the operator who wants the column to be honest about every revocation this
product has ever made rather than only about the ones still doing harm. The split is computed from
the deployment's OWN ``JWT_EXPIRES_MINUTES``, read out of the same settings object the token minter
uses, because a box that has lengthened its tokens has lengthened this window with them.

**THE VALUE WRITTEN IS THE MOMENT OF THE BARRING, NOT THE MOMENT OF THE RUN**, and that is what
makes this script idempotent. Writing ``now()`` instead would move the watermark on every run, so a
second run would report the same rows again and change them again — the failure mode
``backfill_stage_search_text`` avoids by comparing before it writes. It costs nothing in strictness:
no token can have been minted between the bar and today, because the bar is exactly what refuses a
sign-in.

WHICH COLUMN THAT MOMENT IS IN IS PER STATUS, AND THIS PARAGRAPH USED TO GET IT WRONG (corrected
2026-09-03). It said "``decidedAt`` on an allow-list row, ``revokedAt`` on an empanelment". On a
SUSPENDED allow-list row ``decidedAt`` is the day the person was APPROVED — the suspend path keeps
it deliberately — so a colleague barred this morning was reported as barred two years ago and
stamped with a watermark that refuses none of their live tokens. :func:`_barred_at` now answers per
status, with the writes that make each arm true quoted in its docstring.

**THE EMPANELMENT SIDE IS GUARDED AND THE ALLOW-LIST SIDE IS NOT**, and the asymmetry is the same
one the endpoints carry. Being barred from the allow-list is being barred from the application —
there is no role, rank or account state for which a token issued before it should still work. Ending
an EMPANELMENT is narrower: a professor or an admin who is on the designer roster because they run
workshops keeps their access to the product (``auth.assert_roster_admits`` returns early for any
role that is not DESIGNER, and argues at length why collapsing the two refusals would be an outage).
Signing them out here would be that outage delivered by a script instead of by a click. The guard is
``access_roster.admissions_an_empanelment_carries`` — the SAME function the endpoint consults and
the same one the mirror backfill consults, called rather than reproduced, because a backfill whose
plan and whose write ask different questions prints a report that is fiction.

**AN EMPANELMENT THAT HAS ALREADY BEEN MIRRORED IS HANDLED BY THE ALLOW-LIST BUCKET, NOT THIS ONE.**
Once ``mirror_suspension`` has suspended the admission, that row is no longer ACTIVE, so the guard
answers ``[]`` about it for ever — and the address arrives in the barred-allow-list bucket instead,
where no carve-out is needed. The two buckets therefore partition the population rather than
competing for it, and a candidate found in both is reported and written once, under the allow-list.

IT NEVER CLEARS A STAMP AND NEVER LOWERS ONE. Every write is conditional on the stored value still
being NULL or still being older than the bar, so a row somebody has already stamped by hand — or a
password redemption's more recent stamp — is left exactly where it is. Restoring access is not this
script's business in either direction; ``sessionsValidFrom`` is not cleared when somebody is let back
in, deliberately (see ``access.decide_access_request``: they simply sign in again).

**THE STAMP DOES NOT REACH A RUNNING API INSTANTLY, AND AN OPERATOR SHOULD NOT BE SURPRISED BY IT.**
``deps.resolve_user`` caches the identity row for ``AUTH_USER_CACHE_TTL_SECONDS`` (5 by default) and
this process is not the API's, so it cannot invalidate that cache the way the endpoints do. The
revocation takes effect on every instance within that TTL. Seconds, against a token that had days
left; worth knowing, not worth waiting for.

Usage, from ``backend/``::

    python -m scripts.backfill_sessions_valid_from                        # DRY RUN, writes nothing
    python -m scripts.backfill_sessions_valid_from --write
    python -m scripts.backfill_sessions_valid_from --write --include-expired
    python -m scripts.backfill_sessions_valid_from --write --limit 20

**THE SAFE INVOCATION IS THE ONE WITH NO ARGUMENTS.** An operator handed this script and typing its
name reads a report; nothing in this repository is written by a command somebody ran to find out
what it does. ``--execute`` is accepted as a second spelling of ``--write`` for the reason its
siblings accept it — the other scripts in this tree use that word, and an unrecognised flag at the
moment somebody is applying a fix under pressure costs more than a line in ``--help``.

**ON WINDOWS**, run it with UTF-8 mode on — ``set PYTHONUTF8=1``, or ``py -X utf8 -m
scripts.backfill_sessions_valid_from``. Prisma's own config loader opens ``backend/pyproject.toml``
with the interpreter's default codec, that file contains a rupee sign and several em dashes, and on
a cp1252 box the read dies inside ``db.connect()`` with a ``UnicodeDecodeError`` naming a byte
offset in a file this script never mentions. :func:`_connect` catches exactly that and says so.
"""

import argparse
import asyncio
import sys
from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.services import access_roster
from app.services.designers import canonical_email, normalise_email

#: A ceiling on each candidate read, so a run pointed at a large production database cannot pull a
#: whole table into memory. Its own number rather than a borrowed one, and far above any plausible
#: deployment of a single institution's portal; if a run reports that it was hit, the answer is to
#: raise it deliberately rather than to trust a cut list — a cut list here is a person who is still
#: signed in and whom this run silently did not find.
CANDIDATE_READ_LIMIT = 50_000

#: The buckets, in the order the report prints them. Named here so the plan, the report, the count
#: line and the write loop cannot end up disagreeing about what exists.
BUCKETS: tuple[str, ...] = ("live", "expired", "protected", "already")


def _console_that_cannot_kill_the_run() -> None:
    """Make an unencodable character degrade to ``?`` instead of ending the run.

    ``scripts/backfill_roster_suspension_mirror.py``'s rule, for its reason, which applies here word
    for word: this report is full of em dashes, Windows falls back to the ANSI codepage the moment
    the stream is redirected, and the failure is not a mangled character but a ``UnicodeEncodeError``
    raised out of ``print`` PART-WAY THROUGH. In a dry run that costs a re-run; in a ``--write`` run
    it aborts the loop between two writes and leaves the operator with a half-applied repair.

    The ERROR HANDLER is reconfigured and not the encoding: forcing UTF-8 onto a console still on
    codepage 437 replaces a crash with mojibake in an email address, which is worse in a report whose
    whole purpose is to be read as a list of addresses.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(errors="replace")


async def _connect() -> None:
    """``connect_db`` plus the one Windows failure that does not name itself.

    Prisma's config loader reads ``backend/pyproject.toml`` with the interpreter's default codec.
    That file carries a rupee sign and em dashes, so on a box that has not been put into UTF-8 mode
    the connection dies with a ``UnicodeDecodeError`` about a byte position in a TOML file this
    script never mentions — which every reader takes for a broken install and none take for a locale
    setting. It cannot be fixed from inside the process: ``PYTHONUTF8`` is read once at start-up.
    """
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise SystemExit(
            "Could not connect: Prisma's config loader could not decode backend/pyproject.toml "
            f"with this interpreter's default codec ({exc.encoding}). This is a locale setting, "
            "not a broken install. Re-run with UTF-8 mode on:\n"
            "    set PYTHONUTF8=1  &&  python -m scripts.backfill_sessions_valid_from\n"
            "or:\n"
            "    py -X utf8 -m scripts.backfill_sessions_valid_from"
        ) from exc


async def _read(label: str, model: Any, where: dict[str, Any]) -> list[Any]:
    """One capped read, with the cut reported rather than swallowed.

    A cut list here is not merely a short report: every row past the cut is somebody who may still be
    holding a live token that this run does not know about, and the operator's reasonable reading of
    a clean report is that there is nothing left to revoke.
    """
    rows = await model.find_many(where=where, take=CANDIDATE_READ_LIMIT + 1)
    if len(rows) > CANDIDATE_READ_LIMIT:
        print(
            f"WARNING: more than {CANDIDATE_READ_LIMIT} {label} rows matched, so this run is "
            "working from a CUT list and will silently leave some live sessions un-revoked. Raise "
            "CANDIDATE_READ_LIMIT and run again before believing the totals below."
        )
        return rows[:CANDIDATE_READ_LIMIT]
    return rows


def _barred_at(row: Any) -> datetime:
    """When this standing ended. One question, two tables, and THREE answers rather than one.

    **``decidedAt`` IS NOT "WHEN THEY WERE BARRED" ON A SUSPENDED ROW, AND READING IT AS ONE BROKE
    BOTH HALVES OF THIS SCRIPT — corrected 2026-09-03.** This function used to be
    ``getattr(row, "decidedAt", None) or getattr(row, "revokedAt", None) or row.updatedAt``, on the
    stated belief that ``AccessRoster`` records a bar in ``decidedAt``. It records a DECISION there,
    and the writes were read to check it:

    * ``access_roster.admit(..., decided=True)`` writes ``decidedAt = now`` ON APPROVAL;
    * ``routes/access.suspend_access_entry`` writes ``decidedAt: row.decidedAt or _now()`` — it
      KEEPS whatever is there, deliberately, so a second click cannot move the date. So does the
      cross-roster mirror, ``access_roster._bar_an_ended_empanelment``, statement for statement;
    * only the REJECT arm of ``routes/access.decide_access_request`` writes ``decidedAt = _now()``
      unconditionally.

    So on an ACTIVE → SUSPENDED row — the ordinary "an admin pressed Suspend" path — ``decidedAt``
    is THE DAY THEY WERE APPROVED, which for a colleague of two years is two years ago. Both
    consequences are silent and both are wrong in the unsafe direction: the account is filed in the
    EXPIRED bucket (bar looks ancient, so no token can still be live) when it was barred this
    morning and its token has six days to run, and the watermark it would write is dated before
    every token that account holds, so the stamp REVOKES NOTHING while the report says it did.

    THE THREE ARMS, EACH FROM THE WRITE THAT MAKES IT TRUE:

    * **REJECTED** — ``decidedAt`` IS the bar, written by the reject arm at the moment of it. Exact,
      so it is preferred. (A SUSPENDED row later moved to REJECTED gets the LATER date, which
      refuses strictly more tokens than the original suspension would have. That is the safe way for
      this to be imprecise.)
    * **SUSPENDED** — ``decidedAt`` cannot be trusted and there is no ``revokedAt`` on this table, so
      the bar is ``updatedAt``. It is the timestamp of the LAST write to the row, so it is never
      earlier than the suspension and may be later (an admin editing the notes afterwards). Later is
      the direction that refuses more tokens on an account that is barred anyway.
    * **NEITHER** — a ``DesignerRoster`` row, which has no ``status`` column at all. ``revokedAt`` is
      stamped at the moment ``isActive`` goes false and is deliberately preserved on a repeat
      suspension, so it is the exact bar; ``updatedAt`` is the fallback for a grandfathered row that
      predates the column being written.

    ``updatedAt`` CARRIES ALL THREE FALLBACKS because both tables declare it, it is NOT NULL, and it
    cannot be earlier than the write that barred the row.
    """
    status = access_roster.status_of(row)
    if status == access_roster.REJECTED:
        return getattr(row, "decidedAt", None) or row.updatedAt
    if status == access_roster.SUSPENDED:
        # NOT ``decidedAt``: on this row it is the APPROVAL date. See the docstring.
        return row.updatedAt
    return getattr(row, "revokedAt", None) or row.updatedAt


def _needs_stamp(account: Any, barred_at: datetime) -> bool:
    """Is this account's watermark missing, or older than the bar it should reflect?

    The ENTIRE idempotence of this script, expressed once and then repeated verbatim in the write's
    own ``WHERE`` clause so that a row somebody stamps between the plan and the write is not
    stamped twice. NEVER lowers a stamp: a more recent value is a password redemption
    (``auth.set_password``) or a later bar, and both of those refuse strictly more tokens than this
    one would.
    """
    stored = account.sessionsValidFrom
    return stored is None or stored < barred_at


def _stored_as(address: str, row: Any) -> str:
    """Name a row's own spelling, but only where it is not the address being reported.

    ``scripts/backfill_roster_suspension_mirror.py``'s helper, for its reason: every line of this
    report prints the MAILBOX, which since canonicalisation need not be the string any row is filed
    under, and an operator who types the printed address into an admin screen's search box is shown
    nothing at all. Empty for every address that is not a Gmail alias, so the ordinary line keeps its
    ordinary shape.
    """
    stored = normalise_email(row.email)
    return "" if stored == address else f"; filed under {stored!r}, the same mailbox"


def _accounts_by_mailbox(accounts: list[Any]) -> dict[str, list[Any]]:
    """Every account, indexed by the MAILBOX it belongs to rather than by its stored spelling.

    **ONE READ OF THE ACCOUNTS TABLE RATHER THAN ``accounts_on_the_mailbox`` PER ADDRESS, AND THE
    DIFFERENCE IS NOT A SHORTCUT.** That function exists to answer about ONE address inside a
    request, and it pays for exactness on a Gmail mailbox by sweeping the whole Gmail subset of the
    user table — correct once, ruinous a few hundred times in a loop. What it is exact ABOUT is the
    fold, ``canonical_email``, and that is the function used here: applying the shared fold to every
    row once produces the same partition the per-address sweep would produce, address by address,
    for one query instead of hundreds. This is a performance decision about a read, not a second
    copy of a rule — the rule (which spellings are one mailbox) is imported.

    A LIST PER MAILBOX AND NOT ONE ROW, because two spellings of one Gmail mailbox can hold two
    accounts. They are the same person by definition, so barring the address bars both, and a
    dictionary that kept the first would leave the second signed in — the exact half-fix this whole
    family of functions keeps being written about.
    """
    indexed: dict[str, list[Any]] = {}
    for account in accounts:
        indexed.setdefault(canonical_email(account.email), []).append(account)
    return indexed


#: One candidate: the mailbox, the account id, the stamp to write, and the sentence explaining it.
Candidate = tuple[str, str, datetime, str]


async def plan(*, window: timedelta) -> dict[str, list[Candidate]]:
    """Sort every account that may still be holding a revoked session into one of four buckets.

    Four narrow reads for the whole database rather than a query per address — the same shape as the
    mirror backfill, for the same reason: this runs against production with a small connection pool,
    and a per-row loop over a few hundred people is the pattern that turns a repair into an incident.

    THE ONE PER-ADDRESS QUERY IS THE GUARD, and it is worth its cost. It is only asked about ended
    empanelments that STILL hold a live designer-tier admission — the pre-mirror backlog, which is
    the inconsistency itself and is therefore small, and empty on a database the mirror has always
    been running against.
    """
    now = datetime.now(UTC)
    barred_access = await _read(
        "barred allow-list", db.accessroster, {"status": {"in": list(access_roster.BARRED)}}
    )
    ended_empanelments = await _read(
        "suspended empanelment", db.designerroster, {"isActive": False}
    )
    designer_admissions = await _read(
        "designer-tier admission",
        db.accessroster,
        {"status": access_roster.ACTIVE, "admitRole": "DESIGNER"},
    )
    accounts = await _read("account", db.user, {})

    by_mailbox = _accounts_by_mailbox(accounts)
    admitted = {canonical_email(row.email): row for row in designer_admissions}
    buckets: dict[str, list[Candidate]] = {name: [] for name in BUCKETS}
    seen: set[str] = set()

    def _file(mailbox: str, account: Any, barred_at: datetime, why: str) -> None:
        """Put one account in the bucket its bar date decides, or in ``already`` if it is current."""
        if account.id in seen:
            # THE ALLOW-LIST BUCKET RUNS FIRST AND WINS. An address can be barred on the allow-list
            # AND carry an ended empanelment; it is one revocation of one person, and reporting it
            # twice would double every count on the last line.
            return
        seen.add(account.id)
        if not _needs_stamp(account, barred_at):
            buckets["already"].append((mailbox, account.id, barred_at, why))
            return
        key = "live" if now - barred_at <= window else "expired"
        buckets[key].append((mailbox, account.id, barred_at, why))

    # ── THE ALLOW-LIST: barred from the application, so no carve-out applies ──────────────────────
    for row in barred_access:
        mailbox = canonical_email(row.email)
        barred_at = _barred_at(row)
        standing = access_roster.status_of(row)
        for account in by_mailbox.get(mailbox, []):
            _file(
                mailbox,
                account,
                barred_at,
                f"allow-list says {standing} since {barred_at:%Y-%m-%d}{_stored_as(mailbox, row)}",
            )

    # ── THE EMPANELMENT: narrower, and guarded ────────────────────────────────────────────────────
    for row in ended_empanelments:
        mailbox = canonical_email(row.email)
        candidates = [a for a in by_mailbox.get(mailbox, []) if a.id not in seen]
        if not candidates:
            continue
        admission = admitted.get(mailbox)
        if admission is None:
            # No LIVE designer-tier admission rests on this empanelment, and the address is not
            # barred either (that bucket ran first) — so either the mirror already suspended the
            # admission and this account is somebody else on the same mailbox, or the person is
            # admitted at another tier entirely. Both are somebody whose place in the application
            # does not depend on the empanelment. Reported rather than dropped, because "the script
            # did not mention them" is indistinguishable from "the script missed them".
            for account in candidates:
                seen.add(account.id)
                buckets["protected"].append(
                    (
                        mailbox,
                        account.id,
                        _barred_at(row),
                        "the empanelment ended, but no live designer-tier admission rests on it, "
                        "so this account's access to the product is not what the empanelment "
                        "granted",
                    )
                )
            continue
        carried = await access_roster.admissions_an_empanelment_carries(mailbox)
        if not carried:
            # THE GUARD DECLINED, WHICH IS THE OUTCOME THIS SCRIPT MOST NEEDS TO REPORT RATHER THAN
            # ACT ON. The usual reason is the one it exists for: an account on this mailbox is not a
            # designer — a professor or an admin who runs workshops — and ending their empanelment
            # neither bars them nor should sign them out. It also declines when its own Gmail sweep
            # was cut, which it logs at ERROR; either way the answer here is the same and a human
            # decides it on /admin/access.
            held = "/".join(
                sorted({str(getattr(a.role, "value", a.role)) for a in candidates})
            )
            for account in candidates:
                seen.add(account.id)
                buckets["protected"].append(
                    (
                        mailbox,
                        account.id,
                        _barred_at(row),
                        "the empanelment ended and the allow-list still admits them, but the guard "
                        f"declined — the account(s) on this mailbox hold {held}. Signing them out "
                        "would be an outage, not a repair",
                    )
                )
            continue
        barred_at = _barred_at(row)
        for account in candidates:
            _file(
                mailbox,
                account,
                barred_at,
                f"empanelment ended {barred_at:%Y-%m-%d} and the admission rested on it"
                f"{_stored_as(mailbox, row)}",
            )
    return buckets


def _expired_heading(*, write: bool, include_expired: bool, days: float) -> str:
    """The EXPIRED section's heading, which has to know about ``--include-expired`` (2026-09-03).

    IT CLAIMED THESE ROWS WERE BEING WRITTEN WHEN THEY WERE NOT. The heading had two arms, ``write``
    and not, and the write arm read "TO STAMP — EXPIRED … Written only because --include-expired was
    passed" on EVERY ``--write`` run — while :func:`_writes` puts this bucket in the plan only when
    that flag is actually set. So the ordinary repair run (``--write`` alone) printed a list of
    accounts under a heading saying they had been stamped, and the only correction was one line
    hundreds of addresses further down. An operator's reasonable reading of that report is that the
    backlog is cleared. The flag has to reach the heading, so it does.
    """
    aged = f"Barred longer ago than {days:.4g} day(s), so any token they held has expired on its own"
    if write and include_expired:
        return (
            f"TO STAMP — EXPIRED. {aged} and this stamp revokes nothing that is still running; the "
            "column is being made honest. Written because --include-expired was passed."
        )
    if write:
        return (
            f"NOT WRITTEN — EXPIRED. {aged}. This run did NOT pass --include-expired, so nothing "
            "below has been stamped and the backlog stands. Listed so it is visible."
        )
    if include_expired:
        return (
            f"WOULD STAMP — EXPIRED. {aged}, so this revokes nothing that is running; "
            "--include-expired was passed, so a --write run would take these too. Nothing has been "
            "written."
        )
    return (
        f"WOULD STAMP ONLY WITH --include-expired — EXPIRED. {aged} and a stamp revokes nothing. "
        "Listed so the backlog is visible, not because it is urgent."
    )


def report(
    buckets: dict[str, list[Candidate]],
    *,
    write: bool,
    window: timedelta,
    include_expired: bool,
) -> None:
    """Print all four buckets, in full, INCLUDING THE EMPTY ONES.

    The mirror backfill's rule and its reason: a section suppressed for being empty is
    indistinguishable from a section the script forgot, and the guess a reader makes about a missing
    "left alone" section is that the script signed somebody out who should not have been.

    ``include_expired`` IS AN ARGUMENT AND NOT AN OMISSION. This function decides what a heading
    CLAIMS, and one of the four claims is about whether rows were written — a question only that
    flag answers. See :func:`_expired_heading`. (2026-09-03)
    """
    days = window.total_seconds() / 86400
    headings = (
        (
            "live",
            (
                "TO STAMP — STILL LIVE. Barred within the last "
                f"{days:.4g} day(s) (JWT_EXPIRES_MINUTES), so a token from before the bar can still "
                "be in use RIGHT NOW and this stamp is what ends it."
                if write
                else "WOULD STAMP — STILL LIVE. Barred within the last "
                f"{days:.4g} day(s) (JWT_EXPIRES_MINUTES), so these people may be signed in right "
                "now on a token their bar should have killed. Nothing has been written."
            ),
        ),
        (
            "expired",
            _expired_heading(write=write, include_expired=include_expired, days=days),
        ),
        (
            "protected",
            "LEFT ALONE — the empanelment ended but this account's access to the application does "
            "not rest on it (auth.assert_roster_admits). Signing them out would be an outage "
            "delivered by a script. Nothing written; decide it on /admin/access if it is wrong.",
        ),
        (
            "already",
            "SKIPPED: ALREADY STAMPED — the watermark is already at or past the bar, so every token "
            "minted before it is refused today. Listed so a re-run visibly finds nothing to do.",
        ),
    )
    for key, heading in headings:
        rows = buckets[key]
        print(f"\n{heading} — {len(rows)}")
        for mailbox, account_id, _stamp, why in rows:
            print(f"  {mailbox}  [{account_id}]  ({why})")
        if not rows:
            print("  (none)")


def _counts(buckets: dict[str, list[Candidate]]) -> str:
    """The four bucket sizes and their total, for the one line at the bottom.

    A COUNT LINE THAT DOES NOT ADD UP IS THE POINT OF IT: every account this run considered lands in
    exactly one bucket, so an operator who expected forty and reads twelve knows to look at the reads
    rather than at the buckets.
    """
    total = sum(len(rows) for rows in buckets.values())
    return (
        f"{total} account(s): {len(buckets['live'])} still live, "
        f"{len(buckets['expired'])} expired, "
        f"{len(buckets['protected'])} left alone, "
        f"{len(buckets['already'])} already stamped"
    )


def _writes(buckets: dict[str, list[Candidate]], *, include_expired: bool) -> list[Candidate]:
    """Every account this run would stamp, in one list for ``--limit`` to cut.

    The cap therefore means "N stamps this run" rather than "N of each kind", which is what an
    operator running a first pass against production actually asked for. The still-live list comes
    first so that a capped run spends its budget on the sessions that are actually open.
    """
    rows = list(buckets["live"])
    if include_expired:
        rows += buckets["expired"]
    return rows


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--write",
        action="store_true",
        help="actually stamp sessionsValidFrom. Without it this is a DRY RUN and writes nothing.",
    )
    parser.add_argument(
        # The spelling the other scripts in this tree use, kept working on purpose. Same ``dest``,
        # so the two cannot disagree.
        "--execute",
        dest="write",
        action="store_true",
        help="the sibling scripts' spelling of --write; identical in effect.",
    )
    parser.add_argument(
        "--include-expired",
        action="store_true",
        help=(
            "also stamp rows barred longer ago than a token lives. Revokes nothing that is still "
            "running; makes the column honest about the whole backlog."
        ),
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="stamp at most N accounts this run (0 = no cap). The plan is printed in full either way.",
    )
    args = parser.parse_args()

    _console_that_cannot_kill_the_run()
    settings = get_settings()
    # READ FROM THE DEPLOYMENT'S OWN SETTING rather than written as "seven days". This is the same
    # number ``create_access_token`` mints against, so a box that lengthened its tokens lengthens
    # this window with them — and a box that shortened them stops being told that a March bar is
    # still urgent.
    window = timedelta(minutes=settings.jwt_expires_minutes)
    await _connect()
    try:
        buckets = await plan(window=window)
        mode = "WRITE" if args.write else "DRY RUN"
        print(f"[{mode}] sessionsValidFrom backfill for revocations that predate the stamp\n")
        report(
            buckets,
            write=args.write,
            window=window,
            # THE HEADING FOR THE EXPIRED BUCKET IS A CLAIM ABOUT WHETHER THOSE ROWS WERE WRITTEN,
            # and only this flag answers it — see ``_expired_heading``. (2026-09-03)
            include_expired=args.include_expired,
        )

        if not args.write:
            # THE FINAL LINE OF A DRY RUN SAYS THAT NOTHING HAPPENED, in those words, at the bottom
            # where somebody who scrolled past four sections of addresses is looking. A report that
            # lists thirty people under "WOULD STAMP" and then stops reads, to a tired operator, like
            # a report of thirty sessions ended.
            print(
                f"\nCOUNT — {_counts(buckets)}. NOTHING WAS WRITTEN; re-run with --write to apply "
                "(add --include-expired to take the historic rows as well)."
            )
            return

        wanted = _writes(buckets, include_expired=args.include_expired)
        capped = wanted[: args.limit] if args.limit > 0 else wanted
        stamped = 0
        skipped = 0
        for mailbox, account_id, barred_at, _why in capped:
            # THE GUARD IS REPEATED IN THE ``WHERE`` AND NOT ONLY IN THE PLAN, which is what makes a
            # row somebody stamps between the two a no-op rather than a downgrade. ``update_many``
            # because a conditional update is the only way to express it — ``update`` on the id alone
            # would overwrite a fresher watermark with an older one.
            #
            # ``invalidate_cached_user`` IS DELIBERATELY NOT CALLED (2026-09-03). It empties the
            # identity cache of the process that calls it, and this process serves no requests — the
            # running API's cache is out of any script's reach. The closing report names the real
            # bound instead (AUTH_USER_CACHE_TTL_SECONDS). This paragraph names the symbol on
            # purpose: tests/test_user_identity_cache.py sweeps every user-row writer for it, and a
            # written refusal is that sweep's honest answer where a call would be theatre.
            changed = await db.user.update_many(
                where={
                    "id": account_id,
                    "OR": [
                        {"sessionsValidFrom": None},
                        {"sessionsValidFrom": {"lt": barred_at}},
                    ],
                },
                data={"sessionsValidFrom": barred_at},
            )
            if changed:
                stamped += changed
                print(f"  stamped {mailbox} [{account_id}] at {barred_at.isoformat()}")
            else:
                skipped += 1
                print(
                    f"  skipped {mailbox} [{account_id}] (already stamped at or past that moment "
                    "— somebody wrote it between the plan and this line)"
                )
        if args.limit > 0 and len(wanted) > args.limit:
            print(
                f"CAPPED at --limit {args.limit}; {len(wanted) - args.limit} account(s) remain. "
                "Run again to continue."
            )
        if not args.include_expired and buckets["expired"]:
            print(
                f"NOT WRITTEN: {len(buckets['expired'])} account(s) barred longer ago than a token "
                "lives. Nothing there is signed in on a stale token; pass --include-expired to make "
                "the column honest about them too."
            )
        print(
            f"\nCOUNT — {_counts(buckets)}. {stamped} account(s) stamped, {skipped} already current "
            f"at the write. Every running API picks this up within AUTH_USER_CACHE_TTL_SECONDS "
            f"({settings.auth_user_cache_ttl_seconds:g}s) — this process cannot invalidate their "
            "identity caches."
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
