import uuid

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password
from prisma import Json

pytestmark = [needs_db, pytest.mark.anyio]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8].upper()
    await db.connect()
    try:
        author = await db.user.create(
            data={
                "email": f"matrix-{stamp}@example.org",
                "name": "Matrix Author",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
        highest = await db.questionnairesection.find_first(order={"sortOrder": "desc"})
        base = (highest.sortOrder if highest else 0) + 1

        sections: dict[str, str] = {}
        questions: dict[str, str] = {}
        for offset, slug in enumerate(("ANSWER", "BLANK", "META", "FILE")):
            code = f"T{stamp}{slug}"
            section = await db.questionnairesection.create(
                data={
                    "code": code,
                    "title": f"Matrix section {slug}",
                    "sortOrder": base + offset,
                    "isActive": True,
                }
            )
            question = await db.questionnairequestion.create(
                data={
                    "sectionCode": code,
                    "sectionTitle": section.title,
                    "prompt": f"Matrix prompt {slug}",
                    "sortOrder": offset + 1,
                    "sectionId": section.id,
                }
            )
            sections[slug] = section.id
            questions[slug] = question.id
            sections[f"{slug}_code"] = code

        artisan = await db.artisan.create(
            data={
                "name": f"Matrix Artisan {stamp}",
                "place": "Bagru",
                "createdById": author.id,
            }
        )
        interview = await db.questionnaireinterview.create(
            data={
                "title": f"Matrix sitting {stamp}",
                "createdById": author.id,
            }
        )
        await db.questionnaireinterviewartisan.create(
            data={"interviewId": interview.id, "artisanId": artisan.id}
        )

        await db.questionnaireresponse.create(
            data={
                "interviewId": interview.id,
                "questionId": questions["ANSWER"],
                "answerText": "Twelve looms, two of them idle.",
                "answeredById": author.id,
            }
        )
        await db.questionnaireresponse.create(
            data={
                "interviewId": interview.id,
                "questionId": questions["BLANK"],
                "answerText": "   \t  ",
                "answeredById": author.id,
            }
        )

        await db.mediafile.create(
            data={
                "originalFilename": "loom-detail.jpg",
                "mediaType": "IMAGE",
                "mimeType": "image/jpeg",
                "sizeBytes": 2048,
                "bucket": "test-bucket",
                "objectKey": f"matrix/{stamp}/meta.jpg",
                "uploadedById": author.id,
                "questionnaireInterviewId": interview.id,
                "extraMetadata": Json({"questionId": questions["META"]}),
            }
        )
        await db.mediafile.create(
            data={
                "originalFilename": f"{sections['FILE_code']}_Q1_INT_45_20260903.m4a",
                "mediaType": "AUDIO",
                "mimeType": "audio/mp4",
                "sizeBytes": 4096,
                "bucket": "test-bucket",
                "objectKey": f"matrix/{stamp}/clip.m4a",
                "uploadedById": author.id,
                "questionnaireInterviewId": interview.id,
            }
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "auth": {"Authorization": f"Bearer {create_access_token(subject=author.id)}"},
            "artisan_id": artisan.id,
            "interview_id": interview.id,
            "sections": sections,
        }


def _derived_sections(env, *, artisan_id: str | None = None) -> set[str]:
    query = f"?artisanId={artisan_id}" if artisan_id else ""
    response = env["client"].get(f"/api/questionnaire/completion{query}", headers=env["auth"])
    assert response.status_code == 200, response.text
    return {
        cell["sectionId"]
        for cell in response.json()["cells"]
        if cell["artisanId"] == env["artisan_id"] and cell["derived"]
    }



def test_the_unscoped_matrix_does_not_load_answers_or_media(env, monkeypatch):
    from prisma.actions import QuestionnaireInterviewActions

    original = QuestionnaireInterviewActions.find_many
    seen: list[dict] = []

    async def _spy(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `find_many`
        seen.append(kwargs.get("include") or {})
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(QuestionnaireInterviewActions, "find_many", _spy)

    response = env["client"].get("/api/questionnaire/completion", headers=env["auth"])
    assert response.status_code == 200, response.text

    assert seen, "the completion matrix issued no interview scan at all"
    for include in seen:
        assert "responses" not in include, (
            "the unscoped matrix is still loading every answer in the repository to compute a tick"
        )
        assert "media" not in include, (
            "the unscoped matrix is still loading every media row in the repository"
        )
    assert any(include.get("artisans") for include in seen)


def test_the_scoped_matrix_is_deliberately_left_alone(env, monkeypatch):
    from prisma.actions import QuestionnaireInterviewActions

    original = QuestionnaireInterviewActions.find_many
    seen: list[dict] = []

    async def _spy(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `find_many`
        seen.append(kwargs.get("include") or {})
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(QuestionnaireInterviewActions, "find_many", _spy)

    response = env["client"].get(
        f"/api/questionnaire/completion?artisanId={env['artisan_id']}", headers=env["auth"]
    )
    assert response.status_code == 200, response.text
    assert any("responses" in include and "media" in include for include in seen)



def test_a_recorded_answer_still_completes_its_section(env):
    assert env["sections"]["ANSWER"] in _derived_sections(env)


def test_a_blank_answer_still_completes_nothing(env):
    assert env["sections"]["BLANK"] not in _derived_sections(env)


def test_a_photograph_tagged_with_its_question_still_completes_that_section(env):
    assert env["sections"]["META"] in _derived_sections(env)


def test_a_clip_whose_filename_leads_with_the_section_code_still_counts(env):
    assert env["sections"]["FILE"] in _derived_sections(env)


def test_the_scoped_and_unscoped_matrices_agree_cell_for_cell(env):
    assert _derived_sections(env) == _derived_sections(env, artisan_id=env["artisan_id"])


def test_an_artisan_outside_the_selected_workshops_gets_a_matrix_without_rows(env):
    response = env["client"].get(
        "/api/questionnaire/completion",
        params={"artisanId": env["artisan_id"], "workshopIds": f"absent-{uuid.uuid4().hex}"},
        headers=env["auth"],
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["artisans"] == []
    assert body["cells"] == []
    assert body["sections"]


def test_an_artisan_that_does_not_exist_is_still_not_found(env):
    response = env["client"].get(
        "/api/questionnaire/completion",
        params={"artisanId": f"absent-{uuid.uuid4().hex}"},
        headers=env["auth"],
    )
    assert response.status_code == 404, response.text
