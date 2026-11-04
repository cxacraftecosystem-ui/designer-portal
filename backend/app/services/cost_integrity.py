from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from app.services.market_analysis import as_number
from app.services.text_format import by_count

COST_HEADS = (
    "materialCost",
    "labourCost",
    "packagingCost",
    "finishingCost",
    "transportCost",
    "overheadCost",
)

_COST_HEAD_LABELS = {
    "materialCost": "Material cost",
    "labourCost": "Labour cost",
    "packagingCost": "Packaging",
    "finishingCost": "Finishing",
    "transportCost": "Transport",
    "overheadCost": "Overhead",
}

TOLERANCE_RUPEES = 1.00

MARGIN_TOLERANCE_POINTS = 1.0


def _money(value: Any) -> float | None:
    return as_number(value)


def _lines(count: int, noun: str) -> str:
    return f"{count} {noun} line adds" if count == 1 else f"{count} {noun} lines add"


def _rupees(value: float) -> str:
    return f"₹{value:,.2f}"



@dataclass(frozen=True, slots=True)
class LineRollUp:
    kind: str
    count: int = 0
    readable: int = 0
    total: float = 0.0
    unreadable: tuple[str, ...] = ()


def roll_up(kind: str, lines: list[dict[str, Any]]) -> LineRollUp:
    label_key = "item" if kind == "MATERIAL" else "task"
    total = 0.0
    readable = 0
    unreadable: list[str] = []
    for line in lines:
        amount = _money(line.get("amount"))
        if amount is None:
            unreadable.append(str(line.get(label_key) or "").strip() or "an unnamed line")
            continue
        total += amount
        readable += 1
    return LineRollUp(
        kind=kind, count=len(lines), readable=readable, total=total, unreadable=tuple(unreadable)
    )



@dataclass(frozen=True, slots=True)
class Check:
    key: str
    label: str
    unit: str
    declared: float | None
    computed: float | None
    difference: float | None
    verdict: str
    message: str
    line_count: int = 0

    @property
    def is_finding(self) -> bool:
        return self.verdict == "MISMATCH"


def _compare(declared: float | None, computed: float | None, tolerance: float) -> str:
    if declared is None or computed is None:
        return "NOT_DECLARED"
    return "AGREES" if abs(declared - computed) <= tolerance else "MISMATCH"


def check_subtotal(
    sheet_label: str, key: str, noun: str, declared_raw: Any, rolled: LineRollUp
) -> Check:
    declared = _money(declared_raw)

    if rolled.count == 0:
        if declared is None:
            message = f"{sheet_label}: no {noun} lines are recorded and no {noun} cost is declared."
        else:
            message = (
                f"{sheet_label}: {_rupees(declared)} is declared as {noun} cost and there "
                f"are no {noun} lines to check it against. An un-itemised subtotal is not "
                f"an error — it simply cannot be verified from this stage."
            )
        return Check(key, sheet_label, "INR", declared, None, None, "NOT_ITEMISED", message)

    if rolled.unreadable:
        named = ", ".join(rolled.unreadable[:3])
        message = (
            f"{sheet_label}: {len(rolled.unreadable)} of {rolled.count} {noun} lines have "
            f"no readable amount ({named}), so the lines cannot be totalled and the "
            f"declared subtotal cannot be checked. Reopening those lines and saving them "
            f"will recompute the Amount column."
        )
        return Check(
            key, sheet_label, "INR", declared, None, None, "INCOMPLETE", message, rolled.count
        )

    if declared is None:
        message = (
            f"{sheet_label}: the {rolled.count} {noun} {by_count(rolled.count, 'line adds', 'lines add')} up to "
            f"{_rupees(rolled.total)}; no {noun} cost is declared on the sheet."
        )
        return Check(
            key, sheet_label, "INR", None, rolled.total, None, "NOT_DECLARED", message, rolled.count
        )

    difference = declared - rolled.total
    verdict = _compare(declared, rolled.total, TOLERANCE_RUPEES)
    if verdict == "AGREES":
        message = (
            f"{sheet_label}: the {rolled.count} {noun} {by_count(rolled.count, 'line adds', 'lines add')} up to "
            f"{_rupees(rolled.total)}, which is what the sheet declares."
        )
    else:
        direction = "less" if difference < 0 else "more"
        message = (
            f"{sheet_label}: the {rolled.count} {noun} {by_count(rolled.count, 'line adds', 'lines add')} up to "
            f"{_rupees(rolled.total)}, but the sheet declares {_rupees(declared)} — "
            f"{_rupees(abs(difference))} {direction} than the lines account for."
        )
    return Check(
        key, sheet_label, "INR", declared, rolled.total, difference, verdict, message, rolled.count
    )


def sum_cost_heads(sheet: dict[str, Any]) -> tuple[float | None, tuple[str, ...]]:
    total = 0.0
    seen = False
    unreadable: list[str] = []
    for key in COST_HEADS:
        raw = sheet.get(key)
        if raw is None or raw == "":
            continue
        value = _money(raw)
        if value is None:
            unreadable.append(_COST_HEAD_LABELS[key])
            continue
        total += value
        seen = True
    return (total if seen else None), tuple(unreadable)


def check_total_cost(sheet_label: str, sheet: dict[str, Any]) -> Check:
    computed, unreadable = sum_cost_heads(sheet)
    declared = _money(sheet.get("totalCost"))

    if unreadable:
        message = (
            f"{sheet_label}: {', '.join(unreadable)} could not be read as a number, so the "
            f"total cost cannot be checked against the cost heads."
        )
        return Check("totalCost", sheet_label, "INR", declared, None, None, "INCOMPLETE", message)

    if computed is None:
        message = (
            f"{sheet_label}: none of the six cost heads is filled in, so there is no total "
            f"to check."
        )
        return Check("totalCost", sheet_label, "INR", declared, None, None, "NOT_ITEMISED", message)

    if declared is None:
        message = (
            f"{sheet_label}: the cost heads add up to {_rupees(computed)}; no total cost is "
            f"stored on the sheet."
        )
        return Check("totalCost", sheet_label, "INR", None, computed, None, "NOT_DECLARED", message)

    difference = declared - computed
    verdict = _compare(declared, computed, TOLERANCE_RUPEES)
    if verdict == "AGREES":
        message = (
            f"{sheet_label}: the stored total cost {_rupees(declared)} matches the six cost heads."
        )
    else:
        message = (
            f"{sheet_label}: the six cost heads add up to {_rupees(computed)}, but the "
            f"stored total cost is {_rupees(declared)}. Total cost is a derived field, so "
            f"the stored value is out of date — reopening the sheet and saving it will "
            f"recompute it."
        )
    return Check("totalCost", sheet_label, "INR", declared, computed, difference, verdict, message)



@dataclass(frozen=True, slots=True)
class Margin:
    total_cost: float | None
    expected_price: float | None
    amount: float | None
    percent: float | None
    verdict: str
    message: str

    @property
    def is_finding(self) -> bool:
        return self.verdict == "BELOW_COST"


def compute_margin(sheet_label: str, sheet: dict[str, Any]) -> Margin:
    computed, unreadable = sum_cost_heads(sheet)
    cost = computed if not unreadable else None
    if cost is None:
        cost = _money(sheet.get("totalCost"))
    price = _money(sheet.get("expectedPrice"))

    if cost is None or price is None or cost <= 0:
        if price is None and cost is None:
            reason = "neither an expected price nor any cost is recorded"
        elif price is None:
            reason = "no expected price is recorded"
        elif cost is None:
            reason = "no cost is recorded"
        else:
            reason = "the total cost is zero"
        return Margin(
            cost,
            price,
            None,
            None,
            "NOT_COMPUTABLE",
            f"{sheet_label}: no margin can be implied — {reason}.",
        )

    amount = price - cost
    percent = amount / cost * 100.0

    if abs(amount) <= TOLERANCE_RUPEES:
        return Margin(
            cost,
            price,
            amount,
            percent,
            "AT_COST",
            f"{sheet_label}: the expected price {_rupees(price)} is the same as the "
            f"total cost {_rupees(cost)} — this product earns nothing as priced.",
        )
    if amount < 0:
        return Margin(
            cost,
            price,
            amount,
            percent,
            "BELOW_COST",
            f"{sheet_label}: the expected price {_rupees(price)} is below the total cost "
            f"{_rupees(cost)} — a loss of {_rupees(abs(amount))} on every piece sold.",
        )
    return Margin(
        cost,
        price,
        amount,
        percent,
        "COMPUTED",
        f"{sheet_label}: an expected price of {_rupees(price)} against a total cost of "
        f"{_rupees(cost)} implies a margin of {_rupees(amount)}, or {percent:.1f}% on "
        f"cost.",
    )


def check_declared_margin(sheet_label: str, sheet: dict[str, Any], margin: Margin) -> Check:
    declared = as_number(sheet.get("marginPercent"))

    if margin.percent is None:
        message = (
            f"{sheet_label}: there is no implied margin to check a declared margin against."
            if declared is None
            else f"{sheet_label}: a margin of {declared:.1f}% is declared, but the sheet has no "
            f"price and cost to imply a margin from, so it cannot be checked."
        )
        return Check(
            "marginPercent", sheet_label, "PCT", declared, None, None, "NOT_COMPUTABLE", message
        )

    if declared is None:
        return Check(
            "marginPercent",
            sheet_label,
            "PCT",
            None,
            margin.percent,
            None,
            "NOT_DECLARED",
            f"{sheet_label}: the sheet's figures imply a margin of {margin.percent:.1f}% "
            f"on cost; no margin is declared.",
        )

    difference = declared - margin.percent
    verdict = _compare(declared, margin.percent, MARGIN_TOLERANCE_POINTS)
    if verdict == "AGREES":
        message = (
            f"{sheet_label}: the declared margin of {declared:.1f}% matches the "
            f"{margin.percent:.1f}% implied by the price and cost."
        )
    else:
        message = (
            f"{sheet_label}: a margin of {declared:.1f}% is declared, but the expected "
            f"price and total cost on this sheet imply {margin.percent:.1f}% on cost."
        )
    return Check(
        "marginPercent", sheet_label, "PCT", declared, margin.percent, difference, verdict, message
    )



@dataclass(frozen=True, slots=True)
class OrphanLine:
    kind: str
    label: str
    ref: str
    amount: float | None



@dataclass(frozen=True, slots=True)
class SheetFindings:
    entry_id: str
    label: str
    checks: tuple[Check, ...]
    material: LineRollUp
    labour: LineRollUp
    margin: Margin

    @property
    def mismatches(self) -> list[Check]:
        return [c for c in self.checks if c.is_finding]


@dataclass(frozen=True, slots=True)
class CostFindings:
    sheets: tuple[SheetFindings, ...] = ()
    orphans: tuple[OrphanLine, ...] = ()
    cautions: list[str] = field(default_factory=list)

    @property
    def sheet_count(self) -> int:
        return len(self.sheets)

    @property
    def mismatches(self) -> list[Check]:
        return [c for s in self.sheets for c in s.mismatches]

    @property
    def warnings(self) -> list[str]:
        out: list[str] = []
        for sheet in self.sheets:
            out.extend(c.message for c in sheet.checks if c.is_finding)
            if sheet.margin.is_finding:
                out.append(sheet.margin.message)
        return out


def _sheet_label(sheet: dict[str, Any], index: int, labels: dict[str, str]) -> str:
    ref = sheet.get("productRef")
    if isinstance(ref, str) and ref:
        label = labels.get(ref, "")
        if label:
            return label
    return f"Cost sheet {index}"


def _orphan_caution(noun: str, orphans: list[OrphanLine]) -> str:
    named = ", ".join(o.label for o in orphans[:3] if o.label)
    amounts = [o.amount for o in orphans if o.amount is not None]
    total = (
        f" Their amounts come to {_rupees(sum(amounts))}, counted in no subtotal."
        if amounts
        else " None of them has a readable amount."
    )
    plural = "line is" if len(orphans) == 1 else "lines are"
    return (
        f"{len(orphans)} {noun} {plural} not attached to any cost sheet in this stage"
        f"{f' ({named})' if named else ''}. The sheet they named may have been deleted after "
        f"they were recorded.{total}"
    )


def analyse_cost_integrity(
    *,
    sheets: list[dict[str, Any]],
    material_lines: list[dict[str, Any]],
    labour_lines: list[dict[str, Any]],
    labels: dict[str, str] | None = None,
) -> CostFindings:
    labels = labels or {}

    material_by_sheet: dict[str, list[dict[str, Any]]] = {}
    labour_by_sheet: dict[str, list[dict[str, Any]]] = {}
    for rows, bucket in ((material_lines, material_by_sheet), (labour_lines, labour_by_sheet)):
        for row in rows:
            ref = row.get("costSheetRef")
            bucket.setdefault(ref if isinstance(ref, str) and ref else "", []).append(row)

    found: list[SheetFindings] = []
    for index, sheet in enumerate(sheets, start=1):
        entry_id = sheet.get("_entryId")
        entry_id = entry_id if isinstance(entry_id, str) and entry_id else ""
        label = _sheet_label(sheet, index, labels)

        material = roll_up("MATERIAL", material_by_sheet.pop(entry_id, []) if entry_id else [])
        labour = roll_up("LABOUR", labour_by_sheet.pop(entry_id, []) if entry_id else [])

        margin = compute_margin(label, sheet)
        checks = (
            check_subtotal(label, "materialCost", "material", sheet.get("materialCost"), material),
            check_subtotal(label, "labourCost", "labour", sheet.get("labourCost"), labour),
            check_total_cost(label, sheet),
            check_declared_margin(label, sheet, margin),
        )
        found.append(SheetFindings(entry_id, label, checks, material, labour, margin))

    orphans: list[OrphanLine] = []
    cautions: list[str] = []
    for kind, noun, bucket in (
        ("MATERIAL", "material", material_by_sheet),
        ("LABOUR", "labour", labour_by_sheet),
    ):
        label_key = "item" if kind == "MATERIAL" else "task"
        of_kind = [
            OrphanLine(
                kind=kind,
                label=str(row.get(label_key) or "").strip() or "an unnamed line",
                ref=str(row.get("costSheetRef") or ""),
                amount=_money(row.get("amount")),
            )
            for rows in bucket.values()
            for row in rows
        ]
        if of_kind:
            orphans.extend(of_kind)
            cautions.append(_orphan_caution(f"{noun} cost", of_kind))

    return CostFindings(sheets=tuple(found), orphans=tuple(orphans), cautions=cautions)



def _round(value: float | None, places: int = 2) -> float | None:
    return None if value is None else round(value, places)


def cost_findings_payload(findings: CostFindings) -> dict[str, Any]:
    return {
        "sheetCount": findings.sheet_count,
        "warnings": findings.warnings,
        "cautions": list(findings.cautions),
        "sheets": [
            {
                "entryId": sheet.entry_id,
                "label": sheet.label,
                "materialLines": sheet.material.count,
                "labourLines": sheet.labour.count,
                "checks": [
                    {
                        "key": check.key,
                        "unit": check.unit,
                        "declared": _round(check.declared),
                        "computed": _round(check.computed),
                        "difference": _round(check.difference),
                        "verdict": check.verdict,
                        "message": check.message,
                        "lineCount": check.line_count,
                    }
                    for check in sheet.checks
                ],
                "margin": {
                    "totalCost": _round(sheet.margin.total_cost),
                    "expectedPrice": _round(sheet.margin.expected_price),
                    "amount": _round(sheet.margin.amount),
                    "percent": _round(sheet.margin.percent, 1),
                    "verdict": sheet.margin.verdict,
                    "message": sheet.margin.message,
                },
            }
            for sheet in findings.sheets
        ],
        "orphans": [
            {
                "kind": orphan.kind,
                "label": orphan.label,
                "ref": orphan.ref,
                "amount": _round(orphan.amount),
            }
            for orphan in findings.orphans
        ],
    }
