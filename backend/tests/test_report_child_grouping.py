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


def _meta() -> ReportMeta:
    return ReportMeta(title="Workshop", subtitle="Cluster",
                      generated_at="2026-08-07T00:00:00Z")


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _build(data: WorkshopData, template_id: str = "DETAILED_TECHNICAL"):
    document, _warnings = build_report(data, template_id, _resolver, meta=_meta())
    return document


def _costing_data() -> WorkshopData:
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
                    {"_entryId": "m4", "costSheetRef": "", "item": "Zari thread",
                     "quantity": 1, "unit": "m", "rate": 90, "amount": 90},
                    {"_entryId": "m5", "costSheetRef": "cmsdeletedsheet0001",
                     "item": "Lining cloth", "quantity": 1, "unit": "m", "rate": 60,
                     "amount": 60},
                ],
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



def _outline(document) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for block in document.blocks:
        if isinstance(block, HeadingBlock):
            out.append((f"H{block.level}", runs_text(block.runs)))
        elif isinstance(block, TableBlock):
            out.append(("TABLE", block.caption))
    return out


def _section(document, heading: str) -> list[tuple[str, str]]:
    out = _outline(document)
    start = next(i for i, (kind, text) in enumerate(out)
                 if kind == "H2" and text == heading)
    end = next((i for i in range(start + 1, len(out))
                if out[i][0] in {"H1", "H2"}), len(out))
    return out[start + 1:end]


def _under(document, heading: str, cells) -> list[tuple[str, list[str]]]:
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
                out[-1][1].append(runs_text(block.runs))
        elif out:
            out[-1][1].extend(cells(row) for row in block.rows)
    return out


def _groups(document, heading: str) -> list[tuple[str, list[str]]]:
    return _under(document, heading, lambda row: runs_text(row[0]))


def _group_rows(document, heading: str) -> list[tuple[str, list[str]]]:
    return _under(document, heading, lambda row: " ".join(runs_text(cell) for cell in row))


def _group_headings(document, heading: str) -> list[HeadingBlock]:
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
    return next(s for s in stages() if s.key == stage_key).title


def _first_cells(document, caption: str) -> list[list[str]]:
    return [[runs_text(row[0]) for row in b.rows]
            for b in document.blocks
            if isinstance(b, TableBlock) and b.caption == caption]



def test_material_lines_are_printed_under_the_cost_sheet_they_belong_to():
    groups = _groups(_build(_costing_data()), "Material cost lines")
    assert [name for name, _ in groups] == [
        "Sambalpuri stole", "Ikat cushion cover", "No cost sheet recorded",
    ]
    assert dict(groups)["Sambalpuri stole"] == ["Tussar yarn", "Natural dye"]
    assert dict(groups)["Ikat cushion cover"] == ["Cotton yarn"]


def test_no_group_carries_a_line_belonging_to_another_sheet():
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
    groups = [name for name, _ in _groups(_build(_costing_data()), "Labour cost lines")]
    assert groups == ["Sambalpuri stole", "Ikat cushion cover"]


def test_every_line_that_was_recorded_is_still_printed_exactly_once():
    document = _build(_costing_data())
    printed = [label for _name, lines in _groups(document, "Material cost lines")
               for label in lines]
    assert sorted(printed) == [
        "Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread",
    ]


def test_a_line_naming_no_cost_sheet_is_printed_under_a_heading_that_says_so():
    groups = _groups(_build(_costing_data()), "Material cost lines")
    assert groups[-1][0] == "No cost sheet recorded"
    assert set(groups[-1][1]) == {"Zari thread", "Lining cloth"}


def test_an_orphan_heading_is_not_printed_when_every_line_names_its_sheet():
    data = _costing_data()
    lines = data.collections["COSTING_MARKET_LINKAGE"]["costMaterialLine"]
    data.collections["COSTING_MARKET_LINKAGE"]["costMaterialLine"] = [
        row for row in lines if row["costSheetRef"] in {"cs1", "cs2"}
    ]
    groups = _groups(_build(data), "Material cost lines")
    assert [name for name, _ in groups] == ["Sambalpuri stole", "Ikat cushion cover"]


def test_a_sheet_with_no_lines_of_its_own_gets_no_empty_heading():
    data = _costing_data()
    data.collections["COSTING_MARKET_LINKAGE"]["costLabourLine"] = [
        {"_entryId": "l2", "costSheetRef": "cs1", "task": "Dyeing", "persons": 2,
         "days": 1, "rate": 350, "amount": 700},
    ]
    assert [name for name, _ in _groups(_build(data), "Labour cost lines")] == [
        "Sambalpuri stole",
    ]


def test_a_sheet_that_has_not_synced_yet_claims_none_of_the_orphans():
    data = _costing_data()
    data.collections["COSTING_MARKET_LINKAGE"]["costSheet"][0]["_entryId"] = ""
    groups = _groups(_build(data), "Material cost lines")

    assert [name for name, _ in groups] == ["Ikat cushion cover", "No cost sheet recorded"]
    assert set(dict(groups)["No cost sheet recorded"]) == {
        "Zari thread", "Lining cloth", "Tussar yarn", "Natural dye",
    }
    printed = [label for _name, lines in groups for label in lines]
    assert sorted(printed) == [
        "Cotton yarn", "Lining cloth", "Natural dye", "Tussar yarn", "Zari thread",
    ]


def test_a_sibling_collection_with_no_parent_is_left_flat_in_the_same_stage():
    document = _build(_costing_data())
    assert _groups(document, "Buyer linkages") == []
    assert _first_cells(document, "Buyer linkages") == [["Tantuja"]]
    assert _first_cells(document, "Cost sheets") == [
        ["Sambalpuri stole", "Ikat cushion cover"],
    ]


def test_a_group_is_headed_by_the_same_words_as_the_sheet_it_belongs_to():
    document = _build(_costing_data())
    sheet_labels = _first_cells(document, "Cost sheets")[0]
    group_names = [name for name, _ in _groups(document, "Material cost lines")]
    assert sheet_labels == ["Sambalpuri stole", "Ikat cushion cover"]
    assert group_names[:2] == sheet_labels


def test_a_sheet_that_names_no_product_still_heads_its_group_readably():
    data = _costing_data()
    data.collections["COSTING_MARKET_LINKAGE"]["costSheet"][1]["productRef"] = ""
    groups = [name for name, _ in _groups(_build(data), "Material cost lines")]
    assert groups[1] == "Cost sheets 2"



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
    document = _build(_prototype_data())
    assert _groups(document, "Stage logs") == [
        ("Stole A", ["Dyeing"]), ("Cushion B", ["Warping"]),
    ]
    assert _groups(document, "Material usage") == [
        ("Stole A", ["Tussar"]), ("Cushion B", ["Cotton"]),
    ]


def test_an_iteration_is_grouped_under_a_prototype_declared_in_another_stage():
    groups = dict(_group_rows(_build(_prototype_data()), _stage_title("PROTOTYPE_ITERATION")))
    assert list(groups) == ["Stole A", "Cushion B"]
    assert "Widened border" in " ".join(groups["Stole A"])
    assert "Softer weft" in " ".join(groups["Cushion B"])
    assert "Softer weft" not in " ".join(groups["Stole A"])
    assert "Widened border" not in " ".join(groups["Cushion B"])


def test_an_iteration_whose_prototype_is_missing_is_still_printed():
    data = _prototype_data()
    data.collections.pop("PROTOTYPE_DEVELOPMENT")
    groups = _group_rows(_build(data), _stage_title("PROTOTYPE_ITERATION"))
    assert [name for name, _ in groups] == ["No prototype recorded"]
    printed = " ".join(groups[0][1])
    assert "Widened border" in printed
    assert "Softer weft" in printed



def _filler(spec) -> object | None:
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


@pytest.mark.parametrize("stage_key", [
    "TRADITIONAL_PROCESS_BASELINE",
    "MARKET_ANALYSIS_DIRECTION",
    "INSPECTION_CLOSING",
    "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
    "MARKET_SURVEY_CAPTURE",
])
def test_a_parent_free_stage_is_untouched_by_the_grouping(stage_key, monkeypatch):
    data = _stage_data(stage_key)
    after = _build(data)

    monkeypatch.setattr(ReportBuilder, "_parent_groups", lambda self, entity, rows: None)
    before = _build(data)

    assert before.blocks == after.blocks
    assert any(isinstance(b, TableBlock) for b in after.blocks), f"{stage_key} produced no table"


def test_every_stage_declaring_no_parent_is_listed_in_that_guard():
    with_parents = {s.key for s in stages() if any(e.parent for e in s.entities)}
    assert with_parents == {
        "PROTOTYPE_DEVELOPMENT", "PROTOTYPE_ITERATION", "COSTING_MARKET_LINKAGE",
    }



def test_group_headings_are_numbered_beneath_the_collection_they_belong_to():
    document = _build(_costing_data())
    collection = next(b for b in document.blocks
                      if isinstance(b, HeadingBlock) and runs_text(b.runs) == "Material cost lines")
    assert [(b.number, runs_text(b.runs))
            for b in _group_headings(document, "Material cost lines")] == [
        (f"{collection.number}.1", "Sambalpuri stole"),
        (f"{collection.number}.2", "Ikat cushion cover"),
        (f"{collection.number}.3", "No cost sheet recorded"),
    ]
    labour = next(b for b in document.blocks
                  if isinstance(b, HeadingBlock) and runs_text(b.runs) == "Labour cost lines")
    head, _, last = collection.number.rpartition(".")
    assert labour.number == f"{head}.{int(last) + 1}"


def test_group_headings_carry_no_number_in_a_template_that_numbers_nothing():
    document = _build(_costing_data(), template_id="PHOTO_CATALOGUE")
    assert [b.number for b in document.blocks if isinstance(b, HeadingBlock)] == [
        "" for b in document.blocks if isinstance(b, HeadingBlock)
    ]


def test_a_group_heading_never_outranks_the_collection_it_is_part_of():
    costing = _section(_build(_costing_data()), "Material cost lines")
    assert [kind for kind, _ in costing][:2] == ["H3", "TABLE"]

    assert [kind for kind, _ in _section(_build(_prototype_data()), "Stage logs")][:2] == \
        ["H3", "H4"]

    outline = _outline(_build(_prototype_data()))
    stage = _stage_title("PROTOTYPE_ITERATION")
    start = next(i for i, (kind, text) in enumerate(outline) if text == stage)
    assert outline[start][0] == "H1"
    assert outline[start + 1] == ("H2", "Stole A")
    assert not any(text == "Iterations" for kind, text in outline if kind != "TABLE")



def _docx_text(document) -> str:
    data, _dropped = render_docx(document, lambda _ref: None)
    with zipfile.ZipFile(BytesIO(data)) as archive:
        return archive.read("word/document.xml").decode("utf-8")


_MATERIAL_SECTION = [
    "Sambalpuri stole", "Tussar yarn", "Natural dye",
    "Ikat cushion cover", "Cotton yarn",
    "No cost sheet recorded", "Zari thread", "Lining cloth",
]


def _ordered(section: str, expected: list[str]) -> None:
    for needle in expected:
        assert needle in section, f"{needle!r} never reached the material cost section"
    found = [section.index(needle) for needle in expected]
    assert found == sorted(found), dict(zip(expected, found))


def test_the_docx_shows_each_sheets_lines_under_that_sheets_heading():
    xml = _docx_text(_build(_costing_data()))
    section = xml[xml.index("Material cost lines"):xml.index("Labour cost lines")]
    _ordered(section, _MATERIAL_SECTION)
    assert 'w:val="Heading3"' in section


def _pdf_text(document) -> str:
    pypdf = pytest.importorskip("pypdf")
    data, _dropped = render_pdf(document, lambda _ref: None)
    pages = [page.extract_text() or "" for page in pypdf.PdfReader(BytesIO(data)).pages]
    return " ".join(" ".join(p for p in pages if "........" not in p).split())


def test_the_pdf_shows_each_sheets_lines_under_that_sheets_heading():
    text = _pdf_text(_build(_costing_data()))
    section = text[text.index("Material cost lines"):text.index("Labour cost lines")]
    _ordered(section, _MATERIAL_SECTION)


def test_the_pdf_bookmark_outline_carries_the_group_headings():
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
