"""The empty-note rule: what a report prints for a field that was never filled in.

THIS IS THE SERVER HALF OF A CROSS-SURFACE DEFECT, AND IT IS THE HALF THAT IS RIGHT. The office's
copy of a report prints "Not recorded." once for every unfilled REQUIRED field, so a reviewing
officer can see which answers a partly captured workshop is missing. The handset's copy prints
nothing at all for the same fields — ``ReportScreen.kt``'s ``renderEntity`` drops every unfilled
field with ``if (!DwValues.isFilled(stored)) return@forEach``, and its ``cellRuns`` has no
equivalent of the substitution either, so the divergence is in the TABLE path as well as the prose
one. The phone's own Data completeness annexure then scores the same draft and prints the shortfall
in its Outstanding column, so one document states the gap in one section and hides it in another.

The handset is the side that has to move, and ``ReportScreen.kt`` belongs to another lane. What this
file does is nail the target down so the two surfaces cannot converge on the WRONG answer — which is
a live risk while a parity defect is being closed, because deleting four lines from ``_printable``
would also make the two documents agree, and it would agree by hiding the gap in both. Every
assertion below is a statement about what the handset must be made to do:

* an unfilled REQUIRED field prints the note;
* an unfilled OPTIONAL field prints nothing (printing negatives for every blank Advanced-tier field
  would bury the report in them);
* the note reaches all three presentations — prose, key-value grid and table cell — because a
  required blank hidden in a table is exactly as invisible as one hidden in prose;
* media-typed fields are exempt, because a missing photograph is not a sentence.

``show_empty_note`` is not a per-template escape hatch in practice: it is declared once on each side
(``report_templates.ReportTemplate.show_empty_note`` and ``ReportTemplates.kt``'s ``showEmptyNote``)
and no template on either surface overrides it, so this applies to all six.

No database and no network: the builder is driven directly over synthetic entities, the same way
``test_report_rich_text_fields`` drives it.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import ReportBuilder, WorkshopData
from app.services.report_model import (
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

NOTE = "Not recorded."


def _builder() -> ReportBuilder:
    return ReportBuilder(
        WorkshopData(workshop_id="w1", title="Workshop"),
        template("DETAILED_TECHNICAL"),
        lambda media_id: ImageRef(
            source=media_id, width_px=800, height_px=600, mime_type="image/jpeg"
        ),
        meta=ReportMeta(title="Workshop", generated_at="2026-08-07T00:00:00Z"),
    )


def _entity(
    role: ReportRole,
    *,
    required: bool,
    ftype: FieldType = FieldType.LONG_TEXT,
    label: str = "Materials",
) -> EntitySpec:
    return EntitySpec(
        key="prototype",
        name="DwPrototype",
        cardinality=Cardinality.COLLECTION,
        title="Prototype",
        label_field="",
        fields=(
            FieldSpec(
                key="materials",
                label=label,
                type=ftype,
                required=required,
                report_role=role,
            ),
        ),
    )


def _text(builder: ReportBuilder) -> str:
    """Everything the document says, whatever kind of block it landed in."""
    parts: list[str] = []
    for block in builder.doc.build().blocks:
        if isinstance(block, ParagraphBlock):
            parts.append(runs_text(block.runs))
        elif isinstance(block, KeyValueBlock):
            parts.extend(f"{label}: {runs_text(value)}" for label, value in block.pairs)
        elif isinstance(block, TableBlock):
            parts.extend(runs_text(cell) for row in block.rows for cell in row)
    return " | ".join(parts)


# --------------------------------------------------------------------------------------
# 1. The rule itself, at the source
# --------------------------------------------------------------------------------------


def test_an_unfilled_required_field_is_printable_with_the_note():
    builder = _builder()
    entity = _entity(ReportRole.NARRATIVE, required=True)
    printable = builder._printable(entity, {}, {ReportRole.NARRATIVE})
    assert [(spec.label, text) for spec, text in printable] == [("Materials", NOTE)]


def test_an_unfilled_optional_field_is_not_printable_at_all():
    """The other half of the rule, and it is not symmetry for its own sake: a missing required field
    says the record is incomplete, a missing optional one says nothing, and a report that printed
    "Not recorded." for every blank Advanced-tier field would bury the reader in negatives."""
    builder = _builder()
    entity = _entity(ReportRole.NARRATIVE, required=False)
    assert builder._printable(entity, {}, {ReportRole.NARRATIVE}) == []


def test_a_filled_field_prints_its_value_and_never_the_note():
    builder = _builder()
    entity = _entity(ReportRole.NARRATIVE, required=True)
    printable = builder._printable(entity, {"materials": "Indigo-dyed cotton"}, {ReportRole.NARRATIVE})
    assert [text for _spec, text in printable] == ["Indigo-dyed cotton"]


def test_an_unfilled_required_photograph_prints_no_note():
    """A missing photograph is not a sentence. Images go through ``_images``, which never
    substitutes, and ``_printable`` is only ever asked for the non-media roles — so a required IMAGE
    field left empty prints nothing at all. The handset must keep this exemption when it adopts the
    rest of the rule; substituting here would put "Not recorded." where a picture belongs, in a
    gallery, under a heading that promises photographs."""
    builder = _builder()
    entity = _entity(ReportRole.GALLERY, required=True, ftype=FieldType.IMAGE, label="Photograph")
    builder._render_narrative(entity, {}, 1)
    assert NOTE not in _text(builder)


# --------------------------------------------------------------------------------------
# 2. …and it survives every presentation the value can land in
# --------------------------------------------------------------------------------------


def test_the_note_reaches_the_prose(monkeypatch):
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.NARRATIVE, required=True), {}, 1)
    assert NOTE in _text(builder)


def test_the_note_reaches_the_key_value_grid():
    builder = _builder()
    builder._render_narrative(_entity(ReportRole.KEY_VALUE, required=True), {}, 1)
    printed = _text(builder)
    assert NOTE in printed
    assert "Materials" in printed, "the gap has to be attributable to a field, not floating"


def test_the_note_reaches_a_table_cell():
    """THE PATH A PARTIAL FIX WOULD MISS, and the handset's ``cellRuns`` is where it will have to be
    made. A required blank hidden in a table is exactly as invisible as one hidden in prose, and the
    stage that shows this worst — prototype rows with a required field left blank on several of them
    — is a TABLE presentation."""
    builder = _builder()
    entity = _entity(ReportRole.TABLE_COLUMN, required=True)
    builder._render_table(entity, [{}], template("DETAILED_TECHNICAL").sections[0], 1)
    tables = [b for b in builder.doc.build().blocks if isinstance(b, TableBlock)]
    assert tables, "a required TABLE_COLUMN field must still produce a table"
    assert NOTE in " ".join(runs_text(cell) for row in tables[0].rows for cell in row)


def test_a_row_of_nothing_but_blanks_still_prints_one_note_per_required_field():
    """The section-level note is NOT a substitute for this one — the handset's only empty note today
    is per-SECTION (``if (!wrote && template.showEmptyNote)``), which fires when an entire stage
    produced nothing. A row with one filled field and two blank required ones prints its value and
    two notes here, and printed one value and nothing else on the phone."""
    builder = _builder()
    entity = EntitySpec(
        key="prototype",
        name="DwPrototype",
        cardinality=Cardinality.COLLECTION,
        title="Prototype",
        label_field="",
        fields=(
            FieldSpec(key="name", label="Name", type=FieldType.TEXT, required=True,
                      report_role=ReportRole.KEY_VALUE),
            FieldSpec(key="materials", label="Materials", type=FieldType.LONG_TEXT, required=True,
                      report_role=ReportRole.KEY_VALUE),
            FieldSpec(key="finish", label="Finish", type=FieldType.LONG_TEXT, required=True,
                      report_role=ReportRole.KEY_VALUE),
        ),
    )
    builder._render_narrative(entity, {"name": "Stole"}, 1)
    printed = _text(builder)
    assert printed.count(NOTE) == 2
    assert "Stole" in printed
