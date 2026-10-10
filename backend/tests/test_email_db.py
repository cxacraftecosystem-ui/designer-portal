"""E-mail against a real database: the migration's table and column, the notification preference
routes, a password link an administrator chose to e-mail, and the worker draining it.

ONE SYNC TEST, in the order ``conftest.py`` prescribes under "HOW A DATABASE-BACKED MODULE IS
SUPPOSED TO REACH THE DATABASE": seed in a private ``asyncio.run`` -> drive the ``TestClient`` ->
read back and drain in another private ``asyncio.run`` once the client is closed. No database call is
ever made while a client is alive. SES is stubbed at ``mailer.send``; mail is switched on for the
length of the test by replacing ``get_settings`` in the two mail modules.
"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

import pytest
from conftest import needs_db

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import email_outbox, mailer

pytestmark = needs_db


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def test_preferences_a_mailed_link_and_the_drain(monkeypatch: pytest.MonkeyPatch) -> None:
    from fastapi.testclient import TestClient

    from app.main import app

    on = get_settings().model_copy(update={"mail_from_address": "no-reply@dpw.example.org"})
    off = get_settings().model_copy(update={"mail_from_address": None})
    current = {"settings": on}
    monkeypatch.setattr(mailer, "get_settings", lambda: current["settings"])
    monkeypatch.setattr(email_outbox, "get_settings", lambda: current["settings"])
    stamp = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        admin = await db.user.create(
            data={
                "email": f"mail-admin-{stamp}@example.org",
                "name": "Mail Admin",
                "role": "ADMIN",
                "passwordHash": hash_password("unused-password"),
            }
        )
        target = await db.user.create(
            data={
                "email": f"mail-designer-{stamp}@example.org",
                "name": "Asha Patel",
                "role": "DESIGNER",
                "passwordHash": hash_password("unused-password"),
            }
        )
        return {"admin": admin, "target": target}

    people = _private(seed)
    headers = {"Authorization": f"Bearer {create_access_token(subject=people['admin'].id)}"}

    with TestClient(app) as client:
        first = client.get("/api/preferences/notifications", headers=headers)
        assert first.status_code == 200, first.text
        assert first.json() == {"available": True, "emailReviewNotes": True}

        saved = client.put(
            "/api/preferences/notifications", json={"emailReviewNotes": False}, headers=headers
        )
        assert saved.status_code == 200, saved.text
        assert client.get("/api/preferences/notifications", headers=headers).json() == {
            "available": True,
            "emailReviewNotes": False,
        }
        # The appearance body does not carry the opt-out, so saving a theme leaves it alone.
        assert client.put("/api/preferences/me", json={"theme": "dark"}, headers=headers).status_code == 200
        assert client.get("/api/preferences/notifications", headers=headers).json()["emailReviewNotes"] is False

        mailed = client.post(
            "/api/auth/password-links",
            json={"userId": people["target"].id, "delivery": "EMAIL"},
            headers=headers,
        )
        assert mailed.status_code == 201, mailed.text
        body = mailed.json()
        assert body["deliveredBy"] == "EMAIL"
        assert body["link"] is None, "an e-mailed link is not also handed to the administrator"

        copied = client.post(
            "/api/auth/password-links", json={"userId": people["target"].id}, headers=headers
        )
        assert copied.status_code == 201, copied.text
        assert copied.json()["deliveredBy"] == "COPY_LINK" and copied.json()["link"]

        current["settings"] = off
        assert client.get("/api/preferences/notifications", headers=headers).json()["available"] is False
        refused = client.post(
            "/api/auth/password-links",
            json={"userId": people["target"].id, "delivery": "EMAIL"},
            headers=headers,
        )
        assert refused.status_code == 422, refused.text
        current["settings"] = on

    sent: list[dict[str, Any]] = []

    def fake_send(**message: Any) -> str:
        sent.append(message)
        return "ses-test-1"

    monkeypatch.setattr(mailer, "send", fake_send)

    async def read_back_and_drain() -> dict[str, Any]:
        queued = await db.emailmessage.find_many(where={"recipientId": people["target"].id})
        links = await db.passwordresettoken.count(where={"userId": people["target"].id})
        tally = await email_outbox.process_next_email_jobs(limit=50, recover=False)
        after = await db.emailmessage.find_many(where={"recipientId": people["target"].id})
        return {"queued": queued, "links": links, "tally": tally, "after": after}

    found = _private(read_back_and_drain)
    (row,) = found["queued"]
    assert row.kind == mailer.PASSWORD_LINK and row.status == "QUEUED"
    assert row.sealed and "token=" not in row.sealed
    assert "token" not in str(row.params)
    assert found["links"] == 2, "the refused e-mail request minted nothing"
    (done,) = found["after"]
    assert done.status == "SENT" and done.sealed is None and done.providerMessageId == "ses-test-1"
    (message,) = [m for m in sent if m["to"] == people["target"].email]
    assert "/set-password?token=" in message["text"]
