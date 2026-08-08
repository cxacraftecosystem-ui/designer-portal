"""A child collection is printed under the parent record its rows belong to.

THE DOCUMENT THIS DEFENDS. ``EntitySpec.parent`` has been declared in the registry, validated by
``stage_schema`` and shipped to every client since the schema existed, and no renderer read it. So
stage 17 printed "Cost sheets" as one table and then "Material cost lines" as a SECOND flat table
holding every line of every sheet interleaved in entry order. The field that says which line
belongs to which sheet — ``costMaterialLine.costSheetRef`` — is ``report_role=HIDDEN``, so it was
not in that table either. The submitted document therefore contained no way whatsoever to work out
which materials cost which product, which is the one question a cost sheet exists to answer, in a
report an officer reads as the basis of a sanctioned amount.

Three stages declare a parent and all three are covered here: 13 (stage logs and material usage
under a prototype), 14 (iterations under a prototype declared in stage 13 — the parent is in
ANOTHER stage) and 17 (cost lines under a cost sheet).

The other half of these tests is the half that makes the change safe. Eighteen stages declare no
parent at all and every one of them must render exactly as it did before this behaviour existed —
``_render_stage`` is the path every stage of every report goes through, so a regression here is a
regression in all of them at once. ``test_a_parent_free_stage_is_untouched…`` compares the whole
block list against the same builder with grouping switched off, which IS the pre-change code path.
"""

import zipfile
from io import BytesIO

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import ReportBuilder, WorkshopData, build_report
from app.services.report_docx import render_docx
from app.services.report_model import (
    HeadingBlock,
    ImageRef,
    ReportMeta,
    TableBlock,
    runs_text,
)
from app.services.report_pdf import render_pdf
from app.services.stage_schema import ENUMS, FieldType, stages

# --------------------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------------------


def _meta() -> ReportMeta:
    return ReportMeta(title="Workshop", subtitle="Cluster",
                      generated_at="2026-08-07T00:00:00Z")


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _build(data: WorkshopData, template_id: str = "DETAILED_TECHNICAL"):
    document, _warnings = build_report(data, template_id, _resolver, meta=_meta())
    return document


def _costing_data() -> WorkshopData:
    """Two cost sheets whose lines are stored INTERLEAVED, plus two kinds of orphan.

    The interleaving is the point: entry order alone can never reconstruct the grouping, so a test
    that stored sheet 1's lines together then sheet 2's would pass against the flat table it is
    meant to reject.
    """
    return WorkshopData(
        workshop_id="w1",
        title="Workshop",
        collections={
            "FINAL_PROTOTYPE_DOCUMENTATION": {
                "finalProduct": [
                    {"_entryId": "fp1", "name": "Sambalpuri stole"},
                    {"_entryId": "fp2", "name": "Ikat cushion cover"},
                ],
            },
            "COSTING_MARKET_LINKAGE": {
                "costSheet": [
                    {"_entryId": "cs1", "productRef": "fp1", "totalCost": 1200,
                     "expectedPrice": 1800},
                    {"_entryId": "cs2", "productRef": "fp2", "totalCost": 640,
                     "expectedPrice": 900},
                ],
                "costMaterialLine": [
                    {"_entryId": "m1", "costSheetRef": "cs1", "item": "Tussar yarn",
                     "quantity": 3, "unit": "kg", "rate": 250, "amount": 750},
                    {"_entryId": "m2", "costSheetRef": "cs2", "item": "Cotton yarn",
                     "quantity": 2, "unit": "kg", "rate": 120, "amount": 240},
                    {"_entryId": "m3", "costSheetRef": "cs1", "item": "Natural dye",
                     "quantity": 1, "unit": "kg", "rate": 200, "amount": 200},
                    # Names no sheet at all — the picker was never opened.
                    {"_entryId": "m4", "costSheetRef": "", "item": "Zari thread",
                     "quantity": 1, "unit": "m", "rate": 90, "amount": 90},
                    # Names a sheet that was deleted after this line cited it.
                    {"_entryId": "m5", "costSheetRef": "cmsdeletedsheet0001",
                     "item": "Lining cloth", "quantity": 1, "unit": "m", "rate": 60,
                     "amount": 60},
                ],
                # Stored in the OPPOSITE order to the sheets, so "groups follow the parent's
                # order" cannot pass by accident.
                "costLabourLine": [
                    {"_entryId": "l1", "costSheetRef": "cs2", "task": "Weaving",
                     "persons": 1, "days": 4, "rate": 400, "amount": 1600},
                    {"_entryId": "l2", "costSheetRef": "cs1", "task": "Dyeing",
                     "persons": 2, "days": 1, "rate": 350, "amount": 700},
                ],
                "buyerLink": [
                    {"_entryId": "b1", "buyerName": "Tantuja", "contact": "9",
                     "interest": "Bulk order"},
                ],
            },
        },
    )


# --------------------------------------------------------------------------------------
# Reading the built document
# --------------------------------------------------------------------------------------


def _outline(document) -> list[tuple[str, str]]:
    """The document as a reader meets it: ``("H3", "Sambalpuri stole")`` / ``("TABLE", caption)``."""
    out: list[tuple[str, str]] = []
    for block in document.blocks:
        if isinstance(block, HeadingBlock):
            out.append((f"H{block.level}", runs_text(block.runs)))
        elif isinstance(block, TableBlock):
            out.append(("TABLE", block.caption))
    return out


def _section(document, heading: str) -> list[tuple[str, str]]:
    """Everything under an H2, up to the next heading at H2 or above."""
    out = _outline(document)
    start = next(i for i, (kind, text) in enumerate(out)
                 if kind == "H2" and text == heading)
    end = next((i for i in range(start + 1, len(out))
                if out[i][0] in {"H1", "H2"}), len(out))
    return out[start + 1:end]


def _under(document, heading: str, cells) -> list[tuple[str, list[str]]]:
    """Each group under `heading` and one string per record printed beneath it.

    ANCHORED ON THE HEADING'S OWN LEVEL, not on H2. A stage with a single collection prints no
    entity heading at all — ``_render_stage`` only names a collection when the stage has several —
    so stage 14's groups hang off the stage's H1, and a helper that insisted on an H2 went looking
    for an "Iterations" heading this report has never had and called a correct document empty.

    A record is a table row OR a card. ``DETAILED_TECHNICAL`` renders stage 13 as
    ``Presentation.CARDS``, so its records are sub-headings with key-value blocks under them and
    not table rows; reading only tables reported every stage-13 group as carrying nothing.
    """
    blocks = [b for b in document.blocks if isinstance(b, (HeadingBlock, TableBlock))]
    start = next(i for i, b in enumerate(blocks)
                 if isinstance(b, HeadingBlock) and runs_text(b.runs) == heading)
    level = blocks[start].level
    out: list[tuple[str, list[str]]] = []
    for block in blocks[start + 1:]:
        if isinstance(block, HeadingBlock):
            if block.level <= level:
                break
            if block.level == level + 1:
                out.append((runs_text(block.runs), []))
            elif out:
                out[-1][1].append(runs_text(block.runs))   # a card IS its record
        elif out:
            out[-1][1].extend(cells(row) for row in block.rows)
    return out


def _groups(document, heading: str) -> list[tuple[str, list[str]]]:
    """Each group and the label of every record in it — a row's first cell, or a card's heading."""
    return _under(document, heading, lambda row: runs_text(row[0]))


def _group_rows(document, heading: str) -> list[tuple[str, list[str]]]:
    """As :func:`_groups`, but a table row is ALL of its cells rather than the first.

    An iteration's first column is the prototype it belongs to, not the change it records, so the
    first cell there names the group rather than the row inside it and could not tell one
    iteration from another.
    """
    return _under(document, heading, lambda row: " ".join(runs_text(cell) for cell in row))


def _group_headings(document, heading: str) -> list[HeadingBlock]:
    """The group headings under `heading`, so a test can read the numbers they carry."""
    headings = [b for b in document.blocks if isinstance(b, HeadingBlock)]
    start = next(i for i, b in enumerate(headings) if runs_text(b.runs) == heading)
    level = headings[start].level
    out: list[HeadingBlock] = []
    for block in headings[start + 1:]:
        if block.level <= level:
            break
        if block.level == level + 1:
            out.append(block)
    return out


def _stage_title(stage_key: str) -> str:
    """A stage's H1 as the registry titles it — never the entity's title, which is a different
    string: stage 14's one collection is "Iterations" and the stage itself is "Prototype
    Iteration & Testing", and only the stage's own words reach the page as a heading."""
    return next(s for s in stages() if s.key == stage_key).title


def _first_cells(document, caption: str) -> list[list[str]]:
    return [[runs_text(row[0]) for row in b.rows]
            for b in document.blocks
            if isinstance(b, TableBlock) and b.caption == caption]


# --------------------------------------------------------------------------------------
# Stage 17: the cost sheet
# --------------------------------------------------------------------------------------


def test_material_lines_are_printed_under_the_cost_sheet_they_belong_to():
    """THE DEFECT. One table per sheet, headed by the sheet, instead of one flat interleaved table.

    Fails before the change with a single 5-row "Material cost lines" table in which nothing says
    that Tussar yarn and Natural dye are what the Sambalpuri stole cost.
    """
    groups = _groups(_build(_costing_data()), "Material cost lines")
    assert [name for name, _ in groups] == [
        "Sambalpuri stole", "Ikat cushion cover", "No cost sheet recorded",
    ]
    assert dict(groups)["Sambalpuri stole"] == ["Tussar yarn", "Natural dye"]
    assert dict(groups)["Ikat cushion cover"] == ["Cotton yarn"]


def test_no_group_carries_a_line_belonging_to_another_sheet():
    """The whole value of the grouping. A line under the wrong heading is worse than a flat table:
    a flat table is merely unusable, a mis-filed line is a wrong number an officer would act on."""
    document = _build(_costing_data())
    for caption, owned in (
        ("Material cost lines", {"Sambalpuri stole": {"Tussar yarn", "Natural dye"},
                                 "Ikat cushion cover": {"Cotton yarn"}}),
        ("Labour cost lines", {"Sambalpuri stole": {"Dyeing"},
                               "Ikat cushion cover": {"Weaving"}}),
    ):
        for name, lines in _groups(document, caption):
            if name in owned:
                assert set(lines) == owned[name], f"{caption}: {name} carried {lines}"


def test_groups_follow_the_parents_own_order_not_the_order_the_lines_were_typed():
    """The groups run down the page in the same sequence as the cost sheet table above them.

    The labour lines are stored sheet-2-first; ordering by the children would print "Ikat cushion
    cover" above "Sambalpuri stole" and a reader comparing the two tables would have to search.
    """
    groups = [name for name, _ in _groups(_build(_costing_data()), "Labour cost lines")]
    assert groups == ["Sambalpuri stole", "Ikat cushion cover"]


def test_every_line_that_was_recorded_is_still_printed_exactly_once():
    """Grouping must not become a filter. Every line the flat table used to carry is still in the
    document, once — a line that cost real money silently missing from a total is the failure that
    would make this change worse than the defect it fixes."""
    document = _build(_costing_data())
    printed = [label for _name, lines in _groups(document, "Material cost lines")
               for label in lines]
    assert sorted(printed) == [
        "Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread",
    ]


def test_a_line_naming_no_cost_sheet_is_printed_under_a_heading_that_says_so():
    """AN ORPHAN IS FIELDWORK SOMEBODY COLLECTED. Filing it under a sheet it does not belong to
    would be a fabrication and dropping it would delete a measurement, so it gets an honest
    heading of its own — and it comes last, after every real sheet."""
    groups = _groups(_build(_costing_data()), "Material cost lines")
    assert groups[-1][0] == "No cost sheet recorded"
    # Both kinds of orphan land here: the blank ref and the one naming a deleted sheet.
    assert set(groups[-1][1]) == {"Zari thread", "Lining cloth"}


def test_an_orphan_heading_is_not_printed_when_every_line_names_its_sheet():
    """The honest heading must not become a permanent empty fixture at the foot of every table."""
    data = _costing_data()
    lines = data.collections["COSTING_MARKET_LINKAGE"]["costMaterialLine"]
    data.collections["COSTING_MARKET_LINKAGE"]["costMaterialLine"] = [
        row for row in lines if row["costSheetRef"] in {"cs1", "cs2"}
    ]
    groups = _groups(_build(data), "Material cost lines")
    assert [name for name, _ in groups] == ["Sambalpuri stole", "Ikat cushion cover"]


def test_a_sheet_with_no_lines_of_its_own_gets_no_empty_heading():
    """A sheet nobody itemised is reported by its absence from the breakdown, not by a heading with
    an empty table under it that reads as "this product cost nothing"."""
    data = _costing_data()
    data.collections["COSTING_MARKET_LINKAGE"]["costLabourLine"] = [
        {"_entryId": "l2", "costSheetRef": "cs1", "task": "Dyeing", "persons": 2,
         "days": 1, "rate": 350, "amount": 700},
    ]
    assert [name for name, _ in _groups(_build(data), "Labour cost lines")] == [
        "Sambalpuri stole",
    ]


def test_a_sheet_that_has_not_synced_yet_claims_none_of_the_orphans():
    """A SHEET WITH NO ID CANNOT OWN A LINE THAT NAMES NO SHEET, and the two look identical to a
    dict: the orphan bucket is keyed by the empty string and an unsynced row's ``_entryId`` IS the
    empty string. Popping it for that sheet printed every unattributed line as that one product's
    material cost — a fabricated number in the column an officer sanctions against, arrived at by
    the same code that exists to prevent fabrications. ``cost_integrity.summarise_sheets`` guards
    the identical join and the two must not disagree about what is attributable.

    Fails before the guard with "Zari thread" — a line naming no sheet at all — heading up as the
    Sambalpuri stole's breakdown.
    """
    data = _costing_data()
    data.collections["COSTING_MARKET_LINKAGE"]["costSheet"][0]["_entryId"] = ""
    groups = _groups(_build(data), "Material cost lines")

    assert [name for name, _ in groups] == ["Ikat cushion cover", "No cost sheet recorded"]
    # Nothing is lost either: the sheet's own lines can no longer be joined to it, so they are
    # reported as unattributed rather than dropped.
    assert set(dict(groups)["No cost sheet recorded"]) == {
        "Zari thread", "Lining cloth", "Tussar yarn", "Natural dye",
    }
    printed = [label for _name, lines in groups for label in lines]
    assert sorted(printed) == [
        "Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread",
    ]


def test_a_sibling_collection_with_no_parent_is_left_flat_in_the_same_stage():
    """Grouping is a property of the ENTITY, not of the stage. Stage 17 holds both kinds, and
    buyer linkages belong to no cost sheet — one flat table is the right shape for them."""
    document = _build(_costing_data())
    assert _groups(document, "Buyer linkages") == []
    assert _first_cells(document, "Buyer linkages") == [["Tantuja"]]
    # And the parent collection itself stays one table of all the sheets.
    assert _first_cells(document, "Cost sheets") == [
        ["Sambalpuri stole", "Ikat cushion cover"],
    ]


def test_a_group_is_headed_by_the_same_words_as_the_sheet_it_belongs_to():
    """A cost sheet is titled by its ``productRef``, which is an id. Both the sheet's own row in the
    table above and its group heading go through ``_row_label``, so the reader matches them by
    eye — and neither prints the cuid, which is what `_ref_label` exists to prevent."""
    document = _build(_costing_data())
    sheet_labels = _first_cells(document, "Cost sheets")[0]
    group_names = [name for name, _ in _groups(document, "Material cost lines")]
    assert sheet_labels == ["Sambalpuri stole", "Ikat cushion cover"]
    assert group_names[:2] == sheet_labels


def test_a_sheet_that_names_no_product_still_heads_its_group_readably():
    """``_row_label``'s positional fallback. A sheet whose product was never picked is "Cost sheets
    2" in the table above and "Cost sheets 2" over its lines, rather than a blank heading."""
    data = _costing_data()
    data.collections["COSTING_MARKET_LINKAGE"]["costSheet"][1]["productRef"] = ""
    groups = [name for name, _ in _groups(_build(data), "Material cost lines")]
    assert groups[1] == "Cost sheets 2"


# --------------------------------------------------------------------------------------
# Stages 13 and 14: the prototype, including a parent in another stage
# --------------------------------------------------------------------------------------


def _prototype_data() -> WorkshopData:
    return WorkshopData(
        workshop_id="w1",
        title="Workshop",
        collections={
            "PROTOTYPE_DEVELOPMENT": {
                "prototype": [
                    {"_entryId": "p1", "name": "Stole A"},
                    {"_entryId": "p2", "name": "Cushion B"},
                ],
                "prototypeStageLog": [
                    {"_entryId": "sl1", "prototypeRef": "p2", "stageName": "Warping"},
                    {"_entryId": "sl2", "prototypeRef": "p1", "stageName": "Dyeing"},
                ],
                "materialUsage": [
                    {"_entryId": "mu1", "prototypeRef": "p1", "material": "Tussar"},
                    {"_entryId": "mu2", "prototypeRef": "p2", "material": "Cotton"},
                ],
            },
            "PROTOTYPE_ITERATION": {
                "prototypeIteration": [
                    {"_entryId": "it1", "prototypeRef": "p1", "changesMade": "Widened border"},
                    {"_entryId": "it2", "prototypeRef": "p2", "changesMade": "Softer weft"},
                ],
            },
        },
    )


def test_stage_logs_and_material_usage_are_grouped_under_their_prototype():
    """Stage 13 declares TWO children of the same parent; both must group, independently."""
    document = _build(_prototype_data())
    assert _groups(document, "Stage logs") == [
        ("Stole A", ["Dyeing"]), ("Cushion B", ["Warping"]),
    ]
    assert _groups(document, "Material usage") == [
        ("Stole A", ["Tussar"]), ("Cushion B", ["Cotton"]),
    ]


def test_an_iteration_is_grouped_under_a_prototype_declared_in_another_stage():
    """STAGE 14'S PARENT LIVES IN STAGE 13. The child's own stage holds no ``prototype`` rows at
    all, so a lookup that searched only the current stage would find no parents and print every
    iteration as an orphan. ``_entity_stage`` is what carries the link across the boundary.

    Read from the STAGE heading: stage 14 declares one collection, so ``_render_stage`` prints no
    entity heading and the groups sit directly under the stage's H1.
    """
    groups = dict(_group_rows(_build(_prototype_data()), _stage_title("PROTOTYPE_ITERATION")))
    assert list(groups) == ["Stole A", "Cushion B"]
    assert "Widened border" in " ".join(groups["Stole A"])
    assert "Softer weft" in " ".join(groups["Cushion B"])
    # Neither prototype carries the other's change, which is the whole point of the grouping.
    assert "Softer weft" not in " ".join(groups["Stole A"])
    assert "Widened border" not in " ".join(groups["Cushion B"])


def test_an_iteration_whose_prototype_is_missing_is_still_printed():
    """The same honesty rule as the cost line, on a stage whose parent is loaded from elsewhere —
    if stage 13 was never filled in, every iteration would otherwise vanish from the report."""
    data = _prototype_data()
    data.collections.pop("PROTOTYPE_DEVELOPMENT")
    groups = _group_rows(_build(data), _stage_title("PROTOTYPE_ITERATION"))
    assert [name for name, _ in groups] == ["No prototype recorded"]
    printed = " ".join(groups[0][1])
    assert "Widened border" in printed
    assert "Softer weft" in printed


# --------------------------------------------------------------------------------------
# The eighteen stages that declare no parent
# --------------------------------------------------------------------------------------


def _filler(spec) -> object | None:
    """A deterministic value for one field, so a stage can be filled without naming its schema."""
    if spec.deprecated or spec.type in {FieldType.IMAGE, FieldType.IMAGE_LIST, FieldType.FILE,
                                        FieldType.AUDIO, FieldType.VIDEO, FieldType.REF,
                                        FieldType.GEO, FieldType.RICH_TEXT}:
        return None
    if spec.type in {FieldType.INT, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT}:
        return 7
    if spec.type is FieldType.BOOL:
        return True
    if spec.type is FieldType.DATE:
        return "2026-02-03"
    if spec.type is FieldType.TIME:
        return "10:30"
    if spec.type in {FieldType.ENUM, FieldType.MULTI_ENUM}:
        # A FieldSpec does not carry its own choices: it NAMES a shared list in ``ENUMS``, so that
        # two stages asking the same question offer the same answers and the analyst joining them
        # is comparing one vocabulary. Reading a non-existent ``spec.options`` raised before this
        # helper ever reached the builder, which turned three of the parent-free guards below —
        # the tests that make the whole change safe — into errors that proved nothing.
        options = list(ENUMS.get(spec.enum, {}))
        if not options:
            return None
        return options[0] if spec.type is FieldType.ENUM else options[:1]
    if spec.type is FieldType.TAGS:
        return ["alpha", "beta"]
    return f"{spec.key} value"


def _stage_data(stage_key: str, rows_per_entity: int = 3) -> WorkshopData:
    stage = next(s for s in stages() if s.key == stage_key)
    collections: dict[str, list[dict[str, object]]] = {}
    singletons: dict[str, dict[str, object]] = {}
    for entity in stage.entities:
        rows = []
        for index in range(rows_per_entity):
            row: dict[str, object] = {"_entryId": f"{entity.key}{index}"}
            for spec in entity.fields:
                value = _filler(spec)
                if value is not None:
                    row[spec.key] = value
            rows.append(row)
        if entity.cardinality.name == "SINGLETON":
            singletons[stage_key] = rows[0]
        else:
            collections[entity.key] = rows
    return WorkshopData(workshop_id="w1", title="Workshop",
                        singletons=singletons,
                        collections={stage_key: collections})


# Three stages that declare no parent on any entity, chosen because each has SEVERAL collections
# and so exercises the entity sub-heading path that the grouping code sits inside.
@pytest.mark.parametrize("stage_key", [
    "TRADITIONAL_PROCESS_BASELINE",
    "MARKET_ANALYSIS_DIRECTION",
    "INSPECTION_CLOSING",
    "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
    "MARKET_SURVEY_CAPTURE",
])
def test_a_parent_free_stage_is_untouched_by_the_grouping(stage_key, monkeypatch):
    """A STAGE WITH NO PARENT MUST RENDER BYTE-IDENTICALLY TO BEFORE.

    ``_render_stage`` is the path every stage of every report goes through, so this is the test
    that makes the change safe rather than merely correct. Switching ``_parent_groups`` off gives
    back the exact pre-change code path — the caller falls straight through to the single
    ``_render_rows`` call it always made — so an identical block list proves nothing else moved.

    The comparison is the WHOLE block list, not a summary of it: heading numbers, table columns,
    captions and paragraph styles all have to survive, and an outline-level assertion would miss
    every one of them.
    """
    data = _stage_data(stage_key)
    after = _build(data)

    monkeypatch.setattr(ReportBuilder, "_parent_groups", lambda self, entity, rows: None)
    before = _build(data)

    assert before.blocks == after.blocks
    # Guard against the whole thing being vacuously equal because the stage rendered nothing.
    assert any(isinstance(b, TableBlock) for b in after.blocks), f"{stage_key} produced no table"


def test_every_stage_declaring_no_parent_is_listed_in_that_guard():
    """If a future stage gains a parent, or an existing one loses it, this is the test that says
    so — rather than the guard above silently protecting a stage that no longer needs it."""
    with_parents = {s.key for s in stages() if any(e.parent for e in s.entities)}
    assert with_parents == {
        "PROTOTYPE_DEVELOPMENT", "PROTOTYPE_ITERATION", "COSTING_MARKET_LINKAGE",
    }


# --------------------------------------------------------------------------------------
# Heading numbering
# --------------------------------------------------------------------------------------


def test_group_headings_are_numbered_beneath_the_collection_they_belong_to():
    """The sub-headings join the "3.2.1" counters the table of contents and the PDF bookmark
    outline are built from. An unnumbered stray here breaks both.

    Read in document ORDER, not into a dict keyed by heading text: a cost sheet heads a group of
    material lines AND a group of labour lines, so "Sambalpuri stole" is legitimately two headings
    with two different numbers, and a dict silently keeps whichever came last.
    """
    document = _build(_costing_data())
    collection = next(b for b in document.blocks
                      if isinstance(b, HeadingBlock) and runs_text(b.runs) == "Material cost lines")
    assert [(b.number, runs_text(b.runs))
            for b in _group_headings(document, "Material cost lines")] == [
        (f"{collection.number}.1", "Sambalpuri stole"),
        (f"{collection.number}.2", "Ikat cushion cover"),
        (f"{collection.number}.3", "No cost sheet recorded"),
    ]
    # The collection AFTER the groups keeps its own place in the sequence, so the groups have not
    # consumed a number that belonged to a sibling and shifted the rest of the report.
    labour = next(b for b in document.blocks
                  if isinstance(b, HeadingBlock) and runs_text(b.runs) == "Labour cost lines")
    head, _, last = collection.number.rpartition(".")
    assert labour.number == f"{head}.{int(last) + 1}"


def test_group_headings_carry_no_number_in_a_template_that_numbers_nothing():
    """PHOTO_CATALOGUE sets ``number_headings=False``. A group heading that numbered itself anyway
    would be the only numbered line in that document."""
    document = _build(_costing_data(), template_id="PHOTO_CATALOGUE")
    assert [b.number for b in document.blocks if isinstance(b, HeadingBlock)] == [
        "" for b in document.blocks if isinstance(b, HeadingBlock)
    ]


def test_a_group_heading_never_outranks_the_collection_it_is_part_of():
    """Stage 17 names its collections (four of them) so a group sits at H3 under the H2; stage 14
    has one collection and no entity heading, so its groups sit at H2 under the stage's H1. Either
    way the group is one level BELOW whatever named it — a group at the same level would read as a
    new collection and would renumber the rest of the report."""
    costing = _section(_build(_costing_data()), "Material cost lines")
    assert [kind for kind, _ in costing][:2] == ["H3", "TABLE"]

    # Stage 13 is CARDS in this template, so the group is a heading over a run of card headings,
    # and the cards must fall BELOW their group rather than beside it.
    assert [kind for kind, _ in _section(_build(_prototype_data()), "Stage logs")][:2] == \
        ["H3", "H4"]

    # Stage 14 prints no entity heading, so the groups answer to the stage's own H1. The heading
    # they sit under is the STAGE's title — "Iterations" is the entity's title and reaches the
    # page only as a table caption, never as a heading of any level.
    outline = _outline(_build(_prototype_data()))
    stage = _stage_title("PROTOTYPE_ITERATION")
    start = next(i for i, (kind, text) in enumerate(outline) if text == stage)
    assert outline[start][0] == "H1"
    assert outline[start + 1] == ("H2", "Stole A")
    assert not any(text == "Iterations" for kind, text in outline if kind != "TABLE")


# --------------------------------------------------------------------------------------
# The artefacts a reader actually opens
# --------------------------------------------------------------------------------------


def _docx_text(document) -> str:
    data, _dropped = render_docx(document, lambda _ref: None)
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return archive.read("word/document.xml").decode("utf-8")


#: The material cost section top to bottom, as the reader meets it: each sheet, then that sheet's
#: own lines, then the next sheet — and the two orphans last, under the honest heading.
_MATERIAL_SECTION = [
    "Sambalpuri stole", "Tussar yarn", "Natural dye",
    "Ikat cushion cover", "Cotton yarn",
    "No cost sheet recorded", "Zari thread", "Lining cloth",
]


def _ordered(section: str, expected: list[str]) -> None:
    """Every name is in `section`, once the section is found, and in this order.

    SCOPED TO THE SECTION on purpose. "Ikat cushion cover" is also a final product at stage 16 and
    a row of the cost sheet table at 3.1, both printed ABOVE these lines, so a first-occurrence
    search across the whole file compares the wrong strings and fails on a document that is
    perfectly correct — which is exactly what it did.
    """
    for needle in expected:
        assert needle in section, f"{needle!r} never reached the material cost section"
    found = [section.index(needle) for needle in expected]
    assert found == sorted(found), dict(zip(expected, found))


def test_the_docx_shows_each_sheets_lines_under_that_sheets_heading():
    """A block-list assertion does not prove the .docx is right — the writer could drop a heading
    level or emit the tables in another order. This reads the XML the officer's Word opens."""
    xml = _docx_text(_build(_costing_data()))
    section = xml[xml.index("Material cost lines"):xml.index("Labour cost lines")]
    _ordered(section, _MATERIAL_SECTION)
    # The group headings are real Word headings, so they appear in the navigation pane and in a
    # generated table of contents rather than looking like bold paragraphs.
    assert 'w:val="Heading3"' in section


def _pdf_text(document) -> str:
    """The PDF as somebody searching it in a reader would meet it.

    Two things have to be undone or the assertion lies about a correct file. pypdf breaks a table
    cell across lines — "Tussar\\nyarn" — so a plain substring search for an item name fails on a
    PDF that prints it perfectly; and the CONTENTS page repeats every heading in the document
    above all of them, so an ordering assertion that met it first would hold whatever order the
    body was in. The contents page is the one carrying the dot leaders.
    """
    pypdf = pytest.importorskip("pypdf")
    data, _dropped = render_pdf(document, lambda _ref: None)
    pages = [page.extract_text() or "" for page in pypdf.PdfReader(BytesIO(data)).pages]
    return " ".join(" ".join(p for p in pages if "........" not in p).split())


def test_the_pdf_shows_each_sheets_lines_under_that_sheets_heading():
    text = _pdf_text(_build(_costing_data()))
    section = text[text.index("Material cost lines"):text.index("Labour cost lines")]
    _ordered(section, _MATERIAL_SECTION)


def test_the_pdf_bookmark_outline_carries_the_group_headings():
    """The outline is built from the heading blocks, so a group heading that did not reach it would
    leave the reader's sidebar showing "Material cost lines" with nothing under it."""
    pypdf = pytest.importorskip("pypdf")
    data, _dropped = render_pdf(_build(_costing_data()), lambda _ref: None)
    reader = pypdf.PdfReader(BytesIO(data))

    def titles(items) -> list[str]:
        found = []
        for item in items:
            if isinstance(item, list):
                found.extend(titles(item))
            else:
                found.append(str(item.title))
        return found

    outline = titles(reader.outline)
    assert any("Sambalpuri stole" in title for title in outline), outline
    assert any("No cost sheet recorded" in title for title in outline), outline
