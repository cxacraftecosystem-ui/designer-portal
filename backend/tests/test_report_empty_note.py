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
    parts: list[str] = []
    for block in builder.doc.build().blocks:
        if isinstance(block, ParagraphBlock):
            parts.append(runs_text(block.runs))
        elif isinstance(block, KeyValueBlock):
            parts.extend(f"{label}: {runs_text(value)}" for label, value in block.pairs)
        elif isinstance(block, TableBlock):
            parts.extend(runs_text(cell) for row in block.rows for cell in row)
    return " | ".join(parts)



def test_an_unfilled_required_field_is_printable_with_the_note():
    builder = _builder()
    entity = _entity(ReportRole.NARRATIVE, required=True)
    printable = builder._printable(entity, {}, {ReportRole.NARRATIVE})
    assert [(spec.label, text) for spec, text in printable] == [("Materials", NOTE)]


def test_an_unfilled_optional_field_is_not_printable_at_all():
    builder = _builder()
    entity = _entity(ReportRole.NARRATIVE, required=False)
    assert builder._printable(entity, {}, {ReportRole.NARRATIVE}) == []


def test_a_filled_field_prints_its_value_and_never_the_note():
    builder = _builder()
    entity = _entity(ReportRole.NARRATIVE, required=True)
    printable = builder._printable(entity, {"materials": "Indigo-dyed cotton"}, {ReportRole.NARRATIVE})
    assert [text for _spec, text in printable] == ["Indigo-dyed cotton"]


def test_an_unfilled_required_photograph_prints_no_note():
    builder = _builder()
    entity = _entity(ReportRole.GALLERY, required=True, ftype=FieldType.IMAGE, label="Photograph")
    builder._render_narrative(entity, {}, 1)
    assert NOTE not in _text(builder)



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
    builder = _builder()
    entity = _entity(ReportRole.TABLE_COLUMN, required=True)
    builder._render_table(entity, [{}], template("DETAILED_TECHNICAL").sections[0], 1)
    tables = [b for b in builder.doc.build().blocks if isinstance(b, TableBlock)]
    assert tables, "a required TABLE_COLUMN field must still produce a table"
    assert NOTE in " ".join(runs_text(cell) for row in tables[0].rows for cell in row)


def test_a_row_of_nothing_but_blanks_still_prints_one_note_per_required_field():
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
