"""A section that does not print is named exactly once — never twice, and never not at all.

**THE DEFECT THIS FILE PINS, WHICH IS A DEFECT IN TWO FIXES RATHER THAN IN THE FEATURE.** Two
corrections landed on the custom-section render in the same afternoon:

* ``append_custom_section`` gained the template's capture-tier cap, so a designer's Standard-tier
  question stopped printing under COMPACT_SUMMARY — the one template in ``TEMPLATES`` whose
  ``max_tier`` is not ADVANCED and whose own description promises "Basic-tier fields only".
* ``design_workshops.attach_report_custom_sections``' "…have no answers recorded and are not in this
  file" warning was re-pointed at ``CustomSectionItem.has_content``, because as written it fired for
  exactly the sections the renderer DOES print: a designer was told "Dye bath log … is not in this
  file" about a document containing the heading "Dye bath log" with "Dye source — Not recorded."
  underneath it.

Separately each is right. Together they opened the same wound from the other side. The loader runs
BEFORE ``apply_report_settings`` — which is what splices these sections into a template at all, and
cannot do so until it has been handed the definition that load produces — so the warning has no
template to ask and reads the cap-blind ``has_content``. A section whose every answered question
sits above the template's cap therefore prints nothing AND is warned about by nobody: the designer
attaches a .docx to a ministry email with their own block silently missing.

**SO THE RULE THIS FILE ENFORCES IS A PARTITION, NOT A PREDICATE.** ``section_prints`` is the single
answer to "did this section print", the renderer and both warnings read it, and the two warning sets
— the loader's (at ``ALL_TIERS``, the only cap it can honestly ask) and the builder's
(``sections_hidden_by_tier``, at the template's own) — are disjoint and together cover every section
the document left out. ``test_the_two_warnings_partition_the_sections_that_do_not_print`` is the one
that would catch a future "simplification" back into two hand-rolled tests.

**HOW I KNOW THESE BITE.** ``test_a_block_the_cap_suppressed_is_named_beside_the_download`` was run
against the pre-fix tree (the warning block deleted from ``build_report``) and fails there with an
empty warning list while the document is missing the block — the exact silent submission above.

**NO DATABASE AND NO NETWORK, and nothing here skips.** Every rule under test is decided by a pure
function reached through the real ``apply_report_settings`` → ``build_report`` chain, which is the
chain the download and the preview both use.
"""

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

#: Printed by COMPACT_SUMMARY as well as by DETAILED_TECHNICAL, which is what lets one section be
#: rendered under both caps without changing anything else about the document.
STAGE = "WORKSHOP_SETUP"

#: The only template in ``TEMPLATES`` with a non-ADVANCED ``max_tier``. If a second one is ever
#: added this file keeps working; if COMPACT_SUMMARY's cap is ever raised to ADVANCED, the guard
#: immediately below fails rather than letting every test here pass vacuously.
CAPPED = "COMPACT_SUMMARY"


def test_the_template_these_tests_lean_on_still_has_a_cap_to_lean_on():
    """A guard against the whole file going quietly green.

    Every assertion below depends on COMPACT_SUMMARY suppressing something. Raise its ``max_tier``
    to ADVANCED and nothing here would fail — the documents would simply contain everything and the
    warnings would correctly be empty — so the suppression itself is asserted first.
    """
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
    """The stored definition matching ``item``, so the template splices the same section the data
    carries. Only ``key``, ``title``, ``stage_key`` and ``sort_order`` are read by
    ``apply_report_settings``, but the fields are mirrored anyway: a definition that did not match
    its answers is a fixture that could pass for the wrong reason."""
    return CustomSectionSpec(
        key=item.key, title=item.title, stage_key=item.stage_key,
        fields=tuple(
            _spec(f.key, f.label, tier=Tier(f.tier), required=f.required, type=FieldType(f.type))
            for f in item.fields
        ),
    )


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
    return "".join(out)


def _generate(template_id: str, *items: CustomSectionItem) -> tuple[str, list[str]]:
    """One report, generated the way the real download generates it: the sections attached to the
    data, the template shaped by ``apply_report_settings`` from the same definitions, and
    ``build_report`` handed both. Returns the document as searchable text, and its warnings.

    THE WHOLE POINT IS THAT BOTH COME OUT OF ONE CALL. The defect is a document and a warning list
    disagreeing, and a helper that produced one without the other could not see it.
    """
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
    """Only the warnings this file is about. ``build_report`` also reports unfilled required stage
    fields, and a bare ``WorkshopData`` has plenty of those — asserting on the whole list would make
    every test here a test of the completeness warnings instead."""
    return [w for w in warnings if "own section(s)" in w]


# --------------------------------------------------------------------------------------
# The quiet direction: suppressed by the cap, and nobody said so
# --------------------------------------------------------------------------------------


def test_a_block_the_cap_suppressed_is_named_beside_the_download():
    """**THE DEFECT.** Every answered question in this section is Standard-tier, so COMPACT_SUMMARY
    prints none of it — correctly, that is the tier fix — and the loader's warning cannot see it,
    because that warning runs before any template exists and asks ``has_content`` with every tier
    admitted, where this section is emphatically not empty.

    Before ``build_report`` grew the block that raises this, the designer got a document with their
    own section missing and an empty warning list: they submit it believing the ministry's copy
    carries the block, or they hunt for a bug in the app. Delete that block from ``build_report``
    and the second assertion below goes red while the first still passes — which is exactly the
    state the verifier found.
    """
    text, warnings = _generate(CAPPED, _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"warp": 60, "dyebath": 90},
    ))

    # The document is honest: the template's cap left the whole block out, heading and all.
    assert "Loom shed" not in text
    assert "Warp count" not in text

    # And so is the sentence beside it. The TITLE is the assertion — a designer scanning a
    # ten-warning header needs to know WHICH of their sections is missing.
    said = _about_custom_sections(warnings)
    assert len(said) == 1
    assert "Loom shed" in said[0]
    assert "not in this file" in said[0]
    # And WHY, because the reason is the only thing they can act on: generating the same workshop
    # under DETAILED_TECHNICAL prints it.
    assert "capture tier" in said[0]
    assert get_template(CAPPED).name in said[0]


def test_without_the_builders_half_the_designer_is_told_nothing(monkeypatch):
    """**THE STATE THE VERIFIER FOUND, REPRODUCED, so the test above cannot pass for free.**

    ``build_report`` gained exactly one call to close this — ``sections_hidden_by_tier`` — so
    neutering that call is the tree as it stood between the two fixes and the download this file
    exists to prevent. Same workshop, same template, same document; the block is missing from the
    .docx and the warning list is empty. If a later edit makes
    ``test_a_block_the_cap_suppressed_is_named_beside_the_download`` pass by some other route, this
    test goes red and says so.

    MONKEYPATCHED RATHER THAN COMMENTED OUT BY HAND, because this repository is worked on by
    several people at once and a service module temporarily missing a warning is a failure that
    lands in somebody else's test run, not in mine.
    """
    import app.services.report_builder as builder_module

    monkeypatch.setattr(builder_module, "sections_hidden_by_tier", lambda items, cap: ())
    text, warnings = _generate(CAPPED, _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        _rf("dyebath", "Dye bath temperature", tier="ADVANCED"),
        values={"warp": 60, "dyebath": 90},
    ))
    assert "Loom shed" not in text                      # gone from the ministry's copy…
    assert _about_custom_sections(warnings) == []       # …and nobody said so. That was the defect.


def test_the_same_workshop_under_an_advanced_template_prints_it_and_says_nothing():
    """The other half of the previous test, and the one that proves the warning is about the
    TEMPLATE rather than about the section. One workshop, two downloads, two truthful stories."""
    item = _item(
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"warp": 60},
    )
    text, warnings = _generate("DETAILED_TECHNICAL", item)
    assert "Loom shed" in text
    assert "Warp count" in text
    assert _about_custom_sections(warnings) == []


def test_a_section_that_prints_under_the_cap_is_not_warned_about():
    """THE LOUD DIRECTION, WHICH THE ORIGINAL WARNING FIX CLOSED AND THIS KEEPS CLOSED.

    A Basic-tier answer survives COMPACT_SUMMARY, so the block is in the file — and a warning
    saying it is not would send the designer hunting for a bug that is not there, or make them
    submit believing the ministry's copy is shorter than it is. That is the exact sentence the
    audit finding was about; it must not come back through the tier gate's door.
    """
    text, warnings = _generate(CAPPED, _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        _rf("warp", "Warp count", tier="STANDARD"),
        values={"looms": 12, "warp": 60},
    ))
    assert "Loom shed" in text
    assert "How many looms?" in text
    assert "Warp count" not in text          # the tier fix still bites INSIDE the section
    assert _about_custom_sections(warnings) == []


def test_an_unanswered_required_basic_question_prints_its_gap_and_draws_no_warning():
    """The audit finding's own example, run through the capped template.

    "Dye bath log" with one required Basic question nobody reached prints the heading and
    "Dye source — Not recorded.", because a gap in the record has to be visible AS a gap. It is in
    the file, so nothing may say it is not — under EITHER template.
    """
    for template_id in (CAPPED, "DETAILED_TECHNICAL"):
        text, warnings = _generate(template_id, _item(
            _rf("dyesource", "Dye source", tier="BASIC", required=True, type="TEXT"),
            key="dye", title="Dye bath log", values={},
        ))
        assert "Dye bath log" in text
        assert "Not recorded." in text
        assert _about_custom_sections(warnings) == [], template_id


# --------------------------------------------------------------------------------------
# One predicate, and a partition
# --------------------------------------------------------------------------------------


#: The four shapes a section can take against a BASIC cap, and what each is: does it print at all
#: (cap-blind), and does it print under the cap. Ordered so the two interesting rows are adjacent.
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
    """``section_prints`` must not be an OPINION about the renderer — it must BE the renderer's
    answer, or the warning that reads it is back to being a second copy of the rule.

    So this asserts the predicate against what ``append_custom_section`` actually did to a document:
    blocks appended, or none at all. Both caps, every shape. A future edit that teaches the appender
    one more reason to stay silent — and forgets this function — fails here.
    """
    for cap in (Tier.BASIC.rank, ALL_TIERS):
        doc = DocumentBuilder(meta=ReportMeta(title=""))
        append_custom_section(doc, item, heading=item.title, max_tier_rank=cap)
        drew_something = doc.build().blocks != ()
        assert section_prints(item, cap) is drew_something, f"{name} @ cap {cap}"


def test_the_two_warnings_partition_the_sections_that_do_not_print():
    """**THE RULE THIS WHOLE FILE EXISTS FOR: named once, or not at all.**

    Two warnings speak about missing custom sections and they read one predicate at two caps —
    the loader's ``has_content`` (``ALL_TIERS``; it has no template and never will) and the
    builder's ``sections_hidden_by_tier`` (the template's own). Between them they must cover every
    section the document leaves out, and they must never both name one — a download whose header
    says a block is missing twice, for two different reasons, is the same "two stories about one
    section" defect wearing a different coat.

    ``fields_at`` is monotone in the cap, which is what makes the partition hold rather than merely
    happen to hold on these four fixtures; a change that broke that monotonicity fails here.
    """
    cap = Tier.BASIC.rank
    for name, item in _SHAPES:
        # The loader's half, spelled exactly as `attach_report_custom_sections` spells it.
        loader_names_it = not item.has_content
        # The builder's half.
        builder_names_it = bool(sections_hidden_by_tier([item], cap))
        prints = section_prints(item, cap)

        assert not (loader_names_it and builder_names_it), f"{name} named twice"
        assert prints is not (loader_names_it or builder_names_it), f"{name} named wrongly"
        # And the loader's predicate really is this module's, not a lookalike: if these two ever
        # part, the partition above is arithmetic about nothing.
        assert item.has_content is section_prints(item, ALL_TIERS)


def test_a_section_nobody_answered_is_left_entirely_to_the_loaders_warning():
    """The builder must stay silent about the empty-section case, because the loader already names
    it — and it names it with the wording the audit settled on. Two sentences about one section is
    the defect this fix is closing, arrived at by over-correcting."""
    text, warnings = _generate(CAPPED, _item(
        _rf("looms", "How many looms?", tier="BASIC"),
        values={},
    ))
    assert "Loom shed" not in text
    assert _about_custom_sections(warnings) == []


# --------------------------------------------------------------------------------------
# What must not move
# --------------------------------------------------------------------------------------


def test_a_template_that_carries_no_custom_section_gains_no_warning():
    """**THE 485 KB FIXTURE'S GUARANTEE.** ``report_templates_pin.json`` compares 38
    ``apply_report_settings`` calls by value against the Kotlin port and not one of them passes a
    definition, so a bare template must reach ``build_report`` and come back exactly as it did.

    The sections here ARE attached to the data — the harder case — but no ``CUSTOM_SECTION`` was
    spliced, so nothing was skipped and there is nothing to warn about. A warning driven off the
    attached tuple instead of off ``template.sections`` would fire here, and would be telling the
    designer the template suppressed a block it was never asked to print.
    """
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [_item(
        _rf("warp", "Warp count", tier="STANDARD"), values={"warp": 60},
    )])
    _document, warnings = build_report(
        data, CAPPED, lambda _id: None, meta=ReportMeta(title="Ikat workshop"),
    )
    assert _about_custom_sections(warnings) == []


def test_a_template_section_naming_a_definition_that_vanished_warns_nobody():
    """A ``CUSTOM_SECTION`` whose key is not attached is an ordinary outcome — a definition that
    changed between the two loads, or a section retired while the report was being generated. The
    builder appends nothing, and this must not turn that silence into "the template's tier left your
    block out", which would be a fabricated explanation for something else entirely.
    """
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
    """The warnings travel in an HTTP header that ``_warnings_header`` truncates, so one sentence
    naming four and counting the rest — the shape every other warning in this pipeline uses — is
    what keeps the tail of the list from being cut mid-word."""
    items = [
        _item(_rf(f"f{n}", f"Field {n}", tier="STANDARD"),
              key=f"s{n}", title=f"Section {n}", values={f"f{n}": n})
        for n in range(1, 7)
    ]
    _text, warnings = _generate(CAPPED, *items)
    said = _about_custom_sections(warnings)
    assert len(said) == 1
    assert said[0].startswith("6 of this workshop's own section(s)")
    assert "Section 1, Section 2, Section 3, Section 4…" in said[0]
    assert "Section 5" not in said[0]
