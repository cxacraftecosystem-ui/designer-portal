import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from scripts.seed_shared_questionnaire import TITLE, seed_shared_questionnaire

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database"),
    pytest.mark.anyio,
]

NOTHING_CHANGED = {
    "sections_created": 0,
    "sections_updated": 0,
    "questions_created": 0,
    "questions_updated": 0,
    "questions_left_alone": 0,
}


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def seeded():
    from fastapi.testclient import TestClient

    from app.core.security import create_access_token
    from app.main import app

    facts: dict[str, Any] = {}
    await db.connect()
    try:
        if await db.user.count(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}}) == 0:
            await db.user.create(
                data={
                    "email": f"seed-shared-{uuid.uuid4().hex[:8]}@example.org",
                    "name": "Seed Admin",
                    "role": "ADMIN",
                }
            )
        owner = await db.user.find_first(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}})

        facts["id"], facts["first_counts"] = await seed_shared_questionnaire()
        row = await db.questionnaire.find_unique(where={"id": facts["id"]})
        facts["row"] = {
            "title": row.title,
            "isShared": row.isShared,
            "isActive": row.isActive,
            "designWorkshopId": row.designWorkshopId,
            "kind": row.kind,
        }
        facts["sections"] = await db.questionnaireformsection.count(
            where={"questionnaireId": facts["id"]}
        )
        facts["questions"] = await db.questionnaireformquestion.count(
            where={"section": {"is": {"questionnaireId": facts["id"]}}}
        )

        facts["second_id"], facts["second_counts"] = await seed_shared_questionnaire()
        facts["published"] = await db.questionnaire.count(
            where={"isShared": True, "title": TITLE}
        )

        section = await db.questionnaireformsection.find_first(
            where={"questionnaireId": facts["id"]}, order={"sortOrder": "asc"}
        )
        question = await db.questionnaireformquestion.find_first(
            where={"sectionId": section.id}, order={"sortOrder": "asc"}
        )
        original_prompt = question.prompt
        reworded = "How many weavers work with you?"
        entry = None
        try:
            await db.questionnaireformquestion.update(
                where={"id": question.id}, data={"prompt": reworded}
            )
            entry = await db.questionnaireformentry.create(
                data={
                    "questionnaireId": facts["id"],
                    "title": f"Seeder rule check {uuid.uuid4().hex[:6]}",
                    "createdById": owner.id,
                }
            )
            await db.questionnaireformanswer.create(
                data={
                    "entryId": entry.id,
                    "questionId": question.id,
                    "answerText": "12",
                    "answeredById": owner.id,
                }
            )
            _id, facts["third_counts"] = await seed_shared_questionnaire()
            after = await db.questionnaireformquestion.find_unique(where={"id": question.id})
            facts["prompt_after_reseed"] = after.prompt
            facts["reworded"] = reworded
        finally:
            if entry is not None:
                await db.questionnaireformentry.delete(where={"id": entry.id})
            await db.questionnaireformquestion.update(
                where={"id": question.id}, data={"prompt": original_prompt}
            )

        newcomer = await db.user.create(
            data={
                "email": f"seed-newcomer-{uuid.uuid4().hex[:8]}@example.org",
                "name": "Brand New Designer",
                "role": "DESIGNER",
            }
        )
        facts["newcomer_token"] = create_access_token(subject=newcomer.id)
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        facts["client"] = client
        yield facts


def test_the_published_row_exists_and_is_shared(seeded) -> None:
    row = seeded["row"]
    assert row["title"] == TITLE
    assert row["isShared"] is True, "the whole point of the row is that it is published"
    assert row["isActive"] is True
    assert row["designWorkshopId"] is None, (
        "the standard form belongs to every workshop, so attaching it to ONE would put its sittings "
        "into that workshop's report annexure"
    )


def test_it_is_filed_as_a_workshop_interview(seeded) -> None:
    assert seeded["row"]["kind"] == "WORKSHOP_INTERVIEW"


def test_it_carries_the_whole_instrument(seeded) -> None:
    assert seeded["sections"] == 24
    assert seeded["questions"] == 285


def test_re_running_it_changes_nothing(seeded) -> None:
    assert seeded["second_id"] == seeded["id"], "a re-run made a SECOND published questionnaire"
    assert seeded["second_counts"] == NOTHING_CHANGED, seeded["second_counts"]
    assert seeded["published"] == 1


def test_an_answered_question_is_never_reworded(seeded) -> None:
    assert seeded["third_counts"]["questions_left_alone"] == 1, seeded["third_counts"]
    assert seeded["prompt_after_reseed"] == seeded["reworded"], (
        "the seeder reworded a question that already carries an answer — '12' was given under the "
        "wording that was there, and this is the 'twelve looms becomes twelve weavers' failure "
        "arriving by a different door"
    )


def test_a_designer_sees_it_without_owning_it(seeded) -> None:
    client = seeded["client"]
    headers = {"Authorization": f"Bearer {seeded['newcomer_token']}"}
    listed = client.get("/api/questionnaires", headers=headers)
    assert listed.status_code == 200, listed.text
    rows = listed.json()["items"]
    assert any(row["id"] == seeded["id"] and row["isShared"] for row in rows), (
        "the published questionnaire is invisible to a designer who owns nothing — which is the "
        "exact report this feature answers"
    )
    position = next(i for i, row in enumerate(rows) if row["id"] == seeded["id"])
    assert all(row["isShared"] for row in rows[:position]), (
        "an unpublished questionnaire sorts above the standard form — a designer with more than a "
        "page of their own forms would never reach it"
    )

    options = client.get("/api/questionnaires/options", headers=headers)
    assert options.status_code == 200
    assert any(option["id"] == seeded["id"] for option in options.json()), (
        "the attach dropdown does not offer the published questionnaire — the seeing half without "
        "the selecting half looks like the feature working right up to the moment it is used"
    )
