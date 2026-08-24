"""Designer-defined sections, pinned: where the answers live, what is refused, and what supersedes.

Step 6 of ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §5. What is protected here is the half of
this feature that would be wrong in the same way on every workshop.

**NO DATABASE AND NO NETWORK. NOTHING IN THIS FILE SKIPS.** Every rule under test is decided by a
pure function in ``app.services.custom_sections`` — validation returns a list of sentences, an edit
returns a *plan* instead of performing a write — so the rules can be asserted on a laptop with no
Postgres and no generated Prisma client. That is deliberate, and it is the same argument
``test_ai_layers.py`` makes: this repository's history says the untestable half is the half that is
wrong, and a rule that is only exercised by a round-trip script somebody runs occasionally is a rule
nobody is enforcing.

**AND IT IS ONLY HALF THE ARGUMENT. READ ``test_custom_sections_endpoints.py`` BESIDE THIS FILE.**
For the whole of this feature's life, ``PUT /api/design-workshops/{id}/custom-sections`` answered 500
to every body that contained a field — one ``None`` handed to the driver, which refuses an explicit
``null`` for a nullable ``Json`` column — so no workshop could hold a single custom question and
nothing built on top of this service had ever run. Every test in this file passed throughout. None of
them could have failed: the defect is not in a decision, it is in the one step that has no decision
in it, and a suite with no database cannot reach it. Nothing here is wrong and nothing here should be
deleted to make room for that lesson — the planning rules below are exactly what a pure test should
pin. The lesson is that "the rules are covered" was read as "the feature works", and the sibling file
is what makes the second claim checkable. **A rule proven here is proven. An ENDPOINT is not.**

THE FOUR DECISIONS THIS FILE EXISTS TO KEEP, and what each one costs if it stops holding:

1. **The answers live in their own ``DwStageEntry`` row under the reserved key ``_custom``.** If
   they were ever nested inside a core entry, ``save_stage``'s default wholesale write means a
   client one release behind — one that sends no ``custom`` key — would delete every custom answer
   on the stage, silently, with nothing in ``droppedKeys`` to say so. The key is asserted to be
   unreachable by the registry, by the collection sweep and by ``promoted_values``.
2. **``customSchemaVersion`` is its own digest and never enters ``registry_version()``.** The core
   digest is the refetch signal for a 119 KB file compiled into the APK; a per-workshop digest would
   mark that asset stale on every handset in the fleet the moment any designer anywhere added a
   field. ``registry_to_dict()`` is asserted to mention custom sections nowhere at all, because it
   is compared byte for byte against that bundled asset and re-dumping it is an Android release.
3. **Custom drift never enters ``droppedKeys``.** That field is the only client/server registry-drift
   signal this repository has and both clients render it as "this phone is running a newer field
   registry than the server". An unknown custom key comes back separately, or every save of every
   workshop with a custom section cries wolf and the one signal that matters gets ignored.
4. **The scorer and the submit gate are taught together.** Two independent decisions of "required
   and missing" — ``stage_completeness``'s loop and ``validate_custom_entry``'s — must agree on
   every input, or a stage reads 100% on the readiness screen and then 422s on submit.

And the rule this feature borrows whole from ``services/questionnaire_forms.py``: **an answer is
evidence, and the words it was given under are part of that evidence.** "How many looms?" answered
"12", reworded to "How many weavers?", and a ministry report now states there are twelve weavers.
"""

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
    # The guard set itself, asserted directly beside the refusal it feeds. It is private because
    # nothing outside the module calls it, and it is imported here for the reason `test_ai_layers`
    # imports `_writable_model`: the refusal can be right for the wrong reason, and this is the
    # assertion that fails the day the set and the matching in `_plan_fields` part company again.
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
#: Stages 11-17 declare NO singleton entity at all. It is here because "hang the container on the
#: stage's singleton row" could not have served a third of the stages, and this feature has to work
#: on all of them.
#:
#: THIS USED TO BE STAGE 6 AND MOVED ON 2026-08-24. Stage 6 gained the `artisanBaseline` singleton
#: with the questionnaire-interview citation, so it is no longer an example of anything this
#: constant is for — and the assertion below (`.singleton is None`) is what said so rather than
#: leaving the constant quietly lying about the registry. Stage 11 is the first of the seven that
#: still have none.
STAGE_WITHOUT_SINGLETON = "SKETCH_DEVELOPMENT"


def _field(key="looms", label="How many looms?", **kw) -> CustomFieldSpec:
    kw.setdefault("type", FieldType.INT)
    return CustomFieldSpec(key=key, label=label, **kw)


def _section(*fields: CustomFieldSpec, key="extra", stage_key=STAGE, **kw) -> CustomSectionSpec:
    return CustomSectionSpec(
        key=key, title=kw.pop("title", "Loom shed"), stage_key=stage_key,
        fields=tuple(fields), **kw
    )


# --------------------------------------------------------------------------------------
# Decision 1: the reserved row, and everything it is deliberately invisible to
# --------------------------------------------------------------------------------------


def test_the_reserved_key_cannot_be_a_registry_entity_or_a_designers_own():
    """``_custom`` must be unreachable from both directions, or the whole design leaks.

    From the REGISTRY side: if any entity were ever keyed ``_custom``, the stage save would route
    the container through ``validate_entry`` and the collection sweep could soft-delete it.

    From the DESIGNER side: if a designer could name a section or a field ``_custom``, they would be
    writing into the protocol's own namespace — the same namespace ``_clientKey``, ``_entryId`` and
    ``_ordinal`` occupy, which both clients strip.
    """
    assert CUSTOM_ENTITY_KEY.startswith("_")
    assert all(
        e.key != CUSTOM_ENTITY_KEY for s in stages() for e in s.entities
    )
    problems = validate_definition([_section(_field(key=CUSTOM_ENTITY_KEY))])
    assert any(CUSTOM_ENTITY_KEY in p for p in problems)


def test_the_collection_sweep_can_never_reach_the_custom_row():
    """Half the invariant, and this test is honest about which half.

    ``save_stage``'s sweep is ``(touched_entities | emptiedEntities) & collection_keys``, and
    ``collection_keys`` is built from ``spec.entities``. What is asserted HERE is that no stage
    declares a COLLECTION entity keyed ``_custom``, which is what keeps the reserved row out of that
    intersection today. It cannot assert the other half — that nobody widens the set — because the
    set is a local of a database function; the guard for that is the schema refusal pinned by the
    next test, and the incident behind both is in ``save_stage``'s own comment: a sweep that reached
    entities the payload never named cost the flagship workshop four cost sheets, two buyer links
    and six prototypes.
    """
    for spec in stages():
        collection_keys = {
            e.key for e in spec.entities if e.cardinality is Cardinality.COLLECTION
        }
        assert CUSTOM_ENTITY_KEY not in collection_keys


def test_the_reserved_container_cannot_be_named_as_an_emptied_collection():
    """The other half of the sweep invariant, refused where a client can be told about it.

    ``emptiedEntities`` is a client saying "I hold zero rows of this, delete what you still have" —
    the one instruction that can delete rows the payload does not carry. A workshop's entire custom
    record for a stage is ONE row, so a sweep that ever reached it would take every designer-defined
    answer on that stage in a single statement. It is harmless today only because of the
    intersection; refusing the reserved namespace outright is what keeps it harmless after somebody
    widens that set for a good reason.
    """
    from app.schemas.design_workshops import StageSaveIn

    with pytest.raises(ValueError, match="underscore"):
        StageSaveIn(emptiedEntities=[CUSTOM_ENTITY_KEY])
    # An ordinary collection is untouched: this refusal is exactly as wide as the namespace.
    assert StageSaveIn(emptiedEntities=["costSheet"]).emptiedEntities == ["costSheet"]


def test_promoted_columns_cannot_be_written_from_a_custom_field():
    """Structurally impossible, and the assertion is that it stays structural.

    ``promoted_values`` matches ``source_entity == entity_key`` against the ``PROMOTED_COLUMNS``
    module literal. ``_custom`` appears in no path of it, so no guard, allowlist or runtime check is
    needed to keep designer data out of the queryable columns — and this test is what would notice
    if a path were ever added.
    """
    from app.services.stage_schema import PROMOTED_COLUMNS, promoted_values

    assert all(not path.startswith(CUSTOM_ENTITY_KEY) for path in PROMOTED_COLUMNS)
    assert promoted_values(CUSTOM_ENTITY_KEY, {"craftName": "Ikat", "title": "x"}) == {}


def test_every_stage_can_carry_a_custom_section_including_the_eight_with_no_singleton():
    """Eight of the twenty-two declare no SINGLETON entity, and they are the ones designers extend.

    Stages 6 and 11–17 are existing products, sketches, sketch review, prototypes, iteration,
    validation, final documentation and costing. A container hung off the singleton row would have
    been unavailable on all of them.
    """
    assert stage(STAGE_WITHOUT_SINGLETON).singleton is None
    assert validate_definition([_section(_field(), stage_key=STAGE_WITHOUT_SINGLETON)]) == []


# --------------------------------------------------------------------------------------
# Decision 2: the digest is its own, and the core registry never mentions this feature
# --------------------------------------------------------------------------------------


def test_the_registry_dump_says_nothing_about_custom_sections():
    """``registry_to_dict()`` gains NOTHING — not a key, not a flag, not one string.

    It is compared by CONTENT against a checked-in 119 KB Android asset
    (``test_the_bundled_android_asset_is_the_registry_it_claims_to_be``). Anything added here fails
    that suite until the asset is re-dumped, and re-dumping it is an Android release — so this test
    fails first, in the lane that would have caused it.
    """
    dump = registry_to_dict()
    assert set(dump) == {"version", "enums", "stages"}
    assert "_custom" not in json.dumps(dump)
    assert "customSchemaVersion" not in json.dumps(dump)


def test_the_digest_moves_when_a_question_is_reworded():
    """The one place this digest deliberately differs from ``registry_version()``.

    The core registry excludes labels so that retitling one field does not invalidate a bundled
    asset on every handset in the fleet. Neither half of that is true here: this definition is one
    workshop's, and the label IS the question. A phone that goes on showing "How many looms?" after
    the designer rewrote it records an answer against a question nobody asked.
    """
    before = custom_schema_version([_section(_field())])
    after = custom_schema_version([_section(_field(label="How many weavers?"))])
    assert before != after


def test_the_digest_moves_when_an_option_is_added_and_when_one_is_relabelled():
    """A designer's list has no bundled asset and no second content test behind it.

    ``ENUMS``' contents are deliberately outside ``registry_version()`` because a second test
    compares the whole dump against the asset. There is no such second test for a per-workshop
    definition, so the digest is the only staleness signal it will ever have — and a designer
    standing in the cluster picking from last week's list is the failure that argument was written
    about.
    """
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
    """The heading is the wording the answers under it were given under, exactly as a label is.

    This loop iterated FIELDS only, so retitling "Loom shed" to "Weaver shed" — or dragging it above
    another section, which changes nothing but ``sortOrder`` — digested byte-identically. That is not
    a cosmetic miss on the web: ``fetchCustomDefinition``/``adoptCustomDefinition``
    (frontend/lib/customSections.ts) DISCARD a freshly fetched definition and return the previously
    cached object when the digest is equal, deliberately, for identity reasons. So every tab that had
    already loaded the workshop went on printing "Loom shed", in the old order, over the same answers
    the server's .docx printed "Weaver shed" over — with nothing on either surface saying they
    disagreed.

    Three distinct digests, not two assertions of inequality, because the failure mode is a
    COLLISION: any implementation that folds all three into one string without a separator would pass
    a pair of != checks and still be wrong.
    """
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
    """"I hold nothing" and "there is nothing to hold" must not look identical to a phone.

    That distinction is why ``DwQuestionnaireCopy`` has three states and not two, and warning on
    both is how a designer learns to stop reading warnings.
    """
    assert custom_schema_version([]) == ""
    assert custom_schema_version([_section()]) == ""


def test_help_text_is_the_one_visible_string_outside_the_digest():
    """A stale hint beside a correct question is a lesser failure than a stale question."""
    assert custom_schema_version([_section(_field())]) == custom_schema_version(
        [_section(_field(help="Count the pit looms only."))]
    )


# --------------------------------------------------------------------------------------
# Definition-time validation: every refusal names the key and what it collided with
# --------------------------------------------------------------------------------------


def test_a_sound_definition_has_no_problems():
    assert validate_definition([_section(_field(), _field(key="shed", label="Shed condition",
                                                          type=FieldType.TEXT))]) == []


def test_a_required_field_must_be_basic_tier():
    """Verbatim from ``validate_registry`` rule 3, and for its reason.

    The tiers exist so a workshop held in a village without power can still produce a complete
    report. A required Standard field makes the completeness gate unsatisfiable exactly where the
    app is most needed — and a designer's own field is no different in that respect.
    """
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
    """**The check the plan asks for last and the one that actually bites.**

    ``StageCompleteness.missing`` holds LABELS and is de-duplicated with ``dict.fromkeys``, so two
    required fields sharing a label collapse into ONE row on the readiness screen and in the
    report's "Outstanding" column while ``required_total`` still counts two — a document
    disagreeing with itself about its own arithmetic, which this repository has shipped once.
    """
    problems = validate_definition([_section(_field(key="loomCraft", label="Craft"))])
    assert any("Craft" in p and "readiness" in p for p in problems)


def test_two_custom_fields_on_one_stage_may_not_share_a_label():
    problems = validate_definition([
        _section(_field(key="a", label="Loom count"), key="one"),
        _section(_field(key="b", label="Loom count"), key="two"),
    ])
    assert any("Loom count" in p for p in problems)


def test_the_same_label_on_two_different_stages_is_fine():
    """The collapse is per stage, so the refusal is too. Anything wider refuses ordinary words."""
    assert validate_definition([
        _section(_field(key="a", label="Notes"), key="one", stage_key=STAGE),
        _section(_field(key="b", label="Notes"), key="two",
                 stage_key=STAGE_WITHOUT_SINGLETON),
    ]) == []


def test_two_fields_may_not_share_a_key_even_in_different_sections():
    """One stage's answers share ONE flat container, so one key is one answer.

    The database's ``@@unique([sectionId, key])`` cannot express this — it spans sections — so the
    service is the only thing standing between a designer and two questions with one answer.
    """
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
    """Five separate walkers translate a local media reference into a server id.

    Every one of them enumerates the media-typed fields OF THE ROW'S REGISTRY ENTITY and reads them
    at the top level of the row, so a custom media answer syncs as a ``dwlocal:`` reference
    resolving to nothing: the save reports success and the photograph is simply absent from the
    .docx, which the designer discovers from the officer who received it.

    REF is out for a different reason with the same shape: ``ref_resolves`` is supplied by the
    REPORT and by nothing else, so a dangling custom reference reads *filled* on every form and
    *unfilled* in the document — the 144/144-versus-"Not recorded" defect, verbatim.
    """
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
    """A stored-and-ignored control is the failure this repository has already had to fix seven of.

    ``_range_checked`` is reached only from ``coerce_value``'s numeric branches, so a ``minValue``
    on a TEXT field is inert — the designer sets it, the form obeys nothing, and nothing says so.
    """
    problems = validate_definition(
        [_section(_field(key="notes", label="Notes", type=FieldType.TEXT, min_value=1))]
    )
    assert any("never be checked" in p for p in problems)


def test_every_problem_is_a_sentence_and_not_a_code():
    """House rule, asserted: errors name the next move and never look like an error code."""
    problems = validate_definition([
        _section(_field(key="craftName", required=True, tier=Tier.ADVANCED))
    ])
    assert problems
    for message in problems:
        assert message[0].isupper() or message.startswith(("'", "`"))
        assert message.rstrip().endswith((".", "?"))
        assert " " in message


# --------------------------------------------------------------------------------------
# Answer time: never a wholesale refusal, and one coercer
# --------------------------------------------------------------------------------------


def test_an_unknown_custom_key_is_dropped_and_reported_and_never_a_refusal():
    result = validate_custom_entry([_field()], {"looms": 12, "ghost": "x"})
    assert result.clean == {"looms": 12}
    assert result.dropped == ("ghost",)
    assert result.errors == {}


def test_the_protocol_keys_are_not_reported_as_dropped():
    """Reporting them would put a line in every response for something working as designed."""
    result = validate_custom_entry([_field()], {"looms": 1, "_clientKey": "abc", "_ordinal": 0})
    assert result.dropped == ()


def test_a_custom_money_is_coerced_by_exactly_the_code_a_core_money_is():
    """One coercer, reached by handing ``coerce_value`` the shape it already knows how to read.

    A custom DECIMAL that rounded differently from a core one, or a MONEY that stored a float where
    the registry stores a fixed-2 string, would be a cross-surface divergence whose cause nobody
    would find: the two values look identical on screen and differ in the .docx.
    """
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
    """A stage half-filled overnight is the normal state of this app, not an error."""
    field = _field(required=True, tier=Tier.BASIC)
    assert validate_custom_entry([field], {}, enforce_required=False).errors == {}
    assert "required" in validate_custom_entry([field], {}, enforce_required=True).errors["looms"]


def test_a_retired_fields_stored_answer_round_trips_instead_of_being_dropped():
    """Its wording is evidence; dropping it as an unknown key would delete the evidence."""
    retired = _field(key="oldLooms", label="How many looms?", retired=True)
    result = validate_custom_entry([retired], {"oldLooms": 12})
    assert result.clean == {"oldLooms": 12}
    assert result.dropped == ()


def test_a_retired_required_field_never_blocks_a_submission():
    retired = _field(required=True, tier=Tier.BASIC, retired=True)
    assert validate_custom_entry([retired], {}, enforce_required=True).errors == {}


# --------------------------------------------------------------------------------------
# The write path, composed: merge, rejected values, and the submit gate
#
# ``plan_custom_write`` is the whole of the answer-time decision and is pure, so the COMBINATION of
# the three rules can be pushed on here rather than only through a database round trip. Each
# ingredient on its own is easy; the composition is where this gets subtle.
# --------------------------------------------------------------------------------------


def test_a_client_one_release_behind_writes_no_custom_row_at_all():
    """**The failure the whole storage design exists to prevent, asserted as a shape.**

    ``save_stage``'s default is ``merge=false``, which writes ``data`` WHOLESALE. Had the container
    been nested inside a core entry, a client that has never heard of custom sections — one that
    sends no ``custom`` key — would have deleted every custom answer on the stage, silently, with
    nothing in ``droppedKeys`` to say so. Sending no entry has to mean writing no row.
    """
    write = plan_custom_write([_field()], sent=None, previous={"looms": 12})
    assert write.data is None
    assert write.errors == {}
    assert write.dropped == ()


def test_an_empty_container_is_a_designer_clearing_every_answer_and_is_written():
    """None and ``{}`` are different instructions and must not be collapsed into one."""
    write = plan_custom_write([_field()], sent={}, previous={"looms": 12})
    assert write.data == {}


def test_a_never_read_client_merges_the_custom_row_instead_of_replacing_it():
    """The shallow merge is already correct here, with no recursive variant written for one key.

    That is the property that made a row of its own cheaper than nesting: keys the client never had
    are preserved, keys it did have win, and an empty string the designer actually typed stays.
    """
    fields = [_field(), _field(key="shed", label="Shed", type=FieldType.TEXT)]
    write = plan_custom_write(
        fields, sent={"shed": "Tiled"}, previous={"looms": 12}, merge=True
    )
    assert write.data == {"looms": 12, "shed": "Tiled"}


def test_a_client_that_has_read_the_row_still_deletes_by_omission():
    """``merge`` is the exception and not the rule: for a client that HAS seen the server's copy an
    absent key is a real deletion and must still delete."""
    write = plan_custom_write([_field()], sent={}, previous={"looms": 12}, merge=False)
    assert write.data == {}


def test_a_rejected_value_does_not_destroy_the_answer_already_stored_under_it():
    """Type "6500", save, later fat-finger "65OO": the price must not be GONE while the response
    says only that the edit was rejected."""
    price = _field(key="price", label="Price", type=FieldType.MONEY)
    write = plan_custom_write([price], sent={"price": "65OO"}, previous={"price": "6500.00"})
    assert write.data == {"price": "6500.00"}
    assert "price" in write.errors


def test_the_submit_gate_reads_the_row_as_it_will_stand_and_not_the_payload():
    """A client that sends no custom entry would otherwise submit a stage clean while
    ``stage_completeness`` scores the very same stage as incomplete.

    Requiredness is a property of the RECORD, not of one request — the second half of the
    scorer-and-gate agreement, in the direction that is easy to miss.
    """
    field = _field(required=True, tier=Tier.BASIC)
    # Nothing sent, nothing stored, submitting: refused.
    assert "looms" in plan_custom_write([field], sent=None, previous={}, submit=True).errors
    # Nothing sent, but the answer is already stored: allowed.
    assert plan_custom_write([field], sent=None, previous={"looms": 12}, submit=True).errors == {}
    # Sent and answered: allowed.
    assert plan_custom_write([field], sent={"looms": 12}, previous={}, submit=True).errors == {}
    # Left as a draft: never refused.
    assert plan_custom_write([field], sent={}, previous={}, submit=False).errors == {}


def test_the_coercion_message_wins_over_the_required_one():
    """"That is not a valid number" tells the designer what to do about the value in front of them;
    "… is required" does not."""
    field = _field(required=True, tier=Tier.BASIC)
    write = plan_custom_write([field], sent={"looms": "twelve"}, previous={}, submit=True)
    assert "valid" in write.errors["looms"]


def test_a_retired_sections_answers_survive_the_next_ordinary_save():
    """**Rule 4 is "the stored answer stays readable and printable", not "until the next save".**

    A section is retired precisely BECAUSE somebody answered it, so its keys are exactly the keys the
    ``_custom`` row still holds. Reading the definition as "live sections only" made every one of
    them an unknown key: the next save from any client that sends its container — which is what both
    of them do, having just been handed that container by GET — dropped the lot, reported them as
    ``droppedCustomKeys`` and wrote the row back without them. The report would then print a heading
    for a section whose answers the save path had deleted an hour earlier.
    """
    definition = CustomDefinition(sections=(
        CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, retired=True,
                          fields=(_field(id="f1"),)),
    ))
    specs = definition.fields_for(STAGE)
    assert [f.key for f in specs] == ["looms"]
    # …and marked retired, so nothing asks it again and the scorer does not count it.
    assert all(f.retired for f in specs)
    write = plan_custom_write(specs, sent={"looms": 12}, previous={"looms": 12})
    assert write.data == {"looms": 12}
    assert write.dropped == ()
    # …AND WHEN THE CLIENT DOES NOT ECHO THE KEY AT ALL, which is the half of this rule that was
    # resting on client goodwill. Everything above only ever asserted the well-behaved client — the
    # one that sends `{"looms": 12}` straight back — so "a retired answer survives" was true of every
    # payload the test wrote and false of the payload a client that renders only the LIVE fields
    # actually sends. A form is built from `live_fields`; a client that fills its container from the
    # form it drew therefore omits every retired key, and `merge` defaults to false, so the omission
    # read as a deletion and the retired answer was gone with nothing in `droppedCustomKeys` to say
    # so — silent precisely BECAUSE the key was known.
    write = plan_custom_write(specs, sent={}, previous={"looms": 12})
    assert write.data == {"looms": 12}
    assert write.dropped == ()
    # The shape a supersede leaves behind, which is the one this actually happens on: the retired
    # wording and its replacement side by side in one container, and a payload that names only the
    # replacement because that is the only one on screen. An absent retired key can never be a
    # deletion the designer meant — no form offers it, so nobody can have cleared it — which is the
    # same argument that already stops a bad value deleting a stored good one.
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
    # A LIVE key absent from the payload still deletes, and that is not a contradiction: a live
    # question IS on the form, so an absent live key is a designer clearing an answer and must be
    # honoured. The asymmetry is the whole rule.
    live_only = plan_custom_write([_field()], sent={}, previous={"looms": 12})
    assert live_only.data == {}
    # The scorer must not count a retired section's required field, or the annexure and the
    # readiness screen would disagree about one stage.
    required = CustomSectionSpec(
        key="extra", title="Loom shed", stage_key=STAGE, retired=True,
        fields=(_field(id="f1", required=True, tier=Tier.BASIC),),
    )
    scored = _score(CustomDefinition(sections=(required,)).fields_for(STAGE), {})
    assert scored.required_total == stage_completeness(stage(STAGE), {}, {}).required_total


def test_a_definition_edit_that_narrows_a_bound_cannot_make_a_stage_unsubmittable():
    """**The 100%-and-a-422 defect, reached through the submit gate instead of through the scorer.**

    Rule 2 says an answered field's bounds may change freely. Re-coercing the stored row inside the
    gate meant that lowering a maximum from 500 to 100 after 500 was recorded reported an error
    against a value nobody had sent, on every save — and the route 422s on any error under
    ``submit``, so the stage could never be submitted again while ``stage_completeness`` went on
    calling the same 500 filled and the stage complete. The gate asks about PRESENCE, with the
    scorer's own function, and nothing else.
    """
    narrowed = _field(max_value=100, required=True, tier=Tier.BASIC)
    write = plan_custom_write([narrowed], sent=None, previous={"looms": 500}, submit=True)
    assert write.errors == {}
    # And the scorer agrees, which is the half that makes it one arithmetic: 500 is an answer.
    assert "How many looms?" not in _score([narrowed], {"looms": 500}).missing
    # An option removed after it was answered is the same shape, and the report already prints the
    # stored token rather than failing an export over it.
    dye = _field(key="dye", label="Dye", type=FieldType.ENUM,
                 options=(CustomOption("NATURAL", "Natural"), CustomOption("MIXED", "Mixed")))
    assert plan_custom_write(
        [dye], sent=None, previous={"dye": "CHEMICAL"}, submit=True
    ).errors == {}


def test_the_write_path_never_reports_a_custom_key_as_registry_drift():
    """``droppedKeys`` is the only client/server registry-drift signal this repository has, and both
    clients render it as "this phone is running a newer field registry than the server".

    An unknown custom key is a different fact with a different remedy. Feeding it into that signal
    would fire the banner on every save of every workshop that has a custom section, and the people
    who read it would learn to ignore the one message that matters.
    """
    write = plan_custom_write([_field()], sent={"looms": 1, "ghost": "x"}, previous={})
    assert write.dropped == ("ghost",)
    assert write.data == {"looms": 1}


# --------------------------------------------------------------------------------------
# Completeness: one rule, scored key by key, and the same rule the submit gate applies
# --------------------------------------------------------------------------------------


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
    """A dict with keys reads as filled even when every answer in it is blank.

    ``_is_filled`` returns ``bool(value)`` for a dict, so a stage scored on the CONTAINER would
    report itself complete on the strength of the container existing — twenty blank answers and a
    hundred per cent.
    """
    fields = [_field(required=True, tier=Tier.BASIC),
              _field(key="shed", label="Shed", required=True, tier=Tier.BASIC,
                     type=FieldType.TEXT)]
    blanks = {"looms": None, "shed": "   "}
    assert bool(blanks) is True          # the container itself is truthy …
    scored = _score(fields, blanks)      # … and the scorer must not care
    assert scored.required_filled == stage_completeness(stage(STAGE), {}, {}).required_filled
    assert scored.missing[-2:] == ("How many looms?", "Shed")


def test_a_retired_field_is_skipped_exactly_as_a_deprecated_registry_field_is():
    """Otherwise a stage is permanently incomplete because of a question the designer corrected."""
    retired = _field(required=True, tier=Tier.BASIC, retired=True)
    assert _score([retired], {}).required_total == stage_completeness(
        stage(STAGE), {}, {}
    ).required_total


def test_a_custom_field_is_filed_under_its_bare_label_like_a_singleton_field():
    """A collection field files ``"Entity: label"``; a custom one files the label alone.

    That is what makes the duplicate-label refusal exactly as wide as the collapse it prevents.
    """
    scored = _score([_field(required=True, tier=Tier.BASIC)], {})
    assert "How many looms?" in scored.missing
    assert not any(m.endswith(": How many looms?") for m in scored.missing)


def test_the_scorer_and_the_submit_gate_agree_on_every_input():
    """**The two gates, taught in one change and asserted to stay taught.**

    ``stage_completeness``'s loop and ``validate_custom_entry``'s ``enforce_required`` are two
    independent decisions of "required and missing". Teach one without the other and a stage reads
    100% on the readiness screen and then 422s on submit — the designer is told two contradictory
    things about one form, with no way to tell which is right.
    """
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
        # AND THROUGH THE GATE THE ROUTE ACTUALLY GOES THROUGH, against the row as it will stand.
        # Asserting only against `validate_custom_entry` would leave the real submit gate — the one
        # `save_stage` calls, which reads the stored row and not the payload — cross-checked against
        # nothing.
        written = plan_custom_write(fields, sent=values, previous={}, submit=True)
        scored = _score(fields, written.data or {})
        assert {f.label for f in fields if f.key in written.errors} == (
            set(scored.missing) & {f.label for f in fields}
        ), values


# --------------------------------------------------------------------------------------
# Editing a definition that already has answers
# --------------------------------------------------------------------------------------


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
    """The ordinary case — a designer drafting their form — and it must stay frictionless."""
    stored = [_stored(_field(id="f1"))]
    edited = [_stored(_field(id="f1", label="How many weavers?"))]
    assert ("EDIT", "EDIT", "looms") in _plans(stored, edited, {})
    assert ("EDIT", "DELETE", "looms") in _plans(stored, [_stored()], {})


def test_rewriting_the_label_of_an_answered_field_supersedes_it():
    """**The failure ``questionnaire_forms`` names, prevented here for its reason.**

    "How many looms?" answered "12", reworded to "How many weavers?", and a ministry report now
    states there are twelve weavers. The old field keeps its answers under its own key with the
    wording they were given; the new wording becomes a new field under a minted key.
    """
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
    """Help, required, unit, bounds and position alter nothing a recorded answer asserts."""
    stored = [_stored(_field(id="f1"))]
    incoming = [_stored(_field(
        id="f1", help="Pit looms only.", required=True, tier=Tier.BASIC, unit="looms",
        min_value=0, max_value=500, sort_order=3,
    ))]
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    (field_plan,) = plan.sections[0].fields
    assert field_plan.action == "EDIT"


def test_deleting_an_answered_field_retires_it_and_can_never_delete_it():
    """There is no foreign key underneath this rule, so this branch IS the enforcement.

    ``QuestionnaireFormAnswer.questionId`` is ON DELETE RESTRICT and the equivalent cannot exist
    here — a custom answer is a key inside a JSONB blob, so Postgres has nothing to point at and
    nothing to refuse. The plan being unable to express the delete is what stands in for it.
    """
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
    """**The idempotence that stops one reconnection leaving six copies of one question.**

    A whole-set PUT names fields by key. After a supersede the old key belongs to a retired row and
    the new wording lives under a minted key — so an offline client that never refetched sends the
    OLD key with the NEW label, on every save, for ever. Each of those saves would otherwise look
    like another rewording of an answered field.
    """
    stored = [_stored(
        _field(id="f1", retired=True, superseded_by="f2"),
        _field(id="f2", key="loomsR2", label="How many weavers?"),
    )]
    incoming = [_stored(_field(id="", label="How many weavers?"))]   # the stale client's body
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    actions = [f.action for f in plan.sections[0].fields]
    assert "SUPERSEDE" not in actions
    assert plan.superseded == 0


def test_a_key_retired_by_deletion_cannot_be_claimed_again():
    """Its answers are still held under it, so handing the key back would attach them to a new
    question — the twelve-weavers failure with an extra step."""
    stored = [_stored(_field(id="f1", retired=True))]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, [_stored(_field(id="", label="Something else"))],
                        {STAGE: {"looms"}})
    assert "looms" in str(exc.value)


def test_an_answered_section_cannot_be_moved_to_another_stage():
    """The answers live in the container of the stage the section is asked at.

    Moving the section leaves them behind: still stored, no longer asked, no longer scored, and
    invisible on every form. That is not an edit, it is a silent data loss.
    """
    stored = [_stored(_field(id="f1"))]
    moved = [CustomSectionSpec(key="extra", title="Loom shed", stage_key="DESIGN_BRIEF",
                               id="sec1", fields=(_field(id="f1"),))]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, moved, {STAGE: {"looms"}})
    assert "stage" in str(exc.value)
    # …and it is perfectly free while nobody has answered it.
    assert plan_definition(stored, moved, {}).sections[0].action == "EDIT"


def test_a_key_that_holds_an_answer_cannot_be_claimed_by_a_new_section():
    """**The twelve-weavers failure by the one route the label rule does not cover.**

    ``validate_definition`` is pure and reads only the payload, so it cannot see that ``looms`` still
    holds an answer given to a question that has since been retired. Without this refusal a designer
    could retire "Loom shed", add "Shed survey" declaring its own ``looms`` labelled "How many
    weavers?", and — because the container is per STAGE — the new question would read the old
    question's answer straight out of the row. The report would then state there are twelve weavers.
    """
    stored = [_stored(_field(id="f1"), key="extra")]
    fresh = [CustomSectionSpec(
        key="survey", title="Shed survey", stage_key=STAGE,
        fields=(_field(key="looms", label="How many weavers?"),),
    )]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, fresh, {STAGE: {"looms"}})
    assert "looms" in str(exc.value)
    # Nobody answered it: the key is free, and the ordinary case stays frictionless.
    assert plan_definition(stored, fresh, {}).sections


def test_a_key_retired_as_collateral_cannot_be_claimed_by_a_new_section_either():
    """**The twelve-weavers failure again, by the one route "does it hold an answer" cannot see.**

    RETIRING A SECTION RETIRES EVERY FIELD UNDER IT, INCLUDING THE ONES NOBODY ANSWERED. The section
    is kept whole rather than sifted — which is right, because its heading and its questions are the
    context the answers were given in — so removing "Loom shed" left BOTH its keys held by retired
    rows: ``secretary``, which holds "Sita", and ``looms``, which was never answered at all. A guard
    that asks only "does this key hold an answer" therefore hands ``looms`` straight back to the next
    section that asks for it, and the container is per STAGE, so ``fields_for`` then returns TWO specs
    under ONE key. Both ways that lands are a designer's fieldwork destroyed:

    * the types differ, so the retired INT spec refuses "about nine" with *"How many looms? is not a
      valid int"* — naming a question on no screen — and the rejected-value preservation loop writes
      the row back as ``{"looms": 12}``, replacing what was just typed with the retired question's
      old answer. Under ``submit`` the same error 422s the whole stage;
    * the types match, the save looks clean, and the document prints the one stored value TWICE —
      once under the retired heading as "(no longer asked)" and once under the new wording.

    The database cannot catch this and no schema change would let it: field uniqueness is
    section-scoped (``migration.sql``), which is correct for the schema and simply cannot express a
    stage-wide rule.
    """
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
    # `looms` holds NO answer — only `secretary` does — and it must still be refused.
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, fresh, {STAGE: {"secretary"}})
    assert "looms" in str(exc.value)
    # Not even when nothing in the whole retired section was ever answered: the rows are still there,
    # still holding both keys, and `fields_for` still reads them.
    with pytest.raises(CustomSectionEditError):
        plan_definition(stored, fresh, {})
    # The refusal is about ONE container, and a container is one stage. The same key on another
    # stage is not a collision at all, and treating it as one would refuse a designer the obvious
    # name for the obvious question on nineteen other stages.
    other = [CustomSectionSpec(
        key="survey", title="Shed survey", stage_key="DESIGN_BRIEF", fields=(_field(),)
    )]
    assert plan_definition(stored, other, {STAGE: {"secretary"}}).sections


def test_a_key_the_same_put_deletes_outright_is_still_free_for_the_taking():
    """The widened guard must not make drafting a form a fight, and this is where it would.

    Two ordinary edits look exactly like a new field claiming a stored key: moving a question from
    one section to another, and changing a section's key (the key is what identifies a section, so a
    new one IS a new section). In both, the row that held the key is DELETEd by this same write —
    which the plan can only express because nobody had answered it — so nothing is left holding it
    and nothing can be attached to the wrong wording. Refusing these would mean a designer who
    reorganises a form they are still drafting is told to choose another key for a question they
    have not yet asked anybody.
    """
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
    # …and the moment one of those answers exists, the same two edits are refused: the row is
    # RETIREd rather than deleted, so the key is still held.
    with pytest.raises(CustomSectionEditError):
        _plans(stored, moved, {STAGE: {"looms"}})
    with pytest.raises(CustomSectionEditError):
        _plans([_stored(_field(id="f1"))], renamed, {STAGE: {"looms"}})


def test_a_minted_key_the_supersede_walk_keeps_cannot_be_claimed_either():
    """**The way round the widened guard, found by following the walk the guard did not mirror.**

    ``_plan_fields`` does not match an incoming field by key alone. A key that lands on a RETIRED row
    is followed through ``supersededById`` to whatever is live now — that is what stops a stale client
    minting a sixth copy of one question — so a payload naming the old key ``looms`` matches, EDITs and
    therefore KEEPS the row minted under ``loomsR2``, a key the payload never mentions. A guard that
    read "the payload matched this row" as "the payload names this row's key" called ``loomsR2`` free
    and handed it to the next section that asked for it.

    Executed against that state the PUT was ACCEPTED and planned ``loomsR2`` as an EDIT and a CREATE
    in one write. ``fields_for`` then had two LIVE specs for one container slot, the INT one took it,
    and "indigo" typed into "Which bath was used?" came back *"How many weavers work here? is not a
    valid int"* — a question on no screen — with the row rewritten as ``{"looms": 12, "loomsR2": 30}``
    and the whole stage 422 under ``submit``. In rows this module wrote it is only ever a MINTED key
    that can be taken this way — a SUPERSEDE is the only thing that sets ``supersededById`` — and a
    designer may key their own field ``loomsR2``: nothing refuses it, and this is what happens then.

    The stale body is not an exotic input. ``_live_successor`` exists because it is what a client that
    never refetched sends on every save, for ever.
    """
    stored = [_stored(
        _field(id="f1", retired=True, superseded_by="f2"),
        _field(id="f2", key="loomsR2", label="How many weavers work here?"),
    )]
    put = [
        _stored(_field(id="", label="How many weavers work here?")),   # the stale client's body
        CustomSectionSpec(key="baths", title="Dye baths", stage_key=STAGE, fields=(
            _field(key="loomsR2", label="Which bath was used?", type=FieldType.TEXT),
        )),
    ]
    with pytest.raises(CustomSectionEditError) as exc:
        plan_definition(stored, put, {STAGE: {"looms"}})
    assert "loomsR2" in str(exc.value)
    # The row the walk lands on is the thing being protected, so it has to be in the held set under
    # the stage it will be asked at — asserted directly, because the refusal above would also pass if
    # it fired for the wrong key.
    assert "loomsR2" in _keys_this_put_keeps(stored, put, {STAGE: {"looms"}})[STAGE]
    # AND THE WALK THE MIRROR NOW FOLLOWS IS STILL DOING ITS OWN JOB: the stale body on its own is
    # idempotent, and widening the guard must not have turned it into a refusal. A designer whose
    # client is behind must still be able to save the form they can see.
    assert plan_definition(stored, put[:1], {STAGE: {"looms"}}).superseded == 0
    assert ("EDIT", "EDIT", "loomsR2") in _plans(stored, put[:1], {STAGE: {"looms"}})


def test_two_specs_can_never_reach_the_answer_path_under_one_container_key():
    """Belt and braces for a definition that is ALREADY in the state above, however it got there.

    A row written by hand, a definition restored from a backup taken before the refusal existed, or
    a bug nobody has found yet: one container key carrying two specs must degrade to one question
    rather than to a coercion war, because the loser of that war is whatever the designer just typed.

    THE LIVE SPEC WINS THE SLOT, and which one wins is the whole point rather than a tie-break. The
    live question is the one on the screen the answer is being typed into, so it is the one that must
    coerce it; keeping the retired spec is exactly the failure this guards — an error naming a
    question on no screen, and the typed value replaced by the old one. Nothing is lost by dropping
    the retired duplicate: it shares the one container slot with the live field, which is a key the
    answer path knows, so the stored value still round-trips rather than being dropped as unknown.
    What this CANNOT undo is the document printing that value under both headings; only the refusal
    in ``plan_definition`` prevents that, and this is the second line of defence, not the first.
    """
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
    # The designer types their answer into the question they can see, and it is what gets stored.
    write = plan_custom_write(specs, sent={"looms": "about nine"}, previous={"looms": 12})
    assert write.errors == {}
    assert write.data == {"looms": "about nine"}
    assert write.dropped == ()


def test_re_adding_a_section_that_was_retired_asks_it_again_rather_than_doing_nothing():
    """A whole-set PUT IS the definition, so naming a section means "ask this".

    Leaving ``isActive`` alone made re-adding a removed section a 200 that changed nothing anybody
    could see: the section came back in the response still marked retired, no form ever offered it,
    and the fields created under it were invisible on every screen. A silent no-op is the failure
    this pipeline keeps having to fix, and the plan is the only thing that can say otherwise.
    """
    stored = [CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE, id="sec1",
                               retired=True, fields=(_field(id="f1", retired=True),))]
    incoming = [CustomSectionSpec(key="extra", title="Loom shed", stage_key=STAGE,
                                  fields=(_field(key="shed", label="Shed condition",
                                                 type=FieldType.TEXT),))]
    plan = plan_definition(stored, incoming, {STAGE: {"looms"}})
    (section_plan,) = plan.sections
    assert section_plan.action == "EDIT"
    assert section_plan.spec is not None and section_plan.spec.retired is False
    # The retired field is left retired — its wording is evidence — and the new one is created.
    assert [(f.action, f.key) for f in section_plan.fields] == [("CREATE", "shed")]


def test_answered_is_the_completeness_scorers_own_question():
    """A field the readiness screen counts as filled is a field whose wording is now evidence."""
    fields = [_field(), _field(key="shed", label="Shed", type=FieldType.TEXT)]
    assert answered_keys(fields, {"looms": 0, "shed": "   "}) == {"looms"}
    assert answered_keys(fields, {"shed": "Tiled"}) == {"shed"}


# --------------------------------------------------------------------------------------
# The report: placement, and the guarantee that lets this ship
# --------------------------------------------------------------------------------------


def test_a_workshop_with_no_custom_sections_gets_the_identical_template_object():
    """The guarantee the 485 KB Kotlin pin depends on.

    ``report_templates_pin.json`` is a by-value comparison covering 38 calls of
    ``apply_report_settings``, and it can only be regenerated by a script run inside the API
    container. ``is`` and not ``==``: the template must be returned untouched, not rebuilt equal.
    """
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
    """Inserting each block immediately after the stage section REVERSED them.

    Two sections asked at stage 1 came out 2, 1 — and the sort order is the one thing the placement
    rule exists to decide, so it would have been read as the ordering not working at all rather than
    as an off-by-one.
    """
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
    """PHOTO_CATALOGUE prints three of the twenty-two stages, and a designer's questions asked at one
    of the other nineteen still have to reach the document."""
    base = get_template("PHOTO_CATALOGUE")
    shaped = apply_report_settings(base, None, custom_sections=[_section(_field())])
    spliced = [s for s in shaped.sections if s.special is SpecialSection.CUSTOM_SECTION]
    assert len(spliced) == 1
    assert spliced[0].page_break_before is True
    assert shaped.sections.index(spliced[0]) == len(shaped.sections) - 1


def test_a_section_on_an_excluded_stage_is_not_orphaned_mid_narrative():
    """Spliced AFTER the exclusion filtering, so it cannot be inserted behind a section that is then
    removed from under it — which would leave the designer's questions in the middle of somebody
    else's narrative with no heading to say what stage they belong to."""
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
    """A section that matches no branch renders NOTHING, silently — the omission failure this whole
    pipeline keeps having to fix. Refused where the template is built instead."""
    with pytest.raises(ValueError, match="custom_key"):
        TemplateSection(special=SpecialSection.CUSTOM_SECTION)
    with pytest.raises(ValueError, match="custom_key"):
        TemplateSection(special=SpecialSection.COMPLETENESS, custom_key="extra")


# --------------------------------------------------------------------------------------
# The report: what a section actually draws
# --------------------------------------------------------------------------------------


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
    """The builder's own editorial rule: a gap in the record has to be visible AS a gap."""
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
    """Both are in the document, and the old one says it is no longer asked. That is what makes the
    record explicable a year later to somebody who was not there."""
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
    """Not even the heading, so a report of a workshop that has not got there is the report it
    would have been."""
    assert custom_section_blocks_standalone(_item(
        fields=(CustomReportField(key="shed", label="Shed", type="TEXT"),), values={}
    )) == ()


def test_a_choice_prints_the_designers_own_label_and_never_the_token():
    """``format_value`` resolves an enum through the shared ``ENUMS`` table, which a designer's list
    is deliberately not in — unresolved, a ministry document would read "TIE_AND_DYE"."""
    field = CustomReportField(
        key="dye", label="Dye", type="ENUM",
        options=(("NATURAL", "Natural dye"), ("CHEMICAL", "Chemical dye")),
    )
    assert display_value(field, "NATURAL") == "Natural dye"
    assert display_value(field, "UNKNOWN_TOKEN") == "UNKNOWN_TOKEN"


def test_a_designers_question_survives_a_round_trip_and_reaches_a_built_report():
    """**A MODULE WITH NO CALL SITE IS NOT A FEATURE**, and this is the test that says otherwise.

    The transcript annexure was a complete, tested module with no branch in ``ReportBuilder.build``,
    so every report ever generated dropped it in silence while three surfaces told the designer the
    office's copy would carry it. This walks the whole chain the way a real generate does — the
    answers coerced by the answer path, attached to the workshop data, the template shaped by the
    single arbiter, and the document built — and requires the answer to be in the document at the
    end of it.
    """
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
    # The designer's own option LABEL, never the token they stored it under.
    assert "Natural dye" in text
    assert "NATURAL\"" not in text


class _Row:
    """One stored ``DwStageEntry``, in the four attributes every reader of one uses."""

    def __init__(self, stage_key, entity_key, data):
        self.id, self.stageKey, self.entityKey, self.data = "r1", stage_key, entity_key, data
        self.deletedAt = None


async def test_the_annexure_and_the_readiness_screen_count_one_stage_the_same_way(monkeypatch):
    """**One workshop, one arithmetic — asserted across the two loaders, not within one.**

    The report re-scores every stage at render time off the sections attached to the workshop data;
    the readiness screen scores the same stage off the definition rows. They read the SAME
    ``stage_completeness``, so the only way they can disagree is by handing it different fields — and
    they did: a retired section's field rows are usually still ``isActive``, so the report counted a
    required question of a section nobody is being asked while ``workshop_completeness`` did not. The
    document then printed an outstanding count for a stage the screen beside it called complete,
    which is the defect this repository has already shipped once (144/144 against "Not recorded."
    printed thirty-six times).
    """
    from app.services import custom_sections as service
    from app.services.design_workshops import attach_report_custom_sections, workshop_completeness
    from app.services.report_builder import WorkshopData
    from app.services.report_custom_sections import custom_scoring

    definition = CustomDefinition(sections=(
        CustomSectionSpec(
            key="extra", title="Loom shed", stage_key=STAGE, id="sec1", retired=True,
            # Still isActive on its own row: retiring the SECTION is what stopped it being asked.
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
    # The one live requirement is outstanding; the retired section's is not asked any more.
    assert "Shed condition" in in_the_document.missing
    assert "How many looms?" not in in_the_document.missing


async def test_a_retired_section_answered_zero_still_reaches_the_document(monkeypatch):
    """Nought looms is a finding, and in a cluster where the looms were sold it is the finding.

    The first version asked whether the stored values were TRUTHY, so a retired section whose only
    answer was ``0`` was dropped out of the report entirely — recorded fieldwork, absent from the
    document, with nothing anywhere saying it had been left out.
    """
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
    # …and a retired section nobody ever answered still prints nothing at all.
    blank = WorkshopData(workshop_id="w1", title="Ikat workshop")
    await attach_report_custom_sections(blank, [_Row(STAGE, CUSTOM_ENTITY_KEY, {})], "w1")
    assert custom_sections_of(blank) == ()


async def test_the_warning_names_only_the_sections_the_document_really_leaves_out(monkeypatch):
    """**THE REGRESSION: the warning fired for exactly the sections the renderer DOES print.**

    Everything in ``items`` is attached and spliced into the template unconditionally; whether a
    section prints is decided solely by ``append_custom_section``, which appends nothing when
    ``has_content`` is false. ``has_content`` is true for an answered section OR one carrying a
    live required field — deliberately, so an unanswered required question prints "Not recorded."
    and its absence is visible in the document. The warning asked a DIFFERENT question, ``not
    answered_count and any(not f.retired …)``, which is true for precisely that class.

    So a designer who added "Dye bath log" with one required question and did not reach it got a
    .docx containing the heading and "Dye source — Not recorded.", beside a sentence saying the
    block was not in the file. They then either hunt for a bug that is not there, or submit the
    document believing the ministry's copy does not carry the empty block. The warning exists to
    tell "the feature is broken" apart from "we did not get to those questions"; as written it
    manufactured the first.

    Both directions are asserted, because a warning that never fires is the other way to pass.
    """
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

    # The renderer's own answer, asked directly, so the test cannot drift from the rule it pins.
    assert custom_section_of(data, "dye").has_content is True
    assert custom_section_of(data, "notes").has_content is False

    assert len(warnings) == 1
    assert "Shed notes" in warnings[0], "the all-optional block really is absent from the file"
    assert "Dye bath log" not in warnings[0], (
        "this one prints its heading and 'Not recorded.' — saying it is missing sends the "
        "designer looking for a bug that is not there"
    )


async def _resolved(value):
    """The definition, already loaded. A coroutine because the loader it stands in for is one."""
    return value


def test_a_workshop_with_no_custom_sections_builds_the_document_it_always_did():
    """The other half of the guarantee: this feature is invisible to every workshop without one."""
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
    """One formatter, so the two halves of one document cannot disagree about how a rupee is
    written."""
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
