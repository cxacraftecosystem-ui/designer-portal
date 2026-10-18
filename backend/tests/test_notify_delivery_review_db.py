"""THE REVIEW FIXES TO THE 2026-10-18 NOTICES AND DELIVERY, OVER A DATABASE.

* "Approve and hand on" is ONE transaction: a hand-on that loses its race leaves the report NOT
  approved and the stored file removed; a delivery that fails after the hand-on committed still
  answers 200 (``deliveryFailed``) and the link can then be sent; the designers hear REPORT_APPROVED
  as well as REPORT_HANDED_ON when the route approved the report itself.
* A repeated data-access request while one is pending does not e-mail the owner again.
* The inspection badge drops once the inspector has acted in the current round.
* The approving authority barred by rule 7 is not e-mailed a hand-in; a held notice of a person who
  has since turned hand-in e-mails OFF is set aside, not sent.
* ``received_for`` keeps the newest delivery's line and the earliest opening.

Same shape as ``test_notify_delivery_db.py``: seed in a private ``asyncio.run``, drive the
``TestClient``, read back in another.
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


ACCOUNTS = {
    "ministry": "MINISTRY_ADMIN",
    "barred": "MINISTRY_ADMIN",
    "admin": "ADMIN",
    "designer": "DESIGNER",
    "inspector": "INSPECTOR",
    "owner": "RESEARCHER",
    "asker": "RESEARCHER",
}


def _seed() -> dict[str, Any]:
    tag = uuid.uuid4().hex[:8]

    async def work() -> dict[str, Any]:
        people: dict[str, Any] = {}
        for slug, role in ACCOUNTS.items():
            people[slug] = await db.user.create(
                data={
                    "email": f"review-{slug}-{tag}@example.org",
                    "name": f"{slug.title()} {tag}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        await db.designerroster.create(
            data={
                "email": people["designer"].email,
                "fullName": people["designer"].name,
                "institution": "Directorate of Handicrafts",
                "isActive": True,
                "addedById": people["admin"].id,
            }
        )
        workshops: dict[str, str] = {}
        for name, status_value, round_no in (
            ("race", "PRE_SUBMISSION", 1),
            ("fail", "PRE_SUBMISSION", 1),
            ("handin", "IN_PROGRESS", 0),
            ("reviewed", "PRE_SUBMISSION", 2),
            ("waiting", "PRE_SUBMISSION", 2),
        ):
            row = await db.designworkshop.create(
                data={
                    "title": f"Review {name} {tag}",
                    "createdById": people["admin"].id,
                    "status": status_value,
                    "submissionRound": round_no,
                }
            )
            workshops[name] = row.id
            await db.designworkshopviewer.create(
                data={"designWorkshopId": row.id, "userId": people["designer"].id, "grantedById": people["admin"].id}
            )
            await db.designworkshopinspector.create(
                data={"designWorkshopId": row.id, "userId": people["inspector"].id, "assignedById": people["admin"].id}
            )
        # Rule 7: "barred" is on the hand-in workshop's design team, so may not decide it.
        await db.designworkshopviewer.create(
            data={"designWorkshopId": workshops["handin"], "userId": people["barred"].id, "grantedById": people["admin"].id}
        )
        # The inspector already filed a suggestion on "reviewed" in its CURRENT round (2), and one on
        # "waiting" in an EARLIER round (a resubmission is waiting for them again).
        for workshop, round_no in (("reviewed", 2), ("waiting", 1)):
            await db.dwinspectionfeedback.create(
                data={
                    "designWorkshopId": workshops[workshop],
                    "round": round_no,
                    "note": "Stage 4 needs the cost.",
                    "actorId": people["inspector"].id,
                }
            )
        from app.services.app_settings import get_or_create_app_settings

        await get_or_create_app_settings()
        await db.appsetting.update(
            where={"id": "singleton"}, data={"dcOfficeEmails": {"set": [f"dc-{tag}@example.org"]}}
        )
        return {"people": people, "workshops": workshops, "tag": tag}

    return _private(work)


def _clear_office() -> None:
    async def work() -> None:
        await db.appsetting.update(where={"id": "singleton"}, data={"dcOfficeEmails": {"set": []}})

    _private(work)


def _as(people: dict[str, Any], slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=people[slug].id)}"}


def _messages(where: dict[str, Any]) -> list[Any]:
    async def work() -> list[Any]:
        return list(await db.emailmessage.find_many(where=where, order={"createdAt": "asc"}))

    return _private(work)


def test_approve_and_hand_on_is_one_transaction_and_survives_a_failed_delivery(
    mail_on: None, monkeypatch: pytest.MonkeyPatch
) -> None:
    from fastapi.testclient import TestClient

    from app.api.routes import design_workshop_approvals as route
    from app.main import app

    world = _seed()
    people, workshops = world["people"], world["workshops"]
    stored: dict[str, bytes] = {}
    removed: list[str] = []

    async def fake_render(record: Any, user: Any, payload: Any) -> tuple[bytes, list[str], int, str, str]:
        return b"%PDF-1.7 report", [], 3, f"{record.title}.pdf", "classic"

    monkeypatch.setattr(route, "_render", fake_render)
    monkeypatch.setattr(s3, "put_object_bytes", lambda key, data, mime_type: stored.__setitem__(key, data))
    monkeypatch.setattr(s3, "delete_object", removed.append)
    monkeypatch.setattr(s3, "presign_get_url", lambda key, **_: f"https://bucket.example/{key}?sig=1")

    # ── the race: the hand-on's guard matches nothing, so the approval rolls back with it ─────────
    real_apply_in = route._apply_in

    async def lose_the_hand_on(tx: Any, decision: Any) -> None:
        if decision.workshop.where.get("status") == "APPROVED":
            raise route._LostTheRace
        await real_apply_in(tx, decision)

    try:
        with TestClient(app) as client:
            read = client.get(f"/api/design-workshop-approvals/{workshops['race']}", headers=_as(people, "ministry"))
            assert read.status_code == 200, read.text
            monkeypatch.setattr(route, "_apply_in", lose_the_hand_on)
            lost = client.post(
                f"/api/design-workshop-approvals/{workshops['race']}/approve-and-hand-on",
                json={"round": 1, "readAt": read.json()["updatedAt"]},
                headers=_as(people, "ministry"),
            )
            monkeypatch.setattr(route, "_apply_in", real_apply_in)
            assert lost.status_code == 409, lost.text
            assert removed == list(stored), "the file stored for the lost hand-on is removed again"

            # ── the delivery fails after the hand-on committed: still a 200 ────────────────────
            async def broken_send(*args: Any, **kwargs: Any) -> Any:
                raise RuntimeError("delivery table unavailable")

            read = client.get(f"/api/design-workshop-approvals/{workshops['fail']}", headers=_as(people, "ministry"))
            real_send = report_delivery.send
            monkeypatch.setattr(report_delivery, "send", broken_send)
            sent = client.post(
                f"/api/design-workshop-approvals/{workshops['fail']}/approve-and-hand-on",
                json={"round": 1, "readAt": read.json()["updatedAt"]},
                headers=_as(people, "ministry"),
            )
            monkeypatch.setattr(report_delivery, "send", real_send)
            assert sent.status_code == 200, sent.text
            answer = sent.json()
            assert answer["status"] == "SUBMITTED" and answer["deliveryFailed"] is True
            assert answer["delivery"]["latest"] is None

            # "Send the link": the handed-on export is sent for the first time.
            first = client.post(
                f"/api/design-workshop-approvals/{workshops['fail']}/delivery/resend", headers=_as(people, "ministry")
            )
            assert first.status_code == 200, first.text
            latest = first.json()["delivery"]["latest"]
            assert latest and latest["state"] == "LIVE"
            assert {e["address"] for e in latest["emails"]} == {f"dc-{world['tag']}@example.org"}
    finally:
        _clear_office()

    async def read_back() -> tuple[Any, list[Any]]:
        race = await db.designworkshop.find_unique(where={"id": workshops["race"]})
        exports = await db.dwreportexport.find_many(where={"designWorkshopId": workshops["race"]})
        return race, list(exports)

    race, exports = _private(read_back)
    assert race.status == "PRE_SUBMISSION" and race.approvedAt is None, "the approval rolled back with the hand-on"
    assert exports == [], "the export row rolled back too"

    def kinds(workshop: str) -> list[str]:
        return [
            m.kind
            for m in _messages({"recipientId": people["designer"].id})
            if (m.params or {}).get("workshopId") == workshops[workshop]
        ]

    assert kinds("race") == [], "nothing was approved, so nobody is told"
    fail_kinds = kinds("fail")
    assert mailer.REPORT_APPROVED in fail_kinds and mailer.REPORT_HANDED_ON in fail_kinds
    (handed_on,) = [
        m
        for m in _messages({"recipientId": people["designer"].id, "kind": mailer.REPORT_HANDED_ON})
        if m.params.get("workshopId") == workshops["fail"]
    ]
    assert handed_on.params["emailed"] is False


def test_requests_badges_and_hand_in_audience(mail_on: None) -> None:
    from fastapi.testclient import TestClient

    from app.main import app

    world = _seed()
    people, workshops = world["people"], world["workshops"]
    try:
        with TestClient(app) as client:
            # ── A5: three POSTs while pending e-mail the owner once ───────────────────────────
            for _ in range(3):
                asked = client.post(
                    "/api/data-access/requests",
                    json={"ownerId": people["owner"].id, "tier": "COMMENT"},
                    headers=_as(people, "asker"),
                )
                assert asked.status_code == 201, asked.text

            # ── A4: "reviewed" is acted on in its current round; the badge leaves it out ─────────
            count = client.get("/api/design-workshop-inspections/awaiting-count", headers=_as(people, "inspector"))
            assert count.status_code == 200, count.text
            # race, fail, waiting are waiting; reviewed is not.
            assert count.json() == {"awaiting": 3}

            # ── C2: the hand-in reaches the inspector and the unbarred approver only ──────────────
            handed = client.patch(
                f"/api/design-workshops/{workshops['handin']}",
                json={"status": "PRE_SUBMISSION"},
                headers=_as(people, "designer"),
            )
            assert handed.status_code == 200, handed.text
    finally:
        _clear_office()

    asks = _messages({"kind": mailer.ACCESS_REQUESTED, "recipientId": people["owner"].id})
    assert len(asks) == 1, "a repeat request while pending does not e-mail the owner again"

    def handed_in(slug: str) -> list[Any]:
        return [
            m
            for m in _messages({"kind": mailer.REPORT_HANDED_IN, "recipientId": people[slug].id})
            if m.params.get("workshopId") == workshops["handin"]
        ]

    assert handed_in("inspector")
    assert handed_in("ministry"), "an approver free to decide hears of it"
    assert handed_in("barred") == [], "rule 7 bars this approver from deciding, so no notice"

    # ── C2: a held notice of a person who switched to OFF is set aside ───────────────────────
    async def digest_after_off() -> tuple[int, Any, list[Any]]:
        await db.emailmessage.update_many(
            where={"recipientId": people["inspector"].id, "status": "QUEUED"}, data={"status": "SENT"}
        )
        held = await email_outbox.enqueue(
            mailer.REPORT_HANDED_IN,
            to_address=people["inspector"].email,
            recipient_id=people["inspector"].id,
            params={"workshopId": workshops["handin"], "workshopTitle": "x", "round": 1, "resubmission": False},
            topic=f"handin:{workshops['handin']}",
            hold=True,
        )
        assert held
        await db.userpreference.upsert(
            where={"userId": people["inspector"].id},
            data={
                "create": {"userId": people["inspector"].id, "emailHandInNotices": "OFF"},
                "update": {"emailHandInNotices": "OFF"},
            },
        )
        tomorrow = datetime.now(UTC) + timedelta(days=1, hours=1)
        await email_outbox.flush_digests(now=tomorrow, force=True)
        row = await db.emailmessage.find_first(
            where={"recipientId": people["inspector"].id, "kind": mailer.REPORT_HANDED_IN, "status": {"not": "QUEUED"}},
            order={"createdAt": "desc"},
        )
        digests = await db.emailmessage.find_many(
            where={"recipientId": people["inspector"].id, "kind": mailer.HAND_IN_DIGEST}
        )
        return 0, row, list(digests)

    _, row, digests = _private(digest_after_off)
    assert row.status == email_outbox.DIGESTED
    assert digests == [], "nothing is sent to a person who turned hand-in e-mails off"


def test_received_for_keeps_the_newest_line_and_the_earliest_opening() -> None:
    tag = uuid.uuid4().hex[:8]

    async def work() -> dict[str, Any]:
        user = await db.user.create(
            data={"email": f"recv-{tag}@example.org", "name": "R", "role": "MINISTRY_ADMIN", "passwordHash": "x"}
        )
        workshop = await db.designworkshop.create(data={"title": f"Recv {tag}", "createdById": user.id})
        now = datetime.now(UTC)
        for index, (office, opened) in enumerate(
            (("Old office", now - timedelta(days=5)), ("Mid office", now - timedelta(days=9)), ("New office", None))
        ):
            await db.dwreportdelivery.create(
                data={
                    "designWorkshopId": workshop.id,
                    "storageKey": f"k-{tag}-{index}",
                    "fileName": "r.pdf",
                    "tokenHash": f"{tag}-{index}",
                    "toAddresses": ["a@example.org"],
                    "officeName": office,
                    "expiresAt": now + timedelta(days=7),
                    "firstOpenedAt": opened,
                    "createdAt": now - timedelta(days=10 - index),
                }
            )
        return {"answer": await report_delivery.received_for([workshop.id]), "id": workshop.id, "now": now}

    got = _private(work)
    line = got["answer"][got["id"]]
    assert line["officeName"] == "New office"
    opened = datetime.fromisoformat(line["firstOpenedAt"])
    assert abs(opened - (got["now"] - timedelta(days=9))) < timedelta(seconds=1)
