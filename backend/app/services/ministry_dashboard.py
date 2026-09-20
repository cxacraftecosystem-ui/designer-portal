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
from app.services import (
    custom_sections,
    design_workshop_inspectors as inspector_service,
    design_workshop_oversight as officer_service,
    designers,
)
from app.services.concurrency import gather_reads
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


# --------------------------------------------------------------------------------------
# THE PEOPLE REGISTERS — designers, officers and inspectors
# --------------------------------------------------------------------------------------
#
# ═══════════════════════════════════════════════════════════════════════════════════════════════
# WHY THE STRING ON THE WORKSHOP ROW COULD NOT ANSWER ANY OF THIS
# ═══════════════════════════════════════════════════════════════════════════════════════════════
#
# Until these three reads existed, everything this dashboard knew about a PERSON was
# ``DesignWorkshop.designerName`` — a column ``promoted_values()`` denormalises off stage 1. It is a
# free-typed string, it carries no index, it is not unique, it is not a foreign key and nothing
# reconciles it against an account: two spellings of one person are two designers, one spelling
# shared by two people is one designer, and a designer who never opened stage 1 has no name at all.
# Counting workshops by grouping on it would have produced a register that looked authoritative and
# was arithmetic over typing. ``design_workshop_oversight._the_lead_among`` exists precisely because
# matching that string back to an account is a fuzzy, case-folded, profile-assisted guess that
# answers ``None`` whenever it is not certain — the honest shape for a guess and the wrong shape for
# a register.
#
# So every figure below is counted over an ID-LEVEL RELATION:
#
#   * designers  — ``DesignWorkshopViewer`` (who may open the workshop), UNION the workshop's own
#                  ``createdById``. Both arms are needed and the second is the one a later reader
#                  will try to delete: ``named_designer_rows``' own docstring records that **the
#                  creator holds no viewer row** — their access is ``createdById`` — so a register
#                  built on viewer rows alone would omit the one person most likely to BE the lead
#                  designer, on every workshop nobody has shared. Absence reading as non-existence,
#                  in the column the page is named after.
#   * officers   — ``DesignWorkshopOversight``, the same table ``/officers/monitored`` is scoped by.
#   * inspectors — ``DesignWorkshopInspector``, plus ``DwInspectionFeedback`` for what they filed.
#
# ═══════════════════════════════════════════════════════════════════════════════════════════════
# THE SCAN IS CAPPED AND EVERY PAYLOAD SAYS SO, WHICH IS THE ONLY WAY A DERIVED REGISTER READS TRUE
# ═══════════════════════════════════════════════════════════════════════════════════════════════
#
# These lists are not rows in a table — they are an aggregate over the workshops one request read.
# That makes "how many workshops is she running" a figure whose meaning depends entirely on how many
# workshops were looked at, and a count over a silently-capped scan is the most confident wrong
# number this feature could print. :func:`scan_report` therefore travels with all three payloads
# carrying ``workshopsInScope``, ``workshopsRead`` and a sentence, and the sentence is present
# whenever the two differ. Same rule as ``truncated`` on the pickers, same rule as the
# ``_truncation_row`` inside the CSV: a list that quietly stops is indistinguishable from a place
# with no records.
#
# ⚠ AND THE COUNTS ARE COUNTS WITHIN THE SCOPE, WHICH IS NOT THE SAME SENTENCE AS THE CAP. An
# Assistant Director reads the workshops they were POSTED TO, so "four workshops" against a designer
# means four of THIS OFFICER'S workshops and not four on the platform. That is why this router's
# per-list ``scope``/``scopeLabel`` rule applies here with more force than it does to the two
# workshop registers: :func:`people_scope_label` says the thing in words rather than leaving a
# reader to infer it from a tab they are not looking at.


#: How many workshops ONE people read aggregates over.
#:
#: **A CEILING ON THE SCAN, STATED ON THE WIRE WHEN IT BITES** (:func:`scan_report`). It is not a
#: page size: the page size bounds how many PEOPLE come back, this bounds how many WORKSHOPS were
#: looked at to find them, and only the second one can silently change what a number MEANS.
#:
#: FIVE HUNDRED, which is ``designers.DIRECTORY_TAKE``'s figure and chosen the same way. Each scanned
#: workshop drags a handful of link rows behind it — a viewer row or three, at most two oversight
#: rows, an inspector row or two — so a full scan is the same order of work as the directory reads
#: this product already serves behind a picker, and it is bounded by a ``take`` the deployment's
#: single-worker box can predict. Raising it is a question about that box (``docs/SCALABILITY.md``)
#: and not a question about this screen.
PEOPLE_WORKSHOP_SCAN = 500

#: The order a people read scans workshops in.
#:
#: ⚠ **IDENTICAL TO** ``routes/ministry_dashboard._DESIGN_ORDER``, **TIEBREAK INCLUDED**, and written
#: again here rather than imported because a service importing its own router inverts the dependency
#: this module's header spends its first paragraph on. ``tests/test_ministry_dashboard_gate.py``
#: holds the two equal, so the duplication cannot drift.
#:
#: THE ``id`` TIEBREAK IS LOAD-BEARING ON A CAPPED SCAN and not only on a paged list. Without it,
#: WHICH five hundred workshops fall inside the cap is Postgres's choice among rows sharing an
#: ``updatedAt`` — so two identical requests can scan two different sets, and a designer's workshop
#: count would change on refresh with nothing on screen to say why. ``records.with_id_tiebreak``
#: carries the general rule.
PEOPLE_SCAN_ORDER: list[dict[str, str]] = [
    {"updatedAt": "desc"},
    {"createdAt": "desc"},
    {"id": "asc"},
]

#: The tiers a people register NAMES NOBODY FROM, however many workshops they hold.
#:
#: ⚠ **THIS IS THE** ``include_admins=False`` **BOUNDARY, AND IT IS A DISCLOSURE RULE RATHER THAN A
#: TIDY-UP.** ``routes/sanction_orders.list_sanction_designers`` records what it is for: the third
#: door onto the designer directory opened at a rank floor of ASSISTANT_DIRECTOR and would have
#: handed *"the complete privileged-account directory of the installation to a tier that is refused
#: all of the other designer lists, one letter of search at a time"*. This router's floor is the same
#: rank 42, and a register keyed on a relation is a slower version of the same read: an ADMIN who
#: opened one workshop would otherwise appear here by name, address and role.
#:
#: Named off ``designers.NEVER_ROSTER_GATED_ROLES`` so there is ONE list of "the privileged tiers"
#: rather than two that can disagree — that constant is exactly what ``workshop_capable_accounts``
#: folds out when its caller passes ``include_admins=False``.
#:
#: **WHAT IS WITHHELD IS COUNTED AND REPORTED** (``withheldAccounts`` on every people payload). A
#: person dropped from a list without a word is the truncation bug wearing a permissions hat: the
#: workshops they hold are still inside ``workshopsRead``, so the totals would not reconcile and
#: nothing would say why. The COUNT names nobody and is the honest half of the same fact.
WITHHELD_PERSON_ROLES: tuple[str, ...] = tuple(designers.NEVER_ROSTER_GATED_ROLES)


def people_scope_label(user: Any, *, noun: str, includes_unposted: bool) -> str:
    """One sentence naming who is in THIS list and who therefore is not. Compare :func:`scope_label`.

    ⚠ **ITS OWN SENTENCE AND NOT** :func:`scope_label` **REUSED**, which is this router's per-list
    scope rule applied to the people half. ``scope_label`` describes a list of WORKSHOPS; these lists
    are people derived FROM workshops, and the derivation is exactly what a reader cannot see. "The
    workshops you were named on" printed over a list of designers does not tell an Assistant Director
    that a colleague running twenty workshops elsewhere is missing from it — this does, in words,
    which is the whole defence against four rows reading as the size of the directorate.

    ⚠ **AND IT TAKES** ``includes_unposted`` **BECAUSE THE FOLD CHANGES WHO IS IN THE LIST, WHICH IS
    A CORRECTNESS BUG AND NOT A WORDING ONE.** Two of these registers fold an account directory in
    (see :func:`may_read_account_directories` and :func:`empanelled_designer_accounts`), and when
    they do, "somebody who works only on workshops you were not posted to is absent from this list"
    STOPS BEING TRUE — they are present, with a measured zero. A caption that says a person is
    absent while the row is on screen beside it is the same class of defect as a caption claiming the
    estate, pointed the other way: the reader stops believing the caption, and this caption is the
    whole defence the page has.
    """
    if sees_whole_estate(user):
        head = (
            f"Every {noun} holding a row on a design & prototype workshop on the platform. This "
            "register is built FROM the workshops, so every figure counts workshops rather than "
            "people."
        )
        tail = (
            f" It also names every {noun} account holding nothing at all, with a measured zero, so "
            "somebody who has been given no work is visible rather than absent."
            if includes_unposted
            else f" An account with no workshop row at all does not appear here — which is not the "
            f"same statement as there being no such {noun}."
        )
        return head + tail
    head = (
        f"The {noun}s on the workshops you were named on as Assistant Director or Regional "
        "Director. The counts beside each name are counts WITHIN your postings rather than on the "
        "platform. A Ministry Administrator posts an officer to a workshop on Workshop oversight; "
        "until they have, there is nothing here to read."
    )
    tail = (
        f" The list also names {noun}s holding nothing in your postings, with a measured zero, so "
        "somebody who has been given no work is visible rather than absent."
        if includes_unposted
        else f" A {noun} working solely on workshops you were not posted to is absent from this "
        "list entirely."
    )
    return head + tail


def scan_report(total_in_scope: int, read: int) -> dict[str, Any]:
    """What this request actually looked at, and the sentence a capped scan owes the screen.

    **THE NOTE IS** ``None`` **WHEN NOTHING WAS CUT AND A SENTENCE WHEN SOMETHING WAS**, rather than
    a boolean the client has to find words for. The two halves of this dashboard already proved that
    a caption composed in the browser drifts from the decision the server took (see the warning on
    ``register_summary``), and this is a harder sentence than that one: it has to say that the
    COUNTS are partial and not merely that the LIST is.
    """
    truncated = read < total_in_scope
    return {
        "workshopsInScope": total_in_scope,
        "workshopsRead": read,
        "workshopScanCap": PEOPLE_WORKSHOP_SCAN,
        "truncated": truncated,
        "truncationNote": (
            (
                f"Built from the {read} most recently updated workshops of {total_in_scope} in "
                f"scope — this register scans at most {PEOPLE_WORKSHOP_SCAN}. Somebody whose "
                "workshops are all older than that is missing from this list, and every count "
                "below is a count within the workshops that were read."
            )
            if truncated
            else None
        ),
    }


async def people_spine(
    where: dict[str, Any], *, take: int = PEOPLE_WORKSHOP_SCAN
) -> tuple[int, list[Any]]:
    """The workshops a people register aggregates over: how many exist, and the ones it read.

    TWO QUERIES, GATHERED, and the count is over the WHOLE scope rather than over the scan — the
    same split ``_design_rows`` makes, for the reason ``register_summary`` states: a total taken
    from the rows that were loaded is a property of the request and not of the programme, and on a
    ministry screen it is read as the second thing.
    """
    total, records = await gather_reads(
        db.designworkshop.count(where=where),
        db.designworkshop.find_many(where=where, take=take, order=PEOPLE_SCAN_ORDER),
    )
    return int(total), list(records)


def is_withheld_person(user: Any) -> bool:
    """Is this an account a people register names nobody from? See :data:`WITHHELD_PERSON_ROLES`."""
    return role_value(user) in WITHHELD_PERSON_ROLES


def new_person_row(user: Any) -> dict[str, Any]:
    """One person as every register on this prefix names them, plus the counters it measures.

    ⚠ **THE IDENTITY HALF COMES FROM** ``designers.assignable_designers_payload`` **AND IS NOT
    HAND-WRITTEN HERE**, so the four keys cannot drift from the officer's picker: ``id``, ``name``,
    ``email``, ``role``. **NO** ``rosterActive``, **NO** ``canSignIn``, **NO** ``firstSeenAt``,
    **NO** ``institution``, **NO** ``rosterId``, **NO** ``hasProfile``. Every one of those is a fact
    about the EMPANELMENT table, which ``can_manage_designer_roster`` (``is_admin``) stands in front
    of, and that payload's own docstring gives the reason in a line: *"suspended" is a judgement the
    institution made about a person, and it is not an officer's business which designers have one.*
    An Assistant Director reading this register is rank 42 and is outside that gate.

    THE COUNTERS START AT ZERO AND THE PROGRESS FIGURES START AT ``None``, WHICH IS THE DIFFERENCE
    THIS WHOLE FEATURE IS BUILT ON. A workshop count of zero is unreachable — a person is in this
    register because a relation put them there — so those counters are measured by construction. A
    progress figure is not: it exists only for the workshops this request SCORED, and ``0%`` against
    a person nobody scored is :func:`_roll_up`'s "single most damaging false statement" one surface
    further out. See :func:`close_person_progress`.
    """
    row = designers.assignable_designers_payload([user])[0]
    row.update(
        {
            "workshops": 0,
            # The standing groups, seeded so a person with none in a group prints a MEASURED zero
            # rather than a missing key — a client cannot tell an absent key from a zero.
            "registered": 0,
            "ongoing": 0,
            "completed": 0,
            # A status this build has not heard of. ``group_of`` answers None for it and
            # ``register_summary`` carries the same key for the same reason: a workshop counted in
            # the total and absent from every group is a row contradicting its own summary.
            "unclassifiedStanding": 0,
            "workshopsScored": 0,
            "percent": None,
            "stagesComplete": None,
            "stagesTotal": None,
            "requiredTotal": None,
            "requiredFilled": None,
            "progressReason": None,
        }
    )
    return row


def count_workshop(row: dict[str, Any], record: Any) -> None:
    """Add one workshop to a person's tally, under the standing group it belongs to."""
    row["workshops"] += 1
    group = group_of(getattr(record, "status", None))
    row["unclassifiedStanding" if group is None else group] += 1


def add_score(row: dict[str, Any], scored: dict[str, Any] | None) -> None:
    """Fold one workshop's roll-up into a person's totals, or leave the person unscored.

    A WORKSHOP THAT WAS NOT SCORED CONTRIBUTES NOTHING AND IS NOT COUNTED AS SCORED, which is what
    keeps ``workshopsScored`` an honest denominator: it is the number of this person's workshops
    whose stage entries this request actually read, and it travels beside the percentage so "61%
    over 2 of her 9 workshops" cannot be misread as "61% of her work".
    """
    if not scored or scored.get("requiredTotal") is None:
        return
    row["workshopsScored"] += 1
    for key in ("requiredTotal", "requiredFilled", "stagesComplete", "stagesTotal"):
        row[key] = int(row[key] or 0) + int(scored.get(key) or 0)


def close_person_progress(row: dict[str, Any], *, scoring_read: bool) -> None:
    """The percentage across a person's scored workshops, or the REASON there is none.

    THE ARITHMETIC IS :func:`_roll_up`'S, ONE LEVEL UP: sum ``requiredFilled`` over sum
    ``requiredTotal`` across the person's workshops, and a zero denominator reads as complete rather
    than as 0%. Averaging the per-workshop percentages instead would weight a workshop with four
    required fields the same as one with two hundred, and would disagree with the register's own rows
    on the tab beside this one.

    **AND THE THREE ABSENCES STAY THREE FACTS.** ``"noWorkshops"`` means this person holds no
    workshop in the scanned scope at all — a MEASURED nothing, and the answer for an empanelled
    designer who has not been given anything yet, which is precisely the row a ministry chases.
    ``"unreadable"`` means the scoring read failed for this whole request. ``"capped"`` means it
    succeeded and none of THIS person's workshops fell inside ``PROGRESS_SCORE_CAP``.
    :func:`unscored` splits its own pair for the same reason — they suggest different next moves, and
    one shared "no progress" would collapse "she has done none of it", "we did not look" and "there
    is nothing to look at" into a single shrug.
    """
    if int(row["workshops"]) == 0:
        row["percent"] = None
        row["progressReason"] = "noWorkshops"
        return
    if not scoring_read:
        row["percent"] = None
        row["progressReason"] = "unreadable"
        return
    if row["workshopsScored"] == 0:
        row["percent"] = None
        row["progressReason"] = "capped"
        return
    required_total = int(row["requiredTotal"] or 0)
    row["percent"] = (
        100 if required_total == 0 else round(100 * int(row["requiredFilled"] or 0) / required_total)
    )
    row["progressReason"] = None


def order_people(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The people order, and it is TOTAL — busiest first, then by name, then by account id.

    ⚠ THE ``id`` TIEBREAK IS ``_DESIGN_ORDER``'S RULE AND IT APPLIES HERE FOR ITS REASON rather than
    by analogy: these lists are PAGED, display names in ``User`` are not unique, and two people with
    the same name and the same workshop count would otherwise sort in whatever order the accumulator
    happened to be built in — which is insertion order over a relation read, i.e. Postgres's choice.
    A row that changes side of the page cut between two requests is *"handed over TWICE … or NEVER —
    and either way the response looks perfectly healthy"* (``records.with_id_tiebreak``).

    Busiest first, because the question this page was asked is who is carrying the programme; the
    alphabet is the tiebreak and not the sort.
    """
    return sorted(
        rows,
        key=lambda row: (
            -int(row["workshops"]),
            (row.get("name") or "").casefold(),
            str(row["id"]),
        ),
    )


# ── The id-level links, one query each ───────────────────────────────────────────────────────────


async def designer_links(workshop_ids: list[str]) -> list[Any]:
    """Every viewer row on these workshops, with its account. ONE query for the whole scan.

    ``DesignWorkshopViewer`` IS THE DESIGNER-TO-WORKSHOP LINK and ``DesignWorkshop`` carries no
    designer foreign key at all — see this section's header for what the ``designerName`` string
    cannot do. The ``@@index([userId])`` on that table serves the other direction (*"which workshops
    may this user see"*); this read goes by ``designWorkshopId``, the leading column of the primary
    key, so the ``IN`` is served by the PK rather than by a scan.

    ⚠ READ, AND ONLY EVER READ. ``tests/test_workshop_oversight_unit.py`` sweeps the three oversight
    files for ANY call on this model, because a viewer row confers STAGE WRITES —
    ``load_workshop_or_404(for_edit=True)`` reads the same relation. That sweep does not cover this
    module and the rule it protects does: nothing on this prefix writes, the router declares no
    non-GET route, and a ``create`` here would hand an officer a designer's save button.
    """
    if not workshop_ids:
        return []
    return await db.designworkshopviewer.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, include={"user": True}
    )


async def creator_accounts(records: list[Any]) -> dict[str, Any]:
    """The accounts that OPENED these workshops, keyed by user id. ONE query.

    THE SECOND ARM OF THE DESIGNER REGISTER, and the one a later reader will try to delete as a
    duplicate of :func:`designer_links`. It is not: ``named_designer_rows`` states in capitals that
    **the creator holds no viewer row**, their access being ``createdById``, so a register built on
    viewer rows alone omits the lead designer of every workshop nobody has shared — which is most of
    them on the day they are opened.

    Read on the DISTINCT set of creator ids rather than through ``include={"createdBy": True}`` on
    the scan, because the scan is five hundred rows and the creators are a few dozen accounts: the
    include would carry the same User object back hundreds of times.
    """
    ids = sorted({str(getattr(record, "createdById", "") or "") for record in records} - {""})
    if not ids:
        return {}
    users = await db.user.find_many(where={"id": {"in": ids}})
    return {str(user.id): user for user in users}


async def oversight_links(workshop_ids: list[str]) -> list[Any]:
    """Every oversight row on these workshops, with its account and its capacity. ONE query.

    The same table ``oversight_by_clause`` scopes this whole dashboard by, read in the other
    direction. ``DesignWorkshopOversight``'s primary key is ``[designWorkshopId, capacity]``, so this
    ``IN`` is a prefix read of that key.
    """
    if not workshop_ids:
        return []
    return await db.designworkshopoversight.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, include={"user": True}
    )


async def inspector_links(workshop_ids: list[str]) -> list[Any]:
    """Every inspector assignment on these workshops, with its account. ONE query.

    ── ⚠ THIS MODULE IS A CLASSIFIED READER OF THE INSPECTOR ASSIGNMENT TABLE ───────────────────

    ``tests/test_dw_inspector_scope_gate.py`` sweeps all of ``app/`` for four names and this read
    speaks one of them — the Prisma delegate on the line below. That sweep caught this route on the
    day it landed, which is the sweep working rather than the sweep being wrong, and its own failure
    message asks the right question: *is this a READ?*

    It is, and narrowly:

    * **It counts rows and serves a number.** How many workshops each inspector holds — nothing off
      an inspection, no note, no field value, no stage content.
    * **It decides nothing.** The workshop ids handed to it have ALREADY been scoped by
      ``ministry_dashboard._design_where``, which composes the caller's own scope under
      ``where["AND"]``. This function narrows nothing and widens nothing; hand it a list and it
      answers about that list.
    * **It consults no predicate.** The OTHER three names in that test's ``THE_NAMES`` tuple are the
      ones that decide who may REACH a workshop, and this module speaks none of them.
      ``test_a_classified_reader_speaks_the_table_and_none_of_the_predicates`` is the fence that
      keeps that true: the classification exempts this file from the delegate alone, not from the
      feature.

    ⚠ **AND THAT IS WHY THE THREE ARE NOT WRITTEN OUT ABOVE.** The sweep matches RAW TEXT, on
    purpose — its own comment says the drift it defends against is somebody reaching for an
    autocompleted symbol, and text is where that happens. A docstring listing the three forbidden
    names to explain that this module does not use them fails the check exactly as three call sites
    would. The first draft of this paragraph did precisely that.

    So the entitlement question this register answers is its own — ``require_ministry_dashboard_
    reader``, a SET with a deliberate hole at ADMIN — and the inspection scope is not consulted here
    at all. If a future edit needs one of those three, the answer is no: that would be this scope
    deciding something new, on a surface whose audience is every ministry and directorate account in
    the installation.
    """
    if not workshop_ids:
        return []
    return await db.designworkshopinspector.find_many(
        where={"designWorkshopId": {"in": workshop_ids}}, include={"user": True}
    )


def _counts_by_key(groups: Any, key: str) -> dict[str, int]:
    """A ``group_by(..., count=True)`` result as ``{id: n}``, through :func:`_group_count`.

    ⚠ ``_count`` IS A DICT AND NOT AN INT — that function carries the whole argument, including why
    ``next(iter(count.values()))`` is right only by accident. This wrapper exists so the people reads
    cannot each re-derive it slightly differently.
    """
    out: dict[str, int] = {}
    for row in groups or []:
        identifier = row.get(key) if isinstance(row, dict) else None
        if not identifier:
            continue
        out[str(identifier)] = _group_count(row)
    return out


async def inspection_feedback_counts(workshop_ids: list[str]) -> dict[str, dict[str, int]]:
    """Per inspector: suggestions filed on these workshops, and how many sent a workshop back.

    TWO AGGREGATES AND NOT A READ OF THE ROWS. ``DwInspectionFeedback.note`` is the sentence an
    officer wrote to a named designer about a named field; a register that COUNTS suggestions has no
    business loading them, and ``group_by`` answers the count without any note reaching this process.
    ``services/usage.py`` is the precedent for reaching for it when the answer is an aggregate.

    **TWO NUMBERS BECAUSE THEY ARE TWO FACTS.** ``sentBack`` is true only on the suggestion that
    actually moved a workshop to NEEDS_REVISION (that column's own note); the rest are suggestions
    added to an open round. An inspector who filed forty notes and sent nothing back, and one who
    filed forty and sent back thirty, are doing different jobs, and one "feedback" number cannot tell
    them apart.

    A COUNT ABSENT FROM THE RESULT IS ZERO AND THAT IS SAFE HERE, exactly as it is for
    :func:`beneficiary_counts` and exactly as it is NOT for progress: an inspector who has filed
    nothing yet is an ordinary state on the day they are assigned, and it is a fact this request
    measured. A FAILED read is a different thing and never reaches here — this function raises for
    the whole pair, and the route decides what to do about that in one place.
    """
    if not workshop_ids:
        return {"filed": {}, "sentBack": {}}
    where: dict[str, Any] = {"designWorkshopId": {"in": workshop_ids}}
    filed, sent_back = await gather_reads(
        db.dwinspectionfeedback.group_by(by=["actorId"], count=True, where=where),
        db.dwinspectionfeedback.group_by(
            by=["actorId"], count=True, where={**where, "sentBack": True}
        ),
    )
    return {
        "filed": _counts_by_key(filed, "actorId"),
        "sentBack": _counts_by_key(sent_back, "actorId"),
    }


# ── The two account directories, and the rule that decides whether they may be offered ──────────


def may_read_account_directories(user: Any) -> bool:
    """May this caller be shown accounts holding NO row on any workshop they can see?

    ⚠ **THIS IS NOT A NEW GATE AND IT DELIBERATELY WIDENS NOTHING.** ``officer_directory`` and
    ``eligible_inspectors`` are each served today behind ``require_workshop_assigner``, i.e.
    ``OVERSIGHT_ASSIGNER_ROLES`` = ``{MINISTRY_ADMIN, ADMIN, MASTER_ADMIN}`` — which is, member for
    member, the set :func:`sees_whole_estate` already answers True for. So an estate reader is handed
    a list they can already open at ``GET /design-workshop-oversight/officers`` and
    ``GET /design-workshop-inspections/eligible-inspectors``, through a door that is still a GET and
    still under this router's own gate.

    **AN ASSISTANT DIRECTOR AND A REGIONAL DIRECTOR ARE REFUSED IT, AND THE PAGE SAYS SO RATHER THAN
    PRETENDING.** ``OVERSIGHT_ASSIGNER_ROLES`` excludes a REGIONAL_DIRECTOR on purpose — *"the
    supervised must not choose the supervisor"* — and ``routes/sanction_orders`` records the standing
    ruling that the answer to a tier needing a list is **a fifth door with a narrower payload, never
    a widened gate**. Reading the national officer directory to a rank-42 account through this router
    would be that widening wearing a different URL.

    So the people registers answer those two tiers from the RELATION alone, and every payload carries
    ``includesUnpostedAccounts`` with a sentence saying which of the two questions was answered. A
    list that silently answers a narrower question than the one asked is this repository's most
    repeated bug class with the numbers left intact.
    """
    return sees_whole_estate(user)


async def officer_accounts() -> dict[str, Any]:
    """Every account holding a ministry post, through ``oversight.officer_directory``.

    THE EXISTING SERVICE AND NOT A SECOND QUERY. That function already folds in the platform
    allow-list as a CUT LIST, already carries its own ``truncated`` flag at
    ``ELIGIBLE_OFFICER_LIMIT``, and already attaches ``capacities`` per role. A second read here
    would be a second answer to "who is an officer", and only one of the two would move the day the
    allow-list changes shape.
    """
    return await officer_service.officer_directory()


async def inspector_accounts() -> dict[str, Any]:
    """Every account that may be assigned an inspection, through ``eligible_inspectors``.

    The sibling of :func:`officer_accounts`, for its reason and behind the same rule in
    :func:`may_read_account_directories`. ``INSPECTION_ROLES`` is ``{INSPECTOR}`` and that set is
    that module's to own rather than this one's to restate.
    """
    return await inspector_service.eligible_inspectors()


async def empanelled_designer_accounts() -> tuple[list[Any], bool]:
    """The empanelled designer roster, and whether the read was cut at its ceiling.

    ⚠ **THIS ONE IS OFFERED TO EVERY TIER OF THIS ROUTER AND THE OTHER TWO DIRECTORIES ARE NOT, AND
    THE ASYMMETRY IS MEASURED RATHER THAN CASUAL.** ``GET /sanction-orders/designers`` is
    ``require_sanction_recorder`` — ``sanction_orders.can_record_sanction_orders``, a RANK FLOOR at
    ASSISTANT_DIRECTOR (42) — and it answers this exact call, with these exact two arguments, shaped
    by ``assignable_designers_payload``. Every tier this router admits sits at or above that floor
    (42, 45, 48, 60), so folding the same row set in here widens nothing whatsoever: it is a row set
    all four callers can already read, on a door that is already open, and this is a GET behind this
    router's own gate. Compare :func:`may_read_account_directories`, where the corresponding door is
    ``require_workshop_assigner`` and two of the four tiers are deliberately refused.

    ``include_admins=False`` IS THE HALF THAT KEEPS IT TRUE. ``workshop_capable_accounts`` admits
    ADMIN and MASTER_ADMIN unconditionally — they are never roster-gated — so the default answer
    would be every empanelled designer **plus every privileged account in the installation**, which
    is the defect ``list_sanction_designers`` records having been fixed on 2026-09-16.

    **WHY THE ROSTER IS FOLDED IN AT ALL**, when the register is built from relations: a designer who
    is empanelled and has been given NOTHING is the single most actionable row on a ministry's
    chasing screen, and a register built only from workshop links cannot contain them — they would be
    indistinguishable from somebody who does not exist. Their counters are a MEASURED zero (no link
    row on any scanned workshop), which is a different fact from an unread one and is reported as
    ``progressReason: "noWorkshops"`` rather than as 0%.

    THE CEILING IS ``DIRECTORY_TAKE`` AND THE CALLER MUST SAY SO. That function folds every filter
    into the ``WHERE`` precisely so a length inference is sound — its own docstring, and the two
    clients that already infer truncation this way.
    """
    users = await designers.workshop_capable_accounts(
        search=None, include_suspended=False, include_admins=False
    )
    return list(users), len(users) >= designers.DIRECTORY_TAKE
