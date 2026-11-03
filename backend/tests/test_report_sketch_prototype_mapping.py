from types import SimpleNamespace

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.design_workshops import assemble_workshop_data
from app.services.report_builder import ReportBuilder, WorkshopData, build_report, format_value
from app.services.report_model import ImageBlock, ImageGridBlock, ImageRef, ReportMeta
from app.services.report_templates import TEMPLATES, Presentation, template
from app.services.stage_schema import FieldType, ReportRole, Tier, stages

SKETCH = "sketch"
PROTOTYPE = "prototype"


def _stage_of(entity_key: str) -> str:
    found = [s.key for s in stages() for e in s.entities if e.key == entity_key]
    assert found, f"no stage declares {entity_key}"
    assert len(found) == 1, (
        f"{entity_key} is declared on {found}; the sketches page resolves its stage by asking which "
        f"stage declares this entity, and that question needs exactly one answer"
    )
    return found[0]


def _entity(entity_key: str):
    stage_key = _stage_of(entity_key)
    stage = next(s for s in stages() if s.key == stage_key)
    return next(e for e in stage.entities if e.key == entity_key)


def _field(entity_key: str, field_key: str):
    spec = _entity(entity_key).field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is no longer declared"
    return spec


def _meta() -> ReportMeta:
    return ReportMeta(title="Workshop", subtitle="Cluster", generated_at="2026-08-24T00:00:00Z")


class _Resolver:
    def __init__(self) -> None:
        self.asked: list[str] = []

    def __call__(self, media_id: str) -> ImageRef:
        self.asked.append(media_id)
        return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _text(document) -> str:
    out: list[str] = []

    def harvest(value) -> None:
        if isinstance(value, str):
            out.append(value)
            return
        if isinstance(value, (list, tuple)):
            for item in value:
                harvest(item)
            return
        text = getattr(value, "text", None)
        if isinstance(text, str):
            out.append(text)
        for slot in getattr(value, "__slots__", ()) or ():
            if slot != "text":
                harvest(getattr(value, slot, None))

    for block in document.blocks:
        harvest(block)
    return " | ".join(out)


def _placed_images(document) -> list[str]:
    out: list[str] = []
    for block in document.blocks:
        if isinstance(block, ImageBlock):
            out.append(block.image.source)
        elif isinstance(block, ImageGridBlock):
            out.extend(image.source for image, _caption in block.images)
    return out


def _report(collections: dict, template_id: str = "DETAILED_TECHNICAL"):
    resolver = _Resolver()
    document, warnings = build_report(
        WorkshopData(
            workshop_id="w1",
            title="Workshop",
            singletons={"WORKSHOP_SETUP": {"workshopTitle": "W"}},
            collections=collections,
        ),
        template_id,
        resolver,
        meta=_meta(),
    )
    return document, warnings, resolver



def test_the_source_photograph_of_a_sketch_is_printed_as_a_picture():
    spec = _field(SKETCH, "image")
    assert spec.type is FieldType.IMAGE
    assert spec.report_role is ReportRole.GALLERY
    assert spec.required, "the sketch photograph is the required half of this surface"
    assert spec.tier is Tier.BASIC, "a photograph above BASIC would leave the Compact summary"

    document, _warnings, resolver = _report({
        _stage_of(SKETCH): {SKETCH: [{
            "_entryId": "ent_s1", "sketchNo": "S-1", "name": "Bandha runner",
            "image": "med_photo", "imageCaption": "The sheet on the courtyard table",
        }]}
    })
    assert "med_photo" in _placed_images(document), (
        "the photograph of the sheet is the picture the report is FOR; it reached no page"
    )
    assert "The sheet on the courtyard table" in _text(document), (
        "the caption is a CAPTION-role field and is placed with its image, never on its own"
    )
    assert resolver.asked.count("med_photo") >= 1


def test_the_traced_line_art_is_named_in_the_document_and_never_placed_as_a_picture():
    spec = _field(SKETCH, "lineArtFile")
    assert spec.type is FieldType.FILE
    assert format_value(spec, "med_art") == "1 document attached"

    document, _warnings, resolver = _report({
        _stage_of(SKETCH): {SKETCH: [{
            "_entryId": "ent_s1", "sketchNo": "S-1", "name": "Bandha runner",
            "lineArtFile": "med_art",
        }]}
    })
    printed = _text(document)
    assert spec.label in printed, f"{spec.label!r} is not on the page under any heading"
    assert "1 document attached" in printed
    assert "med_art" not in _placed_images(document)
    assert "med_art" not in resolver.asked, (
        "a FILE id was handed to the image resolver: a .svg or .glb offered to the picture path "
        "prints nothing and hides the sentence that was working"
    )


def test_the_3d_model_is_named_in_the_document_and_never_placed_as_a_picture():
    spec = _field(PROTOTYPE, "modelFile")
    assert spec.type is FieldType.FILE
    assert format_value(spec, ["med_glb", "med_stl"]) == "2 3D models attached"

    document, _warnings, resolver = _report({
        _stage_of(PROTOTYPE): {PROTOTYPE: [{
            "_entryId": "ent_p1", "prototypeCode": "P-1", "name": "Table runner",
            "modelFile": "med_glb",
        }]}
    })
    printed = _text(document)
    assert spec.label in printed
    assert "1 3D model attached" in printed
    assert "med_glb" not in resolver.asked


def test_the_turntable_is_the_one_prototype_attachment_that_prints_as_pictures():
    spec = _field(PROTOTYPE, "turntablePhotos")
    assert spec.type is FieldType.IMAGE_LIST
    assert format_value(spec, ["med_1", "med_2"]) == "", "a picture never prints as text as well"

    document, _warnings, _resolver = _report({
        _stage_of(PROTOTYPE): {PROTOTYPE: [{
            "_entryId": "ent_p1", "prototypeCode": "P-1", "name": "Table runner",
            "turntablePhotos": ["med_f1", "med_f2", "med_f3"],
        }]}
    })
    placed = _placed_images(document)
    for frame in ("med_f1", "med_f2", "med_f3"):
        assert frame in placed, f"{frame} was captured as a turntable frame and printed nowhere"



def _carrying(stage_key: str) -> set[str]:
    return {
        t.id for t in TEMPLATES
        if any(section.stage_key == stage_key for section in t.sections)
    }


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_the_two_stages_are_carried_by_the_three_templates_that_print_the_whole_workshop(entity_key):
    stage_key = _stage_of(entity_key)
    assert _carrying(stage_key) == {"DCH_STANDARD", "DIC_STANDARD", "DETAILED_TECHNICAL"}
    for template_id in _carrying(stage_key):
        assert template(template_id).max_tier is Tier.ADVANCED, (
            f"{template_id} carries {stage_key} at a tier that drops its ADVANCED fields: the "
            f"section would print with the line art, the model and the turntable missing"
        )


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_the_two_stages_are_printed_as_cards_so_every_row_can_carry_its_own_photograph(entity_key):
    stage_key = _stage_of(entity_key)
    for t in TEMPLATES:
        for section in t.sections:
            if section.stage_key == stage_key:
                assert section.presentation is Presentation.CARDS, (
                    f"{t.id} prints {stage_key} as {section.presentation.value}: a presentation "
                    f"that draws no per-record gallery loses every photograph on this surface"
                )
                assert section.include_photos, (
                    f"{t.id} prints {stage_key} with photographs switched off"
                )



def _entry(entry_id: str, stage_key: str, entity_key: str, data: dict, ordinal: int):
    return SimpleNamespace(
        id=entry_id, stageKey=stage_key, entityKey=entity_key, ordinal=ordinal,
        data=dict(data), fieldProvenance={}, clientKey=None, deletedAt=None,
    )


def test_the_designers_arrangement_is_the_order_the_report_prints_them_in():
    stage_key = _stage_of(SKETCH)
    entries = [
        _entry("ent_c", stage_key, SKETCH, {"sketchNo": "S-3", "name": "Third sketch"}, ordinal=2),
        _entry("ent_a", stage_key, SKETCH, {"sketchNo": "S-1", "name": "First sketch"}, ordinal=0),
        _entry("ent_b", stage_key, SKETCH, {"sketchNo": "S-2", "name": "Second sketch"}, ordinal=1),
    ]
    data = assemble_workshop_data(SimpleNamespace(id="w1", title="Workshop"), entries)
    assert [row["name"] for row in data.rows(stage_key, SKETCH)] == [
        "First sketch", "Second sketch", "Third sketch",
    ]

    document, _warnings = build_report(data, "DETAILED_TECHNICAL", _Resolver(), meta=_meta())
    printed = _text(document)
    first, second, third = (
        printed.index("First sketch"), printed.index("Second sketch"), printed.index("Third sketch")
    )
    assert first < second < third, (
        "the report re-ordered the designers' arrangement: the ordinal is the ONLY record that an "
        "order was deliberate, and a document that prints it in another order says nothing about it"
    )


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_the_document_says_who_settled_the_order_and_when(entity_key):
    stage_key = _stage_of(entity_key)
    by = _field(entity_key, "rankFixedBy")
    at = _field(entity_key, "rankFixedAt")
    assert by.report_role is ReportRole.KEY_VALUE and at.report_role is ReportRole.KEY_VALUE

    document, _warnings, _resolver = _report({
        stage_key: {entity_key: [{
            "_entryId": "ent_1", "name": "Arranged piece",
            "rankFixedBy": "Meena Iyer", "rankFixedAt": "2026-08-20",
        }]}
    })
    printed = _text(document)
    assert "Meena Iyer" in printed
    assert "20 Aug 2026" in printed



def _probe_value(spec, marker: str):
    t = spec.type
    if t is FieldType.RICH_TEXT:
        return {"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": marker}]}]}, marker
    if t in (FieldType.TEXT, FieldType.LONG_TEXT, FieldType.URL, FieldType.PHONE, FieldType.EMAIL):
        return marker, marker
    if t is FieldType.TAGS:
        return [marker], marker
    if t is FieldType.MULTI_ENUM:
        return [marker], None
    if t is FieldType.ENUM:
        return marker, None
    if t is FieldType.BOOL:
        return True, None
    if t is FieldType.INT:
        return 7, None
    if t in (FieldType.DECIMAL, FieldType.PERCENT):
        return 7.5, None
    if t is FieldType.MONEY:
        return "1234", None
    if t is FieldType.DATE:
        return "2026-08-20", None
    if t is FieldType.TIME:
        return "10:30", None
    if t is FieldType.GEO:
        return {"lat": 20.5, "lon": 84.5}, None
    if t is FieldType.REF:
        return "ent_other", None
    if t in (FieldType.IMAGE, FieldType.IMAGE_LIST):
        return ([marker] if t is FieldType.IMAGE_LIST else marker), None
    if t.is_media:
        return marker, None
    raise AssertionError(f"{t.value} has no probe value; add one rather than skipping the field")


@pytest.mark.parametrize("entity_key", [SKETCH, PROTOTYPE])
def test_no_field_of_a_sketch_or_a_prototype_reaches_no_section(entity_key):
    entity = _entity(entity_key)
    stage_key = _stage_of(entity_key)
    row: dict[str, object] = {"_entryId": "ent_probe"}
    expected: dict[str, tuple[object, object]] = {}
    for spec in entity.fields:
        if spec.deprecated or spec.report_role is ReportRole.HIDDEN:
            continue
        marker = f"Zqx{spec.key}"
        value, text = _probe_value(spec, marker)
        row[spec.key] = value
        expected[spec.key] = (spec, text)

    document, _warnings, resolver = _report({stage_key: {entity_key: [row]}})
    printed = _text(document)
    placed = set(_placed_images(document)) | set(resolver.asked)

    unprinted: list[str] = []
    for key, (spec, text) in expected.items():
        if spec.type in (FieldType.IMAGE, FieldType.IMAGE_LIST):
            if f"Zqx{key}" not in placed:
                unprinted.append(f"{key} ({spec.type.value}, no picture placed)")
            continue
        if spec.report_role is ReportRole.CAPTION:
            if f"Zqx{key}" not in printed:
                unprinted.append(f"{key} (CAPTION, text not on the page)")
            continue
        if spec.label not in printed and (text is None or str(text) not in printed):
            unprinted.append(f"{key} ({spec.type.value}/{spec.report_role.value})")

    assert not unprinted, (
        f"{entity_key} fields captured on the sketches and prototypes page that reach no section of "
        f"the archival report: {unprinted}. Give each one a printing report_role, or declare HIDDEN "
        f"with the reason, so that 'captured' and 'printed' cannot silently disagree"
    )


def test_a_media_field_of_either_entity_is_never_a_table_column():
    builder = ReportBuilder(
        WorkshopData(workshop_id="w1", title="Workshop"), template("DETAILED_TECHNICAL"),
        _Resolver(), meta=_meta(),
    )
    for entity_key in (SKETCH, PROTOTYPE):
        entity = _entity(entity_key)
        for spec in builder._table_columns(entity):
            assert not spec.type.is_media, f"{entity_key}.{spec.key} became a media table column"
        assert len(builder._table_columns(entity)) <= 6
