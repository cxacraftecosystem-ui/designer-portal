from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    is_admin,
    is_empty_value,
    may_edit_lower_ranked_record,
    require_record_creator,
)
from app.schemas.records import (
    TOOL_CRAFT_NAME_MAX_LENGTH,
    ToolArtisanAssign,
    ToolCreate,
    ToolUpdate,
)
from app.services.access import effective_tier_for_record, guard_record_edit, record_revision
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import assert_payload_workshop
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    assert_expected_updated_at,
    attach_location,
    clean_data,
    client_key_replay,
    client_key_replay_after_violation,
    contains,
    count_and_page,
    decimal_to_string,
    enum_filter_or_422,
    hydrate_relations,
    include_of,
    media_url_owners,
    merge_field_provenance,
    prose_contains,
    public_encode,
    require_record,
    resubmit_status,
    take_expected_updated_at,
    viewable_where,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/tools", tags=["tools"])

# What a tool carries on the wire. Reads load these in one parallel wave (see services/records.py
# for why — this list is the longest in the app, and it is why /tools was the slowest endpoint);
# writes still pass the derived ``INCLUDE`` to Prisma, so the two can never describe different tools.
RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "toolId", many=True),
    Relation("createdBy", "user", "createdById"),
    Relation("artisanLinks", "toolartisan", "toolId", many=True, include={"artisan": True}),
    # EVERY CRAFT THE TOOL IS LINKED TO, the plural reading of ``craftId``. ``craft`` above is still
    # loaded and still resolves the FIRST one; this is all of them, and the two must both be here
    # because a client that read only the singular would silently show a three-craft tool as a
    # one-craft tool. ``include={"craft": True}`` is what makes the names available without a second
    # request — the picker draws ticks from the ids and labels from the names.
    Relation("craftLinks", "toolcraft", "toolId", many=True, include={"craft": True}),
)
INCLUDE = include_of(RELATIONS)

#: The two join-table relations alone, for RE-HYDRATING a row whose links were written after it.
#:
#: ``create_tool`` cannot write links inside its ``create``: the rows need the tool's id, which does
#: not exist until that statement returns. So the response would carry the two empty arrays the
#: ``include`` faithfully read a moment before the links landed — a 201 that says the tool has no
#: crafts, answering the very request that gave it three. Re-reading the WHOLE of ``RELATIONS`` to
#: fix two of them would put five more queries on the create; this pulls exactly the two that moved.
_LINK_RELATIONS = tuple(rel for rel in RELATIONS if rel.field in ("artisanLinks", "craftLinks"))

# TOOLDOCUMENTATION'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null``
# through for on this model, so emptying a box on the tool form actually empties the column instead
# of answering 200 and keeping the old value.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives: the global
# set cannot know which table a payload is bound for. Derived from ``model ToolDocumentation`` in
# prisma/schema.prisma, intersected with what ``ToolUpdate`` actually accepts. It OVERLAPS the
# product list without being it — a tool has ``height``/``width``/``thickness``/``weight``/``radius``
# beside the documented trio, and it has no ``size`` or ``costOfMaking`` — so the two must not be
# shared.
#
# THIS PARAGRAPH SAID "AND NO ``heightInches``" UNTIL 2026-08-27, AND THAT SENTENCE IS RECORDED
# RATHER THAN QUIETLY DELETED BECAUSE OF WHAT IT WAS USED TO ARGUE. The column landed that day
# (schema, an additive migration, this tuple, ``ToolCreate``/``ToolUpdate``, and both Android DTOs),
# so ``ToolDocumentation`` now carries the same ``lengthInches``/``breadthInches``/``heightInches``
# triple ``ProductDocumentation`` does. While it was absent it was cited across the repository as the
# reason a tool's height could be measured off a photograph, accepted by a designer, and then
# recorded as nothing — ``measurement_provenance.DIMENSION_FIELDS`` names all three columns, so a
# marker for a tool height had nowhere to land. It lands now. Re-check with::
#
#     grep -n "heightInches" backend/prisma/schema.prisma
#
# ``height`` and ``width`` beside it are still ORDINARY typed inputs and are still outside
# ``DIMENSION_FIELDS``: a method marker naming one of them is refused by
# ``schemas/records.validate_measurement_methods`` rather than dropped, so a client aiming a grid
# reading at the wrong column is told which three it may name.
#
# Only valid because ``update_tool`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``craftName``/``place``/``artisanName``/``toolkitName`` (NOT NULL), the enums
# ``maker``/``traditionType`` and ``status``/``recordedAt``/``recordedTimezone`` (NOT NULL with
# defaults), ``artisanId``/``craftId``/``workshopId``/``locationId`` (already global), and the
# measurement trio ``measurementImageId``/``measurementAnalysis``/``measurementAnalysisStatus``,
# which ``services/media_queue`` owns and ``records.PROVENANCE_SKIP_FIELDS`` already classes as
# system-managed. ``extraMetadata`` is left out because naming it would be inert:
# ``merge_field_provenance`` rebuilds and reassigns that column further down this route.
# ``measurementMethods`` is out for a stronger reason than either: it is not a column at all, so
# there is no null for it to write — see the note under this tuple.
#
# AND ``craftIds``/``artisanIds`` ARE OUT FOR THAT SAME STRONGER REASON, stated here because they are
# the newest keys on ``ToolUpdate`` and the tuple is the first place a reader looks for them. Neither
# names a column on ``ToolDocumentation``: they are popped by ``_pop_link_ids`` below and written as
# ``ToolCraft`` / ``ToolArtisan`` rows, so there is no NULL for this tuple to let through. Their
# "clear" spelling is ``[]``, not ``null`` — ``schemas/records.ToolUpdate`` REFUSES an explicit null
# on both, precisely because ``clean_data`` would drop it and absent and null would then mean the
# same thing to this route while meaning opposite things to the caller. The columns they DERIVE
# (``craftId``, ``craftName``, ``artisanId``) are ordinary NOT-NULL-or-global scalars and are
# deliberately absent from this tuple for the reasons already given two paragraphs up.
_CLEARABLE_COLUMNS = (
    "localName",
    "englishName",
    "processUsedIn",
    "material",
    "yearsInUse",
    "height",
    "width",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "thickness",
    "weight",
    "radius",
    "replacementCost",
    "suggestionsForToolImprovement",
    "remarks",
)

# ── ``measurementMethods`` RIDES THIS BODY AND IS NOT A COLUMN ───────────────────────────────────
#
# THIS PARAGRAPH BEGAN "THE ONE KEY ON ``ToolCreate``/``ToolUpdate`` THAT NAMES NO COLUMN ON
# ``ToolDocumentation``" UNTIL 2026-09-15, and the sentence is corrected rather than quietly dropped
# because it was load-bearing: it is what told a reader that a key surviving ``clean_data`` and
# reaching ``db.tooldocumentation.create`` would be an unknown-column error, and therefore that every
# such key must be POPPED by somebody. There are now THREE of them — this one and the
# ``craftIds``/``artisanIds`` pair the multi-select sends — and the obligation is unchanged for all
# three. The two POPPERS are what to re-check, because a key is safe only if one of them takes it
# out: ``grep -n "_pop_link_ids(\|merge_field_provenance(" backend/app/api/routes/tools.py`` — the
# first pops the two link lists, the second pops this marker. What follows is about
# ``measurementMethods`` alone.
#
# It is a per-dimension hint about HOW ``lengthInches`` / ``breadthInches`` / ``heightInches`` came
# to be known — typed off a tape, computed from marks on a photograph, or estimated by a vision
# model — and ``merge_field_provenance`` POPS it a few lines into each write path below and merges
# it into the ``{by, byName, at}`` stamp beside each dimension. ``services/measurement_provenance``
# holds the argument; ``schemas/records.validate_measurement_methods`` holds what a client may send.
#
# THE PRECONDITION THAT IS EASY TO BREAK FROM HERE, which is why this note is in the route and not
# only in the service. ``clean_data`` drops only ``None``, so the marker survives every step between
# the parse and that pop. Anything inserted in between that REBUILDS ``data`` from a column list
# instead of mutating it in place would drop the marker silently — the dimension would then store an
# explicit UNRECORDED and the designer who pressed Accept on a machine reading would never be told.
# And anything that moved the pop EARLIER, in front of ``guard_record_edit``, would hand
# ``merge_field_provenance`` a payload with nothing left to merge.
#
# It is in ``access.REVISION_SKIP_FIELDS`` and in ``records.PROVENANCE_SKIP_FIELDS``, so it is
# neither audited as an edit somebody made nor attributed as a field somebody filled in. True as of
# 2026-08-27; re-check with ``grep -n "MARKER_BODY_KEY" backend/app/services/access.py
# backend/app/services/records.py``.

# WHY EVERY ENCODE BELOW NAMES THE CALLER. ``public_encode(obj)`` with no viewer is not "the default";
# it is the CHEAPEST SAFE answer — mask every identity number and withhold every media URL — and it is
# the answer a route reaches by not thinking about the question. This module used to take it on all
# four of its record responses, and the cost was not theoretical: ``RELATIONS`` declares ``media``, so
# every tool came back with its photographs listed and their ``url``/``publicUrl``/``objectKey``
# popped off. ``tools/page.tsx`` builds its tile from ``media.url``, so the list proved a photograph
# existed and then rendered a placeholder with nothing to open — for a MASTER_ADMIN and for the
# designer who had uploaded it seconds earlier, because the viewer-less branch is taken before any
# rank test. Naming the caller also lifts the Aadhaar/Pehchan mask for the ranks entitled to it, which
# is the same policy artisans.py, media.py and search.py already apply on their own reads.

# WHY THIS MODULE STAYS ON THE UPLOADER HALF. The three encodes below that resolve a media set at all —
# the list, the detail and the PATCH — ask ``media_url_owners`` for the uploader set alone and leave
# ``public_encode``'s ``media_workshops`` at its empty default. That is a
# DECISION, recorded here once and pointed at from each call, not an omission — the banner in
# ``records.py`` exists because a transcript leak was achieved by exactly this shape of silence.
#
# ``records.media_url_scope`` answers "whose media bytes may travel" in two halves, because a
# design-workshop attachment is entitled to by TAG — ``linkedRecordType="designWorkshop"`` plus the
# workshop id (``dictation_consent.MEDIA_TAG``) — rather than by who uploaded it. The tag half exists
# for surfaces that can actually be handed such a file. This one cannot, and here is the argument.
#
# A TOOL'S MEDIA IS REACHED BY FOREIGN KEY, AND THAT FOREIGN KEY IS WRITTEN FROM THE TAG. ``RELATIONS``
# above pulls ``media`` through ``MediaFile.toolId``, and the only writer of that column in this
# repository is ``records.media_relation_data``, which DERIVES it from the link type: ``{"toolId": …}``
# for the tag ``tool``, and nothing at all for ``designWorkshop`` — that tag has no column on MediaFile,
# which is the whole reason the workshop half has to be a tag test in the first place. Its two
# callers (``POST /media/complete`` and ``POST /media/{id}/relink`` — true as of 2026-08-27; check
# ``grep -rn media_relation_data backend/app``) write the tag and the key from the SAME pair, and
# ``MediaCompleteRequest`` carries no ``toolId`` of its own for a client to send past them
# (``APIModel`` forbids extra keys). So a row in a tool's ``media`` list is tagged ``tool``; passing
# ``media_workshops`` here would ship a set that nothing on this payload could ever be tested against,
# at the price of a second round trip on the widest read in the module. Compare ``search.py``, which
# reads the ``MediaFile`` table itself and therefore does need it.
#
# THE NEAR-MISS THAT IS NOT ONE, AND THE FALSE VERSION OF IT THAT STOOD HERE UNTIL 2026-08-27.
# An earlier draft of this paragraph offered a worked example: a file first attached to a tool and
# later RECOVERED onto a design workshop, keeping its ``toolId`` and gaining the lower-cased tag
# ``designworkshop``, invisible to a workshop arm that compares camelCase. No route in this
# repository can write that row. The wrong version is recorded here rather than quietly deleted,
# because a comment that invents a hazard is worse than no comment at all: it is written as a
# concrete scenario, which is the form a future editor acts on, and while they are defending the
# fiction they are not looking at the real thing. The premise came from the banner over
# ``media.ORPHAN_TAG_TYPES``, which says the relink route lower-cases whatever it is given before
# storing it — true of the types that route ACCEPTS, and over-general for this one.
#
# EVERY WRITER OF ``MediaFile.linkedRecordType``, one at a time (true as of 2026-08-27; check
# ``grep -rn mediafile.create backend/app`` and the same for ``mediafile.update``):
#
#   * ``POST /media/{id}/relink`` refuses the tag outright. It lower-cases the requested type and
#     looks it up in ``media._relink_delegate``, which has no ``designworkshop`` entry — a design
#     workshop has no typed FK to re-point — so the route raises 400 "Unsupported record type for
#     re-linking" before it writes anything. A relink genuinely does NOT clear the previous foreign
#     key, which is what made the invented row sound plausible; it never reaches the write.
#   * ``POST /media/complete`` stores the tag VERBATIM (lower-cased only to look the parent delegate
#     up) and takes the foreign key from ``media_relation_data``, which has no entry for either
#     spelling. So a workshop-tagged row — camelCase from both clients, lower-case if some caller
#     ever sends one that way — carries no ``toolId``, and never enters a tool's ``media`` list to
#     be redacted in the first place.
#   * There is no PATCH or PUT on media at all; the remaining writes touch transcript columns only.
#
# The two spellings cannot part company HERE, then, because nothing here carries the tag in either of
# them. Where they could is ``records.py``, and both gates read it from one constant:
# ``_redact_sensitive`` and ``_design_workshop_media_branches`` compare against the same camelCase
# ``MEDIA_TAG``. Agreement is the property this change exists to restore, and the fix the day one of
# them starts folding case is to settle the spelling in ``records.py``, not to widen this route.
#
# The day a tool genuinely can carry a design-workshop-tagged file, move all three calls to
# ``media_url_scope`` and pass ``media_workshops`` at every one of them — not at whichever single one a
# bug report happened to name. The measurement-grid frames are the shape to check first: they are
# ordinary ``tool``-tagged uploads marked in ``extraMetadata``, and a design workshop only ever READS
# them through ``design_workshops._reference_photos``, which copies the picture into a report and
# creates no row here.


async def _assigned_artisans(tool_id: str, viewer: Any) -> list[dict[str, Any]]:
    """All artisans a tool is assigned to (the many-to-many links), oldest first.

    The viewer is named here for the identity mask alone: an Artisan carries ``aadhaarNumber`` and
    ``pehchanCardNumber`` and no media, so there is no URL decision to pay ``media_url_owners`` for.
    """
    links = await db.toolartisan.find_many(
        where={"toolId": tool_id},
        include={"artisan": True},
        order={"createdAt": "asc"},
    )
    return public_encode([link.artisan for link in links if link.artisan], viewer)


# ── THE PLURAL LINKS A TOOL BODY CARRIES, AND THE HELPERS THAT LAND THEM ─────────────────────────
#
# ``craftIds`` and ``artisanIds`` are ORDERED lists of ids on ``ToolCreate``/``ToolUpdate`` that name
# no column on ``ToolDocumentation``. They are taken out of ``data`` before it reaches Prisma and
# written as ``ToolCraft`` / ``ToolArtisan`` rows, and while they are being taken out they DERIVE the
# three scalars every existing reader depends on:
#
#     tool.craftId    := craftIds[0]                      (overrides any craftId in the same body)
#     tool.craftName  := ", ".join(the craft names, in craftIds order)   (likewise overrides)
#     tool.artisanId  := artisanIds[0]                    (overrides any artisanId in the same body)
#
# ``artisanName`` and ``place`` are NOT derived. Both are NOT NULL, both are already populated, and
# both are boxes a researcher legitimately corrects by hand — an artisan recorded under a married
# name, a hamlet the register spells differently. The CLIENT fills them from ``artisanIds[0]``'s
# record when the selection changes, exactly as the single-select already does, so a correction typed
# afterwards survives the save. ``craftName`` cannot have that courtesy and the cost is stated where
# it is paid, in ``_resolve_craft_links``.
#
# THE ABSENT / ``[]`` / ``null`` DISTINCTION IS THE WHOLE CONTRACT.
#
#     absent   — the caller said nothing about links; stored links are untouched.
#     ``[]``   — the caller says there are none; every link row for this tool is deleted.
#     ``null`` — refused at the door with a 422, by ``ToolCreate``/``ToolUpdate``'s own validator.
#
# ⚠ AND "ABSENT" HAS A FOURTH READING ON THE CRAFT HALF, BECAUSE THIS CONTRACT ONLY EVER REASONED
# ABOUT CLIENTS THAT KNOW THESE KEYS. A body from a client that does NOT — every build shipped before
# 0.0.12, and every outbox entry queued by one, which ``lib/offline.ts`` deliberately keeps
# byte-for-byte across deploys — states its whole craft as the scalar ``craftId``. Read as "said
# nothing about links" it left the migration-backfilled ``ToolCraft`` row naming the craft the
# designer had just REMOVED. ``_craft_ids_from_a_legacy_body`` is that arm; its docstring carries the
# argument and the reason the artisan half deliberately has no twin.
#
# The third arm exists because ``clean_data`` drops ``None`` for anything outside its clearable set
# and these are not columns, so a null would arrive here indistinguishable from an absent key — the
# one distinction a partial update cannot afford to lose.
#
# WHY NEITHER KEY IS REGISTERED IN ``access.REVISION_SKIP_FIELDS`` OR ``records.PROVENANCE_SKIP_FIELDS``
# (both of which ``measurementMethods`` IS in). A change of linked crafts or artisans IS an edit
# somebody made, and it belongs in the revision ledger and in the field-contributions panel. Neither
# registry could carry them usefully in any case — the pop happens before ``guard_record_edit`` and
# before ``merge_field_provenance``, so the keys are already gone by the time either one is
# consulted, and an entry would be inert while reading as a decision to hide them.
#
# THIS PARAGRAPH CLAIMED THE LEDGER GOT THE CHANGE "THROUGH THE DERIVED SCALARS", AND THAT WAS TRUE
# OF EXACTLY THE NON-EMPTY CASE. For ``craftIds: ["c1", "c2"]`` it still is: ``craftId``/
# ``craftName``/``artisanId`` move, ``record_revision`` diffs them off ``data``, and "this tool's
# craft changed from Bandhani to Bandhani, Block Printing" is an honest summary of what happened. For
# ``[]`` it was false in the way that matters. Nothing is derived on that branch — both resolvers
# return at their ``if not …`` line — the denormalised columns are deliberately left alone
# (``tests/test_tool_link_lists.test_clearing_the_links_does_not_touch_the_denormalised_columns``
# asserts it: they are NOT NULL and hold the last thing a person stated), and ``data`` can therefore
# be empty, so ``record_revision`` returned at its own ``if not changes`` line and the rows were
# hard-deleted with NOTHING naming who did it and nothing to put them back from. ``update_tool``
# now appends an entry carrying the before and after id lists whenever a set actually moves; the
# derived scalars still carry the summary beside it. Both halves, not one.


def _pop_link_ids(data: dict[str, Any], key: str) -> list[str] | None:
    """Take ``key`` out of *data* and return its ids, or ``None`` when the caller did not send it.

    Blank and whitespace-only entries are dropped and duplicates are collapsed KEEPING THE FIRST
    OCCURRENCE — ``dict.fromkeys``, the same idiom ``assign_tool_artisans`` below already uses —
    because order is the wire contract here: element 0 becomes the singular column and the craft
    names are joined in this order. A duplicate is never an error; a multi-select that sends the same
    id twice has said one thing twice, not two things.

    A list of nothing but blanks therefore collapses to ``[]``, which MEANS "no links" and clears
    them. That is the same answer as an explicitly empty list and is the only reading that does not
    require inventing a third state for a client that sent ``[""]``.
    """
    if key not in data:
        return None
    raw = data.pop(key)
    if raw is None:
        # Unreachable through the schemas — both refuse an explicit null — and kept as a total
        # function so a future internal caller cannot turn a null into "clear everything" by accident.
        return None
    return [value for value in dict.fromkeys(str(item).strip() for item in raw) if value]


def _craft_ids_from_a_legacy_body(
    data: dict[str, Any], craft_ids: list[str] | None, tool: Any
) -> list[str] | None:
    """A PRE-0.0.12 BODY'S ``craftId`` IS ITS WHOLE STATEMENT ABOUT THE TOOL'S CRAFT.

    ── THE FOURTH ARM OF THE CONTRACT ABOVE, AND THE ONE THE BANNER DID NOT HAVE ────────────────

    ``absent`` means "the caller said nothing about links" — true of a client that KNOWS about
    ``craftIds`` and chose not to mention them, and false of every client shipped before this
    release. At HEAD both of them sent ``craftId`` as the entire statement about a tool's craft
    (``ToolForm.tsx`` HEAD:669-670; ``ToolCreateRequest`` in the handset's ``ApiModels.kt`` had no
    ``craftIds`` at all), and the craft join has exactly one meaning: migration
    20260915100000 backfilled ONE ``ToolCraft`` row per tool from that same scalar.

    ⚠ OUTBOX BODIES OUTLIVE DEPLOYS, DELIBERATELY. ``frontend/lib/offline.ts`` stores ``body``
    "serialised at queue time so a later schema change cannot alter what the user actually saved",
    so entries queued on the old build replay against this API. Without this arm, a designer who
    corrected a toolkit's craft from Bandhani to Block Printing in a courtyard drained into
    ``craftId = Block Printing`` over ``ToolCraft = [Bandhani]`` — the craft they REMOVED, still
    linked, with nothing anywhere reconciling the two. The next person to open that tool on the new
    form got both crafts ticked, the mount reconciliation rewrote the required Craft name box to
    "Block Printing, Bandhani", and the next save made that the stored, denormalised name every
    export and exact-match craft lookup reads — with nothing on screen having said so.

    ONLY WHEN IT DIFFERS FROM THE STORED SCALAR, which is what keeps this from being a rewrite of
    every ordinary PATCH. A body restating the craft it already has says nothing that needs acting
    on, and re-deriving there would turn a remarks-only save into a 404 the day the craft is deleted
    from the register, plus a delete-and-recreate of a link row that was already right.

    NOT ON THE ARTISAN SIDE, and that asymmetry is the whole reason this is craft-only.
    ``ToolArtisan`` has ALWAYS legitimately held rows beyond ``artisanId`` — ``POST
    /tools/{id}/artisans`` predates this release — so a legacy body's ``artisanId`` was never a
    statement about the whole set and reading it as one would delete assignments nobody touched.

    A cleared ``craftId`` needs no arm either: it is not in ``_CLEARABLE_COLUMNS``, so ``clean_data``
    drops the null and the key never reaches here.
    """
    if craft_ids is not None:
        return craft_ids
    stated = str(data.get("craftId") or "").strip()
    if not stated or stated == (getattr(tool, "craftId", None) or ""):
        return craft_ids
    return [stated]


async def _resolve_craft_links(data: dict[str, Any], craft_ids: list[str] | None) -> set[str]:
    """Validate the craft ids and derive ``craftId``/``craftName`` from them. Mutates *data*.

    RETURNS THE KEYS IT WROTE, for ``update_tool`` to hand to ``guard_record_edit``'s ``derived=``.
    Only ``craftName`` is ever reported, and the asymmetry is the point: ``craftId`` is element 0 of
    the list the CALLER sent and means exactly what a ``craftId`` in the same body would have meant,
    so it keeps the ordinary contributor guard. ``craftName`` is a string no caller can state at all
    while crafts are linked — the paragraph below says why — so refusing a contributor over it is
    refusing them over the server's own work. See ``access.guard_record_edit``'s ``derived`` note.

    Every id is looked up in ONE query and the WHOLE batch is validated before anything is written,
    so a rejected request never leaves partial state behind — the rule ``assign_tool_artisans``
    states, and the reason the link rows are written by the caller, after this returns.

    AN UNKNOWN ID IS A 404 ON THE WHOLE REQUEST, not a silently dropped element. A picker that offers
    a craft the server will not link to is broken, and answering 201 while storing two of the three
    crafts a researcher ticked is the shape of failure nobody finds for a season.

    "NOT VISIBLE" AND "DOES NOT EXIST" ARE THE SAME ANSWER HERE, and the reason is worth writing
    down rather than leaving to be inferred. ``records.viewable_where`` is EMPTY for every signed-in
    account, so there is nothing to compose — and a by-id LOOKUP in this repository does not compose
    it anyway: ``records.require_record`` is a bare ``find_unique(where={"id": …})`` and
    ``assign_tool_artisans`` below is a bare ``find_many(where={"id": {"in": …}})``. This matches
    them deliberately, because three doors onto one rule that disagree is worse than three that are
    all equally due for an edit. The day a read rule does appear, all three move together.

    THERE IS NO 403 ARM, and that is a decision rather than an omission. ``Craft`` carries a nullable
    ``createdById`` and NOTHING in this repository gates linking to somebody else's craft — a craft is
    controlled vocabulary, not a person's record. Compare ``_resolve_artisan_links`` below, which does
    have one, because an Artisan is a subject.

    THE ACCEPTED COST, STATED ONCE SO NOBODY RE-LITIGATES IT: a hand correction typed into the Craft
    name box is lost while crafts are linked, because ``craftName`` has to be derivable by the server
    from ``craftIds`` alone — a queued body replayed a fortnight later must still produce a
    ``craftName`` that agrees with its links. The honest future fix is an explicit
    ``craftNameOverride`` flag, NOT a heuristic that compares against the previous value.

    THE MITIGATION SENTENCE THAT STOOD HERE WAS OVER-CLAIMED AND IS CORRECTED RATHER THAN DELETED,
    because a second defect was argued from it. It read: *"The clients mitigate it by writing the
    joined value into the box on every selection change, so the box never shows something other than
    what will be stored."* True of a SELECTION CHANGE; false of load-then-save, which is the common
    case — the box is seeded from the stored ``craftName`` and the server stores the derived one, so
    a record whose stored name has drifted from its crafts' current names (``crafts.update_craft``
    cascades a rename to no tool, and the box is independently editable) shows one string and saves
    another. The rewrite itself is the accepted cost above and stands. What did NOT follow from it,
    and was a real defect until 2026-09-16, is that ``assert_can_contribute_fields`` then saw a
    populated field CHANGE that the caller had not made and refused every non-privileged editor 403
    over it — locking them out of the whole record. ``update_tool`` now reports ``craftName`` to
    ``guard_record_edit`` as ``derived=``, which withholds it from that one guard and from nothing
    else; the ledger still records the change and the selection behind it is guarded by
    ``assert_can_contribute_relation`` against the stored link rows.

    THE CEILING THIS INHERITS IS NOW ENFORCED HERE, AND THE PARAGRAPH THAT DEFERRED IT IS RETIRED
    RATHER THAN DELETED, because what it deferred turned out to be the worst outcome in the release.
    It read: *"The joined string is assigned HERE, after the parse, so it is never measured against
    that bound and no write can fail on it … The fix when somebody does is to raise the bound on both
    schema fields TOGETHER WITH the clients' own input limits."* Both halves were wrong about the
    consequence. ``craftName`` is ``String`` on the model (Postgres TEXT, unbounded) while
    ``ToolCreate``/``ToolUpdate`` bound it at ``TOOL_CRAFT_NAME_MAX_LENGTH`` — a cap written for ONE
    craft name — so "no write can fail on it" was true of THIS write and of no later one: the row
    then held a ``craftName`` its own update schema refuses, and both record forms echo the stored
    value back on every save (``ToolForm`` seeds the box from ``initial.craftName``; the handset from
    ``editing?.craftName``). Every subsequent edit of that tool, from either client, is a 422 about a
    length, on a box the designer never typed in, for ever — and ``saveOrQueue`` will not queue a
    4xx, so offline the only remaining control is Discard, which destroys the record and the
    photographs staged against it. A selection is recoverable; a stored value that cannot be sent
    back is not.

    SO THE JOIN IS MEASURED BEFORE IT IS ASSIGNED, and an over-long one is a 422 that names
    ``craftIds`` — the thing the caller can actually change — rather than a length on a column they
    did not write. The bound is IMPORTED from the schemas rather than repeated, because the property
    that matters is that this function can never produce a value ``ToolUpdate`` will not take back,
    and two spellings of one number is how that stops being true.

    RAISING THE BOUND WAS THE OTHER AVAILABLE ANSWER AND IS NOT THE ONE TAKEN. The web client had
    already shipped the refusal at this same number, at the picker, where a designer is told which
    selection is too long while they can still change it (``forms/recordPickers.CRAFT_NAME_MAX_LENGTH``
    and ``craftSelectionVerdict``, whose sentence is "untick a craft, or link fewer of them, and
    record the rest as a second tool"). A server that accepted more than the client offers would put
    the two in disagreement about one column; and the worst case a raise would have to cover is every
    craft in the register at 180 characters each, which is not a bound, it is the absence of one.
    NEVER TRUNCATE, which is the one part of the old paragraph that stands: a truncated ``craftName``
    would disagree with the links it was derived from, and that agreement is the whole of what this
    function guarantees.

    WHAT IT DOES NOT REPAIR, stated so nobody reads more into it: a row that ALREADY holds an
    over-long ``craftName`` — written before this check existed, or by a client older than it — is
    still refused by ``ToolUpdate`` at the door, because the body echoes the stored value. Unticking
    a craft repairs it (both forms rewrite the box from the selection on every change, so the body
    then carries the shorter string), and that is the only route back.
    """
    if not craft_ids:
        return set()
    crafts = await db.craft.find_many(where={"id": {"in": craft_ids}})
    by_id = {craft.id: craft for craft in crafts}
    names: list[str] = []
    for craft_id in craft_ids:
        craft = by_id.get(craft_id)
        if craft is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        names.append((getattr(craft, "name", None) or "").strip())
    joined = ", ".join(name for name in names if name)
    if len(joined) > TOOL_CRAFT_NAME_MAX_LENGTH:
        # ABOVE ``data["craftId"]``, so a refused request leaves the dict exactly as it arrived. This
        # helper mutates its argument and the caller writes that dict; a half-derived ``data`` after a
        # raise would be a live object nobody re-reads. Same batch-before-write rule as the 404 arm.
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"These {len(craft_ids)} crafts make a craft name of {len(joined)} characters and "
                f"the longest a tool record stores is {TOOL_CRAFT_NAME_MAX_LENGTH}. Link fewer "
                "crafts, and record the rest as a second tool."
            ),
        )
    data["craftId"] = craft_ids[0]
    if not joined:
        # Nothing was derived, so nothing is reported as derived — the empty set is load-bearing and
        # not a tidy default. ``craftName`` is untouched on this branch, which means whatever the
        # body carries for it is the CALLER's and has to face the contributor guard like any other
        # hand-typed column.
        return set()
    # Assigned AFTER ``clean_data`` has run, so the joined string is never re-title-cased: each
    # ``Craft.name`` is already canonical (``clean_data`` title-cased it on the craft's own write),
    # and running ``title_case`` over "Bandhani, Block Printing" is a second normalisation of an
    # already-normalised value. Guarded on non-empty because ``craftName`` is NOT NULL and
    # ``ToolCreate`` demands at least one character: an unnamed craft would otherwise turn a 201 into
    # a constraint violation with nothing on screen to explain it.
    data["craftName"] = joined
    return {"craftName"}


async def _resolve_artisan_links(
    data: dict[str, Any],
    artisan_ids: list[str] | None,
    current_user: Any,
    *,
    tool: Any | None,
    tool_id: str | None = None,
) -> set[str]:
    """Validate the artisan ids and derive ``artisanId`` from them. Mutates *data*.

    Same batch-before-write rule and the same 404 as :func:`_resolve_craft_links`, plus the
    permission arm that this module already applies to the assignment routes: an admin, the tool's
    owner, a professor outranking the tool's author or an EDIT-tier collaborator may link the tool to
    any artisan; anybody else may only link it to artisans THEY created. The message is the one
    ``assign_tool_artisans`` gives, verbatim, because it is the same refusal reached through a
    different door, and two wordings of one rule is how the two doors come to be described
    differently in a support thread.

    ── IT GATES THE IDS BEING **ADDED**, NOT THE WHOLE LIST, AND THAT IS THE WHOLE OF IT ───────────

    A record form's multi-select states its ENTIRE selection on every save — this module's own
    ``_pop_link_ids`` banner says so, and both clients do it — so a save that changes a measurement
    and touches no picker still arrives here carrying every artisan the tool is already linked to.
    Ownership-checking that list refused the save: a tool documented by researcher A and linked to
    A's artisan X could not be saved by ANYONE who was not A, an admin or an EDIT-grantee, because
    ``X.createdById != current_user.id`` and the ids were already in the record. That is every
    professor, every directorate tier, every inspector and every ordinary contributor, on every tool
    the assignment panel has ever been used on — and it refused them over a link they were not
    proposing to make. ``assign_tool_artisans`` has never had the defect: it narrows to
    ``aid not in have`` before it checks anything. This now does the same, and the 404 arm
    deliberately does NOT narrow — an id that names no visible artisan is refused whether it is new
    or stored, because the whole list is about to be rewritten as link rows.

    REMOVAL IS NOT GATED HERE, and that is not an omission: whether this caller may drop links at all
    is a question about the tool's populated relation rather than about any artisan's authorship, and
    ``update_tool`` asks it of ``assert_can_contribute_relation`` inside its transaction. Two gates,
    two questions; see the banner above ``_pop_link_ids``.

    RETURNS THE KEYS IT WROTE, for ``guard_record_edit``'s ``derived=``. It is always empty:
    ``artisanId`` is element 0 of the caller's own list and means exactly what an ``artisanId`` in
    the same body would have meant, so it keeps the ordinary contributor guard. The return type is
    the sibling's so that a future derivation here cannot be added without a caller noticing.

    ``tool=None`` MEANS "the caller is about to be this tool's owner" — the create path, where the
    row does not exist yet and ``createdById`` is ``current_user.id`` by construction. Owner implies
    may-assign-any, so the gate is open; it is spelled as an explicit branch rather than left to fall
    out of a ``getattr`` on ``None``, which would have opened it for the same reason but by accident.
    """
    if not artisan_ids:
        return set()
    may_assign_any = True
    have: set[str] = set()
    if tool is not None:
        may_assign_any = await _may_manage_tool_links(tool, tool_id or tool.id, current_user)
        if not may_assign_any:
            # Only on the branch that can refuse. A privileged caller pays no query for a set whose
            # answer cannot change theirs, which is the same shape as ``guard_record_edit`` deferring
            # its grant lookup behind the rank test.
            linked = await db.toolartisan.find_many(where={"toolId": tool_id or tool.id})
            have = {link.artisanId for link in linked}
    artisans = await db.artisan.find_many(where={"id": {"in": artisan_ids}})
    by_id = {artisan.id: artisan for artisan in artisans}
    for artisan_id in artisan_ids:
        artisan = by_id.get(artisan_id)
        if artisan is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        if (
            not may_assign_any
            and artisan_id not in have
            and getattr(artisan, "createdById", None) != current_user.id
        ):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, a professor above its author, an EDIT-grant "
                "collaborator, or an admin can assign this tool to artisans created by "
                "someone else; you may only assign it to your own artisans.",
            )
    data["artisanId"] = artisan_ids[0]
    return set()


async def _write_links(
    writer: Any,
    tool_id: str,
    craft_ids: list[str] | None,
    artisan_ids: list[str] | None,
    *,
    fresh: bool = False,
) -> None:
    """Replace this tool's link sets with the ones the caller sent. An absent list changes nothing.

    REPLACEMENT, NEVER A DIFF. ``ToolCraft`` and ``ToolArtisan`` carry no payload beyond the pair, so
    a recreated row is indistinguishable from a kept one except for ``createdAt``. A diff would buy a
    stable ``createdAt`` at the price of a second query and a second set of edge cases on every save.

    THIS PARAGRAPH ENDED "so nothing observable depends on which rows survived" UNTIL 2026-09-16, and
    the sentence is corrected rather than dropped because it was an argument about ORDER and half of
    it stopped being true the day :func:`_order_links` landed. The craft half is unchanged: that array
    is ordered off ``craftName``, which this route derives from the caller's own list, so a
    replacement cannot move it. The ARTISAN array is ordered ``createdAt asc, id asc`` — and one
    ``create_many`` gives every row the same transaction-start instant, so after any save through a
    record form the tiebreak is the whole of the order and the array comes back in ``id`` (cuid)
    order, not in the order the designer ticked. Genuinely distinct ``createdAt`` values written by
    ``POST /tools/{id}/artisans`` over weeks are collapsed by one save of the record form.

    THAT IS ACCEPTED, AND IT IS NOT THE HAZARD IT LOOKS LIKE, because nothing derives anything from
    the RETURNED order. ``tool.artisanId`` is element 0 of the list the CALLER sent, stored by
    ``_resolve_artisan_links`` before this runs; and both record forms seed their picker SCALAR
    FIRST — ``ToolForm.initialLinkIds`` and the handset's twin both build ``[artisanId, ...links]``
    and de-duplicate — so the head they send back is the stored column, whatever order the array
    arrived in. What the ordering has to be is TOTAL and STATED, so that two clients drawing chips
    from it agree and a vacuum cannot change the answer; it does not have to be tick order. An
    ordinal column on ``ToolArtisan`` is the fix on the day tick order has to survive a save, and
    that is a migration rather than a change here.

    ``writer`` IS THE CLIENT TO WRITE THROUGH, and on the PATCH it must be the transaction's.
    ``db.tx()`` hands back a DIFFERENT client, so a write through the module singleton is not inside
    the block however it reads — the same trap ``update_tool``'s ``client=tx`` note sets out for
    ``guard_record_edit``.

    THE SIBLING TO READ BEFORE CHANGING THIS is ``workshops.update_workshop``, which has replaced the
    workshop's two rosters this way since they existed: the same ``data.pop(…, None)`` above the
    transaction, the same "only when it is not ``None``" test, the same ``client=tx``, through
    ``replace_workshop_artisans`` / ``replace_workshop_crafts``. Those two are not reused here only
    because they name the ``WorkshopArtisan``/``WorkshopCraft`` delegates; the shape is theirs. It
    writes its links AFTER its row update and this one writes them BEFORE, and that is not a
    disagreement — that route hydrates its relations separately afterwards, while this one takes them
    off the ``include=INCLUDE`` the update itself carries.

    ``fresh=True`` MEANS "THIS ROW WAS CREATED ONE STATEMENT AGO", so it has no links and the two
    deletes are statements whose answer is already known. It is not a micro-optimisation on a rare
    path: both record forms state their FULL selection on every save, ``[]`` included — the web
    client's payload comment says so in as many words — so without this every create of a tool with
    no crafts and no artisans would pay two cross-region round trips to delete nothing. Same
    reasoning, same shape, as ``processes.create_process`` reading its step plan off an empty list
    "rather than off a query whose answer is known".
    """
    if craft_ids is not None:
        if not fresh:
            await writer.toolcraft.delete_many(where={"toolId": tool_id})
        if craft_ids:
            await writer.toolcraft.create_many(
                data=[{"toolId": tool_id, "craftId": craft_id} for craft_id in craft_ids]
            )
    if artisan_ids is not None:
        if not fresh:
            await writer.toolartisan.delete_many(where={"toolId": tool_id})
        if artisan_ids:
            await writer.toolartisan.create_many(
                data=[{"toolId": tool_id, "artisanId": artisan_id} for artisan_id in artisan_ids]
            )


def _order_links(encoded: Any) -> Any:
    """Impose a STATED order on both link arrays of an encoded tool, or page of tools, and return it.

    ``hydrate_relations`` issues one batched ``find_many`` per to-many relation with no ``order``, so
    without this the arrays come back in whatever order Postgres found the rows — stable enough on a
    quiet table to look deliberate, and free to change under a vacuum. Two clients drawing chips from
    that would disagree about which craft is "first", which is the one thing ``craftId`` says.

    ``craftLinks`` IS ORDERED BY ``craftName``. The join table has no ordinal column, and adding one
    would be a second source for a fact the string already carries; ``Craft.name`` is ``@unique`` in
    this schema, so the name-to-position map is injective and the order is total. A link whose craft
    name is not in the string — a craft renamed since the save, a row written by an older client —
    is appended in ``createdAt asc, id asc`` rather than dropped or randomly placed.

    A CRAFT NAME CONTAINING A COMMA DEFEATS THE SPLIT, and the answer is that same tail. Nothing
    forbids ``Craft.name`` holding one, so "Bandhani, Kutch" joined with a second craft produces a
    string this cannot re-split into the names it was built from; those links miss the map and fall
    into the ``createdAt``/``id`` tail, which is STATED and STABLE rather than arbitrary. It is an
    ordering imperfection and never a lost or duplicated link — the array still holds exactly the rows
    the table holds. The client that built the string has the identical ambiguity
    (``forms/recordPickers.joinCraftNames``), so the two agree about what neither can recover; an
    ordinal column on ``ToolCraft`` is the fix if a comma'd craft name ever reaches the register.

    ``artisanLinks`` IS ORDERED ``createdAt asc, id asc``, which is what ``_assigned_artisans`` above
    already produces and what ``GET /tools/{id}/artisans`` already promises ("oldest first"). It is
    NOT re-sorted by name: ``design_workshops._linked_artisan_names`` does that for the report and its
    docstring is the record of that decision, so doing it here as well would give the two surfaces two
    reasons to be in the same order and no way to change one of them.
    """
    rows = encoded if isinstance(encoded, list) else [encoded]
    for row in rows:
        if not isinstance(row, dict):
            continue
        craft_links = row.get("craftLinks")
        if isinstance(craft_links, list):
            names = [part.strip() for part in (row.get("craftName") or "").split(",")]
            at = {name: index for index, name in enumerate(names) if name}
            craft_links.sort(
                key=lambda link: (
                    at.get(((link.get("craft") or {}).get("name") or "").strip(), len(at)),
                    link.get("createdAt") or "",
                    link.get("id") or "",
                )
            )
        artisan_links = row.get("artisanLinks")
        if isinstance(artisan_links, list):
            artisan_links.sort(key=lambda link: (link.get("createdAt") or "", link.get("id") or ""))
    return encoded


@router.get("")
async def list_tools(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    workshopId: str | None = None,
    designWorkshopId: str | None = None,
    place: str | None = None,
    maker: str | None = None,
    traditionType: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    # WHOSE RECORDS. Reading is open to every signed-in account, so "the records I filed" is no
    # longer a side effect of the visibility filter and has to be asked for. Without this the
    # My Activity page had to fetch page 1 of the WHOLE repository and sift it client-side, which
    # silently under-reported the moment the repository outgrew one page.
    createdBy: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    # Visibility is AND-composed so the search OR (assigned below) can never overwrite it.
    vis = await viewable_where(current_user)
    if vis:
        where["AND"] = [vis]
    if search:
        where["OR"] = [
            {"toolkitName": contains(search)},
            {"localName": contains(search)},
            {"englishName": contains(search)},
            {"craftName": contains(search)},
            {"artisanName": contains(search)},
            {"place": contains(search)},
            # ``processUsedIn`` and ``remarks`` are the two narrative columns on a tool and can now
            # hold a formatted document; ``material`` is a short single-line value and stays on the
            # plain filter. ``prose_contains`` argues the difference.
            prose_contains("processUsedIn", search),
            {"material": contains(search)},
            prose_contains("remarks", search),
        ]
    if craftId:
        # STILL THE FIRST CRAFT ALONE, AND THAT IS THE ACCEPTED CONSEQUENCE OF KEEPING THE COLUMN.
        # A tool linked to three crafts holds the first in ``craftId`` and all three in
        # ``craftLinks``, so it does not appear under the other two here. Widening this to
        # ``{"OR": [{"craftId": craftId}, {"craftLinks": {"some": {"craftId": craftId}}}]}`` — the
        # exact shape ``artisans.list_artisans``'s ``workshopId`` arm already uses for its own
        # two-readings problem — is the obvious follow-up and is DELIBERATELY out of scope for the
        # change that added the join table: it changes what every existing craft-filtered list,
        # count and export returns, which is a decision with its own blast radius. Recorded here so
        # the next reader finds a decision rather than an oversight.
        where["craftId"] = craftId
    if artisanId:
        where["artisanId"] = artisanId
    if designWorkshopId:
        # The design & prototype workshop filter — a plain equality on the column. See
        # `api/routes/artisans.list_artisans` for why it is not an OR and why the reserved word
        # "none" is not accepted on a singular filter.
        where["designWorkshopId"] = designWorkshopId
    if workshopId:
        where["workshopId"] = workshopId
    if place:
        where["place"] = contains(place)
    if maker:
        where["maker"] = maker
    if traditionType:
        where["traditionType"] = traditionType
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    add_date_range(where, "createdAt", dateFrom, dateTo)
    # ONE grant lookup for the whole page — ``media_url_owners`` costs a single query, and only below
    # professor, which is exactly the rank whose colleagues' photographs would otherwise be listed and
    # withheld. The cheap ``viewer``-derived default would hand back only the caller's OWN uploads,
    # which on a shared workshop's tool list is most of a page of dead tiles.
    #
    # IT RIDES THE PAGE'S OWN WAVE RATHER THAN FOLLOWING IT. It depends only on the VIEWER, not on
    # which rows came back, so awaiting it after ``count_and_page`` returned added a whole
    # cross-region round trip to this route for every account below professor — invisible in the
    # measured table, which was taken as an admin, where the lookup short-circuits without querying.
    # Width: the count, the page and this make 3, and the relation hydration inside
    # ``count_and_page`` is a second wave of at most ``len(RELATIONS)``, so nothing here approaches
    # ``pool_width()`` (10).
    #
    # THE UPLOADER HALF ALONE, DELIBERATELY: no design-workshop-tagged row can reach a tool's ``media``
    # list, so ``media_workshops`` stays empty here by decision, not by nobody asking. The argument is
    # under "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above.
    (total, items), media_urls = await gather_reads(
        count_and_page(
            db.tooldocumentation,
            where=where,
            skip=skip,
            take=page_size,
            order={"createdAt": "desc"},
            relations=RELATIONS,
        ),
        media_url_owners(current_user),
    )
    return page_payload(
        _order_links(public_encode(items, current_user, media_urls=media_urls)),
        total,
        page,
        page_size,
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    # The idempotent replay, above every gate and every write in this route. The full argument for
    # the ordering — above the workshop gates so a lapsed grant cannot refuse a create that already
    # succeeded, above ``attach_location`` so a replay mints no orphan ``Location`` — is written out
    # once, on ``products.create_product``, and applies here unchanged.
    replayed = await client_key_replay(
        db.tooldocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        return _order_links(public_encode(replayed, current_user))
    data = decimal_to_string(clean_data(payload.model_dump()))
    # THE TWO LINK LISTS COME OUT HERE, before ``attach_location`` and long before Prisma sees
    # ``data``: neither names a column, so either one left in the dict is an unknown-argument error
    # on the create below. Resolving them here also means ``craftId``/``craftName``/``artisanId`` are
    # already DERIVED when ``merge_field_provenance`` and ``apply_status_policy_create`` read the
    # payload, so the provenance stamp attributes the craft the caller will actually be stored with.
    # See the banner above ``_pop_link_ids`` for the absent / ``[]`` / ``null`` contract.
    craft_ids = _pop_link_ids(data, "craftIds")
    artisan_ids = _pop_link_ids(data, "artisanIds")
    await _resolve_craft_links(data, craft_ids)
    # ``tool=None``: on a create the caller IS the owner, so the assign-any gate is open. Both
    # helpers validate the WHOLE batch before this route writes anything, so a 404 or a 403 here
    # leaves no row and no link behind.
    await _resolve_artisan_links(data, artisan_ids, current_user, tool=None)
    data = await attach_location(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    # THE DESIGN & PROTOTYPE WORKSHOP is a DIFFERENT SCOPE with different machinery, so it needs
    # its own gate beside the line above rather than instead of it: `workshopId` is
    # `WorkshopAssignment`, `designWorkshopId` is creator / admin / `DesignWorkshopViewer`.
    # `assert_payload_workshop` calls `load_workshop_or_404(for_edit=True)` — the same helper the
    # stage writes and the questionnaire attach use — because filing a record under a workshop
    # puts it inside that workshop's scoped lists and totals, which is a change to somebody
    # else's record. Ungated, any client could post a stranger's workshop id and file into it,
    # which is the hole `_require_attachable_workshop` was written to close one door over.
    await assert_payload_workshop(data, current_user)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:
        # The race the pre-read cannot settle — two passes of one queue in flight together, each
        # finding no row and each planning an INSERT. See ``products.create_product`` for the whole
        # argument, and ``artisans.create_artisan`` for the same belt-and-braces on the dedup key.
        raced = await client_key_replay_after_violation(
            db.tooldocumentation, payload.clientKey, exc, user_id=current_user.id, include=INCLUDE
        )
        if raced is None:
            raise
        return _order_links(public_encode(raced, current_user))
    # THE LINK ROWS, AFTER THE ROW THEY POINT AT, because they need its id — the same shape and the
    # same ordering as ``create_process``, which writes its ``ProcessStep`` rows after the create for
    # the same reason. There is no transaction here (this route has never had one, and the replay
    # above is what makes a retried create safe rather than a rollback), so a failure between the two
    # leaves a tool with no links: the researcher sees an empty picker on the record they just filed
    # and re-ticks, which is a repair they can make. The inverse — links with no tool — is
    # impossible, because the foreign key is on the link.
    await _write_links(db, created.id, craft_ids, artisan_ids, fresh=True)
    if craft_ids or artisan_ids:
        # The ``include`` on the create read both arrays a moment before those rows existed, so the
        # 201 would otherwise say this tool has no crafts while answering the request that gave it
        # three. Exactly the two relations that moved are re-read; see ``_LINK_RELATIONS``.
        await hydrate_relations([created], _LINK_RELATIONS)
    # No grant lookup on the create: a MediaFile points at its tool by ``toolId``, and this tool did
    # not exist until the statement above, so ``media`` is empty by construction and there is no URL
    # for a resolved set to decide about. The viewer is still named, for the identity mask.
    return _order_links(public_encode(created, current_user))


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await hydrate_relations([tool], RELATIONS)
    # The uploader half alone, and for this detail read as much as for the list: the ``media`` this
    # hydrates comes through ``MediaFile.toolId``, so it cannot be a design-workshop attachment. See
    # "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above; ``media_workshops`` is left empty on purpose.
    return _order_links(
        public_encode(tool, current_user, media_urls=await media_url_owners(current_user))
    )


@router.patch("/{tool_id}")
async def update_tool(
    tool_id: str,
    payload: ToolUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    # The precondition is a question, not a column — taken out of the body here, asked inside the
    # transaction below. See ``records.take_expected_updated_at``.
    expected_updated_at = take_expected_updated_at(data)
    # THE TWO LINK LISTS, popped and validated BEFORE the transaction opens. ``exclude_unset=True``
    # is what makes a present key mean "the caller sent this", so an absent list is genuinely
    # distinguishable from ``[]`` here and only here — a partial update that mentions neither leaves
    # both link sets exactly as they are. The validation is a pair of reads and belongs outside the
    # transaction; the WRITES are inside it, through ``tx``. See the banner above ``_pop_link_ids``.
    craft_ids = _pop_link_ids(data, "craftIds")
    artisan_ids = _pop_link_ids(data, "artisanIds")
    # AND THE BODY THAT PREDATES BOTH KEYS. A pre-0.0.12 client — including an outbox entry queued by
    # one, which survives the deploy by design — states its whole craft as the scalar. Read as
    # "mentioned no links" it leaves the backfilled ``ToolCraft`` row naming the craft the designer
    # removed, and the next save from either new client silently re-links it. See the function.
    craft_ids = _craft_ids_from_a_legacy_body(data, craft_ids, tool)
    # WHAT THE SERVER WROTE INTO ``data`` RATHER THAN THE CALLER — ``craftName``, and only when
    # crafts are linked. It is carried to ``guard_record_edit`` as ``derived=`` and is withheld there
    # from ``assert_can_contribute_fields`` and from nothing else, because refusing a contributor
    # over a string this route computed a moment ago refuses them over the server's own work. The
    # full argument, and the rule for what may ever join it, is on that function.
    derived = await _resolve_craft_links(data, craft_ids)
    derived |= await _resolve_artisan_links(
        data, artisan_ids, current_user, tool=tool, tool_id=tool_id
    )
    data = await attach_location(data)
    # Re-check workshop assignment + window if this edit moves the tool into/between workshops, so the
    # create-time guard can't be bypassed by PATCHing the workshop in afterwards.
    check = None
    if "workshopId" in data and data.get("workshopId") != tool.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    # Same gate on the PATCH, so the create-time check cannot be bypassed by filing the record
    # afterwards. Keyed on PRESENCE, so an edit that does not mention the workshop is not
    # re-validated — a record filed under a workshop the designer was later removed from must
    # still be editable by them.
    await assert_payload_workshop(data, current_user)
    # ONE TRANSACTION FOR THE AUDIT ROW AND THE ROW IT DESCRIBES (2026-09-03). ``guard_record_edit``
    # ends in ``record_revision``, which used to COMMIT on its own a handful of statements before the
    # update below — so a request that died in the gap (P2024 on a cross-region pool, a dropped
    # connection) left a ledger entry asserting a change to a tool that still holds the old values.
    # ``client=tx`` is load-bearing rather than decorative: ``db.tx()`` hands back a DIFFERENT client,
    # so a callee writing through the module singleton is not inside this block however it reads. The
    # argument in full is in ``access.record_revision``; the 403 ordering is untouched, because
    # ``guard_record_edit`` still refuses above its own write.
    async with db.tx() as tx:
        # Before ``guard_record_edit``, which is the first write in this block — a refusal raised
        # after it would leave a committed ledger entry for an edit that was then turned down. ``None``
        # passes and changes nothing, which is every client shipped to date. See
        # ``records.assert_expected_updated_at``.
        assert_expected_updated_at(tool, expected_updated_at)
        privileged = await guard_record_edit(
            tool, current_user, data, "tool", client=tx, derived=derived
        )
        await apply_status_policy_update(current_user, tool, data)
        # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's
        # edit) and pinned after the status policy, so an already-flagged record cannot be
        # self-approved.
        stamp_workshop_submission(data, check=check, record=tool)
        pin_pending_if_late(data, current_user, check=check, record=tool)
        merge_field_provenance(data, current_user, previous=tool)
        resubmit_status(tool, current_user, data)
        # ── WHO MAY REWRITE A POPULATED LINK SET, ASKED BEFORE THE SET IS REWRITTEN ──────────
        #
        # THE HOLE THIS CLOSES, BECAUSE IT WAS NOT A NARROW ONE. ``craftIds: []`` / ``artisanIds: []``
        # derive nothing — ``_resolve_craft_links`` and ``_resolve_artisan_links`` both return at
        # their ``if not …`` line — so ``data`` reached ``guard_record_edit`` EMPTY, and
        # ``assert_can_contribute_fields`` iterating an empty dict can lock no field and refuse
        # nobody. ``_write_links`` below is replacement-never-a-diff, so the two ``delete_many``
        # calls then ran unconditionally: any signed-in account could delete every ``ToolCraft`` and
        # ``ToolArtisan`` row on any tool in the repository and be answered 200. This PATCH is
        # ``get_current_user`` and not ``require_record_creator``, so "any signed-in account"
        # included the tiers that cannot create a tool at all. The craft half had a second door in
        # the same wall: a non-empty ``craftIds`` naming the tool's own stored ``craftId`` derives
        # scalars that MATCH, so ``values_match`` kept them out of ``locked_fields`` and every craft
        # link after the first was still deleted.
        #
        # IT IS A DIFF AND NOT A COUNT, WHICH IS WHERE THIS PARTS COMPANY WITH THE SIBLING.
        # ``workshops.update_workshop`` passes ``link_count > 0`` — "the roster has rows, so an
        # ordinary contributor may not send one" — and that rule cannot be copied here, because a
        # workshop's rosters are edited on a dedicated panel while a tool's links ride the RECORD
        # FORM, which states its entire selection on every save (both clients do; see the
        # ``_pop_link_ids`` banner). A count test would therefore refuse every non-privileged save of
        # every tool that has a craft link — which, after migration 20260915100000 backfilled one for
        # every tool naming a craft, is every tool — and "fill an empty field on someone else's
        # record" would be revoked repository-wide by a permission check. So the question asked is
        # ``assert_can_contribute_fields``'s own: did the CALLER change a populated thing. A restated
        # selection is not a change and is not refused; a changed one on a populated relation is.
        #
        # SETS, NOT SEQUENCES. The join tables carry no ordinal column, so membership is the whole of
        # what they hold; the ORDER of the selection is observable only through the derived
        # ``craftId``/``artisanId``, which are element 0 of the caller's own list and are guarded as
        # ordinary columns by the call above — a reorder that moves them is refused there.
        #
        # THROUGH ``tx``, so the reads see this transaction's own writes, and INSIDE it so that a
        # refusal takes the row update back with it — the ordering ``workshops.update_workshop``
        # settled and stated at length.
        #
        # THE DELEGATE IS NAMED AND NOT PASSED, and that is not a style choice. Written as
        # ``("craftIds", tx.toolcraft, …)`` the attribute is resolved when the TUPLE is built, which
        # is before ``if sent is None`` has had a chance to say that this request mentioned no link
        # list at all — so a PATCH of nothing but ``remarks`` reached into both join delegates on its
        # way past. Against the real client that is invisible; against anything standing in for one
        # it is an ``AttributeError``, and it turned every tool case in
        # ``tests/test_record_patch_clearing`` (a module about ``clearable``, which has no reason to
        # know this route has join tables) red at once. The rule the rest of this route already keeps
        # — ``_write_links`` tests ``is not None`` before it touches ``writer.toolcraft``,
        # ``_resolve_artisan_links`` pays for no read whose answer cannot change its verdict — is that
        # an edit which says nothing about the links touches nothing that holds them. This keeps it.
        #
        # ⚠ AND "POPULATED" FOR THE CRAFT HALF IS THE JOIN **OR** THE DENORMALISED NAME, WHICH IS
        # THE HOLE THE FIRST VERSION OF THIS LOOP LEFT OPEN (found 2026-09-16, before release).
        # ``guard_record_edit`` is handed ``craftName`` as ``derived=``, which withholds it from
        # ``assert_can_contribute_fields``, and the justification written on that function is this
        # very loop: "``update_tool`` reads the tool's stored link rows and refuses a non-privileged
        # caller who changes a populated set". That is only true while the join is populated whenever
        # the NAME is, and it is routinely not: ``ToolDocumentation.craftName`` is NOT NULL while
        # ``craftId`` is nullable, migration 20260915100000 backfills a ``ToolCraft`` row only
        # ``WHERE t."craftId" IS NOT NULL``, and ``ToolDocumentation.craft`` is ``onDelete: SetNull``
        # while ``ToolCraft.craft`` is ``onDelete: Cascade`` — so every tool whose craft was TYPED
        # rather than picked, and every tool whose craft was later deleted from the register, holds a
        # populated name over an empty join. Keyed on ``bool(stored)`` alone, a stranger sending
        # ``craftIds: [anything]`` at one of those tools passed both guards — the field guard never
        # saw ``craftName``, the relation guard saw no rows — and the server-derived name was written
        # over the owner's, answered 200.
        #
        # The craft's denormalised name is therefore part of the question "is this relation
        # populated". It restores exactly the rule that held before the join table existed, when the
        # web form sent ``craftName`` by hand and ``assert_can_contribute_fields`` refused a
        # non-privileged caller who changed it. A RESTATED selection is still not a change (the
        # ``stored == wanted`` continue above is reached first), so this does not revive the
        # count-test objection argued above; only a caller who genuinely MOVES the craft of somebody
        # else's tool is refused.
        #
        # THE ARTISAN HALF TAKES NO SUCH PARTNER, and that is a decision rather than an omission.
        # ``artisanName``/``place`` are NOT derived (see the ``_pop_link_ids`` banner) — they arrive
        # from the body and face ``assert_can_contribute_fields`` like any other typed column — so
        # there is no exemption there to compensate for. And ``ToolArtisan`` has always legitimately
        # held rows beyond ``artisanId``, so an empty join under a populated name says nothing about
        # that relation the way it does about the craft one.
        link_before: dict[str, Any] = {}
        link_after: dict[str, Any] = {}
        for key, delegate_name, column, denormalised, sent in (
            ("craftIds", "toolcraft", "craftId", "craftName", craft_ids),
            ("artisanIds", "toolartisan", "artisanId", None, artisan_ids),
        ):
            if sent is None:
                continue
            delegate = getattr(tx, delegate_name)
            stored = sorted(
                {
                    value
                    for value in (
                        getattr(row, column, None)
                        for row in await delegate.find_many(where={"toolId": tool_id})
                    )
                    if value
                }
            )
            wanted = sorted(set(sent))
            if stored == wanted:
                continue
            if not privileged:
                populated = bool(stored) or (
                    denormalised is not None
                    and not is_empty_value(get_value(tool, denormalised))
                )
                assert_can_contribute_relation(tool, current_user, populated, key)
            link_before[key] = stored
            link_after[key] = wanted
        # THROUGH ``tx``, AND BEFORE THE UPDATE. Through ``tx`` because ``db.tx()`` hands back a
        # DIFFERENT client and a write through the module singleton would sit outside this block,
        # leaving a tool whose links were replaced by an edit the transaction then rolled back.
        # Before the update because that statement carries ``include=INCLUDE``, so it reads both link
        # arrays back — issued after it, the response would describe the links this request replaced.
        await _write_links(tx, tool_id, craft_ids, artisan_ids)
        if link_after:
            # FEED THE AUDIT, AND THE EMPTY LIST IS THE WHOLE REASON IT HAS TO BE FED SEPARATELY.
            # ``REVISION_SKIP_FIELDS`` does not skip these two; ``record_revision`` was simply never
            # handed them, because the lists are popped above and never travel inside ``data``. The
            # banner over ``_pop_link_ids`` argues that the DERIVED scalars are the honest summary,
            # and for a non-empty list they are — ``craftId``/``craftName``/``artisanId`` move and
            # the ledger says so. For ``[]`` nothing is derived, ``data`` can be empty, and
            # ``record_revision`` returns at its ``if not changes`` line: the rows were hard-deleted
            # with no entry naming who did it and no way to reconstruct the set, in a table whose
            # whole purpose is that a change cannot be made invisibly. The ids are what an admin
            # needs to put the links back, so the ids are what is recorded.
            #
            # THE SHAPE IS ``processes.update_process``'s, deliberately: the BEFORE digest is passed
            # as the record (``record_revision`` reads fields through ``get_value``, which takes a
            # mapping) so that the one implementation of "diff, encode and append an immutable
            # revision" is reused rather than a second one written here. Sorted on both sides, so a
            # tick order that differs from the stored row order is not logged as a change — the
            # entries are only built for keys that genuinely moved.
            await record_revision(
                {"id": tool_id, **link_before}, current_user, link_after, "tool", client=tx
            )
        updated = await tx.tooldocumentation.update(
            where={"id": tool_id}, data=data, include=INCLUDE
        )
    # The PATCH response carries ``media`` (it is in ``INCLUDE``) and the editor need not be the
    # uploader — an EDIT-tier grantee or a professor routinely saves a tool somebody else
    # photographed. Resolved rather than left to the cheap default so a photograph that was openable
    # before the save is still openable in the response that comes back from it; a URL that vanishes on
    # save reads as the save having destroyed the file.
    #
    # ``INCLUDE`` is derived from ``RELATIONS``, so the rows it returns are the same ``toolId``-keyed
    # rows the list returns and the same reasoning settles the second half: the uploader set alone,
    # ``media_workshops`` empty by decision. See "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above.
    return _order_links(
        public_encode(updated, current_user, media_urls=await media_url_owners(current_user))
    )


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.tooldocumentation, tool_id)
    await db.tooldocumentation.delete(where={"id": tool_id})


@router.get("/{tool_id}/artisans")
async def list_tool_artisans(
    tool_id: str, current_user: Any = Depends(get_current_user)
) -> list[dict[str, Any]]:
    await require_record(db.tooldocumentation, tool_id)
    return await _assigned_artisans(tool_id, current_user)


@router.post("/{tool_id}/artisans")
async def assign_tool_artisans(
    tool_id: str,
    payload: ToolArtisanAssign,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Assign the tool to the given artisans (idempotent: existing links are kept, new ones added).

    Permission: whoever ``_may_manage_tool_links`` calls privileged — an admin, the tool's owner, a
    professor outranking its author, or a collaborator holding an EDIT-tier grant on the tool — may
    assign it to any artisan; anyone else may only assign it to artisans THEY created. Validation
    happens for the WHOLE batch before any link is written, so a rejected request never leaves
    partial state behind."""
    tool = await require_record(db.tooldocumentation, tool_id)
    may_assign_any = await _may_manage_tool_links(tool, tool_id, current_user)
    existing = await db.toolartisan.find_many(where={"toolId": tool_id})
    have = {link.artisanId for link in existing}
    # Every artisan being added is fetched in ONE query and every link written in ONE insert. Asking
    # per artisan cost two cross-region round trips each, so assigning a tool to a workshop's worth
    # of makers took longer than recording the tool did.
    wanted = [aid for aid in dict.fromkeys(payload.artisanIds) if aid and aid not in have]
    if not wanted:
        return await _assigned_artisans(tool_id, current_user)
    artisans = await db.artisan.find_many(where={"id": {"in": wanted}})
    by_id = {a.id: a for a in artisans}
    for artisan_id in wanted:
        artisan = by_id.get(artisan_id)
        if artisan is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
        if not may_assign_any and getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, a professor above its author, an EDIT-grant "
                "collaborator, or an admin can assign this tool to artisans created by "
                "someone else; you may only assign it to your own artisans.",
            )
    await db.toolartisan.create_many(data=[{"toolId": tool_id, "artisanId": aid} for aid in wanted])
    return await _assigned_artisans(tool_id, current_user)


@router.delete("/{tool_id}/artisans/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def unassign_tool_artisan(
    tool_id: str,
    artisan_id: str,
    current_user: Any = Depends(get_current_user),
) -> None:
    """Remove a tool-artisan link. Whoever could have created the link can remove it: the tool's
    owner, a professor above its author, an EDIT-grant collaborator, an admin, or the artisan's own
    creator (so a mistaken self-service link is reversible by the person who made it)."""
    tool = await require_record(db.tooldocumentation, tool_id)
    if not await _may_manage_tool_links(tool, tool_id, current_user):
        artisan = await db.artisan.find_unique(where={"id": artisan_id})
        if not artisan or getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, the artisan's creator, a professor above the "
                "tool's author, an EDIT-grant collaborator, or an admin can unassign "
                "artisans from this tool.",
            )
    # One statement, and still a no-op when the link is already gone — reading the row back first
    # only bought us its id, at the price of another cross-region round trip.
    await db.toolartisan.delete_many(where={"toolId": tool_id, "artisanId": artisan_id})


async def _may_manage_tool_links(tool: Any, tool_id: str, current_user: Any) -> bool:
    """Admin, tool owner, a professor outranking the tool's author, or an EDIT-tier collaborator —
    the same four people ``guard_record_edit`` calls privileged, and therefore the same people who
    may change the tool's populated fields.

    THE THIRD ARM LANDED 2026-09-16 AND THE DOCSTRING WAS ALREADY PROMISING IT. This function has
    claimed to be "the same people who may edit the tool's populated fields (guard_record_edit)"
    since it was written, and it was not: that function's ``privileged`` is admin OR owner OR
    ``may_edit_lower_ranked_record`` OR an EDIT grant, and this had three of the four. So a professor
    saving a tool filed by a researcher below them was privileged for every column on the row and
    unprivileged for its artisan links — passing ``workshops.update_workshop``, which performs the
    same whole-roster replacement behind ``guard_record_edit``'s own verdict, and refused here. The
    divergence is closed in the direction the docstring already stated rather than by rewriting the
    sentence to match the code, because the sentence is the rule and the code was the drift.

    IT WIDENS ``POST``/``DELETE /tools/{id}/artisans`` TOO, deliberately: those are the other two
    doors onto this same rule, and a professor who may rewrite the whole link set through a PATCH but
    not add one link through the assignment panel is the two-doors-one-rule problem this helper was
    extracted to prevent.

    The rank clause costs one query and only for a Professor+ who is neither the author nor an
    admin — ``may_edit_lower_ranked_record`` returns False without touching the database for
    everybody else — and it is asked BEFORE the grant lookup for that reason, which is the ordering
    ``guard_record_edit`` uses and the reason it gives.
    """
    if is_admin(current_user) or getattr(tool, "createdById", None) == current_user.id:
        return True
    owner_id = getattr(tool, "createdById", None)
    if not owner_id:
        return False
    if await may_edit_lower_ranked_record(current_user, owner_id):
        return True
    tier = await effective_tier_for_record(current_user, owner_id, "tool", tool_id)
    return tier == "EDIT"
