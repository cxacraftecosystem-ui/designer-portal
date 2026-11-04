import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.custom_sections import CustomFieldSpec, CustomSectionSpec
from app.services.report_builder import WorkshopData, build_report
from app.services.report_custom_sections import (
    ALL_TIERS,
    CustomReportField,
    CustomSectionItem,
    append_custom_section,
    attach_custom_sections,
    section_prints,
    sections_hidden_by_tier,
)
from app.services.report_model import DocumentBuilder, ReportMeta
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.stage_schema import FieldType, Tier

STAGE = "WORKSHOP_SETUP"

CAPPED = "COMPACT_SUMMARY"


def test_the_template_these_tests_lean_on_still_has_a_cap_to_lean_on():
    assert get_template(CAPPED).max_tier is Tier.BASIC
    assert get_template("DETAILED_TECHNICAL").max_tier is Tier.ADVANCED


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


def _definition(item: CustomSectionItem) -> CustomSectionSpec:
    return CustomSectionSpec(
        key=item.key, title=item.title, stage_key=item.stage_key,
        fields=tuple(
            _spec(f.key, f.label, tier=Tier(f.tier), required=f.required, type=FieldType(f.type))
            for f in item.fields
        ),
    )


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
    return "".join(out)


def _generate(template_id: str, *items: CustomSectionItem) -> tuple[str, list[str]]:
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, list(items))
    shaped = apply_report_settings(
        get_template(template_id), None, custom_sections=[_definition(i) for i in items]
    )
    document, warnings = build_report(
        data, template_id, lambda _id: None,
        meta=ReportMeta(title="Ikat workshop"), template=shaped,
    )
    return _text_of(document), warnings


def _about_custom_sections(warnings) -> list[str]:
    return [w for w in warnings if "own sections" in w]



def test_a_block_the_cap_suppressed_is_named_beside_the_download():
    text, warnings = _generate(CAPPED, _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"warp": 60, "dyebath": 90},
    ))

    assert "Loom shed" not in text
    assert "Warp count" not in text

    said = _about_custom_sections(warnings)
    assert len(said) == 1
    assert "Loom shed" in said[0]
    assert "not in this file" in said[0]
    assert "capture tier" in said[0]
    assert get_template(CAPPED).name in said[0]


def test_without_the_builders_half_the_designer_is_told_nothing(monkeypatch):
    import app.services.report_builder as builder_module

    monkeypatch.setattr(builder_module, "sections_hidden_by_tier", lambda items, cap: ())
    text, warnings = _generate(CAPPED, _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"warp": 60, "dyebath": 90},
    ))
    assert "Loom shed" not in text
    assert _about_custom_sections(warnings) == []


def test_the_same_workshop_under_an_advanced_template_prints_it_and_says_nothing():
    item = _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"warp": 60},
    )
    text, warnings = _generate("DETAILED_TECHNICAL", item)
    assert "Loom shed" in text
    assert "Warp count" in text
    assert _about_custom_sections(warnings) == []


def test_a_section_that_prints_under_the_cap_is_not_warned_about():
    text, warnings = _generate(CAPPED, _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"looms": 12, "warp": 60},
    ))
    assert "Loom shed" in text
    assert "How many looms?" in text
    assert "Warp count" not in text
    assert _about_custom_sections(warnings) == []


def test_an_unanswered_required_basic_question_prints_its_gap_and_draws_no_warning():
    for template_id in (CAPPED, "DETAILED_TECHNICAL"):
        text, warnings = _generate(template_id, _item(
            _rf("dyesource", "Dye source", tier="BASIC", required=True, type="TEXT"),
            key="dye", title="Dye bath log", values={},
        ))
        assert "Dye bath log" in text
        assert "Not recorded." in text
        assert _about_custom_sections(warnings) == [], template_id



_SHAPES = [
    ("nothing recorded, nothing required", _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        values={},
    )),
    ("only above the cap", _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"warp": 60},
    )),
    ("answered under the cap", _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        values={"looms": 12},
    )),
    ("required under the cap, unanswered", _item(
        _rf("looms", "How many looms?", tier="BASIC", required=True),
        values={},
    )),
]


@pytest.mark.parametrize("name,item", _SHAPES, ids=[s[0] for s in _SHAPES])
def test_one_predicate_is_the_answer_the_renderer_actually_gives(name, item):
    for cap in (Tier.BASIC.rank, ALL_TIERS):
        doc = DocumentBuilder(meta=ReportMeta(title=""))
        append_custom_section(doc, item, heading=item.title, max_tier_rank=cap)
        drew_something = doc.build().blocks != ()
        assert section_prints(item, cap) is drew_something, f"{name} @ cap {cap}"


def test_the_two_warnings_partition_the_sections_that_do_not_print():
    cap = Tier.BASIC.rank
    for name, item in _SHAPES:
        loader_names_it = not item.has_content
        builder_names_it = bool(sections_hidden_by_tier([item], cap))
        prints = section_prints(item, cap)

        assert not (loader_names_it and builder_names_it), f"{name} named twice"
        assert prints is not (loader_names_it or builder_names_it), f"{name} named wrongly"
        assert item.has_content is section_prints(item, ALL_TIERS)


def test_a_section_nobody_answered_is_left_entirely_to_the_loaders_warning():
    text, warnings = _generate(CAPPED, _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        values={},
    ))
    assert "Loom shed" not in text
    assert _about_custom_sections(warnings) == []



def test_a_template_that_carries_no_custom_section_gains_no_warning():
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [_item(
        _rf("warp", "Warp count", tier="STANDARD"), values={"warp": 60},
    )])
    _document, warnings = build_report(
        data, CAPPED, lambda _id: None, meta=ReportMeta(title="Ikat workshop"),
    )
    assert _about_custom_sections(warnings) == []


def test_a_template_section_naming_a_definition_that_vanished_warns_nobody():
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [])
    shaped = apply_report_settings(
        get_template(CAPPED), None,
        custom_sections=[_definition(_item(_rf("warp", "Warp count", tier="STANDARD")))],
    )
    _document, warnings = build_report(
        data, CAPPED, lambda _id: None,
        meta=ReportMeta(title="Ikat workshop"), template=shaped,
    )
    assert _about_custom_sections(warnings) == []


def test_several_suppressed_sections_are_one_sentence_and_are_counted():
    items = [
        _item(_rf(f"f{n}", f"Field {n}", tier="STANDARD"),
              key=f"s{n}", title=f"Section {n}", values={f"f{n}": n})
        for n in range(1, 7)
    ]
    _text, warnings = _generate(CAPPED, *items)
    said = _about_custom_sections(warnings)
    assert len(said) == 1
    assert said[0].startswith("6 of this workshop's own sections")
    assert "Section 1, Section 2, Section 3, Section 4…" in said[0]
    assert "Section 5" not in said[0]
