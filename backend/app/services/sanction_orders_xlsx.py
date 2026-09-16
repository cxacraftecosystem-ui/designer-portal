"""The ministry's sheet of sanction orders: the pro-forma it is typed into, and the reader that
gets it back out.

THE FOURTH .XLSX READER IN THIS REPOSITORY, and like ``annual_plan_xlsx`` it is built on
``app/services/xlsx_table.py`` rather than beside it. Every primitive that is about SPREADSHEETS —
header discovery by heading rather than by position, NFKC and non-breaking-space folding, the
two-pass formula map, the magic-byte refusals, :class:`ParseProblem` — is shared and is not
re-implemented here. What lives in this file is the part that is about THIS document: which columns
a sheet of sanction orders has, how the money is read, and the one thing no other reader in this
repository has had to do — **two columns that hold comma-separated lists which correspond
positionally**.

══ WHY IT IS A CLOSE COPY OF ``annual_plan_xlsx`` AND WHERE IT DELIBERATELY IS NOT ═══════════════

The structure is that file's, on purpose, so that an administrator's two workbooks behave the same
way and the next reader has one shape to learn: ``_COLUMN_ALIASES`` tried in dict order, ``_TEXT_CAPS``
mirroring the bounds of the model the values are written into, ``_clip_role`` that reports every clip,
``_report_unrecognised_columns`` with its text-only filter, and ``read_plan_date``'s day-first rule
IMPORTED rather than re-derived.

Three differences, each of which is a decision:

1. **NOTHING HERE IS PROMOTED INTO A STAGE**, so ``_TEXT_CAPS`` mirrors
   :class:`app.schemas.sanction_orders.SanctionOrderCreate` rather than a registry ``FieldSpec``.
   That is a stricter mirror rather than a looser one: the create schema is the door every one of
   these values goes through, and a cap here that is one character looser is a 422 the officer meets
   at the END of a two-hundred-row import instead of a warning they meet at the start of it.
2. **THE DATE FUNCTION IS IMPORTED, NOT COPIED.** ``read_plan_date`` is the day-first rule with the
   both-readings-valid warning, and a sanction date has exactly the hazard an annual-plan date has —
   ``03/04/2026`` is the third of April in every office that will send this file. A second copy of
   that rule would be a second place for it to drift. Its ``column_label`` argument is what lets one
   function name "the Sanction Date" here and "the Start Date" there.
3. **THERE IS NO DETAILS SHEET AND NO FILTER NOTE.** The annual plan's ``_details_filter`` machinery
   exists because a filtered export re-uploaded with ``withdrawAbsent`` withdraws the rows the filter
   hid. **The sanction register has no destructive flag and no delete at all**, so that hazard does
   not transfer and the machinery is deliberately absent. If a sanction EXPORT is ever built, say so
   in its own header, so that nobody copies the filter machinery for a round trip that cannot lose
   anything.

══ THE TWO COLUMNS THAT CORRESPOND POSITIONALLY ═════════════════════════════════════════════════

"Designer Name(s)" and "Designer Email(s)" each hold a comma-separated list, and the Nth name goes
with the Nth address. That is a shape no other sheet in this product has, and every way it can fail
is enumerated and resolved in ``app/services/sanction_import.py`` — this file's only job is to SPLIT
the cells faithfully and report what it had to assume.

SPLIT ON THE COMMA AND ON NOTHING ELSE. Not on ``;``, not on a newline, not on a slash:

* a person's name legitimately contains none of those, so a second separator buys nothing;
* a cell with a line break in it is a FORMATTING ACCIDENT (an office typed Alt+Enter between two
  names) and splitting on it silently would make one person out of two, or two out of one, with no
  symptom. It is folded to a space and REPORTED instead, which is the only outcome that lets the
  officer see what their sheet actually contains;
* a semicolon between two addresses is somebody's mail client's habit and is likewise reported
  rather than guessed at.

AN EMPTY SEGMENT IS DROPPED AND COUNTED, AND THAT HAS TO HAPPEN BEFORE THE TWO COUNTS ARE COMPARED.
``"Ramesh, , Kavita"`` is three segments and two names; a trailing comma is two segments and one
name. Left in, either would manufacture a count mismatch out of a typo and send a perfectly
unambiguous row to a human for a decision there was nothing to decide.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, InvalidOperation
from io import BytesIO
from typing import Any

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.worksheet import Worksheet

from app.services.annual_plan_xlsx import read_plan_date
from app.services.xlsx_report import _put
from app.services.xlsx_table import (
    FormulaCell,
    ParseProblem,
    XlsxTableError,
    as_text_rows,
    cell_text,
    clip,
    load_value_sheets,
    norm_header,
    pick_sheet,
)

__all__ = [
    "MAX_SANCTION_ROWS",
    "PRO_FORMA_FILENAME",
    "ParsedSanctionRow",
    "ParsedSanctionSheet",
    "SanctionXlsxError",
    "build_sanction_pro_forma",
    "parse_sanction_workbook",
    "split_list_cell",
]


PRO_FORMA_FILENAME = "sanction-orders-pro-forma.xlsx"

SHEET_ORDERS = "Sanction orders"
SHEET_HELP = "How to fill this in"

# Orange, and deliberately none of the three already in use: the questionnaire's purple (5B21B6),
# the artisan list's teal (0F766E), the annual plan's blue (1D4ED8). FOUR pro-formas now leave this
# building and an administrator with two of them open has only the colour of the header band to tell
# them apart at a glance. It is also the ministry accent the four ministry web screens carry, which
# is the one visual thread between the workbook and the screen it is uploaded on.
_BRAND = "9A3412"
_WHITE = "FFFFFFFF"
_GREY = "FF6B7280"

# ── THE CEILINGS ─────────────────────────────────────────────────────────────────────────────────
#
# 200 AND NOT THE ANNUAL PLAN'S 600, and the two numbers are calibrated to different documents. A
# year's PLAN is 200-300 workshops and is one document; a sheet of SANCTION ORDERS is a batch an
# office issued, and a ministry does not issue six hundred orders in one sitting the way it plans six
# hundred workshops.
#
# It also bounds something the annual plan's number does not. Each order is SEVEN-PLUS-N writes in
# its own transaction and up to N account creations, so 200 rows is already up to 1400 writes and 200
# new ``User`` rows — each of which admits somebody to the platform. Raising this is not a
# performance decision, it is a decision about how many people one press may let in.
MAX_SANCTION_ROWS = 200
MAX_ROWS_SCANNED = 5_000  # a sheet with a title block, blank spacer rows and a total row
MAX_SHEETS_SCANNED = 12
MAX_HEADER_SCAN_ROWS = 15

#: How many designers one ROW may name before the parser stops reading the cell.
#:
#: Lower than the schema's team cap on purpose, and it is a parser guard rather than a product rule:
#: a cell holding 2000 commas is a malformed file, not an order naming 2000 designers, and the
#: reconciliation in ``sanction_import`` is O(n) per designer against the roster. The schema's
#: ``MAX_SANCTION_DESIGNERS`` is what actually refuses an over-long team, with a sentence; this
#: number only stops one cell turning into unbounded work. Anything above it is reported, never
#: silently truncated.
MAX_DESIGNERS_PER_ROW = 40


class SanctionXlsxError(ValueError):
    """The upload is not a sheet of sanction orders this can read AT ALL.

    The whole FILE, never one row — the distinction :class:`ParseProblem` exists for. Its message is
    written to be shown to the officer as-is, and the route re-raises it as a 422 rather than
    paraphrasing it.
    """


# ── THE CLIP CAPS, AND WHAT EACH ONE MIRRORS ─────────────────────────────────────────────────────
#
# EVERY ONE OF THESE MIRRORS A FIELD ON ``SanctionOrderCreate``, which is the door every imported
# value goes through. The numbers are stated here rather than imported because the schema module
# imports THIS one (for ``MAX_SANCTION_ROWS``) and the reverse would be a cycle;
# ``tests/test_sanction_orders_xlsx.py::test_no_clip_cap_exceeds_the_field_it_is_written_into``
# reads both and fails if either side moves, which is the same protection
# ``_CAP_MIRRORS`` gives the annual plan.
#
# A clip that is one character LOOSER than its field is the expensive direction: the row parses, the
# officer confirms it, and the 422 arrives from pydantic at the moment the order is being written —
# by which time the officer has already made a decision about it on the confirmation screen.
_TEXT_CAPS: dict[str, int] = {
    "sanctionOrderNo": 120,  # SanctionOrderCreate.sanctionOrderNo
    "designerNames": 160,  # SanctionOrderCreate.designerName, applied PER NAME after the split
    "notes": 2_000,  # SanctionOrderCreate.notes
}

#: The parser role → the ``SanctionOrderCreate`` field its value is written into. Consumed only by
#: the contract test; kept beside the caps so the two cannot be edited apart.
_CAP_MIRRORS: dict[str, str] = {
    "sanctionOrderNo": "sanctionOrderNo",
    "designerNames": "designerName",
    "notes": "notes",
}


# ── THE COLUMNS ──────────────────────────────────────────────────────────────────────────────────
#
# Aliases are tried IN DICT ORDER and the first role that claims a heading keeps it, so the more
# specific spellings come first. "designer email" must be reachable before the bare "email", and
# "sanction amount" before the bare "amount", or a sheet with both headings loses one of them.
#
# ⚠ "sanction no" IS NOT AN ALIAS FOR ANY COLUMN BUT THE ORDER NUMBER HERE, AND IT IS AN ALIAS FOR
# ``workshopNo`` IN ``annual_plan_xlsx``. That is not a contradiction: the two sheets are different
# documents and the ministry uses the phrase for both, which is precisely why each reader has to say
# what it means by it. A sanction sheet uploaded to the annual-plan door lands its order numbers in
# the workshop-reference column and is refused by ``pick_sheet`` on the other columns; the reverse
# is refused here for want of a designer address.
_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "serialNo": ("sl no", "sl", "s no", "sr no", "serial no", "serial number", "sno", "srno", "no"),
    "sanctionOrderNo": (
        "sanction order no",
        "sanction order number",
        "sanction no",
        "sanction number",
        "order no",
        "order number",
        "sanction order",
        "so no",
        "sono",
    ),
    "sanctionOrderDate": (
        "sanction order date",
        "sanction date",
        "order date",
        "date of sanction",
        "date of order",
        "dated",
        "date",
    ),
    "sanctionAmount": (
        "sanction amount",
        "sanctioned amount",
        "amount sanctioned",
        "amount in rs",
        "amount rs",
        "sanctioned amount rs",
        "amount",
        "budget",
    ),
    "designerNames": (
        "designer name",
        "designer names",
        # ⚠ "designer name s" IS THE PRO-FORMA'S OWN HEADING, and it looks like a typo because
        # ``norm_header`` is not a word-boundary-aware thing: it strips punctuation to SPACES, so
        # "Designer Name(s)" folds to "designer name s" with the "s" standing alone. Without this
        # entry the blank pro-forma this module SHIPS would have had a column its own reader could
        # not find — an office would have filled in the designers and the app would have imported
        # orders naming nobody, under a 200. The ``_DECLARED`` assertion below is what caught it,
        # at import time, which is the whole reason that assertion exists.
        "designer name s",
        "name of designer",
        "names of designers",
        "name of designers",
        "designers",
        "designer",
    ),
    "designerEmails": (
        "designer email",
        "designer emails",
        #: The pro-forma's own "Designer Email(s)" — see the note on "designer name s" above.
        "designer email s",
        "designer email address",
        "designer email addresses",
        "designer gmail id",
        "designer gmail",
        "email address",
        "email addresses",
        "gmail id",
        "gmail",
        "emails",
        "email",
    ),
    "notes": ("notes", "note", "remarks", "remark", "comment", "comments", "observation"),
}

#: The one column a sheet MUST have for this reader to claim it at all.
#:
#: THE ORDER NUMBER AND NOT THE EMAIL, even though the email is the column that makes a row usable.
#: ``pick_sheet`` uses this to decide WHICH SHEET of a workbook is the data sheet, and the order
#: number is the heading that says "this sheet is a sanction register" — an office directory of
#: designers also has an email column and would otherwise be picked. A row missing its address is
#: then a per-ROW problem with a sentence, which is the right severity for it.
_REQUIRED_ROLE = "sanctionOrderNo"

_PRO_FORMA_COLUMNS: tuple[tuple[str, str], ...] = (
    ("sanctionOrderNo", "Sanction Order No."),
    ("sanctionOrderDate", "Sanction Date"),
    ("sanctionAmount", "Sanctioned Amount"),
    ("designerNames", "Designer Name(s)"),
    ("designerEmails", "Designer Email(s)"),
    ("notes", "Remarks"),
)

#: Coloured differently on the header band: the five an order cannot be recorded without.
_REQUIRED = frozenset({"sanctionOrderNo", "sanctionOrderDate", "sanctionAmount", "designerEmails"})

_WIDTHS: dict[str, int] = {
    "sanctionOrderNo": 22,
    "sanctionOrderDate": 14,
    "sanctionAmount": 18,
    "designerNames": 42,
    "designerEmails": 46,
    "notes": 34,
}

#: ⚠ THE IMPORT-TIME INVARIANT, AND IT IS THE ONE THAT CATCHES THE SILENT FAILURE.
#:
#: A heading PRINTED ON THE PRO-FORMA that no alias matches is a column an office fills in and the
#: app ignores, under a 201 saying the upload succeeded. There is no test run in production and no
#: screen that could show it, so it is asserted at IMPORT time — the shape ``artisan_xlsx`` uses —
#: and the process refuses to start rather than shipping a workbook that lies about what it reads.
_DECLARED = {role for role, _label in _PRO_FORMA_COLUMNS}
assert set(_COLUMN_ALIASES) >= _DECLARED, sorted(_DECLARED - set(_COLUMN_ALIASES))
for _role, _label in _PRO_FORMA_COLUMNS:
    assert norm_header(_label) in _COLUMN_ALIASES[_role], (_role, _label)


# --- What comes out -------------------------------------------------------------------------------


@dataclass
class ParsedSanctionRow:
    """One row of the sheet, split but NOT reconciled.

    Everything here is what the CELLS said, cleaned of the things only a spreadsheet reader can know
    about (non-breaking spaces, a formula with no cached result, a comma that separated nothing).
    Whether these names and these addresses describe the same people is a question about the ROSTER,
    it needs the database, and it is answered in ``app/services/sanction_import.py``. Keeping the two
    apart is what lets the whole of this module be tested without a fixture.

    ``designerNames`` and ``designerEmails`` are LISTS AND MAY BE DIFFERENT LENGTHS. That is not an
    error state at this layer — it is one of the sixteen things the reconciliation enumerates, and
    several of them are resolvable without asking anybody.
    """

    sheetRow: int
    sanctionOrderNo: str
    sanctionOrderKey: str
    sanctionOrderDate: date | None = None
    sanctionAmount: Decimal | None = None
    designerNames: list[str] = field(default_factory=list)
    designerEmails: list[str] = field(default_factory=list)
    notes: str | None = None


@dataclass
class ParsedSanctionSheet:
    sheet: str | None = None
    sourceFilename: str | None = None
    rows: list[ParsedSanctionRow] = field(default_factory=list)
    problems: list[ParseProblem] = field(default_factory=list)

    @property
    def rowCount(self) -> int:
        return len(self.rows)


# --- The key fold ---------------------------------------------------------------------------------

#: Whitespace of every kind Excel and Word between them can put in a cell, including the
#: non-breaking space a reference pasted out of a PDF routinely carries and the zero-width space
#: that is invisible in an editor as well as in a diff. Spelled as escapes for that reason — this is
#: ``annual_plan_xlsx._SPACEY`` verbatim, and the duplication is one regex rather than a shared
#: import so that neither file's folding can be changed for the other's sake by accident.
_SPACEY = re.compile(r"[\s\u00a0\u1680\u2000-\u200b\u202f\u205f\u3000]+")

#: What a cell may hold instead of a comma when an office meant one. Never split on — only reported.
_OTHER_SEPARATORS = re.compile(r"[;\n\r\u2028\u2029]")

#: Everything a person types into a money cell that is not part of the number: the rupee sign, the
#: word, the thousands separators, and the "/-" an Indian office writes after an amount.
_MONEY_NOISE = re.compile(r"(?:rs\.?|inr|₹|/-|,|\s)+", re.IGNORECASE)


def fold_name(value: Any) -> str:
    """A person's name reduced to the thing two spellings of it have in common.

    NFKC first, so a full-width or ligature form folds onto its ordinary one; then every run of
    whitespace to a single space; then case. USED FOR COMPARISON AND NEVER FOR STORAGE — the stored
    name is what the officer or the roster spelled, and a folded name on a ministry record would be
    the app deciding how somebody's name is written.
    """
    text = unicodedata.normalize("NFKC", str(value or ""))
    return _SPACEY.sub(" ", text).strip().casefold()


def split_list_cell(
    raw: Any, *, sheet: str, row: int, column_label: str, problems: list[ParseProblem]
) -> list[str]:
    """One comma-separated cell into its entries, reporting every assumption it had to make.

    THREE THINGS HAPPEN HERE AND ALL THREE ARE REPORTABLE:

    1. **A separator that is not a comma is folded to a space and reported.** See the module
       docstring: a line break between two names is an office pressing Alt+Enter, and splitting on it
       would silently make two people out of one whose name happens to wrap. Reported so the officer
       can see what their own cell contains, and so a genuinely two-name cell typed with semicolons
       is corrected at the source rather than imported as one person with a very long name.
    2. **An empty segment is dropped and reported by its neighbours.** ``"Ramesh, , Kavita"`` is a
       typo and not a third designer. THIS HAPPENS BEFORE THE TWO CELLS' COUNTS ARE COMPARED, which
       is the whole reason it is worth reporting rather than doing quietly: left in, a trailing comma
       in one cell and not the other manufactures a count mismatch and sends an unambiguous row to a
       human for a decision there was nothing to decide.
    3. **A cell naming more than** :data:`MAX_DESIGNERS_PER_ROW` **entries is cut and reported.** A
       guard against a malformed file, never a silent truncation.

    A FORMULA WITH NO CACHED RESULT IS AN ERROR AND NOT AN EMPTY CELL. ``xlsx_table`` hands those
    back as :class:`FormulaCell` precisely so that a reader cannot mistake "Excel did not save the
    answer" for "the officer left it blank" — and here the difference is between an order that names
    nobody and an order whose designers are in the file but unreadable.
    """
    if isinstance(raw, FormulaCell):
        problems.append(
            ParseProblem(
                sheet,
                row,
                "error",
                (
                    f"The {column_label} cell holds a formula whose result is not saved in the "
                    "file, so it could not be read. Open the workbook in Excel, press F9, save, and "
                    "upload again."
                ),
                str(raw),
            )
        )
        return []

    text = cell_text(raw)
    if not text:
        return []

    if _OTHER_SEPARATORS.search(text):
        problems.append(
            ParseProblem(
                sheet,
                row,
                "warning",
                (
                    f"The {column_label} cell contains a line break or a semicolon. Only a COMMA "
                    "separates one designer from the next, so this was read as a single entry with "
                    "the break treated as a space. If it is meant to be two people, separate them "
                    "with a comma and upload again."
                ),
                text[:200],
            )
        )
        text = _OTHER_SEPARATORS.sub(" ", text)

    pieces = text.split(",")
    entries: list[str] = []
    dropped = 0
    for piece in pieces:
        cleaned = _SPACEY.sub(" ", unicodedata.normalize("NFKC", piece)).strip()
        if cleaned:
            entries.append(cleaned)
        else:
            dropped += 1

    if dropped:
        problems.append(
            ParseProblem(
                sheet,
                row,
                "warning",
                (
                    f"The {column_label} cell has {dropped} empty "
                    f"{'entry' if dropped == 1 else 'entries'} — an extra or a trailing comma. "
                    f"{'It was' if dropped == 1 else 'They were'} ignored and the "
                    f"{len(entries)} {'entry' if len(entries) == 1 else 'entries'} either side of "
                    f"{'it' if dropped == 1 else 'them'} were kept."
                ),
                text[:200],
            )
        )

    if len(entries) > MAX_DESIGNERS_PER_ROW:
        problems.append(
            ParseProblem(
                sheet,
                row,
                "error",
                (
                    f"The {column_label} cell names {len(entries)} entries, which is more than the "
                    f"{MAX_DESIGNERS_PER_ROW} one sanction order can be read as naming. Only the "
                    f"first {MAX_DESIGNERS_PER_ROW} were read. Check the cell — this is usually a "
                    "whole column pasted into one box."
                ),
                text[:200],
            )
        )
        entries = entries[:MAX_DESIGNERS_PER_ROW]

    return entries


def read_amount(
    raw: Any, *, sheet: str, row: int, problems: list[ParseProblem]
) -> Decimal | None:
    """The sanctioned amount as a :class:`Decimal`, or ``None`` having said why not.

    **NEVER A ``float``, AT ANY POINT, INCLUDING HERE.** A money cell Excel stored as a number
    arrives as a Python ``float`` — openpyxl has no other type for it — and ``Decimal(0.1)`` is
    ``0.1000000000000000055511151231257827``. So a numeric cell is converted through ``str()``
    first, which gives ``repr``'s shortest round-tripping form and therefore the figure the office
    actually typed. This is the same rule ``schemas/sanction_orders`` states for the wire and
    ``sanction_payload`` states for the response; this is the fourth layer of it and the one that is
    easiest to get wrong, because ``Decimal(value)`` compiles and looks right.

    ₹, "Rs.", "INR", thousands commas and the Indian "/-" suffix are all stripped, because they are
    all things an office writes in an amount cell and none of them is part of the number. What is
    NOT tolerated is a cell with two numbers in it or a range — those come back as an error naming
    the text, because guessing which one the ministry sanctioned is not a parser's decision.
    """
    if isinstance(raw, FormulaCell):
        problems.append(
            ParseProblem(
                sheet,
                row,
                "error",
                (
                    "The Sanctioned Amount cell holds a formula whose result is not saved in the "
                    "file. Open the workbook in Excel, press F9, save, and upload again."
                ),
                str(raw),
            )
        )
        return None
    if raw is None:
        return None
    # ``str(raw)`` AND NOT ``Decimal(raw)`` FOR A NUMERIC CELL — see the docstring. ``bool`` is an
    # ``int`` subclass and would arrive as "True"; it cannot come out of a cell openpyxl typed as a
    # number, and ``InvalidOperation`` below would refuse it with a sentence if it ever did.
    text = str(raw) if isinstance(raw, int | float) else cell_text(raw)
    text = _MONEY_NOISE.sub("", text)
    if not text:
        return None
    try:
        amount = Decimal(text)
    except (InvalidOperation, ValueError):
        problems.append(
            ParseProblem(
                sheet,
                row,
                "error",
                (
                    f"The Sanctioned Amount '{cell_text(raw)}' is not an amount this can read. "
                    "Type it as a plain number — 450000 or 450000.00. A range, two figures in one "
                    "cell, or words beside the number cannot be read, because which figure the "
                    "ministry sanctioned is not something this can decide."
                ),
                cell_text(raw)[:200],
            )
        )
        return None
    if amount <= 0:
        problems.append(
            ParseProblem(
                sheet,
                row,
                "error",
                (
                    f"The Sanctioned Amount '{cell_text(raw)}' is not more than zero. A sanction "
                    "order authorises money; type the figure printed on it."
                ),
                cell_text(raw)[:200],
            )
        )
        return None
    if amount.as_tuple().exponent < -2:
        # QUANTISED RATHER THAN REFUSED, AND REPORTED EITHER WAY. The column is NUMERIC(14,2) and
        # ``SanctionOrderCreate`` is ``decimal_places=2``, so a third decimal place would 422 at the
        # end of the import. A figure with fractions of a paisa in it is a spreadsheet's own
        # rounding artefact far more often than it is a ministry's intention, and refusing the row
        # would lose an order over a digit nobody typed.
        rounded = amount.quantize(Decimal("0.01"))
        problems.append(
            ParseProblem(
                sheet,
                row,
                "warning",
                (
                    f"The Sanctioned Amount '{cell_text(raw)}' has more than two decimal places and "
                    f"was recorded as {rounded}. The register holds rupees and paise. If that is "
                    "not the figure on the order, correct the cell and upload again."
                ),
                cell_text(raw)[:200],
            )
        )
        amount = rounded
    if amount >= Decimal(10) ** 12:
        problems.append(
            ParseProblem(
                sheet,
                row,
                "error",
                (
                    f"The Sanctioned Amount '{cell_text(raw)}' is larger than the register can "
                    "hold (twelve digits before the decimal point). Check whether the cell has a "
                    "stray digit or is in paise rather than rupees."
                ),
                cell_text(raw)[:200],
            )
        )
        return None
    return amount


_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")


def fold_order_no(value: Any) -> str:
    """The in-file duplicate key.

    **THE SAME FOLD** ``sanction_orders.normalise_sanction_order_no`` **APPLIES, AND IT IS SPELLED
    HERE RATHER THAN IMPORTED SO THAT THIS MODULE STAYS FREE OF THE SERVICE.** That is the one piece
    of duplication in this file and it is deliberate: ``sanction_orders`` imports the database, the
    credential links and the workshop service, and importing it here would make every test of this
    parser need a Prisma client to collect.
    ``tests/test_sanction_orders_xlsx.py::test_the_in_file_fold_is_the_registers_own_fold`` asserts
    the two agree over a table of spellings, which is what makes the duplication safe: if they ever
    disagreed, two rows of one sheet could pass the in-file check and then collide on
    ``@@unique(sanctionOrderKey)`` — one inside a transaction that has already minted accounts.
    """
    return _NON_ALNUM.sub("", str(value or "")).upper()


# --- The read -------------------------------------------------------------------------------------


def parse_sanction_workbook(data: bytes, *, filename: str | None = None) -> ParsedSanctionSheet:
    """Read an uploaded sheet of sanction orders into rows.

    Raises :class:`SanctionXlsxError` ONLY when the FILE is unusable; anything wrong with a row of an
    otherwise-readable file comes back in ``result.problems`` and the other rows are still offered.
    An officer with 198 of 200 orders and a list of the two that need correcting is far better off
    than one with an error page — and on this feature better off by more than on any other, because
    the two hundred that did import each opened a workshop and let somebody in.

    VALUE ROWS FOR THE DATA, TEXT ROWS FOR THE HEADER SCAN. That split is ``annual_plan_xlsx``'s and
    it is load-bearing here for the same two columns: ``cell_text`` renders a date cell as
    "2026-03-12 00:00:00" and a money cell as a float's repr, and the resolved values are then gone.
    ``pick_sheet``/``scan_header`` declare ``list[tuple[int, list[str]]]`` and mean it.
    """
    if not data:
        raise SanctionXlsxError(
            "The upload was empty. Attach the filled-in sanction order pro-forma."
        )
    try:
        sheets = load_value_sheets(
            data,
            max_sheets=MAX_SHEETS_SCANNED,
            max_rows=MAX_ROWS_SCANNED,
            pro_forma="the sanction order pro-forma",
        )
    except XlsxTableError as exc:
        raise SanctionXlsxError(str(exc)) from exc

    if not sheets:
        raise SanctionXlsxError("The workbook has no sheets in it.")

    result = ParsedSanctionSheet(sourceFilename=filename)
    text_sheets = {name: as_text_rows(rows) for name, rows in sheets.items()}

    picked = pick_sheet(
        text_sheets,
        result.problems,
        aliases=_COLUMN_ALIASES,
        required_role=_REQUIRED_ROLE,
        preferred_names=(SHEET_ORDERS, "sanction orders", "sanctions", "orders", "sheet1"),
        preferred_display=SHEET_ORDERS,
        looks_like="a sheet of sanction orders",
        most_noun="rows",
        max_scan_rows=MAX_HEADER_SCAN_ROWS,
    )
    if picked is None:
        raise SanctionXlsxError(
            "No sheet in that workbook has a 'Sanction Order No.' column, so there was nothing to "
            "import. Download the pro-forma and type the orders under its headings, or add a header "
            "row with a column called 'Sanction Order No.'. A sheet with only a serial number "
            "cannot be used: the order number is what the register is keyed on, and 1, 2, 3 is not "
            "a number the ministry issued."
        )
    sheet_name, header_row, columns = picked
    result.sheet = sheet_name

    _report_unrecognised_columns(
        text_sheets[sheet_name], sheet_name, header_row, columns, result.problems
    )
    _read_rows(sheets[sheet_name], sheet_name, header_row, columns, result)

    if not result.rows:
        raise SanctionXlsxError(
            f"The '{sheet_name}' sheet has a Sanction Order No. column but no rows under it. Type "
            "at least one order and upload again."
        )
    return result


def _report_unrecognised_columns(
    rows: list[tuple[int, list[str]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    problems: list[ParseProblem],
) -> None:
    """ONE warning naming every heading on the header row this reader did not recognise.

    ⚠ ONLY HEADINGS THAT HAVE TEXT IN THEM. openpyxl pads every row out to ``ws.max_column``, so a
    sheet with stray formatting out to column BZ has forty header cells holding ``None`` — and
    ``norm_header(None)`` is ``""``, which matches no alias. Without the non-empty filter below,
    EVERY ORDINARY UPLOAD produces a warning reading "These columns were not recognised and nothing
    in them was stored: , , , , , …", which teaches officers to ignore this panel — and this is the
    panel that tells them a column of real data was skipped.

    This is ``annual_plan_xlsx._report_unrecognised_columns``, and it is a near-copy rather than a
    shared helper for the reason that file gives: ``xlsx_table.scan_header`` is shared with the
    questionnaire and the artisan list, both of which INVITE columns of the uploader's own and
    neither of which wants this warning. A ministry sheet's columns are all data.
    """
    header = next((values for number, values in rows if number == header_row), [])
    unknown = [
        text
        for index, raw in enumerate(header)
        if index not in columns and (text := cell_text(raw).strip())
    ]
    if not unknown:
        return
    problems.append(
        ParseProblem(
            sheet,
            header_row,
            "warning",
            (
                "These columns were not recognised and nothing in them was stored: "
                f"{', '.join(unknown)}. Rename them to one of the pro-forma's headings if the "
                "information should be kept."
            ),
            None,
        )
    )


def _clip_role(
    value: str | None, role: str, sheet: str, row: int, problems: list[ParseProblem]
) -> str | None:
    """Hold one stored value to its :data:`_TEXT_CAPS` bound, reporting the clip. Never silent.

    A clip that says nothing is an order number quietly losing its suffix — and the suffix is
    exactly what distinguishes the two orders the ministry issued under one number, which
    ``SANCTION_DUPLICATE_TEMPLATE`` tells officers to type in.
    """
    if value is None:
        return None
    cap = _TEXT_CAPS[role]
    if len(value) <= cap:
        return value
    label = dict(_PRO_FORMA_COLUMNS).get(role, role)
    problems.append(
        ParseProblem(
            sheet,
            row,
            "warning",
            (
                f"'{label}' was longer than {cap} characters and was shortened. Everything else on "
                "the row was kept."
            ),
            value[:200],
        )
    )
    return clip(value, cap)


def _read_rows(
    rows: list[tuple[int, list[Any]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    result: ParsedSanctionSheet,
) -> None:
    """Walk the data rows under the header. **NEVER DROPS A ROW IN SILENCE.**

    A row is either a :class:`ParsedSanctionRow`, or a wholly blank spacer, or a
    :class:`ParseProblem` with ``severity="error"``. There is no fourth outcome and no ``continue``
    that leaves nothing behind.

    ⚠ A ROW WITH AN UNREADABLE DATE OR AMOUNT IS STILL RETURNED, with that field ``None``. The
    reconciliation refuses it (a sanction order with no date is not an order) and names it in the
    ``refused`` list with the parser's own sentence. It is returned rather than dropped so that the
    counts add up on screen: ``rowsRead`` must equal ``recorded + skipped + refused``, and a row this
    function swallowed would be missing from all four.
    """
    seen: dict[str, int] = {}

    for row_number, values in rows:
        if row_number <= header_row:
            continue

        def get(role: str, _values: list[Any] = values) -> Any:
            for index, (found, _label) in columns.items():
                if found == role and index < len(_values):
                    return _values[index]
            return None

        if not any(cell_text(v) for v in values):
            # A SPACER ROW IS SKIPPED IN SILENCE AND THAT IS THE ONE SILENT SKIP IN THIS FUNCTION.
            # Ministry sheets are full of them — a blank line under each month's block — and a
            # problem per blank line would bury the two that matter under forty that do not.
            continue

        if len(result.rows) >= MAX_SANCTION_ROWS:
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        f"This sheet has more than {MAX_SANCTION_ROWS} rows. The first "
                        f"{MAX_SANCTION_ROWS} were read and everything from row {row_number} down "
                        "was not. Each order opens a workshop and may create an account, so a sheet "
                        "is bounded on purpose; split this one and upload each part."
                    ),
                    None,
                )
            )
            break

        raw_no = cell_text(get("sanctionOrderNo"))
        if not raw_no:
            trace = ", ".join(t for v in values if (t := cell_text(v)))[:200]
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        "This row has no Sanction Order No., so there is nothing to record it "
                        "under. The order number is what the register is keyed on and what an "
                        "officer searches by. Type the ministry's number for it, or delete the row."
                    ),
                    trace or None,
                )
            )
            continue

        order_no = _clip_role(raw_no, "sanctionOrderNo", sheet, row_number, result.problems) or ""
        key = fold_order_no(order_no)
        if not key:
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        f"The Sanction Order No. '{raw_no}' has no letters or digits in it, so it "
                        "cannot be recorded or searched for. Type the number as it is printed on "
                        "the order."
                    ),
                    raw_no[:200],
                )
            )
            continue

        if key in seen:
            # PRE-EMPTING THE ONE CONSTRAINT A SHEET CAN VIOLATE AGAINST ITSELF. ``sanctionOrderKey``
            # is ``@unique``, and two rows of one sheet carrying one number collide with EACH OTHER
            # before either reaches the register — which the register's own duplicate check cannot
            # see, because the first row has not been written yet. Both Excel rows are named because
            # the officer has to look at both to decide which is right.
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        f"Sanction order '{order_no}' is also on row {seen[key]} of this sheet. One "
                        "number is one order, so this row was not read. If the ministry really has "
                        "issued two orders under this number, type each one under the number as "
                        "printed including its suffix; otherwise delete the duplicate row."
                    ),
                    order_no,
                )
            )
            continue
        seen[key] = row_number

        entry = ParsedSanctionRow(
            sheetRow=row_number, sanctionOrderNo=order_no, sanctionOrderKey=key
        )

        entry.sanctionOrderDate = read_plan_date(
            get("sanctionOrderDate"),
            sheet=sheet,
            row=row_number,
            # THE COLUMN LABEL IS THIS SHEET'S AND NOT THE ANNUAL PLAN'S — the whole reason
            # ``read_plan_date`` takes the argument. An officer reading "The Start Date '03/04/2026'
            # was read as…" on a sanction sheet would go looking for a column that is not there.
            column_label="Sanction Date",
            problems=result.problems,
        )
        entry.sanctionAmount = read_amount(
            get("sanctionAmount"), sheet=sheet, row=row_number, problems=result.problems
        )

        entry.designerNames = [
            _clip_role(name, "designerNames", sheet, row_number, result.problems) or ""
            for name in split_list_cell(
                get("designerNames"),
                sheet=sheet,
                row=row_number,
                column_label="Designer Name(s)",
                problems=result.problems,
            )
        ]
        entry.designerEmails = split_list_cell(
            get("designerEmails"),
            sheet=sheet,
            row=row_number,
            column_label="Designer Email(s)",
            problems=result.problems,
        )

        notes = cell_text(get("notes")).strip() or None
        entry.notes = _clip_role(notes, "notes", sheet, row_number, result.problems)

        result.rows.append(entry)


# --- The pro-forma --------------------------------------------------------------------------------


def _header(ws: Worksheet, row: int, labels: tuple[tuple[str, str], ...]) -> None:
    """The frozen header band. EVERY CELL GOES THROUGH ``xlsx_report._put`` AND NOTHING ELSE.

    ``_put`` is what strips the control characters Excel refuses to open a file containing, what
    holds a value to the 32767-character ceiling, and what makes a leading ``=`` a literal rather
    than a formula. (The one call below that is NOT a write — ``.column_letter`` — reads a coordinate
    to set a column width, so it is outside that rule rather than an exception to it.)
    """
    fill = PatternFill(fill_type="solid", start_color=f"FF{_BRAND}")
    required_fill = PatternFill(fill_type="solid", start_color="FF7C2D12")
    for index, (key, label) in enumerate(labels, start=1):
        cell = _put(ws, row, index, label)
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = required_fill if key in _REQUIRED else fill
        cell.alignment = Alignment(horizontal="left", vertical="center", wrap_text=True)
        ws.column_dimensions[ws.cell(row=row, column=index).column_letter].width = _WIDTHS.get(
            key, 20
        )
    ws.row_dimensions[row].height = 32
    ws.freeze_panes = f"A{row + 1}"


_HELP_LINES: tuple[tuple[str, str], ...] = (
    ("h", "Sanction orders — how to fill this in"),
    (
        "p",
        "Type one sanction order per row on the 'Sanction orders' sheet. Leave every heading "
        "exactly as you found it: the app finds the columns by their headings and not by where "
        "they are, so you may reorder or delete columns you do not use, but not rename them.",
    ),
    ("b", "The four darker headings are the ones an order cannot be recorded without."),
    (
        "p",
        "Sanction Order No. is what the register is keyed on. Type it exactly as printed, suffix "
        "included — SO/2026/42 and SO-2026-42 are treated as the same number, so an order already "
        "recorded under either spelling will be reported as a duplicate rather than recorded twice.",
    ),
    (
        "p",
        "Sanction Date is read DAY FIRST: 03/04/2026 is the third of April. Format the column as a "
        "date in Excel and this is never in doubt. If you type dates as text, the app will tell you "
        "which reading it took whenever both are possible.",
    ),
    (
        "p",
        "Sanctioned Amount is rupees and paise. Type a plain number — 450000 or 450000.00. Rs., the "
        "rupee sign, thousands commas and a trailing /- are all understood and removed.",
    ),
    ("b", "Designer Name(s) and Designer Email(s): one order may name several designers."),
    (
        "p",
        "Separate them with COMMAS, and put them in the SAME ORDER in both cells — the first name "
        "goes with the first address, the second with the second. Do not use semicolons and do not "
        "press Alt+Enter to put them on separate lines inside one cell: both are read as a single "
        "entry and reported back to you.",
    ),
    (
        "p",
        "THE FIRST DESIGNER IS THE LEAD. Their name is the one that goes on the report cover and "
        "into the workshop's first stage; every designer named gets an account, an empanelment and "
        "access to the workshop. Put the lead first deliberately rather than leaving it to the "
        "order the names happened to be typed in.",
    ),
    (
        "p",
        "If you leave Designer Name(s) empty the app fills each name in from the account or the "
        "roster the address already belongs to. If you leave Designer Email(s) empty the app cannot "
        "guess who is meant and will ask you, row by row, before recording anything.",
    ),
    (
        "p",
        "A designer who has never used the portal is the ordinary case and needs nothing extra — "
        "the order creates their account. What the app will refuse outright is an address an "
        "administrator has barred, an empanelment an administrator has ended, an account that is "
        "not a designer's, and your own address: the officer who approves the money is not the "
        "person who spends it.",
    ),
    (
        "p",
        "Nothing is recorded when you upload. You are shown what the sheet says, asked about any "
        "row the two designer columns disagree about, and nothing is written until you confirm.",
    ),
)

#: The worked example, deliberately on the INSTRUCTIONS sheet rather than under the headings.
#:
#: A seeded example row on the data sheet is a row somebody uploads, and then the register contains
#: an order called "SO/2026/EXAMPLE" that cannot be deleted — there is no DELETE on this prefix.
#: Every line here is ONE cell of piped text, so no cell on this sheet normalises to a column alias
#: and ``scan_header`` finds no header row on it at all, which is what stops this sheet ever being
#: picked as the data sheet.
_HELP_EXAMPLE: tuple[str, ...] = (
    "Sanction Order No. | Sanction Date | Sanctioned Amount | Designer Name(s) | Designer Email(s)",
    "SO/2026/42         | 03/04/2026    | 450000.00         | Ramesh Kumar     | r.kumar@gmail.com",
    "SO/2026/43         | 11/04/2026    | 6,25,000/-        | Kavita Rao, Anil Shah | "
    "kavita.rao@gmail.com, anil.shah@gmail.com",
    "",
    "In SO/2026/43 the lead is Kavita Rao: she is first in both cells, so her name reaches the",
    "report cover and Anil Shah is the second designer on the same order.",
)


def _help_sheet(ws: Worksheet) -> None:
    """The instructions, and the worked example that cannot be imported. See :data:`_HELP_EXAMPLE`."""
    ws.sheet_properties.tabColor = _GREY
    ws.column_dimensions["A"].width = 110
    row = 1
    for kind, text in _HELP_LINES:
        cell = _put(ws, row, 1, text)
        if kind == "h":
            cell.font = Font(bold=True, size=14, color=f"FF{_BRAND}")
        elif kind == "b":
            cell.font = Font(bold=True)
        else:
            cell.font = Font(color=_GREY)
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.row_dimensions[row].height = 42
        row += 1
    row += 1
    for index, line in enumerate(_HELP_EXAMPLE):
        cell = _put(ws, row, 1, line)
        cell.font = Font(bold=index == 0, color=_GREY, name="Consolas", size=9)
        row += 1


def build_sanction_pro_forma() -> bytes:
    """The BLANK pro-forma: headings and instructions, and nothing under the headings.

    EMPTY UNDER THE HEADINGS, for the reason ``questionnaire_xlsx.build_pro_forma`` gives about its
    own and with more at stake here: a seeded example row is a row somebody uploads, and an imported
    sanction order mints accounts, issues credentials and opens a workshop — none of which this
    product can delete.

    THERE IS NO DETAILS SHEET. The annual plan has one because its upload needs a plan YEAR the
    sheet itself can name; a sanction order carries its own date on every row and there is nothing
    about the workbook as a whole to declare. Adding one later would mean adding
    ``xlsx_table.read_details`` aliases, and that helper's own warning block records what a
    decorative row costs when a parser starts STORING it.
    """
    wb = Workbook()
    orders = wb.active
    orders.title = SHEET_ORDERS
    orders.sheet_properties.tabColor = f"FF{_BRAND}"
    _header(orders, 1, _PRO_FORMA_COLUMNS)
    _help_sheet(wb.create_sheet(title=SHEET_HELP))
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()
