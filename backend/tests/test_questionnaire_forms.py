import os
import uuid
import zipfile
from io import BytesIO
from typing import Any

import pytest
from openpyxl import Workbook, load_workbook

from app.core.db import db
from app.core.security import create_access_token
from app.services.questionnaire_xlsx import (
    QuestionnaireXlsxError,
    build_pro_forma,
    build_questionnaire_workbook,
    derive_section_code,
    parse_questionnaire_workbook,
)

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

FORM = [
    {
        "code": "CRAFT",
        "title": "About the craft",
        "questions": [
            {
                "id": "q-looms",
                "prompt": "How many looms do you own?",
                "helpText": "Count working looms only",
                "isRequired": True,
                "answers": {"Ramesh": "12"},
                "answerNotes": {"Ramesh": "answered through a translator"},
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
                "answers": {"Ramesh": "Panipat mandi"},
            }
        ],
    },
]


def _flatten(parsed: Any) -> list[tuple[str, str, str]]:
    return [
        (section.title, question.prompt, next(iter(question.answers.values()), ""))
        for section in parsed.sections
        for question in section.questions
    ]


def _sheet(rows: list[list[Any]], *, title: str = "Questionnaire") -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = title
    for row in rows:
        ws.append(row)
    buffer = BytesIO()
    wb.save(buffer)
    return buffer.getvalue()



def test_the_pro_forma_is_a_workbook_excel_will_open_without_offering_to_repair_it():
    import xml.etree.ElementTree as ET

    payload = build_pro_forma()
    with zipfile.ZipFile(BytesIO(payload)) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert "xl/workbook.xml" in names
        for name in names:
            if name.endswith((".xml", ".rels")):
                ET.fromstring(archive.read(name))


def test_the_pro_forma_carries_no_example_questions_to_import_by_accident():
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(build_pro_forma(), filename="questionnaire-pro-forma.xlsx")
    assert "no questions under it" in str(excinfo.value)

    book = load_workbook(BytesIO(build_pro_forma()))
    assert book["Questionnaire"].max_row == 1


def test_a_file_that_is_not_a_workbook_is_refused_with_a_sentence_a_designer_can_act_on():
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(b"this is a .csv, not a workbook", filename="answers.xls")
    assert "Save As" in str(excinfo.value)



def test_generate_then_parse_returns_exactly_the_same_questions():
    payload = build_questionnaire_workbook(
        title="Panipat loom study",
        description="Baseline for the cluster",
        questionnaire_id="qn-1",
        version=1,
        sections=FORM,
        entry_labels=["Ramesh"],
    )
    parsed = parse_questionnaire_workbook(payload, filename="panipat.xlsx")

    assert parsed.title == "Panipat loom study"
    assert parsed.description == "Baseline for the cluster"
    assert parsed.questionnaireId == "qn-1"
    assert [s.code for s in parsed.sections] == ["CRAFT", "MAT"]
    assert _flatten(parsed) == [
        ("About the craft", "How many looms do you own?", "12"),
        ("About the craft", "Who taught you?", ""),
        ("Materials", "Where do you buy your yarn?", "Panipat mandi"),
    ]
    assert [q.questionId for s in parsed.sections for q in s.questions] == [
        "q-looms",
        "q-taught",
        "q-yarn",
    ]
    first = parsed.sections[0].questions[0]
    assert first.isRequired is True
    assert first.helpText == "Count working looms only"
    assert first.answerNotes == {"Ramesh": "answered through a translator"}


def test_the_round_trip_survives_being_run_twice():
    once = parse_questionnaire_workbook(
        build_questionnaire_workbook(
            title="Loom study",
            description=None,
            questionnaire_id="qn-1",
            version=1,
            sections=FORM,
            entry_labels=["Ramesh"],
        ),
        filename="a.xlsx",
    )
    rewritten = build_questionnaire_workbook(
        title=once.title or "",
        description=once.description,
        questionnaire_id=once.questionnaireId or "",
        version=2,
        sections=[
            {
                "code": section.code,
                "title": section.title,
                "questions": [
                    {
                        "id": q.questionId,
                        "prompt": q.prompt,
                        "helpText": q.helpText,
                        "isRequired": q.isRequired,
                        "answers": q.answers,
                        "answerNotes": q.answerNotes,
                    }
                    for q in section.questions
                ],
            }
            for section in once.sections
        ],
        entry_labels=once.entryLabels,
    )
    twice = parse_questionnaire_workbook(rewritten, filename="a.xlsx")
    assert _flatten(twice) == _flatten(once)
    assert [q.questionId for s in twice.sections for q in s.questions] == [
        q.questionId for s in once.sections for q in s.questions
    ]


def test_several_sittings_round_trip_as_several_sittings():
    sections = [
        {
            "code": "A",
            "title": "Section A",
            "questions": [
                {
                    "id": "q1",
                    "prompt": "How many looms?",
                    "answers": {"Ramesh": "12", "Sita": "4"},
                    "answerNotes": {"Sita": "shared with her sister"},
                }
            ],
        }
    ]
    parsed = parse_questionnaire_workbook(
        build_questionnaire_workbook(
            title="T",
            description=None,
            questionnaire_id="q",
            version=1,
            sections=sections,
            entry_labels=["Ramesh", "Sita"],
        ),
        filename="t.xlsx",
    )
    question = parsed.sections[0].questions[0]
    assert question.answers == {"Ramesh": "12", "Sita": "4"}
    assert question.answerNotes == {"Sita": "shared with her sister"}
    assert parsed.entryLabels == ["Ramesh", "Sita"]



def test_columns_may_be_renamed_reordered_and_pushed_down_the_sheet():
    payload = _sheet(
        [
            ["Kullu shawl questionnaire — draft 3"],
            [],
            ["  ANSWER : ", "section_name", "Questions", "Mandatory?"],
            ["", "About the craft", "", ""],
            ["12", "", "How many looms do you own?", "yes"],
            ["", "", "Who taught you?", "N"],
        ],
        title="Final v3",
    )
    parsed = parse_questionnaire_workbook(payload, filename="kullu.xlsx")
    assert parsed.sheet == "Final v3"
    assert _flatten(parsed) == [
        ("About the craft", "How many looms do you own?", "12"),
        ("About the craft", "Who taught you?", ""),
    ]
    assert parsed.sections[0].questions[0].isRequired is True
    assert parsed.sections[0].questions[1].isRequired is False


def test_a_section_named_once_carries_down_the_rows_beneath_it():
    parsed = parse_questionnaire_workbook(
        _sheet(
            [
                ["Section Title", "Question"],
                ["Materials", "Where do you buy yarn?"],
                ["", "What dye do you use?"],
                ["Materials", "Who supplies your mordant?"],
                ["Tools", "How old is your loom?"],
            ]
        ),
        filename="x.xlsx",
    )
    assert [(s.title, len(s.questions)) for s in parsed.sections] == [("Materials", 3), ("Tools", 1)]


def test_a_question_before_any_section_is_kept_and_reported_never_dropped():
    parsed = parse_questionnaire_workbook(
        _sheet([["Question"], ["How many looms do you own?"], ["Who taught you?"]]),
        filename="x.xlsx",
    )
    assert len(parsed.sections) == 1
    assert len(parsed.sections[0].questions) == 2
    warnings = [p for p in parsed.problems if p.severity == "warning"]
    assert warnings and warnings[0].row == 2
    assert "General" in warnings[0].reason


def test_an_unreadable_required_value_is_reported_with_its_excel_row_number():
    parsed = parse_questionnaire_workbook(
        _sheet(
            [
                ["Section Title", "Question", "Required"],
                ["Craft", "How many looms?", "maybe"],
            ]
        ),
        filename="x.xlsx",
    )
    assert len(parsed.sections[0].questions) == 1
    problem = parsed.problems[0]
    assert problem.row == 2
    assert "maybe" in problem.reason
    assert problem.severity == "warning"


def test_a_formula_with_no_cached_result_is_reported_as_a_formula_not_as_a_blank_row():
    wb = Workbook()
    ws = wb.active
    ws.title = "Questionnaire"
    ws.append(["Section Title", "Question"])
    ws.append(["Craft", "How many looms?"])
    ws["A3"] = "=A2"
    ws["B3"] = '=B2&" (2024)"'
    buffer = BytesIO()
    wb.save(buffer)

    parsed = parse_questionnaire_workbook(buffer.getvalue(), filename="x.xlsx")
    assert len(parsed.sections[0].questions) == 1
    errors = [p for p in parsed.problems if p.severity == "error"]
    assert errors, [p.payload() for p in parsed.problems]
    assert errors[0].row == 3
    assert "formula" in errors[0].reason


def test_a_workbook_with_no_question_column_is_refused_rather_than_imported_empty():
    with pytest.raises(QuestionnaireXlsxError) as excinfo:
        parse_questionnaire_workbook(
            _sheet([["Name", "Village", "Phone"], ["Ramesh", "Panipat", "0"]]), filename="x.xlsx"
        )
    assert "'Question' column" in str(excinfo.value)


def test_a_section_code_is_derived_from_the_title_so_inserting_a_section_does_not_renumber():
    taken: set[str] = set()
    assert derive_section_code("About the craft", taken) == "ABOUT_THE_CRAFT"
    assert derive_section_code("About the craft", taken) == "ABOUT_THE_CRAFT_2"
    assert derive_section_code("", taken) == "SECTION"


pytestmark_db = [
    pytest.mark.skipif(
        not _LOCAL, reason="needs a LOCAL database; refuses to run against a remote DATABASE_URL"
    ),
    pytest.mark.anyio,
]


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
            ("designer", "DESIGNER", "Custom Questionnaire Designer"),
            ("colleague", "DESIGNER", "Another Designer"),
            ("stranger", "DESIGNER", "Unrelated Designer"),
            ("admin", "ADMIN", "Questionnaire Admin"),
        ):
            people[slug] = await db.user.create(
                data={
                    "email": f"cq-{slug}-{stamp}@example.org",
                    "name": name,
                    "role": role,
                }
            )
        for slug in ("designer", "colleague", "stranger"):
            await db.designerroster.create(
                data={
                    "email": f"cq-{slug}-{stamp}@example.org",
                    "fullName": people[slug].name,
                    "institution": "Directorate of Handicrafts",
                    "isActive": True,
                    "addedById": people["admin"].id,
                }
            )
    finally:
        await db.disconnect()
    with TestClient(app) as client:
        yield {"client": client, "people": people, "stamp": stamp}


@pytest.fixture
def client(world):
    return world["client"]


def _headers(world: dict[str, Any], slug: str = "designer") -> dict[str, str]:
    return {"Authorization": f"Bearer {create_access_token(subject=world['people'][slug].id)}"}


def _open_workshop(client: Any, world: dict[str, Any], body: dict[str, Any], owner: str = "designer") -> str:
    response = client.post("/api/design-workshops", json=body, headers=_headers(world, "admin"))
    assert response.status_code == 201, response.text
    workshop_id = response.json()["id"]
    granted = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"][owner].id]},
        headers=_headers(world, "admin"),
    )
    assert granted.status_code == 200, granted.text
    return workshop_id


def _upload(client: Any, world: dict[str, Any], payload: bytes, name: str = "form.xlsx") -> Any:
    return client.post(
        "/api/questionnaires/upload",
        files={"file": (name, payload, XLSX_MIME)},
        headers=_headers(world),
    )


def _questions(form: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {q["prompt"]: q for section in form["sections"] for q in section["questions"]}


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_uploading_a_filled_in_pro_forma_creates_a_questionnaire_with_its_answers(
    client, world
):
    payload = build_questionnaire_workbook(
        title=f"Loom study {world['stamp']}",
        description=None,
        questionnaire_id="",
        version=1,
        sections=[
            {
                "code": "CRAFT",
                "title": "About the craft",
                "questions": [
                    {"id": "", "prompt": "How many looms do you own?", "answers": {"Ramesh": "12"}},
                    {"id": "", "prompt": "Who taught you?", "answers": {}},
                ],
            }
        ],
        entry_labels=["Ramesh"],
    )
    response = _upload(client, world, payload)
    assert response.status_code == 201, response.text
    body = response.json()
    form = body["questionnaire"]

    assert form["questionCount"] == 2
    assert body["report"]["created"] == 2
    assert body["report"]["problems"] == []
    assert len(form["entries"]) == 1
    assert form["entries"][0]["source"] == "UPLOAD"
    assert form["entries"][0]["respondentName"] == "Ramesh"
    answers = {a["questionId"]: a["answerText"] for a in form["entries"][0]["answers"]}
    looms = _questions(form)["How many looms do you own?"]
    assert answers[looms["id"]] == "12"
    assert looms["hasAnswers"] is True
    assert _questions(form)["Who taught you?"]["hasAnswers"] is False


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_rewording_an_answered_question_supersedes_it_and_the_answer_keeps_its_words(
    client, world
):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Supersede {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "CRAFT",
                    "title": "About the craft",
                    "questions": [
                        {"id": "", "prompt": "How many looms do you own?", "answers": {"R": "12"}}
                    ],
                }
            ],
            entry_labels=["R"],
        ),
    ).json()
    questionnaire_id = created["questionnaire"]["id"]
    original = _questions(created["questionnaire"])["How many looms do you own?"]

    response = client.patch(
        f"/api/questionnaires/{questionnaire_id}/questions/{original['id']}",
        json={"prompt": "How many weavers work with you?"},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["action"] == "superseded"
    assert body["questionId"] == original["id"]
    replacement_id = body["replacementId"]
    assert replacement_id != original["id"]

    form = body["questionnaire"]
    by_id = {q["id"]: q for section in form["sections"] for q in section["questions"]}
    assert by_id[original["id"]]["prompt"] == "How many looms do you own?"
    assert by_id[original["id"]]["isActive"] is False
    assert by_id[original["id"]]["retiredAt"] is not None
    assert by_id[original["id"]]["supersededById"] == replacement_id
    assert by_id[replacement_id]["prompt"] == "How many weavers work with you?"
    assert by_id[replacement_id]["isActive"] is True
    assert by_id[replacement_id]["hasAnswers"] is False
    answers = {a["questionId"]: a["answerText"] for a in form["entries"][0]["answers"]}
    assert answers[original["id"]] == "12"
    assert replacement_id not in answers
    assert form["version"] == created["questionnaire"]["version"] + 1


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_an_unanswered_question_is_reworded_in_place_with_no_ceremony(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Draft {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Typo?"}]}],
        ),
    ).json()
    questionnaire_id = created["questionnaire"]["id"]
    question = _questions(created["questionnaire"])["Typo?"]

    body = client.patch(
        f"/api/questionnaires/{questionnaire_id}/questions/{question['id']}",
        json={"prompt": "Fixed."},
        headers=_headers(world),
    ).json()
    assert body["action"] == "updated"
    form = body["questionnaire"]
    assert [q["prompt"] for s in form["sections"] for q in s["questions"]] == ["Fixed."]
    assert form["version"] == created["questionnaire"]["version"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_deleting_an_answered_question_retires_it_and_keeps_the_answer(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Retire {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "A",
                    "title": "A",
                    "questions": [
                        {"id": "", "prompt": "Answered question", "answers": {"R": "yes"}},
                        {"id": "", "prompt": "Unanswered question"},
                    ],
                }
            ],
            entry_labels=["R"],
        ),
    ).json()
    questionnaire_id = created["questionnaire"]["id"]
    answered = _questions(created["questionnaire"])["Answered question"]
    unanswered = _questions(created["questionnaire"])["Unanswered question"]

    body = client.delete(
        f"/api/questionnaires/{questionnaire_id}/questions/{answered['id']}",
        headers=_headers(world, "admin"),
    ).json()
    assert body["action"] == "retired"
    retired = {q["id"]: q for s in body["questionnaire"]["sections"] for q in s["questions"]}
    assert retired[answered["id"]]["isActive"] is False
    answers = {a["questionId"] for a in body["questionnaire"]["entries"][0]["answers"]}
    assert answered["id"] in answers

    body = client.delete(
        f"/api/questionnaires/{questionnaire_id}/questions/{unanswered['id']}",
        headers=_headers(world, "admin"),
    ).json()
    assert body["action"] == "deleted"
    remaining = {q["id"] for s in body["questionnaire"]["sections"] for q in s["questions"]}
    assert unanswered["id"] not in remaining


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_retired_question_cannot_collect_new_answers(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"NoNewAnswers {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "A",
                    "title": "A",
                    "questions": [{"id": "", "prompt": "Original wording", "answers": {"R": "12"}}],
                }
            ],
            entry_labels=["R"],
        ),
    ).json()
    questionnaire_id = created["questionnaire"]["id"]
    original = _questions(created["questionnaire"])["Original wording"]
    client.patch(
        f"/api/questionnaires/{questionnaire_id}/questions/{original['id']}",
        json={"prompt": "New wording"},
        headers=_headers(world),
    )
    entry = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"respondentName": "Sita"},
        headers=_headers(world),
    ).json()
    response = client.put(
        f"/api/questionnaires/{questionnaire_id}/entries/{entry['id']}/answers",
        json={"answers": [{"questionId": original["id"], "answerText": "4"}]},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    saved = response.json()["saved"]
    assert saved["created"] == 0
    assert [(r["questionId"], r["code"]) for r in saved["rejected"]] == [
        (original["id"], "QUESTION_RETIRED")
    ]
    assert "retired" in saved["rejected"][0]["message"]
    assert response.json()["answers"] == []


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_re_uploading_an_edited_workbook_supersedes_answered_and_retires_removed(
    client, world
):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Reupload {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "A",
                    "title": "Section A",
                    "questions": [
                        {"id": "", "prompt": "Untouched question", "answers": {"R": "a"}},
                        {"id": "", "prompt": "Answered and reworded", "answers": {"R": "12"}},
                        {"id": "", "prompt": "Unanswered and reworded"},
                        {"id": "", "prompt": "Removed but answered", "answers": {"R": "c"}},
                    ],
                }
            ],
            entry_labels=["R"],
        ),
    ).json()
    questionnaire_id = created["questionnaire"]["id"]
    before = _questions(created["questionnaire"])

    download = client.get(
        f"/api/questionnaires/{questionnaire_id}/xlsx", headers=_headers(world)
    )
    assert download.status_code == 200, download.text
    assert download.headers["content-type"].startswith(XLSX_MIME)
    roundtripped = parse_questionnaire_workbook(download.content, filename="edit.xlsx")
    assert roundtripped.questionnaireId == questionnaire_id

    edited = []
    for section in roundtripped.sections:
        questions = []
        for question in section.questions:
            if question.prompt == "Removed but answered":
                continue
            prompt = question.prompt
            if prompt in ("Answered and reworded", "Unanswered and reworded"):
                prompt = prompt + " (v2)"
            questions.append(
                {
                    "id": question.questionId,
                    "prompt": prompt,
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "answers": question.answers,
                    "answerNotes": question.answerNotes,
                }
            )
        questions.append({"id": "", "prompt": "Brand new question"})
        edited.append({"code": section.code, "title": section.title, "questions": questions})

    response = client.post(
        f"/api/questionnaires/{questionnaire_id}/upload",
        files={
            "file": (
                "edit.xlsx",
                build_questionnaire_workbook(
                    title=roundtripped.title or "",
                    description=None,
                    questionnaire_id=questionnaire_id,
                    version=2,
                    sections=edited,
                    entry_labels=roundtripped.entryLabels,
                ),
                XLSX_MIME,
            )
        },
        headers=_headers(world, "admin"),
    )
    assert response.status_code == 200, response.text
    report = response.json()["report"]
    form = response.json()["questionnaire"]
    by_id = {q["id"]: q for s in form["sections"] for q in s["questions"]}

    assert report["superseded"] == 1
    assert report["retired"] == 1
    assert report["created"] == 1

    assert by_id[before["Untouched question"]["id"]]["isActive"] is True
    old = by_id[before["Answered and reworded"]["id"]]
    assert old["prompt"] == "Answered and reworded"
    assert old["isActive"] is False
    assert by_id[old["supersededById"]]["prompt"] == "Answered and reworded (v2)"
    assert by_id[before["Unanswered and reworded"]["id"]]["prompt"] == "Unanswered and reworded (v2)"
    assert by_id[before["Unanswered and reworded"]["id"]]["isActive"] is True
    removed = by_id[before["Removed but answered"]["id"]]
    assert removed["isActive"] is False
    kept_answers = {a["questionId"] for a in form["entries"][0]["answers"]}
    assert before["Removed but answered"]["id"] in kept_answers
    assert "Brand new question" in {q["prompt"] for q in by_id.values()}


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_downloading_and_re_uploading_unchanged_does_not_resurrect_retired_questions(
    client, world
):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Noop {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "A",
                    "title": "A",
                    "questions": [{"id": "", "prompt": "Original wording", "answers": {"R": "12"}}],
                }
            ],
            entry_labels=["R"],
        ),
    ).json()["questionnaire"]
    original = _questions(created)["Original wording"]
    superseded = client.patch(
        f"/api/questionnaires/{created['id']}/questions/{original['id']}",
        json={"prompt": "Replacement wording"},
        headers=_headers(world),
    ).json()
    replacement_id = superseded["replacementId"]
    version_after_supersede = superseded["questionnaire"]["version"]

    same_file = client.get(
        f"/api/questionnaires/{created['id']}/xlsx", headers=_headers(world)
    ).content
    response = client.post(
        f"/api/questionnaires/{created['id']}/upload",
        files={"file": ("same.xlsx", same_file, XLSX_MIME)},
        headers=_headers(world),
    )
    assert response.status_code == 200, response.text
    report = response.json()["report"]
    form = response.json()["questionnaire"]
    by_id = {q["id"]: q for s in form["sections"] for q in s["questions"]}

    assert report["created"] == 0
    assert report["superseded"] == 0
    assert report["retired"] == 0
    assert report["removed"] == 0
    assert by_id[original["id"]]["isActive"] is False
    assert by_id[original["id"]]["supersededById"] == replacement_id
    assert by_id[replacement_id]["isActive"] is True
    assert len(by_id) == 2
    assert form["version"] == version_after_supersede


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_uploading_a_workbook_downloaded_from_a_different_questionnaire_is_refused(
    client, world
):
    first = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"First {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Q one"}]}],
        ),
    ).json()["questionnaire"]
    second = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Second {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "B", "title": "B", "questions": [{"id": "", "prompt": "Q two"}]}],
        ),
    ).json()["questionnaire"]

    wrong_file = client.get(
        f"/api/questionnaires/{second['id']}/xlsx", headers=_headers(world)
    ).content
    response = client.post(
        f"/api/questionnaires/{first['id']}/upload",
        files={"file": ("wrong.xlsx", wrong_file, XLSX_MIME)},
        headers=_headers(world),
    )
    assert response.status_code == 409, response.text
    assert second["id"] in response.json()["detail"]
    still = client.get(f"/api/questionnaires/{first['id']}", headers=_headers(world)).json()
    assert [q["prompt"] for s in still["sections"] for q in s["questions"]] == ["Q one"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_answers_are_recorded_in_the_app_and_saving_twice_changes_nothing(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Answer in app {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "A",
                    "title": "A",
                    "questions": [{"id": "", "prompt": "How many looms?"}, {"id": "", "prompt": "Who taught you?"}],
                }
            ],
        ),
    ).json()["questionnaire"]
    assert created["entries"] == []

    entry = client.post(
        f"/api/questionnaires/{created['id']}/entries",
        json={"respondentName": "Ramesh"},
        headers=_headers(world),
    ).json()
    questions = _questions(created)
    batch = {
        "answers": [
            {"questionId": questions["How many looms?"]["id"], "answerText": "12"},
            {"questionId": questions["Who taught you?"]["id"], "answerText": "My mother"},
        ]
    }
    first = client.put(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}/answers",
        json=batch,
        headers=_headers(world),
    )
    assert first.status_code == 200, first.text
    assert first.json()["saved"] == {"created": 2, "updated": 0, "unchanged": 0, "rejected": []}

    again = client.put(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}/answers",
        json=batch,
        headers=_headers(world),
    )
    assert again.json()["saved"] == {"created": 0, "updated": 0, "unchanged": 2, "rejected": []}
    assert len(again.json()["answers"]) == 2


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_questionnaire_attaches_to_a_workshop_and_shows_up_in_the_dropdown(client, world):
    workshop_id = _open_workshop(client, world, {"title": f"Attach test {world['stamp']}"})

    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Attachable {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Q?"}]}],
        ),
    ).json()["questionnaire"]

    patched = client.patch(
        f"/api/questionnaires/{created['id']}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world),
    )
    assert patched.status_code == 200, patched.text
    assert patched.json()["designWorkshopId"] == workshop_id

    scoped = client.get(
        "/api/questionnaires",
        params={"designWorkshopId": workshop_id},
        headers=_headers(world),
    ).json()
    assert [row["id"] for row in scoped["items"]] == [created["id"]]

    options = client.get("/api/questionnaires/options", headers=_headers(world)).json()
    assert created["id"] in {row["id"] for row in options}

    detached = client.patch(
        f"/api/questionnaires/{created['id']}",
        json={"designWorkshopId": None},
        headers=_headers(world),
    ).json()
    assert detached["designWorkshopId"] is None


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_questionnaire_cannot_be_attached_to_a_workshop_the_designer_cannot_open(
    client, world
):
    workshop_id = _open_workshop(client, world, {"title": f"Not yours {world['stamp']}"})

    assert (
        client.get(
            f"/api/design-workshops/{workshop_id}", headers=_headers(world, "stranger")
        ).status_code
        == 404
    )

    made = client.post(
        "/api/questionnaires",
        json={"title": f"Injected {world['stamp']}", "designWorkshopId": workshop_id},
        headers=_headers(world, "stranger"),
    )
    assert made.status_code == 404, made.text

    mine = client.get("/api/questionnaires", headers=_headers(world, "stranger")).json()
    assert f"Injected {world['stamp']}" not in {row["title"] for row in mine["items"]}

    marker = f"Which loom did you inherit {world['stamp']}?"
    workbook = build_questionnaire_workbook(
        title=f"Uploaded elsewhere {world['stamp']}",
        description=None,
        questionnaire_id="",
        version=1,
        sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": marker,
                                                             "answers": {"Ramesh": "The big one"}}]}],
        entry_labels=["Ramesh"],
    )
    uploaded = client.post(
        "/api/questionnaires/upload",
        files={"file": ("form.xlsx", workbook, XLSX_MIME)},
        data={"designWorkshopId": workshop_id},
        headers=_headers(world, "stranger"),
    )
    assert uploaded.status_code == 404, uploaded.text

    own = client.post(
        "/api/questionnaires/upload",
        files={"file": ("form.xlsx", workbook, XLSX_MIME)},
        headers=_headers(world, "stranger"),
    )
    assert own.status_code == 201, own.text
    own_id = own.json()["questionnaire"]["id"]

    patched = client.patch(
        f"/api/questionnaires/{own_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world, "stranger"),
    )
    assert patched.status_code == 404, patched.text
    still = client.get(f"/api/questionnaires/{own_id}", headers=_headers(world, "stranger")).json()
    assert still["designWorkshopId"] is None

    scoped = client.get(
        "/api/questionnaires",
        params={"designWorkshopId": workshop_id},
        headers=_headers(world),
    ).json()
    assert scoped["items"] == []
    preview = client.get(
        f"/api/design-workshops/{workshop_id}/report/preview", headers=_headers(world)
    )
    assert preview.status_code == 200, preview.text
    assert marker not in preview.text

    grant = client.put(
        f"/api/design-workshops/{workshop_id}/viewers",
        json={"userIds": [world["people"]["stranger"].id]},
        headers=_headers(world, "admin"),
    )
    assert grant.status_code == 200, grant.text
    allowed = client.patch(
        f"/api/questionnaires/{own_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world, "stranger"),
    )
    assert allowed.status_code == 200, allowed.text
    assert allowed.json()["designWorkshopId"] == workshop_id


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_questionnaire_cannot_be_attached_to_a_deleted_workshop(client, world):
    workshop_id = _open_workshop(client, world, {"title": f"Deleted target {world['stamp']}"})
    created = client.post(
        "/api/questionnaires",
        json={"title": f"For a dead workshop {world['stamp']}"},
        headers=_headers(world),
    )
    assert created.status_code == 201, created.text
    questionnaire_id = created.json()["id"]

    removed = client.delete(
        f"/api/design-workshops/{workshop_id}", headers=_headers(world, "admin")
    )
    assert removed.status_code == 204, removed.text

    refused = client.patch(
        f"/api/questionnaires/{questionnaire_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world),
    )
    assert refused.status_code in (404, 409), refused.text
    still = client.get(
        f"/api/questionnaires/{questionnaire_id}", headers=_headers(world)
    ).json()
    assert still["designWorkshopId"] is None


def _annexure_blocks(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    for index, block in enumerate(blocks):
        if block["type"] == "HEADING" and "Questionnaire responses" in _runs_text(block["runs"]):
            return blocks[index:]
    return []


def _runs_text(runs: list[dict[str, Any]]) -> str:
    return "".join(run["text"] for run in runs)


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_the_report_annexure_prints_a_two_section_form_section_by_section(client, world):
    workshop_id = _open_workshop(client, world, {"title": f"Annexure order {world['stamp']}"})

    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Two sections {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "CRAFT",
                    "title": "About the craft",
                    "questions": [
                        {"id": "", "prompt": "How many looms do you own?",
                         "answers": {"Ramesh": "12"}},
                        {"id": "", "prompt": "Who taught you?",
                         "answers": {"Ramesh": "My father"}},
                    ],
                },
                {
                    "code": "MAT",
                    "title": "Materials",
                    "questions": [
                        {"id": "", "prompt": "Where do you buy your yarn?",
                         "answers": {"Ramesh": "Panipat mandi"}},
                        {"id": "", "prompt": "What does a kilo cost?",
                         "answers": {"Ramesh": "Rs 240"}},
                    ],
                },
            ],
            entry_labels=["Ramesh"],
        ),
    )
    assert created.status_code == 201, created.text
    questionnaire_id = created.json()["questionnaire"]["id"]

    attached = client.patch(
        f"/api/questionnaires/{questionnaire_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world),
    )
    assert attached.status_code == 200, attached.text

    preview = client.get(
        f"/api/design-workshops/{workshop_id}/report/preview", headers=_headers(world)
    )
    assert preview.status_code == 200, preview.text
    annexure = _annexure_blocks(preview.json()["blocks"])
    assert annexure, "the questionnaire annexure never reached the preview at all"

    for index, block in enumerate(annexure):
        if block["type"] == "HEADING" and _runs_text(block["runs"]) == "Ramesh":
            sitting = annexure[index:]
            break
    else:
        raise AssertionError("the sitting's heading is missing from the annexure")

    tables = [
        b for b in sitting
        if b["type"] == "TABLE" and [c["header"] for c in b["columns"]] == ["Question", "Answer"]
    ]
    assert len(tables) == 2, (
        "one table per SECTION, not one per question — a table per row is what the interleaved "
        f"ordering produced; got {len(tables)}"
    )
    assert [[_runs_text(row[0]) for row in table["rows"]] for table in tables] == [
        ["How many looms do you own?", "Who taught you?"],
        ["Where do you buy your yarn?", "What does a kilo cost?"],
    ]
    assert [[_runs_text(row[1]) for row in table["rows"]] for table in tables] == [
        ["12", "My father"],
        ["Panipat mandi", "Rs 240"],
    ]

    labels = [
        _runs_text(b["runs"]) for b in sitting
        if b["type"] == "PARAGRAPH" and _runs_text(b["runs"]).startswith(("CRAFT", "MAT"))
    ]
    assert labels == ["CRAFT — About the craft", "MAT — Materials"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_recorded_answer_is_inside_the_generated_docx_file(client, world):
    workshop_id = _open_workshop(client, world, {"title": f"Docx annexure {world['stamp']}"})

    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Docx instrument {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{
                "code": "CRAFT",
                "title": "About the craft",
                "questions": [{
                    "id": "",
                    "prompt": "How many looms do you own?",
                    "answers": {"Ramesh Meher": "Twelve looms, ZEBRAFISHLOOM"},
                }],
            }],
            entry_labels=["Ramesh Meher"],
        ),
    )
    assert created.status_code == 201, created.text
    questionnaire_id = created.json()["questionnaire"]["id"]

    attached = client.patch(
        f"/api/questionnaires/{questionnaire_id}",
        json={"designWorkshopId": workshop_id},
        headers=_headers(world),
    )
    assert attached.status_code == 200, attached.text

    generated = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"], "record": False},
        headers=_headers(world),
    )
    assert generated.status_code == 200, generated.text

    with zipfile.ZipFile(BytesIO(generated.content)) as archive:
        document = archive.read("word/document.xml").decode("utf-8")

    for expected in (
        "Questionnaire responses",
        f"Docx instrument {world['stamp']}",
        "Ramesh Meher",
        "How many looms do you own?",
        "ZEBRAFISHLOOM",
    ):
        assert expected in document, (
            f"{expected!r} is not in word/document.xml, so the questionnaire annexure did not "
            "reach the file the designer downloads"
        )


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_an_unanswered_questionnaire_is_never_silently_absent_from_the_report(client, world):
    workshop_id = _open_workshop(client, world, {"title": f"Unanswered {world['stamp']}", "templateId": "DCH_STANDARD"})

    created = client.post(
        "/api/questionnaires",
        json={
            "title": f"Unanswered instrument {world['stamp']}",
            "designWorkshopId": workshop_id,
            "sections": [{
                "title": "About the craft",
                "code": "CRAFT",
                "questions": [{"prompt": "How many looms do you own?", "isRequired": True}],
            }],
        },
        headers=_headers(world),
    )
    assert created.status_code == 201, created.text

    preview = client.get(
        f"/api/design-workshops/{workshop_id}/report/preview", headers=_headers(world)
    )
    assert preview.status_code == 200, preview.text
    assert any("questionnaire annexure" in w for w in preview.json()["warnings"]), (
        "an attached questionnaire with no recorded answers raised no warning at all, so its "
        "absence from the report is invisible on every surface"
    )

    generated = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"], "record": False},
        headers=_headers(world),
    )
    assert generated.status_code == 200, generated.text
    header = generated.headers["x-report-warnings"]
    pieces = [piece for piece in header.split("; ") if piece]

    assert "questionnaire annexure" in header or "did not fit in this header" in header, (
        "the questionnaire annexure's warning did not reach the header AND the header did not say "
        f"anything was dropped, so the designer is told nothing at all. Header was: {header!r}"
    )
    assert int(generated.headers["x-report-warning-count"]) >= len(pieces)


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_questionnaire_title_a_designer_typed_cannot_split_its_own_warning(client, world):
    workshop_id = _open_workshop(client, world, {"title": f"Split title {world['stamp']}", "templateId": "PHOTO_CATALOGUE"})

    title = f"Loom survey; round two {world['stamp']}"
    created = client.post(
        "/api/questionnaires",
        json={
            "title": title,
            "designWorkshopId": workshop_id,
            "sections": [{
                "title": "About the craft",
                "code": "CRAFT",
                "questions": [{"prompt": "How many looms do you own?", "isRequired": True}],
            }],
        },
        headers=_headers(world),
    )
    assert created.status_code == 201, created.text

    generated = client.post(
        f"/api/design-workshops/{workshop_id}/report",
        json={"formats": ["DOCX"], "record": False},
        headers=_headers(world),
    )
    assert generated.status_code == 200, generated.text
    header = generated.headers["x-report-warnings"]
    count = int(generated.headers["x-report-warning-count"])
    assert "did not fit in this header" not in header, (
        "this fixture is meant to fit inside the budget whole; if it stopped fitting, the "
        "assertion below is no longer measuring the separator"
    )

    pieces = [piece.strip() for piece in header.split(";") if piece.strip()]
    assert len(pieces) == count, (
        f"x-report-warning-count says {count} but the designer's screen shows {len(pieces)} "
        f"warnings, so a designer-typed semicolon split one sentence into halves: {pieces}"
    )
    annexure = [piece for piece in pieces if "questionnaire annexure" in piece]
    assert len(annexure) == 1 and annexure[0].endswith(")."), (
        f"the annexure's own warning did not arrive as one finished sentence: {pieces}"
    )


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_colleague_may_answer_a_questionnaire_but_may_not_reword_it(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Shared {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "How many looms?"}]}],
        ),
    ).json()["questionnaire"]
    question = _questions(created)["How many looms?"]
    colleague = _headers(world, "colleague")

    entry = client.post(
        f"/api/questionnaires/{created['id']}/entries",
        json={"respondentName": "Sita"},
        headers=colleague,
    )
    assert entry.status_code == 201, entry.text
    saved = client.put(
        f"/api/questionnaires/{created['id']}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": question["id"], "answerText": "4"}]},
        headers=colleague,
    )
    assert saved.status_code == 200, saved.text

    refused = client.patch(
        f"/api/questionnaires/{created['id']}/questions/{question['id']}",
        json={"prompt": "Reworded by somebody else"},
        headers=colleague,
    )
    assert refused.status_code == 403, refused.text


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_designer_cannot_rewrite_whose_sitting_a_colleagues_answers_belong_to(
    client, world
):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Authorship {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Q?"}]}],
        ),
    ).json()["questionnaire"]
    colleague = _headers(world, "colleague")

    entry = client.post(
        f"/api/questionnaires/{created['id']}/entries",
        json={"respondentName": "Sita Devi"},
        headers=colleague,
    ).json()

    stranger = _headers(world, "stranger")
    refused = client.patch(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}",
        json={"respondentName": "Somebody Else"},
        headers=stranger,
    )
    assert refused.status_code == 403, refused.text

    reread = client.get(
        f"/api/questionnaires/{created['id']}", headers=colleague
    ).json()["entries"]
    assert [e["respondentName"] for e in reread] == ["Sita Devi"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_the_interviewer_and_the_form_owner_may_both_correct_a_sitting(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Correctable {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Q?"}]}],
        ),
    ).json()["questionnaire"]
    colleague = _headers(world, "colleague")

    entry = client.post(
        f"/api/questionnaires/{created['id']}/entries",
        json={"respondentName": "Sta Devi"},
        headers=colleague,
    ).json()

    by_author = client.patch(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}",
        json={"respondentName": "Sita Devi"},
        headers=colleague,
    )
    assert by_author.status_code == 200, by_author.text

    by_owner = client.patch(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}",
        json={"notes": "Recorded through a translator."},
        headers=_headers(world),
    )
    assert by_owner.status_code == 200, by_owner.text

    by_admin = client.patch(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}",
        json={"title": "Sitting 1"},
        headers=_headers(world, "admin"),
    )
    assert by_admin.status_code == 200, by_admin.text


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_another_designers_sittings_do_not_ride_along_on_the_form(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Sittings {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Q?"}]}],
        ),
    ).json()["questionnaire"]
    question = _questions(created)["Q?"]

    entry = client.post(
        f"/api/questionnaires/{created['id']}/entries",
        json={"respondentName": "Sita Devi", "notes": "Her account of the dye pit."},
        headers=_headers(world),
    ).json()
    client.put(
        f"/api/questionnaires/{created['id']}/entries/{entry['id']}/answers",
        json={"answers": [{"questionId": question["id"], "answerText": "Twelve looms"}]},
        headers=_headers(world),
    )

    stranger = _headers(world, "stranger")
    seen = client.get(f"/api/questionnaires/{created['id']}", headers=stranger)
    assert seen.status_code == 200, "the FORM stays readable, or a colleague cannot fill it in"
    payload = seen.json()
    assert payload["entries"] == [], "another designer's sittings must not ride along"
    body = seen.text
    for secret in ("Sita Devi", "Her account of the dye pit.", "Twelve looms"):
        assert secret not in body, f"{secret!r} reached a designer who recorded none of it"

    assert _questions(payload)["Q?"]["hasAnswers"] is True

    mine = client.get(f"/api/questionnaires/{created['id']}", headers=_headers(world)).json()
    assert [e["respondentName"] for e in mine["entries"]] == ["Sita Devi"]


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_the_workbook_of_somebody_elses_answers_is_not_a_public_download(client, world):
    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"Workbook {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[{"code": "A", "title": "A", "questions": [{"id": "", "prompt": "Q?"}]}],
        ),
    ).json()["questionnaire"]

    refused = client.get(
        f"/api/questionnaires/{created['id']}/xlsx", headers=_headers(world, "stranger")
    )
    assert refused.status_code == 403, refused.text

    mine = client.get(f"/api/questionnaires/{created['id']}/xlsx", headers=_headers(world))
    assert mine.status_code == 200, mine.text


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_the_database_itself_refuses_to_orphan_an_answer(client, world):
    from prisma.errors import ForeignKeyViolationError

    from prisma import Prisma

    created = _upload(
        client,
        world,
        build_questionnaire_workbook(
            title=f"FK floor {world['stamp']}",
            description=None,
            questionnaire_id="",
            version=1,
            sections=[
                {
                    "code": "A",
                    "title": "A",
                    "questions": [{"id": "", "prompt": "Answered", "answers": {"R": "12"}}],
                }
            ],
            entry_labels=["R"],
        ),
    ).json()["questionnaire"]
    question_id = _questions(created)["Answered"]["id"]

    own = Prisma()
    await own.connect()
    try:
        with pytest.raises(ForeignKeyViolationError):
            await own.questionnaireformquestion.delete(where={"id": question_id})
        survivor = await own.questionnaireformquestion.find_unique(where={"id": question_id})
        assert survivor is not None
    finally:
        await own.disconnect()


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_question_someone_only_tabbed_past_can_still_be_deleted(client, world):
    created = client.post(
        "/api/questionnaires",
        json={"title": f"Tabbed past {world['stamp']}"},
        headers=_headers(world),
    )
    assert created.status_code == 201, created.text
    form = created.json()
    questionnaire_id = form["id"]

    section = client.post(
        f"/api/questionnaires/{questionnaire_id}/sections",
        json={"code": "S1", "title": "Section one"},
        headers=_headers(world),
    )
    assert section.status_code == 201, section.text
    section_id = section.json()["sections"][-1]["id"]

    made = client.post(
        f"/api/questionnaires/{questionnaire_id}/sections/{section_id}/questions",
        json={"prompt": "How many looms?"},
        headers=_headers(world),
    )
    assert made.status_code == 201, made.text
    question_id = _questions(made.json())["How many looms?"]["id"]

    entry = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"title": "A sitting somebody opened and left"},
        headers=_headers(world),
    )
    assert entry.status_code == 201, entry.text
    entry_id = entry.json()["id"]

    saved = client.put(
        f"/api/questionnaires/{questionnaire_id}/entries/{entry_id}/answers",
        json={"answers": [{"questionId": question_id, "answerText": "   "}]},
        headers=_headers(world),
    )
    assert saved.status_code == 200, saved.text

    removed = client.delete(
        f"/api/questionnaires/{questionnaire_id}/questions/{question_id}",
        headers=_headers(world, "admin"),
    )
    assert removed.status_code == 200, removed.text
    assert removed.json()["action"] == "deleted", (
        "a question nobody actually answered must DELETE, not retire — retiring it would leave the "
        "designer's mistyped question in the form for ever"
    )
    assert not _questions(removed.json()["questionnaire"]), "the question is gone from the form"


@pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database")
@pytest.mark.anyio
async def test_a_question_with_a_real_answer_is_still_retired_rather_than_deleted(client, world):
    created = client.post(
        "/api/questionnaires",
        json={"title": f"Really answered {world['stamp']}"},
        headers=_headers(world),
    )
    questionnaire_id = created.json()["id"]
    section = client.post(
        f"/api/questionnaires/{questionnaire_id}/sections",
        json={"code": "S1", "title": "Section one"},
        headers=_headers(world),
    )
    section_id = section.json()["sections"][-1]["id"]
    made = client.post(
        f"/api/questionnaires/{questionnaire_id}/sections/{section_id}/questions",
        json={"prompt": "How many looms?"},
        headers=_headers(world),
    )
    question_id = _questions(made.json())["How many looms?"]["id"]
    entry = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        json={"title": "A real sitting"},
        headers=_headers(world),
    )
    client.put(
        f"/api/questionnaires/{questionnaire_id}/entries/{entry.json()['id']}/answers",
        json={"answers": [{"questionId": question_id, "answerText": "Twelve"}]},
        headers=_headers(world),
    )

    removed = client.delete(
        f"/api/questionnaires/{questionnaire_id}/questions/{question_id}",
        headers=_headers(world, "admin"),
    )
    assert removed.status_code == 200, removed.text
    assert removed.json()["action"] == "retired"
    kept = _questions(removed.json()["questionnaire"])["How many looms?"]
    assert kept["isActive"] is False
