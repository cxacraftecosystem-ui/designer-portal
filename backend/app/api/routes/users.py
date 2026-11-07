import logging
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import Any, NamedTuple

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.encoders import jsonable_encoder
from prisma.errors import ForeignKeyViolationError, UniqueViolationError

from app.core.db import db
from app.core.deps import (
    can_provision_accounts,
    get_current_user,
    invalidate_cached_user,
    is_admin,
    is_master_admin,
    require_account_provisioner,
    require_admin,
    require_professor,
    role_rank,
    role_value,
)
from app.core.security import hash_password
from app.core.user_fields import HAS_PASSWORD_KEY, SECRET_USER_FIELDS
from app.schemas.users import UserCreate, UserUpdate
from app.services import (
    access_roster,
    credential_links,
    email_outbox,
    mailer,
    signin_role,
    two_factor,
)
from app.services.aadhaar import flags as aadhaar_flags
from app.services.account_provisioning import (
    DUPLICATE_EMAIL_DETAIL,
    GRANT_FLAGS,
    MASTER_EMAIL_DETAIL,
    NO_PASSWORD_TO_CHANGE_DETAIL,
    OWN_CREDENTIALS_DETAIL,
    PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL,
    assert_can_manage_target,
    assert_may_grant,
    assert_may_reset_credentials,
    assert_not_escaping_a_bar,
    assert_not_overturning_a_bar,
    assert_role,
    create_account_in_one_step,
    email_in_use,
    holds_a_temporary_password,
    is_master_address,
    is_master_email,
    requested_grants,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains, count_and_page, with_id_tiebreak
from app.services.text_format import by_count, count_noun

router = APIRouter(prefix="/users", tags=["users"])

IDENTITY_FIELDS = frozenset({"name", "email"})
CREDENTIAL_FIELDS = frozenset({"password", "mustChangePassword"})

AUDITED_FIELDS: tuple[str, ...] = (
    "canDownloadDataset",
    "canManageCrafts",
    "canManageQuestionnaire",
    "canManageWorkshops",
    "canReview",
    "canViewProvenance",
    "email",
    "mustChangePassword",
    "name",
    "password",
    "role",
)

OWN_EMAIL_DETAIL = "Ask another administrator to change your email address."

IDENTITY_NEEDS_PROVISIONER_DETAIL = (
    "Correcting a person's name, email address or password requires Ministry Admin access or "
    "above. Professors and the directorate change roles only."
)

logger = logging.getLogger(__name__)


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    for field in SECRET_USER_FIELDS:
        payload.pop(field, None)
    payload[HAS_PASSWORD_KEY] = getattr(user, "passwordHash", None) is not None
    return aadhaar_flags.hide_columns(payload)


def assert_not_demoting_master(
    target_user: Any, payload_role: str | None, current_user: Any
) -> None:
    if not is_master_address(target_user.email):
        return
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account is protected"
        )
    if payload_role and payload_role != "MASTER_ADMIN":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The master admin must keep the Master Admin role.",
        )


@router.get("/directory")
async def user_directory(
    current_user: Any = Depends(get_current_user),
    search: str | None = None,
) -> list[dict[str, Any]]:
    where: dict[str, Any] = {}
    if search:
        where["OR"] = [{"name": contains(search)}, {"email": contains(search)}]
    users = await db.user.find_many(where=where, order=with_id_tiebreak({"name": "asc"}), take=500)
    return [
        {
            "id": u.id,
            "name": u.name,
            "email": u.email,
            "role": str(getattr(u.role, "value", u.role)),
        }
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
    total, users = await count_and_page(
        db.user, where=where, skip=skip, take=page_size, order={"createdAt": "desc"}
    )
    return page_payload([serialize_user(user) for user in users], total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_user(
    payload: UserCreate, current_user: Any = Depends(require_account_provisioner)
) -> dict[str, Any]:
    created = await create_account_in_one_step(current_user, payload)
    answer = serialize_user(created.user)
    if created.temporary_password is not None:
        answer["temporaryPassword"] = created.temporary_password
    if created.password_link is not None:
        answer["passwordLink"] = created.password_link
    if created.password_link_error is not None:
        answer["passwordLinkError"] = created.password_link_error
    answer["rosterAdded"] = True
    answer["roleListsAdded"] = created.role_lists
    return answer


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    current_user: Any = Depends(require_professor),
) -> dict[str, Any]:
    assert_role(payload.role, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    sent = set(data)
    is_self = user_id == current_user.id
    if is_self and "email" in sent:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_EMAIL_DETAIL)
    if is_self and sent & CREDENTIAL_FIELDS:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
    if sent & (IDENTITY_FIELDS | CREDENTIAL_FIELDS) and not can_provision_accounts(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=IDENTITY_NEEDS_PROVISIONER_DETAIL
        )
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    grants = requested_grants(data)
    echoed: set[str] = set()
    if grants and not is_admin(current_user):
        assert_may_grant(current_user, grants, current=user)
        echoed = set(grants)
        for flag in echoed:
            data.pop(flag, None)
    if is_self:
        privileged_fields = set(data) - IDENTITY_FIELDS
        if privileged_fields:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="You cannot change your own role or privileges",
            )
    else:
        assert_can_manage_target(current_user, user)

    ends_sessions = False
    if "password" in data:
        data["passwordHash"] = hash_password(data.pop("password"))
        data["passwordIssuerKnown"] = True
        data.setdefault("mustChangePassword", True)
        ends_sessions = True
    if data.get("mustChangePassword") is True:
        if "passwordHash" not in data and user.passwordHash is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=NO_PASSWORD_TO_CHANGE_DETAIL,
            )
        if not user.mustChangePassword:
            ends_sessions = True

    assert_not_demoting_master(user, data.get("role"), current_user)
    if "email" in data:
        data["email"] = data["email"].lower()
    email_changed = "email" in data and data["email"] != user.email
    barred_on_arrival: str | None = None
    empanelment_ended_on_arrival = False
    barred_on_departure: str | None = None
    empanelment_ended_on_departure = False
    if email_changed:
        if is_master_email(data["email"]) and not is_master_admin(current_user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_EMAIL_DETAIL)
        if await email_in_use(data["email"], except_id=user.id):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL)
        role_after = data.get("role") or role_value(user)
        barred_on_departure, empanelment_ended_on_departure = await assert_not_escaping_a_bar(
            current_user, user.email, data["email"], role=role_after
        )
        barred_on_arrival, empanelment_ended_on_arrival = await assert_not_overturning_a_bar(
            current_user, data["email"], role=role_after, moving=True
        )
    if "email" in data and is_master_address(data["email"]):
        data["role"] = "MASTER_ADMIN"
    if data.get("role") == "MASTER_ADMIN":
        data.update(dict.fromkeys(GRANT_FLAGS, True))
    promoted = "role" in data and role_rank(data["role"]) > role_rank(user)
    if promoted and holds_a_temporary_password(user) and "passwordHash" not in data:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL,
        )
    now = datetime.now(UTC)
    if "passwordHash" in data:
        data["passwordSetAt"] = now
    if ends_sessions:
        data["sessionsValidFrom"] = now
    role_before = role_value(user)
    try:
        updated = await db.user.update(where={"id": user_id}, data=data)
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL
        ) from exc
    invalidate_cached_user(user_id)
    if "role" in data:
        signin_role.audit_role_change(
            user_id=user_id, actor_id=current_user.id, before=role_before, after=updated.role, via="users"
        )
    links_withdrawn = await credential_links.revoke_outstanding(user_id) if promoted else 0
    moved = access_roster.EmailMove()
    if email_changed:
        moved = await access_roster.follow_email_change(
            user.email, data["email"], actor_id=current_user.id
        )
    changed = sent - echoed
    notes = [
        f"moved off an address the allow-list held as {barred_on_departure}"
        if barred_on_departure
        else "",
        "moved off an address whose designer empanelment was ended"
        if empanelment_ended_on_departure
        else "",
        f"moved onto an address the allow-list held as {barred_on_arrival}"
        if barred_on_arrival
        else "",
        "the new address's designer empanelment is suspended, so this designer cannot sign in "
        "until it is restored"
        if empanelment_ended_on_arrival
        else "",
        f"the account's {moved.carried_bar} bar went with it and its old address stays barred"
        if moved.carried_bar
        else "",
        "the ended designer empanelment moved with it" if moved.carried_ended_empanelment else "",
        f"promoted, so {count_noun(links_withdrawn, 'outstanding password link')} "
        f"{by_count(links_withdrawn, 'was', 'were')} withdrawn"
        if links_withdrawn
        else "",
    ]
    logger.info(
        "users: %s updated account %s (fields=%s, mustChangePassword=%s, sessionsEnded=%s)%s",
        current_user.id,
        user_id,
        ",".join(field for field in AUDITED_FIELDS if field in changed) or "-",
        "required" if updated.mustChangePassword else "not required",
        ends_sessions,
        "".join(f"; {note}" for note in notes if note),
    )
    if "passwordHash" in data:
        await email_outbox.notify_password_event(updated, mailer.PASSWORD_CHANGED, by="administrator")
        if email_changed and user.email:
            await email_outbox.notify_password_event(
                updated, mailer.PASSWORD_CHANGED, by="administrator", to_address=user.email
            )
    return serialize_user(updated)


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
    ("sanctionorder", "createdById", "sanction order"),
)


_NAMED_ON_RELATIONS: tuple[tuple[str, str, str], ...] = (
    ("sanctionorderdesigner", "designerUserId", "sanction order"),
)


class _Tally(NamedTuple):
    named: list[tuple[str, int]]
    uncounted: list[str]


async def _count_relation(user_id: str, model: str, column: str) -> int | None:
    delegate = getattr(db, model, None)
    if delegate is None:
        logger.error(
            "delete_user: the generated Prisma client carries no model %r, so the 409 for user %s "
            "cannot say how many %r rows are in the way. The client is behind schema.prisma — "
            "regenerate it with backend/scripts/regenerate-client.md, NOT with a bare "
            "`prisma generate`, which exits 0 without doing anything on Windows. Answering a less "
            "specific 409 rather than a 500; see _count_relation.",
            model,
            user_id,
            model,
        )
        return None
    try:
        return await delegate.count(where={column: user_id})
    except Exception:
        logger.exception(
            "delete_user: counting %s.%s for user %s failed, so the 409 cannot say how many are in "
            "the way. Usually a migration that has not been applied to this database. Answering a "
            "less specific 409 rather than a 500; see _count_relation.",
            model,
            column,
            user_id,
        )
        return None


async def _counts_over(user_id: str, relations: tuple[tuple[str, str, str], ...]) -> _Tally:
    from app.services.concurrency import gather_reads

    counts = await gather_reads(
        *(_count_relation(user_id, model, column) for model, column, _noun in relations)
    )
    named: list[tuple[str, int]] = []
    uncounted: list[str] = []
    for (_model, _column, noun), count in zip(relations, counts, strict=True):
        if count is None:
            uncounted.append(noun)
        elif count:
            named.append((noun, count))
    return _Tally(sorted(named, key=lambda pair: pair[1], reverse=True), uncounted)


async def _records_created_by(user_id: str) -> _Tally:
    return await _counts_over(user_id, _CREATOR_RELATIONS)


async def _records_naming(user_id: str) -> _Tally:
    return await _counts_over(user_id, _NAMED_ON_RELATIONS)


def _undeletable_detail(
    owned: list[tuple[str, int]],
    named_on: list[tuple[str, int]] | None = None,
    *,
    uncounted: Sequence[str] = (),
) -> str:
    named_on = named_on or []
    if not owned and not named_on:
        return (
            "This account is referenced by records that are kept for research, so it cannot be "
            "deleted. Deactivate it instead, or ask a master admin to reassign what it owns."
        )
    sentences: list[str] = []
    if owned:
        parts = [f"{count} {noun}{'s' if count != 1 else ''}" for noun, count in owned[:3]]
        if len(owned) > 3:
            parts.append("and more")
        sentences.append(
            f"This account created {', '.join(parts)}. Those records are kept for research, so the "
            "account cannot be deleted. Deactivate it instead, or ask a master admin to reassign "
            "them."
        )
    for noun, count in named_on:
        sentences.append(
            f"It is also named on {count} {noun}{'s' if count != 1 else ''}, which record who the "
            "ministry issued them to."
            if owned
            else (
                f"This account is named on {count} {noun}{'s' if count != 1 else ''}, which record "
                "who the ministry issued them to, so it cannot be deleted. Deactivate it instead."
            )
        )
    if uncounted:
        nouns = list(dict.fromkeys(f"{noun}s" for noun in uncounted))
        listed = nouns[0] if len(nouns) == 1 else f"{', '.join(nouns[:-1])} and {nouns[-1]}"
        sentences.append(f"This list may be incomplete: {listed} could not be counted.")
    return " ".join(sentences)


@router.delete("/{user_id}/two-factor")
async def reset_two_factor(
    user_id: str, current_user: Any = Depends(require_account_provisioner)
) -> dict[str, Any]:
    target = await db.user.find_unique(where={"id": user_id})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    assert_may_reset_credentials(current_user, target)
    if not target.twoFactorEnabled:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Two-step verification is already off for this account. Reload the list.",
        )
    destroyed = await two_factor.reset_by_administrator(target)
    invalidate_cached_user(target.id)
    logger.info(
        "users: %s turned two-step verification off for account %s (%d recovery code(s) destroyed, "
        "sessions ended)",
        current_user.id,
        target.id,
        destroyed,
    )
    updated = await db.user.find_unique(where={"id": target.id})
    return serialize_user(updated)


OWN_SESSIONS_DETAIL = (
    "Use Sign out on this device instead; your own sessions end when you change your password."
)


@router.post("/{user_id}/sign-out-everywhere")
async def sign_out_everywhere(
    user_id: str, current_user: Any = Depends(require_account_provisioner)
) -> dict[str, Any]:
    target = await db.user.find_unique(where={"id": user_id})
    if target is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if target.id == current_user.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_SESSIONS_DETAIL)
    assert_may_reset_credentials(current_user, target)
    updated = await db.user.update(
        where={"id": target.id}, data={"sessionsValidFrom": datetime.now(UTC)}
    )
    invalidate_cached_user(target.id)
    logger.info("users: %s signed account %s out of every device", current_user.id, target.id)
    return serialize_user(updated)


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(user_id: str, current_user: Any = Depends(require_admin)) -> None:
    user = await db.user.find_unique(where={"id": user_id})
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User not found")
    if is_master_address(user.email):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="The master admin account cannot be deleted",
        )
    if user.id == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="You cannot delete your own account",
        )
    assert_can_manage_target(current_user, user)
    issued_links = await credential_links.outstanding_issued_by(user_id)
    try:
        await db.user.delete(where={"id": user_id})
    except ForeignKeyViolationError as exc:
        owned = await _records_created_by(user_id)
        named_on = await _records_naming(user_id)
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=_undeletable_detail(
                owned.named,
                named_on.named,
                uncounted=owned.uncounted + named_on.uncounted,
            ),
        ) from exc
    await credential_links.revoke_ids(issued_links)
    invalidate_cached_user(user_id)
