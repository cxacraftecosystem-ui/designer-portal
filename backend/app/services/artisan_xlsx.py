from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from io import BytesIO
from typing import TYPE_CHECKING, Any

from app.services import naming
from app.services.artisan_identity import (
    aadhaar_error,
    is_masked_aadhaar,
    mask_aadhaar,
    normalize_aadhaar,
    normalize_pehchan,
    pehchan_error,
)
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

if TYPE_CHECKING:
    from openpyxl.worksheet.worksheet import Worksheet

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

ARTISAN_PRO_FORMA_FILENAME = naming.export_filename("Artisan", "Pro-Forma", "xlsx", dated=False)

SHEET_ARTISANS = "Artisans"
SHEET_DETAILS = "Details"
SHEET_HELP = "How to fill this in"

_BRAND = "0F766E"
_WHITE = "FFFFFFFF"
_GREY = "FF6B7280"


MAX_ARTISANS = 200

MAX_ROWS_SCANNED = 5_000
MAX_SHEETS_SCANNED = 12
MAX_HEADER_SCAN_ROWS = 15

MAX_NAME_CHARS = 180
MAX_PLACE_CHARS = 180
MAX_PROSE_CHARS = 4_000
MAX_SHORT_CHARS = 180


class ArtisanXlsxError(ValueError):
    pass


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

_DETAIL_KEYS: dict[str, tuple[str, ...]] = {
    "designWorkshopId": ("design workshop id", "workshop id", "design workshop"),
    "workshopTitle": ("workshop title",),
    "state": ("state",),
    "district": ("district",),
    "venue": ("venue",),
}



@dataclass(frozen=True)
class ArtisanDefaults:
    state: str = ""
    district: str = ""
    place: str = ""
    craftName: str = ""


@dataclass
class ParsedArtisan:
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
    designWorkshopId: str | None = None
    rowsRead: int = 0

    def payload(self) -> dict[str, Any]:
        return {
            "sheet": self.sheet,
            "rowsRead": self.rowsRead,
            "artisanCount": len(self.artisans),
            "problems": [p.payload() for p in self.problems],
        }


_ISO_DATE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})")
_DMY_DATE = re.compile(r"^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$")


def _date_text(raw: str) -> str | None:
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
    kept = 0
    ignored_reported: set[str] = set()
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
        if severity == "error":
            errored.add(row)
        result.problems.append(
            ParseProblem(sheet=sheet, row=row, severity=severity, reason=reason, value=value)
        )

    for row_number, values in rows:
        if row_number <= header_row:
            continue
        if not any(str(v).strip() for v in values):
            continue
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
                result.problems.append(
                    ParseProblem(sheet=sheet, row=None, severity="warning", reason=sentence)
                )
                ignored_reported.add(role)

        if row_number in errored:
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

_REQUIRED = frozenset({"name", "craftName", "place", "aadhaarNumber", "dos", "donts"})


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


def _details_sheet(ws: Worksheet, workshop: Any | None) -> None:
    from openpyxl.styles import Alignment, Font
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
        "Phone and Email are not checked here. A number that is not a valid phone number is kept "
        "on the artisan record but left out of the workshop's participant list, so check them "
        "before uploading.",
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
    from openpyxl import Workbook
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
    if workshop is None or not (
        getattr(workshop, "workshopCode", None) or getattr(workshop, "title", None)
    ):
        return ARTISAN_PRO_FORMA_FILENAME
    return naming.workshop_filename(workshop, "Artisan-List", "xlsx")


_DECLARED = {key for key, _label in _COLUMNS}
_MATCHED = set(_ARTISAN_COLUMN_ALIASES)
if _DECLARED != _MATCHED:  # pragma: no cover - a declaration error, not a runtime state
    raise RuntimeError(
        "artisan_xlsx._COLUMNS and _ARTISAN_COLUMN_ALIASES must name the same columns; "
        f"only on the sheet: {sorted(_DECLARED - _MATCHED)}; "
        f"only in the aliases: {sorted(_MATCHED - _DECLARED)}."
    )
for _key, _label in _COLUMNS:
    if norm_header(_label) not in _ARTISAN_COLUMN_ALIASES[_key]:  # pragma: no cover
        raise RuntimeError(
            f"artisan_xlsx prints the heading {_label!r} for column {_key!r}, which normalises to "
            f"{norm_header(_label)!r} and is not one of its aliases. The pro-forma this module "
            f"writes would not be readable by the parser in the same module."
        )
