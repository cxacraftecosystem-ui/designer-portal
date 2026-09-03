"""The cascading-picker declarations, and the registry rule that keeps them resolvable.

A REF field that a designer picks from carries three things beyond the model it points at: how
wide the picker casts (``ref_scope``), which other field on the same row narrows it
(``ref_filter_by``), and the text fields the chosen record fills in. Every one of those is
silent when it is wrong — a broken cascade does not error, it merely stops narrowing — so the
rules are enforced by :func:`validate_registry` and asserted here.

Nothing in this file touches a database. The resolver that reads one is in
``test_reference_resolver.py``.
"""

import re

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


def test_the_hydration_mapping_crosses_the_wire_with_the_field():
    """WHICH BOX EACH OF THE RECORD'S VALUES GOES IN, said by the server rather than guessed.

    The clients fill the row in at the keyboard so a designer who picks an artisan does not watch
    nine empty boxes stay empty until Save. Deriving the mapping client-side by matching key names
    is the obvious shortcut and is actively wrong: on `existingProduct` the reference's `name` is
    the ARTISAN's under `artisanRef` and the PRODUCT's under `productRef`, and the entity has a
    `name` of its own that means the product — so a name-matching client writes a participant's
    name into a ministry report's product table, and the only-fill-blanks rule then refuses to
    correct it at save.
    """
    artisan_on_product = field_to_dict(_field("existingProduct", "artisanRef"), "existingProduct")
    assert artisan_on_product["refHydration"] == {"name": "artisanName"}

    product_on_product = field_to_dict(_field("existingProduct", "productRef"), "existingProduct")
    assert product_on_product["refHydration"]["name"] == "name"
    assert product_on_product["refHydration"]["photo"] == "productPhotos"


def test_a_field_that_hydrates_nothing_publishes_nothing():
    """Absent rather than empty, so a client can fail closed on the absence. A missing entry costs
    one retyped box; a guessed one costs a wrong value nobody can see is wrong."""
    sketch = field_to_dict(_field("prototype", "sketchRef"), "prototype")
    assert "refHydration" not in sketch
    # And a field serialised without its entity gets no mapping either: the table is keyed by
    # "entityKey.fieldKey" because field keys are unique only within an entity.
    assert "refHydration" not in field_to_dict(_field("existingProduct", "artisanRef"))


# --------------------------------------------------------------------------------------
# The hydration table, and the rename that would empty it in silence
# --------------------------------------------------------------------------------------


def test_every_hydration_target_is_a_real_field_of_its_entity():
    """The check that makes a rename fail loudly instead of quietly.

    `hydrate_entries` resolves each target with `entity.field(target_key)` and SKIPS what it
    cannot resolve — no error, no log — so a mapping naming a field somebody renamed copies
    nothing, on every save, for ever, and the first symptom is a submitted document with a hole in
    a table. `validate_registry` is asserted sound above; this names the rule so a future edit
    that removes it fails here rather than in a ministry office.
    """
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
    """Only a REF field hydrates a row. A mapping hung off a text box would never run at all —
    `hydrate_entries` only looks at REF fields — so it would sit in the table reading as coverage
    the report does not have."""
    monkeypatch.setattr(
        stage_schema, "REFERENCE_HYDRATION",
        {"participant.name": {"name": "name"}},
    )
    assert any("only a REF field" in p for p in validate_registry()), validate_registry()


def test_the_documented_process_now_fills_more_than_its_name():
    """The gap this lane closed. Stage 5's process table is one of the report's substantive
    narrative sections and it copied ONE field while its siblings copied six and eight."""
    mapping = stage_schema.REFERENCE_HYDRATION["processStep.processRef"]
    assert mapping == {"name": "name", "notes": "description",
                       "productName": "documentedFor"}
    entity = next(e for _s, e in all_entities() if e.key == "processStep")
    # Both new targets must PRINT, or the copy is a field that is hydrated and never seen.
    assert entity.field("description").report_role is not ReportRole.HIDDEN
    assert entity.field("documentedFor").report_role is not ReportRole.HIDDEN


def test_a_prototype_is_not_the_product_it_derives_from():
    """DECIDED, NOT OVERLOOKED. `prototype.productRef` copies the product's name into "Developed
    from" and deliberately nothing else: a prototype is defined by how it DIFFERS from its
    source, so `materials` is a required answer about what this object is actually made of, and
    the product's `price` is a SELLING price with nothing on a prototype to receive it but the two
    COST fields. The only-fill-blanks rule would leave any such copy standing, indistinguishable
    from an answer the designer gave.

    The same reasoning caps `existingProduct.artisanRef` at the name: that row documents a
    PRODUCT, the entity declares no box for a village or a phone, and the roster row at stage 3
    already holds both against the same artisan.
    """
    assert stage_schema.REFERENCE_HYDRATION["prototype.productRef"] == {"name": "productName"}
    assert stage_schema.REFERENCE_HYDRATION["existingProduct.artisanRef"] == {"name": "artisanName"}


def _web_hydration_table() -> dict[str, dict[str, str]] | None:
    """`DW_REFERENCE_HYDRATION` as `frontend/lib/designWorkshops.ts` declares it, or None.

    Parsed rather than imported because there is no TypeScript here. The shape is a flat object
    literal of object literals with string values, so a scan is enough and a change that made it
    anything else would fail the assertion below rather than pass silently — which is the right
    direction for a guard whose whole job is to notice that the two copies stopped matching.
    """
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
    """THE DRIFT THIS FEATURE HAS ALREADY PAID FOR ONCE, guarded on the surface that still copies.

    Android hydrated by matching key names while the server mapped, and the two disagreed
    permanently on the same pick: choosing the artisan on a stage 6 row wrote her name into the
    PRODUCT column, and the only-fill-blanks rule then refused to correct it at save. Android now
    reads the server's `refHydration` off the schema, so it cannot drift again. The web still keeps
    a hand-maintained copy — deliberately, and it is currently correct — which makes it the one
    remaining place the same defect can be reintroduced by a widening that updates one file.

    A MISSING web entry costs one retyped box the server fills at save; a WRONG one costs a value
    nobody can see is wrong. So this asserts equality, not containment.
    """
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
        # THE STAGE-5 SINGLETON'S OWN PROCESS PICKER, which this list has been missing since the
        # singleton was given a ref field: `processStep.processRef` was here and its sibling on
        # `traditionalProcess` was not, so a widening that touched one and not the other was
        # invisible to the one test whose subject is "the records the requirement named".
        ("traditionalProcess", "processRef", "Process"),
        # The two parents added with the process cascade. Four product pickers exist now; these two
        # are the ones whose child is a process rather than a prototype.
        ("traditionalProcess", "productRef", "ProductDocumentation"),
        ("processStep", "productRef", "ProductDocumentation"),
        # The sixth external reference model, on stage 6's new artisan-baseline singleton.
        ("artisanBaseline", "interviewRef", "QuestionnaireInterview"),
        # The seventh, on stage 7's survey plan (2026-09-03). The INSTRUMENT, not a sitting on it:
        # the plan could previously say nothing about the questionnaire the app already held except
        # by retyping its questions into the prose box beside this one.
        ("surveyPlan", "questionnaireRef", "Questionnaire"),
    ],
)
def test_the_records_the_requirement_named_are_selectable(entity_key, field_key, model):
    spec = _field(entity_key, field_key)
    assert spec.type is FieldType.REF
    assert spec.ref_model == model


def test_the_survey_plan_links_the_instrument_without_disturbing_the_prose_beside_it():
    """Stage 7's questionnaire link is ADDITIVE, and that is the whole of why it is safe.

    ── WHAT RE-TYPING THE PROSE FIELD WOULD HAVE COST ────────────────────────────────────────────

    ``surveyPlan.questionnaire`` is RICH_TEXT, BASIC and required, and every workshop that has
    reached stage 7 has a ``{"blocks": […]}`` document stored under it. Re-typing that key as a REF
    would hand ``coerce_value`` that dict on a branch that calls ``clean_text`` — the stored plan
    stringified into a report as literal JSON, which is the exact defect ``format_value``'s
    RICH_TEXT arm exists to record having shipped once. So the link is a NEW key, the prose is
    untouched, and a plan may legitimately carry both: the prose is the plan and the link is the
    instrument.

    THE LINK IS OPTIONAL AND STANDARD, deliberately. A designer who has uploaded no questionnaire —
    the ordinary case for a workshop planned in a courtyard — must not be blocked from submitting a
    stage by a picker over a table they have nothing in.
    """
    ref = _field("surveyPlan", "questionnaireRef")
    prose = _field("surveyPlan", "questionnaire")

    assert prose.type is FieldType.RICH_TEXT and prose.required and prose.tier is Tier.BASIC
    assert ref.ref_scope == REF_SCOPE_ALL
    assert not ref.required and ref.tier is not Tier.BASIC
    # HIDDEN, like every other picker in the registry: the id is a join key and the report prints
    # the name box the pick fills in.
    assert ref.report_role is ReportRole.HIDDEN
    assert field_to_dict(ref, "surveyPlan")["refHydration"] == {"name": "questionnaireName"}
    assert _field("surveyPlan", "questionnaireName").type is FieldType.TEXT


def test_a_record_backed_multi_enum_declares_a_picker_the_wire_can_carry():
    """``field_to_dict`` publishes the picker for a MULTI_ENUM exactly as it does for a REF.

    THE HALF THAT COULD HAVE BEEN MISSED. `field_to_dict` keys ``refModel``/``refScope`` off
    ``f.ref_model`` and never off the field TYPE, so this worked the day the registry first declared
    one — but nothing asserted it, and a "tidy" that moved those two lines inside a
    ``type is FieldType.REF`` guard would have left both clients rendering a closed dropdown with no
    options and no way to know why. The clients dispatch on the type and read the model; the wire
    has to carry both or the promotion is invisible.
    """
    for entity_key in ("processStep", "prototype"):
        out = field_to_dict(_field(entity_key, "toolsUsed"), entity_key)
        assert out["type"] == "MULTI_ENUM", entity_key
        assert out["refModel"] == "ToolDocumentation", entity_key
        assert out["refScope"] == REF_SCOPE_ALL, entity_key
        # NO `options` AND NO `enum`, which is what tells a client to draw the record arm rather
        # than the vocabulary one — Android branches on `refModel`, and its hydration coercion
        # skips the allow-list precisely because `options` is empty here.
        assert "options" not in out and "enum" not in out, entity_key
        # Nothing hydrates from a multi-select: five tools have five names and the row has one box.
        assert "refHydration" not in out, entity_key


def test_the_two_artisan_fed_product_pickers_cascade_from_the_artisan_on_their_own_row():
    """RENAMED FROM `test_both_product_pickers_cascade_from_the_artisan_on_their_own_row`, which
    became a false statement rather than merely a stale name: there are FOUR product pickers now and
    only two of them hang off an artisan. The other two are the parents of the process pickers below.
    """
    for entity_key in ("existingProduct", "prototype"):
        spec = _field(entity_key, "productRef")
        assert spec.ref_filter_by == "artisanRef", entity_key


def test_both_process_pickers_cascade_from_the_product_on_their_own_row():
    """`Process.productId` is NON-NULLABLE — a process reaches a workshop only through its parent
    product — so one product has many processes and the cascade is the natural one.

    THE SIBLING MUST BE A REF, AND THAT IS THE HALF `validate_registry` CANNOT CHECK. It only
    requires that the field named by `ref_filter_by` EXIST on the entity, so
    `ref_filter_by="documentedFor"` — the `fromref` TEXT box holding the product's NAME, sitting a
    few lines away in both entities — would pass validation and then send a product name to the
    endpoint as `filterBy`, where it is treated as an id and matches nothing. An empty picker that
    reads as an empty repository is the exact failure that validation block was written to prevent,
    arriving through the one door it leaves open. So the type is asserted here.
    """
    for entity_key in ("traditionalProcess", "processStep"):
        spec = _field(entity_key, "processRef")
        assert spec.ref_filter_by == "productRef", entity_key
        parent = _field(entity_key, "productRef")
        assert parent.type is FieldType.REF, entity_key
        assert parent.ref_model == "ProductDocumentation", entity_key
        # A wider parent than the child it narrows would be incoherent: the product picker must not
        # offer a product whose processes the process picker would then refuse to show.
        assert parent.ref_scope == spec.ref_scope == REF_SCOPE_WORKSHOP, entity_key
        # HIDDEN is the only role with no layout consequence. `documentedFor` already prints the
        # product name, so a printing `productRef` would be a second, possibly-disagreeing statement
        # of one fact — and on `processStep` the five declared column widths already total exactly
        # 100, so a sixth TABLE_COLUMN would re-lay-out tables in submitted documents.
        assert parent.report_role is ReportRole.HIDDEN, entity_key
        # Tier S and never BASIC: `EntityForm.tsx` picks the collection's bulk multi-select as THE
        # field that is `REF && refModel && !refFilterBy && tier === "BASIC"`, and a BASIC parent
        # here would turn a picker into a "tick thirty records and make thirty rows" control.
        assert parent.tier is not Tier.BASIC, entity_key


def test_the_cascade_parent_is_never_the_text_box_that_holds_the_parents_name():
    """Stated once over the whole registry, because the trap is not specific to stage 5.

    Every `ref_filter_by` in the registry must name a REF field. `validate_registry` checks only
    that the named field exists, and the difference between "exists" and "is a REF" is a picker that
    silently sends a NAME where an id is expected and shows nothing at all.
    """
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
        # THE CASCADE'S PARENT COMES BEFORE ITS CHILD, and the child before the boxes it fills.
        # `awaitingCascade` fetches NOTHING while the parent is blank, so a parent declared below the
        # picker it narrows is a picker that is dead until the designer scrolls back up.
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
