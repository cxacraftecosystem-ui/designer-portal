import asyncio
import uuid
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.core.db import db
from app.core.security import hash_password
from app.schemas.design_workshops import StageEntryIn, StageSaveIn
from app.services import design_workshops as service

SETUP_STAGE = "WORKSHOP_SETUP"
SETUP_ENTITY = "workshopSetup"

NOTE = "Stage 7's cost table does not add up."


def _in_a_private_loop[T](work: Callable[[], Awaitable[T]]) -> T:
    async def connected() -> T:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


@pytest.fixture
def people() -> dict[str, Any]:
    stamp = uuid.uuid4().hex[:8]

    async def account(slug: str, role: str, name: str) -> Any:
        return await db.user.create(
            data={
                "email": f"resubmit-{slug}-{stamp}@example.org",
                "name": name,
                "role": role,
                "passwordHash": hash_password("unused"),
            }
        )

    async def seed() -> dict[str, Any]:
        return {
            "designer": await account("designer", "DESIGNER", "Asha Patel"),
            "inspector": await account("inspector", "INSPECTOR", "Ravi Nair"),
            "officer": await account("officer", "MINISTRY_ADMIN", "A Ministry Officer"),
        }

    return _in_a_private_loop(seed)


def _spec(key: str) -> Any:
    from app.services.stage_schema import stages

    spec = next((s for s in stages() if s.key == key), None)
    assert spec is not None, f"{key} is no longer in the registry"
    return spec


def _setup_payload(data: dict[str, Any]) -> StageSaveIn:
    return StageSaveIn(
        entries=[StageEntryIn(entityKey=SETUP_ENTITY, ordinal=0, data=data)],
        replaceCollections=False,
        emptiedEntities=[],
        submit=False,
    )


async def _sent_back(people: dict[str, Any], **overrides: Any) -> Any:
    data: dict[str, Any] = {
        "title": "Bargarh ikat 2026",
        "createdById": people["designer"].id,
        "status": "NEEDS_REVISION",
        "submissionRound": 2,
        "reviewNotes": NOTE,
        "reviewedById": people["inspector"].id,
        "reviewedAt": datetime.now(UTC),
    }
    data.update(overrides)
    return await db.designworkshop.create(data=data)


async def _header(workshop_id: str) -> Any:
    return await db.designworkshop.find_unique(where={"id": workshop_id})



@needs_db
def test_an_officers_write_stores_the_content_and_does_not_hand_the_report_back_in(people):
    async def scenario() -> None:
        workshop = await _sent_back(people)
        result = await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload({"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}),
            people["officer"],
        )
        assert result["errors"] == {}, result["errors"]

        after = await _header(workshop.id)
        assert str(after.status) == "NEEDS_REVISION", (
            "an officer's bookkeeping write handed the designer's report back in. See the "
            "`resubmits` keyword: the actor is the caller's to declare, not save_stage's to infer."
        )
        assert after.submissionRound == 2, (
            "a submission round was spent by somebody who was not answering the inspector. Nothing "
            "decrements this counter, and DwInspectionFeedback.round is copied from it and never "
            "recomputed, so every later suggestion would name a cycle nobody entered."
        )
        assert after.reviewNotes == NOTE, "what was asked for was cleared off the header"
        assert after.reviewedById == people["inspector"].id, (
            "who asked for it was cleared off the header"
        )
        assert after.reviewedAt is not None
        assert after.craftName == "Sambalpuri Ikat"
        assert after.title == "Bargarh ikat cover"

    _in_a_private_loop(scenario)


@needs_db
def test_the_designers_edit_is_the_resubmission(people):
    async def scenario() -> None:
        workshop = await _sent_back(people)
        await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload({"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}),
            people["designer"],
            resubmits=True,
        )

        after = await _header(workshop.id)
        assert str(after.status) == "PRE_SUBMISSION"
        assert after.submissionRound == 3, (
            "the edit IS the resubmission and spends exactly one round"
        )
        assert after.reviewNotes is None
        assert after.reviewedById is None
        assert after.reviewedAt is None

    _in_a_private_loop(scenario)


@needs_db
def test_a_save_that_changed_nothing_does_not_spend_a_round(people):
    async def scenario() -> None:
        workshop = await _sent_back(
            people, status="IN_PROGRESS", submissionRound=0, reviewNotes=None
        )
        payload = {"workshopTitle": "Bargarh ikat cover", "craftName": "Sambalpuri Ikat"}
        await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload(payload),
            people["designer"],
            resubmits=True,
        )
        await db.designworkshop.update(
            where={"id": workshop.id},
            data={"status": "NEEDS_REVISION", "submissionRound": 2, "reviewNotes": NOTE},
        )

        await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload(payload),
            people["designer"],
            resubmits=True,
        )

        after = await _header(workshop.id)
        assert str(after.status) == "NEEDS_REVISION", (
            "re-sending the stored answers read as a resubmission. An offline replay is the most "
            "ordinary path this table has."
        )
        assert after.submissionRound == 2
        assert after.reviewNotes == NOTE

    _in_a_private_loop(scenario)



@needs_db
def test_a_status_flip_inside_the_window_costs_the_counter_and_not_the_corrections(
    people, monkeypatch
):
    async def scenario() -> None:
        workshop = await _sent_back(people, craftName="Sambalpuri Ikat")
        original = service.hydrate_entries
        landed: list[str] = []

        async def wrapper(pending: Any, **kwargs: Any) -> Any:
            result = await original(pending, **kwargs)
            if not landed:
                landed.append(workshop.id)
                await db.designworkshop.update_many(
                    where={"id": workshop.id, "status": "NEEDS_REVISION"},
                    data={
                        "status": "PRE_SUBMISSION",
                        "submissionRound": {"increment": 1},
                        "reviewNotes": None,
                        "reviewedById": None,
                        "reviewedAt": None,
                    },
                )
            return result

        monkeypatch.setattr(service, "hydrate_entries", wrapper)

        result = await service.save_stage(
            workshop.id,
            _spec(SETUP_STAGE),
            _setup_payload({"workshopTitle": "Bandhej cover", "craftName": "Bandhej"}),
            people["designer"],
            resubmits=True,
        )
        assert landed, "the competing write never fired; this test proved nothing"
        assert result["errors"] == {}, result["errors"]

        after = await _header(workshop.id)
        assert after.craftName == "Bandhej", (
            "the designer's correction was dropped from the header because the status predicate "
            "missed. The compare-and-set belongs on the transition keys alone — the promoted "
            "columns must be written by id."
        )
        assert after.title == "Bandhej cover"
        assert after.schemaVersion is not None, (
            "schemaVersion rode with the promoted columns in the merged statement and was lost "
            "with them"
        )
        assert str(after.status) == "PRE_SUBMISSION"
        assert after.submissionRound == 3, (
            "the round moved twice for one act. The predicate on the transition write is what "
            "makes the loser of this race write nothing, and it must stay."
        )

    _in_a_private_loop(scenario)
