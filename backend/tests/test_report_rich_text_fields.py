"""A RICH_TEXT registry field, from the stored JSON to the blocks a renderer receives.

``tests/test_rich_text.py`` pins the document model itself — the round trip, the sanitisation, the
block mapping. This file pins the JOIN: what ``report_builder`` does when a field the registry
declares as RICH_TEXT turns up in a stage entry, which is a different question and had a different
answer for every one of the three places a value can land.

The failure being defended against is not "the bold was lost". It is worse and it shipped:

    A RICH_TEXT value is a dict. ``format_value`` had no branch for it, so it fell through to
    ``clean_text``, which stringifies whatever it is handed — and the report printed
    ``{'blocks': [{'kind': 'PARAGRAPH', 'spans': [{'text': 'The cluster …'}]}]}``
    into a document submitted to a ministry. Nothing failed and nothing warned, because every
    emptiness check upstream saw a long non-empty string and counted the field as filled.

No stage field is RICH_TEXT in this build, so the entities below are synthetic. That is the point:
this path has to be correct BEFORE the first field is promoted, rather than discovered to be wrong
by the first designer who bolds a product name.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services import rich_text
from app.services.report_builder import (
    ReportBuilder,
    WorkshopData,
    format_value,
)
from app.services.report_model import (
    Align,
    BulletListBlock,
    HeadingBlock,
    ImageRef,
    KeyValueBlock,
    ParagraphBlock,
    ReportMeta,
    TableBlock,
    runs_text,
)
from app.services.report_templates import template
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
)

# One stored document exercising everything a designer can actually apply: marks inside a
# paragraph, a run of list items, and a heading. Written as raw JSON rather than built through the
# model's constructors because raw JSON is what arrives from the phone and from the browser.
_DOC = {
    "blocks": [
        {"kind": "PARAGRAPH", "spans": [
            {"text": "The cluster weaves "},
            {"text": "Sambalpuri ikat", "marks": ["BOLD"]},
            {"text": " and, less often, "},
            {"text": "Pasapalli", "marks": ["ITALIC"]},
            {"text": "."},
        ]},
        {"kind": "HEADING", "level": 2, "spans": [{"text": "Recommendations"}]},
        {"kind": "ORDERED_ITEM", "spans": [{"text": "Reduce the repeat", "marks": ["BOLD"]}]},
        {"kind": "ORDERED_ITEM", "spans": [{"text": "Test a natural indigo"}]},
        {"kind": "ORDERED_ITEM", "spans": [{"text": "Cost the packaging"}]},
    ]
}


def _meta() -> ReportMeta:
    return ReportMeta(title="Workshop", generated_at="2026-08-07T00:00:00Z")


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _builder() -> ReportBuilder:
    return ReportBuilder(WorkshopData(workshop_id="w1", title="Workshop"),
                         template("DETAILED_TECHNICAL"), _resolver, meta=_meta())


def _entity(role: ReportRole, *, required: bool = False) -> EntitySpec:
    """A one-field collection entity whose only field is RICH_TEXT in ``role``."""
    return EntitySpec(
        key="richThing", name="DwRichThing", cardinality=Cardinality.COLLECTION,
        title="Rich thing", label_field="",
        fields=(FieldSpec(key="intro", label="Introduction", type=FieldType.RICH_TEXT,
                          required=required, report_role=role),),
    )


def _marks_in(blocks) -> set[tuple[str, bool, bool]]:
    """``(text, bold, italic)`` for every run anywhere in ``blocks``.

    Reaches into list items and table cells as well as paragraphs, because "the marks survived"
    has to be true wherever the field landed — and the three destinations take three different
    code paths through the builder.
    """
    found: set[tuple[str, bool, bool]] = set()
    for block in blocks:
        for run in getattr(block, "runs", ()) or ():
            found.add((run.text, run.bold, run.italic))
        for item in getattr(block, "items", ()) or ():
            found |= {(run.text, run.bold, run.italic) for run in item}
        for _label, value in getattr(block, "pairs", ()) or ():
            found |= {(run.text, run.bold, run.italic) for run in value}
        for row in getattr(block, "rows", ()) or ():
            for cell in row:
                found |= {(run.text, run.bold, run.italic) for run in cell}
    return found


# --------------------------------------------------------------------------------------
# format_value: the flattening path
# --------------------------------------------------------------------------------------


def test_a_rich_value_never_prints_as_its_own_json():
    """The defect at the top of this module, stated as one assertion."""
    spec = FieldSpec(key="intro", label="Introduction", type=FieldType.RICH_TEXT)
    printed = format_value(spec, _DOC)
    assert "{" not in printed and "blocks" not in printed and "kind" not in printed
    assert "Sambalpuri ikat" in printed


def test_a_rich_value_flattens_to_readable_prose_with_its_list_markers():
    spec = FieldSpec(key="intro", label="Introduction", type=FieldType.RICH_TEXT)
    printed = format_value(spec, _DOC)
    assert "1. Reduce the repeat" in printed
    assert "3. Cost the packaging" in printed


def test_a_field_promoted_from_long_text_still_prints_the_prose_already_stored():
    """Promoting a field must not blank what is under it: every value written before the
    promotion is a bare string, and a report that lost them would lose years of narrative."""
    spec = FieldSpec(key="intro", label="Introduction", type=FieldType.RICH_TEXT)
    assert format_value(spec, "Typed before the promotion.") == "Typed before the promotion."


def test_an_empty_rich_document_prints_nothing_rather_than_an_empty_shape():
    """An editor that has been focused and left alone saves a block with no spans. That is not a
    filled field and must not print as one."""
    spec = FieldSpec(key="intro", label="Introduction", type=FieldType.RICH_TEXT)
    assert format_value(spec, {"blocks": [{"kind": "PARAGRAPH", "spans": []}]}) == ""


# --------------------------------------------------------------------------------------
# Narrative: the formatted path
# --------------------------------------------------------------------------------------


def test_rich_text_reaches_the_document_with_its_marks_intact():
    """THE POINT OF THE WHOLE FEATURE. A designer who bolded a product name and numbered five
    recommendations wrote structure, not decoration; flattening it here would have made the
    rich-text editor a more expensive textarea."""
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.NARRATIVE), {"intro": _DOC}, 1)
    marks = _marks_in(builder.doc.build().blocks)
    assert ("Sambalpuri ikat", True, False) in marks
    assert ("Pasapalli", False, True) in marks
    assert ("Reduce the repeat", True, False) in marks


def test_consecutive_list_items_become_one_list_block():
    """A .docx list is a run of paragraphs sharing a numbering id. One block per item restarts
    the numbering at every line — a three-point recommendation list printed as "1. 1. 1."."""
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.NARRATIVE), {"intro": _DOC}, 1)
    lists = [b for b in builder.doc.build().blocks if isinstance(b, BulletListBlock)]
    assert len(lists) == 1
    assert lists[0].ordered is True
    assert len(lists[0].items) == 3


def test_a_heading_inside_the_prose_arrives_as_a_heading_block():
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.NARRATIVE), {"intro": _DOC}, 1)
    headings = [runs_text(b.runs) for b in builder.doc.build().blocks
                if isinstance(b, HeadingBlock)]
    assert "Recommendations" in headings


def test_a_rich_paragraph_keeps_the_alignment_the_designer_chose():
    builder = _builder()
    centred = {"blocks": [{"kind": "PARAGRAPH", "align": "CENTER",
                           "spans": [{"text": "In memory of Bhikari Meher."}]}]}
    builder._render_narrative(_entity(ReportRole.NARRATIVE), {"intro": centred}, 1)
    paragraphs = [b for b in builder.doc.build().blocks
                  if isinstance(b, ParagraphBlock) and "memory" in runs_text(b.runs)]
    assert paragraphs and paragraphs[0].align is Align.CENTER


def test_an_empty_optional_rich_field_writes_no_blocks_at_all():
    builder = _builder()
    wrote = builder._render_narrative(_entity(ReportRole.NARRATIVE), {"intro": None}, 1)
    assert wrote is False
    assert builder.doc.build().blocks == ()


def test_an_unfilled_required_rich_narrative_field_still_says_not_recorded():
    """``to_report_blocks`` of an empty document is an empty list, and skipping on that would
    swallow the note ``_printable`` substituted — a required field silently absent instead of
    visibly unanswered. A gap in the record has to be visible AS a gap."""
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.NARRATIVE, required=True), {"intro": None}, 1)
    printed = " ".join(runs_text(b.runs) for b in builder.doc.build().blocks
                       if isinstance(b, ParagraphBlock))
    assert "Not recorded." in printed


# --------------------------------------------------------------------------------------
# Cells: the path where a block cannot go
# --------------------------------------------------------------------------------------


def test_rich_text_in_a_table_cell_keeps_its_marks():
    """A cell holds runs, not blocks. Losing the paragraph structure to single spaces is the
    right trade — the alternative is a nested table — but losing the marks is not, and printing
    the stored dict is the failure this whole file is about."""
    builder = _builder()
    entity = _entity(ReportRole.TABLE_COLUMN)
    builder._render_table(entity, [{"intro": _DOC}], template("DETAILED_TECHNICAL").sections[0], 1)
    tables = [b for b in builder.doc.build().blocks if isinstance(b, TableBlock)]
    assert tables, "a TABLE_COLUMN rich field must still produce a table"
    cell_text = runs_text(tables[0].rows[0][0])
    assert "{" not in cell_text and "Sambalpuri ikat" in cell_text
    assert ("Sambalpuri ikat", True, False) in _marks_in(tables)


def test_rich_text_in_a_key_value_grid_keeps_its_marks():
    """A key-value cell is under the same constraint as a table cell and gets the same answer.
    It used to get ``runs_of(str(the dict))`` instead."""
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.KEY_VALUE), {"intro": _DOC}, 1)
    grids = [b for b in builder.doc.build().blocks if isinstance(b, KeyValueBlock)]
    assert grids
    assert ("Sambalpuri ikat", True, False) in _marks_in(grids)


def test_an_unfilled_required_rich_field_still_says_not_recorded():
    """``_printable`` substitutes the note for an unfilled required field. Routing that through
    ``plain_runs`` would hand back a blank cell instead — turning a gap the reader can see into
    one they cannot, which is the exact failure the note exists to prevent."""
    builder = _builder()
    spec = _entity(ReportRole.TABLE_COLUMN, required=True).fields[0]
    empty = {"blocks": [{"kind": "PARAGRAPH", "spans": []}]}
    assert rich_text.is_empty(empty)
    assert runs_text(builder._cell_runs(spec, {"intro": empty}, "Not recorded.")) \
        == "Not recorded."


def test_a_rich_field_is_never_silently_dropped_by_any_presentation():
    """The general rule this repository already enforces over every other field type, extended to
    the one whose value is not a string: a presentation is a layout of the designer's work, never
    a filter on it."""
    for role in (ReportRole.NARRATIVE, ReportRole.KEY_VALUE, ReportRole.TABLE_COLUMN):
        builder = _builder()
        entity = _entity(role)
        if role is ReportRole.TABLE_COLUMN:
            builder._render_table(entity, [{"intro": _DOC}],
                                  template("DETAILED_TECHNICAL").sections[0], 1)
        else:
            builder._render_narrative(entity, {"intro": _DOC}, 1)
        blocks = builder.doc.build().blocks
        texts = {text for text, _bold, _italic in _marks_in(blocks)}
        assert any("Sambalpuri ikat" in text for text in texts), \
            f"a filled RICH_TEXT field in role {role.value} appears nowhere"
