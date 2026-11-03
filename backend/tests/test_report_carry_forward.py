from __future__ import annotations

from typing import Any

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.design_workshops import (
    _PRODUCT_TYPE_TO_CATEGORY,
    REFERENCE_MODELS,
    ReferencePhoto,
)
from app.services.report_annexures import TranscriptItem, attach_transcripts
from app.services.report_builder import (
    COVER_INFO_ROWS,
    COVER_PINNED_KEYS,
    ReferencedRecord,
    ReportBuilder,
    WorkshopData,
    build_report,
    cover_rows,
    format_value,
)
from app.services.report_model import (
    CoverBlock,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    KeyValueBlock,
    MetricRowBlock,
    ParagraphBlock,
    ReportMeta,
    TableBlock,
    runs_text,
)
from app.services.report_templates import TEMPLATES, SpecialSection, template
from app.services.stage_schema import (
    ENUMS,
    REFERENCE_HYDRATION,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    stages,
)

CARRIED: dict[str, dict[str, str]] = {}
for _path, _mapping in REFERENCE_HYDRATION.items():
    _entity_key, _, _ref_field = _path.partition(".")
    CARRIED.setdefault(_entity_key, {}).update({t: s for s, t in _mapping.items()})

_ENTITIES: dict[str, EntitySpec] = {e.key: e for s in stages() for e in s.entities}
_ENTITY_STAGE: dict[str, str] = {e.key: s.key for s in stages() for e in s.entities}


def _spec(entity_key: str, field_key: str) -> FieldSpec:
    field = _ENTITIES[entity_key].field(field_key)
    assert field is not None, (
        f"{entity_key}.{field_key} is a REFERENCE_HYDRATION target with no field to land on. "
        f"stage_schema.validate_registry refuses this; if you are reading it here the registry "
        f"check was skipped."
    )
    return field


def _hydration_pairs() -> list[tuple[str, str, str]]:
    return [(entity_key, target, source)
            for entity_key, fields in CARRIED.items()
            for target, source in fields.items()]


_PRINTED_ROLES = frozenset({
    ReportRole.NARRATIVE, ReportRole.KEY_VALUE, ReportRole.TABLE_COLUMN,
    ReportRole.COVER_FIELD, ReportRole.BULLETS,
})


@pytest.mark.parametrize("entity_key,target,source", _hydration_pairs(),
                         ids=lambda v: str(v))
def test_every_carried_field_has_a_role_the_report_prints(entity_key, target, source):
    spec = _spec(entity_key, target)
    if spec.type in (FieldType.IMAGE, FieldType.IMAGE_LIST):
        return
    if spec.report_role is ReportRole.CAPTION:
        captioned = _ENTITIES[entity_key].field(spec.caption_for)
        assert captioned is not None and captioned.type.is_media, (
            f"{entity_key}.{target} is a CAPTION for {spec.caption_for!r}, which is not a media "
            f"field of this entity, so the carried caption is printed by nothing."
        )
        return
    assert spec.report_role in _PRINTED_ROLES, (
        f"{entity_key}.{target} receives {source!r} from the picker and is declared "
        f"report_role={spec.report_role.value}, which no stage section prints. Give it "
        f"KEY_VALUE (the default) unless it genuinely belongs in a table."
    )


@pytest.mark.parametrize("entity_key,target,source", _hydration_pairs(),
                         ids=lambda v: str(v))
def test_no_carried_field_is_a_metric(entity_key, target, source):
    spec = _spec(entity_key, target)
    assert spec.report_role is not ReportRole.METRIC, (
        f"{entity_key}.{target} is declared METRIC. A collection's METRIC fields are never "
        f"rendered and a singleton prints only its first four; use KEY_VALUE."
    )


_WIDTHS_ALREADY_INERT = frozenset({"prototype", "finalProduct", "prototypeValidation"})


@pytest.mark.parametrize("entity_key", sorted(CARRIED))
def test_a_carried_table_keeps_its_declared_column_widths(entity_key):
    if entity_key in _WIDTHS_ALREADY_INERT:
        pytest.skip(f"{entity_key}'s declared widths were already ignored before this work")
    columns = [f for f in _ENTITIES[entity_key].fields
               if f.report_role is ReportRole.TABLE_COLUMN and not f.deprecated]
    declared = sum(c.column_width_pct for c in columns[:6])
    assert not declared or abs(declared - 100.0) < 0.5, (
        f"the six drawn columns of {entity_key} declare widths summing to {declared}, so "
        f"_render_table discards all of them and lays the table out proportionally instead. "
        f"Declare a carried field as KEY_VALUE, or re-balance every width in the entity."
    )


def test_a_carried_enum_can_only_receive_a_token_the_report_can_name():
    declared = set(ENUMS["PRODUCT_CATEGORY"])
    produced = {v for v in _PRODUCT_TYPE_TO_CATEGORY.values() if v}
    assert produced <= declared, (
        f"_PRODUCT_TYPE_TO_CATEGORY maps a ProductType onto {sorted(produced - declared)}, which "
        f"is not a PRODUCT_CATEGORY token. enum_label would print it verbatim into the report."
    )
    for entity_key, target, _source in _hydration_pairs():
        spec = _spec(entity_key, target)
        if spec.type in (FieldType.ENUM, FieldType.MULTI_ENUM):
            assert spec.enum in ENUMS, (
                f"{entity_key}.{target} is an ENUM field with no canonical option list, so every "
                f"value hydrated into it prints as its raw token."
            )


_UNIT_UNKNOWN_MARKERS = ("as recorded", "unit not stated", "unit unknown")


def test_a_carried_measurement_says_what_it_is_measured_in():
    for entity_key, target, source in _hydration_pairs():
        spec = _spec(entity_key, target)
        if spec.type not in (FieldType.INT, FieldType.DECIMAL):
            continue
        if spec.unit:
            printed = format_value(spec, 12)
            assert spec.unit in printed, f"{entity_key}.{target} printed {printed!r}"
            continue
        label = spec.label.casefold()
        assert any(marker in label for marker in _UNIT_UNKNOWN_MARKERS), (
            f"{entity_key}.{target} carries {source!r} as a bare {spec.type.value} with no unit "
            f"and a label ({spec.label!r}) that does not say the unit is unknown, so the report "
            f"prints a number a reader will silently assume a unit for. Declare unit=, or say so "
            f"in the LABEL — the help text is not printed by any report."
        )



class _SourceRow:
    def __init__(self, **columns: Any) -> None:
        self.__dict__.update(columns)

    def __getattr__(self, name: str) -> Any:
        raise AssertionError(
            f"REFERENCE_MODELS read the column {name!r}, which this fixture does not set. If you "
            f"have just widened a reference model, add {name!r} to the source row in "
            f"tests/test_report_carry_forward.py so the new value is actually asserted on."
        )


def _media(media_type: str, filename: str, purpose: str | None = None) -> _SourceRow:
    return _SourceRow(mediaType=media_type, originalFilename=filename, extraMetadata=purpose
                      and {"purpose": purpose})


_MEDIA_IMAGE = _media("IMAGE", "saree-on-the-loom.jpg")


def _source_rows() -> dict[str, _SourceRow]:
    return {
        "Artisan": _SourceRow(
            id="artisan-1",
            name="Bhikari Meher",
            localName="ଭିକାରୀ ମେହେର",
            gender="Male",
            phone="9876543210",
            phoneCountryCode="+91",
            email="bhikari.meher@example.in",
            place="Barpali",
            address="House 44, Weavers' Lane, Barpali",
            aadhaarNumber="234567890123",
            aadhaarLast4="0123",
            pehchanCardAvailable=True,
            pehchanCardNumber="APC7781234509",
            notes="Works to commission through the cooperative; prefers morning sittings.",
            dos="Address him as Guruji\nAllow time for the tying to be explained",
            donts="Do not photograph the loom shed without asking",
            recordedAt="2025-03-12T09:15:00+05:30",
            dateOfBirth="1971-01-08T00:00:00+05:30",
            craftStartDate="1994-01-15T00:00:00+05:30",
            experienceYears=31,
            craft=_SourceRow(name="Sambalpuri Ikat"),
            location=_SourceRow(
                village="Barpali", district="Bargarh", state="Odisha", pincode="768029",
                address="Weavers' Lane, Barpali", subjectLatitude=21.1857, subjectLongitude=83.5876,
            ),
            workshop=_SourceRow(title="Barpali cluster documentation, 2025"),
            media=(
                _MEDIA_IMAGE,
                _media("AUDIO", "introduction.m4a"),
            ),
            extraMetadata={"experienceYears": 27, "age": 54},
        ),
        "ProductDocumentation": _SourceRow(
            id="product-1",
            productName="Sambalpuri Ikat saree",
            localName="ସମ୍ବଲପୁରୀ ଶାଢ଼ୀ",
            productType="PACKAGING",
            craftName="Sambalpuri Ikat",
            place="Barpali",
            rawMaterialsUsed="Mulberry silk, natural indigo",
            mainToolsUsed="Pit loom, warping drum",
            sellingPrice="12500.00",
            costOfMaking="7400.00",
            marketDemand="HIGH",
            productFunctionUse="Worn at weddings and festivals",
            artisanName="Bhikari Meher",
            size="Six yards with a two-foot pallu",
            timeTakenToCompleteProduct="Eighteen days on the loom",
            remarks="Two colourways were documented; the indigo one is shown.",
            recordedAt="2025-03-12T10:40:00+05:30",
            lengthInches="18.00",
            breadthInches="12.00",
            heightInches="2.00",
            extraMetadata={
                "fieldProvenance": {
                    "lengthInches": {"by": "usr_3", "byName": "R. Menon",
                                     "at": "2025-03-12T10:40:00+05:30",
                                     "method": "VISION_MODEL", "methodProvider": "gemini"},
                    "breadthInches": {"by": "usr_3", "byName": "R. Menon",
                                      "at": "2025-03-12T10:40:00+05:30",
                                      "method": "VISION_MODEL", "methodProvider": "gemini"},
                    "heightInches": {"by": "usr_3", "byName": "R. Menon",
                                     "at": "2025-03-12T10:40:00+05:30", "method": "TYPED"},
                },
            },
            location=_SourceRow(
                village="Barpali", district="Bargarh", state="Odisha", pincode="768029",
                subjectLatitude=21.1857, subjectLongitude=83.5876,
            ),
            media=(_MEDIA_IMAGE, _media("VIDEO", "cutting-down-the-saree.mp4")),
        ),
        "ToolDocumentation": _SourceRow(
            id="tool-1",
            toolkitName="Pit loom",
            localName="ଗାଡ଼ ତନ୍ତ",
            englishName="Throw-shuttle pit loom",
            craftName="Sambalpuri Ikat",
            place="Barpali",
            artisanName="Bhikari Meher",
            material="Sal wood and bamboo",
            processUsedIn="Weaving the tie-dyed warp and weft",
            replacementCost="18500.00",
            yearsInUse=22,
            maker="LOCAL_BLACKSMITH",
            traditionType="TRADITIONAL",
            suggestionsForToolImprovement="A wider reed would let the pallu be woven in one pass.",
            remarks="Rebuilt once after the 2019 flood.",
            recordedAt="2025-03-13T08:05:00+05:30",
            lengthInches="96.00",
            breadthInches="48.00",
            heightInches="30.00",
            height="150.00",
            width="120.00",
            thickness="6.50",
            weight="88.00",
            radius="14.00",
            extraMetadata={
                "fieldProvenance": {
                    "lengthInches": {"by": "usr_4", "byName": "S. Bal",
                                     "at": "2025-03-13T08:05:00+05:30",
                                     "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                    "breadthInches": {"by": "usr_4", "byName": "S. Bal",
                                      "at": "2025-03-13T08:05:00+05:30",
                                      "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                    "heightInches": {"by": "usr_4", "byName": "S. Bal",
                                     "at": "2025-03-13T08:05:00+05:30",
                                     "method": "PHOTO_GEOMETRY", "methodTechnique": "SCALE"},
                },
            },
            location=_SourceRow(
                village="Barpali", district="Bargarh", state="Odisha", pincode="768029",
                subjectLatitude=21.1857, subjectLongitude=83.5876,
            ),
            media=(
                _media("IMAGE", "STAGE_STEP_1_warping.jpg"),
                _media("IMAGE", "STAGE_STEP_2_treadles.jpg"),
                _media("AUDIO", "weaver-explains.m4a"),
                _media("IMAGE", "grid-length.jpg", purpose="MEASUREMENT_GRID"),
            ),
            artisanLinks=(
                _SourceRow(artisan=_SourceRow(name="Bhikari Meher")),
                _SourceRow(artisan=_SourceRow(name="Sanjukta Meher")),
            ),
        ),
        "Process": _SourceRow(
            id="process-1",
            name="Bandha tie-and-dye",
            notes="The warp is tied in bundles and dipped, darkest colour last.",
            preProcessAvailable=True,
            recordedAt="2025-03-14T11:00:00+05:30",
            product=_SourceRow(productName="Sambalpuri Ikat saree"),
            media=(_media("VIDEO", "pre-process.mp4"),),
            steps=(
                _SourceRow(sortOrder=1, name="Degumming the silk", stepType="STEP",
                           notes="Boiled with soda ash.", media=(_MEDIA_IMAGE,)),
                _SourceRow(sortOrder=2, name="Tying the bundles", stepType="STEP", notes="",
                           media=()),
                _SourceRow(sortOrder=3, name="Dyeing", stepType="GROUP",
                           notes="Lightest colour first.", media=()),
            ),
        ),
        "Craft": _SourceRow(
            id="craft-1",
            name="Sambalpuri Ikat",
            localName="ସମ୍ବଲପୁରୀ ବନ୍ଧା",
            category="Weaving",
            place="Barpali",
            description="Warp and weft are tied and dyed before the cloth is woven.",
            recordedAt="2024-11-05T10:00:00+05:30",
            workshop=_SourceRow(title="Barpali cluster documentation, 2024"),
            media=(_MEDIA_IMAGE, _media("PDF", "gazetteer-page.pdf")),
        ),
        "Questionnaire": _SourceRow(
            id="questionnaire-1",
            title="Barpali cluster intake survey",
        ),
        "QuestionnaireInterview": _SourceRow(
            id="interview-1",
            title="Barpali weavers, group 2",
            interviewDate="2026-03-14T09:30:00+05:30",
            place="Barpali",
            language="Odia",
            recordedAt="2026-03-15T18:20:00+05:30",
            workshop=_SourceRow(title="Barpali cluster documentation, 2024"),
            artisans=tuple(_SourceRow() for _ in range(6)),
            responses=(
                _SourceRow(questionId="q-1", answerText="Six looms in the house, four working.",
                           updatedAt="2026-03-14T10:05:00+05:30",
                           question=_SourceRow(id="q-1", sectionCode="AHOUSEHOLD")),
                _SourceRow(questionId="q-2", answerText="Two sons weave; the elder also dyes.",
                           updatedAt="2026-03-14T10:20:00+05:30",
                           question=_SourceRow(id="q-2", sectionCode="AHOUSEHOLD")),
                _SourceRow(questionId="q-3", answerText="Sold through the cooperative at Barpali.",
                           updatedAt="2026-03-14T11:40:00+05:30",
                           question=_SourceRow(id="q-3", sectionCode="CMARKET")),
                _SourceRow(questionId="q-4", answerText="   ",
                           updatedAt="2026-03-16T09:00:00+05:30",
                           question=_SourceRow(id="q-4", sectionCode="DTRAINING")),
            ),
            media=(
                _MEDIA_IMAGE,
                _media("AUDIO", "BLIVELIHOOD_Q14_GROUP2_001432_140320260930.m4a"),
                _SourceRow(mediaType="IMAGE", originalFilename="consent-sheet.jpg",
                           extraMetadata={"sectionCode": "DTRAINING"}),
            ),
        ),
    }


PHOTO_ID = "media-photo-1"
PHOTO_CAPTION = "The saree on the loom, three days before it was cut down."


_INTERNAL_SOURCE_ROWS: dict[str, dict[str, Any]] = {
    "DwPrototype": {
        "name": "Ikat table runner, wide border",
        "materials": ["Cotton", "Natural indigo"],
        "lengthCm": 180.0,
        "widthCm": 45.0,
        "heightCm": 2.0,
        "weightG": 640.0,
        "dimensionsNote": "180 x 45, fringe 6 cm either end",
        "makingTimeDays": 4.5,
        "processSummary": "Warped on the pit loom; four tie-and-dye bands across the width.",
    },
}


def _internal_source_models() -> dict[str, set[str]]:
    wanted: dict[str, set[str]] = {}
    models = {e.name for e in _ENTITIES.values()}
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, ref_field = path.partition(".")
        model = _spec(entity_key, ref_field).ref_model
        if model in models:
            wanted.setdefault(model, set()).update(mapping)
    return wanted


def _reference_data() -> dict[str, dict[str, Any]]:
    rows = _source_rows()
    photo = ReferencePhoto(id=PHOTO_ID, caption=PHOTO_CAPTION)
    produced: dict[str, dict[str, Any]] = {
        model: REFERENCE_MODELS[model].data(rows[model], photo)
        for model in rows
    }
    for model, keys in _internal_source_models().items():
        fixture = _INTERNAL_SOURCE_ROWS.get(model)
        assert fixture is not None, (
            f"{model} is now an internal hydration source and this file has no fixture row for it. "
            f"Add one to _INTERNAL_SOURCE_ROWS holding every key the mapping reads, or the carry "
            f"will be asserted against an empty record and pass while printing nothing."
        )
        missing = sorted(keys - set(fixture))
        assert not missing, (
            f"_INTERNAL_SOURCE_ROWS[{model!r}] is missing {missing}, which some mapping reads. A "
            f"blank source is indistinguishable here from a carry that does not work."
        )
        produced[model] = dict(fixture)
    return produced


def _hydrated_rows() -> dict[str, dict[str, Any]]:
    produced = _reference_data()
    ref_field_model = {
        f"{e.key}.{f.key}": f.ref_model
        for e in _ENTITIES.values() for f in e.fields if f.type is FieldType.REF
    }
    out: dict[str, dict[str, Any]] = {}
    for path, mapping in REFERENCE_HYDRATION.items():
        entity_key, _, ref_field = path.partition(".")
        source = produced[ref_field_model[path]]
        row = out.setdefault(entity_key, {})
        row[ref_field] = f"{ref_field_model[path]}-ref"
        for source_key, target_key in mapping.items():
            value = source.get(source_key)
            if value in (None, ""):
                continue
            spec = _spec(entity_key, target_key)
            row[target_key] = (
                [value] if spec.type.is_multi and not isinstance(value, list) else value
            )
    return out


def _workshop_data() -> WorkshopData:
    rows = _hydrated_rows()
    data = WorkshopData(workshop_id="dw-1", title="Design & Prototype Workshop — Sambalpuri Ikat")
    for entity_key, row in rows.items():
        stage_key = _ENTITY_STAGE[entity_key]
        entity = _ENTITIES[entity_key]
        row = dict(row, _entryId=f"entry-{entity_key}")
        if entity.cardinality is Cardinality.SINGLETON:
            data.singletons.setdefault(stage_key, {}).update(row)
        else:
            data.collections.setdefault(stage_key, {}).setdefault(entity_key, []).append(row)
    data.collections["PROTOTYPE_DEVELOPMENT"]["prototype"][0].update(
        prototypeCode="PT-01", name="Ikat table runner", materials=["Cotton"],
    )
    data.collections["FINAL_PROTOTYPE_DOCUMENTATION"]["finalProduct"][0].setdefault(
        "productCode", "FP-01"
    )
    data.collections["PROTOTYPE_VALIDATION"]["prototypeValidation"][0].setdefault(
        "decision", "APPROVED"
    )
    data.collections["TRADITIONAL_PROCESS_BASELINE"]["processStep"][0]["stepNumber"] = 1
    for model in ("Artisan", "ProductDocumentation", "ToolDocumentation", "Process", "Craft"):
        data.references[f"{model}-ref"] = ReferencedRecord(
            model=model, label=f"{model} record", photo=PHOTO_ID,
            place="Barpali", district="Bargarh", state="Odisha",
        )
    return data


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=1200, height_px=900, mime_type="image/jpeg")


def _document_text(document: Any) -> str:
    parts: list[str] = []
    for block in document.blocks:
        if isinstance(block, ParagraphBlock):
            parts.append(runs_text(block.runs))
        elif isinstance(block, KeyValueBlock):
            parts.extend(f"{label}: {runs_text(value)}" for label, value in block.pairs)
        elif isinstance(block, TableBlock):
            parts.extend(c.header for c in block.columns)
            parts.extend(runs_text(cell) for row in block.rows for cell in row)
        elif isinstance(block, CoverBlock):
            parts.extend(f"{label}: {value}" for label, value in block.info_rows)
            parts.append(block.title)
        elif isinstance(block, MetricRowBlock):
            parts.extend(f"{label} {value} {unit}" for label, value, unit in block.metrics)
        elif isinstance(block, ImageBlock):
            parts.append(block.caption)
        elif isinstance(block, ImageGridBlock):
            parts.extend(caption for _image, caption in block.images)
        else:
            items = getattr(block, "items", None)
            if items:
                parts.extend(runs_text(item) if not isinstance(item, str) else item
                             for item in items)
    return "\n".join(parts)


def _reaches(spec: FieldSpec, expected: str, printed: str) -> bool:
    if spec.report_role is not ReportRole.BULLETS:
        return expected in printed
    lines = [line.strip() for part in expected.split("\n")
             for line in part.split(";") if line.strip()]
    return bool(lines) and all(line in printed for line in lines)


def _image_sources(document: Any) -> set[str]:
    found: set[str] = set()
    for block in document.blocks:
        if isinstance(block, ImageBlock):
            found.add(block.image.source)
        elif isinstance(block, ImageGridBlock):
            found.update(image.source for image, _caption in block.images)
    return found


def _render(template_id: str) -> tuple[Any, list[str]]:
    return build_report(
        _workshop_data(), template_id, _resolver,
        meta=ReportMeta(title="Sambalpuri Ikat", generated_at="2026-08-16T00:00:00Z"),
    )


FULL_TEMPLATES = ("DCH_STANDARD", "DIC_STANDARD", "DETAILED_TECHNICAL")


@pytest.mark.parametrize("template_id", FULL_TEMPLATES)
def test_every_carried_field_reaches_the_document(template_id):
    document, _warnings = _render(template_id)
    printed = _document_text(document)
    missing: list[str] = []
    for entity_key, row in _hydrated_rows().items():
        for target, value in row.items():
            spec = _ENTITIES[entity_key].field(target)
            if spec is None or spec.type is FieldType.REF or spec.type.is_media:
                continue
            expected = format_value(spec, value)
            if expected and not _reaches(spec, expected, printed):
                missing.append(f"{entity_key}.{target} = {expected!r}")
    assert not missing, (
        f"{template_id} printed no trace of {len(missing)} carried value(s): "
        + "; ".join(missing)
        + f"\n--- what the document actually said ---\n{printed}"
    )


@pytest.mark.parametrize("template_id", FULL_TEMPLATES)
def test_a_carried_photograph_is_placed(template_id):
    document, _warnings = _render(template_id)
    assert PHOTO_ID in _image_sources(document)


def test_a_carried_measurement_prints_its_unit_in_the_document():
    data = _workshop_data()
    data.collections["EXISTING_PRODUCTS_BASELINE"]["existingProduct"][0]["lengthCm"] = 45.72
    builder = ReportBuilder(data, template("DETAILED_TECHNICAL"), _resolver,
                            meta=ReportMeta(title="x", generated_at="2026-08-16T00:00:00Z"))
    printed = _document_text(builder.build())
    assert "45.72 cm" in printed, printed


def test_a_carried_enum_prints_its_label_and_never_its_token():
    document, _warnings = _render("DETAILED_TECHNICAL")
    printed = _document_text(document)
    assert "Packaging" in printed
    assert "PACKAGING" not in printed


def test_a_carried_price_prints_as_money():
    document, _warnings = _render("DETAILED_TECHNICAL")
    assert "₹ 12,500.00" in _document_text(document)


def test_the_process_notes_reach_the_report_as_the_step_description():
    printed = _document_text(_render("DCH_STANDARD")[0])
    assert "darkest colour last" in printed
    assert "Sambalpuri Ikat saree" in printed



def test_compact_summary_is_the_one_template_that_drops_a_carried_field_by_tier():
    assert [t.id for t in TEMPLATES if t.max_tier.rank < 2] == ["COMPACT_SUMMARY"]
    _document, warnings = _render("COMPACT_SUMMARY")
    tier_warnings = [w for w in warnings if "capture tier" in w]
    assert tier_warnings, (
        "COMPACT_SUMMARY dropped Standard-tier fields the designer filled in and said nothing"
    )
    assert "Generate the report with a template that captures every tier" in tier_warnings[0]


@pytest.mark.parametrize("template_id", FULL_TEMPLATES)
def test_a_template_that_admits_every_tier_raises_no_tier_warning(template_id):
    _document, warnings = _render(template_id)
    assert not [w for w in warnings if "capture tier" in w]


def test_the_cover_says_so_when_a_filled_cover_field_fits_nowhere():
    data = _workshop_data()
    setup = data.singletons.setdefault("WORKSHOP_SETUP", {})
    setup.update(
        workshopTitle="Design & Prototype Development Workshop",
        schemeName="National Handicrafts Development Programme",
        clusterName="Barpali", state="Odisha", district="Bargarh",
        block="Barpali", village="Barpali", venue="Weavers' Service Centre",
        startDate="2026-02-10", endDate="2026-02-24", durationDays=15,
        designerName="A. Kumar", designerInstitution="NID Ahmedabad",
        implementingAgency="Sambalpuri Bastralaya", sponsor="DC (Handicrafts)",
        sanctionOrderNo="DCH/2026/114", sanctionOrderDate="2026-01-06",
        workshopCode="DW-2026-114",
    )

    def render(template_id: str) -> tuple[Any, list[str]]:
        return build_report(data, template_id, _resolver,
                            meta=ReportMeta(title="Sambalpuri Ikat",
                                            generated_at="2026-08-16T00:00:00Z"))

    document, warnings = render("IMPLEMENTING_AGENCY")
    covers = [b for b in document.blocks if isinstance(b, CoverBlock)]
    assert covers and len(covers[0].info_rows) == COVER_INFO_ROWS
    dropped = [w for w in warnings if "cover field" in w]
    assert dropped, "ten cover rows printed, eleven more filled in, and nothing said"
    assert "Start date" in dropped[0] or "did not fit" in dropped[0]

    for template_id in (*FULL_TEMPLATES, "COMPACT_SUMMARY"):
        _doc, quiet = render(template_id)
        assert not [w for w in quiet if "cover field" in w], template_id


@pytest.mark.parametrize("template_id", [t.id for t in TEMPLATES])
def test_the_sanctioned_amount_is_on_the_rendered_cover_of_a_fully_filled_workshop(template_id):
    data = _workshop_data()
    setup = data.singletons.setdefault("WORKSHOP_SETUP", {})
    setup.update(
        workshopTitle="Design & Prototype Development Workshop",
        schemeName="National Handicrafts Development Programme",
        clusterName="Barpali", state="Odisha", district="Bargarh",
        block="Barpali", village="Barpali", venue="Weavers' Service Centre",
        startDate="2026-02-10", endDate="2026-02-24", durationDays=15,
        designerName="A. Kumar", designerInstitution="NID Ahmedabad",
        implementingAgency="Sambalpuri Bastralaya", sponsor="DC (Handicrafts)",
        sanctionOrderNo="DCH/2026/114", sanctionOrderDate="2026-01-06",
        sanctionAmount=450000, workshopCode="DW-2026-114",
    )
    document, warnings = build_report(
        data, template_id, _resolver,
        meta=ReportMeta(title="Sambalpuri Ikat", generated_at="2026-08-16T00:00:00Z"),
    )
    covers = [b for b in document.blocks if isinstance(b, CoverBlock)]
    if not covers:
        pytest.skip(f"{template_id} has no cover")
    labels = [label for label, _value in covers[0].info_rows]
    assert "Sanctioned amount" in labels, labels
    assert len(labels) == COVER_INFO_ROWS
    assert not [w for w in warnings if "cover field" in w and "Sanctioned amount" in w]


def test_cover_rows_reserves_a_row_for_a_pinned_field_and_keeps_declared_order():
    assert frozenset({"sanctionAmount"}) == COVER_PINNED_KEYS
    keyed = [(f"k{i}", f"L{i}", "v") for i in range(12)] + [("sanctionAmount", "Amt", "1")]
    printed, overflow = cover_rows(keyed)
    assert [label for label, _v in printed] == [f"L{i}" for i in range(9)] + ["Amt"]
    assert [label for label, _v in overflow] == ["L9", "L10", "L11"]
    short = [("a", "A", "1"), ("sanctionAmount", "Amt", "2"), ("b", "B", "3")]
    assert cover_rows(short) == ([("A", "1"), ("Amt", "2"), ("B", "3")], [])


def test_the_three_narrow_templates_drop_carried_stages_by_section_and_not_by_accident():
    printed = {t.id: {s.stage_key for s in t.sections if s.stage_key} for t in TEMPLATES}
    for template_id in FULL_TEMPLATES:
        assert {_ENTITY_STAGE[k] for k in CARRIED} <= printed[template_id]
    assert "WORKSHOP_SETUP" not in printed["IMPLEMENTING_AGENCY"]
    assert "WORKSHOP_SETUP" not in printed["PHOTO_CATALOGUE"]
    assert "WORKSHOP_PLAN_PARTICIPANTS_OPENING" in printed["PHOTO_CATALOGUE"]


def test_the_buyer_facing_catalogue_prints_the_roster_at_every_tier():
    catalogue = template("PHOTO_CATALOGUE")
    assert catalogue.max_tier.rank == 2
    document, _warnings = _render("PHOTO_CATALOGUE")
    printed = _document_text(document)
    assert "9876543210" in printed
    assert "bhikari.meher@example.in" in printed
    assert "House 44, Weavers' Lane, Barpali" in printed
    assert "21.18570, 83.58760" in printed
    assert "Address him as Guruji" in printed
    assert "prefers morning sittings" in printed
    assert "APC7781234509" not in printed



def test_the_media_annexure_carries_the_picture_and_the_words_under_it():
    document, _warnings = _render("DETAILED_TECHNICAL")
    grids = [b for b in document.blocks if isinstance(b, ImageGridBlock)]
    assert grids, "DETAILED_TECHNICAL declares ANNEXURE_MEDIA and it drew nothing"
    captions = {caption for grid in grids for _image, caption in grid.images}
    assert PHOTO_ID in {img.source for grid in grids for img, _c in grid.images}
    assert PHOTO_CAPTION in captions, sorted(captions)


def test_every_template_carries_the_questionnaire_and_transcript_annexures():
    for report_template in TEMPLATES:
        specials = {s.special for s in report_template.sections}
        assert SpecialSection.ANNEXURE_QUESTIONNAIRES in specials, report_template.id
        assert SpecialSection.ANNEXURE_TRANSCRIPTS in specials, report_template.id


def test_an_audio_clip_carried_onto_a_stage_field_reaches_the_transcript_annexure():
    data = _workshop_data()
    attach_transcripts(data, [TranscriptItem(
        media_id="media-audio-1",
        stage_key="TRADITIONAL_PROCESS_BASELINE", stage_number=5,
        stage_title="Traditional Process, Tools & Raw Materials",
        entity_key="traditionalProcess", field_key="artisanAudio",
        field_label="Artisan’s spoken explanation",
        text="**Speaker 1:** The tying takes longer than the weaving.",
    )])
    builder = ReportBuilder(data, template("COMPACT_SUMMARY"), _resolver,
                            meta=ReportMeta(title="x", generated_at="2026-08-16T00:00:00Z"))
    printed = _document_text(builder.build())
    assert "The tying takes longer than the weaving." in printed, (
        "the transcript annexure is the one section a Basic-tier template still prints in full"
    )
