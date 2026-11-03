import os
import uuid
from typing import Any

import pytest

from app.core.db import db
from scripts.seed_toolkit_questionnaires import (
    INSTRUMENTS,
    OBSERVED_COUNTS,
    WRITE_COUNTS,
    SeedRefused,
    load_corpus,
    seed_toolkit_questionnaires,
)

_URL = os.environ.get("DATABASE_URL", "")
_LOCAL = any(host in _URL for host in ("localhost", "127.0.0.1"))

pytestmark = [
    pytest.mark.skipif(not _LOCAL, reason="needs a LOCAL database"),
    pytest.mark.anyio,
]


@pytest.fixture(scope="module")
def anyio_backend():
    return "asyncio"


@pytest.fixture(scope="module")
async def seeded():
    from fastapi.testclient import TestClient

    from app.core.security import create_access_token
    from app.main import app

    facts: dict[str, Any] = {}
    made_questionnaire_ids: list[str] = []
    made_entry_ids: list[str] = []
    await db.connect()
    try:
        if await db.user.count(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}}) == 0:
            await db.user.create(
                data={
                    "email": f"seed-toolkit-{uuid.uuid4().hex[:8]}@example.org",
                    "name": "Seed Admin",
                    "role": "ADMIN",
                }
            )
        owner = await db.user.find_first(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}})

        facts["global_sections_before"] = await db.questionnairesection.count(where={"questionnaireId": None})
        facts["global_questions_before"] = await db.questionnairequestion.count(where={"questionnaireId": None})

        facts["first"] = {
            instrument.title: (questionnaire_id, counts)
            for instrument, questionnaire_id, counts in await seed_toolkit_questionnaires()
        }
        facts["global_sections_after"] = await db.questionnairesection.count(where={"questionnaireId": None})
        facts["global_questions_after"] = await db.questionnairequestion.count(where={"questionnaireId": None})

        facts["rows"] = {}
        for instrument in INSTRUMENTS:
            questionnaire_id, _counts = facts["first"][instrument.title]
            row = await db.questionnaire.find_unique(where={"id": questionnaire_id})
            facts["rows"][instrument.title] = {
                "id": row.id,
                "title": row.title,
                "isShared": row.isShared,
                "isActive": row.isActive,
                "kind": row.kind,
                "designWorkshopId": row.designWorkshopId,
                "sourceFilename": row.sourceFilename,
                "ownerRole": str(getattr(owner.role, "value", owner.role)),
                "sections": await db.questionnaireformsection.count(
                    where={"questionnaireId": row.id}
                ),
                "questions": await db.questionnaireformquestion.count(
                    where={"section": {"is": {"questionnaireId": row.id}}}
                ),
                "rowsUnderThisTitle": await db.questionnaire.count(where={"title": row.title}),
            }

        facts["second"] = {
            instrument.title: (questionnaire_id, counts)
            for instrument, questionnaire_id, counts in await seed_toolkit_questionnaires()
        }

        target = INSTRUMENTS[1]
        target_id = facts["first"][target.title][0]
        section = await db.questionnaireformsection.find_first(
            where={"questionnaireId": target_id}, order={"sortOrder": "asc"}
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
                    "questionnaireId": target_id,
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
            facts["third"] = {
                instrument.title: counts
                for instrument, _id, counts in await seed_toolkit_questionnaires()
            }
            after = await db.questionnaireformquestion.find_unique(where={"id": question.id})
            facts["prompt_after_reseed"] = after.prompt
            facts["reworded"] = reworded
            facts["answered_target_title"] = target.title
        finally:
            if entry is not None:
                await db.questionnaireformentry.delete(where={"id": entry.id})
            await db.questionnaireformquestion.update(
                where={"id": question.id}, data={"prompt": original_prompt}
            )

        newcomer = await db.user.create(
            data={
                "email": f"toolkit-newcomer-{uuid.uuid4().hex[:8]}@example.org",
                "name": "Brand New Designer",
                "role": "DESIGNER",
            }
        )
        facts["newcomer_id"] = newcomer.id
        facts["newcomer_token"] = create_access_token(subject=newcomer.id)

        facts["first_question"] = {}
        for instrument in INSTRUMENTS:
            questionnaire_id = facts["first"][instrument.title][0]
            head = await db.questionnaireformsection.find_first(
                where={"questionnaireId": questionnaire_id}, order={"sortOrder": "asc"}
            )
            first_question = await db.questionnaireformquestion.find_first(
                where={"sectionId": head.id}, order={"sortOrder": "asc"}
            )
            facts["first_question"][instrument.title] = first_question.id
    finally:
        await db.disconnect()

    with TestClient(app) as client:
        facts["client"] = client
        facts["made_questionnaire_ids"] = made_questionnaire_ids
        facts["made_entry_ids"] = made_entry_ids
        yield facts

    await db.connect()
    try:
        for entry_id in made_entry_ids:
            await db.questionnaireformentry.delete(where={"id": entry_id})
        for questionnaire_id in made_questionnaire_ids:
            await db.questionnaire.delete(where={"id": questionnaire_id})
    finally:
        await db.disconnect()



@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_the_instrument_is_published_and_unattached(seeded, instrument) -> None:
    row = seeded["rows"][instrument.title]
    assert row["isShared"] is True, "the whole point of the row is that it is published"
    assert row["isActive"] is True, (
        "`visible_questionnaire_where`'s fourth clause is the PAIR {isShared, isActive}, so a "
        "published-but-inactive row is invisible to every designer"
    )
    assert row["designWorkshopId"] is None
    assert row["kind"] == "WORKSHOP_INTERVIEW", (
        "`kind` is a routing instruction and NULL routes nowhere — the instrument would arrive in "
        "every designer's report as unfiled material"
    )
    assert row["sourceFilename"] == f"app/data/{instrument.corpus}", (
        "the row must say which corpus built it, or the two are distinguishable only by counting "
        "their questions"
    )
    assert row["ownerRole"] in ("ADMIN", "MASTER_ADMIN")


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_the_instrument_carries_its_whole_corpus(seeded, instrument) -> None:
    row = seeded["rows"][instrument.title]
    assert (row["sections"], row["questions"]) == (
        instrument.expected_sections,
        instrument.expected_questions,
    )


def test_the_two_rows_are_tellable_apart_at_a_glance() -> None:
    titles = [instrument.title for instrument in INSTRUMENTS]
    assert len(set(titles)) == 2, "two instruments sharing a title is one instrument"
    assert titles[0].startswith("2nd Craft Toolkit Workshop")
    assert titles[1].startswith("3rd Craft Toolkit Workshop")



def test_the_global_artisan_instrument_is_untouched(seeded) -> None:
    assert seeded["global_sections_after"] == seeded["global_sections_before"]
    assert seeded["global_questions_after"] == seeded["global_questions_before"]



@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_re_running_it_writes_nothing(seeded, instrument) -> None:
    first_id, _first_counts = seeded["first"][instrument.title]
    second_id, second_counts = seeded["second"][instrument.title]
    assert second_id == first_id, "a re-run published a SECOND copy of this instrument"
    assert {key: second_counts[key] for key in WRITE_COUNTS} == dict.fromkeys(WRITE_COUNTS, 0), (
        second_counts
    )
    assert {key: second_counts[key] for key in OBSERVED_COUNTS} == dict.fromkeys(
        OBSERVED_COUNTS, 0
    ), (
        "the second run found rows the corpus does not describe — on a row this script just created "
        "there should be none, so either the seeder wrote something the corpus does not contain or "
        "a previous run of a DIFFERENT corpus adopted this title"
    )
    assert seeded["rows"][instrument.title]["rowsUnderThisTitle"] == 1



def test_an_answered_question_is_never_reworded(seeded) -> None:
    counts = seeded["third"][seeded["answered_target_title"]]
    assert counts["questions_left_alone"] == 1, counts
    assert counts["questions_updated"] == 0, (
        "the seeder rewrote something on the run where its only outstanding change was the "
        "answered question"
    )
    assert seeded["prompt_after_reseed"] == seeded["reworded"], (
        "the seeder reworded a question that already carries an answer — '12' was given under the "
        "wording that was there, and this is the 'twelve looms becomes twelve weavers' failure "
        "arriving by a different door"
    )



def test_a_designer_who_owns_nothing_sees_both(seeded) -> None:
    client = seeded["client"]
    headers = {"Authorization": f"Bearer {seeded['newcomer_token']}"}

    listed = client.get("/api/questionnaires", headers=headers)
    assert listed.status_code == 200, listed.text
    rows = listed.json()["items"]
    by_id = {row["id"]: row for row in rows}
    options = client.get("/api/questionnaires/options", headers=headers)
    assert options.status_code == 200, options.text
    option_ids = {option["id"] for option in options.json()}

    for instrument in INSTRUMENTS:
        questionnaire_id = seeded["first"][instrument.title][0]
        assert questionnaire_id in by_id, (
            f"{instrument.title!r} is invisible to a designer who owns nothing — which is the exact "
            f"report `Questionnaire.isShared` was added to answer"
        )
        assert by_id[questionnaire_id]["isShared"] is True
        assert questionnaire_id in option_ids, (
            f"{instrument.title!r} is not offered in the attach dropdown"
        )


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_a_designer_can_use_it_as_is(seeded, instrument) -> None:
    client = seeded["client"]
    headers = {"Authorization": f"Bearer {seeded['newcomer_token']}"}
    questionnaire_id = seeded["first"][instrument.title][0]

    started = client.post(
        f"/api/questionnaires/{questionnaire_id}/entries",
        headers=headers,
        json={"title": "Sitting on a form I do not own", "respondentName": "Test respondent"},
    )
    assert started.status_code == 201, started.text
    entry_id = started.json()["id"]
    seeded["made_entry_ids"].append(entry_id)

    saved = client.put(
        f"/api/questionnaires/{questionnaire_id}/entries/{entry_id}/answers",
        headers=headers,
        json={
            "answers": [
                {
                    "questionId": seeded["first_question"][instrument.title],
                    "answerText": "Recorded by a designer who owns neither the form nor a workshop.",
                }
            ]
        },
    )
    assert saved.status_code == 200, saved.text
    assert len(saved.json()["answers"]) == 1


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_a_designer_can_reuse_it_to_edit(seeded, instrument) -> None:
    client = seeded["client"]
    headers = {"Authorization": f"Bearer {seeded['newcomer_token']}"}
    questionnaire_id = seeded["first"][instrument.title][0]

    copied = client.post(
        f"/api/questionnaires/{questionnaire_id}/reuse",
        headers=headers,
        json={"title": f"My copy of {instrument.title}"[:220]},
    )
    assert copied.status_code == 201, copied.text
    body = copied.json()
    assert body["sourceQuestionnaireId"] == questionnaire_id
    copy = body["questionnaire"]
    seeded["made_questionnaire_ids"].append(copy["id"])

    assert copy["ownerId"] == seeded["newcomer_id"], "the copy belongs to the designer who made it"
    assert copy["isShared"] is False, (
        "a designer's private copy must not be published to everybody by inheriting the flag"
    )
    assert len(copy["sections"]) == instrument.expected_sections
    assert (
        sum(len(section["questions"]) for section in copy["sections"])
        == instrument.expected_questions
    )
    assert copy["entries"] == [], "a reused copy carries questions and no fieldwork"

    question_id = copy["sections"][0]["questions"][0]["id"]
    edited = client.patch(
        f"/api/questionnaires/{copy['id']}/questions/{question_id}",
        headers=headers,
        json={"prompt": "My own wording of the first question."},
    )
    assert edited.status_code == 200, edited.text



def test_a_corpus_that_is_not_what_it_claims_refuses() -> None:
    from dataclasses import replace

    swapped = replace(INSTRUMENTS[0], corpus=INSTRUMENTS[1].corpus)
    with pytest.raises(SeedRefused) as refusal:
        load_corpus(swapped)
    assert "22 sections / 81 questions" in str(refusal.value)
    assert "24 / 284" in str(refusal.value)


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_the_vendored_corpus_is_the_shape_the_seeder_promises(instrument) -> None:
    sections = load_corpus(instrument)
    assert len(sections) == instrument.expected_sections
    assert (
        sum(len(section["questions"]) for section in sections) == instrument.expected_questions
    )
