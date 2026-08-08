"""Fields that compute themselves actually compute.

Every field pinned here carried a PROMISE in its own help text — "Leave blank to derive it from
the start and end dates", "Leave blank to compute it as quantity x rate" — and nothing anywhere
kept it. The value stayed empty, the save stored nothing, and the cover page of a submitted report
printed a blank where the form had said a number would appear. That is the same shape as the seven
stored-and-ignored report settings: a control that says it does something and does not.

The rule is declared ONCE, on the field, and read by three interpreters — this one, the web form
and the Android form — so the number a designer watches appear while typing is the number that
lands in the database.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.stage_schema import STAGES, FieldType, derive_value, validate_entry


def _spec(entity_key: str, field_key: str):
    for stage in STAGES:
        for entity in stage.entities:
            if entity.key == entity_key:
                return entity, entity.field(field_key)
    raise AssertionError(f"{entity_key}.{field_key} is not in the registry")


def test_every_declared_derivation_names_fields_that_exist():
    """A derivation reading a key that is not on the entity computes nothing, silently, forever."""
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
    """Inclusive, because the attendance register and the utilisation certificate are.

    12 January to 10 February is THIRTY days in every other document in the file. The exclusive
    reading gives 29 and disagrees with all of them.
    """
    _, spec = _spec("workshopSetup", "durationDays")
    assert derive_value(spec, {"startDate": "2026-01-12", "endDate": "2026-02-10"}) == 30
    assert derive_value(spec, {"startDate": "2026-01-12", "endDate": "2026-01-12"}) == 1


def test_an_uncomputable_duration_is_absent_rather_than_zero():
    """"0 days" on a cover page is a wrong fact; a blank is a missing one."""
    _, spec = _spec("workshopSetup", "durationDays")
    for row in (
        {"startDate": "2026-01-12"},
        {"endDate": "2026-02-10"},
        {},
        {"startDate": "not a date", "endDate": "2026-02-10"},
        # End before start: a typo, not a negative workshop.
        {"startDate": "2026-02-10", "endDate": "2026-01-12"},
    ):
        assert derive_value(spec, row) is None, row


def test_a_money_product_keeps_the_fixed_two_string():
    """MONEY is a string on the wire so 1250.10 does not come back as 1250.1."""
    _, spec = _spec("materialUsage", "amount")
    assert spec.type is FieldType.MONEY
    assert derive_value(spec, {"quantity": 2.5, "rate": "180.00"}) == "450.00"
    assert derive_value(spec, {"quantity": "1,000", "rate": "1.25"}) == "1250.00"
    assert derive_value(spec, {"quantity": 2.5}) is None


def test_saving_a_blank_derivable_field_stores_the_computed_value():
    """THE POINT OF THE WHOLE FEATURE. Blank plus derivable means computed, on the way in."""
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
    """The help says "LEAVE BLANK to derive it". A workshop that ran short of its sanctioned dates
    is recorded by typing the real figure, and a derivation that clobbered it would make that
    impossible to state."""
    entity, _ = _spec("workshopSetup", "durationDays")
    cleaned, _errors = validate_entry(entity, {
        "workshopTitle": "W",
        "startDate": "2026-01-12",
        "endDate": "2026-02-10",
        "durationDays": 24,
    }, enforce_required=False)

    assert cleaned["durationDays"] == 24


def test_the_registry_tells_the_clients_how_to_compute():
    """The web and Android forms compute these live, and they read the rule from here."""
    from app.services.stage_schema import registry_to_dict

    fields = [f for s in registry_to_dict()["stages"] for e in s["entities"] for f in e["fields"]
              if f.get("derivedKind")]
    assert fields, "no derivation reaches the clients, so nothing can compute live"
    for field in fields:
        assert field["derivedFrom"], field


def test_no_required_field_tells_the_designer_to_leave_it_blank():
    """THE AUDIT THAT WOULD HAVE CAUGHT STAGE 17, mechanically, across all 22 stages at once.

    "Leave blank to total the material lines below" sat on a field that is REQUIRED and BASIC.
    A designer who read it, entered the material and labour lines instead and pressed Submit got
    422 "Material cost is required" for the exact field the form had told them to leave empty, so
    the stage could never be completed by following its own instructions.

    The contradiction is total and needs no judgement about who computes what: a required field
    must hold a value by save time, so only a save-time derivation can satisfy one, and a
    required field with no derivation may never invite a designer to leave it empty.
    """
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
    """The other half of the same promise, in its declarative form: "Derived as quantity × rate"
    printed an empty Amount column on 28 material lines because the sentence was the whole
    implementation."""
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
    """Every Amount cell in the report's material-lines table was empty — 28 of them, across four
    products — because the field's help said "Derived as quantity × rate" and no derivation was
    declared. The kind already existed; only the declaration was missing."""
    _, spec = _spec("costMaterialLine", "amount")
    assert derive_value(spec, {"quantity": "1.5", "rate": "1100"}) == "1650.00"
    assert derive_value(spec, {"quantity": "1.5"}) is None, "half a line has no amount"


def test_a_labour_line_amount_multiplies_all_three_factors():
    _, spec = _spec("costLabourLine", "amount")
    assert derive_value(spec, {"persons": 2, "days": "3.5", "rate": "450"}) == "3150.00"
    assert derive_value(spec, {"persons": 2, "days": "3.5"}) is None


def test_a_cost_sheet_total_adds_the_heads_that_were_filled_in():
    """SUM, not PRODUCT, and the difference is which blanks it tolerates: four of the six heads
    are optional, so requiring all of them would leave `totalCost` empty for every workshop with
    no packaging or transport cost — which is most of them."""
    _, spec = _spec("costSheet", "totalCost")
    assert derive_value(spec, {
        "materialCost": "1650.00", "labourCost": "3150.00", "packagingCost": "120.00",
    }) == "4920.00"
    assert derive_value(spec, {"materialCost": "1650.00"}) == "1650.00"


def test_a_cost_sheet_with_no_costs_at_all_has_no_total():
    """"₹ 0.00" in a cost sheet a ministry reads is a claim, not a blank."""
    _, spec = _spec("costSheet", "totalCost")
    assert derive_value(spec, {"productRef": "p1", "expectedPrice": "6500.00"}) is None


def test_the_stage_that_could_not_be_submitted_can_now_be_submitted():
    """End to end on the entity itself: a cost sheet filled in as its help now describes passes
    the required-field gate, and the total it promises is computed rather than demanded."""
    entity, _ = _spec("costSheet", "totalCost")
    cleaned, errors = validate_entry(entity, {
        "productRef": "prod-1",
        "materialCost": "1650.00",
        "labourCost": "3150.00",
        "expectedPrice": "6500.00",
    }, enforce_required=True)

    assert errors == {}, errors
    assert cleaned["totalCost"] == "4800.00"
