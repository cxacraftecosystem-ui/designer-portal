from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from io import BytesIO
from typing import TYPE_CHECKING, Any
from zipfile import BadZipFile

if TYPE_CHECKING:
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
    pass


_SPACEY = re.compile(r"[\s  -\u200b  　]+")
_PUNCT = re.compile(r"[^\w\s]+", re.UNICODE)


def norm_header(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or ""))
    text = _PUNCT.sub(" ", text.replace("_", " "))
    return _SPACEY.sub(" ", text).strip().lower()


TRUEISH = {"y", "yes", "true", "t", "1", "required", "mandatory", "compulsory", "x", "✓", "✔", "☑"}
FALSEISH = {"n", "no", "false", "f", "0", "optional", "not required", "", "-", "—", "na", "n a"}

_LABEL_SPLIT = re.compile(r"\s*(?:[-–—:/|]|\bfor\b|\bby\b)\s*", re.IGNORECASE)


@dataclass(frozen=True)
class ParseProblem:
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



class FormulaCell(str):
    pass


def cell_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "Yes" if value else "No"
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    text = str(value).strip()
    if text.startswith("#") and text.endswith("!") and len(text) <= 10:
        return ""
    if text in {"#N/A", "#NULL!"}:
        return ""
    return text


def clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1] + "…"


def truthy(text: str) -> bool | None:
    key = norm_header(text)
    if key in TRUEISH:
        return True
    if key in FALSEISH:
        return False
    return None



def _role_for(
    header: str,
    *,
    aliases: dict[str, tuple[str, ...]],
    prefix_roles: tuple[tuple[str, tuple[str, ...]], ...],
) -> tuple[str, str | None] | None:
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
    repeatable = {role for role, _prefixes in prefix_roles}
    for row_number, values in rows[:max_scan_rows]:
        mapping: dict[int, tuple[str, str | None]] = {}
        seen_roles: set[str] = set()
        for index, raw in enumerate(values):
            role = _role_for(norm_header(raw), aliases=aliases, prefix_roles=prefix_roles)
            if role is None:
                continue
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
                    cells.append(FormulaCell(formula))
                    continue
            cells.append(text)
        out.append((row_number, cells))
    return out


def sheet_value_rows(
    ws: Worksheet, formulas: dict[tuple[int, int], str], *, max_rows: int
) -> list[tuple[int, list[Any]]]:
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
    return [
        (n, [c if isinstance(c, FormulaCell) else cell_text(c) for c in cells]) for n, cells in rows
    ]


def formula_map(
    data: bytes, *, max_sheets: int, max_rows: int
) -> dict[str, dict[tuple[int, int], str]]:
    from openpyxl import load_workbook
    from openpyxl.utils.exceptions import InvalidFileException
    found: dict[str, dict[tuple[int, int], str]] = {}
    try:
        wb = load_workbook(BytesIO(data), data_only=False, read_only=True)
    except (InvalidFileException, OSError, ValueError, KeyError, TypeError):
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


ZIP_MAGIC = b"PK\x03\x04"
OLE2_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"


def unreadable_file_message(data: bytes, *, pro_forma: str = "the .xlsx pro-forma") -> str:
    head = data[:8]
    if head.startswith(OLE2_MAGIC):
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
    from openpyxl import load_workbook
    from openpyxl.utils.exceptions import InvalidFileException
    try:
        wb = load_workbook(BytesIO(data), data_only=True, read_only=True)
    except (InvalidFileException, BadZipFile, OSError, ValueError, KeyError, TypeError) as exc:
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
    from openpyxl import load_workbook
    from openpyxl.utils.exceptions import InvalidFileException
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
