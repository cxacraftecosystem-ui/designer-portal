import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.core.db import connect_db, db, disconnect_db
from app.services import cross_portal_codes

DATA_DIR = Path(__file__).resolve().parents[1] / "app" / "data"


class SeedRefused(RuntimeError):
    pass


@dataclass(frozen=True)
class Instrument:
    title: str
    description: str
    corpus: str
    expected_sections: int
    expected_questions: int


INSTRUMENTS: tuple[Instrument, ...] = (
    Instrument(
        title="2nd Craft Toolkit Workshop - artisan interview",
        description=(
            "The instrument used at the 2nd Craft Toolkit Workshop: 24 sections (RESP, A-W) and "
            "284 questions, from \"2nd Workshop_Interview Questions.docx\". Published for every "
            "designer - record a sitting against it as it stands, or reuse it into a copy of your "
            "own and edit that."
        ),
        corpus="craft_toolkit_workshop_2_questions.json",
        expected_sections=24,
        expected_questions=284,
    ),
    Instrument(
        title="3rd Craft Toolkit Workshop - artisan interview",
        description=(
            "The instrument used from the 3rd Craft Toolkit Workshop on: 22 sections (A-V) and 81 "
            "questions. Section V maps the artisan's network - designers, suppliers, buyers, "
            "digital, financial and institutional connections - and is answered as prose, like "
            "every other question here. Published for every designer - record a sitting against it "
            "as it stands, or reuse it into a copy of your own and edit that."
        ),
        corpus="craft_toolkit_workshop_3_questions.json",
        expected_sections=22,
        expected_questions=81,
    ),
)

WRITE_COUNTS = (
    "sections_created",
    "sections_updated",
    "questions_created",
    "questions_updated",
    "republished",
)

OBSERVED_COUNTS = (
    "questions_left_alone",
    "sections_not_in_corpus",
    "questions_not_in_corpus",
)


def load_corpus(instrument: Instrument) -> list[dict[str, Any]]:
    path = DATA_DIR / instrument.corpus
    if not path.exists():
        raise SeedRefused(
            f"{instrument.corpus} is missing from {DATA_DIR}. It is vendored from the field "
            f"repository's backend/app/data/; copy it back rather than pointing this script at "
            f"another checkout, which would break inside the container."
        )
    sections = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(sections, list) or not sections:
        raise SeedRefused(f"{instrument.corpus}: expected a non-empty list of sections")

    seen_codes: set[str] = set()
    total_questions = 0
    for index, section in enumerate(sections, start=1):
        code = str(section.get("code", "")).strip()
        title = str(section.get("title", "")).strip()
        if not code or not title:
            raise SeedRefused(f"{instrument.corpus}: section #{index} is missing a code or a title")
        if code in seen_codes:
            raise SeedRefused(
                f"{instrument.corpus}: section code {code!r} appears twice. Codes are unique WITHIN "
                f"a questionnaire (@@unique([questionnaireId, code])), so the second one would "
                f"either collide or silently overwrite the first."
            )
        seen_codes.add(code)

        questions = section.get("questions") or []
        orders = [int(question["sortOrder"]) for question in questions]
        if sorted(orders) != list(range(1, len(orders) + 1)):
            raise SeedRefused(
                f"{instrument.corpus}: section {code} has sortOrders {sorted(orders)}; they must be "
                f"contiguous from 1. (sectionId, sortOrder) is this seeder's identity for a "
                f"question, so a gap or a repeat makes a re-run create duplicates instead of "
                f"matching what it wrote last time."
            )
        for question in questions:
            if not str(question.get("prompt", "")).strip():
                raise SeedRefused(
                    f"{instrument.corpus}: section {code} #{question.get('sortOrder')} has no "
                    f"prompt. A question with a blank prompt is a blank box on the answer screen."
                )
        total_questions += len(questions)

    if (len(sections), total_questions) != (
        instrument.expected_sections,
        instrument.expected_questions,
    ):
        raise SeedRefused(
            f"{instrument.corpus} holds {len(sections)} sections / {total_questions} questions, but "
            f"{instrument.title!r} is declared as {instrument.expected_sections} / "
            f"{instrument.expected_questions}. Either the corpus was replaced deliberately - in "
            f"which case update expected_sections/expected_questions in INSTRUMENTS in the same "
            f"commit - or the wrong file was copied in."
        )
    return sections


async def _owner_id() -> str:
    owner = await db.user.find_first(
        where={"role": "MASTER_ADMIN"}, order={"createdAt": "asc"}
    ) or await db.user.find_first(where={"role": "ADMIN"}, order={"createdAt": "asc"})
    if owner is None:
        raise SystemExit(
            "No ADMIN or MASTER_ADMIN account exists, and `Questionnaire.ownerId` is NOT NULL. "
            "Run `python scripts/seed_admin.py` first, then re-run this."
        )
    return owner.id


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
    if not question_ids:
        return set()
    answers = await db.questionnaireformanswer.find_many(where={"questionId": {"in": question_ids}})
    return {answer.questionId for answer in answers}


async def _seed_one(instrument: Instrument, sections: list[dict[str, Any]], owner_id: str) -> tuple[str, dict[str, int]]:
    counts = dict.fromkeys(WRITE_COUNTS + OBSERVED_COUNTS, 0)

    existing = await db.questionnaire.find_first(
        where={"isShared": True, "title": instrument.title}
    )
    if existing is None:
        existing = await db.questionnaire.find_first(where={"title": instrument.title})

    if existing is None:
        record = await db.questionnaire.create(
            data={
                "title": instrument.title,
                "description": instrument.description,
                "ownerId": owner_id,
                "isShared": True,
                "isActive": True,
                "designWorkshopId": None,
                "kind": "WORKSHOP_INTERVIEW",
                "sourceFilename": f"app/data/{instrument.corpus}",
            }
        )
        print(f"  created {record.id} - published, owned by the admin account, attached to no workshop")
    else:
        was_withdrawn = not existing.isShared or not existing.isActive
        record = await db.questionnaire.update(
            where={"id": existing.id},
            data={
                "description": instrument.description,
                "isShared": True,
                "isActive": True,
                "kind": "WORKSHOP_INTERVIEW",
                "sourceFilename": f"app/data/{instrument.corpus}",
            },
        )
        if was_withdrawn:
            counts["republished"] += 1
            print(
                f"  RE-PUBLISHED {record.id} - it was withdrawn "
                f"(isShared={existing.isShared}, isActive={existing.isActive}) and is now visible to "
                f"every designer again. If that was deliberate, withdraw it through "
                f"PATCH /api/questionnaires/{{id}} rather than re-running this."
            )
        else:
            print(f"  refreshing {record.id}")

    stored_sections = await db.questionnaireformsection.find_many(
        where={"questionnaireId": record.id}
    )
    section_by_code = {row.code: row for row in stored_sections}
    corpus_codes = {str(section["code"]).strip() for section in sections}

    for index, section in enumerate(sections, start=1):
        code = str(section["code"]).strip()
        title = str(section["title"]).strip()
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
        corpus_orders = {int(question["sortOrder"]) for question in section["questions"]}

        for question in section["questions"]:
            order = int(question["sortOrder"])
            prompt = str(question["prompt"]).strip()
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
                    f"    left alone (has answers): {code} #{order} - "
                    f"{stored_question.prompt[:60]!r} is NOT being reworded to {prompt[:60]!r}"
                )
                continue
            await db.questionnaireformquestion.update(
                where={"id": stored_question.id},
                data={"prompt": prompt, "isActive": True},
            )
            counts["questions_updated"] += 1

        for stored_question in stored_questions:
            if stored_question.sortOrder not in corpus_orders:
                counts["questions_not_in_corpus"] += 1
                print(
                    f"    kept, not in the corpus: {code} #{stored_question.sortOrder} - "
                    f"{stored_question.prompt[:60]!r}. NOT deactivated; retire it through the "
                    f"builder if it should stop being offered."
                )

    for row in stored_sections:
        if row.code not in corpus_codes:
            counts["sections_not_in_corpus"] += 1
            print(
                f"    kept, not in the corpus: section {row.code} - {row.title[:60]!r} at "
                f"sortOrder {row.sortOrder}. NOT deactivated and NOT moved."
            )

    await cross_portal_codes.reconcile_form_question_codes(db, questionnaire_id=record.id)
    return record.id, counts


async def seed_toolkit_questionnaires() -> list[tuple[Instrument, str, dict[str, int]]]:
    loaded = [(instrument, load_corpus(instrument)) for instrument in INSTRUMENTS]
    owner_id = await _owner_id()
    owner = await db.user.find_unique(where={"id": owner_id})

    owner_role = str(getattr(owner.role, "value", owner.role))

    print("PLAN")
    print(
        f"  owner for both rows: {owner.email} ({owner_role}) - bookkeeping, not authority; "
        f"`isShared` is what makes them everybody's"
    )
    for instrument, sections in loaded:
        questions = sum(len(section.get("questions") or []) for section in sections)
        existing = await db.questionnaire.find_first(where={"title": instrument.title})
        state = (
            f"refresh {existing.id} (isShared={existing.isShared}, isActive={existing.isActive})"
            if existing
            else "create"
        )
        print(f"  {instrument.title}")
        print(f"    corpus  app/data/{instrument.corpus} - {len(sections)} sections, {questions} questions")
        print(f"    action  {state}; published to every designer, attached to no workshop, kind WORKSHOP_INTERVIEW")
    print()

    results: list[tuple[Instrument, str, dict[str, int]]] = []
    for instrument, sections in loaded:
        print(instrument.title)
        questionnaire_id, counts = await _seed_one(instrument, sections, owner_id)
        results.append((instrument, questionnaire_id, counts))
    return results


def print_summary(results: list[tuple[Instrument, str, dict[str, int]]]) -> None:
    print()
    print("SUMMARY")
    for instrument, questionnaire_id, counts in results:
        print(f"  {instrument.title} -> {questionnaire_id}")
        print(
            "    sections:  {sections_created} created, {sections_updated} updated, "
            "{sections_not_in_corpus} kept but absent from the corpus".format(**counts)
        )
        print(
            "    questions: {questions_created} created, {questions_updated} updated, "
            "{questions_left_alone} LEFT ALONE (answered), {questions_not_in_corpus} kept but "
            "absent from the corpus".format(**counts)
        )
        if counts["republished"]:
            print("    RE-PUBLISHED a row that had been withdrawn - see the line above")
    print()
    print(
        "NOTE: `scripts/seed_shared_questionnaire.py` publishes the SAME 2nd-workshop corpus as\n"
        '      "Standard artisan questionnaire" (285 questions - the 284 above plus a local\n'
        '      "Date of Interview"). A deployment that runs both publishes that instrument twice,\n'
        "      under two names. Nothing here withdraws the other row; do that deliberately through\n"
        "      PATCH /api/questionnaires/{id} with isShared=false, AFTER this has run."
    )


async def main() -> None:
    await connect_db()
    try:
        print_summary(await seed_toolkit_questionnaires())
    finally:
        await disconnect_db()


if __name__ == "__main__":
    asyncio.run(main())
