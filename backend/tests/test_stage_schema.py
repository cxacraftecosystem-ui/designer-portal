"""The field registry's own rules, and the coercion every stage save depends on.

The registry is the schema. Nothing here talks to a database, because nothing in the registry
does — that is the point of keeping the field definitions as data. What these tests protect is
the set of invariants that, if broken, would corrupt research data silently rather than loudly:
a duplicated field key overwrites another field's answers, a renamed key orphans two weeks of
fieldwork, and a required Standard-tier field makes a stage permanently unsubmittable in exactly
the village the app exists for.
"""

import dataclasses
import json
import pathlib
import re
from contextlib import contextmanager
from dataclasses import replace
from enum import Enum

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


@contextmanager
def _swapped_attrs(spec, **attrs):
    """Temporarily change attributes ON THE LIVE SPEC, and always put them back.

    Deliberately not ``dataclasses.replace``: that builds a NEW object, and the registry the code
    under test reads is the module-level ``STAGES`` tuple, which would still hold the old one. The
    specs are frozen, so the assignment goes through ``object.__setattr__`` — the same escape
    hatch, and the same mandatory ``finally``, as :func:`_swapped_field` above.
    """
    before = {name: getattr(spec, name) for name in attrs}
    for name, value in attrs.items():
        object.__setattr__(spec, name, value)
    try:
        yield
    finally:
        for name, value in before.items():
            object.__setattr__(spec, name, value)


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
# What is allowed to reach a designer's screen
# --------------------------------------------------------------------------------------

#: Words that only ever appear in a sentence ABOUT THE SPECIFICATION rather than about the work.
#:
#: Deliberately a rule and not a list of the seventeen strings that were actually wrong. A test
#: pinning "may be Deepika app for now" would have gone green the moment somebody wrote a new note
#: quoting a different colleague, which is the failure it exists to prevent — and it would have
#: read, to the next person, as though shipping that sentence were intended.
#:
#: The additions below the first group came from re-sweeping every channel by hand after the notes
#: were rewritten, and each one closes a way the SAME sentence could come back past this list.
#: "we may consider" caught the stage-12 note verbatim but not "we may add this later"; the phase
#: numbers stopped at 4 because the source document did, so "phase 5" would have walked through;
#: "TBD" and "TODO" are what build-time commentary looks like once somebody stops writing prose.
#: Terms that could plausibly appear in legitimate designer guidance were considered and REJECTED:
#: bare "later" occurs in fourteen help strings ("if that record is later changed or removed"),
#: and "optional fields" is a sentence a real instruction might need. A term that fires on honest
#: guidance gets the whole rule deleted by whoever it blocks.
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
    # --- added by the re-sweep; all four channels measured clean under them ---
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


#: The shipped prose files, resolved from this file rather than from the working directory — these
#: tests are run from ``backend/`` by the documented command and from the repo root by editors.
DATA_DIR = pathlib.Path(stage_schema.__file__).resolve().parent.parent / "data"


def _walk_json(node, path):
    """Every string leaf in a JSON-shaped document, with the path it was found at.

    One walker shared by the registry sweep and the shipped-data sweep, so a blind spot cannot be
    fixed in one and left in the other.
    """
    if isinstance(node, dict):
        for key, value in node.items():
            yield from _walk_json(value, f"{path}.{key}" if path else str(key))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _walk_json(value, f"{path}[{index}]")
    elif isinstance(node, str):
        yield path, node


def _client_facing_registry_strings():
    """EVERY string ``registry_to_dict`` publishes, whatever key it arrives under.

    Taken from the SERIALISED form, not from the specs, because the question this answers is
    "what crosses the wire" and the two differ on purpose — ``FieldSpec.phase_note`` holds the
    reviewer's marginal comments verbatim and ``field_to_dict`` deliberately does not emit it.

    IT WALKS THE WHOLE DOCUMENT RATHER THAN A LIST OF KEYS, and that is the difference between the
    docstring above being true and being a comforting sentence. The first version of this helper
    read a hand-written set — ``title``, ``purpose``, ``notes``, ``label``, ``help``, option and
    enum labels — and claimed in its own docstring that it would fail "the day somebody adds
    ``phase_note`` to the serialiser". It would not have. Measured, by making ``field_to_dict``
    emit ``phaseNote``: the added key was not in the hand-written set, so it was never yielded and
    ``test_no_client_facing_registry_string_carries_build_time_commentary`` stayed GREEN with the
    reviewer's margin notes crossing the wire on every field that has one. Only
    ``test_the_reviewers_marginal_comments_are_kept_in_the_code_and_never_serialised`` caught it,
    and only because it asserts on the literal key name ``phaseNote`` — so the same leak under any
    other name (``note``, ``provenance``, ``rationale``) would have passed BOTH tests.

    A full walk has no such blind spot: a new client-facing string is covered the moment a
    serialiser starts emitting it, which is the only version of this rule that survives somebody
    adding a field to the wire without reading this file.

    Machine identifiers (``key``, ``type``, ``tier``, ``reportRole``, the ``version`` digest, enum
    tokens) are walked too rather than skipped. They are not exempt by category — a token is a
    string a client can render — and excluding them would rebuild exactly the hand-written set this
    docstring is about. Measured on the tree this landed on: 4,128 strings, zero matches.
    """
    yield from _walk_json(registry_to_dict(), "")


def test_no_client_facing_registry_string_carries_build_time_commentary():
    """Nothing a designer reads on a stage screen may be about how this app was planned.

    THE DEFECT THIS REPLACES, because a rule with no incident behind it gets relaxed by the next
    person who finds it inconvenient. Seventeen of the twenty-two stages' ``notes`` quoted the
    reviewer of the source requirements document at whoever opened the stage — 3,971 characters
    of it, on the web page and in the bundled handset asset both. "noted the Advanced
    image-processing tier as 'may be Deepika app for now'". "referred the Advanced measurement
    tier to 'Kumarjit da and team'". "we may consider deleting this entire section for now". The
    owner of this repository found it by using the app in the way it is meant to be used.

    None of it was actionable in a workshop: it is internal phasing, colleagues' names, what to
    defer and what might be deleted, and several entries named people with no connection at all
    to the person reading the screen. ``StageSpec.notes`` is now what a designer needs in order to
    do the stage, in the app's own voice, or it is absent.

    THE PROVENANCE WAS NOT DESTROYED, which matters as much as the removal — every remark that
    explained why a stage is SHAPED as it is survives as a comment on the spec in
    ``stage_definitions.py``, and ``FieldSpec.phase_note`` still carries the marginal comments
    verbatim. This test is what stops them travelling back the other way.

    Scoped to the whole serialised registry rather than to ``notes``, because ``notes`` was not
    the only channel fed by that document: one field's ``help`` ended "Moved here from the cluster
    background at the reviewer's request", which no test would have caught while this one only
    looked at stages.
    """
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
    """Every string the report templates carry, walked as OBJECTS rather than parsed as source.

    Dataclass-walked for the same reason ``_client_facing_registry_strings`` walks the serialised
    document: a hand-written list of attribute names stops covering the thing it is about the first
    time somebody adds a field.
    """
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
    """The same rule, on the three shipped-prose channels that had no test at all.

    WHY THIS EXISTS SEPARATELY FROM THE REGISTRY TEST. When the stage notes were rewritten, the
    registry got a rule and the admin analytics page got a rule, and the sweep that followed found
    the registry was not the only channel fed by the source document — one field's ``help`` had
    also been contaminated. That prompted checking every OTHER body of shipped prose by hand:
    the report templates, the questionnaire bank and the craft vocabulary. All three were clean,
    and all three were clean by luck rather than by anything that would notice if they stopped
    being. A channel whose cleanliness rests on somebody re-sweeping it by hand is a channel that
    gets contaminated the next time nobody does.

    The report templates are the most consequential of the three and the least watched: their
    section titles and static headings are printed into a .docx that goes to a ministry office,
    where "Phase 2 work" would be read by somebody with no idea what phase 2 was, in a document
    that outlives the app.

    Measured when written: 628 template strings, 332 questionnaire strings, 71 vocabulary lines,
    zero matches in any of them.
    """
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

    # Each channel is asserted non-empty individually: a renamed data file or a TEMPLATES tuple that
    # stopped being walkable would otherwise turn this test green by giving it nothing to check,
    # which is the failure mode every rule test of this shape dies of.
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
    """A DIFFERENT RULE FROM THE COMMENTARY ONE ABOVE, and the notes cleanup proved both are needed.

    THE LEAK THIS WOULD HAVE CAUGHT. Stage 21's note shipped, to the web page and to every handset,
    reading "…so those flags are real fields with an ``autoDetected`` marker rather than a deferred
    feature". Two RST backticks and a field key, rendered verbatim: the web page interpolates
    ``stage.notes`` into a ``<p>`` and Android hands it to ``Text()``, and neither parses markup.
    ``_BUILD_TIME_COMMENTARY`` does not contain a term that matches it — measured against the
    registry as it was actually being served, that note was one of only three carrying notes that
    the commentary rule did NOT flag. It was rewritten as part of the same cleanup, by hand, and
    nothing was left behind to notice it coming back.

    The same shape had also survived on the admin analytics page, where two refusals read "is
    declared ``optional_stage=True``" and named "``revenue``, ``unitsSold`` and ``ordersReceived``"
    — see ``test_the_refusals_and_cautions_never_quote_whoever_asked_for_the_feature``, which now
    carries this rule for that channel. Two channels independently acquiring the same defect is the
    argument for a rule rather than two more hand corrections.

    PROSE IS DEFINED AS "CONTAINS A SPACE", which is a rule and not a list of keys. Every machine
    identifier the walk yields — ``key``, ``type``, ``tier``, an enum token, the ``version`` digest
    — is a single word, so it is excluded by construction and stays excluded when somebody adds a
    new one. Every label, help string, title, purpose and note has a space. The alternative, naming
    the prose-bearing keys, is the hand-written set that let ``phaseNote`` through (see
    ``_client_facing_registry_strings``).

    A field key belongs in a note when the reader can SEE it: stage 21's replacement says tick
    “Detected automatically”, which is the label on the checkbox, not the key behind it.

    Measured when written: 4,128 strings, 799 of them prose, zero offenders.
    """
    prose = [(where, text) for where, text in _client_facing_registry_strings() if " " in text]
    assert len(prose) > 400, (
        f"only {len(prose)} prose strings were found — the walk or the space heuristic has broken "
        "and this test has gone vacuous, not clean"
    )

    #: A lowercase snake_case run is checked as well as the backticks, so dropping the ticks is not
    #: a way to satisfy this: "the optional_stage flag" is the same defect without the markup.
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
    """A note is ``""`` or it is real prose. One space in between and the two clients DISAGREE.

    THE ASYMMETRY THIS CLOSES, which is live in the tree and reachable by a one-character slip.
    The two clients guard the note differently:

    * web — ``{stage?.notes ? <p class="… border … bg-surface-50">{stage.notes}</p> : null}``
      in ``design-workshops/[id]/stages/[stageKey]/page.tsx``. The test is JS truthiness, so
      ``" "`` is TRUTHY and paints the bordered, tinted box with nothing in it.
    * Android — ``if (stage.notes.isNotBlank())`` in ``StageScreen.kt``, which renders nothing.

    So ``notes=" "`` gives a designer on the web an empty grey box under the progress bar and an
    Android designer nothing at all, from one registry. That is the "empty box where a note used to
    be" shape, and eleven of the twenty-two stages had their notes emptied in the cleanup — an
    author writing ``notes=" "`` instead of deleting the argument is exactly how it would arrive.

    FIXED HERE RATHER THAN IN THE CLIENTS ON PURPOSE. Trimming in one client leaves the two still
    disagreeing about a value the registry should never have sent; trimming in both is two edits to
    two hot files to handle input that is always wrong. Refusing it at the source makes the case
    unreachable for every client, including the next one. The web's truthiness test remains the
    looser of the two, and that is worth knowing if this rule is ever relaxed.

    ONE ASSERTION, BECAUSE A SECOND ONE HERE CANNOT FIRE. The obvious pairing is "stripped" AND
    "empty or non-blank", and the second half is dead code: every blank-but-non-empty string —
    ``" "``, ``"\\t"``, ``"\\xa0"``, ``"\\u3000"`` — is already unequal to its own ``strip()``, so the
    trimming assertion has taken it first. The only string that is blank AND equal to its own strip
    is ``""``, the permitted case. Checking trimming therefore checks both, and an assertion that
    can never fail is worse than no assertion: it reads as coverage.
    """
    for stage in STAGES:
        assert stage.notes == stage.notes.strip(), (
            f"{stage.key}.notes is whitespace-padded or whitespace-only. The web renders it verbatim "
            'in a bordered, tinted box — " " is truthy there, so it paints an EMPTY box — while '
            'Android\'s isNotBlank() renders nothing. Use notes="" to mean "no note".'
        )


def test_the_reviewers_marginal_comments_are_kept_in_the_code_and_never_serialised():
    """The other half of the rule, and the one that makes the removal safe to have done.

    ``phase_note`` is the record of what the source document deferred and why. It is REAL — a
    fifth of the fields carry one — and it must stay real, because "we deleted the notes" and "we
    deleted the reasoning" are two different changes and only the first was wanted. It must also
    never be published: it is a code comment that happens to be attached to a field.

    Both halves are asserted together so that neither can be satisfied by breaking the other.
    Deleting ``phase_note`` outright would pass an emit check trivially; publishing it would pass
    a "still populated" check trivially.
    """
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
    """Rewriting a note must NOT invalidate a single cached draft, and this pins why.

    It is the counterpart to ``test_the_version_changes_when_a_derivation_changes``. That one
    exists because a silent behaviour change slipped through a digest that was too narrow; this
    one exists because the obvious response — widen the digest until it covers everything — would
    fire the "drafts written against the previous registry" warning on every phone in the field
    for a typo correction, and a warning that cries wolf is a warning nobody reads.

    THE CONSEQUENCE OF THIS BEING TRUE, which is not obvious and is worth stating where somebody
    changing the digest will read it: an unchanged version means the clients' CACHES have to be
    content-aware on their own. ``StageSchemaStore.store`` rewrites its file on every fetch for
    this reason, and ``cacheRegistry`` in ``frontend/lib/designWorkshopStore.ts`` compares content
    before skipping a write — it used to skip on an equal version, which would have kept the
    reviewer quotes in IndexedDB indefinitely after they were removed from the server.
    """
    baseline = registry_version()
    stage = next(s for s in STAGES if s.notes)
    field = stage.entities[0].fields[0]

    with _swapped_attrs(stage, notes="Something else entirely."):
        assert registry_version() == baseline, "a stage note moved the digest"
    with _swapped_attrs(stage, purpose="A different purpose."):
        assert registry_version() == baseline, "a stage purpose moved the digest"
    with _swapped_attrs(field, help="Different help.", label="Different label."):
        assert registry_version() == baseline, "a field label or help text moved the digest"

    # And the registry really is back the way it was — a leaked mutation here would surface as an
    # unrelated failure in whichever test happened to run next.
    assert registry_version() == baseline


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
