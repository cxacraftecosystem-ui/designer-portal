from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user, require_record_creator
from app.schemas.records import ProductCreate, ProductUpdate
from app.services.access import guard_record_edit
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
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

router = APIRouter(prefix="/products", tags=["products"])

# What a product carries on the wire. Reads load these in one parallel wave (see
# services/records.py for why); writes still pass the derived ``INCLUDE`` to Prisma, so the two can
# never describe different products.
RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "productId", many=True),
    Relation("createdBy", "user", "createdById"),
)
INCLUDE = include_of(RELATIONS)

# PRODUCTDOCUMENTATION'S OWN NULLABLE SCALARS — the names ``clean_data`` must let an explicit ``null``
# through for on this model, so emptying a box on the product form actually empties the column
# instead of answering 200 and keeping the old value.
#
# PER-MODEL AND NOT GLOBAL, for the reason ``clean_data``'s ``clearable`` docstring gives: the global
# set cannot know which table a payload is bound for. Derived from ``model ProductDocumentation`` in
# prisma/schema.prisma, intersected with what ``ProductUpdate`` actually accepts — do not copy this
# tuple to Tool or Artisan, whose nullable columns are a different list.
#
# Only valid because ``update_product`` dumps with ``exclude_unset=True``; see the note at that call.
#
# DELIBERATELY ABSENT: ``craftName``/``place``/``artisanName``/``productName`` (NOT NULL), the two
# enums ``productType``/``marketDemand`` and ``status``/``recordedAt``/``recordedTimezone`` (NOT NULL
# with defaults), ``artisanId``/``craftId``/``workshopId``/``locationId`` (already global), and the
# measurement trio ``measurementImageId``/``measurementAnalysis``/``measurementAnalysisStatus``,
# which ``services/media_queue`` owns — ``records.PROVENANCE_SKIP_FIELDS`` already classes all three
# as system-managed, and no client form sends them. ``extraMetadata`` is left out because naming it
# would be inert: ``merge_field_provenance`` rebuilds and reassigns that column further down this
# route, so a null could never reach Prisma anyway. ``measurementMethods`` is out for a stronger
# reason than either: it is not a column at all, so there is no null for it to write — see the note
# under this tuple.
_CLEARABLE_COLUMNS = (
    "localName",
    "timeTakenToCompleteProduct",
    "size",
    "lengthInches",
    "breadthInches",
    "heightInches",
    "costOfMaking",
    "sellingPrice",
    "rawMaterialsUsed",
    "mainToolsUsed",
    "productFunctionUse",
    "remarks",
)

# ── ``measurementMethods`` RIDES THIS BODY AND IS NOT A COLUMN ───────────────────────────────────
#
# The one key on ``ProductCreate`` / ``ProductUpdate`` that names no column on ``ProductDocumentation``. It is a per-dimension hint
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
# four of its responses, and the cost was not theoretical: ``RELATIONS`` declares ``media`` two lines
# up, so every product came back with its photographs listed and their ``url``/``publicUrl``/
# ``objectKey`` popped off. ``products/page.tsx`` builds its tile from ``media.url``, so the list
# proved a photograph existed and then rendered a placeholder with nothing to open — for a
# MASTER_ADMIN and for the designer who had uploaded it seconds earlier, because the viewer-less
# branch is taken before any rank test. Naming the caller also lifts the Aadhaar/Pehchan mask for the
# ranks entitled to it, which is the same policy artisans.py, media.py and search.py already apply on
# their own reads.

# WHY THIS MODULE STAYS ON THE UPLOADER HALF. The three encodes below ask ``media_url_owners`` for the
# uploader set alone and leave ``public_encode``'s ``media_workshops`` at its empty default. That is a
# DECISION, recorded here once and pointed at from each call, not an omission — the banner in
# ``records.py`` exists because a transcript leak was achieved by exactly this shape of silence.
#
# ``records.media_url_scope`` answers "whose media bytes may travel" in two halves, because a
# design-workshop attachment is entitled to by TAG — ``linkedRecordType="designWorkshop"`` plus the
# workshop id (``dictation_consent.MEDIA_TAG``) — rather than by who uploaded it. The tag half exists
# for surfaces that can actually be handed such a file. This one cannot, and here is the argument.
#
# A PRODUCT'S MEDIA IS REACHED BY FOREIGN KEY, AND THAT FOREIGN KEY IS WRITTEN FROM THE TAG.
# ``RELATIONS`` above pulls ``media`` through ``MediaFile.productId``, and the only writer of that
# column in this repository is ``records.media_relation_data``, which DERIVES it from the link type:
# ``{"productId": …}`` for the tag ``product``, and nothing at all for ``designWorkshop`` — that tag
# has no column on MediaFile, which is the whole reason the workshop half has to be a tag test in the
# first place. Its two callers (``POST /media/complete`` and ``POST /media/{id}/relink`` — true as
# of 2026-08-27; check ``grep -rn media_relation_data backend/app``) write the tag and the key from
# the SAME pair, and ``MediaCompleteRequest`` carries no ``productId`` of its own for a client to
# send past them (``APIModel`` forbids extra keys). So a row in a product's ``media`` list is tagged
# ``product``; passing ``media_workshops`` here would ship a set that nothing on this payload could
# ever be tested against, at the price of a second round trip on the widest read in the module.
# Compare ``search.py``, which reads the ``MediaFile`` table itself and therefore does need it.
#
# THE NEAR-MISS THAT IS NOT ONE, AND THE FALSE VERSION OF IT THAT STOOD HERE UNTIL 2026-08-27.
# An earlier draft of this paragraph offered a worked example: a file first attached to a product and
# later RECOVERED onto a design workshop, keeping its ``productId`` and gaining the lower-cased tag
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
#     ever sends one that way — carries no ``productId``, and never enters a product's ``media``
#     list to be redacted in the first place.
#   * There is no PATCH or PUT on media at all; the remaining writes touch transcript columns only.
#
# The two spellings cannot part company HERE, then, because nothing here carries the tag in either of
# them. Where they could is ``records.py``, and both gates read it from one constant:
# ``_redact_sensitive`` and ``_design_workshop_media_branches`` compare against the same camelCase
# ``MEDIA_TAG``. Agreement is the property this change exists to restore, and the fix the day one of
# them starts folding case is to settle the spelling in ``records.py``, not to widen this route.
#
# The day a product genuinely can carry a design-workshop-tagged file, move all three calls to
# ``media_url_scope`` and pass ``media_workshops`` at every one of them — not at whichever single one
# a bug report happened to name.


@router.get("")
async def list_products(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    artisanName: str | None = None,
    workshopId: str | None = None,
    place: str | None = None,
    marketDemand: str | None = None,
    productType: str | None = None,
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
    # OR-bearing conditions are collected here and combined under a single top-level "AND" so that,
    # e.g., a free-text search OR and the artisan-name OR never overwrite one another. The row-visibility
    # filter joins the same AND, so it too is safe from being clobbered by any OR.
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        and_filters.append({"OR": [
            {"productName": contains(search)},
            {"localName": contains(search)},
            {"craftName": contains(search)},
            {"artisanName": contains(search)},
            {"place": contains(search)},
            # The three narrative columns take rich text from this release on; the identifier-ish
            # columns above them do not. ``prose_contains`` explains why the two need different
            # filters and what breaks if they are levelled back to one.
            prose_contains("rawMaterialsUsed", search),
            prose_contains("mainToolsUsed", search),
            prose_contains("remarks", search),
        ]})
    if craftId:
        where["craftId"] = craftId
    if artisanId:
        if artisanName and artisanName.strip():
            # Match products linked to this artisan by FK, PLUS *every* product that carries this
            # artisan's typed name (case-insensitive) regardless of its FK. This is deliberately
            # inclusive so the process form's product dropdown never hides a product that genuinely
            # belongs to the artisan — covering products saved with a typed name and no FK link, and
            # products FK-linked to a duplicate artisan record that shares the same name. The only
            # cost is that two genuinely distinct artisans with an identical name would share a list,
            # which is rare and far preferable to silently dropping a real product.
            and_filters.append({"OR": [
                {"artisanId": artisanId},
                {"artisanName": {"equals": artisanName.strip(), "mode": "insensitive"}},
            ]})
        else:
            where["artisanId"] = artisanId
    if workshopId:
        where["workshopId"] = workshopId
    if place:
        where["place"] = contains(place)
    if marketDemand:
        where["marketDemand"] = marketDemand
    if productType:
        where["productType"] = productType
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "createdAt", dateFrom, dateTo)
    # ONE grant lookup for the whole page — ``media_url_owners`` costs a single query, and only below
    # professor, which is exactly the rank whose colleagues' photographs would otherwise be listed and
    # withheld. The cheap ``viewer``-derived default would hand back only the caller's OWN uploads,
    # which on a shared workshop's product list is most of a page of dead tiles.
    #
    # IT RIDES THE PAGE'S OWN WAVE RATHER THAN FOLLOWING IT. It depends only on the VIEWER, not on
    # which rows came back, so awaiting it after ``count_and_page`` returned added a whole
    # cross-region round trip to this route for every account below professor — invisible in the
    # measured table, which was taken as an admin, where the lookup short-circuits without querying.
    # Width: the count, the page and this make 3, and the relation hydration inside
    # ``count_and_page`` is a second wave of at most ``len(RELATIONS)``, so nothing here approaches
    # ``pool_width()`` (10).
    #
    # THE UPLOADER HALF ALONE, DELIBERATELY: no design-workshop-tagged row can reach a product's
    # ``media`` list, so ``media_workshops`` stays empty here by decision, not by nobody asking. The
    # argument is under "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above.
    (total, items), media_urls = await gather_reads(
        count_and_page(
            db.productdocumentation,
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
async def create_product(
    payload: ProductCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    data = decimal_to_string(clean_data(payload.model_dump()))
    data = await attach_location(data)
    # Workshop entries: enforce assignment, then flag + pin a late submission for admin approval.
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    # After the status policy, so a late submission outranks the submitter's own approval rights.
    pin_pending_if_late(data, current_user, check=check)
    created = await db.productdocumentation.create(data=data, include=INCLUDE)
    # No grant lookup on the create: a MediaFile points at its product by ``productId``, and this
    # product did not exist until the statement above, so ``media`` is empty by construction and there
    # is no URL for a resolved set to decide about. The viewer is still named, for the identity mask.
    return public_encode(created, current_user)


@router.get("/{product_id}")
async def get_product(product_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    await hydrate_relations([product], RELATIONS)
    # The uploader half alone, and for this detail read as much as for the list: the ``media`` this
    # hydrates comes through ``MediaFile.productId``, so it cannot be a design-workshop attachment.
    # See "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above; ``media_workshops`` is left empty on
    # purpose.
    return public_encode(product, current_user, media_urls=await media_url_owners(current_user))


@router.patch("/{product_id}")
async def update_product(
    product_id: str,
    payload: ProductUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    # ``exclude_unset=True`` IS THE PRECONDITION OF ``clearable``, not a stylistic choice: it is what
    # makes a present key mean "the caller sent this". Drop it and every optional the client left
    # alone would arrive as ``None`` and be written as an explicit NULL over stored data.
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    data = await attach_location(data)
    # Moving a record into (or to a different) workshop is a workshop submission too — re-check
    # assignment + window, so the create-time guard can't be bypassed by PATCHing the workshop in later.
    check = None
    if "workshopId" in data and data.get("workshopId") != product.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await guard_record_edit(product, current_user, data, "product")
    await apply_status_policy_update(current_user, product, data)
    # Stamped after the edit guard (the stamp is the API's bookkeeping, never a contributor's edit)
    # and pinned after the status policy, so an already-flagged record cannot be self-approved.
    stamp_workshop_submission(data, check=check, record=product)
    pin_pending_if_late(data, current_user, check=check, record=product)
    merge_field_provenance(data, current_user, previous=product)
    resubmit_status(product, current_user, data)
    updated = await db.productdocumentation.update(where={"id": product_id}, data=data, include=INCLUDE)
    # The PATCH response carries ``media`` (it is in ``INCLUDE``) and the editor need not be the
    # uploader — an EDIT-tier grantee or a professor routinely saves a product somebody else
    # photographed. Resolved rather than left to the cheap default so a photograph that was openable
    # before the save is still openable in the response that comes back from it; a URL that vanishes on
    # save reads as the save having destroyed the file.
    #
    # ``INCLUDE`` is derived from ``RELATIONS``, so the rows it returns are the same ``productId``-keyed
    # rows the list returns and the same reasoning settles the second half: the uploader set alone,
    # ``media_workshops`` empty by decision. See "WHY THIS MODULE STAYS ON THE UPLOADER HALF" above.
    return public_encode(updated, current_user, media_urls=await media_url_owners(current_user))


@router.delete("/{product_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_product(product_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.productdocumentation, product_id)
    await db.productdocumentation.delete(where={"id": product_id})
