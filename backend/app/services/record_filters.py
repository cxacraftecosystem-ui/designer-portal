from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import HTTPException, status

from app.services.concurrency import gather_reads
from app.services.records import add_date_range, contains, viewable_where

RECORD_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools", "media")

DESIGN_WORKSHOP_TYPE = "designWorkshops"

SEARCH_TYPES: tuple[str, ...] = (*RECORD_TYPES, DESIGN_WORKSHOP_TYPE)

PLACED_TYPES: tuple[str, ...] = ("artisans", "workshops", "products", "tools")

UNASSIGNED_WORKSHOP = "none"


def resolve_workshop_ids(raw: list[str] | None) -> tuple[list[str], bool] | None:
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return None
    include_unassigned = any(value == UNASSIGNED_WORKSHOP for value in wanted)
    ids = list(dict.fromkeys(value for value in wanted if value != UNASSIGNED_WORKSHOP))
    return ids, include_unassigned


def resolve_craft_ids(raw: list[str] | None) -> list[str] | None:
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    return list(dict.fromkeys(wanted)) or None


def workshop_clause(
    ids: list[str], include_unassigned: bool, *, is_workshop_table: bool = False
) -> dict[str, Any] | None:
    if is_workshop_table:
        return {"id": {"in": ids}} if ids else None
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
    if include_unassigned:
        branches.append({"workshopId": None})
    if not branches:
        return None
    return branches[0] if len(branches) == 1 else {"OR": branches}


def artisan_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
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
        branches.append({"workshopId": None})
    return {"OR": branches} if branches else {"id": {"in": []}}


def craft_workshop_clause(ids: list[str], include_unassigned: bool) -> dict[str, Any]:
    branches: list[dict[str, Any]] = []
    if ids:
        branches.append({"workshopId": {"in": ids}})
        branches.append({"workshops": {"some": {"workshopId": {"in": ids}}}})
    if include_unassigned:
        branches.append({"AND": [{"workshopId": None}, {"workshops": {"none": {}}}]})
    return {"OR": branches} if branches else {"id": {"in": []}}


_WIDE_WORKSHOP_CLAUSES = {
    "artisans": artisan_workshop_clause,
    "artisan": artisan_workshop_clause,
    "crafts": craft_workshop_clause,
    "craft": craft_workshop_clause,
}


def bucket_workshop_clause(bucket: str, ids: list[str], include_unassigned: bool) -> dict[str, Any]:
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
    if not raw:
        return None
    wanted = [part.strip() for value in raw for part in str(value).split(",") if part.strip()]
    if not wanted:
        return None

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

    unknown = sorted({token for token in wanted if token.lower() not in canonical})
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown {field} value{'s' if len(unknown) > 1 else ''}: {', '.join(unknown)}. "
                f"{field} must be one of {', '.join(sorted(allowed))}."
            ),
        )
    return {canonical[token.lower()] for token in wanted}


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

DESIGN_WORKSHOP_PLACE_COLUMNS: tuple[str, ...] = ("state", "district", "venue", "clusterName")

DESIGN_WORKSHOP_STAGE_RELATION = "entries"
DESIGN_WORKSHOP_STAGE_TEXT_COLUMN = "searchText"


def design_workshop_stage_text_clause(q: str) -> dict[str, Any]:
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
    where: dict[str, Any] = {"AND": [{"deletedAt": None}]}
    if visibility:
        where["AND"].append(visibility)

    if q:
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
    record_visibility, media_visibility = await gather_reads(
        viewable_where(user, owner_field="createdById"),
        viewable_where(user, owner_field="uploadedById"),
    )

    artisan_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    workshop_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    product_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    tool_where: dict[str, Any] = {"AND": [record_visibility]} if record_visibility else {}
    media_where: dict[str, Any] = {"AND": [media_visibility]} if media_visibility else {}

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

    if date_from or date_to:
        date_range: dict[str, Any] = {}
        if date_from:
            date_range["gte"] = date_from
        if date_to:
            date_range["lte"] = date_to
        workshop_where.setdefault("AND", []).append(
            {"OR": [{"startDate": date_range}, {"startDate": None, "date": date_range}]}
        )
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

    resolved = resolve_workshop_ids(workshop_ids)
    if resolved is not None:
        ids, include_unassigned = resolved
        for bucket, where in wheres.items():
            where.setdefault("AND", []).append(
                bucket_workshop_clause(bucket, ids, include_unassigned)
            )

    return wheres
