"""How many SQL statements the fixed paths send to Postgres — pinned, and pinned NOT TO GROW WITH ROWS.

Every test here counts with :mod:`query_count_support` (the query engine's own statement counter) and
asserts one of two things about a path that used to issue a statement per row: that it now costs the
SAME for a small and a larger set of rows, or that it stays under a fixed ceiling whatever the data.
The failure message prints both counts, so a regression says what it costs, not only that it moved.

Each test runs its whole story in one private ``asyncio.run`` — connect, seed, count, clean up — the
order ``conftest.py`` prescribes, and talks to the app over ASGI without its lifespan, so no background
task (the connection watchdog, the usage flush) can add a statement to a count.
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from collections import defaultdict
from types import SimpleNamespace
from typing import Any

from conftest import needs_db
from query_count_support import asgi_client, count_statements

from app.core.config import get_settings
from app.core.db import db
from app.core.security import create_access_token, hash_password

STAMP = uuid.uuid4().hex[:8]


def _private(work: Any) -> Any:
    async def connected() -> Any:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


async def _person(role: str, tag: str) -> Any:
    return await db.user.create(
        data={
            "email": f"qc-{tag}-{STAMP}-{uuid.uuid4().hex[:6]}@example.org",
            "name": f"Query Count {tag.title()}",
            "role": role,
            "passwordHash": hash_password("unused-password"),
        }
    )


def _auth(user: Any) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=user.id)}"}


async def _quietly(*cleanups: Any) -> None:
    """Best-effort cleanup: a row another table still points at is left for the next run's stamp."""
    for cleanup in cleanups:
        with contextlib.suppress(Exception):
            await cleanup


def _same(small: int, large: int, what: str) -> None:
    assert large == small, f"{what}: {small} statements for the small set, {large} for the larger one"


# ── The queue process: an idle pass ──────────────────────────────────────────────────────────────


@needs_db
def test_the_export_sweep_skips_the_stale_lock_recovery_between_minutes_and_still_expires_files():
    """``recover_and_purge(recover=False)`` is the five-second pass: the expiry only. The two stale-
    lock sweeps (thirty-minute locks) run on the once-a-minute pass — two of them each wrapped in
    its own transaction, every pass, on production."""
    from datetime import UTC, datetime, timedelta

    from app.services import ministry_export as exports

    async def work() -> dict[str, Any]:
        owner = await _person("MINISTRY_ADMIN", "exports")
        long_ago = datetime.now(UTC) - timedelta(hours=2)
        stale = await db.ministrydataexport.create(
            data={"requestedById": owner.id, "status": exports.RUNNING, "lockedAt": long_ago}
        )
        expired = await db.ministrydataexport.create(
            data={"requestedById": owner.id, "status": exports.READY, "expiresAt": long_ago}
        )
        try:
            async with count_statements() as between:
                await exports.recover_and_purge(recover=False)
            after_between = {
                row.id: row.status
                for row in await db.ministrydataexport.find_many(where={"id": {"in": [stale.id, expired.id]}})
            }
            async with count_statements() as minute:
                await exports.recover_and_purge(recover=True)
            stale_after = await db.ministrydataexport.find_unique(where={"id": stale.id})
            return {
                "between": between.value,
                "minute": minute.value,
                "after_between": after_between,
                "stale_after": stale_after.status,
                "stale": stale.id,
                "expired": expired.id,
            }
        finally:
            await _quietly(
                db.ministrydataexport.delete_many(where={"requestedById": owner.id}),
                db.user.delete(where={"id": owner.id}),
            )

    seen = _private(work)
    # The expiry still lands on the five-second pass; the stale lock waits for the minute.
    assert seen["after_between"][seen["expired"]] == "EXPIRED"
    assert seen["after_between"][seen["stale"]] == "RUNNING"
    assert seen["stale_after"] == "QUEUED"
    # One update_many (BEGIN, UPDATE, COMMIT) between minutes; three on the minute.
    assert seen["between"] <= 3, seen
    assert seen["minute"] > seen["between"], seen


def test_a_drain_pass_hands_recover_to_every_sweep_it_runs(monkeypatch):
    """``worker.drain_pass`` forwards the once-a-minute decision to all three services."""
    from app import worker

    seen: dict[str, Any] = {}

    async def nothing(*_args: Any, **_kwargs: Any) -> None:
        return None

    def recorder(name: str) -> Any:
        async def record(*_args: Any, **kwargs: Any) -> dict[str, int]:
            seen[name] = kwargs.get("recover")
            return {}

        return record

    monkeypatch.setattr(worker, "ensure_db_connected", nothing)
    monkeypatch.setattr(worker, "process_next_media_jobs", recorder("media"))
    monkeypatch.setattr(worker, "process_next_email_jobs", recorder("email"))
    monkeypatch.setattr(worker, "process_next_ministry_exports", recorder("exports"))
    monkeypatch.setattr(worker, "purge_expired_snapshots", nothing)
    monkeypatch.setattr(worker.aadhaar_log_retention, "maybe_run", nothing)
    monkeypatch.setattr(worker, "run_weekly_digests", nothing)
    monkeypatch.setattr(worker.artisan_duplicates, "maybe_run", nothing)
    monkeypatch.setattr(worker.artisan_merge, "maybe_purge", nothing)
    monkeypatch.setattr(worker.ministry_exports, "maybe_run", nothing)
    settings = SimpleNamespace(media_queue_batch_size=3)
    for recover in (False, True):
        asyncio.run(worker.drain_pass(settings, recover=recover, search=False))
        assert seen == {"media": recover, "email": recover, "exports": recover}


def _quiet_drain(monkeypatch: Any, worker: Any) -> None:
    """Every service ``drain_pass`` calls, answering nothing."""

    async def nothing(*_args: Any, **_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(worker, "ensure_db_connected", nothing)
    for name in ("process_next_media_jobs", "process_next_email_jobs", "process_next_ministry_exports"):
        monkeypatch.setattr(worker, name, nothing)
    monkeypatch.setattr(worker, "purge_expired_snapshots", nothing)
    monkeypatch.setattr(worker.aadhaar_log_retention, "maybe_run", nothing)
    monkeypatch.setattr(worker, "run_weekly_digests", nothing)
    monkeypatch.setattr(worker.artisan_duplicates, "maybe_run", nothing)
    monkeypatch.setattr(worker.artisan_merge, "maybe_purge", nothing)
    monkeypatch.setattr(worker.ministry_exports, "maybe_run", nothing)


def test_a_drain_pass_says_whether_its_sweep_held(monkeypatch):
    """A recovering service that raises makes the pass answer False — the sweep did not happen."""
    from app import worker

    _quiet_drain(monkeypatch, worker)
    settings = SimpleNamespace(media_queue_batch_size=3)
    assert asyncio.run(worker.drain_pass(settings, recover=True, search=False)) is True

    for failing in ("process_next_media_jobs", "process_next_email_jobs", "process_next_ministry_exports"):
        _quiet_drain(monkeypatch, worker)

        async def down(*_args: Any, **_kwargs: Any) -> None:
            raise ConnectionError("database went away")

        monkeypatch.setattr(worker, failing, down)
        assert asyncio.run(worker.drain_pass(settings, recover=True, search=False)) is False, failing
        # Not asked to recover, a failure says nothing about the sweep's clock.
        assert asyncio.run(worker.drain_pass(settings, recover=False, search=False)) is True, failing


def test_the_minute_gate_restarts_only_from_a_sweep_that_held(monkeypatch):
    """``_run``: the first pass sweeps; a failed sweep is tried again on the very next pass; a sweep
    that held starts the minute, and nothing sweeps again until it has passed."""
    import pytest

    from app import worker

    class Stop(Exception):
        pass

    async def nothing(*_args: Any, **_kwargs: Any) -> None:
        return None

    # (clock reading, what the pass answers when asked to sweep)
    script = [(0.0, False), (5.0, True), (10.0, True), (64.0, True), (65.0, True), (70.0, True)]
    clock = iter(at for at, _ in script)
    outcomes = iter(held for _, held in script)
    asked: list[bool] = []

    async def drain(_settings: Any, *, recover: bool, search: bool) -> bool:
        if len(asked) == len(script):
            raise Stop
        asked.append(recover)
        held = next(outcomes)
        return held if recover else True

    def tick() -> float:
        try:
            return next(clock)
        except StopIteration:
            return 1000.0

    monkeypatch.setattr(worker, "get_settings", lambda: SimpleNamespace(media_queue_interval_seconds=5.0))
    monkeypatch.setattr(worker, "connect_db", nothing)
    monkeypatch.setattr(worker, "disconnect_db", nothing)
    monkeypatch.setattr(worker, "_wait_for_stop", nothing)
    monkeypatch.setattr(worker, "_clock", tick)
    monkeypatch.setattr(worker, "drain_pass", drain)
    with pytest.raises(Stop):
        asyncio.run(worker._run())
    # 0 s: first pass sweeps and fails. 5 s: tried again at once, holds. 10 s, 64 s: inside the
    # minute that began at 5 s. 65 s: the minute is up. 70 s: inside the next one.
    assert asked == [True, True, False, False, True, False]


# ── E-mail fan-out: one preference read per notice, not per recipient ───────────────────────────


@needs_db
def test_a_notice_reads_every_recipients_preference_in_one_statement():
    """``_queue_for`` (access requests, review notes, approvals) read ``UserPreference`` once per
    recipient. With mail off the queueing itself writes nothing, so what is left is that one read."""
    from app.services import email_outbox, mailer

    off = get_settings().model_copy(update={"mail_from_address": None})

    def people(count: int) -> list[Any]:
        return [
            SimpleNamespace(id=f"qc-nobody-{STAMP}-{i}", email=f"qc{i}-{STAMP}@example.org", name=f"N {i}")
            for i in range(count)
        ]

    async def work() -> tuple[int, int]:
        counts = []
        for size in (2, 9):
            async with count_statements() as counted:
                await email_outbox._queue_for(
                    people(size), mailer.REVIEW_SENT_BACK, {}, actor_id=None, settings=off
                )
            counts.append(counted.value)
        return counts[0], counts[1]

    small, large = _private(work)
    _same(small, large, "_queue_for preference reads")
    assert small == 1


@needs_db
def test_a_hand_in_notice_reads_every_recipients_choice_in_one_statement(monkeypatch):
    """``notify_handed_in`` read each inspector's and approver's preference row one by one."""
    from app.services import email_outbox

    on = get_settings().model_copy(update={"mail_from_address": "qc@example.org"})
    audience: list[Any] = []

    async def hand_in_audience(_workshop_id: str) -> list[Any]:
        return list(audience)

    async def enqueue(*_args: Any, **_kwargs: Any) -> Any:
        return SimpleNamespace(id="queued")

    monkeypatch.setattr(email_outbox, "hand_in_audience", hand_in_audience)
    monkeypatch.setattr(email_outbox, "enqueue", enqueue)
    record = SimpleNamespace(id="qc-workshop", title="Kantha", designerName="Asha", submissionRound=1)
    actor = SimpleNamespace(id="qc-actor", name="Asha")

    async def work() -> tuple[int, int, int, int]:
        out = []
        for size in (2, 7):
            audience[:] = [
                (SimpleNamespace(id=f"qc-official-{STAMP}-{i}", email=f"o{i}@example.org", name=f"O {i}"), i == 0)
                for i in range(size)
            ]
            async with count_statements() as counted:
                queued = await email_outbox.notify_handed_in(record, actor=actor, resubmission=False, settings=on)
            out.extend([counted.value, queued])
        return out[0], out[1], out[2], out[3]

    small, queued_small, large, queued_large = _private(work)
    _same(small, large, "notify_handed_in preference reads")
    assert small == 1
    assert (queued_small, queued_large) == (2, 7)


# ── The craft master list: the review report and the seed loader ─────────────────────────────────


@needs_db
def test_the_craft_review_report_costs_the_same_for_two_legacy_crafts_or_seven():
    """``GET /crafts/review`` (``map_crafts.plan``) read the spelling table twice and counted seven
    tables once PER legacy craft. Now: one read of the spellings, one grouped read per table.

    THE CRAFTS CARRY REFERENCES, AND DIFFERENT NUMBERS OF THEM, so the grouped count is checked as well
    as its cost: every craft's ``references`` must equal what :func:`craft_master.reference_count`
    counts for it alone. Each craft is named by its own tool twice over (``craftId`` and a ``ToolCraft``
    row, which counts once), by a tool shared across its batch through ``ToolCraft``, by ``k % 3``
    workshops, and by a product when ``k`` is even."""
    from datetime import UTC, datetime

    from app.services import craft_master as cm

    async def work() -> dict[str, Any]:
        admin = await _person("ADMIN", "crafts")
        made: list[str] = []
        tools: list[str] = []
        workshops: list[str] = []
        products: list[str] = []
        expected: dict[str, int] = {}

        async def tool(craft: Any, *, links: list[str]) -> None:
            row = await db.tooldocumentation.create(
                data={
                    "craftName": craft.name, "place": "Bagru", "artisanName": "Qc", "toolkitName": "Block",
                    "createdById": admin.id, "craftId": craft.id,
                }
            )
            tools.append(row.id)
            for craft_id in links:
                await db.toolcraft.create(data={"toolId": row.id, "craftId": craft_id})

        async def legacy(count: int) -> None:
            batch: list[Any] = []
            for _ in range(count):
                k = len(made)
                tag = uuid.uuid4().hex[:10]
                craft = await db.craft.create(
                    data={"name": f"Qzv legacy {tag}", "slug": f"qc-legacy-{tag}", "status": cm.LEGACY}
                )
                made.append(craft.id)
                batch.append(craft)
                await tool(craft, links=[craft.id])
                for _ in range(k % 3):
                    shop = await db.workshop.create(
                        data={"title": f"Qc {tag}", "date": datetime.now(UTC), "place": "Bagru", "createdById": admin.id}
                    )
                    workshops.append(shop.id)
                    await db.workshopcraft.create(data={"workshopId": shop.id, "craftId": craft.id})
                if k % 2 == 0:
                    product = await db.productdocumentation.create(
                        data={
                            "craftName": craft.name, "place": "Bagru", "artisanName": "Qc", "productName": "Sheet",
                            "createdById": admin.id, "craftId": craft.id,
                        }
                    )
                    products.append(product.id)
                expected[craft.id] = 2 + k % 3 + (1 if k % 2 == 0 else 0)
            await tool(batch[0], links=[c.id for c in batch])

        try:
            async with asgi_client() as client:
                await client.get("/api/crafts/review", headers=_auth(admin))  # warms the identity cache
                await legacy(2)
                async with count_statements() as two:
                    first = await client.get("/api/crafts/review", headers=_auth(admin))
                await legacy(5)
                async with count_statements() as seven:
                    second = await client.get("/api/crafts/review", headers=_auth(admin))
            assert first.status_code == second.status_code == 200, second.text
            reported = {row["id"]: row["references"] for row in second.json()["crafts"]}
            one_by_one = {craft_id: await cm.reference_count(db, craft_id) for craft_id in made}
            return {
                "two": two.value,
                "seven": seven.value,
                "reported": {craft_id: reported.get(craft_id) for craft_id in made},
                "one_by_one": one_by_one,
                "expected": expected,
            }
        finally:
            await _quietly(
                db.tooldocumentation.delete_many(where={"id": {"in": tools}}),
                db.workshop.delete_many(where={"id": {"in": workshops}}),
                db.productdocumentation.delete_many(where={"id": {"in": products}}),
                db.craft.delete_many(where={"id": {"in": made}}),
                db.user.delete(where={"id": admin.id}),
            )

    seen = _private(work)
    assert seen["one_by_one"] == seen["expected"], seen
    assert seen["reported"] == seen["expected"], seen
    assert len(set(seen["expected"].values())) > 1, seen
    _same(seen["two"], seen["seven"], "GET /crafts/review")


@needs_db
def test_the_seed_loader_reads_the_master_list_in_a_handful_of_statements():
    """``sync_master`` looked every category, sub-category and craft of the 455-row master list up by
    slug, one read each. A run with nothing to change is now a few reads in all."""
    from app.services import craft_master as cm

    async def work() -> tuple[int, dict[str, int]]:
        await cm.sync_master(db)
        async with count_statements() as counted:
            report = await cm.sync_master(db)
        return counted.value, report

    statements, report = _private(work)
    assert report["created"] == report["updated"] == 0, report
    assert statements <= 8, f"an idempotent sync_master issued {statements} statements"


# ── Tasks: the chip counts ride the list ─────────────────────────────────────────────────────────


@needs_db
def test_the_task_board_chip_counts_cost_one_grouped_read_and_match_the_per_status_totals():
    async def work() -> dict[str, Any]:
        worker_account = await _person("RESEARCHER", "tasks")
        author = await _person("ADMIN", "tasks-author")
        try:
            for status_value in ("OPEN", "OPEN", "IN_PROGRESS", "DONE"):
                await db.assignedtask.create(
                    data={
                        "title": f"QC task {status_value}",
                        "status": status_value,
                        "assigneeId": worker_account.id,
                        "createdById": author.id,
                    }
                )
            headers = _auth(worker_account)
            async with asgi_client() as client:
                await client.get("/api/tasks?view=assigned&withDerived=false", headers=headers)
                async with count_statements() as plain:
                    await client.get("/api/tasks?view=assigned&withDerived=false", headers=headers)
                async with count_statements() as with_counts:
                    body = (
                        await client.get(
                            "/api/tasks?view=assigned&withDerived=false&withStatusCounts=true",
                            headers=headers,
                        )
                    ).json()
                totals = {
                    status_value: (
                        await client.get(
                            f"/api/tasks?view=assigned&status={status_value}&pageSize=1&withDerived=false",
                            headers=headers,
                        )
                    ).json()["total"]
                    for status_value in ("OPEN", "IN_PROGRESS", "DONE", "CANCELLED")
                }
            return {"plain": plain.value, "with": with_counts.value, "counts": body["statusCounts"], "totals": totals}
        finally:
            await _quietly(
                db.assignedtask.delete_many(where={"assigneeId": worker_account.id}),
                db.user.delete_many(where={"id": {"in": [worker_account.id, author.id]}}),
            )

    seen = _private(work)
    assert seen["counts"] == seen["totals"] == {"OPEN": 2, "IN_PROGRESS": 1, "DONE": 1, "CANCELLED": 0}
    assert seen["with"] == seen["plain"] + 1, seen


# ── Held posts: one answer for every workshop on a screen ────────────────────────────────────────


@needs_db
def test_my_posts_answers_every_workshop_in_the_same_statements_however_many_there_are():
    async def work() -> dict[str, Any]:
        admin = await _person("ADMIN", "posts")
        workshops: list[Any] = []

        async def post_on(count: int) -> None:
            for index in range(count):
                workshop = await db.designworkshop.create(
                    data={"title": f"QC posts {STAMP} {len(workshops)}", "createdById": admin.id}
                )
                workshops.append(workshop)
                if index % 2 == 0:
                    await db.designworkshopoversight.create(
                        data={"designWorkshopId": workshop.id, "capacity": "ASSISTANT_DIRECTOR", "userId": admin.id}
                    )
                else:
                    await db.designworkshopinspector.create(
                        data={"designWorkshopId": workshop.id, "userId": admin.id}
                    )

        try:
            async with asgi_client() as client:
                headers = _auth(admin)
                await client.get("/api/design-workshop-oversight/my-posts", headers=headers)
                await post_on(2)
                async with count_statements() as two:
                    first = (await client.get("/api/design-workshop-oversight/my-posts", headers=headers)).json()
                await post_on(5)
                gone = workshops[-1]
                await db.designworkshop.update(where={"id": gone.id}, data={"deletedAt": gone.createdAt})
                async with count_statements() as seven:
                    second = (await client.get("/api/design-workshop-oversight/my-posts", headers=headers)).json()
                one = workshops[0].id
                single = (await client.get(f"/api/design-workshop-oversight/{one}", headers=headers)).json()
            return {
                "two": two.value,
                "seven": seven.value,
                "first": first,
                "second": second,
                "ids": [w.id for w in workshops],
                "single_oversight": [row for row in single["oversight"] if row["userId"] == admin.id],
                "admin": admin.id,
            }
        finally:
            ids = [w.id for w in workshops]
            await _quietly(
                db.designworkshopoversight.delete_many(where={"designWorkshopId": {"in": ids}}),
                db.designworkshopinspector.delete_many(where={"designWorkshopId": {"in": ids}}),
                db.designworkshop.delete_many(where={"id": {"in": ids}}),
                db.user.delete(where={"id": admin.id}),
            )

    seen = _private(work)
    _same(seen["two"], seen["seven"], "GET /design-workshop-oversight/my-posts")
    assert sorted(seen["first"]["workshops"]) == sorted(seen["ids"][:2])
    # The soft-deleted workshop is left out, as the per-workshop read answers 404 for it.
    assert sorted(seen["second"]["workshops"]) == sorted(seen["ids"][:-1])
    mine = seen["second"]["workshops"][seen["ids"][0]]
    assert mine["oversight"] == [{"capacity": "ASSISTANT_DIRECTOR", "userId": seen["admin"]}]
    assert [row["capacity"] for row in seen["single_oversight"]] == ["ASSISTANT_DIRECTOR"]
    assert seen["second"]["workshops"][seen["ids"][1]] == {
        "oversight": [],
        "inspectors": [{"userId": seen["admin"]}],
    }


# ── Questionnaires: a reuse and an upload write their questions in bulk ──────────────────────────


def _toolkit_questions(count: int) -> tuple[str, list[str]]:
    """``count`` questions of one shared-code section, as (section code, prompts)."""
    from app.services import cross_portal_codes

    by_section: dict[str, list[str]] = defaultdict(list)
    for question in cross_portal_codes.shared().questions:
        by_section[question.section].append(question.prompt)
    section, prompts = max(by_section.items(), key=lambda item: len(item[1]))
    assert len(prompts) >= count, f"the largest shared section has {len(prompts)} questions"
    return section, prompts[:count]


@needs_db
def test_reusing_a_questionnaire_costs_the_same_for_two_coded_questions_or_eight():
    """``POST /questionnaires/{id}/reuse`` then reconciled the shared codes by UPDATING every copied
    toolkit question, one statement each. The copy now carries its codes from the insert."""
    from app.services import cross_portal_codes

    async def source(owner: Any, prompts: list[str], section: str) -> Any:
        made = await db.questionnaire.create(data={"title": f"QC reuse {STAMP}", "ownerId": owner.id})
        block = await db.questionnaireformsection.create(
            data={"questionnaireId": made.id, "code": section, "title": "Toolkit", "sortOrder": 1}
        )
        for position, prompt in enumerate(prompts, start=1):
            await db.questionnaireformquestion.create(
                data={"sectionId": block.id, "prompt": prompt, "sortOrder": position}
            )
        return made

    async def work() -> dict[str, Any]:
        owner = await _person("ADMIN", "reuse")
        made: list[str] = []
        try:
            counts = []
            async with asgi_client() as client:
                headers = _auth(owner)
                await client.get("/api/questionnaires", headers=headers)
                for size in (2, 8):
                    section, prompts = _toolkit_questions(size)
                    original = await source(owner, prompts, section)
                    made.append(original.id)
                    async with count_statements() as counted:
                        reused = await client.post(
                            f"/api/questionnaires/{original.id}/reuse", json={}, headers=headers
                        )
                    assert reused.status_code == 201, reused.text
                    copy_id = reused.json()["questionnaire"]["id"]
                    made.append(copy_id)
                    counts.append(counted.value)
                    codes = [
                        q.questionCode
                        for q in await db.questionnaireformquestion.find_many(
                            where={"section": {"is": {"questionnaireId": copy_id}}}
                        )
                    ]
                    assert all(codes) and len(codes) == size, codes
                    assert await cross_portal_codes.reconcile_form_question_codes(db, questionnaire_id=copy_id) == 0
            return {"two": counts[0], "eight": counts[1]}
        finally:
            await _quietly(
                db.questionnaire.delete_many(where={"id": {"in": made}}),
                db.user.delete(where={"id": owner.id}),
            )

    seen = _private(work)
    _same(seen["two"], seen["eight"], "POST /questionnaires/{id}/reuse")


@needs_db
def test_an_uploaded_section_is_written_in_the_same_statements_however_many_questions_it_has():
    """``create_from_parsed`` (``POST /questionnaires/upload``) inserted its questions one by one."""
    from app.services.questionnaire_forms import create_from_parsed
    from app.services.questionnaire_xlsx import ParsedQuestion, ParsedQuestionnaire, ParsedSection

    async def work() -> dict[str, Any]:
        owner = await _person("ADMIN", "upload")
        made: list[str] = []
        try:
            counts = []
            for size in (2, 9):
                section, prompts = _toolkit_questions(size)
                parsed = ParsedQuestionnaire(
                    title=f"QC upload {STAMP}",
                    sections=[
                        ParsedSection(
                            code=section,
                            title="Toolkit",
                            questions=[ParsedQuestion(prompt=p, row=i + 2) for i, p in enumerate(prompts)],
                        )
                    ],
                )
                async with count_statements() as counted:
                    questionnaire_id, _report = await create_from_parsed(parsed, owner_id=owner.id)
                made.append(questionnaire_id)
                counts.append(counted.value)
                stored = await db.questionnaireformquestion.find_many(
                    where={"section": {"is": {"questionnaireId": questionnaire_id}}}, order={"sortOrder": "asc"}
                )
                assert [q.prompt for q in stored] == prompts
                assert all(q.questionCode for q in stored)
            return {"two": counts[0], "nine": counts[1]}
        finally:
            await _quietly(
                db.questionnaire.delete_many(where={"id": {"in": made}}),
                db.user.delete(where={"id": owner.id}),
            )

    seen = _private(work)
    _same(seen["two"], seen["nine"], "create_from_parsed")


# ── Design workshops: new stage rows and new custom fields go in as one insert ───────────────────


async def _admin_workshop(admin: Any) -> Any:
    return await db.designworkshop.create(data={"title": f"QC workshop {uuid.uuid4().hex[:8]}", "createdById": admin.id})


@needs_db
def test_saving_new_sketch_rows_costs_the_same_for_two_rows_or_seven():
    """``PUT /design-workshops/{id}/stages/{key}`` inserted every new collection row on its own."""

    def body(count: int) -> dict[str, Any]:
        return {
            "entries": [
                {
                    "entityKey": "sketch",
                    "ordinal": i,
                    "data": {"_clientKey": f"qc-sk-{i}", "sketchNo": f"SK-{i:02d}", "name": f"Sketch {i}"},
                }
                for i in range(count)
            ],
            "replaceCollections": True,
        }

    async def work() -> dict[str, Any]:
        admin = await _person("ADMIN", "stages")
        made: list[str] = []
        try:
            counts = []
            stored = []
            async with asgi_client() as client:
                headers = _auth(admin)
                warm = await _admin_workshop(admin)
                made.append(warm.id)
                await client.get(f"/api/design-workshops/{warm.id}/stages/SKETCH_DEVELOPMENT", headers=headers)
                for size in (2, 7):
                    workshop = await _admin_workshop(admin)
                    made.append(workshop.id)
                    async with count_statements() as counted:
                        saved = await client.put(
                            f"/api/design-workshops/{workshop.id}/stages/SKETCH_DEVELOPMENT",
                            json=body(size),
                            headers=headers,
                        )
                    assert saved.status_code == 200, saved.text
                    counts.append(counted.value)
                    stored.append(
                        await db.dwstageentry.count(
                            where={"designWorkshopId": workshop.id, "entityKey": "sketch", "deletedAt": None}
                        )
                    )
            return {"two": counts[0], "seven": counts[1], "stored": stored}
        finally:
            await _quietly(
                db.dwstageentry.delete_many(where={"designWorkshopId": {"in": made}}),
                db.designworkshop.delete_many(where={"id": {"in": made}}),
                db.user.delete(where={"id": admin.id}),
            )

    seen = _private(work)
    assert seen["stored"] == [2, 7]
    _same(seen["two"], seen["seven"], "PUT /design-workshops/{id}/stages/SKETCH_DEVELOPMENT")


@needs_db
def test_defining_a_custom_section_costs_the_same_for_two_fields_or_eight():
    """``PUT /design-workshops/{id}/custom-sections`` inserted every new field on its own."""

    def definition(count: int) -> dict[str, Any]:
        return {
            "sections": [
                {
                    "key": "qcnotes",
                    "title": "QC notes",
                    "stageKey": "CLUSTER_CRAFT_BACKGROUND",
                    "fields": [{"key": f"qcf{i}", "label": f"Field {i}", "type": "TEXT"} for i in range(count)],
                }
            ]
        }

    async def work() -> dict[str, Any]:
        admin = await _person("ADMIN", "custom")
        made: list[str] = []
        try:
            counts = []
            stored = []
            async with asgi_client() as client:
                headers = _auth(admin)
                warm = await _admin_workshop(admin)
                made.append(warm.id)
                await client.get(f"/api/design-workshops/{warm.id}/custom-sections", headers=headers)
                for size in (2, 8):
                    workshop = await _admin_workshop(admin)
                    made.append(workshop.id)
                    async with count_statements() as counted:
                        saved = await client.put(
                            f"/api/design-workshops/{workshop.id}/custom-sections",
                            json=definition(size),
                            headers=headers,
                        )
                    assert saved.status_code == 200, saved.text
                    counts.append(counted.value)
                    stored.append(
                        await db.dwcustomfield.count(where={"section": {"is": {"designWorkshopId": workshop.id}}})
                    )
            return {"two": counts[0], "eight": counts[1], "stored": stored}
        finally:
            await _quietly(
                db.dwcustomsection.delete_many(where={"designWorkshopId": {"in": made}}),
                db.designworkshop.delete_many(where={"id": {"in": made}}),
                db.user.delete(where={"id": admin.id}),
            )

    seen = _private(work)
    assert seen["stored"] == [2, 8]
    _same(seen["two"], seen["eight"], "PUT /design-workshops/{id}/custom-sections")


# ── The Monday summary: the whole estate is read once per pass ───────────────────────────────────


@needs_db
def test_every_administrators_summary_after_the_first_reads_nothing_more():
    """``build_payload`` re-read the inspection holders and the six whole-estate figures for EVERY
    administrator in a pass; they are the same answer for all of them, so the pass reads them once.
    The payload itself is unchanged: it is compared with one built without the shared reads."""
    from datetime import UTC, datetime

    from app.services import weekly_digest

    async def work() -> dict[str, Any]:
        admins = [await _person("ADMIN", f"digest-{i}") for i in range(4)]
        try:
            now = datetime.now(UTC)
            window = weekly_digest.window_for(weekly_digest.week_start_for(now))
            shared: dict[str, Any] = {}
            async with count_statements() as first:
                payload = await weekly_digest.build_payload(admins[0], window, now=now, shared=shared)
            async with count_statements() as rest:
                for admin in admins[1:]:
                    await weekly_digest.build_payload(admin, window, now=now, shared=shared)
            alone = await weekly_digest.build_payload(admins[0], window, now=now, shared=None)
            return {"first": first.value, "rest": rest.value, "same": payload == alone}
        finally:
            await _quietly(
                db.user.delete_many(where={"id": {"in": [a.id for a in admins]}}),
            )

    seen = _private(work)
    assert seen["same"], "the shared reads changed what a summary says"
    assert seen["rest"] == 0, f"three more administrators cost {seen['rest']} statements (the first: {seen['first']})"
