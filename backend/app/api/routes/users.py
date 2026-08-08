from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder
from prisma.errors import ForeignKeyViolationError

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    get_current_user,
    invalidate_cached_user,
    is_admin,
    is_master_admin,
    require_admin,
    require_professor,
    role_rank,
)
from app.core.security import hash_password
from app.schemas.users import UserCreate, UserUpdate
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains

router = APIRouter(prefix="/users", tags=["users"])

ALLOWED_ROLES = set(ROLE_RANK)


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def assert_role(role: str | None, current_user: Any) -> None:
    """A user may assign roles at or below their own tier: admins promote to their level and
    beneath; only the master admin can mint MASTER_ADMIN."""
    if not role:
        return
    if role not in ALLOWED_ROLES:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid user role")
    if role == "MASTER_ADMIN" and not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Only the master admin can grant master admin")
    if ROLE_RANK[role] > role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign roles at or below your own tier",
        )


def assert_can_manage_target(current_user: Any, target_user: Any) -> None:
    """Only the master admin may manage peers; everyone else manages strictly lower tiers.
    This blocks one admin from silently rewriting another admin's account."""
    if is_master_admin(current_user):
        return
    if role_rank(target_user) >= role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only manage users below your own tier",
        )


def is_master_email(email: str | None) -> bool:
    if not email:
        return False
    return email.lower() == get_settings().master_admin_email.lower()


def assert_not_demoting_master(target_user: Any, payload_role: str | None, current_user: Any) -> None:
    if not is_master_email(target_user.email):
        return
    if not is_master_admin(current_user):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account is protected")
    if payload_role and payload_role != "MASTER_ADMIN":
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="The master admin must keep MASTER_ADMIN role")


@router.get("/directory")
async def user_directory(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
) -> list[dict[str, Any]]:
    """A minimal directory of all users (id, name, email, role) readable by ANY authenticated user.

    Powers the data-access "request access from a researcher" picker, where a non-admin needs to choose
    a colleague. Returns no privileges or password material — just enough to identify a person.
    """
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    users = await db.user.find_many(where=where, order={"name": "asc"}, take=500)
    return [
        {"id": u.id, "name": u.name, "email": u.email, "role": str(getattr(u.role, "value", u.role))}
        for u in users
    ]


@router.get("")
async def list_users(
    current_user: Any = Depends(require_professor),
    search: str | None = None,
    role: str | None = None,
    page: int = Query(1, ge=1),
    pageSize: int = Query(20, ge=1, le=100),
) -> dict[str, Any]:
    assert_role(role, current_user)
    page, page_size, skip = normalize_pagination(page, pageSize)
    where: dict[str, Any] = {}
    if role:
        where["role"] = role
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    total = await db.user.count(where=where)
    users = await db.user.find_many(where=where, skip=skip, take=page_size, order={"createdAt": "desc"})
    return page_payload([serialize_user(user) for user in users], total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_user(payload: UserCreate, current_user: Any = Depends(require_admin)) -> dict[str, Any]:
    role = "MASTER_ADMIN" if is_master_email(payload.email) else payload.role
    assert_role(role, current_user)
    existing = await db.user.find_unique(where={"email": payload.email.lower()})
    if existing:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    is_master = role == "MASTER_ADMIN"
    user = await db.user.create(
        data={
            "email": payload.email.lower(),
            "name": payload.name,
            "passwordHash": hash_password(payload.password),
            "role": role,
            "authProvider": "LOCAL",
            "canManageQuestionnaire": is_master or payload.canManageQuestionnaire,
            "canManageCrafts": is_master or payload.canManageCrafts,
            "canManageWorkshops": is_master or payload.canManageWorkshops,
            "canReview": is_master or payload.canReview,
            "canViewProvenance": is_master or payload.canViewProvenance,
            # Dataset download is grantable by any admin (the whole route is admin-gated), unlike the
            # master-admin-only grants above — so it needs no extra permission assertion.
            "canDownloadDataset": is_master or payload.canDownloadDataset,
        }
    )
    # A brand-new cuid cannot already be cached, but every write to a User row invalidates without
    # exception — a rule with a documented exception is a rule the next person has to re-derive.
    invalidate_cached_user(user.id)
    return serialize_user(user)


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    current_user: Any = Depends(require_professor),
) -> dict[str, Any]:
    assert_role(payload.role, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    if not is_admin(current_user):
        # Professors manage the ladder, not accounts: they may promote/demote people below them
        # (up to their own tier, per assert_role + assert_can_manage_target) but everything else —
        # identity, passwords, privilege flags — stays admin-only.
        extra_fields = set(data) - {"role"}
        if extra_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Professors can only change a user's role",
            )
    if "email" in data:
        data["email"] = data["email"].lower()
    if "password" in data:
        data["passwordHash"] = hash_password(data.pop("password"))
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if user.id == current_user.id:
        # Self-service is limited to identity fields; nobody edits their own role or privileges.
        privileged_fields = set(data) - {"name", "email", "passwordHash"}
        if privileged_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You cannot change your own role or privileges",
            )
    else:
        assert_can_manage_target(current_user, user)
    assert_not_demoting_master(user, data.get("role"), current_user)
    if "email" in data and data["email"] != user.email:
        if is_master_email(data["email"]) and not is_master_admin(current_user):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Only the master admin can assign the master admin email",
            )
        clash = await db.user.find_unique(where={"email": data["email"]})
        if clash and clash.id != user_id:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already exists")
    if "email" in data and is_master_email(data["email"]):
        data["role"] = "MASTER_ADMIN"
    if data.get("role") == "MASTER_ADMIN":
        data["canManageQuestionnaire"] = True
        data["canManageCrafts"] = True
        data["canManageWorkshops"] = True
        data["canReview"] = True
        data["canViewProvenance"] = True
        data["canDownloadDataset"] = True
    updated = await db.user.update(where={"id": user_id}, data=data)
    # This is the promotion/demotion route: the cached identity now describes authority the user no
    # longer has (or has not been given yet), so it must not outlive the write by even one request.
    invalidate_cached_user(user_id)
    return serialize_user(updated)


#: What this account made, in the words the Settings > Users screen uses for them.
#:
#: Deliberately a NAMED list rather than a walk of the schema: it is read only to tell an admin
#: what is in the way, and a model missing from it can only UNDER-state the tally — the database
#: refuses the delete whether or not this list is complete, so drift here costs a vaguer message
#: and never a lost record.
_CREATOR_RELATIONS: tuple[tuple[str, str, str], ...] = (
    ("artisan", "createdById", "artisan record"),
    ("workshop", "createdById", "workshop"),
    ("productdocumentation", "createdById", "product record"),
    ("tooldocumentation", "createdById", "tool record"),
    ("process", "createdById", "process record"),
    ("mediafile", "uploadedById", "media file"),
    ("designworkshop", "createdById", "design workshop"),
    ("questionnaire", "ownerId", "questionnaire"),
    ("questionnaireformentry", "createdById", "questionnaire sitting"),
    ("reviewlog", "reviewerId", "review"),
)


async def _records_created_by(user_id: str) -> list[tuple[str, int]]:
    """``[(noun, count)]`` for everything this account made, biggest first, empties dropped."""
    from app.services.concurrency import gather_reads

    counts = await gather_reads(*(
        db_model.count(where={column: user_id})
        for db_model, column in (
            (getattr(db, model), column) for model, column, _noun in _CREATOR_RELATIONS
        )
    ))
    named = [
        (noun, count)
        for (_model, _column, noun), count in zip(_CREATOR_RELATIONS, counts, strict=True)
        if count
    ]
    return sorted(named, key=lambda pair: pair[1], reverse=True)


def _undeletable_detail(owned: list[tuple[str, int]]) -> str:
    """The 409's message: what is in the way, how much of it, and what to do instead.

    The count is here because it is the fact that decides the admin's next move — three records
    is a reassignment, four hundred is a deactivation — and an admin who is not told the number
    has to go and count it themselves.
    """
    if not owned:
        # The relation that refused is one this list does not name. Say so plainly rather than
        # inventing a number: the remedy is the same either way.
        return (
            "This account is referenced by records that are kept for research, so it cannot be "
            "deleted. Deactivate it instead, or ask a master admin to reassign what it owns."
        )
    parts = [f"{count} {noun}{'s' if count != 1 else ''}" for noun, count in owned[:3]]
    if len(owned) > 3:
        parts.append("and more")
    return (
        f"This account created {', '.join(parts)}. Those records are kept for research, so the "
        "account cannot be deleted. Deactivate it instead, or ask a master admin to reassign them."
    )


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(user_id: str, current_user: Any = Depends(require_admin)) -> None:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if is_master_email(user.email):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account cannot be deleted")
    if user.id == current_user.id:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="You cannot delete your own account")
    assert_can_manage_target(current_user, user)
    try:
        await db.user.delete(where={"id": user_id})
    except ForeignKeyViolationError as exc:
        # EVERY CREATOR RELATION ON User IS `onDelete: Restrict` — Artisan, Workshop, Product,
        # Tool, Media, Questionnaire, Process, ReviewLog, DesignWorkshop and the rest — because
        # research data must not disappear when the person who recorded it leaves. Postgres
        # therefore refuses this delete for ANY account that has ever created anything, and the
        # route had no except clause: the admin got "Something went wrong on the server. The
        # error has been logged." — which says nothing about the real situation and nothing they
        # can act on.
        #
        # This is the ORDINARY case, not an edge case. Any colleague who did any work at all is
        # undeletable, so the only accounts this endpoint could ever delete are the ones that
        # never did anything — which is precisely backwards from what an admin is trying to do
        # when a designer leaves the project.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=_undeletable_detail(await _records_created_by(user_id)),
        ) from exc
    # A deleted account must stop authenticating immediately, not when a TTL happens to expire.
    invalidate_cached_user(user_id)
