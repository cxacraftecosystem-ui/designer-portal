import logging
import re
from collections.abc import Iterable
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import is_break_glass_master, role_value
from app.services import access_roster, design_workshop_posts as posts
from app.services.concurrency import gather_reads
from app.services.records import contains

logger = logging.getLogger(__name__)


INSPECTION_ROLES = frozenset({"INSPECTOR"})

INSPECTION_HOLDER_ROLES = INSPECTION_ROLES | posts.SERVING_ADMIN_ROLES

MAX_DESIGN_WORKSHOP_INSPECTORS = 25

ELIGIBLE_INSPECTOR_LIMIT = 2000


def _role(user: Any) -> str:
    return role_value(user) if user is not None else ""


def is_inspector(user: Any) -> bool:
    return _role(user) in INSPECTION_ROLES


def may_hold_an_inspection(user: Any) -> bool:
    return _role(user) in INSPECTION_HOLDER_ROLES



async def has_inspection_scope(workshop_id: str, user_id: str) -> bool:
    if not workshop_id or not user_id:
        return False
    row = await db.designworkshopinspector.find_unique(
        where={"designWorkshopId_userId": {"designWorkshopId": workshop_id, "userId": user_id}}
    )
    return row is not None


def inspectable_by_clause(user_id: str) -> dict[str, Any]:
    return {"inspectors": {"some": {"userId": user_id}}}


async def load_inspectable_workshop_or_404(workshop_id: str, user: Any) -> Any:
    if not may_hold_an_inspection(user):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    record = await db.designworkshop.find_unique(where={"id": workshop_id})
    if record is None or record.deletedAt is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    if not await has_inspection_scope(workshop_id, getattr(user, "id", "")):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record



def inspector_payload(row: Any) -> dict[str, Any]:
    user = getattr(row, "user", None)
    return {
        "userId": row.userId,
        "name": getattr(user, "name", "") or "",
        "email": getattr(user, "email", "") or "",
        "role": _role(user),
        "assignedAt": row.createdAt.isoformat() if getattr(row, "createdAt", None) else None,
    }


async def inspector_accounts(workshop_id: str) -> list[Any]:
    rows = await db.designworkshopinspector.find_many(
        where={"designWorkshopId": workshop_id}, include={"user": True}, order={"createdAt": "asc"}
    )
    return [row.user for row in rows if getattr(row, "user", None) is not None]


async def holds_any_inspection(user_id: str) -> bool:
    return await db.designworkshopinspector.count(where={"userId": user_id}) > 0


async def inspector_rows(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.designworkshopinspector.find_many(
        where={"designWorkshopId": workshop_id},
        include={"user": True},
        order={"createdAt": "asc"},
    )
    return [inspector_payload(row) for row in rows]


async def eligible_inspectors(
    search: str | None = None, *, exclude_user_id: str | None = None
) -> dict[str, Any]:
    barred = await access_roster.barred_emails()

    clauses: list[dict[str, Any]] = [{"role": {"in": sorted(INSPECTION_HOLDER_ROLES)}}]
    if exclude_user_id:
        clauses.append({"id": {"not": exclude_user_id}})
    if barred:
        configured = (get_settings().master_admin_email or "").strip().lower()
        exemptions: list[dict[str, Any]] = [{"role": "MASTER_ADMIN"}]
        if configured:
            exemptions.append({"email": {"equals": configured, "mode": "insensitive"}})
        clauses.append({"OR": [*exemptions, {"email": {"not_in": barred, "mode": "insensitive"}}]})

    term = (search or "").strip()
    if term:
        clauses.append({"OR": [{"name": contains(term)}, {"email": contains(term)}]})

    users = await db.user.find_many(
        where={"AND": clauses},
        order=[{"name": "asc"}, {"id": "asc"}],
        take=ELIGIBLE_INSPECTOR_LIMIT + 1,
    )
    truncated = len(users) > ELIGIBLE_INSPECTOR_LIMIT
    users = users[:ELIGIBLE_INSPECTOR_LIMIT]
    if truncated:
        logger.warning(
            "eligible-inspectors hit its ceiling of %s accounts (search=%r); the answer is "
            "truncated and says so, and the caller can narrow it",
            ELIGIBLE_INSPECTOR_LIMIT,
            term,
        )
    return {
        "users": [{"id": u.id, "name": u.name, "email": u.email, "role": _role(u)} for u in users],
        "truncated": truncated,
    }



async def replace_inspectors(
    workshop_id: str, user_ids: list[str], *, assigned_by_id: str
) -> list[dict[str, Any]]:
    wanted = _deduplicate(user_ids)

    existing = await db.designworkshopinspector.find_many(where={"designWorkshopId": workshop_id})
    held = {row.userId for row in existing}
    removed = sorted(held - wanted)
    added = sorted(wanted - held)
    if assigned_by_id in removed:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=posts.self_release_refusal({posts.INSPECTOR}),
        )
    await _assert_every_id_may_inspect(
        workshop_id,
        wanted,
        appointing=assigned_by_id if assigned_by_id not in held else None,
    )

    if removed:
        await db.designworkshopinspector.delete_many(
            where={"designWorkshopId": workshop_id, "userId": {"in": removed}}
        )
    if added:
        await db.designworkshopinspector.create_many(
            data=[
                {"designWorkshopId": workshop_id, "userId": uid, "assignedById": assigned_by_id}
                for uid in added
            ],
            skip_duplicates=True,
        )
    return await inspector_rows(workshop_id)


def _deduplicate(user_ids: list[str]) -> set[str]:
    return {uid.strip() for uid in user_ids if uid and uid.strip()}


_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


def _displayable(user_id: str) -> str:
    return _UNSTORABLE_IN_AN_ID.sub("", user_id)


async def _assert_every_id_may_inspect(
    workshop_id: str, user_ids: set[str], *, appointing: str | None = None
) -> None:
    if not user_ids:
        return

    lookup = sorted(uid for uid in user_ids if not _UNSTORABLE_IN_AN_ID.search(uid))
    users = await db.user.find_many(where={"id": {"in": lookup}}) if lookup else []
    by_id = {u.id: u for u in users}

    unknown = sorted(_displayable(uid) for uid in user_ids if uid not in by_id)
    if unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "No account exists with "
                + ("these ids: " if len(unknown) > 1 else "this id: ")
                + ", ".join(unknown)
                + ". Nothing was changed."
            ),
        )

    barred, authored, held = await gather_reads(
        access_roster.barred_among([u.email for u in users]),
        posts.authorship_among(workshop_id, by_id),
        posts.supervisory_posts_among(workshop_id, by_id),
    )

    refusals: list[str] = []
    separation: list[str] = []
    for uid in sorted(user_ids):
        user = by_id[uid]
        role = _role(user)
        if role not in INSPECTION_HOLDER_ROLES:
            refusals.append(
                f"{user.name} ({user.email}) is a {role}, and only an Inspector / Reviewer, a "
                f"Ministry Admin, an admin or the master admin can be assigned an inspection."
            )
            continue
        if not is_break_glass_master(user) and _normalised(user.email) in barred:
            refusals.append(
                f"{user.name} ({user.email}) is barred by the platform access list, so they cannot "
                f"sign in at all. Restore their access on the access screen first."
            )
        separation.extend(
            posts.separation_refusals(
                person=f"{user.name} ({user.email})",
                post=posts.INSPECTOR,
                standing=posts.Standing(
                    posts=held.get(uid, frozenset()), authored=authored.get(uid, frozenset())
                ),
                self_appointed=uid == appointing,
            )
        )

    posts.raise_refusals(refusals, separation)


def _normalised(email: Any) -> str:
    return str(email or "").strip().lower()


async def inspection_holders_among(workshop_id: str, user_ids: Iterable[str]) -> set[str]:
    ids = sorted({uid for uid in user_ids if uid and not _UNSTORABLE_IN_AN_ID.search(uid)})
    if not workshop_id or not ids:
        return set()
    rows = await db.designworkshopinspector.find_many(
        where={"designWorkshopId": workshop_id, "userId": {"in": ids}}
    )
    return {row.userId for row in rows}


async def inspected_workshop_ids(user_id: str) -> set[str]:
    if not user_id or _UNSTORABLE_IN_AN_ID.search(user_id):
        return set()
    rows = await db.designworkshopinspector.find_many(where={"userId": user_id})
    return {row.designWorkshopId for row in rows}


async def inspected_live_workshop_ids(user_id: str) -> set[str]:
    if not user_id or _UNSTORABLE_IN_AN_ID.search(user_id):
        return set()
    rows = await db.designworkshopinspector.find_many(
        where={"userId": user_id, "designWorkshop": {"is": {"deletedAt": None}}}
    )
    return {row.designWorkshopId for row in rows}



NOT_AN_INSPECTOR_DETAIL = (
    "This page is for inspectors. Sign out and back in, or ask an administrator."
)


def assert_inspection_surface(user: Any) -> None:
    if may_hold_an_inspection(user):
        return
    raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=NOT_AN_INSPECTOR_DETAIL)
