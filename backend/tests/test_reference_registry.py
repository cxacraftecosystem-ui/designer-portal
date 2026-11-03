import re

import pytest

import app.services.stage_definitions  # noqa: F401
from app.services import stage_schema
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
    ReportRole,
    StageSpec,
    Tier,
    all_entities,
    field_to_dict,
    stage,
    validate_registry,
)


def _field(entity_key: str, field_key: str) -> FieldSpec:
    entity = next(e for _s, e in all_entities() if e.key == entity_key)
    found = entity.field(field_key)
    assert found is not None, f"{entity_key}.{field_key} is not in the registry"
    return found



def test_the_registry_is_still_sound_with_the_cascades_declared():
    assert validate_registry() == []


def _registry_of(*fields: FieldSpec) -> tuple[StageSpec, ...]:
    return (
        StageSpec(
            number=1,
            key="TEST_STAGE",
            title="Test",
            purpose="",
            entities=(
                EntitySpec(
                    key="thing",
                    name="DwTestThing",
                    cardinality=Cardinality.COLLECTION,
                    title="Things",
                    fields=fields,
                ),
            ),
        ),
    )


def _problems_about(monkeypatch, *fields: FieldSpec) -> list[str]:
    monkeypatch.setattr(stage_schema, "STAGES", _registry_of(*fields))
    return [p for p in validate_registry() if "thing" in p]


def test_a_ref_filter_by_naming_a_field_that_does_not_exist_fails_validation(monkeypatch):
    problems = _problems_about(
        monkeypatch,
        FieldSpec(key="name", label="Name", type=FieldType.TEXT, tier=Tier.BASIC),
        FieldSpec(key="productRef", label="Product", type=FieldType.REF,
                  ref_model="ProductDocumentation", ref_filter_by="makerRef"),
    )
    assert any("makerRef" in p and "not a field" in p for p in problems), problems


def test_a_ref_filter_by_that_resolves_is_accepted(monkeypatch):
    problems = _problems_about(
        monkeypatch,
        FieldSpec(key="artisanRef", label="Artisan", type=FieldType.REF, ref_model="Artisan"),
        FieldSpec(key="productRef", label="Product", type=FieldType.REF,
                  ref_model="ProductDocumentation", ref_filter_by="artisanRef"),
    )
    assert problems == []


def test_a_field_cannot_narrow_itself(monkeypatch):
    problems = _problems_about(
        monkeypatch,
        FieldSpec(key="productRef", label="Product", type=FieldType.REF,
                  ref_model="ProductDocumentation", ref_filter_by="productRef"),
    )
    assert any("itself" in p for p in problems), problems


def test_only_a_ref_field_may_carry_a_cascade_or_a_scope(monkeypatch):
    problems = _problems_about(
        monkeypatch,
        FieldSpec(key="artisanRef", label="Artisan", type=FieldType.REF, ref_model="Artisan"),
        FieldSpec(key="note", label="Note", type=FieldType.TEXT, ref_filter_by="artisanRef"),
        FieldSpec(key="other", label="Other", type=FieldType.TEXT, ref_scope=REF_SCOPE_ALL),
    )
    assert any("thing.note" in p for p in problems), problems
    assert any("thing.other" in p for p in problems), problems


def test_an_unknown_ref_scope_fails_validation(monkeypatch):
    problems = _problems_about(
        monkeypatch,
        FieldSpec(key="artisanRef", label="Artisan", type=FieldType.REF, ref_model="Artisan",
                  ref_scope="CLUSTER"),
    )
    assert any("CLUSTER" in p for p in problems), problems



def test_every_ref_field_publishes_a_scope_to_the_clients():
    refs = [f for _s, e in all_entities() for f in e.fields if f.type is FieldType.REF]
    assert refs
    for spec in refs:
        payload = field_to_dict(spec)
        assert payload["refModel"] == spec.ref_model
        assert payload["refScope"] in {REF_SCOPE_ALL, REF_SCOPE_WORKSHOP}


def test_a_cascade_is_published_and_an_absent_one_is_not():
    with_cascade = field_to_dict(_field("existingProduct", "productRef"))
    assert with_cascade["refFilterBy"] == "artisanRef"
    assert "refFilterBy" not in field_to_dict(_field("participant", "artisanRef"))


def test_the_hydration_mapping_crosses_the_wire_with_the_field():
    artisan_on_product = field_to_dict(_field("existingProduct", "artisanRef"), "existingProduct")
    assert artisan_on_product["refHydration"] == {"name": "artisanName"}

    product_on_product = field_to_dict(_field("existingProduct", "productRef"), "existingProduct")
    assert product_on_product["refHydration"]["name"] == "name"
    assert product_on_product["refHydration"]["photo"] == "productPhotos"


def test_a_field_that_hydrates_nothing_publishes_nothing():
    sketch = field_to_dict(_field("prototype", "sketchRef"), "prototype")
    assert "refHydration" not in sketch
    assert "refHydration" not in field_to_dict(_field("existingProduct", "artisanRef"))



def test_every_hydration_target_is_a_real_field_of_its_entity():
    for path, mapping in stage_schema.REFERENCE_HYDRATION.items():
        entity_key, _, ref_key = path.partition(".")
        entity = next(e for _s, e in all_entities() if e.key == entity_key)
        assert entity.field(ref_key) is not None, path
        assert entity.field(ref_key).type is FieldType.REF, path
        for source_key, target_key in mapping.items():
            assert entity.field(target_key) is not None, f"{path}[{source_key}] -> {target_key}"


def test_a_hydration_target_that_does_not_exist_fails_validation(monkeypatch):
    monkeypatch.setattr(
        stage_schema, "REFERENCE_HYDRATION",
        {"participant.artisanRef": {"name": "fullName"}},
    )
    problems = validate_registry()
    assert any("fullName" in p and "not a field" in p for p in problems), problems


def test_a_hydration_path_naming_a_non_ref_field_fails_validation(monkeypatch):
    monkeypatch.setattr(
        stage_schema, "REFERENCE_HYDRATION",
        {"participant.name": {"name": "name"}},
    )
    assert any("only a REF field" in p for p in validate_registry()), validate_registry()


def test_the_documented_process_now_fills_more_than_its_name():
    mapping = stage_schema.REFERENCE_HYDRATION["processStep.processRef"]
    assert mapping == {"name": "name", "notes": "description",
                       "productName": "documentedFor"}
    entity = next(e for _s, e in all_entities() if e.key == "processStep")
    assert entity.field("description").report_role is not ReportRole.HIDDEN
    assert entity.field("documentedFor").report_role is not ReportRole.HIDDEN


def test_a_prototype_is_not_the_product_it_derives_from():
    assert stage_schema.REFERENCE_HYDRATION["prototype.productRef"] == {"name": "productName"}
    assert stage_schema.REFERENCE_HYDRATION["existingProduct.artisanRef"] == {"name": "artisanName"}


def _web_hydration_table() -> dict[str, dict[str, str]] | None:
    from pathlib import Path

    web = Path(__file__).resolve().parents[2] / "frontend/lib/designWorkshops.ts"
    if not web.is_file():
        return None
    text = re.sub(r"//[^\n]*", "", web.read_text(encoding="utf-8"))
    start = text.find("const DW_REFERENCE_HYDRATION")
    assert start != -1, "the web dropped DW_REFERENCE_HYDRATION without telling this test"
    body = text[text.index("{", start): text.index("};", start)]
    out: dict[str, dict[str, str]] = {}
    for path, inner in re.findall(r'"([\w.]+)"\s*:\s*\{([^{}]*)\}', body):
        out[path] = dict(re.findall(r'"?([\w]+)"?\s*:\s*"([\w]+)"', inner))
    return out


def test_the_web_carries_the_same_hydration_table():
    web = _web_hydration_table()
    if web is None:
        pytest.skip("the frontend is not present in this checkout")

    server = stage_schema.REFERENCE_HYDRATION
    assert set(web) == set(server), (
        "the web's hydration table names different refs from the server's: only in web "
        f"{sorted(set(web) - set(server))}, only in server {sorted(set(server) - set(web))}"
    )
    for path, mapping in server.items():
        assert web[path] == mapping, (
            f"{path} hydrates {mapping} on the server and {web[path]} in the browser, so the same "
            f"pick fills the row differently depending on which surface the designer used"
        )



def test_the_roster_picker_is_the_one_artisan_field_that_is_not_scoped():
    roster = _field("participant", "artisanRef")
    assert roster.ref_model == "Artisan"
    assert roster.ref_scope == REF_SCOPE_ALL
    assert _field("existingProduct", "artisanRef").ref_scope == REF_SCOPE_WORKSHOP


@pytest.mark.parametrize(
    ("entity_key", "field_key", "model"),
    [
        ("participant", "artisanRef", "Artisan"),
        ("existingProduct", "productRef", "ProductDocumentation"),
        ("prototype", "artisanRef", "DwParticipant"),
        ("prototype", "productRef", "ProductDocumentation"),
        ("tool", "toolRef", "ToolDocumentation"),
        ("processStep", "processRef", "Process"),
        ("traditionalProcess", "processRef", "Process"),
        ("traditionalProcess", "productRef", "ProductDocumentation"),
        ("processStep", "productRef", "ProductDocumentation"),
        ("artisanBaseline", "interviewRef", "QuestionnaireInterview"),
        ("surveyPlan", "questionnaireRef", "Questionnaire"),
    ],
)
def test_the_records_the_requirement_named_are_selectable(entity_key, field_key, model):
    spec = _field(entity_key, field_key)
    assert spec.type is FieldType.REF
    assert spec.ref_model == model


def test_the_survey_plan_links_the_instrument_without_disturbing_the_prose_beside_it():
    ref = _field("surveyPlan", "questionnaireRef")
    prose = _field("surveyPlan", "questionnaire")

    assert prose.type is FieldType.RICH_TEXT and prose.required and prose.tier is Tier.BASIC
    assert ref.ref_scope == REF_SCOPE_ALL
    assert not ref.required and ref.tier is not Tier.BASIC
    assert ref.report_role is ReportRole.HIDDEN
    assert field_to_dict(ref, "surveyPlan")["refHydration"] == {"name": "questionnaireName"}
    assert _field("surveyPlan", "questionnaireName").type is FieldType.TEXT


def test_a_record_backed_multi_enum_declares_a_picker_the_wire_can_carry():
    for entity_key in ("processStep", "prototype"):
        out = field_to_dict(_field(entity_key, "toolsUsed"), entity_key)
        assert out["type"] == "MULTI_ENUM", entity_key
        assert out["refModel"] == "ToolDocumentation", entity_key
        assert out["refScope"] == REF_SCOPE_ALL, entity_key
        assert "options" not in out and "enum" not in out, entity_key
        assert "refHydration" not in out, entity_key


def test_the_two_artisan_fed_product_pickers_cascade_from_the_artisan_on_their_own_row():
    for entity_key in ("existingProduct", "prototype"):
        spec = _field(entity_key, "productRef")
        assert spec.ref_filter_by == "artisanRef", entity_key


def test_both_process_pickers_cascade_from_the_product_on_their_own_row():
    for entity_key in ("traditionalProcess", "processStep"):
        spec = _field(entity_key, "processRef")
        assert spec.ref_filter_by == "productRef", entity_key
        parent = _field(entity_key, "productRef")
        assert parent.type is FieldType.REF, entity_key
        assert parent.ref_model == "ProductDocumentation", entity_key
        assert parent.ref_scope == spec.ref_scope == REF_SCOPE_WORKSHOP, entity_key
        assert parent.report_role is ReportRole.HIDDEN, entity_key
        assert parent.tier is not Tier.BASIC, entity_key


def test_the_cascade_parent_is_never_the_text_box_that_holds_the_parents_name():
    for _stage, entity in all_entities():
        for f in entity.fields:
            if not f.ref_filter_by:
                continue
            parent = entity.field(f.ref_filter_by)
            assert parent is not None and parent.type is FieldType.REF, (
                f"{entity.key}.{f.key} is filtered by {f.ref_filter_by!r}, which is not a REF field "
                f"— so the client would send that box's TEXT as filterBy and the picker would be "
                f"permanently empty"
            )


def test_the_text_a_reference_fills_in_is_kept_and_says_so():
    for entity_key, field_key in (
        ("participant", "name"),
        ("participant", "village"),
        ("existingProduct", "name"),
        ("existingProduct", "price"),
        ("tool", "name"),
        ("processStep", "name"),
    ):
        spec = _field(entity_key, field_key)
        assert spec.type is not FieldType.REF
        assert "linked record" in spec.details, f"{entity_key}.{field_key} does not say where it "\
                                             "comes from"


def test_the_core_chain_is_still_traversable():
    refs = {
        (entity.key, f.ref_model)
        for _s, entity in all_entities()
        for f in entity.fields
        if f.type is FieldType.REF
    }
    for hop in (
        ("prototype", "DwSketch"),
        ("prototypeIteration", "DwPrototype"),
        ("prototypeValidation", "DwPrototype"),
        ("finalProduct", "DwPrototype"),
        ("costSheet", "DwFinalProduct"),
        ("followUp", "DwFinalProduct"),
    ):
        assert hop in refs, hop


def test_the_picker_comes_before_the_fields_it_fills_in():
    for stage_key, entity_key, ref_key, filled_key in (
        ("TRADITIONAL_PROCESS_BASELINE", "tool", "toolRef", "name"),
        ("EXISTING_PRODUCTS_BASELINE", "existingProduct", "productRef", "name"),
        ("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", "artisanRef", "name"),
        ("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "productRef", "processRef"),
        ("TRADITIONAL_PROCESS_BASELINE", "traditionalProcess", "processRef",
         "documentedProcessName"),
        ("TRADITIONAL_PROCESS_BASELINE", "processStep", "productRef", "processRef"),
        ("TRADITIONAL_PROCESS_BASELINE", "processStep", "processRef", "name"),
        ("EXISTING_PRODUCTS_BASELINE", "artisanBaseline", "interviewRef", "interviewTitle"),
    ):
        entity = stage(stage_key).entity(entity_key)
        keys = [f.key for f in entity.fields]
        assert keys.index(ref_key) < keys.index(filled_key), entity_key
