"""THE NOTICES AND THE DELIVERY OF 2026-10-18, OVER A DATABASE.

Designers hear that a report was approved, handed on, or that they were given a workshop (A4);
owners, admins and requesters hear about access requests, decisions and comments (A5); inspectors
and the approving authority hear about hand-ins, one at a time or in a daily digest (C2); "Approve
and hand on" e-mails the office an expiring link at the addresses an administrator set (C4); the
inspection badge counts what is waiting (A4).

THE SHAPE IS ``test_email_db.py``'s: seed in a private ``asyncio.run`` -> drive the ``TestClient`` ->
read back in another private ``asyncio.run`` once the client is closed. Mail is switched on for the
module by replacing ``get_settings`` in the two mail modules; SES, the report renderer and S3 are
stubbed.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password
from app.services import email_outbox, mailer, report_delivery, s3

pytestmark = needs_db

ACCOUNTS = {
    "ministry": "MINISTRY_ADMIN",
    "admin": "ADMIN",
    "designer": "DESIGNER",
    "quiet": "DESIGNER",
    "joiner": "DESIGNER",
    "inspector": "INSPECTOR",
    "digest": "INSPECTOR",
    "owner": "RESEARCHER",
    "asker": "RESEARCHER",
}


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


@pytest.fixture
def mail_on(monkeypatch: pytest.MonkeyPatch) -> None:
    on = get_settings().model_copy(update={"mail_from_address": "no-reply@dpw.example.org"})
    monkeypatch.setattr(mailer, "get_settings", lambda: on)
    monkeypatch.setattr(email_outbox, "get_settings", lambda: on)


def _seed() -> dict[str, Any]:
    tag = uuid.uuid4().hex[:8]

    async def work() -> dict[str, Any]:
        people: dict[str, Any] = {}
        for slug, role in ACCOUNTS.items():
            people[slug] = await db.user.create(
                data={
                    "email": f"notify-{slug}-{tag}@example.org",
                    "name": f"{slug.title()} {tag}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        for slug in ("designer", "quiet", "joiner"):
            await db.designerroster.create(
                data={
                    "email": people[slug].email,
                    "fullName": people[slug].name,
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
        workshops: dict[str, Any] = {}
        for name, status_value, round_no in (
            ("approve", "PRE_SUBMISSION", 1),
            ("handin", "IN_PROGRESS", 0),
            ("deliver", "PRE_SUBMISSION", 1),
            ("team", "IN_PROGRESS", 0),
        ):
            row = await db.designworkshop.create(
                data={
                    "title": f"Notify {name} {tag}",
                    "createdById": people["admin"].id,
                    "status": status_value,
                    "submissionRound": round_no,
                }
            )
            workshops[name] = row.id
            if name != "team":
                for slug in ("designer", "quiet"):
                    await db.designworkshopviewer.create(
                        data={"designWorkshopId": row.id, "userId": people[slug].id, "grantedById": people["admin"].id}
                    )
            for slug in ("inspector", "digest"):
                await db.designworkshopinspector.create(
                    data={"designWorkshopId": row.id, "userId": people[slug].id, "assignedById": people["admin"].id}
                )
        craft = await db.craft.create(data={"name": f"Notify craft {tag}", "createdById": people["owner"].id})
        return {"people": people, "workshops": workshops, "craft": craft.id, "tag": tag}

    return _private(work)


def _as(people: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=people[slug].id)}"}


def _messages(where: dict[str, Any]) -> list[Any]:
    async def work() -> list[Any]:
        return list(await db.emailmessage.find_many(where=where, order={"createdAt": "asc"}))

    return _private(work)


def test_designers_officials_and_access_hear_what_happened(
    mail_on: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    from fastapi.testclient import TestClient

    from app.main import app

    world = _seed()
    people, workshops = world["people"], world["workshops"]

    with TestClient(app) as client:
        # ── preferences: partial saves, and who sees the hand-in choice ──────────────────────
        first = client.get("/api/preferences/notifications", headers=_as(people, "quiet")).json()
        assert first == {
            "available": True,
            "emailReviewNotes": True,
            "emailWorkshopUpdates": True,
            "emailAccessNotices": True,
            "emailHandInNotices": "EACH",
            "reviewsHandIns": False,
        }
        saved = client.put(
            "/api/preferences/notifications", json={"emailWorkshopUpdates": False}, headers=_as(people, "quiet")
        )
        assert saved.status_code == 200, saved.text
        assert saved.json()["emailWorkshopUpdates"] is False and saved.json()["emailReviewNotes"] is True
        # An older client that sends only the first switch changes only that one.
        older = client.put(
            "/api/preferences/notifications", json={"emailReviewNotes": False}, headers=_as(people, "quiet")
        ).json()
        assert older["emailReviewNotes"] is False and older["emailWorkshopUpdates"] is False
        daily = client.put(
            "/api/preferences/notifications", json={"emailHandInNotices": "DAILY"}, headers=_as(people, "digest")
        ).json()
        assert daily["emailHandInNotices"] == "DAILY" and daily["reviewsHandIns"] is True
        refused = client.put(
            "/api/preferences/notifications", json={"emailHandInNotices": "HOURLY"}, headers=_as(people, "digest")
        )
        assert refused.status_code == 422

        # ── A4: the inspection badge ───────────────────────────────────────────────────────
        count = client.get("/api/design-workshop-inspections/awaiting-count", headers=_as(people, "inspector"))
        assert count.status_code == 200, count.text
        assert count.json() == {"awaiting": 2}
        assert (
            client.get("/api/design-workshop-inspections/awaiting-count", headers=_as(people, "owner")).status_code
            == 403
        )

        # ── A4: approval reaches the designer who did not opt out ─────────────────────────────
        read = client.get(f"/api/design-workshop-approvals/{workshops['approve']}", headers=_as(people, "ministry"))
        assert read.status_code == 200, read.text
        assert read.json()["delivery"]["configured"] is False
        approved = client.post(
            f"/api/design-workshop-approvals/{workshops['approve']}/approve",
            json={"round": 1, "readAt": read.json()["updatedAt"], "note": "Well done."},
            headers=_as(people, "ministry"),
        )
        assert approved.status_code == 200, approved.text

        # ── C2: a hand-in reaches each inspector by their own choice ──────────────────────────
        handed = client.patch(
            f"/api/design-workshops/{workshops['handin']}",
            json={"status": "PRE_SUBMISSION"},
            headers=_as(people, "designer"),
        )
        assert handed.status_code == 200, handed.text

        # ── A4: being added to a workshop's team ───────────────────────────────────────────
        team = client.put(
            f"/api/design-workshops/{workshops['team']}/viewers",
            json={"userIds": [people["joiner"].id]},
            headers=_as(people, "admin"),
        )
        assert team.status_code == 200, team.text

        # ── A5: a workshop access request, then its decision ───────────────────────────────
        asked = client.post(
            "/api/design-workshop-access/requests",
            json={"workshopId": workshops["approve"], "note": "Asked by Rekha"},
            headers=_as(people, "joiner"),
        )
        assert asked.status_code == 202, asked.text
        queue = client.get("/api/design-workshop-access/requests", headers=_as(people, "admin")).json()
        mine = [row for row in queue["requests"] if row["requestedBy"]["id"] == people["joiner"].id]
        assert mine, queue
        decided = client.post(
            f"/api/design-workshop-access/requests/{mine[0]['id']}/decide",
            json={"status": "DENIED", "note": "Not this one."},
            headers=_as(people, "admin"),
        )
        assert decided.status_code == 200, decided.text

        # ── A5: data access, and a comment on the owner's record ───────────────────────────
        request = client.post(
            "/api/data-access/requests",
            json={"ownerId": people["owner"].id, "tier": "COMMENT"},
            headers=_as(people, "asker"),
        )
        assert request.status_code == 201, request.text
        grant = client.post(
            f"/api/data-access/grants/{request.json()['id']}/decide",
            json={"status": "GRANTED"},
            headers=_as(people, "owner"),
        )
        assert grant.status_code == 200, grant.text
        comment = client.post(
            "/api/data-access/comments",
            json={"recordType": "craft", "recordId": world["craft"], "body": "Is the dye natural?"},
            headers=_as(people, "asker"),
        )
        assert comment.status_code == 201, comment.text

    def by_kind(kind: str, slug: str) -> list[Any]:
        return _messages({"kind": kind, "recipientId": people[slug].id})

    (approved_notice,) = by_kind(mailer.REPORT_APPROVED, "designer")
    assert approved_notice.params["note"] == "Well done."
    assert by_kind(mailer.REPORT_APPROVED, "quiet") == [], "the opted-out designer is not e-mailed"
    assert by_kind(mailer.REPORT_APPROVED, "ministry") == [], "nobody is told about their own act"

    (each,) = by_kind(mailer.REPORT_HANDED_IN, "inspector")
    assert each.status == "QUEUED" and each.params["resubmission"] is False
    (held,) = by_kind(mailer.REPORT_HANDED_IN, "digest")
    assert held.status == email_outbox.HELD
    assert by_kind(mailer.REPORT_HANDED_IN, "ministry"), "the approving authority hears of it too"

    (assigned,) = by_kind(mailer.WORKSHOP_ASSIGNED, "joiner")
    assert assigned.params["workshopId"] == workshops["team"]

    assert by_kind(mailer.ACCESS_REQUESTED, "admin"), "the administrators hear of a workshop request"
    (decision,) = by_kind(mailer.ACCESS_DECIDED, "joiner")
    assert decision.params["decision"] == "DENIED" and decision.params["scope"] == "workshop"
    (data_ask,) = by_kind(mailer.ACCESS_REQUESTED, "owner")
    assert data_ask.params["scope"] == "data" and data_ask.params["tier"] == "COMMENT"
    (data_decision,) = by_kind(mailer.ACCESS_DECIDED, "asker")
    assert data_decision.params["decision"] == "GRANTED"
    (comment_notice,) = by_kind(mailer.RECORD_COMMENT, "owner")
    assert comment_notice.params["body"] == "Is the dye natural?"

    # The digest: held rows from before the last 09:00 IST fold into one message, once.
    async def digest() -> tuple[int, int, list[Any]]:
        tomorrow = datetime.now(UTC) + timedelta(days=1, hours=1)
        first = await email_outbox.flush_digests(now=tomorrow, force=True)
        again = await email_outbox.flush_digests(now=tomorrow, force=True)
        rows = await db.emailmessage.find_many(
            where={"recipientId": people["digest"].id, "kind": mailer.HAND_IN_DIGEST}
        )
        return first, again, list(rows)

    first, again, digests = _private(digest)
    assert first >= 1 and again == 0
    (one,) = digests
    assert [item["workshopId"] for item in one.params["items"]] == [workshops["handin"]]
    (folded,) = by_kind(mailer.REPORT_HANDED_IN, "digest")
    assert folded.status == email_outbox.DIGESTED


def test_approve_and_hand_on_emails_the_office_an_expiring_link(
    mail_on: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    from fastapi.testclient import TestClient

    from app.api.routes import design_workshop_approvals as route
    from app.main import app

    world = _seed()
    people, workshops = world["people"], world["workshops"]
    stored: dict[str, bytes] = {}

    async def fake_render(record: Any, user: Any, payload: Any) -> tuple[bytes, list[str], int, str, str]:
        return b"%PDF-1.7 report", [], 3, f"{record.title}.pdf", "classic"

    monkeypatch.setattr(route, "_render", fake_render)
    monkeypatch.setattr(s3, "put_object_bytes", lambda key, data, mime_type: stored.__setitem__(key, data))
    monkeypatch.setattr(s3, "presign_get_url", lambda key, **_: f"https://bucket.example/{key}?sig=1")

    async def clear_office() -> None:
        from app.services.app_settings import get_or_create_app_settings

        await get_or_create_app_settings()
        await db.appsetting.update(where={"id": "singleton"}, data={"dcOfficeEmails": {"set": []}})

    _private(clear_office)
    deliver = workshops["deliver"]

    with TestClient(app) as client:
        read = client.get(f"/api/design-workshop-approvals/{deliver}", headers=_as(people, "ministry")).json()
        body = {"round": 1, "readAt": read["updatedAt"]}
        refused = client.post(
            f"/api/design-workshop-approvals/{deliver}/approve-and-hand-on", json=body, headers=_as(people, "ministry")
        )
        assert refused.status_code == 409 and refused.json()["detail"] == report_delivery.NOT_CONFIGURED

        assert client.get("/api/settings/report-delivery", headers=_as(people, "ministry")).status_code == 403
        bad = client.put(
            "/api/settings/report-delivery", json={"addresses": ["not an address"]}, headers=_as(people, "admin")
        )
        assert bad.status_code == 422
        office = client.put(
            "/api/settings/report-delivery",
            json={"addresses": ["DC-Office@Example.org, second@example.org"], "officeName": "DC (Handicrafts)"},
            headers=_as(people, "admin"),
        )
        assert office.status_code == 200, office.text
        assert office.json()["addresses"] == ["dc-office@example.org", "second@example.org"]

        sent = client.post(
            f"/api/design-workshop-approvals/{deliver}/approve-and-hand-on", json=body, headers=_as(people, "ministry")
        )
        assert sent.status_code == 200, sent.text
        answer = sent.json()
        assert answer["status"] == "SUBMITTED" and answer["handedOnTo"] == "DC (Handicrafts)"
        latest = answer["delivery"]["latest"]
        assert latest["state"] == "LIVE" and {e["address"] for e in latest["emails"]} == {
            "dc-office@example.org",
            "second@example.org",
        }
        assert all(e["status"] == "QUEUED" for e in latest["emails"])
        assert len(stored) == 1

        again = client.post(
            f"/api/design-workshop-approvals/{deliver}/approve-and-hand-on", json=body, headers=_as(people, "ministry")
        )
        assert again.status_code == 422, "a handed-on report cannot be handed on twice"

    async def the_link() -> tuple[list[Any], list[Any]]:
        messages = await db.emailmessage.find_many(where={"kind": mailer.REPORT_DELIVERY, "topic": f"delivery:{latest['id']}"})
        designers = await db.emailmessage.find_many(
            where={"kind": mailer.REPORT_HANDED_ON, "recipientId": people["designer"].id}
        )
        return list(messages), list(designers)

    messages, designers = _private(the_link)
    assert len(messages) == 2 and all(m.sealed for m in messages)
    assert "token" not in str(messages[0].params)
    (handed_on,) = designers
    assert handed_on.params["emailed"] is True

    sent_mail: list[dict[str, Any]] = []
    monkeypatch.setattr(mailer, "send", lambda **message: sent_mail.append(message) or "ses-1")
    _private(lambda: email_outbox.process_next_email_jobs(limit=200, recover=False))
    office_mail = [m for m in sent_mail if m["to"] == "dc-office@example.org"]
    assert office_mail, "the office's message went out"
    link = next(line for line in office_mail[0]["text"].splitlines() if "/report-download?token=" in line)
    token = link.split("token=", 1)[1].strip()

    with TestClient(app) as client:
        looked = client.post("/api/report-deliveries/lookup", json={"token": token})
        assert looked.status_code == 200, looked.text
        assert looked.json()["officeName"] == "DC (Handicrafts)"
        got = client.post("/api/report-deliveries/download", json={"token": token})
        assert got.status_code == 200 and got.json()["url"].startswith("https://bucket.example/report-deliveries/")
        assert client.post("/api/report-deliveries/lookup", json={"token": "nope"}).status_code == 404

        received = client.get(
            "/api/ministry-dashboard/design-workshops",
            params={"search": world["tag"]},
            headers=_as(people, "ministry"),
        )
        assert received.status_code == 200, received.text
        row = next(item for item in received.json()["items"] if item["id"] == deliver)
        assert row["received"]["firstOpenedAt"] and row["received"]["officeName"] == "DC (Handicrafts)"

        resent = client.post(f"/api/design-workshop-approvals/{deliver}/delivery/resend", headers=_as(people, "ministry"))
        assert resent.status_code == 200, resent.text
        assert resent.json()["delivery"]["latest"]["id"] != latest["id"]
        assert client.post("/api/report-deliveries/lookup", json={"token": token}).status_code == 410

    _private(clear_office)
