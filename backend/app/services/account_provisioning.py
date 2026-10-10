"""ACCOUNT PROVISIONING: who may create, correct and reset whose account. The rules, written once.

Owner's decision, 2026-10-09. Three tiers provision password accounts —
``deps.ACCOUNT_PROVISIONER_ROLES``, which is MINISTRY_ADMIN, ADMIN and MASTER_ADMIN — and every door
that does so asks the questions in this module rather than its own copy of them:

* ``POST /api/users`` (``routes/users.create_user``) calls :func:`create_account`;
* ``PATCH /api/users/{id}`` (``routes/users.update_user``) composes the guards below field by field;
* ``POST /api/auth/password-links`` and its revoke (``routes/auth``) call
  :func:`assert_may_reset_credentials`;
* ``scripts/provision_account.py``, the operator's command line, calls :func:`plan_account` and
  :func:`write_account`, or :func:`plan_google_admission` and :func:`apply_google_admission` — the
  very functions the routes call, so the script cannot come to permit something the routes refuse.

── THE FOUR RULES ───────────────────────────────────────────────────────────────────────────────

1. **THE CEILING** — :func:`assert_role`. An account is created or promoted at the actor's own tier
   and below, and only a master admin mints a master admin. Inclusive on purpose: an admin creates an
   admin, a ministry admin a ministry admin.
2. **THE TARGET** — :func:`assert_can_manage_target`. Strictly lower tiers only, and master admins are
   peers who cannot manage each other. A provisioner can touch nobody it could not already promote.
3. **NEVER YOURSELF** — :func:`assert_may_reset_credentials`. Nobody sets or resets their own password,
   raises their own flag or issues themselves a link from these doors. ``POST /auth/change-password``
   asks for the current password and spends a per-account guessing budget; a door that skipped both
   would turn a stolen session into a permanent takeover, which is the one thing that route exists
   to prevent.
4. **WHAT ONLY AN ADMIN DOES.** A provisioner who is not ``is_admin`` may not grant a capability flag
   (:func:`assert_may_grant`), and may not create or move an account onto an address an administrator
   barred on the allow-list, nor create a DESIGNER — or move a DESIGNER account — onto an address
   whose empanelment an administrator ended (:func:`assert_not_overturning_a_bar`); nor move an
   account OFF an address barred in either way, nor — whatever its role — carry an ended
   empanelment onto an address with an active one, which would end that one
   (:func:`assert_not_escaping_a_bar`) — all answer 409. ``access_roster.admit`` re-activates a
   barred row, so without the first refusal "create an account" would be a way round somebody
   else's decision, and without the second "correct an address" would be. Deleting an account stays
   ``require_admin`` at its own route.

ONE ACCOUNT PER MAILBOX, FOR EVERY ACTOR (2026-10-09): no account is created at, or moved onto, any
spelling of a mailbox another account uses — :func:`email_in_use`, which says why.
5. **A PROMOTION DOES NOT CARRY A LOWER PROVISIONER'S CREDENTIAL UPWARD** (2026-10-09). A temporary
   password or a password link is a credential its issuer holds. Raising the account above the issuer
   would hand the issuer an account it could never have managed, so a ``PATCH`` that raises a role
   revokes the account's outstanding links and refuses (409) while a temporary password still stands
   (:func:`holds_a_temporary_password`) unless it sets a new one
   (:data:`PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL`), and a link is redeemable only while its
   issuer could still manage the account (:func:`issuer_still_manages`). The access screen's
   approval, which lifts an account that already exists, applies the same rule
   (``routes/access._lift_existing_account``): it withdraws the links of an account it lifts, and
   leaves an account still holding a temporary password at its tier, saying so in its answer
   (:data:`APPROVAL_KEEPS_THE_TIER_DETAIL`) — it has no password field to set a new one in.

── WHY THE TWO GUARDS MOVED HERE FROM routes/users ──────────────────────────────────────────────

``assert_role`` and ``assert_can_manage_target`` were route-module functions, and ``routes/access``
already reached across for the first. The password-link routes need the second, and a third
route-to-route import is how a rule ends up with one canonical home nobody can find. They are
re-exported from ``routes/users`` so every existing import keeps working.

── THE DESIGNER EMPANELMENT, ONE IMPLEMENTATION ─────────────────────────────────────────────────

:func:`empanel_an_admitted_designer` is ``routes/access._empanel_an_admitted_designer``'s rule —
ACTIVE row, DESIGNER role, read off the STORED row, create-only — which ``routes/users`` used to
re-spell inline with a note saying a third caller must move it to a service rather than copy it. The
operator script is that third caller, so it moved.
"""

import logging
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.config import get_settings
from app.core.db import db
from app.core.deps import (
    ACCOUNT_PROVISIONER_REQUIRED_DETAIL,
    ROLE_RANK,
    can_manage_access_roster,
    can_provision_accounts,
    get_value,
    invalidate_cached_user,
    is_admin,
    is_master_admin,
    role_rank,
    # The ROLE, spelled the one way. ``target.role`` is a Prisma enum member on a live row and a plain
    # string on anything hand-built, and the peer guard in :func:`assert_can_manage_target` compares it
    # against a literal — an equality test that silently answers False for the enum, i.e. lets the
    # refusal through, which is the direction that loses an account. Do not inline ``target.role``.
    role_value,
)
from app.core.security import hash_password
from app.services import access_roster
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

#: The six per-account capability grants. Admin-only to set, on create and on update alike.
GRANT_FLAGS: tuple[str, ...] = (
    "canManageQuestionnaire",
    "canManageCrafts",
    "canManageWorkshops",
    "canReview",
    "canViewProvenance",
    "canDownloadDataset",
)

#: Kept word for word from the route this logic came out of: clients have shown it for years. Said for
#: another account's MAILBOX as well as its address since 2026-10-09 (:func:`email_in_use`).
DUPLICATE_EMAIL_DETAIL = "Email already exists"

#: :func:`email_in_use` could not finish reading every spelling of a Gmail mailbox (the lookup's cap
#: was reached and its answer withdrawn — ``access_roster.accounts_on_the_mailbox_for_sign_in``, which
#: logs the remedy at ERROR). 503 and nothing written: a duplicate this check could not rule out is
#: exactly the one it exists to refuse.
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

#: No password link in the remedy, though one used to be offered: nearly every account with no
#: password signs in with Google, and the link route refuses those (:data:`GOOGLE_ONLY_LINK_DETAIL`),
#: so the two answers sent a provisioner from one refusal to the other.
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

#: The same refusal for ``PATCH /api/users/{id}`` moving a DESIGNER account onto such an address.
#: Its own sentence because the create's remedy ("create the account at another role") is not a
#: move a provisioner correcting an address can make; keeping the address it has is.
SUSPENDED_EMPANELMENT_MOVE_DETAIL = (
    "{email}'s designer empanelment was ended by an administrator, so this designer account could "
    "not sign in at that address. Ask an admin to restore the empanelment on the designer roster, "
    "or keep the account at the address it has."
)

#: ``PATCH /api/users/{id}`` moving an account OFF an address an administrator barred — the other
#: direction from the two above. ``{verb}`` is "rejected" or "suspended".
LEAVING_A_BARRED_ADDRESS_DETAIL = (
    "An administrator {verb} this account's address, {email}, on the access screen. Moving the "
    "account to another address would leave that decision behind, so only an admin or the master "
    "admin can change its address; ask one to review it there."
)

#: The same, for a DESIGNER account whose empanelment an administrator ended.
LEAVING_AN_ENDED_EMPANELMENT_DETAIL = (
    "An administrator ended the designer empanelment of this account's address, {email}, so this "
    "designer account cannot sign in. Moving it to another address would leave that decision "
    "behind, so only an admin or the master admin can change its address; ask one to restore the "
    "empanelment on the designer roster."
)

#: The move above for an account of ANY role, onto an address with an ACTIVE empanelment
#: (2026-10-09). An address change carries an ended empanelment to the new mailbox
#: (``designers.carry_ended_empanelment``), which ENDS an active one there — possibly somebody else's
#: — and ending an empanelment is an admin's act on the designer roster. ``{destination}`` is the
#: address the account would move to.
ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL = (
    "An administrator ended the designer empanelment of this account's address, {email}, and "
    "{destination} has an active designer empanelment. Moving the account there would carry the "
    "ended one with it and end that one too, and only an admin or the master admin can end an "
    "empanelment; ask one to make this change."
)

#: Creating an account on, or moving one onto, any spelling of the master admin's mailbox.
MASTER_EMAIL_DETAIL = "Only the master admin can assign the master admin email"

#: A role raised while the account still holds a password a provisioner typed — see rule 5 above.
#:
#: THE REMEDY NAMES WHERE IT LIVES. Only the website's Users page sends a password and a role in one
#: change; the handset's role control carries no password field (D5), so a sentence saying "in the
#: same request" sent an administrator on a phone looking for a box that is not there.
PROMOTION_WITH_A_TEMPORARY_PASSWORD_DETAIL = (
    "This account still has to replace a password somebody typed for it, and promoting it now would "
    "hand that password to a higher role than whoever typed it. Promote it once its owner has "
    "chosen their own password, or set it a new temporary password on the website's Users page in "
    "the same change as the promotion."
)

#: The same rule at the access screen's APPROVE, which lifts an account that already exists
#: (``routes/access._lift_existing_account``). Not a refusal: the approval of the ADDRESS stands,
#: and only the account's TIER waits — so it rides in the approval's answer, for the screen to show
#: beside its own confirmation. ``{email}`` is the approved address; ``{current}`` and ``{granted}``
#: are tier labels (``deps.ROLE_LABELS``).
APPROVAL_KEEPS_THE_TIER_DETAIL = (
    "{email} is approved, but the account stays {current} until its owner replaces the password "
    "somebody typed for it. Promote it to {granted} then, or set it a new temporary password on "
    "the website's Users page in the same change as the promotion."
)

GOOGLE_ADMISSION_REQUIRES_ADMIN_DETAIL = (
    "Admitting an address for Google sign-in is done on the access screen, which requires Admin "
    "access or above."
)

#: What an allow-list row admitted from the command line says about itself on the access screen.
GOOGLE_ADMISSION_NOTE = (
    "Admitted for Google sign-in. The account is created with this role the first time the person "
    "signs in with Google."
)


# --------------------------------------------------------------------------------------
# The ceiling and the target — moved here from routes/users, unchanged in behaviour
# --------------------------------------------------------------------------------------


def is_master_email(email: str | None) -> bool:
    """Does this address reach the configured ``MASTER_ADMIN_EMAIL``'s MAILBOX, under any spelling?

    The question the CREATE and the MOVE ask, so that nobody but a master admin can put an account
    there (:data:`MASTER_EMAIL_DETAIL`). CANONICAL ON BOTH SIDES SINCE 2026-10-09: it compared the
    two strings until then, so a provisioner could create ``newmaster+x@gmail.com`` beside a master
    configured as ``new.master@gmail.com`` — one inbox — and the master's first Google sign-in, missing
    its own literal row, found that account through the Gmail fold and promoted it, password and all,
    to MASTER_ADMIN. ``auth.login_with_google`` no longer folds for the master; this closes the door
    the account came in by.

    NOT the question "is this the master admin's account": that one is :func:`is_master_address`,
    literal on purpose, because an account under another spelling is somebody's account and must stay
    deletable and correctable rather than inherit the master's protection.
    """
    configured = get_settings().master_admin_email
    if not email or not configured:
        return False
    return canonical_email(email) == canonical_email(configured)


def is_master_address(email: str | None) -> bool:
    """Is this the configured ``MASTER_ADMIN_EMAIL`` itself (letter case aside)?

    The account at this address always carries MASTER_ADMIN, cannot be deleted and is changed only by
    a master admin — ``routes/users``. Literal, unlike :func:`is_master_email`, and in step with
    ``deps.is_configured_master_admin``, the break-glass's own reading of the same setting.
    """
    configured = (get_settings().master_admin_email or "").strip().lower()
    return bool(configured) and normalise_email(email) == configured


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
            detail="You can only give a role at or below your own",
        )


#: Refusing a master admin an action on ANOTHER master admin. Named so the routes and the tests
#: assert the same sentence, and phrased to say what the reader can actually do about it.
_MASTER_PEER_DETAIL = (
    "Master admin accounts can't change or remove each other."
)


def assert_can_manage_target(current_user: Any, target_user: Any) -> None:
    """Nobody manages a peer, INCLUDING the master admin; everyone else manages strictly lower
    tiers. This blocks one admin from silently rewriting another admin's account — and, since
    2026-10-09, from minting a password link for one.

    **THE MASTER-ADMIN CLAUSE USED TO BE ``if is_master_admin(current_user): return`` WITH NO PEER
    TEST**, and that made this the stricter of two mirrors' looser half. ``canManageUser`` in
    ``frontend/lib/permissions.ts`` returns ``target.role !== "MASTER_ADMIN" || target.id ===
    user?.id``, and ``docs/PERMISSIONS.md`` §2 states that rule as the system's — so both browsers
    render a second master-admin row with no controls on it while ``PATCH /users/{id}`` and
    ``DELETE /users/{id}`` accepted exactly that target. An operator who promoted a deputy for a
    handover read "protected" off the screen and could still demote or delete them with one curl.
    Worse, the only peer protection that existed keyed on ``MASTER_ADMIN_EMAIL``
    (``routes/users.assert_not_demoting_master``), so protection followed one address in the
    environment rather than the privilege.

    **AND THE PASSWORD-LINK ROUTE NEVER ASKED IT AT ALL UNTIL 2026-10-09.** ``POST
    /auth/password-links`` checked only that the CALLER was an admin, never whom the link was for,
    and ``POST /auth/set-password`` has no role check by design (the link is the whole authority) —
    so any ADMIN could mint a link for a MASTER_ADMIN or a peer ADMIN, redeem it, set that account's
    password and sign its holder out of every device. The link door now asks this question through
    :func:`assert_may_reset_credentials`, exactly as ``PATCH`` does.

    **THE COST, SAID OUT LOUD: promoting somebody to MASTER_ADMIN is a one-way door through the
    API.** They cannot be demoted by a peer (this guard) and cannot demote themselves
    (``update_user`` refuses privilege changes on one's own row), which is the same standing the
    configured master-admin address has always had. If a reversible deputy is wanted, the answer is
    a lower tier, not a hole in this rule.
    """
    if is_master_admin(current_user):
        # The self-exception mirrors ``canManageUser``'s ``target.id === user?.id``. Every caller
        # already branches on self before reaching here — ``update_user`` down the identity-only
        # path, ``delete_user`` with its 422, the link routes through the own-credentials refusal —
        # so this arm is unreachable today and is written anyway, because a predicate that answers a
        # different question from the one the UI asks is how these two got out of step.
        if role_value(target_user) == "MASTER_ADMIN" and target_user.id != current_user.id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=_MASTER_PEER_DETAIL)
        return
    if role_rank(target_user) >= role_rank(current_user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only manage accounts with a role below your own",
        )


# --------------------------------------------------------------------------------------
# Who may do what to whom
# --------------------------------------------------------------------------------------


def assert_provisioner(actor: Any) -> None:
    """The routes already depend on ``require_account_provisioner``; this is the same refusal for a
    caller with no dependency in front of it — the operator script."""
    if not can_provision_accounts(actor):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN, detail=ACCOUNT_PROVISIONER_REQUIRED_DETAIL
        )


def assert_may_reset_credentials(actor: Any, target: Any) -> None:
    """The rule for a password, the password flag or a password link on somebody's account.

    A provisioner, never on themselves, and only on an account they may manage. One function so the
    PATCH route and both link routes cannot disagree about whose credentials a provisioner may touch.
    """
    assert_provisioner(actor)
    if get_value(target, "id") == get_value(actor, "id"):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=OWN_CREDENTIALS_DETAIL)
    assert_can_manage_target(actor, target)


def issuer_still_manages(issuer: Any, target: Any) -> bool:
    """May the account that ISSUED a password link still manage the account it is for?

    Asked when a link is redeemed (``routes/auth``), because the link is the whole authority there and
    it was authorised once, at issue, against tiers that can move afterwards: a ministry admin's
    invitation for a researcher, redeemed after an admin promoted the researcher to ADMIN, would set
    an ADMIN's password with the ministry admin's credential (rule 5 above). ``PATCH`` revokes the
    links of an account it promotes; this catches every other way a rank can change.

    The rank comparison :func:`assert_can_manage_target` makes, without its provisioner question —
    the sanction register's officers issue first-sign-in links for designers they cannot otherwise
    manage. A master admin always may, as the owner ruled. ``None`` — a link issued by nobody, or by
    an account since deleted, whose id the foreign key cleared — is accepted: there is no issuer
    whose reach could have been outgrown.
    """
    if issuer is None or is_master_admin(issuer):
        return True
    return role_rank(target) < role_rank(issuer)


def holds_a_temporary_password(user: Any) -> bool:
    """Does this account still hold a password somebody else typed for it? Rule 5's question, asked
    by every door that raises a role (``routes/users.update_user``, ``routes/access``'s approval).

    The flag on an account that HAS a password: a flag with no hash behind it holds nothing, and
    holds nobody either (``deps.password_change_pending``). Deliberately not that function, which
    also exempts the configured master admin from the HOLD — a question about who may use the API,
    where this one is about what a promotion would carry upward.
    """
    flagged = bool(get_value(user, "mustChangePassword"))
    return flagged and get_value(user, "passwordHash") is not None


def requested_grants(values: Mapping[str, Any]) -> dict[str, bool]:
    """The capability flags present in a request body, as booleans; absent and null are not sent."""
    return {flag: bool(values[flag]) for flag in GRANT_FLAGS if values.get(flag) is not None}


def assert_may_grant(actor: Any, grants: Mapping[str, bool], current: Any = None) -> None:
    """403 when a provisioner who is not an admin would CHANGE a capability flag.

    "Change", not "send": a flag sent with the value the account already holds — every flag False on
    a create, or a form echoing an account's current grants on an update — grants nothing, and
    refusing it would make a client's harmless echo look like an escalation. Anything that would move
    a flag is refused outright rather than dropped, so a ministry admin is told the grant did not
    happen instead of finding out later.
    """
    if is_admin(actor):
        return
    for flag, wanted in grants.items():
        held = bool(get_value(current, flag)) if current is not None else False
        if wanted != held:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=GRANTS_ARE_ADMIN_ONLY_DETAIL
            )


async def barred_status(email: Any) -> str | None:
    """REJECTED or SUSPENDED when the allow-list bars this mailbox, else None.

    Through ``access_roster.access_row``, so it reads every spelling of a Gmail mailbox and lets a
    barred twin win exactly as the sign-in gate does.
    """
    state = access_roster.status_of(await access_roster.access_row(email))
    return state if state in access_roster.BARRED else None


async def empanelment_suspended(email: Any) -> bool:
    """Has an administrator ended a designer empanelment for this mailbox?

    Any suspended row under any spelling counts, which is ``roster_allows``' fail-closed reading of
    the same table: if one spelling is suspended, a DESIGNER at this address is refused at sign-in.
    """
    keys = email_match_keys(email)
    if not keys:
        return False
    rows = await db.designerroster.find_many(where={"email": {"in": keys}})
    return any(not row.isActive for row in rows)


async def empanelment_active(email: Any) -> bool:
    """Does an ACTIVE designer empanelment stand at this mailbox?

    The rows ``designers.carry_ended_empanelment`` would END if an account carrying an ended
    empanelment moved here — read over the same keys that function reads, so the refusal in
    :func:`assert_not_escaping_a_bar` asks exactly about the rows the move would change.
    """
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


async def assert_not_overturning_a_bar(
    actor: Any, email: Any, *, role: str | None, moving: bool = False
) -> tuple[str | None, bool]:
    """409 for a provisioner who is not an admin, on an address an administrator barred.

    Barred means a REJECTED or SUSPENDED allow-list row, or — when the account would be a DESIGNER —
    an empanelment an administrator ended. Returns what it found either way, so an ADMIN's write can
    say in its log line that it re-admitted somebody: admins keep the power, and keep the record.

    ``role`` is the role the account will HOLD once the write lands — on a create the body's, on an
    email change the target's own unless the same request changes it. ``moving`` says the address is
    an existing account's new one, which only changes the empanelment sentence's remedy.
    """
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
    """409 for a provisioner who is not an admin, moving an account OFF a barred address.

    :func:`assert_not_overturning_a_bar` asks about the address an account moves TO; until
    2026-10-09 nothing asked about the one it was leaving. So a ministry admin could take an account an
    administrator had suspended, move it onto an address the person had planted (a refused Google
    sign-in leaves a PENDING row), and ``access_roster.follow_email_change`` admitted the plant —
    the person back in, the SUSPENDED row stranded on an address no account held, the access screen
    still showing the bar. The same move off an address whose designer empanelment was ended let the
    next sign-in empanel the new address afresh. Both are the bar D1 reserves to an admin, overturned
    by correcting an address.

    Barred means what it means there: a REJECTED or SUSPENDED allow-list row, or — when the account
    will be a DESIGNER after the write — an ended empanelment. Asked only when the MAILBOX changes:
    a new spelling of the same Gmail inbox leaves nothing behind (and the destination check refuses
    it for a barred account anyway). Returns what it found, so an ADMIN's move can say in its audit
    line that the bar went with the account — ``follow_email_change`` carries it for admins too.

    **AND FOR ANY ROLE, AN ENDED EMPANELMENT MAY NOT BE CARRIED ONTO AN ACTIVE ONE (2026-10-09).**
    ``follow_email_change`` carries an ended empanelment to the new mailbox whatever the account's
    role, and carrying it ENDS an active empanelment there (``designers.carry_ended_empanelment``) —
    an administrator's empanelment of somebody who may not even be this account's owner, ended by a
    correction of an address, with nothing in the answer to say so. Ending an empanelment is an
    admin's act (``PATCH /designers/roster/{id}``), so a provisioner who is not an admin is refused
    that move (:data:`ENDING_AN_EMPANELMENT_BY_MOVING_DETAIL`); an admin's move still carries the
    ending onto it, as documented, and its audit line says so.
    """
    if canonical_email(current_email) == canonical_email(new_email):
        return None, False
    barred = await barred_status(current_email)
    # Read for a DESIGNER whoever moves it (the admin's audit line reports it), and for anybody else
    # only when the mover is not an admin — the one case the answer can refuse.
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


async def email_in_use(email: Any, *, except_id: str | None = None) -> bool:
    """Does another account already hold this address, in ANY letter case — or its MAILBOX?

    ``User.email`` is unique only as typed, and older rows were not all lower-cased, so the
    ``find_unique`` this replaced let ``A.Sharma@x.org`` and ``a.sharma@x.org`` become two accounts.
    The database still catches the race two simultaneous requests can win past this read; the
    callers turn that ``UniqueViolationError`` into the same 409.

    **ANY SPELLING OF ANOTHER ACCOUNT'S GMAIL MAILBOX IS IN USE TOO (2026-10-09), for every actor.**
    The literal comparison let ``sandycraft3+x@gmail.com`` be created, or an account be moved onto
    ``sandycraft3@gmail.com``, beside an account at ``sandy.craft3@gmail.com`` — one inbox, two
    accounts. Every gate that reads the allow-list and both rosters reads that inbox as ONE key, so
    the second account inherited the first one's admission and empanelment, a move onto it rewrote
    the first account's allow-list row (``access_roster.follow_email_change`` admits through the row
    the destination already has), and a move carrying an ended empanelment ended the first
    account's. Google sign-in, which refuses to guess between two password accounts on one mailbox,
    then refused them both. So no account is created at, or moved onto, a mailbox another account
    already uses: the question is ``access_roster.accounts_on_the_mailbox_for_sign_in``'s, the Gmail
    fold done by Postgres. Outside the Gmail domains the literal answer above is already the whole
    one, and nothing more is read. A lookup that could not finish refuses (503) rather than guessing.
    """
    where: dict[str, Any] = {"email": {"equals": normalise_email(email), "mode": "insensitive"}}
    if except_id:
        where["id"] = {"not": except_id}
    if await db.user.find_first(where=where) is not None:
        return True
    if canonical_email(email).partition("@")[2] not in GMAIL_DOMAINS:
        return False
    accounts = await access_roster.accounts_on_the_mailbox_for_sign_in(email)
    if accounts is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=MAILBOX_UNCHECKED_DETAIL
        )
    return any(getattr(account, "id", None) != except_id for account in accounts)


async def empanel_an_admitted_designer(row: Any, actor_id: str | None) -> None:
    """An ACTIVE allow-list row admitting a DESIGNER gets the empanelment that admission implies.

    ``routes/access._empanel_an_admitted_designer``'s rule, which is the authority for every word of
    it: both conditions asked of the STORED row, and :func:`ensure_empanelled` only ever CREATES — a
    suspended empanelment is an administrator's revocation and is never revived from here. When the
    row already existed, the allow-list's name is offered to it (fill, never overwrite).
    """
    if access_roster.status_of(row) != access_roster.ACTIVE:
        return
    if access_roster.role_of(row) != "DESIGNER":
        return
    created = await ensure_empanelled(getattr(row, "email", None), actor_id=actor_id)
    if not created:
        await adopt_allow_list_name(getattr(row, "email", None), getattr(row, "fullName", None))


# --------------------------------------------------------------------------------------
# Creating a password account — POST /api/users and the operator script
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class AccountPlan:
    """Everything :func:`create_account` will write, decided before it writes any of it.

    Holds no password — the script prints a plan, and a plan that could carry the secret is one
    ``print`` away from a log.
    """

    email: str
    name: str
    role: str
    must_change_password: bool
    grants: dict[str, bool]
    #: REJECTED or SUSPENDED when an ADMIN is about to re-admit a barred address; None otherwise.
    barred: str | None
    #: True when an ADMIN is creating a DESIGNER whose empanelment was ended: the account will exist
    #: and the empanelment gate will refuse its sign-in until somebody restores the empanelment.
    empanelment_suspended: bool


async def plan_account(actor: Any, payload: Any) -> AccountPlan:
    """Every refusal ``POST /api/users`` can give, in order, and nothing written.

    ``payload`` is a ``schemas.users.UserCreate`` — the route's own body model, which the script
    builds too, so the length and address rules are pydantic's in both places.
    """
    assert_provisioner(actor)
    email = normalise_email(payload.email)
    # ANY SPELLING OF THE MASTER ADMIN'S MAILBOX IS A MASTER ADMIN'S TO ASSIGN — see
    # :func:`is_master_email` for the escalation the literal comparison allowed. Only the configured
    # address itself is forced to MASTER_ADMIN; a master admin who creates an account under another
    # spelling gets the role they asked for.
    if is_master_email(email) and not is_master_admin(actor):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=MASTER_EMAIL_DETAIL)
    role = "MASTER_ADMIN" if is_master_address(email) else payload.role
    assert_role(role, actor)
    grants = {flag: bool(getattr(payload, flag)) for flag in GRANT_FLAGS}
    assert_may_grant(actor, grants)
    if await email_in_use(email):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL)
    barred, suspended = await assert_not_overturning_a_bar(actor, email, role=role)
    if role == "MASTER_ADMIN":
        grants = dict.fromkeys(GRANT_FLAGS, True)
    return AccountPlan(
        email=email,
        name=payload.name,
        role=role,
        must_change_password=bool(payload.mustChangePassword),
        grants=grants,
        barred=barred,
        empanelment_suspended=suspended,
    )


async def write_account(
    actor: Any, plan: AccountPlan, password: str, *, via: str = "on the users screen"
) -> Any:
    """Create the account :func:`plan_account` approved, admit it, and empanel it if a designer.

    ``password`` is hashed here and goes nowhere else — not into the plan, not into a log line.
    ``via`` is how the allow-list note says where the account was made.
    """
    now = datetime.now(UTC)
    try:
        user = await db.user.create(
            data={
                "email": plan.email,
                "name": plan.name,
                "passwordHash": hash_password(password),
                # THE FIRST-SIGN-IN FLAG, and since 2026-10-09 it REFUSES as well as reports. A
                # password one person typed for another is a shared secret by construction, so the
                # default is True; ``deps.refuse_while_password_change_pending`` holds the account
                # to it on every route but the change-password screen's own, and both clients draw
                # that screen. A provisioner who unticks it has said the password is final.
                "mustChangePassword": plan.must_change_password,
                # Stamped so that "has never had a password" stays distinguishable from "signs in
                # with Google" — the whole reason this column exists. There is no default.
                "passwordSetAt": now,
                "role": plan.role,
                "authProvider": "LOCAL",
                **plan.grants,
            }
        )
    except UniqueViolationError as exc:
        # The race :func:`email_in_use` cannot close: a double-click, or two provisioners typing the
        # same person in at once. The database is the referee and the loser hears what it would have
        # heard a moment later — not a 500.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail=DUPLICATE_EMAIL_DETAIL
        ) from exc
    # A brand-new cuid cannot already be cached, but every write to a User row invalidates without
    # exception — a rule with a documented exception is a rule the next person has to re-derive.
    invalidate_cached_user(user.id)
    # PROVISIONING AN ACCOUNT IS APPROVING IT. Without this the platform allow-list would refuse the
    # account the moment it was made, and the provisioner would hand somebody a password and watch
    # them be told they are awaiting approval. The gate fails closed (``auth.assert_access_admits``),
    # so every path that mints an account has to admit it.
    actor_email = getattr(actor, "email", None) or "an administrator"
    admitted = await access_roster.admit(
        user.email,
        admit_role=plan.role,
        actor_id=getattr(actor, "id", None),
        full_name=user.name,
        note=f"Admitted with the account, created {via} by {actor_email}.",
    )
    # AND A DESIGNER PROVISIONED HERE HAS BEEN EMPANELLED, read off the STORED row — see
    # :func:`empanel_an_admitted_designer`. ``actor_id`` is the provisioner, unlike the sign-in
    # path's None: somebody really did take this action, and the roster screen says who.
    await empanel_an_admitted_designer(admitted, getattr(actor, "id", None))
    logger.info(
        "accounts: %s created account %s at %s (mustChangePassword=%s)%s",
        getattr(actor, "id", None),
        user.id,
        plan.role,
        # A WORD THE FLAG PICKS, NOT THE FLAG: its own value is read by a name a scanner takes for
        # the password — see the audit line in ``routes/users.update_user``, which says it the same.
        "required" if plan.must_change_password else "not required",
        (f"; re-admitted an address the allow-list held as {plan.barred}" if plan.barred else "")
        + (
            "; the address's designer empanelment is suspended, so this designer cannot sign in "
            "until it is restored"
            if plan.empanelment_suspended
            else ""
        ),
    )
    return user


async def create_account(actor: Any, payload: Any, *, via: str = "on the users screen") -> Any:
    """``POST /api/users``: plan, then write. Raises the route's own HTTPExceptions."""
    plan = await plan_account(actor, payload)
    return await write_account(actor, plan, payload.password, via=via)


# --------------------------------------------------------------------------------------
# Admitting an address for Google sign-in — the operator script's other mode
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class GoogleAdmissionPlan:
    """An ACTIVE allow-list row the script will write, so a first Google sign-in creates the account
    at ``role``. No ``User`` row is involved: the sign-in creates it."""

    #: The address the row is written under: an existing row's own spelling, else the mailbox.
    email: str
    role: str
    full_name: str
    #: The row's state before the write, or None for a new row.
    existing_status: str | None
    #: As on :class:`AccountPlan`: a DESIGNER whose empanelment an administrator ended.
    empanelment_suspended: bool


async def plan_google_admission(
    actor: Any, email: Any, *, role: str, full_name: str
) -> GoogleAdmissionPlan:
    """What ``POST /api/access/roster`` would decide for this actor, with one widening and two
    refusals of the script's own.

    THE GATE IS THAT ROUTE'S — ``require_access_manager``, Admin and above — and its ceiling is
    :func:`assert_role`, the one ``routes/access`` imports. A ministry admin is refused here exactly as
    it would be on the access screen.

    THE WIDENING: the route answers 409 for an address that already has a row; this admits a PENDING
    row and re-states an ACTIVE one, which is what an admin would do with the decision endpoint. The
    TWO REFUSALS: an address an administrator barred (the command line does not overturn a bar — that
    decision belongs on the access screen, where the record of who made it is visible), and an
    address that already has an ACCOUNT, because Google sign-in would then use that account and the
    tier written here would never apply.
    """
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
        # THE ROW THE PLAN FOUND, WRITTEN THROUGH ITS OWN SPELLING (2026-10-09). This was always the
        # canonical form, and ``admit`` keyed on a canonical address reads ONE key — so a legacy row
        # stored with dots (the pending queue wrote them before canonicalisation existed) was found
        # here, missed there, and a second row created beside it, the literal one still deciding the
        # person's sign-in. ``routes/access``'s decision endpoint calls ``admit(row.email, ...)`` for
        # exactly this reason. A new row is still written under the mailbox.
        email=normalise_email(row.email) if row is not None else canonical_email(address),
        role=role,
        full_name=full_name.strip(),
        existing_status=state,
        empanelment_suspended=role == "DESIGNER" and await empanelment_suspended(address),
    )


async def apply_google_admission(actor: Any, plan: GoogleAdmissionPlan) -> Any:
    """Write the row through ``access_roster.admit`` — the call ``POST /api/access/roster`` makes —
    then the empanelment that admission implies. Returns the row.

    ``plan.email`` is the existing row's own spelling when there is one, so ``admit``'s key list
    reaches that row rather than missing it and creating a second."""
    row = await access_roster.admit(
        plan.email,
        admit_role=plan.role,
        actor_id=getattr(actor, "id", None),
        full_name=plan.full_name or None,
        # A new row explains itself; an existing row keeps whatever an administrator wrote on it.
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
