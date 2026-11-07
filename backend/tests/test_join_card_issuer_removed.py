from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from typing import Any

from conftest import needs_db

from app.core.db import db
from app.core.security import hash_password
from app.services import design_workshop_grants as grants
from app.services.design_workshop_viewers import has_viewer_grant

pytestmark = [needs_db]

STAMP = uuid.uuid4().hex[:8]


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


async def _person(slug: str, role: str, admin: Any | None = None) -> Any:
    email = f"card-issuer-{slug}-{STAMP}@example.org"
    user = await db.user.create(
        data={
            "email": email,
            "name": f"Card {slug} {STAMP}",
            "role": role,
            "passwordHash": hash_password("card-test-password"),
        }
    )
    await db.accessroster.create(
        data={
            "email": email,
            "status": "ACTIVE",
            "admitRole": role,
            "joinedAt": datetime.now(UTC),
            "notes": "Seeded by tests/test_join_card_issuer_removed.py.",
        }
    )
    if role == "DESIGNER":
        await db.designerroster.create(
            data={
                "email": email,
                "fullName": f"Card {slug} {STAMP}",
                "isActive": True,
                "addedById": admin.id if admin is not None else None,
            }
        )
    return user


async def _workshop_with_a_card(tag: str) -> dict[str, Any]:
    admin = await _person(f"{tag}-admin", "ADMIN")
    issuer = await _person(f"{tag}-issuer", "DESIGNER", admin)
    colleague = await _person(f"{tag}-colleague", "DESIGNER", admin)
    workshop = await db.designworkshop.create(
        data={"title": f"Card workshop {tag} {STAMP}", "createdById": admin.id}
    )
    await db.designworkshopviewer.create(
        data={"designWorkshopId": workshop.id, "userId": issuer.id, "grantedById": admin.id}
    )
    minted = await grants.mint_grant(
        issuer,
        record_type="DESIGN_WORKSHOP",
        record_id=workshop.id,
        max_uses=1,
        days_valid=14,
        label=None,
    )
    return {"admin": admin, "issuer": issuer, "colleague": colleague, "workshop": workshop, "code": minted["code"]}


async def _outcome(user: Any, code: str) -> str:
    try:
        answer = await grants.redeem(user, code=code)
    except grants.CardRefused:
        return "REFUSED"
    return str(answer.get("outcome"))


def test_a_removed_designer_cannot_readmit_themselves_with_their_own_card() -> None:
    async def story() -> dict[str, Any]:
        world = await _workshop_with_a_card("self")
        await db.designworkshopviewer.delete_many(
            where={"designWorkshopId": world["workshop"].id, "userId": world["issuer"].id}
        )
        outcome = await _outcome(world["issuer"], world["code"])
        return {
            "outcome": outcome,
            "member": await has_viewer_grant(world["workshop"].id, world["issuer"].id),
        }

    answer = _private(story)
    assert answer == {"outcome": "REFUSED", "member": False}


def test_a_card_stops_admitting_anyone_once_its_issuer_is_removed() -> None:
    async def story() -> dict[str, Any]:
        world = await _workshop_with_a_card("colleague")
        await db.designworkshopviewer.delete_many(
            where={"designWorkshopId": world["workshop"].id, "userId": world["issuer"].id}
        )
        outcome = await _outcome(world["colleague"], world["code"])
        return {
            "outcome": outcome,
            "member": await has_viewer_grant(world["workshop"].id, world["colleague"].id),
        }

    answer = _private(story)
    assert answer == {"outcome": "REFUSED", "member": False}


def test_a_card_from_an_issuer_still_on_the_workshop_admits() -> None:
    async def story() -> str:
        world = await _workshop_with_a_card("standing")
        return await _outcome(world["colleague"], world["code"])

    assert _private(story) == "FULL"
