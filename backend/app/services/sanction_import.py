"""BULK SANCTION ORDERS: reconciling what a sheet says against what the rosters know.

The parser (``sanction_orders_xlsx``) answers "what do the cells say". This module answers the two
questions that need the database — **who are these people**, and **is there anything here only a
human can decide** — and then records the orders the human agreed to.

══ THE ONE SHAPE THAT MAKES THIS DIFFERENT FROM THE OTHER TWO IMPORTERS ═════════════════════════

"Designer Name(s)" and "Designer Email(s)" each hold a comma-separated list and the Nth name goes
with the Nth address. Nothing else in this product has two columns that correspond positionally, and
a positional correspondence is a thing a spreadsheet loses silently: one extra comma, one name typed
into the wrong cell, one row sorted while only half the columns were selected, and every pairing
below it is wrong while every cell still looks perfectly reasonable.

So the whole design of this module is one rule:

    **AUTO-RESOLVE EVERYTHING MECHANICAL. ASK ONLY WHERE A HUMAN COULD LEGITIMATELY HAVE MEANT
    SOMETHING DIFFERENT. REFUSE, WITHOUT ASKING, ANYTHING THAT WOULD OVERTURN A DECISION AN
    ADMINISTRATOR TOOK ON A SCREEN.**

A confirmation dialog that asks about everything is as useless as one that asks about nothing: an
officer who is asked forty questions answers them the way they answer a cookie banner.

══ EVERY WAY THE TWO COLUMNS CAN FAIL TO TALLY, AND WHAT HAPPENS TO EACH ════════════════════════

**A** = auto-resolved, imported, a ``warning`` problem says what was assumed.
**H** = the row goes to the human; nothing about it is written until they answer.
**R** = refused outright, an ``error`` problem says why, and it is NEVER offered for confirmation.

── STRUCTURAL: the two cells disagree about SHAPE ───────────────────────────────────────────────

 1. **An empty segment from a doubled or trailing comma** — ``"Ramesh, , Kavita"``. **A.** Dropped
    and counted, by :func:`sanction_orders_xlsx.split_list_cell`. **THIS HAPPENS FIRST, BEFORE THE
    COUNTS ARE COMPARED**, and the ordering is the whole point: left in, a trailing comma in one
    cell and not the other manufactures a count mismatch (#2) out of a typo, and sends a perfectly
    unambiguous row to a human for a decision there was nothing to decide.

 2. **N names, M addresses, N ≠ M, both non-empty.** **H.** This cannot be guessed — an extra name
    could belong at any position, and pairing "as far as they go" would file work under the wrong
    person from the mismatch down. Both lists are shown, paired as far as they go, with the surplus
    named; the officer re-pairs or drops the row.

 3. **Names cell empty, addresses populated.** **A** where every address resolves to somebody this
    product already has a name for, with a warning naming the names it used; **H** where any address
    is new, because ``SanctionOrderCreate.designerName`` is required and nobody in the building
    knows what that person is called. (The briefing called this wholly auto-resolvable; it is not —
    a brand-new address is the ORDINARY case here, and it is precisely the case with no name to
    fall back on.)

 4. **Addresses cell empty, names populated.** **H**, always, even when every name resolves to
    exactly one active roster row. A name is not an identity. Where a unique active roster row does
    match, its address is PRE-FILLED so the officer is accepting a proposal rather than typing;
    where it does not, the officer supplies it. Never silently picked: the cost of being wrong is a
    workshop and a credential issued to somebody who shares a name with the intended designer.

 5. **Both cells empty.** **R.** The order names nobody, and a sanction order that names nobody is
    not a sanction order.

── IDENTITY: a name or an address does not resolve ──────────────────────────────────────────────

 6. **The address is on no roster and has no account.** **A, and this is the ORDINARY CASE, not an
    error.** The whole point of a sanction order is that the designer is not here yet: the order
    admits them, empanels them, mints the account and issues the sign-in link. No problem is
    emitted, because nothing was assumed.

 7. **The name differs from the name on the record the address resolves to.** **A.** The ADDRESS is
    the identity. The order is recorded under the resolved account and **the stored record is not
    changed** — a warning says both spellings, in the shape ``artisan_import._report_disagreements``
    already uses. Compared after folding case and whitespace, so "RAMESH  KUMAR" against "Ramesh
    Kumar" is silent: that is a difference in typing and not a difference in fact.

 8. **The name and the address name two DIFFERENT KNOWN PEOPLE.** **H.** This is #7's dangerous
    twin and the officer's "where the two do not tally" case in its sharpest form — the signature of
    a column that has slipped by one row. It is distinguished from #7 by a deliberately narrow test:
    the address must already belong to somebody with a name on file, AND the sheet's name must
    independently match an active roster row that is NOT this address. Both candidates are shown
    with their name and address; the officer picks one or drops the row. **The narrowness is the
    feature** — a test that fired whenever a name merely existed elsewhere would ask about every
    order naming a common name, which is the "asks about everything" failure above.

 9. **The name matches more than one roster row, and the address decided.** **Not asked, and no
    warning either.** That two other designers share this name is a fact about the roster and not
    about this row, and a warning about it is noise on a row where nothing was assumed. It becomes
    #4's problem only when the name is being used AS the identity.

10. **The address is on the roster but the empanelment was ENDED.** **R.** ``SANCTION_EMPANELMENT_
    ENDED``, verbatim from the single-order path. Not confirmable: restoring an empanelment is an
    administrator's act on the roster screen, and a spreadsheet must not be able to take it.

11. **The address is valid and the account is NOT empanelled at all** (no roster row). **A** — this
    is #6 with an account already in existence, and ``ensure_empanelled`` writes the row inside the
    order's own transaction. Only an empanelment somebody ENDED is a refusal; never having had one
    is the ordinary state of a designer being sanctioned for the first time.

── DUPLICATION ──────────────────────────────────────────────────────────────────────────────────

12. **The same address twice in one cell.** **A.** Collapsed keeping the first position, with a
    warning naming it. The paired name is dropped with it, so the correspondence survives the
    collapse — dropping one side only is how a de-duplication turns into a mis-pairing.

13. **Two spellings in one cell that are one mailbox** — ``r.kumar@gmail.com`` and
    ``rkumar+dch@gmail.com``. **A**, collapsed on ``designers.canonical_email``, with a warning
    naming BOTH spellings, because this one is invisible otherwise. It is also not cosmetic: two
    rows for one account would violate ``SanctionOrderDesigner``'s composite primary key at the last
    statement of the order's transaction.

14. **The same designer named on two different ROWS of the sheet.** **Not a failure at all.** Two
    sanction orders for one designer is ordinary — quite unlike ``artisan_import``, where a person
    cannot be recorded twice. No problem is emitted and nothing is collapsed across rows.

15. **The same order number twice in the sheet, or already in the register.** **R** for both, with
    different sentences: the in-file one names the other Excel row (in the parser); the register one
    is ``SANCTION_DUPLICATE_TEMPLATE``, which names the existing order, its workshop, its designer
    and the day it was recorded.

── STANDING: refusals that survive any confirmation ─────────────────────────────────────────────

16. **The address is BARRED on the platform allow-list** (REJECTED or SUSPENDED). **R.**
17. **The account exists and cannot run a design workshop** (INSPECTOR, PROFESSOR, a directorate
    tier). **R.** Naming them would write a viewer row ``load_workshop_or_404`` refuses to honour.
18. **The address is one of the uploading officer's own spellings.** **R.** The person who approves
    the money is not the person who spends it — and compared over ``email_match_keys`` on both
    sides, so one dot does not step over it.
19. **Two accounts already answer to one mailbox.** **R.** Picking one is not a decision an import
    takes on an institution's behalf.

**#10 AND #16-19 ARE THE FIVE THAT ARE NEVER OFFERED FOR CONFIRMATION**, and that is the governing
rule rather than five separate decisions: each is a fact about a decision an administrator took on a
screen, and a confirmation step that could overturn one would make a spreadsheet the senior
authority. Every one of their sentences is imported from ``services/sanction_orders`` and not
re-worded, so the refusal an officer meets here is byte-for-byte the one they meet on the form —
which is what ``test_the_refusal_sentence_is_identical_on_every_surface`` exists to keep true.

══ WHITESPACE AND CASE ══════════════════════════════════════════════════════════════════════════

Folded and silent, everywhere, in both columns. NFKC, then every run of whitespace (including the
non-breaking and zero-width kinds a pasted cell carries) to one space, then case. An officer is not
asked about "Ramesh  Kumar" versus "Ramesh Kumar", and the address is lower-cased the way both
rosters already lower-case it. **What is stored is never the folded form** — the fold is for
comparison only, and a name folded onto a ministry record would be the app deciding how somebody's
name is written.

══ ONE TRANSACTION PER ORDER, LOOPED — AND THAT IS A DELIBERATE DIVERGENCE ══════════════════════

``annual_plan.apply_parsed_plan`` puts the WHOLE SHEET in one ``db.tx()`` and argues at length for
it, because "a directory is a document, not a stream of independent records". **This importer does
the opposite and the next reader must not assume one copied the other.**

A sanction order is not a line in a document; it is an instrument. Each one is already individually
all-or-nothing — that is the guarantee ``create_from_sanction``'s transaction exists to give — and
that is exactly the guarantee an officer needs. Two hundred orders in ONE transaction would be up to
1400 writes plus 200 account creations inside a single 30-second window, and **one bad row at
position 190 would roll back 189 recorded ministry orders**. Looped, a failure part-way leaves N
recorded orders and a report saying precisely which; the officer corrects the rest of the sheet and
uploads it again, and the ones already recorded are refused as duplicates by number rather than
recorded twice.

``MAX_SANCTION_ROWS`` is what bounds the loop; see the parser for why that number is 200 and not the
annual plan's 600.

══ NO SIGN-IN LINKS ARE MINTED BY AN IMPORT ═════════════════════════════════════════════════════

``create_from_sanction`` mints one per account it creates and returns them in its answer. This
module **throws them away**, deliberately, and the officer re-issues each from the per-row button on
the register.

Two hundred one-time credentials returned on one screen changes the SECURITY posture of the feature
rather than its ergonomics: the link is shown once, cannot be shown again, and the officer's
clipboard is the only transport there is, so a screen holding two hundred of them is a screen whose
accidental closure strands two hundred designers — and whose accidental screenshot is two hundred
live credentials. The 4-per-hour-per-designer throttle would also refuse most of a sheet that names
one designer repeatedly. The cost of this decision is real and is stated on the officer's screen:
every imported designer whose account was created needs their link re-issued by hand.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import HTTPException

from app.core.db import db
from app.schemas.sanction_orders import (
    MAX_SANCTION_DESIGNERS,
    SanctionImportConfirm,
    SanctionImportRowIn,
    SanctionOrderCoDesigner,
    SanctionOrderImportRow,
)
from app.services import designers, sanction_orders
from app.services.sanction_orders_xlsx import (
    MAX_SANCTION_ROWS,
    ParsedSanctionRow,
    ParsedSanctionSheet,
    fold_name,
    fold_order_no,
)
from app.services.xlsx_table import ParseProblem

log: logging.Logger = logging.getLogger(__name__)

__all__ = [
    "MAX_UPLOAD_BYTES",
    "apply_confirmed_rows",
    "review_sheet",
]

#: Four megabytes, the number ``annual_plan`` uses, mirrored on the client as its own ``MAX_BYTES``.
#:
#: The SERVER'S is the one that decides; the client's copy exists so the refusal arrives before a
#: ministry letterhead PDF is pushed up a village connection. A sheet of 200 sanction orders with
#: six columns is tens of kilobytes, so anything near this ceiling is not the sheet.
MAX_UPLOAD_BYTES = 4 * 1024 * 1024

#: The verdicts a reviewed row can carry. Spelled as constants because the client branches on them
#: and a typo in a string literal on either side is a row that renders in no panel at all.
READY = "ready"
REVIEW = "review"
REFUSED = "refused"


# --------------------------------------------------------------------------------------
# What the review produces
# --------------------------------------------------------------------------------------


@dataclass
class ProposedDesigner:
    """One person this row proposes to name, after the sheet and the rosters have been reconciled.

    ``userId`` is ``None`` for the ordinary case (#6) and that is not a missing value: it means this
    order will MINT the account, which is what the whole feature is for. The client draws the
    difference, because "a new account will be created for this person" and "this order will be
    filed under an account that already exists" are two different things for an officer to agree to.

    ``empanelled`` is ``False`` for somebody who has simply never been empanelled, which is ordinary
    and is not a refusal (#11); an empanelment that was ENDED never reaches this dataclass, because
    it is refused one function up.
    """

    name: str
    email: str
    canonical: str
    userId: str | None = None
    accountExists: bool = False
    empanelled: bool = False
    #: Where the name came from when the sheet did not give one: ``"record"`` for #3, ``"sheet"``
    #: otherwise. Rendered, because "we filled this in for you" is exactly the assumption an officer
    #: should be able to see and disagree with.
    nameSource: str = "sheet"

    def payload(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "email": self.email,
            "canonicalEmail": self.canonical,
            "userId": self.userId,
            "accountExists": self.accountExists,
            "empanelled": self.empanelled,
            "nameSource": self.nameSource,
        }


@dataclass
class RowQuestion:
    """One thing about this row that only the officer can settle.

    ``code`` is for the client's layout and ``question`` is the sentence a person reads; both travel
    because a client that had only the code would have to re-word the question, and this repository
    has four places that could paraphrase a rule and one of them already cost an officer their
    understanding of who owns what.

    ``candidates`` is populated only where there is a closed set to choose FROM (#4's roster match,
    #8's two people). An empty list is not an empty dropdown: it means the answer has to be typed.
    """

    code: str
    question: str
    candidates: list[dict[str, Any]] = field(default_factory=list)

    def payload(self) -> dict[str, Any]:
        return {"code": self.code, "question": self.question, "candidates": self.candidates}


@dataclass
class ReviewedRow:
    """One sheet row with a verdict on it."""

    sheetRow: int
    sanctionOrderNo: str
    sanctionOrderDate: date | None
    sanctionAmount: Decimal | None
    notes: str | None
    verdict: str
    designers: list[ProposedDesigner] = field(default_factory=list)
    questions: list[RowQuestion] = field(default_factory=list)
    #: Why this row is refused. Always set when ``verdict == REFUSED`` and always ``None`` otherwise
    #: — a reason on a row that is going to be recorded is a sentence nothing renders.
    reason: str | None = None
    #: The surplus, for #2 only: the entries that had no partner. Drawn beside the paired ones so
    #: the officer can see WHERE the two lists stopped agreeing rather than only that they did.
    unpairedNames: list[str] = field(default_factory=list)
    unpairedEmails: list[str] = field(default_factory=list)

    def payload(self) -> dict[str, Any]:
        return {
            "sheetRow": self.sheetRow,
            "sanctionOrderNo": self.sanctionOrderNo,
            "sanctionOrderDate": (
                self.sanctionOrderDate.isoformat() if self.sanctionOrderDate else None
            ),
            # A DECIMAL STRING AND NEVER A NUMBER, at this layer as at every other. See
            # ``schemas/sanction_orders``: ``jsonable_encoder`` turns a ``Decimal`` into a ``float``,
            # and a preview that rendered 4,50,000.00000000001 would be a preview an officer cannot
            # confirm — and would send that float straight back on the confirm.
            "sanctionAmount": (str(self.sanctionAmount) if self.sanctionAmount is not None else None),
            "notes": self.notes,
            "verdict": self.verdict,
            "reason": self.reason,
            "designers": [designer.payload() for designer in self.designers],
            "questions": [question.payload() for question in self.questions],
            "unpairedNames": list(self.unpairedNames),
            "unpairedEmails": list(self.unpairedEmails),
        }


# --------------------------------------------------------------------------------------
# The reconciliation
# --------------------------------------------------------------------------------------


def _problem(
    row: int | None, severity: str, reason: str, sheet: str | None, value: str | None = None
) -> ParseProblem:
    return ParseProblem(sheet or "", row, severity, reason, value)


async def _roster_rows_by_name(names: list[str]) -> dict[str, list[Any]]:
    """Folded name → the ACTIVE roster rows spelled that way. One query for the whole sheet.

    **ACTIVE ONLY, AND THAT IS NOT AN OPTIMISATION.** This index answers two questions — "whose
    address is this, when the sheet gave only a name" (#4) and "does this name independently name
    somebody else" (#8) — and a suspended roster row is the right answer to neither. Proposing a
    suspended designer's address in #4 would walk the officer into a refusal they had been invited
    to make; counting one in #8 would ask about a collision with somebody who cannot be named
    anyway.

    ONE ``find_many`` WITH AN ``OR`` OF INSENSITIVE EQUALITIES, and not one query per name. The
    alternative on a two-hundred-row sheet is four hundred round trips before the officer sees
    anything. The list is bounded by :data:`_NAME_LOOKUP_CAP`; past it the index is simply smaller
    and the two features that use it degrade to "no proposal" and "not detected", which is the
    honest failure — see :func:`review_sheet`, which says so on the report rather than silently.

    ``fullName`` IS NULLABLE ON ``DesignerRoster`` and most rows carry one only because
    ``ensure_empanelled`` copies the administrator's own spelling off the allow-list. A row with no
    name simply does not appear in this index, which is correct: it cannot match a name.
    """
    wanted = [name for name in names if name][:_NAME_LOOKUP_CAP]
    if not wanted:
        return {}
    rows = await db.designerroster.find_many(
        where={
            "isActive": True,
            "OR": [{"fullName": {"equals": name, "mode": "insensitive"}} for name in wanted],
        },
        take=_NAME_LOOKUP_CAP * 4,
    )
    index: dict[str, list[Any]] = {}
    for row in rows:
        folded = fold_name(getattr(row, "fullName", None))
        if folded:
            index.setdefault(folded, []).append(row)
    return index


#: How many distinct names one sheet may look up by name.
#:
#: A ceiling on a query this builds an ``OR`` for, not a product rule. Two hundred rows of a
#: realistic sheet carry well under a hundred distinct names; a sheet that carries eight thousand is
#: a malformed file, and an ``OR`` of eight thousand insensitive equalities is a query that will not
#: come back. What is lost past the cap is stated on the report rather than silently absorbed.
_NAME_LOOKUP_CAP = 400


def _pair_cells(
    row: ParsedSanctionRow, sheet: str | None, problems: list[ParseProblem]
) -> tuple[list[tuple[str, str]], list[str], list[str], list[RowQuestion]]:
    """The two cells into ``[(name, address)]``, plus whatever could not be paired.

    Everything in here is structural — it needs no database and it is where failures #1, #2, #3, #4,
    #5, #12 and #13 are decided. Returns ``(pairs, unpaired_names, unpaired_emails, questions)``;
    ``pairs`` may carry an empty name (the address is known and the name will be taken from the
    record) or an empty address (the officer must supply one).
    """
    names = list(row.designerNames)
    emails = list(row.designerEmails)
    questions: list[RowQuestion] = []

    # ── #12 AND #13: COLLAPSE ON THE MAILBOX, DROPPING THE PAIRED NAME WITH IT ────────────────
    # The paired name goes with the address it was a duplicate of. Dropping one side only is how a
    # de-duplication turns into a mis-pairing — "Ramesh, Ramesh, Kavita" against
    # "r@gmail.com, r@gmail.com, k@gmail.com" would otherwise leave two names and two addresses
    # offset by one, and Kavita would be recorded under Ramesh's mailbox.
    aligned = len(names) == len(emails) and bool(emails)
    kept_emails: list[str] = []
    kept_names: list[str] = []
    seen: dict[str, str] = {}
    for index, address in enumerate(emails):
        mailbox = designers.canonical_email(address)
        if not mailbox:
            # Not an address this system can use. Kept in place rather than dropped, so the counts
            # still line up and the standing verdict can refuse it by name with its own sentence.
            kept_emails.append(address)
            if aligned:
                kept_names.append(names[index])
            continue
        if mailbox in seen:
            first = seen[mailbox]
            problems.append(
                _problem(
                    row.sheetRow,
                    "warning",
                    (
                        f"'{address}' and '{first}' are the same mailbox, so this order names that "
                        "designer once. The first spelling was kept."
                        if first.lower() != address.lower()
                        else f"'{address}' is named twice on this row; it was recorded once."
                    ),
                    sheet,
                    address,
                )
            )
            continue
        seen[mailbox] = address
        kept_emails.append(address)
        if aligned:
            kept_names.append(names[index])
    if aligned:
        names = kept_names
    emails = kept_emails

    # ── #5: BOTH EMPTY ────────────────────────────────────────────────────────────────────────
    if not names and not emails:
        return [], [], [], questions

    # ── #4: ADDRESSES EMPTY. Always a question; the proposal is filled in one level up. ───────
    if not emails:
        questions.append(
            RowQuestion(
                "no-addresses",
                (
                    "This row names "
                    + _english_list(names)
                    + " but gives no email address. A name is not an identity — two designers share "
                    "a name more often than this register would like — so the address has to be "
                    "confirmed before an account is created or a workshop is opened."
                ),
            )
        )
        return [(name, "") for name in names], [], [], questions

    # ── #3: NAMES EMPTY. Resolved against the records one level up; may still become a question.
    if not names:
        return [("", address) for address in emails], [], [], questions

    # ── #2: COUNTS DIFFER ─────────────────────────────────────────────────────────────────────
    if len(names) != len(emails):
        paired = min(len(names), len(emails))
        questions.append(
            RowQuestion(
                "counts-differ",
                (
                    f"This row names {len(names)} "
                    f"{'designer' if len(names) == 1 else 'designers'} and gives {len(emails)} "
                    f"{'address' if len(emails) == 1 else 'addresses'}. The first {paired} of each "
                    "have been paired in the order they were typed; which designer the rest belong "
                    "to cannot be worked out from the sheet."
                ),
            )
        )
        return (
            list(zip(names[:paired], emails[:paired], strict=True)),
            names[paired:],
            emails[paired:],
            questions,
        )

    return list(zip(names, emails, strict=True)), [], [], questions


def _english_list(items: list[str]) -> str:
    """``"A, B and C"``. One sentence-builder, so no question reads like a machine."""
    clean = [item for item in items if item]
    if not clean:
        return "nobody"
    if len(clean) == 1:
        return clean[0]
    return ", ".join(clean[:-1]) + " and " + clean[-1]


def _candidate(name: str | None, email: str | None, *, source: str) -> dict[str, Any]:
    return {"name": name or "", "email": email or "", "source": source}


async def _review_one_row(
    row: ParsedSanctionRow,
    *,
    sheet: str | None,
    officer: Any,
    by_name: dict[str, list[Any]],
    problems: list[ParseProblem],
    standing: dict[str, Any],
    roster_rows: dict[str, Any],
) -> ReviewedRow:
    """One sheet row, reconciled. The order of the checks below is the order of the verdicts.

    **R BEATS H, ALWAYS.** A row that both asks a question and trips a standing refusal is REFUSED
    and is not offered for confirmation, because confirming it would be the officer agreeing to
    something the product will not do — and because the five standing refusals are administrators'
    decisions that a spreadsheet must not be able to overturn.
    """
    reviewed = ReviewedRow(
        sheetRow=row.sheetRow,
        sanctionOrderNo=row.sanctionOrderNo,
        sanctionOrderDate=row.sanctionOrderDate,
        sanctionAmount=row.sanctionAmount,
        notes=row.notes,
        verdict=READY,
    )

    def refuse(reason: str) -> ReviewedRow:
        reviewed.verdict = REFUSED
        reviewed.reason = reason
        # ── AND THE QUESTIONS GO WITH IT. R BEATS H, AND A REFUSED ROW MUST ASK NOTHING. ────────
        # A row can reach here having already collected one: mismatched counts (a question) on a row
        # that ALSO names a barred address (a refusal). Leaving the question on it would put a
        # control on the officer's screen that cannot change the outcome — they would re-pair the
        # names, press confirm, and meet the same refusal — and it would imply that settling the
        # pairing was what the row needed. The refusal sentence is the whole story of a refused row.
        #
        # ``test_a_standing_refusal_beats_a_question_on_the_same_row`` is what found this: the first
        # draft set the verdict and left the list alone, which passed every single-cause test.
        reviewed.questions = []
        problems.append(_problem(row.sheetRow, "error", reason, sheet, row.sanctionOrderNo))
        return reviewed

    # ── THE THREE FACTS OF THE INSTRUMENT ─────────────────────────────────────────────────────
    # The parser has already said, in its own words, WHY each of these is missing (an unreadable
    # date, an amount with two figures in it). What it cannot say is what that means for the ORDER,
    # which is this: an instrument with no date or no amount is not an instrument. The parser's
    # sentence and this one are both on the report and neither repeats the other.
    if row.sanctionOrderDate is None:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' has no date that could be read, so it cannot "
            "be recorded. The date is one of the three facts the instrument consists of."
        )
    if row.sanctionAmount is None:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' has no amount that could be read, so it cannot "
            "be recorded. The amount is one of the three facts the instrument consists of."
        )

    # ── #15: ALREADY IN THE REGISTER ──────────────────────────────────────────────────────────
    # The in-file half is the parser's (it can see both rows); this is the register half, and its
    # sentence is ``SANCTION_DUPLICATE_TEMPLATE`` — which names the existing order, its workshop,
    # its designer and the day it was recorded, because the officer's next move differs between "I
    # already did this" and "the ministry issued two orders with one number".
    taken = await sanction_orders.duplicate_reason(fold_order_no(row.sanctionOrderNo))
    if taken is not None:
        return refuse(taken)

    pairs, unpaired_names, unpaired_emails, questions = _pair_cells(row, sheet, problems)
    reviewed.unpairedNames = unpaired_names
    reviewed.unpairedEmails = unpaired_emails
    reviewed.questions = questions

    # ── #5 ────────────────────────────────────────────────────────────────────────────────────
    if not pairs:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' names no designer at all — both the name and "
            "the address column are empty. An order authorises somebody's work; there is nobody "
            "here for it to authorise."
        )

    if len(pairs) > MAX_SANCTION_DESIGNERS:
        return refuse(
            f"Sanction order '{row.sanctionOrderNo}' names {len(pairs)} designers, and one order "
            f"can name at most {MAX_SANCTION_DESIGNERS} — the same number of people one workshop "
            "can be opened for. Split the work across two orders."
        )

    assumed_names: list[str] = []

    for name, address in pairs:
        if not address:
            # ── #4: the officer has to supply it. The roster is asked for a PROPOSAL only. ────
            matches = by_name.get(fold_name(name), [])
            question = reviewed.questions[0] if reviewed.questions else None
            if question is not None and question.code == "no-addresses":
                question.candidates.extend(
                    _candidate(
                        getattr(match, "fullName", None),
                        getattr(match, "email", None),
                        source="roster",
                    )
                    for match in matches
                )
            reviewed.designers.append(ProposedDesigner(name=name, email="", canonical=""))
            continue

        verdict = standing.get(designers.canonical_email(address))
        if verdict is None:
            verdict = await sanction_orders.designer_standing_verdict(address, officer=officer)
            standing[verdict.canonical] = verdict

        # ── #10 AND #16-19: THE FIVE THAT ARE NEVER OFFERED FOR CONFIRMATION ─────────────────
        if verdict.refusal is not None:
            return refuse(verdict.refusal.detail)

        account = verdict.user
        # ── THE ROSTER ROW IS READ ONCE PER MAILBOX, FOR TWO SEPARATE QUESTIONS ──────────────
        # "What is this person called" (the fallback when the account has no name, and when the
        # sheet gave none) and "are they empanelled". Both need the same row, so it is read once and
        # memoised alongside the standing verdict — a sheet naming one designer on five rows asks
        # this once. The memo is scoped to ONE request and is never process-lifetime: an
        # administrator ending an empanelment mid-import must be obeyed by the next upload.
        if verdict.canonical in roster_rows:
            roster = roster_rows[verdict.canonical]
        else:
            roster = await db.designerroster.find_first(where={"email": {"in": verdict.keys}})
            roster_rows[verdict.canonical] = roster
        # ``isActive`` IS THE EMPANELMENT AND ``None`` IS NOT A REFUSAL. Never having been empanelled
        # is the ordinary state of somebody being sanctioned for the first time (#11); an empanelment
        # somebody ENDED never reaches this line, because ``designer_standing_verdict`` refused it.
        empanelled = bool(roster is not None and getattr(roster, "isActive", False))

        on_file = (
            str(getattr(account, "name", "") or "").strip()
            or str(getattr(roster, "fullName", "") or "").strip()
        )
        proposed = ProposedDesigner(
            name=name,
            email=address,
            canonical=verdict.canonical,
            userId=getattr(account, "id", None),
            accountExists=account is not None,
            empanelled=empanelled,
        )

        if not name:
            # ── #3: THE NAME CELL WAS EMPTY ──────────────────────────────────────────────────
            if on_file:
                proposed.name = on_file
                proposed.nameSource = "record"
                assumed_names.append(on_file)
            else:
                # The address is new AND nobody gave a name. There is nothing in the building that
                # knows what this person is called, and ``designerName`` is required — so this is
                # the one arm of #3 that a human has to answer.
                reviewed.questions.append(
                    RowQuestion(
                        "unknown-name",
                        (
                            f"No name was given for {address}, and this address has never been used "
                            "on this platform, so there is no record to take a name from. A "
                            "sanction order has to name the designer it was issued to."
                        ),
                    )
                )
        elif on_file and fold_name(on_file) != fold_name(name):
            # ── #7 vs #8: THE NAME DISAGREES WITH THE RECORD THE ADDRESS RESOLVES TO ─────────
            # The narrow test: does the sheet's name INDEPENDENTLY name somebody else who is on the
            # roster and active? If it does, the two columns name two different known people and
            # only the officer can say which they meant (#8). If it does not, the address is the
            # identity and the disagreement is a spelling (#7).
            others = [
                match
                for match in by_name.get(fold_name(name), [])
                if designers.canonical_email(getattr(match, "email", None)) != verdict.canonical
            ]
            if others:
                reviewed.questions.append(
                    RowQuestion(
                        "name-and-address-disagree",
                        (
                            f"This row pairs the name '{name}' with the address {address}, but that "
                            f"address belongs to {on_file} and '{name}' is somebody else on the "
                            "designer roster. A column that has slipped by one row looks exactly "
                            "like this. Which of them is this order for?"
                        ),
                        candidates=[
                            _candidate(on_file, address, source="address"),
                            *(
                                _candidate(
                                    getattr(match, "fullName", None),
                                    getattr(match, "email", None),
                                    source="name",
                                )
                                for match in others
                            ),
                        ],
                    )
                )
            else:
                problems.append(
                    _problem(
                        row.sheetRow,
                        "warning",
                        (
                            f"Designer name differs: this sheet says '{name}' and the stored record "
                            f"for {address} says '{on_file}'. The address is what identifies a "
                            f"designer, so the order was prepared for {on_file} and the stored "
                            "record was not changed."
                        ),
                        sheet,
                        name,
                    )
                )
                # #7's resolution: the ADDRESS is the identity, and the name that goes on the order
                # is the one the record holds. Keeping the sheet's spelling here would put a name on
                # a ministry instrument that disagrees with the account it was filed under.
                proposed.name = on_file
                proposed.nameSource = "record"

        reviewed.designers.append(proposed)

    if assumed_names:
        problems.append(
            _problem(
                row.sheetRow,
                "warning",
                (
                    "No designer name was given, so the name already on file was used: "
                    f"{_english_list(assumed_names)}."
                ),
                sheet,
                None,
            )
        )

    if reviewed.questions:
        reviewed.verdict = REVIEW
    return reviewed


async def review_sheet(
    parsed: ParsedSanctionSheet, *, officer: Any
) -> dict[str, Any]:
    """The whole sheet, reconciled into three lists and a bag of problems.

    ``ready`` will be recorded as it stands, ``needsReview`` is the confirmation step's subject, and
    ``refused`` is never recorded whatever the officer answers. **All three travel, including the
    empty ones**, because the counts have to add up on screen: ``rowsRead = recorded + skipped +
    refused`` is the arithmetic ``ImportReport`` argues for, and it can only be checked if every
    number including the zeroes is drawn.

    NOTHING IS WRITTEN HERE. Not an account, not a roster row, not a ledger row — this is a read, and
    an officer who uploads the wrong file and closes the tab has changed nothing.
    """
    problems = list(parsed.problems)
    names_to_look_up: list[str] = []
    for row in parsed.rows:
        names_to_look_up.extend(row.designerNames)
    distinct = list(dict.fromkeys(name for name in names_to_look_up if name))
    by_name = await _roster_rows_by_name(distinct)
    if len(distinct) > _NAME_LOOKUP_CAP:
        # SAID RATHER THAN SWALLOWED. Past the cap the two name-driven features degrade — a row with
        # no address gets no proposal, and a name/address disagreement is not detected — and an
        # officer who was not told would read "nothing to review" as "nothing wrong".
        problems.append(
            _problem(
                # None AND NOT 0 — the client draws "This workbook" for a null row and "Row 0" for a
                # zero, and there is no row 0 in a spreadsheet. This problem is about the sheet.
                None,
                "warning",
                (
                    f"This sheet names {len(distinct)} different designers, and names were looked up "
                    f"against the roster for the first {_NAME_LOOKUP_CAP} of them. Rows below that "
                    "were still checked by email address, which is what identifies a designer; what "
                    "was not checked is whether a NAME on those rows belongs to somebody else."
                ),
                parsed.sheet,
            )
        )

    # ONE MEMO FOR THE WHOLE SHEET, keyed on the canonical mailbox. A sheet naming one designer on
    # five rows asks the four standing questions about them once — see ``designer_standing_verdict``,
    # which was split out of ``resolve_named_designer`` for exactly this. It is scoped to one request
    # and is never a process-lifetime cache: an administrator barring an address mid-import must be
    # obeyed by the next upload, not by the next restart.
    standing: dict[str, Any] = {}
    #: The same memo for the roster row behind each mailbox, which answers two questions per
    #: designer ("what are they called" and "are they empanelled") and is otherwise read twice.
    roster_rows: dict[str, Any] = {}

    reviewed: list[ReviewedRow] = []
    for row in parsed.rows:
        reviewed.append(
            await _review_one_row(
                row,
                sheet=parsed.sheet,
                officer=officer,
                by_name=by_name,
                problems=problems,
                standing=standing,
                roster_rows=roster_rows,
            )
        )

    return {
        "sheet": parsed.sheet,
        "sourceFilename": parsed.sourceFilename,
        # THE SHEET'S OWN COUNT AND NOT ``len(ready) + len(needsReview) + len(refused)``. A row the
        # parser could not read at all (no order number, a duplicate of an earlier row) is counted
        # here and appears in none of the three lists, so deriving this number from them would
        # quietly under-report the size of the file the officer uploaded.
        "rowsRead": parsed.rowCount + _rows_the_parser_dropped(problems, parsed),
        "ready": [row.payload() for row in reviewed if row.verdict == READY],
        "needsReview": [row.payload() for row in reviewed if row.verdict == REVIEW],
        "refused": [row.payload() for row in reviewed if row.verdict == REFUSED],
        "problems": [problem.payload() for problem in problems],
    }


def _rows_the_parser_dropped(problems: list[ParseProblem], parsed: ParsedSanctionSheet) -> int:
    """How many rows of the sheet never became a :class:`ParsedSanctionRow`.

    A row with no order number, a second row carrying a number already used, a row past
    ``MAX_SANCTION_ROWS`` — each leaves an ``error`` problem with its own Excel gutter row and no
    parsed row. They belong in ``rowsRead`` because the officer typed them and can see them in the
    file; leaving them out would make the panel's arithmetic say the sheet was smaller than it is.

    Counted by DISTINCT ROW NUMBER, because one row can carry several problems (an unreadable date
    AND an over-long name) and counting problems would double-count it.
    """
    parsed_rows = {row.sheetRow for row in parsed.rows}
    dropped = {
        problem.row
        for problem in problems
        if problem.severity == "error" and problem.row and problem.row not in parsed_rows
    }
    return len(dropped)


# --------------------------------------------------------------------------------------
# The write
# --------------------------------------------------------------------------------------


def _create_body(row: SanctionImportRowIn, *, sheet_filename: str | None) -> SanctionOrderImportRow:
    """One confirmed row as the body the single-order path already knows how to record.

    **THE LEAD IS ELEMENT 0 AND THE REST ARE ``coDesigners``**, which is where the confirmation
    screen's positional list is split back into the shape ``SanctionOrderCreate`` has always had.
    Position 0 is the lead everywhere in this feature: the pro-forma's help sheet says so, the
    officer's confirmation screen says so, and this is the line that makes it true.

    Validated by ``SanctionOrderImportRow`` — the officer's own form schema plus two provenance
    columns — so an imported order cannot hold a value the form would refuse.
    """
    lead, *rest = row.designers
    return SanctionOrderImportRow(
        sanctionOrderNo=row.sanctionOrderNo,
        sanctionOrderDate=row.sanctionOrderDate,
        sanctionAmount=row.sanctionAmount,
        designerName=lead.name,
        designerEmail=lead.email,
        notes=row.notes,
        coDesigners=[SanctionOrderCoDesigner(name=co.name, email=co.email) for co in rest],
        sourceFilename=sheet_filename,
        sheetRow=row.sheetRow,
    )


async def apply_confirmed_rows(body: SanctionImportConfirm, officer: Any) -> dict[str, Any]:
    """Record every row the officer said to record. ONE TRANSACTION PER ORDER — see the header.

    Every refusal that the preview could answer is asked AGAIN here, because it has to be: the
    confirmation is stateless, so what arrives is the client's word for what the sheet said, and
    minutes may have passed. ``create_from_sanction`` is the one door — nothing here writes a row
    itself — so a stale or a hand-edited confirmation meets exactly the rules the officer's own form
    meets, in exactly the same words.

    **THE LOOP NEVER RAISES OUT.** An ``HTTPException`` from one order is that ORDER's refusal and
    not the import's: two hundred rows must not be lost to the one at position 190. It is turned
    into an ``error`` problem carrying its own detail, verbatim, against the Excel row it came from.
    A non-HTTP exception is caught too and logged with the row number — it is the shape a driver
    fault takes, and an officer who gets a 500 after 189 orders were recorded cannot tell that from
    an import that did nothing.
    """
    recorded: list[dict[str, Any]] = []
    problems: list[ParseProblem] = []
    skipped = 0
    # SEEDED FROM THE CLIENT'S ECHO AND NOT FROM ZERO. A row the preview refused for an unreadable
    # date or amount has no legal shape in ``SanctionImportRowIn`` — the date is a required ``date``
    # — so it cannot be sent back at all, and counting only what arrives would leave the report's
    # ``rowsRead = recorded + skipped + refused`` short by exactly the rows nobody could describe.
    # Their SENTENCES are already on the preview, which the officer has seen and the client keeps on
    # screen beside this report; what is added here is only the arithmetic.
    refused = body.refusedBeforeConfirm
    accounts_created = 0

    for row in body.rows[:MAX_SANCTION_ROWS]:
        if row.action == "skip":
            # COUNTED AND NAMED, NEVER SILENTLY DROPPED. The officer chose this, and the report
            # lists the Excel row so they can correct the sheet and upload it again.
            skipped += 1
            problems.append(
                _problem(
                    row.sheetRow,
                    "warning",
                    (
                        f"Sanction order '{row.sanctionOrderNo}' was not recorded because you chose "
                        "to leave it out. Correct the row in the sheet and upload again if it "
                        "should be on the register."
                    ),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue

        if not row.designers:
            refused += 1
            problems.append(
                _problem(
                    row.sheetRow,
                    "error",
                    (
                        f"Sanction order '{row.sanctionOrderNo}' was sent for recording with no "
                        "designer on it, so nothing was written. An order authorises somebody's "
                        "work."
                    ),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue

        try:
            created = await sanction_orders.create_from_sanction(
                _create_body(row, sheet_filename=body.sourceFilename), officer
            )
        except HTTPException as exc:
            refused += 1
            problems.append(
                _problem(
                    row.sheetRow,
                    "error",
                    # VERBATIM. This is the fourth place in the stack that could paraphrase a
                    # refusal and the one where paraphrasing costs an officer their understanding of
                    # who owns what.
                    str(exc.detail),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue
        except Exception:
            # NOT A BLANKET SWALLOW OF A BUG — a blanket ``except`` around a write is how a create
            # that could not honour its body becomes a silent success. It is here because the unit
            # of failure is ONE ORDER: this loop's whole argument is that a fault at position 190
            # must not roll back 189 recorded ministry orders, and an exception that escaped would
            # do exactly that to the report rather than to the database. Logged with the row number
            # because the officer's sentence cannot carry a stack trace.
            refused += 1
            log.exception(
                "sanction import: row %s (%s) failed and was not recorded",
                row.sheetRow,
                row.sanctionOrderNo,
            )
            problems.append(
                _problem(
                    row.sheetRow,
                    "error",
                    (
                        f"Sanction order '{row.sanctionOrderNo}' could not be recorded because of a "
                        "fault on the server. Nothing was written for this row. Every other row in "
                        "this upload was handled independently; try this one on the form above."
                    ),
                    body.sheet,
                    row.sanctionOrderNo,
                )
            )
            continue

        order = created["sanctionOrder"]
        minted = [
            designer
            for designer in order.get("designers", [])
            if designer.get("accountCreated")
        ]
        accounts_created += len(minted)
        recorded.append(
            {
                "sheetRow": row.sheetRow,
                "sanctionOrderId": order["id"],
                "sanctionOrderNo": order["sanctionOrderNo"],
                "designWorkshopId": order["designWorkshopId"],
                "designers": order.get("designers", []),
                "accountsCreated": len(minted),
            }
        )

    ledger = await _write_the_ledger_row(
        body,
        officer=officer,
        recorded=len(recorded),
        skipped=skipped,
        refused=refused,
        problems=problems,
    )

    return {
        "sheet": body.sheet,
        "sourceFilename": body.sourceFilename,
        # ── THE FOUR COUNTS, AND THEY MUST ADD UP ON SCREEN ──────────────────────────────────
        # ``rowsRead = recorded + skipped + refused`` is the arithmetic ``ImportReport`` argues for.
        # ``rowsRead`` is the count the PREVIEW reported and the client echoed back, not
        # ``len(body.rows)``: if those two differ, rows went missing between the two requests, and
        # the panel drawing both is the only way anybody would ever see it.
        "rowsRead": body.rowsRead,
        "recorded": len(recorded),
        "skipped": skipped,
        "refused": refused,
        # HOW MANY PEOPLE THIS PRESS LET IN. Not derivable from the row counts — one order can mint
        # three accounts and the next none — and it is the number an officer most needs, because
        # every one of them needs a sign-in link re-issued by hand. See the module header for why no
        # links are minted here.
        "accountsCreated": accounts_created,
        "credentialLinksIssued": 0,
        "created": recorded,
        "importId": ledger,
        "problems": [problem.payload() for problem in problems],
    }


async def _write_the_ledger_row(
    body: SanctionImportConfirm,
    *,
    officer: Any,
    recorded: int,
    skipped: int,
    refused: int,
    problems: list[ParseProblem],
) -> str | None:
    """One ``SanctionOrderImport`` row: which upload did this, and who pressed it.

    THE SHAPE IS ``DwArtisanImport``'S AND THE CASE FOR IT IS STRONGER HERE, NOT WEAKER. That import
    files people onto a workshop; this one MINTS ACCOUNTS AND ADMITS PEOPLE TO THE PLATFORM. "Which
    upload created these forty accounts, and who pressed it" is not derivable from anything else
    once the workbook is closed — and the workbook itself is deliberately NOT stored, exactly as the
    artisan import says of its own.

    **WRITTEN LAST AND NEVER ALLOWED TO FAIL THE IMPORT.** The orders are already committed, each in
    its own transaction; an exception here would answer 500 to an officer whose orders were recorded
    and send them to record them all again into two hundred duplicates. It is a ledger, and a ledger
    that cost the thing it records would be the wrong trade — so it is logged and the report still
    goes back, with ``importId: null`` saying honestly that no ledger row exists.
    """
    try:
        row = await db.sanctionorderimport.create(
            data={
                "sourceFilename": body.sourceFilename,
                "sheetName": body.sheet,
                "rowsRead": body.rowsRead,
                "recorded": recorded,
                "skipped": skipped,
                "refused": refused,
                # THE SENTENCES AS THE CLIENT RENDERED THEM, as JSON rather than a child table —
                # the reason ``DwArtisanImport.problems`` is JSON: these are written to be read once,
                # not rows anything queries.
                "problems": [problem.payload() for problem in problems],
                "uploadedById": getattr(officer, "id", None),
            }
        )
    except Exception:
        log.exception(
            "sanction import: the ledger row could not be written. %s orders WERE recorded.",
            recorded,
        )
        return None
    return str(getattr(row, "id", "")) or None
