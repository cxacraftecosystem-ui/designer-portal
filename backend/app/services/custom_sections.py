"""Designer-defined sections and fields: one workshop's own questions, added with no deployment.

Step 6 of ``docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md`` §5, and the whole of its §4. Server half.

**WHY THIS EXISTS.** The 22 stages are Python literals in ``services/stage_definitions.py``, and
that file is a deployment. A designer standing in a cluster who needs to record one more thing —
how many looms are in the shed, the name of the co-operative's secretary, which of three dye baths
was used — has had exactly two options: type it into a free-text notes box where nothing can count
it, or not record it at all. This module is the third option, and everything below is about making
it safe rather than making it possible.

================================================================================================
WHERE THE ANSWERS LIVE, AND WHY IT IS NOT WHERE THE PLAN FIRST READ AS SAYING
================================================================================================

A custom answer is a value in a ``DwStageEntry.data`` blob, as the plan requires — but in a row of
its **own**, one per (workshop, stage), whose ``entityKey`` is the reserved literal
:data:`CUSTOM_ENTITY_KEY` and whose whole ``data`` is the container. It does **not** sit nested
inside the stage's own singleton row, and the reason is the installed fleet rather than taste.

``save_stage``'s ``merge`` defaults to **false**, which writes the incoming ``data`` wholesale. A
client one release behind — one that has never heard of custom sections and therefore sends no
``custom`` key — would have **deleted every custom answer on the stage, silently, with nothing in
``droppedKeys`` to say so**. Nesting would have needed a bespoke "the entry carried no ``custom``
key, therefore preserve the stored one" rule keyed on the raw payload, and getting that rule wrong
once costs the fleet its data. With a separate row there is nothing to get wrong: an old client
sends no ``_custom`` entry, so no ``_custom`` row is touched.

Three further things fall out of the same choice rather than needing code of their own:

* the shallow ``{**previous, **clean}`` merge is already correct, because the row's keys are top
  level;
* ``MAX_FIELD_KEYS`` already bounds the container, for the same reason;
* the rejected-value preservation loop — the one that stops ``"65OO"`` typed over a saved
  ``"6500"`` from deleting the good value — works verbatim.

And it is the only shape that serves all 22 stages. **Eight of them declare no SINGLETON entity at
all** (6, 11, 12, 13, 14, 15, 16, 17 — existing products, sketches, sketch review, prototypes,
iteration, validation, final documentation, costing), so "hang the container on the stage's
singleton row" could not have served the third of the stages a designer is most likely to want to
extend.

**THE PRICE, STATED SO IT IS NOT REDISCOVERED.** Four places in the server derive everything from
the registry and have had to learn one reserved entity key: ``save_stage``'s entity lookup,
``_stages_payload``'s cardinality lookup, ``workshop_completeness``/``assemble_workshop_data``'s
cardinality lookup, and — as an invariant rather than as code — **the collection sweep must never
widen to include it**. ``collection_keys`` is derived from ``spec.entities`` and ``_custom`` is not
one of them, so the sweep cannot reach the row today; a later change that widened that set could
soft-delete a workshop's whole custom record, which is the incident
``services/design_workshops.py`` already records for the four cost sheets and six prototypes.

``promoted_values`` and the analytics reads need no guard at all and deliberately have none:
``promoted_values`` matches ``source_entity == entity_key`` against the ``PROMOTED_COLUMNS``
literal, and ``_custom`` appears in no path of it.

================================================================================================
``customSchemaVersion`` IS ITS OWN DIGEST AND MUST NEVER ENTER ``registry_version()``
================================================================================================

Four independent reasons, any one sufficient:

1. ``registry_version()`` takes no arguments and digests module-level ``STAGES`` only. It is a
   process constant; two workshops must produce the same string or the word "version" means nothing.
2. That digest is the refetch signal for a **file compiled into the APK** — the 119 KB
   ``assets/design-workshop-schema.json`` a handset renders forms from before it has ever reached
   the network. A per-workshop digest would mark that asset stale on every handset in the fleet the
   moment any designer anywhere added a field.
3. ``stage_schema`` may not read a database. It is the module the Kotlin and TypeScript ports mirror
   line for line, and a per-workshop digest would put a query in the one module that must not have
   one. That is why this module imports ``stage_schema`` and never the reverse.
4. ``registry_to_dict()`` is pinned by **content equality** against that bundled asset
   (``test_the_bundled_android_asset_is_the_registry_it_claims_to_be``). **The hard rule that
   follows: ``registry_to_dict()`` gains nothing — not a key, not a flag, not one string.** Anything
   added there fails the suite until 119 KB is re-dumped, and re-dumping it is an Android release.

================================================================================================
THE EDIT-AFTER-ANSWERS RULE, COPIED FROM ``services/questionnaire_forms.py``
================================================================================================

That module states it in full and this feature obeys the same rule for the same reason, so the
reasoning is cited rather than re-argued: **an answer is evidence, and the words it was given under
are part of that evidence.** The failure it names is the one to prevent here — *"How many looms?"
answered "12", reworded to "How many weavers?", and a ministry report now states there are twelve
weavers.*

1. A field **nobody has answered** is fully editable and deletable. The ordinary case, and it stays
   frictionless.
2. A field **with answers** may freely change its help, its required flag, its unit, its bounds and
   its position. None of those alter what a recorded answer asserts.
3. Rewriting the **label** of an answered field **SUPERSEDES** it: the old field is retired carrying
   its original label and keeps its answers under its own key, and the new label becomes a new field
   under a newly minted key, linked by ``supersededById``.
4. Deleting an answered field **RETIRES** it: it stops being asked, and the stored answer stays
   readable and printable.
5. A section title may change freely even when answered — a heading is not what an answer answers.

**ONE HONEST GAP, WHICH THE MIGRATION ALSO RECORDS.** ``QuestionnaireFormAnswer.questionId`` is
``ON DELETE RESTRICT``, so that module's rule 4 is enforced by the database as well as by Python.
**Custom answers are JSON keyed by a string; no foreign key exists and none can.** The retire-never-
delete rule here is therefore Python-only — :func:`plan_definition` is the only thing that can
express a delete and it refuses to express one for an answered field, ``tests/test_custom_sections.py``
asserts that it refuses, and the migration header says so in the SQL where a later author will meet
it. That is three guards short of a foreign key, and the difference is stated rather than papered
over.

**AND THE RULE HAS A SECOND HALF, ON THE ANSWER PATH, WHICH IS WHERE IT WAS ACTUALLY BROKEN.** Keeping
the QUESTION is only half of "retire never deletes"; the ANSWER lives in a JSON container the stage
save rewrites wholesale, and a form is drawn from ``live_fields``, so a client that fills its container
from the form it drew omits every retired key — and ``merge`` defaults to false, so that omission read
as a deletion. The rule held only for a client that echoed keys it is told not to render. The loop in
:func:`plan_custom_write` that carries a retired key's stored value forward whether the payload named
it or not is the other half, and it is on the server rather than in three clients' good manners.
"""

from __future__ import annotations

import hashlib
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from typing import Any

from app.core.db import db

# The filled-ness test, imported rather than copied. "Answered" is the hinge of the whole
# edit-after-answers rule above, and a second definition of it would eventually disagree with the
# one the completeness scorer uses — which is precisely the 144/144-versus-"Not recorded" defect
# this repository has already shipped once, arrived at from a new direction. It is private to
# `stage_schema` because nothing outside that module had needed it until now; it is imported under
# its own name so a grep for `_is_filled` finds this call site.
from app.services.stage_schema import (
    FieldSpec,
    FieldType,
    Tier,
    _is_filled,
    coerce_value,
    stages,
)

# --------------------------------------------------------------------------------------
# The vocabulary
# --------------------------------------------------------------------------------------

#: The reserved ``DwStageEntry.entityKey`` a workshop's custom answers live under. One row per
#: (workshop, stage); the row's whole ``data`` is the container, keyed by custom field key.
#:
#: THE LEADING UNDERSCORE IS THE REPOSITORY'S OWN RESERVATION, not a new convention: ``_clientKey``,
#: ``_entryId`` and ``_ordinal`` already mean "the protocol's own, not a designer key" on both
#: clients and in ``save_stage``. It cannot collide with a registry entity key — those are camelCase
#: words — and it cannot collide with a designer's own key either, because :data:`KEY_PATTERN`
#: below refuses anything that does not start with a lower-case letter.
CUSTOM_ENTITY_KEY = "_custom"

#: The field types a designer may declare in v1, and the whole list.
#:
#: **NO MEDIA, AND THAT IS A CORRECTION TO THE PLAN RATHER THAN A RESTATEMENT OF IT.** There are
#: FIVE separate walkers that translate a local media reference into a server id, and every one of
#: them enumerates the media-typed fields **of the row's registry entity** and reads them at the
#: **top level of the row**: the server's own ``_media_ids``, Android's ``wireData``, the web's
#: ``unresolvedMediaRefs``, its draft-resolve and its ``rewriteMediaRefs``. None of them can see a
#: value that is not a registry field. A custom media answer would therefore sync as a ``dwlocal:``
#: reference resolving to nothing: the save reports success, and the photograph is simply absent
#: from the .docx — which the designer discovers from the officer who received it. That is not
#: hypothetical; it is the ``RICH_TEXT`` scar tissue in ``WorkshopSync.kt``, the same bug shipped
#: once already. ``RICH_TEXT`` is out for the same reason one level deeper: it carries media several
#: levels down inside its document JSON.
#:
#: **NO REF**, because ``ref_resolves`` is supplied by the REPORT and by nothing else, so a dangling
#: custom reference would read *filled* on every form and *unfilled* in the document — the
#: 144/144-versus-"Not recorded"-thirty-six-times defect, verbatim. **No GEO**, which has its own
#: coercion branch and two renderers and was judged not worth the surface in v1. **Nothing derived
#: or computed**, because ``derive_value`` has three implementations by design and §3's rule forbids
#: anything non-deterministic feeding a compared field.
#:
#: Twelve tokens. The brief's rule table says "the fifteen v1 tokens" in one row and lists twelve in
#: its own §8; the plan §4 names exactly these twelve, and the plan wins.
V1_FIELD_TYPES: frozenset[FieldType] = frozenset({
    FieldType.TEXT,
    FieldType.LONG_TEXT,
    FieldType.INT,
    FieldType.DECIMAL,
    FieldType.MONEY,
    FieldType.PERCENT,
    FieldType.DATE,
    FieldType.TIME,
    FieldType.BOOL,
    FieldType.ENUM,
    FieldType.MULTI_ENUM,
    FieldType.TAGS,
})

#: Types whose stored value is a list of scalars, so a blank one is ``[]`` rather than ``None``.
_LIST_TYPES: frozenset[FieldType] = frozenset({FieldType.MULTI_ENUM, FieldType.TAGS})

#: Types that carry an option list, and the only ones that may.
_OPTION_TYPES: frozenset[FieldType] = frozenset({FieldType.ENUM, FieldType.MULTI_ENUM})

#: Types a numeric bound means anything for. ``_range_checked`` is only reached from the numeric
#: branches of ``coerce_value``, so a ``minValue`` on a TEXT field is an inert control — exactly the
#: stored-and-ignored setting this repository has already had to go back and fix seven of.
_BOUNDED_TYPES: frozenset[FieldType] = frozenset({
    FieldType.INT, FieldType.DECIMAL, FieldType.MONEY, FieldType.PERCENT,
})

#: Types ``coerce_value`` applies ``max_length`` to. Same reasoning as :data:`_BOUNDED_TYPES`.
_LENGTH_TYPES: frozenset[FieldType] = frozenset({FieldType.TEXT, FieldType.LONG_TEXT})

#: What a key may look like. Mirrors ``validate_registry``'s rule that a field key must start with a
#: letter, narrowed to lower-case first so no designer key can ever collide with the ``_``-prefixed
#: protocol keys or with :data:`CUSTOM_ENTITY_KEY` itself.
KEY_PATTERN = re.compile(r"^[a-z][A-Za-z0-9]{0,39}$")

# --------------------------------------------------------------------------------------
# Bounds
#
# The cost of an unbounded field is not paid by the writer. `schemas/design_workshops.py` records
# the argument in full against `MAX_NOTES_CHARS`: nothing upstream stops the write (nginx allows
# 200 MB bodies and there is no body-size middleware), and every later reader pays for it — a
# definition is returned in full on every GET of every stage of every workshop that has one.
# --------------------------------------------------------------------------------------

MAX_CUSTOM_SECTIONS = 12
MAX_CUSTOM_FIELDS_PER_SECTION = 60
MAX_CUSTOM_OPTIONS = 100
MAX_CUSTOM_LABEL_CHARS = 160
MAX_CUSTOM_HELP_CHARS = 600
MAX_CUSTOM_TITLE_CHARS = 160
MAX_CUSTOM_DESCRIPTION_CHARS = 600
MAX_CUSTOM_KEY_CHARS = 40
MAX_CUSTOM_UNIT_CHARS = 24

#: How far a supersede chain is followed before the walk gives up. A chain is one hop per rewording
#: of one question, so eight is more rewordings than any real form has had — and it is a BOUND and
#: not a claim about depth: ``supersededById`` is a plain column with no foreign key, so a row
#: pointing at itself is expressible by hand and an unbounded walk over one would spin inside a
#: request a designer is waiting on. Visited ids are tracked too, so a cycle stops at once.
MAX_SUPERSEDE_HOPS = 8


# --------------------------------------------------------------------------------------
# The definition, as plain data
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class CustomOption:
    """One option of a designer's own ENUM or MULTI_ENUM list.

    INLINE RATHER THAN AN ENTRY IN THE SHARED ``ENUMS`` TABLE, deliberately. The registry's rule 2
    exists so that "cotton" recorded in stage 5 is the same token as the one in a stage 17 cost
    sheet — a promise a shared table can keep and a per-workshop list cannot. A designer's list is
    theirs, it means what it means inside their workshop, and pretending otherwise would put
    unreviewed tokens into the one table whose whole value is that it is reviewed.
    """

    value: str
    label: str = ""

    @property
    def display(self) -> str:
        """What a form and a report print. The token itself when nobody wrote a label."""
        return self.label.strip() or self.value


@dataclass(frozen=True, slots=True)
class CustomFieldSpec:
    """One designer-defined field. Once it has an answer it is effectively immutable.

    The shape deliberately mirrors :class:`~app.services.stage_schema.FieldSpec` name for name where
    the two overlap, because :func:`to_field_spec` turns one into the other so that a custom DECIMAL
    is coerced by exactly the code a core DECIMAL is coerced by. Two coercers would round
    differently and a cross-surface parity test would fail for a reason nobody could find.
    """

    key: str
    label: str
    type: FieldType
    tier: Tier = Tier.STANDARD
    required: bool = False
    help: str = ""
    unit: str = ""
    options: tuple[CustomOption, ...] = ()
    max_length: int = 0
    min_value: float | None = None
    max_value: float | None = None
    sort_order: int = 0
    #: Stopped being asked, but its answers stay readable and printable. Rules 3 and 4 above.
    retired: bool = False
    #: The field that replaced this one when its LABEL was rewritten after it had answers.
    superseded_by: str = ""
    #: The database row id. Empty for a field that has only ever existed in a request body.
    id: str = ""

    @property
    def option_values(self) -> tuple[str, ...]:
        return tuple(o.value for o in self.options)

    def option_label(self, value: Any) -> str:
        """The printable label for a stored token, falling back to the token itself.

        Falling back rather than raising, exactly as ``stage_schema.enum_label`` does and for its
        reason: a draft written before an option was removed still holds that token, and printing
        it raw in the report is better than failing an export a designer is waiting on.
        """
        token = str(value)
        return next((o.display for o in self.options if o.value == token), token)


@dataclass(frozen=True, slots=True)
class CustomSectionSpec:
    """A block of questions a designer added to ONE design workshop.

    WORKSHOP-SCOPED, and that is a decision rather than a default (plan §6, answer 2). A bad
    definition damages one record instead of every record sharing a template; completeness scoring
    has one unambiguous owner; and editing a definition cannot retroactively change a workshop that
    has already been submitted. It also means **no new permission concept exists** —
    ``DesignWorkshopViewer`` already answers "who may open this workshop", and these routes are
    gated by the same two calls ``save_stage_data`` uses.
    """

    key: str
    title: str
    #: The ``StageSpec.key`` this section's answers belong to. **Never empty**, and that is a
    #: deliberate narrowing of the plan's "empty means it belongs nowhere" — see
    #: :func:`validate_definition` for the reason, which is that answers must have a home.
    stage_key: str
    description: str = ""
    sort_order: int = 0
    fields: tuple[CustomFieldSpec, ...] = ()
    retired: bool = False
    revision: int = 1
    id: str = ""

    @property
    def live_fields(self) -> tuple[CustomFieldSpec, ...]:
        """The fields still being asked. What a form renders and what completeness counts."""
        return tuple(f for f in self.fields if not f.retired)


@dataclass(frozen=True, slots=True)
class CustomDefinition:
    """One workshop's whole custom definition, already loaded, plus its digest.

    Carried as a value object rather than passed around as a list of rows so that every reader —
    the stage save, the four completeness call sites, the report — is looking at the same thing and
    computing the digest the same way.
    """

    sections: tuple[CustomSectionSpec, ...] = ()
    version: str = ""

    @property
    def is_empty(self) -> bool:
        return not self.sections

    def sections_for(self, stage_key: str) -> tuple[CustomSectionSpec, ...]:
        return tuple(s for s in self.sections if s.stage_key == stage_key and not s.retired)

    def fields_for(self, stage_key: str) -> tuple[CustomFieldSpec, ...]:
        """Every field of every section of one stage, in the order they are asked.

        INCLUDING THE RETIRED FIELDS, because this is what the answer path validates against and a
        retired field's stored answer must still round-trip rather than being dropped as an unknown
        key on the next save. The completeness scorer skips them itself, exactly as it skips a
        deprecated registry field.

        **AND INCLUDING THE FIELDS OF A RETIRED SECTION, EACH FORCED TO ``retired``. Reading this as
        "live sections only" was a silent data-loss bug and it is worth naming, because it is the
        one this whole storage design exists to prevent, arrived at from the definition side instead
        of the client side.** A section is retired precisely BECAUSE somebody answered it (rule 4:
        an answered section is retired, never deleted), so its keys are exactly the keys the
        ``_custom`` row still holds. Left out of this list they became keys the definition does not
        carry — so the next ordinary save from any client that sends its container at all dropped
        every one of them, reported them as ``droppedCustomKeys`` and wrote the row back without
        them. "The stored answer stays readable and printable" would have lasted until the next
        save of that stage, and the report would then print a heading for a section whose answers
        the save path had just deleted.

        Forced to ``retired`` rather than passed through, because a section's flag and its fields'
        flags are two facts and only one of them is the question "is this asked": the flag has to say
        "no longer asked" or the completeness scorer would count a required question of a section
        nobody is being asked, and the report's annexure and the readiness screen would disagree about
        one stage of one workshop. **WHAT THIS IS AND IS NOT DEFENDING AGAINST, checked by running the
        plan rather than assumed:** :func:`plan_definition`'s section RETIRE carries a ``RETIRE``
        :class:`FieldPlan` for every live field under it, so a section retired THROUGH this module
        leaves no live field row behind and the forcing changes nothing on that path. It is for the
        rows this module did not write — a row retired by hand, a definition restored from a backup,
        an older build, or a spec assembled in memory by a caller that set only the section's flag —
        which is the same audience as the one-key-one-spec rule below, and the cost of being wrong
        about it is a submitted document that disagrees with itself about its own arithmetic.

        **ONE KEY, ONE SPEC — NEVER TWO, however the definition got into that state.** The container
        is per STAGE while field uniqueness is per SECTION (``migration.sql``, correctly: the schema
        cannot express a stage-wide rule), so two sections of one stage declaring one key would hand
        the answer path two questions fighting over a single slot. :func:`plan_definition` refuses to
        create that state and is the first line of defence; this is the second, for a row written by
        hand, a definition restored from a backup taken before that refusal existed, or a bug nobody
        has found yet. **THE LIVE SPEC WINS THE SLOT, and which one wins is the point rather than a
        tie-break**: the live question is the one on the screen the answer is being typed into, so it
        must be the one that coerces it. Keeping the retired spec instead was the observed failure —
        a designer typing "about nine" into a new TEXT question got back *"How many looms? is not a
        valid int"*, naming a question on no screen, and the rejected-value preservation loop then
        wrote the row back as ``{"looms": 12}``, replacing what had just been typed with the retired
        question's old answer. Dropping the retired duplicate costs nothing on this path: it shared
        the one container slot with the live field, whose key the answer path knows, so the stored
        value still round-trips rather than being dropped as an unknown key. What no dedup here can
        undo is the REPORT printing that value under both wordings — that is the refusal's job.

        **WHERE IT REALLY IS ONLY A TIE-BREAK, SAID PLAINLY: two LIVE specs under one key.** There is
        no "the live one" to prefer then, the first by ``(sort_order, key)`` takes the slot, and a
        question a designer can see on their form has no spec on this path at all — whatever they type
        into it is coerced by, stored under and printed beside the OTHER section's wording. Nothing
        here can do better, because both questions are equally on the screen. Executed, that is where
        the ``loomsR2`` hole :func:`_keys_this_put_keeps` now closes landed: a new section's TEXT
        question answered "indigo" came back *"How many weavers work here? is not a valid int"*. The
        only defence against that state is :func:`plan_definition` refusing to create it; this line
        exists so that what gets through is one question lost rather than a coercion war.
        """
        out: list[CustomFieldSpec] = []
        slot_of: dict[str, int] = {}
        for section in sorted(
            (s for s in self.sections if s.stage_key == stage_key),
            key=lambda s: (s.sort_order, s.key),
        ):
            for f in sorted(section.fields, key=lambda f: (f.sort_order, f.key)):
                spec = f if not section.retired else replace(f, retired=True)
                slot = slot_of.get(spec.key)
                if slot is None:
                    slot_of[spec.key] = len(out)
                    out.append(spec)
                elif out[slot].retired and not spec.retired:
                    out[slot] = spec
        return tuple(out)

    def fields_by_stage(self) -> dict[str, tuple[CustomFieldSpec, ...]]:
        keys = {s.stage_key for s in self.sections if not s.retired}
        return {key: self.fields_for(key) for key in keys}


EMPTY_DEFINITION = CustomDefinition()


# --------------------------------------------------------------------------------------
# The digest
# --------------------------------------------------------------------------------------


def custom_schema_version(sections: Sequence[CustomSectionSpec]) -> str:
    """A short stable digest of one workshop's custom definition. The mirror of ``registry_version``.

    CONTENT-ADDRESSED AND NOT HAND-MAINTAINED, for the reason ``registry_to_dict``'s docstring
    already records: "a hand-maintained one is a version that stops changing".

    **IT DIGESTS THE LABEL, AND ``registry_version()`` DELIBERATELY DOES NOT. That difference is the
    single most important line in this function.** The core registry excludes labels because
    retitling one field must not invalidate every cached draft on every handset in the fleet — a
    119 KB asset, thousands of devices, and a retitle that changes no meaning. Neither half of that
    is true here. This definition is one workshop's, it is small, and the label **is the question**:
    a phone that goes on showing "How many looms?" after the designer rewrote it to "How many
    weavers?" records an answer against a question nobody asked, which is the exact failure the
    supersede rule exists to prevent — arrived at from the caching side instead of the editing side.

    **THE OPTION LIST'S CONTENTS ARE INSIDE THE DIGEST**, unlike ``ENUMS``, whose contents are
    deliberately outside ``registry_version()``. The core registry can afford that because a SECOND
    test compares the whole ``registry_to_dict()`` dump against the bundled asset. A per-workshop
    definition has no bundled asset and no second content test, so this digest is the only staleness
    signal it will ever have — and a designer adding an option that never reaches the phone standing
    in the cluster is the same class of failure as the ``TOOL_TYPE`` case that argument was written
    about. Option LABELS are in it too: an option whose value is ``COTTON`` and whose label was
    corrected from "Cotton" to "Cotton (unbleached)" is a different thing to print in a report.

    ``help`` is the one visible string left OUT, and that is a judgement rather than an oversight: a
    stale hint beside a correct question is a lesser failure than a stale question, and help text is
    edited far more often than anything else here.

    Retired fields are inside the digest as well — a phone holding a copy that still OFFERS a
    retired question is stale in the way that matters most.
    """
    parts: list[str] = []
    for s in sections:
        for f in s.fields:
            options = ",".join(f"{o.value}={o.display}" for o in f.options)
            parts.append(
                f"{s.stage_key}.{s.key}.{f.key}:{f.type.value}:{f.tier.value}:"
                f"{int(f.required)}:{f.label}:{f.unit}:{options}:"
                f"{int(f.retired)}:{int(s.retired)}"
            )
    if not parts:
        # A WORKSHOP WITH NO DEFINITION HAS AN EMPTY VERSION, NOT A DIGEST OF NOTHING. The clients
        # compare this string to decide whether their cached copy is stale, and a workshop that has
        # never had a custom section must be distinguishable from one whose definition happens to
        # hash to something — otherwise "I hold nothing" and "there is nothing to hold" look
        # identical, which is the distinction `DwQuestionnaireCopy` was given three states for.
        return ""
    digest = hashlib.sha256("|".join(sorted(parts)).encode("utf-8")).hexdigest()
    return digest[:16]


# --------------------------------------------------------------------------------------
# Definition-time validation — loud, and every refusal names what it collided with
# --------------------------------------------------------------------------------------


def validate_definition(sections: Sequence[CustomSectionSpec]) -> list[str]:
    """Every rule this definition breaks, as sentences. An empty list means it is sound.

    Shaped exactly like ``validate_registry``: a LIST of problems rather than the first one, because
    a designer fixing a form one 422 at a time is a designer who gives up on the third round trip.
    The route turns a non-empty list into a 422 carrying all of them.

    **EVERY MESSAGE NAMES THE OFFENDING KEY AND WHAT IT COLLIDES WITH.** "Invalid field" is a code
    with spaces in it; "``craftName`` is already a field of stage 1 (Workshop setup → Craft name)"
    is something the person holding the screen can act on.
    """
    problems: list[str] = []
    by_stage_key = {s.key: s for s in stages()}

    if len(sections) > MAX_CUSTOM_SECTIONS:
        problems.append(
            f"A workshop may carry at most {MAX_CUSTOM_SECTIONS} custom sections and this one "
            f"names {len(sections)}. Merge two of them, or move the questions that belong to a "
            f"different stage into a section on that stage."
        )

    seen_section_keys: dict[str, str] = {}
    # Field keys are unique across the WHOLE workshop, which is stricter than the per-section
    # uniqueness the database enforces — and the extra strictness is load-bearing rather than
    # tidiness. The answer container is one row per (workshop, STAGE), so two sections on one stage
    # both declaring `q1` would write into the same key of the same container: one answer, two
    # questions, and no way to tell which of them it answers. Scoping the rule to the workshop
    # rather than to the stage costs a designer nothing (they name their own keys once) and means
    # the rule does not silently change meaning if a section is later moved to another stage.
    seen_field_keys: dict[str, str] = {}
    # Labels collide only where the completeness scorer would collapse them, which is per stage.
    seen_labels: dict[tuple[str, str], str] = {}

    for section in sections:
        where = section.key or "(unnamed)"
        if not KEY_PATTERN.match(section.key or ""):
            problems.append(
                f"Section key {section.key!r} cannot be used. A key must start with a lower-case "
                f"letter, carry only letters and digits after it, and be at most "
                f"{MAX_CUSTOM_KEY_CHARS} characters — it is what the answers are stored under and "
                f"it is never shown to anybody."
            )
        elif section.key in seen_section_keys:
            problems.append(
                f"Two sections are both keyed {section.key!r}. A section key is unique within the "
                f"workshop; rename one of them."
            )
        else:
            seen_section_keys[section.key] = section.title

        if not (section.title or "").strip():
            problems.append(f"Section {where} has no title. Give it the heading it should print under.")
        elif len(section.title) > MAX_CUSTOM_TITLE_CHARS:
            problems.append(
                f"Section {where}'s title is longer than {MAX_CUSTOM_TITLE_CHARS} characters."
            )
        if len(section.description or "") > MAX_CUSTOM_DESCRIPTION_CHARS:
            problems.append(
                f"Section {where}'s description is longer than {MAX_CUSTOM_DESCRIPTION_CHARS} "
                f"characters."
            )

        # THE STAGE IS REQUIRED, AND THAT IS A NARROWING OF THE PLAN RATHER THAN A RESTATEMENT.
        #
        # The plan says an empty stage key means "belongs nowhere — printed as its own annexure",
        # and that sentence is about REPORT PLACEMENT, which is honoured in full: a section whose
        # stage the chosen template does not print is appended as its own annexure. But the ANSWERS
        # have to live somewhere, and under the design above they live in a `DwStageEntry` row
        # addressed by (workshop, stageKey, "_custom"). A section with no stage has no row — its
        # answers would have nowhere to be written, nothing to score them against, and no form to
        # be asked on. Refusing here is the honest version of that; inventing a pseudo-stage to
        # hold them would put a key in the storage that the registry does not know, which is the
        # one thing this whole design is built to avoid.
        stage = by_stage_key.get(section.stage_key or "")
        if stage is None:
            problems.append(
                f"Section {where} names {section.stage_key or '(nothing)'!r}, which is not one of "
                f"the 22 stages. Choose the stage these questions are asked at — that is where the "
                f"answers are stored and where they are counted towards the stage's completeness. "
                f"If the section should print at the back of the report instead, it still belongs "
                f"to the stage it is ASKED at; a template that does not print that stage prints the "
                f"section as its own annexure."
            )

        if len(section.fields) > MAX_CUSTOM_FIELDS_PER_SECTION:
            problems.append(
                f"Section {where} declares {len(section.fields)} fields; the limit is "
                f"{MAX_CUSTOM_FIELDS_PER_SECTION}. Split it into two sections."
            )

        for f in section.fields:
            problems.extend(_field_problems(section, f, stage, seen_field_keys, seen_labels))

    return problems


def _field_problems(
    section: CustomSectionSpec,
    f: CustomFieldSpec,
    stage: Any,
    seen_field_keys: dict[str, str],
    seen_labels: dict[tuple[str, str], str],
) -> list[str]:
    """One field's rule violations. Split out only so :func:`validate_definition` stays readable."""
    problems: list[str] = []
    where = f"{section.key}.{f.key}" if f.key else f"{section.key}.(unnamed)"

    if not KEY_PATTERN.match(f.key or ""):
        problems.append(
            f"Field key {f.key!r} in section {section.key!r} cannot be used. A key must start with "
            f"a lower-case letter, carry only letters and digits after it, and be at most "
            f"{MAX_CUSTOM_KEY_CHARS} characters. It is what the answer is stored under and it can "
            f"never be renamed — renaming one orphans the answers already given under it."
        )
    elif f.key in seen_field_keys:
        problems.append(
            f"Field key {f.key!r} is used twice ({seen_field_keys[f.key]} and {where}). A field "
            f"key is unique across the whole workshop, because the answers for one stage are all "
            f"stored side by side and two fields sharing a key would share one answer."
        )
    else:
        seen_field_keys[f.key] = where

    if not (f.label or "").strip():
        problems.append(f"Field {where} has no label. The label is the question the designer reads.")
    elif len(f.label) > MAX_CUSTOM_LABEL_CHARS:
        problems.append(
            f"Field {where}'s label is longer than {MAX_CUSTOM_LABEL_CHARS} characters. A label is "
            f"a question, not a paragraph — put the explanation in the help text."
        )
    if len(f.help or "") > MAX_CUSTOM_HELP_CHARS:
        problems.append(f"Field {where}'s help text is longer than {MAX_CUSTOM_HELP_CHARS} characters.")
    if len(f.unit or "") > MAX_CUSTOM_UNIT_CHARS:
        problems.append(f"Field {where}'s unit is longer than {MAX_CUSTOM_UNIT_CHARS} characters.")

    if f.type not in V1_FIELD_TYPES:
        allowed = ", ".join(sorted(t.value for t in V1_FIELD_TYPES))
        problems.append(
            f"Field {where} is a {f.type.value}, which a custom field cannot be yet. Choose one of: "
            f"{allowed}. Photographs, files, recordings, formatted text, coordinates and references "
            f"to other records are deliberately not available: a photograph attached to a custom "
            f"field would sync as a reference that resolves to nothing, the save would report "
            f"success, and the picture would simply be absent from the report."
        )

    # Verbatim from `validate_registry` rule 3, and for its reason: the tiers exist so a workshop
    # held in a village without power can still produce a complete report, and a required
    # Standard-tier field makes the completeness gate unsatisfiable exactly where the app is most
    # needed. A designer's own field is no different from a registry field in that respect.
    if f.required and f.tier is not Tier.BASIC:
        problems.append(
            f"Field {where} is required but its tier is {f.tier.value}. Only a Basic field may be "
            f"required — the tiers exist so a workshop held somewhere without power or a "
            f"specialist can still be completed, and a required Standard field makes that "
            f"impossible."
        )

    if f.type in _OPTION_TYPES:
        if len(f.options) < 2:
            problems.append(
                f"Field {where} is a {f.type.value} with {len(f.options)} option(s). A choice needs "
                f"at least two; a single-option list is a label."
            )
        if len(f.options) > MAX_CUSTOM_OPTIONS:
            problems.append(
                f"Field {where} declares {len(f.options)} options; the limit is "
                f"{MAX_CUSTOM_OPTIONS}."
            )
        seen_values: set[str] = set()
        for option in f.options:
            token = (option.value or "").strip()
            if not token:
                problems.append(f"Field {where} has an option with no value.")
            elif token in seen_values:
                problems.append(
                    f"Field {where} lists the option {token!r} twice. Two options with one value "
                    f"are one answer under two labels."
                )
            seen_values.add(token)
    elif f.options:
        problems.append(
            f"Field {where} is a {f.type.value} and cannot carry options. Remove them, or make it "
            f"a choice."
        )

    if f.min_value is not None and f.max_value is not None and f.min_value > f.max_value:
        problems.append(
            f"Field {where} has a smallest value ({f.min_value:g}) above its largest "
            f"({f.max_value:g})."
        )
    if (f.min_value is not None or f.max_value is not None) and f.type not in _BOUNDED_TYPES:
        problems.append(
            f"Field {where} is a {f.type.value} and its smallest/largest value would never be "
            f"checked. Remove the bounds, or make the field a number."
        )
    if f.max_length and f.type not in _LENGTH_TYPES:
        problems.append(
            f"Field {where} is a {f.type.value} and its maximum length would never be checked. "
            f"Remove it, or make the field text."
        )

    if stage is None:
        # The stage was already reported as unknown; the two collision checks below cannot run
        # against a stage that does not exist and would only add noise to the same mistake.
        return problems

    # RESERVED-KEY COLLISION. Under this design a collision is not mechanically harmful — the custom
    # answers are in their own row and cannot shadow a core key — but it is refused anyway, for two
    # reasons that outlive the storage choice: a designer looking at one stage form must not see two
    # different questions with one key, and refusing it keeps a later move to nested storage open
    # instead of making it a data migration.
    for entity in stage.entities:
        core = entity.field(f.key)
        if core is not None and not core.deprecated:
            problems.append(
                f"{f.key!r} is already a field of stage {stage.number} "
                f"({stage.title} → {entity.title}: {core.label}). Choose another key."
            )

    # RESERVED-LABEL COLLISION. THIS IS THE ONE THAT ACTUALLY BITES, and the plan asks only for the
    # key check.
    #
    # `StageCompleteness.missing` holds LABELS and is de-duplicated with `dict.fromkeys`, so two
    # required fields sharing a label collapse into ONE row on the readiness screen, in the report's
    # "Outstanding" column and in the export warnings — while `required_total` still counts two. The
    # document then disagrees with itself about its own arithmetic, which is a defect this
    # repository has already shipped once and written up (144/144 beside "Not recorded." printed
    # thirty-six times).
    #
    # THE CHECK IS EXACTLY AS WIDE AS THE COLLAPSE AND NO WIDER, which is a deliberate narrowing of
    # the brief's "any live label of that stage". A SINGLETON field files its label bare and so does
    # a custom field, so those two can collapse into each other. A COLLECTION field files
    # `f"{entity.title}: {label}"`, which cannot collide with a bare label at all — so refusing
    # "Notes" on stage 13 because a prototype row has a "Notes" column would be a refusal with no
    # failure behind it, on some of the most ordinary words a form uses.
    singleton = stage.singleton
    if singleton is not None:
        clash = next(
            (c for c in singleton.fields
             if not c.deprecated and c.label.strip().casefold() == (f.label or "").strip().casefold()),
            None,
        )
        if clash is not None:
            problems.append(
                f"A field on stage {stage.number} ({stage.title}) is already called "
                f"{clash.label!r}. Two questions with one name become one line on the readiness "
                f"screen and in the report's Outstanding column, while the count beside it still "
                f"says two — so the report disagrees with itself. Give this one a different label."
            )
    label_id = (section.stage_key, (f.label or "").strip().casefold())
    if (f.label or "").strip():
        if label_id in seen_labels:
            problems.append(
                f"Field {where} and {seen_labels[label_id]} are both called {f.label!r} on the same "
                f"stage. Two questions with one name become one line on the readiness screen while "
                f"the count beside it says two."
            )
        else:
            seen_labels[label_id] = where

    return problems


# --------------------------------------------------------------------------------------
# Answer-time validation — never a wholesale refusal
# --------------------------------------------------------------------------------------


def to_field_spec(f: CustomFieldSpec) -> FieldSpec:
    """A transient :class:`FieldSpec` for one custom field, so ``coerce_value`` can read it.

    **THIS FUNCTION IS THE WHOLE REASON THERE IS NOT A SECOND COERCER IN THIS FILE.** A custom
    DECIMAL that rounded differently from a core DECIMAL, or a custom MONEY that stored a float
    where a core MONEY stores a fixed-2 string, would be a cross-surface divergence whose cause
    nobody would find — the two values look identical on screen and differ in the .docx. One
    coercer, reached by giving it the shape it already knows how to read.

    The enum is handed over as an EMPTY name on purpose. ``coerce_value``'s ENUM branch looks the
    token up in the shared ``ENUMS`` table, which a designer's list is deliberately not in, so
    membership is checked here instead — see :func:`_coerce_custom`. Everything else about the spec
    is a faithful copy.
    """
    return FieldSpec(
        key=f.key,
        label=f.label,
        type=f.type,
        tier=f.tier,
        required=f.required,
        help=f.help,
        unit=f.unit,
        max_length=f.max_length,
        min_value=f.min_value,
        max_value=f.max_value,
    )


def _coerce_custom(f: CustomFieldSpec, raw: Any) -> tuple[Any, str | None]:
    """Coerce one custom answer. ``coerce_value`` does the work; the option list is checked here.

    The two option types are the only ones this function decides anything about, and it decides the
    same thing ``coerce_value`` decides for a registry enum — membership of the declared list, with
    the offending token named — because the list itself lives on the field rather than in ``ENUMS``.
    """
    spec = to_field_spec(f)
    if f.type is FieldType.ENUM:
        if raw is None or (isinstance(raw, str) and not raw.strip()):
            return None, None
        token = str(raw).strip()
        if token not in f.option_values:
            return None, f"{f.label}: {token!r} is not one of the options offered."
        return token, None
    if f.type is FieldType.MULTI_ENUM:
        if raw is None:
            return None, None
        if not isinstance(raw, (list, tuple)):
            return None, f"{f.label} must be a list"
        items = [str(v).strip() for v in raw if str(v).strip()]
        unknown = [v for v in items if v not in f.option_values]
        if unknown:
            return None, f"{f.label}: unknown option(s) {', '.join(unknown)}"
        return items, None
    return coerce_value(spec, raw)


@dataclass(frozen=True, slots=True)
class CustomEntryResult:
    """What one stage save made of the custom container it was sent."""

    clean: dict[str, Any]
    errors: dict[str, str]
    #: Keys the definition does not carry. **These never enter ``droppedKeys``** — see
    #: :func:`validate_custom_entry`.
    dropped: tuple[str, ...]


def validate_custom_entry(
    fields: Sequence[CustomFieldSpec],
    data: Mapping[str, Any],
    *,
    enforce_required: bool = True,
) -> CustomEntryResult:
    """Coerce and validate one stage's custom container. The mirror of ``validate_entry``.

    **IT NEVER REFUSES THE WHOLE THING**, for the reason ``schemas/design_workshops.py``'s module
    docstring gives about stage payloads in general: an Android draft written two weeks ago in a
    village, by a build one release ahead of the server, carries keys this build has never heard of,
    and refusing the whole submission would lose the fieldwork rather than the field.

    **AN UNKNOWN KEY IS DROPPED AND REPORTED — AND ITS REPORT IS NOT ``droppedKeys``.** That field is
    the only client/server registry-drift signal this repository has, and both clients render it in
    those words: *"this phone is running a newer field registry than the server"*. A custom key the
    server's definition does not carry is a different fact with a different remedy, and feeding it
    into that signal would fire the banner on every save of every workshop that has a custom
    section — training the people who read it to ignore the one message that matters. It goes back
    as ``droppedCustomKeys`` with a sentence of its own.

    ``enforce_required`` is off while a stage is a draft and on at submission, exactly as it is for
    the registry fields beside it. **The scorer and this gate are taught in the same change on
    purpose**: teach one without the other and a stage reads 100% and then 422s on submit, or the
    reverse, and either way the designer is told two contradictory things about one form.

    A RETIRED FIELD'S STORED ANSWER SURVIVES. Its key is still known here, so a container that
    carries it round-trips untouched rather than having it dropped as unknown; what a retired field
    does not do is get asked again, and it is not counted by the scorer. **HALF OF THAT SURVIVAL IS
    NOT THIS FUNCTION'S TO GIVE, and it is worth naming here because this is the paragraph somebody
    reads when they want to know whether the rule holds.** This function only ever sees the keys the
    payload carried, and a client renders its form from ``live_fields`` and so sends none of the
    retired ones — so the key being "known" saves it from ``dropped`` and nothing more. What actually
    keeps the answer is the loop in :func:`plan_custom_write` that carries a retired field's stored
    value forward when the payload is silent about it.
    """
    by_key = {f.key: f for f in fields}
    clean: dict[str, Any] = {}
    errors: dict[str, str] = {}

    for f in fields:
        raw = data.get(f.key)
        value, error = _coerce_custom(f, raw)
        if error:
            errors[f.key] = error
            continue
        # WHAT COUNTS AS BLANK IS ASKED WITH THE SCORER'S OWN FUNCTION and not with a second test
        # written here. `value is None or an empty list` was the same answer for all twelve v1 types
        # — but only by inspection, and the moment v1.1 admits a type whose blank is some other
        # shape the two would part company and a stage would read 100% on the readiness screen and
        # 422 on submit. There is one definition of "there is no answer here" in this system.
        if not _is_filled(value):
            # Nothing derives itself here: derived and computed custom fields are out of v1
            # (``derive_value`` has three implementations by design, and §3's rule forbids anything
            # non-deterministic feeding a compared field), so a blank is simply a blank.
            if enforce_required and f.required and not f.retired:
                errors[f.key] = f"{f.label} is required"
            continue
        clean[f.key] = value

    dropped = tuple(sorted(
        key for key in data
        # The `_`-prefixed keys are the sync protocol's own — `_clientKey`, `_entryId`, `_ordinal`
        # — and reporting them would put a line in every response for something working exactly as
        # designed, which is the same reason `save_stage` skips them for the registry entities.
        if key not in by_key and not str(key).startswith("_")
    ))
    return CustomEntryResult(clean=clean, errors=errors, dropped=dropped)


@dataclass(frozen=True, slots=True)
class CustomWrite:
    """Everything one stage save does to the custom row, decided with no database in the way.

    ``data`` is what the row will hold, or **None** when this payload writes no custom row at all —
    which is what a client one release behind sends, and the whole reason nothing of theirs can be
    destroyed. None and ``{}`` are deliberately different: the empty dict is a designer clearing
    every answer, and it is written.
    """

    data: dict[str, Any] | None
    errors: dict[str, str]
    dropped: tuple[str, ...]


def plan_custom_write(
    fields: Sequence[CustomFieldSpec],
    *,
    sent: Mapping[str, Any] | None,
    previous: Mapping[str, Any],
    merge: bool = False,
    submit: bool = False,
) -> CustomWrite:
    """What one stage save stores, refuses and drops for the custom container.

    ``sent`` is the ``_custom`` entry's ``data``, or **None** when the payload carried no such entry.

    **THIS IS THE WHOLE OF THE ANSWER-TIME DECISION, AND IT IS PURE**, so ``pytest`` can push on the
    combination of merge, rejected-value preservation and the submit gate with no Postgres and no
    Prisma client. The composition is where this gets subtle; each ingredient on its own is easy.

    TWO PHASES, AND THE SECOND ONE IS THE ONE THAT IS EASY TO GET WRONG.

    * **Phase one is what the client sent.** Coercion errors are raised against the value the
      designer just typed, so the message can name it; unknown keys are dropped and reported; a
      REJECTED key keeps whatever the row already held, because typing ``"65OO"`` over a saved
      ``"6500"`` must not delete the good value while the response says only that the edit was
      rejected; and a RETIRED key keeps whatever the row already held **whether this payload named it
      or not**, which is where "retire never deletes" stops being a promise about clients and starts
      being something the server does. Then the merge, which is a plain shallow one and correct as
      written because this row's data is flat.

    * **Phase two is the submit gate, applied to the row AS IT WILL STAND** — not to the payload in
      front of us. A client that sends no custom entry at all would otherwise submit a stage clean
      while ``stage_completeness`` scores the very same stage as incomplete. Requiredness is a
      property of the RECORD, not of one request, and the two gates have to agree on every input or
      the designer is told two contradictory things about one form.

    **PHASE TWO ASKS ABOUT PRESENCE AND NOTHING ELSE, THROUGH THE SCORER'S OWN ``_is_filled``, AND
    IT DELIBERATELY DOES NOT COERCE THE ROW A SECOND TIME.** Re-coercing was the first version and it
    reintroduced the very defect this function exists to prevent, from the other direction: rule 2
    above lets an answered field's BOUNDS change freely, so a designer who lowers a maximum from 500
    to 100 after 500 was recorded made every later save of that stage report an error against a value
    nobody had sent — and, because the route 422s on any error under ``submit``, made the stage
    permanently unsubmittable while ``stage_completeness`` went on scoring the same 500 as filled and
    the stage as 100% complete. One document, two arithmetics. The same happened to an ENUM answer
    whose option the designer removed, where the REPORT had already chosen tolerance (it prints the
    stored token when the list no longer carries it, rather than failing an export). A definition
    edit does not invalidate evidence already recorded, so the gate asks exactly what the scorer
    asks — is there an answer here — and asks it with the same function, which is what makes the two
    unable to disagree about any value, including a shape a v1.1 type has not invented yet.

    The phase-one message wins wherever both spoke: "that is not a valid number" tells the designer
    what to do about the value in front of them, and "… is required" does not.
    """
    dropped: tuple[str, ...] = ()
    errors: dict[str, str] = {}
    to_store: dict[str, Any] | None = None

    if sent is not None:
        first = validate_custom_entry(fields, sent, enforce_required=False)
        dropped = first.dropped
        errors = dict(first.errors)
        to_store = dict(first.clean)
        for bad_key in first.errors:
            if bad_key in previous:
                to_store[bad_key] = previous[bad_key]
        # A RETIRED FIELD'S STORED ANSWER IS CARRIED FORWARD WHETHER THE CLIENT ECHOED IT OR NOT, AND
        # UNTIL THIS LOOP EXISTED THE RULE WAS UPHELD BY CLIENT GOODWILL RATHER THAN BY THE SERVER.
        #
        # `clean` is built only from the keys the incoming container carries, and nothing above
        # carried a value forward except for a coercion error and `merge=true`. So "a retired field's
        # stored answer survives" — stated as absolute in the module docstring, in
        # `validate_custom_entry` and in the migration header — held only for a client that echoed
        # keys for questions it is explicitly told not to render. A form is built from `live_fields`;
        # a client that fills its container from the form it drew therefore omits every retired key;
        # `merge` defaults to FALSE, so that omission read as a deletion. Executed:
        # `plan_custom_write([retired 'looms', live 'loomsR2'], sent={'loomsR2': 14},
        # previous={'looms': 12})` returned `data={'loomsR2': 14}`, `dropped=()`, `errors={}` — the
        # stored 12 simply gone, and gone SILENTLY precisely because the key was known, so nothing
        # appeared in `droppedCustomKeys` either. That is the supersede path, which is the common one:
        # rewording an answered question leaves exactly this shape behind.
        #
        # WHY THIS CANNOT SWALLOW A DELETION THE DESIGNER MEANT: a retired field is offered by no
        # form, so nobody can have cleared it, and an absent retired key therefore cannot be an
        # instruction. It is the same argument that already stops a bad value deleting a stored good
        # one, three lines above. A LIVE key absent from the payload still deletes, and must — a live
        # question is on the form, so its absence is a designer clearing an answer.
        for f in fields:
            if f.retired and f.key in previous and f.key not in to_store:
                to_store[f.key] = previous[f.key]
        if merge and previous:
            to_store = {**previous, **to_store}

    if submit:
        row = to_store if to_store is not None else previous
        for f in fields:
            # A retired field is not asked any more, so it cannot block a submission — exactly as
            # the scorer skips it, and for its reason: otherwise a stage is permanently incomplete
            # because of a question the designer corrected.
            if not f.required or f.retired or f.key in errors:
                continue
            if not _is_filled(row.get(f.key)):
                errors[f.key] = f"{f.label} is required"

    return CustomWrite(data=to_store, errors=errors, dropped=dropped)


def answered_keys(fields: Sequence[CustomFieldSpec], values: Mapping[str, Any]) -> set[str]:
    """Which of these fields actually hold an answer, judged by the completeness scorer's own test.

    "Answered" is the hinge of the edit-after-answers rule, and it is deliberately the SAME question
    the readiness screen asks — a field the scorer counts as filled is a field whose wording is now
    evidence.
    """
    return {f.key for f in fields if _is_filled(values.get(f.key))}


# --------------------------------------------------------------------------------------
# Editing a definition that already has answers
# --------------------------------------------------------------------------------------


class CustomSectionEditError(ValueError):
    """An edit that cannot be applied at all, carrying a sentence written for the designer.

    A ``ValueError`` and not an ``HTTPException``, exactly as ``QuestionnaireEditError`` and
    ``LayerRuleViolation`` are, so this module stays importable and testable with no framework
    underneath it. The route turns it into a status code; the sentence it carries names the next
    move, because this repository's errors are sentences and never codes.
    """


@dataclass(frozen=True, slots=True)
class FieldPlan:
    """What is to happen to one field. ``action`` is CREATE, EDIT, SUPERSEDE, RETIRE or DELETE."""

    action: str
    #: The stored row this acts on. Empty for a CREATE.
    field_id: str = ""
    #: The stored key this acts on, or the key a CREATE will be written under.
    key: str = ""
    #: The field as it should end up. For a SUPERSEDE this is the NEW field, under its minted key.
    spec: CustomFieldSpec | None = None
    #: For a SUPERSEDE: the row id of the retired predecessor, which will point at the new row.
    supersedes_id: str = ""


@dataclass(frozen=True, slots=True)
class SectionPlan:
    """What is to happen to one section and to every field under it."""

    action: str                    # CREATE | EDIT | RETIRE | DELETE
    section_id: str = ""
    spec: CustomSectionSpec | None = None
    fields: tuple[FieldPlan, ...] = ()
    #: Whether anything under this section supersedes or retires, which is what bumps the revision.
    bumps_revision: bool = False


@dataclass(frozen=True, slots=True)
class DefinitionPlan:
    """The whole PUT, described rather than performed.

    A PLAN AND NOT A COROUTINE THAT WRITES, for ``ai_layers.LayerWritePlan``'s reason: the rules
    above can then be asserted by ``pytest`` on a laptop with no Postgres and no generated client,
    and this repository's own history says the untestable half is the half that is wrong.
    """

    sections: tuple[SectionPlan, ...] = ()

    @property
    def superseded(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "SUPERSEDE")

    @property
    def retired(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "RETIRE") + sum(
            1 for s in self.sections if s.action == "RETIRE"
        )

    @property
    def created(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "CREATE")

    @property
    def deleted(self) -> int:
        return sum(1 for s in self.sections for f in s.fields if f.action == "DELETE") + sum(
            1 for s in self.sections if s.action == "DELETE"
        )


def _live_successor(
    field: CustomFieldSpec, by_id: Mapping[str, CustomFieldSpec]
) -> CustomFieldSpec | None:
    """Follow ``supersededById`` from a retired field to the live field that replaced it.

    **THIS IS WHAT MAKES A STALE CLIENT'S RE-PUT IDEMPOTENT RATHER THAN A SECOND SUPERSEDE.** A
    whole-set PUT names fields by key. After a supersede the old key belongs to a retired row and
    the new wording lives under a minted key — so an offline client that never refetched will send
    the OLD key with the NEW label, on every save, for ever. Without this walk each of those saves
    would look like another rewording of an answered field and mint another field, and one
    reconnection would leave a form with six copies of one question.

    Bounded and cycle-aware; see :data:`MAX_SUPERSEDE_HOPS` for why a plain column with no foreign
    key needs both.
    """
    seen: set[str] = set()
    current = field
    for _ in range(MAX_SUPERSEDE_HOPS):
        if not current.retired:
            return current
        if not current.superseded_by or current.superseded_by in seen:
            return None
        seen.add(current.superseded_by)
        nxt = by_id.get(current.superseded_by)
        if nxt is None:
            return None
        current = nxt
    return None


def _mint_key(base: str, taken: Iterable[str]) -> str:
    """A new key for the replacement field, unique across the workshop.

    IT IS NOT SHOWN TO ANYBODY AND IT IS NOT MEANT TO BE READ. The designer's key stays where their
    answers are; this is the storage key for the new wording, and the only property it needs is that
    it can never be one the designer chooses or has chosen. ``R2``/``R3`` rather than an underscore
    suffix because :data:`KEY_PATTERN` allows only letters and digits — the same rule that keeps a
    designer key from ever colliding with the ``_``-prefixed protocol ones.
    """
    used = set(taken)
    stem = (base or "field")[:MAX_CUSTOM_KEY_CHARS - 4]
    for n in range(2, 1000):
        candidate = f"{stem}R{n}"
        if candidate not in used:
            return candidate
    raise CustomSectionEditError(
        f"{base!r} has been reworded too many times to store another version of it. Retire the "
        f"field and add a new one with its own key."
    )


def plan_definition(
    stored: Sequence[CustomSectionSpec],
    incoming: Sequence[CustomSectionSpec],
    answered: Mapping[str, set[str]],
) -> DefinitionPlan:
    """Work out what one whole-set PUT actually does to a definition that may already have answers.

    ``answered`` is ``{stage key: the field keys that hold an answer}``, judged by
    :func:`answered_keys` — the completeness scorer's own test, so "answered" means one thing in
    this system.

    THE FIVE RULES ARE THE MODULE DOCSTRING'S, and each one is a branch below:

    * an unanswered field is edited or deleted outright;
    * an answered field may change everything except its label;
    * an answered field whose LABEL changed is SUPERSEDED — retired under its original wording,
      with a new field minted for the new wording;
    * an answered field the payload no longer names is RETIRED, never deleted;
    * a section is retired if anything under it has been answered, deleted if not.

    **A KEY STILL HELD BY ANY QUESTION ON THIS STAGE CANNOT BE CLAIMED BY A NEW FIELD, whichever
    section claims it.** ``validate_definition`` cannot see this — it is pure and reads only the
    payload — and without it the twelve-weavers failure was reachable by a route the label rule does
    not cover: a designer retires the section "Loom shed" (its answers stay under ``looms``), then
    adds a section "Shed survey" declaring its own ``looms`` labelled "How many weavers?". The
    container is per STAGE, so the new question would have read the old question's answer, and a
    ministry report would state there are twelve weavers.

    **AND "HELD" IS WIDER THAN "HOLDS AN ANSWER", WHICH IS WHERE THIS PARAGRAPH USED TO BE WRONG
    ABOUT ITS OWN CODE.** Retiring a section retires every field under it as collateral — INCLUDING
    the fields nobody ever answered, because the section's rows are kept whole rather than sifted. So
    "Loom shed", which asked for the secretary's name (answered "Sita") and how many looms there were
    (never answered), left ``looms`` held by a retired row with no answer in it: guarded by nothing,
    handed straight back to "Shed survey", and ``fields_for`` then returned TWO specs under ONE
    container key. Executed, that lands two ways and both destroy fieldwork. Types differing, the
    designer types "about nine" and the response is a 200 whose errors read *"How many looms? is not
    a valid int"* — naming a question on no screen — while the rejected-value preservation loop writes
    the row back as ``{"looms": 12}``, replacing the typed answer with the retired question's old
    value; under ``submit`` the same error 422s the whole stage. Types matching, the save looks clean
    and the document prints the one stored value TWICE, once under the retired heading as "(no longer
    asked)" and once under the new wording — the twelve-weavers failure verbatim, in a submitted
    document. The guard is therefore every key :func:`_keys_this_put_keeps` says will still be held by
    a row once this write is done, answered or not, with the same remedy ``_plan_fields`` offers for a
    retired key inside its own section: choose another key.

    **IT IS NOT PARITY WITH ``_plan_fields``, AND CALLING IT THAT IS HOW THE HOLE ABOVE WAS MISSED
    ONCE ALREADY.** Inside its own section a retired key is not refused unconditionally: it is
    FOLLOWED — :func:`_live_successor` walks ``supersededById`` to whatever is live now and the
    incoming field is treated as naming that, which is what stops a stale client minting a sixth copy
    of one question. Only a walk that dead-ends is refused. So the two rules are deliberately
    different, and the difference has to be mirrored in :func:`_keys_this_put_keeps` rather than
    waved at: the row the walk lands on is KEPT under a key the payload never named, and reading
    "the payload matched it" as "the payload named its key" let a new section claim exactly that key.

    **NO SCHEMA CHANGE COULD HAVE CAUGHT THIS.** Field uniqueness is section-scoped
    (``migration.sql``), which is correct for the schema — two sections may of course ask their own
    questions — and simply cannot express a rule about the one container a stage's answers share.
    :meth:`CustomDefinition.fields_for` refuses to return two specs under one key as the second line
    of defence, for a definition that is already in this state however it got there.

    THE ONE EDIT THIS FUNCTION REFUSES OUTRIGHT is moving an answered section to a different stage.
    The answers for a section live in the container of the stage it is asked at, so moving the
    section would leave them behind in the old stage's row: still stored, no longer asked, no longer
    scored, and invisible on every form. That is not an edit, it is a silent data loss, so it is a
    refusal with a sentence naming the way round it (retire this section and add a new one).
    """
    stored_by_key = {s.key: s for s in stored}
    plans: list[SectionPlan] = []
    taken_keys = {f.key for s in stored for f in s.fields} | {
        f.key for s in incoming for f in s.fields
    }
    held = _keys_this_put_keeps(stored, incoming, answered)

    for section in incoming:
        previous = stored_by_key.get(section.key)
        # The keys that already hold an answer IN THE CONTAINER THIS SECTION WOULD WRITE INTO. Read
        # off the incoming stage rather than the stored one, because that is the row a new field's
        # answers would land in — a section moved to another stage (only possible while nobody has
        # answered it) writes into a different container and inherits nothing.
        answered_target = set(answered.get(section.stage_key, set()))
        # And every key that container will still have a QUESTION for, answered or not. Both sets are
        # passed rather than one union so the refusal can say which of the two it is: "an answer is
        # already recorded under that key" and "that key still belongs to a question nobody is asked
        # any more" are different facts and a designer acts on them differently.
        held_target = held.get(section.stage_key, set())
        if previous is None:
            for f in section.fields:
                _refuse_answered_key(f, section, answered_target, held_target)
            plans.append(SectionPlan(
                action="CREATE",
                spec=section,
                fields=tuple(
                    FieldPlan(action="CREATE", key=f.key, spec=f) for f in section.fields
                ),
            ))
            continue

        answered_here = set(answered.get(previous.stage_key, set()))
        if section.stage_key != previous.stage_key and (
            answered_here & {f.key for f in previous.fields}
        ):
            raise CustomSectionEditError(
                f"Section {previous.title!r} cannot be moved to another stage, because answers have "
                f"already been recorded against it and they are stored with the stage they were "
                f"asked at. Leave it where it is, or remove it here and add a new section on the "
                f"other stage — the answers already given stay readable either way."
            )

        field_plans, bumped = _plan_fields(
            previous, section, answered_here, taken_keys, answered_target, held_target
        )
        plans.append(SectionPlan(
            action="EDIT",
            section_id=previous.id,
            spec=section,
            fields=field_plans,
            bumps_revision=bumped,
        ))

    incoming_keys = {s.key for s in incoming}
    for previous in stored:
        if previous.key in incoming_keys:
            continue
        if previous.retired:
            # Already retired and still absent: nothing to do. Re-retiring would rewrite the moment
            # it stopped being asked, which is the same mistake `save_stage`'s sweep avoids by
            # working over `live` rather than over every row.
            continue
        answered_here = set(answered.get(previous.stage_key, set()))
        touched = answered_here & {f.key for f in previous.fields}
        if touched:
            plans.append(SectionPlan(
                action="RETIRE",
                section_id=previous.id,
                spec=previous,
                bumps_revision=True,
                fields=tuple(
                    FieldPlan(action="RETIRE", field_id=f.id, key=f.key, spec=f)
                    for f in previous.fields if not f.retired
                ),
            ))
        else:
            plans.append(SectionPlan(action="DELETE", section_id=previous.id, spec=previous))

    return DefinitionPlan(sections=tuple(plans))


def _keys_this_put_keeps(
    stored: Sequence[CustomSectionSpec],
    incoming: Sequence[CustomSectionSpec],
    answered: Mapping[str, set[str]],
) -> dict[str, set[str]]:
    """Per stage, the stored field keys that will STILL be held by a row once this PUT is applied.

    The guard set for :func:`_refuse_answered_key`, and the reason it is not simply "every stored key
    on this stage" is that a designer drafting a form must be able to reorganise it. Moving a question
    from one section to another, and changing a section's key (the key IS what identifies a section,
    so a new one is a new section), both look exactly like a new field claiming a stored key — and in
    both, the row that held it is DELETEd by this same write. Refusing those would tell a designer to
    choose another key for a question they have not yet asked anybody.

    So this mirrors the only two branches of :func:`plan_definition` and :func:`_plan_fields` that can
    express a delete, and it is deliberately the mirror rather than a second opinion: **if a delete
    branch is ever widened, this must be widened with it, or the guard starts refusing keys that are
    about to be free.** It has to be computed up front rather than as the loop goes, because the
    section that frees a key is very often processed after the section that wants to claim it.

    **AND MIRRORING ``_plan_fields`` MEANS MIRRORING HOW IT MATCHES, NOT JUST WHAT IT DELETES.** The
    first version of this function read "the payload matched this row" as "the payload names this
    row's key", and that is the one thing in ``_plan_fields`` which is not true: an incoming key that
    lands on a RETIRED row is followed through :func:`_live_successor` to whatever is live now, so a
    payload naming the old key ``looms`` matches — and EDITs, and therefore KEEPS — the row minted
    under ``loomsR2``, a key the payload never mentions. That is not an exotic body either; it is the
    steady state :func:`_live_successor` exists for, which its own docstring describes as what a
    client that never refetched sends "on every save, for ever".

    A key is filed under the stage the section will be asked at AFTER this write, because that is the
    container a new field's answers would land in; the answers are read off the stage it is stored
    under, because that is the container they are sitting in now. The two differ only for a section
    being moved, which is only possible while nothing under it has been answered.
    """
    incoming_by_key = {s.key: s for s in incoming}
    kept: dict[str, set[str]] = {}
    for section in stored:
        answered_here = set(answered.get(section.stage_key, set()))
        named = incoming_by_key.get(section.key)
        if named is None:
            # Absent from the payload. The section is deleted outright — every field with it, through
            # the CASCADE — ONLY when nothing under it has been answered. Anything else keeps all of
            # its rows, INCLUDING the fields nobody answered: that collateral is the case the
            # answered-only guard could not see, and it is the whole of this defect.
            if section.retired or (answered_here & {f.key for f in section.fields}):
                kept.setdefault(section.stage_key, set()).update(f.key for f in section.fields)
            continue
        declared = {f.key for f in named.fields}
        # THE ROWS THIS PAYLOAD MATCHES, RESOLVED THE WAY `_plan_fields` RESOLVES THEM — through the
        # supersede walk and not by key alone. Reading `declared` as the whole of the match was a way
        # round this entire guard, and it was executed rather than reasoned about: against the state
        # one ordinary supersede leaves behind (`looms` answered 12, retired, pointing at a live
        # `loomsR2`), a PUT whose "Loom shed" re-sent the stale key `looms` while a new section "Dye
        # baths" declared its own `loomsR2` was ACCEPTED, and planned `loomsR2` as an EDIT and a
        # CREATE in one write. `fields_for` then had two live specs for one container slot, kept the
        # INT one, and the answer typed into "Which bath was used?" came back
        # `{'loomsR2': 'How many weavers work here? is not a valid int'}` — a question on no screen —
        # with the row rewritten as `{'looms': 12, 'loomsR2': 30}`, the typed value replaced by the
        # other question's old answer, and the whole stage 422 under `submit`. Verbatim the failure
        # the guard above exists to refuse, reached through the walk that exists to keep a stale
        # client's re-PUT idempotent. In rows this module wrote it is only ever a MINTED key that can
        # be taken this way, because a SUPERSEDE is the only thing that sets `supersededById` and
        # `_mint_key` names what it points at — but a designer may key their own field `loomsR2`,
        # nothing in this file has ever told them not to, and `supersededById` is a plain column a
        # repair script can point anywhere.
        by_key = {f.key: f for f in section.fields}
        by_id = {f.id: f for f in section.fields if f.id}
        matched: set[str] = set()
        for incoming_field in named.fields:
            target = by_key.get(incoming_field.key)
            if target is not None and target.retired:
                target = _live_successor(target, by_id)
            if target is not None and target.id:
                matched.add(target.id)
        for f in section.fields:
            # An unanswered, still-live field the payload has stopped naming is DELETEd and its key is
            # then genuinely free. Everything else — named (by its own key or through the walk),
            # answered, or already retired — keeps its row and therefore keeps its key.
            if (
                f.key in declared
                or f.retired
                or f.key in answered_here
                or (f.id and f.id in matched)
            ):
                kept.setdefault(named.stage_key, set()).add(f.key)
    return kept


def _refuse_answered_key(
    f: CustomFieldSpec,
    section: CustomSectionSpec,
    answered_target: set[str],
    held_target: set[str],
) -> None:
    """Refuse a NEW field whose key is still held by a different question on the same stage.

    TWO SETS AND NOT ONE, and the second is what makes this as strong as the file says it is. A key
    that HOLDS AN ANSWER is the obvious case and was the only one guarded; a key that is merely still
    HELD — by a question retired as collateral when its section was retired, which nobody ever
    answered — is the case that let one container key serve two questions. See
    :func:`plan_definition` for what that then did to a designer's fieldwork, and
    :func:`_keys_this_put_keeps` for how "still held" is decided.

    Each sentence names the key and the way round it, because "duplicate key" is a code with spaces
    in it and the designer holding the screen has to be able to act on it.
    """
    if f.key in answered_target:
        raise CustomSectionEditError(
            f"{f.key!r} cannot be used for {section.title!r}: an answer is already recorded under "
            f"that key on this stage, given to a different question. Attaching it to this one would "
            f"make the report state that the old answer answers the new wording. Choose another key "
            f"— the answer already given stays readable under the question it was asked as."
        )
    if f.key in held_target:
        raise CustomSectionEditError(
            f"{f.key!r} cannot be used for {section.title!r}: that key still belongs to a question "
            f"on this stage that is no longer asked. A stage's answers are one set of keys, so the "
            f"two questions would share one answer and the report would print it under both "
            f"wordings. Choose another key — the question that holds it keeps it for as long as the "
            f"section it was asked in is kept."
        )


def _plan_fields(
    previous: CustomSectionSpec,
    section: CustomSectionSpec,
    answered_here: set[str],
    taken_keys: set[str],
    answered_target: set[str],
    held_target: set[str],
) -> tuple[tuple[FieldPlan, ...], bool]:
    """The per-field half of :func:`plan_definition`. Returns the plans and whether to bump."""
    by_key = {f.key: f for f in previous.fields}
    by_id = {f.id: f for f in previous.fields if f.id}
    plans: list[FieldPlan] = []
    bumped = False
    matched_ids: set[str] = set()

    for f in section.fields:
        target = by_key.get(f.key)
        if target is not None and target.retired:
            # The stale-client case. Follow the supersede chain to whatever is live now and treat
            # the incoming field as naming THAT, so a client that has not refetched cannot mint a
            # new copy of a question on every save. See `_live_successor`.
            target = _live_successor(target, by_id)
            if target is None:
                raise CustomSectionEditError(
                    f"{f.key!r} belonged to a question that was removed after it had been "
                    f"answered. The answers are still held under that key, so it cannot be used "
                    f"again — choose another key for this question."
                )
        if target is None:
            _refuse_answered_key(f, section, answered_target, held_target)
            plans.append(FieldPlan(action="CREATE", key=f.key, spec=f))
            continue
        matched_ids.add(target.id)

        wording_changed = target.label.strip() != (f.label or "").strip()
        if wording_changed and target.key in answered_here:
            # RULE 3. The old field keeps its answers under its own key, retired, carrying the
            # wording those answers were given under; the new wording becomes a new field. Nothing
            # is refused and nothing is lost — and the report can still print both, which is what
            # makes an answer given under an old wording explicable a year later.
            minted = _mint_key(target.key, taken_keys)
            taken_keys.add(minted)
            plans.append(FieldPlan(
                action="SUPERSEDE",
                field_id=target.id,
                key=target.key,
                spec=replace(f, key=minted, id=""),
                supersedes_id=target.id,
            ))
            bumped = True
            continue
        # RULE 2. Everything else about an answered field is free to change: help, required, unit,
        # bounds, position. None of them alter what a recorded answer asserts.
        plans.append(FieldPlan(action="EDIT", field_id=target.id, key=target.key, spec=f))

    for stored_field in previous.fields:
        if stored_field.id in matched_ids or stored_field.retired:
            continue
        if stored_field.key in answered_here:
            # RULE 4. Retired, not deleted: it stops being asked and its answer stays readable and
            # printable. There is no foreign key underneath this — see the module docstring — so
            # this branch IS the enforcement.
            plans.append(FieldPlan(
                action="RETIRE", field_id=stored_field.id, key=stored_field.key, spec=stored_field
            ))
            bumped = True
        else:
            plans.append(FieldPlan(
                action="DELETE", field_id=stored_field.id, key=stored_field.key, spec=stored_field
            ))
    return tuple(plans), bumped


# --------------------------------------------------------------------------------------
# The wire
# --------------------------------------------------------------------------------------


def field_payload(f: CustomFieldSpec) -> dict[str, Any]:
    """One custom field as the clients read it. camelCase on the wire, snake_case in Python.

    Every key is present on every field, including the defaults — the opposite of
    ``field_to_dict``'s omit-the-defaults rule, and for a reason that does not apply here. That
    function is trimming a 119 KB registry that crosses the wire on every app start; a workshop's
    own definition is a few kilobytes at most, and a client that has to supply its own default for
    an absent key is a client that will eventually supply a different one from the server's.
    """
    return {
        "id": f.id,
        "key": f.key,
        "label": f.label,
        "type": f.type.value,
        "tier": f.tier.value,
        "required": f.required,
        "help": f.help,
        "unit": f.unit,
        "options": [{"value": o.value, "label": o.display} for o in f.options],
        "maxLength": f.max_length,
        "minValue": f.min_value,
        "maxValue": f.max_value,
        "sortOrder": f.sort_order,
        "retired": f.retired,
        "supersededById": f.superseded_by or None,
    }


def section_payload(s: CustomSectionSpec) -> dict[str, Any]:
    return {
        "id": s.id,
        "key": s.key,
        "stageKey": s.stage_key,
        "title": s.title,
        "description": s.description,
        "sortOrder": s.sort_order,
        "revision": s.revision,
        "retired": s.retired,
        "fields": [field_payload(f) for f in s.fields],
    }


def definition_payload(definition: CustomDefinition) -> dict[str, Any]:
    """A whole definition as ``GET /design-workshops/{id}/custom-sections`` returns it.

    **THE RETIRED SECTIONS AND FIELDS ARE ALWAYS INCLUDED**, and that is not a debugging
    convenience. ``DwQuestionnaireStore`` refuses a payload fetched without ``includeRetired`` for
    exactly this reason: a copy missing every answer given under a superseded wording makes the two
    copies of one report disagree about the fieldwork, with nothing in either saying so. A client
    renders the live ones and PRINTS the retired ones that hold an answer.
    """
    return {
        "customSchemaVersion": definition.version,
        "sections": [section_payload(s) for s in definition.sections],
        "fetchedAt": datetime.now(UTC).isoformat(),
    }


# --------------------------------------------------------------------------------------
# The database half: loading a definition, and applying a plan
#
# Everything above this line is pure and is what tests/test_custom_sections.py exercises with no
# Postgres and no generated Prisma client. Everything below is a thin call site: it reads rows, or
# it applies a plan built above. No rule is decided here.
# --------------------------------------------------------------------------------------


def _option_list(raw: Any) -> tuple[CustomOption, ...]:
    """Read the stored ``options`` JSON back, tolerating anything that is not the expected shape.

    A stored blob that is not a list of ``{value, label}`` objects yields NO options rather than
    raising. The alternative is a 500 on every read of a workshop whose one malformed row was
    written by hand — and an ENUM with no options renders as an empty picker, which is visible and
    fixable, while a failed load is a workshop nobody can open.
    """
    if not isinstance(raw, (list, tuple)):
        return ()
    out: list[CustomOption] = []
    for item in raw:
        if isinstance(item, Mapping):
            value = str(item.get("value", "") or "").strip()
            label = str(item.get("label", "") or "").strip()
        else:
            value, label = str(item or "").strip(), ""
        if value:
            out.append(CustomOption(value=value, label=label))
    return tuple(out)


def _field_from_row(row: Any) -> CustomFieldSpec:
    """One stored field row as a spec, reading an unknown type or tier as its safest neighbour.

    An unknown TYPE reads as TEXT and an unknown TIER as STANDARD, matching ``DwFieldType.of``'s
    degrade rule on the handset — but note what that costs and why it is still right HERE: a type
    this build cannot coerce would otherwise raise inside a stage save. The definition can only have
    been written by a server that allowed the token, so this branch is reachable only across a
    downgrade, and losing the type is better than losing the save.
    """
    try:
        field_type = FieldType(str(getattr(row, "type", "") or ""))
    except ValueError:
        field_type = FieldType.TEXT
    try:
        tier = Tier(str(getattr(row, "tier", "") or ""))
    except ValueError:
        tier = Tier.STANDARD
    return CustomFieldSpec(
        key=str(getattr(row, "key", "") or ""),
        label=str(getattr(row, "label", "") or ""),
        type=field_type,
        tier=tier,
        required=bool(getattr(row, "isRequired", False)),
        help=str(getattr(row, "help", "") or ""),
        unit=str(getattr(row, "unit", "") or ""),
        options=_option_list(getattr(row, "options", None)),
        max_length=int(getattr(row, "maxLength", 0) or 0),
        min_value=getattr(row, "minValue", None),
        max_value=getattr(row, "maxValue", None),
        sort_order=int(getattr(row, "sortOrder", 0) or 0),
        retired=not bool(getattr(row, "isActive", True)),
        superseded_by=str(getattr(row, "supersededById", "") or ""),
        id=str(getattr(row, "id", "") or ""),
    )


def _section_from_row(row: Any, fields: Sequence[CustomFieldSpec]) -> CustomSectionSpec:
    return CustomSectionSpec(
        key=str(getattr(row, "key", "") or ""),
        title=str(getattr(row, "title", "") or ""),
        stage_key=str(getattr(row, "stageKey", "") or ""),
        description=str(getattr(row, "description", "") or ""),
        sort_order=int(getattr(row, "sortOrder", 0) or 0),
        fields=tuple(sorted(fields, key=lambda f: (f.sort_order, f.key))),
        retired=not bool(getattr(row, "isActive", True)),
        revision=int(getattr(row, "revision", 1) or 1),
        id=str(getattr(row, "id", "") or ""),
    )


async def load_definition(workshop_id: str) -> CustomDefinition:
    """One workshop's whole definition, retired rows included.

    TWO FLAT QUERIES, NOT A NESTED ``include``. Prisma issues an include as its own sequential round
    trip per level, and on this deployment one round trip measured 756ms across regions — so a
    definition of twelve sections would cost thirteen. ``load_form`` in ``questionnaire_forms``
    makes the same choice for the same reason.

    A FAILURE HERE IS NOT AN EMPTY DEFINITION. It raises, deliberately: the callers that must not
    fail a designer's work over this — the report, and the stage save — decide that for themselves
    and say so where they decide it. Swallowing it here would mean a stage save silently dropping
    every custom answer as an unknown key, which is the loudest possible failure wearing the
    quietest possible clothes.
    """
    sections = await db.dwcustomsection.find_many(
        where={"designWorkshopId": workshop_id}, order={"sortOrder": "asc"}
    )
    if not sections:
        return EMPTY_DEFINITION
    field_rows = await db.dwcustomfield.find_many(
        where={"sectionId": {"in": [s.id for s in sections]}}, order={"sortOrder": "asc"}
    )
    grouped: dict[str, list[CustomFieldSpec]] = {}
    for row in field_rows:
        grouped.setdefault(str(row.sectionId), []).append(_field_from_row(row))
    specs = tuple(
        _section_from_row(row, grouped.get(str(row.id), [])) for row in sections
    )
    return CustomDefinition(sections=specs, version=custom_schema_version(specs))


async def load_definition_or_empty(workshop_id: str) -> CustomDefinition:
    """:func:`load_definition`, but an unreadable definition is an empty one and a log line.

    FOR THE READ PATHS ONLY — the report and the completeness scores — and never for the write. A
    report is the end of two weeks of fieldwork and one unreadable table must not take away a
    designer's ability to generate it at all; the same blind-except reasoning
    ``attach_report_questionnaires`` records. The stage save deliberately does NOT use this: there,
    an empty definition would mean every custom answer in the payload is an unknown key, so the
    honest outcome is to fail the save rather than to accept it having quietly dropped the fieldwork.
    """
    try:
        return await load_definition(workshop_id)
    except Exception:
        import logging

        logging.getLogger(__name__).exception(
            "custom sections could not be read for workshop %s", workshop_id
        )
        return EMPTY_DEFINITION


def _field_columns(f: CustomFieldSpec) -> dict[str, Any]:
    """The stored form of one field, ``options`` included and already wrapped for the driver.

    ── WHY ``options`` IS BUILT HERE AND NOT BY THE CALLER ────────────────────────────────────────
    It used to be the caller's, in one expression written out three times — CREATE, EDIT and
    SUPERSEDE — and all three of them wrote ``Json([...]) if spec.options else None``. That ``None``
    is why ``PUT /api/design-workshops/{id}/custom-sections`` answered **500 to every body that
    contained a field**, from the day the endpoint was written until it was measured on the wire:
    prisma-client-py renders an explicit ``None`` as ``options: null``, and the query engine refuses
    ``null`` for a nullable ``Json`` column outright —

        MissingRequiredValueError: `data.options`: A value is required but not set

    One field with no options was enough to fail the transaction, so no workshop could ever have a
    custom question, and the whole feature — this service, the web editor, the handset form and the
    report — was dead on the wire and always had been.

    **IT IS THE ONLY COLUMN OF THE ELEVEN HERE THAT BEHAVES THIS WAY,** which is exactly why the
    fault did not look like it could be about a null at all: ``maxLength``, ``minValue`` and
    ``maxValue`` are nullable SCALARS and the engine takes a ``null`` for each of them without
    complaint (measured, one at a time, against this database). Nullable ``Json`` is the special
    case. So the fix is not "strip every None from the create input" — that would have changed three
    columns that were never wrong and left this one exactly as it was, because ``options`` was never
    in this dict to be stripped.

    ── ONE EXPRESSION FOR "NO OPTIONS", ON CREATE AND ON UPDATE ALIKE ─────────────────────────────
    ``Json(None)`` and not an omitted key, and not ``Json([])``. Omitting it is correct on a CREATE
    (the column would take its declared NULL) and is a silent bug on an UPDATE: a field that HAD
    options and now has none would keep the stale list for ever — an ENUM retyped as TEXT still
    offering yesterday's picker, with a 200 saying it was saved. The update path therefore has to
    write a value, ``null`` is the one value the driver will not carry, and ``Json(None)`` reads
    back through prisma-client-py as ``None`` — identical to the NULL an omitted key would have
    left, and :func:`_option_list` turns both into no options. Using it in both places keeps ONE
    stored representation of "no options" instead of one per operation, and nothing in this
    repository filters this column on being null, so the jsonb-null/SQL-null distinction is not
    observable anywhere. ``Json([])`` would also be accepted, but it reads back as ``[]`` and so
    invents a second spelling of the same fact.
    """
    from prisma import Json

    return {
        "key": f.key,
        "label": f.label,
        "help": f.help,
        "type": f.type.value,
        "tier": f.tier.value,
        "isRequired": f.required,
        "unit": f.unit,
        "maxLength": f.max_length or None,
        "minValue": f.min_value,
        "maxValue": f.max_value,
        "sortOrder": f.sort_order,
        "options": Json(
            [{"value": o.value, "label": o.display} for o in f.options] if f.options else None
        ),
    }


async def apply_definition_plan(
    plan: DefinitionPlan, workshop_id: str, *, actor_id: str | None
) -> None:
    """Perform one planned definition write, whole, in one transaction.

    ONE TRANSACTION, for ``save_stage``'s reason: a definition edit is a many-statement write, and a
    failure halfway through would leave a form that is neither the old one nor the new one — with
    some fields superseded, their replacements missing, and answers hanging under keys nothing asks
    for any more.

    THE ORDER INSIDE IT IS NOT ARBITRARY. A supersede creates the replacement first and only then
    points the retired predecessor at it, because ``supersededById`` is a plain column with no
    foreign key: written the other way round, a failure between the two statements would leave a
    retired field pointing at a row that does not exist, and :func:`_live_successor` would read that
    as "removed after it was answered" and refuse the designer their own key for ever.

    NOTHING HERE TOUCHES THE DRIVER'S ``Json`` WRAPPER ANY MORE. It used to, in three places, and the
    three disagreed with each other about nothing and with the query engine about ``null`` — see
    :func:`_field_columns`, which is now the single place that decides the stored form of a field.
    """
    now = datetime.now(UTC)
    async with db.tx() as tx:
        for section_plan in plan.sections:
            section = section_plan.spec
            section_id = section_plan.section_id

            if section_plan.action == "CREATE" and section is not None:
                created = await tx.dwcustomsection.create(data={
                    "designWorkshopId": workshop_id,
                    "key": section.key,
                    "stageKey": section.stage_key,
                    "title": section.title,
                    "description": section.description,
                    "sortOrder": section.sort_order,
                    "createdById": actor_id,
                })
                section_id = created.id
            elif section_plan.action == "EDIT" and section is not None:
                await tx.dwcustomsection.update(
                    where={"id": section_id},
                    data={
                        "title": section.title,
                        "description": section.description,
                        "sortOrder": section.sort_order,
                        "stageKey": section.stage_key,
                        # A SECTION THE PAYLOAD NAMES IS ASKED, EVEN IF IT WAS RETIRED. A whole-set
                        # PUT is the definition, so naming a section means "ask this" — and the
                        # first version left `isActive` alone, which made re-adding a section a
                        # designer had removed do NOTHING AT ALL and say so nowhere: the response
                        # came back 200 with the section listed, no form ever offered it, and the
                        # fields created under it were invisible on every screen. Silent
                        # no-ops of exactly that shape are what this pipeline keeps having to fix.
                        # Its retired FIELDS stay retired — a wording that has answers under it is
                        # still evidence, and re-declaring one of those keys is refused by name.
                        "isActive": True,
                        "retiredAt": None,
                        **({"revision": {"increment": 1}} if section_plan.bumps_revision else {}),
                    },
                )
            elif section_plan.action == "RETIRE":
                await tx.dwcustomsection.update(
                    where={"id": section_id},
                    data={"isActive": False, "retiredAt": now, "revision": {"increment": 1}},
                )
            elif section_plan.action == "DELETE":
                # Only ever reached for a section nothing under which has been answered — the plan
                # cannot express any other delete. The fields go with it through the CASCADE.
                await tx.dwcustomsection.delete(where={"id": section_id})
                continue

            for field_plan in section_plan.fields:
                spec = field_plan.spec
                if field_plan.action == "CREATE" and spec is not None:
                    await tx.dwcustomfield.create(data={
                        "sectionId": section_id,
                        **_field_columns(spec),
                    })
                elif field_plan.action == "EDIT" and spec is not None:
                    await tx.dwcustomfield.update(
                        where={"id": field_plan.field_id},
                        data={
                            **_field_columns(spec),
                            # The stored key wins over the incoming one. They are equal on every
                            # ordinary path; they differ only if a caller reached this with a
                            # renamed key, and a rename is what orphans the answers.
                            "key": field_plan.key,
                        },
                    )
                elif field_plan.action == "SUPERSEDE" and spec is not None:
                    replacement = await tx.dwcustomfield.create(data={
                        "sectionId": section_id,
                        **_field_columns(spec),
                    })
                    await tx.dwcustomfield.update(
                        where={"id": field_plan.supersedes_id},
                        data={
                            "isActive": False,
                            "retiredAt": now,
                            "supersededById": replacement.id,
                        },
                    )
                elif field_plan.action == "RETIRE":
                    await tx.dwcustomfield.update(
                        where={"id": field_plan.field_id},
                        data={"isActive": False, "retiredAt": now},
                    )
                elif field_plan.action == "DELETE":
                    await tx.dwcustomfield.delete(where={"id": field_plan.field_id})


__all__ = [
    "CUSTOM_ENTITY_KEY",
    "EMPTY_DEFINITION",
    "KEY_PATTERN",
    "MAX_CUSTOM_DESCRIPTION_CHARS",
    "MAX_CUSTOM_FIELDS_PER_SECTION",
    "MAX_CUSTOM_HELP_CHARS",
    "MAX_CUSTOM_KEY_CHARS",
    "MAX_CUSTOM_LABEL_CHARS",
    "MAX_CUSTOM_OPTIONS",
    "MAX_CUSTOM_SECTIONS",
    "MAX_CUSTOM_TITLE_CHARS",
    "MAX_CUSTOM_UNIT_CHARS",
    "V1_FIELD_TYPES",
    "CustomDefinition",
    "CustomEntryResult",
    "CustomFieldSpec",
    "CustomOption",
    "CustomSectionEditError",
    "CustomSectionSpec",
    "CustomWrite",
    "DefinitionPlan",
    "FieldPlan",
    "SectionPlan",
    "answered_keys",
    "apply_definition_plan",
    "custom_schema_version",
    "definition_payload",
    "field_payload",
    "load_definition",
    "load_definition_or_empty",
    "plan_custom_write",
    "plan_definition",
    "section_payload",
    "to_field_spec",
    "validate_custom_entry",
    "validate_definition",
]
