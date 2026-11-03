import html
import os
import uuid
import zipfile
from io import BytesIO
from typing import Any

import pytest

from app.core.db import db
from app.core.security import create_access_token
from app.services.questionnaire_xlsx import (
    QUESTION_SET_CONTENTS,
    build_pro_forma,
    build_question_set_workbook,
    build_questionnaire_workbook,
    download_filename,
    parse_questionnaire_workbook,
    question_set_filename,
)

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

ANSWERED_FORM = [
    {
        "code": "CRAFT",
        "title": "About the craft",
        "questions": [
            {
                "id": "q-looms",
                "prompt": "How many looms do you own?",
                "helpText": "Count working looms only",
                "isRequired": True,
                "answers": {"Ramesh Devi": "12"},
                "answerNotes": {"Ramesh Devi": "answered through a translator"},
            },
            {"id": "q-taught", "prompt": "Who taught you?", "isRequired": False, "answers": {}},
        ],
    },
    {
        "code": "MAT",
        "title": "Materials",
        "questions": [
            {
                "id": "q-yarn",
                "prompt": "Where do you buy your yarn?",
                "isRequired": True,
                "answers": {"Ramesh Devi": "Panipat mandi"},
            }
        ],
    },
]


def _every_string_in(payload: bytes) -> str:
    with zipfile.ZipFile(BytesIO(payload)) as archive:
        return "\n".join(
            html.unescape(archive.read(name).decode("utf-8", "replace"))
            for name in archive.namelist()
        )


def _flatten(parsed: Any) -> list[tuple[str, str]]:
    return [
        (section.title, question.prompt)
        for section in parsed.sections
        for question in section.questions
    ]



def _question_set_of(sections: list[dict[str, Any]]) -> bytes:
    return build_question_set_workbook(
        title="Loom study",
        description="Weaving cluster survey",
        sections=[
            {
                "code": section["code"],
                "title": section["title"],
                "questions": [
                    {
                        "id": "",
                        "prompt": q["prompt"],
                        "helpText": q.get("helpText"),
                        "isRequired": q.get("isRequired", False),
                        "answers": {},
                        "answerNotes": {},
                    }
                    for q in section["questions"]
                ],
            }
            for section in sections
        ],
        source_title="Loom study",
        shared_by="A Designer",
        exported_on="2026-08-16",
    )


def test_a_question_set_carries_no_answers_and_no_respondent_names_anywhere_in_the_file():
    payload = _question_set_of(ANSWERED_FORM)
    text = _every_string_in(payload)

    assert "Ramesh Devi" not in text, "a respondent's name reached a file advertised as carrying none"
    assert "Panipat mandi" not in text, "a recorded answer reached the questions-only export"
    assert "answered through a translator" not in text, "an interviewer's note on an answer got out"

    assert "How many looms do you own?" in text
    assert "Count working looms only" in text


def test_a_question_set_round_trips_as_a_brand_new_questionnaire_rather_than_an_edit():
    parsed = parse_questionnaire_workbook(_question_set_of(ANSWERED_FORM), filename="shared.xlsx")

    assert parsed.questionnaireId is None, "the question set must not claim to be an edit of its source"
    assert [q.questionId for s in parsed.sections for q in s.questions] == [None, None, None]
    assert parsed.entryLabels == [], "an answer column with a sitting's name on it came through"
    assert not parsed.hasAnswers
    assert _flatten(parsed) == [
        ("About the craft", "How many looms do you own?"),
        ("About the craft", "Who taught you?"),
        ("Materials", "Where do you buy your yarn?"),
    ]
    required = {q.prompt: q.isRequired for s in parsed.sections for q in s.questions}
    assert required == {
        "How many looms do you own?": True,
        "Who taught you?": False,
        "Where do you buy your yarn?": True,
    }


def test_the_provenance_rows_on_the_details_sheet_do_not_overwrite_the_description():
    parsed = parse_questionnaire_workbook(_question_set_of(ANSWERED_FORM), filename="shared.xlsx")

    assert parsed.title == "Loom study"
    assert parsed.description == "Weaving cluster survey"

    text = _every_string_in(_question_set_of(ANSWERED_FORM))
    assert QUESTION_SET_CONTENTS in text
    assert "A Designer" in text
    assert "2026-08-16" in text


def test_the_two_downloads_have_different_file_names():
    assert download_filename("Loom study").endswith("_Loom-Study_Export.xlsx")
    assert question_set_filename("Loom study").endswith("_Loom-Study_Questions.xlsx")
    assert question_set_filename(None).endswith("_Questions.xlsx")
    assert download_filename("Loom study") != question_set_filename("Loom study")


def test_the_pro_forma_still_parses_after_the_details_sheet_learned_extra_rows():
    from openpyxl import load_workbook

    book = load_workbook(BytesIO(build_pro_forma()))
    details = book["Details"]
    assert [details.cell(row=row, column=1).value for row in range(3, 7)] == [
        "Questionnaire title",
        "Description",
        "Questionnaire ID",
        "Version",
    ]
    assert details.cell(row=5, column=2).value in (None, ""), (
        "the blank pro-forma must carry no Questionnaire ID, or every upload of it would read as an "
        "edit of some other questionnaire"
    )



@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def world():
    if not _LOCAL:
        pytest.skip("needs a local database")
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    people: dict[str, Any] = {}
    await db.connect()
    try:
        for slug, role, name in (
            ("owner", "DESIGNER", "Questionnaire Owner"),
            ("receiver", "DESIGNER", "Receiving Designer"),
            ("admin", "ADMIN", "Interchange Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"qi-{slug}-{stamp}@example.org",
                    "name": name,
                    "role": role,
                }
            )
    finally:
        await db.disconnect()
    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str = "owner") -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _upload(client: Any, world: dict[str, Any], payload: bytes, slug: str = "owner", name: str = "form.xlsx") -> Any:
    return client.post(
        "/api/questionnaires/upload",
        files={"file": (name, payload, XLSX_MIME)},
        headers=_headers(world, slug),
    )


def _questions(form: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {q["prompt"]: q for section in form["sections"] for q in section["questions"]}


def _hand_typed_workbook(title: str, *, with_answers: bool) -> bytes:
    answers = {"Ramesh Devi": "12"} if with_answers else {}
    return build_questionnaire_workbook(
        title=title,
        description=None,
        questionnaire_id="",
        version=1,
        sections=[
            {
                "code": "CRAFT",
                "title": "About the craft",
                "questions": [
                    {"id": "", "prompt": "How many looms do you own?", "answers": answers},
                    {"id": "", "prompt": "Who taught you?", "answers": {}},
                ],
            }
        ],
        entry_labels=["Ramesh Devi"] if with_answers else [],
    )



@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_the_question_set_is_open_to_any_designer_while_the_full_workbook_is_not(client, world):
    created = _upload(client, world, _hand_typed_workbook(f"Shared {world['stamp']}", with_answers=True))
    assert created.status_code == 201, created.text
    questionnaire_id = created.json()["questionnaire"]["id"]

    refused = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world, "receiver")
    )
    assert refused.status_code == 403, refused.text
    assert "question set" in refused.json()["detail"]

    allowed = client.get(
        f"/api/questionnaires/{questionnaire_id}/question-set.xlsx",
        headers=_headers(world, "receiver"),
    )
    assert allowed.status_code == 200, allowed.text
    assert allowed.headers["content-type"].startswith(XLSX_MIME)
    assert "_Questions.xlsx" in allowed.headers["content-disposition"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_the_question_set_served_by_the_api_carries_no_recorded_answer(client, world):
    created = _upload(client, world, _hand_typed_workbook(f"Served {world['stamp']}", with_answers=True))
    assert created.status_code == 201, created.text
    form = created.json()["questionnaire"]
    assert len(form["entries"]) == 1, "the fixture must actually have a sitting, or this proves nothing"

    served = client.get(
        f"/api/questionnaires/{form['id']}/question-set.xlsx", headers=_headers(world, "receiver")
    )
    assert served.status_code == 200, served.text
    text = _every_string_in(served.content)
    assert "Ramesh Devi" not in text
    assert "How many looms do you own?" in text


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_retired_question_is_not_sent_on_in_a_question_set(client, world):
    created = _upload(client, world, _hand_typed_workbook(f"Retired {world['stamp']}", with_answers=True))
    form = created.json()["questionnaire"]
    looms = _questions(form)["How many looms do you own?"]
    assert looms["hasAnswers"] is True

    superseded = client.patch(
        f"/api/questionnaires/{form['id']}/questions/{looms['id']}",
        json={"prompt": "How many weavers work with you?"},
        headers=_headers(world),
    )
    assert superseded.status_code == 200, superseded.text
    assert superseded.json()["action"] == "superseded"

    served = client.get(
        f"/api/questionnaires/{form['id']}/question-set.xlsx", headers=_headers(world)
    )
    text = _every_string_in(served.content)
    assert "How many weavers work with you?" in text, "the wording being asked today must travel"
    assert "How many looms do you own?" not in text, (
        "the retired wording was sent on; it is kept only because answers hang off it and it is not "
        "part of the instrument any more"
    )



@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_importing_a_question_set_creates_an_empty_questionnaire(client, world):
    sent = _upload(client, world, _hand_typed_workbook(f"To send {world['stamp']}", with_answers=True))
    source_id = sent.json()["questionnaire"]["id"]

    question_set = client.get(
        f"/api/questionnaires/{source_id}/question-set.xlsx", headers=_headers(world, "receiver")
    )
    assert question_set.status_code == 200, question_set.text

    received = _upload(client, world, question_set.content, slug="receiver", name="from-a-colleague.xlsx")
    assert received.status_code == 201, received.text
    body = received.json()
    form = body["questionnaire"]

    assert form["questionCount"] == 2
    assert _questions(form).keys() == {"How many looms do you own?", "Who taught you?"}
    assert form["entries"] == [], "the sender's sitting arrived in the receiver's questionnaire"
    assert body["report"]["entriesCreated"] == 0
    assert body["report"]["answersImported"] == 0
    assert body["report"]["answersSkipped"] == 0
    assert body["report"]["provenance"] is None
    assert form["ownerId"] == world["people"]["receiver"].id

    original = client.get(f"/api/questionnaires/{source_id}", headers=_headers(world)).json()
    assert len(original["entries"]) == 1


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_uploading_someone_elses_full_workbook_never_records_their_answers_as_yours(
    client, world
):
    owner_upload = _upload(
        client, world, _hand_typed_workbook(f"Owned {world['stamp']}", with_answers=True)
    )
    assert owner_upload.status_code == 201, owner_upload.text
    owners_form = owner_upload.json()["questionnaire"]
    assert len(owners_form["entries"]) == 1
    assert owners_form["entries"][0]["respondentName"] == "Ramesh Devi"

    full = client.get(f"/api/questionnaires/{owners_form['id']}/xlsx", headers=_headers(world))
    assert full.status_code == 200, full.text
    assert "Ramesh Devi" in _every_string_in(full.content), (
        "the fixture must genuinely carry the respondent, or the theft this test is about cannot "
        "happen and the assertions below are vacuous"
    )

    stolen = _upload(client, world, full.content, slug="receiver", name="someone-elses.xlsx")
    assert stolen.status_code == 201, stolen.text
    body = stolen.json()
    form = body["questionnaire"]

    assert form["entries"] == [], (
        "the sender's sitting was recorded against the uploader's brand-new questionnaire — this is "
        "the defect: somebody else's respondent, answers and interview, now attributed to whoever "
        "opened the file"
    )
    assert body["report"]["entriesCreated"] == 0
    assert body["report"]["answersImported"] == 0
    assert body["report"]["answersSkipped"] >= 1

    provenance = body["report"]["provenance"]
    assert provenance is not None, "the uploader must be told; silence is what made this invisible"
    assert provenance["action"] == "answersNotImported"
    assert provenance["sourceQuestionnaireId"] == owners_form["id"]
    assert "already recorded" in provenance["reason"]
    assert any(p["reason"] == provenance["reason"] for p in body["report"]["problems"])

    assert _questions(form).keys() == {"How many looms do you own?", "Who taught you?"}

    still = client.get(f"/api/questionnaires/{owners_form['id']}", headers=_headers(world)).json()
    assert len(still["entries"]) == 1
    assert still["entries"][0]["createdById"] == world["people"]["owner"].id


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_answers_typed_into_a_blank_pro_forma_are_still_imported_and_still_the_uploaders(
    client, world
):
    typed = _upload(
        client,
        world,
        _hand_typed_workbook(f"Typed up {world['stamp']}", with_answers=True),
        slug="receiver",
        name="paper-interviews.xlsx",
    )
    assert typed.status_code == 201, typed.text
    body = typed.json()
    form = body["questionnaire"]

    assert len(form["entries"]) == 1
    entry = form["entries"][0]
    assert entry["source"] == "UPLOAD"
    assert entry["respondentName"] == "Ramesh Devi"
    assert entry["createdById"] == world["people"]["receiver"].id
    assert body["report"]["entriesCreated"] == 1
    assert body["report"]["answersImported"] == 1
    assert body["report"]["answersSkipped"] == 0

    assert entry["notes"] and "uploaded spreadsheet" in entry["notes"]
    assert "paper-interviews.xlsx" in entry["notes"]

    provenance = body["report"]["provenance"]
    assert provenance["action"] == "answersImported"
    assert "attributed to you" in provenance["reason"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_workbook_whose_details_sheet_was_deleted_still_gives_its_answers_away_to_nobody(
    client, world
):
    copied_sheet = build_questionnaire_workbook(
        title=f"Copied sheet {world['stamp']}",
        description=None,
        questionnaire_id="",
        version=1,
        sections=[
            {
                "code": "CRAFT",
                "title": "About the craft",
                "questions": [
                    {
                        "id": "ckzz0000somebodyelses0001",
                        "prompt": "How many looms do you own?",
                        "answers": {"Ramesh Devi": "12"},
                    }
                ],
            }
        ],
        entry_labels=["Ramesh Devi"],
    )
    received = _upload(client, world, copied_sheet, slug="receiver", name="copied.xlsx")
    assert received.status_code == 201, received.text
    body = received.json()

    assert body["questionnaire"]["entries"] == []
    assert body["report"]["answersSkipped"] == 1
    provenance = body["report"]["provenance"]
    assert provenance["action"] == "answersNotImported"
    assert provenance["sourceQuestionnaireId"] is None
    assert "Question ID column" in provenance["reason"]



def _supersede_columns_generated() -> bool:
    try:
        from prisma.models import QuestionnaireQuestion
    except (ImportError, RuntimeError):  # pragma: no cover - a client that was never generated
        return False
    fields = getattr(QuestionnaireQuestion, "model_fields", None)
    if fields is None:
        fields = getattr(QuestionnaireQuestion, "__fields__", {})
    return "supersededById" in fields and "retiredAt" in fields


def test_the_supersede_columns_are_in_the_schema_and_not_only_in_the_migration():
    assert _supersede_columns_generated(), (
        "QuestionnaireQuestion.retiredAt / .supersededById are missing from the generated Prisma "
        "client. The migration 20260816200000_questionnaire_question_supersede adds them to the "
        "database and app/api/routes/questionnaire.py reads and writes them, so without the model "
        "block in schema.prisma three endpoints answer a bare 500: rewording an answered question, "
        "deleting one, and deleting a section that holds one. Add the two fields to model "
        "QuestionnaireQuestion and run `python -m prisma generate`."
    )



_DEFAULT_QUESTIONS = {
    "How many looms do you own?": "12",
    "Who taught you?": None,
    "What does a kilo of yarn cost?": "Rs 240",
    "Where do you buy your yarn?": "Panipat",
}


@pytest.fixture(scope="module")
async def default_questionnaire(world):
    if not _LOCAL:
        pytest.skip("needs a local database")
    from prisma import Prisma

    client = world["client"]
    stamp = world["stamp"]
    headers = _headers(world, "admin")

    section = client.post(
        "/api/questionnaire/sections",
        json={"code": f"ZQI{stamp[:5].upper()}", "title": f"Interchange rule {stamp}"},
        headers=headers,
    )
    assert section.status_code == 201, section.text
    section_id = section.json()["id"]

    made: dict[str, str] = {}
    for prompt in _DEFAULT_QUESTIONS:
        question = client.post(
            "/api/questionnaire/questions",
            json={"sectionId": section_id, "prompt": prompt},
            headers=headers,
        )
        assert question.status_code == 201, question.text
        made[prompt] = question.json()["id"]

    own = Prisma()
    await own.connect()
    try:
        interview = await own.questionnaireinterview.create(
            data={
                "title": f"Interchange interview {stamp}",
                "createdById": world["people"]["admin"].id,
            }
        )
        for prompt, answer in _DEFAULT_QUESTIONS.items():
            if answer is None:
                continue
            await own.questionnaireresponse.create(
                data={
                    "interviewId": interview.id,
                    "questionId": made[prompt],
                    "answerText": answer,
                    "answeredById": world["people"]["admin"].id,
                }
            )
    finally:
        await own.disconnect()

    yield {"sectionId": section_id, "questions": made, "interviewId": interview.id}

    cleanup = Prisma()
    await cleanup.connect()
    try:
        await cleanup.questionnairesection.update(
            where={"id": section_id}, data={"isActive": False}
        )
        await cleanup.questionnairequestion.update_many(
            where={"sectionId": section_id}, data={"isActive": False}
        )
    finally:
        await cleanup.disconnect()


def _global_question(client: Any, world: dict[str, Any], question_id: str) -> dict[str, Any] | None:
    sections = client.get(
        "/api/questionnaire/sections?activeOnly=false", headers=_headers(world, "admin")
    )
    assert sections.status_code == 200, sections.text
    for section in sections.json():
        for question in section["questions"]:
            if question["id"] == question_id:
                return question
    return None


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_rewording_an_answered_default_question_supersedes_it_instead_of_overwriting_it(
    client, world, default_questionnaire
):
    original_id = default_questionnaire["questions"]["How many looms do you own?"]

    reworded = client.patch(
        f"/api/questionnaire/questions/{original_id}",
        json={"prompt": "How many weavers work with you?"},
        headers=_headers(world, "admin"),
    )
    assert reworded.status_code == 200, reworded.text
    body = reworded.json()

    assert body["action"] == "superseded", (
        "the prompt was written straight over the old one — every answer already recorded now reads "
        "as an answer to a question nobody was ever asked"
    )
    assert body["id"] != original_id, "the replacement must be a NEW row, not the answered one"
    assert body["prompt"] == "How many weavers work with you?"
    assert body["supersededQuestionId"] == original_id
    assert body["isActive"] is True
    assert body["sectionId"] == default_questionnaire["sectionId"]

    retired = _global_question(client, world, original_id)
    assert retired is not None, "the original was deleted; its nineteen answers would be orphaned"
    assert retired["prompt"] == "How many looms do you own?"
    assert retired["isActive"] is False
    assert retired["retiredAt"] is not None
    assert retired["supersededById"] == body["id"], (
        "without the link this is indistinguishable from somebody deleting one question and adding "
        "another, which loses the fact that a correction happened at all"
    )

    from prisma import Prisma

    own = Prisma()
    await own.connect()
    try:
        rows = await own.questionnaireresponse.find_many(
            where={"interviewId": default_questionnaire["interviewId"]}
        )
        by_question = {r.questionId: r.answerText for r in rows}
        assert by_question.get(original_id) == "12"
        assert body["id"] not in by_question, (
            "the answer followed the new wording; twelve looms just became twelve weavers"
        )
    finally:
        await own.disconnect()


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_an_unanswered_default_question_is_reworded_in_place_with_no_ceremony(
    client, world, default_questionnaire
):
    question_id = default_questionnaire["questions"]["Who taught you?"]

    reworded = client.patch(
        f"/api/questionnaire/questions/{question_id}",
        json={"prompt": "Who taught you this craft?"},
        headers=_headers(world, "admin"),
    )
    assert reworded.status_code == 200, reworded.text
    body = reworded.json()

    assert body["action"] == "updated"
    assert body["id"] == question_id, "an unanswered question must keep its identity"
    assert body["prompt"] == "Who taught you this craft?"
    assert body["isActive"] is True
    assert body["supersededById"] is None
    assert body["retiredAt"] is None


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_default_question_that_was_already_superseded_refuses_a_second_reword(
    client, world, default_questionnaire
):
    original_id = default_questionnaire["questions"]["Where do you buy your yarn?"]

    first = client.patch(
        f"/api/questionnaire/questions/{original_id}",
        json={"prompt": "Where do you buy your cotton?"},
        headers=_headers(world, "admin"),
    )
    assert first.status_code == 200, first.text
    assert first.json()["action"] == "superseded"
    replacement_id = first.json()["id"]

    refused = client.patch(
        f"/api/questionnaire/questions/{original_id}",
        json={"prompt": "Where do you buy your silk?"},
        headers=_headers(world, "admin"),
    )
    assert refused.status_code == 409, refused.text
    assert "replaced by a newer one" in refused.json()["detail"]

    unchanged = _global_question(client, world, original_id)
    assert unchanged["prompt"] == "Where do you buy your yarn?"
    assert unchanged["supersededById"] == replacement_id, "the existing link survived the refusal"

    onward = client.patch(
        f"/api/questionnaire/questions/{replacement_id}",
        json={"prompt": "Where do you buy your silk?"},
        headers=_headers(world, "admin"),
    )
    assert onward.status_code == 200, onward.text
    assert onward.json()["action"] == "updated"
    assert onward.json()["id"] == replacement_id


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_help_text_and_position_stay_editable_on_an_answered_default_question(
    client, world, default_questionnaire
):
    answered_id = default_questionnaire["questions"]["What does a kilo of yarn cost?"]
    before = _global_question(client, world, answered_id)
    assert before["isActive"] is True

    moved = client.patch(
        f"/api/questionnaire/questions/{answered_id}",
        json={"sortOrder": (before["sortOrder"] or 1) + 5},
        headers=_headers(world, "admin"),
    )
    assert moved.status_code == 200, moved.text
    assert moved.json()["action"] == "updated", "moving a question is not rewording it"
    assert moved.json()["id"] == answered_id, "a reposition must not fork the question"
    assert moved.json()["sortOrder"] == (before["sortOrder"] or 1) + 5
    assert moved.json()["prompt"] == "What does a kilo of yarn cost?"
    assert moved.json()["isActive"] is True
    assert moved.json()["supersededById"] is None


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_removing_an_answered_default_question_stamps_that_it_carried_evidence(
    client, world, default_questionnaire
):
    unanswered_id = default_questionnaire["questions"]["Who taught you?"]
    removed = client.delete(
        f"/api/questionnaire/questions/{unanswered_id}", headers=_headers(world, "admin")
    )
    assert removed.status_code == 204, removed.text
    gone = _global_question(client, world, unanswered_id)
    assert gone["isActive"] is False
    assert gone["retiredAt"] is None, (
        "nobody answered this one, so it was switched off rather than retired — a stamp here would "
        "make the column mean 'inactive', which the isActive column already means"
    )

    answered_id = default_questionnaire["questions"]["What does a kilo of yarn cost?"]
    retired = client.delete(
        f"/api/questionnaire/questions/{answered_id}", headers=_headers(world, "admin")
    )
    assert retired.status_code == 204, retired.text
    kept = _global_question(client, world, answered_id)
    assert kept is not None, "an answered question must never actually be deleted"
    assert kept["isActive"] is False
    assert kept["retiredAt"] is not None, (
        "this question carries a recorded answer, so it was retired rather than merely switched "
        "off, and the record has to say which"
    )
    assert kept["supersededById"] is None, "nothing replaced it; it was removed, not reworded"

    superseded_id = default_questionnaire["questions"]["How many looms do you own?"]
    already = _global_question(client, world, superseded_id)
    assert already["retiredAt"] is not None
