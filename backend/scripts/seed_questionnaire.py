import asyncio
import json
from pathlib import Path

from app.core.db import connect_db, db, disconnect_db
from app.services import cross_portal_codes

QUESTIONNAIRE_PATH = Path(__file__).resolve().parents[1] / "app" / "data" / "questionnaire_questions.json"


async def main() -> None:
    sections = json.loads(QUESTIONNAIRE_PATH.read_text(encoding="utf-8-sig"))
    await connect_db()
    try:
        count = 0
        for section_index, section in enumerate(sections, start=1):
            section_code = section["code"]
            section_title = section["title"]
            section_record = await db.questionnairesection.upsert(
                where={"code": section_code},
                data={
                    "create": {
                        "code": section_code,
                        "title": section_title,
                        "sortOrder": section_index,
                        "isActive": True,
                    },
                    "update": {
                        "title": section_title,
                        "sortOrder": section_index,
                        "isActive": True,
                    },
                },
            )
            for question in section["questions"]:
                existing = await db.questionnairequestion.find_first(
                    where={"sectionId": section_record.id, "sortOrder": question["sortOrder"]}
                )
                question_data = {
                    "sectionId": section_record.id,
                    "sectionCode": section_code,
                    "sectionTitle": section_title,
                    "sortOrder": question["sortOrder"],
                    "prompt": question["prompt"],
                    "isActive": True,
                }
                if existing:
                    await db.questionnairequestion.update(where={"id": existing.id}, data=question_data)
                else:
                    await db.questionnairequestion.create(
                        data={
                            "sectionCode": section_code,
                            "sectionTitle": section_title,
                            "sectionId": section_record.id,
                            "sortOrder": question["sortOrder"],
                            "prompt": question["prompt"],
                            "isActive": True,
                        },
                    )
                count += 1
        # The shared question codes (C26), derived from section and wording, for every row.
        coded = await cross_portal_codes.reconcile_question_codes(db)
        print(f"Seeded questionnaire questions: {count} (question codes changed: {coded})")
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
