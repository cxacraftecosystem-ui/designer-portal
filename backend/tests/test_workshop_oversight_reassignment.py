from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the stage registry
from app.core.db import db
from app.core.security import hash_password
from app.schemas.design_workshops import StageEntryIn, StageSaveIn
from app.services import design_workshop_oversight as oversight, design_workshops

STAGE_ONE = "WORKSHOP_SETUP"
SETUP_ENTITY = "workshopSetup"
STAGE_THREE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
PLAN_ENTITY = "workshopPlan"

PASSWORD = "workshop-oversight-reassignment-password"


def _in_a_private_loop[T](work: Callable[[], Awaitable[T]]) -> T:
    async def connected() -> T:
        await db.connect()
        try:
            return await work()
        finally:
            await db.disconnect()

    return asyncio.run(connected())


async def _designer(stamp: str, slug: str, name: str, *, with_profile: bool) -> Any:
    address = f"oversight-reassign-{slug}-{stamp}@example.org"
    user = await db.user.create(
        data={
            "email": address,
            "name": name,
            "role": "DESIGNER",
            "passwordHash": hash_password(PASSWORD),
        }
    )
    await db.designerroster.create(
        data={
            "email": address,
            "fullName": name,
            "institution": "Directorate of Handicrafts",
            "isActive": True,
            "addedById": user.id,
        }
    )
    if with_profile:
        await db.designerprofile.create(
            data={"user": {"connect": {"id": user.id}}, "displayName": name}
        )
    return user


@pytest.fixture
def world() -> dict[str, Any]:
    stamp = uuid.uuid4().hex[:8]

    async def seed() -> dict[str, Any]:
        officer = await db.user.create(
            data={
                "email": f"oversight-officer-{stamp}@example.org",
                "name": f"Ministry admin {stamp}",
                "role": "MINISTRY_ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        creator = await db.user.create(
            data={
                "email": f"oversight-creator-{stamp}@example.org",
                "name": f"Platform admin {stamp}",
                "role": "ADMIN",
                "passwordHash": hash_password(PASSWORD),
            }
        )
        outgoing = await _designer(stamp, "outgoing", "A. Sharma", with_profile=True)
        incoming = await _designer(stamp, "incoming", "B. Mohanty", with_profile=True)
        profileless = await _designer(stamp, "profileless", "C. Nayak", with_profile=False)
        stranger = await _designer(stamp, "codesigner", "D. Patnaik", with_profile=True)

        workshop = await db.designworkshop.create(
            data={
                "title": f"Reassignment fixture {stamp}",
                "templateId": "DCH_STANDARD",
                "createdById": creator.id,
                "status": "IN_PROGRESS",
                "designerName": "A. Sharma",
            }
        )
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": outgoing.id}]
        )
        return {
            "officer": officer,
            "creator": creator,
            "outgoing": outgoing,
            "incoming": incoming,
            "profileless": profileless,
            "stranger": stranger,
            "workshop": workshop,
        }

    return _in_a_private_loop(seed)


async def _viewer_ids(workshop_id: str) -> set[str]:
    rows = await db.designworkshopviewer.find_many(where={"designWorkshopId": workshop_id})
    return {row.userId for row in rows}


def _spec(stage_key: str) -> Any:
    from app.services.stage_schema import stages

    spec = next((s for s in stages() if s.key == stage_key), None)
    assert spec is not None, f"{stage_key} is no longer in the registry"
    return spec


async def _stage_data(workshop_id: str, stage_key: str, entity_key: str) -> dict[str, Any]:
    rows = await db.dwstageentry.find_many(
        where={"designWorkshopId": workshop_id, "stageKey": stage_key, "deletedAt": None}
    )
    row = next((r for r in rows if r.entityKey == entity_key), None)
    return dict(getattr(row, "data", None) or {})


async def _save_stage_one(workshop_id: str, actor: Any, data: dict[str, Any]) -> None:
    await design_workshops.save_stage(
        workshop_id,
        _spec(STAGE_ONE),
        StageSaveIn(
            entries=[StageEntryIn(entityKey=SETUP_ENTITY, data=data, merge=True)],
            replaceCollections=False,
            emptiedEntities=[],
            submit=False,
        ),
        actor,
    )


async def _a_workshop_whose_stage_one_names_its_designer(world: dict[str, Any]) -> Any:
    designer = world["incoming"]
    await db.designerprofile.update(
        where={"userId": designer.id},
        data={
            "institution": "National Institute of Design",
            "qualification": "M.Des (Textile Design)",
            "biography": "Twelve years on pit-loom ikat.",
        },
    )
    workshop = world["workshop"]
    await oversight.set_named_designers(
        workshop, user_ids=[designer.id], lead_user_id=designer.id, actor=world["officer"]
    )
    await _save_stage_one(
        workshop.id,
        world["officer"],
        {"craftName": "Sambalpuri Ikat", "clusterName": "Barpali", "venue": "Weavers' Centre"},
    )
    return await db.designworkshop.find_unique(where={"id": workshop.id})


@needs_db
def test_naming_a_different_designer_takes_the_outgoing_one_off_the_workshop(world) -> None:
    async def scenario() -> None:
        world_workshop = world["workshop"]
        answer = await oversight.reassign_designer(
            world_workshop, world["incoming"].id, actor=world["officer"]
        )

        assert await _viewer_ids(world_workshop.id) == {world["incoming"].id}, (
            "the outgoing designer still holds a viewer row, so they still hold every stage write "
            "on a workshop the ministry has reassigned away from them"
        )
        assert answer["removedDesigner"]["userId"] == world["outgoing"].id
        assert answer["stillHaveAccess"] == [], (
            "the answer must name who ELSE can still write to this workshop; the officers screen "
            "has no viewer list and no route that could show them"
        )

    _in_a_private_loop(scenario)


@needs_db
def test_a_co_designer_the_reassignment_did_not_name_keeps_access_and_is_reported(
    world,
) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
        )

        answer = await oversight.reassign_designer(
            workshop, world["incoming"].id, actor=world["officer"]
        )

        assert world["stranger"].id in await _viewer_ids(workshop.id), (
            "a co-designer was removed by a call that named somebody else; this route knows "
            "nothing about their membership and must not decide it"
        )
        assert [row["userId"] for row in answer["stillHaveAccess"]] == [world["stranger"].id]
        assert answer["removedDesigner"]["userId"] == world["outgoing"].id

    _in_a_private_loop(scenario)


@needs_db
def test_the_outgoing_designer_is_not_guessed_at_when_two_viewers_would_write_one_name(
    world,
) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        twin = await _designer(uuid.uuid4().hex[:8], "twin", "A. Sharma", with_profile=True)
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": twin.id}]
        )

        answer = await oversight.reassign_designer(
            workshop, world["incoming"].id, actor=world["officer"]
        )

        assert answer["removedDesigner"] is None
        assert {world["outgoing"].id, twin.id} <= await _viewer_ids(workshop.id)
        assert {row["userId"] for row in answer["stillHaveAccess"]} == {
            world["outgoing"].id,
            twin.id,
        }, "an ambiguous answer has to be REPORTED; silence is what made the stale grant invisible"

    _in_a_private_loop(scenario)


@needs_db
def test_a_designer_who_has_never_opened_their_profile_still_moves_the_promoted_column(
    world,
) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        assert (
            await db.designerprofile.find_unique(where={"userId": world["profileless"].id})
        ) is None, "the fixture is not exercising the branch this test is about"

        answer = await oversight.reassign_designer(
            workshop, world["profileless"].id, actor=world["officer"]
        )

        assert answer["designerName"] == "C. Nayak", (
            f"the promoted column reads {answer['designerName']!r}; a workshop reassigned to a "
            "designer with no profile row still names the designer it was taken away from"
        )
        reread = await db.designworkshop.find_unique(where={"id": workshop.id})
        assert reread.designerName == "C. Nayak"
        assert answer["stagesWritten"], "no stage was written, so nothing can have moved the column"

    _in_a_private_loop(scenario)


@needs_db
def test_the_reassignment_never_writes_the_officers_own_name_into_the_report(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        answer = await oversight.reassign_designer(
            workshop, world["incoming"].id, actor=world["officer"]
        )
        assert answer["designerName"] == "B. Mohanty"
        assert world["officer"].name not in str(answer["designerName"])

    _in_a_private_loop(scenario)



@needs_db
def test_a_co_designer_can_be_added_without_touching_the_reports_designer(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        answer = await oversight.set_named_designers(
            workshop,
            user_ids=[world["outgoing"].id, world["stranger"].id],
            lead_user_id=None,
            actor=world["officer"],
        )

        assert await _viewer_ids(workshop.id) == {world["outgoing"].id, world["stranger"].id}
        assert answer["designerName"] == "A. Sharma", (
            "adding a co-designer moved the name on the report's cover; that is defect 2 in a new "
            "place"
        )
        assert answer["stagesWritten"] == [], "the prefill arm ran on a save that only added access"
        assert answer["removedDesigners"] == []
        reread = await db.designworkshop.find_unique(where={"id": workshop.id})
        assert reread.designerName == "A. Sharma"

    _in_a_private_loop(scenario)


@needs_db
def test_a_co_designer_can_be_taken_off_and_the_answer_names_them(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
        )

        answer = await oversight.set_named_designers(
            workshop, user_ids=[world["outgoing"].id], lead_user_id=None, actor=world["officer"]
        )

        assert await _viewer_ids(workshop.id) == {world["outgoing"].id}
        assert [row["userId"] for row in answer["removedDesigners"]] == [world["stranger"].id]
        assert answer["removedDesigners"][0]["name"] == "D. Patnaik", (
            "the officer is shown an account id rather than a person"
        )
        assert answer["designerName"] == "A. Sharma"
        assert answer["stagesWritten"] == []

    _in_a_private_loop(scenario)


@needs_db
def test_taking_every_designer_off_a_workshop_takes_the_name_off_its_cover_too(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        answer = await oversight.set_named_designers(
            workshop, user_ids=[], lead_user_id=None, actor=world["officer"]
        )

        assert await _viewer_ids(workshop.id) == set(), "the last designer kept their stage writes"
        assert [row["userId"] for row in answer["removedDesigners"]] == [world["outgoing"].id]
        assert answer["designerName"] is None, (
            "the workshop is for nobody and its cover still names the designer it was taken from"
        )
        assert answer["designers"] == []
        reread = await db.designworkshop.find_unique(where={"id": workshop.id})
        assert reread.designerName is None
        assert reread.title == workshop.title, "the NOT NULL promoted column was written too"

    _in_a_private_loop(scenario)


@needs_db
def test_the_cover_name_is_blanked_on_stage_one_and_nothing_else_on_it_is(world) -> None:
    async def scenario() -> None:
        record = await _a_workshop_whose_stage_one_names_its_designer(world)
        before = await _stage_data(record.id, STAGE_ONE, SETUP_ENTITY)
        assert before["designerName"] == "B. Mohanty", (
            "the fixture never named anybody on the stage"
        )
        assert before["designerInstitution"] == "National Institute of Design"

        answer = await oversight.set_named_designers(
            record, user_ids=[], lead_user_id=None, actor=world["officer"]
        )

        assert answer["stagesWritten"] == [STAGE_ONE], (
            "no stage was written, so nothing can have blanked the field the column is promoted "
            "from"
        )
        after = await _stage_data(record.id, STAGE_ONE, SETUP_ENTITY)
        assert not str(after.get("designerName") or "").strip(), (
            "stage 1 still names the designer, so the next save of it re-promotes the name onto "
            "the cover of a workshop nobody is on"
        )
        assert after["designerInstitution"] == "National Institute of Design", (
            "a field the removal was never about was taken with it"
        )
        assert after["craftName"] == "Sambalpuri Ikat"
        assert after["clusterName"] == "Barpali"
        assert after["venue"] == "Weavers' Centre"

        plan = await _stage_data(record.id, STAGE_THREE, PLAN_ENTITY)
        assert plan["designerQualification"] == "M.Des (Textile Design)", (
            "stage 3 was rewritten; nothing on it is promoted and nothing on it is the authorship"
        )
        assert plan["designerProfile"], (
            "the designer's biography was deleted by a removal of access"
        )

        reread = await db.designworkshop.find_unique(where={"id": record.id})
        assert reread.designerName is None
        assert reread.craftName == "Sambalpuri Ikat", (
            "a promoted column nobody asked about was nulled"
        )
        assert reread.clusterName == "Barpali"
        assert reread.venue == "Weavers' Centre"
        assert await _viewer_ids(record.id) == set()

    _in_a_private_loop(scenario)


@needs_db
def test_the_blanked_cover_name_is_not_put_back_by_the_next_stage_one_save(world) -> None:
    async def scenario() -> None:
        record = await _a_workshop_whose_stage_one_names_its_designer(world)
        await oversight.set_named_designers(
            record, user_ids=[], lead_user_id=None, actor=world["officer"]
        )

        await _save_stage_one(record.id, world["officer"], {"venue": "Community hall, Barpali"})

        reread = await db.designworkshop.find_unique(where={"id": record.id})
        assert reread.designerName is None, (
            "the next stage-1 save re-promoted the name off a stage field that was never blanked; "
            "the column had been written directly"
        )
        assert reread.venue == "Community hall, Barpali", (
            "the control: that save did reach the header"
        )

    _in_a_private_loop(scenario)


@needs_db
def test_dropping_the_named_designer_while_others_remain_is_refused(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
        )

        with pytest.raises(Exception) as exc:
            await oversight.set_named_designers(
                workshop,
                user_ids=[world["stranger"].id],
                lead_user_id=None,
                actor=world["officer"],
            )
        assert getattr(exc.value, "status_code", None) == 422
        assert "A. Sharma" in str(getattr(exc.value, "detail", "")), (
            "the refusal must name the designer it is about; an officer cannot act on 'that "
            "designer'"
        )
        assert await _viewer_ids(workshop.id) == {world["outgoing"].id, world["stranger"].id}

    _in_a_private_loop(scenario)


@needs_db
def test_naming_a_different_lead_in_the_set_moves_the_column_and_the_access_together(
    world,
) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        answer = await oversight.set_named_designers(
            workshop,
            user_ids=[world["incoming"].id],
            lead_user_id=world["incoming"].id,
            actor=world["officer"],
        )

        assert await _viewer_ids(workshop.id) == {world["incoming"].id}
        assert [row["userId"] for row in answer["removedDesigners"]] == [world["outgoing"].id]
        assert answer["designerName"] == "B. Mohanty"
        assert answer["stagesWritten"], "no stage was written, so nothing can have moved the column"
        reread = await db.designworkshop.find_unique(where={"id": workshop.id})
        assert reread.designerName == "B. Mohanty"
        assert {row["userId"] for row in answer["designers"]} == {world["incoming"].id}
        assert [row["isLead"] for row in answer["designers"]] == [True]

    _in_a_private_loop(scenario)


@needs_db
def test_a_lead_with_no_profile_row_still_moves_the_promoted_column_through_this_door(
    world,
) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        assert (
            await db.designerprofile.find_unique(where={"userId": world["profileless"].id})
        ) is None, "the fixture is not exercising the branch this test is about"

        answer = await oversight.set_named_designers(
            workshop,
            user_ids=[world["profileless"].id],
            lead_user_id=world["profileless"].id,
            actor=world["officer"],
        )

        assert answer["designerName"] == "C. Nayak"
        reread = await db.designworkshop.find_unique(where={"id": workshop.id})
        assert reread.designerName == "C. Nayak"
        assert world["officer"].name not in str(answer["designerName"])

    _in_a_private_loop(scenario)


@needs_db
def test_the_workshops_creator_in_the_body_is_a_no_op_rather_than_a_second_grant(
    world,
) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        answer = await oversight.set_named_designers(
            workshop,
            user_ids=[world["outgoing"].id, world["creator"].id],
            lead_user_id=None,
            actor=world["officer"],
        )

        assert await _viewer_ids(workshop.id) == {world["outgoing"].id}, (
            "the creator was given a redundant viewer row, so revoking their access now takes two "
            "deletions in two tables"
        )
        assert world["creator"].id not in {row["userId"] for row in answer["designers"]}

    _in_a_private_loop(scenario)


@needs_db
def test_the_read_the_screen_was_missing_marks_exactly_one_row_as_the_lead(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        await db.designworkshopviewer.create_many(
            data=[{"designWorkshopId": workshop.id, "userId": world["stranger"].id}]
        )
        reread = await db.designworkshop.find_unique(where={"id": workshop.id})

        rows = await oversight.named_designer_rows(reread)

        assert {row["userId"] for row in rows} == {world["outgoing"].id, world["stranger"].id}
        assert all("isLead" in row for row in rows)
        assert [row["userId"] for row in rows if row["isLead"]] == [world["outgoing"].id]
        assert world["creator"].id not in {row["userId"] for row in rows}, (
            "the creator is in `designers`; they hold no viewer row and an empty list here means "
            "'nobody but whoever opened it', which the screen says in words"
        )

    _in_a_private_loop(scenario)


@needs_db
def test_unfiling_an_artisan_clears_the_link_and_deletes_no_record(world) -> None:
    async def scenario() -> None:
        workshop = world["workshop"]
        other = await db.designworkshop.create(
            data={
                "title": f"Roster neighbour {uuid.uuid4().hex[:8]}",
                "templateId": "DCH_STANDARD",
                "createdById": world["creator"].id,
            }
        )
        artisan = await db.artisan.create(
            data={
                "name": "Roster artisan",
                "place": "Puri",
                "designWorkshopId": workshop.id,
                "createdById": world["creator"].id,
            }
        )

        assert await oversight.unlink_artisan_from_workshop(workshop.id, artisan.id) is True
        assert await oversight.unlink_artisan_from_workshop(workshop.id, artisan.id) is False

        still_there = await db.artisan.find_unique(where={"id": artisan.id})
        assert still_there is not None, "an unfiling deleted a regulated person-record"
        assert still_there.designWorkshopId is None

        await db.artisan.update(where={"id": artisan.id}, data={"designWorkshopId": other.id})
        assert await oversight.unlink_artisan_from_workshop(workshop.id, artisan.id) is False, (
            "a stale screen unfiled a record from a workshop it was not looking at"
        )
        reread = await db.artisan.find_unique(where={"id": artisan.id})
        assert reread.designWorkshopId == other.id

    _in_a_private_loop(scenario)
