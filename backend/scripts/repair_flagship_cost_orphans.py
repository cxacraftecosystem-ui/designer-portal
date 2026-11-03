import asyncio
import json
import os
import sys
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from app.core.db import connect_db, db, disconnect_db

from app.services.cost_integrity import (
    MARGIN_TOLERANCE_POINTS,
    TOLERANCE_RUPEES,
    analyse_cost_integrity,
    sum_cost_heads,
)
from app.services.market_analysis import as_number
from prisma import Json

WORKSHOP_ID = "cmsik2jg8000eh8xc1lcy661a"

SWEPT_ENTITIES = ("prototype", "costSheet", "buyerLink")

RESTATABLE_HEADS = ("packagingCost", "finishingCost", "transportCost")

MARGIN_LEFT_AS_TYPED = "PT-01"


def _is_local(url: str) -> bool:
    return any(host in url for host in ("localhost", "127.0.0.1", "@postgres", "@design-workshop"))


def _money_str(value: float) -> str:
    return str(Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


async def _rows(entity_key: str, *, live_only: bool = True) -> list[Any]:
    where: dict[str, Any] = {"designWorkshopId": WORKSHOP_ID, "entityKey": entity_key}
    if live_only:
        where["deletedAt"] = None
    return await db.dwstageentry.find_many(where=where, order={"ordinal": "asc"})



async def restore_swept_collections() -> int:
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



def _restated_heads(sheet: dict[str, Any], material_lines: list[dict[str, Any]]) -> list[str]:
    amounts = {as_number(line.get("amount")) for line in material_lines}
    amounts.discard(None)
    found = []
    for head in RESTATABLE_HEADS:
        value = as_number(sheet.get(head))
        if value is not None and any(abs(value - a) < 0.005 for a in amounts):
            found.append(head)
    return found


async def repair_cost_sheets() -> int:
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

        computed_total, unreadable = sum_cost_heads(data)
        declared_total = as_number(data.get("totalCost"))
        if unreadable:
            notes.append(f"totalCost NOT CHECKED — unreadable head(s): {', '.join(unreadable)}")
        elif computed_total is not None and (
            declared_total is None or abs(declared_total - computed_total) > TOLERANCE_RUPEES
        ):
            notes.append(f"totalCost {data.get('totalCost')} -> {_money_str(computed_total)}")
            data["totalCost"] = _money_str(computed_total)

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

        changed = json.dumps(data, sort_keys=True) != before
        if changed:
            await db.dwstageentry.update(where={"id": row.id}, data={"data": Json(data)})
            written += 1
        print(f"  {'written' if changed else 'unchanged':<9} {label:<8} "
              f"{'; '.join(notes) if notes else 'already correct'}")

    return written


async def verify() -> None:
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



async def audit_parented_entities() -> None:
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
