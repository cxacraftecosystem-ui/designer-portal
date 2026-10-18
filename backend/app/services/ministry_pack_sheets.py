"""THE MINISTRY PACK'S SHEETS: pure functions from already-read rows to ``xlsx_report`` sheet dicts.

No database, no storage, no clock. :mod:`app.services.ministry_exports` reads the rows and hands
them here as plain dicts; this module decides what the Excel workbook says and what the PDF summary
counts, so both can be tested without Postgres.

    sheet = {"name": str, "color": "#RRGGBB", "columns": [label, ...], "rows": [[cell, ...], ...]}

── WHAT IS IN A PACK ───────────────────────────────────────────────────────────────────────────

Summary first (period, snapshot, scope, standing and period-activity counts, a by-state table, the
data-protection note and, when the cap was hit, a truncation note), then one sheet per record type
the Ministry keeps a register of: Workshops, Sanction orders, Annual plan, Inspections and Report
hand-ons.

── WHAT IS NOT, AND WHY ────────────────────────────────────────────────────────────────────────

NO STAGE ANSWERS. ``deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES`` is ``{ADMIN, MASTER_ADMIN}``: a Ministry
Admin may read design-workshop stage data on screen and may not take it out of the product (owner
rulings of 2026-08-30 and 2026-09-13). One pack goes to every recipient, Ministry Admins included,
so the pack carries the registers the Ministry already downloads (``/ministry-dashboard/
design-workshops.csv``, ``/annual-plan/export.xlsx``) and nothing from inside a workshop. No
questionnaire answers, transcripts, media, URLs or AI layers either.

NO CONTACT DETAILS. A sanction order's designer e-mail is left out, and :func:`scrub_sheet` runs over
every sheet anyway: phone and e-mail columns are dropped, identity numbers are cut to their last four
digits, any twelve-digit run in free text is masked the same way, and any value that is a URL is
blanked. The registers above should never carry those values; the scrub is what makes "should" a
guarantee for a file that leaves the product.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from app.services.ministry_dashboard import STANDING_GROUPS, group_of
from app.services.records import mask_identity_number
from app.services.stage_schema import ENUMS

#: The scheme key for a workshop whose kind was never stated (``workshopKind IS NULL``). Without it
#: those workshops would silently drop out of every pack.
UNSTATED = "UNSTATED"
UNSTATED_LABEL = "Scheme not yet stated"

#: The data-protection sentence both files carry, word for word.
DATA_PROTECTION_NOTE = (
    "Registers only: no stage answers, contact details, photographs, recordings or links. "
    "Identity numbers, where any appear, show their last four digits."
)

_COLORS = {
    "summary": "#5B4636",
    "workshops": "#B5651D",
    "sanctions": "#2F6B4F",
    "plan": "#2C5C8A",
    "inspections": "#7A4E8C",
    "handons": "#8A6D1F",
}

MONTHS = (
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)

#: The register columns of ``/ministry-dashboard/design-workshops.csv``, in that file's order, then
#: what the pack adds. Kept as the CSV's labels so the two files read alike.
WORKSHOP_COLUMNS: tuple[str, ...] = (
    "Workshop code",
    "Title",
    "Kind",
    "Craft",
    "Cluster",
    "State",
    "District",
    "Venue",
    "Start date",
    "End date",
    "Designer",
    "Standing",
    "Standing group",
    "Artisans",
    "Stages complete",
    "Stages total",
    "Required fields answered",
    "Required fields asked",
    "Progress %",
    "Last updated",
    "Scheme name",
    "Implementing agency",
    "Sponsor",
    "Sanction number",
    "Sanction date",
    "Sanction amount (₹)",
    "Handed on",
)


# --------------------------------------------------------------------------------------
# Schemes and periods
# --------------------------------------------------------------------------------------


def scheme_keys() -> list[str]:
    """The six ``WORKSHOP_KIND`` tokens in registry order, then :data:`UNSTATED`."""
    return [*ENUMS["WORKSHOP_KIND"].keys(), UNSTATED]


def scheme_label(key: str | None) -> str:
    if key == UNSTATED or key is None:
        return UNSTATED_LABEL
    return str(ENUMS["WORKSHOP_KIND"].get(key, key))


def scheme_slug(key: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", str(key).lower()).strip("-") or "scheme"


_PERIOD_RE = re.compile(r"^(\d{4})-(0[1-9]|1[0-2])$")


def parse_period(period: str) -> tuple[int, int] | None:
    found = _PERIOD_RE.match(str(period or ""))
    if not found:
        return None
    return int(found.group(1)), int(found.group(2))


def period_label(period: str) -> str:
    """``"2026-09"`` -> ``"September 2026"``."""
    parsed = parse_period(period)
    if parsed is None:
        return str(period)
    year, month = parsed
    return f"{MONTHS[month - 1]} {year}"


def period_bounds(period: str, tz: Any) -> tuple[datetime, datetime]:
    """The period's first instant and the next period's first instant, in UTC, for India time."""
    parsed = parse_period(period)
    if parsed is None:
        raise ValueError(f"Not a period: {period!r}")
    year, month = parsed
    start = datetime(year, month, 1, tzinfo=tz)
    end = datetime(year + (month == 12), 1 if month == 12 else month + 1, 1, tzinfo=tz)
    return start.astimezone(UTC), end.astimezone(UTC)


def plan_year_for(period: str) -> int:
    """The financial year (April to March) a period falls in, as ``AnnualPlanEntry.planYear``."""
    parsed = parse_period(period)
    if parsed is None:
        raise ValueError(f"Not a period: {period!r}")
    year, month = parsed
    return year if month >= 4 else year - 1


# --------------------------------------------------------------------------------------
# Scrub
# --------------------------------------------------------------------------------------

_DROP_SUFFIXES = (".phone", ".email")
_IDENTITY_SUFFIXES = (".aadhaarnumber", ".pehchancardnumber", ".artisancardno")
_DROP_LABEL = re.compile(r"\b(phone|mobile|e-?mail)\b", re.IGNORECASE)
_IDENTITY_LABEL = re.compile(r"aadhaar|pehchan|artisan card", re.IGNORECASE)
_URL = re.compile(r"^\s*https?://", re.IGNORECASE)
#: Twelve digits in a row, or in the 4-4-4 groups Aadhaar is printed in, standing alone: not inside a
#: longer number and not inside a word (a checksum's hex keeps its digits).
_TWELVE = re.compile(r"(?<![0-9A-Za-z])(\d{4})[ -]?(\d{4})[ -]?(\d{4})(?![0-9A-Za-z])")


def _mask_runs(text: str) -> str:
    return _TWELVE.sub(lambda m: f"XXXX XXXX {m.group(3)}", text)


def scrub_sheet(sheet: dict[str, Any]) -> dict[str, Any]:
    """Make one sheet safe to leave the product. Returns a new dict; the input is not changed.

    A column is matched on its KEY when the sheet carries ``keys`` (stage-shaped sheets do) and on its
    label otherwise: phone and e-mail columns are dropped, identity-number columns keep their last four
    digits, and in every remaining cell a twelve-digit run is masked and a URL is blanked.
    """
    columns = list(sheet.get("columns") or [])
    keys = [str(k).lower() for k in (sheet.get("keys") or [])]
    if len(keys) != len(columns):
        keys = [""] * len(columns)

    keep: list[int] = []
    identity: set[int] = set()
    for index, label in enumerate(columns):
        key = keys[index]
        text = str(label or "")
        if key.endswith(_DROP_SUFFIXES) or (not key and _DROP_LABEL.search(text)):
            continue
        if key.endswith(_IDENTITY_SUFFIXES) or (not key and _IDENTITY_LABEL.search(text)):
            identity.add(index)
        keep.append(index)

    def clean(index: int, value: Any) -> Any:
        if value is None:
            return None
        if index in identity:
            return mask_identity_number(str(value)) if str(value).strip() else value
        if isinstance(value, str):
            if _URL.match(value):
                return ""
            return _mask_runs(value)
        if isinstance(value, int) and not isinstance(value, bool) and len(str(abs(value))) == 12:
            return _mask_runs(str(value))
        return value

    rows = []
    for row in sheet.get("rows") or []:
        cells = list(row) + [None] * (len(columns) - len(row))
        rows.append([clean(index, cells[index]) for index in keep])

    out = {k: v for k, v in sheet.items() if k not in ("columns", "rows", "keys")}
    out["columns"] = [columns[index] for index in keep]
    if sheet.get("keys") is not None and any(keys):
        out["keys"] = [sheet["keys"][index] for index in keep]
    out["rows"] = rows
    return out


# --------------------------------------------------------------------------------------
# Cells
# --------------------------------------------------------------------------------------


def _aware(value: Any) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def _day(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, datetime):
        return value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()
    return str(value)


def _text(value: Any) -> str:
    return "" if value is None else str(value)


def _money(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value)).quantize(Decimal("0.01"))
    except InvalidOperation, ValueError:
        return None


def money_text(value: Decimal | None) -> str:
    return f"{(value or Decimal(0)).quantize(Decimal('0.01'))}"


def indian_number(value: Any) -> str:
    """``1234500`` -> ``"12,34,500"``; decimals kept to two places when present."""
    amount = _money(value) or Decimal(0)
    negative = amount < 0
    whole, _, fraction = f"{abs(amount):.2f}".partition(".")
    if len(whole) > 3:
        head, tail = whole[:-3], whole[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        whole = ",".join(groups) + "," + tail
    text = whole if fraction == "00" else f"{whole}.{fraction}"
    return f"-{text}" if negative else text


def _in_period(value: Any, start: datetime, end: datetime) -> bool:
    moment = _aware(value)
    return moment is not None and start <= moment < end


# --------------------------------------------------------------------------------------
# Counts
# --------------------------------------------------------------------------------------


def compute_counts(
    workshops: list[dict[str, Any]], start: datetime, end: datetime, *, truncated: bool
) -> dict[str, Any]:
    """The pack's figures, from real timestamps only. ``start``/``end`` bound the period in UTC.

    Standing counts are what the workshops stand at NOW (the snapshot); the four "in period" counts
    are events whose timestamp falls inside the period. ``handedInInPeriod`` reads the LAST hand-in,
    which is the only one the row keeps.
    """
    standing = dict.fromkeys(STANDING_GROUPS, 0)
    participants = 0
    sanctioned = Decimal(0)
    for row in workshops:
        group = group_of(row.get("status"))
        if group in standing:
            standing[group] += 1
        participants += int(row.get("participants") or 0)
        sanctioned += _money(row.get("sanctionAmount")) or Decimal(0)
    return {
        "workshops": len(workshops),
        **standing,
        "createdInPeriod": sum(1 for r in workshops if _in_period(r.get("createdAt"), start, end)),
        "handedInInPeriod": sum(
            1 for r in workshops if _in_period(r.get("lastHandedInAt"), start, end)
        ),
        "approvedInPeriod": sum(
            1 for r in workshops if _in_period(r.get("approvedAt"), start, end)
        ),
        "handedOnInPeriod": sum(
            1 for r in workshops if _in_period(r.get("handedOnAt"), start, end)
        ),
        "participants": participants,
        "sanctionedAmount": money_text(sanctioned),
        "truncated": bool(truncated),
    }


def empty_counts() -> dict[str, Any]:
    now = datetime(2000, 1, 1, tzinfo=UTC)
    return compute_counts([], now, now, truncated=False)


def by_state(workshops: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """``[{state, workshops, completed, participants}]``, most workshops first, unnamed last."""
    table: dict[str, dict[str, Any]] = {}
    for row in workshops:
        name = str(row.get("state") or "").strip() or "State not given"
        entry = table.setdefault(
            name, {"state": name, "workshops": 0, "completed": 0, "participants": 0}
        )
        entry["workshops"] += 1
        if group_of(row.get("status")) == "completed":
            entry["completed"] += 1
        entry["participants"] += int(row.get("participants") or 0)
    return sorted(
        table.values(),
        key=lambda e: (e["state"] == "State not given", -e["workshops"], e["state"].lower()),
    )


# --------------------------------------------------------------------------------------
# Sheets
# --------------------------------------------------------------------------------------


def _summary_sheet(
    data: dict[str, Any], counts: dict[str, Any], states: list[dict[str, Any]]
) -> dict[str, Any]:
    snapshot = _aware(data.get("snapshotAt"))
    label = data.get("schemeLabel") or scheme_label(data.get("scheme"))
    rows: list[list[Any]] = [
        ["Period", data.get("periodLabel") or period_label(data.get("period", ""))],
        ["Scheme", label],
        ["Scope", f"Every workshop under {label} on the platform"],
        ["Snapshot taken", snapshot.isoformat(timespec="minutes") if snapshot else ""],
        ["Revision", data.get("revision") or 1],
    ]
    if data.get("monthToDate"):
        rows.append(
            ["Month to date", "This month is not over; the figures cover it up to the snapshot."]
        )
    rows += [
        [None, None],
        ["Standing now", None],
        ["Workshops", counts["workshops"]],
        ["Newly registered", counts["registered"]],
        ["Ongoing", counts["ongoing"]],
        ["Completed", counts["completed"]],
        ["Artisans on these workshops", counts["participants"]],
        ["Sanctioned (₹)", indian_number(counts["sanctionedAmount"])],
        [None, None],
        ["During the period", None],
        ["Registered", counts["createdInPeriod"]],
        ["Last handed in", counts["handedInInPeriod"]],
        ["Approved", counts["approvedInPeriod"]],
        ["Handed on to the office", counts["handedOnInPeriod"]],
    ]
    plan = data.get("annualPlan") or []
    if data.get("planYearLabel"):
        promoted = sum(1 for entry in plan if entry.get("designWorkshopId"))
        rows += [
            [None, None],
            [f"Annual plan {data['planYearLabel']}", None],
            ["Planned", len(plan)],
            ["Opened as workshops", promoted],
        ]
    rows += [[None, None], ["By state", "Workshops", "Completed", "Artisans"]]
    rows += [[e["state"], e["workshops"], e["completed"], e["participants"]] for e in states]
    rows += [[None, None], ["Data protection", DATA_PROTECTION_NOTE]]
    rows.append(
        [
            "Snapshot",
            "Figures are as the platform stood when the snapshot was taken, not as of the period's end.",
        ]
    )
    if counts.get("truncated"):
        rows.append(
            [
                "Truncated",
                f"Capped at {data.get('cap')} workshops — the scheme has more. Counts cover the rows in this file.",
            ]
        )
    width = 4
    return {
        "name": "Summary",
        "color": _COLORS["summary"],
        "columns": ["Item", "Value", "", ""],
        "rows": [row + [None] * (width - len(row)) for row in rows],
    }


def _workshop_sheet(
    workshops: list[dict[str, Any]], progress: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    rows = []
    for row in workshops:
        scored = progress.get(str(row.get("id")))
        rows.append(
            [
                _text(row.get("workshopCode")),
                _text(row.get("title")),
                scheme_label(row.get("workshopKind")) if row.get("workshopKind") else "",
                _text(row.get("craftName")),
                _text(row.get("clusterName")),
                _text(row.get("state")),
                _text(row.get("district")),
                _text(row.get("venue")),
                _day(row.get("startDate")),
                _day(row.get("endDate")),
                _text(row.get("designerName")),
                _text(row.get("status")),
                _text(group_of(row.get("status"))),
                int(row.get("participants") or 0),
                scored.get("stagesComplete") if scored else "not scored",
                scored.get("stagesTotal") if scored else None,
                scored.get("requiredFilled") if scored else None,
                scored.get("requiredTotal") if scored else None,
                scored.get("percent") if scored else None,
                _day(row.get("updatedAt")),
                _text(row.get("scheme")),
                _text(row.get("implementingAgency")),
                _text(row.get("sponsor")),
                _text(row.get("sanctionOrderNo")),
                _day(row.get("sanctionOrderDate")),
                float(_money(row.get("sanctionAmount")))
                if _money(row.get("sanctionAmount")) is not None
                else None,
                _day(row.get("handedOnAt")),
            ]
        )
    return {
        "name": "Workshops",
        "color": _COLORS["workshops"],
        "columns": list(WORKSHOP_COLUMNS),
        "rows": rows,
    }


def _sanction_sheet(workshops: list[dict[str, Any]]) -> dict[str, Any]:
    rows = [
        [
            _text(row.get("sanctionOrderNo")),
            _day(row.get("sanctionOrderDate")),
            float(_money(row.get("sanctionAmount")) or 0),
            _text(row.get("designerName")),
            _text(row.get("workshopCode")),
            _text(row.get("title")),
        ]
        for row in workshops
        if row.get("sanctionOrderNo")
    ]
    rows.sort(key=lambda r: (r[1], r[0]))
    return {
        "name": "Sanction orders",
        "color": _COLORS["sanctions"],
        "columns": [
            "Sanction number",
            "Date",
            "Amount (₹)",
            "Designer",
            "Workshop code",
            "Workshop",
        ],
        "rows": rows,
    }


def _plan_sheet(
    plan: list[dict[str, Any]], codes: dict[str, str], year_label: str
) -> dict[str, Any]:
    rows = [
        [
            year_label,
            _text(entry.get("workshopNo")),
            _text(entry.get("plannedTitle")),
            _text(entry.get("state")),
            _text(entry.get("district")),
            _day(entry.get("plannedStartDate")),
            _day(entry.get("plannedEndDate")),
            _text(entry.get("implementingAgency")),
            "Yes" if entry.get("designWorkshopId") else "No",
            codes.get(str(entry.get("designWorkshopId") or ""), ""),
        ]
        for entry in plan
    ]
    return {
        "name": "Annual plan",
        "color": _COLORS["plan"],
        "columns": [
            "Plan year",
            "Workshop no.",
            "Planned title",
            "State",
            "District",
            "Planned start",
            "Planned end",
            "Implementing agency",
            "Opened",
            "Workshop code",
        ],
        "rows": rows,
    }


def _inspection_sheet(
    workshops: list[dict[str, Any]], feedback: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    rows = []
    for row in workshops:
        tally = feedback.get(str(row.get("id")))
        if not tally and not int(row.get("submissionRound") or 0):
            continue
        tally = tally or {}
        rows.append(
            [
                _text(row.get("workshopCode")),
                _text(row.get("title")),
                int(row.get("submissionRound") or 0),
                int(tally.get("notes") or 0),
                int(tally.get("sentBack") or 0),
                _day(tally.get("lastAt")),
            ]
        )
    return {
        "name": "Inspections",
        "color": _COLORS["inspections"],
        "columns": [
            "Workshop code",
            "Workshop",
            "Times handed in",
            "Notes filed",
            "Sent back",
            "Last note",
        ],
        "rows": rows,
    }


def _handon_sheet(
    workshops: list[dict[str, Any]], exports: dict[str, dict[str, Any]]
) -> dict[str, Any]:
    rows = []
    for row in workshops:
        if not row.get("handedOnAt"):
            continue
        export = exports.get(str(row.get("handedOnExportId") or "")) or {}
        rows.append(
            [
                _text(row.get("workshopCode")),
                _text(row.get("title")),
                _day(row.get("handedOnAt")),
                _text(row.get("handedOnTo")),
                _text(export.get("fileName")),
                _text(export.get("format")).upper(),
                _day(export.get("generatedAt")),
                _text(export.get("checksumSha256")),
            ]
        )
    return {
        "name": "Report hand-ons",
        "color": _COLORS["handons"],
        "columns": [
            "Workshop code",
            "Workshop",
            "Handed on",
            "To",
            "File",
            "Format",
            "Generated",
            "SHA-256",
        ],
        "rows": rows,
    }


def build_sheets(
    data: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, Any], list[dict[str, Any]]]:
    """Every sheet of one pack, Summary first, each scrubbed. Returns ``(sheets, counts, by_state)``.

    ``data`` keys: ``period``, ``periodLabel``, ``scheme``, ``schemeLabel``, ``snapshotAt``,
    ``monthToDate``, ``revision``, ``truncated``, ``cap``, ``periodStart``/``periodEnd`` (UTC),
    ``workshops`` (flat dicts, soft-deleted already excluded by the reader — and excluded here again),
    ``progress`` (by workshop id), ``feedback`` (by workshop id), ``exports`` (by export id),
    ``annualPlan`` and ``planYearLabel``.
    """
    workshops = [row for row in data.get("workshops") or [] if not row.get("deletedAt")]
    counts = compute_counts(
        workshops, data["periodStart"], data["periodEnd"], truncated=bool(data.get("truncated"))
    )
    states = by_state(workshops)
    codes = {str(row.get("id")): _text(row.get("workshopCode")) for row in workshops}
    sheets = [
        _summary_sheet(data, counts, states),
        _workshop_sheet(workshops, data.get("progress") or {}),
        _sanction_sheet(workshops),
        _plan_sheet(data.get("annualPlan") or [], codes, data.get("planYearLabel") or ""),
        _inspection_sheet(workshops, data.get("feedback") or {}),
        _handon_sheet(workshops, data.get("exports") or {}),
    ]
    return [scrub_sheet(sheet) for sheet in sheets], counts, states


def all_cells(sheets: Iterable[dict[str, Any]]) -> Iterable[Any]:
    """Every header and cell, for the tests' "nothing like this anywhere" sweeps."""
    for sheet in sheets:
        yield sheet.get("name")
        yield from sheet.get("columns") or []
        for row in sheet.get("rows") or []:
            yield from row


def files_expire_on(finished_at: datetime | None, days: int) -> str | None:
    moment = _aware(finished_at)
    return (moment + timedelta(days=days)).date().isoformat() if moment else None
