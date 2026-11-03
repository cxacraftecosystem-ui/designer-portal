import uuid

import pytest
from conftest import needs_db

from app.core.db import db
from app.core.security import create_access_token, hash_password

pytestmark = [needs_db, pytest.mark.anyio]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def env():
    from fastapi.testclient import TestClient

    from app.main import app

    stamp = uuid.uuid4().hex[:8]
    await db.connect()
    try:
        owner = await db.user.create(
            data={
                "email": f"ledger-owner-{stamp}@example.org",
                "name": "Ledger Owner",
                "role": "DESIGNER",
                "passwordHash": hash_password("unused"),
            }
        )
        reviewer = await db.user.create(
            data={
                "email": f"ledger-reviewer-{stamp}@example.org",
                "name": "Ledger Reviewer",
                "role": "ADMIN",
                "passwordHash": hash_password("unused"),
            }
        )
        products = []
        for index in range(5):
            row = await db.productdocumentation.create(
                data={
                    "craftName": "Block printing",
                    "place": "Bagru",
                    "artisanName": "Ramesh Kumar",
                    "productName": f"Printed cotton yardage {index}",
                    "createdById": owner.id,
                }
            )
            products.append(row.id)
        question = await db.questionnairequestion.create(
            data={
                "sectionCode": f"LEDGER-{stamp}",
                "sectionTitle": "Ledger atomicity",
                "prompt": "What did the artisan say about the dye bath?",
                "sortOrder": 1,
            }
        )
        interviews = []
        for index in range(4):
            row = await db.questionnaireinterview.create(
                data={"title": f"Ledger sitting {index} {stamp}", "createdById": owner.id}
            )
            interviews.append(row.id)
        contested = interviews.pop()
        await db.questionnaireresponse.create(
            data={
                "interviewId": contested,
                "questionId": question.id,
                "answerText": "The reviewer's own answer, which the owner may not overwrite.",
                "answeredById": reviewer.id,
            }
        )
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        yield {
            "client": client,
            "owner": {"Authorization": f"Bearer {create_access_token(subject=owner.id)}"},
            "reviewer": {"Authorization": f"Bearer {create_access_token(subject=reviewer.id)}"},
            "owner_id": owner.id,
            "reviewer_id": reviewer.id,
            "products": products,
            "question_id": question.id,
            "interviews": interviews,
            "contested": contested,
        }


THREE_STEPS = [
    {"name": "Wash the cloth", "stepType": "SEQUENTIAL", "sortOrder": 1},
    {"name": "Print the border", "stepType": "SEQUENTIAL", "sortOrder": 2},
    {"name": "Sun-dry", "stepType": "SEQUENTIAL", "sortOrder": 3},
]


def _spend_product(env) -> str:
    assert env["products"], "the product pool is empty; raise the count in the env fixture"
    return env["products"].pop()


def _process(env, *, notes=None) -> dict:
    response = env["client"].post(
        "/api/processes",
        json={
            "name": "Bagru printing sequence",
            "productId": env["products"][0],
            "notes": notes,
            "steps": THREE_STEPS,
        },
        headers=env["owner"],
    )
    assert response.status_code == 201, response.text
    return response.json()


def _resend(process: dict) -> list[dict]:
    return [
        {
            "id": step["id"],
            "name": step["name"],
            "stepType": step["stepType"],
            "sortOrder": step["sortOrder"],
            "notes": step["notes"],
        }
        for step in process["steps"]
    ]


def _revisions(env, record_type: str, record_id: str) -> list[dict]:
    response = env["client"].get(
        f"/api/data-access/revisions?recordType={record_type}&recordId={record_id}",
        headers=env["owner"],
    )
    assert response.status_code == 200, response.text
    return response.json()


def _process_detail(env, process_id: str) -> dict:
    response = env["client"].get(f"/api/processes/{process_id}", headers=env["owner"])
    assert response.status_code == 200, response.text
    return response.json()


def _product_detail(env, product_id: str) -> dict:
    response = env["client"].get(f"/api/products/{product_id}", headers=env["owner"])
    assert response.status_code == 200, response.text
    return response.json()


def _spend_interview(env) -> str:
    assert env["interviews"], "the interview pool is empty; raise the count in the env fixture"
    return env["interviews"].pop()


def _interview_detail(env, interview_id: str) -> dict:
    response = env["client"].get(
        f"/api/questionnaire/interviews/{interview_id}", headers=env["owner"]
    )
    assert response.status_code == 200, response.text
    return response.json()



def test_a_failed_row_update_takes_its_audit_row_down_with_it(env, monkeypatch):
    process = _process(env, notes=None)

    from prisma.actions import ProcessActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `update`
        raise RuntimeError("the process row could not be written")

    monkeypatch.setattr(ProcessActions, "update", _boom)

    response = env["client"].patch(
        f"/api/processes/{process['id']}",
        json={"notes": "A note whose write is about to fail."},
        headers=env["owner"],
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _process_detail(env, process["id"])["notes"] is None
    assert _revisions(env, "process", process["id"]) == [], (
        "the audit ledger kept a row for an edit that never landed"
    )


def test_a_failure_in_the_step_write_rolls_back_the_row_and_the_ledger_together(env, monkeypatch):
    process = _process(env, notes=None)
    added = [*_resend(process), {"name": "Fold and stack", "stepType": "SEQUENTIAL", "sortOrder": 4}]

    from prisma.actions import ProcessStepActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the new step could not be written")

    monkeypatch.setattr(ProcessStepActions, "create_many", _boom)

    response = env["client"].patch(
        f"/api/processes/{process['id']}",
        json={"notes": "Recorded alongside a step the write is about to lose.", "steps": added},
        headers=env["owner"],
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    detail = _process_detail(env, process["id"])
    assert detail["notes"] is None, "the scalar half of a failed save was committed"
    assert [s["name"] for s in detail["steps"]] == [s["name"] for s in THREE_STEPS]
    assert _revisions(env, "process", process["id"]) == [], (
        "the ledger recorded an edit whose row and steps were both rolled back"
    )


def test_the_successful_edit_still_writes_both_halves(env):
    process = _process(env, notes=None)

    response = env["client"].patch(
        f"/api/processes/{process['id']}",
        json={"notes": "Observed again during the second sitting."},
        headers=env["owner"],
    )
    assert response.status_code == 200, response.text

    rows = _revisions(env, "process", process["id"])
    assert len(rows) == 1, f"expected exactly one revision, got {[r['changes'] for r in rows]}"
    assert set(rows[0]["changes"]) == {"notes"}
    assert rows[0]["changes"]["notes"]["new"] == "Observed again during the second sitting."
    assert rows[0]["editedById"] == env["owner_id"]
    assert _process_detail(env, process["id"])["notes"] == "Observed again during the second sitting."



def test_a_failed_review_log_leaves_the_record_unreviewed(env, monkeypatch):
    product_id = _spend_product(env)
    assert _product_detail(env, product_id)["status"] == "PENDING"

    from prisma.actions import ReviewLogActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create`
        raise RuntimeError("the review log could not be written")

    monkeypatch.setattr(ReviewLogActions, "create", _boom)

    response = env["client"].post(
        f"/api/review/product/{product_id}/approve",
        json={"notes": "Approved in a request that is about to fail."},
        headers=env["reviewer"],
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _product_detail(env, product_id)["status"] == "PENDING", (
        "a record was approved with no ReviewLog entry naming who approved it"
    )


def test_a_failed_row_update_on_a_reviewer_edit_leaves_no_revision(env, monkeypatch):
    product_id = _spend_product(env)

    from prisma.actions import ProductDocumentationActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `update`
        raise RuntimeError("the product row could not be written")

    monkeypatch.setattr(ProductDocumentationActions, "update", _boom)

    response = env["client"].post(
        f"/api/review/product/{product_id}/edit",
        json={"fields": {"productName": "A correction that will not survive"}, "note": "Fixing."},
        headers=env["reviewer"],
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _product_detail(env, product_id)["productName"] != "A correction that will not survive"
    assert _revisions(env, "product", product_id) == [], (
        "a reviewer's edit left an audit row for a correction that was rolled back"
    )


def test_a_successful_approval_still_moves_the_status(env):
    product_id = _spend_product(env)

    response = env["client"].post(
        f"/api/review/product/{product_id}/approve",
        json={"notes": "Complete and legible."},
        headers=env["reviewer"],
    )
    assert response.status_code == 200, response.text
    assert _product_detail(env, product_id)["status"] == "APPROVED"



def test_a_refused_answer_takes_the_interviews_own_edit_back_with_it(env):
    interview_id = env["contested"]
    before = _interview_detail(env, interview_id)["notes"]

    response = env["client"].patch(
        f"/api/questionnaire/interviews/{interview_id}",
        json={
            "notes": "A note saved alongside an answer this account may not change.",
            "responses": [
                {
                    "questionId": env["question_id"],
                    "answerText": "Overwriting somebody else's answer.",
                }
            ],
        },
        headers=env["owner"],
    )
    assert response.status_code == 403, response.text

    assert _interview_detail(env, interview_id)["notes"] == before, (
        "the interview's own edit was committed behind a 403 about the answers in the same save"
    )
    assert _revisions(env, "questionnaire", interview_id) == [], (
        "the ledger kept a revision for a save the server refused"
    )


def test_an_unknown_artisan_id_rolls_the_whole_save_back(env):
    interview_id = _spend_interview(env)
    before = _interview_detail(env, interview_id)["notes"]

    response = env["client"].patch(
        f"/api/questionnaire/interviews/{interview_id}",
        json={
            "notes": "A note saved alongside an artisan id that does not exist.",
            "artisanIds": ["art-that-was-never-created"],
        },
        headers=env["owner"],
    )
    assert response.status_code == 404, response.text

    assert _interview_detail(env, interview_id)["notes"] == before, (
        "the interview's own edit survived a 404 raised by the artisan set in the same save"
    )
    assert _revisions(env, "questionnaire", interview_id) == [], (
        "the ledger kept a revision for a save that was refused"
    )


def test_a_failed_answer_write_rolls_back_the_row_and_the_ledger_together(env, monkeypatch):
    interview_id = _spend_interview(env)

    from prisma.actions import QuestionnaireResponseActions

    async def _boom(self, *args, **kwargs):  # noqa: ANN001 - mirrors the generated `create_many`
        raise RuntimeError("the answer could not be written")

    monkeypatch.setattr(QuestionnaireResponseActions, "create_many", _boom)

    response = env["client"].patch(
        f"/api/questionnaire/interviews/{interview_id}",
        json={
            "notes": "Recorded alongside an answer the write is about to lose.",
            "responses": [
                {"questionId": env["question_id"], "answerText": "An answer that will not land."}
            ],
        },
        headers=env["owner"],
    )
    assert response.status_code == 500, response.text

    monkeypatch.undo()
    assert _interview_detail(env, interview_id)["notes"] is None, (
        "the scalar half of a failed interview save was committed"
    )
    assert _revisions(env, "questionnaire", interview_id) == [], (
        "the ledger recorded an interview edit whose row and answers were both rolled back"
    )


def test_the_successful_interview_save_still_writes_every_half(env):
    interview_id = _spend_interview(env)

    response = env["client"].patch(
        f"/api/questionnaire/interviews/{interview_id}",
        json={
            "notes": "Observed again during the second sitting.",
            "responses": [
                {"questionId": env["question_id"], "answerText": "Indigo, three dips, sun-dried."}
            ],
        },
        headers=env["owner"],
    )
    assert response.status_code == 200, response.text

    detail = _interview_detail(env, interview_id)
    assert detail["notes"] == "Observed again during the second sitting."
    answers = {row["questionId"]: row["answerText"] for row in detail.get("responses") or []}
    assert answers.get(env["question_id"]) == "Indigo, three dips, sun-dried.", (
        "the answer was not committed, so the widened transaction is rolling back its own writes"
    )
    rows = _revisions(env, "questionnaire", interview_id)
    assert len(rows) == 1, f"expected exactly one revision, got {[r['changes'] for r in rows]}"
    assert set(rows[0]["changes"]) == {"notes"}
    assert rows[0]["editedById"] == env["owner_id"]
