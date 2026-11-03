import logging
import re
from typing import Any

from fastapi import HTTPException, status

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import DESIGN_WORKSHOP_ROLES, is_break_glass_master
from app.services import access_roster, design_workshop_posts as posts
from app.services.concurrency import gather_reads
from app.services.designers import normalise_email, roster_exempt_workshop_roles
from app.services.records import contains

logger = logging.getLogger(__name__)

ELIGIBLE_VIEWER_LIMIT = 2000

ACTIVE_ROSTER_READ_LIMIT = 50_000


def _role(user: Any) -> str:
    value = getattr(user, "role", None)
    return str(getattr(value, "value", value) or "")



async def viewer_ids_among(workshop_id: str, user_ids: Any) -> set[str]:
    ids = sorted({uid for uid in user_ids if uid and not _UNSTORABLE_IN_AN_ID.search(uid)})
    if not workshop_id or not ids:
        return set()
    rows = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": workshop_id, "userId": {"in": ids}}
    )
    return {row.userId for row in rows}


async def has_viewer_grant(workshop_id: str, user_id: str) -> bool:
    if not workshop_id or not user_id:
        return False
    row = await db.designworkshopviewer.find_unique(
        where={"designWorkshopId_userId": {"designWorkshopId": workshop_id, "userId": user_id}}
    )
    return row is not None


def visible_to_clause(user_id: str) -> dict[str, Any]:
    return {
        "OR": [
            {"createdById": user_id},
            {"viewers": {"some": {"userId": user_id}}},
        ]
    }



def viewer_payload(row: Any) -> dict[str, Any]:
    user = getattr(row, "user", None)
    return {
        "userId": row.userId,
        "name": getattr(user, "name", "") or "",
        "email": getattr(user, "email", "") or "",
        "role": _role(user),
        "grantedAt": row.createdAt.isoformat() if getattr(row, "createdAt", None) else None,
    }


async def viewer_rows(workshop_id: str) -> list[dict[str, Any]]:
    rows = await db.designworkshopviewer.find_many(
        where={"designWorkshopId": workshop_id},
        include={"user": True},
        order={"createdAt": "asc"},
    )
    return [viewer_payload(row) for row in rows]


async def eligible_viewers(
    search: str | None = None, *, exclude_user_id: str | None = None
) -> dict[str, Any]:
    (admitted, roster_truncated), barred = await gather_reads(
        active_roster_emails(), access_roster.barred_emails()
    )

    clauses: list[dict[str, Any]] = [
        {
            "OR": [
                {"role": {"in": roster_exempt_workshop_roles()}},
                {"AND": [{"role": "DESIGNER"}, {"email": {"in": admitted, "mode": "insensitive"}}]},
            ]
        }
    ]
    if barred:
        configured = (get_settings().master_admin_email or "").strip().lower()
        exemptions: list[dict[str, Any]] = [{"role": "MASTER_ADMIN"}]
        if configured:
            exemptions.append({"email": {"equals": configured, "mode": "insensitive"}})
        clauses.append({"OR": [*exemptions, {"email": {"not_in": barred, "mode": "insensitive"}}]})
    if exclude_user_id:
        clauses.append({"id": {"not": exclude_user_id}})
    term = (search or "").strip()
    if term:
        clauses.append({"OR": [{"name": contains(term)}, {"email": contains(term)}]})

    users = await db.user.find_many(
        where={"AND": clauses},
        order=[{"name": "asc"}, {"id": "asc"}],
        take=ELIGIBLE_VIEWER_LIMIT + 1,
    )
    truncated = len(users) > ELIGIBLE_VIEWER_LIMIT
    users = users[:ELIGIBLE_VIEWER_LIMIT]
    if truncated:
        logger.warning(
            "eligible-viewers hit its ceiling of %s accounts (search=%r); the answer is truncated "
            "and says so, and the caller can narrow it",
            ELIGIBLE_VIEWER_LIMIT,
            term,
        )
    return {
        "users": [{"id": u.id, "name": u.name, "email": u.email, "role": _role(u)} for u in users],
        "truncated": truncated or roster_truncated,
    }


async def active_roster_emails() -> tuple[list[str], bool]:
    rows = await db.designerroster.find_many(
        where={"isActive": True}, take=ACTIVE_ROSTER_READ_LIMIT + 1
    )
    truncated = len(rows) > ACTIVE_ROSTER_READ_LIMIT
    if truncated:
        rows = rows[:ACTIVE_ROSTER_READ_LIMIT]
        logger.error(
            "the active designer roster exceeds %s rows, so only part of it was read; designers "
            "past that cut are missing from the viewer picker AND the designer directory, for "
            "every search either of them can make",
            ACTIVE_ROSTER_READ_LIMIT,
        )
    return sorted({normalise_email(row.email) for row in rows}), truncated


async def _posts_held_on(workshop_id: str | None, user_ids: Any) -> dict[str, frozenset[str]]:
    if not workshop_id:
        return {}
    return await posts.supervisory_posts_among(workshop_id, user_ids)


async def _designers_the_roster_still_admits(users: list[Any]) -> set[str]:
    emails = [normalise_email(u.email) for u in users if _role(u) == "DESIGNER"]
    if not emails:
        return set()
    rows = await db.designerroster.find_many(where={"email": {"in": emails}, "isActive": True})
    return {normalise_email(row.email) for row in rows}



async def replace_viewers(
    workshop_id: str, user_ids: list[str], *, creator_id: str, granted_by_id: str
) -> list[dict[str, Any]]:
    wanted = _deduplicate(user_ids, creator_id)

    existing = await db.designworkshopviewer.find_many(where={"designWorkshopId": workshop_id})
    held = {row.userId for row in existing}
    await _assert_every_id_may_be_granted(
        wanted,
        workshop_id=workshop_id,
        appointing=granted_by_id if granted_by_id not in held else None,
    )

    removed = sorted(held - wanted)
    added = sorted(wanted - held)

    if removed:
        await db.designworkshopviewer.delete_many(
            where={"designWorkshopId": workshop_id, "userId": {"in": removed}}
        )
    if added:
        await db.designworkshopviewer.create_many(
            data=[
                {"designWorkshopId": workshop_id, "userId": uid, "grantedById": granted_by_id}
                for uid in added
            ],
            skip_duplicates=True,
        )
        from app.services import email_outbox

        workshop = await db.designworkshop.find_unique(where={"id": workshop_id})
        granter = await db.user.find_unique(where={"id": granted_by_id}) if granted_by_id else None
        if workshop is not None:
            await email_outbox.notify_workshop_assigned(workshop, added, actor=granter)
    return await viewer_rows(workshop_id)


async def remove_one_viewer(workshop_id: str, user_id: str) -> bool:
    removed = await db.designworkshopviewer.delete_many(
        where={"designWorkshopId": workshop_id, "userId": user_id}
    )
    return bool(removed)


def _deduplicate(user_ids: list[str], creator_id: str) -> set[str]:
    return {uid.strip() for uid in user_ids if uid and uid.strip()} - {creator_id}


_UNSTORABLE_IN_AN_ID = re.compile(r"[\x00-\x1f\x7f\ud800-\udfff]")


def _displayable(user_id: str) -> str:
    return _UNSTORABLE_IN_AN_ID.sub("", user_id)


async def _assert_every_id_may_be_granted(
    user_ids: set[str], *, workshop_id: str | None = None, appointing: str | None = None
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

    allowed, barred, held = await gather_reads(
        _designers_the_roster_still_admits(users),
        access_roster.barred_among([u.email for u in users]),
        _posts_held_on(workshop_id, by_id),
    )
    refusals: list[str] = []
    separation: list[str] = []
    for uid in sorted(user_ids):
        user = by_id[uid]
        role = _role(user)
        if role not in DESIGN_WORKSHOP_ROLES:
            refusals.append(
                f"{user.name} ({user.email}) is a {role} and cannot run a design & prototype "
                f"workshop, so they can't be given access to one."
            )
            continue
        if role == "DESIGNER" and normalise_email(user.email) not in allowed:
            refusals.append(
                f"{user.name} ({user.email}) is not on the active designer roster, so they cannot "
                f"sign in at all. Restore their roster entry first."
            )
        if not is_break_glass_master(user) and normalise_email(user.email) in barred:
            refusals.append(
                f"{user.name} ({user.email}) can't sign in: their access has been rejected or "
                f"suspended. Restore them on the access screen first."
            )
        separation.extend(
            posts.separation_refusals(
                person=f"{user.name} ({user.email})",
                post=posts.DESIGNER,
                standing=posts.Standing(posts=held.get(uid, frozenset())),
                self_appointed=uid == appointing,
            )
        )
    posts.raise_refusals(refusals, separation)
