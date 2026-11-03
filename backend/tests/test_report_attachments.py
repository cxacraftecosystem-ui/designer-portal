import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import (
    MODEL_3D_FIELD_KEYS,
    ReportBuilder,
    WorkshopData,
    build_report,
    format_value,
    model_preview_note,
)
from app.services.report_model import ImageRef, ReportMeta
from app.services.report_templates import template
from app.services.stage_schema import (
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    stages,
)


def _entity(stage_key: str, entity_key: str):
    stage = next(s for s in stages() if s.key == stage_key)
    return next(e for e in stage.entities if e.key == entity_key)


def _field(stage_key: str, entity_key: str, field_key: str):
    spec = _entity(stage_key, entity_key).field(field_key)
    assert spec is not None, f"{entity_key}.{field_key} is no longer declared"
    return spec


def _resolver(media_id: str) -> ImageRef:
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



def test_an_attached_document_says_so_instead_of_printing_nothing():
    spec = _field("WORKSHOP_SETUP", "workshopSetup", "sanctionDocument")
    assert spec.type is FieldType.FILE, "the fixture must stay a FILE field"
    assert format_value(spec, "media-1") == "1 document attached"
    assert format_value(spec, ["media-1", "media-2"]) == "2 documents attached"


def test_a_recording_and_a_video_are_named_as_what_they_are():
    audio = _field("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "artisanAudio")
    video = _field("PROTOTYPE_DEVELOPMENT", "prototype", "processVideo")
    assert format_value(audio, "media-1") == "1 recording attached"
    assert format_value(video, ["media-1", "media-2"]) == "2 videos attached"


def test_an_empty_media_field_still_prints_nothing():
    spec = _field("WORKSHOP_SETUP", "workshopSetup", "sanctionDocument")
    assert format_value(spec, None) == ""
    assert format_value(spec, []) == ""
    assert format_value(spec, "") == ""


def test_a_photograph_still_prints_no_text_at_all():
    photo = _field("WORKSHOP_SETUP", "workshopSetup", "coverPhoto")
    assert photo.type is FieldType.IMAGE
    assert format_value(photo, "media-1") == ""
    gallery = _field("EXISTING_PRODUCTS_BASELINE", "existingProduct", "productPhotos")
    assert gallery.type is FieldType.IMAGE_LIST
    assert format_value(gallery, ["media-1", "media-2"]) == ""



def test_the_generated_report_says_a_sanction_order_was_attached():
    document, _warnings = build_report(
        WorkshopData(
            workshop_id="w1",
            title="Workshop",
            singletons={"WORKSHOP_SETUP": {"workshopTitle": "W", "sanctionDocument": "media-1"}},
        ),
        "DETAILED_TECHNICAL",
        _resolver,
        meta=ReportMeta(title="Workshop", subtitle="Cluster",
                        generated_at="2026-08-07T00:00:00Z"),
    )
    printed = _text(document)
    assert "Sanction order document" in printed
    assert "1 document attached" in printed


_UNPLACEABLE_MEDIA = frozenset(
    t for t in FieldType if t.is_media and t not in (FieldType.IMAGE, FieldType.IMAGE_LIST)
)


def test_a_media_field_is_never_a_table_column_whatever_role_it_declares():
    entity = EntitySpec(
        key="probe", name="DwProbe", cardinality=Cardinality.COLLECTION, title="Probe",
        label_field="name",
        fields=(
            FieldSpec(key="name", label="Name", type=FieldType.TEXT,
                      report_role=ReportRole.TABLE_COLUMN),
            FieldSpec(key="photo", label="Photograph", type=FieldType.IMAGE,
                      report_role=ReportRole.TABLE_COLUMN),
            FieldSpec(key="clip", label="Clip", type=FieldType.VIDEO,
                      report_role=ReportRole.TABLE_COLUMN),
        ),
    )
    builder = ReportBuilder(
        WorkshopData(workshop_id="w1", title="Workshop"), template("DETAILED_TECHNICAL"),
        _resolver, meta=ReportMeta(title="Workshop", generated_at="2026-08-19T00:00:00Z"),
    )
    assert [spec.key for spec in builder._table_columns(entity)] == ["name"], (
        "a media-typed field declared TABLE_COLUMN became a column: on A4 that is a blank column "
        "in a six-column budget, and one more column than the same table has on the handset"
    )


def test_every_media_type_that_no_image_path_places_still_prints_a_sentence():
    assert _UNPLACEABLE_MEDIA, "no media type falls outside the image path; this file is obsolete"
    for media_type in sorted(_UNPLACEABLE_MEDIA, key=lambda t: t.value):
        spec = FieldSpec(key="probe", label="Probe", type=media_type)
        assert format_value(spec, "media-1"), (
            f"{media_type.value} is a media type that ``_images`` does not place and "
            f"``format_value`` prints nothing for, so a file stored in one of these fields is "
            f"mentioned on no surface of the report"
        )


def test_no_media_field_is_left_with_a_role_that_prints_nothing_for_it():
    checked = 0
    for stage in stages():
        for entity in stage.entities:
            for spec in entity.fields:
                if spec.type not in _UNPLACEABLE_MEDIA:
                    continue
                checked += 1
                if spec.report_role is ReportRole.HIDDEN:
                    continue
                assert format_value(spec, "media-1"), (
                    f"{entity.key}.{spec.key} is {spec.type.value} with "
                    f"report_role={spec.report_role.value} and prints nothing on any surface: "
                    f"the report would not mention that the file exists"
                )
    assert checked >= 17, (
        f"the registry declares {checked} non-image media fields; this census was written against "
        f"seventeen and a shrinking count means fields were removed rather than covered"
    )



def test_a_3d_model_is_named_as_a_model_and_not_as_a_document():
    spec = _field("PROTOTYPE_DEVELOPMENT", "prototype", "modelFile")
    assert spec.type is FieldType.FILE
    assert format_value(spec, "media-1") == "1 3D model attached"
    assert format_value(spec, ["media-1", "media-2"]) == "2 3D models attached"
    other = _field("PROTOTYPE_DEVELOPMENT", "prototype", "measurementSheet")
    assert format_value(other, "media-1") == "1 document attached"


def test_the_model_table_names_real_fields_with_their_registry_labels():
    prototype = _entity("PROTOTYPE_DEVELOPMENT", "prototype")
    for model_key, (preview_key, preview_label) in MODEL_3D_FIELD_KEYS.items():
        model = prototype.field(model_key)
        preview = prototype.field(preview_key)
        assert model is not None and model.type is FieldType.FILE
        assert preview is not None and preview.type is FieldType.IMAGE_LIST
        assert preview.label == preview_label


def test_a_model_points_at_its_photographs_only_when_there_are_some():
    spec = _field("PROTOTYPE_DEVELOPMENT", "prototype", "modelFile")
    assert model_preview_note(spec, {"modelFile": "m1"}) == ""
    assert model_preview_note(spec, {"modelFile": "m1", "turntablePhotos": []}) == ""
    assert model_preview_note(spec, {"modelFile": "m1", "turntablePhotos": ["a"]}) == (
        "; 1 photograph of it under “360° capture”"
    )
    assert model_preview_note(spec, {"modelFile": "m1", "turntablePhotos": ["a", "b"]}) == (
        "; 2 photographs of it under “360° capture”"
    )
    sheet = _field("PROTOTYPE_DEVELOPMENT", "prototype", "measurementSheet")
    assert model_preview_note(sheet, {"measurementSheet": "x", "turntablePhotos": ["a"]}) == ""


def test_the_generated_report_describes_a_prototype_model_and_its_turntable():
    document, _warnings = build_report(
        WorkshopData(
            workshop_id="w1",
            title="Workshop",
            collections={
                "PROTOTYPE_DEVELOPMENT": {
                    "prototype": [
                        {
                            "_entryId": "p1",
                            "prototypeCode": "P-01",
                            "modelFile": "media-model",
                            "turntablePhotos": ["f1", "f2", "f3"],
                        }
                    ]
                }
            },
        ),
        "DETAILED_TECHNICAL",
        _resolver,
        meta=ReportMeta(title="Workshop", subtitle="Cluster", generated_at="2026-10-10T00:00:00Z"),
    )
    printed = _text(document)
    assert "1 3D model attached; 3 photographs of it under “360° capture”" in printed
    assert "document attached" not in printed
