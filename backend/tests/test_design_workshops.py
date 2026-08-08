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
"""

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
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
    """"₹ nan." IS WHAT THIS PRINTED, in the browser preview, in the .docx a ministry receives
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
    assert format_value(spec, ["EMPORIUM", "ONLINE"]) == \
        "Government emporium, Online marketplace"


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
    assert format_value(_f(FieldType.GEO), {"lat": 21.33331, "lon": 83.61672}) == \
        "21.33331, 83.61672"


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
    known = {s.key for s in __import__(
        "app.services.stage_schema", fromlist=["STAGES"]).STAGES}
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
    assert warnings   # every stage's required fields are unfilled, and it says so


def test_cover_rows_come_from_the_cover_field_role():
    doc, _w = build_report(
        _data(singletons={"WORKSHOP_SETUP": {
            "workshopTitle": "W", "craftName": "Sambalpuri Ikat",
            "clusterName": "Bargarh", "designerName": "A. Sharma"}}),
        "DCH_STANDARD", _resolver, meta=_meta(),
    )
    cover = next(b for b in doc.blocks if isinstance(b, CoverBlock))
    labels = {label for label, _value in cover.info_rows}
    assert "Craft" in labels and "Designer" in labels


def test_a_collection_becomes_a_table_with_its_declared_columns():
    doc, _w = build_report(
        _data(collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Bhikari Meher", "specialisation": "Tie-and-dye",
             "experienceYears": 28, "isMasterCraftsperson": True},
            {"serialNo": 2, "name": "Sunita Bag", "specialisation": "Weaving",
             "experienceYears": 12, "isMasterCraftsperson": False},
        ]}}),
        "DCH_STANDARD", _resolver, meta=_meta(),
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
        _data(collections={"SKETCH_DEVELOPMENT": {"sketch": [
            {"sketchNo": "SK-01", "name": "Runner", "category": "TABLE_LINEN",
             "expectedPrice": "1900.00"}]}}),
        "DETAILED_TECHNICAL", _resolver, meta=_meta(),
    )
    for block in doc.blocks:
        if isinstance(block, TableBlock) and block.columns:
            assert sum(c.width_pct for c in block.columns) == pytest.approx(100.0, abs=0.5)


def test_headline_counts_are_derived_not_typed():
    """Stage 18 asks for "no. of designs"; a hand-typed count is a second source of truth that
    goes stale the moment one more sketch is added."""
    doc, _w = build_report(
        _data(collections={
            "SKETCH_DEVELOPMENT": {"sketch": [{"sketchNo": f"SK-{i}", "name": str(i)}
                                              for i in range(3)]},
            "PROTOTYPE_DEVELOPMENT": {"prototype": [{"prototypeCode": "PR-01", "name": "p"}]},
        }),
        "DCH_STANDARD", _resolver, meta=_meta(),
    )
    metrics = next(b for b in doc.blocks if isinstance(b, MetricRowBlock))
    as_dict = {label: value for label, value, _unit in metrics.metrics}
    assert as_dict["Sketches"] == "3"
    assert as_dict["Prototypes"] == "1"


def test_a_tier_capped_template_omits_standard_fields():
    """COMPACT_SUMMARY admits BASIC only, so a Standard-tier answer must not appear in it."""
    data = _data(singletons={"CLUSTER_CRAFT_BACKGROUND": {
        "clusterIntroduction": "BASIC TIER TEXT",
        "culturalSignificance": "STANDARD TIER TEXT",
    }})
    compact, _w = build_report(data, "COMPACT_SUMMARY", _resolver, meta=_meta())
    detailed, _w2 = build_report(data, "DETAILED_TECHNICAL", _resolver, meta=_meta())

    def text_of(doc):
        return " ".join(
            runs_text(getattr(b, "runs", ()) or ()) for b in doc.blocks
        )

    assert "BASIC TIER TEXT" in text_of(compact)
    assert "STANDARD TIER TEXT" not in text_of(compact)
    assert "STANDARD TIER TEXT" in text_of(detailed)


def test_report_generation_and_archive_stages_never_print():
    """They configure the report and record the archive; printing them would be the report
    describing its own generation."""
    doc, _w = build_report(
        _data(singletons={"REPORT_GENERATION": {"templateId": "DCH_STANDARD"},
                          "DATA_QUALITY_ARCHIVE": {"reportSaved": True}}),
        "DCH_STANDARD", _resolver, meta=_meta(),
    )
    headings = " ".join(
        runs_text(b.runs) for b in doc.blocks if hasattr(b, "level")
    )
    assert "Report Generation" not in headings
    assert "Data Quality" not in headings


def test_an_unresolvable_photo_is_skipped_not_fatal():
    doc, _w = build_report(
        _data(collections={"SKETCH_DEVELOPMENT": {"sketch": [
            {"sketchNo": "SK-01", "name": "Runner", "image": "missing"}]}}),
        "DCH_STANDARD", lambda _id: None, meta=_meta(),
    )
    assert doc.images == ()


def test_warnings_name_the_stage_and_the_missing_fields():
    doc, warnings = build_report(
        _data(singletons={"WORKSHOP_SETUP": {"workshopTitle": "W"}}),
        "DCH_STANDARD", _resolver, meta=_meta(),
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

    assert _heading_summary(
        "SK-01 is taken forward as the first prototype. It is the only drawing on the sheet "
        "that the panel agreed on."
    ) == "SK-01 is taken forward as the first prototype."


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
    """"Priced at 1250.10 per metre" must not become a heading reading "Priced at 1250."."""
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
        ("SKETCH_DEVELOPMENT", "sketch", {
            "sketchNo": "SK-01", "name": "Pasapalli runner", "category": "TABLE_LINEN",
            "expectedPrice": "1900.00", "intendedUse": "Home dining",
            "designerNotes": "Repeat reduced from 12 cm to 8 cm.",
        }),
        ("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", {
            "serialNo": 1, "name": "Bhikari Meher", "specialisation": "Tie-and-dye",
            "experienceYears": 28, "isMasterCraftsperson": True, "village": "Barpali",
            "artisanCardNo": "OD/BGH/1188",
        }),
        ("PROTOTYPE_DEVELOPMENT", "prototype", {
            "prototypeCode": "PR-01", "name": "Table runner", "sketchRef": "SK-01",
            "artisanRef": "Bhikari Meher", "materials": ["Cotton"], "makingTimeDays": 5.5,
            "processSummary": "Tied over three days.",
        }),
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
        "DETAILED_TECHNICAL", _resolver, meta=_meta(),
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
