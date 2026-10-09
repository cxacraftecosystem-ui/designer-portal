"""The e-mail outbox (``app/services/email_outbox.py``): queueing, the drain, retries, the send log,
the opt-out, and who a review notice goes to.

No database: the module's ``db`` is replaced by a small in-memory table that answers exactly the
calls the outbox makes and fails loudly on any other (the convention
``test_design_workshop_send_back_guard`` states for its own stubs). SES is replaced at
``mailer.send``. The database-backed half — the migration, the preference routes, a link issued
for e-mail — is ``test_email_db.py``.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from app.core.config import get_settings
from app.services import email_outbox, mailer, managed_secrets

LINK = "https://dpw.example.org/set-password?token=SECRET-TOKEN-VALUE.sig"


def _configured(**overrides: Any) -> Any:
    values = {"mail_from_address": "no-reply@dpw.example.org", "mail_max_attempts": 3}
    values.update(overrides)
    return get_settings().model_copy(update=values)


def _matches(row: Any, where: dict[str, Any]) -> bool:
    for key, expected in where.items():
        actual = getattr(row, key)
        if isinstance(expected, dict):
            if "lte" in expected and not actual <= expected["lte"]:
                return False
            if "lt" in expected and not (actual is not None and actual < expected["lt"]):
                return False
        elif actual != expected:
            return False
    return True


class _Messages:
    def __init__(self) -> None:
        self.rows: list[SimpleNamespace] = []

    async def create(self, data: dict[str, Any]) -> SimpleNamespace:
        params = data.get("params")
        row = SimpleNamespace(
            id=f"msg_{len(self.rows) + 1}",
            attempts=0,
            runAfter=datetime.now(UTC) - timedelta(seconds=1),
            lockedAt=None,
            lockedBy=None,
            sentAt=None,
            providerMessageId=None,
            error=None,
            recipientId=None,
            **{k: v for k, v in data.items() if k != "params"},
        )
        row.params = getattr(params, "data", params)
        self.rows.append(row)
        return row

    async def find_many(self, where: dict[str, Any], order: Any = None, take: int = 100) -> list[Any]:
        found = [r for r in self.rows if _matches(r, where)]
        return [SimpleNamespace(**vars(r)) for r in found[:take]]

    async def update_many(self, where: dict[str, Any], data: dict[str, Any]) -> int:
        hits = [r for r in self.rows if _matches(r, where)]
        for row in hits:
            vars(row).update(data)
        return len(hits)

    async def update(self, where: dict[str, Any], data: dict[str, Any]) -> Any:
        (row,) = [r for r in self.rows if r.id == where["id"]]
        vars(row).update(data)
        return row


class _Preferences:
    def __init__(self, rows: dict[str, bool]) -> None:
        self.rows = rows

    async def find_unique(self, where: dict[str, Any]) -> Any:
        value = self.rows.get(where["userId"])
        return None if value is None else SimpleNamespace(emailReviewNotes=value)


class _Viewers:
    def __init__(self, users: list[Any]) -> None:
        self.users = users

    async def find_many(self, where: dict[str, Any], include: Any = None) -> list[Any]:
        return [SimpleNamespace(user=u) for u in self.users]


class _Users:
    def __init__(self, users: list[Any]) -> None:
        self.by_id = {u.id: u for u in users}

    async def find_unique(self, where: dict[str, Any]) -> Any:
        return self.by_id.get(where["id"])


def _person(uid: str, role: str = "DESIGNER") -> SimpleNamespace:
    return SimpleNamespace(id=uid, name=f"Person {uid}", email=f"{uid}@example.org", role=role)


@pytest.fixture
def world(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    settings = _configured()
    fake = SimpleNamespace(emailmessage=_Messages(), userpreference=_Preferences({}))
    monkeypatch.setattr(email_outbox, "db", fake)
    monkeypatch.setattr(email_outbox, "get_settings", lambda: settings)
    monkeypatch.setattr(mailer, "get_settings", lambda: settings)
    sent: list[dict[str, Any]] = []
    outcome: dict[str, Any] = {"error": None}

    def fake_send(**message: Any) -> str:
        sent.append(message)
        if outcome["error"] is not None:
            raise outcome["error"]
        return f"ses-{len(sent)}"

    monkeypatch.setattr(mailer, "send", fake_send)
    return SimpleNamespace(db=fake, settings=settings, sent=sent, outcome=outcome)


def _queue_link(world: SimpleNamespace, *, expires_in: timedelta = timedelta(hours=2)) -> Any:
    params = {
        "purpose": "RESET",
        "recipientName": "Asha",
        "expiresAt": (datetime.now(UTC) + expires_in).isoformat(),
    }
    return asyncio.run(
        email_outbox.enqueue(
            mailer.PASSWORD_LINK, to_address="asha@example.org", recipient_id="u1",
            params=params, secret=LINK,
        )
    )


def _drain() -> dict[str, int]:
    return asyncio.run(email_outbox.process_next_email_jobs(limit=10))


def test_nothing_is_queued_when_mail_is_not_configured(monkeypatch: pytest.MonkeyPatch, world: Any) -> None:
    off = _configured(mail_from_address=None)
    monkeypatch.setattr(email_outbox, "get_settings", lambda: off)
    monkeypatch.setattr(mailer, "get_settings", lambda: off)
    assert _queue_link(world) is None
    assert world.db.emailmessage.rows == []
    assert _drain()["processed"] == 0


def test_the_link_is_sealed_at_rest_and_the_seal_is_cleared_once_sent(world: Any) -> None:
    row = _queue_link(world)
    assert row.status == "QUEUED"
    assert "SECRET-TOKEN-VALUE" not in str(vars(row)), "the link must never be stored in the clear"
    assert managed_secrets.decrypt(row.sealed) == LINK

    assert _drain() == {"processed": 1, "sent": 1, "retrying": 0, "failed": 0}
    (stored,) = world.db.emailmessage.rows
    assert stored.status == "SENT" and stored.sealed is None
    assert stored.providerMessageId == "ses-1" and stored.sentAt is not None
    (message,) = world.sent
    assert LINK in message["text"] and message["to"] == "asha@example.org"


def test_a_throttle_is_retried_later_and_a_permanent_refusal_is_not(world: Any) -> None:
    _queue_link(world)
    world.outcome["error"] = mailer.SendFailed("TooManyRequestsException", permanent=False)
    assert _drain()["retrying"] == 1
    (row,) = world.db.emailmessage.rows
    assert row.status == "QUEUED" and row.attempts == 1 and row.sealed is not None
    assert row.runAfter > datetime.now(UTC), "a retry waits out its backoff"
    assert "TooManyRequestsException" in row.error

    row.runAfter = datetime.now(UTC) - timedelta(seconds=1)
    world.outcome["error"] = mailer.SendFailed("MessageRejected", permanent=True)
    assert _drain()["failed"] == 1
    assert row.status == "FAILED" and row.sealed is None


def test_the_last_attempt_fails_the_message_and_drops_the_link(world: Any) -> None:
    _queue_link(world)
    world.outcome["error"] = mailer.SendFailed("InternalFailure", permanent=False)
    (row,) = world.db.emailmessage.rows
    for _ in range(3):
        row.runAfter = datetime.now(UTC) - timedelta(seconds=1)
        _drain()
    assert row.attempts == 3 and row.status == "FAILED" and row.sealed is None
    assert len(world.sent) == 3


def test_an_expired_link_is_never_sent(world: Any) -> None:
    _queue_link(world, expires_in=timedelta(minutes=-1))
    assert _drain()["failed"] == 1
    assert world.sent == []
    assert world.db.emailmessage.rows[0].sealed is None


def test_a_row_a_dead_worker_left_sending_is_handed_back(world: Any) -> None:
    _queue_link(world)
    (row,) = world.db.emailmessage.rows
    row.status, row.attempts = "SENDING", 1
    row.lockedAt = datetime.now(UTC) - timedelta(hours=1)
    assert _drain()["sent"] == 1
    assert row.status == "SENT"


def test_no_log_line_carries_the_link_or_the_address(world: Any, caplog: pytest.LogCaptureFixture) -> None:
    caplog.set_level(logging.DEBUG)
    _queue_link(world)
    world.outcome["error"] = mailer.SendFailed("MessageRejected", permanent=True)
    _drain()
    joined = "\n".join(record.getMessage() for record in caplog.records)
    assert "mail:" in joined
    assert "SECRET-TOKEN-VALUE" not in joined and "asha@example.org" not in joined


def _notify(world: Any, *, viewers: list[Any], creator: Any, actor: Any, prefs: dict[str, bool], sent_back: bool) -> int:
    world.db.designworkshopviewer = _Viewers(viewers)
    world.db.user = _Users([creator])
    world.db.userpreference = _Preferences(prefs)
    record = SimpleNamespace(id="ws_1", title="Kantha, Bolpur", createdById=creator.id)
    return asyncio.run(
        email_outbox.notify_review_note(
            record, actor=actor, note="Stage 7 costs do not add up.", stage_key=None, sent_back=sent_back
        )
    )


def test_a_review_notice_goes_to_each_designer_who_has_not_opted_out(world: Any) -> None:
    asha, bina, opted_out = _person("asha"), _person("bina"), _person("chitra")
    creator = _person("creator")
    inspector = _person("ravi", "INSPECTOR")
    queued = _notify(
        world, viewers=[asha, bina, opted_out, inspector], creator=creator, actor=inspector,
        prefs={"chitra": False, "asha": True}, sent_back=True,
    )
    recipients = sorted(r.recipientId for r in world.db.emailmessage.rows)
    # The opted-out designer and the inspector who acted are never written to; the creator, a
    # designer, is.
    assert queued == 3
    assert recipients == ["asha", "bina", "creator"]
    assert {r.kind for r in world.db.emailmessage.rows} == {mailer.REVIEW_SENT_BACK}
    assert all(r.sealed is None for r in world.db.emailmessage.rows)


def test_an_administrator_who_only_created_the_workshop_is_not_a_designer(world: Any) -> None:
    officer = _person("officer", "MINISTRY_ADMIN")
    inspector = _person("ravi", "INSPECTOR")
    queued = _notify(
        world, viewers=[_person("asha")], creator=officer, actor=inspector, prefs={}, sent_back=False,
    )
    assert queued == 1
    (row,) = world.db.emailmessage.rows
    assert row.recipientId == "asha" and row.kind == mailer.REVIEW_SUGGESTION


def test_a_notice_failure_never_reaches_the_inspector(world: Any, monkeypatch: pytest.MonkeyPatch) -> None:
    async def broken(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("database went away")

    monkeypatch.setattr(email_outbox, "workshop_designers", broken)
    record = SimpleNamespace(id="ws_1", title="t", createdById="x")
    assert asyncio.run(
        email_outbox.notify_review_note(
            record, actor=_person("ravi"), note="n", stage_key=None, sent_back=False
        )
    ) == 0
