"""The coverage map: what the programme has reached, district by district, and where the gaps are.

``/map/points`` (``map_points.py``) plots records where they are. These routes fold the same corpus
into LGD units (``services/lgd``) so a ministry reader can see COVERAGE: design workshops held and
planned, field records per district, the shared crafts nobody has documented, and the districts no
workshop has reached. The folding lives in ``services/coverage``; this module is the doors.

WHO MAY ASK. The ministry's readers (``require_ministry_dashboard_reader``): Ministry Admin and the
master admin see the whole country; an Assistant or Regional Director sees the design workshops they
were named on (``ministry_dashboard.scope_clause``), the plan rows of their states
(``annual_plan.plan_scope``), and only units in those states.

WHO MAY MAP A PLACE. A free-text state or district that does not resolve is listed for the master
admin and the Ministry Admin, who can map that spelling to an LGD code once (``GeoAlias``). Nothing is
matched fuzzily or automatically. Every alias write is a ``RecordRevision`` and can be deleted.

NAMES ONLY. The stubs carry an id, a title, a link, a craft code and a financial year. Aadhaar,
Pehchan, phone and address text are never read into them.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from prisma.errors import UniqueViolationError
from pydantic import BaseModel, ConfigDict, Field

from app.api.routes.ministry_dashboard import require_ministry_dashboard_reader
from app.core.config import get_settings
from app.core.db import db
from app.core.deps import CONTENT_ADMIN_ROLES, get_current_user, role_value
from app.services import coverage, lgd
from app.services.access import record_revision

router = APIRouter(prefix="/map", tags=["map"])

#: Who may map a place spelling to an LGD code: the content admins (``deps.CONTENT_ADMIN_ROLES``,
#: 2026-10-20). ADMIN joined the master admin and the Ministry Admin that day.
PLACE_MAPPING_ROLES = CONTENT_ADMIN_ROLES

PLACE_MAPPING_REFUSAL = "Only an admin can map place names."


def can_map_places(user: Any) -> bool:
    return role_value(user) in PLACE_MAPPING_ROLES


async def require_place_mapper(current_user: Any = Depends(get_current_user)) -> Any:
    if not can_map_places(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=PLACE_MAPPING_REFUSAL)
    return current_user


def _filters(
    level: str | None,
    craft_code: list[str] | None,
    scheme: list[str] | None,
    fy: list[str] | None,
    state_lgd: int | None,
    peer: str | None,
) -> coverage.Filters:
    try:
        return coverage.parse_filters(
            level=level,
            craft_code=craft_code,
            scheme=scheme,
            fy=fy,
            state_lgd=state_lgd,
            peer=(peer or "").strip().lower() in {"1", "true", "yes"},
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(exc)) from None


@router.get("/coverage")
async def map_coverage(
    current_user: Any = Depends(require_ministry_dashboard_reader),
    level: str | None = None,
    craftCode: list[str] | None = Query(None),
    scheme: list[str] | None = Query(None),
    fy: list[str] | None = Query(None),
    stateLgd: int | None = None,
    peer: str | None = None,
) -> dict[str, Any]:
    filters = _filters(level, craftCode, scheme, fy, stateLgd, peer)
    return await coverage.coverage(
        current_user, filters, settings=get_settings(), is_admin=can_map_places(current_user)
    )


@router.get("/coverage/units/{state_lgd}/{district_lgd}/records")
async def map_coverage_unit_records(
    state_lgd: int,
    district_lgd: int,
    current_user: Any = Depends(require_ministry_dashboard_reader),
    level: str | None = None,
    craftCode: list[str] | None = Query(None),
    scheme: list[str] | None = Query(None),
    fy: list[str] | None = Query(None),
    bucket: str | None = None,
    cursor: str | None = None,
) -> dict[str, Any]:
    filters = _filters(level, craftCode, scheme, fy, None, None)
    if bucket is not None and bucket not in coverage.ALL_BUCKETS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"bucket must be one of {', '.join(coverage.ALL_BUCKETS)}.",
        )
    answer = await coverage.unit_records(
        current_user,
        filters,
        state_lgd,
        district_lgd,
        bucket=bucket,
        cursor=cursor,
        settings=get_settings(),
    )
    if answer is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No such state or district.")
    return answer


@router.get("/coverage/unmatched")
async def map_coverage_unmatched(current_user: Any = Depends(require_place_mapper)) -> dict[str, Any]:
    return await coverage.unmatched_report(current_user)


class AliasCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    rawState: str | None = Field(default=None, max_length=160)
    rawDistrict: str | None = Field(default=None, max_length=160)
    stateLgd: int
    districtLgd: int | None = None


def _alias_payload(row: Any) -> dict[str, Any]:
    return {
        "id": row.id,
        "rawStateKey": row.rawStateKey,
        "rawDistrictKey": row.rawDistrictKey,
        "stateLgd": row.stateLgd,
        "districtLgd": row.districtLgd,
        "state": lgd.state_name(row.stateLgd),
        "district": lgd.district_name(row.districtLgd),
        "createdAt": row.createdAt,
    }


@router.post("/coverage/aliases", status_code=status.HTTP_201_CREATED)
async def create_alias(
    payload: AliasCreate, current_user: Any = Depends(require_place_mapper)
) -> dict[str, Any]:
    state_key, district_key = lgd.alias_key(payload.rawState, payload.rawDistrict)
    if not state_key and not district_key:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="Give the spelling to map."
        )
    if lgd.state_name(payload.stateLgd) is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"{payload.stateLgd} is not an LGD state code.",
        )
    if payload.districtLgd is not None and lgd.district_state(payload.districtLgd) != payload.stateLgd:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=f"That district is not in {lgd.state_name(payload.stateLgd)}.",
        )
    existing = await db.geoalias.find_unique(
        where={"rawStateKey_rawDistrictKey": {"rawStateKey": state_key, "rawDistrictKey": district_key}}
    )
    if existing is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="This spelling is already mapped.")
    data = {
        "rawStateKey": state_key,
        "rawDistrictKey": district_key,
        "stateLgd": payload.stateLgd,
        "districtLgd": payload.districtLgd,
    }
    try:
        async with db.tx() as tx:
            row = await tx.geoalias.create(data={**data, "createdById": getattr(current_user, "id", None)})
            await record_revision({"id": row.id}, current_user, data, "geoAlias", client=tx)
    except UniqueViolationError:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="This spelling is already mapped."
        ) from None
    coverage.clear_caches()
    return _alias_payload(row)


@router.delete("/coverage/aliases/{alias_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_alias(alias_id: str, current_user: Any = Depends(require_place_mapper)) -> Response:
    row = await db.geoalias.find_unique(where={"id": alias_id})
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="No such place mapping.")
    async with db.tx() as tx:
        await record_revision(
            row,
            current_user,
            {"rawStateKey": None, "rawDistrictKey": None, "stateLgd": None, "districtLgd": None},
            "geoAlias",
            client=tx,
        )
        await tx.geoalias.delete(where={"id": alias_id})
    coverage.clear_caches()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/coverage/aliases")
async def list_aliases(_: Any = Depends(require_place_mapper)) -> dict[str, Any]:
    rows = await db.geoalias.find_many(order={"createdAt": "desc"}, take=500)
    return {"items": [_alias_payload(row) for row in rows], "total": len(rows)}
