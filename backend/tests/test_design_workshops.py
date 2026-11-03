import inspect
import uuid
from datetime import UTC, datetime
from types import SimpleNamespace
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.api.routes import design_workshops as routes
from app.services.report_builder import (
    ReportBuilder,
    WorkshopData,
    build_report,
    format_value,
)
from app.services.report_model import (
    CoverBlock,
    ImageRef,
    MetricRowBlock,
    ReportMeta,
    TableBlock,
    runs_text,
)
from app.services.report_templates import TEMPLATES, template, template_choices
from app.services.report_theme import theme_from_accent
from app.services.stage_schema import FieldSpec, FieldType, Tier, all_entities


def _f(type: FieldType, **kw) -> FieldSpec:
    return FieldSpec(key="x", label="X", type=type, **kw)


@pytest.mark.parametrize(
    ("amount", "expected"),
    [
        ("562.50", "₹ 562.50"),
        ("1250.00", "₹ 1,250.00"),
        ("209000.00", "₹ 2,09,000.00"),
        ("12345678.00", "₹ 1,23,45,678.00"),
    ],
)
def test_money_is_grouped_the_indian_way(amount, expected):
    assert format_value(_f(FieldType.MONEY), amount) == expected


def test_negative_money_keeps_its_sign_outside_the_symbol():
    assert format_value(_f(FieldType.MONEY), "-500.00") == "-₹ 500.00"


def test_unparseable_money_prints_verbatim_rather_than_vanishing():
    assert format_value(_f(FieldType.MONEY), "about two thousand") == "about two thousand"


@pytest.mark.parametrize("stored", ["nan", "NaN", "inf", "-inf", float("nan")])
def test_a_stored_non_finite_number_is_never_dressed_up_as_an_amount(stored):
    assert format_value(_f(FieldType.MONEY), stored) == str(stored)
    assert format_value(_f(FieldType.PERCENT), stored) == str(stored)
    assert format_value(_f(FieldType.DECIMAL), stored) == str(stored)


def test_dates_print_as_a_person_writes_them():
    assert format_value(_f(FieldType.DATE), "2026-02-10") == "10 Feb 2026"
    assert format_value(_f(FieldType.DATE), "not a date") == "not a date"


def test_enums_print_their_label_not_their_token():
    spec = _f(FieldType.ENUM, enum="PRODUCT_CATEGORY")
    assert format_value(spec, "TABLE_LINEN") == "Table linen"


def test_multi_enum_joins_labels():
    spec = _f(FieldType.MULTI_ENUM, enum="MARKET_CHANNEL")
    assert format_value(spec, ["EMPORIUM", "ONLINE"]) == "Government emporium, Online marketplace"


def test_booleans_print_as_yes_and_no():
    assert format_value(_f(FieldType.BOOL), True) == "Yes"
    assert format_value(_f(FieldType.BOOL), False) == "No"


def test_units_are_appended():
    assert format_value(_f(FieldType.DECIMAL, unit="cm"), 180) == "180 cm"
    assert format_value(_f(FieldType.INT, unit="days"), 15) == "15 days"


def test_large_plain_numbers_are_grouped_too():
    assert format_value(_f(FieldType.INT), 209000) == "2,09,000"


def test_empty_values_print_nothing_never_the_word_none():
    for empty in (None, "", []):
        assert format_value(_f(FieldType.TEXT), empty) == ""


def test_media_never_prints_as_text():
    assert format_value(_f(FieldType.IMAGE), "cmshxj9id0001") == ""
    assert format_value(_f(FieldType.IMAGE_LIST), ["a", "b"]) == ""


def test_geo_prints_readable_coordinates():
    assert (
        format_value(_f(FieldType.GEO), {"lat": 21.33331, "lon": 83.61672}) == "21.33331, 83.61672"
    )



def test_every_template_is_choosable_and_distinct():
    ids = [t.id for t in TEMPLATES]
    assert len(ids) == len(set(ids))
    assert {c["id"] for c in template_choices()} == set(ids)


def test_an_unknown_template_falls_back_rather_than_raising():
    assert template("NO_SUCH_TEMPLATE").id == TEMPLATES[0].id


def test_every_template_section_names_exactly_one_thing():
    from app.services.report_templates import TemplateSection

    with pytest.raises(ValueError):
        TemplateSection()
    with pytest.raises(ValueError):
        TemplateSection(stage_key="WORKSHOP_SETUP", special="COVER")


def test_every_stage_key_a_template_names_exists():
    known = {s.key for s in __import__("app.services.stage_schema", fromlist=["STAGES"]).STAGES}
    for t in TEMPLATES:
        for section in t.sections:
            if section.stage_key:
                assert section.stage_key in known, f"{t.id} -> {section.stage_key}"


def test_the_compact_template_admits_only_basic_fields():
    assert template("COMPACT_SUMMARY").max_tier is Tier.BASIC



def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop", "subtitle": "Cluster", "generated_at": "2026-08-07T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


def _data(**kw) -> WorkshopData:
    base = {"workshop_id": "w1", "title": "Workshop"}
    base.update(kw)
    return WorkshopData(**base)


def _resolver(media_id: str):
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/png")


def test_an_empty_workshop_still_produces_a_document():
    doc, warnings = build_report(_data(), "DCH_STANDARD", _resolver, meta=_meta())
    assert any(isinstance(b, CoverBlock) for b in doc.blocks)
    assert warnings


def test_cover_rows_come_from_the_cover_field_role():
    doc, _w = build_report(
        _data(
            singletons={
                "WORKSHOP_SETUP": {
                    "workshopTitle": "W",
                    "craftName": "Sambalpuri Ikat",
                    "clusterName": "Bargarh",
                    "designerName": "A. Sharma",
                }
            }
        ),
        "DCH_STANDARD",
        _resolver,
        meta=_meta(),
    )
    cover = next(b for b in doc.blocks if isinstance(b, CoverBlock))
    labels = {label for label, _value in cover.info_rows}
    assert "Craft" in labels and "Designer" in labels


def test_a_collection_becomes_a_table_with_its_declared_columns():
    doc, _w = build_report(
        _data(
            collections={
                "WORKSHOP_PLAN_PARTICIPANTS_OPENING": {
                    "participant": [
                        {
                            "serialNo": 1,
                            "name": "Bhikari Meher",
                            "specialisation": "Tie-and-dye",
                            "experienceYears": 28,
                            "isMasterCraftsperson": True,
                        },
                        {
                            "serialNo": 2,
                            "name": "Sunita Bag",
                            "specialisation": "Weaving",
                            "experienceYears": 12,
                            "isMasterCraftsperson": False,
                        },
                    ]
                }
            }
        ),
        "DCH_STANDARD",
        _resolver,
        meta=_meta(),
    )
    tables = [b for b in doc.blocks if isinstance(b, TableBlock) and len(b.rows) == 2]
    assert tables, "the participant list should render as a table"
    table = tables[0]
    assert "Artisan name" in [c.header for c in table.columns]
    assert runs_text(table.rows[0][1]) == "Bhikari Meher"
    assert any(runs_text(cell) == "Yes" for cell in table.rows[0])


def test_table_column_widths_always_sum_to_one_hundred():
    doc, _w = build_report(
        _data(
            collections={
                "SKETCH_DEVELOPMENT": {
                    "sketch": [
                        {
                            "sketchNo": "SK-01",
                            "name": "Runner",
                            "category": "TABLE_LINEN",
                            "expectedPrice": "1900.00",
                        }
                    ]
                }
            }
        ),
        "DETAILED_TECHNICAL",
        _resolver,
        meta=_meta(),
    )
    for block in doc.blocks:
        if isinstance(block, TableBlock) and block.columns:
            assert sum(c.width_pct for c in block.columns) == pytest.approx(100.0, abs=0.5)


def test_headline_counts_are_derived_not_typed():
    doc, _w = build_report(
        _data(
            collections={
                "SKETCH_DEVELOPMENT": {
                    "sketch": [{"sketchNo": f"SK-{i}", "name": str(i)} for i in range(3)]
                },
                "PROTOTYPE_DEVELOPMENT": {"prototype": [{"prototypeCode": "PR-01", "name": "p"}]},
            }
        ),
        "DCH_STANDARD",
        _resolver,
        meta=_meta(),
    )
    metrics = next(b for b in doc.blocks if isinstance(b, MetricRowBlock))
    as_dict = {label: value for label, value, _unit in metrics.metrics}
    assert as_dict["Sketches"] == "3"
    assert as_dict["Prototypes"] == "1"


def test_a_tier_capped_template_omits_standard_fields():
    data = _data(
        singletons={
            "CLUSTER_CRAFT_BACKGROUND": {
                "clusterIntroduction": "BASIC TIER TEXT",
                "culturalSignificance": "STANDARD TIER TEXT",
            }
        }
    )
    compact, _w = build_report(data, "COMPACT_SUMMARY", _resolver, meta=_meta())
    detailed, _w2 = build_report(data, "DETAILED_TECHNICAL", _resolver, meta=_meta())

    def text_of(doc):
        return " ".join(runs_text(getattr(b, "runs", ()) or ()) for b in doc.blocks)

    assert "BASIC TIER TEXT" in text_of(compact)
    assert "STANDARD TIER TEXT" not in text_of(compact)
    assert "STANDARD TIER TEXT" in text_of(detailed)


def test_report_generation_and_archive_stages_never_print():
    doc, _w = build_report(
        _data(
            singletons={
                "REPORT_GENERATION": {"templateId": "DCH_STANDARD"},
                "DATA_QUALITY_ARCHIVE": {"reportSaved": True},
            }
        ),
        "DCH_STANDARD",
        _resolver,
        meta=_meta(),
    )
    headings = " ".join(runs_text(b.runs) for b in doc.blocks if hasattr(b, "level"))
    assert "Report Generation" not in headings
    assert "Data Quality" not in headings


def test_an_unresolvable_photo_is_skipped_not_fatal():
    doc, _w = build_report(
        _data(
            collections={
                "SKETCH_DEVELOPMENT": {
                    "sketch": [{"sketchNo": "SK-01", "name": "Runner", "image": "missing"}]
                }
            }
        ),
        "DCH_STANDARD",
        lambda _id: None,
        meta=_meta(),
    )
    assert doc.images == ()


def test_warnings_name_the_stage_and_the_missing_fields():
    doc, warnings = build_report(
        _data(singletons={"WORKSHOP_SETUP": {"workshopTitle": "W"}}),
        "DCH_STANDARD",
        _resolver,
        meta=_meta(),
    )
    setup_warning = next(w for w in warnings if "Stage 1" in w)
    assert "not recorded" in setup_warning.lower()
    assert doc.warnings == ()


def test_the_document_carries_the_template_palette_unless_one_is_supplied():
    default, _w = build_report(_data(), "DCH_STANDARD", _resolver, meta=_meta())
    assert default.theme == template("DCH_STANDARD").theme

    maroon = theme_from_accent("802F42", base=template("DCH_STANDARD").theme)
    recoloured, _w2 = build_report(_data(), "DCH_STANDARD", _resolver, meta=_meta(), theme=maroon)
    assert recoloured.theme == maroon
    assert recoloured.theme.table_header_fill == "802F42"
    assert recoloured.theme.body_font == template("DCH_STANDARD").theme.body_font


def test_every_entity_the_registry_declares_is_reachable_by_some_template():
    printed: set[str] = set()
    for t in TEMPLATES:
        for section in t.sections:
            if section.stage_key:
                printed.add(section.stage_key)
    from app.services.report_templates import NON_PRINTING_STAGES

    for spec, _entity in all_entities():
        if spec.key in NON_PRINTING_STAGES:
            continue
        assert spec.key in printed, f"stage {spec.key} appears in no template"


def test_builder_is_deterministic():
    data = _data(singletons={"WORKSHOP_SETUP": {"workshopTitle": "W", "craftName": "Ikat"}})
    a, _ = build_report(data, "DCH_STANDARD", _resolver, meta=_meta())
    b, _ = build_report(data, "DCH_STANDARD", _resolver, meta=_meta())
    assert len(a.blocks) == len(b.blocks)
    assert [type(x).__name__ for x in a.blocks] == [type(x).__name__ for x in b.blocks]


def test_row_labels_fall_back_through_label_field_then_text_then_ordinal():
    builder = ReportBuilder(_data(), template("DCH_STANDARD"), _resolver, meta=_meta())
    entity = next(e for _s, e in all_entities() if e.key == "sketch")
    assert builder._row_label(entity, {"name": "Runner"}, 1) == "Runner"
    assert builder._row_label(entity, {"sketchNo": "SK-09"}, 1) == "SK-09"
    assert builder._row_label(entity, {}, 7).endswith("7")


def test_a_heading_taken_from_free_text_is_cut_at_a_sentence_not_at_a_byte():
    from app.services.report_builder import _heading_summary

    assert (
        _heading_summary(
            "SK-01 is taken forward as the first prototype. It is the only drawing on the sheet "
            "that the panel agreed on."
        )
        == "SK-01 is taken forward as the first prototype."
    )


def test_a_heading_with_no_sentence_short_enough_is_elided_on_a_word_boundary():
    from app.services.report_builder import _heading_summary

    long_run = (
        "A single very long sentence with no terminator at all that simply runs on and on past "
        "the eighty character mark without stopping"
    )
    heading = _heading_summary(long_run)
    assert len(heading) <= 80
    assert heading.endswith("…"), "an elision must be visible as one"
    assert long_run.startswith(heading[:-1].rstrip()), "and must not invent words"
    assert long_run[len(heading[:-1].rstrip())] == " "


def test_a_decimal_point_is_not_the_end_of_a_sentence():
    from app.services.report_builder import _heading_summary

    assert _heading_summary("Priced at 1250.10 per metre.") == "Priced at 1250.10 per metre."


def test_a_short_answer_is_left_exactly_as_it_is():
    from app.services.report_builder import _heading_summary

    assert _heading_summary("Runner, 180 x 40 cm") == "Runner, 180 x 40 cm"
    assert _heading_summary("") == ""
    assert _heading_summary("   ") == ""



def _all_text(doc) -> str:
    from app.services.report_model import (
        BulletListBlock,
        CoverBlock,
        KeyValueBlock,
        MetricRowBlock,
        TableBlock,
    )

    out: list[str] = []
    for block in doc.blocks:
        out.append(runs_text(getattr(block, "runs", ()) or ()))
        if isinstance(block, TableBlock):
            out += [c.header for c in block.columns]
            for row in block.rows:
                out += [runs_text(cell) for cell in row]
            for cell in block.total_row or ():
                out.append(runs_text(cell))
        elif isinstance(block, KeyValueBlock):
            for label, value in block.pairs:
                out += [label, runs_text(value)]
        elif isinstance(block, BulletListBlock):
            out += [runs_text(item) for item in block.items]
        elif isinstance(block, MetricRowBlock):
            for label, value, unit in block.metrics:
                out += [label, value, unit]
        elif isinstance(block, CoverBlock):
            out += [block.title, block.subtitle]
            for label, value in block.info_rows:
                out += [label, value]
    return " | ".join(out)


@pytest.mark.parametrize(
    ("stage_key", "entity_key", "row"),
    [
        (
            "SKETCH_DEVELOPMENT",
            "sketch",
            {
                "sketchNo": "SK-01",
                "name": "Pasapalli runner",
                "category": "TABLE_LINEN",
                "expectedPrice": "1900.00",
                "intendedUse": "Home dining",
                "designerNotes": "Repeat reduced from 12 cm to 8 cm.",
            },
        ),
        (
            "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
            "participant",
            {
                "serialNo": 1,
                "name": "Bhikari Meher",
                "specialisation": "Tie-and-dye",
                "experienceYears": 28,
                "isMasterCraftsperson": True,
                "village": "Barpali",
                "artisanCardNo": "OD/BGH/1188",
            },
        ),
        (
            "PROTOTYPE_DEVELOPMENT",
            "prototype",
            {
                "prototypeCode": "PR-01",
                "name": "Table runner",
                "sketchRef": "SK-01",
                "artisanRef": "Bhikari Meher",
                "materials": ["Cotton"],
                "makingTimeDays": 5.5,
                "processSummary": "Tied over three days.",
            },
        ),
    ],
)
def test_no_presentation_silently_drops_a_filled_field(stage_key, entity_key, row):
    from app.services.stage_schema import ReportRole, all_entities

    entity = next(e for _s, e in all_entities() if e.key == entity_key)
    doc, _warnings = build_report(
        _data(collections={stage_key: {entity_key: [row]}}),
        "DETAILED_TECHNICAL",
        _resolver,
        meta=_meta(),
    )
    text = _all_text(doc)

    for spec in entity.fields:
        if spec.key not in row or spec.report_role is ReportRole.HIDDEN or spec.caption_for:
            continue
        expected = format_value(spec, row[spec.key])
        if not expected:
            continue
        assert expected in text, (
            f"{entity_key}.{spec.key} ({spec.report_role.value}) was filled in as "
            f"{expected!r} and appears nowhere in the report"
        )



def _header(warnings):
    from app.api.routes.design_workshops import _warnings_header

    return _warnings_header(warnings)


def test_short_warning_lists_are_carried_whole():
    assert _header([]) == ""
    assert _header(["One thing.", "Another thing."]) == "One thing.; Another thing."


def test_a_long_warning_list_drops_whole_warnings_and_says_how_many():
    from app.api.routes.design_workshops import _WARNINGS_HEADER_BUDGET

    items = [
        f"Stage {n} (Some Stage): 3 required field(s) not recorded - {'x' * 60}."
        for n in range(1, 21)
    ]
    header = _header(items)
    pieces = header.split("; ")

    assert len(header) <= _WARNINGS_HEADER_BUDGET
    assert pieces[-1].endswith("The report preview lists all of them.")
    assert int(pieces[-1].split()[0]) == len(items) - (len(pieces) - 1)
    assert all(piece in items for piece in pieces[:-1])


def test_a_single_warning_wider_than_the_budget_is_marked_rather_than_passed_off_whole():
    from app.api.routes.design_workshops import _WARNINGS_HEADER_BUDGET

    header = _header(["y" * 4000, "A second warning."])
    assert len(header) <= _WARNINGS_HEADER_BUDGET
    assert header.split("; ")[0].endswith("...")
    assert "1 further warning did not fit" in header


def test_non_ascii_is_replaced_rather_than_raising_inside_starlette():
    header = _header(["Craft ସମ୍ବଲପୁରୀ has no cost sheet."])
    assert header.isascii()
    assert "has no cost sheet." in header


def test_a_semicolon_inside_a_warning_does_not_split_it_on_the_designers_screen():
    typed_by_a_designer = (
        "1 questionnaire attached to this workshop has no recorded answers and was left out "
        "of the questionnaire annexure (Loom survey; round two)."
    )
    header = _header([typed_by_a_designer, "Stage 3 (Workshop Plan): 1 required field(s)."])

    pieces = [piece.strip() for piece in header.split(";") if piece.strip()]
    assert len(pieces) == 2, (
        f"two warnings reached the designer as {len(pieces)} pieces, so at least one of them is a "
        f"half-sentence: {pieces}"
    )
    assert pieces[0].endswith("(Loom survey, round two).")
    assert "round two" in pieces[0]



def _preview_meta_of(**meta_kwargs):
    from app.api.routes.design_workshops import _preview_meta
    from app.services.report_model import ReportDocument, ReportMeta, ReportTheme

    document = ReportDocument(
        meta=ReportMeta(title="A workshop", **meta_kwargs), theme=ReportTheme(), blocks=()
    )
    return _preview_meta(document)


def test_the_preview_carries_both_halves_of_the_page_geometry():
    from app.services.report_model import ReportMeta

    payload = _preview_meta_of()

    assert payload["pageSize"] == "A4"
    assert payload["marginMm"] == ReportMeta(title="x").margin_mm


def test_the_preview_reports_the_documents_own_margin_and_not_a_constant():
    from app.services.report_model import PageSize

    payload = _preview_meta_of(page_size=PageSize.LETTER, margin_mm=18.0)

    assert payload["marginMm"] == 18.0
    assert payload["pageSize"] == "LETTER"



def _routes():
    from app.api.routes import design_workshops as routes

    return routes



def test_the_edit_reaches_exactly_what_the_create_reaches():
    from app.schemas.design_workshops import DesignWorkshopCreate

    routes = _routes()
    body_fields = set(DesignWorkshopCreate.model_fields)
    designer_keys = {"designerUserId", "designerUserIds"}
    create_writes = (body_fields - designer_keys) | routes._CREATE_ONLY_STAMPS | {"status"}
    patch_writes = set(routes._HEADER_TEXT_COLUMNS) | set(routes._HEADER_DATE_COLUMNS)

    assert patch_writes == create_writes - routes._CREATE_ONLY_STAMPS

    assert body_fields - patch_writes == designer_keys
    assert set(routes._NEVER_PATCHABLE) >= designer_keys


def test_the_shared_column_list_still_spells_the_create_loop_it_replaced():
    assert _routes()._CREATE_OPTIONAL_COLUMNS == (
        "workshopKind",
        "craftId",
        "craftName",
        "clusterName",
        "state",
        "district",
        "notes",
        "workshopId",
    )


def test_no_column_is_both_writable_and_refused():
    routes = _routes()
    writable = set(routes._HEADER_TEXT_COLUMNS) | set(routes._HEADER_DATE_COLUMNS)
    assert writable.isdisjoint(routes._NEVER_PATCHABLE)
    assert writable >= routes._HEADER_REQUIRED_COLUMNS


def test_every_key_the_header_serialises_is_either_writable_or_refused_by_name():
    from app.services.design_workshops import workshop_summary

    routes = _routes()
    stamp = datetime(2026, 2, 10, tzinfo=UTC)
    record = SimpleNamespace(
        id="dw_1",
        title="Ikat in Barpali",
        templateId="DCH_STANDARD",
        status="DRAFT",
        workshopCode="DCH/2026/017",
        workshopKind="DESIGN_PROTOTYPE_DEVELOPMENT",
        scheme="NHDP",
        craftName="Ikat",
        clusterName="Barpali",
        state="Odisha",
        district="Bargarh",
        venue="Weavers' Service Centre",
        startDate=stamp,
        endDate=stamp,
        designerName="Rekha Sahu",
        implementingAgency="DC Handicrafts",
        sponsor="Ministry of Textiles",
        notes="Second sitting.",
        workshopId=None,
        createdById="user_1",
        createdAt=stamp,
        updatedAt=stamp,
        deletedAt=None,
        dictationConsent="NOT_RECORDED",
        dictationConsentAt=None,
        dictationConsentById=None,
        reviewNotes=None,
        reviewedById=None,
        reviewedAt=None,
        submissionRound=0,
    )
    writable = set(routes._HEADER_TEXT_COLUMNS) | set(routes._HEADER_DATE_COLUMNS)
    unexplained = set(workshop_summary(record)) - writable - set(routes._NEVER_PATCHABLE)
    assert not unexplained, (
        f"{sorted(unexplained)} is serialised to every client and would be answered "
        "'Extra inputs are not permitted' if one sent it back. Add it to _HEADER_TEXT_COLUMNS or "
        "to _NEVER_PATCHABLE with the reason beside it."
    )
    assert "dictationConsentByName" in routes._NEVER_PATCHABLE
    assert {"reviewNotes", "reviewedById", "reviewedAt", "submissionRound"} <= set(
        routes._NEVER_PATCHABLE
    )
    assert {"approvedById", "approvedAt", "handedOnAt", "lastHandedInAt"} <= set(routes._NEVER_PATCHABLE)
    assert len(workshop_summary(record)) == 38, (
        "the header dict changed size; the counts in this test's docstring are the two sentences a "
        "reader trusts, and they are wrong the moment this number moves"
    )


def test_a_header_patch_cannot_make_a_decision_edge():
    from app.schemas import design_workshop_review_loop as loop

    routes = _routes()
    assert "design_workshop_review_loop" in inspect.getsource(routes.update_design_workshop), (
        "the PATCH handler no longer consults the transition graph; any status may follow any other "
        "again, which is the state this repository was in before 2026-09-13"
    )
    for current, nxt in loop.DECISION_EDGES:
        assert loop.transition_refusal(current, nxt), f"{current} -> {nxt} is patchable"


def test_a_header_patch_cannot_archive_an_approved_report():
    from app.schemas import design_workshop_review_loop as loop

    refusal = loop.transition_refusal("APPROVED", "ARCHIVED")
    assert refusal, "an approved report can be archived by a header edit again"
    assert "hand-on" in refusal and "revise" in refusal



def test_a_field_the_body_never_mentions_is_not_in_the_write_at_all():
    assert _routes()._header_patch_data({"title": "Renamed"}) == {"title": "Renamed"}


def test_an_explicit_null_survives_all_the_way_to_the_write():
    assert _routes()._header_patch_data({"notes": None}) == {"notes": None}


def test_a_blank_box_clears_the_column_rather_than_storing_an_empty_string():
    data = _routes()._header_patch_data({"craftName": "", "clusterName": "   ", "notes": " x "})
    assert data == {"craftName": None, "clusterName": None, "notes": "x"}


@pytest.mark.parametrize("blank", [None, "", "   "])
def test_emptying_a_not_null_column_is_a_422_naming_it_and_never_a_500(blank):
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as raised:
        _routes()._header_patch_data({"title": blank})
    assert raised.value.status_code == 422
    assert "title" in str(raised.value.detail)


def test_a_date_this_server_cannot_read_is_refused_rather_than_clearing_the_column():
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as raised:
        _routes()._header_patch_data({"startDate": "10-02-2026 or thereabouts"})
    assert raised.value.status_code == 422
    assert "startDate" in str(raised.value.detail)
    assert _routes()._header_patch_data({"startDate": None, "endDate": ""}) == {
        "startDate": None,
        "endDate": None,
    }


def test_the_immutable_refusal_names_every_field_it_objected_to_not_the_first():
    import pydantic

    routes = _routes()
    with pytest.raises(pydantic.ValidationError) as raised:
        routes.DesignWorkshopPatch.model_validate(
            {"title": "Fine", "designerName": "Somebody Else", "workshopCode": "DCH/2026/017"}
        )
    message = str(raised.value)
    assert "designerName" in message
    assert "workshopCode" in message
    assert "stage 1" in message

    with pytest.raises(pydantic.ValidationError) as typo:
        routes.DesignWorkshopPatch.model_validate({"crafName": "Ikat"})
    assert typo.value.errors()[0]["type"] == "extra_forbidden"


def test_the_edit_body_inherits_the_creates_bounds_rather_than_restating_them():
    import pydantic

    routes = _routes()
    with pytest.raises(pydantic.ValidationError) as bad_status:
        routes.DesignWorkshopPatch.model_validate({"status": "FINISHED"})
    assert "SUBMITTED" in str(bad_status.value)
    with pytest.raises(pydantic.ValidationError) as bad_template:
        routes.DesignWorkshopPatch.model_validate({"templateId": "NOT_A_TEMPLATE"})
    assert "DCH_STANDARD" in str(bad_template.value)


PASSWORD = "dw-header-edit-password"

HEADER_ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Header Admin"),
    ("designer", "DESIGNER", "Rekha Sahu"),
    ("stranger", "DESIGNER", "Unrelated Designer"),
    ("professor", "PROFESSOR", "Senior Professor"),
)

PROFILE_AT_CREATE = {"displayName": "Rekha Sahu", "institution": "NIFT Bhubaneswar"}
PROFILE_AFTER = {"displayName": "Dr Rekha Sahu", "institution": "NID Ahmedabad"}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def header_world():
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import hash_password
    from app.main import app

    stamp = uuid.uuid4().hex[:8]

    def address(slug: str) -> str:
        return f"dwheader-{slug}-{stamp}@example.org"

    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in HEADER_ACCOUNTS:
            people[slug] = await db.user.create(
                data={
                    "email": address(slug),
                    "name": name,
                    "role": role,
                    "passwordHash": hash_password(PASSWORD),
                }
            )
        await db.designerroster.create(
            data={
                "email": address("designer"),
                "fullName": PROFILE_AT_CREATE["displayName"],
                "institution": PROFILE_AT_CREATE["institution"],
                "isActive": True,
                "addedById": people["admin"].id,
            }
        )
        await db.designerprofile.create(data={"userId": people["designer"].id, **PROFILE_AT_CREATE})
        granted = await db.designworkshop.create(
            data={
                "title": f"Granted workshop {stamp}",
                "templateId": "DCH_STANDARD",
                "status": "DRAFT",
                "createdById": people["admin"].id,
            }
        )
        for slug in ("designer", "professor"):
            await db.designworkshopviewer.create(
                data={
                    "designWorkshopId": granted.id,
                    "userId": people[slug].id,
                    "grantedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "people": people,
            "address": address,
            "stamp": stamp,
            "granted_workshop": granted.id,
        }


def _auth(world: dict[str, Any], slug: str) -> dict[str, str]:
    from app.core.security import create_access_token

    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _new_workshop(world: dict[str, Any], **body: Any) -> dict[str, Any]:
    response = world["client"].post(
        "/api/design-workshops",
        json={"title": f"Header test {uuid.uuid4().hex[:8]}", **body},
        headers=_auth(world, "admin"),
    )
    assert response.status_code == 201, response.text
    return response.json()


@needs_db
@pytest.mark.anyio
def test_an_edit_writes_what_it_names_and_leaves_everything_else_alone(header_world):
    created = _new_workshop(header_world, craftName="Ikat", clusterName="Barpali", notes="Day one.")
    response = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"title": "Ikat, second sitting", "notes": "Day two."},
        headers=_auth(header_world, "admin"),
    )
    assert response.status_code == 200, response.text
    after = response.json()
    assert after["title"] == "Ikat, second sitting"
    assert after["notes"] == "Day two."
    assert after["craftName"] == "Ikat"
    assert after["clusterName"] == "Barpali"
    assert after["status"] == created["status"]
    assert after["createdById"] == created["createdById"]
    reread = header_world["client"].get(
        f"/api/design-workshops/{created['id']}", headers=_auth(header_world, "admin")
    )
    assert reread.status_code == 200, reread.text
    assert {key: reread.json()[key] for key in after} == after


@needs_db
@pytest.mark.anyio
def test_an_explicit_null_clears_the_stored_value_and_a_missing_key_does_not(header_world):
    created = _new_workshop(header_world, notes="Delete me.", craftName="Ikat")
    cleared = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"notes": None, "craftName": ""},
        headers=_auth(header_world, "admin"),
    )
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["notes"] is None
    assert cleared.json()["craftName"] is None

    untouched = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"title": "Renamed once more"},
        headers=_auth(header_world, "admin"),
    )
    assert untouched.status_code == 200, untouched.text
    assert untouched.json()["notes"] is None
    assert untouched.json()["title"] == "Renamed once more"


@needs_db
@pytest.mark.anyio
def test_an_immutable_field_is_refused_by_name_and_nothing_beside_it_is_written(header_world):
    created = _new_workshop(header_world)
    refused = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"title": "Should not land", "designerName": "Somebody Else"},
        headers=_auth(header_world, "admin"),
    )
    assert refused.status_code == 422, refused.text
    assert "designerName" in refused.text
    assert "stage 1" in refused.text

    after = header_world["client"].get(
        f"/api/design-workshops/{created['id']}", headers=_auth(header_world, "admin")
    )
    assert after.json()["title"] == created["title"]

    team = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"designerUserIds": [header_world["people"]["designer"].id]},
        headers=_auth(header_world, "admin"),
    )
    assert team.status_code == 422, team.text
    assert "visibility settings" in team.text


@needs_db
@pytest.mark.anyio
def test_a_viewer_grant_is_not_permission_to_edit_and_a_stranger_is_told_nothing(header_world):
    workshop_id = header_world["granted_workshop"]
    body = {"title": "Edited by somebody"}

    professor = header_world["client"].patch(
        f"/api/design-workshops/{workshop_id}", json=body, headers=_auth(header_world, "professor")
    )
    assert professor.status_code == 403, professor.text
    assert "Designer access" in professor.text

    stranger = header_world["client"].patch(
        f"/api/design-workshops/{workshop_id}", json=body, headers=_auth(header_world, "stranger")
    )
    assert stranger.status_code == 404, stranger.text
    assert "Record not found" in stranger.text

    designer = header_world["client"].patch(
        f"/api/design-workshops/{workshop_id}",
        json={"title": "Edited by the granted designer"},
        headers=_auth(header_world, "designer"),
    )
    assert designer.status_code == 200, designer.text
    assert designer.json()["title"] == "Edited by the granted designer"


@needs_db
@pytest.mark.anyio
def test_an_edit_does_not_rewrite_the_designer_details_from_a_since_changed_profile(header_world):
    created = _new_workshop(
        header_world, designerUserId=header_world["people"]["designer"].id, craftName="Ikat"
    )
    assert created["designerName"] == PROFILE_AT_CREATE["displayName"]

    changed = header_world["client"].put(
        f"/api/designers/{header_world['people']['designer'].id}/profile",
        json=PROFILE_AFTER,
        headers=_auth(header_world, "admin"),
    )
    assert changed.status_code == 200, changed.text
    assert changed.json()["institution"] == PROFILE_AFTER["institution"]

    edited = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"title": "Renamed a month later"},
        headers=_auth(header_world, "designer"),
    )
    assert edited.status_code == 200, edited.text
    assert edited.json()["title"] == "Renamed a month later"
    assert edited.json()["designerName"] == PROFILE_AT_CREATE["displayName"]

    detail = header_world["client"].get(
        f"/api/design-workshops/{created['id']}", headers=_auth(header_world, "designer")
    )
    assert detail.status_code == 200, detail.text
    stage_one = detail.json()["stages"]["WORKSHOP_SETUP"]["singleton"]
    assert stage_one["designerName"] == PROFILE_AT_CREATE["displayName"]
    assert stage_one["designerInstitution"] == PROFILE_AT_CREATE["institution"]



def _dw_row(rid: str, *, created_at: datetime | None = None) -> Any:
    return SimpleNamespace(
        id=rid,
        title=f"Workshop {rid}",
        templateId="default",
        status="DRAFT",
        workshopCode=None,
        workshopKind=None,
        scheme=None,
        craftName=None,
        clusterName=None,
        state=None,
        district=None,
        venue=None,
        startDate=None,
        endDate=None,
        designerName=None,
        implementingAgency=None,
        sponsor=None,
        notes=None,
        workshopId=None,
        createdById="u-admin",
        createdAt=created_at,
        updatedAt=None,
        deletedAt=None,
        deletedById=None,
        dictationConsent="NOT_RECORDED",
        dictationConsentAt=None,
        dictationConsentById=None,
        reviewNotes=None,
        reviewedById=None,
        reviewedAt=None,
        submissionRound=0,
    )


class _TiedDesignWorkshopTable:
    def __init__(self, rows: list[Any]) -> None:
        self.rows = rows
        self.reads = 0
        self.finds: list[dict[str, Any]] = []
        self.counts: list[dict[str, Any]] = []

    async def count(self, where: dict[str, Any]) -> int:
        self.counts.append(where)
        return len(self.rows)

    async def find_many(self, **kwargs: Any) -> list[Any]:
        self.finds.append(kwargs)
        order = kwargs["order"]
        clauses = order if isinstance(order, list) else [order]
        if any("id" in clause for clause in clauses):
            ordered = self.rows
        else:
            self.reads += 1
            ordered = list(reversed(self.rows)) if self.reads % 2 == 0 else list(self.rows)
        skip = kwargs.get("skip") or 0
        take = kwargs.get("take")
        return ordered[skip : skip + take] if take else ordered


async def test_a_paged_walk_does_not_repeat_or_skip_a_row_that_ties_on_the_sort_key(monkeypatch):
    shared = datetime(2026, 8, 1, 9, 0, tzinfo=UTC)
    fake = _TiedDesignWorkshopTable(
        [_dw_row("w1", created_at=shared), _dw_row("w2", created_at=shared)]
    )
    monkeypatch.setattr(routes, "db", SimpleNamespace(designworkshop=fake))
    admin = SimpleNamespace(id="u-admin", email="admin@example.test", role="ADMIN")

    page_one = await routes.list_design_workshops(page=1, pageSize=1, current_user=admin)
    page_two = await routes.list_design_workshops(page=2, pageSize=1, current_user=admin)

    walked = [item["id"] for item in page_one["items"]] + [item["id"] for item in page_two["items"]]
    assert sorted(walked) == ["w1", "w2"], (
        f"the walk saw {walked!r} — a tie must not let one row surface twice while the other never does"
    )
    assert fake.finds[0]["order"] == [{"createdAt": "desc"}, {"id": "desc"}]


@needs_db
@pytest.mark.anyio
def test_an_oversized_page_size_is_refused_rather_than_clamped(header_world):
    refused = header_world["client"].get(
        "/api/design-workshops", params={"pageSize": 500}, headers=_auth(header_world, "admin")
    )
    assert refused.status_code == 422

    at_the_ceiling = header_world["client"].get(
        "/api/design-workshops", params={"pageSize": 100}, headers=_auth(header_world, "admin")
    )
    assert at_the_ceiling.status_code == 200, at_the_ceiling.text
