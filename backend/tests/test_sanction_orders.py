from __future__ import annotations

import asyncio
import json
import time
import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from typing import Any
from urllib.parse import parse_qs, urlsplit

import pytest
from conftest import needs_db

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import access_roster, credential_links
from app.services.designers import DERIVED_EMPANELMENT_NOTE, canonical_email, normalise_email
from app.services.sanction_orders import (
    SANCTION_ADMISSION_NOTE,
    SANCTION_MASTER_MAILBOX,
    normalise_sanction_order_no,
)
from app.services.stage_schema import registry_version, stage, stage_completeness
from prisma import Json

pytestmark = [needs_db]

PASSWORD = "sanction-orders-officer-password"
AMOUNT = "450000.00"
TOP_OF_COLUMN = "999999999999.99"

INELIGIBLE_ROLES = (
    "INSPECTOR",
    "PROFESSOR",
)

BARRED_STATES = ("REJECTED", "SUSPENDED")

MASTER_MAILBOX_BODIES = ("master_lead", "master_dotless", "master_tagged", "master_codesigner")


def _body(**overrides: Any) -> dict[str, Any]:
    stamp = uuid.uuid4().hex[:8]
    body = {
        "sanctionOrderNo": f"SO/2026/{stamp}",
        "sanctionOrderDate": "2026-04-01",
        "sanctionAmount": AMOUNT,
        "designerName": f"Designer {stamp}",
        "designerEmail": f"sanction-designer-{stamp}@example.org",
        "notes": None,
    }
    body.update(overrides)
    return body


def _created_order(reply: Any) -> dict[str, Any] | None:
    if reply.status_code != 201:
        return None
    try:
        return reply.json()["sanctionOrder"]
    except (ValueError, KeyError, TypeError):
        return None


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    officer_email = f"sanction-officer-{stamp}@example.org"

    facts: dict[str, Any] = {"stamp": stamp}
    bodies: dict[str, dict[str, Any]] = {}
    answers: dict[str, Any] = {}
    rows: dict[str, Any] = {}
    facts["bodies"] = bodies
    facts["answers"] = answers
    facts["rows"] = rows

    async def seed() -> None:
        from prisma.errors import PrismaError

        await db.connect()
        try:
            officer = await db.user.create(
                data={
                    "email": officer_email,
                    "name": f"Sanction officer {stamp}",
                    "role": "ASSISTANT_DIRECTOR",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": officer_email,
                    "status": "ACTIVE",
                    "admitRole": "ASSISTANT_DIRECTOR",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_sanction_orders.py.",
                }
            )
            facts["officer"] = officer

            bodies["five_fields"] = _body()
            bodies["round_trip"] = _body(sanctionAmount=TOP_OF_COLUMN)
            bodies["stage_copy"] = _body()
            bodies["missing_list"] = _body()
            bodies["sanctioned"] = _body()
            bodies["pending_queue"] = _body()

            existing = f"sanction-existing-{stamp}@example.org"
            await db.user.create(
                data={
                    "email": existing,
                    "name": f"Existing {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            facts["existing_address"] = existing
            bodies["existing"] = _body(designerEmail=existing)

            admin_address = f"sanction-admin-{stamp}@example.org"
            await db.user.create(
                data={
                    "email": admin_address,
                    "name": f"Admin designer {stamp}",
                    "role": "ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            facts["admin_address"] = admin_address
            bodies["admin"] = _body(designerEmail=admin_address)

            dotted = f"sanction.alias.{stamp}@gmail.com"
            facts["dotted"] = dotted
            bodies["alias_dotted"] = _body(designerEmail=dotted)
            bodies["alias_canonical"] = _body(designerEmail=canonical_email(dotted))

            split = f"sanction.split.{stamp}@gmail.com"
            for spelling in (normalise_email(split), canonical_email(split)):
                await db.user.create(
                    data={
                        "email": spelling,
                        "name": f"Split {stamp}",
                        "role": "RESEARCHER",
                        "passwordHash": hash_password(PASSWORD),
                    }
                )
            facts["split"] = split
            bodies["split"] = _body(designerEmail=split)

            facts["barred_addresses"] = {}
            for state in BARRED_STATES:
                address = f"sanction-barred-{state.lower()}-{stamp}@example.org"
                await db.accessroster.create(
                    data={
                        "email": canonical_email(address),
                        "status": state,
                        "notes": "Barred by hand. A sanction order must not undo this.",
                    }
                )
                facts["barred_addresses"][state] = address
                bodies[f"barred_{state}"] = _body(designerEmail=address)

            ended = f"sanction-ended-{stamp}@example.org"
            await db.designerroster.create(
                data={
                    "email": canonical_email(ended),
                    "isActive": False,
                    "revokedAt": datetime.now(UTC),
                    "notes": "Empanelment ended by hand. Do not restore.",
                }
            )
            facts["ended_address"] = ended
            bodies["ended"] = _body(designerEmail=ended)

            number = f"SO/RACE/{stamp}"
            race_address = f"sanction-race-{stamp}@example.org"
            squatter = await db.designworkshop.create(
                data={
                    "title": f"Squatter {stamp}",
                    "templateId": "DCH_STANDARD",
                    "createdById": officer.id,
                    "status": "DRAFT",
                }
            )
            await db.sanctionorder.create(
                data={
                    "sanctionOrderNo": f"{number} (already recorded)",
                    "sanctionOrderKey": normalise_sanction_order_no(number),
                    "sanctionOrderDate": datetime(2026, 4, 1, tzinfo=UTC),
                    "sanctionAmount": "1.00",
                    "designerUserId": officer.id,
                    "designerEmail": f"squatter-{stamp}@example.org",
                    "designWorkshopId": squatter.id,
                    "createdById": officer.id,
                }
            )
            facts["race_number"] = number
            facts["race_address"] = race_address
            bodies["race"] = _body(sanctionOrderNo=number, designerEmail=race_address)

            throttle_address = f"sanction-throttle-{stamp}@example.org"
            throttled = await db.user.create(
                data={
                    "email": throttle_address,
                    "name": f"Throttled {stamp}",
                    "role": "RESEARCHER",
                    "passwordHash": None,
                }
            )
            for _ in range(credential_links.ISSUE_BUDGET):
                await db.passwordresettoken.create(
                    data={
                        "userId": throttled.id,
                        "tokenHash": uuid.uuid4().hex,
                        "purpose": "INVITE",
                        "expiresAt": datetime.now(UTC),
                    }
                )
            facts["throttle_address"] = throttle_address

            designer = await db.user.create(
                data={
                    "email": f"sanction-refused-{stamp}@example.org",
                    "name": f"Refused {stamp}",
                    "role": "DESIGNER",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            facts["refused_designer_id"] = designer.id

            facts["ineligible_addresses"] = {}
            for role in INELIGIBLE_ROLES:
                address = f"sanction-ineligible-{role.lower()}-{stamp}@example.org"
                await db.user.create(
                    data={
                        "email": address,
                        "name": f"Named {role} {stamp}",
                        "role": role,
                        "passwordHash": hash_password(PASSWORD),
                    }
                )
                facts["ineligible_addresses"][role] = address
                bodies[f"ineligible_{role}"] = _body(designerEmail=address)

            bodies["own_mailbox"] = _body(designerEmail=officer.email)

            master = f"sanction.master.{stamp}@gmail.com"
            facts["master_configured"] = master
            bodies["master_lead"] = _body(designerEmail=master)
            bodies["master_dotless"] = _body(designerEmail=canonical_email(master))
            bodies["master_tagged"] = _body(designerEmail=f"Sanction.Master.{stamp}+x@googlemail.com")
            bodies["master_codesigner"] = _body(
                coDesigners=[
                    {"name": "Co-designer", "email": f"sanctionmaster{stamp}+team@gmail.com"}
                ]
            )

            already = f"sanction-already-admitted-{stamp}@example.org"
            admins_note = (
                "Admitted 2025-04 on the department's request; craft taxonomy contributor."
            )
            joined = datetime(2024, 4, 1, tzinfo=UTC)
            deciding = await db.user.create(
                data={
                    "email": f"sanction-deciding-admin-{stamp}@example.org",
                    "name": f"Deciding admin {stamp}",
                    "role": "ADMIN",
                    "passwordHash": hash_password(PASSWORD),
                }
            )
            await db.accessroster.create(
                data={
                    "email": canonical_email(already),
                    "status": "ACTIVE",
                    "admitRole": "RESEARCHER",
                    "fullName": "R. Sundaram (as the admin typed it)",
                    "notes": admins_note,
                    "joinedAt": joined,
                    "decidedAt": joined,
                    "decidedById": deciding.id,
                }
            )
            facts["already_address"] = already
            facts["already_note"] = admins_note
            facts["already_joined"] = joined
            facts["deciding_admin_id"] = deciding.id
            bodies["already_admitted"] = _body(
                designerEmail=already, designerName="Sundaram R"
            )

            foreign = await db.passwordresettoken.create(
                data={
                    "userId": deciding.id,
                    "tokenHash": uuid.uuid4().hex,
                    "purpose": "RESET",
                    "expiresAt": datetime.now(UTC) + timedelta(hours=2),
                }
            )
            facts["foreign_token_id"] = foreign.id
            bodies["revoke"] = _body()

            check_workshop = await db.designworkshop.create(
                data={
                    "title": f"Check constraint {stamp}",
                    "templateId": "DCH_STANDARD",
                    "createdById": officer.id,
                    "status": "DRAFT",
                }
            )
            try:
                await db.sanctionorder.create(
                    data={
                        "sanctionOrderNo": f"SO/CHECK/{stamp}",
                        "sanctionOrderKey": normalise_sanction_order_no(f"SO/CHECK/{stamp}"),
                        "sanctionOrderDate": datetime(2026, 4, 1, tzinfo=UTC),
                        "sanctionAmount": "0.00",
                        "designerUserId": officer.id,
                        "designerEmail": f"check-{stamp}@example.org",
                        "designWorkshopId": check_workshop.id,
                        "createdById": officer.id,
                    }
                )
            except Exception as exc:  # noqa: BLE001 - the CLASS is the finding; see the test
                facts["non_positive_refusal"] = type(exc).__name__
                facts["non_positive_refusal_is_prisma"] = isinstance(exc, PrismaError)
            else:
                facts["non_positive_refusal"] = None
                facts["non_positive_refusal_is_prisma"] = False

            facts["pending_before"] = await access_roster.pending_count()
        finally:
            await db.disconnect()

    def act(client: Any) -> None:
        headers = _headers(facts)

        def post(key: str) -> None:
            answers[key] = client.post(
                "/api/sanction-orders", json=bodies[key], headers=headers
            )

        post("five_fields")
        post("existing")
        post("admin")
        post("alias_dotted")
        post("alias_canonical")
        post("split")
        for state in BARRED_STATES:
            post(f"barred_{state}")
        post("ended")
        post("race")
        post("round_trip")
        post("stage_copy")
        post("missing_list")
        post("sanctioned")
        post("pending_queue")
        for role in INELIGIBLE_ROLES:
            post(f"ineligible_{role}")
        post("own_mailbox")
        post("already_admitted")

        settings = get_settings()
        previous_master = settings.master_admin_email
        settings.master_admin_email = facts["master_configured"]
        try:
            for key in MASTER_MAILBOX_BODIES:
                post(key)
        finally:
            settings.master_admin_email = previous_master

        post("revoke")
        order = _created_order(answers["revoke"])
        own_link = (answers["revoke"].json().get("credentialLink") or {}) if order else {}
        facts["own_token_id"] = own_link.get("id")
        if order is not None:
            path = f"/api/sanction-orders/{order['id']}/credential-link/revoke"
            for key, token_id in (
                ("revoke_foreign", facts["foreign_token_id"]),
                ("revoke_unknown", "cmnosuchlinkatall00000000"),
                ("revoke_own", facts["own_token_id"]),
            ):
                answers[key] = client.post(path, params={"tokenId": token_id}, headers=headers)

    async def observe() -> None:
        await db.connect()
        try:
            facts["pending_after"] = await access_roster.pending_count()

            payload = _created_order(answers["five_fields"])
            if payload is not None:
                canonical = canonical_email(bodies["five_fields"]["designerEmail"])
                account = await db.user.find_unique(
                    where={"email": normalise_email(bodies["five_fields"]["designerEmail"])}
                )
                workshop = await db.designworkshop.find_unique(
                    where={"id": payload["designWorkshopId"]}
                )
                rows["five_fields"] = {
                    "access": await db.accessroster.find_unique(where={"email": canonical}),
                    "roster": await db.designerroster.find_unique(where={"email": canonical}),
                    "account": account,
                    "profile": (
                        await db.designerprofile.find_unique(where={"userId": account.id})
                        if account is not None
                        else None
                    ),
                    "workshop": workshop,
                    "viewers": (
                        await db.designworkshopviewer.find_many(
                            where={"designWorkshopId": workshop.id, "userId": account.id}
                        )
                        if workshop is not None and account is not None
                        else []
                    ),
                    "order": await db.sanctionorder.find_unique(where={"id": payload["id"]}),
                }

            rows["existing_accounts"] = await db.user.find_many(
                where={"email": {"equals": facts["existing_address"], "mode": "insensitive"}}
            )

            rows["admin_account"] = await db.user.find_unique(
                where={"email": facts["admin_address"]}
            )

            dotted = facts["dotted"]
            rows["alias"] = {
                "account": await db.user.find_unique(where={"email": normalise_email(dotted)}),
                "access": await db.accessroster.find_unique(
                    where={"email": canonical_email(dotted)}
                ),
                "roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(dotted)}
                ),
            }

            rows["split_orders"] = await db.sanctionorder.find_many(
                where={
                    "sanctionOrderKey": normalise_sanction_order_no(
                        bodies["split"]["sanctionOrderNo"]
                    )
                }
            )

            rows["barred"] = {}
            for state in BARRED_STATES:
                address = facts["barred_addresses"][state]
                rows["barred"][state] = {
                    "row": await db.accessroster.find_unique(
                        where={"email": canonical_email(address)}
                    ),
                    "orders": await db.sanctionorder.find_many(
                        where={
                            "sanctionOrderKey": normalise_sanction_order_no(
                                bodies[f"barred_{state}"]["sanctionOrderNo"]
                            )
                        }
                    ),
                    "accounts": await db.user.find_many(
                        where={"email": {"equals": address, "mode": "insensitive"}}
                    ),
                }

            rows["ended_roster"] = await db.designerroster.find_unique(
                where={"email": canonical_email(facts["ended_address"])}
            )

            race_address = facts["race_address"]
            rows["race"] = {
                "accounts": await db.user.find_many(
                    where={"email": {"equals": race_address, "mode": "insensitive"}}
                ),
                "access": await db.accessroster.find_unique(
                    where={"email": canonical_email(race_address)}
                ),
                "roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(race_address)}
                ),
                "workshops": await db.designworkshop.find_many(
                    where={"title": {"contains": facts["race_number"]}}
                ),
            }

            payload = _created_order(answers["round_trip"])
            if payload is not None:
                rows["round_trip"] = await db.sanctionorder.find_unique(
                    where={"id": payload["id"]}
                )

            payload = _created_order(answers["stage_copy"])
            if payload is not None:
                entry = await db.dwstageentry.find_first(
                    where={
                        "designWorkshopId": payload["designWorkshopId"],
                        "stageKey": "WORKSHOP_SETUP",
                        "entityKey": "workshopSetup",
                        "deletedAt": None,
                    }
                )
                data = dict(entry.data or {}) if entry is not None else {}
                if entry is not None:
                    edited = dict(data)
                    edited["sanctionOrderNo"] = "SOMETHING ELSE ENTIRELY"
                    await db.dwstageentry.update(
                        where={"id": entry.id},
                        data={"data": Json(json.loads(json.dumps(edited)))},
                    )
                rows["stage_copy"] = {
                    "entry": entry,
                    "data": data,
                    "order": await db.sanctionorder.find_unique(where={"id": payload["id"]}),
                }

            payload = _created_order(answers["missing_list"])
            if payload is not None:
                entry = await db.dwstageentry.find_first(
                    where={
                        "designWorkshopId": payload["designWorkshopId"],
                        "stageKey": "WORKSHOP_SETUP",
                        "entityKey": "workshopSetup",
                        "deletedAt": None,
                    }
                )
                rows["missing_list_singleton"] = (
                    dict(getattr(entry, "data", None) or {}) if entry else {}
                )

            payload = _created_order(answers["sanctioned"])
            if payload is not None:
                ad_hoc = await db.designworkshop.create(
                    data={
                        "title": f"By hand {stamp}",
                        "templateId": "DCH_STANDARD",
                        "createdById": facts["officer"].id,
                        "status": "DRAFT",
                    }
                )
                rows["sanctioned"] = {
                    "workshop": await db.designworkshop.find_unique(
                        where={"id": payload["designWorkshopId"]},
                        include={"sanctionOrder": True},
                    ),
                    "ad_hoc": await db.designworkshop.find_unique(
                        where={"id": ad_hoc.id}, include={"sanctionOrder": True}
                    ),
                }

            rows["ineligible"] = {}
            for role in INELIGIBLE_ROLES:
                address = facts["ineligible_addresses"][role]
                account = await db.user.find_unique(where={"email": address})
                rows["ineligible"][role] = {
                    "account": account,
                    "orders": await db.sanctionorder.find_many(
                        where={
                            "sanctionOrderKey": normalise_sanction_order_no(
                                bodies[f"ineligible_{role}"]["sanctionOrderNo"]
                            )
                        }
                    ),
                    "access": await db.accessroster.find_unique(
                        where={"email": canonical_email(address)}
                    ),
                    "roster": await db.designerroster.find_unique(
                        where={"email": canonical_email(address)}
                    ),
                    "viewers": (
                        await db.designworkshopviewer.find_many(where={"userId": account.id})
                        if account is not None
                        else []
                    ),
                }

            own = facts["officer"].email
            rows["own_mailbox"] = {
                "orders": await db.sanctionorder.find_many(
                    where={
                        "sanctionOrderKey": normalise_sanction_order_no(
                            bodies["own_mailbox"]["sanctionOrderNo"]
                        )
                    }
                ),
                "roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(own)}
                ),
                "access": await db.accessroster.find_unique(
                    where={"email": canonical_email(own)}
                ),
            }

            rows["already_admitted"] = await db.accessroster.find_unique(
                where={"email": canonical_email(facts["already_address"])}
            )

            master = facts["master_configured"]
            spellings = sorted(
                {
                    normalise_email(bodies[key]["designerEmail"])
                    for key in ("master_lead", "master_dotless", "master_tagged")
                }
                | {canonical_email(master)}
            )
            team_lead = normalise_email(bodies["master_codesigner"]["designerEmail"])
            rows["master_mailbox"] = {
                "accounts": await access_roster.accounts_on_the_mailbox_for_sign_in(master),
                "access": await db.accessroster.find_many(where={"email": {"in": spellings}}),
                "roster": await db.designerroster.find_many(where={"email": {"in": spellings}}),
                "orders": await db.sanctionorder.find_many(
                    where={
                        "sanctionOrderKey": {
                            "in": [
                                normalise_sanction_order_no(bodies[key]["sanctionOrderNo"])
                                for key in MASTER_MAILBOX_BODIES
                            ]
                        }
                    }
                ),
                "lead_accounts": await db.user.find_many(
                    where={"email": {"equals": team_lead, "mode": "insensitive"}}
                ),
                "lead_access": await db.accessroster.find_unique(
                    where={"email": canonical_email(team_lead)}
                ),
                "lead_roster": await db.designerroster.find_unique(
                    where={"email": canonical_email(team_lead)}
                ),
            }

            rows["revoke"] = {
                "foreign": await db.passwordresettoken.find_unique(
                    where={"id": facts["foreign_token_id"]}
                ),
                "own": (
                    await db.passwordresettoken.find_unique(where={"id": facts["own_token_id"]})
                    if facts.get("own_token_id")
                    else None
                ),
            }
        finally:
            await db.disconnect()

    asyncio.run(seed())
    with TestClient(app) as client:
        act(client)
    asyncio.run(observe())
    with TestClient(app) as client:
        facts["client"] = client
        yield facts


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any]) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['officer'].id)}"}


def _post(world: dict[str, Any], body: dict[str, Any]) -> Any:
    return world["client"].post("/api/sanction-orders", json=body, headers=_headers(world))



def test_five_fields_produce_seven_rows(world) -> None:
    body = world["bodies"]["five_fields"]
    answer = world["answers"]["five_fields"]
    assert answer.status_code == 201, answer.text

    seen = world["rows"]["five_fields"]
    access = seen["access"]
    roster = seen["roster"]
    account = seen["account"]
    profile = seen["profile"]
    workshop = seen["workshop"]
    viewers = seen["viewers"]
    order = seen["order"]

    assert access is not None and access_roster.status_of(access) == "ACTIVE"
    assert str(getattr(access.admitRole, "value", access.admitRole)) == "DESIGNER"
    assert roster is not None and roster.isActive is True
    assert roster.addedById == world["officer"].id
    assert roster.notes != DERIVED_EMPANELMENT_NOTE
    assert roster.notes == SANCTION_ADMISSION_NOTE.format(
        no=body["sanctionOrderNo"], officer=world["officer"].name
    )
    assert roster.fullName == body["designerName"], "the allow-list name did not reach the roster"
    assert account is not None
    assert str(getattr(account.role, "value", account.role)) == "DESIGNER"
    assert account.mustChangePassword is False
    assert account.passwordSetAt is not None
    assert account.passwordHash, "a NULL hash makes the account unreachable if the link is lost"
    assert profile is not None
    assert workshop is not None
    assert workshop.createdById == world["officer"].id
    assert workshop.schemaVersion == registry_version()
    assert len(viewers) == 1
    assert order is not None
    assert order.accountCreated is True
    assert order.designerUserId == account.id


def test_a_new_account_is_offered_a_seventy_two_hour_invite_link(world, client) -> None:
    answer = _post(world, _body())
    assert answer.status_code == 201, answer.text
    link = answer.json()["credentialLink"]
    assert link is not None
    assert link["purpose"] == credential_links.INVITE
    assert link["deliveredBy"] == "COPY_LINK", "there is no mailer; the officer is the transport"
    assert answer.json()["credentialLinkProblem"] is None


def test_a_designer_who_signs_in_with_google_first_is_not_held_and_the_link_still_works(
    world, client, monkeypatch
) -> None:
    from app.api.routes import auth as auth_routes

    body = _body()
    created = _post(world, body)
    assert created.status_code == 201, created.text
    token = parse_qs(urlsplit(created.json()["credentialLink"]["link"]).query)["token"][0]

    monkeypatch.setattr(
        auth_routes,
        "verify_google_token",
        lambda _token: {
            "email": body["designerEmail"],
            "email_verified": True,
            "name": body["designerName"],
            "picture": None,
        },
    )
    signed_in = client.post("/api/auth/login", json={"googleIdToken": "stand-in"})
    assert signed_in.status_code == 200, signed_in.text
    assert signed_in.json()["user"]["mustChangePassword"] is False
    session = {"Authorization": f"Bearer {signed_in.json()['accessToken']}"}
    workshops = client.get("/api/design-workshops", headers=session)
    assert workshops.status_code == 200, workshops.text
    assert "X-Password-Change-Required" not in workshops.headers

    redeemed = client.post(
        "/api/auth/set-password", json={"token": token, "password": "the-designers-own-choice"}
    )
    assert redeemed.status_code == 200, redeemed.text
    assert client.get("/api/design-workshops", headers=session).status_code == 401

    time.sleep(1.1)
    again = client.post(
        "/api/auth/login",
        json={"email": body["designerEmail"], "password": "the-designers-own-choice"},
    )
    assert again.status_code == 200, again.text



def test_an_existing_account_is_reused_and_never_duplicated(world) -> None:
    answer = world["answers"]["existing"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()

    accounts = world["rows"]["existing_accounts"]
    assert len(accounts) == 1, "a second account was minted for a mailbox that already had one"
    assert str(getattr(accounts[0].role, "value", accounts[0].role)) == "DESIGNER"
    assert payload["sanctionOrder"]["accountCreated"] is False
    assert payload["credentialLink"] is None


def test_an_admin_named_on_a_sanction_order_is_not_demoted(world) -> None:
    assert world["answers"]["admin"].status_code == 201, world["answers"]["admin"].text
    account = world["rows"]["admin_account"]
    assert str(getattr(account.role, "value", account.role)) == "ADMIN"


def test_a_gmail_alias_lands_on_one_mailbox_and_can_still_sign_in(world) -> None:
    dotted = world["dotted"]
    canonical = canonical_email(dotted)
    assert canonical != normalise_email(dotted), "the fixture is not testing an alias"

    answer = world["answers"]["alias_dotted"]
    assert answer.status_code == 201, answer.text
    assert answer.json()["sanctionOrder"]["designerEmail"] == canonical

    seen = world["rows"]["alias"]
    assert seen["account"] is not None, (
        "the account must be reachable at the address the designer types"
    )
    assert seen["access"] is not None
    assert seen["roster"] is not None

    second = world["answers"]["alias_canonical"]
    assert second.status_code == 201, second.text
    assert second.json()["sanctionOrder"]["accountCreated"] is False
    assert second.json()["sanctionOrder"]["designerUserId"] == seen["account"].id


def test_two_accounts_on_one_mailbox_refuse_the_whole_create(world) -> None:
    answer = world["answers"]["split"]
    assert answer.status_code == 409, answer.text
    assert canonical_email(world["split"]) in answer.json()["detail"]
    assert world["rows"]["split_orders"] == []



def test_a_duplicate_sanction_number_is_a_409_naming_the_existing_order(world, client) -> None:
    body = _body()
    assert _post(world, body).status_code == 201
    again = _post(world, _body(sanctionOrderNo=body["sanctionOrderNo"]))
    assert again.status_code == 409, again.text
    detail = again.json()["detail"]
    assert body["sanctionOrderNo"] in detail
    assert "Sanctioned workshop" in detail


@pytest.mark.parametrize("separator", ["-", " ", "."])
def test_three_spellings_of_one_number_are_one_order(world, client, separator: str) -> None:
    stamp = uuid.uuid4().hex[:8]
    first = f"SO/2026/{stamp}"
    assert _post(world, _body(sanctionOrderNo=first)).status_code == 201
    restyled = first.replace("/", separator).lower()
    again = _post(world, _body(sanctionOrderNo=restyled))
    assert again.status_code == 409, again.text



@pytest.mark.parametrize("state", list(BARRED_STATES))
def test_a_barred_address_is_refused_and_the_decision_still_stands(world, state: str) -> None:
    answer = world["answers"][f"barred_{state}"]
    assert answer.status_code == 422, answer.text
    assert "allow-list" in answer.json()["detail"]

    seen = world["rows"]["barred"][state]
    assert access_roster.status_of(seen["row"]) == state, (
        "a sanction order overturned an admin's decision"
    )
    assert seen["orders"] == []
    assert seen["accounts"] == [], "a refused create left an orphan account behind"


def test_an_ended_empanelment_is_not_restored_by_a_sanction_order(world) -> None:
    answer = world["answers"]["ended"]
    assert answer.status_code == 422, answer.text
    assert "empanelment" in answer.json()["detail"].lower()
    assert world["rows"]["ended_roster"].isActive is False, (
        "a sanction order restored an empanelment an admin ended"
    )



def test_a_refused_create_leaves_no_orphan_anything(world) -> None:
    answer = world["answers"]["race"]
    assert answer.status_code == 409, answer.text

    seen = world["rows"]["race"]
    assert seen["accounts"] == [], "the account committed outside the transaction"
    assert seen["access"] is None, "the allow-list row committed outside the transaction"
    assert seen["roster"] is None, "the empanelment committed outside the transaction"
    assert seen["workshops"] == [], "the workshop committed outside the transaction"



def test_the_amount_never_reaches_the_wire_as_a_float(world, client) -> None:
    answer = _post(world, _body(sanctionAmount=AMOUNT))
    assert answer.status_code == 201, answer.text
    parsed = json.loads(answer.content)["sanctionOrder"]["sanctionAmount"]
    assert isinstance(parsed, str), f"the amount arrived as {type(parsed).__name__}"
    assert Decimal(parsed) == Decimal(AMOUNT), f"the amount changed value on the wire: {parsed!r}"
    assert f'"sanctionAmount":"{parsed}"' in answer.content.decode().replace(" ", ""), (
        "the amount is not quoted on the wire, so it is a JSON number and a client will parse it "
        "as a float"
    )


def test_the_amount_round_trips_exactly_at_the_top_of_the_column(world) -> None:
    answer = world["answers"]["round_trip"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]
    assert payload["sanctionAmount"] == TOP_OF_COLUMN

    row = world["rows"]["round_trip"]
    assert Decimal(str(row.sanctionAmount)) == Decimal(TOP_OF_COLUMN)


@pytest.mark.parametrize("amount", ["0", "0.00", "-1.00"])
def test_a_zero_or_negative_amount_is_refused_at_the_door(world, client, amount: str) -> None:
    assert _post(world, _body(sanctionAmount=amount)).status_code == 422


def test_the_table_itself_refuses_a_non_positive_amount(world) -> None:
    assert world["non_positive_refusal"] is not None, (
        "the table accepted a zero-rupee sanction order — the CHECK constraint is gone, and a psql "
        "session or an importer can now write one"
    )
    assert world["non_positive_refusal_is_prisma"], (
        f"the insert failed with {world['non_positive_refusal']}, which is not a PrismaError — "
        "something other than the CHECK constraint refused it, so the constraint is untested"
    )



def test_the_stage_one_copy_is_seeded_and_is_a_copy(world, client) -> None:
    body = world["bodies"]["stage_copy"]
    answer = world["answers"]["stage_copy"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]
    assert payload["reportCopyMatches"] is True

    seen = world["rows"]["stage_copy"]
    assert seen["entry"] is not None, "the prefill did not seed stage 1"
    assert seen["data"].get("sanctionOrderNo") == body["sanctionOrderNo"]
    assert str(seen["data"].get("sanctionOrderDate") or "")[:10] == body["sanctionOrderDate"]

    assert seen["order"].sanctionOrderNo == body["sanctionOrderNo"], "a stage edit moved the register"
    again = client.get(f"/api/sanction-orders/{payload['id']}", headers=_headers(world))
    assert again.status_code == 200, again.text
    assert again.json()["reportCopyMatches"] is False


def test_the_missing_list_is_the_registrys_and_not_a_second_count(world) -> None:
    answer = world["answers"]["missing_list"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]

    singleton = world["rows"]["missing_list_singleton"]
    expected = stage_completeness(stage("WORKSHOP_SETUP"), singleton, {})
    assert payload["missingMandatory"] == list(expected.missing)
    assert payload["readyForWork"] is False
    assert "Workshop title" in " · ".join(payload["missingMandatory"])


def test_a_sanctioned_workshop_carries_its_order_and_an_ad_hoc_one_does_not(world) -> None:
    answer = world["answers"]["sanctioned"]
    assert answer.status_code == 201, answer.text
    payload = answer.json()["sanctionOrder"]

    seen = world["rows"]["sanctioned"]
    assert seen["workshop"].sanctionOrder is not None
    assert seen["workshop"].sanctionOrder.id == payload["id"]
    assert seen["ad_hoc"].sanctionOrder is None



def test_the_sanction_order_admits_nobody_to_the_pending_queue(world) -> None:
    assert world["answers"]["pending_queue"].status_code == 201, (
        world["answers"]["pending_queue"].text
    )
    assert world["pending_after"] == world["pending_before"]


def test_a_throttled_link_does_not_roll_back_the_sanction_order(world, client) -> None:
    existing = _post(world, _body(designerEmail=world["throttle_address"]))
    assert existing.status_code == 201, existing.text
    assert existing.json()["credentialLink"] is None
    assert existing.json()["credentialLinkProblem"] is None, (
        "an account that already existed has no link to fail to issue, so there is nothing to report"
    )

    fresh = _post(world, _body())
    assert fresh.status_code == 201, fresh.text
    assert fresh.json()["sanctionOrder"]["accountCreated"] is True
    order_id = fresh.json()["sanctionOrder"]["id"]

    for _ in range(credential_links.ISSUE_BUDGET - 1):
        spent = client.post(
            f"/api/sanction-orders/{order_id}/credential-link", headers=_headers(world)
        )
        assert spent.status_code == 200, spent.text
        assert spent.json()["purpose"] == credential_links.INVITE

    again = client.post(
        f"/api/sanction-orders/{order_id}/credential-link", headers=_headers(world)
    )
    assert again.status_code == 429, again.text
    assert again.headers.get("retry-after")
    assert "hour" in again.json()["detail"]


def test_the_reissue_arm_refuses_an_account_this_register_did_not_create(world, client) -> None:
    answer = _post(world, _body(designerEmail=world["throttle_address"]))
    assert answer.status_code == 201, answer.text
    assert answer.json()["sanctionOrder"]["accountCreated"] is False
    order_id = answer.json()["sanctionOrder"]["id"]

    refused = client.post(
        f"/api/sanction-orders/{order_id}/credential-link", headers=_headers(world)
    )
    assert refused.status_code == 422, refused.text
    detail = refused.json()["detail"]
    assert "did not create" in detail
    assert "users screen" in detail, "the officer is not told where the honest door is"


def test_the_revoke_arm_withdraws_only_a_link_belonging_to_a_designer_on_the_order(world) -> None:
    answers, rows = world["answers"], world["rows"]
    assert answers["revoke"].status_code == 201, answers["revoke"].text
    assert world["own_token_id"], "the order's designer was offered no link to withdraw"

    foreign = answers["revoke_foreign"]
    assert foreign.status_code == 404, foreign.text
    assert "belongs to a designer on this sanction order" in foreign.json()["detail"]
    assert rows["revoke"]["foreign"] is not None
    assert rows["revoke"]["foreign"].revokedAt is None, "a foreign link was withdrawn anyway"

    assert answers["revoke_unknown"].status_code == 404, answers["revoke_unknown"].text

    assert answers["revoke_own"].status_code == 200, answers["revoke_own"].text
    assert rows["revoke"]["own"] is not None
    assert rows["revoke"]["own"].revokedAt is not None


def test_a_designer_may_not_record_a_sanction_order(world, client) -> None:
    answer = client.post(
        "/api/sanction-orders",
        json=_body(),
        headers={
            "Authorization": f"Bearer {create_access_token(subject=world['refused_designer_id'])}"
        },
    )
    assert answer.status_code == 403, answer.text
    assert "Assistant Director" in answer.json()["detail"]


def test_the_awaiting_count_matches_the_rows_the_list_calls_unready(world, client) -> None:
    assert _post(world, _body()).status_code == 201
    badge = client.get("/api/sanction-orders/awaiting-count", headers=_headers(world))
    assert badge.status_code == 200, badge.text
    listing = client.get("/api/sanction-orders?pageSize=200", headers=_headers(world))
    assert listing.status_code == 200, listing.text
    unready = [row for row in listing.json()["items"] if not row["readyForWork"]]
    assert badge.json()["awaiting"] >= len(unready)
    assert badge.json()["awaiting"] >= 1


def test_the_notes_are_the_only_thing_an_officer_may_edit(world, client) -> None:
    body = _body()
    created = _post(world, body)
    assert created.status_code == 201, created.text
    order_id = created.json()["sanctionOrder"]["id"]

    patched = client.patch(
        f"/api/sanction-orders/{order_id}",
        json={"notes": "Order withdrawn by the ministry on 2026-05-02."},
        headers=_headers(world),
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["notes"].startswith("Order withdrawn")
    assert patched.json()["sanctionOrderNo"] == body["sanctionOrderNo"]

    refused = client.patch(
        f"/api/sanction-orders/{order_id}",
        json={"sanctionAmount": "1.00"},
        headers=_headers(world),
    )
    assert refused.status_code == 422, refused.text



@pytest.mark.parametrize("role", list(INELIGIBLE_ROLES))
def test_an_account_that_cannot_run_a_workshop_is_refused_before_anything_is_written(
    world, role: str
) -> None:
    answer = world["answers"][f"ineligible_{role}"]
    assert answer.status_code == 422, answer.text
    detail = answer.json()["detail"]
    assert role in detail, "the officer is not told what is actually wrong with the account"
    assert "users screen" in detail, "the officer is not told where the fix lives"

    seen = world["rows"]["ineligible"][role]
    account = seen["account"]
    assert str(getattr(account.role, "value", account.role)) == role, "the account was demoted"
    assert seen["orders"] == [], "a refused create recorded the order anyway"
    assert seen["access"] is None, "a refused create admitted the address to the platform anyway"
    assert seen["roster"] is None, "a refused create empanelled them anyway"
    assert seen["viewers"] == [], "the viewer row nothing would honour was written anyway"


def test_an_officer_may_not_record_an_order_naming_their_own_mailbox(world) -> None:
    answer = world["answers"]["own_mailbox"]
    assert answer.status_code == 422, answer.text
    assert "cannot name the officer recording it" in answer.json()["detail"]

    seen = world["rows"]["own_mailbox"]
    assert seen["orders"] == [], "a refused create recorded the order anyway"
    assert seen["roster"] is None, "the officer empanelled themselves as a designer"
    assert seen["access"] is not None
    assert str(getattr(seen["access"].admitRole, "value", seen["access"].admitRole)) == (
        "ASSISTANT_DIRECTOR"
    )


def test_a_sanction_order_does_not_overwrite_an_administrators_allow_list_decision(world) -> None:
    body = world["bodies"]["already_admitted"]
    answer = world["answers"]["already_admitted"]
    assert answer.status_code == 201, "an already-admitted address must still work"

    row = world["rows"]["already_admitted"]
    machine_note = SANCTION_ADMISSION_NOTE.format(
        no=body["sanctionOrderNo"], officer=world["officer"].name
    )
    assert row.fullName == "R. Sundaram (as the admin typed it)"
    assert row.notes == f"{world['already_note']} {machine_note}"
    assert row.decidedById == world["deciding_admin_id"], (
        "the administrator who admitted them was overwritten"
    )
    assert row.decidedAt == world["already_joined"]
    assert str(getattr(row.admitRole, "value", row.admitRole)) == "DESIGNER"
    assert row.joinedAt == world["already_joined"]


def test_no_order_names_any_spelling_of_the_master_admins_mailbox(world) -> None:
    for key in MASTER_MAILBOX_BODIES:
        answer = world["answers"][key]
        assert answer.status_code == 422, (key, answer.text)
        assert answer.json() == {"detail": SANCTION_MASTER_MAILBOX}, key

    seen = world["rows"]["master_mailbox"]
    assert seen["accounts"] == [], "an account was created on the master admin's mailbox"
    assert seen["access"] == [], "the master admin's mailbox was admitted to the allow-list"
    assert seen["roster"] == [], "the master admin's mailbox was empanelled"
    assert seen["orders"] == [], "a refused order was recorded anyway"
    assert seen["lead_accounts"] == [], "the co-designer order's lead was given an account anyway"
    assert seen["lead_access"] is None and seen["lead_roster"] is None
