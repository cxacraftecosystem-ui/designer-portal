import logging
from collections.abc import Sequence
from datetime import UTC, datetime
from typing import Any, NamedTuple

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

# WHERE THE DRIFT IN :func:`_count_relation` GETS SHOUTED ABOUT. The 409 body is deliberately quiet
# about it — an admin can do nothing with "the generated client has no model sanctionorderdesigner"
# — so the log line is the one place the fact is stated in the words a developer needs. See
# :func:`_count_relation` for why it is a log and not an exception.
logger = logging.getLogger(__name__)


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
    # The OFFICER's half of the sanction register. `SanctionOrder.createdById` is `onDelete:
    # Restrict`, so an officer who has recorded one order is as undeletable as anyone who has
    # created a record — and without this row the admin would get the generic "referenced by records
    # kept for research" sentence with no number and no noun.
    ("sanctionorder", "createdById", "sanction order"),
)


#: Rows this account is NAMED ON without having created them — a SECOND list, and the reason it is
#: second is THE VERB.
#:
#: :func:`_undeletable_detail`'s sentence is "This account created …", and a designer named on a
#: sanction order created nothing: the ministry officer did. Folding the relation into
#: :data:`_CREATOR_RELATIONS` would print "This account created 1 sanction order", which is false
#: about the one account it is describing, on the one screen where an admin is deciding what to do
#: with somebody's record.
#:
#: A second sentence is the honest shape, and it is cheap. `SanctionOrder.designerUserId` is
#: `onDelete: Restrict` exactly as `createdById` is, so BOTH are reachable reasons for the same 409,
#: and an admin told only about the half that happens to be authorship has been sent on the first of
#: two trips.
#:
#: ── THE ROW THAT WAS OWED LANDED IN 0.0.12, AS A REPLACEMENT ──────────────────────────────────
#:
#: This tuple named ``("sanctionorder", "designerUserId", "sanction order")`` until the
#: multi-designer register shipped. It now names the JOIN TABLE, and the swap was a REPLACEMENT
#: rather than an addition — deliberately, and the reason is arithmetic rather than taste.
#:
#: ``SanctionOrderDesigner`` holds a row for EVERY designer an order names INCLUDING THE LEAD, which
#: is what ``named_designer_team`` returns (``[lead, ...rest]``) and what the sanction create writes.
#: So the lead has a row in BOTH tables. Counting both would tell an admin that a designer who leads
#: exactly one sanction order is "named on 2 sanction orders" — a number that is wrong on the one
#: screen where somebody is deciding what to do with a colleague's record, and wrong in the
#: direction that makes the account look busier than it is.
#:
#: Counting the JOIN and not the scalar is also the only one of the two that is now COMPLETE. A
#: co-designer — the second and third names on an order, which is the whole point of the release —
#: has no row on ``SanctionOrder`` at all, so the old tuple would have answered "0" for them and the
#: 409 would have fallen through to the no-number branch ("referenced by records that are kept for
#: research"), which is precisely the vagueness ``named_on`` was added to remove. Both columns are
#: ``onDelete: Restrict``, so both really do refuse the delete; only one of them can say how many.
#:
#: ``SanctionOrder.designerUserId`` KEEPS ITS ``Restrict`` and is NOT listed here. It is the same
#: fact about the same account, reached through a second column, and naming it twice is the
#: double-count above. If the lead scalars are ever retired in favour of the join alone, nothing on
#: this line changes — which is one more reason it is the join that is named.
#:
#: WHY THIS COULD NOT BE SWAPPED EARLIER, recorded because the constraint is invisible from here and
#: the next reader may be tempted to move a name in this tuple speculatively. :func:`_counts_over`
#: resolves each model BY NAME at REQUEST time, and the generated Prisma client declares its models
#: in ``__slots__`` with no ``__getattr__``, so a name the schema does not carry does not return an
#: empty count. Schema model, migration and this line therefore land together, and they did:
#: ``prisma/migrations/20260916150000_sanction_order_designers``.
#:
#: ── AND IT LANDED TOGETHER AND STILL BROKE, WHICH IS WHY THAT IS NO LONGER THE WHOLE STORY ────
#:
#: The paragraph above used to end "…raises ``AttributeError`` rather than returning an empty count
#: — inside ``delete_user``'s ``except ForeignKeyViolationError`` handler, turning the informative
#: 409 into 'Something went wrong on the server' for every account that has created or been named on
#: anything." It was exactly right about the mechanism and it happened anyway, on 2026-09-16, on a
#: machine where all three parts of this change WERE in the working tree: the model was in
#: schema.prisma, the migration was in ``prisma/migrations``, this line named the join table — and
#: ``tests/test_user_deletion.py::test_removing_a_colleague_who_did_work_says_what_is_in_the_way``
#: answered ``500`` with ``'Prisma' object has no attribute 'sanctionorderdesigner'``, because
#: ``prisma generate`` had not been re-run and the migration had not been applied to the local
#: database. Landing the three together is a discipline about a COMMIT; the client and the database
#: are STATE, and no ordering of edits can make a checkout's generated artefacts correct.
#:
#: So the constraint this paragraph describes has been removed rather than documented harder:
#: :func:`_count_relation` now degrades a relation it cannot read to "not counted" instead of
#: raising, and the argument for that is written there. This tuple is still a place to be careful —
#: a wrong name here silently under-states the tally — but a wrong name here can no longer take the
#: endpoint out.
_NAMED_ON_RELATIONS: tuple[tuple[str, str, str], ...] = (
    ("sanctionorderdesigner", "designerUserId", "sanction order"),
)


class _Tally(NamedTuple):
    """What :func:`_counts_over` could read, and what it could not.

    Two fields rather than one list because they answer different questions and a caller must not
    be able to confuse them: ``named`` is "these are in the way, this many of them", ``uncounted``
    is "and I could not look here at all". Collapsing the second into a zero in the first would be
    a lie of exactly the kind :data:`_NAMED_ON_RELATIONS` exists to stop telling.
    """

    #: ``[(noun, count)]``, biggest first, zeroes dropped — what the message can put a number on.
    named: list[tuple[str, int]]
    #: Nouns whose relation could not be read at all. Not "zero of these": "unknown".
    uncounted: list[str]


async def _count_relation(user_id: str, model: str, column: str) -> int | None:
    """``count(where={column: user_id})`` for one relation, or ``None`` if it could not be read.

    ══ A MISSING RELATION MUST NOT BE ABLE TO TURN THE 409 INTO A 500 ═════════════════════════

    THE 409 IS ALREADY EARNED BEFORE THIS FUNCTION RUNS. Postgres refused ``DELETE FROM "User"``;
    that refusal is a fact, established, in hand, and the ONLY reason ``delete_user`` is in an
    ``except`` block at all. Everything counted here is DECORATION on a verdict already reached —
    it makes the 409 more useful, it cannot make it more correct. So a failure to decorate must
    change the message and must not change the status. The old code let it change the status.

    WHAT THE OLD CODE DID. ``getattr(db, model)`` at REQUEST time, no default, inside
    ``delete_user``'s ``except ForeignKeyViolationError`` handler. The generated Prisma client
    declares its models in ``__slots__`` with no ``__getattr__``, so a name the client does not
    carry raised ``AttributeError`` straight out of the handler — and the admin got "Something
    went wrong on the server. The error has been logged.", which is the exact sentence the handler
    was written to abolish. The handler defeated its own purpose, and it did so only for accounts
    that HAD created something, i.e. only when an admin actually needed it.

    THIS IS NOT HYPOTHETICAL AND IT IS NOT A ONE-OFF. It happened on 2026-09-16 with a correct
    schema, a correct migration and a correct tuple, because ``prisma generate`` had not been
    re-run and the migration had not been applied. Every one of these produces it again: a stale
    generated client in any checkout or image; a model renamed in ``schema.prisma`` before this
    file is updated; a migration written but not deployed to the environment serving the request;
    a partially-applied migration where the table exists and the column does not. None of those is
    exotic, all of them are recoverable, and not one of them is a reason to take an administrator's
    endpoint out.

    AND ON WINDOWS THE DRIFT CANNOT EVEN BE REPAIRED BY THE OBVIOUS COMMAND, WHICH IS WHAT SETTLED
    THIS. ``backend/scripts/regenerate-client.md`` records it in full, diagnosed 2026-09-14:
    ``python -m prisma generate`` there dies with ``Error: spawn prisma-client-py ENOENT`` because
    the Node CLI spawns the provider by the bare name with no ``shell: true``, so ``PATHEXT`` never
    applies and ``prisma-client-py.EXE`` is never tried — putting the venv on ``PATH`` does not help,
    because the name is wrong rather than the directory. **AND THE COMMAND EXITS 0.** Re-verified on
    2026-09-16, both with ``backend/.venv/Scripts`` prepended to ``PATH`` and without: the same
    error on stdout, exit status zero both times, the generated client untouched. So on the platform
    this repository is developed on, the one command that keeps the client in step with the schema
    fails silently and reports success, and the supported repair is a Docker round trip through
    Linux that copies twelve generated modules — plus ``site-packages/prisma/schema.prisma``, which
    that runbook calls "the thirteenth file, which is not a .py and is the one that bites" — back
    into the venv by hand. A client that has drifted behind ``schema.prisma`` is therefore not an
    unlucky state somebody has to blunder into; it is the DEFAULT outcome of doing the obvious thing,
    and the repair is long enough to be postponed. Code downstream of that cannot treat the client's
    contents as a guarantee.

    THE FILE ALREADY DECIDED THIS, FOR THE OTHER HALF OF THE SAME DRIFT. :data:`_CREATOR_RELATIONS`
    says in as many words: "a model missing from it can only UNDER-state the tally — the database
    refuses the delete whether or not this list is complete, so drift here costs a vaguer message
    and never a lost record." A model OMITTED from the tuple costs a vaguer message; a model NAMED
    in the tuple that the client cannot resolve used to cost the whole endpoint. Same class of
    drift, same harmlessness to the data, two wildly different outcomes — and the difference was
    only whether the stale name happened to be present or absent. This function makes both cost a
    vaguer message.

    ══ THE ALTERNATIVE, WHICH IS TO LET IT RAISE, AND WHY IT LOSES ════════════════════════════

    The case for strictness is real and worth stating: a swallowed failure is a silent failure, and
    a typo in :data:`_CREATOR_RELATIONS` would now under-report for ever with nobody the wiser.
    Loud-and-early beats quiet-and-wrong, usually.

    It loses HERE on WHEN it is loud. This code path runs only when an admin has already been
    refused a deletion — the worst possible moment to replace the one sentence that would have
    helped them with a stack trace they cannot see. The typo the strict form catches is caught just
    as well by ``tests/test_sanction_order_undeletable.py``'s assertions on the tuples' contents, at
    author time, for free, in a place where being loud costs nothing; the drift the strict form
    catches is a *deployment* fact that no amount of strictness in this file can prevent, only
    punish, and punish the wrong person. And it is not silent: every branch below logs at ERROR
    with the model name, so the fact reaches the people who can act on it through the channel built
    for facts developers need, instead of through an admin's error toast.

    ``None`` RATHER THAN ``0``. A relation that could not be read is not a relation with no rows.
    Returning 0 would let the caller say "this account created 1 questionnaire" and stop, when
    there may be four hundred sanction orders it could not see — sending the admin to reassign one
    record and meet the same refusal again, which is the two-trips failure :data:`_NAMED_ON_RELATIONS`
    was added to remove. ``None`` keeps "unknown" and "none" apart all the way to the sentence.

    TWO BRANCHES, TWO LOG LINES, BECAUSE THEY HAVE DIFFERENT FIXES. A delegate the client does not
    carry means ``prisma generate``; a count that raises means the migration, the column or the
    connection. A single message covering both would name neither.
    """
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
        # Deliberately every exception and not a named Prisma error. What is being defended is the
        # STATUS CODE of a verdict Postgres has already returned, and the set of ways a count can
        # fail — table absent because a migration is pending, column absent because one was applied
        # in part, the pool exhausted, the connection dropped — is not a set this file can enumerate
        # correctly and has no business trying to. `CancelledError` derives from `BaseException`, so
        # a cancelled request still cancels rather than being logged as drift.
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
    """What is in the way over one relation list, biggest first, empties dropped.

    Each relation is counted inside :func:`_count_relation`, which never raises, so nothing here
    has to ask ``gather_reads`` for ``return_exceptions`` — a change to a shared service this route
    has no standing to make, and a worse shape anyway: a bare ``asyncio.gather`` that propagates
    leaves its siblings' results unretrieved, whereas a coroutine that handles its own failure
    leaves the gather with nothing to propagate.
    """
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
    """What this account made, biggest first, empties dropped — plus what could not be read."""
    return await _counts_over(user_id, _CREATOR_RELATIONS)


async def _records_naming(user_id: str) -> _Tally:
    """What NAMES this account without having been made by it, plus what could not be read."""
    return await _counts_over(user_id, _NAMED_ON_RELATIONS)


def _undeletable_detail(
    owned: list[tuple[str, int]],
    named_on: list[tuple[str, int]] | None = None,
    *,
    uncounted: Sequence[str] = (),
) -> str:
    """The 409's message: what is in the way, how much of it, and what to do instead.

    The count is here because it is the fact that decides the admin's next move — three records
    is a reassignment, four hundred is a deactivation — and an admin who is not told the number
    has to go and count it themselves.

    **TWO SENTENCES, BECAUSE THERE ARE TWO VERBS AND ONLY ONE OF THEM IS AUTHORSHIP.** "This account
    created …" is true of everything in :data:`_CREATOR_RELATIONS` and false of everything in
    :data:`_NAMED_ON_RELATIONS`: a designer named on a sanction order created nothing, the ministry
    officer did. Both relations are `onDelete: Restrict`, so both are reachable reasons for the same
    409 — and an admin told only about the half that happens to be authorship has been sent on the
    first of two trips.

    ``named_on`` DEFAULTS TO None RATHER THAN TO ``[]`` so that no existing caller changes meaning
    by omission, and the generic branch below is guarded on BOTH lists: a designer who has created
    nothing and is named on one order must not be answered with "referenced by records that are kept
    for research" — no number, no noun — which is the exact failure this parameter exists to remove.

    ``uncounted`` IS THE NOUNS THAT COULD NOT BE READ AT ALL — see :func:`_count_relation`, which
    now degrades a relation it cannot resolve instead of raising through the handler. It earns its
    sentence by the SAME argument ``named_on`` earns its own: an admin told about only part of what
    is in the way "has been sent on the first of two trips". They reassign the one questionnaire the
    message named, ask again, are refused again, and have learnt nothing — unless the message admits
    its list was short. One sentence, only ever present in a drifted deployment, and it changes what
    they do next: stop hunting, escalate.

    IT NAMES THE NOUN AND ASSERTS NOTHING ABOUT THE COUNT, which is the only honest shape available.
    "It is also named on sanction orders" would be a claim that there ARE some, and in the 2026-09-16
    failure there were none — the departing designer owned one questionnaire and nothing else, so
    that sentence would have sent an admin looking through the sanction register for a row that does
    not exist. "Sanction orders could not be counted" is true either way.

    THE WORDING IS THE PRODUCT'S OWN, not a phrase invented here. ``frontend/app/(protected)/
    activity/page.tsx`` already tells a reader "Some records could not be loaded — the lists below
    may be incomplete." when part of a fan-out read fails, which is the same situation with the same
    remedy (none; the list is short and you are being told so). Saying it a second way on a second
    screen would make two sentences a user has to learn instead of one.

    KEYWORD-ONLY, AND DEFAULTING TO ``()``. ``tests/test_sanction_order_undeletable.py`` calls this
    function positionally with two lists and is the specification for its wording; a third positional
    would put a new argument next to ``named_on``, where a mistaken call site would read as valid.
    The default makes every existing caller and every existing assertion mean exactly what it did.

    NOT APPENDED TO THE GENERIC BRANCH BELOW. When both lists are empty the message is already "this
    account is referenced by records that are kept for research" — a sentence that names no number,
    no noun and no relation, and offers the same remedy. "This list may be incomplete" added to a
    message that presents no list would be noise dressed as information.
    """
    named_on = named_on or []
    if not owned and not named_on:
        # The relation that refused is one neither list names. Say so plainly rather than inventing
        # a number: the remedy is the same either way.
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
        # De-duplicated, order preserved: both tuples spell the sanction register "sanction order",
        # so a stale client that resolves neither would otherwise say the word twice in one sentence.
        nouns = list(dict.fromkeys(f"{noun}s" for noun in uncounted))
        listed = nouns[0] if len(nouns) == 1 else f"{', '.join(nouns[:-1])} and {nouns[-1]}"
        sentences.append(f"This list may be incomplete: {listed} could not be counted.")
    return " ".join(sentences)


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
        #
        # NEITHER TALLY MAY RAISE, AND THAT IS ENFORCED IN :func:`_count_relation` RATHER THAN HERE.
        # Both calls below run INSIDE this handler, so anything they throw replaces the 409 with the
        # very "Something went wrong on the server" this block exists to abolish — which is exactly
        # what a stale generated client did on 2026-09-16. A `try` wrapped round these two lines
        # would have caught it too, and was rejected: it would answer the generic no-number sentence
        # for an account whose OTHER relations were perfectly readable, throwing away the counts the
        # message is for. Degrading one relation at a time keeps everything that still works.
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
    # A deleted account must stop authenticating immediately, not when a TTL happens to expire.
    invalidate_cached_user(user_id)
