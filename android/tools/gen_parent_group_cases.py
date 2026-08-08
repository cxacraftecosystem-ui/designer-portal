"""Run one shared case table through the SERVER'S `_parent_groups` and write the answers out.

    python android/tools/gen_parent_group_cases.py \
        android/app/src/test/resources/dw-parent-group-cases.json

Run it from the repository root after ANY change to `report_builder._parent_groups`, then run
`DwParentGroupParityTest`: it is the one thing that says whether the handset still puts the same
lines under the same cost sheet as the server does. The file it writes is checked in because the
test must run in a cluster and on a build machine with no Python and no backend checkout.

The Kotlin port is asserted against this file rather than against a hand-written expectation, which
is the standing rule for a port in this repository: pin it BY VALUE over a shared case table, not by
restating its properties in two languages, because a property restated twice is two guesses about
the same behaviour and a diff over values is the behaviour itself.

Both sides load their OWN copy of the registry — Python from `stage_definitions`, Kotlin from the
bundled `design-workshop-schema.json` that is generated from it — so the entity names, the parent
declarations and the REF models are not part of the case data and cannot drift apart inside it.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

BACKEND = Path(r"D:\Portal_Development_Designer\backend")
sys.path.insert(0, str(BACKEND))

import app.services.stage_definitions  # noqa: F401,E402  - installs the registry
from app.services.report_builder import ReportBuilder, WorkshopData  # noqa: E402
from app.services.report_model import ImageRef, ReportMeta  # noqa: E402
from app.services.report_templates import template as get_template  # noqa: E402
from app.services.stage_schema import stages  # noqa: E402

# -- the shared case table ---------------------------------------------------------------------
#
# Every case names a CHILD collection of the real registry and supplies the parent rows and the
# child rows by hand. `label` is the value of the child field the report prints first, so a group's
# membership can be read as a list of names in both languages.

CASES: list[dict] = [
    {
        "name": "two sheets, lines interleaved",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "cs1", "productRef": "fp1"},
            {"_entryId": "cs2", "productRef": "fp2"},
        ],
        "children": [
            {"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn"},
            {"_entryId": "m2", "costSheetRef": "cs2", "item": "Cotton yarn"},
            {"_entryId": "m3", "costSheetRef": "cs1", "item": "Natural dye"},
        ],
    },
    {
        "name": "a line naming no sheet at all",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [{"_entryId": "cs1", "productRef": "fp1"}],
        "children": [
            {"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn"},
            {"_entryId": "m2", "costSheetRef": "", "item": "Zari thread"},
        ],
    },
    {
        "name": "a line naming a sheet that was deleted",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [{"_entryId": "cs1", "productRef": "fp1"}],
        "children": [
            {"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn"},
            {"_entryId": "m2", "costSheetRef": "cmsdeletedsheet0001", "item": "Lining cloth"},
        ],
    },
    {
        "name": "both kinds of orphan land in one bucket, last",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "cs1", "productRef": "fp1"},
            {"_entryId": "cs2", "productRef": "fp2"},
        ],
        "children": [
            {"_entryId": "m1", "costSheetRef": "", "item": "Zari thread"},
            {"_entryId": "m2", "costSheetRef": "cs2", "item": "Cotton yarn"},
            {"_entryId": "m3", "costSheetRef": "cmsdeletedsheet0001", "item": "Lining cloth"},
            {"_entryId": "m4", "costSheetRef": "cs1", "item": "Tussar yarn"},
        ],
    },
    {
        "name": "groups follow the parent's order, not the children's",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costLabourLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "cs1", "productRef": "fp1"},
            {"_entryId": "cs2", "productRef": "fp2"},
        ],
        "children": [
            {"_entryId": "l1", "costSheetRef": "cs2", "task": "Weaving"},
            {"_entryId": "l2", "costSheetRef": "cs1", "task": "Dyeing"},
        ],
    },
    {
        "name": "a sheet with no lines of its own gets no heading",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costLabourLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "cs1", "productRef": "fp1"},
            {"_entryId": "cs2", "productRef": "fp2"},
        ],
        "children": [{"_entryId": "l1", "costSheetRef": "cs1", "task": "Dyeing"}],
    },
    {
        "name": "an unsynced sheet claims none of the orphans",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "", "productRef": "fp1"},
            {"_entryId": "cs2", "productRef": "fp2"},
        ],
        "children": [
            {"_entryId": "m1", "costSheetRef": "", "item": "Zari thread"},
            {"_entryId": "m2", "costSheetRef": "cs2", "item": "Cotton yarn"},
        ],
    },
    {
        "name": "a sheet that names no product falls back to its ordinal",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "cs1", "productRef": "fp1"},
            {"_entryId": "cs2", "productRef": ""},
        ],
        "children": [
            {"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn"},
            {"_entryId": "m2", "costSheetRef": "cs2", "item": "Cotton yarn"},
        ],
    },
    {
        "name": "no parent rows at all — every line is an orphan",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [],
        "children": [
            {"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn"},
            {"_entryId": "m2", "costSheetRef": "", "item": "Zari thread"},
        ],
    },
    {
        "name": "two sheets answering to the same id — the first claims the lines",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costMaterialLine",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [
            {"_entryId": "cs1", "productRef": "fp1"},
            {"_entryId": "cs1", "productRef": "fp2"},
        ],
        "children": [{"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn"}],
    },
    {
        "name": "stage 13: stage logs under their prototype",
        "stage": "PROTOTYPE_DEVELOPMENT",
        "entity": "prototypeStageLog",
        "parentStage": "PROTOTYPE_DEVELOPMENT",
        "parentEntity": "prototype",
        "parents": [
            {"_entryId": "p1", "name": "Stole A"},
            {"_entryId": "p2", "name": "Cushion B"},
        ],
        "children": [
            {"_entryId": "sl1", "prototypeRef": "p2", "stageName": "Warping"},
            {"_entryId": "sl2", "prototypeRef": "p1", "stageName": "Dyeing"},
        ],
    },
    {
        "name": "stage 13: material usage under the same prototypes",
        "stage": "PROTOTYPE_DEVELOPMENT",
        "entity": "materialUsage",
        "parentStage": "PROTOTYPE_DEVELOPMENT",
        "parentEntity": "prototype",
        "parents": [
            {"_entryId": "p1", "name": "Stole A"},
            {"_entryId": "p2", "name": "Cushion B"},
        ],
        "children": [
            {"_entryId": "mu1", "prototypeRef": "p1", "material": "Tussar"},
            {"_entryId": "mu2", "prototypeRef": "p2", "material": "Cotton"},
        ],
    },
    {
        "name": "stage 14: the parent lives in another stage",
        "stage": "PROTOTYPE_ITERATION",
        "entity": "prototypeIteration",
        "parentStage": "PROTOTYPE_DEVELOPMENT",
        "parentEntity": "prototype",
        "parents": [
            {"_entryId": "p1", "name": "Stole A"},
            {"_entryId": "p2", "name": "Cushion B"},
        ],
        "children": [
            {"_entryId": "it1", "prototypeRef": "p1", "changesMade": "Widened border"},
            {"_entryId": "it2", "prototypeRef": "p2", "changesMade": "Softer weft"},
        ],
    },
    {
        "name": "stage 14 with stage 13 never filled in",
        "stage": "PROTOTYPE_ITERATION",
        "entity": "prototypeIteration",
        "parentStage": "PROTOTYPE_DEVELOPMENT",
        "parentEntity": "prototype",
        "parents": [],
        "children": [
            {"_entryId": "it1", "prototypeRef": "p1", "changesMade": "Widened border"},
            {"_entryId": "it2", "prototypeRef": "p2", "changesMade": "Softer weft"},
        ],
    },
    {
        "name": "a sibling collection that declares no parent",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "buyerLink",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [{"_entryId": "cs1", "productRef": "fp1"}],
        "children": [{"_entryId": "b1", "buyerName": "Tantuja"}],
    },
    {
        "name": "the parent collection itself",
        "stage": "COSTING_MARKET_LINKAGE",
        "entity": "costSheet",
        "parentStage": "COSTING_MARKET_LINKAGE",
        "parentEntity": "costSheet",
        "parents": [{"_entryId": "cs1", "productRef": "fp1"}],
        "children": [{"_entryId": "cs1", "productRef": "fp1"}],
    },
]

#: The final products every `productRef` above points at, so a sheet is titled by a NAME on both
#: sides rather than by the cuid it stores.
PRODUCTS = [
    {"_entryId": "fp1", "name": "Sambalpuri stole"},
    {"_entryId": "fp2", "name": "Ikat cushion cover"},
]

#: The field whose value names a child row in the output, per entity.
LABEL_KEY = {
    "costMaterialLine": "item",
    "costLabourLine": "task",
    "prototypeStageLog": "stageName",
    "materialUsage": "material",
    "prototypeIteration": "changesMade",
    "buyerLink": "buyerName",
    "costSheet": "productRef",
}


def _entity(stage_key: str, entity_key: str):
    stage = next(s for s in stages() if s.key == stage_key)
    return next(e for e in stage.entities if e.key == entity_key)


def run(case: dict) -> dict:
    collections: dict[str, dict[str, list[dict]]] = {
        "FINAL_PROTOTYPE_DOCUMENTATION": {"finalProduct": list(PRODUCTS)},
    }
    collections.setdefault(case["parentStage"], {})[case["parentEntity"]] = list(case["parents"])
    collections.setdefault(case["stage"], {}).setdefault(case["entity"], [])
    collections[case["stage"]][case["entity"]] = list(case["children"])

    data = WorkshopData(workshop_id="w1", title="Workshop", collections=collections)
    builder = ReportBuilder(
        data,
        get_template("DETAILED_TECHNICAL"),
        lambda media_id: ImageRef(source=media_id, width_px=8, height_px=6, mime_type="image/jpeg"),
        meta=ReportMeta(title="Workshop", subtitle="", generated_at="2026-08-08T00:00:00Z"),
    )
    entity = _entity(case["stage"], case["entity"])
    groups = builder._parent_groups(entity, list(case["children"]))
    label_key = LABEL_KEY[case["entity"]]
    return {
        "name": case["name"],
        "groups": None if groups is None else [
            {"heading": heading, "rows": [row.get(label_key, "") for row in rows]}
            for heading, rows in groups
        ],
    }


def main() -> None:
    out = {
        "products": PRODUCTS,
        "labelKeys": LABEL_KEY,
        "cases": CASES,
        "expected": [run(case) for case in CASES],
    }
    target = Path(sys.argv[1])
    target.parent.mkdir(parents=True, exist_ok=True)
    # EXPLICIT UTF-8. Redirecting stdout on this machine dies on cp1252 the moment a label carries a
    # character outside it, and a half-written table is a parity test that silently compares nothing.
    target.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(CASES)} cases to {target}")


if __name__ == "__main__":
    main()
