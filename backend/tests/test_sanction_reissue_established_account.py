from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from typing import Any
from urllib.parse import parse_qs, urlsplit

from conftest import needs_db
from query_count_support import asgi_client

from app.core.db import db
from app.core.security import create_access_token, hash_password

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


def _auth(user_id: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user_id)}"}


async def _officer(key: str, role: str) -> Any:
    email = f"reissue-est-{key}-{STAMP}@example.org"
    user = await db.user.create(
        data={
            "email": email,
            "name": f"Officer {key} {STAMP}",
            "role": role,
            "passwordHash": hash_password("officer-test-password"),
        }
    )
    await db.accessroster.create(
        data={
            "email": email,
            "status": "ACTIVE",
            "admitRole": role,
            "joinedAt": datetime.now(UTC),
            "notes": "Seeded by tests/test_sanction_reissue_established_account.py.",
        }
    )
    return user


def test_an_established_account_gets_no_password_link_from_the_sanction_register() -> None:
    async def story() -> dict[str, Any]:
        recorder = await _officer("ma", "MINISTRY_ADMIN")
        other = await _officer("ad", "ASSISTANT_DIRECTOR")
        designer_email = f"reissue-est-lead-{STAMP}@example.org"
        async with asgi_client() as client:
            created = await client.post(
                "/api/sanction-orders",
                json={
                    "sanctionOrderNo": f"SO/EST/{STAMP}",
                    "sanctionOrderDate": "2026-04-01",
                    "sanctionAmount": "125000.00",
                    "designerName": f"Lead {STAMP}",
                    "designerEmail": designer_email,
                },
                headers=_auth(recorder.id),
            )
            assert created.status_code == 201, created.text
            order = created.json()["sanctionOrder"]
            lead = next(d for d in order["designers"] if d["designerEmail"] == designer_email)
            assert lead["accountCreated"] is True
            fresh = await client.post(
                f"/api/sanction-orders/{order['id']}/credential-link"
                f"?designerUserId={lead['designerUserId']}",
                headers=_auth(other.id),
            )
            await db.user.update(
                where={"id": lead["designerUserId"]},
                data={
                    "passwordHash": hash_password("Owner-pass-1"),
                    "passwordSetAt": datetime.now(UTC),
                    "firstLoginAt": datetime.now(UTC),
                },
            )
            established = await client.post(
                f"/api/sanction-orders/{order['id']}/credential-link"
                f"?designerUserId={lead['designerUserId']}&deliverBy=COPY_LINK",
                headers=_auth(other.id),
            )
            redeemed = None
            if established.status_code == 200:
                token = parse_qs(urlsplit(established.json()["link"]).query)["token"][0]
                redeemed = await client.post(
                    "/api/auth/set-password",
                    json={"token": token, "password": "Attacker-pass-1"},
                )
        return {"fresh": fresh, "established": established, "redeemed": redeemed}

    answer = _private(story)
    assert answer["fresh"].status_code == 200, answer["fresh"].text
    assert answer["fresh"].json()["purpose"] == "INVITE"
    assert answer["established"].status_code == 422, (
        answer["established"].text,
        answer["redeemed"].text if answer["redeemed"] is not None else None,
    )
    assert "already" in answer["established"].json()["detail"]
