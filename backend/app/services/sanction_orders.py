from __future__ import annotations

import json
import logging
import re
import secrets
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any

from fastapi import HTTPException, status
from prisma.errors import UniqueViolationError

from app.core.db import db
from app.core.deps import (
    ROLE_RANK,
    can_run_design_workshops,
    has_rank,
    invalidate_cached_user,
    role_rank,
    role_value,
)
from app.core.security import hash_password
from app.services import (
    access_roster,
    annual_plan,
    credential_links,
    designers,
    email_outbox,
    mailer,
    signin_role,
)
from app.services.account_provisioning import is_master_email
from app.services.design_workshops import (
    attach_the_named_designer,
    entry_rows,
    seed_designer_prefill,
)
from app.services.records import decimal_to_string
from app.services.stage_schema import registry_version, stage, stage_completeness

log: logging.Logger = logging.getLogger(__name__)

STAGE_ONE_KEY = "WORKSHOP_SETUP"
STAGE_ONE_ENTITY = "workshopSetup"



def can_record_sanction_orders(user: Any) -> bool:
    return has_rank(user, "ASSISTANT_DIRECTOR")


SANCTION_ORDER_REFUSAL = (
    "Only an Assistant Director or above records sanction orders. Ask the officer holding yours "
    "— the workshop appears in your list once they do."
)


_NON_ALNUM = re.compile(r"[^A-Za-z0-9]+")


def normalise_sanction_order_no(value: Any) -> str:
    return _NON_ALNUM.sub("", str(value or "")).upper()


def tidy_sanction_order_no(value: Any) -> str:
    return " ".join(str(value or "").split())


SANCTION_ADMISSION_NOTE = "Admitted by sanction order {no}, recorded by {officer}."

SANCTION_ADDRESS_BARRED = (
    "This address has been {state} on the platform allow-list. A sanction order cannot let somebody "
    "back in — ask an admin to review the allow-list entry first, then record the order."
)
SANCTION_EMPANELMENT_ENDED = (
    "This designer's empanelment has been ended. A sanction order does not restore it — ask an "
    "admin to restore the roster entry first, then record the order."
)

SANCTION_DESIGNER_CANNOT_RUN_WORKSHOPS = (
    "{name} ({email}) already has an account on this platform as a {role}, and a {role} cannot run "
    "a design & prototype workshop. A sanction order does not change somebody's role, and a "
    "workshop opened for them would answer “not found” the first time they tried to open it — ask "
    "an admin to move the account to Designer on the users screen and then record the order, or "
    "record it naming the designer who will do the work."
)

SANCTION_SELF_NAMED = (
    "A sanction order cannot name the officer recording it as its designer. The instrument "
    "authorises somebody else's work and its whole value as a record is that the person who "
    "approves the money is not the person who spends it. Record the order naming the designer who "
    "will do the work; if you are that designer, ask a colleague at Assistant Director or above to "
    "record it."
)

SANCTION_MASTER_MAILBOX = (
    "This address is the master admin's mailbox, under one spelling or another, and a sanction "
    "order cannot name it. Nobody but the master admin creates or changes the account on that "
    "mailbox, and an order naming it would create one and hand you its first sign-in link. Record "
    "the order naming the designer who will do the work, at an address of their own."
)

SANCTION_DUPLICATE_TEMPLATE = (
    "Sanction order {no} is already recorded. It opened “{title}” for {designer} on {date}. If the "
    "ministry has issued a second order with this number, record it under the number as printed "
    "including its suffix; if this is the same order, open the workshop from the sanction list."
)

SANCTION_AMBIGUOUS_ACCOUNTS = (
    "Two accounts already answer to this mailbox — {addresses}. Recording a sanction order would "
    "have to pick one of them to file the workshop under, and picking wrong puts a ministry "
    "workshop on an account the designer does not sign in to. Ask an admin to merge or correct the "
    "two accounts first, then record the order."
)

SANCTION_THROTTLE_DETAIL = (
    "Several sign-in links have already been issued for this designer in the last hour. Wait an "
    "hour, or ask them to use the one they were sent — each one works until it is used."
)

SANCTION_LINK_PROBLEM_THROTTLED = (
    "The sanction order is recorded and the workshop is open, but the sign-in link could not be "
    "issued: " + SANCTION_THROTTLE_DETAIL + " Re-issue it from this list when the hour is up."
)

SANCTION_LINK_NOT_THIS_REGISTERS_ACCOUNT = (
    "This order did not create the designer's account, so there is no first sign-in link for it to "
    "re-issue — they already sign in the way they always have. A password link for an account this "
    "register did not create is an administrator's act: ask an admin to send one from the users "
    "screen."
)
SANCTION_LINK_NOT_ON_THIS_ORDER = (
    "That designer is not named on this sanction order, so it cannot issue them a sign-in link."
)
SANCTION_LINK_OUTRANKS_OFFICER = (
    "The account this order names is now a {role}, and a sanction order cannot issue a sign-in "
    "link for an account senior to the officer re-issuing it. Ask an admin to send a password link "
    "from the users screen."
)



@dataclass(frozen=True)
class Refusal:
    status: int
    detail: str

    def raise_it(self) -> None:
        raise HTTPException(status_code=self.status, detail=self.detail)


async def allow_list_verdict(email: str) -> tuple[Any | None, Refusal | None]:
    row = await access_roster.access_row(email)
    state = access_roster.status_of(row)
    if state in access_roster.BARRED:
        return row, Refusal(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            SANCTION_ADDRESS_BARRED.format(state=state.lower()),
        )
    return row, None


async def _the_allow_list_row_or_refuse_if_barred(email: str) -> Any | None:
    row, refusal = await allow_list_verdict(email)
    if refusal is not None:
        refusal.raise_it()
    return row


def _an_admission_that_preserves_an_admins_decision(
    existing: Any | None, *, designer_name: str, note: str
) -> dict[str, Any]:
    if existing is None:
        return {
            "admit_role": "DESIGNER",
            "full_name": designer_name,
            "note": note,
            "decided": True,
        }

    existing_notes = str(getattr(existing, "notes", None) or "").strip()
    if not existing_notes:
        merged: str | None = note
    elif note in existing_notes:
        merged = None
    else:
        merged = f"{existing_notes} {note}"

    stored_tier = access_roster.role_of(existing)
    already_outranks_a_designer = role_rank(stored_tier or "") >= ROLE_RANK["DESIGNER"]
    already_has_a_name = bool(str(getattr(existing, "fullName", None) or "").strip())

    return {
        "admit_role": None if already_outranks_a_designer else "DESIGNER",
        "full_name": None if already_has_a_name else designer_name,
        "note": merged,
        "decided": getattr(existing, "decidedById", None) is None
        and getattr(existing, "decidedAt", None) is None,
    }


def self_named_reason(officer: Any, keys: Sequence[str]) -> str | None:
    own = set(designers.email_match_keys(getattr(officer, "email", None)))
    if own and own.intersection(keys):
        return SANCTION_SELF_NAMED
    return None


def master_mailbox_reason(address: str) -> str | None:
    if is_master_email(address):
        return SANCTION_MASTER_MAILBOX
    return None


def _refuse_if_the_officer_named_themselves(officer: Any, keys: list[str]) -> None:
    reason = self_named_reason(officer, keys)
    if reason is not None:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)


def _refuse_if_the_named_account_cannot_run_the_workshop(user: Any | None) -> None:
    reason = cannot_run_reason(user)
    if reason is not None:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)


def cannot_run_reason(user: Any | None) -> str | None:
    if user is None:
        return None
    if role_rank(user) < ROLE_RANK["DESIGNER"]:
        return None
    if can_run_design_workshops(user):
        return None
    return SANCTION_DESIGNER_CANNOT_RUN_WORKSHOPS.format(
        name=getattr(user, "name", None) or getattr(user, "email", None) or "That account",
        email=getattr(user, "email", None) or "",
        role=role_value(user),
    )


async def _refuse_if_empanelment_ended(keys: list[str]) -> None:
    reason = await empanelment_ended_reason(keys)
    if reason is not None:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)


async def empanelment_ended_reason(keys: Sequence[str]) -> str | None:
    rows = await db.designerroster.find_many(where={"email": {"in": list(keys)}})
    if any(not bool(getattr(row, "isActive", False)) for row in rows):
        return SANCTION_EMPANELMENT_ENDED
    return None


async def _existing_account(keys: list[str]) -> Any | None:
    found, refusal = await account_verdict(keys)
    if refusal is not None:
        refusal.raise_it()
    return found


async def account_verdict(keys: Sequence[str]) -> tuple[Any | None, Refusal | None]:
    rows = await db.user.find_many(
        where={"OR": [{"email": {"equals": key, "mode": "insensitive"}} for key in keys]},
        take=3,
    )
    if len(rows) > 1:
        addresses = ", ".join(sorted(str(getattr(row, "email", "") or "") for row in rows))
        return None, Refusal(
            status.HTTP_409_CONFLICT,
            SANCTION_AMBIGUOUS_ACCOUNTS.format(addresses=addresses),
        )
    return (rows[0] if rows else None), None


async def _account_the_register_already_knows(canonical: str, found: Any | None) -> Any | None:
    settled, refusal = await register_account_verdict(canonical, found)
    if refusal is not None:
        refusal.raise_it()
    return settled


async def register_account_verdict(
    canonical: str, found: Any | None
) -> tuple[Any | None, Refusal | None]:
    prior = await db.sanctionorder.find_first(
        where={"designerEmail": canonical}, include={"designerUser": True}
    )
    known = getattr(prior, "designerUser", None) if prior is not None else None
    if known is None:
        named = await db.sanctionorderdesigner.find_first(
            where={"designerEmail": canonical}, include={"designerUser": True}
        )
        known = getattr(named, "designerUser", None) if named is not None else None
    if known is None:
        return found, None
    if found is None:
        return known, None
    if found.id != known.id:
        addresses = ", ".join(
            sorted({str(found.email or ""), str(getattr(known, "email", "") or "")})
        )
        return None, Refusal(
            status.HTTP_409_CONFLICT,
            SANCTION_AMBIGUOUS_ACCOUNTS.format(addresses=addresses),
        )
    return found, None


async def _refuse_if_number_taken(key: str) -> None:
    reason = await duplicate_reason(key)
    if reason is not None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=reason)


async def duplicate_reason(key: str) -> str | None:
    existing = await db.sanctionorder.find_unique(
        where={"sanctionOrderKey": key},
        include={"designWorkshop": True, "designerUser": True},
    )
    if existing is None:
        return None
    return _duplicate_detail(existing)


def _duplicate_detail(existing: Any) -> str:
    workshop = getattr(existing, "designWorkshop", None)
    designer = getattr(existing, "designerUser", None)
    created = getattr(existing, "createdAt", None)
    return SANCTION_DUPLICATE_TEMPLATE.format(
        no=getattr(existing, "sanctionOrderNo", "") or "",
        title=(getattr(workshop, "title", None) or "a workshop"),
        designer=(
            getattr(designer, "name", None)
            or getattr(existing, "designerEmail", None)
            or "a designer"
        ),
        date=(created.date().isoformat() if isinstance(created, datetime) else "an earlier date"),
    )



@dataclass
class NamedDesigner:
    name: str
    email: str
    canonical: str
    keys: list[str] = field(default_factory=list)
    user: Any | None = None
    admission: dict[str, Any] = field(default_factory=dict)
    accountCreated: bool = False
    userId: str = ""


def collapse_named_team(pairs: Sequence[tuple[str, str]]) -> list[tuple[str, str]]:
    kept: list[tuple[str, str]] = []
    seen: set[str] = set()
    for name, address in pairs:
        mailbox = designers.canonical_email(address)
        if not mailbox or mailbox in seen:
            continue
        seen.add(mailbox)
        kept.append((name, address))
    return kept


@dataclass(frozen=True)
class StandingVerdict:
    keys: list[str]
    canonical: str
    allowListRow: Any | None
    user: Any | None
    refusal: Refusal | None


async def designer_standing_verdict(address: str, *, officer: Any) -> StandingVerdict:
    keys = designers.email_match_keys(address)
    canonical = designers.canonical_email(address)
    if not keys:
        return StandingVerdict(
            keys=[],
            canonical=canonical,
            allowListRow=None,
            user=None,
            refusal=Refusal(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "That email address can't be used.",
            ),
        )

    def refused(refusal: Refusal, *, row: Any = None) -> StandingVerdict:
        return StandingVerdict(
            keys=list(keys), canonical=canonical, allowListRow=row, user=None, refusal=refusal
        )

    reason = self_named_reason(officer, keys)
    if reason is not None:
        return refused(Refusal(status.HTTP_422_UNPROCESSABLE_ENTITY, reason))

    reason = master_mailbox_reason(address)
    if reason is not None:
        return refused(Refusal(status.HTTP_422_UNPROCESSABLE_ENTITY, reason))

    allow_list_row, refusal = await allow_list_verdict(address)
    if refusal is not None:
        return refused(refusal, row=allow_list_row)

    reason = await empanelment_ended_reason(keys)
    if reason is not None:
        return refused(Refusal(status.HTTP_422_UNPROCESSABLE_ENTITY, reason), row=allow_list_row)

    found, refusal = await account_verdict(keys)
    if refusal is not None:
        return refused(refusal, row=allow_list_row)

    settled, refusal = await register_account_verdict(canonical, found)
    if refusal is not None:
        return refused(refusal, row=allow_list_row)

    reason = cannot_run_reason(settled)
    if reason is not None:
        return refused(Refusal(status.HTTP_422_UNPROCESSABLE_ENTITY, reason), row=allow_list_row)

    return StandingVerdict(
        keys=list(keys),
        canonical=canonical,
        allowListRow=allow_list_row,
        user=settled,
        refusal=None,
    )


async def resolve_named_designer(
    name: str, address: str, *, officer: Any, note: str
) -> NamedDesigner | Refusal:
    verdict = await designer_standing_verdict(address, officer=officer)
    if verdict.refusal is not None:
        return verdict.refusal

    designer_name = str(name).strip()
    return NamedDesigner(
        name=designer_name,
        email=address,
        canonical=verdict.canonical,
        keys=list(verdict.keys),
        user=verdict.user,
        admission=_an_admission_that_preserves_an_admins_decision(
            verdict.allowListRow, designer_name=designer_name, note=note
        ),
    )


def named_team_of(payload: Any) -> list[tuple[str, str]]:
    lead = (str(getattr(payload, "designerName", "") or ""), str(payload.designerEmail))
    rest = [
        (str(getattr(co, "name", "") or ""), str(getattr(co, "email", "")))
        for co in (getattr(payload, "coDesigners", None) or [])
    ]
    return collapse_named_team([lead, *rest])



def _midnight_utc(value: date) -> datetime:
    return datetime(value.year, value.month, value.day, tzinfo=UTC)


async def create_from_sanction(payload: Any, officer: Any) -> dict[str, Any]:
    stored_no = tidy_sanction_order_no(payload.sanctionOrderNo)
    key = normalise_sanction_order_no(stored_no)
    if not key:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="A sanction order number needs at least one letter or digit.",
        )

    officer_name = str(getattr(officer, "name", None) or getattr(officer, "email", "") or "")
    note = SANCTION_ADMISSION_NOTE.format(no=stored_no, officer=officer_name)

    for _name, address in named_team_of(payload):
        reason = self_named_reason(officer, designers.email_match_keys(address))
        if reason is not None:
            raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=reason)

    await _refuse_if_number_taken(key)

    planned_id = getattr(payload, "annualPlanEntryId", None)
    planned = (
        await annual_plan.plan_row_for_sanction(
            planned_id, scope=await annual_plan.sanction_pick_scope(officer)
        )
        if planned_id
        else None
    )

    team: list[NamedDesigner] = []
    for name, address in named_team_of(payload):
        resolved = await resolve_named_designer(name, address, officer=officer, note=note)
        if isinstance(resolved, Refusal):
            resolved.raise_it()
        else:
            team.append(resolved)

    settled: list[NamedDesigner] = []
    claimed: set[str] = set()
    for designer in team:
        account_id = getattr(designer.user, "id", None)
        if account_id is not None:
            if account_id in claimed:
                continue
            claimed.add(account_id)
        settled.append(designer)
    team = settled
    lead = team[0]

    secrets_minted: dict[str, str] = {}
    roles_raised: list[tuple[str, str]] = []

    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=30)) as tx:
        for designer in team:
            await access_roster.admit(
                designer.email,
                actor_id=getattr(officer, "id", None),
                client=tx,
                **designer.admission,
            )

            await designers.ensure_empanelled(
                designer.email,
                actor_id=getattr(officer, "id", None),
                note=note,
                client=tx,
                swallow_race=False,
            )

            if designer.user is None:
                secret = secrets.token_urlsafe(32)
                secrets_minted[designer.canonical] = secret
                user = await tx.user.create(
                    data={
                        "email": designers.normalise_email(designer.email),
                        "name": designer.name,
                        "passwordHash": hash_password(secret),
                        "passwordSetAt": datetime.now(UTC),
                        "mustChangePassword": False,
                        "role": "DESIGNER",
                        "authProvider": "LOCAL",
                        "canManageQuestionnaire": False,
                        "canManageCrafts": False,
                        "canManageWorkshops": False,
                        "canReview": False,
                        "canViewProvenance": False,
                        "canDownloadDataset": False,
                    }
                )
                designer.accountCreated = True
            else:
                user = designer.user
                if role_rank(user) < ROLE_RANK["DESIGNER"]:
                    before = role_value(user)
                    user = await tx.user.update(where={"id": user.id}, data={"role": "DESIGNER"})
                    roles_raised.append((user.id, before))

            await designers.get_or_create_profile(user.id, client=tx)
            designer.user = user
            designer.userId = user.id

        workshop = await tx.designworkshop.create(
            data={
                "title": (
                    annual_plan.promotion_title(planned)
                    if planned is not None
                    else f"Sanctioned workshop {stored_no}"
                ),
                "templateId": "DCH_STANDARD",
                "createdById": getattr(officer, "id", None),
                "schemaVersion": registry_version(),
                "status": "DRAFT",
            }
        )

        for designer in team:
            await attach_the_named_designer(
                workshop.id,
                designer.userId,
                granted_by_id=getattr(officer, "id", None),
                creator_id=getattr(officer, "id", None),
                client=tx,
            )

        try:
            row = await tx.sanctionorder.create(
                data=decimal_to_string(
                    {
                        "sanctionOrderNo": stored_no,
                        "sanctionOrderKey": key,
                        "sanctionOrderDate": _midnight_utc(payload.sanctionOrderDate),
                        "sanctionAmount": payload.sanctionAmount,
                        "designerUserId": lead.userId,
                        "designerEmail": lead.canonical,
                        "designWorkshopId": workshop.id,
                        "createdById": getattr(officer, "id", None),
                        "accountCreated": lead.accountCreated,
                        "notes": payload.notes,
                        "sourceFilename": getattr(payload, "sourceFilename", None),
                        "sheetRow": getattr(payload, "sheetRow", None),
                    }
                )
            )
        except UniqueViolationError as exc:
            duplicate = await db.sanctionorder.find_unique(
                where={"sanctionOrderKey": key},
                include={"designWorkshop": True, "designerUser": True},
            )
            detail = (
                _duplicate_detail(duplicate)
                if duplicate is not None
                else f"Sanction order {stored_no} is already recorded."
            )
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail) from exc

        if planned is not None:
            await annual_plan.claim_for_sanction(
                planned.id, workshop.id, actor_id=getattr(officer, "id", None), client=tx
            )

        await tx.sanctionorderdesigner.create_many(
            data=[
                {
                    "sanctionOrderId": row.id,
                    "designerUserId": designer.userId,
                    "designerEmail": designer.canonical,
                    "accountCreated": designer.accountCreated,
                    "position": position,
                }
                for position, designer in enumerate(team)
            ]
        )

    for raised_id, before in roles_raised:
        signin_role.audit_role_change(
            user_id=raised_id,
            actor_id=getattr(officer, "id", None),
            before=before,
            after="DESIGNER",
            via="sanction-order",
        )
    for designer in team:
        invalidate_cached_user(designer.userId)

    report_copy_seeded = await _seed_the_report_copy(
        workshop,
        officer,
        lead.userId,
        stored_no,
        payload.sanctionOrderDate,
        amount=payload.sanctionAmount,
        planned=annual_plan.plan_stage_one_values(planned) if planned is not None else None,
    )

    credential_links_out: list[dict[str, Any]] = []
    by_email = mailer.mail_configured()
    already_had_accounts = [designer.userId for designer in team if not designer.accountCreated]
    if already_had_accounts:
        await email_outbox.notify_workshop_assigned(
            workshop, already_had_accounts, actor=officer, sanction_order_no=stored_no
        )
    for designer in team:
        link, problem = await _issue_first_credential(
            user=designer.user,
            officer=officer,
            account_created=designer.accountCreated,
            deliver_by=credential_links.EMAIL if by_email else credential_links.COPY_LINK,
            order_id=row.id,
            email_params={
                "context": "SANCTION",
                "sanctionOrderNo": stored_no,
                "workshopTitle": getattr(workshop, "title", None),
            },
        )
        if link is None and problem is None:
            continue
        credential_links_out.append(
            {
                "designerUserId": designer.userId,
                "designerName": designer.name,
                "designerEmail": designer.canonical,
                "signInEmail": str(getattr(designer.user, "email", "") or ""),
                "link": link,
                "problem": problem,
            }
        )

    lead_entry = next(
        (entry for entry in credential_links_out if entry["designerUserId"] == lead.userId), None
    )
    return {
        "sanctionOrder": await sanction_payload(
            row, workshop=workshop, designer=lead.user, officer=officer, team=team
        ),
        "linksEmailed": by_email,
        "credentialLink": (lead_entry or {}).get("link"),
        "credentialLinkProblem": (lead_entry or {}).get("problem"),
        "credentialLinks": credential_links_out,
        "reportCopySeeded": report_copy_seeded,
    }


async def _seed_the_report_copy(
    workshop: Any,
    officer: Any,
    designer_id: str,
    stored_no: str,
    order_date: date,
    *,
    amount: Any = None,
    planned: Mapping[str, Any] | None = None,
) -> bool:
    try:
        await seed_designer_prefill(
            workshop,
            officer,
            designer_id=designer_id,
            extra={
                **dict(planned or {}),
                "sanctionOrderNo": stored_no,
                "sanctionOrderDate": order_date.isoformat(),
                **({"sanctionAmount": f"{amount:.2f}"} if amount is not None else {}),
            },
        )
    except Exception:  # pragma: no cover - the seed's own contract is that it never fails a create
        log.exception("sanction order %s: stage-1 prefill failed", stored_no)
        return False
    try:
        entry = await db.dwstageentry.find_first(
            where={
                "designWorkshopId": workshop.id,
                "stageKey": "WORKSHOP_SETUP",
                "entityKey": "workshopSetup",
            }
        )
    except Exception:  # pragma: no cover - a read failing here is not the officer's problem either
        log.exception("sanction order %s: could not read back the stage-1 seed", stored_no)
        return False
    data = getattr(entry, "data", None) if entry is not None else None
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except ValueError:
            data = None
    seeded = isinstance(data, Mapping) and bool(data.get("sanctionOrderNo"))
    if not seeded:
        log.warning(
            "sanction order %s: stage 1 of workshop %s was not filled from the order%s",
            stored_no,
            getattr(workshop, "id", None),
            " or its plan row" if planned else "",
        )
    return seeded


async def _issue_first_credential(
    *,
    user: Any,
    officer: Any,
    account_created: bool,
    deliver_by: str = credential_links.COPY_LINK,
    order_id: str | None = None,
    email_params: dict[str, Any] | None = None,
) -> tuple[dict[str, Any] | None, str | None]:
    if not account_created:
        return None, None
    if str(getattr(user, "authProvider", "") or "").upper() == "GOOGLE":
        return None, None
    if deliver_by == credential_links.EMAIL and not getattr(user, "email", None):
        deliver_by = credential_links.COPY_LINK
    try:
        delivered = await credential_links.issue_link(
            user=user,
            purpose=credential_links.INVITE,
            issued_by_id=getattr(officer, "id", None),
            deliver_by=deliver_by,
            email_params=email_params,
            topic=f"sanction:{order_id}" if order_id else None,
        )
    except credential_links.IssueThrottled:
        return None, SANCTION_LINK_PROBLEM_THROTTLED
    except credential_links.DeliveryUnavailable:
        return await _issue_first_credential(
            user=user, officer=officer, account_created=account_created, order_id=order_id
        )
    return _link_payload(delivered), None


def _link_payload(delivered: Any) -> dict[str, Any]:
    return {
        "id": delivered.id,
        "link": delivered.link,
        "expiresAt": delivered.expiresAt,
        "purpose": delivered.purpose,
        "deliveredBy": delivered.deliveredBy,
    }


async def reissue_credential_link(
    row: Any,
    officer: Any,
    designer_user_id: str | None = None,
    deliver_by: str = credential_links.COPY_LINK,
) -> dict[str, Any]:
    target_id = designer_user_id or row.designerUserId
    member = next(
        (
            named
            for named in (getattr(row, "designers", None) or [])
            if getattr(named, "designerUserId", None) == target_id
        ),
        None,
    )
    if member is None and target_id != row.designerUserId:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail=SANCTION_LINK_NOT_ON_THIS_ORDER
        )
    created_by_this_order = (
        bool(getattr(member, "accountCreated", False))
        if member is not None
        else bool(getattr(row, "accountCreated", False))
    )
    if not created_by_this_order:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=SANCTION_LINK_NOT_THIS_REGISTERS_ACCOUNT,
        )
    user = await db.user.find_unique(where={"id": target_id})
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="The designer this order names no longer has an account.",
        )
    if is_master_email(getattr(user, "email", None)):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=SANCTION_MASTER_MAILBOX
        )
    if role_rank(user) >= role_rank(officer):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=SANCTION_LINK_OUTRANKS_OFFICER.format(role=role_value(user)),
        )
    if str(getattr(user, "authProvider", "") or "").upper() == "GOOGLE":
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"{user.name or user.email} signs in with Google — there is no password to set, so "
                "there is no sign-in link to issue."
            ),
        )
    if deliver_by == credential_links.EMAIL and not (mailer.mail_configured() and user.email):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="E-mail isn't available here. Copy the link instead.",
        )
    try:
        delivered = await credential_links.issue_link(
            user=user,
            issued_by_id=getattr(officer, "id", None),
            deliver_by=deliver_by,
            email_params={
                "context": "SANCTION",
                "sanctionOrderNo": getattr(row, "sanctionOrderNo", None),
                "workshopTitle": getattr(getattr(row, "designWorkshop", None), "title", None),
            },
            topic=f"sanction:{row.id}",
        )
    except credential_links.IssueThrottled as exc:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=SANCTION_THROTTLE_DETAIL,
            headers={"retry-after": str(exc.retry_after_minutes * 60)},
        ) from exc
    return _link_payload(delivered)



async def _stage_one_state(workshop_id: str) -> tuple[dict[str, Any] | None, Any]:
    rows = await entry_rows(workshop_id, stage_key=STAGE_ONE_KEY)
    singleton: dict[str, Any] | None = None
    for row in rows:
        if getattr(row, "entityKey", None) == STAGE_ONE_ENTITY:
            singleton = dict(getattr(row, "data", None) or {})
            break
    score = stage_completeness(stage(STAGE_ONE_KEY), singleton or {}, {})
    return singleton, score


async def _stage_one_singletons(workshop_ids: Sequence[str | None]) -> dict[str, dict[str, Any]]:
    wanted = sorted({workshop_id for workshop_id in workshop_ids if workshop_id})
    if not wanted:
        return {}
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": {"in": wanted}, "stageKey": STAGE_ONE_KEY, "deletedAt": None},
        order={"ordinal": "asc"},
    )
    found: dict[str, dict[str, Any]] = {}
    for row in rows:
        workshop_id = getattr(row, "designWorkshopId", None)
        if workshop_id in found or getattr(row, "entityKey", None) != STAGE_ONE_ENTITY:
            continue
        found[workshop_id] = dict(getattr(row, "data", None) or {})
    return found


def _report_copy_matches(singleton: dict[str, Any] | None, row: Any) -> bool | None:
    if singleton is None:
        return None
    same_no = normalise_sanction_order_no(singleton.get("sanctionOrderNo")) == str(
        getattr(row, "sanctionOrderKey", "") or ""
    )
    stored_date = getattr(row, "sanctionOrderDate", None)
    want = stored_date.date().isoformat() if isinstance(stored_date, datetime) else None
    same_date = str(singleton.get("sanctionOrderDate") or "")[:10] == (want or "")
    copied_amount = singleton.get("sanctionAmount")
    same_amount = True
    if copied_amount not in (None, ""):
        try:
            same_amount = Decimal(str(copied_amount)) == Decimal(str(row.sanctionAmount))
        except (InvalidOperation, AttributeError, TypeError):
            same_amount = False
    return bool(same_no and same_date and same_amount)


def _named_designer_rows(row: Any, team: Sequence[Any] | None) -> list[dict[str, Any]]:
    if team:
        return [
            {
                "designerUserId": designer.userId,
                "designerName": designer.name,
                "designerEmail": designer.canonical,
                "signInEmail": str(getattr(designer.user, "email", "") or ""),
                "accountCreated": bool(designer.accountCreated),
                "position": position,
            }
            for position, designer in enumerate(team)
        ]
    rows = list(getattr(row, "designers", None) or [])
    rows.sort(
        key=lambda item: (
            int(getattr(item, "position", 0) or 0),
            getattr(item, "createdAt", None) or datetime.min.replace(tzinfo=UTC),
            str(getattr(item, "designerUserId", "") or ""),
        )
    )
    return [
        {
            "designerUserId": str(getattr(item, "designerUserId", "") or ""),
            "designerName": str(getattr(getattr(item, "designerUser", None), "name", "") or ""),
            "designerEmail": str(getattr(item, "designerEmail", "") or ""),
            "signInEmail": str(
                getattr(getattr(item, "designerUser", None), "email", "") or ""
            ),
            "accountCreated": bool(getattr(item, "accountCreated", False)),
            "position": int(getattr(item, "position", 0) or 0),
        }
        for item in rows
    ]


async def sanction_payload(
    row: Any,
    *,
    workshop: Any | None = None,
    designer: Any | None = None,
    officer: Any | None = None,
    team: Sequence[Any] | None = None,
    stage_one: Mapping[str, dict[str, Any]] | None = None,
    mail: Mapping[str, Any] | None = None,
    planned_rows: Mapping[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    workshop = workshop or getattr(row, "designWorkshop", None)
    designer = designer or getattr(row, "designerUser", None)
    officer = officer or getattr(row, "createdBy", None)

    workshop_id = getattr(row, "designWorkshopId", None) or getattr(workshop, "id", None)
    if stage_one is None:
        singleton, score = await _stage_one_state(workshop_id)
    else:
        singleton = stage_one.get(workshop_id) if workshop_id else None
        score = stage_completeness(stage(STAGE_ONE_KEY), singleton or {}, {})

    if mail is None:
        mail = await order_mail_state([row.id])
    if planned_rows is None:
        planned_rows = await annual_plan.planned_rows_for_workshops([workshop_id] if workshop_id else [])

    order_date = getattr(row, "sanctionOrderDate", None)
    return {
        "id": row.id,
        "sanctionOrderNo": row.sanctionOrderNo,
        "plannedWorkshop": planned_rows.get(workshop_id) if workshop_id else None,
        "sanctionOrderDate": (
            order_date.date().isoformat() if isinstance(order_date, datetime) else None
        ),
        "sanctionAmount": str(row.sanctionAmount),
        "designerUserId": row.designerUserId,
        "designerName": getattr(designer, "name", None) or "",
        "designerEmail": row.designerEmail,
        "accountCreated": bool(row.accountCreated),
        "notes": row.notes,
        "designWorkshopId": workshop_id,
        "workshopTitle": getattr(workshop, "title", None) or "",
        "workshopStatus": str(
            getattr(getattr(workshop, "status", None), "value", None)
            or getattr(workshop, "status", None)
            or "DRAFT"
        ),
        "createdById": row.createdById,
        "createdByName": getattr(officer, "name", None),
        "createdAt": _iso(getattr(row, "createdAt", None)),
        "updatedAt": _iso(getattr(row, "updatedAt", None)),
        "reportCopyMatches": _report_copy_matches(singleton, row),
        "readyForWork": bool(score.is_complete),
        "missingMandatory": list(score.missing),
        "designers": _with_email_status(_named_designer_rows(row, team), mail.get(f"sanction:{row.id}", {})),
        "lastRemindedAt": (mail.get("__reminded__", {}) or {}).get(row.id),
        "sourceFilename": getattr(row, "sourceFilename", None),
        "sheetRow": getattr(row, "sheetRow", None),
    }


def _with_email_status(
    designers: list[dict[str, Any]], statuses: Mapping[str, dict[str, Any]]
) -> list[dict[str, Any]]:
    for entry in designers:
        entry["linkEmail"] = statuses.get(entry["designerUserId"])
    return designers


async def order_mail_state(order_ids: Sequence[str]) -> dict[str, Any]:
    if not order_ids or not mailer.mail_configured():
        return {}
    try:
        state: dict[str, Any] = await email_outbox.latest_by_topics([f"sanction:{oid}" for oid in order_ids])
        reminders = await db.emailmessage.find_many(
            where={"topic": {"in": [f"remind:{oid}" for oid in order_ids]}},
            order={"createdAt": "desc"},
            take=500,
        )
        reminded: dict[str, str] = {}
        for message in reminders:
            order_id = str(message.topic).split(":", 1)[1]
            if order_id not in reminded and message.createdAt is not None:
                reminded[order_id] = message.createdAt.isoformat()
        state["__reminded__"] = reminded
        return state
    except Exception:
        log.exception("sanction orders: the e-mail state could not be read")
        return {}

async def sanction_payloads(rows: Sequence[Any]) -> list[dict[str, Any]]:
    workshop_ids = [getattr(row, "designWorkshopId", None) for row in rows]
    stage_one = await _stage_one_singletons(workshop_ids)
    mail = await order_mail_state([row.id for row in rows])
    planned_rows = await annual_plan.planned_rows_for_workshops([w for w in workshop_ids if w])
    return [
        await sanction_payload(row, stage_one=stage_one, mail=mail, planned_rows=planned_rows) for row in rows
    ]


async def remind_designers(row: Any, officer: Any) -> dict[str, Any]:
    if not mailer.mail_configured():
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="E-mail isn't set up here, so reminders can't be sent.",
        )
    workshop_id = getattr(row, "designWorkshopId", None)
    _, score = await _stage_one_state(workshop_id)
    missing = list(score.missing)
    if score.is_complete or not missing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Workshop Setup is complete. Nothing to remind."
        )
    workshop = getattr(row, "designWorkshop", None) or await db.designworkshop.find_unique(
        where={"id": workshop_id}
    )
    ids = [entry["designerUserId"] for entry in _named_designer_rows(row, None)] or [row.designerUserId]
    people = {person.id: person for person in await db.user.find_many(where={"id": {"in": ids}})}
    designers = [people[uid] for uid in ids if uid in people]
    tally = await email_outbox.send_setup_reminders(row, workshop, designers, missing, actor=officer)
    return {**tally, "missing": missing}


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


SANCTION_INCLUDE: dict[str, Any] = {
    "designWorkshop": True,
    "designerUser": True,
    "createdBy": True,
    "designers": {"include": {"designerUser": True}},
}


async def awaiting_count() -> int:
    rows = await db.sanctionorder.find_many(order={"sanctionOrderDate": "desc"})
    singletons = await _stage_one_singletons([row.designWorkshopId for row in rows])
    spec = stage(STAGE_ONE_KEY)
    awaiting = 0
    for row in rows:
        score = stage_completeness(spec, singletons.get(row.designWorkshopId) or {}, {})
        if not score.is_complete:
            awaiting += 1
    return awaiting
