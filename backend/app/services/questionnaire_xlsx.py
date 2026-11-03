from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from io import BytesIO
from typing import TYPE_CHECKING, Any

from app.services import naming

from app.services.xlsx_report import XLSX_MIME, _put, _sanitise

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

if TYPE_CHECKING:
    from openpyxl.worksheet.worksheet import Worksheet

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

PRO_FORMA_FILENAME = naming.export_filename("Questionnaire", "Pro-Forma", "xlsx", dated=False)

SHEET_QUESTIONS = "Questionnaire"
SHEET_DETAILS = "Details"
SHEET_HELP = "How to fill this in"

_BRAND = "5B21B6"
_WHITE = "FFFFFFFF"

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

MAX_QUESTIONS = 2000
MAX_SECTIONS = 200
MAX_ROWS_SCANNED = 20000
MAX_SHEETS_SCANNED = 12
MAX_HEADER_SCAN_ROWS = 15

MAX_PROMPT_CHARS = 2000
MAX_TITLE_CHARS = 220
MAX_CODE_CHARS = 24
MAX_ANSWER_CHARS = 8000

_DEFAULT_SECTION_TITLE = "General"
_UPLOAD_ENTRY_LABEL = "Answer"


class QuestionnaireXlsxError(ValueError):
    pass


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
_ANSWER_PREFIXES = ("answer", "response", "reply")
_NOTES_PREFIXES = ("notes", "note", "remarks", "remark", "comment", "comments", "observation")
_PREFIX_ROLES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("answer", _ANSWER_PREFIXES),
    ("notes", _NOTES_PREFIXES),
)

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
    questionnaireId: str | None = None
    sheet: str | None = None
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
    if not data:
        raise QuestionnaireXlsxError("The upload was empty. Attach the filled-in .xlsx pro-forma.")
    try:
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
    sections: dict[str, ParsedSection] = {}
    order: list[str] = []
    codes_taken: set[str] = set()
    current: ParsedSection | None = None
    seen_ids: set[str] = set()
    entry_labels: list[str] = []
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
            continue

        try:
            if not prompt:
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

    result.sections = [sections[key] for key in order]
    result.entryLabels = entry_labels


class _StopReading(Exception):
    pass



def _header(ws: Worksheet, row: int, labels: list[tuple[str, str]]) -> None:
    from openpyxl.styles import Alignment, Font, PatternFill
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
    from openpyxl.styles import Alignment, Font
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
    from openpyxl.styles import Alignment, Font
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
    from openpyxl.styles import Alignment, Font
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
                    cell.font = grey
            first = False
            row += 1
        if not (section.get("questions") or []):
            _put(ws, row, 1, section.get("code") or "")
            _put(ws, row, 2, section.get("title") or "")
            row += 1


def build_pro_forma() -> bytes:
    from openpyxl import Workbook
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
    from openpyxl import Workbook
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
    "designer. 'Questionnaire ID' and the Question ID column are left blank, so uploading this "
    "file creates a NEW questionnaire that belongs to you instead of editing the one it came "
    "from. Type your own answers into the Answer column, or leave it empty and record "
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
    from openpyxl import Workbook
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


def download_filename(title: str | None) -> str:
    return naming.export_filename("Questionnaire", "Export", "xlsx", subject=_sanitise(title or ""))


def question_set_filename(title: str | None) -> str:
    return naming.export_filename(
        "Questionnaire", "Questions", "xlsx", subject=_sanitise(title or "")
    )
