"""Reading a TABLE out of an uploaded .xlsx, forgivingly, without dropping a row in silence.

This module is the READER half of ``questionnaire_xlsx``, lifted out of it verbatim so that a
second uploader of a second kind of table does not become a second copy of it.

=======================================================================================
WHY THIS IS AN EXTRACTION AND NOT A NEW FILE BESIDE THE OLD ONE
=======================================================================================

``questionnaire_xlsx`` is hard-wired to the questionnaire vocabulary in three places: its header
scan requires a ``prompt`` role, its ceilings are ``MAX_QUESTIONS``/``MAX_SECTIONS``, and its return
type is ``ParsedQuestionnaire``. None of those transfers to an artisan roster, and *everything else
in it does* — the NFKC header normalisation, the alias families, the two-pass formula map, the magic
byte branch, the never-drop-a-row problem contract. Copying it is how two readers of one file format
drift the first time one of them is corrected, which is the argument ``questionnaire_xlsx``'s own
header makes about importing ``xlsx_report._put`` rather than restating it, and the argument
``questionnaire.py`` makes about sharing ``csv_response`` with ``export.py`` rather than writing a
second CSV encoder.

So the vocabulary stays with each caller and the MECHANISM lives here. ``questionnaire_xlsx`` passes
``aliases=_COLUMN_ALIASES, required_role="prompt"``; ``artisan_xlsx`` passes its own table and
``required_role="name"``. Neither knows anything about the other.

=======================================================================================
WHAT MUST NOT BE "SIMPLIFIED" ON THE WAY PAST — EACH ONE IS A SHIPPED BUG FIX
=======================================================================================

1. **Two ``load_workbook`` passes, not one.** ``data_only=True`` returns the value Excel last
   *calculated and stored*. A workbook written by a script — LibreOffice headless, a generator,
   openpyxl itself — has formulas and no cached results, so those cells read as empty. The second,
   non-evaluating pass is what turns "row 12 is blank" into "row 12 holds a formula Excel has never
   calculated", which is the single most confusing thing a parser of this format can get wrong.

2. **The second pass's failure is swallowed and answers ``{}``.** The evaluating load has already
   succeeded by then, so a failure here costs only a diagnostic. A diagnostic nicety must never be
   the thing that fails an upload the caller's file was fine for.

3. **The unreadable-file message branches on MAGIC BYTES**, not on the exception class and not on
   the extension. The extension is the thing most likely to be wrong — somebody renamed
   ``report.xls`` to ``report.xlsx`` to get past an upload filter — and openpyxl raises no
   ``InvalidFileException`` for a ``BytesIO`` because it has no filename to inspect, so an old .xls
   arrives as a bare ``zipfile.BadZipFile`` and "the upload may have been cut short" sends somebody
   to re-download a file that was never the problem.

4. **The header row is FOUND, not assumed**, up to ``max_scan_rows``; columns are located by their
   headings and not by position; and a heading is matched after NFKC normalisation with punctuation
   and non-breaking spaces folded away. "Section Code", "section_code" and "SECTION  CODE " are the
   same column to everyone except a computer, and a header pasted out of a Word table routinely
   carries a U+00A0 that makes the column match nothing while looking identical on screen.

5. **A row that cannot be read is REPORTED, never dropped** — :class:`ParseProblem`, carrying the
   sheet, the 1-based Excel row number exactly as the row gutter shows it, and a sentence. A silent
   drop means somebody uploads forty rows, sees thirty-eight, and has no way to find out which two
   went missing or why.

Nothing in this module reaches the database, the request, or the caller's domain vocabulary. It is
pure over ``bytes``.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from io import BytesIO
from typing import Any
from zipfile import BadZipFile

from openpyxl import load_workbook
from openpyxl.utils.exceptions import InvalidFileException
from openpyxl.worksheet.worksheet import Worksheet

__all__ = [
    "FALSEISH",
    "OLE2_MAGIC",
    "TRUEISH",
    "ZIP_MAGIC",
    "FormulaCell",
    "ParseProblem",
    "XlsxTableError",
    "as_text_rows",
    "cell_text",
    "clip",
    "formula_map",
    "label_after",
    "load_sheets",
    "load_value_sheets",
    "norm_header",
    "pick_sheet",
    "read_details",
    "scan_header",
    "sheet_rows",
    "sheet_value_rows",
    "truthy",
]


class XlsxTableError(ValueError):
    """The upload is not a workbook this can open at all.

    Distinct from a :class:`ParseProblem`, which is one row of an otherwise-good file. This is the
    whole file: not a zip, not an .xlsx, password-protected, or truncated. The message is written to
    be shown to the uploader as-is, and each caller re-raises it as its own domain error so that a
    route's ``except`` clause still names the feature it belongs to.
    """


# --- Header vocabulary --------------------------------------------------------------------------

# Non-breaking and friends. A header pasted out of a Word table or a browser routinely carries one of
# these instead of a space, and the column then matches nothing while looking identical on screen.
_SPACEY = re.compile(r"[\s  -\u200b  　]+")
# Punctuation a person adds to a header without meaning anything by it: "Question:", "Answer *",
# "Section (code)". Stripped before matching so all of those land on the same column.
_PUNCT = re.compile(r"[^\w\s]+", re.UNICODE)


def norm_header(value: Any) -> str:
    """A header cell reduced to what it MEANS: 'Section  Code:' and 'section_code' both -> 'section code'."""
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = _PUNCT.sub(" ", text.replace("_", " "))
    return _SPACEY.sub(" ", text).strip().lower()


TRUEISH = {"y", "yes", "true", "t", "1", "required", "mandatory", "compulsory", "x", "✓", "✔", "☑"}
FALSEISH = {"n", "no", "false", "f", "0", "optional", "not required", "", "-", "—", "na", "n a"}

#: What separates a prefix role from the label after it: "Answer — Ramesh", "Notes for Ramesh".
_LABEL_SPLIT = re.compile(r"\s*(?:[-–—:/|]|\bfor\b|\bby\b)\s*", re.IGNORECASE)


@dataclass(frozen=True)
class ParseProblem:
    """One thing the parser could not do cleanly, in terms the uploader can act on.

    ``row`` is the 1-based worksheet row exactly as Excel's row gutter shows it, so "row 34" means
    press Ctrl+G and type 34. ``severity`` is ``"error"`` when nothing was stored for that row and
    ``"warning"`` when it was stored but something had to be assumed.

    ``value`` IS A DIAGNOSTIC AND IS THEREFORE A DATA PATH. Anything a caller puts here reaches the
    HTTP response, whatever ledger the caller writes, and whatever a client renders. A caller
    reporting a regulated identifier must mask it BEFORE it arrives — nothing in this module
    inspects the string, because a module that cannot know what the column meant cannot know what
    is safe to echo. See ``artisan_import``, which masks at the one place the number is read.
    """

    sheet: str
    row: int | None
    severity: str
    reason: str
    value: str | None = None

    def payload(self) -> dict[str, Any]:
        return {
            "sheet": self.sheet,
            "row": self.row,
            "severity": self.severity,
            "reason": self.reason,
            "value": self.value,
        }


# --- Cells --------------------------------------------------------------------------------------


class FormulaCell(str):
    """Marker for a cell that holds a formula whose value was never cached.

    openpyxl in ``data_only`` mode returns the value Excel last *calculated* and stored in the file.
    A workbook written by a script — LibreOffice headless, a generator, openpyxl itself — has
    formulas but no cached results, so that value is ``None`` and the cell reads as empty. Reporting
    "row 12 is blank" for a row that visibly contains ``=B12&" (2024)"`` on the uploader's screen is
    the single most confusing thing this parser could say, so formula cells are detected on a second,
    non-evaluating pass and reported for what they are.
    """


def cell_text(value: Any) -> str:
    """One cell as trimmed text. ``None``, whitespace and Excel's ``#N/A`` family all read as empty."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "Yes" if value else "No"
    if isinstance(value, float) and value.is_integer():
        # Excel stores every number as a float, so a question numbered 3 arrives as 3.0 and a
        # question ID typed as a number would become "3.0" in the prompt.
        value = int(value)
    text = str(value).strip()
    if text.startswith("#") and text.endswith("!") and len(text) <= 10:
        return ""  # #REF!, #NAME?, #VALUE! — an error, not content
    if text in {"#N/A", "#NULL!"}:
        return ""
    return text


def clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def truthy(text: str) -> bool | None:
    """'Yes'/'x'/'✓' -> True, 'No'/'' -> False, anything else -> None (i.e. tell the uploader)."""
    key = norm_header(text)
    if key in TRUEISH:
        return True
    if key in FALSEISH:
        return False
    return None


# --- The header row -----------------------------------------------------------------------------


def _role_for(
    header: str,
    *,
    aliases: dict[str, tuple[str, ...]],
    prefix_roles: tuple[tuple[str, tuple[str, ...]], ...],
) -> tuple[str, str | None] | None:
    """Map one normalised header to (role, label), or None if it is not a column we know.

    ``aliases`` is tried in ITS OWN ORDER and that is load-bearing rather than incidental: the more
    specific families ("section title") have to be matched before a looser one ("section") could
    swallow them, and a dict preserves insertion order. Rebuilding it as a flat reverse map would
    make the answer depend on which spelling happened to be written last.
    """
    if not header:
        return None
    for role, names in aliases.items():
        if header in names:
            return (role, None)
    for role, prefixes in prefix_roles:
        for prefix in prefixes:
            if header == prefix or header.startswith(prefix + " "):
                return (role, label_after(header, prefix))
    return None


def label_after(header: str, prefix: str) -> str | None:
    """'answer ramesh devi' after prefix 'answer' -> 'Ramesh Devi'; a bare 'answer' -> None."""
    rest = header[len(prefix) :].strip()
    rest = _LABEL_SPLIT.sub(" ", rest).strip()
    return rest.title() if rest else None


def scan_header(
    rows: list[tuple[int, list[str]]],
    *,
    aliases: dict[str, tuple[str, ...]],
    required_role: str,
    prefix_roles: tuple[tuple[str, tuple[str, ...]], ...] = (),
    max_scan_rows: int = 15,
) -> tuple[int, dict[int, tuple[str, str | None]]] | None:
    """Find the header row and what each of its columns means.

    A row qualifies only if it names the ``required_role`` column. Nothing else is sufficient: a
    sheet whose first row happens to read "Notes" is not a questionnaire and is not an artisan list,
    and treating it as one would produce a form full of empty prompts — or a roster full of nameless
    artisans — rather than an honest "I could not find the column this table is keyed on".

    ``max_scan_rows`` is generous enough for a title block and a logo and small enough that a sheet
    of prose is not mistaken for a form.
    """
    repeatable = {role for role, _prefixes in prefix_roles}
    for row_number, values in rows[:max_scan_rows]:
        mapping: dict[int, tuple[str, str | None]] = {}
        seen_roles: set[str] = set()
        for index, raw in enumerate(values):
            role = _role_for(norm_header(raw), aliases=aliases, prefix_roles=prefix_roles)
            if role is None:
                continue
            # A repeated single-value column (two "Section Title"s) keeps the first; the prefix roles
            # legitimately repeat — one Answer/Notes pair per sitting — so they are exempt.
            if role[0] not in repeatable and role[0] in seen_roles:
                continue
            seen_roles.add(role[0])
            mapping[index] = role
        if required_role in seen_roles:
            return row_number, mapping
    return None


def sheet_rows(
    ws: Worksheet, formulas: dict[tuple[int, int], str], *, max_rows: int
) -> list[tuple[int, list[str]]]:
    """One sheet as (row number, [cell text, ...]), trailing blanks kept so column indices line up."""
    out: list[tuple[int, list[str]]] = []
    for row_number, row in enumerate(ws.iter_rows(values_only=True), start=1):
        if row_number > max_rows:
            break
        cells: list[str] = []
        for column, value in enumerate(row, start=1):
            text = cell_text(value)
            if not text:
                formula = formulas.get((row_number, column))
                if formula:
                    # Reads as empty here but is not empty on the uploader's screen. Carried through
                    # as a marker so the row can be reported precisely instead of as "blank".
                    cells.append(FormulaCell(formula))
                    continue
            cells.append(text)
        out.append((row_number, cells))
    return out


def sheet_value_rows(
    ws: Worksheet, formulas: dict[tuple[int, int], str], *, max_rows: int
) -> list[tuple[int, list[Any]]]:
    """One sheet as (row number, [RAW cell value, ...]), trailing blanks kept.

    ══ A SECOND FUNCTION RATHER THAN A FLAG ON THE FIRST, BECAUSE THE DIFFERENCE IS NOT COSMETIC ══

    :func:`sheet_rows` above answers text, which is right for every reader whose columns are prose.
    It is WRONG for a reader with a DATE column. A real Excel date arrives here as a ``datetime``
    that openpyxl has already resolved out of Excel's serial number — the one unambiguous copy of
    that date anywhere in the pipeline — and ``cell_text`` renders it "2026-03-12 00:00:00". A
    parser that then reads a date back out of that string has thrown the resolved value away and is
    guessing at a value it was handed. Worse, the guess is invisible: "03/04/2026" parses both ways.

    So a caller with a date column takes the raw values and calls ``cell_text`` per column itself —
    text for the prose columns, the resolved ``datetime`` for the date ones. The annual-plan
    directory (``app/services/annual_plan_xlsx.py``) is that caller.

    ══ THE EMPTINESS TEST IS ``cell_text`` AND MUST STAY ``cell_text`` ════════════════════════════

    ``not cell_text(value)`` looks equivalent to ``value is None or not str(value).strip()`` and is
    not, in the one case that matters most here. ``cell_text`` maps Excel's ERROR family to ``""`` —
    ``#REF!``, ``#NAME?``, ``#VALUE!``, ``#N/A``, ``#NULL!`` — so a cell holding a cached ``#REF!``
    over a formula is "empty" for the purpose of the formula lookup below, and is therefore reported
    as the formula it is. Under the cheaper predicate that cell is appended raw, the ``FormulaCell``
    marker is never made, and the "formula whose result is not saved" report stops firing for
    exactly the cells most likely to hold a broken formula. That is why the two readers share this
    line rather than each carrying a plausible-looking copy of it — and why
    ``tests/test_xlsx_table_value_rows.py`` builds a ``#REF!``-over-a-formula fixture, which no
    existing questionnaire or artisan test does.
    """
    out: list[tuple[int, list[Any]]] = []
    for row_number, row in enumerate(ws.iter_rows(values_only=True), start=1):
        if row_number > max_rows:
            break
        cells: list[Any] = []
        for column, value in enumerate(row, start=1):
            if not cell_text(value):
                formula = formulas.get((row_number, column))
                if formula:
                    cells.append(FormulaCell(formula))
                    continue
            cells.append(value)
        out.append((row_number, cells))
    return out


def as_text_rows(rows: list[tuple[int, list[Any]]]) -> list[tuple[int, list[str]]]:
    """Value rows rendered as text rows — byte-for-byte what :func:`sheet_rows` would have answered.

    A ``FormulaCell`` is passed through as itself rather than through ``cell_text``: it is a ``str``
    subclass holding the formula SOURCE (``=B12&" (2024)"``), and rendering it would turn a marker
    that means "this cell is a formula with no cached result" into a cell whose text is a formula, a
    string no uploader typed and no column expects.

    This exists so that a caller which took the raw values for ONE column's sake (see
    :func:`sheet_value_rows`) can still hand the other sheets to the text-shaped helpers on this
    module — :func:`read_details` in particular, whose ``dict[str, str]`` contract a value row would
    quietly violate by returning an ``int`` for a year typed as a number.
    """
    return [
        (n, [c if isinstance(c, FormulaCell) else cell_text(c) for c in cells]) for n, cells in rows
    ]


def formula_map(
    data: bytes, *, max_sheets: int, max_rows: int
) -> dict[str, dict[tuple[int, int], str]]:
    """Every cell in the workbook that holds a formula, per sheet, from a non-evaluating load.

    A second parse of the same bytes. It buys the difference between "row 12 is blank" and "row 12
    holds a formula Excel has never calculated" — see :class:`FormulaCell`. Cheap in the normal case,
    where the answer is an empty dict for every sheet.
    """
    found: dict[str, dict[tuple[int, int], str]] = {}
    try:
        wb = load_workbook(BytesIO(data), data_only=False, read_only=True)
    except (InvalidFileException, OSError, ValueError, KeyError, TypeError):
        # The evaluating load has already succeeded, so this one is expected to as well. If it does
        # not, the caller still gets a fully parsed table and merely loses the ability to say "that
        # cell is a formula" — a diagnostic nicety must never be the thing that fails an upload the
        # uploader's file was fine for.
        return found
    try:
        for ws in wb.worksheets[:max_sheets]:
            per_sheet: dict[tuple[int, int], str] = {}
            for row_number, row in enumerate(ws.iter_rows(values_only=True), start=1):
                if row_number > max_rows:
                    break
                for column, value in enumerate(row, start=1):
                    if isinstance(value, str) and value.startswith("="):
                        per_sheet[(row_number, column)] = value
            found[ws.title] = per_sheet
    finally:
        wb.close()
    return found


# --- Opening the file ---------------------------------------------------------------------------

# What the first few bytes of a file say it really is. Checked instead of the extension because the
# extension is the thing most likely to be wrong — somebody who renamed "report.xls" to
# "report.xlsx" to get past an upload filter has an .xls with an .xlsx name, and the message has to
# describe the file rather than the label somebody put on it.
ZIP_MAGIC = b"PK\x03\x04"
OLE2_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"  # .xls (BIFF8), and also an ENCRYPTED .xlsx


def unreadable_file_message(data: bytes, *, pro_forma: str = "the .xlsx pro-forma") -> str:
    """The sentence the uploader is shown when the whole file cannot be opened.

    ``pro_forma`` names the artefact they should have filled in, because two different doors now
    share this function and "download the pro-forma" has to mean the right one.
    """
    head = data[:8]
    if head.startswith(OLE2_MAGIC):
        # Both cases land here and the remedy differs, so both are named. Re-saving fixes the first
        # and removing the password fixes the second; neither is guessable from "invalid file".
        return (
            "That file is in the older .xls format, or it is an .xlsx with a password on it. Open "
            "it in Excel, remove any password, then use File > Save As and choose "
            "'Excel Workbook (.xlsx)'."
        )
    if not head.startswith(ZIP_MAGIC):
        return (
            "That is not an Excel workbook — a .csv, a .numbers file or a PDF will not do. Fill in "
            f"{pro_forma}, or use File > Save As and choose 'Excel Workbook (.xlsx)'."
        )
    return (
        "The workbook could not be opened — it may be password-protected or the upload may have "
        "been cut short. Open it in Excel, use File > Save As to save a fresh copy, and upload that."
    )


def load_sheets(
    data: bytes, *, max_sheets: int, max_rows: int, pro_forma: str = "the .xlsx pro-forma"
) -> dict[str, list[tuple[int, list[str]]]]:
    """Every sheet of the workbook as rows of text, formula cells marked.

    BOTH PASSES, IN THIS ORDER, AND THE ORDER MATTERS. The evaluating load runs first because it is
    the one whose failure means the file is unusable; the non-evaluating load runs only after that
    has succeeded, which is what makes swallowing its failure safe.

    Raises :class:`XlsxTableError` carrying the magic-byte sentence when the file cannot be opened.
    """
    try:
        wb = load_workbook(BytesIO(data), data_only=True, read_only=True)
    except (InvalidFileException, BadZipFile, OSError, ValueError, KeyError, TypeError) as exc:
        # Which of those it was tells the uploader nothing, so the message is chosen from the FILE'S
        # OWN MAGIC BYTES instead of from the exception class or the extension.
        raise XlsxTableError(unreadable_file_message(data, pro_forma=pro_forma)) from exc

    try:
        formulas = formula_map(data, max_sheets=max_sheets, max_rows=max_rows)
        sheets: dict[str, list[tuple[int, list[str]]]] = {}
        for ws in wb.worksheets[:max_sheets]:
            sheets[ws.title] = sheet_rows(ws, formulas.get(ws.title, {}), max_rows=max_rows)
    finally:
        wb.close()
    return sheets


def load_value_sheets(
    data: bytes, *, max_sheets: int, max_rows: int, pro_forma: str = "the .xlsx pro-forma"
) -> dict[str, list[tuple[int, list[Any]]]]:
    """:func:`load_sheets`, but keeping the RAW cell values — see :func:`sheet_value_rows` for why.

    THE SAME TWO PASSES IN THE SAME ORDER, and deliberately not a flag on ``load_sheets``: the two
    differ only in which row reader they call, and a boolean parameter whose value changes the
    element type of the returned lists is a signature that type checkers and readers both have to
    take on trust. Two functions, two return types, one shared body of argument in the docstrings
    above.
    """
    try:
        wb = load_workbook(BytesIO(data), data_only=True, read_only=True)
    except (InvalidFileException, BadZipFile, OSError, ValueError, KeyError, TypeError) as exc:
        raise XlsxTableError(unreadable_file_message(data, pro_forma=pro_forma)) from exc

    try:
        formulas = formula_map(data, max_sheets=max_sheets, max_rows=max_rows)
        sheets: dict[str, list[tuple[int, list[Any]]]] = {}
        for ws in wb.worksheets[:max_sheets]:
            sheets[ws.title] = sheet_value_rows(ws, formulas.get(ws.title, {}), max_rows=max_rows)
    finally:
        wb.close()
    return sheets


# --- Picking the working sheet ------------------------------------------------------------------


def pick_sheet(
    sheets: dict[str, list[tuple[int, list[str]]]],
    problems: list[ParseProblem],
    *,
    aliases: dict[str, tuple[str, ...]],
    required_role: str,
    prefix_roles: tuple[tuple[str, tuple[str, ...]], ...] = (),
    preferred_names: tuple[str, ...],
    preferred_display: str,
    looks_like: str,
    most_noun: str,
    max_scan_rows: int = 15,
) -> tuple[str, int, dict[int, tuple[str, str | None]]] | None:
    """Which sheet holds the table. Name first, then whichever candidate has the most rows under its
    header — an uploader whose working sheet is called "Sheet1" or "Final v3" is still read.

    THE SHEET THAT WAS *NOT* USED IS NAMED IN A ``warning``, both ways round. A file with two
    plausible sheets is a file where the uploader will one day be looking at the wrong one, and
    "nothing imported" with no explanation of which tab was read is the report this format's users
    complain about most.
    """
    candidates: list[tuple[str, int, dict[int, tuple[str, str | None]]]] = []
    for name, rows in sheets.items():
        found = scan_header(
            rows,
            aliases=aliases,
            required_role=required_role,
            prefix_roles=prefix_roles,
            max_scan_rows=max_scan_rows,
        )
        if found is not None:
            candidates.append((name, found[0], found[1]))
    if not candidates:
        return None
    wanted = {norm_header(n) for n in preferred_names}
    for candidate in candidates:
        name = candidate[0]
        if norm_header(name) in wanted:
            if len(candidates) > 1:
                others = [c[0] for c in candidates if c[0] != name]
                problems.append(
                    ParseProblem(
                        sheet=name,
                        row=None,
                        severity="warning",
                        reason=(
                            f"More than one sheet looked like {looks_like}; this one was used. "
                            f"Ignored: {', '.join(others)}."
                        ),
                    )
                )
            return candidate
    best = max(candidates, key=lambda c: len(sheets[c[0]]) - c[1])
    if len(candidates) > 1:
        others = [c[0] for c in candidates if c[0] != best[0]]
        problems.append(
            ParseProblem(
                sheet=best[0],
                row=None,
                severity="warning",
                reason=(
                    f"No sheet was named '{preferred_display}', so the one with the most "
                    f"{most_noun} was used. Ignored: {', '.join(others)}."
                ),
            )
        )
    return best


def read_details(
    rows: list[tuple[int, list[str]]], *, keys: dict[str, tuple[str, ...]], max_rows: int = 60
) -> dict[str, str]:
    """Label/value pairs off a Details sheet.

    Tolerates the value being in any column to the right, which is what happens the moment somebody
    widens column A or inserts one.

    ⚠ **THE LABELS A WRITER PUTS ON A DETAILS SHEET MUST NOT COLLIDE WITH ANOTHER READER'S
    ``keys``.** This function matches column A against a family of aliases, so a row labelled
    "Notes" or "Summary" on one feature's Details sheet is read back as another feature's
    *description* if the two share a workbook shape. Each caller owns its own key table and each
    caller's tests pin that the round trip does not cross.
    """
    found: dict[str, str] = {}
    for _row_number, values in rows[:max_rows]:
        if not values:
            continue
        key = norm_header(values[0])
        for name, names in keys.items():
            if key in names and name not in found:
                value = next((v for v in values[1:] if v and not isinstance(v, FormulaCell)), "")
                if value:
                    found[name] = value
    return found
