"""Stage 17's cost sheets checked against their own line items.

THE PROBLEM THIS SOLVES. A cost sheet carries a header — material cost, labour cost, six heads and
a total — and, underneath it, the material and labour LINES those heads are supposed to summarise.
Nothing ever compared the two. A designer hand-totals six material lines, retypes ₹1,650.00 into
`materialCost` as ₹1,560.00, and the sheet now contradicts itself; or the sheet was right and a
seventh line was appended a week later and the header never moved. Either way the header is what
the report prints into a document submitted to a government office, and the lines that disprove it
sit two tables below on the same page.

The registry cannot express this check, and that is not an oversight to be routed around.
:func:`app.services.stage_schema.derive_value` takes ``(spec, row)`` and reads only
``row.get(key)`` for the keys in ``spec.derived_from`` — the SAME row's other fields. A roll-up
across a sibling collection is out of its reach by construction, which is why `stage_definitions`
says so in a comment on `materialCost` rather than declaring a derivation there. So the arithmetic
lives here instead.

THIS PRODUCES A FINDING. IT DOES NOT CORRECT ANYTHING. The designer's typed subtotal stays exactly
as typed, and every function here is read-only over dicts. Overwriting the header with the computed
figure would be a worse bug than the one it fixes: a subtotal can legitimately differ from its
lines — a rounding, a cost carried but not itemised, a rate renegotiated after the lines were
entered — and the designer was in the room when it was decided. What they did not have was the
arithmetic in front of them. Now they do, with both numbers named.

THREE PROPERTIES, SHARED WITH `market_analysis.py` AND LOAD-BEARING FOR THE SAME REASONS.

**It is pure.** No database, no network. It takes the stored ``data`` dicts and returns findings,
which is what lets it be ported to `frontend/` and to the handset and run with no signal at all —
the check has to fire while the designer is still standing in the workshop, not after a sync.

**It refuses to conclude when it cannot.** A sheet with no line items is NOT a contradiction, it is
an un-itemised sheet, and it is reported as ``NOT_ITEMISED`` rather than as an error. A sheet whose
lines cannot all be read is reported as ``INCOMPLETE`` rather than totalled from the readable half —
a partial total compared against a correct header manufactures a mismatch that is not there, and a
check that accuses correct sheets is a check that gets ignored, taking the true findings with it.

**It never loses a row.** A line whose ``costSheetRef`` names no sheet in this stage is an ORPHAN
and is reported as one, with its amount, because it is fieldwork somebody did and money somebody
spent — and because its absence from every subtotal may be the very discrepancy being looked at.
`report_builder._parent_groups` already takes this position for the printed report ("filing it under
a parent it does not belong to would be a fabrication, and dropping it would delete fieldwork");
this module matches it.

MATCHING A LINE TO ITS SHEET follows the convention `report_builder` established: the child's REF
field — ``costSheetRef``, the one REF on the line whose ``ref_model`` is ``DwCostSheet`` — holds the
parent's ``_entryId``. That key is injected by the caller from the row's database id
(`_stages_payload`, `_workshop_data`); it is not part of the stored ``data``, so a caller that
forgets it gets sheets with no lines rather than a wrong answer.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

# ONE DEFINITION OF HOW MONEY IS READ OFF A STORED ROW, imported rather than copied. MONEY is a
# fixed-2 string that may carry grouping commas, and the rejection of NaN/Infinity in there is
# load-bearing here for the same reason it is there: a non-finite entering a subtotal prints
# "₹ nan" into a document that has already been submitted by the time anybody notices. Both
# modules are pure, so this costs nothing and a second copy would drift.
from app.services.market_analysis import as_number

#: The six heads `totalCost` is declared to SUM over, in `stage_definitions.STAGE_17`.
#:
#: Re-stated here rather than read out of the registry so this module stays free of it and the
#: TypeScript and Kotlin ports have something to mirror. `test_the_six_cost_heads_match_the_
#: registry_declaration` pins the two together, so a head added to the registry fails a test rather
#: than being silently left out of the roll-up.
COST_HEADS = (
    "materialCost", "labourCost", "packagingCost", "finishingCost", "transportCost", "overheadCost",
)

#: The heads by the label the designer sees, so a message can say which one it could not read.
_COST_HEAD_LABELS = {
    "materialCost": "Material cost",
    "labourCost": "Labour cost",
    "packagingCost": "Packaging",
    "finishingCost": "Finishing",
    "transportCost": "Transport",
    "overheadCost": "Overhead",
}

#: How far a declared subtotal may sit from its lines before it is called a contradiction: ONE
#: RUPEE.
#:
#: THE REASONING, because the number is the whole product. There is no float drift to absorb —
#: MONEY is stored as a fixed-2 string and every line `amount` is already rounded to the paisa by
#: `derive_value`, so a hundred lines accumulate at most half a rupee of rounding between them. The
#: tolerance is not there for the machine's arithmetic. It is there for the designer's: someone who
#: totals ₹1,649.50 of yarn and writes ₹1,650 has rounded to the rupee, which is what a cost sheet
#: is normally quoted in, and that is not a sheet contradicting itself.
#:
#: One rupee is also comfortably below every failure this exists to catch. A transposed digit
#: (₹1,650 as ₹1,560) is ₹90; a misplaced decimal is ten times the sheet; a line entered and never
#: added in is that line's whole amount — tens or hundreds of rupees. Nothing real lands between ₹1
#: and ₹10. Setting the tolerance at a paisa instead would flag every rounded-to-the-rupee subtotal
#: on every correct sheet in the workshop, and a warning that fires on correct data is a warning
#: designers learn to dismiss — which would cost the real findings underneath it.
TOLERANCE_RUPEES = 1.00

#: How far a declared `marginPercent` may sit from the margin the sheet's own figures imply: ONE
#: PERCENTAGE POINT. A designer who computes 25.4% and types 25 has rounded; two points apart is a
#: different claim about the same product. The field is stored as a plain number, so the same
#: "correct data must not raise a warning" argument as above applies.
MARGIN_TOLERANCE_POINTS = 1.0


def _money(value: Any) -> float | None:
    """A stored MONEY value as a finite float, or None. Blank is None, not zero."""
    return as_number(value)


def _rupees(value: float) -> str:
    """A figure as a message prints it.

    TO THE PAISA, deliberately, unlike `market_analysis`'s whole-rupee price bands. The tolerance
    here is a rupee, so a message that rounds to the rupee could report a ₹1.40 discrepancy as
    "₹1,650 against ₹1,650" and read as though the check had malfunctioned.
    """
    return f"₹{value:,.2f}"


# --------------------------------------------------------------------------------------
# What the lines under one sheet come to
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class LineRollUp:
    """What one sheet's child lines of a single kind add up to, and what could not be read.

    `unreadable` is separate from `count` on purpose. A total computed from the readable lines
    alone looks exactly like a total computed from all of them, and the difference is the
    difference between a finding and a false accusation.
    """

    kind: str                                   # MATERIAL | LABOUR
    count: int = 0
    readable: int = 0
    total: float = 0.0
    unreadable: tuple[str, ...] = ()


def roll_up(kind: str, lines: list[dict[str, Any]]) -> LineRollUp:
    """Total the `amount` column of `lines`, keeping the unreadable ones visible.

    The STORED `amount` is used rather than recomputed from quantity × rate. It is the registry's
    own PRODUCT derivation, written on save by all three clients, and re-deriving it here would put
    a second implementation of the same arithmetic in the codebase — the drift then shows up as a
    roll-up that disagrees with the Amount column printed directly above it in the report.
    A line whose stored amount is missing or unreadable is therefore counted and NAMED, never
    quietly skipped, so the caller can decline to conclude.
    """
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
    return LineRollUp(kind=kind, count=len(lines), readable=readable, total=total,
                      unreadable=tuple(unreadable))


# --------------------------------------------------------------------------------------
# One declared figure against what the sheet's own rows say it should be
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Check:
    """One comparison between a figure a designer typed and the arithmetic underneath it.

    `verdict` is one of:
      AGREES          within tolerance of what the rows come to
      MISMATCH        outside it — the one verdict that becomes a warning
      NOT_ITEMISED    there are no rows to roll up. NOT a criticism of the sheet
      NOT_DECLARED    nothing was typed to compare against; the computed figure is reported anyway
      INCOMPLETE      rows exist but could not all be read, so no total was formed
      NOT_COMPUTABLE  the computed side cannot be formed at all (a margin with no cost)
    """

    key: str
    label: str
    unit: str                                   # INR | PCT
    declared: float | None
    computed: float | None
    difference: float | None                    # declared - computed
    verdict: str
    message: str
    line_count: int = 0

    @property
    def is_finding(self) -> bool:
        return self.verdict == "MISMATCH"


def _compare(declared: float | None, computed: float | None, tolerance: float) -> str:
    """AGREES or MISMATCH for two figures that both exist.

    The comparison is ``<=`` so a difference EXACTLY at the tolerance still agrees — the boundary
    belongs to the designer, not to the warning.
    """
    if declared is None or computed is None:
        return "NOT_DECLARED"
    return "AGREES" if abs(declared - computed) <= tolerance else "MISMATCH"


def check_subtotal(sheet_label: str, key: str, noun: str, declared_raw: Any,
                   rolled: LineRollUp) -> Check:
    """One declared subtotal against the lines it summarises.

    The order of the refusals is the order of the questions. Are there rows at all? Could they all
    be read? Was anything typed to compare them with? Only then is a mismatch a mismatch.
    """
    declared = _money(declared_raw)

    if rolled.count == 0:
        # AN UN-ITEMISED SHEET IS NOT A WRONG SHEET, and this is the case the whole module has to
        # get right. Plenty of sheets are entered as totals — a subcontracted product, a cost
        # carried over from a previous workshop — and reporting every one of them as a
        # contradiction would bury the sheets that really are one.
        if declared is None:
            message = (f"{sheet_label}: no {noun} lines are recorded and no {noun} cost is "
                       f"declared.")
        else:
            message = (f"{sheet_label}: {_rupees(declared)} is declared as {noun} cost and there "
                       f"are no {noun} lines to check it against. An un-itemised subtotal is not "
                       f"an error — it simply cannot be verified from this stage.")
        return Check(key, sheet_label, "INR", declared, None, None, "NOT_ITEMISED", message)

    if rolled.unreadable:
        named = ", ".join(rolled.unreadable[:3])
        message = (f"{sheet_label}: {len(rolled.unreadable)} of {rolled.count} {noun} lines have "
                   f"no readable amount ({named}), so the lines cannot be totalled and the "
                   f"declared subtotal cannot be checked. Reopening those lines and saving them "
                   f"will recompute the Amount column.")
        return Check(key, sheet_label, "INR", declared, None, None, "INCOMPLETE", message,
                     rolled.count)

    if declared is None:
        message = (f"{sheet_label}: the {rolled.count} {noun} line(s) add up to "
                   f"{_rupees(rolled.total)}; no {noun} cost is declared on the sheet.")
        return Check(key, sheet_label, "INR", None, rolled.total, None, "NOT_DECLARED", message,
                     rolled.count)

    difference = declared - rolled.total
    verdict = _compare(declared, rolled.total, TOLERANCE_RUPEES)
    if verdict == "AGREES":
        message = (f"{sheet_label}: the {rolled.count} {noun} line(s) add up to "
                   f"{_rupees(rolled.total)}, which is what the sheet declares.")
    else:
        direction = "less" if difference < 0 else "more"
        message = (f"{sheet_label}: the {rolled.count} {noun} line(s) add up to "
                   f"{_rupees(rolled.total)}, but the sheet declares {_rupees(declared)} — "
                   f"{_rupees(abs(difference))} {direction} than the lines account for.")
    return Check(key, sheet_label, "INR", declared, rolled.total, difference, verdict, message,
                 rolled.count)


def sum_cost_heads(sheet: dict[str, Any]) -> tuple[float | None, tuple[str, ...]]:
    """The six heads added up, and the labels of any that could not be read.

    MIRRORS `derive_value`'s SUM EXACTLY, and the two rules that make it non-obvious are the reason
    it is spelled out rather than approximated. A blank head counts as ZERO — four of the six are
    optional and requiring all six would leave `totalCost` empty for most workshops. But a sheet
    with NONE of them filled has no total at all rather than a total of zero, because "₹ 0.00" in a
    cost sheet a ministry reads is a claim and not a blank.
    """
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
    """The stored `totalCost` against the six heads it is derived from.

    UNLIKE THE SUBTOTALS, THIS ONE IS A DERIVED FIELD, so a mismatch means something narrower and
    more actionable: the stored value is STALE. It was computed correctly from what the sheet held
    at the time and a head has moved since — most often on a row written by a client running an
    older copy of the registry, or edited while offline. The fix is mechanical, and the message
    says so, which is not true of a subtotal mismatch where only the designer can know the answer.
    """
    computed, unreadable = sum_cost_heads(sheet)
    declared = _money(sheet.get("totalCost"))

    if unreadable:
        message = (f"{sheet_label}: {', '.join(unreadable)} could not be read as a number, so the "
                   f"total cost cannot be checked against the cost heads.")
        return Check("totalCost", sheet_label, "INR", declared, None, None, "INCOMPLETE", message)

    if computed is None:
        message = (f"{sheet_label}: none of the six cost heads is filled in, so there is no total "
                   f"to check.")
        return Check("totalCost", sheet_label, "INR", declared, None, None, "NOT_ITEMISED", message)

    if declared is None:
        message = (f"{sheet_label}: the cost heads add up to {_rupees(computed)}; no total cost is "
                   f"stored on the sheet.")
        return Check("totalCost", sheet_label, "INR", None, computed, None, "NOT_DECLARED", message)

    difference = declared - computed
    verdict = _compare(declared, computed, TOLERANCE_RUPEES)
    if verdict == "AGREES":
        message = (f"{sheet_label}: the stored total cost {_rupees(declared)} matches the six cost "
                   f"heads.")
    else:
        message = (f"{sheet_label}: the six cost heads add up to {_rupees(computed)}, but the "
                   f"stored total cost is {_rupees(declared)}. Total cost is a derived field, so "
                   f"the stored value is out of date — reopening the sheet and saving it will "
                   f"recompute it.")
    return Check("totalCost", sheet_label, "INR", declared, computed, difference, verdict, message)


# --------------------------------------------------------------------------------------
# Margin
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Margin:
    """What the sheet's own figures imply about what it earns.

    `percent` is margin ON COST — ``(price - cost) / cost`` — and saying which convention is meant
    is not pedantry: the same sheet is 25% on cost and 20% on price, and the registry's own
    `marginPercent` allows up to 500, which only a markup on cost can reach.

    `verdict` is one of:
      COMPUTED        a positive margin, reported
      AT_COST         price and cost agree within the tolerance — nothing is earned
      BELOW_COST      the price is under the cost. A real and common data-entry error
      NOT_COMPUTABLE  no price, no cost, or a cost of zero to divide by
    """

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
    """The margin between `expectedPrice` and the sheet's cost.

    COMPUTED FROM THE SIX HEADS RATHER THAN THE STORED `totalCost`, falling back to the stored
    value only when no head is filled. If the stored total is stale — which `check_total_cost` may
    have just reported — a margin taken from it would be a second wrong number derived from the
    first, and the two findings would contradict each other on the same sheet.
    """
    computed, unreadable = sum_cost_heads(sheet)
    cost = computed if not unreadable else None
    if cost is None:
        cost = _money(sheet.get("totalCost"))
    price = _money(sheet.get("expectedPrice"))

    if cost is None or price is None or cost <= 0:
        # A cost of zero is not a free product, it is a sheet nobody has costed yet, and dividing
        # by it would either raise or report an infinite margin into a report.
        if price is None and cost is None:
            reason = "neither an expected price nor any cost is recorded"
        elif price is None:
            reason = "no expected price is recorded"
        elif cost is None:
            reason = "no cost is recorded"
        else:
            reason = "the total cost is zero"
        return Margin(cost, price, None, None, "NOT_COMPUTABLE",
                      f"{sheet_label}: no margin can be implied — {reason}.")

    amount = price - cost
    percent = amount / cost * 100.0

    if abs(amount) <= TOLERANCE_RUPEES:
        return Margin(cost, price, amount, percent, "AT_COST",
                      f"{sheet_label}: the expected price {_rupees(price)} is the same as the "
                      f"total cost {_rupees(cost)} — this product earns nothing as priced.")
    if amount < 0:
        return Margin(cost, price, amount, percent, "BELOW_COST",
                      f"{sheet_label}: the expected price {_rupees(price)} is below the total cost "
                      f"{_rupees(cost)} — a loss of {_rupees(abs(amount))} on every piece sold.")
    return Margin(cost, price, amount, percent, "COMPUTED",
                  f"{sheet_label}: an expected price of {_rupees(price)} against a total cost of "
                  f"{_rupees(cost)} implies a margin of {_rupees(amount)}, or {percent:.1f}% on "
                  f"cost.")


def check_declared_margin(sheet_label: str, sheet: dict[str, Any], margin: Margin) -> Check:
    """The typed `marginPercent` against the margin the sheet's own figures imply.

    The same class of bug as a mistyped subtotal — a number a designer entered by hand that the
    rest of the sheet contradicts — and nothing checked it before either.
    """
    declared = as_number(sheet.get("marginPercent"))

    if margin.percent is None:
        message = (f"{sheet_label}: there is no implied margin to check a declared margin against."
                   if declared is None else
                   f"{sheet_label}: a margin of {declared:.1f}% is declared, but the sheet has no "
                   f"price and cost to imply a margin from, so it cannot be checked.")
        return Check("marginPercent", sheet_label, "PCT", declared, None, None, "NOT_COMPUTABLE",
                     message)

    if declared is None:
        return Check("marginPercent", sheet_label, "PCT", None, margin.percent, None,
                     "NOT_DECLARED",
                     f"{sheet_label}: the sheet's figures imply a margin of {margin.percent:.1f}% "
                     f"on cost; no margin is declared.")

    difference = declared - margin.percent
    verdict = _compare(declared, margin.percent, MARGIN_TOLERANCE_POINTS)
    if verdict == "AGREES":
        message = (f"{sheet_label}: the declared margin of {declared:.1f}% matches the "
                   f"{margin.percent:.1f}% implied by the price and cost.")
    else:
        message = (f"{sheet_label}: a margin of {declared:.1f}% is declared, but the expected "
                   f"price and total cost on this sheet imply {margin.percent:.1f}% on cost.")
    return Check("marginPercent", sheet_label, "PCT", declared, margin.percent, difference,
                 verdict, message)


# --------------------------------------------------------------------------------------
# Orphans
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class OrphanLine:
    """A cost line that belongs to no sheet in this stage.

    `amount` is None when the line's own amount could not be read — the line is still reported,
    because the point of reporting it is that it EXISTS and is in no total.
    """

    kind: str                                   # MATERIAL | LABOUR
    label: str
    ref: str
    amount: float | None


# --------------------------------------------------------------------------------------
# One sheet, and the whole stage
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class SheetFindings:
    """Everything concluded about one cost sheet, including what could not be concluded."""

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
    """Every sheet's findings, the lines that belong to none of them, and the caveats."""

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
        """The findings as sentences, in sheet order.

        DERIVED FROM THE CHECKS rather than accumulated alongside them, so the banner a designer
        reads and the per-field verdicts underneath it cannot disagree about the same sheet.
        """
        out: list[str] = []
        for sheet in self.sheets:
            out.extend(c.message for c in sheet.checks if c.is_finding)
            if sheet.margin.is_finding:
                out.append(sheet.margin.message)
        return out


def _sheet_label(sheet: dict[str, Any], index: int, labels: dict[str, str]) -> str:
    """What a finding calls this sheet.

    The entity's `label_field` is `productRef`, a REF this pure module cannot resolve on its own —
    the products it points at live in stage 13. The caller passes what it has resolved, and the
    fallback is positional, which is the same answer `report_builder._row_label` gives a sheet that
    names no product. A finding a designer cannot trace back to a row is one they cannot act on.
    """
    ref = sheet.get("productRef")
    if isinstance(ref, str) and ref:
        label = labels.get(ref, "")
        if label:
            return label
    return f"Cost sheet {index}"


def _orphan_caution(noun: str, orphans: list[OrphanLine]) -> str:
    """One sentence naming lines that are in no sheet, and the money that is therefore in no total."""
    named = ", ".join(o.label for o in orphans[:3] if o.label)
    amounts = [o.amount for o in orphans if o.amount is not None]
    total = f" Their amounts come to {_rupees(sum(amounts))}, counted in no subtotal." if amounts \
        else " None of them has a readable amount."
    plural = "line is" if len(orphans) == 1 else "lines are"
    return (f"{len(orphans)} {noun} {plural} not attached to any cost sheet in this stage"
            f"{f' ({named})' if named else ''}. The sheet they named may have been deleted after "
            f"they were recorded.{total}")


def analyse_cost_integrity(
    *,
    sheets: list[dict[str, Any]],
    material_lines: list[dict[str, Any]],
    labour_lines: list[dict[str, Any]],
    labels: dict[str, str] | None = None,
) -> CostFindings:
    """Every stage-17 cost sheet checked against its own line items.

    Each argument is the list of stage entry ``data`` dicts exactly as they are stored, with
    ``_entryId`` present on each row — so a caller has nothing to reshape and the browser and the
    handset can pass what they already hold. `labels` maps an entry id to the name it should print
    under, for the `productRef` this module cannot resolve itself; it is optional, and its absence
    costs a readable sheet name, never a wrong verdict.
    """
    labels = labels or {}

    # Bucket the children by the parent id they name, exactly as `report_builder._parent_groups`
    # does: an empty or missing ref goes to "" and is never matched to a sheet, because a sheet
    # that has not synced yet has no `_entryId` either and pairing the two would attach lines to
    # a sheet at random.
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

    # WHATEVER IS LEFT IN THE BUCKETS BELONGS TO NO SHEET. Reported, never dropped: these are lines
    # somebody entered and money somebody spent, and their absence from every subtotal is a
    # candidate explanation for any mismatch above.
    orphans: list[OrphanLine] = []
    cautions: list[str] = []
    for kind, noun, bucket in (("MATERIAL", "material", material_by_sheet),
                               ("LABOUR", "labour", labour_by_sheet)):
        label_key = "item" if kind == "MATERIAL" else "task"
        of_kind = [
            OrphanLine(
                kind=kind,
                label=str(row.get(label_key) or "").strip() or "an unnamed line",
                ref=str(row.get("costSheetRef") or ""),
                amount=_money(row.get("amount")),
            )
            for rows in bucket.values() for row in rows
        ]
        if of_kind:
            orphans.extend(of_kind)
            cautions.append(_orphan_caution(f"{noun} cost", of_kind))

    return CostFindings(sheets=tuple(found), orphans=tuple(orphans), cautions=cautions)


# --------------------------------------------------------------------------------------
# The wire form
# --------------------------------------------------------------------------------------


def _round(value: float | None, places: int = 2) -> float | None:
    return None if value is None else round(value, places)


def cost_findings_payload(findings: CostFindings) -> dict[str, Any]:
    """The wire form, camelCased like every other payload this API returns.

    `warnings` is named at the TOP rather than left to be assembled by each client from the
    per-check verdicts. Three clients reading the same rules three ways is three chances to show a
    designer a clean sheet that this module found a contradiction in.
    """
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
