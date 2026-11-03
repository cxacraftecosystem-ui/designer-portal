from __future__ import annotations

import logging
from typing import Any, NamedTuple

from fastapi import APIRouter, Depends, HTTPException, Query, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import get_current_user, require_content_admin
from app.schemas.workshop_types import (
    WorkshopTypeOptionCreate,
    WorkshopTypeOptionReorder,
    WorkshopTypeOptionUpdate,
)
from app.services.records import public_encode

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/workshop-types", tags=["workshop-types"])

_ORDER: list[dict[str, str]] = [{"sortOrder": "asc"}, {"key": "asc"}]


class Noun(NamedTuple):
    one: str
    many: str


_USES: tuple[tuple[str, str, Noun, dict[str, Any]], ...] = (
    (
        "designworkshop",
        "workshopKind",
        Noun("design workshop", "design workshops"),
        {"deletedAt": None},
    ),
    (
        "annualplanentry",
        "workshopKind",
        Noun(
            "planned workshop in the annual directory",
            "planned workshops in the annual directory",
        ),
        {},
    ),
)


def _payload(row: Any) -> dict[str, Any]:
    return public_encode(row)


async def _count_use(
    client: Any, model: str, column: str, key: str, extra: dict[str, Any]
) -> int | None:
    delegate = getattr(client, model, None)
    if delegate is None:
        logger.error(
            "workshop_types: the generated Prisma client carries no model %r, so a delete of a "
            "workshop type cannot check whether %s.%s still uses it. The client is behind "
            "schema.prisma — regenerate it with backend/scripts/regenerate-client.md, NOT with a "
            "bare `prisma generate`, which exits 0 without doing anything on Windows. Refusing the "
            "delete rather than performing one that cannot be checked; see _count_use.",
            model,
            model,
            column,
        )
        return None
    try:
        return await delegate.count(where={column: key, **extra})
    except Exception:
        logger.exception(
            "workshop_types: counting %s.%s for workshop type key %r failed, so a delete cannot be "
            "checked. Usually a migration that has not been applied to this database. Refusing the "
            "delete; see _count_use.",
            model,
            column,
            key,
        )
        return None


async def _usage_tally(client: Any, key: str) -> tuple[list[tuple[Noun, int]], list[Noun]]:
    named: list[tuple[Noun, int]] = []
    uncounted: list[Noun] = []
    for model, column, noun, extra in _USES:
        count = await _count_use(client, model, column, key, extra)
        if count is None:
            uncounted.append(noun)
        elif count > 0:
            named.append((noun, count))
    named.sort(key=lambda pair: pair[1], reverse=True)
    return named, uncounted


_DEACTIVATE_INSTEAD = (
    " Deactivate it instead — it disappears from every “Type of workshop” dropdown and every "
    "workshop already filed under it keeps its type and its label."
)


def _in_use_message(label: str, named: list[tuple[Noun, int]], uncounted: list[Noun]) -> str:
    if named:
        counted = ", ".join(
            f"{count} {noun.one if count == 1 else noun.many}" for noun, count in named
        )
        sentence = f"“{label}” cannot be deleted: {counted} still filed under it."
        if uncounted:
            sentence += (
                " There may also be "
                + ", ".join(noun.many for noun in uncounted)
                + " filed under it that couldn't be counted just now."
            )
        return sentence + _DEACTIVATE_INSTEAD
    return (
        f"“{label}” can't be deleted right now: it couldn't be checked whether any "
        + ", ".join(noun.many for noun in uncounted)
        + " are filed under it. Try again later."
        + _DEACTIVATE_INSTEAD
    )


async def _require_type(type_id: str) -> Any:
    row = await db.workshoptypeoption.find_unique(where={"id": type_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Workshop type not found")
    return row


@router.get("")
async def list_workshop_types(
    _: Any = Depends(get_current_user),
    includeInactive: bool = Query(False),
) -> list[dict[str, Any]]:
    where = {} if includeInactive else {"isActive": True}
    rows = await db.workshoptypeoption.find_many(where=where, order=_ORDER)
    return [_payload(row) for row in rows]


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_workshop_type(
    payload: WorkshopTypeOptionCreate, _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    try:
        created = await db.workshoptypeoption.create(data=payload.model_dump())
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"A workshop type with the code {payload.key} already exists. Edit that type's "
                "label instead of adding a second one with the same code."
            ),
        ) from exc
    return _payload(created)


@router.patch("/reorder")
async def reorder_workshop_types(
    payload: WorkshopTypeOptionReorder, _: Any = Depends(require_content_admin)
) -> list[dict[str, Any]]:
    rows = await db.workshoptypeoption.find_many()
    known = {row.id for row in rows}
    missing = [type_id for type_id in payload.ids if type_id not in known]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                "The list couldn't be reordered: a workshop type in it no longer exists. "
                "Reload the list — somebody else may have removed one."
            ),
        )
    if len(set(payload.ids)) != len(payload.ids):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="The list couldn't be reordered: the same workshop type appears twice.",
        )
    async with db.tx() as tx:
        for index, type_id in enumerate(payload.ids):
            await tx.workshoptypeoption.update(
                where={"id": type_id}, data={"sortOrder": (index + 1) * 10}
            )
    updated = await db.workshoptypeoption.find_many(order=_ORDER)
    return [_payload(row) for row in updated]


@router.patch("/{type_id}")
async def update_workshop_type(
    type_id: str, payload: WorkshopTypeOptionUpdate, _: Any = Depends(require_content_admin)
) -> dict[str, Any]:
    row = await _require_type(type_id)
    data = payload.model_dump(exclude_unset=True)
    if not data:
        return _payload(row)
    updated = await db.workshoptypeoption.update(where={"id": type_id}, data=data)
    return _payload(updated)


@router.delete("/{type_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_workshop_type(type_id: str, _: Any = Depends(require_content_admin)) -> None:
    row = await _require_type(type_id)
    async with db.tx() as tx:
        named, uncounted = await _usage_tally(tx, row.key)
        if named or uncounted:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=_in_use_message(row.label, named, uncounted),
            )
        await tx.workshoptypeoption.delete(where={"id": type_id})
