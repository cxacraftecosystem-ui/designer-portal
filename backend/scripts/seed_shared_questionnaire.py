import asyncio
import json
from pathlib import Path
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services import cross_portal_codes

QUESTIONNAIRE_PATH = Path(__file__).resolve().parents[1] / "app" / "data" / "questionnaire_questions.json"

TITLE = "Standard artisan questionnaire"

DESCRIPTION = (
    "The standard instrument, published for every designer. Record a sitting against it as you "
    "would your own form. To change the questions, reuse it into a copy of your own."
)


def _load_sections() -> list[dict[str, Any]]:
    return json.loads(QUESTIONNAIRE_PATH.read_text(encoding="utf-8-sig"))


async def _owner_id() -> str:
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
                "isShared": True,
                "designWorkshopId": None,
                "kind": "WORKSHOP_INTERVIEW",
                "sourceFilename": None,
            }
        )
    else:
        record = await db.questionnaire.update(
            where={"id": existing.id},
            data={
                "description": DESCRIPTION,
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
                        "isRequired": False,
                        "sortOrder": order,
                    }
                )
                counts["questions_created"] += 1
                continue
            if stored_question.prompt == prompt and stored_question.isActive:
                continue
            if stored_question.id in answered:
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

    await cross_portal_codes.reconcile_form_question_codes(db, questionnaire_id=record.id)
    return record.id, counts


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
    if not question_ids:
        return set()
    answers = await db.questionnaireformanswer.find_many(
        where={"questionId": {"in": question_ids}}
    )
    return {answer.questionId for answer in answers}


async def main() -> None:
    print(
        "NOTE: `scripts/seed_toolkit_questionnaires.py` publishes this SAME 2nd-workshop corpus"
    )
    print(
        '      as "2nd Craft Toolkit Workshop - artisan interview" (284 questions: these 285'
    )
    print('      without the locally added RESP "Date of Interview"), together with the 3rd')
    print("      workshop's. Run ONE of the two. Running both publishes the 2nd workshop twice,")
    print("      under two titles, and the duplicate can then only be withdrawn by an admin")
    print("      clearing `isShared` on it.")
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
