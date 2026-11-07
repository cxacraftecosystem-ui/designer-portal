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
from app.services import credential_links

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


async def _person(slug: str, role: str, *, password: bool = True) -> Any:
    email = f"link-issuer-{slug}-{STAMP}@example.org"
    user = await db.user.create(
        data={
            "email": email,
            "name": f"Link {slug} {STAMP}",
            "role": role,
            "passwordHash": hash_password("issuer-test-password") if password else None,
        }
    )
    await db.accessroster.create(
        data={
            "email": email,
            "status": "ACTIVE",
            "admitRole": role,
            "joinedAt": datetime.now(UTC),
            "notes": "Seeded by tests/test_password_link_issuer_standing.py.",
        }
    )
    return user


async def _copied_link(issuer: Any, target: Any) -> str:
    issued = await credential_links.issue_link(
        user=target, issued_by_id=issuer.id, deliver_by=credential_links.COPY_LINK
    )
    return parse_qs(urlsplit(issued.link).query)["token"][0]


def test_a_link_from_an_issuer_whose_access_was_suspended_is_refused() -> None:
    async def story() -> Any:
        issuer = await _person("barred-admin", "ADMIN")
        target = await _person("barred-target", "RESEARCHER", password=False)
        token = await _copied_link(issuer, target)
        await db.accessroster.update(
            where={"email": issuer.email}, data={"status": "SUSPENDED", "decidedAt": datetime.now(UTC)}
        )
        async with asgi_client() as client:
            return await client.post(
                "/api/auth/set-password", json={"token": token, "password": "dummy-pass-1"}
            )

    redeemed = _private(story)
    assert redeemed.status_code == 400, redeemed.text


def test_a_link_from_an_issuer_who_was_deleted_is_refused() -> None:
    async def story() -> dict[str, Any]:
        master = await _person("master", "MASTER_ADMIN")
        issuer = await _person("deleted-admin", "ADMIN")
        target = await _person("deleted-target", "RESEARCHER", password=False)
        token = await _copied_link(issuer, target)
        async with asgi_client() as client:
            removed = await client.delete(f"/api/users/{issuer.id}", headers=_auth(master.id))
            redeemed = await client.post(
                "/api/auth/set-password", json={"token": token, "password": "dummy-pass-1"}
            )
        return {"removed": removed, "redeemed": redeemed}

    answer = _private(story)
    assert answer["removed"].status_code == 204, answer["removed"].text
    assert answer["redeemed"].status_code == 400, answer["redeemed"].text


def test_a_link_from_an_issuer_in_good_standing_still_works() -> None:
    async def story() -> Any:
        issuer = await _person("standing-admin", "ADMIN")
        target = await _person("standing-target", "RESEARCHER", password=False)
        token = await _copied_link(issuer, target)
        async with asgi_client() as client:
            return await client.post(
                "/api/auth/set-password", json={"token": token, "password": "dummy-pass-1"}
            )

    redeemed = _private(story)
    assert redeemed.status_code == 200, redeemed.text
