from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query, status

from app.core.db import db
from app.core.deps import assert_can_delete, get_current_user, require_record_creator
from app.schemas.records import ProductCreate, ProductUpdate
from app.services import craft_master
from app.services.access import guard_record_edit
from app.services.artisan_merge import redirect_payload
from app.services.completeness import (
    attach as attach_completeness,
    filter_or_422 as completeness_filter,
)
from app.services.concurrency import gather_reads
from app.services.linked_names import fill_craft_name
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import (
    assert_may_write_a_record_filed_under,
    assert_payload_workshop,
)
from app.services.records import (
    RECORD_STATUSES,
    Relation,
    add_date_range,
    apply_status_policy_create,
    apply_status_policy_update,
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
    lock_and_assert_expected_updated_at,
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

router = APIRouter(prefix="/products", tags=["products"])

RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "productId", many=True),
    Relation("createdBy", "user", "createdById"),
)
INCLUDE = include_of(RELATIONS)

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


@router.get("")
async def list_products(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
    craftId: str | None = None,
    artisanId: str | None = None,
    artisanName: str | None = None,
    workshopId: str | None = None,
    designWorkshopId: str | None = None,
    place: str | None = None,
    marketDemand: str | None = None,
    productType: str | None = None,
    statusFilter: str | None = None,
    dateFrom: datetime | None = None,
    dateTo: datetime | None = None,
    createdBy: str | None = None,
    completeness: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    and_filters: list[dict[str, Any]] = []
    vis = await viewable_where(current_user)
    if vis:
        and_filters.append(vis)
    if search:
        and_filters.append(
            {
                "OR": [
                    {"productName": contains(search)},
                    {"localName": contains(search)},
                    {"craftName": contains(search)},
                    {"artisanName": contains(search)},
                    {"place": contains(search)},
                    prose_contains("rawMaterialsUsed", search),
                    prose_contains("mainToolsUsed", search),
                    prose_contains("remarks", search),
                ]
            }
        )
    if craftId:
        where["craftId"] = craftId
    if artisanId:
        if artisanName and artisanName.strip():
            and_filters.append(
                {
                    "OR": [
                        {"artisanId": artisanId},
                        {"artisanName": {"equals": artisanName.strip(), "mode": "insensitive"}},
                    ]
                }
            )
        else:
            where["artisanId"] = artisanId
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
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
    complete_clause = completeness_filter("product", completeness)
    if complete_clause:
        and_filters.append(complete_clause)
    if and_filters:
        where["AND"] = and_filters
    add_date_range(where, "createdAt", dateFrom, dateTo)
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
        await attach_completeness("product", public_encode(items, current_user, media_urls=media_urls)),
        total,
        page,
        page_size,
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_product(
    payload: ProductCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    replayed = await client_key_replay(
        db.productdocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        return await attach_completeness("product", public_encode(replayed, current_user))
    data = decimal_to_string(clean_data(payload.model_dump()))
    await redirect_payload(data)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=None)
    data = await craft_master.resolve_record_craft(db, data, keep_name=True)
    data = await fill_craft_name(data)
    data = await attach_location(data)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.productdocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:
        raced = await client_key_replay_after_violation(
            db.productdocumentation, payload.clientKey, exc, user_id=current_user.id, include=INCLUDE
        )
        if raced is None:
            raise
        return await attach_completeness("product", public_encode(raced, current_user))
    return await attach_completeness("product", public_encode(created, current_user))


@router.get("/{product_id}")
async def get_product(
    product_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    await hydrate_relations([product], RELATIONS)
    return await attach_completeness(
        "product",
        public_encode(product, current_user, media_urls=await media_url_owners(current_user)),
    )


@router.patch("/{product_id}")
async def update_product(
    product_id: str,
    payload: ProductUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    product = await require_record(db.productdocumentation, product_id)
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    await redirect_payload(data)
    expected_updated_at = take_expected_updated_at(data)
    check = None
    if "workshopId" in data and data.get("workshopId") != product.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=product.designWorkshopId)
    data = await craft_master.resolve_record_craft(db, data, stored=product, keep_name=True)
    data = await fill_craft_name(data, stored=product)
    data = await attach_location(data)
    async with db.tx() as tx:
        await lock_and_assert_expected_updated_at(tx, "productdocumentation", product, expected_updated_at)
        await guard_record_edit(product, current_user, data, "product", client=tx)
        await apply_status_policy_update(current_user, product, data)
        stamp_workshop_submission(data, check=check, record=product)
        pin_pending_if_late(data, current_user, check=check, record=product)
        merge_field_provenance(data, current_user, previous=product)
        resubmit_status(product, current_user, data)
        updated = await tx.productdocumentation.update(
            where={"id": product_id}, data=data, include=INCLUDE
        )
    return await attach_completeness(
        "product",
        public_encode(updated, current_user, media_urls=await media_url_owners(current_user)),
    )


@router.delete("/{product_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_product(product_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    product = await require_record(db.productdocumentation, product_id)
    await assert_may_write_a_record_filed_under(product.designWorkshopId, current_user)
    await db.productdocumentation.delete(where={"id": product_id})
