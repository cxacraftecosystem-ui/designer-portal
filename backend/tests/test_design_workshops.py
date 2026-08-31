"""The design-workshop service layer, minus the database.

The parts worth pinning here are the pure ones: how a stored value becomes the string a report
prints, how the report builder turns registry-declared fields into blocks, and how a template
narrows what a report contains. The database half is exercised end to end against real Postgres
in the round-trip script; what is protected here is the logic that would be wrong in the same
way on every workshop.

The formatting assertions are not cosmetic. A cost sheet is read by an officer who writes
lakhs, a date typed as 10-02-2026 means February in the field and October to a reader, and a
missing optional field that prints "None" reads as a data-entry error. Each of those changes
what the number in a sanctioned report means.

SINCE 2026-08-29 IT ALSO CARRIES THE HEADER-EDIT RULES, and the first line above is now only half
true of this file. ``PATCH /design-workshops/{id}`` is what requirement 27 — "edit a design
prototype workshop" — turns on, and half of what has to be pinned about it is still a statement
about literals in this repository: which columns the create and the edit each own, and how one body
key becomes one Prisma value. The other half is a row in ``DesignWorkshopViewer`` deciding an HTTP
status, which cannot honestly be asserted against anything but real Postgres. Those tests carry
``needs_db`` INDIVIDUALLY rather than through a module-level ``pytestmark``, so the sixty
report-builder tests above still run on a laptop with no Docker — which is where most of this
file's value has always been.
"""

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

# --------------------------------------------------------------------------------------
# format_value
# --------------------------------------------------------------------------------------


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
    """12,34,567 not 1,234,567. A Western-grouped figure is misread at a glance by the officer
    for whom it becomes a sanctioned amount."""
    assert format_value(_f(FieldType.MONEY), amount) == expected


def test_negative_money_keeps_its_sign_outside_the_symbol():
    assert format_value(_f(FieldType.MONEY), "-500.00") == "-₹ 500.00"


def test_unparseable_money_prints_verbatim_rather_than_vanishing():
    assert format_value(_f(FieldType.MONEY), "about two thousand") == "about two thousand"


@pytest.mark.parametrize("stored", ["nan", "NaN", "inf", "-inf", float("nan")])
def test_a_stored_non_finite_number_is_never_dressed_up_as_an_amount(stored):
    """ "₹ nan." IS WHAT THIS PRINTED, in the browser preview, in the .docx a ministry receives
    and in the on-device report.

    A bare ``float()`` reads "NaN" and "Infinity" happily, so a value that reached the column
    before ``coerce_value`` refused them formats as a perfectly ordinary currency figure. The
    charts have always dropped those rows — ``_as_number`` is what they use — so the cost table
    and the figure beside it disagreed, with nothing in the document to say why. Printing the
    stored text as the unreadable thing it is makes the two agree.
    """
    assert format_value(_f(FieldType.MONEY), stored) == str(stored)
    assert format_value(_f(FieldType.PERCENT), stored) == str(stored)
    assert format_value(_f(FieldType.DECIMAL), stored) == str(stored)


def test_dates_print_as_a_person_writes_them():
    assert format_value(_f(FieldType.DATE), "2026-02-10") == "10 Feb 2026"
    assert format_value(_f(FieldType.DATE), "not a date") == "not a date"


def test_enums_print_their_label_not_their_token():
    """A report that says "TIE_AND_DYE" is not a report anyone submits to a ministry."""
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
    """A media field's value is an id; printing it would put a cuid in the middle of a report."""
    assert format_value(_f(FieldType.IMAGE), "cmshxj9id0001") == ""
    assert format_value(_f(FieldType.IMAGE_LIST), ["a", "b"]) == ""


def test_geo_prints_readable_coordinates():
    assert (
        format_value(_f(FieldType.GEO), {"lat": 21.33331, "lon": 83.61672}) == "21.33331, 83.61672"
    )


# --------------------------------------------------------------------------------------
# Templates
# --------------------------------------------------------------------------------------


def test_every_template_is_choosable_and_distinct():
    ids = [t.id for t in TEMPLATES]
    assert len(ids) == len(set(ids))
    assert {c["id"] for c in template_choices()} == set(ids)


def test_an_unknown_template_falls_back_rather_than_raising():
    """A saved workshop can name a template a later release removed. Losing the ability to print
    two weeks of fieldwork because its template was retired is not an acceptable outcome."""
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


# --------------------------------------------------------------------------------------
# The builder
# --------------------------------------------------------------------------------------


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
    """A designer who opens the report screen on day one must get a cover, not a stack trace."""
    doc, warnings = build_report(_data(), "DCH_STANDARD", _resolver, meta=_meta())
    assert any(isinstance(b, CoverBlock) for b in doc.blocks)
    assert warnings  # every stage's required fields are unfilled, and it says so


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
    # The Basic/Standard flags travel into the cells: a bool prints as Yes, not True.
    assert any(runs_text(cell) == "Yes" for cell in table.rows[0])


def test_table_column_widths_always_sum_to_one_hundred():
    """TableBlock raises otherwise, so this proves the builder's normalisation, not the model."""
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
    """Stage 18 asks for "no. of designs"; a hand-typed count is a second source of truth that
    goes stale the moment one more sketch is added."""
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
    """COMPACT_SUMMARY admits BASIC only, so a Standard-tier answer must not appear in it."""
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
    """They configure the report and record the archive; printing them would be the report
    describing its own generation."""
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
    # And they stay OUT of the document: an officer opening the file next month should not find
    # a note about what was missing on the day.
    assert doc.warnings == ()


def test_the_document_carries_the_template_palette_unless_one_is_supplied():
    """The default must not change, and the override must reach the document whole.

    Both halves matter. A deployment that has never opened the colour picker has to keep
    generating exactly the reports it generated last year, which is what the first assertion
    protects; and the second is what makes the picker mean anything, because the theme is read by
    the DOCX writer's styles part and by every colour the PDF draws — a theme that stopped at the
    builder would be a colour the designer chose, saw in the preview, and did not get.
    """
    default, _w = build_report(_data(), "DCH_STANDARD", _resolver, meta=_meta())
    assert default.theme == template("DCH_STANDARD").theme

    maroon = theme_from_accent("802F42", base=template("DCH_STANDARD").theme)
    recoloured, _w2 = build_report(_data(), "DCH_STANDARD", _resolver, meta=_meta(), theme=maroon)
    assert recoloured.theme == maroon
    assert recoloured.theme.table_header_fill == "802F42"
    # The typography is the TEMPLATE's, not the accent's. Choosing a colour is not a request to
    # re-typeset a document somebody else designed.
    assert recoloured.theme.body_font == template("DCH_STANDARD").theme.body_font


def test_every_entity_the_registry_declares_is_reachable_by_some_template():
    """A collection no template prints is data a designer types and never sees again."""
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
    """Two builds of the same data must be the same document, or a checksum means nothing."""
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
    """35 of the 394 headings in one generated report were EXACTLY eighty characters and ended
    mid-word, because the fallback was ``text[:80]`` — a raw slice with no word boundary and no
    ellipsis:

        "15.1. SK-01 is taken forward as the first prototype. It is the only drawing on the she"

    These are section headings in a document submitted to a ministry, and they propagate: the
    same string is printed, listed in the PDF contents with a dot leader and a page number after
    it, and written into the PDF's bookmark outline.

    The first sentence is preferred because a designer's first sentence is almost always the
    summary — the paragraph after it is not a title.
    """
    from app.services.report_builder import _heading_summary

    assert (
        _heading_summary(
            "SK-01 is taken forward as the first prototype. It is the only drawing on the sheet "
            "that the panel agreed on."
        )
        == "SK-01 is taken forward as the first prototype."
    )


def test_a_heading_with_no_sentence_short_enough_is_elided_on_a_word_boundary():
    """The fallback still has to be a fallback: a cut is fine, a cut mid-word is not, and a
    reader has to be able to tell an extract from a sentence somebody left unfinished."""
    from app.services.report_builder import _heading_summary

    long_run = (
        "A single very long sentence with no terminator at all that simply runs on and on past "
        "the eighty character mark without stopping"
    )
    heading = _heading_summary(long_run)
    assert len(heading) <= 80
    assert heading.endswith("…"), "an elision must be visible as one"
    assert long_run.startswith(heading[:-1].rstrip()), "and must not invent words"
    # The cut lands between words, never inside one.
    assert long_run[len(heading[:-1].rstrip())] == " "


def test_a_decimal_point_is_not_the_end_of_a_sentence():
    """ "Priced at 1250.10 per metre" must not become a heading reading "Priced at 1250."."""
    from app.services.report_builder import _heading_summary

    assert _heading_summary("Priced at 1250.10 per metre.") == "Priced at 1250.10 per metre."


def test_a_short_answer_is_left_exactly_as_it_is():
    """The common case by far, and the one a truncation rule must not touch."""
    from app.services.report_builder import _heading_summary

    assert _heading_summary("Runner, 180 x 40 cm") == "Runner, 180 x 40 cm"
    assert _heading_summary("") == ""
    assert _heading_summary("   ") == ""


# --------------------------------------------------------------------------------------
# No presentation may silently drop a field
# --------------------------------------------------------------------------------------


def _all_text(doc) -> str:
    """Every printable string in a document, whatever block it landed in."""
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
        # A sketch is rendered as CARDS by the standard running order, a participant as a TABLE.
        # Both must print every role: the bug this guards was TABLE_COLUMN fields vanishing from
        # CARDS, and KEY_VALUE/BULLETS fields vanishing from a TABLE's per-row detail.
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
    """Every value a designer filled in must appear SOMEWHERE in the report.

    This is the general form of a real defect: CARDS presentation collected NARRATIVE,
    KEY_VALUE and BULLETS roles but not TABLE_COLUMN, so a sketch's number, category and
    expected price were captured, counted towards completeness, and then printed nowhere at
    all. Nothing failed; the report was simply missing three fields per sketch.

    A presentation is a layout of the designer's work, never a filter on it.
    """
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


# --------------------------------------------------------------------------------------
# X-Report-Warnings: what the response can carry, and what it must never lose in silence
# --------------------------------------------------------------------------------------


def _header(warnings):
    """Imported inside the helper: the route module is heavy and only these tests need it."""
    from app.api.routes.design_workshops import _warnings_header

    return _warnings_header(warnings)


def test_short_warning_lists_are_carried_whole():
    assert _header([]) == ""
    assert _header(["One thing.", "Another thing."]) == "One thing.; Another thing."


def test_a_long_warning_list_drops_whole_warnings_and_says_how_many():
    """THE DEFECT: ``"; ".join(warnings)[:900]`` cut the list off mid-word and said nothing.

    On DCH_STANDARD — the default template — a workshop raises about a dozen warnings, and the LOAD
    warnings are appended last, so they were the first to be cut. Those are precisely the ones that
    say a WHOLE ANNEXURE is missing from the file: the questionnaire annexure's "attached to this
    workshop but nothing was recorded against it" never reached the designer, who saw a report with
    no questionnaire annexure and no sentence anywhere explaining why.

    Two properties, and the second is the one that was broken: the header stays inside its budget,
    and everything in it is a WHOLE warning, followed by a count of the ones that did not fit.
    """
    from app.api.routes.design_workshops import _WARNINGS_HEADER_BUDGET

    items = [
        f"Stage {n} (Some Stage): 3 required field(s) not recorded - {'x' * 60}."
        for n in range(1, 21)
    ]
    header = _header(items)
    pieces = header.split("; ")

    assert len(header) <= _WARNINGS_HEADER_BUDGET
    # ONE piece, not two. The note carries no ";" of its own — that character is this header's item
    # separator and `frontend/lib/designWorkshops.ts` splits on it, so a semicolon inside the note
    # would show the designer two half-sentences.
    assert pieces[-1].endswith("The report preview lists all of them.")
    assert int(pieces[-1].split()[0]) == len(items) - (len(pieces) - 1)
    # Not one fragment: every carried piece is a warning exactly as it was written.
    assert all(piece in items for piece in pieces[:-1])


def test_a_single_warning_wider_than_the_budget_is_marked_rather_than_passed_off_whole():
    """A truncated sentence shown as a complete warning is worse than a visibly truncated one.

    ``frontend/lib/designWorkshops.ts`` splits this header on ";" and prints each piece to the
    designer, so a fragment ending "... 2 required " reads as a finished statement of fact.
    """
    from app.api.routes.design_workshops import _WARNINGS_HEADER_BUDGET

    header = _header(["y" * 4000, "A second warning."])
    assert len(header) <= _WARNINGS_HEADER_BUDGET
    assert header.split("; ")[0].endswith("...")
    assert "1 further warning(s)" in header


def test_non_ascii_is_replaced_rather_than_raising_inside_starlette():
    """Every ASGI header value is encoded latin-1; a craft named in Odia must not cost a 500."""
    header = _header(["Craft ସମ୍ବଲପୁରୀ has no cost sheet."])
    assert header.isascii()
    assert "has no cost sheet." in header


def test_a_semicolon_inside_a_warning_does_not_split_it_on_the_designers_screen():
    """PACKING WHOLE WARNINGS IS ONLY HALF OF "never a fragment". This is the other half.

    ``";"`` is this header's separator and ``frontend/lib/designWorkshops.ts`` splits the value on
    it, so a semicolon inside a warning is indistinguishable from the boundary between two.

    MEASURED AGAINST THE RUNNING SERVER, not imagined: ``questionnaire_warnings`` interpolates the
    questionnaire's TITLE, which a designer types. A form called "Loom survey; round two" sent
    ``x-report-warning-count: 2`` with nothing truncated, and the report screen showed THREE
    warnings, the last of them ``"round two)."`` — the sentence saying a whole annexure is missing
    from the file, delivered as two halves, one of which means nothing alone.

    The assertion is the FRONTEND's split — ``";"``, not ``"; "`` — because what is being pinned is
    what the designer reads, not what the header contains.
    """
    typed_by_a_designer = (
        "1 questionnaire(s) attached to this workshop have no recorded answers and were left out "
        "of the questionnaire annexure (Loom survey; round two)."
    )
    header = _header([typed_by_a_designer, "Stage 3 (Workshop Plan): 1 required field(s)."])

    pieces = [piece.strip() for piece in header.split(";") if piece.strip()]
    assert len(pieces) == 2, (
        f"two warnings reached the designer as {len(pieces)} pieces, so at least one of them is a "
        f"half-sentence: {pieces}"
    )
    assert pieces[0].endswith("(Loom survey, round two).")
    # The pause the designer wrote survives as a comma rather than being deleted outright.
    assert "round two" in pieces[0]


# --------------------------------------------------------------------------------------
# GET /report/preview's `meta`: the geometry the web draws its sheets on
# --------------------------------------------------------------------------------------


def _preview_meta_of(**meta_kwargs):
    """Imported inside the helper, for the reason ``_header`` gives: the route module is heavy.

    A ``ReportDocument`` with no blocks, because nothing here is about blocks: ``_preview_meta``
    reads ``document.meta`` and nothing else, and building a document with content would put the
    builder between this test and the thing it is pinning.
    """
    from app.api.routes.design_workshops import _preview_meta
    from app.services.report_model import ReportDocument, ReportMeta, ReportTheme

    document = ReportDocument(
        meta=ReportMeta(title="A workshop", **meta_kwargs), theme=ReportTheme(), blocks=()
    )
    return _preview_meta(document)


def test_the_preview_carries_both_halves_of_the_page_geometry():
    """THE DEFECT: the payload carried the PAPER and not the MARGIN.

    A page is not sized by its sheet alone. ``report_pdf.PdfRenderer`` computes its text column as
    ``page_w - 2 * margin`` and ``report_docx`` writes the same number into the section properties,
    so the margin decides where every line wraps and therefore where every page breaks. With only
    ``pageSize`` on the wire the web preview had to assume the other half: ``previewModel
    .pageGeometry`` fell back to 25 and ``ReportSheet`` printed "25 mm margins assumed (the preview
    payload does not carry the margin)" above every sheet — an apology, on the one screen a
    designer approves the document from, for a number the server was holding.

    The expected margin is READ OFF the dataclass rather than typed as ``25.0``, so this test
    cannot be the thing that disagrees with the default it is describing.
    """
    from app.services.report_model import ReportMeta

    payload = _preview_meta_of()

    assert payload["pageSize"] == "A4"
    assert payload["marginMm"] == ReportMeta(title="x").margin_mm


def test_the_preview_reports_the_documents_own_margin_and_not_a_constant():
    """The half of the fix a default-only assertion cannot see.

    ``margin_mm`` is 25.0 on every document this deployment produces — no ``ReportTemplate`` field
    sets it, ``report_meta`` reads no stage-20 answer for it, and ``render_report`` overrides only
    the page size and the two running lines — so a hardcoded ``"marginMm": 25.0`` would pass the
    test above for ever and be silently wrong on the first document that moves it. The margin has
    to travel FROM the document, which is what makes the preview correct by construction rather
    than by coincidence.
    """
    from app.services.report_model import PageSize

    payload = _preview_meta_of(page_size=PageSize.LETTER, margin_mm=18.0)

    assert payload["marginMm"] == 18.0
    assert payload["pageSize"] == "LETTER"


# --------------------------------------------------------------------------------------
# The workshop header: what an edit may reach, and what it may never  (requirement 27)
# --------------------------------------------------------------------------------------
#
# WHY THESE ARE IN THIS MODULE AT ALL, given its docstring says "minus the database". Because half
# of them genuinely are: which COLUMNS the two header endpoints own, and how one body key becomes
# one Prisma value, are statements about literals in this repository and want no Postgres. The other
# half cannot be answered without it — a 403 for a viewer-grantee and a 404 for a stranger are rows
# in ``DesignWorkshopViewer`` deciding an HTTP status, and asserting them against a mock would be
# asserting the mock. The database-backed ones carry ``needs_db`` individually rather than through a
# module-level ``pytestmark``, so the sixty report-builder tests above still run on a laptop with no
# Docker, which is where most of this file's value has always been.
#
# THE DEFECT THIS WHOLE SECTION EXISTS FOR. ``PATCH /design-workshops/{id}`` shipped reading its body
# with ``getattr(payload, key) is not None``, which makes "the client did not mention this field" and
# "the client cleared this field" the SAME request. Everything falsy was dropped, so a designer who
# emptied the notes box and pressed Save was told the workshop had been saved — 200, the form's
# dirty flag cleared, the screen calm — and found the old note still there on the next load, with
# nothing anywhere to read. There is no ``""`` to send instead: ``notes`` is a nullable column and an
# empty string is a different stored value from NULL. The route module's ``_header_patch_data``
# carries the argument; these tests are what stop it being tidied back.


def _routes():
    """The route module. Imported inside the helper, like ``_header`` above: the route module is
    heavy and only these tests need it."""
    from app.api.routes import design_workshops as routes

    return routes


# --------------------------------------------------------------------------------------
# Which columns the two header endpoints own — no database
# --------------------------------------------------------------------------------------


def test_the_edit_reaches_exactly_what_the_create_reaches():
    """PATCH writes the columns POST writes, less the two stamps POST writes once. Both directions.

    NEITHER DIRECTION FAILS LOUDLY IN THE PRODUCT, which is why the rule is arithmetic here rather
    than a sentence in a docstring.

    A column CREATABLE AND NOT PATCHABLE is write-once with nothing on any screen saying so. That is
    not hypothetical: ``notes``, ``templateId`` and ``workshopId`` were exactly that for the whole
    life of this product — collected by the create form, mirrored by no stage-1 field, and therefore
    unfixable by anything short of deleting the workshop and running the fortnight again.

    A column PATCHABLE AND NOT CREATABLE is a second writer for a column that already has one. For
    the six promoted columns that is the failure written up in ``seed_designer_prefill``: ten
    columns went to NULL under a 200 reading "Stage saved", and the workshop was invisible to every
    list filter and search for the whole of a capture fortnight.
    """
    from app.schemas.design_workshops import DesignWorkshopCreate

    routes = _routes()
    body_fields = set(DesignWorkshopCreate.model_fields)
    designer_keys = {"designerUserId", "designerUserIds"}
    # ``status`` is written by the create as the literal "DRAFT" rather than from the body, so it is
    # a column the create writes even though no create field carries it.
    create_writes = (body_fields - designer_keys) | routes._CREATE_ONLY_STAMPS | {"status"}
    patch_writes = set(routes._HEADER_TEXT_COLUMNS) | set(routes._HEADER_DATE_COLUMNS)

    assert patch_writes == create_writes - routes._CREATE_ONLY_STAMPS

    # And the only create-body keys an edit cannot carry are the two designer ones — each of which
    # is refused BY NAME rather than by `extra_forbidden`, because "not permitted" would send a
    # client that wants to change the team looking for a flag instead of for PUT /{id}/viewers.
    assert body_fields - patch_writes == designer_keys
    assert set(routes._NEVER_PATCHABLE) >= designer_keys


def test_the_shared_column_list_still_spells_the_create_loop_it_replaced():
    """``_CREATE_OPTIONAL_COLUMNS`` is derived, and it must still be the tuple it was derived from.

    The create route used to write that list out by hand. Sharing it is what stops the two endpoints
    disagreeing about which columns the header consists of — but only if the derivation produces the
    same six names in the same order, so the literal is asserted here once rather than trusted.
    """
    assert _routes()._CREATE_OPTIONAL_COLUMNS == (
        # ``workshopKind`` joined the header on 2026-08-30 with the "Type of workshop" dropdown, and
        # this literal moving is the whole point of the assertion: the create body, the patch
        # columns and this tuple have to gain the column together or the endpoints disagree about
        # what a workshop header consists of.
        "workshopKind",
        "craftName",
        "clusterName",
        "state",
        "district",
        "notes",
        "workshopId",
    )


def test_no_column_is_both_writable_and_refused():
    """A key in both tables would be refused by the model and then written by the handler — or, far
    worse, the other way round, depending on which table a later reader edited first."""
    routes = _routes()
    writable = set(routes._HEADER_TEXT_COLUMNS) | set(routes._HEADER_DATE_COLUMNS)
    assert writable.isdisjoint(routes._NEVER_PATCHABLE)
    assert writable >= routes._HEADER_REQUIRED_COLUMNS


def test_every_key_the_header_serialises_is_either_writable_or_refused_by_name():
    """A client that reads ``workshop_summary`` and posts it back must be TOLD, key by key.

    That is not a strawman client, it is the obvious one: an edit form hydrates from the summary, and
    the cheapest way to submit is to send back the object it was given. Fourteen of those
    twenty-three keys are not editable, and the difference between "Extra inputs are not permitted"
    and a sentence naming ``designerName`` and pointing at stage 1 is the difference between a
    developer reading this module and a developer guessing.

    THE TEST IS OVER ``workshop_summary`` ITSELF rather than over a list of key names, because the
    trap is a column ADDED to that dict later: it would reach every client, be posted back by this
    one, and answer ``extra_forbidden`` with no clue which of the twenty-four keys was the problem.
    """
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
    )
    writable = set(routes._HEADER_TEXT_COLUMNS) | set(routes._HEADER_DATE_COLUMNS)
    unexplained = set(workshop_summary(record)) - writable - set(routes._NEVER_PATCHABLE)
    assert not unexplained, (
        f"{sorted(unexplained)} is serialised to every client and would be answered "
        "'Extra inputs are not permitted' if one sent it back. Add it to _HEADER_TEXT_COLUMNS or "
        "to _NEVER_PATCHABLE with the reason beside it."
    )
    # The single-record read adds one more key that no column stands behind. It is refused by name
    # for the same reason: a form that hydrated from GET /{id} sends it too.
    assert "dictationConsentByName" in routes._NEVER_PATCHABLE


# --------------------------------------------------------------------------------------
# One body key -> one Prisma value: absent, null and blank are three different requests
# --------------------------------------------------------------------------------------


def test_a_field_the_body_never_mentions_is_not_in_the_write_at_all():
    """The whole of "partial". A key absent from ``model_dump(exclude_unset=True)`` must not reach
    Prisma as anything — not as NULL, not as the stored value read back and written again."""
    assert _routes()._header_patch_data({"title": "Renamed"}) == {"title": "Renamed"}


def test_an_explicit_null_survives_all_the_way_to_the_write():
    """``{"notes": null}`` means CLEAR IT, and it is the request this route used to lose in silence.

    Dropped, the caller is answered 200 with the old value still stored: the form clears its dirty
    flag and the note comes back on the next load. There is no other spelling available — ``notes``
    is a nullable column and ``""`` is a different stored value that every "has a note" test is
    true of.
    """
    assert _routes()._header_patch_data({"notes": None}) == {"notes": None}


def test_a_blank_box_clears_the_column_rather_than_storing_an_empty_string():
    """``TextInput`` sends ``""`` for an emptied box and never JSON null, so ``""`` has to clear.

    It also keeps this route agreeing with the OTHER writer of the six promoted columns it shares:
    ``_coerce_promoted`` sets a promoted column back to NULL for a blank stage value rather than
    storing "", so a craft cleared here and a craft cleared in stage 1 have to end as the same
    stored value — or the list's "no craft recorded" filter answers differently depending on which
    screen did the clearing.
    """
    data = _routes()._header_patch_data({"craftName": "", "clusterName": "   ", "notes": " x "})
    assert data == {"craftName": None, "clusterName": None, "notes": "x"}


@pytest.mark.parametrize("blank", [None, "", "   "])
def test_emptying_a_not_null_column_is_a_422_naming_it_and_never_a_500(blank):
    """``{"title": null}`` reaches Prisma as ``MissingRequiredValueError`` — a bare 500, which reads
    to a client as "the server is broken" rather than as "a workshop has to have a title".

    ``"   "`` is the one that gets past pydantic: ``min_length=1`` catches ``""`` and not three
    spaces, and a workshop titled with whitespace renders as a blank heading on every screen that
    lists it and can afterwards be found only by its id.
    """
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as raised:
        _routes()._header_patch_data({"title": blank})
    assert raised.value.status_code == 422
    assert "title" in str(raised.value.detail)


def test_a_date_this_server_cannot_read_is_refused_rather_than_clearing_the_column():
    """``_parse_date`` answers None for "nothing was sent" AND for "that is not a date".

    On the CREATE that conflation is harmless — a malformed date is a box nobody has filled in
    properly yet and there is no stored value to lose. On an EDIT the same answer silently NULLs a
    column that held a real date, under a 200, on a workshop whose dates print on the report cover
    and decide which list filters can find it.
    """
    from fastapi import HTTPException

    with pytest.raises(HTTPException) as raised:
        _routes()._header_patch_data({"startDate": "10-02-2026 or thereabouts"})
    assert raised.value.status_code == 422
    assert "startDate" in str(raised.value.detail)
    # And the two spellings of "no date" still clear it, which is what makes the refusal safe.
    assert _routes()._header_patch_data({"startDate": None, "endDate": ""}) == {
        "startDate": None,
        "endDate": None,
    }


def test_the_immutable_refusal_names_every_field_it_objected_to_not_the_first():
    """A form that posted a whole summary back is sending fourteen of these at once.

    Told about one, it fixes one and is refused again — the same round trip the create route's
    eligibility rule is worded to save. A typo is still ``extra_forbidden``, deliberately: a
    misspelling should read as a misspelling and not as a policy.
    """
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
    """The subclass adds a refusal and nothing else. An unknown status or template is still the
    422 that names the allowed values, and it is still the SAME sentence the update model wrote."""
    import pydantic

    routes = _routes()
    with pytest.raises(pydantic.ValidationError) as bad_status:
        routes.DesignWorkshopPatch.model_validate({"status": "FINISHED"})
    assert "SUBMITTED" in str(bad_status.value)
    with pytest.raises(pydantic.ValidationError) as bad_template:
        routes.DesignWorkshopPatch.model_validate({"templateId": "NOT_A_TEMPLATE"})
    assert "DCH_STANDARD" in str(bad_template.value)


# --------------------------------------------------------------------------------------
# The route itself, against real Postgres
# --------------------------------------------------------------------------------------
#
# The fixture creates its accounts, roster row and profile on its OWN loop and DISCONNECTS before
# starting the TestClient, exactly as ``test_design_workshop_viewers`` does and for the reason
# stated there: the Prisma client is shared with the running app and bound to the client's loop, so
# touching it from a test's own loop is the kind of cross-loop use that fails intermittently rather
# than honestly. Everything after the yield goes through HTTP.
#
# The one workshop that needs a VIEWER ROW for an account the viewers API would refuse — a
# PROFESSOR, who is not eligible to be granted and is the whole point of the test — is created in
# that same phase, with the row written directly. There is no API path to that state and it is the
# state the ordering of the two gates exists to refuse.

PASSWORD = "dw-header-edit-password"

#: slug -> (role, display name).
#:
#: ``professor`` IS THE ACCOUNT A RANK LADDER WOULD ADMIT. ``can_run_design_workshops`` is a SET and
#: PROFESSOR sits at rank 40, ABOVE DESIGNER's 35, so every "this tier and above" spelling of the
#: rule lets them in and the set does not. A researcher could not prove that distinction: refused by
#: the ladder and by the set alike, they pass a test written against either.
HEADER_ACCOUNTS: tuple[tuple[str, str, str], ...] = (
    ("admin", "ADMIN", "Header Admin"),
    ("designer", "DESIGNER", "Rekha Sahu"),
    ("stranger", "DESIGNER", "Unrelated Designer"),
    ("professor", "PROFESSOR", "Senior Professor"),
)

#: What the designer's profile says on the day the workshop is opened, and what it is changed to
#: afterwards. Two different institutions, because "a designer who moves from NIFT to NID in 2027
#: must not retroactively rewrite the 2026 report" is the sentence under test and an institution is
#: the example ``prefill_from_profile`` uses to make it.
PROFILE_AT_CREATE = {"displayName": "Rekha Sahu", "institution": "NIFT Bhubaneswar"}
PROFILE_AFTER = {"displayName": "Dr Rekha Sahu", "institution": "NID Ahmedabad"}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def header_world():
    """Four accounts, one empanelment, one designer profile, and one pre-granted workshop."""
    from fastapi.testclient import TestClient

    from app.core.db import db
    from app.core.security import hash_password
    from app.main import app

    # Every address carries a per-run stamp: ``DesignerRoster.email`` is UNIQUE, and fixed addresses
    # would pass on a clean database and fail on the second run of the suite.
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
        # Empanelment, so the create route may NAME this designer: `assert_every_designer_may_be_
        # named` reads the designer roster and the platform allow-list, and an unempanelled designer
        # 422s the create before a row is written.
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
    """A bearer token for one of the fixture's accounts.

    Minted rather than obtained by signing in, because the roster gate lives on the LOGIN path and
    what is under test here is the edit rules. A helper that signed in first would make every one of
    these depend on the sign-in gate as well.
    """
    from app.core.security import create_access_token

    return {"Authorization": f"Bearer {create_access_token(world['people'][slug].id)}"}


def _new_workshop(world: dict[str, Any], **body: Any) -> dict[str, Any]:
    """A fresh workshop owned by ``admin``, made through the API the way workshops are made.

    A real POST rather than a Prisma insert, so the create gate and the promoted-column seeding are
    both exercised on the way past. One per test that mutates, because sharing a single row would
    make the module order-dependent and the failure mode of that is a suite that passes alone and
    fails in CI.
    """
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
    """The requirement, in one call: change two fields of eleven and touch nothing else.

    ``craftName`` is the interesting one to leave alone. It is a promoted column, so a route that
    rebuilt the whole row from its body — the shape ``exclude_unset`` exists to avoid — would write
    NULL over it and take the workshop out of every list filter and search on craft, exactly as the
    stage-save path once did.
    """
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
    # Named by neither the body nor the response's own defaults — still there.
    assert after["craftName"] == "Ikat"
    assert after["clusterName"] == "Barpali"
    assert after["status"] == created["status"]
    assert after["createdById"] == created["createdById"]
    # And the response is the header a client can store wholesale: the same dict the read serialises.
    reread = header_world["client"].get(
        f"/api/design-workshops/{created['id']}", headers=_auth(header_world, "admin")
    )
    assert reread.status_code == 200, reread.text
    assert {key: reread.json()[key] for key in after} == after


@needs_db
@pytest.mark.anyio
def test_an_explicit_null_clears_the_stored_value_and_a_missing_key_does_not(header_world):
    """The two halves of the defect, asserted against Postgres in one call each.

    ``{"notes": null}`` used to be dropped, so the designer was told the note was gone and it was
    not. The second PATCH is the control: the same route, the same workshop, ``notes`` simply not
    mentioned — and the cleared column must stay cleared rather than being restored by anything.
    """
    created = _new_workshop(header_world, notes="Delete me.", craftName="Ikat")
    cleared = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"notes": None, "craftName": ""},
        headers=_auth(header_world, "admin"),
    )
    assert cleared.status_code == 200, cleared.text
    assert cleared.json()["notes"] is None
    # "" clears rather than storing an empty string, which is what keeps this route and the stage
    # save agreeing about what an empty craft looks like in the column they share.
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
    """A 422 naming the field, and the title in the same body does NOT land.

    The second half is the point. A route that dropped the refused key and wrote the rest would
    answer 200 to a request half of which it ignored, and the designer would be told their edit
    succeeded while the box they actually came to fix went back to its old value.
    """
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

    # The two create-body keys an edit deliberately cannot carry, refused with the route that CAN
    # change the team named in the answer rather than left to `extra_forbidden`.
    team = header_world["client"].patch(
        f"/api/design-workshops/{created['id']}",
        json={"designerUserIds": [header_world["people"]["designer"].id]},
        headers=_auth(header_world, "admin"),
    )
    assert team.status_code == 422, team.text
    assert "viewers" in team.text


@needs_db
@pytest.mark.anyio
def test_a_viewer_grant_is_not_permission_to_edit_and_a_stranger_is_told_nothing(header_world):
    """Three accounts, one workshop, three different answers — and the ORDER of the two gates is
    what produces them.

    ``_require_designer`` runs BEFORE ``load_workshop_or_404``. The professor holds a real
    ``DesignWorkshopViewer`` row on this workshop, so the row test would admit them: it performs no
    role check at all, by design, because a grant is what lets a co-designer write the 22 stages.
    Swap the two lines and every viewer-grantee becomes an editor of the header, silently, without
    breaking one test about reading. The professor is 403 and not 404 precisely because the role
    gate answered first and never looked at the row.

    The stranger is a DESIGNER with no grant, so they pass the role gate and are refused by the row
    test — with 404 "Record not found" and not 403, because a 403 would confirm the id exists to
    exactly the people the clause is turning away.

    And the designer WITH a grant may edit, which is the control that stops this test passing by
    refusing everybody. A designer's access is always a grant: they cannot create a workshop at all,
    so ``createdById`` never matches for them.
    """
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
    """**The one that would be silent.** A report is a historical document.

    ``prefill_from_profile`` copies the designer's name, institution and nineteen more values into
    stage 1 and stage 3 ONCE, when the workshop is created, and is never consulted again. If an edit
    re-ran it, a designer who moved from NIFT to NID would have every workshop they ran under the
    old institution re-attributed by somebody merely renaming one of them — on the cover, in the
    certification block and in the .docx's own ``dc:creator``. Nothing would fail: completeness
    would still read 100%, readiness would still be green, and the only detector anywhere is a human
    who happens to remember where that workshop was actually run.

    So: open a workshop naming the designer, change the profile underneath it, rename the workshop,
    and assert the stage still says what it said on the day. The profile write is asserted too — a
    test whose setup silently failed would pass this by proving nothing at all.
    """
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
    # The promoted column, which is what the LIST and the report cover read.
    assert edited.json()["designerName"] == PROFILE_AT_CREATE["displayName"]

    detail = header_world["client"].get(
        f"/api/design-workshops/{created['id']}", headers=_auth(header_world, "designer")
    )
    assert detail.status_code == 200, detail.text
    stage_one = detail.json()["stages"]["WORKSHOP_SETUP"]["singleton"]
    assert stage_one["designerName"] == PROFILE_AT_CREATE["displayName"]
    assert stage_one["designerInstitution"] == PROFILE_AT_CREATE["institution"]


# --------------------------------------------------------------------------------------
# W-B1 — GET /design-workshops, offset pagination over a non-total order
# --------------------------------------------------------------------------------------
#
# No database. ``list_design_workshops`` bypasses ``count_and_page`` (it needs the raw rows for
# ``_attach_deleted_by``), so the tiebreak has to be appended by hand at the call site, and the whole
# point of a hand-appended fix is that it is easy to lose in a later edit. What is pinned here is the
# CONSEQUENCE, not the implementation: a client paging through a tied group must see every row exactly
# once, whatever a database that promises nothing about tie order chooses to do between two reads of
# the same walk.


def _dw_row(rid: str, *, created_at: datetime | None = None) -> Any:
    """A ``DesignWorkshop`` row carrying every key ``workshop_summary`` and ``consent_keys`` read, and
    no more — the same shape ``test_workshop_trash_listing.py`` proves against those two functions."""
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
    )


class _TiedDesignWorkshopTable:
    """Emulates ``LIMIT/OFFSET`` over an order that may or may not be total.

    ``LIMIT/OFFSET`` re-runs the sort from scratch on every call, and a database is free to break a
    tie in the group that shares the primary sort key however it likes UNLESS the order names a
    unique column — that freedom is the entire bug ``with_id_tiebreak`` closes. This double exploits
    exactly that freedom: with no ``id`` clause in the requested order it reverses the tied rows on
    every other read, which is a real (if adversarial) choice a live Postgres owes nothing to avoid.
    With an ``id`` clause present — total order — it never reverses, because there is no tie left for
    it to break differently. Two single-row reads through this double therefore only both come back
    stable if the order they were given is actually total, which is the property under test.
    """

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
    """Two workshops sharing ``createdAt`` (the column ``list_design_workshops`` orders by), walked as
    two single-row pages. Without the tiebreak this same double reverses the tied pair between the two
    reads, so the walk comes back ``["w1", "w1"]`` — ``w1`` sent twice and ``w2`` never sent at all,
    the exact failure ``records.with_id_tiebreak`` documents, reproduced with no Postgres involved.
    """
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
    # Belt and suspenders: the order actually sent to the database is total, by name.
    assert fake.finds[0]["order"] == [{"createdAt": "desc"}, {"id": "desc"}]


@needs_db
@pytest.mark.anyio
def test_an_oversized_page_size_is_refused_rather_than_clamped(header_world):
    """``pageSize=500`` is a 422, matching ``workshops.py``'s own list route (``:229``) rather than a
    second, invented ceiling — and NOT a 200 carrying 100 rows.

    Before ``le=100`` sat on this parameter, ``normalize_pagination`` clamped it silently deep inside
    the handler, so a client that asked for 500 rows in one page got 100 back with a ``pages`` count
    computed from the ``pageSize`` it actually received rather than the one it sent — the request was
    answered differently from how it was asked, and nothing on the wire admitted it. Refusing it here,
    before the handler body runs at all, is what lets a caller's own error handling see the
    disagreement instead of silently rendering a fifth of what it expected.
    """
    refused = header_world["client"].get(
        "/api/design-workshops", params={"pageSize": 500}, headers=_auth(header_world, "admin")
    )
    assert refused.status_code == 422

    # The ceiling refuses what is OVER it and nothing under — 100 itself still succeeds.
    at_the_ceiling = header_world["client"].get(
        "/api/design-workshops", params={"pageSize": 100}, headers=_auth(header_world, "admin")
    )
    assert at_the_ceiling.status_code == 200, at_the_ceiling.text
