from datetime import UTC, datetime
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
    # The ROLE, spelled the one way. ``target_user.role`` is a Prisma enum member on a live row and
    # a plain string on anything hand-built, and the peer guard in
    # :func:`assert_can_manage_target` compares it against a literal — an equality test that
    # silently answers False for the enum, i.e. lets the refusal through, which is the direction
    # that loses an account. ``role_value`` collapses both spellings; do not inline
    # ``target_user.role`` here.
    role_value,
)
from app.core.security import hash_password
from app.schemas.users import UserCreate, UserUpdate
from app.services import access_roster

# THE ONE IMPLEMENTATION OF "AN ADMITTED DESIGNER IS AN EMPANELLED DESIGNER", imported from the
# service rather than from ``routes/access``'s private helper: that module imports ``assert_role``
# from this one, so reaching the other way would close an import cycle. See ``create_user``.
from app.services.designers import ensure_empanelled
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains, count_and_page, with_id_tiebreak

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
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Invalid user role"
        )
    if role == "MASTER_ADMIN" and not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the master admin can grant master admin",
        )
    if ROLE_RANK[role] > role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only assign roles at or below your own tier",
        )


#: Refusing a master admin an action on ANOTHER master admin. Named so the two routes and the test
#: assert the same sentence, and phrased to say what the reader can actually do about it.
_MASTER_PEER_DETAIL = (
    "Master admin accounts are peers: no master admin may change or remove another. "
    "Demote the account from an environment with database access, or leave it in place."
)


def assert_can_manage_target(current_user: Any, target_user: Any) -> None:
    """Nobody manages a peer, INCLUDING the master admin; everyone else manages strictly lower
    tiers. This blocks one admin from silently rewriting another admin's account.

    **THE MASTER-ADMIN CLAUSE USED TO BE ``if is_master_admin(current_user): return`` WITH NO PEER
    TEST**, and that made this the stricter of two mirrors' looser half. ``canManageUser`` in
    ``frontend/lib/permissions.ts`` returns ``target.role !== "MASTER_ADMIN" || target.id ===
    user?.id``, and ``docs/PERMISSIONS.md`` §2 states that rule as the system's — so both browsers
    render a second master-admin row with no controls on it while ``PATCH /users/{id}`` and
    ``DELETE /users/{id}`` accepted exactly that target. An operator who promoted a deputy for a
    handover read "protected" off the screen and could still demote or delete them with one curl.
    Worse, the only peer protection that existed keyed on ``MASTER_ADMIN_EMAIL``
    (:func:`assert_not_demoting_master`), so protection followed one address in the environment
    rather than the privilege — the deputy could demote or delete every master admin except the
    configured one.

    The mirrors are made to agree here, on the SERVER side, because this direction can only refuse:
    the browsers already offered nothing on these rows, so no shipped flow loses a control, and a
    guard that turns out to be too strict is reverted without having deleted anybody's account.

    **THE COST, SAID OUT LOUD: promoting somebody to MASTER_ADMIN is now a one-way door through the
    API.** They cannot be demoted by a peer (this guard) and cannot demote themselves
    (``update_user`` refuses privilege changes on one's own row), which is the same standing the
    configured master-admin address has always had. If a reversible deputy is wanted, the answer is
    a lower tier, not a hole in this rule.
    """
    if is_master_admin(current_user):
        # The self-exception mirrors ``canManageUser``'s ``target.id === user?.id``. Both callers
        # already branch on self before reaching here — ``update_user`` down the identity-only
        # path, ``delete_user`` with its 422 — so this arm is unreachable today and is written
        # anyway, because a predicate that answers a different question from the one the UI asks
        # is how these two got out of step in the first place.
        if role_value(target_user) == "MASTER_ADMIN" and target_user.id != current_user.id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_PEER_DETAIL)
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


def assert_not_demoting_master(
    target_user: Any, payload_role: str | None, current_user: Any
) -> None:
    if not is_master_email(target_user.email):
        return
    if not is_master_admin(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail="The master admin account is protected"
        )
    if payload_role and payload_role != "MASTER_ADMIN":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="The master admin must keep MASTER_ADMIN role",
        )


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
    # ``id`` is the TIEBREAKER on a CAPPED read, and on this table it is load-bearing: display names
    # are not unique (the picker note in tasks.py counts 204 accounts sharing one), so with ``name``
    # alone which rows fall inside the 500 is Postgres's choice and can differ between two identical
    # requests — "who is missing" changing on refresh, which no search term can be relied on to reach.
    # ``/designers/directory`` already spells it this way for the same reason.
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
    # ``count_and_page`` rather than the two awaits this used to be: the count and the page answer
    # different questions about the same WHERE and neither reads the other, so in series they cost
    # one whole cross-region round trip for nothing. The helper applies ``with_id_tiebreak`` on the
    # way through, so the ordering here is character-for-character the one this route already had —
    # offset paging over ``createdAt`` alone repeats rows and skips others whenever two accounts
    # share a creation instant, and nothing on this table stops that: ``createdAt`` is not unique
    # and carries no index. See ``records.with_id_tiebreak`` for the whole argument.
    total, users = await count_and_page(
        db.user, where=where, skip=skip, take=page_size, order={"createdAt": "desc"}
    )
    return page_payload([serialize_user(user) for user in users], total, page, page_size)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_user(
    payload: UserCreate, current_user: Any = Depends(require_admin)
) -> dict[str, Any]:
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
            # ── THE FIRST-LOGIN PASSWORD ─────────────────────────────────────────────────
            #
            # An admin typing a password for somebody else is a shared secret by construction:
            # it was chosen by one person, typed into a form, and read out or messaged to
            # another. `mustChangePassword` is what makes it temporary — the account signs in
            # with it and both clients then send the person to the change-password screen and
            # nowhere else.
            #
            # IT REPORTS AND DOES NOT REFUSE (see the column's own comment in schema.prisma):
            # the only route that can change a password needs a bearer token, so refusing the
            # sign-in would leave the account permanently unable to comply. It is the same
            # decision the usage-consent gate took, for the same reason.
            "mustChangePassword": True,
            # Stamped so that "has never had a password" stays distinguishable from "signs in
            # with Google" — the whole reason this column exists. `datetime.now(UTC)` rather
            # than letting it default, because there is no default: a column that is NULL for
            # an account that demonstrably has a hash would be worse than not having it.
            "passwordSetAt": datetime.now(UTC),
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
    # AN ADMIN CREATING AN ACCOUNT IS AN ADMIN APPROVING IT. Without this the platform allow-list
    # would refuse the account the moment it was made: the admin would hand somebody a password,
    # watch them be told they are awaiting approval, and then have to approve them in a second
    # screen — for a request they themselves caused. The gate fails closed, deliberately (see
    # `auth.assert_access_admits`), so every path that mints an account has to admit it, and this is
    # the only other one besides Google sign-in.
    admitted = await access_roster.admit(
        user.email,
        admit_role=role,
        actor_id=current_user.id,
        full_name=user.name,
        note=f"Admitted with the account, created here by {current_user.email}.",
    )
    # ── AND AN ADMIN CREATING A DESIGNER HAS EMPANELLED THEM, 2026-09-03 ────────────────────────
    #
    # **THE FOURTH DOORWAY.** Three paths already treat "admitted as a DESIGNER" and "empanelled" as
    # one act — ``auth.login`` on the way in, and ``access._empanel_an_admitted_designer`` from the
    # approval and the roster edit — and this one, which is the path an admin uses when they have the
    # person in front of them, did not. It called ``admit`` and stopped. The consequence is the
    # incident the whole feature exists for, reached through the door most likely to be used: the
    # admin types somebody in AS A DESIGNER, ``/admin/designers`` shows nothing, and the person
    # themselves reads *"Your designer access has been suspended"* at the sign-in page about an
    # empanelment nobody ever granted — until their first sign-in silently derives one, at which
    # point the row exists but says it was derived rather than granted by the admin who granted it.
    #
    # THE TWO CONDITIONS ARE ``_empanel_an_admitted_designer``'S, ASKED OF THE STORED ROW, and they
    # are re-spelled here rather than imported for one reason only: ``routes/access`` imports
    # ``assert_role`` from THIS module (see the note at that import), so calling back into it would
    # close an import cycle. If a third caller ever needs this pair, the function moves to
    # ``app/services`` — it does not get copied a third time.
    #
    #   * ACTIVE, not merely "there is a row". ``admit`` returns an ACTIVE row on every path today,
    #     so this is belt-and-braces — it is here so it stays true if that ever changes, exactly as
    #     the sign-in path's ``access_roster.admits`` test is.
    #   * ``role_of(admitted)`` and not ``role``, because the roster row is what the other three
    #     paths read and a row that already carried a role is the row the gate will consult. Asking
    #     the stored row is the one formulation that cannot drift from what was actually written.
    #
    # THE STORED ADDRESS AND NOT ``user.email``. The roster stores the canonical mailbox and
    # ``User.email`` is deliberately not canonicalised, so for a Gmail alias the two differ — and the
    # empanelment has to land on the key the OTHER roster and the sign-in gate are keyed on.
    #
    # ``actor_id`` IS THE ADMIN, unlike the sign-in path's ``None``: an administrator really did take
    # this action, and ``addedById`` is how ``/admin/designers`` says who. Nothing here revives a
    # suspended empanelment — ``ensure_empanelled`` only ever creates, which is the one rule in that
    # function that must not be got wrong.
    if (
        access_roster.status_of(admitted) == access_roster.ACTIVE
        and access_roster.role_of(admitted) == "DESIGNER"
    ):
        await ensure_empanelled(admitted.email, actor_id=current_user.id)
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
        data["passwordSetAt"] = datetime.now(UTC)
        # WHOSE PASSWORD IT IS DECIDES WHETHER IT MUST BE CHANGED, and the branch is below
        # rather than here because `user` has not been loaded yet at this line. See
        # `_password_was_set_by_somebody_else` further down.
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
        if "passwordHash" in data:
            # AN ADMIN TYPED THIS PASSWORD FOR SOMEBODY ELSE, so it is a shared secret exactly
            # as it is at account creation, and it is temporary for the same reason. The
            # self-service branch above deliberately does NOT set this: a person who changed
            # their own password has already chosen one.
            data["mustChangePassword"] = True
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
    if "email" in data and data["email"] != user.email:
        # THE ALLOW-LIST IS KEYED BY EMAIL. An admin correcting a typo in an address would otherwise
        # lock the account out at its next sign-in — the new address has no row, and the gate reads
        # a missing row as "never approved". See `access_roster.follow_email_change`.
        await access_roster.follow_email_change(user.email, data["email"], actor_id=current_user.id)
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

    counts = await gather_reads(
        *(
            db_model.count(where={column: user_id})
            for db_model, column in (
                (getattr(db, model), column) for model, column, _noun in _CREATOR_RELATIONS
            )
        )
    )
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
