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


def test_a_multi_valued_field_is_bounded_in_both_directions():
    """THE REGRESSION. Every multi-valued field accepted an unbounded array of unbounded strings.

    The ``is_multi`` branch RETURNS before the scalar-text branch where ``max_length`` is applied,
    so neither bound existed: no item cap anywhere in the codebase, and no length check on any one
    entry. The envelope bounds do not reach it — ``MAX_STAGE_ROWS`` counts rows and
    ``MAX_FIELD_KEYS`` counts keys per entry, while ONE key may hold an arbitrarily long array —
    and MULTI_ENUM's allow-list does not either, because duplicates of an allowed token pass the
    unknown-token check.

    What it cost is a permanent write amplified on every later read: the blob lands in a jsonb
    column that ``GET /{id}/stages`` serialises in full on every stage-index open, for every
    designer who can see the workshop, with no path that trims it and no error recording it.
    """
    tags = _f(type=FieldType.TAGS, label="Tags")
    assert coerce_value(tags, ["a"] * stage_schema.DEFAULT_MAX_ITEMS)[1] is None
    assert coerce_value(tags, ["a"] * (stage_schema.DEFAULT_MAX_ITEMS + 1)) == (
        None, f"Tags may hold at most {stage_schema.DEFAULT_MAX_ITEMS} entries"
    )
    # ONE entry, not the joined length: a designer given "Tags is too long" for a list of eight
    # short words and one pasted paragraph cannot tell which box to look in.
    assert coerce_value(tags, ["x" * stage_schema.DEFAULT_MAX_ITEM_CHARS])[1] is None
    assert "longer than" in coerce_value(
        tags, ["ok", "x" * (stage_schema.DEFAULT_MAX_ITEM_CHARS + 1)]
    )[1]
    # A REPEATED allowed token is still an entry. `["EMPORIUM"] * 1_000_000` used to be stored.
    multi = _f(type=FieldType.MULTI_ENUM, enum="MARKET_CHANNEL", label="Channels")
    assert coerce_value(multi, ["EMPORIUM"] * 5_000)[1] is not None


def test_a_declared_bound_on_a_multi_field_is_no_longer_a_silent_no_op():
    """``max_length`` and ``max_items`` are both consulted for a multi field.

    ``max_length`` was unreachable for these types — the branch returned first — so a field that
    declared one was declaring nothing.

    NO MULTI-VALUED FIELD DECLARES A ``max_length`` TODAY, which is why ``DEFAULT_MAX_ITEM_CHARS``
    is what actually bounds every TAGS box in the fleet, and this assertion keeps the declared path
    honest for the first field that needs one. ``max_items`` is no longer in that position: the two
    motif galleries declared 20 on 2026-08-25, and the live-registry half of that is pinned by
    ``test_the_capped_motif_galleries_refuse_the_twenty_first_photograph`` and
    ``test_exactly_two_fields_in_the_whole_registry_declare_a_cap`` further down. This test stays a
    statement about the MECHANISM on a synthesised spec, which is the right division: those two
    would still pass if ``coerce_value`` honoured a cap by truncating.
    """
    capped = _f(type=FieldType.TAGS, label="Tags", max_items=2, max_length=4)
    assert coerce_value(capped, ["ikat", "silk"]) == (["ikat", "silk"], None)
    assert coerce_value(capped, ["ikat", "silk", "wool"]) == (
        None, "Tags may hold at most 2 entries"
    )
    assert coerce_value(capped, ["sambalpuri"]) == (
        None, "Tags: one entry is longer than 4 characters"
    )


def test_max_items_crosses_the_wire_only_once_a_field_declares_one():
    """It follows the file's "only non-default keys are emitted" rule, so today's digest is safe.

    The whole registry crosses the wire on every app start and the bundled Android asset is
    compared against this dump; emitting a key nothing sets would rewrite every cached registry in
    the fleet for no client behaviour at all.
    """
    from app.services.stage_schema import field_to_dict

    assert "maxItems" not in field_to_dict(_f(type=FieldType.TAGS))
    assert field_to_dict(_f(type=FieldType.TAGS, max_items=12))["maxItems"] == 12


# --------------------------------------------------------------------------------------
# The two capped galleries — max_items ON A LIVE REGISTRY FIELD
#
# WHAT THE THREE TESTS ABOVE CANNOT SEE, and why these exist beside them rather than instead of
# them. Those pin the ARITHMETIC of the bound, on synthesised `_f(...)` specs, which is the right
# place for it: the cap is a property of `coerce_value`, not of any one field. What none of them
# asks is whether any field in the registry actually declares one — a `max_items` that no
# `FieldSpec` sets is a feature with a green test suite and no effect — or, the other direction,
# whether the `photos()` helper handed the same number to the seventeen galleries nobody asked
# about. `photos()` is the single most repeated shape in the registry (fifteen call sites), so an
# optional keyword added to it has a blast radius of fifteen stages, and a widened or narrowed
# ceiling is invisible until a designer meets a refusal they were never shown.
# --------------------------------------------------------------------------------------

#: The owner's stated ceiling of 2026-08-25, and the ONLY two fields it was stated for.
#:
#: Written as a literal rather than derived from the registry for the same reason
#: `test_the_designer_boxes_stage_3_gained_are_all_there` names its boxes by hand: a test that
#: computed its expectation from the thing under test would pass for any registry at all, which is
#: precisely the failure mode here — the whole risk is that the number reached fields nobody chose.
CAPPED_GALLERIES: dict[str, int] = {
    "motifPhotos": 25,              # relabelled to "Traditional motif photographs", key KEPT
    "contemporaryMotifPhotos": 25,  # the new half of the traditional/contemporary pair
    "lostCraftPhotos": 25,          # 2026-08-30: "Lost craft / products", stated as "upto 25"
}

#: The owner's stated FLOOR of 2026-08-28 — "25 each, and all 25 required" — and the only two
#: fields it was stated for. Equal to the ceiling above, which is not a coincidence to be factored
#: out: the owner asked for exactly twenty-five, so the gallery is complete at the moment it is
#: full. They are declared and asserted separately because they are enforced in DIFFERENT PLACES
#: (`coerce_value` refuses above the ceiling; `stage_completeness` scores below the floor and
#: nothing refuses), and a single constant would hide that the day one of them moves.
#: NO LONGER ``dict(CAPPED_GALLERIES)``, AND THE DAY IT STOPPED BEING SO IS THE POINT OF THE
#: SENTENCE ABOVE. It was a copy for as long as the two owner instructions happened to agree — "25
#: each, and all 25 required" gave the motif galleries an equal ceiling and floor. On 2026-08-30 a
#: THIRD gallery arrived with the ceiling and deliberately WITHOUT the floor: the owner's words were
#: "which would take upto 25 images", which states a maximum and states no minimum, and a floor on
#: photographs of a craft the cluster no longer practises would make stage 4 permanently incomplete
#: for every workshop in the country. The derivation would have handed it one silently. Two
#: literals now, which is what the sentence above always said they were for.
FLOORED_GALLERIES: dict[str, int] = {
    "motifPhotos": 25,
    "contemporaryMotifPhotos": 25,
}


def test_the_capped_motif_galleries_refuse_the_twenty_sixth_photograph():
    """The owner's ceiling is enforced by the SERVER, on the real field, and refuses rather than trims.

    THE DEFECT THIS PREVENTS IS NOT AN OVERSIZED ARRAY — `DEFAULT_MAX_ITEMS` already stopped that
    at 200. It is a cap that exists in a picker and nowhere else. Both clients now read `maxItems`
    off the published registry and stop the picker at twenty-five, and a designer who believes that
    IS the rule will hit the server through every other door: an Android draft syncing a gallery
    assembled by an older build, a bulk import, a direct API call (`validate_entry`'s docstring is
    explicit that a phone one release ahead is a supported caller), or the same handset after a
    retry loop appends rather than replaces. If the declaration were client-side advice, all four
    would store thirty photographs into a report whose figure list promises twenty-five.

    A REFUSAL, NOT A TRUNCATION, AND THE SECOND HALF IS THE ASSERTION A REFACTOR WOULD LOSE.
    Silently keeping the first twenty-five of a twenty-six-photograph array is this repository's
    most repeated bug class — the designer is told "Stage saved" and one photograph is gone with
    nothing on screen. So `stored is None` is asserted as well as the message: `save_stage` restores
    a refused key from `previous`, so nothing is lost either, and the message names the box.

    THE NUMBER WAS 20 UNTIL 2026-08-28 AND THE RAISE WAS SAFE FOR EVERY SHIPPED CLIENT, which is
    worth stating here because this test is where somebody checks that claim. A ceiling that only
    ever REFUSES can be widened without breaking a caller: every body that saved at 20 still saves
    at 25, and a client still reading `maxItems: 20` off a stale registry under-offers its picker
    rather than over-posting. Narrowing it is the direction that strands stored data, and this
    assertion is what would catch that being done by accident.

    AGAINST THE LIVE FIELD AND NOT A SYNTHESISED ONE, which is the entire point of this test
    existing next to `test_a_declared_bound_on_a_multi_field_is_no_longer_a_silent_no_op`. That one
    proves `coerce_value` honours a declared cap; this one proves these two galleries declare it.
    """
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

        # Media ids, because that is what an IMAGE_LIST holds: a cuid is 25 characters, comfortably
        # inside DEFAULT_MAX_ITEM_CHARS, so the per-ITEM bound cannot be what refuses these and the
        # test cannot pass for the wrong reason.
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

    # AND THE CAP CROSSES THE WIRE, because a ceiling only the server knows about is a ceiling the
    # designer discovers after attaching the twenty-first photograph — see `photos()`' docstring.
    for key, cap in CAPPED_GALLERIES.items():
        published = field_to_dict(background.field(key), "clusterBackground")
        assert published["maxItems"] == cap, published


def test_exactly_three_fields_in_the_whole_registry_declare_a_cap():
    """The blast radius of one optional keyword on a helper fifteen galleries share.

    `photos()` gained `max_items` and `help` on 2026-08-25. It is called fifteen times across
    fifteen stages, and its own docstring states why the parameter defaults to 0: "widening or
    narrowing every one of the eighteen galleries in this registry from one keyword here would be a
    silent change to seventeen stages nobody asked about." This is the test that makes that
    sentence true rather than intended.

    BOTH DIRECTIONS OF WRONG ARE SILENT, which is why the assertion is set EQUALITY and not a
    superset:

    * A CAP THAT LEAKED. A default of, say, 20 on the helper would silently narrow `stepPhotos`,
      `finalPhotos` and twelve others. Nothing fails. The symptom arrives weeks later as a designer
      in a cluster being refused their twenty-first process photograph on a stage that has always
      taken as many as they could shoot, and `validate_registry` has nothing to say about it
      because a cap is a legal declaration.
    * A CAP THAT VANISHED. Deleting `max_items=20` from one motif gallery restores the unstated 200
      and both clients stop showing the ceiling. Also nothing fails.

    THE ENTIRE REGISTRY IS SWEPT, not only the IMAGE_LISTs, because `max_items` is legal on every
    multi-valued type — a TAGS or MULTI_ENUM box that quietly gained one would refuse a designer's
    selections with no owner decision behind the number.
    """
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


# --------------------------------------------------------------------------------------
# The two FLOORED galleries — min_items, and the save it must never refuse
#
# The owner's instruction of 2026-08-28 was "25 each, and all 25 should be required". The word
# "required" is the whole risk in this section: this registry already has a `required` flag, it is
# enforced by `validate_entry` on every save, and using it here would have been the obvious reading
# and a destructive one. `test_a_short_gallery_still_saves` below is the assertion that pins the
# reading that was actually taken, and it is the most important test in this file's photo section.
# --------------------------------------------------------------------------------------


def _stage(number: int):
    return next(s for s in STAGES if s.number == number)


def _media_ids(n: int) -> list[str]:
    """`n` plausible media ids. A cuid is 25 characters, well inside DEFAULT_MAX_ITEM_CHARS, so the
    per-ITEM bound can never be what these tests are measuring."""
    return [f"cm{i:023d}" for i in range(n)]


def test_exactly_two_fields_in_the_whole_registry_declare_a_floor():
    """The blast radius of `min_items`, which is strictly worse than `max_items`' and silent.

    A LEAKED CEILING EVENTUALLY REFUSES SOMETHING AND SOMEBODY REPORTS IT. A leaked FLOOR refuses
    nothing at all — it is scored in `stage_completeness` and validated nowhere — so the symptom is
    that every workshop in the country is permanently incomplete, its readiness screen lists
    photographs nobody asked for, and `build_report` warns for ever. There is no error, no log and
    no way out of it from the app; it takes a deploy. `photos()` is called fifteen times, so one
    default on that helper would do it to fifteen stages at once.

    SET EQUALITY, IN BOTH DIRECTIONS, for the same reason the cap test uses it: a floor that
    VANISHED is equally silent — both galleries would go back to reading complete at one
    photograph, and the owner's requirement would be gone with nothing failing.

    THE WHOLE REGISTRY IS SWEPT, not only the IMAGE_LISTs: `min_items` is legal on any multi-valued
    type, and a TAGS box that quietly gained one would make its stage uncompletable just as surely.
    """
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

    # AND IT CROSSES THE WIRE, which is the point of declaring it rather than hard-coding 25 in
    # three codebases. Both clients need it twice over: to draw the "20 of 25" progress bar, and to
    # score the stage the way the server scores it.
    background = _entity("clusterBackground")
    for key, floor in FLOORED_GALLERIES.items():
        published = field_to_dict(background.field(key), "clusterBackground")
        assert published["minItems"] == floor, published
    assert "minItems" not in field_to_dict(background.field("clusterPhotos")), (
        "an unfloored gallery published a minimum; emission must be conditional on declaration, "
        "exactly as maxItems' is"
    )


def test_a_short_gallery_still_saves():
    """**THE ONE THAT MATTERS.** "All 25 required" must never become a refusal on the write path.

    THE FAILURE THIS FORBIDS, END TO END. A designer is in a village with no signal and twenty good
    photographs. If the server refuses a stage whose gallery holds fewer than twenty-five:

      * Android's `saveOrQueue` does NOT queue a 4xx — a body the server refuses is a record
        DROPPED, not retried — so the twenty are gone, not deferred; and
      * on the `submit=true` path the loss happens even WITH a connection, and silently:
        `validate_entry` omits a field it errored on, and `save_stage` then restores that key from
        `previous` ("a rejected field must not destroy the value already stored under it"), so the
        gallery REVERTS to yesterday's contents and the 422 is raised after the transaction has
        already committed.

    This is stage 4 of 22, so either outcome blocks the entire workshop from ever being saved.

    BOTH ARMS ARE ASSERTED, and the `enforce_required=True` arm is the one a future refactor would
    lose: it is tempting to read "required" as "enforce it at submit time like every other required
    field", and that arm is what makes the deliberate difference visible. What a short gallery costs
    is scored, not refused — see `test_a_gallery_one_short_leaves_the_stage_incomplete`.
    """
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

    # AND THE FLOOR IS NOT IN `coerce_value` EITHER, which is the door every other writer comes
    # through: a bulk import, a direct API call, a phone one release ahead.
    stored, error = coerce_value(entity.field("motifPhotos"), _media_ids(1))
    assert error is None and stored == _media_ids(1), (error, stored)


def test_a_gallery_one_short_leaves_the_stage_incomplete():
    """24 is not complete, 25 is, and the outstanding item says which — with its count.

    THE COUNT IN THE LABEL IS AN ASSERTION AND NOT A COSMETIC. Every other string in `missing`
    means "nothing was recorded", and this list is printed verbatim in three places a designer or a
    ministry officer reads — the readiness screen, `build_report`'s `X-Report-Warnings`, and the
    completeness annexure's Outstanding column. "Traditional motif photographs", bare, under the
    heading "required field(s) not recorded", tells a designer holding twenty-four photographs that
    the app has lost them.

    24 AND 25 AND NOT 0 AND 25: the interesting boundary is one short, because that is the only one
    that distinguishes a real floor from `_is_filled`, which any non-empty list already satisfies.
    """
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

    # The other gallery is untouched at 25 in both, so this test cannot pass by scoring the pair
    # together.
    assert not [m for m in at_24.missing if "Contemporary motif" in m], at_24.missing


def test_the_submit_gate_refuses_a_workshop_whose_gallery_is_one_short():
    """`is_complete` is what the submit gate reads, and it must be false at 24 of 25.

    WHY THIS IS ASSERTED THROUGH `is_complete` AND NOT THROUGH A 422. In this repository "the
    workshop cannot be submitted" IS `stage_completeness`: `is_complete` and `missing` are what the
    readiness screens list, what `build_report` warns on, and what both clients' ports
    (`scoreStageData`, `computeStageCompleteness`) mirror field for field. Wiring the floor into the
    422 instead would have reverted the designer's gallery — see `test_a_short_gallery_still_saves`.

    THE STAGE IS FILLED OTHERWISE, deliberately: every other required field of stage 4 is answered,
    so the only thing standing between this workshop and a complete stage 4 is the twenty-fifth
    photograph. Without that the test would pass on the four text fields being blank and would
    still pass if the floor were deleted.
    """
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
    """`validate_registry` catches the two declarations that would make a stage uncompletable.

    NEITHER SHAPE FAILS ANYWHERE ELSE, which is why the registry has to refuse them at definition
    time. A floor on a scalar box is published to both clients and counted by nothing. A floor above
    the ceiling asks for a body `coerce_value` will not accept — and because a minimum refuses no
    save, the only symptom of either is a workshop that can never be finished.

    THE CEILING IS COMPARED AGAINST ITS EFFECTIVE VALUE. `max_items=0` means `DEFAULT_MAX_ITEMS`,
    never "unbounded", so a floor of 500 on a gallery declaring no cap must still be refused.
    """
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
    """The asymmetry the bundled asset depends on, asserted in both directions.

    `registry_version()` is the refetch signal. A CEILING stays out of it: a client that has not
    heard about one still posts a legal body, because `coerce_value` refuses the over-long array
    server-side either way, so re-invalidating every cached draft for a picker hint would be the
    wrong trade. A FLOOR has no such backstop — it is scored and never validated — so a handset
    that has never fetched since it was declared scores the stage complete at twenty photographs
    and tells the designer they may leave the cluster.

    THIS PAIR IS ALSO THE GUARD ON THE ASSET TESTS. `test_the_bundled_android_asset_matches_the_
    registry_it_was_dumped_from` compares versions, so if this test's first half ever flipped, a
    cap change would silently pass a staleness check it did not deserve.
    """
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
    """A gate that says "quality checked" and cannot judge exposure is the failure this repo hates.

    `DwImageQuality.findQualityIssues` measures BLUR, LOW_RESOLUTION and DUPLICATE. OVEREXPOSED,
    UNDEREXPOSED and WRONG_SUBJECT are tokens in stage 21's `QUALITY_FLAG` enum that NO measurement
    in this product makes — STAGE_21's own note says so in the same words, and this help text is the
    other place a designer reads the claim. A designer who reads "checked before it uploads" and
    assumes a dark photograph was judged will stop looking at their own screen, which is exactly the
    check the product does not have.

    THE FLOORS THEMSELVES ARE DELIBERATELY NOT ASSERTED HERE, and must not be added: they are client
    constants (`BLUR_VARIANCE_FLOOR`, `MIN_LONG_EDGE_PX`), and a registry help string repeating a
    number that lives in two other codebases goes stale silently the day it moves. The refusal on
    screen prints the reading and the floor it was measured against; this text names the FAULTS.
    """
    background = _entity("clusterBackground")
    for key, floor in FLOORED_GALLERIES.items():
        help_text = background.field(key).help
        assert f"All {floor} are required" in help_text, help_text
        for measured in ("blur", "low resolution", "duplicates"):
            assert measured in help_text, (
                f"{key}'s help does not name {measured!r}, which the detector does measure: "
                f"{help_text!r}"
            )
        assert "Exposure and subject are not checked" in help_text, (
            f"{key}'s help claims a quality check without naming the two judgements nothing in "
            f"this product makes: {help_text!r}"
        )
        assert not any(str(n) in help_text for n in (60, 20)), (
            f"{key}'s help prints a threshold that lives in a client constant: {help_text!r}"
        )


def test_the_photos_helper_puts_its_help_on_the_gallery_and_never_on_the_caption():
    """The other half of the same keyword, and the half with no error path at all.

    `help` reached `photos()` in the same edit as `max_items`, and it is the more dangerous of the
    two because a wrong cap eventually refuses something whereas wrong help text just sits on a
    form telling a designer the wrong thing for ever. `photos()` states where it lands — "``help``
    lands on the gallery and not on the caption. The caption's own guidance is its label" — and a
    help string that leaked onto fifteen caption boxes would put "Up to 20 photographs…" under a
    one-line text input, which is advice about a different field.

    THE ENTRIES THAT ARE NOT LEAKS ARE NAMED SO NOBODY DELETES THEM, and there are five of them
    across the two sets below: `existingProduct.productPhotos` and four caption boxes. None came
    from `photos()`. Every one is a `fromref` box carrying the reference-carry sentence every
    hydrated field in this registry carries ("Filled in from the linked record when one is
    chosen…"), and every one predates this wave. They are listed by hand because an assertion that
    quietly allowed "any field with help" would allow exactly the leak this test exists for.
    """
    galleries_with_help = {
        f"{entity.key}.{field.key}"
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.type is FieldType.IMAGE_LIST and field.help
    }
    assert galleries_with_help == {
        "clusterBackground.motifPhotos",
        "clusterBackground.contemporaryMotifPhotos",
        "clusterBackground.lostCraftPhotos",  # 2026-08-30, stated help, same shape as its siblings
        "existingProduct.productPhotos",   # the hydration sentence, not photos() — see docstring
    }, (
        f"{galleries_with_help} carry gallery help. A string that leaked out of photos() tells "
        "fifteen stages' designers about a ceiling that is not theirs."
    )

    # THE CAPTIONS, AND WHY THIS HALF IS A PINNED SET RATHER THAN A SWEEP FOR EMPTINESS.
    #
    # The first draft of this test asserted that NO caption in the registry carries help, on the
    # reasoning that `photos()` never puts one there. Four do, and the way they were found is worth
    # keeping: `photos()`-produced pairs are structurally INDISTINGUISHABLE from hand-declared ones.
    # Both are a field keyed `<gallery>Caption` with `caption_for` naming the gallery — the helper
    # follows the registry's convention rather than marking its output — so there is no predicate
    # that means "came from photos()". Every one of the four belongs to a `fromref` box and carries
    # the shared reference-carry sentence, which is a fact about hydration and not about a ceiling.
    #
    # So the set is pinned by hand with its reason, exactly as the gallery set above is. That is the
    # stronger check in any case: `photos()` passing its `help` to the caption as well as the
    # gallery would add thirteen entries here and fail loudly, whereas an "is it empty" sweep could
    # only ever have been deleted.
    hydration_captions = {
        "workshopSetup.craftPhotoCaption",
        "participant.photoCaption",
        "tool.photoCaption",
        "existingProduct.productPhotosCaption",
    }
    captions = [
        (f"{entity.key}.{field.key}", field.help)
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.caption_for and field.key == f"{field.caption_for}Caption"
    ]
    # A FLOOR AND NOT AN EXACT COUNT. Twenty-six pairs today (fifteen from `photos()`, eleven
    # declared directly); a floor of twenty leaves room for a retirement or two while still refusing
    # the one way this can lie — a matcher somebody broke, which would make both assertions below
    # pass over an empty list. The same reasoning, and the same shape, as the `len(vectors) > 30`
    # floor in the vector-table test at the end of this file.
    assert len(captions) >= 20, (
        f"only {len(captions)} caption pairs found; the assertions below are measuring nothing"
    )
    helped = {path for path, help_text in captions if help_text}
    assert helped == hydration_captions, (
        f"{sorted(helped)} are caption boxes carrying help, not the four hydrated ones. photos() "
        "puts help on the gallery; a string that landed on a caption is guidance about a different "
        "field, printed under a one-line text input."
    )
    # AND WHAT THEY SAY IS THE HYDRATION SENTENCE, not a ceiling. This is the assertion that would
    # actually catch a leak: if `photos()` started passing `help` through to the caption, these four
    # would still be the only captions with help on the day a capped gallery's caption inherited
    # "Up to 20 photographs…" — because `motifPhotosCaption` would then join the set above AND its
    # text would be about a photograph count.
    for path, help_text in captions:
        if not help_text:
            continue
        assert help_text.startswith("Filled in from the linked record"), (
            f"{path} carries help that is not the reference-carry sentence: {help_text!r}. A "
            "caption's own guidance is its label; anything else here is advice about the gallery."
        )


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


# --------------------------------------------------------------------------------------
# FieldSpec.text_format — the declared shape of a scalar text value
#
# WHAT THESE TESTS ARE ABOUT, because it is not a new type or a new box. A stage row MIRRORS a
# record page's fields, and every one of the record page's validators was present and working two
# inches away — attached to the box nobody prints. The value the REPORT prints is the stage copy,
# and for that copy `coerce_value` checked a length and nothing else: EMAIL, PHONE and TEXT all
# went through one scalar-text arm. So a typed "hello world 1234" passed
# `participant.aadhaarNumber`'s bound of 20, was masked to "XXXX XXXX 1234" by `store_masked`, and
# was printed as a national identity number in a document submitted to a ministry. The validations
# had not gone missing; they were attached to the wrong box.
# --------------------------------------------------------------------------------------


#: The three-language contract. See the file's own `about` for why a checked-in table and not a
#: parity comment: email is the control experiment for whether prose keeps three implementations
#: in step, and the answer measured on 2026-08-24 was three different answers and nobody noticing.
VECTOR_TABLE = (
    pathlib.Path(__file__).resolve().parents[2] / "shared" / "text-format-vectors.json"
)

#: A Verhoeff-valid Aadhaar, computed rather than written down. A literal here would rot the first
#: time somebody edited a digit of it, and it would rot into a test that passes for the wrong
#: reason: the number would simply be refused by the checksum arm.
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
    """THE DEFECT THIS FEATURE EXISTS TO END, and it shipped.

    `participant.aadhaarNumber` was a plain TEXT box with a 20-character bound and
    `store_masked=True`. `mask_aadhaar` strips separators and keeps the LAST FOUR CHARACTERS OF
    ANYTHING, so "hello world 1234" — fourteen characters, comfortably inside the bound —
    normalised to "helloworld1234" and was stored as "XXXX XXXX 1234". A design workshop's stage
    reads do not pass through `records._redact_sensitive`, a `DesignWorkshopViewer` is a grantee,
    hydration copies at save time and the report never re-resolves: so a typo, in the shape of a
    government identity number, became a permanent line of a ministry document that nothing
    downstream could tell from a real one.

    THE MASKING IS WHAT MADE IT UNDETECTABLE. That is why the format is checked BEFORE the mask and
    not after: reversed, the ordering MANUFACTURES the defect it is meant to prevent — the value is
    a well-formed mask by the time anybody looks at it.

    Both halves are asserted, and the second is the one a later refactor is likely to lose: not
    merely that an error came back, but that nothing was stored.
    """
    aadhaar = _participant_field("aadhaarNumber")
    assert aadhaar.text_format is TextFormat.AADHAAR

    stored, error = coerce_value(aadhaar, "hello world 1234")
    assert error == "Aadhaar number must be 12 digits — remove any letters or symbols.", error
    assert stored is None, (
        f"{stored!r} was stored: a typo in the shape of an Aadhaar number, which is the whole of "
        "the defect. The format check has to run BEFORE store_masked"
    )

    # A REAL NUMBER IS STILL ACCEPTED AND STILL MASKED, which is the second half of the owner's
    # 2026-08-24 instruction ("a designer entitled to the full number can type over the mask") and
    # the reason this is a format rather than a refusal of full numbers.
    real = _valid_aadhaar()
    assert coerce_value(aadhaar, real) == (f"XXXX XXXX {real[-4:]}", None)


def test_a_hydrated_mask_survives_re_coercion_without_a_refusal():
    """The trap a naive Aadhaar validator falls into, on a row nobody touched, for ever.

    `hydrate_entries` writes `mask_identity_number(...)` into this box, and `validate_entry`
    re-coerces EVERY field on EVERY save. A predicate that knew only `aadhaar_error` would answer
    "Aadhaar number must be 12 digits" against the mask; `save_stage` restores the refused key from
    `previous` (the same mask), so the stored value would never change and the error would reappear
    on every save, for ever, naming a fault the designer cannot fix because the digits are not
    theirs to see.

    Idempotence is asserted as well as acceptance: `mask_aadhaar` on its own output must be a
    no-op, or the value would drift a little on each save.
    """
    aadhaar = _participant_field("aadhaarNumber")
    for mask in ("XXXX XXXX 0124", "XXXX XXXX XXXX"):
        stored, error = coerce_value(aadhaar, mask)
        assert error is None, f"{mask!r} was refused: {error}"
        assert stored == mask, f"{mask!r} came back as {stored!r}"


def test_the_mask_predicate_refuses_what_is_masked_aadhaar_accepts():
    """The accept-arm is a SHAPE, and choosing the other one would have re-shipped the defect.

    `is_masked_aadhaar`'s rule is "an X anywhere". That is correct where it is used — a masked
    value posted back by `ArtisanUpdate` means "I was not shown the real number and did not change
    it", and the route DROPS the key before the write (`drop_masked_identity_numbers`), so nothing
    it accepts is ever stored. `coerce_value` has no drop: whatever the format accepts is STORED
    and then masked. Under `is_masked_aadhaar`, "XxamplE 1234" is a mask, passes, and is stored as
    "XXXX XXXX 1234" — the original defect, unchanged, now behind a validator.

    So both facts are asserted together: the old predicate says yes, the new one says no.
    """
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

    # AND THE CASE-SENSITIVITY IS DELIBERATE. Both mask producers emit upper-case X's, and folding
    # case is exactly what would let a run of prose containing "xxxx" through the door.
    assert aadhaar_or_mask_error("xxxx xxxx 0124") is not None


def test_store_masked_requires_an_aadhaar_format():
    """The highest-value line in the whole feature, and it is a registry rule rather than a check.

    `store_masked` on its own is `mask(anything)` — precisely how "hello world 1234" became a
    government identity number in a ministry document. Pairing the flag with the format in
    `validate_registry` means any FUTURE field that declares the flag inherits the guard by
    construction, instead of by somebody remembering the paragraph that explains why.
    """
    aadhaar = _participant_field("aadhaarNumber")
    assert aadhaar.store_masked and aadhaar.text_format is TextFormat.AADHAAR

    with _swapped_attrs(aadhaar, text_format=TextFormat.NONE):
        problems = validate_registry()
        assert any("store_masked without text_format=AADHAAR" in p for p in problems), problems

    assert validate_registry() == []


def test_a_format_may_only_be_declared_on_a_type_that_reaches_the_branch_enforcing_it():
    """The same rule, and the same sentence, as the `store_masked` rule directly above it.

    `coerce_value` enforces a format inside its scalar-text branch and nowhere else. Declared on an
    INT, a DATE or an IMAGE_LIST the format would serialise to both clients, be published in the
    digest, appear in the bundled Android asset — and refuse nothing on the way in. A published
    flag nothing enforces is the silent failure this whole feature exists to end, rebuilt inside
    the feature.
    """
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

    # AND EVERY TYPE THE RULE ALLOWS REALLY DOES REACH THE BRANCH. A rule that permitted a type
    # whose values never pass through the format check would be the same silent failure with a
    # green test beside it.
    for allowed in (FieldType.TEXT, FieldType.LONG_TEXT, FieldType.URL,
                    FieldType.PHONE, FieldType.EMAIL):
        spec = _f(label="Contact", type=allowed, text_format=TextFormat.EMAIL)
        assert coerce_value(spec, "not-an-address")[1] is not None, (
            f"a format declared on {allowed.value} is accepted by validate_registry and enforced "
            "by nothing"
        )


def test_the_version_changes_when_a_field_gains_a_format():
    """A format is BEHAVIOUR, so it belongs in the digest — the rule that docstring already states.

    A handset that has never reached the network and does not know a field has gained a format goes
    on accepting values the server will now refuse, shows no error where the browser shows one, and
    reports the refusal only after a save it presented as complete. That is the same class of
    silent disagreement as a derivation that stopped computing, which is why the digest covers it.
    """
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

    # AND CHANGING WHICH format IS ALSO A CHANGE. A digest that hashed only "has a format" would
    # pass the assertion above while missing a correction from PHONE_IN to EMAIL.
    email = _participant_field("email")
    with _swapped_attrs(email, text_format=TextFormat.PHONE_IN):
        assert registry_version() != baseline
    assert registry_version() == baseline


def test_a_legacy_bare_ten_digit_phone_is_not_refused_on_re_save():
    """The trap that made writing a server-side phone rule dangerous, one field over.

    `Artisan.phone` has never had a server-side rule — `ArtisanCreate`/`ArtisanUpdate` declare
    `phone: str | None` with no validator — so the table holds bare numbers written before the
    dial-code picker existed, and `hydrate_entries` has been copying them into `participant.phone`
    ever since. `PhoneField.tsx`'s parser is explicit about them: "Bare numbers (legacy rows) are
    Indian nationals: 10 digits under +91." A `phone_error` that read a missing dial code as
    malformed would refuse EVERY one of those rows on its next save, and because `save_stage`
    restores a refused key from `previous` the designer would get a permanent red error on a box
    they never touched, over a value they cannot correct.
    """
    phone = _participant_field("phone")
    assert phone.text_format is TextFormat.PHONE_IN

    for legacy in ("9876500001", "9876 500 001"):
        stored, error = coerce_value(phone, legacy)
        assert error is None, f"{legacy!r} was refused: {error}"
        assert stored == legacy, "a legacy number must be kept as typed, not reformatted"

    # The composed shape both clients write, and the nine-digit number the audit found saving
    # cleanly because the stage mounts PhoneField with `mirror={false}` (advisory, not blocking).
    assert coerce_value(phone, "+91 9876500001")[1] is None
    assert coerce_value(phone, "+91 987650000")[1] == "Enter a 10-digit number for +91."


def test_participant_email_is_bounded():
    """A format is a shape, not a length, and this field had NO length at all.

    `participant.email` declared no `max_length`, so it was unbounded: the report's participant
    block, the .docx, the .xlsx and every export would carry whatever a client posted. The bound is
    chosen NOW, while nothing is stored, for the reason written at `pincode` and at
    `aadhaarNumber`: `validate_entry` re-coerces EVERY field on EVERY save, so a bound added later
    becomes a refusal on a box the designer never touched.
    """
    email = _participant_field("email")
    assert email.type is FieldType.EMAIL
    assert email.text_format is TextFormat.EMAIL
    assert email.max_length == 254, "the longest an RFC-conformant address can be"

    # THE BOUND BITES BEFORE THE FORMAT, which is what keeps an over-long answer's refusal its own
    # rather than being reported as a malformed address.
    long_but_well_formed = "a" * 250 + "@example.org"
    stored, error = coerce_value(email, long_but_well_formed)
    assert stored is None and error is not None
    assert "longer than 254" in error, error


def test_every_email_and_phone_field_in_the_registry_declares_a_format():
    """A TYPE WITHOUT A RULE IS WHAT THIS ALL WAS, so the sweep is the part that has to hold.

    `FieldType.EMAIL` and `FieldType.PHONE` are captured, published and printed exactly like TEXT:
    both fall into the same scalar-text arm of `coerce_value`. Declaring the type therefore bought
    the designer a keyboard on the handset and nothing else. Adding the next such field without a
    format is the way this gap comes back, and it would look completely reasonable in review.
    """
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
    """Both clients preview the rule, and a preview they were never told about is no preview.

    The refusal reaches the exact box already (`placeStageErrors` -> `EntityForm` -> `FieldHint`
    with `role="alert"`), so this is not about the error path. It is about the ordinary case never
    needing the round trip at all — a fleet often on one bar of signal, where a save that comes
    back refused looks like a value that silently reverted.
    """
    published = {
        f"{entity.key}.{field.key}": field_to_dict(field, entity.key).get("format")
        for _stage, entity in all_entities()
        for field in entity.fields
        if field.text_format is not TextFormat.NONE and not field.deprecated
    }
    assert published, "no field declares a format; this test is measuring nothing"
    for path, value in published.items():
        assert value, f"{path} declares a format that field_to_dict does not publish"

    # And the default is still omitted, on the same "only non-default keys" rule as everything
    # around it: the whole registry crosses the wire on every app start.
    plain = _participant_field("name")
    assert plain.text_format is TextFormat.NONE
    assert "format" not in field_to_dict(plain, "participant")


def test_the_pehchan_predicate_accepts_a_mask_which_is_why_no_field_declares_it():
    """A no-op that LOOKS like protection is worse than the gap it appears to close.

    `TextFormat.PEHCHAN` exists and is enforceable, and the obvious next move would be to declare
    it on `participant.artisanCardNo` — which also carries a mask from hydration. `pehchan_error`
    ACCEPTS that mask: separators come off, fourteen alphanumerics remain, comfortably inside the
    4-32 window, and there is no checksum to fail. `schemas/records.py` records that this exact
    acceptance once stored a mask OVER a real card number — 200 OK, revision recorded, regulated
    identifier gone. (The Aadhaar mask fails its own validation instead, which is the only reason
    that half was noticed.)

    So the format would refuse nothing that box could hold. Attaching it needs its own mask-aware
    predicate — the `aadhaar_or_mask_error` shape — and an owner decision about
    `IdentityCardCapture kind="PEHCHAN"`, which exists to write full numbers into that field. This
    test is the record of that being a deferral rather than an oversight, and it fails the day
    somebody declares the format without the predicate.
    """
    from app.services.artisan_identity import normalize_pehchan, pehchan_error

    assert pehchan_error(normalize_pehchan("XXXX XXXX 3456")) is None, (
        "pehchan_error now refuses a mask, so the reason artisanCardNo declares no format has "
        "changed — re-read this test's docstring before deleting it"
    )
    card = _participant_field("artisanCardNo")
    assert card.text_format is TextFormat.NONE
    assert card.store_masked is False


def test_the_three_implementations_agree_on_the_shared_vector_table():
    """The server's half of the one mechanism that keeps three languages in step.

    Email is the control experiment for whether parity PROSE works: the same intent stated in a
    private regex in `ArtisanForm.tsx`, again as that input's `pattern` attribute, and again in the
    Kotlin port of `coerce_value` — three implementations, three answers, nobody noticing. The web
    accepted an address with no `@`, Android refused it, the server checked nothing.

    So the rule is a checked-in table read by a test on each side, which is structurally what
    `test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from` does for the schema
    and the only reason that asset cannot silently drift. The MESSAGES are asserted and not just
    the accept/refuse verdict: "the same checks, in the same order, with the same sentences" is
    written in three files in this repository and it is a promise to the researcher, who must read
    the same instruction on the laptop that they read on the handset.
    """
    payload = json.loads(VECTOR_TABLE.read_text(encoding="utf-8"))
    vectors = payload["vectors"]
    # The same floor both client readers assert (`DwTextFormatParityTest`,
    # `text-format-parity-unit.spec.ts`): a table somebody emptied while "regenerating" it
    # would make every row below pass vacuously, which is the one way a parity test can lie.
    assert len(vectors) > 30, "the shared case table is suspiciously small"

    # Every format that a field actually declares must be exercised, or a row could be added to
    # the registry with no vector behind it.
    declared = {
        f.text_format.value
        for _s, e in all_entities()
        for f in e.fields
        if f.text_format is not TextFormat.NONE and not f.deprecated
    }
    covered = {row["format"] for row in vectors}
    assert declared <= covered, f"{declared - covered} is declared and has no vectors"

    # THE SHARED ROWS, then the server-only ones — of which there are NONE today, and the loop still
    # reads the key rather than assuming it is empty.
    #
    # The split is not bookkeeping: a client reader asserting a `server_only` row would be asserting
    # a rule its own control cannot produce. The single row that was ever in there — PHONE_IN "not a
    # number" — turned out not to qualify, and how it failed to is the lesson: "no client can reach
    # it" was measured on the CONTROL (a phone box only accepts digits) and not on the DATA (nothing
    # validates `Artisan.phone`, `hydrate_entries` copies it into `participant.phone`, and both
    # clients are then handed it). It refused on every save while both previews called it clean,
    # which is a silent revert. A row belongs in `server_only` only when no client can produce the
    # value AND no client can be handed it; `divergences_to_reconcile` in the same file carries the
    # three that have been closed and the one residual that is not pinned.
    for row in vectors + payload["server_only"]:
        spec = _f(label="Value", type=FieldType.TEXT,
                  text_format=TextFormat(row["format"]))
        _stored, error = coerce_value(spec, row["value"])
        assert error == row["error"], (
            f"{row['format']} {row['value']!r}: expected {row['error']!r}, got {error!r}"
        )


# --------------------------------------------------------------------------------------
# The stage-3 designer boxes — the SAME feature, one entity over
#
# Stage 3's `workshopPlan` gained nineteen boxes on 2026-08-25 so that everything a designer types
# on the Designer Page reaches every report. Three of them are shaped values, and they arrived with
# exactly the exposure `TextFormat`'s docstring describes: the Designer Page validates its own
# `phone`, `email` and `pincode` columns, and the box the REPORT prints is the stage copy. A stage
# copy that checked only a length would put a nine-digit phone number and an address with no `@`
# into a document submitted to a ministry, with the validated originals sitting two inches away on
# a page nobody prints from.
#
# The tests below are deliberately NOT a fourth statement of the three rules. `phone_error`,
# `email_error` and `pincode_error` are the record path's own functions, the shared vector table
# pins their sentences in three languages, and restating a sentence here would make a fourth copy
# to keep in step. What is asserted instead is the JOIN: that the stage box answers exactly what
# the record page answers, on the live FieldSpec, through `coerce_value`.
# --------------------------------------------------------------------------------------

#: The three shaped boxes stage 3 gained, and the type each one is declared on.
#:
#: Named by hand rather than read off the registry, and the reason is not style: the risk being
#: guarded is a box that declares NO format, and a table derived from `text_format` cannot see one
#: of those. Same argument as `test_the_designer_boxes_stage_3_gained_are_all_there`.
DESIGNER_SHAPED_BOXES: tuple[tuple[str, TextFormat, FieldType], ...] = (
    ("designerPhone", TextFormat.PHONE_IN, FieldType.PHONE),
    ("designerEmail", TextFormat.EMAIL, FieldType.EMAIL),
    # TEXT AND NOT A TYPE OF ITS OWN, which is why this one is the easiest of the three to forget:
    # there is no `FieldType.PINCODE`, so the sweep that catches a formatless EMAIL/PHONE field
    # (`test_every_email_and_phone_field_in_the_registry_declares_a_format`) cannot see a PIN code
    # box at all. `test_a_pin_code_box_anywhere_in_the_registry_declares_the_pincode_format` below
    # is the sweep that can.
    ("designerPincode", TextFormat.PINCODE, FieldType.TEXT),
)


def test_the_stage_three_designer_boxes_declare_the_shapes_they_were_given():
    """The declarations themselves, and that `validate_registry` is content with all three.

    THE RULE BEING CHECKED IS THE ONE THAT REFUSES THE OTHER TYPES. `validate_registry` permits a
    format only on TEXT, LONG_TEXT, URL, PHONE and EMAIL — the five that reach `coerce_value`'s
    scalar-text arm — and refuses every other type on the ground that the declaration would
    serialise to both clients, be published in the digest, appear in the bundled Android asset, and
    refuse nothing on the way in. All three of these boxes sit inside that permitted set, and this
    test says so PER BOX rather than leaning on `test_registry_is_sound`'s single aggregate: a
    registry-wide `problems == []` tells you something is fine, and after nineteen new fields in one
    edit that is not the same as knowing these three are. PHONE and EMAIL are also the two types
    whose whole contribution before this feature was a keyboard on the handset — declaring the type
    bought the designer nothing the server enforced — so "the type is allowed to carry a format" is
    the specific thing worth pinning here.

    THE `max_length` BESIDE EACH FORMAT IS ASSERTED TOO, because a format is a SHAPE and not a
    bound — the lesson of `participant.email`, which declared EMAIL and no length at all and was
    therefore unbounded into the .docx, the .xlsx and every export. A stage-3 box with a format and
    no bound would be that same field under a different key.
    """
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
        # NOT masked, and asserted rather than assumed. `validate_registry` refuses `store_masked`
        # without `AADHAAR`, so a mask here would fail the build — but the reason it must not be
        # masked is worth stating, because a phone number and an email address are printed IN FULL
        # in the report's designer block: a masked one would be a broken contact detail rather than
        # a protected identifier, and `mask_aadhaar` keeps the last four characters of anything.
        assert field.store_masked is False, f"{key} would print as a mask in the designer block"

    problems = validate_registry()
    assert problems == [], "\n".join(problems)


def test_the_stage_three_designer_boxes_refuse_what_the_designer_page_refuses():
    """The join: the stage copy answers exactly what the record path's own validator answers.

    THE DEFECT THIS ENDS, stated for these three boxes specifically. `prefill_from_profile` copies
    `phone`, `email` and `pincode` off `DesignerProfile` into these boxes at create time, and the
    designer may then edit them per report — which is the whole point of them being copies. So there
    are two ways a malformed value gets in: typed here, or carried from a profile column written
    before its own validator existed. `validate_entry` re-coerces EVERY field on EVERY save, so
    declaring the format is what re-refuses the second kind as well as the first.

    ASSERTED AGAINST THE VALIDATOR AND NOT AGAINST A RESTATED SENTENCE, deliberately. The exact
    strings are already pinned by the shared vector table in three languages
    (`test_the_three_implementations_agree_on_the_shared_vector_table`), and a fourth copy here
    would be a fourth thing to keep in step — the precise mistake that table exists to end. What
    this asserts is stronger and is asserted nowhere else: that the stage box's answer IS the record
    page's answer, character for character, for the same input. A `coerce_value` that stopped
    applying formats would answer `None` here and fail every row.

    AND NOTHING IS STORED ON A REFUSAL. `save_stage` restores a refused key from `previous`, so the
    designer keeps the other eighteen answers of the entry and the box keeps whatever it held. A
    version that stored the malformed value alongside the error would be the silent half of this
    defect surviving the fix.
    """
    from app.services.address import normalize_pincode, pincode_error
    from app.services.contact_formats import email_error, normalize_email, phone_error

    plan = _entity("workshopPlan")

    # (field key, the validator applied exactly the way `_FORMATS` applies it, values to try)
    #
    # THE NORMALISATION IS PART OF THE CONTRACT AND NOT COSMETIC. `_pincode_format_error` checks the
    # NORMALISED value so that a box already holding "768 029" — typed that way by somebody reading
    # an address aloud, and named in `participant.pincode`'s own comment as the value a tighter rule
    # would start refusing on a stage a designer is trying to submit — is accepted, while the STORED
    # string is left as typed. Mirroring that here is what makes the comparison honest rather than
    # merely green.
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
        # A CASE TABLE THAT ACCEPTED EVERYTHING WOULD PASS THIS TEST WITHOUT MEASURING THE REFUSAL,
        # so each box must contribute at least one accepted and one refused value.
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
    """The sweep the EMAIL/PHONE sweep structurally cannot do, and the reason it is needed.

    `test_every_email_and_phone_field_in_the_registry_declares_a_format` works because EMAIL and
    PHONE are FIELD TYPES: a new contact box declares one and the sweep sees it. There is no
    `FieldType.PINCODE`. Every PIN code in this registry is a `FieldType.TEXT` box indistinguishable
    from a city name, so the only thing separating it from four hundred other TEXT fields is what
    its label says — and a PIN code box added without `text_format=PINCODE` would look completely
    reasonable in review, which is exactly how `designerPincode` could have arrived without one.

    MATCHED ON THE LABEL, which is the weak part of this and is stated rather than hidden. A box
    labelled "Postal code" or "ZIP" would slip through. The label is nonetheless the strongest
    signal available — the key names are not consistent enough to match on (`pincode`,
    `recordPincode`, `designerPincode`) — and a sweep that catches the spellings actually in use is
    worth more than no sweep at all. Widen the pattern when a fifth spelling appears.
    """
    pattern = re.compile(r"pin\s*code", re.IGNORECASE)
    matched = [
        (f"{entity.key}.{field.key}", field)
        for _stage, entity in all_entities()
        for field in entity.fields
        if pattern.search(f"{field.key} {field.label}")
    ]

    # THE SWEEP IS NOT VACUOUS. Four boxes match today — `participant.pincode`,
    # `tool.recordPincode`, `existingProduct.recordPincode` and `workshopPlan.designerPincode`. A
    # floor rather than the exact count, for the reason written at the caption sweep further up: a
    # fifth PIN code box is an ordinary addition, whereas a pattern somebody broke while editing
    # would make the assertion below pass while measuring nothing.
    assert len(matched) >= 4, f"the PIN code pattern matches only {matched}; it has stopped working"

    unchecked = sorted(
        path for path, field in matched
        if not field.deprecated and field.text_format is not TextFormat.PINCODE
    )
    assert unchecked == [], (
        f"{unchecked} look like PIN code boxes and declare no PINCODE format, so the server checks "
        "nothing but their length — and a five-digit PIN code then prints as an address in a report"
    )
