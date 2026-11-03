import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.stage_schema import STAGES, FieldType, derive_value, validate_entry


def _spec(entity_key: str, field_key: str):
    for stage in STAGES:
        for entity in stage.entities:
            if entity.key == entity_key:
                return entity, entity.field(field_key)
    raise AssertionError(f"{entity_key}.{field_key} is not in the registry")


def test_every_declared_derivation_names_fields_that_exist():
    for stage in STAGES:
        for entity in stage.entities:
            for field in entity.fields:
                if not field.derived_kind:
                    continue
                assert field.derived_from, f"{field.key} declares a kind but no inputs"
                for key in field.derived_from:
                    assert entity.field(key) is not None, (
                        f"{entity.key}.{field.key} derives from {key!r}, which is not a field of "
                        f"{entity.key}"
                    )


def test_the_workshop_duration_counts_both_end_days():
    _, spec = _spec("workshopSetup", "durationDays")
    assert derive_value(spec, {"startDate": "2026-01-12", "endDate": "2026-02-10"}) == 30
    assert derive_value(spec, {"startDate": "2026-01-12", "endDate": "2026-01-12"}) == 1


def test_an_uncomputable_duration_is_absent_rather_than_zero():
    _, spec = _spec("workshopSetup", "durationDays")
    for row in (
        {"startDate": "2026-01-12"},
        {"endDate": "2026-02-10"},
        {},
        {"startDate": "not a date", "endDate": "2026-02-10"},
        {"startDate": "2026-02-10", "endDate": "2026-01-12"},
    ):
        assert derive_value(spec, row) is None, row


def test_a_money_product_keeps_the_fixed_two_string():
    _, spec = _spec("materialUsage", "amount")
    assert spec.type is FieldType.MONEY
    assert derive_value(spec, {"quantity": 2.5, "rate": "180.00"}) == "450.00"
    assert derive_value(spec, {"quantity": "1,000", "rate": "1.25"}) == "1250.00"
    assert derive_value(spec, {"quantity": 2.5}) is None


def test_saving_a_blank_derivable_field_stores_the_computed_value():
    entity, _ = _spec("workshopSetup", "durationDays")
    cleaned, errors = validate_entry(entity, {
        "workshopTitle": "Design & Prototype Development Workshop",
        "schemeName": "NHDP",
        "craftName": "Sambalpuri Bandha",
        "clusterName": "Barpali",
        "state": "Odisha",
        "district": "Bargarh",
        "startDate": "2026-01-12",
        "endDate": "2026-02-10",
    }, enforce_required=False)

    assert not errors, errors
    assert cleaned["durationDays"] == 30


def test_a_value_the_designer_typed_is_never_overwritten_by_the_derivation():
    entity, _ = _spec("workshopSetup", "durationDays")
    cleaned, _errors = validate_entry(entity, {
        "workshopTitle": "W",
        "startDate": "2026-01-12",
        "endDate": "2026-02-10",
        "durationDays": 24,
    }, enforce_required=False)

    assert cleaned["durationDays"] == 24


def test_the_registry_tells_the_clients_how_to_compute():
    from app.services.stage_schema import registry_to_dict

    fields = [f for s in registry_to_dict()["stages"] for e in s["entities"] for f in e["fields"]
              if f.get("derivedKind")]
    assert fields, "no derivation reaches the clients, so nothing can compute live"
    for field in fields:
        assert field["derivedFrom"], field


def test_no_required_field_tells_the_designer_to_leave_it_blank():
    offenders = [
        f"{stage.key}.{entity.key}.{field.key}: {field.help!r}"
        for stage in STAGES
        for entity in stage.entities
        for field in entity.fields
        if field.required and not field.deprecated
        and "leave blank" in field.help.lower()
        and not field.derived_kind
    ]
    assert not offenders, (
        "these fields are required AND tell the designer not to fill them in, so the stage "
        "cannot be submitted by following its own help text:\n" + "\n".join(offenders)
    )


def test_a_field_whose_help_declares_a_derivation_declares_one():
    offenders = [
        f"{stage.key}.{entity.key}.{field.key}: {field.help!r}"
        for stage in STAGES
        for entity in stage.entities
        for field in entity.fields
        if not field.deprecated
        and field.help.lower().startswith(("derived", "computed", "calculated"))
        and not field.derived_kind
    ]
    assert not offenders, (
        "these fields say they are derived and nothing derives them:\n" + "\n".join(offenders)
    )


def test_a_cost_line_amount_is_quantity_times_rate():
    _, spec = _spec("costMaterialLine", "amount")
    assert derive_value(spec, {"quantity": "1.5", "rate": "1100"}) == "1650.00"
    assert derive_value(spec, {"quantity": "1.5"}) is None, "half a line has no amount"


def test_a_labour_line_amount_multiplies_all_three_factors():
    _, spec = _spec("costLabourLine", "amount")
    assert derive_value(spec, {"persons": 2, "days": "3.5", "rate": "450"}) == "3150.00"
    assert derive_value(spec, {"persons": 2, "days": "3.5"}) is None


def test_a_cost_sheet_total_adds_the_heads_that_were_filled_in():
    _, spec = _spec("costSheet", "totalCost")
    assert derive_value(spec, {
        "materialCost": "1650.00", "labourCost": "3150.00", "packagingCost": "120.00",
    }) == "4920.00"
    assert derive_value(spec, {"materialCost": "1650.00"}) == "1650.00"


def test_a_cost_sheet_with_no_costs_at_all_has_no_total():
    _, spec = _spec("costSheet", "totalCost")
    assert derive_value(spec, {"productRef": "p1", "expectedPrice": "6500.00"}) is None


def test_the_stage_that_could_not_be_submitted_can_now_be_submitted():
    entity, _ = _spec("costSheet", "totalCost")
    cleaned, errors = validate_entry(entity, {
        "productRef": "prod-1",
        "materialCost": "1650.00",
        "labourCost": "3150.00",
        "expectedPrice": "6500.00",
    }, enforce_required=True)

    assert errors == {}, errors
    assert cleaned["totalCost"] == "4800.00"
