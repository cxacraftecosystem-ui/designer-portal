from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.db import db
from app.core.deps import get_current_user, role_value
from app.services.concurrency import gather_reads
from app.services.ministry_dashboard import scope_clause as ministry_scope_clause
from app.services.workshop_analytics import (
    WorkshopRows,
    analyse_archive,
    archive_findings_payload,
)

router = APIRouter(prefix="/analytics", tags=["analytics"])

_FOLLOW_UP = "followUp"
_COST_SHEET = "costSheet"
_OPPORTUNITY = "designOpportunity"
ANALYSED_ENTITIES = (_FOLLOW_UP, _COST_SHEET, _OPPORTUNITY)

ROW_CAP = 50_000

HEADER_CAP = 20_000


async def _load(scope: dict[str, Any] | None = None) -> tuple[list[WorkshopRows], int, bool]:
    live_where: dict[str, Any] = {"deletedAt": None}
    entry_where: dict[str, Any] = {"deletedAt": None, "entityKey": {"in": list(ANALYSED_ENTITIES)}}
    if scope is not None:
        live_where = {"AND": [live_where, scope]}
        entry_where = {**entry_where, "designWorkshop": {"is": scope}}
    archive_size, rows = await gather_reads(
        db.designworkshop.count(where=live_where),
        db.dwstageentry.find_many(
            where=entry_where,
            take=ROW_CAP + 1,
            order={"id": "asc"},
        ),
    )
    truncated = len(rows) > ROW_CAP
    rows = rows[:ROW_CAP]

    workshop_ids = sorted({row.designWorkshopId for row in rows})
    truncated = truncated or len(workshop_ids) > HEADER_CAP
    workshop_ids = workshop_ids[:HEADER_CAP]

    headers = (
        await db.designworkshop.find_many(
            where=(
                {"id": {"in": workshop_ids}, "deletedAt": None}
                if scope is None
                else {"AND": [{"id": {"in": workshop_ids}, "deletedAt": None}, scope]}
            ),
        )
        if workshop_ids
        else []
    )

    live = {header.id: header for header in headers}

    grouped: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for row in rows:
        if row.designWorkshopId not in live:
            continue
        bucket = grouped.setdefault(row.designWorkshopId, {})
        payload = row.data if isinstance(row.data, dict) else {}
        bucket.setdefault(row.entityKey, []).append(payload)

    workshops = [
        WorkshopRows(
            workshop_id=workshop_id,
            title=live[workshop_id].title or "",
            craft=live[workshop_id].craftName or "",
            cluster=live[workshop_id].clusterName or "",
            state=live[workshop_id].state or "",
            follow_ups=tuple(entities.get(_FOLLOW_UP, ())),
            cost_sheets=tuple(entities.get(_COST_SHEET, ())),
            opportunities=tuple(entities.get(_OPPORTUNITY, ())),
        )
        for workshop_id, entities in grouped.items()
    ]
    return workshops, archive_size, truncated


ANALYTICS_READ_ROLES = frozenset({"REGIONAL_DIRECTOR", "MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"})

ANALYTICS_REFUSAL = (
    "For admins, Ministry Admins and Regional Directors. Your own workshops are on Design workshops."
)


async def require_analytics_reader(current_user: Any = Depends(get_current_user)) -> Any:
    if role_value(current_user) not in ANALYTICS_READ_ROLES:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=ANALYTICS_REFUSAL)
    return current_user


def analytics_scope(user: Any) -> dict[str, Any] | None:
    if role_value(user) == "REGIONAL_DIRECTOR":
        return ministry_scope_clause(user)
    return None


@router.get("/design-workshops")
async def design_workshop_analytics(
    current_user: Any = Depends(require_analytics_reader),
) -> dict[str, Any]:
    workshops, archive_size, truncated = await _load(analytics_scope(current_user))
    findings = analyse_archive(workshops, workshops_in_archive=archive_size, truncated=truncated)
    return archive_findings_payload(findings)
