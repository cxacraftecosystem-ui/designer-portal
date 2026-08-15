"""A designer's own questions obey the template's capture tier, exactly as a registry field does.

**THE DEFECT THIS FILE PINS.** ``CustomFieldSpec.tier`` is a real choice in the section editor
("Which capture tier this question belongs to"), it is validated, it is stored, and it is serialised
to both clients — and at render time it was read by nothing. Every registry field passes
``ReportBuilder._visible`` (``spec.tier.rank <= template.max_tier.rank``); a designer's own question
passed no gate at all. COMPACT_SUMMARY is the only template in ``TEMPLATES`` whose ``max_tier`` is
not ADVANCED, and its own description promises "Basic-tier fields only, one photograph per
prototype" — so it correctly suppressed every Standard and Advanced REGISTRY field and then printed
the designer's Standard-tier answers underneath in full. One document, two rules, one declared
attribute.

**THE FIX IS A SEAM ACROSS TWO MODULES, WHICH IS WHY THE LAST TEST IN THIS FILE MATTERS MOST.** The
renderer asks the question — ``CustomSectionItem.fields_at`` against the template's ``max_tier.rank``
— and ``design_workshops.attach_report_custom_sections`` is what answers it, by copying ``tier`` off
the stored ``CustomFieldSpec`` along with the six attributes it always copied. Either half alone is
inert: a loader that carries a tier nobody reads changes nothing, and a renderer that filters on a
tier nobody supplies filters on the ``BASIC`` default and changes nothing either. That second shape
is deliberately the harmless one (see the ``tier`` default's comment in ``report_custom_sections``:
the safe direction of a half-wired gate is "print it", never "drop it") — and "harmless" is exactly
how a half-wired feature survives review, so ``test_the_loader_carries_the_designers_tier_all_the_way_to_the_page``
walks the real loader rather than trusting either end.

**NO DATABASE AND NO NETWORK, and nothing here skips** — the same argument
``test_custom_sections.py`` makes at length. Every rule under test is decided by a pure function,
and the one test that reaches the loader stands the definition read in with ``monkeypatch``, exactly
as its siblings in that file do.
"""

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import WorkshopData, build_report
from app.services.report_custom_sections import (
    ALL_TIERS,
    CustomReportField,
    CustomSectionItem,
    _TIER_RANK,
    append_custom_section,
    attach_custom_sections,
    custom_scoring,
    custom_section_blocks_standalone,
)
from app.services.report_model import DocumentBuilder, ReportMeta
from app.services.report_templates import apply_report_settings, template as get_template
from app.services.custom_sections import CustomFieldSpec, CustomSectionSpec
from app.services.stage_schema import FieldType, Tier

#: Printed by COMPACT_SUMMARY (``Presentation.KEY_VALUE``) as well as by DETAILED_TECHNICAL, which is
#: what lets one section be rendered under both caps without changing anything else.
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
    """Every string the document can show a reader, headings, prose and grid cells alike."""
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
    """One report, generated the way a real download generates it, as searchable text."""
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


# --------------------------------------------------------------------------------------
# The ladder itself
# --------------------------------------------------------------------------------------


def test_the_local_tier_ladder_is_the_registrys_own():
    """``report_custom_sections`` keeps its own copy of the three tiers, and this is the licence.

    The module is pure and has to stay transliterable into the Kotlin that builds the on-device
    report, so it cannot import ``Tier`` — that would put the whole 22-stage registry's import graph
    inside the phone's renderer. A second copy of a rule is how most of this repository's defects
    arose, so the copy is pinned rather than trusted: member for member, rank for rank. A fourth
    tier, or a renumbering, fails here before it can reach a document.
    """
    assert _TIER_RANK == {t.value: t.rank for t in Tier}
    assert ALL_TIERS == max(t.rank for t in Tier)


def test_an_unrecognised_tier_token_prints_rather_than_vanishing():
    """A definition written by a newer server and read by an older one must not lose fieldwork.

    Unknown falls to BASIC — admitted by every template — for the same reason ``display_value``
    prints an unknown field TYPE as plain text instead of dropping it: an answer a designer recorded
    is evidence, and a report that silently omits it is worse than one that prints it somewhere a
    terse template might not have asked for.
    """
    assert _rf("x", "X", tier="PLATINUM").tier_rank == 0
    assert _rf("x", "X", tier="platinum").within(0) is True
    # Case is not a way to lose an answer either.
    assert _rf("x", "X", tier="standard").tier_rank == Tier.STANDARD.rank


def test_the_default_is_basic_so_a_caller_that_supplies_no_tier_loses_nothing():
    """**The default is the opposite of ``CustomFieldSpec``'s, deliberately.**

    ``CustomFieldSpec.tier`` defaults to STANDARD, which is right for CAPTURE — a designer adding a
    question in the field is not usually writing a Basic-tier one. Mirroring that default here would
    mean a single missing keyword argument anywhere in the loading chain silently deletes a
    designer's genuinely Basic-tier questions from a submitted report. BASIC is the rank every
    template admits, so the failure mode of forgetting to pass a tier is "prints as it always did"
    rather than "a ministry's copy is missing a page".
    """
    assert CustomReportField(key="k", label="L").tier == "BASIC"
    assert CustomReportField(key="k", label="L").within(Tier.BASIC.rank) is True


# --------------------------------------------------------------------------------------
# What the renderer draws under a cap
# --------------------------------------------------------------------------------------


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
    """Not even the heading — the same silence a section nobody has reached produces.

    ``has_content_at`` and the block walk must read the SAME cap. Asking emptiness at ALL_TIERS and
    then drawing at the template's cap is how a terse report grows a heading over nothing, which is
    the mirror of the "empty heading in a submitted document" the emptiness test exists to prevent.
    """
    doc = DocumentBuilder(meta=ReportMeta(title=""))
    printed = append_custom_section(doc, _item(
        _rf("warp", "Warp count", tier="STANDARD", required=True),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"dyebath": 90},
    ), max_tier_rank=Tier.BASIC.rank)
    assert printed == 0
    assert doc.build().blocks == ()


def test_the_count_a_section_reports_is_the_count_it_printed():
    """``append_custom_section`` returns "how many answers it printed", and under a cap that has to
    mean the ones that reached the page. A number that counted suppressed answers would be a second
    arithmetic about one section — the shape of this repository's oldest report defect."""
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
    """A "Not recorded." for a question the template never asked would be a fabricated gap.

    Note this is reachable only through the API: ``validate_definition`` refuses a required field
    above BASIC, so the editor cannot produce this shape. The guard is here because the renderer
    must not depend on a validator two modules away to stay honest.
    """
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
    """**COMPLETENESS IS A FACT ABOUT THE FIELDWORK, NOT ABOUT THE DOCUMENT SOMEBODY PRINTED.**

    ``stage_completeness`` has no tier test of any kind — a registry field suppressed from a
    COMPACT_SUMMARY still counts against that stage's percentage — so ``custom_scoring`` must hand
    it every field regardless of the cap. Filtering here would make one workshop score differently
    depending on which template a reader happened to choose, and the completeness annexure would
    then disagree with the readiness screen: 144/144 printed eighteen pages after the same document
    said "Not recorded." thirty-six times.
    """
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [_item(
        _rf("looms", "How many looms?", tier="BASIC", required=True),
        _rf("warp", "Warp count", tier="STANDARD", required=True),
        values={"looms": 12},
    )])
    fields, values = custom_scoring(data, STAGE)
    assert [f.key for f in fields] == ["looms", "warp"]
    assert values == {"looms": 12}


# --------------------------------------------------------------------------------------
# Through a real generate
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("template_id", ["DCH_STANDARD", "DETAILED_TECHNICAL"])
def test_every_advanced_template_prints_the_whole_block_exactly_as_before(template_id):
    """Five of the six templates default to ``max_tier=ADVANCED``, so nothing about them moves."""
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
    """"Basic-tier fields only" is the template's promise to the reader, and it now holds for the
    half of the report the designer authored.

    Built through ``apply_report_settings`` and ``build_report`` — the real chain — because a rule
    proven on a helper is not proven on a document: this feature's own history is a complete, tested
    renderer with no branch in ``ReportBuilder.build``.
    """
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
    # The LABEL is the assertion, not the value: a bare "60" could be a page number or a date part
    # in some other block, and a test that can pass for the wrong reason is worse than no test.
    assert "Warp count" not in text


class _Row:
    """One stored ``DwStageEntry``, in the four attributes every reader of one uses."""

    def __init__(self, stage_key, entity_key, data):
        self.id, self.stageKey, self.entityKey, self.data = "r1", stage_key, entity_key, data
        self.deletedAt = None


async def _resolved(value):
    """The definition, already loaded. A coroutine because the loader it stands in for is one."""
    return value


async def test_the_loader_carries_the_designers_tier_all_the_way_to_the_page(monkeypatch):
    """**THE SEAM, ASSERTED ACROSS BOTH MODULES RATHER THAN INSIDE EITHER.**

    Everything above this line tests the renderer against ``CustomReportField``s a test constructed,
    so every one of them would still pass if the loader dropped ``tier`` on the floor — which is
    exactly what it did, and exactly the shape of the failure this feature has already had once:
    ``report_custom_sections`` was a complete, tested renderer with no branch in
    ``ReportBuilder.build``, and every report ever generated dropped it in silence while three
    surfaces told the designer the office's copy would carry it. A tested half is not a feature.

    So this walks the real loader — ``attach_report_custom_sections``, with only the definition read
    stood in — and asserts the token arrives on the far side under the designer's own choice. Delete
    ``tier=f.tier.value`` from that loop and this is the test that goes red; nothing else in the
    suite would notice.
    """
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
    # ``type(...) is str`` AND NOT ``isinstance``: ``Tier`` subclasses ``str``, so an ``isinstance``
    # check — and the equality above — would both pass for the enum itself. ``CustomReportField`` is
    # a flat value object with no server type inside it, because the phone builds the same shape
    # from its own cached definition, so the exact type is the assertion worth making.
    assert all(type(f.tier) is str for f in loaded.fields)
    # And the cap bites on the item the loader produced, not only on one a test hand-built.
    assert [f.key for f in loaded.printed_fields_at(Tier.BASIC.rank)] == ["looms"]
    assert [f.key for f in loaded.printed_fields] == ["looms", "warp"]
