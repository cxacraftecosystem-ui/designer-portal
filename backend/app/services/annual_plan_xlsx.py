from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from datetime import date, datetime
from io import BytesIO
from typing import TYPE_CHECKING, Any

from app.services import address, naming
from app.services.stage_schema import ENUMS
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

if TYPE_CHECKING:
    from openpyxl.worksheet.worksheet import Worksheet

__all__ = [
    "MAX_FILTER_NOTE_CHARS",
    "MAX_PLAN_ROWS",
    "MAX_PLAN_YEAR",
    "MIN_PLAN_YEAR",
    "PRO_FORMA_FILENAME",
    "AnnualPlanXlsxError",
    "ParsedAnnualPlan",
    "ParsedPlanRow",
    "build_annual_plan_pro_forma",
    "build_annual_plan_workbook",
    "export_filename",
    "fold_workshop_no",
    "parse_annual_plan_workbook",
    "plan_year_label",
    "read_plan_date",
    "read_plan_year",
]


PRO_FORMA_FILENAME = naming.export_filename("Annual-Plan", "Pro-Forma", "xlsx", dated=False)

SHEET_PLAN = "Annual plan"
SHEET_DETAILS = "Details"
SHEET_HELP = "How to fill this in"

_BRAND = "1D4ED8"
_WHITE = "FFFFFFFF"
_GREY = "FF6B7280"

MAX_PLAN_ROWS = 600
MAX_ROWS_SCANNED = 5_000
MAX_SHEETS_SCANNED = 12
MAX_HEADER_SCAN_ROWS = 15

_TEXT_CAPS: dict[str, int] = {
    "workshopNo": 60,
    "plannedTitle": 220,
    "craftName": 160,
    "clusterName": 160,
    "state": 80,
    "district": 80,
    "venue": 220,
    "implementingAgency": 220,
    "sponsor": 220,
    "notes": 2_000,
}

_CAP_MIRRORS: dict[str, str] = {
    "workshopNo": "workshopCode",
    "plannedTitle": "workshopTitle",
    "craftName": "craftName",
    "clusterName": "clusterName",
    "state": "state",
    "district": "district",
    "venue": "venue",
    "implementingAgency": "implementingAgency",
    "sponsor": "sponsor",
}

MIN_PLAN_YEAR = 2000
MAX_PLAN_YEAR = 2100


class AnnualPlanXlsxError(ValueError):
    pass


_COLUMN_ALIASES: dict[str, tuple[str, ...]] = {
    "serialNo": ("sl no", "sl", "s no", "sr no", "serial no", "serial number", "sno", "srno", "no"),
    "workshopNo": (
        "workshop no",
        "workshop number",
        "workshop code",
        "workshop id",
        "workshop ref",
        "workshop reference",
        "ws no",
        "wsno",
        "sanction no",
        "sanction number",
    ),
    "plannedTitle": ("workshop title", "name of workshop", "workshop name", "title", "name"),
    "workshopKind": (
        "workshop kind",
        "type of workshop",
        "workshop type",
        "kind",
        "type",
        "category",
    ),
    "craftName": ("craft", "craft name", "handicraft", "craft handicraft"),
    "clusterName": ("cluster", "cluster name", "block"),
    "state": ("state", "state ut", "state union territory", "state ut name"),
    "district": ("district", "district name"),
    "venue": ("venue", "venue name", "venue address", "place", "place of workshop", "location"),
    "plannedStartDate": (
        "date",
        "start date",
        "from date",
        "date of workshop",
        "workshop date",
        "planned date",
        "commencement date",
        "date from",
        "start",
    ),
    "plannedEndDate": ("end date", "to date", "till date", "date to", "closing date", "end"),
    "implementingAgency": (
        "implementing agency",
        "agency",
        "implementing body",
        "implementing organisation",
        "implementing organization",
    ),
    "sponsor": ("sponsor", "sponsoring body", "funded by", "funding body"),
    "notes": ("notes", "note", "remarks", "remark", "comment", "comments", "observation"),
}

_DETAIL_KEYS: dict[str, tuple[str, ...]] = {
    "planYear": ("plan year", "financial year", "year", "fy", "annual plan year", "plan"),
    "filter": ("filtered view", "filtered export", "filter", "partial view", "partial export"),
}

MAX_FILTER_NOTE_CHARS = 200

_DETAIL_SHEET_NAMES = ("details", "about", "plan details", "info", "information")

_WIDTHS: dict[str, int] = {
    "workshopNo": 20,
    "plannedTitle": 40,
    "workshopKind": 26,
    "craftName": 22,
    "clusterName": 22,
    "state": 22,
    "district": 22,
    "venue": 40,
    "plannedStartDate": 14,
    "plannedEndDate": 14,
    "implementingAgency": 28,
    "sponsor": 24,
    "notes": 34,
}

_PRO_FORMA_COLUMNS: tuple[tuple[str, str], ...] = (
    ("workshopNo", "Workshop No."),
    ("plannedTitle", "Workshop Title"),
    ("workshopKind", "Type of Workshop"),
    ("craftName", "Craft"),
    ("clusterName", "Cluster"),
    ("state", "State"),
    ("district", "District"),
    ("venue", "Venue"),
    ("plannedStartDate", "Start Date"),
    ("plannedEndDate", "End Date"),
    ("implementingAgency", "Implementing Agency"),
    ("sponsor", "Sponsoring Body"),
    ("notes", "Remarks"),
)

_REQUIRED = frozenset({"workshopNo"})

_STORED_ROLES = frozenset(_COLUMN_ALIASES) - {"serialNo"}



@dataclass
class ParsedPlanRow:
    sheetRow: int
    workshopNo: str
    workshopNoKey: str
    plannedTitle: str | None = None
    workshopKind: str | None = None
    craftName: str | None = None
    clusterName: str | None = None
    state: str | None = None
    district: str | None = None
    venue: str | None = None
    plannedStartDate: date | None = None
    plannedEndDate: date | None = None
    implementingAgency: str | None = None
    sponsor: str | None = None
    notes: str | None = None


@dataclass
class ParsedAnnualPlan:
    planYear: int | None = None
    sheet: str | None = None
    filterNote: str | None = None
    rows: list[ParsedPlanRow] = field(default_factory=list)
    problems: list[ParseProblem] = field(default_factory=list)

    @property
    def rowCount(self) -> int:
        return len(self.rows)

    def payload(self) -> dict[str, Any]:
        return {
            "planYear": self.planYear,
            "sheet": self.sheet,
            "filterNote": self.filterNote,
            "rowCount": self.rowCount,
            "problems": [p.payload() for p in self.problems],
        }


_SPACEY = re.compile(r"[\s\u00a0\u1680\u2000-\u200b\u202f\u205f\u3000]+")


def fold_workshop_no(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    return _SPACEY.sub(" ", text).strip().upper()


def plan_year_label(year: int | None) -> str:
    if year is None:
        return "—"
    return f"{year}-{(year + 1) % 100:02d}"


def export_filename(year: int | None) -> str:
    if year is None:
        return naming.export_filename("Annual-Plan", "Export", "xlsx")
    return naming.export_filename(
        "Annual-Plan", "Export", "xlsx", period=f"FY{plan_year_label(year)}"
    )


_YEAR_PATTERNS = (
    re.compile(r"^(?P<a>\d{1,4})$"),
    re.compile(r"^(?P<a>\d{4})\s*[-/–—]\s*(?P<b>\d{2})$"),
    re.compile(r"^(?P<a>\d{4})\s*[-/–—]\s*(?P<b>\d{4})$"),
)
_YEAR_PREFIX = re.compile(
    r"^(?:f\s*\.?\s*y\s*\.?|financial\s+year|fin\.?\s*year|year|plan\s+year)\s*[:\-]?\s*",
    re.IGNORECASE,
)


def read_plan_year(
    value: Any, *, sheet: str, row: int | None, problems: list[ParseProblem]
) -> int | None:
    if value is None:
        return None
    if isinstance(value, FormulaCell):
        problems.append(
            ParseProblem(
                sheet,
                row,
                "warning",
                (
                    "The plan year cell holds a formula whose result is not saved in the file, so "
                    "the year could not be read. Open the workbook in Excel, press F9, save, and "
                    "upload again — or choose the year on the upload panel."
                ),
                str(value),
            )
        )
        return None
    if isinstance(value, datetime | date):
        year = value.year
        if MIN_PLAN_YEAR <= year <= MAX_PLAN_YEAR:
            problems.append(
                ParseProblem(
                    sheet,
                    row,
                    "warning",
                    (
                        f"Excel stored the plan year as a date, so it was read as {year} "
                        f"({plan_year_label(year)}). Format that cell as Text and type the year "
                        "again if that is not the year you meant."
                    ),
                    value.isoformat(),
                )
            )
            return year
        return _refuse_year(str(year), sheet, row, problems)
    if isinstance(value, int | float) and not isinstance(value, bool):
        year = int(value)
        if MIN_PLAN_YEAR <= year <= MAX_PLAN_YEAR:
            return year
        return _refuse_year(str(year), sheet, row, problems)

    text = cell_text(value)
    if not text:
        return None
    stripped = _YEAR_PREFIX.sub("", text.replace(".", " ").strip()).strip()
    stripped = _SPACEY.sub(" ", stripped).strip()
    for pattern in _YEAR_PATTERNS:
        match = pattern.match(stripped)
        if not match:
            continue
        start = int(match.group("a"))
        second = match.groupdict().get("b")
        if second is not None:
            expected_short = (start + 1) % 100
            if (len(second) == 2 and int(second) != expected_short) or (
                len(second) == 4 and int(second) != start + 1
            ):
                problems.append(
                    ParseProblem(
                        sheet,
                        row,
                        "error",
                        (
                            f"'{text}' is not one financial year. A financial year runs from one "
                            f"April to the next, so it is written {plan_year_label(start)} or "
                            f"{start}-{start + 1}. Correct the 'Plan year' cell and upload again."
                        ),
                        text,
                    )
                )
                return None
        if MIN_PLAN_YEAR <= start <= MAX_PLAN_YEAR:
            return start
        return _refuse_year(text, sheet, row, problems)

    problems.append(
        ParseProblem(
            sheet,
            row,
            "error",
            (
                f"'{text}' is not a year this can read. Type the financial year as "
                f"{plan_year_label(2026)} or 2026, or choose the year on the upload panel."
            ),
            text,
        )
    )
    return None


def _refuse_year(text: str, sheet: str, row: int | None, problems: list[ParseProblem]) -> None:
    problems.append(
        ParseProblem(
            sheet,
            row,
            "error",
            (
                f"'{text}' is not a plan year this directory can hold — the year has to be between "
                f"{MIN_PLAN_YEAR} and {MAX_PLAN_YEAR}. Correct the 'Plan year' cell and upload "
                "again."
            ),
            text,
        )
    )


_TEXT_DATE_ORDERS: tuple[tuple[str, bool], ...] = (
    ("%d/%m/%Y", True),
    ("%d-%m-%Y", True),
    ("%d.%m.%Y", True),
    ("%d/%m/%y", True),
    ("%d-%m-%y", True),
    ("%Y-%m-%d", False),
    ("%Y/%m/%d", False),
    ("%d %b %Y", False),
    ("%d %B %Y", False),
    ("%b %d %Y", False),
    ("%B %d %Y", False),
    ("%d-%b-%Y", False),
    ("%d-%B-%Y", False),
)


def read_plan_date(
    value: Any, *, sheet: str, row: int, column_label: str, problems: list[ParseProblem]
) -> date | None:
    if isinstance(value, FormulaCell):
        problems.append(
            ParseProblem(
                sheet,
                row,
                "warning",
                (
                    f"The {column_label} cell holds a formula whose result is not saved in the "
                    "file, so the date could not be read. Open the workbook in Excel, press F9, "
                    "save, and upload again."
                ),
                str(value),
            )
        )
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = cell_text(value)
    if not text:
        return None
    for pattern, ambiguous_order in _TEXT_DATE_ORDERS:
        try:
            parsed = datetime.strptime(text, pattern).date()  # noqa: DTZ007
        except ValueError:
            continue
        if ambiguous_order and parsed.day <= 12 and parsed.month != parsed.day:
            other = parsed.replace(day=parsed.month, month=parsed.day)
            problems.append(
                ParseProblem(
                    sheet,
                    row,
                    "warning",
                    (
                        f"The {column_label} '{text}' was read as {parsed.isoformat()} (day "
                        f"first). If you meant {other.isoformat()}, format that column as a date "
                        "in Excel and upload again — a date cell is never guessed at."
                    ),
                    text,
                )
            )
        return parsed
    problems.append(
        ParseProblem(
            sheet,
            row,
            "error",
            (
                f"The {column_label} '{text}' is not a date this can read. Type it as 12/03/2026, "
                "2026-03-12 or 12 March 2026 — or format the column as a date in Excel."
            ),
            text,
        )
    )
    return None



def _read_workshop_kind(
    text: str, *, sheet: str, row: int, problems: list[ParseProblem]
) -> str | None:
    kinds: dict[str, str] = ENUMS.get("WORKSHOP_KIND", {})
    folded = norm_header(text)
    for token, label in kinds.items():
        if folded in (norm_header(token), norm_header(label)):
            return token
    problems.append(
        ParseProblem(
            sheet,
            row,
            "warning",
            (
                f"'{text}' is not a type of workshop this app knows, so the type was left blank "
                f"and everything else on the row was kept. Use one of: "
                f"{', '.join(kinds.values())}."
            ),
            text,
        )
    )
    return None



def parse_annual_plan_workbook(data: bytes, *, filename: str | None = None) -> ParsedAnnualPlan:
    if not data:
        raise AnnualPlanXlsxError(
            "The upload was empty. Attach the filled-in annual plan pro-forma."
        )
    try:
        sheets = load_value_sheets(
            data,
            max_sheets=MAX_SHEETS_SCANNED,
            max_rows=MAX_ROWS_SCANNED,
            pro_forma="the annual plan pro-forma",
        )
    except XlsxTableError as exc:
        raise AnnualPlanXlsxError(str(exc)) from exc

    if not sheets:
        raise AnnualPlanXlsxError("The workbook has no sheets in it.")

    result = ParsedAnnualPlan()

    text_sheets = {name: as_text_rows(rows) for name, rows in sheets.items()}

    for name, rows in sheets.items():
        if norm_header(name) in _DETAIL_SHEET_NAMES:
            result.planYear = _details_plan_year(rows, name, result.problems)
            result.filterNote = _details_filter(rows)
            break

    picked = pick_sheet(
        text_sheets,
        result.problems,
        aliases=_COLUMN_ALIASES,
        required_role="workshopNo",
        preferred_names=(SHEET_PLAN, "annual plan", "plan", "workshops", "sheet1"),
        preferred_display=SHEET_PLAN,
        looks_like="an annual plan",
        most_noun="rows",
        max_scan_rows=MAX_HEADER_SCAN_ROWS,
    )
    if picked is None:
        raise AnnualPlanXlsxError(
            "No sheet in that workbook has a 'Workshop No.' column, so there was nothing to "
            "import. Download the pro-forma and type your directory under its headings, or add a "
            "header row with a column called 'Workshop No.'. A sheet with only a serial number "
            "cannot be used: a corrected sheet has to be able to find the rows it already wrote, "
            "and 1, 2, 3 changes the moment a row is inserted."
        )
    sheet_name, header_row, columns = picked
    result.sheet = sheet_name

    _report_unrecognised_columns(
        text_sheets[sheet_name], sheet_name, header_row, columns, result.problems
    )
    _read_rows(sheets[sheet_name], sheet_name, header_row, columns, result)

    if result.planYear is None and filename:
        match = re.search(r"(20\d\d)", filename)
        if match:
            year = int(match.group(1))
            if MIN_PLAN_YEAR <= year <= MAX_PLAN_YEAR:
                result.planYear = year

    if not result.rows:
        raise AnnualPlanXlsxError(
            f"The '{sheet_name}' sheet has a Workshop No. column but no rows under it. Type at "
            "least one planned workshop and upload again."
        )
    return result


def _report_unrecognised_columns(
    rows: list[tuple[int, list[str]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    problems: list[ParseProblem],
) -> None:
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
    if value is None:
        return None
    cap = _TEXT_CAPS[role]
    if len(value) <= cap:
        return value
    label = dict(_PRO_FORMA_COLUMNS)[role]
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


def _details_plan_year(rows: list[tuple[int, list[Any]]], sheet: str, problems: list[ParseProblem]):
    aliases = _DETAIL_KEYS["planYear"]
    for row_number, values in rows[:60]:
        if not values:
            continue
        if norm_header(values[0]) not in aliases:
            continue
        raw = next(
            (v for v in values[1:] if not isinstance(v, FormulaCell) and cell_text(v)),
            None,
        )
        if raw is None:
            raw = next((v for v in values[1:] if isinstance(v, FormulaCell)), None)
            if raw is None:
                return None
        return read_plan_year(raw, sheet=sheet, row=row_number, problems=problems)
    return None


def _details_filter(rows: list[tuple[int, list[Any]]]) -> str | None:
    aliases = _DETAIL_KEYS["filter"]
    for _row_number, values in rows[:60]:
        if not values:
            continue
        if norm_header(values[0]) not in aliases:
            continue
        for value in values[1:]:
            if isinstance(value, FormulaCell):
                continue
            text = cell_text(value).strip()
            if text:
                return text[:MAX_FILTER_NOTE_CHARS]
        return None
    return None


def _read_rows(
    rows: list[tuple[int, list[Any]]],
    sheet: str,
    header_row: int,
    columns: dict[int, tuple[str, str | None]],
    result: ParsedAnnualPlan,
) -> None:
    labels = dict(_PRO_FORMA_COLUMNS)
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
            continue

        if len(result.rows) >= MAX_PLAN_ROWS:
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        f"This sheet has more than {MAX_PLAN_ROWS} rows. The first {MAX_PLAN_ROWS} "
                        f"were imported and everything from row {row_number} down was not. A "
                        "year's directory is 200-300 workshops; if this file really is bigger, "
                        "split it and upload each part."
                    ),
                    None,
                )
            )
            break

        raw_no = cell_text(get("workshopNo"))
        if not raw_no:
            trace = ", ".join(t for v in values if (t := cell_text(v)))[:200]
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        "This row has no Workshop No., so there is nothing to file it under and "
                        "nothing to match it to when you upload a correction. Type the ministry's "
                        "reference for it, or delete the row."
                    ),
                    trace or None,
                )
            )
            continue

        workshop_no = raw_no
        if len(workshop_no) > _TEXT_CAPS["workshopNo"]:
            workshop_no = clip(workshop_no, _TEXT_CAPS["workshopNo"])
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "warning",
                    (
                        f"The Workshop No. was longer than {_TEXT_CAPS['workshopNo']} characters "
                        "and was shortened. A workshop code is copied onto the workshop itself, "
                        "which is bounded at that length."
                    ),
                    raw_no[:200],
                )
            )

        key = fold_workshop_no(workshop_no)
        if key in seen:
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        f"Workshop No. '{workshop_no}' is also on row {seen[key]} of this sheet. "
                        "The same workshop cannot be planned twice in one year, so this row was "
                        "not imported. Give it its own number or delete it."
                    ),
                    workshop_no,
                )
            )
            continue
        seen[key] = row_number

        entry = ParsedPlanRow(sheetRow=row_number, workshopNo=workshop_no, workshopNoKey=key)

        kind_text = cell_text(get("workshopKind"))
        if kind_text:
            entry.workshopKind = _read_workshop_kind(
                kind_text, sheet=sheet, row=row_number, problems=result.problems
            )

        state_text = cell_text(get("state"))
        if state_text:
            entry.state = _clip_role(
                address.normalize_state(state_text), "state", sheet, row_number, result.problems
            )
            reason = address.state_error(entry.state)
            if reason:
                result.problems.append(
                    ParseProblem(sheet, row_number, "warning", reason, state_text)
                )

        district_text = cell_text(get("district"))
        if district_text:
            entry.district = _clip_role(
                address.normalize_district(entry.state, district_text),
                "district",
                sheet,
                row_number,
                result.problems,
            )
            reason = address.district_error(entry.state, entry.district)
            if reason and not address.state_error(entry.state):
                result.problems.append(
                    ParseProblem(sheet, row_number, "warning", reason, district_text)
                )

        entry.plannedStartDate = read_plan_date(
            get("plannedStartDate"),
            sheet=sheet,
            row=row_number,
            column_label="Start Date",
            problems=result.problems,
        )
        entry.plannedEndDate = read_plan_date(
            get("plannedEndDate"),
            sheet=sheet,
            row=row_number,
            column_label="End Date",
            problems=result.problems,
        )
        if (
            entry.plannedStartDate
            and entry.plannedEndDate
            and entry.plannedEndDate < entry.plannedStartDate
        ):
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "warning",
                    (
                        f"The End Date ({entry.plannedEndDate.isoformat()}) is before the Start "
                        f"Date ({entry.plannedStartDate.isoformat()}). Both were kept as typed — "
                        "correct the sheet and upload it again."
                    ),
                    None,
                )
            )

        for role in ("plannedTitle", "craftName", "clusterName", "venue", "implementingAgency", "sponsor", "notes"):
            text = cell_text(get(role))
            if not text:
                continue
            cap = _TEXT_CAPS[role]
            if len(text) > cap:
                result.problems.append(
                    ParseProblem(
                        sheet,
                        row_number,
                        "warning",
                        (
                            f"'{labels[role]}' was longer than {cap} characters and was shortened. "
                            "Everything else on the row was kept."
                        ),
                        text[:200],
                    )
                )
                text = clip(text, cap)
            setattr(entry, role, text)

        result.rows.append(entry)



def _header(ws: Worksheet, row: int, labels: tuple[tuple[str, str], ...]) -> None:
    from openpyxl.styles import Alignment, Font, PatternFill
    fill = PatternFill(fill_type="solid", start_color=f"FF{_BRAND}")
    required_fill = PatternFill(fill_type="solid", start_color="FF9A3412")
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


_DETAILS_NOTE = (
    "Type the financial year this directory is for. Leave every heading on the 'Annual plan' sheet "
    "exactly as you found it — the app finds the columns by their headings, not by where they are. "
    "Uploading a corrected copy of this file updates the workshops it names and creates the ones it "
    "does not; it never makes a second copy of a workshop already in the plan."
)

_DETAILS_NOTE_FILTERED = (
    "This file is a FILTERED VIEW of the year, not the whole directory — the 'Filtered view' row "
    "above says which filter it was taken under. Correcting these rows and uploading this file "
    "updates the workshops it names and creates the ones it does not; the workshops it does not "
    "name are untouched and stay in the plan. It CANNOT be used to withdraw workshops: an upload "
    "of this file with 'withdraw the workshops missing from this sheet' ticked is refused, because "
    "the rows missing from it are the ones the filter hid rather than the ones the ministry "
    "dropped. Export the year with the filters cleared if that is what you meant to do. Leave "
    "every heading on the 'Annual plan' sheet exactly as you found it — the app finds the columns "
    "by their headings, not by where they are."
)


def _details_sheet(ws: Worksheet, *, plan_year: str, filter_note: str | None = None) -> None:
    from openpyxl.styles import Alignment, Font
    ws.sheet_properties.tabColor = f"FF{_BRAND}"
    label = _put(ws, 1, 1, "Plan year")
    label.font = Font(bold=True)
    _put(ws, 1, 2, plan_year)
    if filter_note:
        marker = _put(ws, 2, 1, "Filtered view")
        marker.font = Font(bold=True)
        value = _put(ws, 2, 2, filter_note[:MAX_FILTER_NOTE_CHARS])
        value.alignment = Alignment(wrap_text=True, vertical="top")
    note = _put(ws, 3, 1, _DETAILS_NOTE if not filter_note else _DETAILS_NOTE_FILTERED)
    note.font = Font(italic=True, color=_GREY)
    note.alignment = Alignment(wrap_text=True, vertical="top")
    ws.merge_cells(start_row=3, start_column=1, end_row=3, end_column=6)
    ws.row_dimensions[3].height = 64
    ws.column_dimensions["A"].width = 22
    ws.column_dimensions["B"].width = 28


_HELP_LINES: tuple[tuple[str, str], ...] = (
    ("h", "How to fill this in"),
    (
        "p",
        "One row per planned workshop, on the 'Annual plan' sheet. Workshop No. is the only column "
        "you must fill in; everything else can be added later by uploading a corrected copy of "
        "this same file.",
    ),
    ("b", "The Workshop No. column"),
    (
        "p",
        "The ministry's own reference for the workshop. It is how the app recognises a workshop "
        "when you upload a corrected sheet, so do not renumber workshops between versions. A "
        "serial number (1, 2, 3) is NOT a workshop number and cannot be used: insert one row at "
        "the top and every number below it changes.",
    ),
    ("b", "The two date columns"),
    (
        "p",
        "Format the date columns as a date in Excel and the app never guesses. Typed as plain "
        "text, 03/04/2026 is read as 3 April 2026 — day first — and you are told so on the upload "
        "report, because 3 April and 4 March are both valid readings of that.",
    ),
    ("b", "The State and District columns"),
    (
        "p",
        "Spelled as the Government of India's list spells them. A spelling the app does not "
        "recognise is KEPT exactly as you typed it and reported on the upload report; it is never "
        "silently blanked.",
    ),
    ("b", "The Type of Workshop column"),
    (
        "p",
        "One of: " + ", ".join(ENUMS.get("WORKSHOP_KIND", {}).values()) + ". Anything else is left "
        "blank and reported, and the rest of the row is kept.",
    ),
    ("b", "Correcting the plan"),
    (
        "p",
        "Upload the corrected sheet again. Rows already in the plan are updated, new rows are "
        "added, and a row you have deleted from the sheet is LEFT ALONE unless you tick 'withdraw "
        "the workshops missing from this sheet' on the upload panel. Uploading the same sheet "
        "twice changes nothing at all.",
    ),
    ("b", "A workshop that has already started"),
    (
        "p",
        "A plan row that has been opened as a real design & prototype workshop is still corrected "
        "in the directory — AND THE WORKSHOP ITSELF IS NOT TOUCHED. The upload report names every "
        "such row. Correct the workshop on its own screen.",
    ),
    ("b", "An example"),
)

_HELP_EXAMPLE: tuple[str, ...] = (
    "Workshop No.  |  Workshop Title  |  Type of Workshop  |  Craft  |  State  |  District  |  "
    "Venue  |  Start Date",
    "DPW/2026/017  |  Kachchh weaving, Bhuj  |  Design & Prototype Development  |  Kachchh "
    "weaving  |  Gujarat  |  Kachchh  |  DIC Hall, Bhuj  |  12/03/2026",
    "DPW/2026/018  |  (left blank)  |  Skill Upgradation  |  Blue pottery  |  Rajasthan  |  "
    "Jaipur  |  Kala Kendra, Jaipur  |  02/04/2026",
)


def _help_sheet(ws: Worksheet) -> None:
    from openpyxl.styles import Alignment, Font
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


def build_annual_plan_pro_forma() -> bytes:
    from openpyxl import Workbook
    wb = Workbook()
    plan = wb.active
    plan.title = SHEET_PLAN
    plan.sheet_properties.tabColor = f"FF{_BRAND}"
    _header(plan, 1, _PRO_FORMA_COLUMNS)
    _details_sheet(wb.create_sheet(title=SHEET_DETAILS), plan_year="")
    _help_sheet(wb.create_sheet(title=SHEET_HELP))
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()


def build_annual_plan_workbook(
    *, plan_year: int | None, rows: list[dict[str, Any]], filter_note: str | None = None
) -> bytes:
    from openpyxl import Workbook
    wb = Workbook()
    plan = wb.active
    plan.title = SHEET_PLAN
    plan.sheet_properties.tabColor = f"FF{_BRAND}"
    _header(plan, 1, _PRO_FORMA_COLUMNS)

    kinds: dict[str, str] = ENUMS.get("WORKSHOP_KIND", {})
    for offset, row in enumerate(rows, start=2):
        for index, (key, _label) in enumerate(_PRO_FORMA_COLUMNS, start=1):
            value = row.get(key)
            if key == "workshopKind" and value:
                value = kinds.get(str(value), value)
            elif key in ("plannedStartDate", "plannedEndDate") and value is not None:
                value = value.date() if isinstance(value, datetime) else value
            cell = _put(plan, offset, index, value)
            if key in ("plannedStartDate", "plannedEndDate") and value is not None:
                cell.number_format = "DD/MM/YYYY"

    _details_sheet(
        wb.create_sheet(title=SHEET_DETAILS),
        plan_year=plan_year_label(plan_year),
        filter_note=filter_note,
    )
    _help_sheet(wb.create_sheet(title=SHEET_HELP))
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()
