"""The cascading-picker declarations, and the registry rule that keeps them resolvable.

A REF field that a designer picks from carries three things beyond the model it points at: how
wide the picker casts (``ref_scope``), which other field on the same row narrows it
(``ref_filter_by``), and the text fields the chosen record fills in. Every one of those is
silent when it is wrong — a broken cascade does not error, it merely stops narrowing — so the
rules are enforced by :func:`validate_registry` and asserted here.

Nothing in this file touches a database. The resolver that reads one is in
``test_reference_resolver.py``.
"""

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.services import stage_schema
from app.services.stage_schema import (
    REF_SCOPE_ALL,
    REF_SCOPE_WORKSHOP,
    Cardinality,
    EntitySpec,
    FieldSpec,
    FieldType,
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


# --------------------------------------------------------------------------------------
# The rule
# --------------------------------------------------------------------------------------


def test_the_registry_is_still_sound_with_the_cascades_declared():
    assert validate_registry() == []


def _registry_of(*fields: FieldSpec) -> tuple[StageSpec, ...]:
    """A one-stage registry, so a deliberately broken field can be validated in isolation."""
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
    """Every complaint validate_registry makes about ``thing``, ignoring promoted-column noise.

    Swapping the whole registry out is what makes a broken declaration testable at all: the
    twenty-two real stages are sound by construction, and the point of the check is what happens
    to one that is not.
    """
    monkeypatch.setattr(stage_schema, "STAGES", _registry_of(*fields))
    return [p for p in validate_registry() if "thing" in p]


def test_a_ref_filter_by_naming_a_field_that_does_not_exist_fails_validation(monkeypatch):
    """THE FAILURE THIS RULE EXISTS FOR.

    A cascade pointing at a field that is not there does not raise anywhere. The registry
    serialises it, the form reads an undefined value off the row and asks the resolver for an
    unfiltered list, and the product picker quietly stops being narrowed by the artisan — so a
    designer picks a neighbouring artisan's product into a report submitted under this one's
    name. Renaming the field the cascade points at is how it happens.
    """
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


# --------------------------------------------------------------------------------------
# What crosses the wire
# --------------------------------------------------------------------------------------


def test_every_ref_field_publishes_a_scope_to_the_clients():
    """Defaulted rather than omitted: a client that supplies its own default is a client that
    will eventually supply a different one from the server's."""
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


# --------------------------------------------------------------------------------------
# The declarations the requirement asked for
# --------------------------------------------------------------------------------------


def test_the_roster_picker_is_the_one_artisan_field_that_is_not_scoped():
    """Stage 3 is where the roster is BUILT, so narrowing it to the roster would be circular and
    the artisan who walks in on day two could never be added."""
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
    ],
)
def test_the_records_the_requirement_named_are_selectable(entity_key, field_key, model):
    spec = _field(entity_key, field_key)
    assert spec.type is FieldType.REF
    assert spec.ref_model == model


def test_both_product_pickers_cascade_from_the_artisan_on_their_own_row():
    for entity_key in ("existingProduct", "prototype"):
        spec = _field(entity_key, "productRef")
        assert spec.ref_filter_by == "artisanRef", entity_key


def test_the_text_a_reference_fills_in_is_kept_and_says_so():
    """The text field beside a REF is what the REPORT prints, and it must not be dropped in
    favour of resolving the id at render time: the artisan record can be corrected, merged or
    deleted in the years before somebody re-opens the file, and a participant table that renders
    a blank cell has lost the fact it exists to carry."""
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
        assert "linked record" in spec.help, f"{entity_key}.{field_key} does not say where it "\
                                             "comes from"


def test_the_core_chain_is_still_traversable():
    """Sketch -> Prototype -> Iteration/Validation -> FinalProduct -> CostSheet -> FollowUp.

    Re-asserted here as well as in test_stage_schema because this change reworked the REF fields
    of four stages, and the in-record chain is what the report joins on.
    """
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
    """Field order here is the order every client renders. A picker sitting under seven boxes a
    designer has already typed into is a picker that never gets used."""
    for stage_key, entity_key, ref_key, filled_key in (
        ("TRADITIONAL_PROCESS_BASELINE", "tool", "toolRef", "name"),
        ("EXISTING_PRODUCTS_BASELINE", "existingProduct", "productRef", "name"),
        ("WORKSHOP_PLAN_PARTICIPANTS_OPENING", "participant", "artisanRef", "name"),
    ):
        entity = stage(stage_key).entity(entity_key)
        keys = [f.key for f in entity.fields]
        assert keys.index(ref_key) < keys.index(filled_key), entity_key
