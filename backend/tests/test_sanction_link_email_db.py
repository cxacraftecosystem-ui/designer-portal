"""THE SANCTION REGISTER E-MAILS SIGN-IN LINKS AND REMINDERS (2026-10-18, C3 and A20), OVER A DATABASE.

With mail on, recording an order e-mails each new designer their sign-in link (the officer is handed
no link), the register shows each e-mail's status, "Copy link instead" still hands one over,
"Resend" e-mails a fresh one, an existing account the order names is told it has a workshop, and
"Remind designers" e-mails what Workshop Setup still needs, once an hour at most.

``test_email_db.py``'s shape: seed in a private ``asyncio.run``, drive the ``TestClient``, read back
once it is closed.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
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


def test_links_and_reminders_go_by_email(monkeypatch: pytest.MonkeyPatch) -> None:
    from fastapi.testclient import TestClient

    from app.main import app

    on = get_settings().model_copy(update={"mail_from_address": "no-reply@dpw.example.org"})
    monkeypatch.setattr(mailer, "get_settings", lambda: on)
    monkeypatch.setattr(email_outbox, "get_settings", lambda: on)
    stamp = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        officer = await db.user.create(
            data={
                "email": f"notify-officer-{stamp}@example.org",
                "name": f"Officer {stamp}",
                "role": "ASSISTANT_DIRECTOR",
                "passwordHash": hash_password("unused"),
            }
        )
        await db.accessroster.create(
            data={
                "email": officer.email,
                "status": "ACTIVE",
                "admitRole": "ASSISTANT_DIRECTOR",
                "joinedAt": datetime.now(UTC),
            }
        )
        existing = await db.user.create(
            data={
                "email": f"notify-existing-{stamp}@example.org",
                "name": f"Existing {stamp}",
                "role": "DESIGNER",
                "passwordHash": hash_password("unused"),
            }
        )
        return {"officer": officer, "existing": existing}

    seeded = _private(seed)
    headers = {"Authorization": f"Bearer {create_access_token(subject=seeded['officer'].id)}"}
    new_email = f"notify-new-{stamp}@example.org"

    with TestClient(app) as client:
        created = client.post(
            "/api/sanction-orders",
            json={
                "sanctionOrderNo": f"SO/NOTIFY/{stamp}",
                "sanctionOrderDate": "2026-04-01",
                "sanctionAmount": "125000.00",
                "designerName": f"New {stamp}",
                "designerEmail": new_email,
            },
            headers=headers,
        )
        assert created.status_code == 201, created.text
        body = created.json()
        assert body["linksEmailed"] is True
        (entry,) = body["credentialLinks"]
        assert entry["link"]["deliveredBy"] == "EMAIL" and entry["link"]["link"] is None
        order = body["sanctionOrder"]
        (designer,) = order["designers"]
        assert designer["linkEmail"]["status"] == "QUEUED"

        copied = client.post(
            f"/api/sanction-orders/{order['id']}/credential-link", headers=headers
        )
        assert copied.status_code == 200, copied.text
        assert copied.json()["deliveredBy"] == "COPY_LINK" and copied.json()["link"]

        resent = client.post(
            f"/api/sanction-orders/{order['id']}/credential-link",
            params={"deliverBy": "EMAIL"},
            headers=headers,
        )
        assert resent.status_code == 200, resent.text
        assert resent.json()["deliveredBy"] == "EMAIL" and resent.json()["link"] is None
        bad = client.post(
            f"/api/sanction-orders/{order['id']}/credential-link",
            params={"deliverBy": "FAX"},
            headers=headers,
        )
        assert bad.status_code == 422

        reminded = client.post(f"/api/sanction-orders/{order['id']}/remind", headers=headers)
        assert reminded.status_code == 200, reminded.text
        assert reminded.json()["queued"] == 1 and reminded.json()["missing"]
        twice = client.post(f"/api/sanction-orders/{order['id']}/remind", headers=headers)
        assert twice.status_code == 200 and twice.json()["queued"] == 0 and twice.json()["recent"] == 1

        listed = client.get("/api/sanction-orders", params={"search": stamp}, headers=headers)
        assert listed.status_code == 200, listed.text
        row = next(item for item in listed.json()["items"] if item["id"] == order["id"])
        assert row["lastRemindedAt"]
        assert row["designers"][0]["linkEmail"]["status"] == "QUEUED"

        named = client.post(
            "/api/sanction-orders",
            json={
                "sanctionOrderNo": f"SO/NOTIFY/{stamp}/2",
                "sanctionOrderDate": "2026-04-02",
                "sanctionAmount": "1000.00",
                "designerName": seeded["existing"].name,
                "designerEmail": seeded["existing"].email,
            },
            headers=headers,
        )
        assert named.status_code == 201, named.text
        assert named.json()["credentialLinks"] == []

    async def read_back() -> dict[str, Any]:
        new_user = await db.user.find_unique(where={"email": new_email})
        links = await db.emailmessage.find_many(
            where={"recipientId": new_user.id, "kind": mailer.PASSWORD_LINK}, order={"createdAt": "asc"}
        )
        reminders = await db.emailmessage.find_many(
            where={"recipientId": new_user.id, "kind": mailer.SETUP_REMINDER}
        )
        assigned = await db.emailmessage.find_many(
            where={"recipientId": seeded["existing"].id, "kind": mailer.WORKSHOP_ASSIGNED}
        )
        return {"links": list(links), "reminders": list(reminders), "assigned": list(assigned)}

    found = _private(read_back)
    assert len(found["links"]) == 2, "the first link and the resend were e-mailed; the copy was not"
    first = found["links"][0]
    assert first.topic == f"sanction:{order['id']}" and first.params["context"] == "SANCTION"
    assert first.sealed and "token" not in str(first.params)
    rendered = mailer.render(first.kind, first.params, secret="https://x.example/set-password?token=t")
    assert f"SO/NOTIFY/{stamp}" in rendered.text and "Choose your password" in rendered.text
    (reminder,) = found["reminders"]
    assert reminder.topic == f"remind:{order['id']}" and reminder.params["missing"]
    (assigned,) = found["assigned"]
    assert assigned.params["sanctionOrderNo"] == f"SO/NOTIFY/{stamp}/2"
