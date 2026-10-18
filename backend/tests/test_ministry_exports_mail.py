"""THE MINISTRY PACKS' DIGEST, OVER A DATABASE.

One message per recipient per period however often the scheduler reruns (outbox topic
``ministry-packs:{period}``); nothing for a recipient demoted since subscribing; nothing at all for
on-demand packs; and a body that carries the signed-in page's link and never a presigned one.
"""

from __future__ import annotations

from typing import Any

import pytest
from conftest import needs_db
from ministry_exports_support import (
    FakeBucket,
    make_user,
    make_workshop,
    private,
    reset,
    settings_on,
)

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.services import mailer, ministry_exports as packs

pytestmark = needs_db

PERIOD = "2026-09"
TOPIC = "ministry-packs:2026-09"


async def _finished_month(ready: dict[str, int]) -> None:
    """Every scheme of PERIOD finished: READY with ``ready[scheme]`` workshops, EMPTY otherwise."""
    from prisma import Json

    for scheme in packs.scheme_keys():
        count = ready.get(scheme, 0)
        await db.ministryexportpack.create(
            data={
                "period": PERIOD,
                "scheme": scheme,
                "trigger": packs.SCHEDULED,
                "status": packs.READY if count else packs.EMPTY,
                "counts": Json({"workshops": count}),
                "xlsxKey": f"k/{scheme}.xlsx" if count else None,
                "pdfKey": f"k/{scheme}.pdf" if count else None,
            }
        )


async def _subscribe(user: Any, schemes: list[str] | None = None) -> None:
    await db.ministryexportrecipient.create(data={"userId": user.id, "schemes": schemes or []})


def _digests() -> list[Any]:
    async def work() -> list[Any]:
        return list(await db.emailmessage.find_many(where={"topic": TOPIC}))

    return private(work)


def test_one_digest_per_recipient_per_period_even_across_reruns() -> None:
    settings = settings_on()

    async def work() -> tuple[int, int, dict[str, Any]]:
        await reset()
        everyone = await make_user("MINISTRY_ADMIN", "everyone")
        narrow = await make_user("MASTER_ADMIN", "narrow")
        await _subscribe(everyone)
        await _subscribe(narrow, ["OTHER"])
        await _finished_month({"SKILL_UPGRADATION": 4, "OTHER": 2})
        first = await packs.send_period_digest(PERIOD, settings)
        second = await packs.send_period_digest(PERIOD, settings)
        return first, second, {"everyone": everyone.id, "narrow": narrow.id}

    first, second, ids = private(work)
    assert (first, second) == (2, 0)
    mails = {m.recipientId: m for m in _digests()}
    assert set(mails) == set(ids.values())
    assert [s["label"] for s in mails[ids["everyone"]].params["schemes"]] == [
        "Skill Upgradation",
        "Other",
    ]
    assert [s["label"] for s in mails[ids["narrow"]].params["schemes"]] == ["Other"]
    assert mails[ids["everyone"]].kind == mailer.MINISTRY_PACKS_READY
    assert mails[ids["everyone"]].subject == "Ministry packs for September 2026 are ready"


def test_nothing_goes_while_a_pack_of_the_month_is_unfinished() -> None:
    async def work() -> int:
        await reset()
        await _subscribe(await make_user("ADMIN", "early"))
        await _finished_month({"OTHER": 1})
        await db.ministryexportpack.update_many(
            where={"period": PERIOD, "scheme": "SKILL_UPGRADATION"}, data={"status": packs.BUILDING}
        )
        return await packs.send_period_digest(PERIOD, settings_on())

    assert private(work) == 0
    assert _digests() == []


def test_a_recipient_demoted_since_subscribing_gets_nothing() -> None:
    async def work() -> str:
        await reset()
        demoted = await make_user("MINISTRY_ADMIN", "demoted")
        await _subscribe(demoted)
        await db.user.update(where={"id": demoted.id}, data={"role": "ASSISTANT_DIRECTOR"})
        await _finished_month({"OTHER": 3})
        await packs.send_period_digest(PERIOD, settings_on())
        return demoted.id

    demoted_id = private(work)
    assert [m for m in _digests() if m.recipientId == demoted_id] == []


def test_inactive_and_suspended_recipients_get_nothing() -> None:
    async def work() -> int:
        await reset()
        paused = await make_user("ADMIN", "paused")
        await db.ministryexportrecipient.create(data={"userId": paused.id, "active": False})
        barred = await make_user("ADMIN", "barred")
        await _subscribe(barred)
        await db.accessroster.create(data={"email": barred.email.lower(), "status": "SUSPENDED"})
        await _finished_month({"OTHER": 3})
        return await packs.send_period_digest(PERIOD, settings_on())

    assert private(work) == 0


def test_on_demand_packs_never_enqueue_mail(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeBucket().install(monkeypatch)
    settings = settings_on()

    async def work() -> list[Any]:
        await reset()
        admin = await make_user("ADMIN", "asker")
        await _subscribe(admin)
        await make_workshop(admin.id, "OTHER", status="APPROVED")
        current = packs.current_period()
        await packs.request_packs(current, [], admin.id)
        for _ in range(len(packs.scheme_keys())):
            await packs.process_next_pack(settings)
        return list(await db.emailmessage.find_many(where={"topic": packs.topic_for(current)}))

    assert private(work) == []


def test_the_body_links_to_the_page_and_never_to_storage() -> None:
    settings = settings_on()

    async def work() -> Any:
        await reset()
        await _subscribe(await make_user("MASTER_ADMIN", "reader"))
        await _finished_month({"OTHER": 3})
        await packs.send_period_digest(PERIOD, settings)
        return (await db.emailmessage.find_many(where={"topic": TOPIC}))[0]

    row = private(work)
    rendered = mailer.render(row.kind, row.params, settings=settings)
    for body in (rendered.text, rendered.html):
        assert "/ministry-exports?period=2026-09" in body
        assert "X-Amz" not in body and "exports/ministry/" not in body
