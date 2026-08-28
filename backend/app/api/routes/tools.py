from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user, is_admin, require_record_creator
from app.schemas.records import ToolArtisanAssign, ToolCreate, ToolUpdate
from app.services.access import effective_tier_for_record, guard_record_edit
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import assert_payload_workshop
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
    attach_location,
    clean_data,
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
)
INCLUDE = include_of(RELATIONS)

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
# The one key on ``ToolCreate`` / ``ToolUpdate`` that names no column on ``ToolDocumentation``. It is a per-dimension hint
# about HOW ``lengthInches`` / ``breadthInches`` / ``heightInches`` came to be known — typed off a
# tape, computed from marks on a photograph, or estimated by a vision model — and
# ``merge_field_provenance`` POPS it a few lines into each write path below and merges it into the
# ``{by, byName, at}`` stamp beside each dimension. ``services/measurement_provenance`` holds the
# argument; ``schemas/records.validate_measurement_methods`` holds what a client may send.
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
        public_encode(items, current_user, media_urls=media_urls),
        total,
        page,
        page_size,
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    data = decimal_to_string(clean_data(payload.model_dump()))
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
    created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    # No grant lookup on the create: a MediaFile points at its tool by ``toolId``, and this tool did
    # not exist until the statement above, so ``media`` is empty by construction and there is no URL
    # for a resolved set to decide about. The viewer is still named, for the identity mask.
    return public_encode(created, current_user)


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await hydrate_relations([tool], RELATIONS)
    # The uploader half alone, and for this detail read as much as for the list: the ``media`` this
    # hydrates comes through ``MediaFile.toolId``, so it cannot be a design-workshop attachment. See
    # "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above; ``media_workshops`` is left empty on purpose.
    return public_encode(tool, current_user, media_urls=await media_url_owners(current_user))


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
    await guard_record_edit(tool, current_user, data, "tool")
    await apply_status_policy_update(current_user, tool, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=tool)
    pin_pending_if_late(data, current_user, check=check, record=tool)
    merge_field_provenance(data, current_user, previous=tool)
    resubmit_status(tool, current_user, data)
    updated = await db.tooldocumentation.update(where={"id": tool_id}, data=data, include=INCLUDE)
    # The PATCH response carries ``media`` (it is in ``INCLUDE``) and the editor need not be the
    # uploader — an EDIT-tier grantee or a professor routinely saves a tool somebody else
    # photographed. Resolved rather than left to the cheap default so a photograph that was openable
    # before the save is still openable in the response that comes back from it; a URL that vanishes on
    # save reads as the save having destroyed the file.
    #
    # ``INCLUDE`` is derived from ``RELATIONS``, so the rows it returns are the same ``toolId``-keyed
    # rows the list returns and the same reasoning settles the second half: the uploader set alone,
    # ``media_workshops`` empty by decision. See "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above.
    return public_encode(updated, current_user, media_urls=await media_url_owners(current_user))


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.tooldocumentation, tool_id)
    await db.tooldocumentation.delete(where={"id": tool_id})


@router.get("/{tool_id}/artisans")
async def list_tool_artisans(tool_id: str, current_user: Any = Depends(get_current_user)) -> list[dict[str, Any]]:
    await require_record(db.tooldocumentation, tool_id)
    return await _assigned_artisans(tool_id, current_user)


@router.post("/{tool_id}/artisans")
async def assign_tool_artisans(
    tool_id: str,
    payload: ToolArtisanAssign,
    current_user: Any = Depends(get_current_user),
) -> list[dict[str, Any]]:
    """Assign the tool to the given artisans (idempotent: existing links are kept, new ones added).

    Permission: an admin, the tool's owner, or a collaborator holding an EDIT-tier grant on the
    tool may assign it to any artisan; anyone else may only assign it to artisans THEY created.
    Validation happens for the WHOLE batch before any link is written, so a rejected request never
    leaves partial state behind."""
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
                detail="Only the tool's owner, an EDIT-grant collaborator, or an admin can "
                "assign this tool to artisans created by someone else; you may only assign "
                "it to your own artisans.",
            )
    await db.toolartisan.create_many(
        data=[{"toolId": tool_id, "artisanId": aid} for aid in wanted]
    )
    return await _assigned_artisans(tool_id, current_user)


@router.delete("/{tool_id}/artisans/{artisan_id}", status_code=status.HTTP_204_NO_CONTENT)
async def unassign_tool_artisan(
    tool_id: str,
    artisan_id: str,
    current_user: Any = Depends(get_current_user),
) -> None:
    """Remove a tool-artisan link. Whoever could have created the link can remove it: the tool's
    owner, an EDIT-grant collaborator, an admin, or the artisan's own creator (so a mistaken
    self-service link is reversible by the person who made it)."""
    tool = await require_record(db.tooldocumentation, tool_id)
    if not await _may_manage_tool_links(tool, tool_id, current_user):
        artisan = await db.artisan.find_unique(where={"id": artisan_id})
        if not artisan or getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, the artisan's creator, an EDIT-grant collaborator, "
                "or an admin can unassign artisans from this tool.",
            )
    # One statement, and still a no-op when the link is already gone — reading the row back first
    # only bought us its id, at the price of another cross-region round trip.
    await db.toolartisan.delete_many(where={"toolId": tool_id, "artisanId": artisan_id})


async def _may_manage_tool_links(tool: Any, tool_id: str, current_user: Any) -> bool:
    """Admin, tool owner, or an EDIT-tier collaborator — the same people who may edit the tool's
    populated fields (guard_record_edit) may manage its artisan links."""
    if is_admin(current_user) or getattr(tool, "createdById", None) == current_user.id:
        return True
    owner_id = getattr(tool, "createdById", None)
    if not owner_id:
        return False
    tier = await effective_tier_for_record(current_user, owner_id, "tool", tool_id)
    return tier == "EDIT"
