import dataclasses
import json
import pathlib
import re
from contextlib import contextmanager
from dataclasses import replace
from enum import Enum

import pytest

import app.services.stage_definitions  # noqa: F401
from app.services import stage_schema
from app.services.stage_schema import (
    ENUMS,
    PROMOTED_COLUMNS,
    STAGES,
    Cardinality,
    FieldSpec,
    FieldType,
    ReportRole,
    TextFormat,
    Tier,
    all_entities,
    coerce_value,
    enum_label,
    field_to_dict,
    promoted_values,
    registry_to_dict,
    registry_version,
    stage_completeness,
    validate_entry,
    validate_registry,
)


@contextmanager
def _swapped_field(original: FieldSpec, substitute: FieldSpec):
    holder = next(
        e for stage in STAGES for e in stage.entities if original in e.fields
    )
    before = holder.fields
    object.__setattr__(
        holder, "fields", tuple(substitute if f is original else f for f in before)
    )
    try:
        yield
    finally:
        object.__setattr__(holder, "fields", before)


@contextmanager
def _swapped_attrs(spec, **attrs):
    before = {name: getattr(spec, name) for name in attrs}
    for name, value in attrs.items():
        object.__setattr__(spec, name, value)
    try:
        yield
    finally:
        for name, value in before.items():
            object.__setattr__(spec, name, value)


def test_registry_is_sound():
    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_all_twenty_two_stages_are_present_and_numbered_once():
    assert len(STAGES) == 22
    assert sorted(s.number for s in STAGES) == list(range(1, 23))


def test_every_stage_has_at_least_one_entity():
    for spec in STAGES:
        assert spec.entities, spec.key


def test_no_stage_declares_two_singletons():
    for spec in STAGES:
        singletons = [e for e in spec.entities if e.cardinality is Cardinality.SINGLETON]
        assert len(singletons) <= 1, spec.key


def test_entity_keys_are_globally_unique():
    keys = [e.key for _s, e in all_entities()]
    assert len(keys) == len(set(keys))


def test_only_basic_fields_are_required():
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.required:
                assert f.tier is Tier.BASIC, f"{entity.key}.{f.key} is {f.tier.value}"


def test_every_enum_field_names_a_canonical_list():
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.type is FieldType.ENUM:
                assert f.enum in ENUMS, f"{entity.key}.{f.key} -> {f.enum!r}"
            elif f.type is FieldType.MULTI_ENUM:
                where = f"{entity.key}.{f.key}"
                assert bool(f.enum) != bool(f.ref_model), (
                    f"{where} names {'both an enum and a ref_model' if f.enum else 'neither'}; "
                    "a MULTI_ENUM's options come from a vocabulary or from a record type"
                )
                if f.enum:
                    assert f.enum in ENUMS, f"{where} -> {f.enum!r}"


def test_the_two_promoted_tool_boxes_are_record_backed_and_cannot_brick_a_stage():
    promoted = [
        (entity.key, f)
        for _spec, entity in all_entities()
        for f in entity.fields
        if f.key == "toolsUsed"
    ]
    assert [key for key, _f in promoted] == ["processStep", "prototype"]
    for key, f in promoted:
        assert f.type is FieldType.MULTI_ENUM, key
        assert f.ref_model == "ToolDocumentation", key
        assert f.ref_scope == "ALL", key
        assert not f.enum, key
        assert not f.required and f.tier is not Tier.BASIC, (
            f"{key}.toolsUsed is a closed list AND required — a workshop with no documented tool "
            "could never complete this stage"
        )


def test_every_caption_points_at_a_media_field_in_its_own_entity():
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.caption_for:
                target = entity.field(f.caption_for)
                assert target is not None, f"{entity.key}.{f.key}"
                assert target.type.is_media, f"{entity.key}.{f.key}"


def test_table_column_width_hints_are_sane():
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.column_width_pct:
                assert 0 < f.column_width_pct <= 100, f"{entity.key}.{f.key}"


def test_promoted_columns_all_resolve_and_target_distinct_columns():
    seen: dict[str, str] = {}
    for path, column in PROMOTED_COLUMNS.items():
        entity_key, _, field_key = path.partition(".")
        entity = next((e for _s, e in all_entities() if e.key == entity_key), None)
        assert entity is not None, path
        assert entity.field(field_key) is not None, path
        assert column not in seen, f"{column} written by {seen.get(column)} and {path}"
        seen[column] = path


def test_promoted_values_is_scoped_to_its_entity():
    assert promoted_values("workshopSetup", {"startDate": "2026-02-10"}) == {
        "startDate": "2026-02-10"
    }
    assert promoted_values("prototype", {"startDate": "2026-02-14"}) == {}


def test_registry_version_is_stable_and_content_addressed():
    first = registry_version()
    assert first == registry_version()
    assert len(first) == 16


def test_the_version_changes_when_a_derivation_changes():
    baseline = registry_version()
    spec = next(
        f
        for stage in STAGES
        for entity in stage.entities
        for f in entity.fields
        if f.derived_kind
    )

    for mutation in ({"derived_kind": ""}, {"derived_kind": "SUM"}, {"derived_from": ()}):
        changed = replace(spec, **mutation)
        if changed.derived_kind == spec.derived_kind and changed.derived_from == spec.derived_from:
            continue
        with _swapped_field(spec, changed):
            assert registry_version() != baseline, (
                f"changing {mutation} left the digest unchanged, so a client holding the old "
                f"derivation would never be told to refetch"
            )

    assert registry_version() == baseline


def test_the_version_changes_when_a_hydration_mapping_changes(monkeypatch):
    baseline = registry_version()
    original = stage_schema.REFERENCE_HYDRATION
    path = "processStep.processRef"

    widened = dict(original)
    widened[path] = {**original[path], "preProcessAvailable": "performedBy"}

    retargeted = dict(original)
    retargeted[path] = {**original[path], "notes": "problems"}

    narrowed = dict(original)
    narrowed[path] = {"name": "name"}

    for label, table in (("widened", widened), ("retargeted", retargeted),
                         ("narrowed", narrowed)):
        monkeypatch.setattr(stage_schema, "REFERENCE_HYDRATION", table)
        assert registry_version() != baseline, (
            f"a {label} hydration mapping left the digest unchanged, so every phone holding the "
            f"old one would go on filling rows in by it and never be told to refetch"
        )
        monkeypatch.undo()

    assert registry_version() == baseline


def test_registry_serialises_and_omits_defaults():
    payload = registry_to_dict()
    assert len(payload["stages"]) == 22
    assert payload["version"] == registry_version()
    sample = payload["stages"][0]["entities"][0]["fields"][0]
    assert "help" not in sample or sample["help"]


def test_enum_label_falls_back_rather_than_raising():
    assert enum_label("PRODUCT_CATEGORY", "SAREE") == "Saree"
    assert enum_label("PRODUCT_CATEGORY", "SOMETHING_NEW") == "SOMETHING_NEW"
    assert enum_label("NO_SUCH_ENUM", "X") == "X"


def test_the_core_chain_is_traversable():
    refs = {
        (entity.key, f.ref_model)
        for _s, entity in all_entities()
        for f in entity.fields
        if f.type is FieldType.REF
    }
    assert ("prototype", "DwSketch") in refs
    assert ("prototypeIteration", "DwPrototype") in refs
    assert ("prototypeValidation", "DwPrototype") in refs
    assert ("finalProduct", "DwPrototype") in refs
    assert ("costSheet", "DwFinalProduct") in refs
    assert ("followUp", "DwFinalProduct") in refs


def test_stage_one_drives_the_report_cover():
    setup = next(s for s in STAGES if s.key == "WORKSHOP_SETUP").singleton
    cover = [f for f in setup.fields if f.report_role is ReportRole.COVER_FIELD]
    assert len(cover) >= 8
    assert {"craftName", "clusterName", "designerName"} <= {f.key for f in cover}


_BUILD_TIME_COMMENTARY = (
    "reviewer",
    "annotator",
    "source document",
    "specification says",
    "phase 2",
    "phase 3",
    "phase 4",
    "plug in",
    "plug-in",
    "for now",
    "later to be discussed",
    "we may consider",
    "deferred to",
    "at the request of",
    "phase 1",
    "phase 5",
    "we may",
    "we might",
    "later we can",
    "to be discussed",
    "tbd",
    "todo",
    "fixme",
    "out of scope",
    "post-mvp",
    "stakeholder",
    "product owner",
)


DATA_DIR = pathlib.Path(stage_schema.__file__).resolve().parent.parent / "data"


def _walk_json(node, path):
    if isinstance(node, dict):
        for key, value in node.items():
            yield from _walk_json(value, f"{path}.{key}" if path else str(key))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _walk_json(value, f"{path}[{index}]")
    elif isinstance(node, str):
        yield path, node


def _client_facing_registry_strings():
    yield from _walk_json(registry_to_dict(), "")


def test_no_client_facing_registry_string_carries_build_time_commentary():
    offenders = [
        (where, text, term)
        for where, text in _client_facing_registry_strings()
        if text
        for term in _BUILD_TIME_COMMENTARY
        if term in text.casefold()
    ]
    assert not offenders, "build-time commentary is reaching a designer's screen:\n" + "\n".join(
        f"  {where}: …{term}… in {text!r}" for where, text, term in offenders
    )


def _report_template_strings():
    from app.services import report_templates

    def walk(obj, path, seen):
        if isinstance(obj, str):
            yield path, obj
            return
        if isinstance(obj, (int, float, bool, type(None), Enum, bytes)) or id(obj) in seen:
            return
        seen.add(id(obj))
        if dataclasses.is_dataclass(obj):
            for f in dataclasses.fields(obj):
                yield from walk(getattr(obj, f.name), f"{path}.{f.name}", seen)
        elif isinstance(obj, dict):
            for key, value in obj.items():
                yield from walk(value, f"{path}[{key!r}]", seen)
        elif isinstance(obj, (list, tuple, set, frozenset)):
            for index, value in enumerate(obj):
                yield from walk(value, f"{path}[{index}]", seen)

    yield from walk(report_templates.TEMPLATES, "TEMPLATES", set())


def test_no_prose_outside_the_registry_carries_build_time_commentary_either():
    channels: list[tuple[str, list[tuple[str, str]]]] = [
        ("report templates", list(_report_template_strings())),
        (
            "questionnaire bank",
            list(
                _walk_json(
                    json.loads(
                        (DATA_DIR / "questionnaire_questions.json").read_text(encoding="utf-8")
                    ),
                    "questionnaire_questions.json",
                )
            ),
        ),
        (
            "craft vocabulary",
            [
                (f"craft_vocabulary.txt:{number}", line.strip())
                for number, line in enumerate(
                    (DATA_DIR / "craft_vocabulary.txt").read_text(encoding="utf-8").splitlines(), 1
                )
                if line.strip()
            ],
        ),
    ]

    for label, items in channels:
        assert items, f"{label} yielded no strings — this test has gone vacuous, not clean"

    offenders = [
        (label, where, term, text)
        for label, items in channels
        for where, text in items
        for term in _BUILD_TIME_COMMENTARY
        if term in text.casefold()
    ]
    assert not offenders, (
        "build-time commentary is reaching a designer or a ministry office:\n"
        + "\n".join(
            f"  [{label}] {where}: …{term}… in {text!r}" for label, where, term, text in offenders
        )
    )


def test_no_registry_prose_shows_a_designer_the_source_it_was_written_from():
    prose = [(where, text) for where, text in _client_facing_registry_strings() if " " in text]
    assert len(prose) > 400, (
        f"only {len(prose)} prose strings were found — the walk or the space heuristic has broken "
        "and this test has gone vacuous, not clean"
    )

    identifier = re.compile(r"(?<![A-Za-z0-9`])[a-z]{3,}_[a-z_]{3,}(?![A-Za-z0-9])")
    offenders = [
        (where, text)
        for where, text in prose
        if "`" in text or identifier.search(text)
    ]
    assert not offenders, (
        "registry prose is showing a designer the source it was written from — name the label on "
        "screen instead:\n" + "\n".join(f"  {where}: {text!r}" for where, text in offenders)
    )


def test_a_stage_note_is_either_absent_or_has_something_in_it():
    for stage in STAGES:
        assert stage.notes == stage.notes.strip(), (
            f"{stage.key}.notes is whitespace-padded or whitespace-only. The web renders it verbatim "
            'in a bordered, tinted box — " " is truthy there, so it paints an EMPTY box — while '
            'Android\'s isNotBlank() renders nothing. Use notes="" to mean "no note".'
        )


def test_the_reviewers_marginal_comments_are_kept_in_the_code_and_never_serialised():
    populated = [
        f
        for stage in STAGES
        for entity in stage.entities
        for f in entity.fields
        if f.phase_note
    ]
    assert len(populated) >= 15, (
        "the source document's marginal comments have been thinned out of the code — they are the "
        "record of what was deferred and why, and losing them is the defect the notes cleanup was "
        "explicitly not allowed to trade for"
    )

    registry = registry_to_dict()
    for stage in registry["stages"]:
        for entity in stage["entities"]:
            for field in entity["fields"]:
                assert "phaseNote" not in field, (
                    f"{stage['key']}.{entity['key']}.{field['key']} publishes phase_note — that is "
                    "the reviewer's margin note on a designer's screen, which is exactly the defect "
                    "the stage notes were cleaned up to end"
                )


def test_prose_edits_do_not_move_the_registry_version():
    baseline = registry_version()
    stage = next(s for s in STAGES if s.notes)
    field = stage.entities[0].fields[0]

    with _swapped_attrs(stage, notes="Something else entirely."):
        assert registry_version() == baseline, "a stage note moved the digest"
    with _swapped_attrs(stage, purpose="A different purpose."):
        assert registry_version() == baseline, "a stage purpose moved the digest"
    with _swapped_attrs(field, help="Different help.", label="Different label."):
        assert registry_version() == baseline, "a field label or help text moved the digest"

    assert registry_version() == baseline



def _f(key="x", label="X", type=FieldType.TEXT, **kw) -> FieldSpec:
    return FieldSpec(key=key, label=label, type=type, **kw)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("1,250.10", "1250.10"), (1250.1, "1250.10"), ("₹500", "500.00"), ("0", "0.00")],
)
def test_money_is_stored_as_a_two_place_string(raw, expected):
    spec = _f(type=FieldType.MONEY, min_value=0)
    assert coerce_value(spec, raw) == (expected, None)


def test_money_rejects_nonsense_and_out_of_range():
    spec = _f(label="Material cost", type=FieldType.MONEY, min_value=0)
    assert coerce_value(spec, "abc")[1] is not None
    assert coerce_value(spec, -5)[1] == "Material cost must be at least 0"


@pytest.mark.parametrize(
    "raw",
    ["NaN", "nan", "Infinity", "inf", "-Infinity", "1e400", "1" + "0" * 400, float("nan")],
)
@pytest.mark.parametrize("kind", [FieldType.MONEY, FieldType.DECIMAL, FieldType.PERCENT])
def test_a_number_that_is_not_a_number_is_refused(kind, raw):
    value, error = coerce_value(_f(label="Cost", type=kind, min_value=0), raw)
    assert value is None
    assert error == "Cost is not a valid number"


def test_a_real_number_still_passes_after_the_finiteness_guard():
    assert coerce_value(_f(type=FieldType.MONEY, min_value=0), "1650.00") == ("1650.00", None)
    assert coerce_value(_f(type=FieldType.DECIMAL), "4.5") == (4.5, None)
    assert coerce_value(_f(type=FieldType.PERCENT), 12) == (12.0, None)


def test_blank_is_accepted_and_becomes_none():
    for raw in ("", "   ", None):
        assert coerce_value(_f(), raw) == (None, None)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [(True, True), ("yes", True), ("1", True), ("NO", False), ("false", False)],
)
def test_bool_accepts_what_three_clients_actually_send(raw, expected):
    assert coerce_value(_f(type=FieldType.BOOL), raw) == (expected, None)


def test_bool_rejects_a_word_it_cannot_read():
    assert coerce_value(_f(type=FieldType.BOOL), "maybe")[1] is not None


def test_enum_membership_is_enforced():
    spec = _f(label="Category", type=FieldType.ENUM, enum="PRODUCT_CATEGORY")
    assert coerce_value(spec, "SAREE") == ("SAREE", None)
    assert coerce_value(spec, "NOPE")[1] is not None


def test_multi_enum_rejects_any_unknown_member():
    spec = _f(type=FieldType.MULTI_ENUM, enum="MARKET_CHANNEL")
    assert coerce_value(spec, ["EMPORIUM", "ONLINE"]) == (["EMPORIUM", "ONLINE"], None)
    assert coerce_value(spec, ["EMPORIUM", "NOPE"])[1] is not None


def test_a_record_backed_multi_enum_keeps_both_shapes_its_column_holds():
    spec = _f(type=FieldType.MULTI_ENUM, label="Tools used", ref_model="ToolDocumentation")

    ids = ["cmsik2jg8000eh8xc1lcy661a", "cmsjb6qaq01ar4otfh1p0hm1a"]
    assert coerce_value(spec, ids) == (ids, None)

    assert coerce_value(spec, ["pit loom", "बुनाई"]) == (["pit loom", "बुनाई"], None)
    assert coerce_value(spec, ["pit loom", ids[0]]) == (["pit loom", ids[0]], None)

    assert coerce_value(_f(type=FieldType.MULTI_ENUM, enum="MARKET_CHANNEL"), ["NOPE"])[1] is not None

    assert coerce_value(spec, ["x"] * (stage_schema.DEFAULT_MAX_ITEMS + 1))[1] is not None
    assert "longer than" in coerce_value(spec, ["x" * (stage_schema.DEFAULT_MAX_ITEM_CHARS + 1)])[1]


def test_multi_value_field_rejects_a_scalar():
    assert coerce_value(_f(type=FieldType.TAGS), "not a list")[1] is not None


def test_a_multi_valued_field_is_bounded_in_both_directions():
    tags = _f(type=FieldType.TAGS, label="Tags")
    assert coerce_value(tags, ["a"] * stage_schema.DEFAULT_MAX_ITEMS)[1] is None
    assert coerce_value(tags, ["a"] * (stage_schema.DEFAULT_MAX_ITEMS + 1)) == (
        None, f"Tags may hold at most {stage_schema.DEFAULT_MAX_ITEMS} entries"
    )
    assert coerce_value(tags, ["x" * stage_schema.DEFAULT_MAX_ITEM_CHARS])[1] is None
    assert "longer than" in coerce_value(
        tags, ["ok", "x" * (stage_schema.DEFAULT_MAX_ITEM_CHARS + 1)]
    )[1]
    multi = _f(type=FieldType.MULTI_ENUM, enum="MARKET_CHANNEL", label="Channels")
    assert coerce_value(multi, ["EMPORIUM"] * 5_000)[1] is not None


def test_a_declared_bound_on_a_multi_field_is_no_longer_a_silent_no_op():
    capped = _f(type=FieldType.TAGS, label="Tags", max_items=2, max_length=4)
    assert coerce_value(capped, ["ikat", "silk"]) == (["ikat", "silk"], None)
    assert coerce_value(capped, ["ikat", "silk", "wool"]) == (
        None, "Tags may hold at most 2 entries"
    )
    assert coerce_value(capped, ["sambalpuri"]) == (
        None, "Tags: one entry is longer than 4 characters"
    )


def test_max_items_crosses_the_wire_only_once_a_field_declares_one():
    from app.services.stage_schema import field_to_dict

    assert "maxItems" not in field_to_dict(_f(type=FieldType.TAGS))
    assert field_to_dict(_f(type=FieldType.TAGS, max_items=12))["maxItems"] == 12


CAPPED_GALLERIES: dict[str, int] = {
    "motifPhotos": 25,
    "contemporaryMotifPhotos": 25,
    "lostCraftPhotos": 25,
}

FLOORED_GALLERIES: dict[str, int] = {
    "motifPhotos": 25,
    "contemporaryMotifPhotos": 25,
}


def test_the_capped_motif_galleries_refuse_the_twenty_sixth_photograph():
    background = _entity("clusterBackground")
    for key, cap in CAPPED_GALLERIES.items():
        gallery = background.field(key)
        assert gallery is not None, f"clusterBackground has no {key!r} field"
        assert gallery.type is FieldType.IMAGE_LIST, (
            f"{key} is {gallery.type.value}; max_items is inert on anything but a multi field, so "
            "the declaration would be published and enforce nothing"
        )
        assert gallery.max_items == cap, (
            f"{key} declares max_items={gallery.max_items}, not the owner's {cap}"
        )

        ids = [f"cm{n:023d}" for n in range(cap + 1)]

        stored, error = coerce_value(gallery, ids[:cap])
        assert error is None, f"the {cap}th photograph was refused: {error}"
        assert stored == ids[:cap], (
            f"the ceiling is inclusive — a cap of {cap} that refuses the {cap}th is an off-by-one "
            "the designer meets as a lost photograph"
        )

        stored, error = coerce_value(gallery, ids)
        assert error == f"{gallery.label} may hold at most {cap} entries", error
        assert stored is None, (
            f"{key} stored {len(stored or [])} of {len(ids)} entries: a silent truncation, which "
            "is the failure this cap must not become"
        )

    for key, cap in CAPPED_GALLERIES.items():
        published = field_to_dict(background.field(key), "clusterBackground")
        assert published["maxItems"] == cap, published


def test_exactly_three_fields_in_the_whole_registry_declare_a_cap():
    declared = {
        f"{entity.key}.{field.key}": field.max_items
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.max_items
    }
    assert declared == {
        f"clusterBackground.{key}": cap for key, cap in CAPPED_GALLERIES.items()
    }, (
        f"the set of fields declaring a cap is {declared}, not the three stage-4 galleries the "
        "owner stated a ceiling for. A cap that leaked from photos() refuses photographs on a stage "
        "nobody asked about; a cap that vanished silently restores DEFAULT_MAX_ITEMS and both "
        "clients stop showing the ceiling."
    )



def _stage(number: int):
    return next(s for s in STAGES if s.number == number)


def _media_ids(n: int) -> list[str]:
    return [f"cm{i:023d}" for i in range(n)]


def test_exactly_two_fields_in_the_whole_registry_declare_a_floor():
    declared = {
        f"{entity.key}.{field.key}": field.min_items
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.min_items
    }
    assert declared == {
        f"clusterBackground.{key}": floor for key, floor in FLOORED_GALLERIES.items()
    }, (
        f"the set of fields declaring a floor is {declared}, not the two motif galleries the owner "
        "stated one for. A floor that leaked from photos() makes a stage permanently incomplete "
        "with no error anywhere; a floor that vanished silently drops the owner's requirement."
    )

    background = _entity("clusterBackground")
    for key, floor in FLOORED_GALLERIES.items():
        published = field_to_dict(background.field(key), "clusterBackground")
        assert published["minItems"] == floor, published
    assert "minItems" not in field_to_dict(background.field("clusterPhotos")), (
        "an unfloored gallery published a minimum; emission must be conditional on declaration, "
        "exactly as maxItems' is"
    )


def test_a_short_gallery_still_saves():
    entity = _entity("clusterBackground")
    for key in FLOORED_GALLERIES:
        for enforce in (False, True):
            clean, errors = validate_entry(entity, {key: _media_ids(20)},
                                           enforce_required=enforce)
            assert key not in errors, (
                f"{key} was refused with 20 of 25 photographs (enforce_required={enforce}): "
                f"{errors[key]!r}. On Android that is twenty photographs dropped, not queued."
            )
            assert clean[key] == _media_ids(20), (
                f"{key} stored {len(clean.get(key, []))} of the 20 ids posted — a partial save "
                "must be kept whole"
            )

    stored, error = coerce_value(entity.field("motifPhotos"), _media_ids(1))
    assert error is None and stored == _media_ids(1), (error, stored)


def test_a_gallery_one_short_leaves_the_stage_incomplete():
    spec = _stage(4)
    full = {key: _media_ids(25) for key in FLOORED_GALLERIES}

    at_25 = stage_completeness(spec, full, {})
    short = dict(full, motifPhotos=_media_ids(24))
    at_24 = stage_completeness(spec, short, {})

    assert at_25.required_total == at_24.required_total, (
        "the denominator moved between 24 and 25 photographs; a stage whose required_total depends "
        "on its own answers has a percentage that cannot be reasoned about"
    )
    assert at_24.required_filled == at_25.required_filled - 1, (
        f"24 of 25 scored {at_24.required_filled}, 25 scored {at_25.required_filled}; the floor is "
        "not being counted"
    )
    assert "Traditional motif photographs (24 of 25)" in at_24.missing, at_24.missing
    assert not [m for m in at_25.missing if "Traditional motif" in m], at_25.missing

    assert not [m for m in at_24.missing if "Contemporary motif" in m], at_24.missing


def test_the_submit_gate_refuses_a_workshop_whose_gallery_is_one_short():
    spec = _stage(4)
    answered = {
        f.key: "Recorded."
        for e in spec.entities for f in e.fields if f.required
    }
    complete = dict(answered, **{key: _media_ids(25) for key in FLOORED_GALLERIES})
    assert stage_completeness(spec, complete, {}).is_complete, (
        "stage 4 is not complete with every required field answered and both galleries full — "
        f"outstanding: {stage_completeness(spec, complete, {}).missing}"
    )

    for key in FLOORED_GALLERIES:
        one_short = dict(complete, **{key: _media_ids(24)})
        score = stage_completeness(spec, one_short, {})
        assert not score.is_complete, (
            f"{key} holding 24 of 25 left stage 4 complete; the workshop could be submitted with "
            "a gallery the owner required 25 photographs in"
        )
        assert score.percent < 100, score.percent


def test_a_floor_is_refused_where_it_could_never_be_satisfied():
    gallery = _entity("clusterBackground").field("motifPhotos")

    with _swapped_field(gallery, replace(gallery, type=FieldType.TEXT, max_items=0)):
        assert any("min_items" in p and "TEXT" in p for p in validate_registry()), \
            validate_registry()

    with _swapped_field(gallery, replace(gallery, min_items=26)):
        assert any("above its ceiling" in p for p in validate_registry()), validate_registry()

    with _swapped_field(gallery, replace(gallery, max_items=0, min_items=500)):
        assert any("above its ceiling" in p for p in validate_registry()), (
            "a floor of 500 on a gallery with no declared cap was accepted; max_items=0 means "
            "DEFAULT_MAX_ITEMS, not unbounded"
        )

    assert validate_registry() == [], "the swaps leaked"


def test_the_version_moves_for_a_floor_and_not_for_a_ceiling():
    gallery = _entity("clusterBackground").field("motifPhotos")
    before = registry_version()

    with _swapped_field(gallery, replace(gallery, max_items=30)):
        assert registry_version() == before, (
            "the digest moved for a cap change; that invalidates every cached draft on every "
            "phone for a picker hint"
        )

    with _swapped_field(gallery, replace(gallery, min_items=24)):
        assert registry_version() != before, (
            "the digest did NOT move for a floor change, so no client would refetch and a phone "
            "would go on scoring the stage complete by the old number"
        )

    assert registry_version() == before, "the swaps leaked"


def test_the_motif_help_names_the_faults_that_are_measured_and_the_ones_that_are_not():
    background = _entity("clusterBackground")
    for key, floor in FLOORED_GALLERIES.items():
        spec = background.field(key)
        assert f"All {floor} are required" in spec.help, spec.help
        help_text = f"{spec.help} {spec.details}".lower()
        for measured in ("blur", "low-resolution", "duplicate"):
            assert measured in help_text, (
                f"{key}'s help does not name {measured!r}, which the detector does measure: "
                f"{help_text!r}"
            )
        assert "exposure and subject are not checked" in help_text, (
            f"{key}'s help claims a quality check without naming the two judgements nothing in "
            f"this product makes: {help_text!r}"
        )
        assert not any(str(n) in help_text for n in (60, 20)), (
            f"{key}'s help prints a threshold that lives in a client constant: {help_text!r}"
        )


def test_the_photos_helper_puts_its_help_on_the_gallery_and_never_on_the_caption():
    galleries_with_help = {
        f"{entity.key}.{field.key}"
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.type is FieldType.IMAGE_LIST and (field.help or field.details)
    }
    assert galleries_with_help == {
        "clusterBackground.motifPhotos",
        "clusterBackground.contemporaryMotifPhotos",
        "clusterBackground.lostCraftPhotos",
        "existingProduct.productPhotos",
    }, (
        f"{galleries_with_help} carry gallery help. A string that leaked out of photos() tells "
        "fifteen stages' designers about a ceiling that is not theirs."
    )

    hydration_captions = {
        "workshopSetup.craftPhotoCaption",
        "participant.photoCaption",
        "tool.photoCaption",
        "existingProduct.productPhotosCaption",
    }
    captions = [
        (f"{entity.key}.{field.key}", field.help or field.details)
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.caption_for and field.key == f"{field.caption_for}Caption"
    ]
    assert len(captions) >= 20, (
        f"only {len(captions)} caption pairs found; the assertions below are measuring nothing"
    )
    helped = {path for path, help_text in captions if help_text}
    assert helped == hydration_captions, (
        f"{sorted(helped)} are caption boxes carrying help, not the four hydrated ones. photos() "
        "puts help on the gallery; a string that landed on a caption is guidance about a different "
        "field, printed under a one-line text input."
    )
    for path, help_text in captions:
        if not help_text:
            continue
        assert help_text.startswith("Filled in from the linked record"), (
            f"{path} carries help that is not the reference-carry sentence: {help_text!r}. A "
            "caption's own guidance is its label; anything else here is advice about the gallery."
        )


def test_date_requires_iso_8601():
    spec = _f(type=FieldType.DATE)
    assert coerce_value(spec, "2026-02-10") == ("2026-02-10", None)
    assert coerce_value(spec, "10/02/2026")[1] is not None


def test_time_is_normalised_to_two_digits():
    assert coerce_value(_f(type=FieldType.TIME), "9:5") == ("09:05", None)
    assert coerce_value(_f(type=FieldType.TIME), "25:00")[1] is not None


def test_geo_bounds_are_checked():
    spec = _f(type=FieldType.GEO)
    value, error = coerce_value(spec, {"lat": 21.33, "lon": 83.61, "accuracy": 8})
    assert error is None and value["lat"] == pytest.approx(21.33)
    assert coerce_value(spec, {"lat": 200, "lon": 0})[1] is not None


@pytest.mark.parametrize("bad", ["NaN", "Infinity", float("inf"), -1, 1e308])
def test_geo_accuracy_is_bounded_like_the_coordinates_beside_it(bad):
    spec = _f(label="Where", type=FieldType.GEO)
    value, error = coerce_value(spec, {"lat": 21.33, "lon": 83.61, "accuracy": bad})
    assert value is None
    assert error and error.startswith("Where:")


def test_a_plausible_geo_accuracy_is_still_accepted():
    spec = _f(type=FieldType.GEO)
    value, error = coerce_value(spec, {"lat": 21.33, "lon": 83.61, "accuracy": "12.5"})
    assert error is None and value["accuracy"] == pytest.approx(12.5)


def test_a_nan_coordinate_is_refused_by_the_range_test_it_falls_through():
    assert coerce_value(_f(type=FieldType.GEO), {"lat": "NaN", "lon": 83.61})[1] is not None


def test_a_lone_surrogate_is_dropped_rather_than_failing_the_whole_stage():
    value, error = coerce_value(_f(type=FieldType.TEXT), "Bandha \ud83d weave")
    assert error is None
    assert value == "Bandha  weave"
    value.encode("utf-8")


def test_a_control_character_cannot_reach_a_text_column():
    value, error = coerce_value(_f(type=FieldType.TEXT), "Bar\x00pali")
    assert (value, error) == ("Barpali", None)


def test_ordinary_text_survives_the_normalisation_unchanged():
    for text in ("ସମ୍ବଲପୁରୀ ବନ୍ଧା", "Ikat (ସମ୍ବଲପୁରୀ) weave", "₹1,650 — 60% cotton", "🧵 spool"):
        assert coerce_value(_f(type=FieldType.TEXT), text) == (text, None)


def test_int_accepts_grouped_digits():
    assert coerce_value(_f(type=FieldType.INT), "1,240") == (1240, None)


def test_max_length_is_enforced():
    assert coerce_value(_f(max_length=5), "far too long")[1] is not None



def _entity(key: str):
    return next(e for _s, e in all_entities() if e.key == key)


def test_unknown_keys_are_dropped_not_rejected():
    entity = _entity("workshopSetup")
    clean, errors = validate_entry(entity, {"workshopTitle": "W", "somethingNew": "x"},
                                   enforce_required=False)
    assert "somethingNew" not in clean
    assert errors == {}


def test_required_is_only_enforced_on_submit():
    entity = _entity("workshopSetup")
    _clean, drafting = validate_entry(entity, {"workshopTitle": "W"}, enforce_required=False)
    assert drafting == {}
    _clean, submitting = validate_entry(entity, {"workshopTitle": "W"}, enforce_required=True)
    assert "craftName" in submitting


def test_one_bad_field_does_not_lose_the_others():
    entity = _entity("existingProduct")
    clean, errors = validate_entry(
        entity, {"name": "Saree", "price": "not a number"}, enforce_required=False
    )
    assert clean["name"] == "Saree"
    assert "price" in errors



def test_a_stage_with_no_required_fields_reads_as_complete():
    spec = next(s for s in STAGES if s.key == "POST_WORKSHOP_FOLLOWUP")
    score = stage_completeness(spec, {}, {})
    assert score.required_total == 0
    assert score.percent == 100
    assert score.is_complete


def test_completeness_counts_collection_rows():
    spec = next(s for s in STAGES if s.key == "SKETCH_DEVELOPMENT")
    rows = [{"sketchNo": "SK-01", "name": "Runner", "image": "m1"},
            {"sketchNo": "SK-02", "name": "Stole"}]
    score = stage_completeness(spec, {}, {"sketch": rows})
    assert score.collection_counts["sketch"] == 2
    assert not score.is_complete
    assert any("Sketch image" in m for m in score.missing)


def test_empty_collection_contributes_nothing():
    spec = next(s for s in STAGES if s.key == "SKETCH_DEVELOPMENT")
    score = stage_completeness(spec, {}, {"sketch": []})
    assert score.required_total == 0
    assert score.is_complete


def test_missing_labels_are_deduplicated():
    spec = next(s for s in STAGES if s.key == "SKETCH_DEVELOPMENT")
    score = stage_completeness(spec, {}, {"sketch": [{}, {}, {}]})
    assert len(score.missing) == len(set(score.missing))


def test_whitespace_only_does_not_count_as_filled():
    spec = next(s for s in STAGES if s.key == "INTRODUCTORY_ADMIN_DOCUMENTATION")
    score = stage_completeness(spec, {"acknowledgement": "   "}, {})
    assert score.required_filled == 0


def test_a_stated_count_that_overrides_the_record_must_say_why():
    outcomes = _entity("outcomes")

    _clean, errors = validate_entry(
        outcomes, {"designsCountOverride": 24}, enforce_required=False
    )
    assert "countOverrideReason" in errors
    assert "Number of designs (override)" in errors["countOverrideReason"]

    _clean, errors = validate_entry(
        outcomes, {"prototypesCountOverride": 6}, enforce_required=False
    )
    assert "countOverrideReason" in errors

    cleaned, errors = validate_entry(outcomes, {
        "designsCountOverride": 24,
        "countOverrideReason": "Only 18 sketches were photographed into the record.",
    }, enforce_required=False)
    assert errors == {}
    assert cleaned["designsCountOverride"] == 24


def test_the_reason_is_not_demanded_when_no_count_was_overridden():
    outcomes = _entity("outcomes")
    for enforce in (False, True):
        _clean, errors = validate_entry(
            outcomes, {"achievements": "Ten designs were developed."}, enforce_required=enforce
        )
        assert "countOverrideReason" not in errors



VECTOR_TABLE = (
    pathlib.Path(__file__).resolve().parents[2] / "shared" / "text-format-vectors.json"
)

def _valid_aadhaar() -> str:
    from app.services.artisan_identity import verhoeff_ok

    for last in "0123456789":
        candidate = f"23456789012{last}"
        if verhoeff_ok(candidate):
            return candidate
    raise AssertionError("no Verhoeff-valid Aadhaar in the candidate range")


def _participant_field(key: str) -> FieldSpec:
    field = _entity("participant").field(key)
    assert field is not None, f"participant has no {key!r} field"
    return field


def test_a_typed_string_that_is_not_an_aadhaar_is_refused_rather_than_masked():
    aadhaar = _participant_field("aadhaarNumber")
    assert aadhaar.text_format is TextFormat.AADHAAR

    stored, error = coerce_value(aadhaar, "hello world 1234")
    assert error == "Aadhaar number must be 12 digits — remove any letters or symbols.", error
    assert stored is None, (
        f"{stored!r} was stored: a typo in the shape of an Aadhaar number, which is the whole of "
        "the defect. The format check has to run BEFORE store_masked"
    )

    real = _valid_aadhaar()
    assert coerce_value(aadhaar, real) == (f"XXXX XXXX {real[-4:]}", None)


def test_a_hydrated_mask_survives_re_coercion_without_a_refusal():
    aadhaar = _participant_field("aadhaarNumber")
    for mask in ("XXXX XXXX 0124", "XXXX XXXX XXXX"):
        stored, error = coerce_value(aadhaar, mask)
        assert error is None, f"{mask!r} was refused: {error}"
        assert stored == mask, f"{mask!r} came back as {stored!r}"


def test_the_mask_predicate_refuses_what_is_masked_aadhaar_accepts():
    from app.services.artisan_identity import aadhaar_or_mask_error, is_masked_aadhaar

    aadhaar = _participant_field("aadhaarNumber")
    for accepted_by_the_wrong_rule in ("XxamplE 1234", "X", "XXXX XXXX 12345"):
        assert is_masked_aadhaar(accepted_by_the_wrong_rule), (
            "this test is asserting a difference that no longer exists"
        )
        assert aadhaar_or_mask_error(accepted_by_the_wrong_rule) is not None
        stored, error = coerce_value(aadhaar, accepted_by_the_wrong_rule)
        assert error is not None and stored is None, (
            f"{accepted_by_the_wrong_rule!r} was stored as {stored!r}"
        )

    assert aadhaar_or_mask_error("xxxx xxxx 0124") is not None


def test_store_masked_requires_an_aadhaar_format():
    aadhaar = _participant_field("aadhaarNumber")
    assert aadhaar.store_masked and aadhaar.text_format is TextFormat.AADHAAR

    with _swapped_attrs(aadhaar, text_format=TextFormat.NONE):
        problems = validate_registry()
        assert any("store_masked without text_format=AADHAAR" in p for p in problems), problems

    assert validate_registry() == []


def test_a_multi_enum_must_draw_its_options_from_exactly_one_place():
    tools = next(
        f
        for _s, e in all_entities()
        for f in e.fields
        if f.key == "toolsUsed" and f.type is FieldType.MULTI_ENUM
    )

    with _swapped_attrs(tools, ref_model="", ref_scope=""):
        problems = validate_registry()
        assert any("names no enum and no ref_model" in p for p in problems), problems

    with _swapped_attrs(tools, enum="MARKET_CHANNEL"):
        problems = validate_registry()
        assert any("BOTH an enum and a ref_model" in p for p in problems), problems

    channels = next(
        f
        for _s, e in all_entities()
        for f in e.fields
        if f.type is FieldType.MULTI_ENUM and f.enum
    )
    with _swapped_attrs(channels, ref_scope="ALL"):
        problems = validate_registry()
        assert any("a scope narrows a list of records" in p for p in problems), problems

    text_field = next(f for _s, e in all_entities() for f in e.fields if f.type is FieldType.TEXT)
    with _swapped_attrs(text_field, ref_model="Artisan"):
        problems = validate_registry()
        assert any("only REF and MULTI_ENUM draw a picker" in p for p in problems), problems

    assert validate_registry() == [], "the swaps leaked"


def test_a_format_may_only_be_declared_on_a_type_that_reaches_the_branch_enforcing_it():
    numeric = next(
        f for _s, e in all_entities() for f in e.fields if f.type is FieldType.INT
    )
    with _swapped_attrs(numeric, text_format=TextFormat.PINCODE):
        problems = validate_registry()
        assert any("only a scalar text field" in p for p in problems), problems

    rich = next(
        f for _s, e in all_entities() for f in e.fields if f.type is FieldType.RICH_TEXT
    )
    with _swapped_attrs(rich, text_format=TextFormat.EMAIL):
        assert any("only a scalar text field" in p for p in validate_registry())

    assert validate_registry() == []

    for allowed in (FieldType.TEXT, FieldType.LONG_TEXT, FieldType.URL,
                    FieldType.PHONE, FieldType.EMAIL):
        spec = _f(label="Contact", type=allowed, text_format=TextFormat.EMAIL)
        assert coerce_value(spec, "not-an-address")[1] is not None, (
            f"a format declared on {allowed.value} is accepted by validate_registry and enforced "
            "by nothing"
        )


def test_the_version_changes_when_a_field_gains_a_format():
    baseline = registry_version()
    plain = next(
        f for _s, e in all_entities()
        if e.key == "surveyResponse"
        for f in e.fields
        if f.type is FieldType.TEXT and f.text_format is TextFormat.NONE
    )
    with _swapped_attrs(plain, text_format=TextFormat.PINCODE):
        assert registry_version() != baseline, (
            "a field gained a format and the digest did not move, so every phone holding the old "
            "registry would go on accepting what the server now refuses and never be told to "
            "refetch"
        )
    assert registry_version() == baseline

    email = _participant_field("email")
    with _swapped_attrs(email, text_format=TextFormat.PHONE_IN):
        assert registry_version() != baseline
    assert registry_version() == baseline


def test_a_legacy_bare_ten_digit_phone_is_not_refused_on_re_save():
    phone = _participant_field("phone")
    assert phone.text_format is TextFormat.PHONE_IN

    for legacy in ("9876500001", "9876 500 001"):
        stored, error = coerce_value(phone, legacy)
        assert error is None, f"{legacy!r} was refused: {error}"
        assert stored == legacy, "a legacy number must be kept as typed, not reformatted"

    assert coerce_value(phone, "+91 9876500001")[1] is None
    assert coerce_value(phone, "+91 987650000")[1] == "Enter a 10-digit number for +91."


def test_participant_email_is_bounded():
    email = _participant_field("email")
    assert email.type is FieldType.EMAIL
    assert email.text_format is TextFormat.EMAIL
    assert email.max_length == 254, "the longest an RFC-conformant address can be"

    long_but_well_formed = "a" * 250 + "@example.org"
    stored, error = coerce_value(email, long_but_well_formed)
    assert stored is None and error is not None
    assert "longer than 254" in error, error


def test_every_email_and_phone_field_in_the_registry_declares_a_format():
    unchecked = [
        f"{entity.key}.{field.key}"
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.type in (FieldType.EMAIL, FieldType.PHONE)
        and not field.deprecated
        and field.text_format is TextFormat.NONE
    ]
    assert unchecked == [], (
        f"{unchecked} are typed EMAIL/PHONE and declare no text_format, so the server checks "
        "nothing but their length — which is the state this feature was written to end"
    )


def test_every_declared_format_is_published_to_the_clients():
    published = {
        f"{entity.key}.{field.key}": field_to_dict(field, entity.key).get("format")
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.text_format is not TextFormat.NONE and not field.deprecated
    }
    assert published, "no field declares a format; this test is measuring nothing"
    for path, value in published.items():
        assert value, f"{path} declares a format that field_to_dict does not publish"

    plain = _participant_field("name")
    assert plain.text_format is TextFormat.NONE
    assert "format" not in field_to_dict(plain, "participant")


def test_the_pehchan_predicate_accepts_a_mask_which_is_why_no_field_declares_it():
    from app.services.artisan_identity import normalize_pehchan, pehchan_error

    assert pehchan_error(normalize_pehchan("XXXX XXXX 3456")) is None, (
        "pehchan_error now refuses a mask, so the reason artisanCardNo declares no format has "
        "changed — re-read this test's docstring before deleting it"
    )
    card = _participant_field("artisanCardNo")
    assert card.text_format is TextFormat.NONE
    assert card.store_masked is False


def test_the_three_implementations_agree_on_the_shared_vector_table():
    payload = json.loads(VECTOR_TABLE.read_text(encoding="utf-8"))
    vectors = payload["vectors"]
    assert len(vectors) > 30, "the shared case table is suspiciously small"

    declared = {
        f.text_format.value
        for _s, e in all_entities()
        for f in e.fields
        if f.text_format is not TextFormat.NONE and not f.deprecated
    }
    covered = {row["format"] for row in vectors}
    assert declared <= covered, f"{declared - covered} is declared and has no vectors"

    for row in vectors + payload["server_only"]:
        spec = _f(label="Value", type=FieldType.TEXT,
                  text_format=TextFormat(row["format"]))
        _stored, error = coerce_value(spec, row["value"])
        assert error == row["error"], (
            f"{row['format']} {row['value']!r}: expected {row['error']!r}, got {error!r}"
        )


DESIGNER_SHAPED_BOXES: tuple[tuple[str, TextFormat, FieldType], ...] = (
    ("designerPhone", TextFormat.PHONE_IN, FieldType.PHONE),
    ("designerEmail", TextFormat.EMAIL, FieldType.EMAIL),
    ("designerPincode", TextFormat.PINCODE, FieldType.TEXT),
)


def test_the_stage_three_designer_boxes_declare_the_shapes_they_were_given():
    plan = _entity("workshopPlan")
    for key, expected_format, expected_type in DESIGNER_SHAPED_BOXES:
        field = plan.field(key)
        assert field is not None, f"workshopPlan has no {key!r} field"
        assert field.type is expected_type, f"{key} is {field.type.value}"
        assert field.text_format is expected_format, (
            f"{key} declares text_format {field.text_format.value or 'NONE'}, so the server checks "
            "nothing but its length — and the value the report prints is this copy, not the "
            "Designer Page's validated column"
        )
        assert field.max_length, (
            f"{key} declares a format and no max_length. A format is a shape, not a bound; see "
            "test_participant_email_is_bounded for the field that was learned on."
        )
        assert field.store_masked is False, f"{key} would print as a mask in the designer block"

    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_the_stage_three_designer_boxes_refuse_what_the_designer_page_refuses():
    from app.services.address import normalize_pincode, pincode_error
    from app.services.contact_formats import email_error, normalize_email, phone_error

    plan = _entity("workshopPlan")

    checks = (
        ("designerPhone", phone_error,
         ("+91 9876500001", "9876500001", "+91 987650000", "+44 20 7946 0958",
          "abc9876500001def", "not a number")),
        ("designerEmail", lambda v: email_error(normalize_email(v)),
         ("latha.nayak@nift.ac.in", "latha", "latha@example", "@example.org",
          "latha@example.org, ammaji@example.org")),
        ("designerPincode", lambda v: pincode_error(normalize_pincode(v) or v),
         ("768029", "768 029", "76802", "068029", "7680A9", "---")),
    )

    for key, validator, values in checks:
        field = plan.field(key)
        verdicts = {validator(value) is None for value in values}
        assert verdicts == {True, False}, (
            f"{key}'s cases are all-accept or all-refuse, so half of this test is vacuous"
        )
        for value in values:
            stored, error = coerce_value(field, value)
            expected = validator(value)
            assert error == expected, (
                f"{key} {value!r}: the stage box answers {error!r} and the Designer Page's own "
                f"validator answers {expected!r}. Two answers for one rule is the state this "
                "feature was written to end."
            )
            if expected is None:
                assert stored == value, (
                    f"{key} {value!r} was accepted and stored as {stored!r}; an accepted value must "
                    "be kept as typed, not reformatted under the designer"
                )
            else:
                assert stored is None, (
                    f"{key} {value!r} was refused with {error!r} and still stored {stored!r} — a "
                    "malformed value in a ministry document behind an error nobody blocked on"
                )


def test_a_pin_code_box_anywhere_in_the_registry_declares_the_pincode_format():
    pattern = re.compile(r"pin\s*code", re.IGNORECASE)
    matched = [
        (f"{entity.key}.{field.key}", field)
        for _stage, entity in all_entities()
        for field in entity.fields
        if pattern.search(f"{field.key} {field.label}")
    ]

    assert len(matched) >= 4, f"the PIN code pattern matches only {matched}; it has stopped working"

    unchecked = sorted(
        path for path, field in matched
        if not field.deprecated and field.text_format is not TextFormat.PINCODE
    )
    assert unchecked == [], (
        f"{unchecked} look like PIN code boxes and declare no PINCODE format, so the server checks "
        "nothing but their length — and a five-digit PIN code then prints as an address in a report"
    )
