"""Put back what the `replaceCollections` sweep regression deleted from the flagship workshop.

WHAT WENT WRONG. `replaceCollections` was briefly scoped by the STAGE SPEC rather than by what the
payload actually named, so a caller that sent one entity's rows silently soft-deleted every OTHER
collection of the same stage. The comment above the sweep in `app/services/design_workshops.py`
records the damage; this script is the other half of that fix, because a corrected code path does
not un-delete anything it already deleted. Two sweeps hit the showcase workshop, and their
signature is still legible in the rows:

    2026-08-07 06:28:46.115   6 × prototype   (stage 13)  — one PUT of PROTOTYPE_DEVELOPMENT
    2026-08-07 06:29:28.874   4 × costSheet + 2 × buyerLink (stage 17) — one PUT of COSTING_MARKET_LINKAGE

Both PUTs wrote their own children and swept their siblings in the same request: the 28 material
and 22 labour cost lines were CREATED by the very transaction that deleted the four sheets they
cite. That is why the ids in `costSheetRef` are not stale and not from another workshop — they are
correct, and they name rows that are sitting right there with `deletedAt` set. 92 live child rows
across three stages were orphaned this way, and the report answered exactly as it should: two
tables of 50 rows under "No cost sheet recorded", and three more under "No prototype recorded".

SO THE REPAIR IS A RESTORE, NOT A RECONSTRUCTION. Nothing here invents a cost sheet. The sheets
exist, with their line-item subtotals already agreeing with their lines to the paisa, and the
honest fix is to clear the `deletedAt` a bug wrote — not to create a second set of sheets beside
the first and leave the originals dead.

WHAT THE RESTORE ALONE DOES NOT FIX, and this script does. With the sheets back,
`GET /cost-integrity` can finally read them, and it finds two arithmetic conventions in the stored
data that disagree with the ones the app is built on:

* THE THREE OPTIONAL HEADS ARE DOUBLE-COUNTED. `packagingCost`, `finishingCost` and
  `transportCost` each restate a material line that is already inside `materialCost` — head for
  head, to the paisa, on all four sheets (₹32.00 "Kraft core, printed band, jute tie and GI hang
  tag", ₹12.55 "Finishing agents — soap, softener and rice starch", and so on). `totalCost` is
  declared to SUM all six heads, so summing them charges packaging, finishing and freight twice
  and overstates PT-01's cost by ₹60.55. Each sheet's own `packagingDescription` says as much —
  "all three sit inside the material schedule above and are not added again to the total" — so the
  prose and the line items agree with each other and only the heads are wrong. The heads go,
  because the LINES are the fieldwork: quantity × rate, itemised and traceable, which is what
  stage 17 exists to capture. The figures survive in the lines and in the prose that quotes them.

* `marginPercent` IS STORED ON PRICE, AND THE FIELD MEANS ON COST. `cost_integrity.compute_margin`
  computes (price − cost) / COST — the registry allows up to 500, which only a markup on cost can
  reach — while the stored 55.0 / 54.5 / 56.0 / 56.1 are all (price − cost) / PRICE. Three sheets
  are restated in the convention the field actually has.

ONE SHEET IS DELIBERATELY LEFT DISAGREEING: PT-01, the table runner, keeps its 55.0. It is left
because it is REAL — a designer mixing margin-on-price into a margin-on-cost field is precisely the
class of error this check was built to catch — and because PT-01 is the one sheet whose own
marketing narrative states its convention out loud ("a margin of 55 per cent measured on the
counter price"), so the finding explains itself to whoever reads the report. Nothing is invented to
manufacture it. The alternative — nudging a subtotal a few rupees off its lines so the warning
would be small and tidy — would mean typing a knowingly wrong number into a research record, which
is a worse thing to do to this database than leaving a large true one in it.

    cd backend && ./.venv/Scripts/python.exe scripts/repair_flagship_cost_orphans.py

IDEMPOTENT. The restore only fires on a collection that is ENTIRELY soft-deleted in a SINGLE batch,
which is the sweep's signature and not the shape of a designer removing a row; every repair
computes its target and writes only what differs. A second run reports "nothing to do" and creates
no eighth cost sheet. NOTHING IS EVER HARD-DELETED here, and nothing is deleted at all: the only
`deletedAt` this script writes is `None`.

NOT FOR PRODUCTION, and it refuses to run against one, by the same guard `seed_test_accounts.py`
uses. It rewrites stage data of one hard-coded workshop by id, and a deployed database that happens
to hold that id is not one anybody should discover this way.
"""

import asyncio
import json
import os
import sys
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from app.core.db import connect_db, db, disconnect_db

# Imported rather than restated, so the numbers this script writes are computed by the exact code
# that will grade them a minute later. A private copy of the six heads or of the margin formula
# would let the repair and the check drift into disagreeing about a sheet neither one touched.
from app.services.cost_integrity import (
    MARGIN_TOLERANCE_POINTS,
    TOLERANCE_RUPEES,
    analyse_cost_integrity,
    sum_cost_heads,
)
from app.services.market_analysis import as_number
from prisma import Json

#: The showcase record. Hard-coded on purpose: this script repairs ONE workshop's known damage from
#: two identified writes, and a version that took an id would be a general-purpose stage rewriter
#: pointed at whatever the caller typed.
WORKSHOP_ID = "cmsik2jg8000eh8xc1lcy661a"

#: The collections the two sweeps emptied. `buyerLink` carries no children and so never appears in
#: an orphan report, but it went in the same transaction as the cost sheets and is half of what
#: stage 17 means by "market linkage" — restoring the sheets and leaving the buyers deleted would
#: fix the symptom that was noticed and keep the one that was not.
SWEPT_ENTITIES = ("prototype", "costSheet", "buyerLink")

#: The heads that each restate a material line already counted inside `materialCost`. Cleared only
#: where that is demonstrably true of the row in hand — see `_restated_heads`.
RESTATABLE_HEADS = ("packagingCost", "finishingCost", "transportCost")

#: The product code whose sheet keeps its as-typed `marginPercent`. See the module docstring.
MARGIN_LEFT_AS_TYPED = "PT-01"


def _is_local(url: str) -> bool:
    return any(host in url for host in ("localhost", "127.0.0.1", "@postgres", "@design-workshop"))


def _money_str(value: float) -> str:
    """A MONEY value in the fixed-2 string form every stage entry stores it as."""
    return str(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


async def _rows(entity_key: str, *, live_only: bool = True) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": WORKSHOP_ID, "entityKey": entity_key}
    if live_only:
        where["deletedAt"] = None
    return await db.dwstageentry.find_many(where=where, order={"ordinal": "asc"})


# --------------------------------------------------------------------------------------
# Step 1 — undo the sweep
# --------------------------------------------------------------------------------------


async def restore_swept_collections() -> int:
    """Clear `deletedAt` on collections a sweep emptied whole.

    THE TWO CONDITIONS ARE WHAT MAKE THIS SAFE TO RE-RUN AGAINST A DATABASE NOBODY HAS BROKEN.
    A sweep takes an entire collection in one `update_many`, so it leaves every row of the entity
    deleted at one identical timestamp. A designer removing a row leaves its siblings alive, and a
    designer removing several over a fortnight leaves several timestamps. Requiring both signatures
    means an ordinary deletion is never resurrected — which matters more than it sounds, because
    resurrecting one would put a row a designer deliberately removed back into a submitted report.
    """
    restored = 0
    for entity_key in SWEPT_ENTITIES:
        rows = await _rows(entity_key, live_only=False)
        if not rows:
            print(f"  absent   {entity_key:<16} no rows at all")
            continue
        deleted = [r for r in rows if r.deletedAt is not None]
        if not deleted:
            print(f"  ok       {entity_key:<16} {len(rows)} row(s), none deleted")
            continue
        if len(deleted) != len(rows):
            print(f"  SKIPPED  {entity_key:<16} {len(deleted)} of {len(rows)} deleted — a partial "
                  f"deletion is a designer's, not a sweep's")
            continue
        stamps = {r.deletedAt for r in deleted}
        if len(stamps) != 1:
            print(f"  SKIPPED  {entity_key:<16} deleted across {len(stamps)} timestamps — not one "
                  f"sweep")
            continue
        await db.dwstageentry.update_many(
            where={"id": {"in": [r.id for r in deleted]}}, data={"deletedAt": None}
        )
        restored += len(deleted)
        print(f"  restored {entity_key:<16} {len(deleted)} row(s) swept at {stamps.pop()}")
    return restored


# --------------------------------------------------------------------------------------
# Step 2 — make each sheet agree with its own lines
# --------------------------------------------------------------------------------------


def _restated_heads(sheet: dict[str, Any], material_lines: list[dict[str, Any]]) -> list[str]:
    """The optional heads on this sheet that a material line already accounts for.

    MATCHED ON THE ROW IN HAND rather than assumed from the module docstring. A head is cleared
    only when a material line of the same sheet carries the identical amount, so a workshop where
    packaging genuinely is a separate head — the ordinary case, and what the registry intends —
    keeps it. Guessing here would quietly delete a real cost.
    """
    amounts = {as_number(line.get("amount")) for line in material_lines}
    amounts.discard(None)
    found = []
    for head in RESTATABLE_HEADS:
        value = as_number(sheet.get(head))
        if value is not None and any(abs(value - a) < 0.005 for a in amounts):
            found.append(head)
    return found


async def repair_cost_sheets() -> int:
    """Point each sheet at its final product and settle its arithmetic. Returns rows written."""
    sheets = await _rows("costSheet")
    products = await _rows("finalProduct")
    material = await _rows("costMaterialLine")
    if not sheets:
        print("  nothing  no live cost sheets to repair")
        return 0

    written = 0
    for index, row in enumerate(sheets):
        data: dict[str, Any] = dict(row.data or {})
        before = json.dumps(data, sort_keys=True)
        product = products[index] if index < len(products) else None
        code = str((product.data or {}).get("productCode") or "") if product else ""
        label = code or f"Cost sheet {index + 1}"
        notes: list[str] = []

        # `productRef` WAS NEVER STORED AT ALL — a required BASIC field, missing on all four, which
        # is why the sheets label themselves "Cost sheet 1" and why the report's cost-sheet table
        # has no product column to print. Paired by ordinal AND confirmed by the product's code
        # appearing in the sheet's own prose, because writing the WRONG id here would be the same
        # dangling-reference bug this script exists to clear, only harder to notice.
        if not data.get("productRef") and product is not None:
            if code and code in before:
                data["productRef"] = product.id
                notes.append(f"productRef -> {code}")
            else:
                notes.append(f"productRef LEFT BLANK — {code or 'the product'} is not named in "
                             f"this sheet, so the pairing is not confirmable")

        for head in _restated_heads(data, [dict(m.data or {}) for m in material
                                           if (m.data or {}).get("costSheetRef") == row.id]):
            notes.append(f"-{head} {data[head]} (already a material line)")
            data.pop(head)

        # `totalCost` is a SUM over the heads, so clearing a head can only be correct if the stored
        # total was never counting it. It was not — but recomputing rather than trusting that is
        # what stops this script from leaving a stale derived field behind if the data ever moves.
        computed_total, unreadable = sum_cost_heads(data)
        declared_total = as_number(data.get("totalCost"))
        if unreadable:
            notes.append(f"totalCost NOT CHECKED — unreadable head(s): {', '.join(unreadable)}")
        elif computed_total is not None and (
            declared_total is None or abs(declared_total - computed_total) > TOLERANCE_RUPEES
        ):
            notes.append(f"totalCost {data.get('totalCost')} -> {_money_str(computed_total)}")
            data["totalCost"] = _money_str(computed_total)

        # Margin ON COST, which is what the field means. PT-01 keeps what the designer typed.
        price = as_number(data.get("expectedPrice"))
        cost = as_number(data.get("totalCost"))
        if code == MARGIN_LEFT_AS_TYPED:
            notes.append(f"marginPercent {data.get('marginPercent')} LEFT AS TYPED — the one "
                         f"deliberate finding")
        elif price is not None and cost is not None and cost > 0:
            implied = round((price - cost) / cost * 100.0, 1)
            declared = as_number(data.get("marginPercent"))
            if declared is None or abs(declared - implied) > MARGIN_TOLERANCE_POINTS:
                notes.append(f"marginPercent {data.get('marginPercent')} -> {implied}")
                data["marginPercent"] = implied

        # THE WRITE IS DECIDED BY THE DATA, not by whether anything above appended a note. A note
        # is prose for a human reading the run; comparing the serialised row to the copy taken
        # before the repairs is what makes a second run a genuine no-op rather than a rewrite that
        # happens to land the same values and bumps `updatedAt` on fifty rows every time.
        changed = json.dumps(data, sort_keys=True) != before
        if changed:
            await db.dwstageentry.update(where={"id": row.id}, data={"data": Json(data)})
            written += 1
        print(f"  {'written' if changed else 'unchanged':<9} {label:<8} "
              f"{'; '.join(notes) if notes else 'already correct'}")

    return written


async def verify() -> None:
    """Print what `GET /cost-integrity` will now answer, from the same pure analyser it calls."""
    def shape(rows: list[Any]) -> list[dict[str, Any]]:
        return [dict(r.data or {}, _entryId=r.id) for r in rows]

    products = await _rows("finalProduct")
    findings = analyse_cost_integrity(
        sheets=shape(await _rows("costSheet")),
        material_lines=shape(await _rows("costMaterialLine")),
        labour_lines=shape(await _rows("costLabourLine")),
        labels={p.id: str((p.data or {}).get("name") or "") for p in products},
    )
    print(f"\n  sheets {findings.sheet_count}   orphan lines {len(findings.orphans)}   "
          f"findings {len(findings.mismatches)}")
    for sheet in findings.sheets:
        for check in sheet.checks:
            mark = "!!" if check.is_finding else "ok"
            print(f"    {mark} {check.key:<14} {check.verdict:<13} {sheet.label[:52]}")
    for caution in findings.cautions:
        print(f"    !! caution: {caution[:150]}")


# --------------------------------------------------------------------------------------
# Step 3 — the same fault, everywhere else it could be
# --------------------------------------------------------------------------------------


async def audit_parented_entities() -> None:
    """Every child row of this workshop whose declared parent does not resolve to a live row.

    Read from the REGISTRY rather than from a list kept here, so an entity that gains a `parent`
    tomorrow is audited without anybody remembering to add it. Two failures are distinguished
    because they have different causes and different fixes: a parent that is soft-deleted is this
    bug and is repairable, while a parent id that matches no row at all is a client that invented
    a reference and cannot be repaired without knowing what it meant.
    """
    from app.services.stage_schema import FieldType, all_entities

    by_key = {e.key: (s, e) for s, e in all_entities()}
    rows = await db.dwstageentry.find_many(where={"designWorkshopId": WORKSHOP_ID})
    live_ids = {r.id for r in rows if r.deletedAt is None}
    dead_ids = {r.id for r in rows if r.deletedAt is not None}

    clean = True
    for _stage, entity in by_key.values():
        if not entity.parent or entity.parent not in by_key:
            continue
        parent = by_key[entity.parent][1]
        link = next((f for f in entity.fields
                     if f.type is FieldType.REF and f.ref_model == parent.name), None)
        if link is None:
            continue
        blank = deleted = missing = 0
        for row in rows:
            if row.entityKey != entity.key or row.deletedAt is not None:
                continue
            ref = str((row.data or {}).get(link.key) or "")
            if not ref:
                blank += 1
            elif ref in dead_ids:
                deleted += 1
            elif ref not in live_ids:
                missing += 1
        if blank or deleted or missing:
            clean = False
            print(f"  {entity.key:<20} -> {entity.parent:<12} "
                  f"blank {blank}  parent-soft-deleted {deleted}  parent-absent {missing}")
    if clean:
        print("  every parented row in this workshop resolves to a live parent")


async def main() -> None:
    # Every label this prints is a craft name — em dashes, and Odia in places. A Windows console
    # defaults to cp1252 and raises UnicodeEncodeError on the first one, which would abort a repair
    # halfway through over a dash in a product name.
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    url = os.environ.get("DATABASE_URL", "")
    if not _is_local(url):
        raise SystemExit(
            "REFUSED: DATABASE_URL does not point at a local database.\n"
            "This script rewrites stage-17 data of one hard-coded workshop id. Running it against "
            "a deployed repository would edit whatever record happens to carry that id."
        )

    await connect_db()
    try:
        workshop = await db.designworkshop.find_unique(where={"id": WORKSHOP_ID})
        if workshop is None:
            raise SystemExit(
                f"REFUSED: no design workshop {WORKSHOP_ID}. This database has not been seeded "
                f"with the showcase record, and there is nothing here to repair."
            )
        print(f"{workshop.title}\n")

        print("UNDOING THE SWEEP")
        restored = await restore_swept_collections()

        print("\nREPAIRING THE COST SHEETS")
        written = await repair_cost_sheets()

        print("\nWHAT THE INTEGRITY CHECK NOW SAYS")
        await verify()

        print("\nPARENTED-ENTITY AUDIT")
        await audit_parented_entities()

        print(f"\n{restored} row(s) restored, {written} cost sheet(s) rewritten.")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    sys.exit(asyncio.run(main()) or 0)
