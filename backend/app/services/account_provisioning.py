import logging
import secrets
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ACCOUNT_PROVISIONER_REQUIRED_DETAIL,
    ROLE_LABELS,
    ROLE_RANK,
    can_manage_access_roster,
    can_provision_accounts,
    get_value,
    invalidate_cached_user,
    is_admin,
    is_master_admin,
    role_rank,
    role_value,
)
from app.core.security import hash_password
from app.services import access_roster, credential_links
from app.services.designers import (
    GMAIL_DOMAINS,
    adopt_allow_list_name,
    canonical_email,
    email_match_keys,
    ensure_empanelled,
    normalise_email,
)

logger = logging.getLogger(__name__)

ALLOWED_ROLES = frozenset(ROLE_RANK)

GRANT_FLAGS: tuple[str, ...] = (
    "canManageQuestionnaire",
    "canManageCrafts",
    "canManageWorkshops",
    "canReview",
    "canViewProvenance",
    "canDownloadDataset",
)

DUPLICATE_EMAIL_DETAIL = "Email already exists"

MAILBOX_UNCHECKED_DETAIL = (
    "Couldn't check whether another account already uses this mailbox, so nothing was saved. "
    "Try again in a moment."
)

OWN_CREDENTIALS_DETAIL = (
    "You cannot set or reset your own password here. Change it from your own account (Change "
    "password), which asks for the password you use now."
)

GRANTS_ARE_ADMIN_ONLY_DETAIL = (
    "Only an admin or the master admin can grant capabilities (questionnaire, crafts, workshops, "
    "review, provenance or dataset download). Save the account without them and ask an admin."
)

NO_PASSWORD_TO_CHANGE_DETAIL = (
    "This account has no password, so it cannot be asked to change one. Set a temporary password "
    "for it in the same request."
)

GOOGLE_ONLY_LINK_DETAIL = (
    "This account signs in with Google and has no password, so there is no password link to issue."
)

SUSPENDED_EMPANELMENT_DETAIL = (
    "{email}'s designer empanelment was ended by an administrator, so a designer account at this "
    "address could not sign in. Ask an admin to restore the empanelment on the designer roster, or "
    "create the account at another role."
)

SUSPENDED_EMPANELMENT_MOVE_DETAIL = (
    "{email}'s designer empanelment was ended by an administrator, so this designer account could "
    "not sign in at that address. Ask an admin to restore the empanelment on the designer roster, "
    "or keep the account at the address it has."
)

LEAVING_A_BARRED_ADDRESS_DETAIL = (
    "An administrator {verb} this account's address, {email}, on the access screen. Moving the "
    "account to another address would leave that decision behind, so only an admin or the master "
    "admin can change its address; ask one to review it there."
)

LEAVING_AN_ENDED_EMPANELMENT_DETAIL = (
    "An administrator ended the designer empanelment of this account's address, {email}, so this "
    "designer account cannot sign in. Moving it to another address would leave that decision "
    "behind, so only an admin or the master admin can change its address; ask one to restore the "
    "empanelment on the designer roster."
)

ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL = (
    "An administrator ended the designer empanelment of this account's address, {email}, and "
    "{destination} has an active designer empanelment. Moving the account there would carry the "
    "ended one with it and end that one too, and only an admin or the master admin can end an "
    "empanelment; ask one to make this change."
)

MASTER_EMAIL_DETAIL = "Only the master admin can assign the master admin email"

PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL = (
    "This account still has to replace a password somebody typed for it, and promoting it now would "
    "hand that password to a higher role than whoever typed it. Promote it once its owner has "
    "chosen their own password, or set it a new temporary password on the website's Users page in "
    "the same change as the promotion."
)

GOOGLE_ADMISSION_REQUIRES_ADMIN_DETAIL = (
    "Admitting an address for Google sign-in is done on the access screen, which requires Admin "
    "access or above."
)

GOOGLE_ADMISSION_NOTE = (
    "Admitted for Google sign-in. The account is created with this role the first time the person "
    "signs in with Google."
)



def is_master_email(email: str | None) -> bool:
    configured = get_settings().master_admin_email
    if not email or not configured:
        return False
    return canonical_email(email) == canonical_email(configured)


def is_master_address(email: str | None) -> bool:
    configured = (get_settings().master_admin_email or "").strip().lower()
    return bool(configured) and normalise_email(email) == configured


def assert_role(role: str | None, current_user: Any) -> None:
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
            detail="You can only give a role at or below your own",
        )


_MASTER_PEER_DETAIL = (
    "Master admin accounts can't change or remove each other. To change this account, ask the "
    "team that runs the platform."
)


def assert_can_manage_target(current_user: Any, target_user: Any) -> None:
    if is_master_admin(current_user):
        if role_value(target_user) == "MASTER_ADMIN" and target_user.id != current_user.id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_PEER_DETAIL)
        return
    if role_rank(target_user) >= role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only manage accounts with a role below your own",
        )



def assert_provisioner(actor: Any) -> None:
    if not can_provision_accounts(actor):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=ACCOUNT_PROVISIONER_REQUIRED_DETAIL
        )


def assert_may_reset_credentials(actor: Any, target: Any) -> None:
    assert_provisioner(actor)
    if get_value(target, "id") == get_value(actor, "id"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
    assert_can_manage_target(actor, target)


def issuer_still_manages(issuer: Any, target: Any) -> bool:
    if issuer is None or is_master_admin(issuer):
        return True
    return role_rank(target) < role_rank(issuer)


def holds_a_temporary_password(user: Any) -> bool:
    flagged = bool(get_value(user, "mustChangePassword")) or bool(
        get_value(user, "passwordIssuerKnown")
    )
    return flagged and get_value(user, "passwordHash") is not None


def requested_grants(values: Mapping[str, Any]) -> dict[str, bool]:
    return {flag: bool(values[flag]) for flag in GRANT_FLAGS if values.get(flag) is not None}


def assert_may_grant(actor: Any, grants: Mapping[str, bool], current: Any = None) -> None:
    if is_admin(actor):
        return
    for flag, wanted in grants.items():
        held = bool(get_value(current, flag)) if current is not None else False
        if wanted != held:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=GRANTS_ARE_ADMIN_ONLY_DETAIL
            )


async def barred_status(email: Any) -> str | None:
    state = access_roster.status_of(await access_roster.access_row(email))
    return state if state in access_roster.BARRED else None


async def empanelment_suspended(email: Any) -> bool:
    keys = email_match_keys(email)
    if not keys:
        return False
    rows = await db.designerroster.find_many(where={"email": {"in": keys}})
    return any(not row.isActive for row in rows)


async def empanelment_active(email: Any) -> bool:
    keys = email_match_keys(email)
    if not keys:
        return False
    found = await db.designerroster.find_first(where={"email": {"in": keys}, "isActive": True})
    return found is not None


def _barred_detail(email: str, state: str) -> str:
    verb = "rejected" if state == access_roster.REJECTED else "suspended"
    return (
        f"An administrator {verb} {email} on the access screen. Only an admin or the master admin "
        "can let this address back in; ask one to review it there."
    )


def _setup_label(mode: Any) -> str:
    if mode == "GENERATE":
        return "GENERATE"
    if mode == "NONE":
        return "NONE"
    return "SET"


async def assert_not_overturning_a_bar(
    actor: Any, email: Any, *, role: str | None, moving: bool = False
) -> tuple[str | None, bool]:
    barred = await barred_status(email)
    suspended = role == "DESIGNER" and await empanelment_suspended(email)
    if not is_admin(actor):
        if barred:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=_barred_detail(normalise_email(email), barred),
            )
        if suspended:
            sentence = SUSPENDED_EMPANELMENT_MOVE_DETAIL if moving else SUSPENDED_EMPANELMENT_DETAIL
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=sentence.format(email=normalise_email(email)),
            )
    return barred, suspended


async def assert_not_escaping_a_bar(
    actor: Any, current_email: Any, new_email: Any, *, role: str | None
) -> tuple[str | None, bool]:
    if canonical_email(current_email) == canonical_email(new_email):
        return None, False
    barred = await barred_status(current_email)
    source_ended = (role == "DESIGNER" or not is_admin(actor)) and await empanelment_suspended(
        current_email
    )
    ended = role == "DESIGNER" and source_ended
    if not is_admin(actor):
        if barred:
            verb = "rejected" if barred == access_roster.REJECTED else "suspended"
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=LEAVING_A_BARRED_ADDRESS_DETAIL.format(
                    verb=verb, email=normalise_email(current_email)
                ),
            )
        if ended:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=LEAVING_AN_ENDED_EMPANELMENT_DETAIL.format(
                    email=normalise_email(current_email)
                ),
            )
        if source_ended and await empanelment_active(new_email):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL.format(
                    email=normalise_email(current_email), destination=normalise_email(new_email)
                ),
            )
    return barred, ended


ONLY_MASTER_CREATES_ADMIN_DETAIL = "Only the master admin can create an admin account."
CREATE_BELOW_YOUR_OWN_DETAIL = "You can only create accounts with a role below your own."

EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"


def assert_may_create_role(actor: Any, role: str) -> None:
    if is_master_admin(actor):
        return
    if role in ("ADMIN", "MASTER_ADMIN"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=ONLY_MASTER_CREATES_ADMIN_DETAIL)
    if ROLE_RANK.get(role, 0) >= role_rank(actor):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=CREATE_BELOW_YOUR_OWN_DETAIL)


def duplicate_account_detail(account: Any) -> str:
    role = role_value(account)
    return (
        f"{get_value(account, 'email')} already has an account ({get_value(account, 'name')}, "
        f"{ROLE_LABELS.get(role, role)}). Open it to change their role or access instead."
    )


def refuse_duplicate(account: Any) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_409_CONFLICT,
        detail=duplicate_account_detail(account),
        headers={EXISTING_ACCOUNT_HEADER: str(get_value(account, "id"))},
    )


async def existing_account(email: Any, *, except_id: str | None = None) -> Any | None:
    where: dict[str, Any] = {"email": {"equals": normalise_email(email), "mode": "insensitive"}}
    if except_id:
        where["id"] = {"not": except_id}
    found = await db.user.find_first(where=where)
    if found is not None:
        return found
    if canonical_email(email).partition("@")[2] not in GMAIL_DOMAINS:
        return None
    accounts = await access_roster.accounts_on_the_mailbox_for_sign_in(email)
    if accounts is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=MAILBOX_UNCHECKED_DETAIL
        )
    return next((a for a in accounts if getattr(a, "id", None) != except_id), None)


async def email_in_use(email: Any, *, except_id: str | None = None) -> bool:
    return await existing_account(email, except_id=except_id) is not None


async def empanel_an_admitted_designer(
    row: Any, actor_id: str | None, *, client: Any = None
) -> bool:
    if access_roster.status_of(row) != access_roster.ACTIVE:
        return False
    if access_roster.role_of(row) != "DESIGNER":
        return False
    created = await ensure_empanelled(
        getattr(row, "email", None), actor_id=actor_id, client=client, swallow_race=client is None
    )
    if not created:
        await adopt_allow_list_name(
            getattr(row, "email", None), getattr(row, "fullName", None), client=client
        )
    return created



@dataclass(frozen=True)
class AccountPlan:
    email: str
    name: str
    role: str
    must_change_password: bool
    grants: dict[str, bool]
    barred: str | None
    empanelment_suspended: bool
    password_mode: str = "SET"
    send_password_link: bool = False


async def plan_account(actor: Any, payload: Any) -> AccountPlan:
    assert_provisioner(actor)
    email = normalise_email(payload.email)
    if is_master_email(email) and not is_master_admin(actor):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_EMAIL_DETAIL)
    role = "MASTER_ADMIN" if is_master_address(email) else payload.role
    assert_role(role, actor)
    assert_may_create_role(actor, role)
    grants = {flag: bool(getattr(payload, flag)) for flag in GRANT_FLAGS}
    assert_may_grant(actor, grants)
    holder = await existing_account(email)
    if holder is not None:
        raise refuse_duplicate(holder)
    barred, suspended = await assert_not_overturning_a_bar(actor, email, role=role)
    if role == "MASTER_ADMIN":
        grants = dict.fromkeys(GRANT_FLAGS, True)
    mode = getattr(payload, "password_mode", None) or "SET"
    return AccountPlan(
        email=email,
        name=payload.name,
        role=role,
        must_change_password=(
            bool(payload.mustChangePassword) if mode == "SET" else mode == "GENERATE"
        ),
        grants=grants,
        barred=barred,
        empanelment_suspended=suspended,
        password_mode=mode,
        send_password_link=bool(getattr(payload, "sendPasswordLink", False)),
    )


GENERATED_PASSWORD_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
GENERATED_PASSWORD_LENGTH = 16

LINK_NOT_SENT_NO_MAIL_DETAIL = (
    "The account was made, but e-mail is not set up here, so no link was sent. Make a password link "
    "from their row on the users screen and pass it on yourself."
)
LINK_NOT_SENT_THROTTLED_DETAIL = (
    "The account was made, but several links have already been sent for this address in the last "
    "hour, so no new one was sent."
)


def generate_temporary_password() -> str:
    return "".join(
        secrets.choice(GENERATED_PASSWORD_ALPHABET) for _ in range(GENERATED_PASSWORD_LENGTH)
    )


@dataclass
class CreatedAccount:
    user: Any
    temporary_password: str | None = None
    role_lists: list[str] = field(default_factory=list)
    password_link: dict[str, Any] | None = None
    password_link_error: str | None = None


async def _write_in_one_transaction(
    actor: Any, plan: AccountPlan, password: str | None, *, via: str
) -> CreatedAccount:
    now = datetime.now(UTC)
    actor_id = getattr(actor, "id", None)
    actor_email = getattr(actor, "email", None) or "an administrator"
    has_password = password is not None
    role_lists: list[str] = []
    try:
        async with db.tx() as tx:
            user = await tx.user.create(
                data={
                    "email": plan.email,
                    "name": plan.name,
                    "passwordHash": hash_password(password) if password is not None else None,
                    "mustChangePassword": plan.must_change_password and has_password,
                    "passwordIssuerKnown": has_password,
                    "passwordSetAt": now if has_password else None,
                    "role": plan.role,
                    "authProvider": "LOCAL",
                    **plan.grants,
                }
            )
            admitted = await access_roster.admit(
                user.email,
                admit_role=plan.role,
                actor_id=actor_id,
                full_name=user.name,
                note=f"Admitted with the account, created {via} by {actor_email}.",
                client=tx,
            )
            if await empanel_an_admitted_designer(admitted, actor_id, client=tx):
                role_lists.append("designerRoster")
    except UniqueViolationError as exc:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL
        ) from exc
    invalidate_cached_user(user.id)
    logger.warning(
        "AUDIT account created: actor=%s user=%s role=%s passwordMode=%s mustChange=%s "
        "rosterAdded=%s roleListsAdded=%s via=%s%s",
        actor_id,
        user.id,
        plan.role,
        _setup_label(plan.password_mode),
        "required" if plan.must_change_password and has_password else "not required",
        True,
        ",".join(role_lists) or "-",
        via,
        (f"; re-admitted an address the allow-list held as {plan.barred}" if plan.barred else "")
        + (
            "; the address's designer empanelment is suspended, so this designer cannot sign in "
            "until it is restored"
            if plan.empanelment_suspended
            else ""
        ),
    )
    return CreatedAccount(user=user, role_lists=role_lists)


async def write_account(
    actor: Any, plan: AccountPlan, password: str | None, *, via: str = "on the users screen"
) -> Any:
    return (await _write_in_one_transaction(actor, plan, password, via=via)).user


async def create_account_in_one_step(
    actor: Any, payload: Any, *, via: str = "on the users screen"
) -> CreatedAccount:
    plan = await plan_account(actor, payload)
    password: str | None
    generated: str | None = None
    if plan.password_mode == "GENERATE":
        password = generated = generate_temporary_password()
    elif plan.password_mode == "NONE":
        password = None
    else:
        password = payload.password
    created = await _write_in_one_transaction(actor, plan, password, via=via)
    created.temporary_password = generated
    if plan.password_mode == "NONE" and plan.send_password_link:
        try:
            issued = await credential_links.issue_link(
                user=created.user,
                purpose=credential_links.INVITE,
                issued_by_id=getattr(actor, "id", None),
                deliver_by=credential_links.EMAIL,
            )
            created.password_link = {
                "id": issued.id,
                "expiresAt": issued.expiresAt,
                "purpose": issued.purpose,
                "deliveredBy": issued.deliveredBy,
            }
        except credential_links.DeliveryUnavailable:
            created.password_link_error = LINK_NOT_SENT_NO_MAIL_DETAIL
        except credential_links.IssueThrottled:
            created.password_link_error = LINK_NOT_SENT_THROTTLED_DETAIL
    return created


async def create_account(actor: Any, payload: Any, *, via: str = "on the users screen") -> Any:
    return (await create_account_in_one_step(actor, payload, via=via)).user



@dataclass(frozen=True)
class GoogleAdmissionPlan:
    email: str
    role: str
    full_name: str
    existing_status: str | None
    empanelment_suspended: bool


async def plan_google_admission(
    actor: Any, email: Any, *, role: str, full_name: str
) -> GoogleAdmissionPlan:
    if not can_manage_access_roster(actor):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=GOOGLE_ADMISSION_REQUIRES_ADMIN_DETAIL
        )
    assert_role(role, actor)
    address = normalise_email(email)
    if is_master_email(address):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                "That address is the master admin's mailbox. The master admin is never gated by the "
                "allow-list, and nobody else is admitted on that mailbox from here."
            ),
        )
    accounts = await access_roster.accounts_on_the_mailbox(address)
    if accounts is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=(
                "Could not check every spelling of this mailbox for an existing account (the Gmail "
                "sweep was cut), so nothing was admitted."
            ),
        )
    if accounts:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"An account already exists for {address}. A Google sign-in will use that account, "
                "so admitting the address would not set its tier; change the account on the users "
                "screen instead."
            ),
        )
    row = await access_roster.access_row(address)
    state = access_roster.status_of(row) or None
    if state in access_roster.BARRED:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=_barred_detail(normalise_email(row.email), state)
            + " The command line does not overturn a bar.",
        )
    return GoogleAdmissionPlan(
        email=normalise_email(row.email) if row is not None else canonical_email(address),
        role=role,
        full_name=full_name.strip(),
        existing_status=state,
        empanelment_suspended=role == "DESIGNER" and await empanelment_suspended(address),
    )


async def apply_google_admission(actor: Any, plan: GoogleAdmissionPlan) -> Any:
    row = await access_roster.admit(
        plan.email,
        admit_role=plan.role,
        actor_id=getattr(actor, "id", None),
        full_name=plan.full_name or None,
        note=None if plan.existing_status else GOOGLE_ADMISSION_NOTE,
    )
    await empanel_an_admitted_designer(row, getattr(actor, "id", None))
    logger.info(
        "accounts: %s admitted allow-list row %s for Google sign-in at %s (was %s)",
        getattr(actor, "id", None),
        row.id,
        plan.role,
        plan.existing_status or "absent",
    )
    return row
