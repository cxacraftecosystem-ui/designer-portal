"""The Monday summary against a real database: who gets one, once per week, the opt-out, mail off,
an empty week, the kill switch, counts that match the badges, the owner-only read and retention.

Each test runs its whole story inside one private ``asyncio.run`` (connect, seed, act, read back),
the order ``conftest.py`` prescribes. Every account is fresh, so a database shared with other suites
changes nothing here; the estate-wide figures are compared with the function the badge itself calls.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime, time, timedelta
from typing import Any

import httpx
import pytest
from conftest import needs_db
from fastapi import FastAPI

from app.api.router import api_router
from app.core import deps
from app.core.config import get_settings
from app.core.db import db
from app.core.security import hash_password
from app.services import (
    design_workshop_approvals,
    mailer,
    review_queue,
    sanction_orders,
    weekly_digest,
)

pytestmark = needs_db


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


def _target_now() -> datetime:
    """Monday 09:00 IST of the week whose "this week" holds the real present, so rows seeded now
    are "new this week" in the summary under test."""
    real = datetime.now(UTC)
    monday = weekly_digest.week_start_for(real)
    if weekly_digest.window_for(monday).end <= real:
        monday += timedelta(days=7)
    return datetime.combine(monday, time(9), tzinfo=mailer.IST).astimezone(UTC)


NOW = _target_now()
WEEK = weekly_digest.week_start_for(NOW)


def _mail(on: bool) -> Any:
    return get_settings().model_copy(
        update={
            "mail_from_address": "digest@example.org" if on else None,
            "weekly_digest_enabled": True,
        }
    )


async def _person(role: str, tag: str) -> Any:
    return await db.user.create(
        data={
            "email": f"digest-{role.lower()}-{tag}-{uuid.uuid4().hex[:6]}@example.org",
            "name": f"Digest {role.title()}",
            "role": role,
            "passwordHash": hash_password("unused-password"),
        }
    )


async def _workshop(owner: Any, tag: str, **data: Any) -> Any:
    return await db.designworkshop.create(
        data={"title": f"Digest workshop {tag}", "createdById": owner.id, **data}
    )


async def _handed_in(owner: Any, tag: str, *, days_ago: int = 1) -> Any:
    return await _workshop(
        owner,
        tag,
        status="PRE_SUBMISSION",
        submissionRound=1,
        lastHandedInAt=datetime.now(UTC) - timedelta(days=days_ago),
    )


async def _rows(user_id: str) -> dict[str, list[Any]]:
    return {
        "digests": await db.weeklydigest.find_many(where={"userId": user_id}),
        "notices": await db.notification.find_many(
            where={"userId": user_id, "kind": "WEEKLY_DIGEST"}
        ),
        "emails": await db.emailmessage.find_many(
            where={"recipientId": user_id, "kind": mailer.WEEKLY_DIGEST}
        ),
    }


def _line(payload: dict[str, Any], section: str, key: str) -> int:
    for block in payload["sections"]:
        if block["key"] == section:
            for line in block["lines"]:
                if line["key"] == key:
                    return int(line["count"])
    return 0


# ── Who ───────────────────────────────────────────────────────────────────────────────────────────


def test_eligibility_follows_the_role_or_an_inspection_post() -> None:
    async def story() -> dict[str, bool]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        posted = await _person("DESIGNER", tag)
        professor = await _person("PROFESSOR", tag)
        inspector = await _person("INSPECTOR", tag)
        director = await _person("ASSISTANT_DIRECTOR", tag)
        workshop = await _workshop(designer, tag)
        await db.designworkshopinspector.create(
            data={"designWorkshopId": workshop.id, "userId": posted.id}
        )
        listed: set[str] = set()
        after = None
        while True:
            page = await weekly_digest.eligible_users(after=after)
            if not page:
                break
            listed |= {row.id for row in page}
            after = page[-1].id
        return {
            "designer": await weekly_digest.is_authority(designer),
            "posted": await weekly_digest.is_authority(posted),
            "professor": await weekly_digest.is_authority(professor),
            "inspector": await weekly_digest.is_authority(inspector),
            "director": await weekly_digest.is_authority(director),
            "posted listed": posted.id in listed,
            "professor listed": professor.id in listed,
        }

    answer = _private(story)
    assert answer == {
        "designer": False,
        "posted": True,
        "professor": False,
        "inspector": True,
        "director": True,
        "posted listed": True,
        "professor listed": False,
    }


# ── Once per person per week ─────────────────────────────────────────────────────────────────────


def test_two_passes_and_two_concurrent_passes_send_once() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        first = await _person("MINISTRY_ADMIN", tag)
        second = await _person("MINISTRY_ADMIN", tag)
        await _handed_in(designer, tag)
        settings = _mail(True)
        await weekly_digest.deliver_one(first, WEEK, now=NOW, settings=settings)
        again = await weekly_digest.deliver_one(first, WEEK, now=NOW, settings=settings)
        await asyncio.gather(
            weekly_digest.deliver_one(second, WEEK, now=NOW, settings=settings),
            weekly_digest.deliver_one(second, WEEK, now=NOW, settings=settings),
        )
        return {"again": again, "first": await _rows(first.id), "second": await _rows(second.id)}

    answer = _private(story)
    assert answer["again"] == "EXISTS"
    for who in ("first", "second"):
        rows = answer[who]
        assert len(rows["digests"]) == 1 and rows["digests"][0].status == weekly_digest.DELIVERED
        assert len(rows["notices"]) == 1 and len(rows["emails"]) == 1
        digest, notice, email = rows["digests"][0], rows["notices"][0], rows["emails"][0]
        assert digest.notificationId == notice.id and digest.emailMessageId == email.id
        assert notice.link == f"/digest/{digest.id}"
        assert email.subject.startswith("Your week: ")


def test_the_scheduler_pass_is_idempotent_and_concurrent_passes_agree() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        approver = await _person("MASTER_ADMIN", tag)
        await _handed_in(designer, tag)
        settings = _mail(True)
        await weekly_digest.run_weekly_digests(NOW, force=True, settings=settings)
        await weekly_digest.run_weekly_digests(NOW, force=True, settings=settings)
        late = await _person("MINISTRY_ADMIN", tag)
        await asyncio.gather(
            weekly_digest.run_weekly_digests(NOW, force=True, settings=settings),
            weekly_digest.run_weekly_digests(NOW, force=True, settings=settings),
        )
        return {"approver": await _rows(approver.id), "late": await _rows(late.id)}

    answer = _private(story)
    for who in ("approver", "late"):
        rows = answer[who]
        assert len(rows["digests"]) == 1, who
        assert len(rows["notices"]) == 1 and len(rows["emails"]) == 1, who


def test_a_crash_after_the_insert_is_repaired_once(monkeypatch: pytest.MonkeyPatch) -> None:
    real_notify = weekly_digest.notifications.notify
    state = {"raised": 0}

    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        approver = await _person("MINISTRY_ADMIN", tag)
        await _handed_in(designer, tag)

        async def flaky(user: Any, *args: Any, **kwargs: Any) -> Any:
            if getattr(user, "id", None) == approver.id and not state["raised"]:
                state["raised"] += 1
                raise RuntimeError("worker died")
            return await real_notify(user, *args, **kwargs)

        monkeypatch.setattr(weekly_digest.notifications, "notify", flaky)
        settings = _mail(True)
        with pytest.raises(RuntimeError):
            await weekly_digest.deliver_one(approver, WEEK, now=NOW, settings=settings)
        stuck = await _rows(approver.id)
        # Within 15 minutes a second pass leaves it alone ...
        await weekly_digest._repair(WEEK, datetime.now(UTC), settings, _tally(), {})
        early = await _rows(approver.id)
        # ... after 15 minutes it is reclaimed and delivered, once.
        await db.weeklydigest.update_many(
            where={"userId": approver.id},
            data={"updatedAt": datetime.now(UTC) - timedelta(minutes=20)},
        )
        tally = _tally()
        await weekly_digest._repair(WEEK, datetime.now(UTC), settings, tally, {})
        await weekly_digest._repair(WEEK, datetime.now(UTC), settings, _tally(), {})
        return {"stuck": stuck, "early": early, "done": await _rows(approver.id), "tally": tally}

    answer = _private(story)
    assert [row.status for row in answer["stuck"]["digests"]] == [weekly_digest.PENDING]
    assert not answer["stuck"]["notices"] and not answer["early"]["notices"]
    done = answer["done"]
    assert [row.status for row in done["digests"]] == [weekly_digest.DELIVERED]
    assert len(done["notices"]) == 1 and len(done["emails"]) == 1
    assert answer["tally"]["repaired"] == 1


def _tally() -> dict[str, Any]:
    return dict.fromkeys(("delivered", "empty", "optedOut", "existing", "failed", "repaired"), 0)


# ── Opt-out, mail off, empty, kill switch ────────────────────────────────────────────────────────


def test_opted_out_mail_off_and_empty_weeks() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        await _handed_in(designer, tag)
        quiet = await _person("MINISTRY_ADMIN", tag)
        await weekly_digest.set_preference(quiet, False)
        offline = await _person("MINISTRY_ADMIN", tag)
        statuses = {
            "quiet": await weekly_digest.deliver_one(quiet, WEEK, now=NOW, settings=_mail(True)),
            "offline": await weekly_digest.deliver_one(
                offline, WEEK, now=NOW, settings=_mail(False)
            ),
        }
        return {
            "statuses": statuses,
            "quiet": await _rows(quiet.id),
            "offline": await _rows(offline.id),
        }

    answer = _private(story)
    assert answer["statuses"] == {
        "quiet": weekly_digest.SKIPPED_OPTOUT,
        "offline": weekly_digest.DELIVERED,
    }
    quiet = answer["quiet"]
    assert quiet["digests"][0].payload is None and not quiet["notices"] and not quiet["emails"]
    offline = answer["offline"]
    assert len(offline["notices"]) == 1 and not offline["emails"]
    assert offline["digests"][0].emailMessageId is None


def test_an_empty_week_sends_nothing(monkeypatch: pytest.MonkeyPatch) -> None:
    async def nothing(*_args: Any, **_kwargs: Any) -> int:
        return 0

    # The shared database holds other suites' pending records; an inspector with no posts and no
    # records beneath them has an empty week.
    monkeypatch.setattr(review_queue, "pending_count", nothing)

    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        idle = await _person("INSPECTOR", tag)
        status = await weekly_digest.deliver_one(idle, WEEK, now=NOW, settings=_mail(True))
        return {"status": status, "rows": await _rows(idle.id)}

    answer = _private(story)
    assert answer["status"] == weekly_digest.SKIPPED_EMPTY
    assert not answer["rows"]["notices"] and not answer["rows"]["emails"]


def test_the_kill_switch_sends_nothing() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        await _handed_in(designer, tag)
        approver = await _person("MINISTRY_ADMIN", tag)
        off = _mail(True).model_copy(update={"weekly_digest_enabled": False})
        tally = await weekly_digest.run_weekly_digests(NOW, force=True, settings=off)
        return {"tally": tally, "rows": await _rows(approver.id)}

    answer = _private(story)
    assert answer["tally"] == {"ran": False, "reason": "disabled"}
    assert not answer["rows"]["digests"] and not answer["rows"]["notices"]


# ── The counts match the badges, inside the person's own scope ───────────────────────────────────


def test_counts_match_the_badges_and_respect_scope() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        approver = await _person("MINISTRY_ADMIN", tag)
        director = await _person("ASSISTANT_DIRECTOR", tag)
        inspector = await _person("DESIGNER", tag)  # a designer holding inspection posts

        await _handed_in(designer, f"{tag}-queue", days_ago=10)
        # The inspector's two posts: one still to review, one already reviewed this round.
        todo = await _handed_in(designer, f"{tag}-todo", days_ago=9)
        done = await _handed_in(designer, f"{tag}-done")
        for workshop in (todo, done):
            await db.designworkshopinspector.create(
                data={"designWorkshopId": workshop.id, "userId": inspector.id}
            )
        await db.dwinspectionfeedback.create(
            data={
                "designWorkshopId": done.id,
                "round": 1,
                "note": "Looked at it.",
                "actorId": inspector.id,
            }
        )
        # Overdue stages: one posted to the director, one nobody posted them to.
        ended = datetime.now(UTC) - timedelta(days=30)
        posted = await _workshop(designer, f"{tag}-posted", status="IN_PROGRESS", endDate=ended)
        await _workshop(designer, f"{tag}-unposted", status="IN_PROGRESS", endDate=ended)
        await db.designworkshopoversight.create(
            data={
                "designWorkshopId": posted.id,
                "userId": director.id,
                "capacity": "ASSISTANT_DIRECTOR",
            }
        )
        # A sanction order still waiting on its setup, recorded three weeks ago.
        ordered = await _workshop(designer, f"{tag}-ordered", status="DRAFT")
        await db.sanctionorder.create(
            data={
                "sanctionOrderNo": f"SO/DIGEST/{tag}",
                "sanctionOrderKey": f"SODIGEST{tag}".upper(),
                "sanctionOrderDate": datetime.now(UTC) - timedelta(days=21),
                "sanctionAmount": "1.00",
                "designerUserId": designer.id,
                "designerEmail": designer.email,
                "designWorkshopId": ordered.id,
                "createdById": director.id,
            }
        )
        window = weekly_digest.window_for(WEEK)
        return {
            "approver": await weekly_digest.build_payload(approver, window, now=NOW),
            "director": await weekly_digest.build_payload(director, window, now=NOW),
            "inspector": await weekly_digest.build_payload(inspector, window, now=NOW),
            "badge": await design_workshop_approvals.awaiting_counts(),
            "sanction badge": await sanction_orders.awaiting_count(),
            "todo": todo.id,
            "posted": posted.id,
        }

    answer = _private(story)
    approver, director, inspector = answer["approver"], answer["director"], answer["inspector"]
    assert _line(approver, "waiting", "approvals") == answer["badge"]["awaiting"]
    assert _line(approver, "waiting", "toHandOn") == answer["badge"]["toHandOn"]
    assert _line(director, "waiting", "sanctions") == answer["sanction badge"]
    assert _line(director, "overdue", "sanctions") >= 1
    # The director sees the one workshop they were posted to, never the other.
    assert _line(director, "overdue", "stages") == 1
    overdue_items = next(s for s in director["sections"] if s["key"] == "overdue")["items"]
    assert [item["link"] for item in overdue_items] == [f"/officers/monitored/{answer['posted']}"]
    assert "approvals" not in {line["key"] for s in director["sections"] for line in s["lines"]}
    # The inspector's queue leaves out what they already reviewed this round.
    assert _line(inspector, "waiting", "inspections") == 1
    assert _line(inspector, "overdue", "reviews") == 1
    waiting_items = next(s for s in inspector["sections"] if s["key"] == "waiting")["items"]
    assert [item["link"] for item in waiting_items] == [
        f"/design-workshop-inspections/{answer['todo']}"
    ]
    # Lines with a zero count are left out, and the coverage names its scope.
    assert all(line["count"] > 0 for s in director["sections"] for line in s["lines"])
    assert director["coverage"]["scope"] == "Workshops you oversee"
    assert director["coverage"]["of"] == 1 and director["coverage"]["value"] == 0


# ── The routes: the switch and the owner-only read ───────────────────────────────────────────────


def _app(user: Any) -> FastAPI:
    application = FastAPI()
    application.include_router(api_router)
    application.dependency_overrides[deps.get_current_user] = lambda: user
    return application


async def _call(
    user: Any, method: str, path: str, body: dict[str, Any] | None = None
) -> httpx.Response:
    transport = httpx.ASGITransport(app=_app(user))
    async with httpx.AsyncClient(transport=transport, base_url="http://digest.test") as client:
        return await client.request(method, f"/api{path}", json=body)


def test_the_switch_and_the_owner_only_read() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        designer = await _person("DESIGNER", tag)
        await _handed_in(designer, tag)
        owner = await _person("MINISTRY_ADMIN", tag)
        other = await _person("MINISTRY_ADMIN", tag)
        await weekly_digest.deliver_one(owner, WEEK, now=NOW, settings=_mail(False))
        digest = await db.weeklydigest.find_first(where={"userId": owner.id})
        return {
            "state": (await _call(owner, "GET", "/notifications/digest")).json(),
            "mine": await _call(owner, "GET", f"/notifications/digests/{digest.id}"),
            "theirs": await _call(other, "GET", f"/notifications/digests/{digest.id}"),
            "off": await _call(owner, "PUT", "/notifications/digest", {"enabled": False}),
            "designer get": (await _call(designer, "GET", "/notifications/digest")).json(),
            "designer put": await _call(
                designer, "PUT", "/notifications/digest", {"enabled": False}
            ),
            "digest": digest,
        }

    answer = _private(story)
    state = answer["state"]
    assert state["eligible"] is True and state["enabled"] is True
    assert state["schedule"] == "Mondays, 08:00 IST"
    assert (
        state["latest"]["id"] == answer["digest"].id
        and state["latest"]["weekStart"] == WEEK.isoformat()
    )
    assert set(state["latest"]) == {
        "id",
        "weekStart",
        "waiting",
        "overdue",
        "newThisWeek",
        "coverage",
    }
    mine = answer["mine"]
    assert mine.status_code == 200, mine.text
    body = mine.json()
    assert set(body) == {"id", "weekStart", "generatedAt", "sections", "coverage"}
    assert [section["key"] for section in body["sections"]] == ["waiting", "overdue", "new"]
    assert answer["theirs"].status_code == 404
    assert answer["off"].status_code == 200 and answer["off"].json()["enabled"] is False
    assert answer["designer get"] == {
        "eligible": False,
        "enabled": False,
        "emailAvailable": answer["designer get"]["emailAvailable"],
        "schedule": "Mondays, 08:00 IST",
        "latest": None,
    }
    assert answer["designer put"].status_code == 403
    assert (
        answer["designer put"].json()["detail"]
        == "The weekly summary is for reviewers and officials."
    )


# ── Retention ────────────────────────────────────────────────────────────────────────────────────


def test_summaries_older_than_26_weeks_are_purged() -> None:
    async def story() -> dict[str, Any]:
        tag = uuid.uuid4().hex[:8]
        person = await _person("INSPECTOR", tag)
        old = await db.weeklydigest.create(
            data={
                "userId": person.id,
                "weekStart": weekly_digest._week_value(WEEK - timedelta(weeks=27)),
                "status": weekly_digest.DELIVERED,
            }
        )
        kept = await db.weeklydigest.create(
            data={
                "userId": person.id,
                "weekStart": weekly_digest._week_value(WEEK - timedelta(weeks=25)),
                "status": weekly_digest.DELIVERED,
            }
        )
        await weekly_digest.run_weekly_digests(NOW, force=True, settings=_mail(False))
        return {
            "old": await db.weeklydigest.find_unique(where={"id": old.id}),
            "kept": await db.weeklydigest.find_unique(where={"id": kept.id}),
        }

    answer = _private(story)
    assert answer["old"] is None and answer["kept"] is not None
