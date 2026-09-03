"""The audit ledger and the row it describes are ONE write, or neither of them happened.

Every write path in this repository that changes a record also appends a row saying it did — a
``RecordRevision`` on the six PATCH routes, a ``ReviewLog`` on the review router. Until 2026-09-03
each pair was TWO SEPARATE COMMITS in a row, and this file is about the gap between them.

    ``guard_record_edit`` ends in ``record_revision``, which committed on its own; the caller's
    ``update`` committed a statement or thirty later. ``set_review_status`` had the same defect
    INVERTED — the status change committed first and the ``ReviewLog`` explaining it second.

Nothing in the source stood between them, which is exactly why no amount of reordering could close
it: the window is the network, not the code. On this deployment the database is in another AWS
region and ``P2024`` (connection-pool timeout) is the failure the stack sees most, so "a request
that dies between two statements" is the ordinary case, not the exotic one. What it left behind:

* an audit row asserting a field was changed to a value NO ROW ANYWHERE HOLDS, authored by a named
  person — worse than a gap in the ledger, because ``GET /api/data-access/revisions`` is what an
  admin reads to reconstruct who did what, and it would report a value nobody ever saved as the
  newest one; and
* on the review side, a record APPROVED or REJECTED with nothing at all saying who decided it or
  why — a decision that appears to have made itself, on the one router whose entire purpose is
  accountability for decisions.

WHAT IS MEASURED, AND WHY IT IS MEASURED THIS WAY. Each test breaks ONE of the two halves and
asserts the OTHER one did not survive. That is the only way to observe a rollback from outside: a
transaction that works and a transaction that is not there look identical on the happy path, so the
happy-path tests below (which are equally load-bearing — a "fix" that stopped writing the audit row
would pass every rollback assertion here) cannot be the whole file.

THE BREAK IS APPLIED AT THE PRISMA ACTION CLASS, NOT ON THE MODULE-LEVEL ``db``, and that is the
whole trick — the same one ``test_questionnaire_reuse`` documents. ``db.tx()`` hands back a
DIFFERENT client, so a patch on ``db`` would not be inside the transaction at all and these tests
would measure nothing. Patching the generated action class reaches whichever client is holding it.

THE FIXTURES ARE BUILT BEFORE THE PATCH IN EVERY TEST, deliberately and for the same reason that
file records: a patch applied first breaks the very row the test needs and the run fails one line
above the thing under test.

These need Postgres: what is under test is which rows survive a failed request. The module skips
itself through the shared gate when ``DATABASE_URL`` does not point at a local database.

    docker compose up -d postgres
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

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
    """An owning designer, an admin reviewer, and a small pool of products to spend.

    Rows are created here rather than inside a test because the Prisma client is shared with the
    running app and bound to the TestClient's event loop; touching it from a test's own loop is the
    kind of cross-loop use that fails intermittently rather than honestly. That is also why the
    products are created UP FRONT as a pool: each review test consumes one (a status change is
    permanent, so two tests cannot share a record), and there is no loop from which to make another
    once the client is running.

    THE REVIEWER IS AN ADMIN AND THE AUTHOR IS A DESIGNER on purpose. ``can_review_record`` admits a
    reviewer only over a creator ranked STRICTLY below them; an admin over a designer clears it with
    room to spare, so nothing here can pass or fail for a rank reason that has nothing to do with
    the transaction under test.
    """
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
        # ── THE INTERVIEW FIXTURES, for section 3 ────────────────────────────────────────────────
        #
        # NO ``QuestionnaireSection`` ROW IS CREATED, and that is deliberate rather than lazy. That
        # table is UNIQUE on both ``code`` and ``sortOrder`` and is shared with whatever the seed
        # scripts put there, so a module fixture inserting one is a module fixture that collides
        # with a seeded database. ``QuestionnaireQuestion.sectionId`` is nullable and nothing in
        # ``upsert_responses`` reads the section, so an unparented question answers every question
        # this section asks.
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
        # ONE ANSWER ALREADY WRITTEN BY SOMEBODY ELSE, on an interview of its own. This is what makes
        # the 403 in ``upsert_responses`` reachable: the rule is "only the original contributor or an
        # admin may change an answer that already has text", the owner is a DESIGNER rather than an
        # admin, and this row's author is the reviewer.
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

    # ONE TestClient, two tokens. Two nested clients each run the app's lifespan against the SAME
    # module-level Prisma client, and the second teardown disconnects it under the first — which
    # hangs rather than failing. The account is chosen per request instead.
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
    """One product from the pool, never handed out twice.

    Each review test APPROVES or REJECTS its record, and a record can only leave PENDING once — a
    shared row would make the second test's result depend on the first having run.
    """
    assert env["products"], "the product pool is empty; raise the count in the env fixture"
    return env["products"].pop()


def _process(env, *, notes=None) -> dict:
    """A fresh three-step process owned by the OWNER account, created through the real route."""
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
    """The step list exactly as ``ProcessForm`` re-sends it on an ordinary save."""
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
    """The record's whole edit history, read through the endpoint an admin actually uses.

    Read through the API rather than through ``db`` directly, deliberately: the Prisma client is
    shared with the running app and bound to the TestClient's event loop, so a query issued from a
    test's own loop is the kind of cross-loop use that fails intermittently instead of honestly. It
    also asserts the stronger thing — that the phantom row is (or is not) VISIBLE on the surface
    built for reconstructing a record, not merely absent from a table.
    """
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
    """One interview from the pool, never handed out twice — ``_spend_product``'s rule.

    Each test below leaves its interview with a committed edit or with a deliberate failure on it,
    and a shared row would make one test's result depend on another having run first.
    """
    assert env["interviews"], "the interview pool is empty; raise the count in the env fixture"
    return env["interviews"].pop()


def _interview_detail(env, interview_id: str) -> dict:
    response = env["client"].get(
        f"/api/questionnaire/interviews/{interview_id}", headers=env["owner"]
    )
    assert response.status_code == 200, response.text
    return response.json()


# --------------------------------------------------------------------------------------
# 1. PATCH /api/processes/{id} — the ledger must not outlive the row it describes
# --------------------------------------------------------------------------------------


def test_a_failed_row_update_takes_its_audit_row_down_with_it(env, monkeypatch):
    """THE DEFECT, in the shape it actually reaches production.

    ``guard_record_edit`` writes the RecordRevision for ``notes`` and returns; the very next
    statement is the ``update`` that stores it. Break that statement — a dropped connection, a pool
    timeout, a constraint nothing pre-empted all arrive here identically — and before this change
    the ledger kept a row saying the note had been changed, on a process whose note is still empty.

    The assertion that matters is the LAST one. The first two only establish that the request really
    did fail after the guard had run; if the note had been stored, the revision would be honest.
    """
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
    """THE THREE-WAY CASE, which is the one a two-statement transaction would still get wrong.

    A steps-carrying PATCH writes in four places: the scalar revision, the process row, the step
    rows, and the step revision. They are all one transaction, so breaking the THIRD must take the
    first two back with it — including the ``notes`` change, which had already succeeded.

    ``_apply_steps`` is where the break lands, and it is worth saying why that function is dangerous
    on its own: it is an insert, then per-step updates, then a delete. Outside a transaction, a
    failure in the middle of THAT commits some of a step rewrite and leaves the record describing a
    process nobody documented, with no revision to reconstruct it from — the revision being written
    afterwards is the same ordering defect one level down.
    """
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
    """THE OTHER DIRECTION, AND IT IS NOT A FORMALITY. Every rollback assertion above is also
    satisfied by a route that stopped writing revisions altogether, which would trade a phantom row
    for a MISSING one — strictly worse, since the step revision is the only thing an admin can
    restore deleted steps from. One PATCH, one revision, the right author, and the value stored."""
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


# --------------------------------------------------------------------------------------
# 2. The review router — the same defect, inverted
#
# Here the ROW commits first and the log of the decision second, so the surviving artefact is the
# opposite one: a record whose status changed with nothing anywhere saying who changed it. There is
# no read endpoint for ReviewLog, so the log half is measured from the other end — break the log
# write and assert the STATUS did not move, which can only be true if the two rolled back together.
# --------------------------------------------------------------------------------------


def test_a_failed_review_log_leaves_the_record_unreviewed(env, monkeypatch):
    """Approve a record and break the ReviewLog write.

    Before the transaction, the product came back APPROVED — permanently, since a record can only
    leave PENDING once and the reviewer was told the server had failed. The decision was real, the
    account that made it was not recorded anywhere, and the reviewer's reasonable next move (approve
    it again) answers 409 or silently re-approves an already-approved record.
    """
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
    """``POST /review/{type}/{id}/edit`` carried BOTH halves of the defect at once: a revision, then
    the row, then a ReviewLog. Breaking the middle one used to leave the revision committed — an
    audit row naming a reviewer as the author of a correction that was never stored, on the router
    whose whole subject is who changed what."""
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
    """The control for the two above: with nothing broken, the decision lands. A transaction that
    rolled everything back would satisfy every rollback assertion in this file and break the
    product."""
    product_id = _spend_product(env)

    response = env["client"].post(
        f"/api/review/product/{product_id}/approve",
        json={"notes": "Complete and legible."},
        headers=env["reviewer"],
    )
    assert response.status_code == 200, response.text
    assert _product_detail(env, product_id)["status"] == "APPROVED"


# --------------------------------------------------------------------------------------
# 3. PATCH /api/questionnaire/interviews/{id} — the two RELATION writes, which the first
#    wave of this work deliberately left outside the transaction
#
# That route opened a ``db.tx()`` for its ledger row and its own columns on 2026-09-03 and stopped
# there, under a comment saying so: ``replace_interview_artisans`` and ``upsert_responses`` still
# wrote through the module client, so pulling them inside without threading ``client`` down would
# have produced calls that LOOK transactional and commit outside the block — the one failure mode
# with no symptom. Both helpers now take ``client``, and this section is what says the widening is
# real rather than cosmetic.
#
# THE REFUSALS ARE WHY IT MATTERS, NOT THE DRIVER ERRORS. Both relation writes can raise on a
# perfectly healthy database — a 403 on somebody else's answer, a 404 on an artisan id, a 409 on the
# unique artisan-set key — and every one of those used to arrive AFTER the interview's own columns
# had been committed. That is not a network-window defect at all; it is the ordinary case.
# --------------------------------------------------------------------------------------


def test_a_refused_answer_takes_the_interviews_own_edit_back_with_it(env):
    """**THE ORDINARY CASE: TWO RESEARCHERS EDITING ONE SECTION.**

    ``upsert_responses`` refuses a save that would overwrite an answer somebody else wrote — the
    rule that stops one researcher taking authorship of another's work. It runs LAST, after the
    interview's own columns have been written. So a designer who fixed the sitting's notes and, in
    the same save, typed over a colleague's answer used to read 403 with the notes already stored,
    a RecordRevision already appended, and no way to tell which half had landed. The obvious retry
    re-sends both and fails again.

    Nothing is broken here: the database is healthy, the request is well-formed, and the refusal is
    the product working correctly. That is the point — this is the failure that did not need a
    dropped connection.
    """
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
    """THE OTHER RELATION, AND ITS REFUSAL IS A 404 RATHER THAN A 403.

    ``replace_interview_artisans`` validates the whole artisan set up front so a bad id cannot leave
    a half-rewritten link set — but the validation is inside a function that used to run after the
    interview row had been committed, so the guarantee stopped at the link table. A save naming one
    artisan who no longer exists (a stale picker on a phone that has been offline, an id typed into
    an integration) answered 404 with the sitting's notes already changed.

    An id that is not in the table is used rather than a real artisan, deliberately: it needs no
    ``Artisan`` fixture, and the refusal it produces is the same statement's.
    """
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
    """THE NETWORK-WINDOW HALF, on the relation the two above reach by refusal instead.

    Breaking the answer INSERT is the same shape as section 1's broken step write: the scalar
    revision and the interview row have both already succeeded when it lands, so the only way the
    note can be absent afterwards is a rollback that reached all three.
    """
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
    """THE CONTROL, AND IT IS NOT A FORMALITY HERE EITHER. Every rollback assertion above is also
    satisfied by a widened transaction that silently stopped committing the relation writes, which
    would trade a partial save for a save that loses the answers — strictly worse, because the
    researcher is told it worked. One PATCH: the note stored, the answer stored, one revision."""
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
