from __future__ import annotations

import asyncio
import uuid
from typing import Any

import pytest
from conftest import needs_db

from app.api.routes import access as access_routes
from app.core.db import db
from app.core.security import hash_password
from app.services import access_roster

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


def test_a_bar_ends_the_exact_accounts_sessions_even_when_the_gmail_sweep_is_cut(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(access_roster, "GMAIL_ACCOUNT_SWEEP_LIMIT", 0)

    async def story() -> Any:
        barred = await db.user.create(
            data={
                "email": f"sweep.cut.{STAMP}@gmail.com",
                "name": f"Sweep {STAMP}",
                "role": "RESEARCHER",
                "passwordHash": hash_password("unused-password"),
            }
        )
        await access_routes.end_live_sessions(barred.email)
        return await db.user.find_unique(where={"id": barred.id})

    after = _private(story)
    assert after.sessionsValidFrom is not None
