import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.custom_sections import CustomFieldSpec, CustomSectionSpec
from app.services.report_builder import WorkshopData, build_report
from app.services.report_custom_sections import (
    _TIER_RANK,
    ALL_TIERS,
    CustomReportField,
    CustomSectionItem,
    append_custom_section,
    attach_custom_sections,
    custom_scoring,
    custom_section_blocks_standalone,
)
from app.services.report_model import DocumentBuilder, ReportMeta
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.stage_schema import FieldType, Tier

STAGE = "WORKSHOP_SETUP"


def _rf(key, label, tier="BASIC", **kw) -> CustomReportField:
    kw.setdefault("type", "INT")
    return CustomReportField(key=key, label=label, tier=tier, **kw)


def _item(*fields: CustomReportField, values=None, **kw) -> CustomSectionItem:
    kw.setdefault("key", "extra")
    kw.setdefault("title", "Loom shed")
    kw.setdefault("stage_key", STAGE)
    return CustomSectionItem(fields=tuple(fields), values=values or {}, **kw)


def _spec(key, label, tier=Tier.BASIC, **kw) -> CustomFieldSpec:
    kw.setdefault("type", FieldType.INT)
    return CustomFieldSpec(key=key, label=label, tier=tier, **kw)


def _text_of(document) -> str:
    out: list[str] = []
    for block in document.blocks:
        out.extend(run.text for run in getattr(block, "runs", ()))
        for pair in getattr(block, "pairs", ()):
            out.append(pair[0])
            out.extend(run.text for run in pair[1])
        for row in getattr(block, "rows", ()):
            for cell in row:
                out.extend(run.text for run in cell)
    return "".join(out)


def _built(template_id: str, item: CustomSectionItem, definition: CustomSectionSpec) -> str:
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [item])
    shaped = apply_report_settings(
        get_template(template_id), None, custom_sections=[definition]
    )
    document, _warnings = build_report(
        data, template_id, lambda _id: None,
        meta=ReportMeta(title="Ikat workshop"), template=shaped,
    )
    return _text_of(document)



def test_the_local_tier_ladder_is_the_registrys_own():
    assert {t.value: t.rank for t in Tier} == _TIER_RANK
    assert max(t.rank for t in Tier) == ALL_TIERS


def test_an_unrecognised_tier_token_prints_rather_than_vanishing():
    assert _rf("x", "X", tier="PLATINUM").tier_rank == 0
    assert _rf("x", "X", tier="platinum").within(0) is True
    assert _rf("x", "X", tier="standard").tier_rank == Tier.STANDARD.rank


def test_the_default_is_basic_so_a_caller_that_supplies_no_tier_loses_nothing():
    assert CustomReportField(key="k", label="L").tier == "BASIC"
    assert CustomReportField(key="k", label="L").within(Tier.BASIC.rank) is True



def test_a_standard_tier_question_is_left_out_of_a_basic_tier_template():
    blocks = custom_section_blocks_standalone(_item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"looms": 12, "warp": 60, "dyebath": 90},
    ))
    printed = "".join(
        [pair[0] for b in blocks for pair in getattr(b, "pairs", ())]
    )
    assert "How many looms?" in printed
    assert "Warp count" in printed
    assert "Dye bath temperature" in printed

    doc = DocumentBuilder(meta=ReportMeta(title=""))
    append_custom_section(doc, _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"looms": 12, "warp": 60, "dyebath": 90},
    ), max_tier_rank=Tier.BASIC.rank)
    capped = "".join(
        [pair[0] for b in doc.build().blocks for pair in getattr(b, "pairs", ())]
    )
    assert "How many looms?" in capped
    assert "Warp count" not in capped
    assert "Dye bath temperature" not in capped


def test_a_section_whose_every_question_is_above_the_cap_appends_no_heading_either():
    doc = DocumentBuilder(meta=ReportMeta(title=""))
    printed = append_custom_section(doc, _item(
        _rf("warp", "Warp count", tier="STANDARD", required=True),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"dyebath": 90},
    ), max_tier_rank=Tier.BASIC.rank)
    assert printed == 0
    assert doc.build().blocks == ()


def test_the_count_a_section_reports_is_the_count_it_printed():
    item = _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"looms": 12, "warp": 60},
    )
    assert item.answered_count == 2
    assert item.answered_count_at(Tier.BASIC.rank) == 1
    doc = DocumentBuilder(meta=ReportMeta(title=""))
    assert append_custom_section(doc, item, max_tier_rank=Tier.BASIC.rank) == 1


def test_an_unanswered_required_question_above_the_cap_prints_no_gap_note():
    doc = DocumentBuilder(meta=ReportMeta(title=""))
    append_custom_section(doc, _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD", required=True),
        values={"looms": 12},
    ), max_tier_rank=Tier.BASIC.rank)
    text = "".join(
        [run.text for b in doc.build().blocks for pair in getattr(b, "pairs", ())
         for run in pair[1]]
    )
    assert "Not recorded." not in text


def test_the_completeness_scorer_still_counts_a_question_the_template_does_not_print():
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [_item(
        _rf("looms", "How many looms?", tier="BASIC", required=True),
        _rf("warp", "Warp count", tier="STANDARD", required=True),
        values={"looms": 12},
    )])
    fields, values = custom_scoring(data, STAGE)
    assert [f.key for f in fields] == ["looms", "warp"]
    assert values == {"looms": 12}



@pytest.mark.parametrize("template_id", ["DCH_STANDARD", "DETAILED_TECHNICAL"])
def test_every_advanced_template_prints_the_whole_block_exactly_as_before(template_id):
    text = _built(
        template_id,
        _item(
            _rf("looms", "How many looms?", tier="BASIC"),
            _rf("warp", "Warp count", tier="STANDARD"),
            values={"looms": 12, "warp": 60},
        ),
        CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, fields=(
            _spec("looms", "How many looms?"),
            _spec("warp", "Warp count", tier=Tier.STANDARD),
        )),
    )
    assert "How many looms?" in text
    assert "Warp count" in text


def test_the_compact_summary_honours_its_own_description_for_the_designers_block_too():
    item = _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"looms": 12, "warp": 60},
    )
    definition = CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, fields=(
        _spec("looms", "How many looms?"),
        _spec("warp", "Warp count", tier=Tier.STANDARD),
    ))
    text = _built("COMPACT_SUMMARY", item, definition)
    assert "How many looms?" in text
    assert "12" in text
    assert "Warp count" not in text


class _Row:
    def __init__(self, stage_key, entity_key, data):
        self.id, self.stageKey, self.entityKey, self.data = "r1", stage_key, entity_key, data
        self.deletedAt = None


async def _resolved(value):
    return value


async def test_the_loader_carries_the_designers_tier_all_the_way_to_the_page(monkeypatch):
    from app.services import custom_sections as service
    from app.services.custom_sections import CUSTOM_ENTITY_KEY, CustomDefinition
    from app.services.design_workshops import attach_report_custom_sections
    from app.services.report_custom_sections import custom_sections_of

    definition = CustomDefinition(sections=(
        CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, id="sec1", fields=(
            _spec("looms", "How many looms?", id="f1"),
            _spec("warp", "Warp count", tier=Tier.STANDARD, id="f2"),
        )),
    ))
    monkeypatch.setattr(service, "load_definition_or_empty",
                        lambda _id: _resolved(definition))

    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    await attach_report_custom_sections(
        data, [_Row(STAGE, CUSTOM_ENTITY_KEY, {"looms": 12, "warp": 60})], "w1"
    )

    loaded = custom_sections_of(data)[0]
    assert {f.key: f.tier for f in loaded.fields} == {"looms": "BASIC", "warp": "STANDARD"}
    assert all(type(f.tier) is str for f in loaded.fields)
    assert [f.key for f in loaded.printed_fields_at(Tier.BASIC.rank)] == ["looms"]
    assert [f.key for f in loaded.printed_fields] == ["looms", "warp"]
