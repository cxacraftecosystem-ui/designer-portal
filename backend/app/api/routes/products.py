from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user, require_record_creator
from app.schemas.records import ProductCreate, ProductUpdate
from app.services.access import guard_record_edit
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
            {"rawMaterialsUsed": contains(search)},
            {"mainToolsUsed": contains(search)},
            {"remarks": contains(search)},
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
    total, items = await count_and_page(
        db.productdocumentation,
        where=where,
        skip=skip,
        take=page_size,
        order={"createdAt": "desc"},
        relations=RELATIONS,
    )
    # ONE grant lookup for the whole page — ``media_url_owners`` costs a single query, and only below
    # professor, which is exactly the rank whose colleagues' photographs would otherwise be listed and
    # withheld. The cheap ``viewer``-derived default would hand back only the caller's OWN uploads,
    # which on a shared workshop's product list is most of a page of dead tiles.
    return page_payload(
        public_encode(items, current_user, media_urls=await media_url_owners(current_user)),
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
    return public_encode(product, current_user, media_urls=await media_url_owners(current_user))


@router.patch("/{product_id}")
async def update_product(
    product_id: str,
    payload: ProductUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    data = decimal_to_string(clean_data(payload.model_dump(exclude_unset=True)))
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
    return public_encode(updated, current_user, media_urls=await media_url_owners(current_user))


@router.delete("/{product_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_product(product_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    await require_record(db.productdocumentation, product_id)
    await db.productdocumentation.delete(where={"id": product_id})
