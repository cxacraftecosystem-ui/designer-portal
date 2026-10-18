"""/api/ministry-exports — the Ministry's monthly export packs: recipients, packs, download links.

Web only (ministry and admin authoring is web-only by standing rule). Every route answers 404 while
``MINISTRY_EXPORTS_ENABLED`` is off, then 403 with :data:`ministry_exports.REFUSAL` for anybody
outside :data:`design_workshop_oversight.OVERSIGHT_ASSIGNER_ROLES` — the whole-estate tiers, because
a pack is built at whole-estate scope.

Generating here never sends e-mail; only the scheduled run does (``services/ministry_exports``).
A download link is minted per click, lives ``MINISTRY_EXPORTS_LINK_SECONDS`` and is logged in
``MinistryExportDownload``.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import get_current_user
from app.schemas.ministry_exports import LinkRequest, PackRequest, RecipientUpdate
from app.services import ministry_exports as packs
from app.services.design_workshop_oversight import OVERSIGHT_ASSIGNER_ROLES
from app.services.ministry_pack_sheets import UNSTATED, scheme_keys, scheme_label

router = APIRouter(prefix="/ministry-exports", tags=["ministry-exports"])

#: How many accounts the recipient search offers.
ELIGIBLE_LIMIT = 20
MAX_SEARCH = 120


def _not_found() -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not Found")


async def require_ministry_export_manager(current_user: Any = Depends(get_current_user)) -> Any:
    """The one door. Lives here and not in ``core/deps.py``, as ``require_workshop_assigner`` does,
    and reads the same set — if the two are ever consolidated, move them together."""
    if not get_settings().ministry_exports_enabled:
        raise _not_found()
    if packs.role_of(current_user) not in OVERSIGHT_ASSIGNER_ROLES:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=packs.REFUSAL)
    return current_user


def _unprocessable(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=detail)


def _check_schemes(schemes: list[str]) -> list[str]:
    known = set(scheme_keys())
    unknown = [s for s in schemes if s not in known]
    if unknown:
        raise _unprocessable(f"Unknown scheme: {', '.join(sorted(set(unknown)))}.")
    return [key for key in scheme_keys() if key in set(schemes)]


def _iso(value: Any) -> str | None:
    if not isinstance(value, datetime):
        return None
    return (value if value.tzinfo else value.replace(tzinfo=UTC)).isoformat()


# --------------------------------------------------------------------------------------
# Schemes
# --------------------------------------------------------------------------------------


@router.get("/schemes")
async def list_schemes(_: Any = Depends(require_ministry_export_manager)) -> dict[str, Any]:
    groups = await db.designworkshop.group_by(
        by=["workshopKind"], where={"deletedAt": None}, count=True
    )
    counts: dict[str, int] = {}
    for group in groups:
        key = group.get("workshopKind") or UNSTATED
        count = group.get("_count")
        counts[key] = counts.get(key, 0) + (
            int(count.get("_all") or 0) if isinstance(count, dict) else int(count or 0)
        )
    return {
        "schemes": [
            {"key": key, "label": scheme_label(key), "workshopCount": counts.get(key, 0)}
            for key in scheme_keys()
        ]
    }


# --------------------------------------------------------------------------------------
# Recipients
# --------------------------------------------------------------------------------------


async def _recipient_payloads(rows: list[Any]) -> list[dict[str, Any]]:
    users = [row.user for row in rows if row.user is not None]
    verdicts = await packs.eligibility(users) if users else {}
    out = []
    for row in rows:
        user = row.user
        reason = verdicts.get(str(row.userId)) if user is not None else "Account removed"
        out.append(
            {
                "userId": row.userId,
                "name": getattr(user, "name", None),
                "email": getattr(user, "email", None),
                "role": packs.role_of(user) if user is not None else None,
                "schemes": [key for key in scheme_keys() if key in set(row.schemes or [])],
                "active": bool(row.active),
                "eligible": reason is None,
                "ineligibleReason": reason,
                "updatedAt": _iso(row.updatedAt),
            }
        )
    out.sort(key=lambda r: (str(r["name"] or "").lower(), str(r["email"] or "")))
    return out


@router.get("/recipients")
async def list_recipients(_: Any = Depends(require_ministry_export_manager)) -> dict[str, Any]:
    rows = await db.ministryexportrecipient.find_many(include={"user": True}, take=500)
    return {"recipients": await _recipient_payloads(list(rows))}


@router.get("/eligible-accounts")
async def eligible_accounts(
    search: str | None = Query(None, max_length=MAX_SEARCH),
    _: Any = Depends(require_ministry_export_manager),
) -> dict[str, Any]:
    where: dict[str, Any] = {"role": {"in": sorted(OVERSIGHT_ASSIGNER_ROLES)}, "email": {"not": ""}}
    term = (search or "").strip()
    if term:
        where["OR"] = [
            {"name": {"contains": term, "mode": "insensitive"}},
            {"email": {"contains": term, "mode": "insensitive"}},
        ]
    users = list(
        await db.user.find_many(where=where, order={"name": "asc"}, take=ELIGIBLE_LIMIT * 2)
    )
    verdicts = await packs.eligibility(users)
    accounts = [
        {"userId": u.id, "name": u.name, "email": u.email, "role": packs.role_of(u)}
        for u in users
        if verdicts.get(str(u.id)) is None
    ]
    return {"accounts": accounts[:ELIGIBLE_LIMIT]}


@router.put("/recipients/{user_id}")
async def put_recipient(
    user_id: str,
    payload: RecipientUpdate,
    current_user: Any = Depends(require_ministry_export_manager),
) -> dict[str, Any]:
    schemes = _check_schemes(payload.schemes)
    user = await db.user.find_unique(where={"id": user_id})
    if user is None:
        raise _unprocessable("That account cannot receive packs.")
    reason = (await packs.eligibility([user])).get(str(user.id))
    if reason is not None:
        raise _unprocessable(f"That account cannot receive packs: {reason.lower()}.")
    row = await db.ministryexportrecipient.upsert(
        where={"userId": user_id},
        data={
            "create": {
                "userId": user_id,
                "schemes": schemes,
                "active": payload.active,
                "createdById": current_user.id,
                "updatedById": current_user.id,
            },
            "update": {
                "schemes": {"set": schemes},
                "active": payload.active,
                "updatedById": current_user.id,
            },
        },
        include={"user": True},
    )
    return (await _recipient_payloads([row]))[0]


@router.delete("/recipients/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_recipient(
    user_id: str, _: Any = Depends(require_ministry_export_manager)
) -> Response:
    await db.ministryexportrecipient.delete_many(where={"userId": user_id})
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# --------------------------------------------------------------------------------------
# Packs
# --------------------------------------------------------------------------------------


@router.get("/packs")
async def list_packs(
    period: str | None = Query(None, max_length=16),
    scheme: str | None = Query(None, max_length=64),
    limit: int = Query(50, ge=1, le=200),
    _: Any = Depends(require_ministry_export_manager),
) -> dict[str, Any]:
    where: dict[str, Any] = {}
    if period:
        if not packs.is_period(period):
            raise _unprocessable("Period must look like 2026-09.")
        where["period"] = period
    if scheme:
        where["scheme"] = _check_schemes([scheme])[0]
    rows = list(
        await db.ministryexportpack.find_many(where=where, order={"period": "desc"}, take=limit)
    )
    rows.sort(key=lambda r: (-int(r.period.replace("-", "")), packs.scheme_order(r)))
    period_rows = await db.ministryexportpack.find_many(
        distinct=["period"], order={"period": "desc"}, take=60
    )
    return {"items": await packs.payloads(rows), "periods": [row.period for row in period_rows]}


async def _pack_or_404(pack_id: str) -> Any:
    row = await db.ministryexportpack.find_unique(where={"id": pack_id})
    if row is None:
        raise _not_found()
    return row


@router.get("/packs/{pack_id}")
async def get_pack(
    pack_id: str, _: Any = Depends(require_ministry_export_manager)
) -> dict[str, Any]:
    return (await packs.payloads([await _pack_or_404(pack_id)]))[0]


@router.post("/packs", status_code=status.HTTP_202_ACCEPTED)
async def generate_packs(
    payload: PackRequest, current_user: Any = Depends(require_ministry_export_manager)
) -> dict[str, Any]:
    period = payload.period.strip()
    if not packs.is_period(period):
        raise _unprocessable("Period must look like 2026-09.")
    if packs.is_future(period):
        raise _unprocessable("That month has not started yet.")
    schemes = _check_schemes(payload.schemes)
    rows = await packs.request_packs(period, schemes, current_user.id)
    return {"items": await packs.payloads(rows)}


@router.post("/packs/{pack_id}/links")
async def mint_link(
    pack_id: str, payload: LinkRequest, current_user: Any = Depends(require_ministry_export_manager)
) -> dict[str, Any]:
    from app.services import s3

    row = await _pack_or_404(pack_id)
    key = row.xlsxKey if payload.file == "xlsx" else row.pdfKey
    if row.status != packs.READY or not key:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="This pack is not ready yet."
        )
    if await asyncio.to_thread(s3.head_object, key) is None:
        raise HTTPException(status_code=status.HTTP_410_GONE, detail=packs.FILES_REMOVED)
    seconds = max(int(get_settings().ministry_exports_link_seconds or 60), 30)
    name = packs.file_name(row.period, row.scheme, payload.file)
    url = await asyncio.to_thread(
        s3.presign_get_url,
        key,
        filename=name,
        mime_type=packs.MIME[payload.file],
        expires_in=seconds,
    )
    await db.ministryexportdownload.create(
        data={
            "packId": row.id,
            "userId": current_user.id,
            "file": payload.file,
            "revision": row.revision,
        }
    )
    expires = datetime.now(UTC) + timedelta(seconds=seconds)
    return {"url": url, "expiresAt": expires.isoformat(), "fileName": name}
