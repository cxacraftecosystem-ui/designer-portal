from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.core.db import db
from app.core.deps import (
    assert_can_contribute_relation,
    assert_can_delete,
    get_current_user,
    get_value,
    is_content_admin,
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
from app.services import craft_master
from app.services.access import effective_tier_for_record, guard_record_edit, record_revision
from app.services.artisan_merge import redirect_ids, redirect_payload
from app.services.completeness import (
    attach as attach_completeness,
    filter_or_422 as completeness_filter,
)
from app.services.concurrency import gather_reads
from app.services.pagination import normalize_pagination, page_payload
from app.services.record_design_workshop import (
    assert_may_write_a_record_filed_under,
    assert_payload_workshop,
)
from app.services.record_filters import (
    tool_artisan_clause,
    tool_craft_clause,
    tool_workshop_clause,
)
from app.services.record_names import fill_linked_names
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
from app.services.standard_values import (
    StandardValueError,
    apply_tool_values,
    retired_tradition_codes,
)
from app.services.workshop_access import (
    enforce_workshop_submission,
    pin_pending_if_late,
    stamp_workshop_submission,
)

router = APIRouter(prefix="/tools", tags=["tools"])

RELATIONS = (
    Relation("artisan", "artisan", "artisanId"),
    Relation("craft", "craft", "craftId"),
    Relation("workshop", "workshop", "workshopId"),
    Relation("location", "location", "locationId"),
    Relation("media", "mediafile", "toolId", many=True),
    Relation("createdBy", "user", "createdById"),
    Relation("artisanLinks", "toolartisan", "toolId", many=True, include={"artisan": True}),
    Relation("craftLinks", "toolcraft", "toolId", many=True, include={"craft": True}),
)
INCLUDE = include_of(RELATIONS)

_LINK_RELATIONS = tuple(rel for rel in RELATIONS if rel.field in ("artisanLinks", "craftLinks"))

_CLEARABLE_COLUMNS = (
    "materialFamily",
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


async def _assigned_artisans(tool_id: str, viewer: Any) -> list[dict[str, Any]]:
    links = await db.toolartisan.find_many(
        where={"toolId": tool_id},
        include={"artisan": True},
        order={"createdAt": "asc"},
    )
    return public_encode([link.artisan for link in links if link.artisan], viewer)



def _pop_link_ids(data: dict[str, Any], key: str) -> list[str] | None:
    if key not in data:
        return None
    raw = data.pop(key)
    if raw is None:
        return None
    return [value for value in dict.fromkeys(str(item).strip() for item in raw) if value]


def _craft_ids_from_a_legacy_body(
    data: dict[str, Any], craft_ids: list[str] | None, tool: Any
) -> list[str] | None:
    if craft_ids is not None:
        return craft_ids
    stated = str(data.get("craftId") or "").strip()
    if not stated or stated == (getattr(tool, "craftId", None) or ""):
        return craft_ids
    return [stated]


async def _resolve_craft_links(data: dict[str, Any], craft_ids: list[str] | None) -> set[str]:
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
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"These {len(craft_ids)} crafts make a craft name of {len(joined)} characters and "
                f"a tool's craft name can be at most {TOOL_CRAFT_NAME_MAX_LENGTH}. Link fewer "
                "crafts, and record the rest as a second tool."
            ),
        )
    data["craftId"] = craft_ids[0]
    if not joined:
        return set()
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
    if not artisan_ids:
        return set()
    may_assign_any = True
    have: set[str] = set()
    if tool is not None:
        may_assign_any = await _may_manage_tool_links(tool, tool_id or tool.id, current_user)
        if not may_assign_any:
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
                detail="Only the tool's owner, a professor above its author, a collaborator "
                "with edit access, or an admin can assign this tool to artisans created by "
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
    createdBy: str | None = None,
    completeness: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
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
            prose_contains("processUsedIn", search),
            {"material": contains(search)},
            prose_contains("remarks", search),
        ]
    if craftId:
        where.setdefault("AND", []).append(tool_craft_clause([craftId]))
    if artisanId:
        where.setdefault("AND", []).append(tool_artisan_clause([artisanId]))
    if designWorkshopId:
        where["designWorkshopId"] = designWorkshopId
    if workshopId:
        where.setdefault("AND", []).append(tool_workshop_clause([workshopId], False, vis))
    if place:
        where["place"] = contains(place)
    if maker:
        where["maker"] = maker
    if traditionType:
        same = [traditionType] + [
            old for old, new in retired_tradition_codes().items() if new == traditionType
        ]
        where["traditionType"] = same[0] if len(same) == 1 else {"in": same}
    if statusFilter:
        where["status"] = enum_filter_or_422(statusFilter, RECORD_STATUSES)
    if createdBy:
        where["createdById"] = createdBy
    complete_clause = completeness_filter("tool", completeness)
    if complete_clause:
        where.setdefault("AND", []).append(complete_clause)
    add_date_range(where, "createdAt", dateFrom, dateTo)
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
        await attach_completeness(
            "tool", _order_links(public_encode(items, current_user, media_urls=media_urls))
        ),
        total,
        page,
        page_size,
    )


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_tool(
    payload: ToolCreate,
    current_user: Any = Depends(require_record_creator),
) -> dict[str, Any]:
    replayed = await client_key_replay(
        db.tooldocumentation, payload.clientKey, user_id=current_user.id, include=INCLUDE
    )
    if replayed is not None:
        return await attach_completeness("tool", _order_links(public_encode(replayed, current_user)))
    data = decimal_to_string(clean_data(payload.model_dump()))
    await redirect_payload(data)
    _standardise(apply_tool_values, data)
    craft_ids = _pop_link_ids(data, "craftIds")
    artisan_ids = _pop_link_ids(data, "artisanIds")
    await _resolve_craft_links(data, craft_ids)
    await _resolve_artisan_links(data, artisan_ids, current_user, tool=None)
    check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=None)
    matched = await craft_master.resolve_tool_crafts(db, data, craft_ids)
    if matched != craft_ids:
        craft_ids = matched
        await _resolve_craft_links(data, craft_ids)
    data = await fill_linked_names(data, client=db)
    data = await attach_location(data)
    stamp_workshop_submission(data, check=check)
    data["createdById"] = current_user.id
    merge_field_provenance(data, current_user, previous=None)
    apply_status_policy_create(current_user, data)
    pin_pending_if_late(data, current_user, check=check)
    try:
        created = await db.tooldocumentation.create(data=data, include=INCLUDE)
    except Exception as exc:
        raced = await client_key_replay_after_violation(
            db.tooldocumentation, payload.clientKey, exc, user_id=current_user.id, include=INCLUDE
        )
        if raced is None:
            raise
        return await attach_completeness("tool", _order_links(public_encode(raced, current_user)))
    await _write_links(db, created.id, craft_ids, artisan_ids, fresh=True)
    if craft_ids or artisan_ids:
        await hydrate_relations([created], _LINK_RELATIONS)
    return await attach_completeness("tool", _order_links(public_encode(created, current_user)))


@router.get("/{tool_id}")
async def get_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    await hydrate_relations([tool], RELATIONS)
    return await attach_completeness(
        "tool",
        _order_links(
            public_encode(tool, current_user, media_urls=await media_url_owners(current_user))
        ),
    )


@router.patch("/{tool_id}")
async def update_tool(
    tool_id: str,
    payload: ToolUpdate,
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    tool = await require_record(db.tooldocumentation, tool_id)
    data = decimal_to_string(
        clean_data(payload.model_dump(exclude_unset=True), clearable=_CLEARABLE_COLUMNS)
    )
    await redirect_payload(data)
    _standardise(apply_tool_values, data, tool)
    expected_updated_at = take_expected_updated_at(data)
    craft_ids = _pop_link_ids(data, "craftIds")
    artisan_ids = _pop_link_ids(data, "artisanIds")
    craft_ids = _craft_ids_from_a_legacy_body(data, craft_ids, tool)
    craft_ids = await craft_master.resolve_tool_crafts(db, data, craft_ids, tool=tool)
    derived = await _resolve_craft_links(data, craft_ids)
    derived |= await _resolve_artisan_links(
        data, artisan_ids, current_user, tool=tool, tool_id=tool_id
    )
    check = None
    if "workshopId" in data and data.get("workshopId") != tool.workshopId:
        check = await enforce_workshop_submission(current_user, data.get("workshopId"))
    await assert_payload_workshop(data, current_user, filed_under=tool.designWorkshopId)
    data = await attach_location(data)
    async with db.tx() as tx:
        await lock_and_assert_expected_updated_at(tx, "tooldocumentation", tool, expected_updated_at)
        privileged = await guard_record_edit(
            tool, current_user, data, "tool", client=tx, derived=derived
        )
        await apply_status_policy_update(current_user, tool, data)
        stamp_workshop_submission(data, check=check, record=tool)
        pin_pending_if_late(data, current_user, check=check, record=tool)
        merge_field_provenance(data, current_user, previous=tool)
        resubmit_status(tool, current_user, data)
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
        await _write_links(tx, tool_id, craft_ids, artisan_ids)
        if link_after:
            await record_revision(
                {"id": tool_id, **link_before}, current_user, link_after, "tool", client=tx
            )
        updated = await tx.tooldocumentation.update(
            where={"id": tool_id}, data=data, include=INCLUDE
        )
    return await attach_completeness(
        "tool",
        _order_links(
            public_encode(updated, current_user, media_urls=await media_url_owners(current_user))
        ),
    )


@router.delete("/{tool_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tool(tool_id: str, current_user: Any = Depends(get_current_user)) -> None:
    assert_can_delete(current_user)
    tool = await require_record(db.tooldocumentation, tool_id)
    await assert_may_write_a_record_filed_under(tool.designWorkshopId, current_user)
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
    tool = await require_record(db.tooldocumentation, tool_id)
    await assert_may_write_a_record_filed_under(tool.designWorkshopId, current_user)
    may_assign_any = await _may_manage_tool_links(tool, tool_id, current_user)
    existing = await db.toolartisan.find_many(where={"toolId": tool_id})
    have = {link.artisanId for link in existing}
    asked = await redirect_ids(list(payload.artisanIds)) or []
    wanted = [aid for aid in dict.fromkeys(asked) if aid and aid not in have]
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
                detail="Only the tool's owner, a professor above its author, a collaborator "
                "with edit access, or an admin can assign this tool to artisans created by "
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
    tool = await require_record(db.tooldocumentation, tool_id)
    await assert_may_write_a_record_filed_under(tool.designWorkshopId, current_user)
    if not await _may_manage_tool_links(tool, tool_id, current_user):
        artisan = await db.artisan.find_unique(where={"id": artisan_id})
        if not artisan or getattr(artisan, "createdById", None) != current_user.id:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the tool's owner, the artisan's creator, a professor above the "
                "tool's author, a collaborator with edit access, or an admin can unassign "
                "artisans from this tool.",
            )
    await db.toolartisan.delete_many(where={"toolId": tool_id, "artisanId": artisan_id})


async def _may_manage_tool_links(tool: Any, tool_id: str, current_user: Any) -> bool:
    if is_content_admin(current_user) or getattr(tool, "createdById", None) == current_user.id:
        return True
    owner_id = getattr(tool, "createdById", None)
    if not owner_id:
        return False
    if await may_edit_lower_ranked_record(current_user, owner_id):
        return True
    tier = await effective_tier_for_record(current_user, owner_id, "tool", tool_id)
    return tier == "EDIT"


def _standardise(apply: Any, data: dict[str, Any], stored: Any = None) -> None:
    try:
        apply(data, stored)
    except StandardValueError as exc:
        raise HTTPException(status_code=422, detail=exc.message) from exc
