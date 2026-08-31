"""The repository's ONE filter language, as Prisma ``where`` clauses.

Every screen that narrows the corpus — the Search page, the View Data panel, Android's browse
screen, and now the map — asks the same eight questions: a free-text query, a craft, a place, an
artisan, a media type, which record types, and a date range. This module turns that vocabulary into
the per-bucket ``where`` clauses, once, so a second screen cannot quietly grow a second dialect of
it. A map that agreed with the search box about "Bagru, last 30 days" only most of the time would be
worse than no map: the two would disagree about how many records exist and there would be no way to
tell which was right.

WHY IT IS A SERVICE AND NOT A ROUTE HELPER. ``GET /search`` built these clauses inline, which was
fine while it was the only caller. It also meant the rules had no tests of their own — the only way
to exercise them was to stand up the route against a database. Lifting them here makes them
ordinary functions over dictionaries, which is what ``tests/test_record_filters.py`` now checks.

ROW VISIBILITY IS PART OF THE ANSWER, never an afterthought a caller might forget. Every clause
returned already has the read predicate from ``records.viewable_where`` AND-composed into it, and the
media bucket gets the ``uploadedById`` variant rather than ``createdById`` because that is the
column media is owned by. A caller cannot get an unfiltered clause out of this module.

That predicate is EMPTY today — reading the repository is open to every signed-in account, see the
banner comment above ``viewable_where`` in ``services/records`` — so composing it costs nothing and
changes no query plan. It is composed anyway, at the one place every search-shaped screen passes
through, because the day a read rule does appear it must appear on all of them at once.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.services.concurrency import gather_reads
from app.services.records import add_date_range, contains, viewable_where

# The five buckets that exist on EVERY search-shaped screen, in the order they are counted, read and
# returned. Kept here beside the clauses they describe.
#
# THIS COMMENT USED TO SAY "the five buckets ... everywhere in the app" AND TO PROMISE THAT
# ``api/routes/search.py`` re-exported its ``SEARCH_TYPES`` from this tuple. Both halves stopped
# being true on 2026-08-31, when ``GET /search`` grew a sixth bucket the map cannot have. The
# no-second-copy rule the old comment was defending is intact — ``SEARCH_TYPES`` below is DERIVED
# from this tuple rather than typed out again, so the five can still only be spelled once — but this
# is no longer the whole vocabulary of every screen, and a reader who took it for that would put a
# design workshop on the map. See :data:`DESIGN_WORKSHOP_TYPE`.
RECORD_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media")

#: The SIXTH bucket, and why it is not in ``RECORD_TYPES`` above.
#:
#: ``RECORD_TYPES`` is the map's vocabulary as well as the search box's, and a design workshop
#: cannot be a map bucket: ``GET /map/points`` groups by ``locationId``/``place``, and
#: ``DesignWorkshop`` has neither column — its geography is ``state``/``district``/``venue``, three
#: free-text strings promoted out of stage 1. Adding it to the shared tuple would put it into
#: ``counted`` at ``map_points.py:793`` and hand Prisma a ``group_by(["locationId", "place"])``
#: over a model with no such fields, which is a 500 rather than an empty bucket.
#:
#: So the map keeps five and search gets six, and the two lists still cannot drift, because the
#: search vocabulary is DERIVED from the map's rather than restated beside it.
DESIGN_WORKSHOP_TYPE = "designWorkshops"

#: The six buckets ``GET /search`` covers, in the order they are counted, read and returned.
SEARCH_TYPES: tuple[str, ...] = (*RECORD_TYPES, DESIGN_WORKSHOP_TYPE)

# Which buckets carry a free-text ``place`` column. Media does NOT: a photo has no place of its own,
# it inherits the record it belongs to. Naming that here stops a caller from quietly filtering media
# to nothing by passing a place, which is what a blanket loop over the five buckets would do.
PLACED_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools")

# The value a client sends in ``workshopIds`` to mean "records that are not linked to any workshop".
#
# A workshop filter without this is unusable as a scope control: tick every workshop and the records
# filed before workshops existed vanish, with nothing on screen to say they were excluded. It is a
# reserved word rather than an empty string because an empty string is what a blank form field sends,
# and "the user has not chosen anything" must not mean "show me only the orphans".
UNASSIGNED_WORKSHOP = "none"


def resolve_workshop_ids(raw: list[str] | None) -> tuple[list[str], bool] | None:
    """Parse ``workshopIds`` into (real ids, include-unassigned), or ``None`` for "every workshop".

    Accepts the two spellings clients build query strings with — repeated parameters
    (``?workshopIds=a&workshopIds=b``) and one comma-joined value (``?workshopIds=a,b``) — for the
    same reason ``resolve_types`` does: the web and Android assemble them differently, and a scope
    that quietly covered everything because it was spelled the other way would look exactly like the
    control not working.

    ``None`` (absent, empty, or all-blank) means DO NOT FILTER. That is deliberately distinct from an
    empty selection: a caller that means "no workshops at all" has nothing to ask for, whereas the
    default state of the control is "all workshops" and must not be spelled the same way as a mistake.
    """
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return None
    include_unassigned = any(value == UNASSIGNED_WORKSHOP for value in wanted)
    ids = list(dict.fromkeys(value for value in wanted if value != UNASSIGNED_WORKSHOP))
    return ids, include_unassigned


def workshop_clause(
    ids: list[str], include_unassigned: bool, *, is_workshop_table: bool = False
) -> dict[str, Any] | None:
    """A workshop predicate for one table, or ``None`` when the selection cannot narrow that table.

    THE WORKSHOP TABLE IS THE ODD ONE and getting it wrong empties the map. Every other table carries
    a ``workshopId`` FOREIGN KEY; a workshop row IS the workshop, so its predicate is on its own
    primary key. Filtering ``Workshop.workshopId`` would be filtering a column that does not exist,
    and asking Prisma for a column a model lacks is an error rather than an empty result — so this is
    a correctness fork, not a tidiness one. It is a keyword flag rather than a bucket-name comparison
    so that a caller outside the five map buckets (the questionnaire's interview scan, say) cannot
    take the wrong branch by passing a name this function has never heard of.

    ``include_unassigned`` widens the clause with ``workshopId: null``. For the workshop table itself
    it is meaningless — a workshop cannot be unassigned from itself — so an "unassigned only"
    selection correctly excludes every workshop row.
    """
    if is_workshop_table:
        return {"id": {"in": ids}} if ids else None
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
    if include_unassigned:
        branches.append({"workshopId": None})
    if not branches:
        return None
    # A single branch is written flat rather than as a one-armed OR: it is the same query to Postgres
    # and a far more readable one in a log.
    return branches[0] if len(branches) == 1 else {"OR": branches}


def artisan_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    """ "Which artisans belong to these workshops" — an ``Artisan`` predicate, always non-empty.

    AN ARTISAN BELONGS TO A WORKSHOP THREE WAYS and all three count, which is why this is a shared
    helper rather than an inline clause on whichever screen needed it first. Two screens disagreeing
    about who was at a workshop is two screens disagreeing about what the workshop's data IS.

      1. ``Artisan.workshopId`` — the column added when every record type gained a workshop.
      2. The ``WorkshopArtisan`` roster, which carried the link before that column existed. Records
         predating the column have only this, and the ``GET /artisans?workshopId=`` filter has always
         honoured both.
      3. Having SAT IN an interview taken at the workshop. Without this a workshop whose roster was
         never filled in shows an empty artisan list while its interviews sit right there — which is
         the difference between "nobody was documented" and "nobody typed the roster".

    Returns an IMPOSSIBLE predicate rather than ``None`` when the selection can match nothing, so a
    caller cannot mistake "matches no artisan" for "do not filter" — the failure mode that shows the
    whole repository under a scope that excludes all of it.
    """
    branches: list[dict[str, Any]] = []
    if ids:
        branches.extend(
            [
                {"workshopId": {"in": ids}},
                {"workshops": {"some": {"workshopId": {"in": ids}}}},
                {
                    "questionnaireInterviews": {
                        "some": {"interview": {"is": {"workshopId": {"in": ids}}}}
                    }
                },
            ]
        )
    if include_unassigned:
        # An artisan with no workshop column set. Deliberately NOT also "no roster row and no
        # interview": an artisan on a workshop's roster whose column was never backfilled is somebody
        # the workshop knows about, and calling them unassigned would double-count them.
        branches.append({"workshopId": None})
    return {"OR": branches} if branches else {"id": {"in": []}}


def craft_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    """ "Which crafts belong to these workshops" — a ``Craft`` predicate, always non-empty.

    A CRAFT REACHES A WORKSHOP TWO WAYS and both count, which is why this is a shared helper rather
    than the bare ``workshopId`` column test every other table gets:

      1. ``Craft.workshopId`` — the column added when every record type gained a workshop.
      2. The ``WorkshopCraft`` join, which carried the link before that column existed and is still
         what ``POST``/``PATCH /crafts`` writes alongside the column (``link_workshop_craft``), what
         a workshop's "Crafts covered" picker reads, and what the export tree builds its craft
         folders from.

    THE TWO GENUINELY DISAGREE ON THE LIVE REPOSITORY. Every craft on it was created before the
    column existed, so all of them had a NULL ``workshopId`` and a perfectly good join row: a
    column-only predicate returned NOTHING for a workshop whose crafts were sitting right there. That
    is the repository's most repeated bug — a scope that renders empty over a full corpus and looks
    exactly like having no data. ``GET /crafts?workshopId=`` has always read both (see the note above
    its ``where`` clause); this is that reading, lifted into the shared vocabulary so a second screen
    cannot answer the question differently.

    Returns an IMPOSSIBLE predicate rather than ``None`` when the selection can match nothing, for
    the same reason ``artisan_workshop_clause`` does: "matches no craft" must not be mistakable for
    "do not filter".
    """
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
        branches.append({"workshops": {"some": {"workshopId": {"in": ids}}}})
    if include_unassigned:
        # Unassigned means BOTH readings are empty. A craft with a join row but no column is linked
        # to that workshop by every query in the app, so calling it unassigned would double-count it
        # — the same rule artisan_workshop_clause applies to a roster-only artisan.
        branches.append({"AND": [{"workshopId": None}, {"workshops": {"none": {}}}]})
    return {"OR": branches} if branches else {"id": {"in": []}}


#: Which bucket names get a reading of "belongs to this workshop" that is WIDER than the bare
#: ``workshopId`` column, and which helper holds that reading. Keyed by the bucket names
#: ``build_record_wheres`` returns, plus the singular table names ``datasets`` narrows by, so one
#: table cannot be spelled two ways into two different answers.
_WIDE_WORKSHOP_CLAUSES = {
    "artisans": artisan_workshop_clause,
    "artisan": artisan_workshop_clause,
    "crafts": craft_workshop_clause,
    "craft": craft_workshop_clause,
}


def bucket_workshop_clause(bucket: str, ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    """ "Which rows of ``bucket`` belong to these workshops" — the ONE answer, always non-empty.

    THIS FUNCTION EXISTS BECAUSE THE ANSWER WAS ONCE WRITTEN TWICE AND THE TWO DISAGREED. The
    artisans bucket of ``build_record_wheres`` was narrowed by the generic ``workshop_clause`` —
    the bare ``Artisan.workshopId`` column — while ``GET /artisans``, the dataset export and the
    consolidated questionnaire index all narrowed it with ``artisan_workshop_clause``'s three
    readings. So an artisan linked ONLY by a ``WorkshopArtisan`` roster row (which is what the
    workshop form's "linked artisans" picker writes, without ever touching the column) or ONLY by
    having sat in an interview taken there was returned by Browse artisans and absent from Search
    and the map, under the SAME scope control. Two screens disagreeing about who was at a workshop
    is two screens disagreeing about what the workshop's data IS — the exact failure the docstrings
    in this module are written to prevent, occurring inside this module.

    The fix is not a second fork at the second call site; a second copy is how the divergence got
    here. Every caller that has a table in hand and a workshop selection asks THIS, and the
    per-table reading lives in exactly one place: ``_WIDE_WORKSHOP_CLAUSES`` above. A table that
    grows a second link tomorrow (crafts already have one, artisans have two) is widened once and
    every screen moves together.

    ``bucket`` accepts both spellings the repository already uses for a table — the plural bucket
    names ``build_record_wheres`` returns and the singular ``DatasetSpec.workshop_filter`` values
    ("artisan", "craft", "self", "column") — so ``api/routes/datasets.py`` can drop its own copy of
    this fork for ``bucket_workshop_clause(dataset.workshop_filter, ids, include_unassigned)``
    without either side having to be renamed first. Until it does, that fork is the THIRD place
    this question is answered, and it is only by inspection that it still agrees.

    ALWAYS RETURNS A PREDICATE, never ``None``. ``workshop_clause`` returns ``None`` for a
    selection that cannot narrow a table — "unassigned only" against the workshops table is the
    real case — and every caller then had to remember to substitute an impossible predicate.
    Forgetting leaves the bucket UNFILTERED, which shows the whole repository under a scope that
    excludes all of it. That substitution is made here so it cannot be forgotten.
    """
    wide = _WIDE_WORKSHOP_CLAUSES.get(bucket)
    if wide is not None:
        return wide(ids, include_unassigned)
    clause = workshop_clause(
        ids, include_unassigned, is_workshop_table=(bucket in ("workshops", "self"))
    )
    return clause if clause is not None else {"id": {"in": []}}


def resolve_types(
    raw: list[str] | None, *, allowed: tuple[str, ...] = RECORD_TYPES
) -> set[str]:
    """Which buckets this request covers. Absent, empty, or all-blank means all of ``allowed``.

    Accepts both spellings a client might reach for — repeated parameters
    (``?types=artisans&types=media``) and one comma-joined value (``?types=artisans,media``) —
    because the web and Android build query strings differently, and a filter that quietly covered
    everything because it was spelled the other way would look exactly like the filter not working.

    An unrecognised bucket name is a 422 rather than a silent omission. Dropping it would answer a
    request for "artisan" (singular, a plausible typo) with a perfectly well-formed empty result,
    and the client would report "no matches" for data that is sitting right there — a wrong answer
    dressed as a correct one.

    ``allowed`` IS A PARAMETER BECAUSE TWO SCREENS ASK THIS AND ONLY ONE OF THEM HAS SIX BUCKETS.
    The map's vocabulary is :data:`RECORD_TYPES` and the search box's is :data:`SEARCH_TYPES`; see
    :data:`DESIGN_WORKSHOP_TYPE` for why a design workshop cannot be a map bucket. Defaulting to the
    narrower one means every existing caller is untouched and the WIDER vocabulary has to be asked
    for by name — the safe direction, because a caller that accidentally got six buckets would hand
    ``group_by`` a model that has none of the columns it groups on.

    CASE IS FOLDED FOR THE COMPARISON AND THE VOCABULARY'S OWN SPELLING IS WHAT COMES BACK. This
    function used to lower-case the token and compare it to the members directly, which WAS
    canonicalisation while every member was lower-case — over ``RECORD_TYPES`` the two readings
    accept and reject exactly the same strings. ``designWorkshops`` is the first member that is not,
    so lowering would now reject the one spelling the API itself publishes. The rule is
    ``enum_filter_list_or_422``'s, restated for the same reason it is written down there: the fold
    decides only WHETHER a token matches; the member of ``allowed`` is what is returned, so
    everything downstream can compare bucket names with ``==``.
    """
    canonical = {member.lower(): member for member in allowed}
    if not raw:
        return set(allowed)
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return set(allowed)
    unknown = sorted({token for token in wanted if token.lower() not in canonical})
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown search type{'s' if len(unknown) > 1 else ''}: {', '.join(unknown)}. "
                f"Valid types are {', '.join(allowed)}."
            ),
        )
    return {canonical[token.lower()] for token in wanted}


def enum_filter_list_or_422(
    raw: list[str] | None, allowed: frozenset[str], *, field: str
) -> set[str] | None:
    """Multi-valued filter over an enum column. ``None`` means DO NOT FILTER.

    THE PLURAL SIBLING OF ``records.enum_filter_or_422``. That one checks a single value on its way
    into ``where[field] = value``; this one checks a list on its way into
    ``where[field] = {"in": sorted(...)}``. Reach for that one when the control is a single-select
    with a blank option, for this one when it is a multi-select. Both exist so that a filter value
    the enum does not have is a 422 the client can act on rather than a Prisma
    ``FieldNotFoundError`` — a bare 500 with a stack trace in the log, which the web then renders to
    the operator as "you are offline" (``lib/offline.ts``). It lives HERE, beside ``resolve_types``
    and ``resolve_workshop_ids`` rather than beside that sibling, because this module is where the
    repository's filter grammar is written down and this is that grammar: two spellings, three ways
    to say "everything", and a named vocabulary in the error.

    ``None`` (absent, empty, or all-blank) MEANS DO NOT FILTER, and it is deliberately not an empty
    set. A caller that means "no statuses at all" has nothing to ask for, while the default state of
    a multi-select is "everything" — and if "nothing ticked" and "everything ticked" were both
    spelled as a list of every member, the control would have two states that cannot be told apart
    on the wire, and no reader of a request log could tell a default from a deliberate choice.
    Empty means everything BY ABSENCE: the caller writes no key into the ``where`` at all.
    ``resolve_workshop_ids`` above says the same thing about ids, and a reserved token like its
    ``UNASSIGNED_WORKSHOP`` ("the rows that have none") is therefore a MEMBER of ``allowed`` — never
    an empty list, which would mean the opposite.

    BOTH SPELLINGS ARE ACCEPTED — repeated parameters (``?roles=ADMIN&roles=DESIGNER``) and one
    comma-joined value (``?roles=ADMIN,DESIGNER``) — for ``resolve_types``' reason: the web and
    Android build query strings differently, and a filter that quietly covered everything because it
    was spelled the other way would look exactly like the filter not working.

    AN UNRECOGNISED TOKEN IS A 422 NAMING THE VOCABULARY, never a silent omission. Dropping it would
    answer a request for "PENDNG" with a perfectly well-formed result over whichever OTHER tokens
    came with it — a wrong answer dressed as a correct one — and dropping the only token would widen
    the request to the whole table. The message carries ``field`` and the allowed values because
    that is the part a client can act on: "roles must be one of ADMIN, …" tells a developer their
    spelling is wrong, where a 500 tells them the server is broken. ``field`` has no default, unlike
    the sibling's ``"status"``: this helper serves ``status``, ``roles`` and ``institutions`` on one
    route, and a message naming the wrong box sends a developer to the wrong control.

    CASE IS FOLDED FOR THE COMPARISON AND THE VOCABULARY'S OWN SPELLING IS WHAT COMES BACK, which is
    ``resolve_types``' case handling restated for a vocabulary that is not uniformly lower-case.
    ``resolve_types`` writes ``part.strip().lower()``, and that IS canonicalisation there because
    every member of ``RECORD_TYPES`` is lower-case; over a lower-case vocabulary the two functions
    accept and reject exactly the same strings. But these vocabularies are MIXED WITHIN ONE SET —
    ``ACCESS_ROLE_FILTER_TOKENS`` is ``frozenset(ROLE_RANK) | {"default"}``, so ``ADMIN`` sits
    beside the reserved ``default`` — and there lowering would hand Prisma an ``admitRole`` of
    "admin", a value the enum does not have and precisely the 500 the sibling exists to prevent,
    while upper-casing would destroy the reserved token instead. So the fold decides only WHETHER a
    token matches; the member of ``allowed`` is what is returned.

    THE RETURN IS ALWAYS A SUBSET OF ``allowed``, and that is the guarantee the call sites ride:
    ``{"in": sorted(result)}`` can only ever name real enum members, and ``result - {"default"}``
    can only ever strip a token the vocabulary actually holds. It is established once here rather
    than re-checked at every caller.

    THE ONE WAY THE FOLD CAN LIE is a vocabulary holding two members that differ only by case, and
    that is a ``ValueError`` — a 500 — rather than a 422, because nothing the client sent is wrong.
    It is not hypothetical for the vocabulary above: a ``DEFAULT`` tier added to ``ROLE_RANK`` would
    collide with the reserved ``default``, and a frozenset has no order, so which of the two a
    request resolved to would depend on the hash seed. One filter option would silently become
    unreachable, or ``?roles=default`` would start filtering ``admitRole = "DEFAULT"`` instead of
    ``admitRole IS NULL`` — a picker row answering a different question than the one on its label.

    ``allowed`` MUST BE A VOCABULARY SOMEBODY WROTE, NEVER ONE ASSEMBLED FROM A TEXT COLUMN, and
    that is a hard rule rather than a preference. Both of the grammar's own devices — the comma
    separator and the case fold — are safe only over tokens chosen to be safe, and every enum in
    this repository is: ``RECORD_STATUSES``, ``MEDIA_TYPES``, ``ROLE_RANK`` and the reserved words
    beside them hold no commas and no two members that fold together. Hand this a set built by a
    ``SELECT DISTINCT`` over free text and all three of its failure modes become DATA conditions,
    none of which any client can spell its way out of and all of which take the route down for
    everybody until somebody edits the database:

    1. **A member with a comma in it is unreachable.** ``DesignerRoster.institution`` is an
       admin-typed ``String?`` (``prisma/schema.prisma:3954``), so "National Institute of Design,
       Ahmedabad" is an ordinary value, and ``GET /designers/roster/institutions`` — which is a
       ``SELECT DISTINCT`` over it — serves that straight into a picker. Ticking the row
       sends the string, the split above cuts it in two, and the request 422s naming two
       institutions that do not exist — a filter option the server itself offered and then refuses.
       The message is unreadable as well, because it comma-joins members that contain commas.
    2. **A reserved token collides with real data.** ``none`` is the reserved word for
       ``institution IS NULL``, on the ``UNASSIGNED_WORKSHOP`` precedent at ``:53`` above — the
       same string, and safe there only because a workshop id is a cuid. One admin who typed
       "None" into the box instead of leaving it blank puts ``"None"`` in the DISTINCT set, it
       folds onto the reserved ``none``, and the ``ValueError`` above 500s every
       institution-filtered request.
    3. **Two spellings of one name.** "NID Ahmedabad" and "NID ahmedabad" are two rows to a text
       column and one token to the fold — the same 500, from two people typing.

    The ``ValueError`` is still right: silently picking a winner would make one filter row mean
    something other than its label. What is wrong is reaching this function for that vocabulary at
    all. A free-text filter needs an accessor with no separator and no fold — the wire carries one
    whole value per repeated parameter and compares it byte for byte — or a served vocabulary of
    stable tokens rather than of the display strings themselves. Whichever is chosen, it is a
    decision about the route (§4.5) and not something to be patched in here, because loosening
    either device would loosen it for ``status`` and ``roles`` too, and those are the two the 422
    exists for.
    """
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return None

    # Rebuilt per call rather than cached on ``allowed``: these vocabularies hold eight to fifteen
    # tokens, so it is a few microseconds against a database round trip, and a cache would be one
    # more thing to reason about for the vocabularies that are assembled at request time.
    canonical: dict[str, str] = {}
    for member in allowed:
        folded = member.lower()
        collision = canonical.get(folded)
        if collision is not None:
            raise ValueError(
                f"{field} cannot be matched case-insensitively: {collision!r} and {member!r} "
                "differ only by case, so which one a request resolved to would depend on the "
                "iteration order of a frozenset. Rename one of them."
            )
        canonical[folded] = member

    # Checked over the WHOLE list before anything is returned, exactly as ``resolve_types`` does:
    # one bad token among five good ones must not come back as a well-formed four-token filter.
    # Reported in the client's own spelling, because that is the string they have to go and find,
    # and sorted so the message reads the same on every request rather than following set order.
    unknown = sorted({token for token in wanted if token.lower() not in canonical})
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown {field} value{'s' if len(unknown) > 1 else ''}: {', '.join(unknown)}. "
                f"{field} must be one of {', '.join(sorted(allowed))}."
            ),
        )
    # A set, so a token repeated across the two spellings ("?roles=ADMIN&roles=admin,ADMIN") narrows
    # once. A duplicate is what a hand-edited URL looks like, not a mistake worth refusing.
    return {canonical[token.lower()] for token in wanted}


#: The promoted columns a free-text query is matched against on a design workshop.
#:
#: NOT THE WHOLE ANSWER ANY MORE, AND THAT IS THE POINT OF :data:`DESIGN_WORKSHOP_STAGE_TEXT_COLUMN`
#: below. This tuple used to carry a long note saying that stage field VALUES were not searchable and
#: costing the two ways of fixing it — the note §6.1 of
#: ``docs/DECISION-design-workshop-data-in-view-data.md`` was written from. Option 2 landed on
#: 2026-08-31, so a free-text query now matches these columns **OR** a stage answer, and the argument
#: that used to live here lives in the migration
#: (``20260831120000_dw_stage_entry_search_text``) and on the column in ``schema.prisma``, which is
#: where a decision about the schema belongs.
#:
#: These stay the columns of the WORKSHOP ITSELF — its title, its code, its place, the promoted
#: values stage 1 copies up — and they are still worth having separately from the stage text: they
#: are indexed, they are what the result row prints as its subtitle, and a hit on one of them needs
#: no stage naming beside it.
DESIGN_WORKSHOP_TEXT_COLUMNS: tuple[str, ...] = (
    "title",
    "workshopCode",
    "scheme",
    "craftName",
    "clusterName",
    "state",
    "district",
    "venue",
    "designerName",
    "implementingAgency",
    "sponsor",
    "notes",
)

#: The columns that answer "where was this workshop", for the shared ``place`` filter. A design
#: workshop has no ``place`` column and no ``Location`` relation — its geography is these four free
#: strings, promoted out of stage 1 — which is also why it cannot be a map bucket.
DESIGN_WORKSHOP_PLACE_COLUMNS: tuple[str, ...] = ("state", "district", "venue", "clusterName")

#: The relation and column a free-text query reaches THROUGH to search inside the 22 stages.
#:
#: ``DesignWorkshop.entries`` is ``DwStageEntry[]``, and ``searchText`` on it is the rendered answers
#: of one row — ENUM labels resolved, rich text flattened, the designer's own custom questions
#: included, contact details and identity numbers deliberately absent. See
#: ``design_workshop_data.entry_search_text`` for what goes in and the migration for why the column
#: exists at all.
#:
#: ``deletedAt: None`` INSIDE THE ``some`` IS NOT OPTIONAL AND IS NOT THE OUTER ONE. The workshop's
#: own soft-delete flag is tested by the unconditional first clause of
#: :func:`design_workshop_where`; this is the ROW's, and a stage row is soft-deleted whenever a
#: designer removes a sketch or a cost line. Without it, a search would surface a live workshop
#: because of an answer its designer deleted — the row is still in the table, ``searchText`` and all,
#: and the whole product treats a soft-deleted stage row as gone.
#:
#: NO ``rich_text.search_needles`` HERE, and the omission is deliberate rather than an oversight.
#: That helper exists because a raw ``contains`` over stored rich-text JSON has to try the
#: JSON-ESCAPED spelling of a term containing a quote (``he said "no"`` is stored as ``he said
#: \\"no\\"``). This column holds ``rich_text.to_plain`` output — the marks and the JSON are already
#: gone — so the typed term matches as itself and a second needle would be a second ``ILIKE`` for
#: zero recall. That is the same trade that function's own docstring makes.
DESIGN_WORKSHOP_STAGE_RELATION = "entries"
DESIGN_WORKSHOP_STAGE_TEXT_COLUMN = "searchText"


def design_workshop_stage_text_clause(q: str) -> dict[str, Any]:
    """The ``some`` clause that matches a query against any live stage answer of a workshop.

    A FUNCTION RATHER THAN A LITERAL, because two call sites need the identical predicate and they
    are in different modules: this one builds the bucket's ``where``, and ``api/routes/search.py``
    re-uses it to find out WHICH STAGES matched so the result can name them. A hit that does not say
    which of twenty-two stages it came from is a hit a researcher cannot act on, and the two
    predicates drifting apart would mean naming a stage the bucket did not match on.
    """
    return {
        DESIGN_WORKSHOP_STAGE_RELATION: {
            "some": {"deletedAt": None, DESIGN_WORKSHOP_STAGE_TEXT_COLUMN: contains(q)}
        }
    }


def design_workshop_where(
    visibility: dict[str, Any],
    *,
    q: str | None = None,
    craft_id: str | None = None,
    place: str | None = None,
    artisan_id: str | None = None,
    date_from: datetime | None = None,
    date_to: datetime | None = None,
) -> dict[str, Any]:
    """The ``DesignWorkshop`` predicate for the shared filter vocabulary.

    ``deletedAt: null`` IS UNCONDITIONAL AND IS THE FIRST THING IN THE CLAUSE. Nothing in this
    product hard-deletes a design workshop — ``DELETE`` sets ``deletedAt``, and every read filters
    it out, so a workshop is invisible to its own creator once removed. A search box that returned
    one would be the single surface in the product that resurrects deleted work, and it would do it
    to the widest audience.

    ``craft_id`` AND ``artisan_id`` REACH THROUGH THE LINK TABLES, because a design workshop carries
    neither column. ``craftName`` is a promoted STRING and a craft id is a cuid, so testing the id
    against it would match nothing at all — a filter that silently empties a bucket. What a design
    workshop does have is ``artisansLinked``/``productsLinked``/``toolsLinked``, so "workshops
    involving this craft" is "workshops with a linked artisan, product or tool of that craft",
    which is the same reading ``artisan_workshop_clause`` takes for the legacy table: a link is a
    link whichever table records it.

    ``media_type`` IS NOT A PARAMETER, and the omission matches the other four record buckets rather
    than being an oversight. In ``build_record_wheres`` that filter writes ``media_where`` alone —
    it does not narrow artisans, workshops, products or tools — so a design workshop bucket that
    DID narrow on it would be the one bucket in six that answers a different question from its
    neighbours under the same control.

    THE DATE RANGE READS ``startDate`` AND FALLS BACK TO ``createdAt``, which is the rule the legacy
    workshop bucket already applies (there the fallback column is ``date``). A workshop's start date
    is the fact a researcher means by "workshops in July"; ``createdAt`` is when somebody typed it
    in, and it is the only answer available for a workshop whose stage 1 has not been filled in yet.
    """
    where: dict[str, Any] = {"AND": [{"deletedAt": None}]}
    if visibility:
        where["AND"].append(visibility)

    if q:
        # THE WORKSHOP'S OWN COLUMNS **OR** A STAGE ANSWER, which is what §6.1 of the decision record
        # said this route could not do until the column behind the last clause existed. The two are
        # one OR rather than two filters because they answer ONE question — "does this workshop have
        # anything to do with indigo" — and a researcher who had to tick a box to decide whether the
        # answer was allowed to come from the title or from stage 5 would be being asked about our
        # storage layout.
        where["OR"] = [
            *({column: contains(q)} for column in DESIGN_WORKSHOP_TEXT_COLUMNS),
            design_workshop_stage_text_clause(q),
        ]
    if place:
        where["AND"].append(
            {"OR": [{column: contains(place)} for column in DESIGN_WORKSHOP_PLACE_COLUMNS]}
        )
    if craft_id:
        where["AND"].append(
            {
                "OR": [
                    {"artisansLinked": {"some": {"craftId": craft_id}}},
                    {"productsLinked": {"some": {"craftId": craft_id}}},
                    {"toolsLinked": {"some": {"craftId": craft_id}}},
                ]
            }
        )
    if artisan_id:
        where["AND"].append({"artisansLinked": {"some": {"id": artisan_id}}})
    if date_from or date_to:
        date_range: dict[str, Any] = {}
        if date_from:
            date_range["gte"] = date_from
        if date_to:
            date_range["lte"] = date_to
        where["AND"].append(
            {"OR": [{"startDate": date_range}, {"startDate": None, "createdAt": date_range}]}
        )
    return where


async def build_record_wheres(
    user: Any,
    *,
    q: str | None = None,
    craft_id: str | None = None,
    place: str | None = None,
    artisan_id: str | None = None,
    media_type: str | None = None,
    date_from: datetime | None = None,
    date_to: datetime | None = None,
    workshop_ids: list[str] | None = None,
    include_design_workshops: bool = False,
) -> dict[str, dict[str, Any]]:
    """One Prisma ``where`` per bucket, row visibility already folded in.

    ``include_design_workshops`` ADDS A SIXTH KEY AND IS OFF BY DEFAULT, so the map — the other
    caller of this function — gets the identical five-key dictionary it has always got. It is an
    opt-in rather than a value everybody receives because ``map_points`` reaches into ``wheres`` by
    bucket name from lists derived from :data:`RECORD_TYPES`; a sixth key would be inert there today
    and a live 500 the day somebody iterates ``wheres.items()`` instead. See
    :data:`DESIGN_WORKSHOP_TYPE`.

    Row visibility is resolved ONCE per owner column rather than once per bucket, and the four record
    buckets all key off ``createdById``, so resolving it bucket by bucket restated the same predicate
    five times before the caller had asked for anything.

    IT COSTS NO ROUND TRIP AT ALL, FOR ANYBODY, and this paragraph used to say it read the grant
    table for anyone below professor. It does not: ``records.viewable_where`` returns ``{}``
    unconditionally — reading the repository is open to every signed-in account, and that function's
    own docstring says so — so the gather below is two awaits of a constant and the "one round trip
    instead of two" it used to promise was two round trips that were never issued. The gather stays
    because ``viewable_where`` is the hook where a future read policy lands, and on the day it starts
    querying this is already the right shape. The grant table IS read below professor, but by
    ``owned_or_granted_where`` on the DOWNLOAD paths, which is a different function and is not on
    this one.
    """
    record_visibility, media_visibility = await gather_reads(
        viewable_where(user, owner_field="createdById"),
        viewable_where(user, owner_field="uploadedById"),
    )

    # Row-visibility joins each where under AND, so the free-text ORs assigned below never overwrite
    # it — and neither does anything else that needs its own OR.
    artisan_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    workshop_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    product_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    tool_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    media_where: dict[str, Any] = {"AND": [media_visibility]} if media_visibility else {}

    # Each filter below writes its own key, so every active one ANDs with the rest: a query plus a
    # place plus a date range narrows to the rows satisfying all three, never their union.
    if q:
        artisan_where["OR"] = [
            {"name": contains(q)},
            {"localName": contains(q)},
            {"place": contains(q)},
        ]
        workshop_where["OR"] = [
            {"title": contains(q)},
            {"place": contains(q)},
            {"description": contains(q)},
        ]
        product_where["OR"] = [
            {"productName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        tool_where["OR"] = [
            {"toolkitName": contains(q)},
            {"englishName": contains(q)},
            {"craftName": contains(q)},
            {"artisanName": contains(q)},
            {"place": contains(q)},
            {"remarks": contains(q)},
        ]
        media_where["OR"] = [
            {"originalFilename": contains(q)},
            {"caption": contains(q)},
            {"mimeType": contains(q)},
        ]

    if craft_id:
        artisan_where["craftId"] = craft_id
        product_where["craftId"] = craft_id
        tool_where["craftId"] = craft_id
    if place:
        artisan_where["place"] = contains(place)
        workshop_where["place"] = contains(place)
        product_where["place"] = contains(place)
        tool_where["place"] = contains(place)
    if artisan_id:
        product_where["artisanId"] = artisan_id
        tool_where["artisanId"] = artisan_id
    if media_type:
        media_where["mediaType"] = media_type

    # Workshops filter on startDate (matching the /workshops list route); rows created before
    # startDate existed fall back to the legacy single `date`. Nested under AND so it composes with
    # the free-text OR built above.
    if date_from or date_to:
        date_range: dict[str, Any] = {}
        if date_from:
            date_range["gte"] = date_from
        if date_to:
            date_range["lte"] = date_to
        workshop_where.setdefault("AND", []).append(
            {"OR": [{"startDate": date_range}, {"startDate": None, "date": date_range}]}
        )
    # Artisans were the one bucket the date range never reached: passing dateFrom returned every
    # artisan ever recorded alongside four correctly-filtered buckets, which reads as the filter
    # being broken rather than as artisans being exempt. Same column as the three below.
    add_date_range(artisan_where, "createdAt", date_from, date_to)
    add_date_range(product_where, "createdAt", date_from, date_to)
    add_date_range(tool_where, "createdAt", date_from, date_to)
    add_date_range(media_where, "createdAt", date_from, date_to)

    wheres = {
        "artisans": artisan_where,
        "workshops": workshop_where,
        "products": product_where,
        "tools": tool_where,
        "media": media_where,
    }

    # The sixth bucket is built by its own function rather than assembled inline, because every one
    # of its readings differs from the five above — no ``place`` column, no ``craftId`` column, a
    # soft-delete flag none of the others has — and inlining those differences here is how a clause
    # ends up being copied to a second screen with one of them missing.
    if include_design_workshops:
        wheres[DESIGN_WORKSHOP_TYPE] = design_workshop_where(
            record_visibility,
            q=q,
            craft_id=craft_id,
            place=place,
            artisan_id=artisan_id,
            date_from=date_from,
            date_to=date_to,
        )

    # THE WORKSHOP SCOPE, applied last and to every bucket at once.
    #
    # A workshop is the unit the fieldwork actually happens in, so "the records from these workshops"
    # is the question every cross-workshop conclusion starts from — which is why it belongs in the
    # SHARED filter vocabulary rather than being bolted onto whichever screen needed it first. The map,
    # the search box, the completion matrix and the consolidated questionnaire all read it from here,
    # so they cannot disagree about what "this workshop" contains.
    #
    # PER BUCKET, THROUGH ``bucket_workshop_clause``, AND NOT THROUGH ``workshop_clause`` DIRECTLY.
    # This loop used to call the generic column clause for all five buckets, which made the claim
    # in the paragraph above false for artisans: Search and the map saw only artisans carrying
    # ``Artisan.workshopId``, while Browse artisans, the dataset export and the questionnaire index
    # saw those PLUS the workshop's roster PLUS anyone who sat in an interview taken there. One
    # scope control, two answers, and the wrong one under-counted silently — "this workshop
    # documented no artisans" over a roster sitting in the database. The dispatcher owns the
    # per-table reading now, so widening a table widens every screen at once.
    #
    # Nested under AND, like the row filter and the workshop-date clause above, so it composes with
    # the free-text OR instead of overwriting it — and so a bucket that already has an AND (workshops,
    # once a date range is in play) gains a clause rather than losing one. This matters more now than
    # it did: the artisans clause is itself an OR, so assigning it would destroy the free-text search.
    # THE DESIGN-WORKSHOP BUCKET TAKES THE ORDINARY COLUMN READING, AND THAT IS CORRECT RATHER THAN
    # A GAP. ``bucket_workshop_clause`` forks on whether the bucket IS the workshop table; a
    # ``DesignWorkshop`` is NOT a ``Workshop`` — it is a different table that carries a nullable
    # ``workshopId`` foreign key to one — so the generic column clause is exactly right, and the
    # reserved "none" correctly selects design workshops not filed under any legacy workshop.
    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        for bucket, where in wheres.items():
            # Never None — a selection that cannot narrow this bucket ("unassigned only" against the
            # workshops bucket is the real case) comes back as an impossible predicate rather than as
            # nothing, because leaving the bucket unfiltered would show every workshop in the
            # repository under a scope that excludes them all.
            where.setdefault("AND", []).append(
                bucket_workshop_clause(bucket, ids, include_unassigned)
            )

    return wheres
