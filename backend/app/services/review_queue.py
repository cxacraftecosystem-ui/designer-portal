from __future__ import annotations

from typing import Any

from app.core.db import db
from app.core.deps import ROLE_RANK, is_master_admin, role_rank
from app.services.concurrency import gather_reads

PENDING_MODELS: tuple[str, ...] = (
    "artisan",
    "workshop",
    "productdocumentation",
    "tooldocumentation",
    "process",
    "questionnaireinterview",
)


def reviewable_creator_roles(reviewer: Any) -> list[str] | None:
    if is_master_admin(reviewer):
        return None
    rank = role_rank(reviewer)
    return [role for role, value in ROLE_RANK.items() if value < rank]


def pending_where(reviewer: Any) -> dict[str, Any] | None:
    roles = reviewable_creator_roles(reviewer)
    if roles is not None and not roles:
        return None
    where: dict[str, Any] = {"status": "PENDING"}
    if roles is not None:
        where["createdBy"] = {"is": {"role": {"in": roles}}}
    return where


async def pending_count(reviewer: Any, *, extra: dict[str, Any] | None = None) -> int:
    where = pending_where(reviewer)
    if where is None:
        return 0
    if extra:
        where = {"AND": [where, extra]}
    counts = await gather_reads(*(getattr(db, name).count(where=where) for name in PENDING_MODELS))
    return int(sum(counts))
