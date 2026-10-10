"""A CO-DESIGNER'S FIRST SIGN-IN LINK CAN BE RE-ISSUED (F10, 2026-10-10), under every rule the lead's is.

``POST /api/sanction-orders/{id}/credential-link?designerUserId=`` names WHICH designer on the order;
left out, it is the lead, as it always was. Every refusal is asked of THAT person:

* their own ``accountCreated`` on the order (an account the order did not create gets nothing);
* their rank against the officer re-issuing (an account since promoted to the officer's tier or above
  is refused — the takeover the lead's arm already closed);
* the master admin's mailbox, and Google sign-in;
* and somebody the order does not name is a 404.

Who may press it is the register's own gate — Assistant Director and above — asked per role, allowed
and refused. The issuer recorded on the link is the officer who pressed it, which is what the
redemption re-checks (``account_provisioning.issuer_still_manages``).

The database pattern is ``tests/test_sanction_orders.py``'s: every database call in a private
``asyncio.run`` loop with no ``TestClient`` alive, requests in between.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import credential_links

pytestmark = [needs_db]

ROLES = {
    "ad": "ASSISTANT_DIRECTOR",
    "rd": "REGIONAL_DIRECTOR",
    "ma": "MINISTRY_ADMIN",
    "admin": "ADMIN",
    "designer": "DESIGNER",
    "inspector": "INSPECTOR",
}


def _auth(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user_id)}"}


@pytest.fixture(scope="module")
def world():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    facts: dict[str, Any] = {"stamp": stamp, "users": {}}

    async def seed() -> None:
        await db.connect()
        try:
            for key, role in ROLES.items():
                email = f"reissue-{key}-{stamp}@example.org"
                user = await db.user.create(
                    data={
                        "email": email,
                        "name": f"Reissue {key} {stamp}",
                        "role": role,
                        "passwordHash": hash_password("reissue-test-password"),
                    }
                )
                await db.accessroster.create(
                    data={
                        "email": email,
                        "status": "ACTIVE",
                        "admitRole": role,
                        "joinedAt": datetime.now(UTC),
                        "notes": "Seeded by tests/test_sanction_reissue_codesigner.py.",
                    }
                )
                facts["users"][key] = user.id
            # A designer who already had an account before the order: the order creates nothing
            # for them, so there is nothing to re-issue.
            existing = await db.user.create(
                data={
                    "email": f"reissue-existing-{stamp}@example.org",
                    "name": f"Existing {stamp}",
                    "role": "DESIGNER",
                    "passwordHash": hash_password("reissue-test-password"),
                }
            )
            facts["existing_email"] = existing.email
            facts["existing_id"] = existing.id
        finally:
            await db.disconnect()

    asyncio.run(seed())

    with TestClient(app) as client:
        body = {
            "sanctionOrderNo": f"SO/REISSUE/{stamp}",
            "sanctionOrderDate": "2026-04-01",
            "sanctionAmount": "125000.00",
            "designerName": f"Lead {stamp}",
            "designerEmail": f"reissue-lead-{stamp}@example.org",
            "coDesigners": [
                {"name": f"Co one {stamp}", "email": f"reissue-co1-{stamp}@example.org"},
                {"name": f"Co two {stamp}", "email": f"reissue-co2-{stamp}@example.org"},
                {"name": f"Co three {stamp}", "email": f"reissue-co3-{stamp}@example.org"},
                {"name": f"Existing {stamp}", "email": facts["existing_email"]},
            ],
        }
        created = client.post("/api/sanction-orders", json=body, headers=_auth(facts["users"]["ad"]))
        facts["create"] = created

    order = created.json()["sanctionOrder"] if created.status_code == 201 else None
    facts["order"] = order
    team = {d["designerEmail"]: d["designerUserId"] for d in (order or {}).get("designers", [])}
    facts["co1"] = team.get(f"reissue-co1-{stamp}@example.org")
    facts["co2"] = team.get(f"reissue-co2-{stamp}@example.org")
    facts["co3"] = team.get(f"reissue-co3-{stamp}@example.org")

    async def reshape() -> None:
        await db.connect()
        try:
            if facts["co2"]:
                # Promoted after the order, above an Assistant Director and below a Ministry Admin.
                await db.user.update(
                    where={"id": facts["co2"]}, data={"role": "REGIONAL_DIRECTOR"}
                )
            if facts["co3"]:
                await db.user.update(where={"id": facts["co3"]}, data={"authProvider": "GOOGLE"})
        finally:
            await db.disconnect()

    asyncio.run(reshape())

    answers: dict[str, Any] = {}
    # The 4-per-hour-per-account throttle is not what this module asks about, and six officers
    # re-issuing for one co-designer within a second would meet it. Widened for the act phase only,
    # and put back whatever happens.
    budget = credential_links.ISSUE_BUDGET
    credential_links.ISSUE_BUDGET = 50
    try:
        _act(app, facts, order, answers)
    finally:
        credential_links.ISSUE_BUDGET = budget
    facts["answers"] = answers

    async def observe() -> None:
        await db.connect()
        try:
            rows = await db.passwordresettoken.find_many(where={"userId": facts["co1"]})
            facts["co1_issuers"] = sorted({str(row.issuedById) for row in rows})
        finally:
            await db.disconnect()

    if facts["co1"]:
        asyncio.run(observe())
    yield facts


def _act(app: Any, facts: dict[str, Any], order: Any, answers: dict[str, Any]) -> None:
    from fastapi.testclient import TestClient

    with TestClient(app) as client:
        oid = (order or {}).get("id", "missing")

        def reissue(actor: str, designer: str | None) -> Any:
            query = f"?designerUserId={designer}" if designer else ""
            return client.post(
                f"/api/sanction-orders/{oid}/credential-link{query}",
                headers=_auth(facts["users"][actor]),
            )

        for actor in ROLES:
            answers[f"co1-{actor}"] = reissue(actor, facts["co1"])
        answers["lead-default"] = reissue("ma", None)
        answers["co2-by-ad"] = reissue("ad", facts["co2"])
        answers["co2-by-ma"] = reissue("ma", facts["co2"])
        answers["co3-google"] = reissue("ma", facts["co3"])
        answers["existing"] = reissue("ma", facts["existing_id"])
        answers["stranger"] = reissue("ma", facts["users"]["designer"])


def test_the_order_names_a_team_whose_accounts_it_created(world) -> None:
    assert world["create"].status_code == 201, world["create"].text
    created = {d["designerUserId"]: d["accountCreated"] for d in world["order"]["designers"]}
    assert created[world["co1"]] is True
    assert created[world["existing_id"]] is False


@pytest.mark.parametrize("actor", ["ad", "rd", "ma", "admin"])
def test_every_sanction_recorder_may_reissue_a_co_designers_link(world, actor: str) -> None:
    reply = world["answers"][f"co1-{actor}"]
    assert reply.status_code == 200, reply.text
    body = reply.json()
    # A designer who has never signed in is still being invited: INVITE, seventy-two hours.
    assert body["purpose"] == credential_links.INVITE
    assert "token=" in body["link"]
    assert set(body) == {"id", "link", "expiresAt", "purpose", "deliveredBy"}


@pytest.mark.parametrize("actor", ["designer", "inspector"])
def test_a_tier_below_the_register_is_refused(world, actor: str) -> None:
    assert world["answers"][f"co1-{actor}"].status_code == 403


def test_each_link_records_the_officer_who_issued_it(world) -> None:
    issuers = set(world["co1_issuers"])
    assert {world["users"][key] for key in ("ad", "rd", "ma", "admin")} <= issuers
    assert world["users"]["designer"] not in issuers


def test_leaving_the_designer_out_still_reissues_the_lead(world) -> None:
    reply = world["answers"]["lead-default"]
    assert reply.status_code == 200, reply.text


def test_a_co_designer_promoted_to_the_officers_tier_or_above_is_refused(world) -> None:
    by_ad = world["answers"]["co2-by-ad"]
    assert by_ad.status_code == 422, by_ad.text
    assert "senior to the officer" in by_ad.json()["detail"]
    # A Ministry Admin still outranks a Regional Director, so the same person is reachable by them.
    assert world["answers"]["co2-by-ma"].status_code == 200


def test_a_co_designer_who_signs_in_with_google_gets_no_link(world) -> None:
    reply = world["answers"]["co3-google"]
    assert reply.status_code == 422
    assert "Google" in reply.json()["detail"]


def test_an_account_the_order_did_not_create_gets_nothing(world) -> None:
    reply = world["answers"]["existing"]
    assert reply.status_code == 422
    assert "did not create" in reply.json()["detail"]


def test_somebody_the_order_does_not_name_is_a_404(world) -> None:
    assert world["answers"]["stranger"].status_code == 404


def test_the_master_mailbox_refusal_is_asked_of_the_named_designer() -> None:
    """Read off the source: the mailbox test reads the account the re-issue resolved, not the lead."""
    import inspect

    from app.services import sanction_orders

    source = inspect.getsource(sanction_orders.reissue_credential_link)
    assert 'db.user.find_unique(where={"id": target_id})' in source
    assert source.index("target_id") < source.index("is_master_email(")
