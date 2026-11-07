import os
import uuid
from datetime import UTC, datetime
from typing import Any

import pytest

from app.api.routes.questionnaire import artisan_set_key
from app.core.db import db
from app.core.security import create_access_token, hash_password

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(
        not _LOCAL,
        reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL",
    ),
    pytest.mark.anyio,
]

STAMP = uuid.uuid4().hex[:8]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


async def _section(code: str, title: str) -> Any:
    found = await db.questionnairesection.find_first(where={"code": code})
    if found:
        return found
    highest = await db.questionnairesection.find_many(order={"sortOrder": "desc"}, take=1)
    return await db.questionnairesection.create(
        data={
            "code": code,
            "title": title,
            "sortOrder": (highest[0].sortOrder if highest else 0) + 1,
        }
    )


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    rows: dict[str, Any] = {}
    await db.connect()
    try:
        people = {}
        for slug, role, name in (
            ("author", "DESIGNER", "Recording Designer"),
            ("stranger", "RESEARCHER", "Unrelated Researcher"),
            ("admin", "ADMIN", "Questionnaire Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qmerge-{slug}-{STAMP}@example.org",
                    "name": f"{name} {STAMP}",
                    "role": role,
                    "passwordHash": hash_password("unused"),
                }
            )
        rows["people"] = people

        section_d = await _section("D", "Black Pottery")
        section_f = await _section("F", "Tools and Materials")
        question_d = await db.questionnairequestion.create(
            data={
                "sectionCode": section_d.code,
                "sectionTitle": section_d.title,
                "prompt": f"How many looms are in the workshop? ({STAMP})",
                "sortOrder": 9001,
                "sectionId": section_d.id,
            }
        )
        question_f = await db.questionnairequestion.create(
            data={
                "sectionCode": section_f.code,
                "sectionTitle": section_f.title,
                "prompt": f"Which tools are shared between households? ({STAMP})",
                "sortOrder": 9002,
                "sectionId": section_f.id,
            }
        )
        rows["questionD"] = question_d
        rows["questionF"] = question_f

        here = await db.workshop.create(
            data={
                "title": f"Bagru workshop {STAMP}",
                "place": f"Bagru {STAMP}",
                "date": datetime(2026, 4, 1, tzinfo=UTC),
                "createdById": people["author"].id,
            }
        )
        there = await db.workshop.create(
            data={
                "title": f"Sanganer workshop {STAMP}",
                "place": f"Sanganer {STAMP}",
                "date": datetime(2026, 4, 2, tzinfo=UTC),
                "createdById": people["author"].id,
            }
        )

        async def artisan(tag: str) -> Any:
            return await db.artisan.create(
                data={
                    "name": f"Artisan {tag} {STAMP}",
                    "place": f"Bhuj {STAMP}",
                    "createdById": people["author"].id,
                }
            )

        async def interview(title: str, artisan_ids: list[str], **extra: Any) -> Any:
            key = artisan_set_key(
                artisan_ids,
                workshop_id=extra.get("workshopId"),
                design_workshop_id=extra.get("designWorkshopId"),
            )
            made = await db.questionnaireinterview.create(
                data={
                    "title": title,
                    "createdById": people["author"].id,
                    "artisanSetKey": key,
                    **extra,
                }
            )
            for aid in artisan_ids:
                await db.questionnaireinterviewartisan.create(
                    data={"interviewId": made.id, "artisanId": aid}
                )
            return made

        a1, a2 = await artisan("1a"), await artisan("1b")
        rows["namedHolder"] = await interview("D Black Pottery", [a1.id, a2.id])
        rows["namedShort"] = await interview("Sitting section F", [a1.id])
        rows["namedIds"] = [a1.id, a2.id]

        b1, b2 = await artisan("2a"), await artisan("2b")
        rows["cleanTarget"] = await interview("D Black Pottery", [b1.id, b2.id])
        rows["cleanSource"] = await interview("Sitting section F notes", [b1.id])
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["cleanTarget"].id,
                "questionId": question_d.id,
                "answerText": "Twelve looms",
                "answeredById": people["author"].id,
            }
        )
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["cleanSource"].id,
                "questionId": question_f.id,
                "answerText": "The dyeing vats are shared",
                "answeredById": people["author"].id,
            }
        )
        rows["clip"] = await db.mediafile.create(
            data={
                "originalFilename": f"F_Q1_{STAMP}_0312_20260920.m4a",
                "mediaType": "AUDIO",
                "mimeType": "audio/mp4",
                "sizeBytes": 481_233,
                "bucket": "design-workshop-local",
                "objectKey": f"questionnaire/{STAMP}/f-sitting.m4a",
                "url": f"https://example.invalid/{STAMP}/f-sitting.m4a",
                "uploadedById": people["author"].id,
                "questionnaireInterviewId": rows["cleanSource"].id,
            }
        )

        c1, c2 = await artisan("3a"), await artisan("3b")
        rows["clashTarget"] = await interview("D Black Pottery", [c1.id, c2.id])
        rows["clashSource"] = await interview("Sitting section F", [c1.id])
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["clashTarget"].id,
                "questionId": question_d.id,
                "answerText": "Twelve looms",
                "answeredById": people["author"].id,
            }
        )
        await db.questionnaireresponse.create(
            data={
                "interviewId": rows["clashSource"].id,
                "questionId": question_d.id,
                "answerText": "Twenty looms",
                "answeredById": people["author"].id,
            }
        )

        d1, d2 = await artisan("4a"), await artisan("4b")
        rows["scopeTarget"] = await interview(
            "D Black Pottery", [d1.id, d2.id], workshopId=here.id
        )
        rows["scopeSource"] = await interview(
            "Sitting section F", [d1.id], workshopId=there.id
        )

        e1, e2 = await artisan("5a"), await artisan("5b")
        rows["gateTarget"] = await interview("D Black Pottery", [e1.id, e2.id])
        rows["gateSource"] = await interview("Sitting section F", [e1.id])
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        rows["client"] = client
        yield rows


def _auth(env, slug: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=env['people'][slug].id)}"}



async def test_patch_409_names_the_interview_that_holds_the_set(env):
    response = env["client"].patch(
        f"/api/questionnaire/interviews/{env['namedShort'].id}",
        json={"artisanIds": env["namedIds"]},
        headers=_auth(env, "author"),
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "duplicate_artisan_set"
    assert "already exists for this exact set of artisans" in detail["message"]
    assert "single shared entry per artisan set" in detail["message"]
    assert detail["existingInterviewId"] == env["namedHolder"].id
    assert detail["existingInterviewTitle"] == "D Black Pottery"

    after = env["client"].get(
        f"/api/questionnaire/interviews/{env['namedShort'].id}", headers=_auth(env, "author")
    )
    assert after.status_code == 200, after.text
    assert [link["artisanId"] for link in after.json()["artisans"]] == [env["namedIds"][0]]



async def test_merge_moves_responses_and_media_then_removes_the_source(env):
    source_id, target_id = env["cleanSource"].id, env["cleanTarget"].id
    response = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "admin"),
    )
    assert response.status_code == 200, response.text
    body = response.json()

    assert body["id"] == target_id
    answers = {row["questionId"]: row["answerText"] for row in body["responses"]}
    assert answers[env["questionD"].id] == "Twelve looms"
    assert answers[env["questionF"].id] == "The dyeing vats are shared"

    media = {row["id"]: row for row in body["media"]}
    assert env["clip"].id in media, "the recording did not move onto the surviving interview"
    assert "X-Amz-Signature=" in media[env["clip"].id]["url"]
    assert media[env["clip"].id]["objectKey"] == env["clip"].objectKey

    assert "D Black Pottery" in body["title"]
    assert "section F" in body["title"]

    merged_from = body["extraMetadata"]["mergedFrom"]
    assert merged_from[-1]["interviewId"] == source_id
    assert merged_from[-1]["sectionCodes"] == ["F"]

    gone = env["client"].get(
        f"/api/questionnaire/interviews/{source_id}", headers=_auth(env, "author")
    )
    assert gone.status_code == 404, gone.text


async def test_detail_read_carries_media_urls_and_still_withholds_them_from_others(env):
    target_id = env["cleanTarget"].id
    mine = env["client"].get(
        f"/api/questionnaire/interviews/{target_id}", headers=_auth(env, "author")
    )
    assert mine.status_code == 200, mine.text
    uploader_view = {row["id"]: row for row in mine.json()["media"]}[env["clip"].id]
    assert "X-Amz-Signature=" in uploader_view["url"]

    theirs = env["client"].get(
        f"/api/questionnaire/interviews/{target_id}", headers=_auth(env, "stranger")
    )
    assert theirs.status_code == 200, theirs.text
    other_view = {row["id"]: row for row in theirs.json()["media"]}[env["clip"].id]
    assert other_view.get("url") is None
    assert "objectKey" not in other_view



async def test_conflicting_answers_are_refused_and_every_question_is_named(env):
    source_id, target_id = env["clashSource"].id, env["clashTarget"].id
    response = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "admin"),
    )
    assert response.status_code == 409, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "merge_answer_conflict"
    assert [c["questionId"] for c in detail["conflicts"]] == [env["questionD"].id]
    assert detail["conflicts"][0]["fields"] == ["answerText"]
    assert detail["conflicts"][0]["thisInterview"]["answerText"] == "Twenty looms"
    assert detail["conflicts"][0]["targetInterview"]["answerText"] == "Twelve looms"
    assert env["questionD"].prompt in detail["message"]
    assert "D" in detail["conflicts"][0]["sectionCode"]

    still_there = env["client"].get(
        f"/api/questionnaire/interviews/{source_id}", headers=_auth(env, "author")
    )
    assert still_there.status_code == 200, still_there.text
    survivor = env["client"].get(
        f"/api/questionnaire/interviews/{target_id}", headers=_auth(env, "author")
    )
    kept = {row["questionId"]: row["answerText"] for row in survivor.json()["responses"]}
    assert kept[env["questionD"].id] == "Twelve looms"


async def test_merge_across_two_workshops_is_refused(env):
    response = env["client"].post(
        f"/api/questionnaire/interviews/{env['scopeSource'].id}"
        f"/merge-into/{env['scopeTarget'].id}",
        headers=_auth(env, "admin"),
    )
    assert response.status_code == 422, response.text
    detail = response.json()["detail"]
    assert detail["code"] == "merge_cross_scope"
    assert detail["thisInterview"]["workshopId"] != detail["targetInterview"]["workshopId"]

    still_there = env["client"].get(
        f"/api/questionnaire/interviews/{env['scopeSource'].id}", headers=_auth(env, "author")
    )
    assert still_there.status_code == 200, still_there.text



async def test_the_gate_is_the_same_one_that_guards_an_edit_of_this_interview(env):
    source_id, target_id = env["gateSource"].id, env["gateTarget"].id
    refused = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "stranger"),
    )
    assert refused.status_code == 403, refused.text

    intact = env["client"].get(
        f"/api/questionnaire/interviews/{source_id}", headers=_auth(env, "stranger")
    )
    assert intact.status_code == 200, intact.text

    allowed = env["client"].post(
        f"/api/questionnaire/interviews/{source_id}/merge-into/{target_id}",
        headers=_auth(env, "admin"),
    )
    assert allowed.status_code == 200, allowed.text
    assert allowed.json()["id"] == target_id


async def test_an_interview_cannot_be_merged_into_itself(env):
    response = env["client"].post(
        f"/api/questionnaire/interviews/{env['namedHolder'].id}"
        f"/merge-into/{env['namedHolder'].id}",
        headers=_auth(env, "admin"),
    )
    assert response.status_code == 422, response.text
    assert response.json()["detail"]["code"] == "merge_self"
