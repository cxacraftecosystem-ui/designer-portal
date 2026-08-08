"""The field registry's own rules, and the coercion every stage save depends on.

The registry is the schema. Nothing here talks to a database, because nothing in the registry
does — that is the point of keeping the field definitions as data. What these tests protect is
the set of invariants that, if broken, would corrupt research data silently rather than loudly:
a duplicated field key overwrites another field's answers, a renamed key orphans two weeks of
fieldwork, and a required Standard-tier field makes a stage permanently unsubmittable in exactly
the village the app exists for.
"""

from contextlib import contextmanager
from dataclasses import replace

import pytest

# Importing this module is what installs the twenty-two stages into the registry.
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
    Tier,
    all_entities,
    coerce_value,
    enum_label,
    promoted_values,
    registry_to_dict,
    registry_version,
    stage_completeness,
    validate_entry,
    validate_registry,
)

# --------------------------------------------------------------------------------------
# The registry itself
# --------------------------------------------------------------------------------------


@contextmanager
def _swapped_field(original: FieldSpec, substitute: FieldSpec):
    """Put `substitute` where `original` sits in the registry, and always put it back.

    `EntitySpec.fields` is a tuple, so the swap is a rebuild of that tuple rather than an item
    assignment. Restoring in a `finally` is not optional: the registry is module-level state shared
    by every test in the session, and a leaked mutation would surface as an unrelated failure
    somewhere else entirely.
    """
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


def test_registry_is_sound():
    """The single most valuable assertion in this file.

    ``validate_registry`` enforces unique keys, canonical enums, resolvable references and the
    Basic-tier-only-required rule. Every one of those failures is silent at runtime.
    """
    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_all_twenty_two_stages_are_present_and_numbered_once():
    assert len(STAGES) == 22
    assert sorted(s.number for s in STAGES) == list(range(1, 23))


def test_every_stage_has_at_least_one_entity():
    for spec in STAGES:
        assert spec.entities, spec.key


def test_no_stage_declares_two_singletons():
    """Two singletons would give a stage two homes for its one-per-workshop answers."""
    for spec in STAGES:
        singletons = [e for e in spec.entities if e.cardinality is Cardinality.SINGLETON]
        assert len(singletons) <= 1, spec.key


def test_entity_keys_are_globally_unique():
    """A stage entry row is addressed by (workshopId, entityKey, ordinal) alone."""
    keys = [e.key for _s, e in all_entities()]
    assert len(keys) == len(set(keys))


def test_only_basic_fields_are_required():
    """The tiers only work if the Basic tier alone can satisfy the completeness gate."""
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.required:
                assert f.tier is Tier.BASIC, f"{entity.key}.{f.key} is {f.tier.value}"


def test_every_enum_field_names_a_canonical_list():
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.type in (FieldType.ENUM, FieldType.MULTI_ENUM):
                assert f.enum in ENUMS, f"{entity.key}.{f.key} -> {f.enum!r}"


def test_every_caption_points_at_a_media_field_in_its_own_entity():
    for _spec, entity in all_entities():
        for f in entity.fields:
            if f.caption_for:
                target = entity.field(f.caption_for)
                assert target is not None, f"{entity.key}.{f.key}"
                assert target.type.is_media, f"{entity.key}.{f.key}"


def test_table_column_width_hints_are_sane():
    """A width outside 0-100 would make the renderer's normalisation produce a table wider than
    the page, which Word rescales and ReportLab clips — two different wrong answers."""
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
    """`startDate` exists on both the workshop and a prototype; only one may reach the column."""
    assert promoted_values("workshopSetup", {"startDate": "2026-02-10"}) == {
        "startDate": "2026-02-10"
    }
    assert promoted_values("prototype", {"startDate": "2026-02-14"}) == {}


def test_registry_version_is_stable_and_content_addressed():
    first = registry_version()
    assert first == registry_version()
    assert len(first) == 16


def test_the_version_changes_when_a_derivation_changes():
    """Losing a derivation must invalidate the caches, because losing one is INVISIBLE otherwise.

    The bundled Android asset once carried two derived fields where the registry had five —
    missing exactly the three cost-sheet ones — and its version string matched the live registry
    character for character, because the digest covered key/type/tier/required/enum/deprecated and
    stopped there. The staleness check that exists to catch precisely this reported agreement, and
    on a handset the affected fields simply never computed: indistinguishable from a designer who
    had not filled them in.

    So each of the three things a derivation is made of is perturbed separately. Asserting only
    that "some difference changes the digest" would pass for an implementation that hashed the
    kind and ignored the operands, which is the same bug one field along.
    """
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
            continue  # the field already had this value; nothing was perturbed
        with _swapped_field(spec, changed):
            assert registry_version() != baseline, (
                f"changing {mutation} left the digest unchanged, so a client holding the old "
                f"derivation would never be told to refetch"
            )

    # And restored exactly: a digest that did not come back is a leak into every later test.
    assert registry_version() == baseline


def test_the_version_changes_when_a_hydration_mapping_changes(monkeypatch):
    """THE SAME HOLE, ONE FEATURE LATER, and this is the one that would have reopened it.

    `field_to_dict` publishes `REFERENCE_HYDRATION` as `refHydration`, which is how a handset
    learns that a documented process fills in "What happens" and "Documented for" rather than the
    step's name alone. That makes the mapping a CLIENT CONTRACT — and correcting one touches no
    key, no type, no tier and no derivation. Left out of the digest, the version would not move,
    so `test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from` (which compares
    the version string, not the content) would report agreement against a stale asset, and a
    phone that has never reached the network would keep hydrating by exactly the mapping the
    correction was written to end. That is the artisan-name-in-the-product-column defect, redelivered.

    Three perturbations, separately, because "some difference moves the digest" would pass for an
    implementation that hashed only the presence of a mapping: a widening, a retargeting, and a
    removal.
    """
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
    # Every client caches this on every app start; the empty strings are most of its bulk.
    sample = payload["stages"][0]["entities"][0]["fields"][0]
    assert "help" not in sample or sample["help"]


def test_enum_label_falls_back_rather_than_raising():
    """A draft written by a phone one release ahead can carry a token this build never saw."""
    assert enum_label("PRODUCT_CATEGORY", "SAREE") == "Saree"
    assert enum_label("PRODUCT_CATEGORY", "SOMETHING_NEW") == "SOMETHING_NEW"
    assert enum_label("NO_SUCH_ENUM", "X") == "X"


def test_the_core_chain_is_traversable():
    """Sketch -> Prototype -> Iteration/Validation -> FinalProduct -> CostSheet -> FollowUp.

    Every hop must be an explicit reference in at least one direction, or the report cannot say
    which prototype a cost sheet belongs to and the research data cannot be joined at all.
    """
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
    """The cover page is built entirely from COVER_FIELD roles on stage 1."""
    setup = next(s for s in STAGES if s.key == "WORKSHOP_SETUP").singleton
    cover = [f for f in setup.fields if f.report_role is ReportRole.COVER_FIELD]
    assert len(cover) >= 8
    assert {"craftName", "clusterName", "designerName"} <= {f.key for f in cover}


# --------------------------------------------------------------------------------------
# Coercion
# --------------------------------------------------------------------------------------


def _f(key="x", label="X", type=FieldType.TEXT, **kw) -> FieldSpec:
    return FieldSpec(key=key, label=label, type=type, **kw)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("1,250.10", "1250.10"), (1250.1, "1250.10"), ("₹500", "500.00"), ("0", "0.00")],
)
def test_money_is_stored_as_a_two_place_string(raw, expected):
    """A float round trip turns 1250.10 into 1250.0999999999999 in a cost sheet."""
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
    """THE REGRESSION: `float()` reads every one of these and `_range_checked` cannot catch any.

    Every comparison against NaN is False, so `nan < 0` passed a `min_value=0` floor untouched,
    and `inf` passes any floor there is. These are plain `<input type="text">` boxes on the web
    (that is how trailing zeros survive), so a designer can type the word.

    What happened next depended only on the type. MONEY stringifies, so `f"{nan:.2f}"` stored the
    literal "nan" behind a 200 with no errors — the designer is told "Stage saved" — and the
    report printed "₹ nan." in the browser preview, in the .docx submitted to the ministry and in
    the on-device copy, while the cost charts dropped the row and the totals disagreed with the
    table. DECIMAL stores the float raw, so it reached the JSON column, Prisma refused it, and
    the whole stage save 500'd — which the stage editor reports to the designer as "no
    connection" and retries forever.

    Only `-Infinity` was ever caught, and only by accident, by the min-0 check.
    """
    value, error = coerce_value(_f(label="Cost", type=kind, min_value=0), raw)
    assert value is None
    assert error == "Cost is not a valid number"


def test_a_real_number_still_passes_after_the_finiteness_guard():
    """The guard must not become a floor: the ordinary values are the point of the field."""
    assert coerce_value(_f(type=FieldType.MONEY, min_value=0), "1650.00") == ("1650.00", None)
    assert coerce_value(_f(type=FieldType.DECIMAL), "4.5") == (4.5, None)
    assert coerce_value(_f(type=FieldType.PERCENT), 12) == (12.0, None)


def test_blank_is_accepted_and_becomes_none():
    """Whether blank is ALLOWED is validate_entry's question, not coerce_value's."""
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


def test_multi_value_field_rejects_a_scalar():
    assert coerce_value(_f(type=FieldType.TAGS), "not a list")[1] is not None


def test_date_requires_iso_8601():
    """Accepting 10/02/2026 would silently store a February date as an October one."""
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
    """`accuracy` had no check of ANY kind while lat and lon had one, so it was the way a
    non-finite float still reached the JSON column — where Prisma refuses it and the whole stage
    save comes back as a bare 500 that the stage editor shows the designer as a lost connection.
    A negative error bar is not a reading either, and neither is one larger than the planet."""
    spec = _f(label="Where", type=FieldType.GEO)
    value, error = coerce_value(spec, {"lat": 21.33, "lon": 83.61, "accuracy": bad})
    assert value is None
    assert error and error.startswith("Where:")


def test_a_plausible_geo_accuracy_is_still_accepted():
    spec = _f(type=FieldType.GEO)
    value, error = coerce_value(spec, {"lat": 21.33, "lon": 83.61, "accuracy": "12.5"})
    assert error is None and value["accuracy"] == pytest.approx(12.5)


def test_a_nan_coordinate_is_refused_by_the_range_test_it_falls_through():
    """Read the bounds test the way round it is written: every comparison against NaN is False,
    so `not (-90 <= lat <= 90)` is True and the coordinate is refused rather than stored."""
    assert coerce_value(_f(type=FieldType.GEO), {"lat": "NaN", "lon": 83.61})[1] is not None


def test_a_lone_surrogate_is_dropped_rather_than_failing_the_whole_stage():
    """A lone surrogate is what ANY client that truncates a string at a UTF-16 index produces by
    cutting an emoji or an astral glyph in half, and JSON permits it as a bare \\udXXX escape. It
    reached the driver, raised UnicodeEncodeError and 500'd the entire stage save — which the
    stage editor reports as "no connection", so a permanently un-saveable stage looked like bad
    signal and retried forever. `rich_text` has passed every string through `clean_text` since it
    was written for exactly this; the plain-text fields beside it had no such guard.

    One glyph is lost, not the stage: the client already destroyed that character when it cut the
    pair, and a 422 would be a rejection the designer cannot act on.
    """
    value, error = coerce_value(_f(type=FieldType.TEXT), "Bandha \ud83d weave")
    assert error is None
    assert value == "Bandha  weave"
    # And the stored value must be writable — which is the whole claim.
    value.encode("utf-8")


def test_a_control_character_cannot_reach_a_text_column():
    """The same normalisation catches the NUL that Postgres refuses in a text column."""
    value, error = coerce_value(_f(type=FieldType.TEXT), "Bar\x00pali")
    assert (value, error) == ("Barpali", None)


def test_ordinary_text_survives_the_normalisation_unchanged():
    """Including the scripts the whole app exists for: dropping a codepoint must mean dropping
    one that cannot be written, never one an artisan typed."""
    for text in ("ସମ୍ବଲପୁରୀ ବନ୍ଧା", "Ikat (ସମ୍ବଲପୁରୀ) weave", "₹1,650 — 60% cotton", "🧵 spool"):
        assert coerce_value(_f(type=FieldType.TEXT), text) == (text, None)


def test_int_accepts_grouped_digits():
    assert coerce_value(_f(type=FieldType.INT), "1,240") == (1240, None)


def test_max_length_is_enforced():
    assert coerce_value(_f(max_length=5), "far too long")[1] is not None


# --------------------------------------------------------------------------------------
# validate_entry
# --------------------------------------------------------------------------------------


def _entity(key: str):
    return next(e for _s, e in all_entities() if e.key == key)


def test_unknown_keys_are_dropped_not_rejected():
    """A phone one release ahead of the server must not lose a whole stage to one new field."""
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
    """A stage with one typo still saves its other twenty answers."""
    entity = _entity("existingProduct")
    clean, errors = validate_entry(
        entity, {"name": "Saree", "price": "not a number"}, enforce_required=False
    )
    assert clean["name"] == "Saree"
    assert "price" in errors


# --------------------------------------------------------------------------------------
# Completeness
# --------------------------------------------------------------------------------------


def test_a_stage_with_no_required_fields_reads_as_complete():
    """Dividing by zero to decide whether a designer may submit makes a stage unsubmittable."""
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
    # The second sketch has no image, which is a Basic-tier requirement on that entity.
    assert not score.is_complete
    assert any("Sketch image" in m for m in score.missing)


def test_empty_collection_contributes_nothing():
    """An empty sketch list is a legitimate state on day one, not an error."""
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
    """`countOverrideReason`'s help has said "Required if either count above is filled in" since
    it was written, and nothing enforced it. That figure now WINS on the report's front page —
    it is what an officer quotes — so a number that contradicts the record without a reason is
    exactly the thing the field exists to prevent."""
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
    """The ordinary stage-18 save, which must not acquire a new error out of nowhere. Checked at
    submit strength too, because this rule is not gated on `enforce_required` — it is only ever
    triggered by a value the designer has just typed."""
    outcomes = _entity("outcomes")
    for enforce in (False, True):
        _clean, errors = validate_entry(
            outcomes, {"achievements": "Ten designs were developed."}, enforce_required=enforce
        )
        assert "countOverrideReason" not in errors
