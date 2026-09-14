"""The .xlsx pro-forma a designer builds their own questionnaire in, and the parser that reads it
back.

The loop this serves, in the owner's words: *designers want to make their OWN questionnaires*. They
download a pro-forma, type their questions into it in Excel, upload it, and then record answers
against it in the platform. The spreadsheet they upload MAY ALREADY CONTAIN ANSWERS — a designer who
ran the interview on paper and typed it up — and it may equally be blank. Both have to work, and
they have to produce the same shape of data, so an answer that arrived on the spreadsheet is stored
in exactly the same table as one typed into the app.

Two directions, and they are inverses of each other:

``build_pro_forma``           -> a blank workbook: the working sheet, a Details sheet for the title,
                                 and a worked example on the instructions sheet.
``build_questionnaire_workbook`` -> an EXISTING questionnaire as the same workbook, carrying question
                                 IDs and one Answer/Notes column pair per recorded sitting.
``build_question_set_workbook`` -> the same questionnaire's QUESTIONS AND NOTHING ELSE — no answers,
                                 no respondents, no question IDs. The artefact a designer SENDS to
                                 another designer; see the block comment above it.
``parse_questionnaire_workbook`` -> any of the above, or something a designer built from scratch,
                                 read back into sections + questions + answers.

THERE ARE THEREFORE TWO DOWNLOADS OF ONE QUESTIONNAIRE AND THEY ARE NOT INTERCHANGEABLE. The full
workbook is a LOSSLESS copy of somebody's fieldwork and belongs to the designer who collected it; the
question set is the INSTRUMENT, which is the part that is meant to travel. Conflating them is how a
sharing feature becomes a data leak, so they are two functions with two names rather than one
function with a flag — a flag defaults, and the wrong default here hands over a stranger's
respondents.

WHAT "FORGIVING" MEANS HERE, precisely, because it is the whole difference between a feature a
designer uses and one they abandon after the first upload:

* **Column order is not fixed.** Columns are located by reading the header row, not by position, so
  a designer who dragged "Answer" to the front loses nothing.
* **Header spelling is not fixed.** Each column accepts a family of names (see ``_COLUMN_ALIASES``);
  matching ignores case, punctuation, non-breaking spaces and repeated whitespace, because "Section
  Code", "section_code" and "SECTION  CODE " are the same column to everyone except a computer.
* **The header does not have to be on row 1.** Designers put a title across the top. The header row
  is *found* by scanning for one that names a question column.
* **The sheet does not have to be the first one.** A sheet named like the working sheet wins;
  failing that, whichever sheet yields the most questions does.
* **Section codes are optional.** A section is identified by its title if no code was typed, and a
  code is derived from the title so it stays stable when a designer inserts a section above it.
* **A section header can be written once or repeated.** Blank section cells inherit the last section
  seen, which is how a person lays a table out; repeating it on every row also works.

AND THE RULE THAT OUTRANKS ALL OF THAT: a row this parser cannot read is REPORTED, never dropped.
Every skipped or assumed-about row comes back in ``ParsedQuestionnaire.problems`` with its sheet, its
Excel row number and a sentence saying what happened. A silent drop means a designer uploads forty
questions, sees thirty-eight, and has no way to find out which two went missing or why — and the
most likely reason for a genuinely unreadable cell is the one case openpyxl cannot help with: a
FORMULA whose cached value was never written (see ``xlsx_table.FormulaCell``).

WRITING SIDE: every value goes into a cell through :func:`app.services.xlsx_report._put`, imported
rather than restated. That module's docstring records three separate ways ordinary field text made
Excel open a download with "We found a problem with some content" — a lone surrogate from a phone
that halved an emoji, a note beginning "=" that openpyxl stored as a formula, and a sheet name
carrying an apostrophe — and ``tests/test_xlsx_report.py`` is what keeps those fixed. A private copy
of that guard here would be a fourth renderer of the same file format that drifts the first time one
of them is corrected; the repo already shares ``csv_response`` between two routers for exactly this
reason. The import is deliberate, and if it ever breaks it breaks loudly at import time.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from io import BytesIO
from typing import Any

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.worksheet.worksheet import Worksheet

# See the module docstring: shared, not restated. `_put` is the single door every value in an .xlsx
# this repo produces goes through, and it is what stops Excel offering to repair the download.
from app.services.xlsx_report import XLSX_MIME, _put, _sanitise

# ── THE READER CORE, EXTRACTED RATHER THAN COPIED (2026-09-13) ──────────────────────────────────
#
# Everything below used to be defined in this file, and every line of it is still the same line —
# it moved to ``services/xlsx_table`` unchanged when a SECOND uploader of a SECOND kind of table
# (the artisan roster) arrived. The alternative was a private copy of the header normaliser, the
# two-pass formula map and the magic-byte branch, which is how two readers of one file format come
# to disagree the first time one of them is corrected: the next fix to ``cell_text`` would land in
# exactly one of them and nothing would say which. That is the same argument this module's header
# already makes about importing ``xlsx_report._put`` rather than restating it, and the one
# ``questionnaire.py:11-15`` makes about sharing ``csv_response`` with ``export.py``.
#
# THE FOUR STILL-PRIVATE NAMES ARE KEPT AS ALIASES ON PURPOSE. Every reference in the body of this
# file goes on reading exactly as it did; an extraction that also renamed a hundred call sites would
# have made the diff unreviewable and buried any real change inside it. The names this file no
# longer uses at all are simply not imported — an alias nothing reads is a second place for a reader
# to look up a definition that has moved.
from app.services.xlsx_table import (
    FormulaCell as _FormulaCell,
    ParseProblem,
    XlsxTableError,
    clip as _clip,
    load_sheets,
    norm_header as _norm_header,
    pick_sheet,
    read_details,
    truthy as _truthy,
)

__all__ = [
    "PRO_FORMA_FILENAME",
    "QUESTION_SET_CONTENTS",
    "XLSX_MIME",
    "ParseProblem",
    "ParsedQuestion",
    "ParsedQuestionnaire",
    "ParsedSection",
    "QuestionnaireXlsxError",
    "build_pro_forma",
    "build_question_set_workbook",
    "build_questionnaire_workbook",
    "derive_section_code",
    "download_filename",
    "parse_questionnaire_workbook",
    "question_set_filename",
]

PRO_FORMA_FILENAME = "questionnaire-pro-forma.xlsx"

# Sheet names. The parser prefers the working sheet by name and the writer emits these, but neither
# depends on them: a designer who renamed the tab is still read (see `xlsx_table.pick_sheet`).
SHEET_QUESTIONS = "Questionnaire"
SHEET_DETAILS = "Details"
SHEET_HELP = "How to fill this in"

_BRAND = "5B21B6"  # the same purple the relational report's Overview uses
_WHITE = "FFFFFFFF"

# Column widths, in characters. The question column is where the typing happens, so it gets room.
_WIDTHS = {
    "sectionCode": 14,
    "sectionTitle": 30,
    "questionId": 26,
    "prompt": 62,
    "help": 34,
    "required": 11,
    "answer": 46,
    "notes": 34,
}

# Ceilings. A questionnaire is a research instrument, not a data dump: these exist so a malformed or
# hostile workbook cannot turn one upload into an unbounded read. They are far above any real form —
# the global artisan questionnaire, the largest instrument in the repository, has a few hundred
# questions across seventeen sections.
MAX_QUESTIONS = 2000
MAX_SECTIONS = 200
MAX_ROWS_SCANNED = 20000
MAX_SHEETS_SCANNED = 12
# How far down a sheet to look for the header row before concluding there isn't one. Generous
# enough for a title block and a logo, small enough that a sheet of prose is not mistaken for a form.
MAX_HEADER_SCAN_ROWS = 15

MAX_PROMPT_CHARS = 2000
MAX_TITLE_CHARS = 220
MAX_CODE_CHARS = 24
MAX_ANSWER_CHARS = 8000

_DEFAULT_SECTION_TITLE = "General"
_UPLOAD_ENTRY_LABEL = "Answer"


class QuestionnaireXlsxError(ValueError):
    """The upload is not a workbook this can read at all.

    Distinct from a *problem*, which is one row of an otherwise-good file. This is the whole file:
    not a zip, not an .xlsx, password-protected, or carrying no recognisable question column
    anywhere. The message is written to be shown to the designer as-is.
    """


_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "sectionCode": (
        "section code",
        "sectioncode",
        "section ref",
        "section id",
        "code",
        "s no section",
    ),
    "sectionTitle": (
        "section title",
        "section name",
        "sectiontitle",
        "section heading",
        "section",
        "part",
        "theme",
    ),
    "questionId": (
        "question id",
        "questionid",
        "id",
        "q id",
        "qid",
        "ref",
        "reference",
        "question ref",
    ),
    "prompt": ("question", "questions", "prompt", "question text", "the question", "item", "q"),
    "help": (
        "help",
        "help text",
        "helptext",
        "guidance",
        "hint",
        "instruction",
        "instructions",
        "description",
    ),
    "required": ("required", "mandatory", "is required", "compulsory", "must answer"),
}
# Answer and note columns are matched by PREFIX, not exact name, because an exported questionnaire
# emits one pair per recorded sitting: "Answer — Ramesh", "Notes — Ramesh". The text after the dash
# names the sitting and is what pairs the two columns back up.
#
# HANDED TO ``xlsx_table.scan_header`` AS ``prefix_roles``, which is also what tells that function
# these two roles may legitimately REPEAT while every alias family above may not. The pairing of
# "the role may occur many times" with "the role is matched by prefix" is not a coincidence to be
# tidied into two parameters: a prefix role carries a LABEL, and a label is what makes the second
# occurrence a different column rather than a duplicate of the first.
_ANSWER_PREFIXES = ("answer", "response", "reply")
_NOTES_PREFIXES = ("notes", "note", "remarks", "remark", "comment", "comments", "observation")
_PREFIX_ROLES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("answer", _ANSWER_PREFIXES),
    ("notes", _NOTES_PREFIXES),
)

#: The Details sheet's label vocabulary. **PRIVATE TO THIS FEATURE AND IT MUST STAY THAT WAY.**
#:
#: ``_details_sheet``'s ``extra`` rows must not collide with these, because this table is what reads
#: the sheet back: a row labelled "Notes" or "Summary" added for a reader's benefit would be read as
#: the questionnaire's DESCRIPTION and would silently overwrite it on the next import.
#: ``tests/test_questionnaire_interchange.py`` pins that the question set's extra rows survive the
#: round trip without doing this, and ``artisan_xlsx`` deliberately uses a disjoint key set for its
#: own Details sheet for the same reason.
_DETAIL_KEYS = {
    "title": ("questionnaire title", "title", "name", "questionnaire name", "form title"),
    "description": ("description", "purpose", "about", "notes", "summary"),
    "questionnaireId": ("questionnaire id", "id", "questionnaire ref", "form id"),
}


@dataclass
class ParsedQuestion:
    prompt: str
    row: int
    questionId: str | None = None
    helpText: str | None = None
    isRequired: bool = False
    # Sitting label -> answer text / note, for answers that arrived already filled in on the sheet.
    answers: dict[str, str] = field(default_factory=dict)
    answerNotes: dict[str, str] = field(default_factory=dict)


@dataclass
class ParsedSection:
    code: str
    title: str
    questions: list[ParsedQuestion] = field(default_factory=list)


@dataclass
class ParsedQuestionnaire:
    sections: list[ParsedSection] = field(default_factory=list)
    problems: list[ParseProblem] = field(default_factory=list)
    title: str | None = None
    description: str | None = None
    # Carried on the Details sheet of an exported questionnaire. Its presence is how a re-upload
    # knows it is an EDIT of a known form rather than a new one — though the endpoint takes the id
    # from the URL and only uses this to catch a designer uploading the wrong file.
    questionnaireId: str | None = None
    sheet: str | None = None
    # The sittings named by the answer columns, in the order they appeared.
    entryLabels: list[str] = field(default_factory=list)

    @property
    def questionCount(self) -> int:
        return sum(len(section.questions) for section in self.sections)

    @property
    def hasAnswers(self) -> bool:
        return any(q.answers for section in self.sections for q in section.questions)

    def payload(self) -> dict[str, Any]:
        return {
            "title": self.title,
            "description": self.description,
            "sheet": self.sheet,
            "sectionCount": len(self.sections),
            "questionCount": self.questionCount,
            "entryLabels": self.entryLabels,
            "problems": [p.payload() for p in self.problems],
        }


def derive_section_code(title: str, taken: set[str]) -> str:
    """A stable, unique code for a section the designer titled but did not code.

    Derived from the TITLE rather than from the position. Positional codes ("S1", "S2") look tidier
    and are wrong: inserting a section at the top renumbers every section below it, so a re-upload
    matches nothing and the whole form churns. A title-derived code survives insertion.
    """
    base = re.sub(r"[^A-Za-z0-9]+", "_", unicodedata.normalize("NFKD", title)).strip("_").upper()
    base = base[:MAX_CODE_CHARS].strip("_") or "SECTION"
    candidate = base
    n = 2
    while candidate.lower() in taken:
        suffix = f"_{n}"
        candidate = base[: MAX_CODE_CHARS - len(suffix)] + suffix
        n += 1
    taken.add(candidate.lower())
    return candidate


def parse_questionnaire_workbook(
    data: bytes, *, filename: str | None = None
) -> ParsedQuestionnaire:
    """Read an uploaded workbook into sections + questions (+ any answers it already carried).

    Raises :class:`QuestionnaireXlsxError` only when the FILE is unusable. Anything wrong with a row
    of an otherwise-readable file comes back in ``result.problems`` and the rest of the file is
    still imported, because a designer with thirty-eight of forty questions and a list of the two
    that need fixing is far better off than one with an error page.
    """
    if not data:
        raise QuestionnaireXlsxError("The upload was empty. Attach the filled-in .xlsx pro-forma.")
    try:
        # BOTH LOAD PASSES, AND THE MAGIC-BYTE BRANCH, LIVE IN ``xlsx_table.load_sheets`` NOW. The
        # behaviour is unchanged and the reasoning is stated there: which exception openpyxl raised
        # tells the designer nothing, so the message is chosen from the FILE'S OWN magic bytes.
        # ``XlsxTableError`` is re-raised as this module's own error so that every caller's
        # ``except QuestionnaireXlsxError`` goes on meaning exactly what it meant.
        sheets = load_sheets(
            data,
            max_sheets=MAX_SHEETS_SCANNED,
            max_rows=MAX_ROWS_SCANNED,
            pro_forma="the .xlsx pro-forma",
        )
    except XlsxTableError as exc:
        raise QuestionnaireXlsxError(str(exc)) from exc

    if not sheets:
        raise QuestionnaireXlsxError("The workbook has no sheets in it.")

    result = ParsedQuestionnaire()

    # Details, if there is a sheet that looks like one. Missing is normal — a designer who built the
    # form from scratch has no Details sheet, and the title then comes from the filename.
    for name, rows in sheets.items():
        if _norm_header(name) in (
            "details",
            "about",
            "questionnaire details",
            "info",
            "information",
        ):
            details = read_details(rows, keys=_DETAIL_KEYS)
            result.title = (
                _clip(details["title"], MAX_TITLE_CHARS) if details.get("title") else None
            )
            result.description = details.get("description") or None
            result.questionnaireId = details.get("questionnaireId") or None
            break

    # THE VOCABULARY IS THIS MODULE'S AND THE MECHANISM IS ``xlsx_table``'S, which is the whole
    # shape of the extraction: the aliases, the required role and the four sentences a reader sees
    # are the questionnaire's, and the sheet-picking rule — name first, then whichever candidate has
    # the most rows under its header, naming the sheets it did NOT use — is shared.
    picked = pick_sheet(
        sheets,
        result.problems,
        aliases=_COLUMN_ALIASES,
        required_role="prompt",
        prefix_roles=_PREFIX_ROLES,
        preferred_names=(SHEET_QUESTIONS, "questions", "form", "sheet1"),
        preferred_display="Questionnaire",
        looks_like="a questionnaire",
        most_noun="questions",
        max_scan_rows=MAX_HEADER_SCAN_ROWS,
    )
    if picked is None:
        raise QuestionnaireXlsxError(
            "No sheet in that workbook has a 'Question' column, so there was nothing to import. "
            "Download the pro-forma and type your questions under its headings, or add a header row "
            "with a column called 'Question'."
        )
    sheet_name, header_row, columns = picked
    result.sheet = sheet_name
    _read_questions(sheets[sheet_name], sheet_name, header_row, columns, result)

    if not result.title and filename:
        # Last resort, and a good one: designers name the file after the questionnaire.
        stem = re.sub(r"\.(xlsx|xlsm|xltx)$", "", filename, flags=re.IGNORECASE).strip()
        stem = re.sub(r"[_-]+", " ", stem).strip()
        if stem:
            result.title = _clip(stem, MAX_TITLE_CHARS)

    if not result.questionCount:
        raise QuestionnaireXlsxError(
            f"The '{sheet_name}' sheet has a Question column but no questions under it. Type at "
            "least one question and upload again."
        )
    return result


def _read_questions(
    rows: list[tuple[int, list[str]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    result: ParsedQuestionnaire,
) -> None:
    """Walk the data rows under the header, building sections as they are met."""
    sections: dict[str, ParsedSection] = {}
    order: list[str] = []
    codes_taken: set[str] = set()
    current: ParsedSection | None = None
    seen_ids: set[str] = set()
    entry_labels: list[str] = []
    # Counted as we go rather than read off `result`, whose sections are only assembled at the end —
    # reading the ceiling off an always-empty list is how this cap silently never fires.
    kept = 0

    def get(values: list[str], role: str, label: str | None = None) -> str:
        for index, (this_role, this_label) in columns.items():
            if this_role == role and this_label == label and index < len(values):
                value = values[index]
                return "" if isinstance(value, _FormulaCell) else value
        return ""

    def formula_in(values: list[str]) -> str | None:
        for index in sorted(columns):
            if index < len(values) and isinstance(values[index], _FormulaCell):
                return str(values[index])
        return None

    def section_for(code: str, title: str, row_number: int) -> ParsedSection:
        nonlocal current
        key = (code or derive_section_code(title, set())).lower()
        existing = sections.get(key)
        if existing is not None:
            if title and existing.title != title:
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="warning",
                        reason=(
                            f"Section '{existing.code}' is titled '{existing.title}' earlier in the "
                            f"sheet; the first title was kept."
                        ),
                        value=title,
                    )
                )
            current = existing
            return existing
        if len(sections) >= MAX_SECTIONS:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="error",
                    reason=f"More than {MAX_SECTIONS} sections; the rest of the sheet was ignored.",
                )
            )
            raise _StopReading
        final_code = code or derive_section_code(title, codes_taken)
        if code:
            codes_taken.add(code.lower())
        made = ParsedSection(code=final_code, title=title or final_code)
        sections[key] = made
        order.append(key)
        current = made
        return made

    for row_number, values in rows:
        if row_number <= header_row:
            continue
        prompt = get(values, "prompt")
        code = _clip(get(values, "sectionCode"), MAX_CODE_CHARS)
        title = _clip(get(values, "sectionTitle"), MAX_TITLE_CHARS)

        if not prompt and not code and not title:
            formula = formula_in(values)
            if formula:
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="error",
                        reason=(
                            "This row holds a formula whose result is not saved in the file, so "
                            "there was nothing to read. Open the workbook in Excel and save it "
                            "again, or replace the formula with the text it produces."
                        ),
                        value=_clip(formula, 120),
                    )
                )
            continue  # an ordinary blank row: layout, not data

        try:
            if not prompt:
                # A section header row: names a section and asks nothing. Common and correct.
                section_for(code, title, row_number)
                continue
            if code or title:
                section_for(code, title, row_number)
            if current is None:
                current = section_for("", _DEFAULT_SECTION_TITLE, row_number)
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="warning",
                        reason=(
                            "This question came before any section was named, so it was filed under "
                            f"'{_DEFAULT_SECTION_TITLE}'. Add a Section Title to move it."
                        ),
                        value=_clip(prompt, 120),
                    )
                )
        except _StopReading:
            break

        if kept >= MAX_QUESTIONS:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="error",
                    reason=f"More than {MAX_QUESTIONS} questions; the rest of the sheet was ignored.",
                )
            )
            break

        if len(prompt) > MAX_PROMPT_CHARS:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="warning",
                    reason=f"The question was longer than {MAX_PROMPT_CHARS} characters and was shortened.",
                    value=_clip(prompt, 120),
                )
            )
            prompt = _clip(prompt, MAX_PROMPT_CHARS)

        question_id = get(values, "questionId") or None
        if question_id and question_id in seen_ids:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="warning",
                    reason=(
                        f"Question ID '{question_id}' appears more than once. This row was imported "
                        "as a NEW question rather than overwriting the earlier one."
                    ),
                    value=_clip(prompt, 120),
                )
            )
            question_id = None
        if question_id:
            seen_ids.add(question_id)

        required_raw = get(values, "required")
        required = _truthy(required_raw)
        if required is None:
            result.problems.append(
                ParseProblem(
                    sheet=sheet,
                    row=row_number,
                    severity="warning",
                    reason=(
                        f"'{_clip(required_raw, 40)}' is not a yes/no value, so the question was "
                        "imported as optional. Use Yes or No."
                    ),
                    value=_clip(prompt, 120),
                )
            )
            required = False

        question = ParsedQuestion(
            prompt=prompt,
            row=row_number,
            questionId=question_id,
            helpText=get(values, "help") or None,
            isRequired=required,
        )
        for index, (role, label) in sorted(columns.items()):
            if role not in ("answer", "notes") or index >= len(values):
                continue
            raw = values[index]
            if isinstance(raw, _FormulaCell):
                result.problems.append(
                    ParseProblem(
                        sheet=sheet,
                        row=row_number,
                        severity="warning",
                        reason=(
                            "The answer on this row is a formula whose result is not saved in the "
                            "file, so the question was imported without it."
                        ),
                        value=_clip(prompt, 120),
                    )
                )
                continue
            if not raw:
                continue
            name = label or _UPLOAD_ENTRY_LABEL
            if name not in entry_labels:
                entry_labels.append(name)
            target = question.answers if role == "answer" else question.answerNotes
            target[name] = _clip(raw, MAX_ANSWER_CHARS)
        current.questions.append(question)
        kept += 1

    # Every section is kept, INCLUDING one that ended up with no questions: a designer who typed
    # their section headings first and their questions second would otherwise upload the file and
    # find the headings gone.
    result.sections = [sections[key] for key in order]
    result.entryLabels = entry_labels


class _StopReading(Exception):
    """Internal: a ceiling was hit and the rest of the sheet is deliberately not read."""


# --- Writing ------------------------------------------------------------------------------------


def _header(ws: Worksheet, row: int, labels: list[tuple[str, str]]) -> None:
    fill = PatternFill(fill_type="solid", start_color=f"FF{_BRAND}")
    for index, (key, label) in enumerate(labels, start=1):
        cell = _put(ws, row, index, label)
        cell.font = Font(bold=True, color=_WHITE)
        cell.fill = fill
        cell.alignment = Alignment(horizontal="left", vertical="center", wrap_text=True)
        letter = ws.cell(row=row, column=index).column_letter
        ws.column_dimensions[letter].width = _WIDTHS.get(key, 24)
    ws.row_dimensions[row].height = 28
    ws.freeze_panes = f"A{row + 1}"


_DETAILS_NOTE = (
    "Type your questionnaire's name and purpose above. Leave 'Questionnaire ID' exactly as "
    "you found it — it is how the app recognises this file as an edit of a questionnaire you "
    "have already uploaded rather than a brand new one."
)


def _details_sheet(
    ws: Worksheet,
    *,
    title: str,
    description: str,
    questionnaire_id: str,
    version: int | None,
    extra: list[tuple[str, str]] | None = None,
    note: str = _DETAILS_NOTE,
) -> None:
    """The Details sheet: four label/value rows, optionally some more, then one italic note.

    ``extra`` LABELS MUST NOT COLLIDE WITH ``_DETAIL_KEYS`` above, and that is a real constraint
    rather than a stylistic one — this sheet is parsed back by ``xlsx_table.read_details`` against
    :data:`_DETAIL_KEYS`, which matches
    the label in column A against a family of aliases. A row labelled "Notes" or "Summary" would be
    read back as the questionnaire's DESCRIPTION, so a provenance line added here for the reader's
    benefit would silently overwrite the description of the questionnaire the file is imported into.
    ``tests/test_questionnaire_interchange.py`` pins that the question set's own extra rows survive
    the round trip without doing this.

    The note row is placed BELOW whatever rows exist rather than at a fixed row 8, so adding a pair
    cannot land the paragraph on top of it. With the four base pairs it still lands on row 8, exactly
    where it always did.
    """
    ws.sheet_properties.tabColor = f"FF{_BRAND}"
    heading = _put(ws, 1, 1, "Questionnaire details")
    heading.font = Font(bold=True, size=14, color=f"FF{_BRAND}")
    pairs = [
        ("Questionnaire title", title),
        ("Description", description),
        ("Questionnaire ID", questionnaire_id),
        ("Version", str(version) if version is not None else ""),
        *(extra or []),
    ]
    for offset, (label, value) in enumerate(pairs):
        row = 3 + offset
        cell = _put(ws, row, 1, label)
        cell.font = Font(bold=True)
        value_cell = _put(ws, row, 2, value)
        value_cell.alignment = Alignment(wrap_text=True, vertical="top")
    note_row = 3 + len(pairs) + 1
    note_cell = _put(ws, note_row, 1, note)
    note_cell.alignment = Alignment(wrap_text=True, vertical="top")
    note_cell.font = Font(italic=True, color="FF6B7280")
    ws.merge_cells(start_row=note_row, start_column=1, end_row=note_row + 2, end_column=4)
    ws.column_dimensions["A"].width = 24
    ws.column_dimensions["B"].width = 62


_HELP_LINES = [
    ("h", "How to fill this in"),
    ("p", "Type your questions on the 'Questionnaire' sheet, one per row, then upload the file."),
    (
        "b",
        "Sections — put the section's name in 'Section Title'. Fill it in on the first question of the section and leave it blank on the rest, or repeat it on every row; both work. Leave 'Section Code' blank and the app will create one for you.",
    ),
    (
        "b",
        "Questions — 'Question' is the only column you must fill in. Every other column is optional.",
    ),
    ("b", "Required — type Yes or No. Anything else is read as No and reported back to you."),
    (
        "b",
        "Answers — you can leave the 'Answer' column empty and record answers in the app, or type answers you already collected on paper straight into it. Both work, and you can do both: answers typed here become the first set of answers, and you can add more in the app afterwards.",
    ),
    (
        "b",
        "Question ID — leave it blank for a new question. On a questionnaire you downloaded back out of the app it is already filled in; DO NOT EDIT OR DELETE IT. It is how the app knows that row is the same question you have already collected answers against.",
    ),
    (
        "b",
        "You can add columns of your own, move columns around, and put a title above the headings. The app finds the columns by their headings, not by where they are.",
    ),
    ("h", "Editing a questionnaire people have already answered"),
    (
        "p",
        "Once an answer has been recorded against a question, that question stops being editable — an answer only means anything next to the words it was given under.",
    ),
    (
        "b",
        "Change the wording of an answered question and the app keeps the old question and its answers, and adds your new wording as a new question underneath. Nothing is lost and no recorded answer changes meaning.",
    ),
    (
        "b",
        "Delete a row for an answered question and the app retires it instead: it stops being asked, and its answers stay in the record.",
    ),
    ("b", "Questions nobody has answered yet can be reworded, reordered and deleted freely."),
    ("h", "Sending your questions to another designer"),
    (
        "p",
        "Two different files come out of a questionnaire, and only one of them is meant to be passed on.",
    ),
    (
        "b",
        "QUESTION SET — the questions, their order, their help text and their Required flags, and nothing else: no answers, no respondents' names, no sittings. This is the one to send. Whoever receives it uploads it and gets their own empty questionnaire with your questions in it, to run their own fieldwork against.",
    ),
    (
        "b",
        "FULL WORKBOOK — the same questions PLUS every sitting recorded against them: each respondent's name, their notes and every answer they gave. That is your fieldwork rather than your instrument, and only you, a designer working on the same design workshop, and an admin can download it.",
    ),
    (
        "b",
        "A workbook that came out of the app and still has answers in it imports its QUESTIONS ONLY. Those answers are already recorded in the platform under the names of the people who recorded them, and copying them into a second questionnaire under your name would duplicate the fieldwork and misattribute it. Answers you typed into a blank pro-forma yourself are imported as normal — that file has no Question IDs in it, which is how the app tells the two apart.",
    ),
    ("h", "An example"),
]
_HELP_EXAMPLE = [
    ["Section Code", "Section Title", "Question ID", "Question", "Required", "Answer"],
    ["", "About the craft", "", "How long have you practised this craft?", "Yes", "22 years"],
    ["", "", "", "Who taught you?", "No", ""],
    ["", "Materials", "", "Where do you buy your yarn?", "Yes", ""],
]


def _help_sheet(ws: Worksheet) -> None:
    ws.sheet_properties.tabColor = "FF9CA3AF"
    row = 1
    for kind, text in _HELP_LINES:
        cell = _put(ws, row, 1, text if kind != "b" else f"•  {text}")
        if kind == "h":
            cell.font = Font(bold=True, size=13, color=f"FF{_BRAND}")
            row += 1
        elif kind == "p":
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.merge_cells(start_row=row, start_column=1, end_row=row + 1, end_column=6)
            row += 2
        else:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
            ws.merge_cells(start_row=row, start_column=1, end_row=row + 2, end_column=6)
            row += 3
    row += 1
    for offset, line in enumerate(_HELP_EXAMPLE):
        for column, value in enumerate(line, start=1):
            cell = _put(ws, row + offset, column, value)
            if offset == 0:
                cell.font = Font(bold=True, color=f"FF{_BRAND}")
    ws.column_dimensions["A"].width = 26
    for letter in ("B", "C", "D", "E", "F"):
        ws.column_dimensions[letter].width = 24


def _question_columns(entry_labels: list[str]) -> list[tuple[str, str]]:
    """The working sheet's headings: the fixed ones, then one Answer/Notes pair per sitting.

    The label is repeated in both headings of a pair, which is exactly what the parser reads to pair
    them back up — so a workbook exported with three sittings re-imports as three sittings.
    """
    columns = [
        ("sectionCode", "Section Code"),
        ("sectionTitle", "Section Title"),
        ("questionId", "Question ID"),
        ("prompt", "Question"),
        ("help", "Help text"),
        ("required", "Required"),
    ]
    for label in entry_labels or [""]:
        suffix = f" — {label}" if label else ""
        columns.append(("answer", f"Answer{suffix}"))
        columns.append(("notes", f"Notes{suffix}"))
    return columns


def _write_questions_sheet(
    ws: Worksheet,
    sections: list[dict[str, Any]],
    entry_labels: list[str],
) -> None:
    ws.sheet_properties.tabColor = f"FF{_BRAND}"
    columns = _question_columns(entry_labels)
    _header(ws, 1, columns)

    grey = Font(color="FF6B7280")
    wrap = Alignment(wrap_text=True, vertical="top")
    row = 2
    for section in sections:
        first = True
        for question in section.get("questions") or []:
            values: list[Any] = [
                section.get("code") if first else "",
                section.get("title") if first else "",
                question.get("id") or "",
                question.get("prompt") or "",
                question.get("helpText") or "",
                "Yes" if question.get("isRequired") else "No",
            ]
            answers = question.get("answers") or {}
            notes = question.get("answerNotes") or {}
            for label in entry_labels or [""]:
                values.append(answers.get(label, ""))
                values.append(notes.get(label, ""))
            for index, value in enumerate(values, start=1):
                cell = _put(ws, row, index, value)
                cell.alignment = wrap
                if index == 3 and value:
                    # The identity column. Greyed because it is the one column a designer must not
                    # retype: it is what ties this row to the answers already recorded against it.
                    cell.font = grey
            first = False
            row += 1
        if not (section.get("questions") or []):
            # A section with no questions still has to survive the round trip, or a designer who
            # typed their headings first and their questions second loses the headings.
            _put(ws, row, 1, section.get("code") or "")
            _put(ws, row, 2, section.get("title") or "")
            row += 1


def build_pro_forma() -> bytes:
    """The BLANK pro-forma: headings, a Details sheet to name the questionnaire, and instructions.

    Deliberately EMPTY under the headings. An earlier shape seeded three example questions on the
    working sheet to show what one looked like, which reads well and imports badly: the designer who
    adds their questions below the examples uploads a questionnaire whose first three questions are
    "How long have you practised this craft?" and has to work out where they came from. The worked
    example lives on the instructions sheet, where it cannot be imported.
    """
    wb = Workbook()
    questions = wb.active
    questions.title = SHEET_QUESTIONS
    _write_questions_sheet(questions, [], [])
    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        title="",
        description="",
        questionnaire_id="",
        version=None,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def build_questionnaire_workbook(
    *,
    title: str,
    description: str | None,
    questionnaire_id: str,
    version: int,
    sections: list[dict[str, Any]],
    entry_labels: list[str] | None = None,
) -> bytes:
    """An EXISTING questionnaire as the same workbook — the download half of the edit loop.

    ``sections`` is ``[{code, title, questions: [{id, prompt, helpText, isRequired, answers,
    answerNotes}]}]``, where ``answers``/``answerNotes`` are ``{sitting label: text}``. Question ids
    are written into the Question ID column, which is what makes a download-edit-upload round trip
    an EDIT of these questions rather than a second copy of them.
    """
    labels = list(entry_labels or [])
    wb = Workbook()
    questions = wb.active
    questions.title = SHEET_QUESTIONS
    _write_questions_sheet(questions, sections, labels)
    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        title=_sanitise(title or ""),
        description=_sanitise(description or ""),
        questionnaire_id=questionnaire_id,
        version=version,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


QUESTION_SET_CONTENTS = "Questions only — no answers, no respondents' names, no recorded sittings."

_QUESTION_SET_NOTE = (
    "This is a QUESTION SET: one questionnaire's questions and nothing else. It carries no answers, "
    "no respondents' names and no recorded sittings, which is what makes it safe to send to another "
    "designer. 'Questionnaire ID' is blank and the Question ID column is empty ON PURPOSE — "
    "uploading this file creates a NEW questionnaire that belongs to you, rather than editing the "
    "one it came from. Type your own answers into the Answer column, or leave it empty and record "
    "them in the app."
)


def build_question_set_workbook(
    *,
    title: str,
    description: str | None,
    sections: list[dict[str, Any]],
    source_title: str | None = None,
    shared_by: str | None = None,
    exported_on: str | None = None,
) -> bytes:
    """One questionnaire's QUESTIONS, as a workbook a designer may hand to another designer.

    ================================================================================================
    WHY THIS EXISTS AS A SECOND ARTEFACT RATHER THAN AS A RELAXED PERMISSION ON THE FIRST
    ================================================================================================

    ``build_questionnaire_workbook`` is deliberately LOSSLESS — every sitting, every respondent's
    name, every answer, every retired question — because a download-edit-upload round trip has to be.
    That is why its route refuses anyone but the owner, a designer on its design workshop, or an
    admin, and that refusal is correct: the artefact really does carry somebody's fieldwork.

    But the thing designers actually want to send each other is the INSTRUMENT — "here are the
    eighteen questions we ask weavers, use them". There was no way to produce that, so sharing was
    impossible and the only workaround was to widen the gate on a file full of respondents' names.
    This is the second artefact instead. It is not the same file with columns blanked out: it is
    built from a query that never reads an answer at all (see ``load_question_set`` in
    ``services/questionnaire_forms.py``), so it cannot leak one by omission.

    THREE THINGS ARE DELIBERATELY ABSENT AND EACH OF THEM WOULD CAUSE A DISTINCT BUG IF ADDED BACK:

    * **No Answer/Notes values.** The obvious one, and the point of the artefact.
    * **No Question IDs.** Those ids belong to the SENDER's questionnaire. Left in, a receiving
      designer's re-upload would report every row as "Question ID … does not belong to this
      questionnaire", and — worse — the ids are the signal the import uses to recognise a workbook
      that came out of the app and therefore to refuse to re-record its answers (see
      ``create_from_parsed``). A shared question set has to read as what it is: a filled-in
      pro-forma.
    * **No Questionnaire ID on the Details sheet.** Same reason from the other side: with one, the
      receiving designer's ``POST /questionnaires/{id}/upload`` answers 409 "that workbook was
      downloaded from a different questionnaire", which is exactly right for the full workbook and
      exactly wrong for a question set they are entitled to use.

    RETIRED QUESTIONS ARE NOT INCLUDED, and that is the one place this differs from the full
    workbook for a reason other than privacy. A retired question is not part of the instrument any
    more — it is kept only because answers hang off it. Sending it would plant a question the sender
    deliberately replaced into the receiver's brand-new form, next to its replacement.
    """
    extra = [("Contents", QUESTION_SET_CONTENTS)]
    if source_title:
        extra.append(("Exported from", source_title))
    if shared_by:
        extra.append(("Shared by", shared_by))
    if exported_on:
        extra.append(("Exported on", exported_on))

    wb = Workbook()
    questions = wb.active
    questions.title = SHEET_QUESTIONS
    # `entry_labels=[]` gives ONE blank Answer/Notes pair, exactly as the pro-forma does, so the
    # receiving designer can record on paper in the same file. It carries no values because every
    # question handed in below has empty `answers`/`answerNotes`.
    _write_questions_sheet(questions, sections, [])
    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        title=_sanitise(title or ""),
        description=_sanitise(description or ""),
        questionnaire_id="",
        version=None,
        extra=[(label, _sanitise(value)) for label, value in extra],
        note=_QUESTION_SET_NOTE,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))

    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def _filename_stem(title: str | None) -> str:
    stem = re.sub(r"[^A-Za-z0-9 _-]+", "", _sanitise(title or "questionnaire")).strip()
    return re.sub(r"\s+", "-", stem)[:80].strip("-") or "questionnaire"


def download_filename(title: str | None) -> str:
    """A safe, readable filename for a questionnaire download."""
    return f"{_filename_stem(title)}.xlsx"


def question_set_filename(title: str | None) -> str:
    """The same, for the questions-only download.

    The ``-questions`` suffix is not decoration. Both downloads land in the same Downloads folder
    with the same questionnaire title on them, and the two files are the difference between sending
    a colleague your question list and sending them every respondent you have ever interviewed. The
    name is the last thing standing between a designer and that mistake, so it says which one this
    is.
    """
    return f"{_filename_stem(title)}-questions.xlsx"
