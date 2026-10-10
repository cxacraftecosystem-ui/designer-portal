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
from app.schemas.users import UserCreate, UserUpdate
from app.services import access_roster, credential_links

# THE RULES OF WHO MAY DO WHAT TO WHOSE ACCOUNT, imported rather than defined here. ``assert_role``
# and ``assert_can_manage_target`` lived in this module until 2026-10-09; the password-link routes
# needed the second, and a route importing a route is how a rule loses its one home. They are
# RE-EXPORTED from here on purpose — ``routes/access`` and ``tests/test_directorate_tiers.py`` import
# ``users.assert_role`` — so do not "tidy" them out of this import.
from app.services.account_provisioning import (
    DUPLICATE_EMAIL_DETAIL,
    GRANT_FLAGS,
    MASTER_EMAIL_DETAIL,
    NO_PASSWORD_TO_CHANGE_DETAIL,
    OWN_CREDENTIALS_DETAIL,
    PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL,
    assert_can_manage_target,
    assert_may_grant,
    assert_not_escaping_a_bar,
    assert_not_overturning_a_bar,
    assert_role,
    create_account,
    email_in_use,
    holds_a_temporary_password,
    is_master_address,
    is_master_email,
    requested_grants,
)
from app.services.pagination import normalize_pagination, page_payload
from app.services.records import clean_data, contains, count_and_page, with_id_tiebreak

router = APIRouter(prefix="/users", tags=["users"])

#: The PATCH fields that are the person's identity, and the ones that are their password. Both are a
#: provisioner's to change on an account it may manage; neither is a professor's.
IDENTITY_FIELDS = frozenset({"name", "email"})
CREDENTIAL_FIELDS = frozenset({"password", "mustChangePassword"})

#: Every field the PATCH accepts — ``UserUpdate``'s eleven, which forbids any other — in ``sorted``
#: order, so the audit line lists a change exactly as it did when it sorted the request's own keys.
#:
#: THE LINE PICKS ITS FIELD NAMES FROM HERE RATHER THAN JOINING THOSE KEYS (2026-10-09), so every
#: word it prints about a password is a literal this module wrote: nothing for a scanner that judges
#: a value by its identifier (CodeQL's py/clear-text-logging-sensitive-data) to follow, and nothing a
#: client could spell should ``UserUpdate`` ever stop forbidding unknown keys. ``"password"`` is
#: here as a word — the line says one was set, never what it was. A field missing from this tuple
#: would vanish from the audit line without a sound, so
#: ``tests/test_auth_identity_and_password_links.py`` holds it to the schema.
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

#: The refusal a professor or a directorate officer reads for a name, an address or a password.
IDENTITY_NEEDS_PROVISIONER_DETAIL = (
    "Correcting a person's name, email address or password requires Ministry Admin access or "
    "above. Professors and the directorate change roles only."
)

# WHERE THE DRIFT IN :func:`_count_relation` GETS SHOUTED ABOUT. The 409 body is deliberately quiet
# about it — an admin can do nothing with "the generated client has no model sanctionorderdesigner"
# — so the log line is the one place the fact is stated in the words a developer needs. See
# :func:`_count_relation` for why it is a log and not an exception.
logger = logging.getLogger(__name__)


def serialize_user(user: Any) -> dict[str, Any]:
    payload = jsonable_encoder(user)
    payload.pop("passwordHash", None)
    return payload


def assert_not_demoting_master(
    target_user: Any, payload_role: str | None, current_user: Any
) -> None:
    # The configured address ITSELF, not any spelling of its mailbox: an account under another
    # spelling is somebody's ordinary account (see ``account_provisioning.is_master_address``).
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
    payload: UserCreate, current_user: Any = Depends(require_account_provisioner)
) -> dict[str, Any]:
    """Provision a password account: MINISTRY_ADMIN, ADMIN and MASTER_ADMIN (owner, 2026-10-09).

    Every rule lives in ``services/account_provisioning.create_account`` so the operator script
    obeys the same ones: the tier ceiling (403), capability flags for admins only (403), one account
    per address in any letter case and — since 2026-10-09, for everybody — per Gmail mailbox under
    any spelling (409, a concurrent double-submit included), and — for a provisioner who is not an
    admin — no account on an address an administrator barred, nor a DESIGNER whose empanelment was
    ended (409). The account is admitted to the allow-list with the
    tier it was made at, and a DESIGNER is empanelled, because a provisioner creating somebody has
    approved them and the sign-in gates fail closed.

    ``mustChangePassword`` comes from the body and defaults to True. The answer is the ordinary user
    payload, which carries ``mustChangePassword``, ``passwordSetAt`` and ``firstLoginAt`` so the
    screen can say what to hand over and whether the temporary secret is still live.
    """
    user = await create_account(current_user, payload)
    return serialize_user(user)


@router.patch("/{user_id}")
async def update_user(
    user_id: str,
    payload: UserUpdate,
    current_user: Any = Depends(require_professor),
) -> dict[str, Any]:
    """Change one account, FIELD BY FIELD — the policy replaced an all-or-role-only split, 2026-10-09.

    * ``role`` — PROFESSOR and above, as it always was: the ceiling is ``assert_role``, the target
      ``assert_can_manage_target``.
    * ``name``, ``email``, ``password``, ``mustChangePassword`` — account provisioners
      (``deps.can_provision_accounts``), on accounts they may manage. Anybody else gets a 403 naming
      the tier that can.
    * the six capability flags — admins only. A provisioner who is not an admin may echo the values an
      account already holds and nothing else; a change is a 403 (``assert_may_grant``).

    **NEVER YOUR OWN PASSWORD OR FLAG, AND THE REFUSAL IS DELIBERATE RATHER THAN THE ACCIDENT IT WAS.**
    Until this change a self-PATCH of ``password`` failed only because ``passwordSetAt`` was added to
    the payload before the self check counted the fields — and tidying that order would have let a
    stolen admin session set a permanent password with no current password and no guessing budget.
    It is now refused first, by name, with a sentence pointing at ``POST /auth/change-password``.

    **A PASSWORD SET FOR SOMEBODY ELSE IS TEMPORARY** unless ``mustChangePassword: false`` is sent
    with it. **RAISING THE FLAG, OR SETTING A PASSWORD, SIGNS THE PERSON OUT EVERYWHERE**
    (``sessionsValidFrom``), so "at the next sign-in" is literal on a phone that would otherwise re-read
    ``/me`` only at a cold start; both clients keep their queued work on the 401 that follows. Raising
    the flag on an account with no password is a 422: nothing could ever satisfy it.

    **``mustChangePassword: false`` ON ITS OWN WITHDRAWS A REQUIRED CHANGE**, and that is a
    provisioner's act on an account it manages, as the owner ruled (2026-10-09). It is the same power
    as setting a password with the flag off, which D1 already gives: it says "the password this account
    holds is final", so it ends no session — a session opened with that password is a session opened
    with the account's final one. The audit line records the field.

    **A PROMOTION DOES NOT CARRY A LOWER PROVISIONER'S CREDENTIAL UPWARD** (2026-10-09; rule 5 of
    ``services/account_provisioning.py``). A request that RAISES the role withdraws every outstanding
    password link of the account in the same request, and is refused (409) while the account still
    holds a temporary password, unless the same request sets a new one: the provisioner who typed
    either would otherwise hold an account above its own reach.

    **A PROVISIONER WHO IS NOT AN ADMIN CANNOT MOVE AN ACCOUNT ONTO A BARRED ADDRESS** (409):
    ``access_roster.follow_email_change`` admits the new address, which would re-activate a row an
    administrator rejected or suspended. **NOR A DESIGNER ACCOUNT ONTO AN ADDRESS WHOSE EMPANELMENT AN
    ADMINISTRATOR ENDED** (409, since 2026-10-09), the rule a create already had: the account would
    be refused at every sign-in. **NOR ANY ACCOUNT OFF AN ADDRESS BARRED EITHER WAY** (409, also
    2026-10-09) — the move that left an administrator's bar on an address nobody held and let the
    person back in at the new one. **NOR, WHATEVER THE ROLE, AN ACCOUNT CARRYING AN ENDED EMPANELMENT
    ONTO AN ADDRESS WITH AN ACTIVE ONE** (409, also 2026-10-09): the move would end that empanelment,
    and only an admin ends one. An admin may make each of these moves, as on create — and the bar
    goes WITH the account (``follow_email_change`` carries it, and the old address stays barred), so
    an admin correcting a barred account's address has not let it back in on either address; that is
    the access screen's act. The audit line says what each address carried.

    **NOBODY MOVES AN ACCOUNT ONTO ANOTHER ACCOUNT'S MAILBOX** (409 "Email already exists", for every
    actor since 2026-10-09): any spelling of a Gmail inbox another account uses is that account's —
    ``account_provisioning.email_in_use`` says what the co-tenancy cost.
    """
    assert_role(payload.role, current_user)
    data = clean_data(payload.model_dump(exclude_unset=True))
    sent = set(data)
    is_self = user_id == current_user.id
    if is_self and sent & CREDENTIAL_FIELDS:
        # Before anything is loaded, and before the tier test, so that everybody who tries this —
        # professor or master admin — reads the one sentence that tells them where the right door is.
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
        # Every one of them matches what the account already holds (or the line above refused), so
        # writing them would change nothing — and must not appear in the audit line as a grant.
        echoed = set(grants)
        for flag in echoed:
            data.pop(flag, None)
    if is_self:
        # Self-service is limited to identity fields; nobody edits their own role or privileges.
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
        # bcrypt HERE, and the timestamps it goes with LAST, immediately before the write — see the
        # stamp below for why the order matters.
        data["passwordHash"] = hash_password(data.pop("password"))
        # A PROVISIONER TYPED THIS PASSWORD FOR SOMEBODY ELSE, so it is a shared secret exactly as
        # it is at account creation, and temporary for the same reason — unless the provisioner sent
        # ``mustChangePassword: false`` beside it, which ``setdefault`` leaves standing.
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
    # What an ADMIN's move found on either address, for the audit line: admins keep the power to
    # move an account onto or off a barred address, and keep the record of having done it.
    barred_on_arrival: str | None = None
    empanelment_ended_on_arrival = False
    barred_on_departure: str | None = None
    empanelment_ended_on_departure = False
    if email_changed:
        # ANY SPELLING OF THE MASTER ADMIN'S MAILBOX, not merely the configured string — see
        # ``account_provisioning.is_master_email`` for the escalation a dotless spelling allowed.
        if is_master_email(data["email"]) and not is_master_admin(current_user):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_EMAIL_DETAIL)
        if await email_in_use(data["email"], except_id=user.id):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL)
        # THE ROLE THE ACCOUNT WILL HOLD, because an ended empanelment refuses a DESIGNER's sign-in
        # and nobody else's — the request's own ``role`` when it changes one, else the account's.
        # Passed as ``role=None`` until 2026-10-09, which asked only about the allow-list: a ministry
        # admin could move a designer onto an address whose empanelment an administrator had ended,
        # and the account was then refused at every sign-in by a decision the provisioner had just
        # stepped round. A create was refused that move from the start.
        role_after = data.get("role") or role_value(user)
        # THE ADDRESS IT IS LEAVING FIRST (2026-10-09). Only the destination used to be asked, so a
        # barred account could be "corrected" onto a fresh address — or onto one the person had
        # planted with a refused sign-in — and walk back in.
        barred_on_departure, empanelment_ended_on_departure = await assert_not_escaping_a_bar(
            current_user, user.email, data["email"], role=role_after
        )
        barred_on_arrival, empanelment_ended_on_arrival = await assert_not_overturning_a_bar(
            current_user, data["email"], role=role_after, moving=True
        )
    # The configured address itself carries MASTER_ADMIN, not every spelling of its mailbox: a master
    # admin moving an account onto another spelling has not asked for a promotion.
    if "email" in data and is_master_address(data["email"]):
        data["role"] = "MASTER_ADMIN"
    if data.get("role") == "MASTER_ADMIN":
        data.update(dict.fromkeys(GRANT_FLAGS, True))
    # A PROMOTION DOES NOT CARRY A LOWER PROVISIONER'S CREDENTIAL UPWARD — rule 5 of
    # ``services/account_provisioning.py``. Asked of the role the write will leave, after the master
    # address has had its say. "Still holds a temporary password" is one predicate shared with the
    # access screen's approval (``account_provisioning.holds_a_temporary_password``), so the two
    # doors that raise a role cannot come to mean two things by it.
    promoted = "role" in data and role_rank(data["role"]) > role_rank(user)
    if promoted and holds_a_temporary_password(user) and "passwordHash" not in data:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL,
        )
    # THE TIMESTAMPS, TAKEN AFTER bcrypt AND EVERY AWAITED CHECK ABOVE, immediately before the write
    # (2026-10-09). The watermark was taken first until then, so the hash and three reads ran between
    # it and the commit: a sign-in with the OLD password that minted its token in that gap, in a
    # later wall second, post-dated the revocation and lived for a week. The credential check in
    # ``deps._user_from_bearer`` now catches that token for a password change anyway; for a flag
    # raised alone, this position is the whole defence. It is the THIRD writer of the watermark — see
    # the list in ``deps._user_from_bearer`` — at full precision, as ``access.end_live_sessions``
    # argues: a token minted in the same wall second is refused, which fails closed by one second.
    now = datetime.now(UTC)
    if "passwordHash" in data:
        data["passwordSetAt"] = now
    if ends_sessions:
        data["sessionsValidFrom"] = now
    try:
        updated = await db.user.update(where={"id": user_id}, data=data)
    except UniqueViolationError as exc:
        # Another request took the address between the check above and this write.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL
        ) from exc
    # The cached identity now describes authority, an address or a session watermark the account no
    # longer has, so it must not outlive the write by even one request.
    invalidate_cached_user(user_id)
    # After the write, so a refused write withdraws nothing. The redemption asks the issuer's reach
    # again anyway (``auth._link_verdict``), which also covers a promotion made by any other door.
    links_withdrawn = await credential_links.revoke_outstanding(user_id) if promoted else 0
    moved = access_roster.EmailMove()
    if email_changed:
        # THE ALLOW-LIST IS KEYED BY EMAIL. A provisioner correcting a typo in an address would
        # otherwise lock the account out at its next sign-in — the new address has no row, and the
        # gate reads a missing row as "never approved". See `access_roster.follow_email_change`,
        # which also carries a bar — the account's own, for an admin's move — to the new address.
        moved = await access_roster.follow_email_change(
            user.email, data["email"], actor_id=current_user.id
        )
    # THE AUDIT LINE: who changed what on whose account. Field NAMES only — the password itself, and
    # its hash, never reach a log. Every word about the password is a literal (2026-10-09): the
    # fields are picked from :data:`AUDITED_FIELDS`, and the flag is "required" or "not required"
    # rather than its own value, which a scanner that judges by name took for the password.
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
        f"promoted, so {links_withdrawn} outstanding password link(s) were withdrawn"
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
