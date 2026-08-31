"""The default questionnaire exists, is everybody's, and re-running the seeder does not double it.

WHAT THIS IS ABOUT. `Questionnaire.isShared` — the "default questionnaire" an administrator publishes
once — shipped on 2026-08-28 with the column, the visibility clause, the options arm, the badge on
both clients and the admin-only PATCH that sets it. **And nothing anywhere ever set it.** On a fresh
deployment `isShared = true` matched zero rows, so `/questionnaires` was empty for every new designer
and the attach dropdown on the design-workshop screen offered them nothing but their own uploads. The
feature was complete except for having anything to show.

`scripts/seed_shared_questionnaire.py` is what fills that gap, and the properties below are the ones
that make it safe to run on a database that is already in use:

1. **IT PUBLISHES A REAL INSTRUMENT.** 24 sections and 285 questions, the same tree
   `scripts/seed_questionnaire.py` puts into the GLOBAL artisan questionnaire — a different set of
   tables reachable at `/questionnaire` SINGULAR — so a designer gets the standard form where they
   actually work rather than an empty row with the right flag on it.
2. **IT IS IDEMPOTENT.** Not merely "does not crash twice": the second run must change NOTHING. A
   seeder that re-created the tree would give every deployment a second published form per run and
   no way for a designer to tell which is current.
3. **IT NEVER REWORDS AN ANSWERED QUESTION.** An answer belongs to the wording it was given under —
   the whole reason `QuestionnaireFormQuestion.supersededById` exists — and a script that rewrote
   prompts in place would do by the back door exactly what the API refuses at the front.
4. **AND A DESIGNER WHO OWNS NOTHING CAN SEE IT AND SELECT IT.** The seeing half and the selecting
   half, which the owner asked for in one sentence: *"designers can directly select and utilize it"*.

── HOW THIS FILE IS ARRANGED, AND WHY IT LOOKS TOP-HEAVY ────────────────────────────────────────

Every database call happens in ONE fixture, before the `TestClient` exists, and the tests assert
over what it recorded. That is the convention every database-backed suite here follows and it is not
stylistic: the Prisma client is a module-level singleton shared with the running app, the client's
lifespan connects it, so a test that calls `db.connect()` raises `AlreadyConnectedError` and one that
calls `db.disconnect()` pulls the connection out from under every test after it. The first version of
this file did exactly that and thirteen tests failed for that reason alone.

Needs Postgres. Skips itself when `DATABASE_URL` is not local, exactly as the other questionnaire
suites do.
"""

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
    """Run the seeder, drive the answered-question rule, and hand back what happened.

    ALL THE DATABASE WORK IS HERE — see the module docstring for why it cannot be spread across the
    tests. What comes back is a plain dict of facts, so each test below asserts one property of one
    run rather than re-running the seeder six times.

    NOT TORN DOWN. The published row is a repository-wide fixture rather than this test's private
    data — it is exactly what the seeder is for — and deleting it would take the standard form away
    from a developer's local database every time the suite ran. The one thing that IS undone is the
    deliberate corruption in step 3, which exists only to drive the rule.
    """
    from fastapi.testclient import TestClient

    from app.core.security import create_access_token
    from app.main import app

    facts: dict[str, Any] = {}
    await db.connect()
    try:
        # An owner must exist or the script refuses by design (`Questionnaire.ownerId` is NOT NULL,
        # and hanging the standard instrument off whichever user happened to be first is worse than
        # a refusal). Every environment this runs in has one; created here if not.
        if await db.user.count(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}}) == 0:
            await db.user.create(
                data={
                    "email": f"seed-shared-{uuid.uuid4().hex[:8]}@example.org",
                    "name": "Seed Admin",
                    "role": "ADMIN",
                }
            )
        owner = await db.user.find_first(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}})

        # 1. The first run.
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

        # 2. The second run, which must change nothing at all.
        facts["second_id"], facts["second_counts"] = await seed_shared_questionnaire()
        facts["published"] = await db.questionnaire.count(
            where={"isShared": True, "title": TITLE}
        )

        # 3. The answered-question rule, driven rather than assumed. Reword a question, answer it,
        #    and re-run: the seeder wants to put the JSON's wording back and must not, because the
        #    answer was given under the wording that is there now.
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
            # Put the fixture back whatever happened: the answer, the sitting, and the wording.
            if entry is not None:
                await db.questionnaireformentry.delete(where={"id": entry.id})
            await db.questionnaireformquestion.update(
                where={"id": question.id}, data={"prompt": original_prompt}
            )

        # 4. A brand-new designer, for the visibility half. Created here so the HTTP half below can
        #    be pure HTTP.
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
    """The one line in this repository that has ever set `isShared`."""
    row = seeded["row"]
    assert row["title"] == TITLE
    assert row["isShared"] is True, "the whole point of the row is that it is published"
    assert row["isActive"] is True
    assert row["designWorkshopId"] is None, (
        "the standard form belongs to every workshop, so attaching it to ONE would put its sittings "
        "into that workshop's report annexure"
    )


def test_it_is_filed_as_a_workshop_interview(seeded) -> None:
    """Stated rather than left unstated, because the report has to be able to place it.

    24 sections of questions put to an individual artisan about their craft, tools and household IS
    a workshop interview, and `questionnaire_kinds` files that under stage 6 — the artisan baseline,
    whose `artisanBaseline.interviewRef` already cites exactly this interview for the global
    questionnaire. Left unstated, the standard instrument would arrive in every designer's report as
    unfiled material.
    """
    assert seeded["row"]["kind"] == "WORKSHOP_INTERVIEW"


def test_it_carries_the_whole_instrument(seeded) -> None:
    """24 sections, 285 questions — the same tree the global questionnaire holds.

    The counts are spelled out rather than derived from the JSON. A test that re-read the file it is
    testing would agree with a seeder that wrote half of it, as long as it read the same half.
    """
    assert seeded["sections"] == 24
    assert seeded["questions"] == 285


def test_re_running_it_changes_nothing(seeded) -> None:
    """IDEMPOTENT, in the sense that actually matters.

    "Does not crash on a second run" would be satisfied by a seeder that created a second published
    questionnaire every time. What is asserted is that the second run reports NO writes at all and
    that there is exactly one published row under this title.
    """
    assert seeded["second_id"] == seeded["id"], "a re-run made a SECOND published questionnaire"
    assert seeded["second_counts"] == NOTHING_CHANGED, seeded["second_counts"]
    assert seeded["published"] == 1


def test_an_answered_question_is_never_reworded(seeded) -> None:
    """The rule the API enforces, enforced by the seeder too — and REPORTED rather than silent.

    A skipped row that nobody is told about is how an operator comes to believe a correction landed.
    The seeder counts it and prints which row it left alone.
    """
    assert seeded["third_counts"]["questions_left_alone"] == 1, seeded["third_counts"]
    assert seeded["prompt_after_reseed"] == seeded["reworded"], (
        "the seeder reworded a question that already carries an answer — '12' was given under the "
        "wording that was there, and this is the 'twelve looms becomes twelve weavers' failure "
        "arriving by a different door"
    )


def test_a_designer_sees_it_without_owning_it(seeded) -> None:
    """THE WHOLE POINT, end to end: the published form reaches a designer who has nothing.

    `_visible_questionnaire_where`'s fourth clause and the `isShared` arm of `/options` were both
    built for this row and had never had one to serve. A brand-new designer account with no
    questionnaires, no workshops and no grants must see it in the list AND be offered it in the
    attach dropdown.
    """
    client = seeded["client"]
    headers = {"Authorization": f"Bearer {seeded['newcomer_token']}"}
    listed = client.get("/api/questionnaires", headers=headers)
    assert listed.status_code == 200, listed.text
    rows = listed.json()["items"]
    assert any(row["id"] == seeded["id"] and row["isShared"] for row in rows), (
        "the published questionnaire is invisible to a designer who owns nothing — which is the "
        "exact report this feature answers"
    )
    # AND ABOVE EVERYTHING THAT IS NOT PUBLISHED. The standard form is by definition the OLDEST row
    # a designer can see — an administrator seeds it once and every form built afterwards is newer —
    # so under a plain `createdAt desc` it sorted LAST, below every draft they had ever made: a badge
    # on a page nobody opens. The list orders `isShared` first for that reason.
    #
    # THE CLAIM IS "NO UNSHARED ROW ABOVE IT", NOT "IT IS ROW ZERO", and the difference is what keeps
    # this honest on a shared database: a second published form (another seeder, another deployment's
    # leftovers) legitimately sorts among the shared ones by date, and pinning index 0 would make
    # this test fail for a reason that has nothing to do with the property being asserted.
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
