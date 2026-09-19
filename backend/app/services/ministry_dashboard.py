"""THE MINISTRY DASHBOARD — the pure half of ``/api/ministry-dashboard``.

═══════════════════════════════════════════════════════════════════════════════════════════════
WHY THIS MODULE EXISTS AT ALL, WHICH IS NOT OBVIOUS FROM THE FEATURE NAME
═══════════════════════════════════════════════════════════════════════════════════════════════

The ministry's question is "how is the programme going?" — which workshops are moving, which have
stalled on a designer who never opened stage 1, which are finished. Before this module there was no
route in the product that could answer it, and the three obvious candidates each fail for a
different reason that is worth knowing before anybody proposes one of them again:

* ``GET /api/design-workshops`` **answers an empty 200 to a Ministry Admin.** It takes
  ``get_current_user`` with no role gate and scopes every non-``is_admin`` caller through
  ``design_workshop_viewers.visible_to_clause`` — created-by OR a viewer row — and a MINISTRY_ADMIN
  holds neither on any workshop. Not a refusal they could act on: a screen reporting no workshops in
  a repository full of them, which is this codebase's most repeated bug class.
  **Widening that clause is refused outright** and the refusal is not this module's to overturn:
  ``load_workshop_or_404(..., for_edit=True)`` reads the same relation, so an arm added there for
  officers would hand STAGE WRITES to accounts that must never have them. The argument is written
  out at ``api/routes/design_workshop_oversight.py``'s ``list_assignable_workshops``.
* ``GET /api/design-workshop-oversight/workshops`` lists the whole estate and is gated
  ``require_workshop_assigner`` — ``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}`` — which **refuses an
  ASSISTANT DIRECTOR and a REGIONAL DIRECTOR**, two thirds of this page's audience. Its docstring
  also refuses, in as many words, to grow the keys this page needs: *"``workshop_summary`` and
  nothing else. No stage data, no entries, no media, no viewer list … every extra field here would
  be a second, unscoped read of workshop content on a prefix whose whole premise is that its callers
  are outside the designer's scope."* Bolting progress onto that list is the one change that
  paragraph exists to prevent.
* ``GET /api/analytics/design-workshops`` is ``require_admin``, and an ADMIN is deliberately outside
  this page's audience (``deps.MINISTRY_DASHBOARD_ROLES`` says why).

So: a new prefix, a new gate, a scope of its own, and the progress arithmetic **borrowed rather than
rewritten**.

═══════════════════════════════════════════════════════════════════════════════════════════════
THE PROGRESS NUMBER IS NOT A NEW ONE, AND THAT IS THE MOST IMPORTANT LINE IN THIS FILE
═══════════════════════════════════════════════════════════════════════════════════════════════

``lib/submissionReadiness.ts`` opens: *"Completeness is already computed, twice and deliberately …
A THIRD scorer written to drive a checklist would be a third opinion, and the day it disagreed the
designer would be told to go and fill in a field the Save button was perfectly happy with."* A
MINISTRY dashboard disagreeing with the designer's own screen is that failure at the scale where it
decides funding and chasing, so :func:`progress_for` calls
``design_workshops.workshop_completeness`` — the same function the single-record read calls — and
rolls its output up with the identical arithmetic as ``overallPercent``
(``frontend/lib/designWorkshops.ts``): sum ``requiredFilled`` over sum ``requiredTotal``, and a
denominator of zero reads as complete rather than as 0%.

**TWO NUMBERS AND NOT ONE.** ``submissionReadiness.ts`` is explicit that a bare percentage is the
figure a progress screen *must not lead with*: *"'82%' … is the same number whether the remaining
18% is one date field or a stage nobody has opened."* So every row carries stages-complete and
fields-outstanding beside the percentage, and the page prints all three.

**AND A WORKSHOP WHOSE DEFINITION COULD NOT BE READ SAYS SO.** ``workshop_completeness``'s own
docstring warns that an absent ``definition`` means "this workshop has no designer-defined fields"
and never "do not count them", so a scorer that quietly skipped a failed read would over-report
progress for exactly the workshops carrying extra required questions. :func:`progress_for` therefore
carries ``definitionRead`` per row and the client renders the difference between "0% done" and "we
could not ask" — the readiness page's ``definitionSource === "unknown"`` rule, one surface further
out.

═══════════════════════════════════════════════════════════════════════════════════════════════
THE QUERY BUDGET, WHICH IS WHY THE BATCHING LOOKS FUSSY
═══════════════════════════════════════════════════════════════════════════════════════════════

``custom_sections.load_definition`` is two round trips PER WORKSHOP, and on this deployment one
round trip has measured 756ms across regions (that module's own note). A page of twenty workshops
scored one at a time is forty-one round trips before the first byte. :func:`load_definitions` and
:func:`progress_for` therefore take a LIST of ids and issue **three queries for the whole page** —
entries, sections, fields — grouped in memory. ``docs/SCALABILITY.md`` sizes this deployment against
a rural link and a single-worker box; a screen that re-reads itself on a timer cannot also be the
most expensive read in the product.

═══════════════════════════════════════════════════════════════════════════════════════════════
NO CLIENT-SIDE BUCKETING. THE STANDING GROUPS ARE A QUERY PARAMETER.
═══════════════════════════════════════════════════════════════════════════════════════════════

``sanction-orders/page.tsx`` states the rule this module serves: *"NO ``.filter()`` AND NO
``.sort()`` OVER ``data.items``, ANYWHERE ON THIS PAGE. Every narrowing is a query parameter. A list
filtered in the browser is the right SIZE and has silently dropped whatever it excluded."* So
:data:`STANDING_GROUPS` is resolved to a ``status: {"in": [...]}`` clause on the server by
:func:`standing_clause`, and ``GET /ministry-dashboard/summary`` counts the WHOLE scope rather than
the page — a bucket count taken from twenty loaded rows is a property of the page and not of the
platform, and on a ministry screen it is read as the size of the programme.
"""

from __future__ import annotations

from typing import Any

from app.core.db import db
from app.core.deps import is_admin, role_value
from app.services import custom_sections
from app.services.design_workshop_oversight import oversight_by_clause
from app.services.design_workshops import workshop_completeness
from app.services.stage_schema import stages

# --------------------------------------------------------------------------------------
# Standing groups
# --------------------------------------------------------------------------------------

#: The three words the ministry asked for, each resolved to the statuses it actually covers.
#:
#: **THESE ARE GROUPS OF EXISTING STANDINGS AND NOT NEW ONES.** ``DesignWorkshopStatus`` has eight
#: members and every one of them is the word the designer's screen, the inspector's queue, the
#: officer's list and the report all print; adding a ninth to mean "ongoing" would land in the three
#: places ``schemas/design_workshops.py`` requires at once and would be a status no transition graph
#: could produce. So this maps the ministry's vocabulary ONTO the enum and changes nothing about it,
#: and the page prints the members of whichever group is selected so a reader is never left guessing
#: which standings a word covered.
#:
#: ⚠ EVERY MEMBER OF THE ENUM IS IN EXACTLY ONE GROUP, AND :func:`_assert_partition` holds that at
#: import time. A status in none of them would be a workshop that exists on the platform, is counted
#: in the total, and cannot be reached by any filter on the screen — absence reading as
#: non-existence, wearing a filter control. A status in two would double-count the summary.
#:
#: WHY ``DRAFT`` IS "newly registered" AND NOT "ongoing". A workshop is created at ``DRAFT`` by the
#: act that registers it — a promoted annual-plan row, or a sanction order — and stays there until
#: somebody saves a stage. That is precisely "registered through the platform and not yet started",
#: which is the state a ministry chases. ``IN_PROGRESS`` is the first status that means a designer
#: has actually written something.
#:
#: WHY ``ARCHIVED`` IS "completed" RATHER THAN A FOURTH GROUP. It is a terminus reached from
#: IN_PROGRESS or PRE_SUBMISSION and never a resting state a workshop is worked in, so grouping it
#: with the other termini keeps "ongoing" meaning "somebody is expected to be doing something" —
#: which is the only reading that makes the group useful for chasing. The row's own badge still says
#: ARCHIVED, so nothing is hidden by the grouping.
STANDING_GROUPS: dict[str, tuple[str, ...]] = {
    "registered": ("DRAFT",),
    "ongoing": ("IN_PROGRESS", "NEEDS_REVISION", "PRE_SUBMISSION"),
    "completed": ("COMPLETE", "SUBMITTED", "APPROVED", "ARCHIVED"),
}

#: The eight members of ``DesignWorkshopStatus``, written out so :func:`_assert_partition` has
#: something independent to check :data:`STANDING_GROUPS` against.
#:
#: ⚠ NOT DERIVED FROM ``schemas.design_workshops.DESIGN_WORKSHOP_STATUSES``, deliberately, for the
#: reason ``e2e/ministry-surface-unit.spec.ts`` types its four ministry paths out by hand: deriving
#: it would make the partition check a restatement of itself, and a ninth status would silently
#: join whichever group the derivation happened to put it in. Typed here, a ninth status fails the
#: import and somebody has to decide which group it belongs to.
ALL_STANDINGS: tuple[str, ...] = (
    "DRAFT",
    "IN_PROGRESS",
    "COMPLETE",
    "PRE_SUBMISSION",
    "NEEDS_REVISION",
    "SUBMITTED",
    "APPROVED",
    "ARCHIVED",
)


def _assert_partition() -> None:
    """Hold :data:`STANDING_GROUPS` to :data:`ALL_STANDINGS` at import time, both directions."""
    grouped: list[str] = [status for members in STANDING_GROUPS.values() for status in members]
    duplicates = {status for status in grouped if grouped.count(status) > 1}
    if duplicates:
        raise RuntimeError(
            f"ministry dashboard standing groups double-count {sorted(duplicates)} — "
            "a workshop would be counted in two buckets of one summary"
        )
    missing = set(ALL_STANDINGS) - set(grouped)
    if missing:
        raise RuntimeError(
            f"ministry dashboard standing groups do not cover {sorted(missing)} — "
            "a workshop in that state would be unreachable from every filter on the screen"
        )
    unknown = set(grouped) - set(ALL_STANDINGS)
    if unknown:
        raise RuntimeError(
            f"ministry dashboard standing groups name {sorted(unknown)}, which is not a "
            "DesignWorkshopStatus"
        )


_assert_partition()


def standing_clause(group: str | None) -> dict[str, Any] | None:
    """The ``status`` filter for one standing group, or ``None`` for "everything".

    **AN UNRECOGNISED WORD IS IGNORED RATHER THAN REFUSED**, which is this repository's rule for
    every other narrowing on every other list route: *"an id the caller cannot see, or one that does
    not exist, matches nothing and is not an error — this is a filter, not a lookup"*
    (``design_workshop_oversight.list_assignable_workshops``). Absent means EVERY group, by absence,
    the way every filter in this product says "everything" — which is also why the client sends a
    WORD and never a boolean: ``buildQuery`` drops ``""`` exactly as it drops null.
    """
    members = STANDING_GROUPS.get((group or "").strip().lower())
    return {"status": {"in": list(members)}} if members else None


# --------------------------------------------------------------------------------------
# Scope
# --------------------------------------------------------------------------------------


def sees_whole_estate(user: Any) -> bool:
    """Does this account read every workshop, or only the ones it was posted to?

    MINISTRY_ADMIN and MASTER_ADMIN read the estate; ASSISTANT_DIRECTOR and REGIONAL_DIRECTOR read
    the workshops they were named on. ``is_admin`` is folded in so that the answer stays true if the
    door is ever widened to admins — this function must never be the reason an ADMIN sees an empty
    page.

    THE SPLIT IS NOT A RANK TEST DRESSED UP. A posting is what gives an officer a workshop, and no
    other screen in this product hands an officer workshops nobody named them on:
    ``/officers/monitored`` is scoped by exactly the clause below, and its own header says *"an
    officer with no oversight row sees an empty page, and that IS the whole scope."* A ministry
    dashboard that widened it would be the one surface in the product where a posting stopped
    meaning anything.
    """
    return is_admin(user) or role_value(user) == "MINISTRY_ADMIN"


def scope_clause(user: Any) -> dict[str, Any] | None:
    """The row scope for this caller, or ``None`` when they read the whole estate.

    ⚠ **THE CALLER MUST AND-COMPOSE THIS INTO** ``where["AND"]`` **AND NEVER ASSIGN IT TO**
    ``where["OR"]``, which the search box already owns. Two assignments to one key: the later
    silently wins, and the result is either a search that stops narrowing or a scope that vanishes
    the moment somebody types. Every list on the oversight prefix carries the same warning, and it is
    repeated here because this module's callers build the widest ``where`` in the feature.
    """
    if sees_whole_estate(user):
        return None
    return oversight_by_clause(str(getattr(user, "id", "") or ""))


def scope_label(user: Any) -> str:
    """One sentence naming what the caller is looking at, sent with every list.

    **ON THE WIRE AND NOT COMPOSED IN THE BROWSER**, which is the cross-client rule this repository
    keeps: where a feature lands on two surfaces the shared vocabulary comes from the server so the
    two cannot describe one decision differently. It matters more than usual here because the
    sentence is the whole defence against the silent-emptiness bug — an officer reading "every
    workshop on the platform" over their own four would be told the programme is four workshops
    large.
    """
    if sees_whole_estate(user):
        return "Every workshop on the platform."
    return (
        "The workshops you were named on as Assistant Director or Regional Director. A Ministry "
        "Administrator posts an officer to a workshop on Workshop oversight; until they have, there "
        "is nothing here to read."
    )


# --------------------------------------------------------------------------------------
# Progress
# --------------------------------------------------------------------------------------

#: How many workshops one request will score.
#:
#: A CEILING ON THE SCORING AND NOT ON THE LIST. The list route clamps ``pageSize`` to 100 like every
#: other paged route in this API; this is the separate question of how many of those rows get their
#: stage entries read, and it is lower because that read is the expensive one. A page larger than
#: this is still served IN FULL — the rows past the ceiling simply carry no progress figure and say
#: so, which is the honest degradation. Silently dropping the rows would be the truncation bug; a
#: silent 0% would be worse, because it is a confident false statement rather than an absence.
PROGRESS_SCORE_CAP = 100


async def load_definitions(workshop_ids: list[str]) -> dict[str, custom_sections.CustomDefinition]:
    """Every workshop's designer-defined fields, in two queries for the whole page.

    ``custom_sections.load_definition`` is the single-workshop version and is two round trips EACH;
    this is the batched twin and exists only because of that arithmetic (see the module header). It
    builds the same ``CustomDefinition`` objects through the same private constructors, so a workshop
    scored here and the same workshop scored by the single-record read cannot disagree.

    A workshop with no sections is absent from the returned map, and the caller reads that as
    ``EMPTY_DEFINITION`` — the same "no designer-defined fields" answer ``load_definition`` gives,
    and deliberately NOT the same answer as a failed read, which never reaches here at all: this
    function either returns for the whole page or raises for the whole page, and the route decides
    what to do about that in one place rather than per row.
    """
    if not workshop_ids:
        return {}
    sections = await db.dwcustomsection.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, order={"sortOrder": "asc"}
    )
    if not sections:
        return {}
    field_rows = await db.dwcustomfield.find_many(
        where={"sectionId": {"in": [s.id for s in sections]}}, order={"sortOrder": "asc"}
    )
    fields_by_section: dict[str, list[Any]] = {}
    for row in field_rows:
        fields_by_section.setdefault(str(row.sectionId), []).append(
            custom_sections._field_from_row(row)
        )

    sections_by_workshop: dict[str, list[Any]] = {}
    for row in sections:
        sections_by_workshop.setdefault(str(row.designWorkshopId), []).append(
            custom_sections._section_from_row(row, fields_by_section.get(str(row.id), []))
        )

    return {
        workshop_id: custom_sections.CustomDefinition(
            sections=tuple(specs),
            version=custom_sections.custom_schema_version(tuple(specs)),
        )
        for workshop_id, specs in sections_by_workshop.items()
    }


def _roll_up(completeness: dict[str, Any]) -> dict[str, Any]:
    """One workshop's per-stage scores rolled into the figures a register prints.

    THE ARITHMETIC IS ``overallPercent``'s, DELIBERATELY AND TO THE ROUNDING. That function
    (``frontend/lib/designWorkshops.ts``) sums ``requiredFilled`` over ``requiredTotal`` across every
    stage and answers 100 for a zero denominator — *"Same rule as the server's ``percent``: nothing
    required means nothing outstanding, not 0%"*. A second roll-up that averaged the per-stage
    percentages instead would weight a two-field stage the same as a hundred-field one and would
    disagree with the designer's own header on almost every workshop.

    ⚠ ``overallPercent`` RETURNS 0 FOR AN EMPTY MAP and this returns ``None``, and the difference is
    the whole reason this function exists rather than a one-line sum. An empty map here means the
    stages were not read, not that nothing has been done — and a ministry register printing 0%
    against a workshop it failed to score is the single most damaging false statement this screen
    could make.
    """
    scores = list(completeness.values())
    if not scores:
        return {
            "percent": None,
            "stagesTotal": len(stages()),
            "stagesComplete": None,
            "requiredTotal": None,
            "requiredFilled": None,
        }
    required_total = sum(int(score["requiredTotal"]) for score in scores)
    required_filled = sum(int(score["requiredFilled"]) for score in scores)
    return {
        "percent": 100 if required_total == 0 else round(100 * required_filled / required_total),
        "stagesTotal": len(scores),
        "stagesComplete": sum(1 for score in scores if score["isComplete"]),
        "requiredTotal": required_total,
        "requiredFilled": required_filled,
    }


def unscored(reason: str) -> dict[str, Any]:
    """The progress block for a workshop this request did not score, and WHY.

    ``reason`` is a token the client turns into a sentence — ``"capped"`` (this page is longer than
    :data:`PROGRESS_SCORE_CAP`) or ``"unreadable"`` (the stage rows or the designer-defined fields
    could not be read). Two tokens rather than one because the two are different facts about the
    workshop and suggest different next moves, and one shared "no progress" would collapse them.

    **NEVER A ZERO.** See :func:`_roll_up`.
    """
    return {
        "percent": None,
        "stagesTotal": len(stages()),
        "stagesComplete": None,
        "requiredTotal": None,
        "requiredFilled": None,
        "definitionRead": False,
        "unscoredReason": reason,
    }


async def progress_for(workshop_ids: list[str]) -> dict[str, dict[str, Any]]:
    """Score a page of workshops in three queries, the ministry register's figures per row.

    Returns a map keyed by workshop id. **A workshop absent from the map was not scored**, and the
    caller must render :func:`unscored` for it rather than a zero — the distinction the module header
    and :func:`_roll_up` both argue at length.

    ``deletedAt: None`` on the entry read, matching ``design_workshops.entry_rows``: a soft-deleted
    entry is not part of the workshop and counting it would score a stage the designer has already
    taken back.
    """
    ids = workshop_ids[:PROGRESS_SCORE_CAP]
    if not ids:
        return {}

    entries = await db.dwstageentry.find_many(
        where={"designWorkshopId": {"in": ids}, "deletedAt": None}, order={"ordinal": "asc"}
    )
    definitions = await load_definitions(ids)

    by_workshop: dict[str, list[Any]] = {workshop_id: [] for workshop_id in ids}
    for row in entries:
        bucket = by_workshop.get(str(row.designWorkshopId))
        if bucket is not None:
            bucket.append(row)

    out: dict[str, dict[str, Any]] = {}
    for workshop_id in ids:
        definition = definitions.get(workshop_id, custom_sections.EMPTY_DEFINITION)
        rolled = _roll_up(
            workshop_completeness(by_workshop.get(workshop_id, []), definition=definition)
        )
        rolled["definitionRead"] = True
        rolled["customSchemaVersion"] = definition.version
        out[workshop_id] = rolled
    return out


# --------------------------------------------------------------------------------------
# Counts
# --------------------------------------------------------------------------------------


def _group_count(row: Any) -> int:
    """The count out of one ``group_by(..., count=True)`` row.

    ⚠ **``_count`` IS A DICT AND NOT AN INT.** ``count=True`` asks prisma-client-py for the ``_all``
    aggregate, and the row comes back as ``{"<by field>": …, "_count": {"_all": 7}}``.
    ``services/usage.py`` — the only other caller of ``group_by`` in this codebase — reads it as
    ``int(group["_count"]["_all"])``, and that is the shape this follows.

    ``_all`` IS NAMED RATHER THAN TAKEN AS "the first value", which is what this function replaced.
    ``next(iter(count.values()))`` happens to be right for a one-key dict and is right BY ACCIDENT:
    the day somebody adds a per-field count to the query, the dict gains a second key and the answer
    becomes whichever one the mapping happens to yield first. A register printing the wrong number of
    beneficiaries against a workshop is not a failure anybody would notice from the screen.

    The int arm is kept because it costs a line and the alternative — a TypeError inside a list read —
    would take out the whole register over a client-library upgrade that changed one return shape.
    """
    count = row.get("_count") if isinstance(row, dict) else None
    if isinstance(count, dict):
        return int(count.get("_all") or 0)
    return int(count or 0)


async def beneficiary_counts(workshop_ids: list[str]) -> dict[str, int]:
    """How many artisans are on each of these design & prototype workshops, in ONE query.

    ``group_by`` and not ``include={"artisans": True}``, and not a count per row: the include loads
    every artisan ROW to count them — regulated person-records, pulled into memory on a
    single-worker box, to produce an integer — and a per-row count is one round trip per workshop on
    a screen that re-reads itself. ``services/usage.py`` is the precedent for reaching for
    ``group_by`` when the answer is an aggregate.

    A WORKSHOP WITH NO ARTISANS IS ABSENT FROM THE RESULT AND THE CALLER READS THAT AS ZERO, which
    is safe HERE and would not be safe for progress: an empty roster is a real, ordinary state on the
    day a workshop is opened, whereas an unscored workshop is a fact about the request. The two
    absences mean different things and are deliberately not handled the same way — see
    :func:`unscored`.
    """
    if not workshop_ids:
        return {}
    groups = await db.artisan.group_by(
        by=["designWorkshopId"],
        count=True,
        where={"designWorkshopId": {"in": workshop_ids}},
    )
    out: dict[str, int] = {}
    for row in groups:
        key = row.get("designWorkshopId") if isinstance(row, dict) else None
        if not key:
            continue
        out[str(key)] = _group_count(row)
    return out


async def legacy_beneficiary_counts(workshop_ids: list[str]) -> dict[str, int]:
    """The same figure for the legacy ``Workshop`` table, whose artisans are a JOIN TABLE.

    ``WorkshopArtisan`` is a many-to-many (``@@id([workshopId, artisanId])``) while
    ``Artisan.designWorkshopId`` is a nullable FK — one artisan belongs to at most one design
    workshop and to any number of recorded workshops. So these two counts are not the same
    arithmetic wearing two names, and the column headings on the two tabs say "Artisans" for
    different reasons. Stated here because the obvious tidy-up is to merge the two functions.
    """
    if not workshop_ids:
        return {}
    groups = await db.workshopartisan.group_by(
        by=["workshopId"], count=True, where={"workshopId": {"in": workshop_ids}}
    )
    out: dict[str, int] = {}
    for row in groups:
        key = row.get("workshopId") if isinstance(row, dict) else None
        if not key:
            continue
        out[str(key)] = _group_count(row)
    return out


def group_of(status: str | None) -> str | None:
    """Which standing group one status falls in, or ``None`` for a status this build has not heard of.

    A server one release ahead can legitimately store a ninth status, and this must answer "I do not
    know" rather than filing it under whichever group is checked first. The caller counts those
    separately and the page says so, because a workshop silently absent from all three buckets while
    present in the total is the summary contradicting its own rows.
    """
    for name, members in STANDING_GROUPS.items():
        if status in members:
            return name
    return None
