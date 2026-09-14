"""The .xlsx pro-forma an officer types a workshop's artisan list into, and the parser that reads
it back.

The loop this serves: a Ministry Admin has a list of the fifteen artisans who will attend a design
& prototype workshop — on paper, in an email, in somebody else's spreadsheet. Today the only way to
get them into the product is to open the artisan form fifteen times and type twenty-five fields
each. This module is the other half of that: download a pro-forma, paste the list into it, upload
it, and the workshop's stage-3 participant table comes up filled in.

``build_artisan_pro_forma``  -> a blank workbook: the Artisans sheet, a Details sheet naming the
                                workshop and the defaults, and an instructions sheet.
``parse_artisan_workbook``   -> that workbook, or something an office built from scratch, read back
                                into rows + problems.

=======================================================================================
WHAT THIS MODULE DOES NOT DO, AND WHY THE SPLIT IS WHERE IT IS
=======================================================================================

**IT NEVER TOUCHES THE DATABASE.** No craft is resolved, no duplicate is looked for, no artisan is
written. Everything here is pure over ``bytes`` plus a small :class:`ArtisanDefaults`, which is what
makes the whole parser testable with openpyxl and nothing else — and that matters more here than
usual, because the rules being tested are about REGULATED DATA and a test that needs a database is a
test that gets skipped.

``services/artisan_import`` is the other side: it takes what comes out of here, resolves the craft
against the controlled vocabulary, asks one batched question about duplicates, writes the artisans
and puts the roster into stage 3 through ``save_stage``.

The line between them is **syntax versus identity**. A number that is not twelve digits is this
module's problem; a number that belongs to somebody already in the repository is the importer's.

=======================================================================================
THE READER IS SHARED, NOT COPIED
=======================================================================================

Every mechanical part of reading an uploaded workbook — the NFKC header normalisation, the alias
matching, the header scan, the two-pass formula map, the magic-byte branch, the
never-drop-a-row :class:`ParseProblem` contract — comes from ``services/xlsx_table``, which is
where it moved out of ``questionnaire_xlsx`` when this module was written. There are two readers of
this file format and ONE implementation of reading it. A private copy would have been the third
renderer/reader pair of .xlsx in this repository and would have drifted the first time one of them
was corrected.

What is NOT shared is the vocabulary: the aliases below, the ceilings, the sentences. Those are
this feature's and the questionnaire's respectively, which is the whole shape of the extraction.

=======================================================================================
AADHAAR IS ON THE PRO-FORMA, AND HERE IS THE ARGUMENT AND THE PRICE
=======================================================================================

**WHY IT MUST BE THERE.** ``ArtisanCreate.aadhaarNumber`` is a bare required ``str`` and
``artisan_identity.require_aadhaar`` refuses a blank with a message about why, so an importer that
omitted the column could create NO artisan at all. Omitting it and asking the office to fill fifteen
numbers in afterwards is strictly worse: ``artisan_identity``'s own header says "a dedup key that
may be omitted only deduplicates the records that happened to fill it in", and fifteen keyless
artisan records is fifteen future duplicates — ``backend/scripts/merge_artisans.py`` is what
cleaning that up looks like, a hand-audited one-off with hard-coded cuids.

**AND THE AUDIENCE IS NOT WIDENED BY IT.** ``artisans._may_read_full_aadhaar`` is
``has_rank(user, "PROFESSOR")``, and every tier that can reach this upload clears that floor. The
spreadsheet moves data those accounts may already read from one surface to another.

**WHAT IS GENUINELY NEW, AND WHAT THIS MODULE THEREFORE GUARANTEES:**

1. **No full number is ever echoed.** Every :class:`ParseProblem` about column 17 or column 19
   carries ``mask_aadhaar(...)`` or ``None`` — never the digits. ``tests/test_artisan_xlsx.py``
   asserts the twelve digits appear nowhere in the whole payload, not merely that the mask is
   present.
2. **No log line carries the number.** ``artisan_identity`` says "nothing here ever writes it to a
   log"; the same rule holds here. Log lines name the column, the Excel row and the rule.
3. **The uploaded bytes are never stored.** The route drops them as soon as this returns, and
   ``DwArtisanImport`` keeps the FILENAME and never the file.
4. **A MASK IN THE UPLOAD IS REFUSED, NOT STORED.** ``pehchan_error`` ACCEPTS ``XXXX XXXX 3456`` —
   fourteen alphanumerics, inside 4-32, no checksum to fail — which is exactly how a mask once got
   stored over a real card number. So any identity cell whose value is a mask this application
   produced is an ``error``: a mask in an upload is a copy-paste out of a screen, not a card.
5. **The pro-forma says all of this in the artefact.** The Help sheet carries it, because the file
   outlives the page — the same rule the CSV export's header lines follow.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from io import BytesIO
from typing import Any

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.worksheet import Worksheet

from app.services.artisan_identity import (
    aadhaar_error,
    is_masked_aadhaar,
    mask_aadhaar,
    normalize_aadhaar,
    normalize_pehchan,
    pehchan_error,
)

# Shared, not restated. ``_put`` is the single door every value in an .xlsx this repo produces goes
# through, and it is what stops Excel opening the download with "We found a problem with some
# content". ``_safe_title`` keeps a worksheet title legal and unique. See ``xlsx_report``'s header
# for the three separate ways ordinary field text broke a download before that door existed.
from app.services.xlsx_report import _put, _safe_title
from app.services.xlsx_table import (
    FormulaCell,
    ParseProblem,
    XlsxTableError,
    clip,
    load_sheets,
    norm_header,
    pick_sheet,
    read_details,
    truthy,
)

__all__ = [
    "ARTISAN_PRO_FORMA_FILENAME",
    "MAX_ARTISANS",
    "ArtisanDefaults",
    "ArtisanXlsxError",
    "ParseProblem",
    "ParsedArtisan",
    "ParsedArtisanList",
    "build_artisan_pro_forma",
    "parse_artisan_workbook",
]

ARTISAN_PRO_FORMA_FILENAME = "artisan-list-pro-forma.xlsx"

SHEET_ARTISANS = "Artisans"
SHEET_DETAILS = "Details"
SHEET_HELP = "How to fill this in"

_BRAND = "0F766E"  # teal, deliberately NOT the questionnaire's purple: two different pro-formas
_WHITE = "FFFFFFFF"
_GREY = "FF6B7280"


# --- Ceilings -----------------------------------------------------------------------------------
#
# Named for what they bound rather than for a shape, and every one of them is a WRONG-FILE ceiling
# rather than a policy: a workshop's roster is ten to fifteen people.

#: How many artisan rows one upload may carry.
#:
#: **200 AND NOT 500, AND THE NUMBER IS LOAD-BEARING RATHER THAN CAUTIOUS.** The importer's roster
#: write goes through ``design_workshops.save_stage``, whose request model bounds a stage at
#: ``MAX_STAGE_ROWS`` (500) entries — and that bound is a ``ValueError`` raised inside a Pydantic
#: model validator. On a request body Pydantic turns that into a 422; on a ``StageSaveIn`` BUILT IN
#: PROCESS, as the importer builds one, it is a bare ``ValidationError`` that becomes a 500 —
#: **after every artisan in the file has already been committed**. The visible effect would be N
#: artisans created, zero participant rows, and a pydantic traceback.
#:
#: So this ceiling sits well under that one, the importer chunks its stage saves under it, and
#: ``tests/test_artisan_import.py`` compares the two LIVE CONSTANTS rather than two literals — a
#: test written against the numbers would go green the day somebody lowered ``MAX_STAGE_ROWS``.
MAX_ARTISANS = 200

#: How far down a sheet to read at all. A roster is fifteen rows; this is the ceiling for a file
#: that is not a roster.
MAX_ROWS_SCANNED = 5_000
MAX_SHEETS_SCANNED = 12
#: How far down to look for the header row before concluding there is not one. Generous enough for
#: a letterhead and a title block, small enough that a sheet of prose is not mistaken for a table.
MAX_HEADER_SCAN_ROWS = 15

MAX_NAME_CHARS = 180  # == Artisan.name's Field(max_length=180)
MAX_PLACE_CHARS = 180  # == Artisan.place's
MAX_PROSE_CHARS = 4_000  # dos / donts / notes / address
MAX_SHORT_CHARS = 180  # gender, phone, email, village — all free text on the model


class ArtisanXlsxError(ValueError):
    """The upload is not a workbook this can read at all.

    Distinct from a :class:`ParseProblem`, which is one row of an otherwise-good file. This is the
    whole file: not a zip, not an .xlsx, password-protected, or carrying no recognisable name column
    anywhere. The message is written to be shown to the uploader as-is, and the route answers it as
    a 422 exactly as ``QuestionnaireXlsxError`` is answered.
    """


# --- The column vocabulary ----------------------------------------------------------------------

#: Every spelling of every column this accepts, matched after :func:`xlsx_table.norm_header` — so
#: case, punctuation, underscores, non-breaking spaces and repeated whitespace are all already gone
#: by the time a header reaches this table.
#:
#: MATCHING IS EXACT WITHIN A ROLE, so two roles must never share a spelling. The pairs worth
#: knowing about, because they look like collisions and are not: "pehchan card" is the yes/no column
#: and "pehchan card number" is the number; "years" is craft experience and "age years" is the age;
#: "craft" is the trade and "craft start date" is the date.
#:
#: TWO ROLES MAP TO NOTHING AND ARE HERE ON PURPOSE — ``age`` and ``specialisation``. Both are
#: columns an office WILL put on a list of artisans, neither has anywhere to go on an ``Artisan``
#: row, and a column silently ignored is the worst of the three possible answers. They are matched,
#: dropped, and reported as a warning naming what the application does instead. See
#: :data:`_ACCEPTED_AND_IGNORED`.
_ARTISAN_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "serialNo": ("s no", "sno", "sr no", "serial", "serial no", "no", "s no artisan"),
    "localName": (
        "local name",
        "name in the local language",
        "name in local language",
        "name in local script",
        "vernacular name",
        "local",
    ),
    "name": ("artisan name", "name", "artisan", "full name", "name of artisan"),
    "gender": ("gender", "sex", "m f"),
    "dateOfBirth": ("date of birth", "dob", "birth date", "born", "d o b"),
    "age": ("age", "age years", "years old"),
    "phone": (
        "phone",
        "mobile",
        "contact",
        "phone number",
        "mobile number",
        "contact number",
        "cell",
    ),
    "email": ("email", "e mail", "email id", "email address"),
    "craftName": ("craft", "craft name", "trade", "craft trade", "skill"),
    "specialisation": ("specialisation", "specialization", "speciality", "specialty"),
    "place": ("place", "town", "settlement", "place of work", "native place"),
    "address": ("address", "postal address", "full address", "house address"),
    "village": ("village", "hamlet", "gram", "village hamlet"),
    "district": ("district", "dist", "zilla", "zila", "district name"),
    "state": ("state", "state ut", "state union territory", "province"),
    "pincode": ("pin", "pincode", "pin code", "postal code", "zip", "postcode"),
    "aadhaarNumber": (
        "aadhaar number",
        "aadhaar",
        "aadhar",
        "adhaar",
        "aadhaar no",
        "uid",
        "uidai",
    ),
    "pehchanCardNumber": (
        "pehchan card number",
        "pehchan number",
        "pehchan no",
        "artisan card no",
        "card number",
        "vishwakarma id",
    ),
    "pehchanCardAvailable": (
        "holds an artisan pehchan card",
        "holds pehchan card",
        "has pehchan card",
        "pehchan card",
        "pehchan",
        "pm vishwakarma",
        "vishwakarma",
    ),
    "experienceYears": (
        "years practising the craft",
        "years of experience",
        "experience years",
        "craft experience",
        "years practising",
        "experience",
        "years",
    ),
    "experienceMonths": (
        "months on top of the years",
        "experience months",
        "additional months",
        "and months",
        "months",
    ),
    "craftStartDate": (
        "craft start date",
        "started craft",
        "practising since",
        "since",
        "start date",
    ),
    "dos": ("dos", "do s", "positive prompt", "what to do"),
    "donts": ("donts", "don ts", "negative prompt", "what not to do"),
    "notes": ("notes", "remarks", "observations", "comments"),
}

#: Columns this accepts, ignores and REPORTS, with the sentence each gets. See the note on
#: :data:`_ARTISAN_COLUMN_ALIASES`.
_ACCEPTED_AND_IGNORED: dict[str, str] = {
    "age": (
        "Age is derived from the date of birth and is not stored — nothing on the artisan record "
        "holds an age. The value in this column was ignored; fill in Date of birth instead."
    ),
    "specialisation": (
        "There is no specialisation column on an artisan record — the workshop's participant table "
        "derives it from the craft. The value in this column was ignored."
    ),
}

#: The Details sheet's label vocabulary.
#:
#: **DISJOINT FROM ``questionnaire_xlsx._DETAIL_KEYS`` BY CONSTRUCTION, AND THAT IS A REAL
#: CONSTRAINT.** Both features write a sheet called "Details" and both read one back by matching
#: column A against a family of aliases. If a label here normalised to ``title``, ``description``,
#: ``notes``, ``summary`` or ``questionnaire id``, an artisan pro-forma uploaded to the
#: questionnaire door — or the reverse — would quietly set the wrong field.
#: ``tests/test_artisan_xlsx.py`` asserts the two tables share no spelling.
_DETAIL_KEYS: dict[str, tuple[str, ...]] = {
    "designWorkshopId": ("design workshop id", "workshop id", "design workshop"),
    "workshopTitle": ("workshop title",),
    "state": ("state",),
    "district": ("district",),
    "venue": ("venue",),
}


# --- What comes out -----------------------------------------------------------------------------


@dataclass(frozen=True)
class ArtisanDefaults:
    """What a blank cell falls back to, and where that fallback came from.

    **A PLAIN VALUE OBJECT AND NOT A WORKSHOP ROW**, which is what keeps this module free of the
    database and its tests free of one. The route fills it from the workshop being imported into.

    EVERY FALLBACK IS REPORTED AS A ``warning``, never applied in silence. An office that left the
    State column blank on fifteen rows has made one decision, and being told "the workshop's state
    was used" is what lets them notice if the workshop's state is wrong.
    """

    state: str = ""
    district: str = ""
    place: str = ""
    craftName: str = ""


@dataclass
class ParsedArtisan:
    """One row of the sheet, syntactically clean, not yet checked against the repository.

    Every field is the value as it will be offered to ``ArtisanCreate``, or empty. Nothing here has
    been looked up: ``craftName`` is still a name, and ``aadhaarNumber`` is normalised digits that
    have passed the checksum but have not been asked about.
    """

    row: int
    name: str
    aadhaarNumber: str
    serialNo: int | None = None
    localName: str = ""
    gender: str = ""
    dateOfBirth: str = ""
    phone: str = ""
    email: str = ""
    craftName: str = ""
    place: str = ""
    address: str = ""
    village: str = ""
    district: str = ""
    state: str = ""
    pincode: str = ""
    pehchanCardAvailable: bool | None = None
    pehchanCardNumber: str = ""
    experienceYears: int | None = None
    experienceMonths: int | None = None
    craftStartDate: str = ""
    dos: str = ""
    donts: str = ""
    notes: str = ""


@dataclass
class ParsedArtisanList:
    artisans: list[ParsedArtisan] = field(default_factory=list)
    problems: list[ParseProblem] = field(default_factory=list)
    sheet: str | None = None
    #: Read off the Details sheet. Its presence is how an upload catches somebody importing a list
    #: they filled in for a DIFFERENT workshop — fifteen people's regulated records filed under a
    #: stranger's project. The route compares it with the id in the URL and answers 409.
    designWorkshopId: str | None = None
    #: How many data rows were looked at, including the ones that were refused. The report's
    #: "rows read" and the honest denominator of everything else.
    rowsRead: int = 0

    def payload(self) -> dict[str, Any]:
        return {
            "sheet": self.sheet,
            "rowsRead": self.rowsRead,
            "artisanCount": len(self.artisans),
            "problems": [p.payload() for p in self.problems],
        }


# --- Reading ------------------------------------------------------------------------------------

_ISO_DATE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})")
_DMY_DATE = re.compile(r"^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$")


def _date_text(raw: str) -> str | None:
    """A cell as ``YYYY-MM-DD``, or ``None`` when it is not a date at all.

    THREE SHAPES, AND THE THIRD IS THE ONE THAT MATTERS. An Excel DATE cell arrives through
    ``xlsx_table.cell_text`` as ``str(datetime)`` — ``"1978-03-01 00:00:00"`` — so the ISO prefix
    match covers both a real date cell and somebody who typed ``1978-03-01``. ``01/03/1978`` is
    accepted as DAY first, which is what an Indian office writes and is the only reading that can be
    right more often than it is wrong; a value that is ambiguous under that reading (``03/01/1978``)
    is still read day-first rather than guessed at, because a parser that switches conventions per
    row produces a column nobody can audit.

    Anything else answers ``None`` and the caller reports a ``warning`` naming the column. It is
    never a row-level error: a mistyped date of birth must not cost the fourteen other fields.
    """
    text = raw.strip()
    if not text:
        return None
    iso = _ISO_DATE.match(text)
    if iso:
        try:
            return date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3))).isoformat()
        except ValueError:
            return None
    dmy = _DMY_DATE.match(text)
    if dmy:
        try:
            return date(int(dmy.group(3)), int(dmy.group(2)), int(dmy.group(1))).isoformat()
        except ValueError:
            return None
    return None


def _int_text(raw: str) -> int | None:
    """A cell as an int, tolerating Excel's float storage. ``None`` for anything else."""
    text = raw.strip()
    if not text:
        return None
    try:
        return int(text)
    except ValueError:
        pass
    try:
        value = float(text)
    except ValueError:
        return None
    return int(value) if value.is_integer() else None


def parse_artisan_workbook(
    data: bytes,
    *,
    filename: str | None = None,
    defaults: ArtisanDefaults | None = None,
) -> ParsedArtisanList:
    """Read an uploaded workbook into artisan rows and a list of everything that went wrong.

    Raises :class:`ArtisanXlsxError` ONLY when the FILE is unusable. Anything wrong with a ROW of an
    otherwise-readable file comes back in ``result.problems`` and the rest of the file is still
    offered, because an office with thirteen of fifteen artisans and a list of the two that need
    fixing is far better off than one with an error page.

    ``defaults`` is the workshop's own state, district, venue and craft. Applying one is always a
    ``warning`` naming the column and where the value came from.
    """
    defaults = defaults or ArtisanDefaults()
    if not data:
        raise ArtisanXlsxError("The upload was empty. Attach the filled-in artisan list pro-forma.")
    try:
        sheets = load_sheets(
            data,
            max_sheets=MAX_SHEETS_SCANNED,
            max_rows=MAX_ROWS_SCANNED,
            pro_forma="the artisan list pro-forma",
        )
    except XlsxTableError as exc:
        raise ArtisanXlsxError(str(exc)) from exc

    if not sheets:
        raise ArtisanXlsxError("The workbook has no sheets in it.")

    result = ParsedArtisanList()

    # The Details sheet, if there is one. Missing is normal — an office that built the list from
    # scratch has none, and everything on it is either a defaulted value or the workshop guard.
    for name, rows in sheets.items():
        if norm_header(name) in ("details", "about", "workshop details", "info", "information"):
            details = read_details(rows, keys=_DETAIL_KEYS)
            result.designWorkshopId = (details.get("designWorkshopId") or "").strip() or None
            break

    picked = pick_sheet(
        sheets,
        result.problems,
        aliases=_ARTISAN_COLUMN_ALIASES,
        required_role="name",
        preferred_names=(SHEET_ARTISANS, "artisan", "participants", "list", "sheet1"),
        preferred_display=SHEET_ARTISANS,
        looks_like="an artisan list",
        most_noun="rows",
        max_scan_rows=MAX_HEADER_SCAN_ROWS,
    )
    if picked is None:
        raise ArtisanXlsxError(
            "No sheet in that workbook has an 'Artisan name' column, so there was nothing to "
            "import. Download the artisan list pro-forma and type the list under its headings, or "
            "add a header row with a column called 'Artisan name'."
        )
    sheet_name, header_row, columns = picked
    result.sheet = sheet_name

    # THE AADHAAR COLUMN IS CHECKED FOR SEPARATELY FROM THE NAME COLUMN, AND IT GETS ITS OWN
    # REFUSAL. Both are required to create an artisan, so a sheet with names and no numbers imports
    # nothing at all — and "no sheet has an Artisan name column" would be a confusing thing to tell
    # somebody looking at a sheet full of names. Two refusals, two remedies.
    if not any(role == "aadhaarNumber" for role, _label in columns.values()):
        raise ArtisanXlsxError(
            f"The '{sheet_name}' sheet has no 'Aadhaar number' column. An artisan record cannot be "
            "created without one — it is the only thing that stops the same person being recorded "
            "twice. Add the column and upload again."
        )

    _read_rows(sheets[sheet_name], sheet_name, header_row, columns, result, defaults)

    if not result.artisans and not any(p.severity == "error" for p in result.problems):
        raise ArtisanXlsxError(
            f"The '{sheet_name}' sheet has the right headings but no artisans under them. Type at "
            "least one row and upload again."
        )
    return result


def _read_rows(
    rows: list[tuple[int, list[str]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    result: ParsedArtisanList,
    defaults: ArtisanDefaults,
) -> None:
    """Walk the data rows under the header. **NEVER DROPS A ROW IN SILENCE, AND NEVER KEEPS A ROW
    IT HAS ALREADY CALLED AN ERROR.**

    A row is either an artisan or a ``ParseProblem`` with ``severity="error"``; there is no third
    outcome and no ``continue`` that leaves nothing behind.

    ⚠ **THE SECOND HALF OF THAT INVARIANT WAS PROSE UNTIL ``errored`` EXISTED, AND FOUR HELPERS
    BROKE IT.** ``_read_place`` (state, district), ``_read_craft`` and ``_read_pehchan`` each file
    ``severity="error"`` through the ``problem`` closure and then FALL THROUGH — the closure only
    appends, it raises nothing and returns nothing, so the loop reached ``result.artisans.append``
    and kept the row with the offending field blanked. ``ParseProblem``'s own contract
    (``xlsx_table.py``) is that ``"error"`` means NOTHING WAS STORED FOR THAT ROW, and
    ``artisan_import`` counts on it literally: ``refused = len({p.row for p in problems if
    p.severity == "error"})``, followed by an assertion that ``created + matched + linked + refused
    == rowsRead``. A row counted in both halves makes that sum fail, and the module's own comment on
    that branch reads ``# pragma: no cover - a bug, not a state``.

    WHAT IT ACTUALLY COST, measured rather than reasoned: three of the four sites were caught
    downstream by ``ArtisanCreate`` (``require_location`` refuses a blank state or district,
    ``require_craft`` a blank craft), so those produced a duplicate refusal sentence and nothing
    worse. **The masked-Pehchan branch had no backstop.** ``reconcile_pehchan`` resolves an
    unanswered flag to False rather than refusing, so a row whose card number was pasted off a
    screen as ``XXXX XXXX 3456`` was CREATED with the card number silently dropped, while the 201
    payload and the permanent ``DwArtisanImport`` ledger both told the officer that row had been
    refused. Re-uploading the corrected sheet does not repair it either: the row now matches on
    Aadhaar and takes the ``matched`` branch, whose own message is "the existing record was not
    changed and was not re-filed". A regulated identifier, dropped permanently, reported as a
    refusal.

    So the errors filed anywhere in a row are collected and the row is dropped at the bottom. The
    row's WARNINGS are still emitted (a formula that read as blank, an ignored column) because they
    explain the refusal rather than compete with it, and the ceiling test is unaffected: a refused
    row was never going to count against ``MAX_ARTISANS``.

    THE KEPT COUNT IS A LOCAL AND IS NOT READ OFF ``result.artisans``, which is the shape
    ``questionnaire_xlsx._read_questions`` records having been fixed for: reading a ceiling off a
    list that is only assembled at the end is how the cap silently never fires. Here the list IS
    appended to as we go, so the two agree — the local is kept anyway, because the next person to
    restructure this loop will make the same change that broke the other one.
    """
    kept = 0
    ignored_reported: set[str] = set()
    #: Row numbers something has filed an ``error`` for. A SET OF ROWS AND NOT A PER-ROW FLAG,
    #: deliberately: it is the same shape ``artisan_import``'s ``refused_rows`` is counted with, so
    #: "was this row refused" is answered here exactly as the importer answers it, and the two
    #: cannot come to disagree about a row carrying two errors.
    errored: set[int] = set()

    def get(values: list[str], role: str) -> str:
        for index, (this_role, _label) in columns.items():
            if this_role == role and index < len(values):
                value = values[index]
                return "" if isinstance(value, FormulaCell) else value
        return ""

    def formula_in(values: list[str]) -> str | None:
        for index in sorted(columns):
            if index < len(values) and isinstance(values[index], FormulaCell):
                return str(values[index])
        return None

    def problem(row: int, severity: str, reason: str, value: str | None = None) -> None:
        # THE RECORD OF THE REFUSAL IS MADE HERE, at the one place every refusal in this loop goes
        # through, rather than at each of the dozen call sites. A helper that files an error and
        # forgets to say so is precisely the defect this closure now makes impossible — see the
        # docstring.
        if severity == "error":
            errored.add(row)
        result.problems.append(
            ParseProblem(sheet=sheet, row=row, severity=severity, reason=reason, value=value)
        )

    for row_number, values in rows:
        if row_number <= header_row:
            continue
        if not any(str(v).strip() for v in values):
            continue  # a blank spacer row is not a row somebody meant to fill in
        result.rowsRead += 1

        if kept >= MAX_ARTISANS:
            problem(
                row_number,
                "error",
                f"This upload stops at {MAX_ARTISANS} artisans and this row is past that. Split "
                f"the list into smaller files and upload them one after another — each upload adds "
                f"to the workshop's roster rather than replacing it.",
            )
            continue

        # A formula with no cached result reads as EMPTY here and is not empty on the uploader's
        # screen. Reported once per row, before anything else, because every "required column is
        # blank" refusal below would otherwise be a lie about that row.
        formula = formula_in(values)

        name = clip(get(values, "name").strip(), MAX_NAME_CHARS)
        raw_aadhaar = get(values, "aadhaarNumber").strip()

        if not name:
            problem(
                row_number,
                "error",
                (
                    "This row has no artisan name, so nothing was created for it."
                    + (
                        f" One of its cells holds a formula Excel has never calculated "
                        f"({clip(formula, 60)}); open the file in Excel, press Ctrl+S and upload "
                        f"the saved copy."
                        if formula
                        else ""
                    )
                ),
            )
            continue

        # ── THE IDENTITY NUMBER. EVERY REFUSAL BELOW CARRIES A MASK AND NEVER THE DIGITS. ───────
        aadhaar = normalize_aadhaar(raw_aadhaar)
        if not aadhaar:
            problem(
                row_number,
                "error",
                f"{name} has no Aadhaar number. An artisan record cannot be created without one — "
                f"it is what keeps the same artisan from being recorded twice.",
            )
            continue
        if is_masked_aadhaar(aadhaar):
            problem(
                row_number,
                "error",
                f"{name}'s Aadhaar number is a masked one (it contains X's), which means it was "
                f"copied off a screen rather than read off the card. Type the twelve digits, or "
                f"leave this person off the list and add them in the app.",
                mask_aadhaar(aadhaar),
            )
            continue
        aadhaar_problem = aadhaar_error(aadhaar)
        if aadhaar_problem:
            problem(row_number, "error", f"{name}: {aadhaar_problem}", mask_aadhaar(aadhaar))
            continue

        # ── THE OTHER TWO REQUIRED PROSE COLUMNS ────────────────────────────────────────────────
        dos = clip(get(values, "dos").strip(), MAX_PROSE_CHARS)
        donts = clip(get(values, "donts").strip(), MAX_PROSE_CHARS)
        missing = [label for label, value in (("Do's", dos), ("Don'ts", donts)) if not value]
        if missing:
            problem(
                row_number,
                "error",
                f"{name} has no {' and no '.join(missing)}. Both are required on an artisan record "
                f"— they are what the workshop's guidance notes are built from.",
            )
            continue

        artisan = ParsedArtisan(row=row_number, name=name, aadhaarNumber=aadhaar)
        artisan.serialNo = _int_text(get(values, "serialNo"))
        artisan.dos = dos
        artisan.donts = donts
        artisan.localName = clip(get(values, "localName").strip(), MAX_SHORT_CHARS)
        artisan.gender = clip(get(values, "gender").strip(), MAX_SHORT_CHARS)
        artisan.phone = clip(get(values, "phone").strip(), MAX_SHORT_CHARS)
        artisan.email = clip(get(values, "email").strip(), MAX_SHORT_CHARS)
        artisan.address = clip(get(values, "address").strip(), MAX_PROSE_CHARS)
        artisan.village = clip(get(values, "village").strip(), MAX_SHORT_CHARS)
        artisan.notes = clip(get(values, "notes").strip(), MAX_PROSE_CHARS)

        _read_place(artisan, get, values, defaults, problem)
        _read_craft(artisan, get, values, defaults, problem)
        _read_dates_and_experience(artisan, get, values, problem)
        _read_pehchan(artisan, get, values, problem)

        if formula:
            problem(
                row_number,
                "warning",
                f"One of this row's cells holds a formula Excel has never calculated "
                f"({clip(formula, 60)}), so it was read as blank. Open the file in Excel, press "
                f"Ctrl+S and upload the saved copy if that cell should have had a value in it.",
            )

        for role, sentence in _ACCEPTED_AND_IGNORED.items():
            if role in ignored_reported:
                continue
            if any(r == role for r, _ in columns.values()) and get(values, role).strip():
                # ONCE PER FILE AND NOT ONCE PER ROW. Fifteen identical warnings about the Age
                # column is a report nobody reads to the bottom of, and the fact is about the
                # COLUMN. ``row=None`` is how ``ParseProblem`` says "this is about the sheet".
                result.problems.append(
                    ParseProblem(sheet=sheet, row=None, severity="warning", reason=sentence)
                )
                ignored_reported.add(role)

        if row_number in errored:
            # AN ERROR MEANS NOTHING WAS STORED FOR THIS ROW — ``ParseProblem``'s contract, the
            # report's arithmetic, and this function's own first sentence. The four helpers above
            # cannot say "drop this row" (they return None and the closure only appends), so the
            # decision is taken here, where the row is about to be kept.
            continue
        result.artisans.append(artisan)
        kept += 1


def _read_place(
    artisan: ParsedArtisan,
    get: Any,
    values: list[str],
    defaults: ArtisanDefaults,
    problem: Any,
) -> None:
    """Place, state, district and pincode — the four columns that become a ``Location``.

    STATE AND DISTRICT ARE CLOSED LISTS AND ARE RESOLVED HERE, not left for the create to 422 on.
    ``require_location`` demands both on create, so a row that cannot answer them is a row that
    cannot become an artisan, and finding that out one row at a time from a Pydantic error is not a
    report anybody can act on.

    AN ``error`` FILED HERE REFUSES THE WHOLE ROW, and it does so in ``_read_rows`` rather than by
    anything this function returns — see that function's docstring for why the four helpers signal
    through the ``problem`` closure and for what it cost when the row was kept anyway. The blanked
    ``state``/``district`` left behind on the parsed object is therefore never read; it is kept so
    that the remaining columns below still have somewhere to land while the row is being described.

    THE DISTRICT IS VALIDATED WITHIN ITS STATE, which is the only scope it means anything in:
    several district names are used by two states at once (Bilaspur, Hamirpur, Aurangabad,
    Pratapgarh, Bijapur), so the refusal names the state it was checked against.

    A BAD PINCODE IS A ``warning`` AND THE ROW STILL IMPORTS. ``require_location`` deliberately does
    not demand one — it cannot be answered from memory in a village with no signal, which is the
    test every mandatory field in this codebase has to pass.
    """
    from app.services.address import validate_district, validate_pincode, validate_state

    row = artisan.row
    place = clip(get(values, "place").strip(), MAX_PLACE_CHARS)
    if not place and defaults.place:
        place = clip(defaults.place, MAX_PLACE_CHARS)
        problem(
            row,
            "warning",
            f"{artisan.name} has no Place, so the workshop's venue ({place}) was used. Fill the "
            f"column in if they work somewhere else.",
        )
    artisan.place = place

    raw_state = get(values, "state").strip()
    used_default_state = False
    if not raw_state and defaults.state:
        raw_state, used_default_state = defaults.state, True
    try:
        artisan.state = validate_state(raw_state) or ""
    except ValueError as exc:
        problem(row, "error", f"{artisan.name}: {exc}", raw_state)
        artisan.state = ""
    else:
        if used_default_state and artisan.state:
            problem(
                row,
                "warning",
                f"{artisan.name} has no State, so the workshop's ({artisan.state}) was used.",
            )

    raw_district = get(values, "district").strip()
    used_default_district = False
    if not raw_district and defaults.district:
        raw_district, used_default_district = defaults.district, True
    try:
        artisan.district = validate_district(artisan.state or None, raw_district) or ""
    except ValueError as exc:
        problem(
            row,
            "error",
            f"{artisan.name}: {exc}",
            raw_district,
        )
        artisan.district = ""
    else:
        if used_default_district and artisan.district:
            problem(
                row,
                "warning",
                f"{artisan.name} has no District, so the workshop's ({artisan.district}) was used.",
            )

    raw_pin = get(values, "pincode").strip()
    if raw_pin:
        try:
            artisan.pincode = validate_pincode(raw_pin) or ""
        except ValueError as exc:
            # A WARNING AND NOT AN ERROR, and the pincode is dropped rather than the row. See the
            # docstring: the address is still complete without it.
            problem(
                row, "warning", f"{artisan.name}: {exc} The row was imported without it.", raw_pin
            )
            artisan.pincode = ""


def _read_craft(
    artisan: ParsedArtisan,
    get: Any,
    values: list[str],
    defaults: ArtisanDefaults,
    problem: Any,
) -> None:
    """The craft NAME only. Resolving it against the controlled vocabulary is the importer's job.

    A BLANK FALLS BACK TO THE WORKSHOP'S CRAFT WITH A WARNING, because a design & prototype workshop
    is FOR one craft and every artisan at it practises it. If the workshop has none either, the row
    is an error and ``_read_rows`` drops it — which is also what ``ArtisanCreate.require_craft``
    would have done to it one layer down, except that the refusal now reaches the officer as the
    sentence below rather than as a Pydantic message about a field they never typed.
    """
    craft = get(values, "craftName").strip()
    if not craft and defaults.craftName:
        craft = defaults.craftName
        problem(
            artisan.row,
            "warning",
            f"{artisan.name} has no Craft, so the workshop's ({craft}) was used.",
        )
    if not craft:
        problem(
            artisan.row,
            "error",
            f"{artisan.name} has no Craft and the workshop does not name one either. Every artisan "
            f"record has to be filed under a craft.",
        )
    artisan.craftName = craft


def _read_dates_and_experience(
    artisan: ParsedArtisan, get: Any, values: list[str], problem: Any
) -> None:
    """Date of birth, craft start date, and the years/months pair.

    ``experienceYears`` IS BOUNDED 0..90 AND ``experienceMonths`` 0..11, mirroring ``ArtisanCreate``
    EXACTLY. Two different ceilings would be a number this pro-forma accepts and the artisan form
    then refuses on a row it filled in itself. **11 and not 12**: twelve months is a year the box
    above already holds.

    A VALUE OUT OF RANGE IS A ``warning`` AND IS DROPPED, not a row refusal. Somebody who typed 900
    in the years column has a typo in one cell; refusing the whole row would lose the twenty-four
    fields they got right, and the create would refuse the row anyway if the value travelled.
    """
    row = artisan.row
    for column, target, label in (
        ("dateOfBirth", "dateOfBirth", "Date of birth"),
        ("craftStartDate", "craftStartDate", "Craft start date"),
    ):
        raw = get(values, column).strip()
        if not raw:
            continue
        parsed = _date_text(raw)
        if parsed is None:
            problem(
                row,
                "warning",
                f"{artisan.name}: {label} '{clip(raw, 40)}' is not a date this could read, so it "
                f"was left blank. Use YYYY-MM-DD or DD/MM/YYYY.",
                clip(raw, 40),
            )
            continue
        setattr(artisan, target, parsed)

    for column, label, low, high in (
        ("experienceYears", "Years practising the craft", 0, 90),
        ("experienceMonths", "Months (on top of the years)", 0, 11),
    ):
        raw = get(values, column).strip()
        if not raw:
            continue
        parsed = _int_text(raw)
        if parsed is None or not (low <= parsed <= high):
            problem(
                row,
                "warning",
                f"{artisan.name}: {label} must be a whole number between {low} and {high}; "
                f"'{clip(raw, 20)}' was left blank.",
                clip(raw, 20),
            )
            continue
        setattr(artisan, column, parsed)


def _read_pehchan(artisan: ParsedArtisan, get: Any, values: list[str], problem: Any) -> None:
    """The PM Vishwakarma card, its number, and the three-branch reconciliation between them.

    THE SAME THREE BRANCHES ``ArtisanCreate.reconcile_pehchan`` APPLIES, applied here so the report
    can EXPLAIN them. Yes with no number is the one combination nothing downstream can interpret;
    No clears a number that came with it, because the form disables that box when the answer is No
    and a number arriving beside it is stale UI state rather than an instruction; unanswered
    resolves from what was sent.

    **A MASKED CARD NUMBER IS AN ``error`` AND THE ROW IS REFUSED, AND THIS IS THE BRANCH WITH A
    REAL SCAR BEHIND IT.** ``pehchan_error`` ACCEPTS ``XXXX XXXX 3456`` — fourteen alphanumerics,
    inside the 4-32 window, with no checksum to fail — so nothing downstream would refuse it, and a
    mask stored over a real card number is exactly how one was lost once before. A mask in an upload
    is a copy-paste out of a screen.

    ⚠ **AND NOTHING DOWNSTREAM REFUSES THE ROW EITHER, WHICH IS WHY THE REFUSAL HAS TO BIND HERE.**
    The other three error branches in this reader are backstopped by ``ArtisanCreate`` —
    ``require_location`` and ``require_craft`` throw on a blank state, district or craft — but a
    dropped card number is not a refusable state: ``reconcile_pehchan`` resolves an unanswered flag
    to False and accepts the record. So while this branch filed its error and let the row through,
    the artisan WAS created, without the card number, on a report that told the officer the row had
    been refused; the corrected sheet then matched on Aadhaar and "the existing record was not
    changed and was not re-filed". ``_read_rows`` now drops any row this files an error for. The
    ``return`` below is kept — there is nothing left to reconcile once the number is gone — but it
    is no longer what decides the row's fate.
    """
    row = artisan.row
    raw_flag = get(values, "pehchanCardAvailable").strip()
    raw_number = get(values, "pehchanCardNumber").strip()

    flag = truthy(raw_flag) if raw_flag else None
    if raw_flag and flag is None:
        problem(
            row,
            "warning",
            f"{artisan.name}: 'Holds an Artisan Pehchan Card' reads '{clip(raw_flag, 20)}', which "
            f"is neither Yes nor No. It was worked out from the card number column instead.",
            clip(raw_flag, 20),
        )

    number = ""
    if raw_number:
        if is_masked_aadhaar(raw_number):
            problem(
                row,
                "error",
                f"{artisan.name}'s Pehchan card number is a masked one (it contains X's), which "
                f"means it was copied off a screen rather than read off the card. Type the number "
                f"as it is printed, or leave the column blank.",
                None,
            )
            artisan.pehchanCardAvailable = flag
            return
        normalised = normalize_pehchan(raw_number)
        card_problem = pehchan_error(normalised)
        if card_problem:
            problem(
                row,
                "warning",
                f"{artisan.name}: {card_problem} The row was imported without a card number.",
                None,
            )
        else:
            number = normalised or ""

    if flag is None:
        # Unanswered: a number implies Yes, no number means No. The same branch an older client
        # that predates these fields gets.
        flag = bool(number)
    elif flag and not number:
        problem(
            row,
            "warning",
            f"{artisan.name} is marked as holding an Artisan Pehchan Card but no number was given, "
            f"so the answer was recorded as No. Add the number and edit the record to correct it.",
        )
        flag = False
    elif not flag and number:
        problem(
            row,
            "warning",
            f"{artisan.name} is marked as NOT holding an Artisan Pehchan Card, so the card number "
            f"in the next column was left out.",
        )
        number = ""

    artisan.pehchanCardAvailable = flag
    artisan.pehchanCardNumber = number


# --- Writing ------------------------------------------------------------------------------------

#: Column widths, in characters. The prose columns are where the typing happens.
_WIDTHS = {
    "serialNo": 8,
    "name": 26,
    "localName": 24,
    "gender": 10,
    "dateOfBirth": 14,
    "age": 10,
    "phone": 16,
    "email": 24,
    "craftName": 20,
    "specialisation": 18,
    "place": 20,
    "address": 34,
    "village": 18,
    "district": 18,
    "state": 18,
    "pincode": 12,
    "aadhaarNumber": 18,
    "pehchanCardAvailable": 16,
    "pehchanCardNumber": 20,
    "experienceYears": 14,
    "experienceMonths": 14,
    "craftStartDate": 16,
    "dos": 40,
    "donts": 40,
    "notes": 34,
}

#: The 25 headings, in the order the pro-forma prints them. **THE ORDER IS FOR A HUMAN AND IS NOT
#: LOAD-BEARING** — the parser finds columns by heading, so an office that drags Aadhaar to the
#: front loses nothing. It is grouped the way somebody reads a list off a page: who they are, how to
#: reach them, what they make, where they are, who the state says they are, how long they have done
#: it, and what to say about them.
_COLUMNS: tuple[tuple[str, str], ...] = (
    ("serialNo", "S. No."),
    ("name", "Artisan name"),
    ("localName", "Name in the local language"),
    ("gender", "Gender"),
    ("dateOfBirth", "Date of birth"),
    ("age", "Age (years)"),
    ("phone", "Phone"),
    ("email", "Email"),
    ("craftName", "Craft"),
    ("specialisation", "Specialisation"),
    ("place", "Place"),
    ("address", "Address"),
    ("village", "Village"),
    ("district", "District"),
    ("state", "State"),
    ("pincode", "PIN code"),
    ("aadhaarNumber", "Aadhaar number"),
    ("pehchanCardAvailable", "Holds an Artisan Pehchan Card"),
    ("pehchanCardNumber", "Pehchan card number"),
    ("experienceYears", "Years practising the craft"),
    ("experienceMonths", "Months (on top of the years)"),
    ("craftStartDate", "Craft start date"),
    ("dos", "Do's"),
    ("donts", "Don'ts"),
    ("notes", "Notes"),
)

#: The five headings a row cannot be imported without. Marked on the sheet, listed on the Help
#: sheet, and enforced by the parser — three places saying one thing, which is one more than usual
#: and is deliberate: a required column discovered at upload time costs a whole round trip through
#: an office.
_REQUIRED = frozenset({"name", "craftName", "place", "aadhaarNumber", "dos", "donts"})


def _header(ws: Worksheet, row: int, labels: tuple[tuple[str, str], ...]) -> None:
    fill = PatternFill(fill_type="solid", start_color=f"FF{_BRAND}")
    required_fill = PatternFill(fill_type="solid", start_color="FF9A3412")
    for index, (key, label) in enumerate(labels, start=1):
        cell = _put(ws, row, index, label)
        cell.font = Font(bold=True, color=_WHITE)
        # THE REQUIRED COLUMNS ARE A DIFFERENT COLOUR AND NOT AN ASTERISK, because an asterisk is
        # part of the heading TEXT and the parser normalises punctuation away — so it would work,
        # and it would also teach somebody that decorating a heading is safe, which it is only
        # because of a rule they cannot see. Colour carries no meaning to the reader.
        cell.fill = required_fill if key in _REQUIRED else fill
        cell.alignment = Alignment(horizontal="left", vertical="center", wrap_text=True)
        ws.column_dimensions[ws.cell(row=row, column=index).column_letter].width = _WIDTHS.get(
            key, 20
        )
    ws.row_dimensions[row].height = 32
    ws.freeze_panes = f"A{row + 1}"


def _details_sheet(ws: Worksheet, workshop: Any | None) -> None:
    """The Details sheet: the workshop this list is for, and what a blank cell will fall back to.

    **``Design workshop ID`` IS READ BACK AND CHECKED**, which is the whole reason it is written.
    If it names a different workshop than the URL being uploaded to, the upload answers 409 naming
    both — the same guard the questionnaire upload makes, for the sharper version of its reason:
    importing fifteen artisans into the wrong workshop files fifteen people's regulated records
    under a stranger's project.

    THE THREE DEFAULT ROWS ARE HERE SO THE DEFAULTS ARE VISIBLE RATHER THAN INVISIBLE. An office
    that leaves the State column blank on every row should be able to see, in the file, which state
    it is about to be filled in with.

    ⚠ **NONE OF THESE LABELS MAY NORMALISE TO ONE OF ``questionnaire_xlsx._DETAIL_KEYS``' aliases.**
    Both features write a sheet called "Details" and read one back by matching column A. See
    :data:`_DETAIL_KEYS`.
    """
    ws.sheet_properties.tabColor = f"FF{_BRAND}"
    heading = _put(ws, 1, 1, "Artisan list details")
    heading.font = Font(bold=True, size=14, color=f"FF{_BRAND}")
    pairs = [
        ("Design workshop ID", getattr(workshop, "id", "") or ""),
        ("Workshop title", getattr(workshop, "title", "") or ""),
        ("State", getattr(workshop, "state", "") or ""),
        ("District", getattr(workshop, "district", "") or ""),
        ("Venue", getattr(workshop, "venue", "") or ""),
    ]
    for offset, (label, value) in enumerate(pairs):
        row = 3 + offset
        cell = _put(ws, row, 1, label)
        cell.font = Font(bold=True)
        value_cell = _put(ws, row, 2, value)
        value_cell.alignment = Alignment(wrap_text=True, vertical="top")
        if offset == 0:
            value_cell.font = Font(color=_GREY)
    note_row = 3 + len(pairs) + 1
    note = _put(
        ws,
        note_row,
        1,
        "Leave 'Design workshop ID' exactly as you found it — it is how the app checks that this "
        "list is going into the workshop you filled it in for. The State, District and Venue above "
        "are what blank cells on the Artisans sheet fall back to; every row that uses one is "
        "reported back to you.",
    )
    note.alignment = Alignment(wrap_text=True, vertical="top")
    note.font = Font(italic=True, color=_GREY)
    ws.merge_cells(start_row=note_row, start_column=1, end_row=note_row + 2, end_column=4)
    ws.column_dimensions["A"].width = 24
    ws.column_dimensions["B"].width = 62


_HELP_LINES: tuple[tuple[str, str], ...] = (
    ("h", "How to fill this in"),
    (
        "p",
        "Type one artisan per row on the 'Artisans' sheet, then upload the file. The app finds the "
        "columns by their headings, not by where they are, so you can move columns around, add "
        "columns of your own, and put a title above the headings.",
    ),
    (
        "b",
        "Six columns are required and are shaded differently: Artisan name, Craft, Place, Aadhaar "
        "number, Do's and Don'ts. A row missing any of them is reported back to you and nothing is "
        "created for it — the rest of the file still imports.",
    ),
    (
        "b",
        "Craft, State and District are chosen from lists the app already holds. A spelling it does "
        "not recognise is reported with the row number; it is never created for you. Leave State, "
        "District, Craft or Place blank and the workshop's own values on the Details sheet are "
        "used — you are told which rows that happened on.",
    ),
    (
        "b",
        "Age and Specialisation are accepted and ignored. An artisan record holds a date of birth "
        "rather than an age (an age written down is wrong within a year and nothing notices), and "
        "the participant table works the specialisation out from the craft.",
    ),
    (
        "b",
        "Years practising the craft is 0 to 90 and Months is 0 to 11 — the months box is the "
        "REMAINDER on top of the years, never a total. Twelve months is a year the box before it "
        "already holds.",
    ),
    (
        "b",
        "Phone and Email are not checked. A malformed number is stored on the artisan record and "
        "then DROPPED on the way into the workshop's participant table, so the contact box there "
        "comes up blank with nothing said about why. Check them yourself before uploading.",
    ),
    (
        "b",
        "Nobody is ever created twice. If somebody on this list is already in the app — matched on "
        "the Aadhaar or Pehchan number — they are ADDED TO THIS WORKSHOP and their existing record "
        "is left exactly as it is. Where your spreadsheet disagrees with what is stored, the stored "
        "value stands and the disagreement is reported. Nothing in this file can overwrite a record.",
    ),
    ("h", "This file contains Aadhaar numbers"),
    (
        "p",
        "Aadhaar is regulated personal data. Once you have filled this file in, it is a list of "
        "identity numbers sitting in your Downloads folder.",
    ),
    (
        "b",
        "Do not email it, do not put it in a shared folder or a chat, and delete it once the upload "
        "has been confirmed. The app never stores the file itself — only its name, the counts, and "
        "the list of rows it could not read.",
    ),
    (
        "b",
        "Inside the app the number is stored once and shown MASKED (XXXX XXXX 9012) to everyone "
        "except the researcher who recorded that artisan and Professor-and-above. The workshop's "
        "participant table and every report print the masked form.",
    ),
    (
        "b",
        "Never paste a masked number back in. A number with X's in it is refused, because a mask "
        "saved over a real card number is a number nobody can get back.",
    ),
    (
        "b",
        "The Aadhaar number is what stops the same artisan being recorded twice. That is the only "
        "thing it is used for here, and it is why the column cannot be left out.",
    ),
    ("h", "An example"),
)

_HELP_EXAMPLE: tuple[tuple[str, ...], ...] = (
    ("S. No.", "Artisan name", "Craft", "Place", "Aadhaar number", "Do's", "Don'ts"),
    (
        "1",
        "Ramesh Devi",
        "Block printing",
        "Bagru",
        "2234 5678 9018",
        "Wash the cloth before printing",
        "Do not let the dye dry on the block",
    ),
    (
        "2",
        "Sita Bai",
        "Block printing",
        "Bagru",
        "3234 5678 9012",
        "Keep the blocks oiled",
        "Do not stack wet prints",
    ),
)


def _help_sheet(ws: Worksheet) -> None:
    """The instructions, and the PII warning, IN THE ARTEFACT.

    Saying it on the download page is not enough and the reason is written down elsewhere in this
    repository: **the file outlives the page**. Somebody opens this workbook three weeks later, on a
    different machine, having never seen the screen it came from.

    THE WORKED EXAMPLE LIVES HERE AND NOT ON THE ARTISANS SHEET, for the reason
    ``questionnaire_xlsx.build_pro_forma`` records: example rows under the headings read well and
    import badly — the office adds their list below the examples and uploads a roster whose first
    two artisans are Ramesh Devi and Sita Bai, with no way to work out where they came from. On this
    sheet it cannot be imported. The Aadhaar numbers in it are checksum-valid inventions, because an
    example number that the app would refuse teaches the wrong thing.
    """
    ws.sheet_properties.tabColor = "FF9CA3AF"
    row = 1
    for kind, text in _HELP_LINES:
        cell = _put(ws, row, 1, text if kind != "b" else f"•  {text}")
        if kind == "h":
            cell.font = Font(bold=True, size=13, color=f"FF{_BRAND}")
            row += 1
        elif kind == "p":
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.merge_cells(start_row=row, start_column=1, end_row=row + 1, end_column=7)
            row += 2
        else:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.merge_cells(start_row=row, start_column=1, end_row=row + 2, end_column=7)
            row += 3
    row += 1
    for offset, line in enumerate(_HELP_EXAMPLE):
        for column, value in enumerate(line, start=1):
            cell = _put(ws, row + offset, column, value)
            if offset == 0:
                cell.font = Font(bold=True, color=f"FF{_BRAND}")
    ws.column_dimensions["A"].width = 26
    for letter in ("B", "C", "D", "E", "F", "G"):
        ws.column_dimensions[letter].width = 24


def build_artisan_pro_forma(*, workshop: Any | None = None) -> bytes:
    """The BLANK pro-forma: headings, a Details sheet naming the workshop, and instructions.

    DELIBERATELY EMPTY UNDER THE HEADINGS. See :func:`_help_sheet` for why the worked example is on
    the instructions sheet.

    GENERATED ON EVERY REQUEST rather than cached: it is a few kilobytes of openpyxl, and a stale
    cached copy whose columns no longer match the parser is an office typing a hundred rows into
    headings the app no longer recognises. It also has to be, because the Details sheet is per
    workshop.

    EVERY VALUE GOES IN THROUGH ``xlsx_report._put`` AND NOTHING ELSE — never ``ws.cell(...)`` and
    never ``ws.append(...)``. ``tests/test_xlsx_report.py`` records the three separate ways ordinary
    field text made Excel open a download with "We found a problem with some content", and that door
    is what keeps them fixed.
    """
    wb = Workbook()
    used: set[str] = set()
    artisans = wb.active
    artisans.title = _safe_title(SHEET_ARTISANS, used)
    artisans.sheet_properties.tabColor = f"FF{_BRAND}"
    _header(artisans, 1, _COLUMNS)

    _details_sheet(wb.create_sheet(title=_safe_title(SHEET_DETAILS, used)), workshop)
    _help_sheet(wb.create_sheet(title=_safe_title(SHEET_HELP, used)))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def pro_forma_filename(workshop: Any | None = None) -> str:
    """The name the browser saves the download under.

    NAMED AFTER THE WORKSHOP WHERE THERE IS ONE, because an officer preparing four workshops has
    four of these in one folder and ``artisan-list-pro-forma (3).xlsx`` tells them nothing about
    which. Non-alphanumerics are collapsed rather than escaped — this string goes into a
    ``Content-Disposition`` header, and a quote or a newline in a workshop title is a header
    injection rather than an odd filename.
    """
    title = str(getattr(workshop, "title", "") or "").strip()
    if not title:
        return ARTISAN_PRO_FORMA_FILENAME
    stem = re.sub(r"[^A-Za-z0-9]+", "-", title).strip("-")[:60]
    return f"artisan-list-{stem or 'pro-forma'}.xlsx"


# THE INVARIANT, CHECKED AT IMPORT. The header writer and the parser must agree about which columns
# exist: a heading printed on the pro-forma that no alias matches is a column an office fills in and
# the app silently ignores, which is the single most expensive failure this file can have — it is
# discovered after the typing, not before.
_DECLARED = {key for key, _label in _COLUMNS}
_MATCHED = set(_ARTISAN_COLUMN_ALIASES)
if _DECLARED != _MATCHED:  # pragma: no cover - a declaration error, not a runtime state
    raise RuntimeError(
        "artisan_xlsx._COLUMNS and _ARTISAN_COLUMN_ALIASES must name the same columns; "
        f"only on the sheet: {sorted(_DECLARED - _MATCHED)}; "
        f"only in the aliases: {sorted(_MATCHED - _DECLARED)}."
    )
# And every printed heading must actually match its own column, which is the same invariant asked
# of the ARTEFACT rather than of the names: a heading the writer prints and the reader's normaliser
# turns into something no alias holds is a round trip that fails on the file the app itself made.
for _key, _label in _COLUMNS:
    if norm_header(_label) not in _ARTISAN_COLUMN_ALIASES[_key]:  # pragma: no cover
        raise RuntimeError(
            f"artisan_xlsx prints the heading {_label!r} for column {_key!r}, which normalises to "
            f"{norm_header(_label)!r} and is not one of its aliases. The pro-forma this module "
            f"writes would not be readable by the parser in the same module."
        )
