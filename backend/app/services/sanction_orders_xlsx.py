from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal, InvalidOperation
from io import BytesIO
from typing import TYPE_CHECKING, Any

from app.services import naming
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

if TYPE_CHECKING:
    from openpyxl.worksheet.worksheet import Worksheet

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


PRO_FORMA_FILENAME = naming.export_filename(
    "Sanction-Orders", "Pro-Forma", "xlsx", dated=False
)

SHEET_ORDERS = "Sanction orders"
SHEET_HELP = "How to fill this in"

_BRAND = "9A3412"
_WHITE = "FFFFFFFF"
_GREY = "FF6B7280"

MAX_SANCTION_ROWS = 200
MAX_ROWS_SCANNED = 5_000
MAX_SHEETS_SCANNED = 12
MAX_HEADER_SCAN_ROWS = 15

MAX_DESIGNERS_PER_ROW = 40


class SanctionXlsxError(ValueError):
    pass


_TEXT_CAPS: dict[str, int] = {
    "sanctionOrderNo": 120,
    "designerNames": 160,
    "notes": 2_000,
}

_CAP_MIRRORS: dict[str, str] = {
    "sanctionOrderNo": "sanctionOrderNo",
    "designerNames": "designerName",
    "notes": "notes",
}


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

_REQUIRED_ROLE = "sanctionOrderNo"

_PRO_FORMA_COLUMNS: tuple[tuple[str, str], ...] = (
    ("sanctionOrderNo", "Sanction Order No."),
    ("sanctionOrderDate", "Sanction Date"),
    ("sanctionAmount", "Sanctioned Amount"),
    ("designerNames", "Designer Name(s)"),
    ("designerEmails", "Designer Email(s)"),
    ("notes", "Remarks"),
)

_REQUIRED = frozenset({"sanctionOrderNo", "sanctionOrderDate", "sanctionAmount", "designerEmails"})

_WIDTHS: dict[str, int] = {
    "sanctionOrderNo": 22,
    "sanctionOrderDate": 14,
    "sanctionAmount": 18,
    "designerNames": 42,
    "designerEmails": 46,
    "notes": 34,
}

_DECLARED = {role for role, _label in _PRO_FORMA_COLUMNS}
assert set(_COLUMN_ALIASES) >= _DECLARED, sorted(_DECLARED - set(_COLUMN_ALIASES))
for _role, _label in _PRO_FORMA_COLUMNS:
    assert norm_header(_label) in _COLUMN_ALIASES[_role], (_role, _label)



@dataclass
class ParsedSanctionRow:
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


_SPACEY = re.compile(r"[\s\u00a0\u1680\u2000-\u200b\u202f\u205f\u3000]+")

_OTHER_SEPARATORS = re.compile(r"[;\n\r\u2028\u2029]")

_MONEY_NOISE = re.compile(r"(?:rs\.?|inr|₹|/-|,|\s)+", re.IGNORECASE)


def fold_name(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    return _SPACEY.sub(" ", text).strip().casefold()


def split_list_cell(
    raw: Any, *, sheet: str, row: int, column_label: str, problems: list[ParseProblem]
) -> list[str]:
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
    return _NON_ALNUM.sub("", str(value or "")).upper()



def parse_sanction_workbook(data: bytes, *, filename: str | None = None) -> ParsedSanctionSheet:
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

        if len(result.rows) >= MAX_SANCTION_ROWS:
            result.problems.append(
                ParseProblem(
                    sheet,
                    row_number,
                    "error",
                    (
                        f"This sheet has more than {MAX_SANCTION_ROWS} rows. The first "
                        f"{MAX_SANCTION_ROWS} were read and everything from row {row_number} down "
                        "was not. Split this sheet and upload each part."
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



def _header(ws: Worksheet, row: int, labels: tuple[tuple[str, str], ...]) -> None:
    from openpyxl.styles import Alignment, Font, PatternFill
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
        "access to the workshop. Make sure the lead is typed first.",
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


def build_sanction_pro_forma() -> bytes:
    from openpyxl import Workbook
    wb = Workbook()
    orders = wb.active
    orders.title = SHEET_ORDERS
    orders.sheet_properties.tabColor = f"FF{_BRAND}"
    _header(orders, 1, _PRO_FORMA_COLUMNS)
    _help_sheet(wb.create_sheet(title=SHEET_HELP))
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()
