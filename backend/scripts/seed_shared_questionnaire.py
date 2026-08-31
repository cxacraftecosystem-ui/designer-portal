"""Publish the standard artisan instrument as a `Questionnaire` every designer can use.

WHAT WAS MISSING, AND WHY THE PRODUCT LOOKED EMPTY OUT OF THE BOX
================================================================

`Questionnaire.isShared` was added on 2026-08-28 to answer the owner's report — *"The default
questionnaire that was previously discussed/configured is still not visible to designers"* — and
everything it needs was built: the column, the fourth clause of `_visible_questionnaire_where`, the
`isShared` arm of `GET /questionnaires/options`, the "Standard form" badge on both clients, and the
admin-only PATCH that sets it.

**And nothing anywhere ever set it.** Not a route (the PATCH can, but only on a row that already
exists and only if an admin knows to press it), not a script, not a test fixture, not a migration.
So on a fresh deployment `isShared = true` matched zero rows: `/questionnaires` was empty for every
new designer, `GET /questionnaires/options` offered them nothing but their own uploads, and the
attach dropdown on the design-workshop screen was blank until the designer had built a form
themselves. The feature was complete except for having anything to show.

`scripts/seed_questionnaire.py` beside this file looks like it fills that gap and does not. It seeds
`QuestionnaireSection` / `QuestionnaireQuestion` — 24 sections and 285 questions of the ONE GLOBAL
ARTISAN QUESTIONNAIRE, reachable at `/questionnaire` SINGULAR — which is a different set of tables
with a different API, a different permission model and a different screen. The two are deliberately
separate all the way down (see the block comment above `model Questionnaire` in schema.prisma); the
word they share is the whole reason this file has to say so.

WHAT THIS SCRIPT DOES
=====================

Writes ONE `Questionnaire` row with `isShared = true`, carrying the SAME instrument — the same 24
sections and 285 questions, read from the same `app/data/questionnaire_questions.json` — as the
custom-questionnaire tables understand it. A designer then sees it in their list (badged "Standard
form"), can select it from the attach dropdown, and can record sittings against it exactly as they
would against a form they built. Nothing about the global questionnaire changes; this is a second,
parallel copy that lives where designers actually work.

IT IS ATTACHED TO NO WORKSHOP, ON PURPOSE. `designWorkshopId` stays NULL because the standard form
belongs to every workshop, and attaching it to one would put its sittings into that workshop's
report annexure. `isShared` is the column that publishes it; "unattached" is not, and reading it as
publication is one of the two accidents `Questionnaire.isShared`'s own comment rejects.

IDEMPOTENT, AND SAFE TO RE-RUN
==============================

Keyed on `(isShared = true, title = TITLE)` — the row this script published, found by what it is
rather than by an id nobody records. Re-running it:

  * does NOT create a second copy;
  * REFRESHES the wording of every section and question that has not been answered, so a correction
    to the JSON reaches a deployment that already ran this;
  * NEVER touches a question that has answers recorded against it. That is not politeness, it is the
    same rule the API enforces: rewording an answered question would leave "12" sitting under "How
    many weavers work with you?" when it was given for "How many looms do you own?", and
    `QuestionnaireFormQuestion.supersededById` exists precisely so that cannot happen. Where the
    wording has changed under an answered question this script leaves the row alone and SAYS SO on
    stdout rather than silently skipping it;
  * never deletes anything. A section removed from the JSON is left standing, because it may hold a
    fortnight of somebody's fieldwork.

WHO OWNS IT
===========

`Questionnaire.ownerId` is NOT NULL, so the row needs an account, and it takes the master admin —
or, failing that, the oldest ADMIN. Ownership here is bookkeeping rather than authority: `isShared`
is what makes the form everybody's, and `_require_owner` only governs REWORDING it, which is
correctly an administrator's act. If there is no admin account at all the script refuses and says
so, rather than hanging the standard instrument off whichever user happened to be first.

    cd backend && PYTHONUTF8=1 .venv/Scripts/python.exe -m scripts.seed_shared_questionnaire
"""

import asyncio
import json
from pathlib import Path
from typing import Any

from app.core.db import connect_db, db, disconnect_db

QUESTIONNAIRE_PATH = Path(__file__).resolve().parents[1] / "app" / "data" / "questionnaire_questions.json"

#: The published row's title, and the key this script finds it by on a re-run.
#:
#: A CONSTANT AND NOT A COMMAND-LINE ARGUMENT, because it is an identity rather than a preference: a
#: deployment that ran this with one title and re-ran it with another would end up with two published
#: standard forms and no way for a designer to tell which is current. Renaming it deliberately is an
#: edit here plus a rename of the existing row, in that order.
TITLE = "Standard artisan questionnaire"

DESCRIPTION = (
    "The standard instrument, published for every designer. Record a sitting against it as you "
    "would your own form. To change the questions, reuse it into a copy of your own."
)


def _load_sections() -> list[dict[str, Any]]:
    """The instrument, from the same JSON `seed_questionnaire.py` reads.

    ``utf-8-sig`` because the file has been re-saved from Excel more than once and a BOM in front of
    the opening bracket is a ``json.JSONDecodeError`` on character 0 — the same encoding the other
    seeder opens it with, for the same reason.
    """
    return json.loads(QUESTIONNAIRE_PATH.read_text(encoding="utf-8-sig"))


async def _owner_id() -> str:
    """The master admin, or the oldest admin. Raises with an actionable sentence if there is neither."""
    owner = await db.user.find_first(
        where={"role": "MASTER_ADMIN"}, order={"createdAt": "asc"}
    ) or await db.user.find_first(where={"role": "ADMIN"}, order={"createdAt": "asc"})
    if owner is None:
        raise SystemExit(
            "No ADMIN or MASTER_ADMIN account exists, and `Questionnaire.ownerId` is NOT NULL. "
            "Run `python -m scripts.seed_admin` first, then re-run this."
        )
    return owner.id


async def seed_shared_questionnaire() -> tuple[str, dict[str, int]]:
    """Publish (or refresh) the standard questionnaire. Returns ``(id, counts)``.

    Returned rather than only printed so a test can drive this function directly and assert the
    second run changes nothing — which is the property "idempotent" actually means, and the one a
    seeder that merely does not crash twice does not have.
    """
    sections = _load_sections()
    counts = {
        "sections_created": 0,
        "sections_updated": 0,
        "questions_created": 0,
        "questions_updated": 0,
        "questions_left_alone": 0,
    }

    existing = await db.questionnaire.find_first(where={"isShared": True, "title": TITLE})
    if existing is None:
        record = await db.questionnaire.create(
            data={
                "title": TITLE,
                "description": DESCRIPTION,
                "ownerId": await _owner_id(),
                # PUBLISHED. This is the flag the whole feature was waiting on, and the one line in
                # this repository that has ever set it.
                "isShared": True,
                # NOT attached to a workshop — see the module docstring.
                "designWorkshopId": None,
                # WORKSHOP_INTERVIEW, because that is what this instrument IS: 24 sections of
                # questions put to an individual artisan about their craft, their tools and their
                # household. `questionnaire_kinds` files that under stage 6, the artisan baseline,
                # which is the stage whose `artisanBaseline.interviewRef` already cites exactly this
                # interview for the global questionnaire. Stating it here rather than leaving the
                # published form unstated is the difference between the standard instrument landing
                # in the right part of every designer's report and landing in the unfiled remainder.
                "kind": "WORKSHOP_INTERVIEW",
                # NULL, because there is no spreadsheet behind this row. Naming one would send a
                # designer off to edit a file that does not exist.
                "sourceFilename": None,
            }
        )
    else:
        record = await db.questionnaire.update(
            where={"id": existing.id},
            data={
                "description": DESCRIPTION,
                # Re-asserted rather than assumed: an admin may have withdrawn publication, and a
                # re-run of the seeder is the operator saying "publish the standard form", which is
                # the same instruction it was the first time.
                "isShared": True,
                "kind": "WORKSHOP_INTERVIEW",
            },
        )

    stored_sections = await db.questionnaireformsection.find_many(
        where={"questionnaireId": record.id}
    )
    section_by_code = {row.code: row for row in stored_sections}

    for index, section in enumerate(sections, start=1):
        code = str(section["code"])
        title = str(section["title"])
        stored = section_by_code.get(code)
        if stored is None:
            stored = await db.questionnaireformsection.create(
                data={
                    "questionnaireId": record.id,
                    "code": code,
                    "title": title,
                    "sortOrder": index,
                }
            )
            counts["sections_created"] += 1
        elif stored.title != title or stored.sortOrder != index or not stored.isActive:
            stored = await db.questionnaireformsection.update(
                where={"id": stored.id},
                data={"title": title, "sortOrder": index, "isActive": True},
            )
            counts["sections_updated"] += 1

        stored_questions = await db.questionnaireformquestion.find_many(
            where={"sectionId": stored.id}
        )
        # MATCHED ON ``sortOrder`` WITHIN THE SECTION, which is what the JSON carries and what
        # `seed_questionnaire.py` matches on for the global tables. Matching on the PROMPT instead
        # would make every correction to a wording look like a brand-new question and leave the old
        # one behind, which is how a 285-question instrument becomes a 400-question one over three
        # re-runs.
        question_by_order = {row.sortOrder: row for row in stored_questions}
        answered = await _answered_question_ids([row.id for row in stored_questions])

        for question in section["questions"]:
            order = int(question["sortOrder"])
            prompt = str(question["prompt"])
            stored_question = question_by_order.get(order)
            if stored_question is None:
                await db.questionnaireformquestion.create(
                    data={
                        "sectionId": stored.id,
                        "prompt": prompt,
                        # The global instrument marks nothing required, and neither does this copy.
                        # Required-ness here would print "[Not recorded]" in a ministry report for
                        # every artisan who did not answer a question nobody told them was
                        # mandatory — see the report annexure's own rule for that marker.
                        "isRequired": False,
                        "sortOrder": order,
                    }
                )
                counts["questions_created"] += 1
                continue
            if stored_question.prompt == prompt and stored_question.isActive:
                continue
            if stored_question.id in answered:
                # THE ONE THING THIS SCRIPT WILL NOT DO. See the module docstring: an answer belongs
                # to the wording it was given under, and a seeder is exactly the wrong place to
                # decide otherwise. Reported rather than skipped in silence, so an operator who
                # changed the JSON knows which rows did not move and can supersede them through the
                # API, which is the door built for it.
                counts["questions_left_alone"] += 1
                print(
                    f"  left alone (has answers): {code} #{order} — "
                    f"{stored_question.prompt[:60]!r} is not being reworded to {prompt[:60]!r}"
                )
                continue
            await db.questionnaireformquestion.update(
                where={"id": stored_question.id},
                data={"prompt": prompt, "isActive": True},
            )
            counts["questions_updated"] += 1

    return record.id, counts


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
    """Which of ``question_ids`` already carry an answer.

    One query for the whole section rather than one per question: this runs 24 times over 285
    questions, and a per-question existence check would be 285 round trips to a database that on the
    deployed system is in another region.
    """
    if not question_ids:
        return set()
    answers = await db.questionnaireformanswer.find_many(
        where={"questionId": {"in": question_ids}}
    )
    return {answer.questionId for answer in answers}


async def main() -> None:
    await connect_db()
    try:
        questionnaire_id, counts = await seed_shared_questionnaire()
        print(f"Published “{TITLE}” as questionnaire {questionnaire_id}")
        print(
            "  sections: {sections_created} created, {sections_updated} updated · "
            "questions: {questions_created} created, {questions_updated} updated, "
            "{questions_left_alone} left alone".format(**counts)
        )
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
