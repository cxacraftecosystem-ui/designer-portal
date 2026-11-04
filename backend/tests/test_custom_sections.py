import json

import pytest

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.custom_sections import (
    CUSTOM_ENTITY_KEY,
    V1_FIELD_TYPES,
    CustomDefinition,
    CustomFieldSpec,
    CustomOption,
    CustomSectionEditError,
    CustomSectionSpec,
    _keys_this_put_keeps,
    answered_keys,
    custom_schema_version,
    plan_custom_write,
    plan_definition,
    to_field_spec,
    validate_custom_entry,
    validate_definition,
)
from app.services.report_custom_sections import (
    NOT_RECORDED,
    RETIRED_NOTE,
    CustomReportField,
    CustomSectionItem,
    custom_section_blocks_standalone,
    display_value,
)
from app.services.report_model import HeadingBlock, KeyValueBlock
from app.services.report_templates import (
    SpecialSection,
    TemplateSection,
    apply_report_settings,
    template as get_template,
)
from app.services.stage_schema import (
    Cardinality,
    FieldType,
    Tier,
    coerce_value,
    registry_to_dict,
    stage,
    stage_completeness,
    stages,
)

STAGE = "WORKSHOP_SETUP"
STAGE_WITHOUT_SINGLETON = "SKETCH_DEVELOPMENT"


def _field(key="looms", label="How many looms?", **kw) -> CustomFieldSpec:
    kw.setdefault("type", FieldType.INT)
    return CustomFieldSpec(key=key, label=label, **kw)


def _section(*fields: CustomFieldSpec, key="extra", stage_key=STAGE, **kw) -> CustomSectionSpec:
    return CustomSectionSpec(
        key=key, title=kw.pop("title", "Loom shed"), stage_key=stage_key,
        fields=tuple(fields), **kw
    )



def test_the_reserved_key_cannot_be_a_registry_entity_or_a_designers_own():
    assert CUSTOM_ENTITY_KEY.startswith("_")
    assert all(
        e.key != CUSTOM_ENTITY_KEY for s in stages() for e in s.entities
    )
    problems = validate_definition([_section(_field(key=CUSTOM_ENTITY_KEY))])
    assert any(CUSTOM_ENTITY_KEY in p for p in problems)


def test_the_collection_sweep_can_never_reach_the_custom_row():
    for spec in stages():
        collection_keys = {
            e.key for e in spec.entities if e.cardinality is Cardinality.COLLECTION
        }
        assert CUSTOM_ENTITY_KEY not in collection_keys


def test_the_reserved_container_cannot_be_named_as_an_emptied_collection():
    from app.schemas.design_workshops import StageSaveIn

    with pytest.raises(ValueError, match="underscore"):
        StageSaveIn(emptiedEntities=[CUSTOM_ENTITY_KEY])
    assert StageSaveIn(emptiedEntities=["costSheet"]).emptiedEntities == ["costSheet"]


def test_promoted_columns_cannot_be_written_from_a_custom_field():
    from app.services.stage_schema import PROMOTED_COLUMNS, promoted_values

    assert all(not path.startswith(CUSTOM_ENTITY_KEY) for path in PROMOTED_COLUMNS)
    assert promoted_values(CUSTOM_ENTITY_KEY, {"craftName": "Ikat", "title": "x"}) == {}


def test_every_stage_can_carry_a_custom_section_including_the_eight_with_no_singleton():
    assert stage(STAGE_WITHOUT_SINGLETON).singleton is None
    assert validate_definition([_section(_field(), stage_key=STAGE_WITHOUT_SINGLETON)]) == []



def test_the_registry_dump_says_nothing_about_custom_sections():
    dump = registry_to_dict()
    assert set(dump) == {"version", "enums", "stages"}
    assert "_custom" not in json.dumps(dump)
    assert "customSchemaVersion" not in json.dumps(dump)


def test_the_digest_moves_when_a_question_is_reworded():
    before = custom_schema_version([_section(_field())])
    after = custom_schema_version([_section(_field(label="How many weavers?"))])
    assert before != after


def test_the_digest_moves_when_an_option_is_added_and_when_one_is_relabelled():
    two = _field(key="dye", type=FieldType.ENUM, options=(
        CustomOption("NATURAL", "Natural"), CustomOption("CHEMICAL", "Chemical"),
    ))
    three = _field(key="dye", type=FieldType.ENUM, options=(
        CustomOption("NATURAL", "Natural"), CustomOption("CHEMICAL", "Chemical"),
        CustomOption("MIXED", "Mixed"),
    ))
    relabelled = _field(key="dye", type=FieldType.ENUM, options=(
        CustomOption("NATURAL", "Natural, undyed"), CustomOption("CHEMICAL", "Chemical"),
    ))
    versions = {
        custom_schema_version([_section(two)]),
        custom_schema_version([_section(three)]),
        custom_schema_version([_section(relabelled)]),
    }
    assert len(versions) == 3


def test_the_digest_moves_when_a_section_is_retitled_or_reordered():
    titled = _section(_field())
    retitled = _section(_field(), title="Weaver shed")
    reordered = _section(_field(), sort_order=3)
    versions = {
        custom_schema_version([titled]),
        custom_schema_version([retitled]),
        custom_schema_version([reordered]),
    }
    assert len(versions) == 3


def test_a_workshop_with_no_definition_has_an_empty_version_rather_than_a_digest_of_nothing():
    assert custom_schema_version([]) == ""
    assert custom_schema_version([_section()]) == ""


def test_help_text_is_the_one_visible_string_outside_the_digest():
    assert custom_schema_version([_section(_field())]) == custom_schema_version(
        [_section(_field(help="Count the pit looms only."))]
    )



def test_a_sound_definition_has_no_problems():
    assert validate_definition([_section(_field(), _field(key="shed", label="Shed condition",
                                                          type=FieldType.TEXT))]) == []


def test_a_required_field_must_be_basic_tier():
    problems = validate_definition(
        [_section(_field(required=True, tier=Tier.STANDARD))]
    )
    assert any("Only a Basic field may be required" in p or "only a BASIC" in p.lower()
               for p in problems)
    assert validate_definition([_section(_field(required=True, tier=Tier.BASIC))]) == []


def test_a_key_that_collides_with_a_core_field_of_that_stage_is_refused_by_name():
    problems = validate_definition([_section(_field(key="craftName", label="Loom count"))])
    assert any("craftName" in p and "Craft" in p for p in problems)


def test_a_label_that_collides_with_a_singleton_label_of_that_stage_is_refused():
    problems = validate_definition([_section(_field(key="loomCraft", label="Craft"))])
    assert any("Craft" in p and "readiness" in p for p in problems)


def test_two_custom_fields_on_one_stage_may_not_share_a_label():
    problems = validate_definition([
        _section(_field(key="a", label="Loom count"), key="one"),
        _section(_field(key="b", label="Loom count"), key="two"),
    ])
    assert any("Loom count" in p for p in problems)


def test_the_same_label_on_two_different_stages_is_fine():
    assert validate_definition([
        _section(_field(key="a", label="Notes"), key="one", stage_key=STAGE),
        _section(_field(key="b", label="Notes"), key="two",
                 stage_key=STAGE_WITHOUT_SINGLETON),
    ]) == []


def test_two_fields_may_not_share_a_key_even_in_different_sections():
    problems = validate_definition([
        _section(_field(key="looms"), key="one"),
        _section(_field(key="looms", label="Loom count"), key="two"),
    ])
    assert any("looms" in p for p in problems)


def test_a_section_must_name_a_real_stage():
    assert any("FOO" in p for p in validate_definition([_section(_field(), stage_key="FOO")]))
    assert any(
        "stages" in p for p in validate_definition([_section(_field(), stage_key="")])
    )


@pytest.mark.parametrize(
    "excluded",
    [FieldType.IMAGE, FieldType.IMAGE_LIST, FieldType.FILE, FieldType.AUDIO, FieldType.VIDEO,
     FieldType.RICH_TEXT, FieldType.REF, FieldType.GEO],
)
def test_media_rich_text_references_and_coordinates_are_refused_in_v1(excluded):
    assert excluded not in V1_FIELD_TYPES
    problems = validate_definition([_section(_field(type=excluded))])
    assert any(excluded.value in p for p in problems)


def test_v1_is_exactly_the_twelve_types_the_plan_names():
    assert {t.value for t in V1_FIELD_TYPES} == {
        "TEXT", "LONG_TEXT", "INT", "DECIMAL", "MONEY", "PERCENT", "DATE", "TIME", "BOOL",
        "ENUM", "MULTI_ENUM", "TAGS",
    }


def test_a_choice_needs_at_least_two_options_and_a_non_choice_needs_none():
    one = _field(key="dye", type=FieldType.ENUM, options=(CustomOption("NATURAL"),))
    assert any("at least two" in p for p in validate_definition([_section(one)]))
    stray = _field(key="dye", type=FieldType.TEXT, options=(CustomOption("A"), CustomOption("B")))
    assert any("cannot carry options" in p for p in validate_definition([_section(stray)]))


def test_a_bound_that_would_never_be_checked_is_refused_rather_than_stored():
    problems = validate_definition(
        [_section(_field(key="notes", label="Notes", type=FieldType.TEXT, min_value=1))]
    )
    assert any("never be checked" in p for p in problems)


def test_every_problem_is_a_sentence_and_not_a_code():
    problems = validate_definition([
        _section(_field(key="craftName", required=True, tier=Tier.ADVANCED))
    ])
    assert problems
    for message in problems:
        assert message[0].isupper() or message.startswith(("'", "`"))
        assert message.rstrip().endswith((".", "?"))
        assert " " in message



def test_an_unknown_custom_key_is_dropped_and_reported_and_never_a_refusal():
    result = validate_custom_entry([_field()], {"looms": 12, "ghost": "x"})
    assert result.clean == {"looms": 12}
    assert result.dropped == ("ghost",)
    assert result.errors == {}


def test_the_protocol_keys_are_not_reported_as_dropped():
    result = validate_custom_entry([_field()], {"looms": 1, "_clientKey": "abc", "_ordinal": 0})
    assert result.dropped == ()


def test_a_custom_money_is_coerced_by_exactly_the_code_a_core_money_is():
    custom = _field(key="price", label="Price", type=FieldType.MONEY)
    core = to_field_spec(custom)
    for raw in ("1250.1", "1,250.10", 1250.1, "  1250.10 "):
        assert validate_custom_entry([custom], {"price": raw}).clean["price"] == (
            coerce_value(core, raw)[0]
        )


def test_a_bad_value_is_a_per_field_message_and_the_other_fields_still_save():
    fields = [_field(), _field(key="shed", label="Shed", type=FieldType.TEXT)]
    result = validate_custom_entry(fields, {"looms": "twelve", "shed": "Tiled"})
    assert set(result.errors) == {"looms"}
    assert "How many looms?" in result.errors["looms"]
    assert result.clean == {"shed": "Tiled"}


def test_an_option_a_designers_own_list_does_not_carry_is_named():
    field = _field(key="dye", label="Dye", type=FieldType.ENUM, options=(
        CustomOption("NATURAL", "Natural"), CustomOption("CHEMICAL", "Chemical"),
    ))
    result = validate_custom_entry([field], {"dye": "MIXED"})
    assert "MIXED" in result.errors["dye"]
    assert validate_custom_entry([field], {"dye": "NATURAL"}).clean == {"dye": "NATURAL"}


def test_required_is_enforced_only_under_submit():
    field = _field(required=True, tier=Tier.BASIC)
    assert validate_custom_entry([field], {}, enforce_required=False).errors == {}
    assert "required" in validate_custom_entry([field], {}, enforce_required=True).errors["looms"]


def test_a_retired_fields_stored_answer_round_trips_instead_of_being_dropped():
    retired = _field(key="oldLooms", label="How many looms?", retired=True)
    result = validate_custom_entry([retired], {"oldLooms": 12})
    assert result.clean == {"oldLooms": 12}
    assert result.dropped == ()


def test_a_retired_required_field_never_blocks_a_submission():
    retired = _field(required=True, tier=Tier.BASIC, retired=True)
    assert validate_custom_entry([retired], {}, enforce_required=True).errors == {}



def test_a_client_one_release_behind_writes_no_custom_row_at_all():
    write = plan_custom_write([_field()], sent=None, previous={"looms": 12})
    assert write.data is None
    assert write.errors == {}
    assert write.dropped == ()


def test_an_empty_container_is_a_designer_clearing_every_answer_and_is_written():
    write = plan_custom_write([_field()], sent={}, previous={"looms": 12})
    assert write.data == {}


def test_a_never_read_client_merges_the_custom_row_instead_of_replacing_it():
    fields = [_field(), _field(key="shed", label="Shed", type=FieldType.TEXT)]
    write = plan_custom_write(
        fields, sent={"shed": "Tiled"}, previous={"looms": 12}, merge=True
    )
    assert write.data == {"looms": 12, "shed": "Tiled"}


def test_a_client_that_has_read_the_row_still_deletes_by_omission():
    write = plan_custom_write([_field()], sent={}, previous={"looms": 12}, merge=False)
    assert write.data == {}


def test_a_rejected_value_does_not_destroy_the_answer_already_stored_under_it():
    price = _field(key="price", label="Price", type=FieldType.MONEY)
    write = plan_custom_write([price], sent={"price": "65OO"}, previous={"price": "6500.00"})
    assert write.data == {"price": "6500.00"}
    assert "price" in write.errors


def test_the_submit_gate_reads_the_row_as_it_will_stand_and_not_the_payload():
    field = _field(required=True, tier=Tier.BASIC)
    assert "looms" in plan_custom_write([field], sent=None, previous={}, submit=True).errors
    assert plan_custom_write([field], sent=None, previous={"looms": 12}, submit=True).errors == {}
    assert plan_custom_write([field], sent={"looms": 12}, previous={}, submit=True).errors == {}
    assert plan_custom_write([field], sent={}, previous={}, submit=False).errors == {}


def test_the_coercion_message_wins_over_the_required_one():
    field = _field(required=True, tier=Tier.BASIC)
    write = plan_custom_write([field], sent={"looms": "twelve"}, previous={}, submit=True)
    assert "valid" in write.errors["looms"]


def test_a_retired_sections_answers_survive_the_next_ordinary_save():
    definition = CustomDefinition(sections=(
        CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, retired=True,
                          fields=(_field(id="f1"),)),
    ))
    specs = definition.fields_for(STAGE)
    assert [f.key for f in specs] == ["looms"]
    assert all(f.retired for f in specs)
    write = plan_custom_write(specs, sent={"looms": 12}, previous={"looms": 12})
    assert write.data == {"looms": 12}
    assert write.dropped == ()
    write = plan_custom_write(specs, sent={}, previous={"looms": 12})
    assert write.data == {"looms": 12}
    assert write.dropped == ()
    superseded = CustomDefinition(sections=(CustomSectionSpec(
        key="extra", title="Loom shed", stage_key=STAGE, id="sec1",
        fields=(
            _field(id="f1", retired=True, superseded_by="f2"),
            _field(id="f2", key="loomsR2", label="How many weavers?"),
        ),
    ),))
    write = plan_custom_write(
        superseded.fields_for(STAGE), sent={"loomsR2": 14}, previous={"looms": 12}
    )
    assert write.data == {"looms": 12, "loomsR2": 14}
    assert write.dropped == ()
    assert write.errors == {}
    live_only = plan_custom_write([_field()], sent={}, previous={"looms": 12})
    assert live_only.data == {}
    required = CustomSectionSpec(
        key="extra", title="Loom shed", stage_key=STAGE, retired=True,
        fields=(_field(id="f1", required=True, tier=Tier.BASIC),),
    )
    scored = _score(CustomDefinition(sections=(required,)).fields_for(STAGE), {})
    assert scored.required_total == stage_completeness(stage(STAGE), {}, {}).required_total


def test_a_definition_edit_that_narrows_a_bound_cannot_make_a_stage_unsubmittable():
    narrowed = _field(max_value=100, required=True, tier=Tier.BASIC)
    write = plan_custom_write([narrowed], sent=None, previous={"looms": 500}, submit=True)
    assert write.errors == {}
    assert "How many looms?" not in _score([narrowed], {"looms": 500}).missing
    dye = _field(key="dye", label="Dye", type=FieldType.ENUM,
                 options=(CustomOption("NATURAL", "Natural"), CustomOption("MIXED", "Mixed")))
    assert plan_custom_write(
        [dye], sent=None, previous={"dye": "CHEMICAL"}, submit=True
    ).errors == {}


def test_the_write_path_never_reports_a_custom_key_as_registry_drift():
    write = plan_custom_write([_field()], sent={"looms": 1, "ghost": "x"}, previous={})
    assert write.dropped == ("ghost",)
    assert write.data == {"looms": 1}



def _score(fields, values, stage_key=STAGE):
    return stage_completeness(
        stage(stage_key), {}, {}, custom_fields=fields, custom_values=values
    )


def test_a_required_custom_field_counts_towards_the_stage_percentage():
    field = _field(required=True, tier=Tier.BASIC)
    base = stage_completeness(stage(STAGE), {}, {})
    scored = _score([field], {})
    assert scored.required_total == base.required_total + 1
    assert "How many looms?" in scored.missing


def test_the_container_is_never_tested_as_a_whole():
    fields = [_field(required=True, tier=Tier.BASIC),
              _field(key="shed", label="Shed", required=True, tier=Tier.BASIC,
                     type=FieldType.TEXT)]
    blanks = {"looms": None, "shed": "   "}
    assert bool(blanks) is True
    scored = _score(fields, blanks)
    assert scored.required_filled == stage_completeness(stage(STAGE), {}, {}).required_filled
    assert scored.missing[-2:] == ("How many looms?", "Shed")


def test_a_retired_field_is_skipped_exactly_as_a_deprecated_registry_field_is():
    retired = _field(required=True, tier=Tier.BASIC, retired=True)
    assert _score([retired], {}).required_total == stage_completeness(
        stage(STAGE), {}, {}
    ).required_total


def test_a_custom_field_is_filed_under_its_bare_label_like_a_singleton_field():
    scored = _score([_field(required=True, tier=Tier.BASIC)], {})
    assert "How many looms?" in scored.missing
    assert not any(m.endswith(": How many looms?") for m in scored.missing)


def test_the_scorer_and_the_submit_gate_agree_on_every_input():
    fields = [
        _field(required=True, tier=Tier.BASIC),
        _field(key="shed", label="Shed", required=True, tier=Tier.BASIC, type=FieldType.TEXT),
        _field(key="dye", label="Dye", type=FieldType.TAGS),
    ]
    for values in (
        {},
        {"looms": 12},
        {"looms": 12, "shed": "Tiled"},
        {"looms": 0, "shed": ""},
        {"dye": ["indigo"]},
        {"looms": None, "shed": None, "dye": []},
    ):
        blocked = set(validate_custom_entry(fields, values, enforce_required=True).errors)
        missing_labels = set(_score(fields, values).missing)
        assert {f.label for f in fields if f.key in blocked} == (
            missing_labels & {f.label for f in fields}
        ), values
        written = plan_custom_write(fields, sent=values, previous={}, submit=True)
        scored = _score(fields, written.data or {})
        assert {f.label for f in fields if f.key in written.errors} == (
            set(scored.missing) & {f.label for f in fields}
        ), values



def _stored(*fields: CustomFieldSpec, key="extra", section_id="sec1") -> CustomSectionSpec:
    return CustomSectionSpec(
        key=key, title="Loom shed", stage_key=STAGE, id=section_id, fields=tuple(fields)
    )


def _plans(stored, incoming, answered):
    plan = plan_definition(stored, incoming, answered)
    return {(s.action, f.action, f.key) for s in plan.sections for f in s.fields} | {
        (s.action, "", s.spec.key if s.spec else "") for s in plan.sections
    }


def test_an_unanswered_field_is_edited_in_place_and_deleted_outright():
    stored = [_stored(_field(id="f1"))]
    edited = [_stored(_field(id="f1", label="How many weavers?"))]
    assert ("EDIT", "EDIT", "looms") in _plans(stored, edited, {})
    assert ("EDIT", "DELETE", "looms") in _plans(stored, [_stored()], {})


def test_rewriting_the_label_of_an_answered_field_supersedes_it():
    stored = [_stored(_field(id="f1"))]
    incoming = [_stored(_field(id="f1", label="How many weavers?"))]
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    (field_plan,) = plan.sections[0].fields
    assert field_plan.action == "SUPERSEDE"
    assert field_plan.supersedes_id == "f1"
    assert field_plan.spec.key != "looms"
    assert field_plan.spec.label == "How many weavers?"
    assert plan.superseded == 1
    assert plan.sections[0].bumps_revision is True


def test_everything_except_the_label_may_change_on_an_answered_field():
    stored = [_stored(_field(id="f1"))]
    incoming = [_stored(_field(
        id="f1", help="Pit looms only.", required=True, tier=Tier.BASIC, unit="looms",
        min_value=0, max_value=500, sort_order=3,
    ))]
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    (field_plan,) = plan.sections[0].fields
    assert field_plan.action == "EDIT"


def test_deleting_an_answered_field_retires_it_and_can_never_delete_it():
    plan = plan_definition([_stored(_field(id="f1"))], [_stored()], {STAGE: {"looms"}})
    (field_plan,) = plan.sections[0].fields
    assert field_plan.action == "RETIRE"
    assert all(
        f.action != "DELETE" for s in plan.sections for f in s.fields
    )


def test_a_section_whose_fields_were_answered_is_retired_rather_than_removed():
    plan = plan_definition([_stored(_field(id="f1"))], [], {STAGE: {"looms"}})
    assert [s.action for s in plan.sections] == ["RETIRE"]
    assert plan.retired >= 1


def test_a_section_nobody_answered_is_removed_outright():
    plan = plan_definition([_stored(_field(id="f1"))], [], {})
    assert [s.action for s in plan.sections] == ["DELETE"]


def test_a_stale_client_re_sending_the_old_key_does_not_supersede_the_question_twice():
    stored = [_stored(
        _field(id="f1", retired=True, superseded_by="f2"),
        _field(id="f2", key="loomsR2", label="How many weavers?"),
    )]
    incoming = [_stored(_field(id="", label="How many weavers?"))]
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    actions = [f.action for f in plan.sections[0].fields]
    assert "SUPERSEDE" not in actions
    assert plan.superseded == 0


def test_a_key_retired_by_deletion_cannot_be_claimed_again():
    stored = [_stored(_field(id="f1", retired=True))]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, [_stored(_field(id="", label="Something else"))],
                        {STAGE: {"looms"}})
    assert "looms" in str(exc.value)


def test_an_answered_section_cannot_be_moved_to_another_stage():
    stored = [_stored(_field(id="f1"))]
    moved = [CustomSectionSpec(key="extra", title="Loom shed", stage_key="DESIGN_BRIEF",
                               id="sec1", fields=(_field(id="f1"),))]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, moved, {STAGE: {"looms"}})
    assert "stage" in str(exc.value)
    assert plan_definition(stored, moved, {}).sections[0].action == "EDIT"


def test_a_key_that_holds_an_answer_cannot_be_claimed_by_a_new_section():
    stored = [_stored(_field(id="f1"), key="extra")]
    fresh = [CustomSectionSpec(
        key="survey", title="Shed survey", stage_key=STAGE,
        fields=(_field(key="looms", label="How many weavers?"),),
    )]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, fresh, {STAGE: {"looms"}})
    assert "looms" in str(exc.value)
    assert plan_definition(stored, fresh, {}).sections


def test_a_key_retired_as_collateral_cannot_be_claimed_by_a_new_section_either():
    stored = [CustomSectionSpec(
        key="extra", title="Loom shed", stage_key=STAGE, id="sec1", retired=True,
        fields=(
            _field(key="secretary", label="Secretary", type=FieldType.TEXT, id="f1", retired=True),
            _field(id="f2", retired=True),
        ),
    )]
    fresh = [CustomSectionSpec(
        key="survey", title="Shed survey", stage_key=STAGE,
        fields=(_field(label="How many looms are in the shed?", type=FieldType.TEXT),),
    )]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, fresh, {STAGE: {"secretary"}})
    assert "looms" in str(exc.value)
    with pytest.raises(CustomSectionEditError):
        plan_definition(stored, fresh, {})
    other = [CustomSectionSpec(
        key="survey", title="Shed survey", stage_key="DESIGN_BRIEF", fields=(_field(),)
    )]
    assert plan_definition(stored, other, {STAGE: {"secretary"}}).sections


def test_a_key_the_same_put_deletes_outright_is_still_free_for_the_taking():
    stored = [_stored(
        _field(id="f1"), _field(key="shed", label="Shed", type=FieldType.TEXT, id="f2")
    )]
    moved = [
        _stored(_field(key="shed", label="Shed", type=FieldType.TEXT, id="f2")),
        CustomSectionSpec(key="survey", title="Shed survey", stage_key=STAGE, fields=(_field(),)),
    ]
    assert ("CREATE", "CREATE", "looms") in _plans(stored, moved, {})
    renamed = [CustomSectionSpec(key="shedSurvey", title="Loom shed", stage_key=STAGE,
                                 fields=(_field(),))]
    assert ("CREATE", "CREATE", "looms") in _plans([_stored(_field(id="f1"))], renamed, {})
    with pytest.raises(CustomSectionEditError):
        _plans(stored, moved, {STAGE: {"looms"}})
    with pytest.raises(CustomSectionEditError):
        _plans([_stored(_field(id="f1"))], renamed, {STAGE: {"looms"}})


def test_a_minted_key_the_supersede_walk_keeps_cannot_be_claimed_either():
    stored = [_stored(
        _field(id="f1", retired=True, superseded_by="f2"),
        _field(id="f2", key="loomsR2", label="How many weavers work here?"),
    )]
    put = [
        _stored(_field(id="", label="How many weavers work here?")),
        CustomSectionSpec(key="baths", title="Dye baths", stage_key=STAGE, fields=(
            _field(key="loomsR2", label="Which bath was used?", type=FieldType.TEXT),
        )),
    ]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, put, {STAGE: {"looms"}})
    assert "loomsR2" in str(exc.value)
    assert "loomsR2" in _keys_this_put_keeps(stored, put, {STAGE: {"looms"}})[STAGE]
    assert plan_definition(stored, put[:1], {STAGE: {"looms"}}).superseded == 0
    assert ("EDIT", "EDIT", "loomsR2") in _plans(stored, put[:1], {STAGE: {"looms"}})


def test_two_specs_can_never_reach_the_answer_path_under_one_container_key():
    definition = CustomDefinition(sections=(
        CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, id="sec1", retired=True,
                          fields=(_field(id="f2", retired=True),)),
        CustomSectionSpec(key="survey", title="Shed survey", stage_key=STAGE, id="sec2",
                          fields=(_field(id="f3", label="How many looms are in the shed?",
                                         type=FieldType.TEXT),)),
    ))
    specs = definition.fields_for(STAGE)
    assert [f.key for f in specs] == ["looms"]
    assert [f.label for f in specs] == ["How many looms are in the shed?"]
    assert [f.retired for f in specs] == [False]
    write = plan_custom_write(specs, sent={"looms": "about nine"}, previous={"looms": 12})
    assert write.errors == {}
    assert write.data == {"looms": "about nine"}
    assert write.dropped == ()


def test_re_adding_a_section_that_was_retired_asks_it_again_rather_than_doing_nothing():
    stored = [CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, id="sec1",
                               retired=True, fields=(_field(id="f1", retired=True),))]
    incoming = [CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE,
                                  fields=(_field(key="shed", label="Shed condition",
                                                 type=FieldType.TEXT),))]
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    (section_plan,) = plan.sections
    assert section_plan.action == "EDIT"
    assert section_plan.spec is not None and section_plan.spec.retired is False
    assert [(f.action, f.key) for f in section_plan.fields] == [("CREATE", "shed")]


def test_answered_is_the_completeness_scorers_own_question():
    fields = [_field(), _field(key="shed", label="Shed", type=FieldType.TEXT)]
    assert answered_keys(fields, {"looms": 0, "shed": "   "}) == {"looms"}
    assert answered_keys(fields, {"shed": "Tiled"}) == {"shed"}



def test_a_workshop_with_no_custom_sections_gets_the_identical_template_object():
    base = get_template("DETAILED_TECHNICAL")
    assert apply_report_settings(base, None, custom_sections=()) is base
    assert apply_report_settings(base, None) is base


def test_a_custom_section_prints_immediately_after_the_stage_it_was_asked_at():
    base = get_template("DETAILED_TECHNICAL")
    shaped = apply_report_settings(base, None, custom_sections=[_section(_field())])
    keys = [s.stage_key or (s.special.value if s.special else "") for s in shaped.sections]
    assert keys.index("CUSTOM_SECTION") == keys.index(STAGE) + 1
    assert len(shaped.sections) == len(base.sections) + 1


def test_two_sections_on_one_stage_print_in_the_designers_own_order():
    base = get_template("DETAILED_TECHNICAL")
    first = CustomSectionSpec(key="aFirst", title="First", stage_key=STAGE, sort_order=0)
    second = CustomSectionSpec(key="bSecond", title="Second", stage_key=STAGE, sort_order=1)
    shaped = apply_report_settings(base, None, custom_sections=[second, first])
    spliced = [s.custom_key for s in shaped.sections
               if s.special is SpecialSection.CUSTOM_SECTION]
    assert spliced == ["aFirst", "bSecond"]
    keys = [s.stage_key or "" for s in shaped.sections]
    assert shaped.sections[keys.index(STAGE) + 1].custom_key == "aFirst"


def test_a_section_whose_stage_the_template_never_prints_becomes_its_own_annexure():
    base = get_template("PHOTO_CATALOGUE")
    shaped = apply_report_settings(base, None, custom_sections=[_section(_field())])
    spliced = [s for s in shaped.sections if s.special is SpecialSection.CUSTOM_SECTION]
    assert len(spliced) == 1
    assert spliced[0].page_break_before is True
    assert shaped.sections.index(spliced[0]) == len(shaped.sections) - 1


def test_a_section_on_an_excluded_stage_is_not_orphaned_mid_narrative():
    base = get_template("DETAILED_TECHNICAL")
    shaped = apply_report_settings(
        base, {"excludedStages": [STAGE]}, custom_sections=[_section(_field())]
    )
    assert not any(s.stage_key == STAGE for s in shaped.sections)
    spliced = [s for s in shaped.sections if s.special is SpecialSection.CUSTOM_SECTION]
    assert len(spliced) == 1
    completeness_at = next(
        i for i, s in enumerate(shaped.sections) if s.special is SpecialSection.COMPLETENESS
    )
    assert shaped.sections.index(spliced[0]) < completeness_at


def test_a_custom_section_template_entry_must_name_exactly_one_section():
    with pytest.raises(ValueError, match="custom_key"):
        TemplateSection(special=SpecialSection.CUSTOM_SECTION)
    with pytest.raises(ValueError, match="custom_key"):
        TemplateSection(special=SpecialSection.COMPLETENESS, custom_key="extra")



def _item(**kw) -> CustomSectionItem:
    kw.setdefault("key", "extra")
    kw.setdefault("title", "Loom shed")
    kw.setdefault("stage_key", STAGE)
    return CustomSectionItem(**kw)


def test_an_answered_custom_field_reaches_the_document_under_its_own_label():
    blocks = custom_section_blocks_standalone(_item(
        fields=(CustomReportField(key="looms", label="How many looms?", type="INT"),),
        values={"looms": 12},
    ))
    assert any(isinstance(b, HeadingBlock) for b in blocks)
    grid = next(b for b in blocks if isinstance(b, KeyValueBlock))
    assert grid.pairs[0][0] == "How many looms?"
    assert "12" in grid.pairs[0][1][0].text


def test_an_unanswered_required_field_prints_the_gap_and_an_optional_one_prints_nothing():
    blocks = custom_section_blocks_standalone(_item(
        fields=(
            CustomReportField(key="looms", label="How many looms?", type="INT", required=True),
            CustomReportField(key="shed", label="Shed", type="TEXT"),
        ),
        values={},
    ))
    grid = next(b for b in blocks if isinstance(b, KeyValueBlock))
    assert [label for label, _ in grid.pairs] == ["How many looms?"]
    assert NOT_RECORDED in grid.pairs[0][1][0].text


def test_an_answer_given_under_a_superseded_wording_prints_with_the_wording_it_was_given():
    blocks = custom_section_blocks_standalone(_item(
        fields=(
            CustomReportField(key="looms", label="How many looms?", type="INT", retired=True),
            CustomReportField(key="loomsR2", label="How many weavers?", type="INT"),
        ),
        values={"looms": 12, "loomsR2": 4},
    ))
    grid = next(b for b in blocks if isinstance(b, KeyValueBlock))
    labels = [label for label, _ in grid.pairs]
    assert f"How many looms? ({RETIRED_NOTE})" in labels
    assert "How many weavers?" in labels


def test_a_section_nobody_has_reached_prints_nothing_at_all():
    assert custom_section_blocks_standalone(_item(
        fields=(CustomReportField(key="shed", label="Shed", type="TEXT"),), values={}
    )) == ()


def test_a_choice_prints_the_designers_own_label_and_never_the_token():
    field = CustomReportField(
        key="dye", label="Dye", type="ENUM",
        options=(("NATURAL", "Natural dye"), ("CHEMICAL", "Chemical dye")),
    )
    assert display_value(field, "NATURAL") == "Natural dye"
    assert display_value(field, "UNKNOWN_TOKEN") == "UNKNOWN_TOKEN"


def test_a_designers_question_survives_a_round_trip_and_reaches_a_built_report():
    from app.services.report_builder import WorkshopData, build_report
    from app.services.report_custom_sections import attach_custom_sections
    from app.services.report_model import ReportMeta

    definition = [_field(required=True, tier=Tier.BASIC), _field(
        key="dye", label="Dye used", type=FieldType.ENUM,
        options=(CustomOption("NATURAL", "Natural dye"), CustomOption("CHEMICAL", "Chemical dye")),
    )]
    written = plan_custom_write(
        definition, sent={"looms": "12", "dye": "NATURAL"}, previous={}, submit=True
    )
    assert written.errors == {}

    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    attach_custom_sections(data, [_item(
        fields=tuple(
            CustomReportField(key=f.key, label=f.label, type=f.type.value,
                              options=tuple((o.value, o.display) for o in f.options),
                              required=f.required)
            for f in definition
        ),
        values=written.data,
    )])

    base = get_template("DETAILED_TECHNICAL")
    shaped = apply_report_settings(base, None, custom_sections=[_section(*definition)])
    document, _warnings = build_report(
        data, "DETAILED_TECHNICAL", lambda _id: None,
        meta=ReportMeta(title="Ikat workshop"), template=shaped,
    )

    text = json.dumps([
        [run.text for run in getattr(block, "runs", ())]
        + [
            run.text
            for pair in getattr(block, "pairs", ())
            for run in pair[1]
        ]
        + [pair[0] for pair in getattr(block, "pairs", ())]
        for block in document.blocks
    ])
    assert "How many looms?" in text
    assert "12" in text
    assert "Natural dye" in text
    assert "NATURAL\"" not in text


class _Row:
    def __init__(self, stage_key, entity_key, data):
        self.id, self.stageKey, self.entityKey, self.data = "r1", stage_key, entity_key, data
        self.deletedAt = None


async def test_the_annexure_and_the_readiness_screen_count_one_stage_the_same_way(monkeypatch):
    from app.services import custom_sections as service
    from app.services.design_workshops import attach_report_custom_sections, workshop_completeness
    from app.services.report_builder import WorkshopData
    from app.services.report_custom_sections import custom_scoring

    definition = CustomDefinition(sections=(
        CustomSectionSpec(
            key="extra", title="Loom shed", stage_key=STAGE, id="sec1", retired=True,
            fields=(_field(id="f1", required=True, tier=Tier.BASIC),),
        ),
        CustomSectionSpec(
            key="live", title="Shed survey", stage_key=STAGE, id="sec2",
            fields=(_field(key="shed", label="Shed condition", type=FieldType.TEXT,
                           required=True, tier=Tier.BASIC, id="f2"),),
        ),
    ))
    monkeypatch.setattr(service, "load_definition_or_empty",
                        lambda _id: _resolved(definition))

    rows = [_Row(STAGE, CUSTOM_ENTITY_KEY, {"looms": 12})]
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    await attach_report_custom_sections(data, rows, "w1")

    fields, values = custom_scoring(data, STAGE)
    in_the_document = stage_completeness(stage(STAGE), {}, {}, custom_fields=fields,
                                        custom_values=values)
    on_the_screen = workshop_completeness(rows, definition=definition)[STAGE]
    assert in_the_document.required_total == on_the_screen["requiredTotal"]
    assert list(in_the_document.missing) == list(on_the_screen["missing"])
    assert "Shed condition" in in_the_document.missing
    assert "How many looms?" not in in_the_document.missing


async def test_a_retired_section_answered_zero_still_reaches_the_document(monkeypatch):
    from app.services import custom_sections as service
    from app.services.design_workshops import attach_report_custom_sections
    from app.services.report_builder import WorkshopData
    from app.services.report_custom_sections import custom_sections_of

    definition = CustomDefinition(sections=(
        CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, id="sec1", retired=True,
                          fields=(_field(id="f1"),)),
    ))
    monkeypatch.setattr(service, "load_definition_or_empty",
                        lambda _id: _resolved(definition))
    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    await attach_report_custom_sections(data, [_Row(STAGE, CUSTOM_ENTITY_KEY, {"looms": 0})], "w1")
    assert [item.key for item in custom_sections_of(data)] == ["extra"]
    blank = WorkshopData(workshop_id="w1", title="Ikat workshop")
    await attach_report_custom_sections(blank, [_Row(STAGE, CUSTOM_ENTITY_KEY, {})], "w1")
    assert custom_sections_of(blank) == ()


async def test_the_warning_names_only_the_sections_the_document_really_leaves_out(monkeypatch):
    from app.services import custom_sections as service
    from app.services.design_workshops import attach_report_custom_sections
    from app.services.report_builder import WorkshopData
    from app.services.report_custom_sections import custom_section_of

    printed = CustomSectionSpec(
        key="dye", title="Dye bath log", stage_key=STAGE, id="sec1",
        fields=(_field(key="dyeSource", label="Dye source", type=FieldType.TEXT,
                       required=True, tier=Tier.BASIC, id="f1"),),
    )
    genuinely_absent = CustomSectionSpec(
        key="notes", title="Shed notes", stage_key=STAGE, id="sec2",
        fields=(_field(key="shedNote", label="Anything else?", type=FieldType.TEXT, id="f2"),),
    )
    monkeypatch.setattr(
        service, "load_definition_or_empty",
        lambda _id: _resolved(CustomDefinition(sections=(printed, genuinely_absent))),
    )

    data = WorkshopData(workshop_id="w1", title="Ikat workshop")
    warnings = await attach_report_custom_sections(
        data, [_Row(STAGE, CUSTOM_ENTITY_KEY, {})], "w1"
    )

    assert custom_section_of(data, "dye").has_content is True
    assert custom_section_of(data, "notes").has_content is False

    assert len(warnings) == 1
    assert "Shed notes" in warnings[0], "the all-optional block really is absent from the file"
    assert "Dye bath log" not in warnings[0], (
        "this one prints its heading and 'Not recorded.' — saying it is missing sends the "
        "designer looking for a bug that is not there"
    )


async def _resolved(value):
    return value


def test_a_workshop_with_no_custom_sections_builds_the_document_it_always_did():
    from app.services.report_builder import WorkshopData, build_report
    from app.services.report_model import ReportMeta

    def _build(template):
        document, warnings = build_report(
            WorkshopData(workshop_id="w1", title="Ikat workshop"), "DETAILED_TECHNICAL",
            lambda _id: None, meta=ReportMeta(title="Ikat workshop"), template=template,
        )
        return len(document.blocks), warnings

    base = get_template("DETAILED_TECHNICAL")
    assert _build(base) == _build(apply_report_settings(base, None, custom_sections=()))


def test_money_and_dates_print_exactly_as_the_registry_prints_them():
    from app.services.report_builder import format_value
    from app.services.stage_schema import FieldSpec

    money = CustomReportField(key="cost", label="Cost", type="MONEY")
    assert display_value(money, "125000.00") == format_value(
        FieldSpec(key="cost", label="Cost", type=FieldType.MONEY), "125000.00"
    )
    date = CustomReportField(key="on", label="On", type="DATE")
    assert display_value(date, "2026-03-04") == format_value(
        FieldSpec(key="on", label="On", type=FieldType.DATE), "2026-03-04"
    )
