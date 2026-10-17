"""The in-app activity list against a real database: the migration's table, ``notify`` writing a row
with mail off, and the routes reading and marking it.

ONE SYNC TEST, in the order ``conftest.py`` prescribes: seed in a private ``asyncio.run`` -> drive the
``TestClient`` -> read back in another private ``asyncio.run`` once the client is closed.
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
from app.services import email_outbox, mailer, notifications

pytestmark = needs_db


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def test_a_notice_written_with_mail_off_is_listed_and_marked_read(monkeypatch: pytest.MonkeyPatch) -> None:
    from fastapi.testclient import TestClient

    from app.main import app

    off = get_settings().model_copy(update={"mail_from_address": None})
    for module in (mailer, email_outbox, notifications):
        monkeypatch.setattr(module, "get_settings", lambda: off)
    stamp = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        asha = await db.user.create(
            data={
                "email": f"notice-asha-{stamp}@example.org",
                "name": "Asha Patel",
                "role": "DESIGNER",
                "passwordHash": hash_password("unused-password"),
            }
        )
        bina = await db.user.create(
            data={
                "email": f"notice-bina-{stamp}@example.org",
                "name": "Bina Rao",
                "role": "DESIGNER",
                "passwordHash": hash_password("unused-password"),
            }
        )
        first = await notifications.notify(
            asha, mailer.REVIEW_SENT_BACK, "Sent back", "Fix stage 7", "/design-workshops/x",
            email_kind=mailer.REVIEW_SENT_BACK, email_params={"workshopTitle": "x"}, preference="emailReviewNotes",
        )
        second = await notifications.notify(asha.id, "HAND_IN", "Handed in")
        await notifications.notify(bina, "HAND_IN", "Not Asha's")
        return {"asha": asha, "bina": bina, "first": first, "second": second}

    seeded = _private(seed)
    # Mail is off: no e-mail, and the notice is in the list all the same.
    assert seeded["first"].in_app and not seeded["first"].emailed
    headers = {"Authorization": f"Bearer {create_access_token(subject=seeded['asha'].id)}"}

    with TestClient(app) as client:
        listed = client.get("/api/notifications", headers=headers)
        assert listed.status_code == 200, listed.text
        body = listed.json()
        assert [item["title"] for item in body["items"]] == ["Handed in", "Sent back"]
        assert body["unreadCount"] == 2
        assert body["items"][1]["link"] == "/design-workshops/x" and body["items"][1]["body"] == "Fix stage 7"

        one = client.post(f"/api/notifications/{seeded['second'].notification.id}/read", headers=headers)
        assert one.status_code == 200 and one.json()["unreadCount"] == 1
        everything = client.post("/api/notifications/read", json={"all": True}, headers=headers)
        assert everything.json() == {"updated": 1, "unreadCount": 0}

    async def read_back() -> dict[str, Any]:
        mine = await db.notification.find_many(where={"userId": seeded["asha"].id})
        theirs = await db.notification.find_many(where={"userId": seeded["bina"].id})
        await db.notification.delete_many(where={"userId": {"in": [seeded["asha"].id, seeded["bina"].id]}})
        await db.user.delete_many(where={"id": {"in": [seeded["asha"].id, seeded["bina"].id]}})
        return {"mine": mine, "theirs": theirs}

    after = _private(read_back)
    assert all(row.readAt is not None for row in after["mine"])
    assert [row.readAt for row in after["theirs"]] == [None]


def test_unread_only_paging_after_a_mark_keeps_the_next_older_notice() -> None:
    """Against Postgres: "Show older" with unreadOnly, anchored on the notice just opened (now read and
    outside the filter), starts at the very next older unread notice, and same-instant rows page by id."""
    from datetime import UTC, datetime, timedelta

    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    base = datetime(2026, 10, 17, 12, tzinfo=UTC)

    async def seed() -> dict[str, Any]:
        asha = await db.user.create(
            data={
                "email": f"notice-paging-{stamp}@example.org",
                "name": "Asha Patel",
                "role": "DESIGNER",
                "passwordHash": hash_password("unused-password"),
            }
        )
        rows = []
        for n in range(5):
            rows.append(
                await db.notification.create(
                    data={"userId": asha.id, "kind": "HAND_IN", "title": f"notice {n}", "createdAt": base - timedelta(minutes=n)}
                )
            )
        twins = [
            await db.notification.create(
                data={"userId": asha.id, "kind": "HAND_IN", "title": f"twin {n}", "createdAt": base - timedelta(hours=1)}
            )
            for n in range(3)
        ]
        return {"asha": asha, "rows": rows, "twins": twins}

    seeded = _private(seed)
    headers = {"Authorization": f"Bearer {create_access_token(subject=seeded['asha'].id)}"}
    try:
        with TestClient(app) as client:
            first = client.get("/api/notifications?limit=2&unreadOnly=true", headers=headers).json()
            assert [item["title"] for item in first["items"]] == ["notice 0", "notice 1"]
            anchor = first["items"][-1]["id"]
            assert client.post(f"/api/notifications/{anchor}/read", headers=headers).status_code == 200
            second = client.get(f"/api/notifications?limit=2&unreadOnly=true&before={anchor}", headers=headers).json()
            assert [item["title"] for item in second["items"]] == ["notice 2", "notice 3"]
            assert second["hasMore"] is True

            twin_ids = sorted((row.id for row in seeded["twins"]), reverse=True)
            page = client.get(f"/api/notifications?limit=2&before={seeded['rows'][-1].id}", headers=headers).json()
            assert [item["id"] for item in page["items"]] == twin_ids[:2]
            rest = client.get(f"/api/notifications?limit=2&before={twin_ids[1]}", headers=headers).json()
            assert [item["id"] for item in rest["items"]] == twin_ids[2:] and rest["hasMore"] is False
    finally:

        async def clean() -> None:
            await db.notification.delete_many(where={"userId": seeded["asha"].id})
            await db.user.delete_many(where={"id": seeded["asha"].id})

        _private(clean)
