"""C1 — the sanction order opens the workshop a planned row was for, and fills stage 1 from it.

Owner decision (2026-10-17): one annual-plan row is one workshop; the officer may pick only an
exactly matching row; the sanctioned amount prints on the cover.

Arranged like ``tests/test_sanction_orders.py``: every database call in a private ``asyncio.run``
loop while no ``TestClient`` is alive (seed, then observe), requests in between.
"""

from __future__ import annotations

import asyncio
import re
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import annual_plan, sanction_orders
from app.services.stage_schema import stage

pytestmark = [needs_db]

APP = Path(sanction_orders.__file__).resolve().parents[1]


def test_the_sanction_module_reaches_the_plan_only_through_its_own_service() -> None:
    """The census in ``test_annual_plan_is_not_a_workshop`` stays exact: the order links a plan row
    through ``services/annual_plan`` and never names the table itself."""
    for module in ("services/sanction_orders.py", "api/routes/sanction_orders.py"):
        text = (APP / module).read_text(encoding="utf-8")
        # The census's own pattern: a word, so the body field `annualPlanEntryId` is not the table.
        assert not re.search(r"\bannualplanentry\b", text.lower()), module
        assert re.search(r"annual_plan\.", text), module


def test_stage_one_declares_the_sanctioned_amount_on_the_cover() -> None:
    spec = stage("WORKSHOP_SETUP").singleton.field("sanctionAmount")
    assert spec is not None
    assert spec.type.name == "MONEY"
    assert spec.report_role is not None and spec.report_role.name == "COVER_FIELD"
    assert not spec.required


def test_promotion_and_the_sanction_door_share_one_fill_rule() -> None:
    source = (APP / "services/annual_plan.py").read_text(encoding="utf-8")
    body = source[source.index("async def promote_entry(") :]
    assert "seeded = plan_stage_one_values(entry)" in body


@pytest.fixture(scope="module")
def world() -> Any:
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    facts: dict[str, Any] = {"stamp": stamp}
    number = f"C1/{stamp}/001"

    async def seed() -> None:
        await db.connect()
        try:
            email = f"c1-officer-{stamp}@example.org"
            officer = await db.user.create(
                data={
                    "email": email,
                    "name": f"C1 officer {stamp}",
                    "role": "MINISTRY_ADMIN",
                    "passwordHash": hash_password("c1-officer-password"),
                }
            )
            await db.accessroster.create(
                data={
                    "email": email,
                    "status": "ACTIVE",
                    "admitRole": "MINISTRY_ADMIN",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_sanction_planned_workshop.py.",
                }
            )
            facts["officer"] = officer
            # A Regional Director answering for Odisha only — the plan's own scope rule.
            director_email = f"c1-director-{stamp}@example.org"
            director = await db.user.create(
                data={
                    "email": director_email,
                    "name": f"C1 director {stamp}",
                    "role": "REGIONAL_DIRECTOR",
                    "passwordHash": hash_password("c1-director-password"),
                }
            )
            await db.accessroster.create(
                data={
                    "email": director_email,
                    "status": "ACTIVE",
                    "admitRole": "REGIONAL_DIRECTOR",
                    "joinedAt": datetime.now(UTC),
                    "notes": "Seeded by tests/test_sanction_planned_workshop.py.",
                }
            )
            await db.regionaldirectorstate.create(data={"userId": director.id, "state": "Odisha"})
            facts["director"] = director
            base = {
                "craftName": "Ajrakh",
                "clusterName": "Ajrakhpur",
                "state": "Gujarat",
                "district": "Kachchh",
                "venue": "Community hall",
                "implementingAgency": "NID",
                "sponsor": "DC (Handicrafts)",
                "plannedStartDate": datetime(2099, 1, 5, tzinfo=UTC),
                "plannedEndDate": datetime(2099, 1, 19, tzinfo=UTC),
            }
            facts["planned"] = await db.annualplanentry.create(
                data={
                    "planYear": 2099,
                    "workshopNo": number,
                    "workshopNoKey": number.upper(),
                    "plannedTitle": f"Ajrakh prototypes {stamp}",
                    **base,
                }
            )
            # The same reference in an older plan year, already withdrawn.
            facts["withdrawn"] = await db.annualplanentry.create(
                data={
                    "planYear": 2098,
                    "workshopNo": number,
                    "workshopNoKey": number.upper(),
                    "withdrawnAt": datetime.now(UTC),
                    **base,
                }
            )
            other = await db.designworkshop.create(
                data={
                    "title": f"Opened {stamp}",
                    "templateId": "DCH_STANDARD",
                    "createdById": officer.id,
                }
            )
            promoted_no = f"C1/{stamp}/002"
            facts["promoted"] = await db.annualplanentry.create(
                data={
                    "planYear": 2099,
                    "workshopNo": promoted_no,
                    "workshopNoKey": promoted_no.upper(),
                    "designWorkshopId": other.id,
                    **base,
                }
            )
            gujarat_no = f"C1/{stamp}/003"
            facts["gujarat"] = await db.annualplanentry.create(
                data={
                    "planYear": 2099,
                    "workshopNo": gujarat_no,
                    "workshopNoKey": gujarat_no.upper(),
                    **base,
                }
            )
            odisha_no = f"C1/{stamp}/004"
            facts["odisha"] = await db.annualplanentry.create(
                data={
                    "planYear": 2099,
                    "workshopNo": odisha_no,
                    "workshopNoKey": odisha_no.upper(),
                    **base, "state": "Odisha", "district": "Bargarh",
                }
            )
        finally:
            await db.disconnect()

    asyncio.run(seed())
    headers = {"Authorization": f"Bearer {create_access_token(subject=facts['officer'].id)}"}
    director_headers = {
        "Authorization": f"Bearer {create_access_token(subject=facts['director'].id)}"
    }

    def body(no: str, **extra: Any) -> dict[str, Any]:
        return {
            "sanctionOrderNo": no,
            "sanctionOrderDate": "2099-01-02",
            "sanctionAmount": "450000.00",
            "designerName": f"C1 designer {stamp}",
            "designerEmail": f"c1designer{stamp}@gmail.com",
            **extra,
        }

    with TestClient(app) as client:
        facts["lookup_folded"] = client.get(
            "/api/sanction-orders/planned-workshops",
            params={"workshopNo": f"  c1/{stamp}/001 "},
            headers=headers,
        )
        facts["lookup_punctuation"] = client.get(
            "/api/sanction-orders/planned-workshops",
            params={"workshopNo": f"C1-{stamp}-001"},
            headers=headers,
        )
        facts["lookup_promoted"] = client.get(
            "/api/sanction-orders/planned-workshops",
            params={"workshopNo": f"C1/{stamp}/002"},
            headers=headers,
        )
        facts["created"] = client.post(
            "/api/sanction-orders",
            json=body(f"SO-C1-{stamp}-A", annualPlanEntryId=facts["planned"].id),
            headers=headers,
        )
        facts["again"] = client.post(
            "/api/sanction-orders",
            json=body(f"SO-C1-{stamp}-B", annualPlanEntryId=facts["planned"].id),
            headers=headers,
        )
        facts["withdrawn_pick"] = client.post(
            "/api/sanction-orders",
            json=body(f"SO-C1-{stamp}-C", annualPlanEntryId=facts["withdrawn"].id),
            headers=headers,
        )
        facts["plain"] = client.post(
            "/api/sanction-orders", json=body(f"SO-C1-{stamp}-D"), headers=headers
        )
        facts["plan_list"] = client.get(
            "/api/annual-plan",
            params={"planYear": 2099, "search": stamp, "pageSize": 100},
            headers=headers,
        )
        facts["director_lookup_other_state"] = client.get(
            "/api/sanction-orders/planned-workshops",
            params={"workshopNo": f"C1/{stamp}/003"},
            headers=director_headers,
        )
        facts["director_lookup_own_state"] = client.get(
            "/api/sanction-orders/planned-workshops",
            params={"workshopNo": f"C1/{stamp}/004"},
            headers=director_headers,
        )
        facts["director_claim_other_state"] = client.post(
            "/api/sanction-orders",
            json=body(f"SO-C1-{stamp}-E", annualPlanEntryId=facts["gujarat"].id),
            headers=director_headers,
        )

    async def observe() -> None:
        await db.connect()
        try:
            facts["planned_after"] = await db.annualplanentry.find_unique(
                where={"id": facts["planned"].id}
            )
            facts["orders"] = {
                order.sanctionOrderNo: order
                for order in await db.sanctionorder.find_many(
                    where={"sanctionOrderNo": {"startswith": f"SO-C1-{stamp}"}}
                )
            }
            facts["gujarat_after"] = await db.annualplanentry.find_unique(
                where={"id": facts["gujarat"].id}
            )
            # A seed that writes nothing (a workshop id that does not exist) reads back as failed.
            ghost = type("Ghost", (), {"id": f"c1-ghost-{stamp}"})()
            facts["ghost_seeded"] = await sanction_orders._seed_the_report_copy(
                ghost,
                facts["officer"],
                facts["officer"].id,
                f"SO-C1-{stamp}-G",
                datetime(2099, 1, 2, tzinfo=UTC).date(),
                amount=None,
            )
            created = facts["orders"].get(f"SO-C1-{stamp}-A")
            if created is not None:
                facts["workshop"] = await db.designworkshop.find_unique(
                    where={"id": created.designWorkshopId}
                )
                facts["setup"] = await db.dwstageentry.find_first(
                    where={
                        "designWorkshopId": created.designWorkshopId,
                        "entityKey": "workshopSetup",
                        "deletedAt": None,
                    }
                )
        finally:
            await db.disconnect()

    asyncio.run(observe())
    with TestClient(app) as client:
        facts["client"] = client
        facts["headers"] = headers
        yield facts


def test_the_lookup_matches_only_the_exact_number(world: Any) -> None:
    assert world["lookup_folded"].status_code == 200, world["lookup_folded"].text
    items = world["lookup_folded"].json()["items"]
    assert [item["planYear"] for item in items] == [2099, 2098]
    assert items[0]["pickable"] is True and items[0]["reason"] is None
    assert (
        items[1]["pickable"] is False and items[1]["reason"] == annual_plan.SANCTION_PICK_WITHDRAWN
    )
    # Punctuation is part of the reference: no fuzzy match.
    assert world["lookup_punctuation"].json()["items"] == []


def test_an_opened_row_is_listed_but_cannot_be_picked(world: Any) -> None:
    items = world["lookup_promoted"].json()["items"]
    assert len(items) == 1
    assert items[0]["pickable"] is False
    assert items[0]["reason"] == annual_plan.SANCTION_PICK_PROMOTED


def test_the_order_opens_the_planned_workshop_and_stamps_the_row(world: Any) -> None:
    assert world["created"].status_code == 201, world["created"].text
    order = world["orders"][f"SO-C1-{world['stamp']}-A"]
    assert world["planned_after"].designWorkshopId == order.designWorkshopId
    assert world["planned_after"].promotedAt is not None
    payload = world["created"].json()["sanctionOrder"]
    assert payload["plannedWorkshop"]["id"] == world["planned"].id
    assert payload["plannedWorkshop"]["planYearLabel"] == annual_plan.plan_year_label(2099)


def test_stage_one_is_filled_from_the_plan_and_carries_the_amount(world: Any) -> None:
    data = world["setup"].data
    assert data["craftName"] == "Ajrakh"
    assert data["state"] == "Gujarat"
    assert data["venue"] == "Community hall"
    assert data["workshopCode"] == f"C1/{world['stamp']}/001"
    assert data["startDate"] == "2099-01-05"
    assert data["sanctionOrderNo"] == f"SO-C1-{world['stamp']}-A"
    assert data["sanctionAmount"] == "450000.00"
    workshop = world["workshop"]
    assert workshop.title == f"Ajrakh prototypes {world['stamp']}"
    assert workshop.craftName == "Ajrakh"
    assert workshop.district == "Kachchh"


def test_the_create_response_says_stage_one_was_filled(world: Any) -> None:
    assert world["created"].json()["reportCopySeeded"] is True
    assert world["plain"].json()["reportCopySeeded"] is True


def test_a_seed_that_wrote_nothing_is_reported_as_failed(world: Any) -> None:
    assert world["ghost_seeded"] is False


def test_a_regional_director_cannot_find_or_claim_another_states_row(world: Any) -> None:
    other = world["director_lookup_other_state"]
    assert other.status_code == 200, other.text
    assert other.json()["items"] == []
    own = world["director_lookup_own_state"].json()["items"]
    assert [item["id"] for item in own] == [world["odisha"].id]
    claim = world["director_claim_other_state"]
    assert claim.status_code == 422, claim.text
    assert claim.json()["detail"] == annual_plan.SANCTION_PICK_UNKNOWN
    assert world["gujarat_after"].designWorkshopId is None
    assert f"SO-C1-{world['stamp']}-E" not in world["orders"]


def test_one_plan_row_is_one_workshop(world: Any) -> None:
    assert world["again"].status_code == 409, world["again"].text
    assert annual_plan.SANCTION_PICK_PROMOTED in world["again"].json()["detail"]
    assert f"SO-C1-{world['stamp']}-B" not in world["orders"]


def test_a_withdrawn_row_is_refused_before_anything_is_written(world: Any) -> None:
    assert world["withdrawn_pick"].status_code == 409
    assert f"SO-C1-{world['stamp']}-C" not in world["orders"]


def test_an_order_without_a_pick_is_unchanged(world: Any) -> None:
    assert world["plain"].status_code == 201, world["plain"].text
    payload = world["plain"].json()["sanctionOrder"]
    assert payload["plannedWorkshop"] is None
    assert payload["workshopTitle"] == f"Sanctioned workshop SO-C1-{world['stamp']}-D"


def test_the_plan_shows_the_sanction_that_opened_its_row(world: Any) -> None:
    assert world["plan_list"].status_code == 200, world["plan_list"].text
    rows = {row["id"]: row for row in world["plan_list"].json()["items"]}
    sanction = rows[world["planned"].id]["sanction"]
    assert sanction["sanctionOrderNo"] == f"SO-C1-{world['stamp']}-A"
    assert sanction["sanctionAmount"] == "450000.00"
    assert rows[world["promoted"].id]["sanction"] is None
