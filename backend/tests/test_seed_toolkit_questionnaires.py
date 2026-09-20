"""Both toolkit-workshop instruments are published, complete, idempotent, and usable by a designer.

WHAT THIS IS ABOUT. The owner asked for *"the questionnaires from 2nd and 3rd toolkit workshop into
designer app for all designers, to use in case they want to edit or use as is"*.
``scripts/seed_toolkit_questionnaires.py`` publishes those two instruments as ``Questionnaire`` rows
with ``isShared = true``; "edit" and "use as is" were already built and are VERIFIED here rather than
rebuilt. The properties below are the ones that make the script safe to run on a database already in
use, and the ones that make the feature real rather than merely present:

1. **BOTH INSTRUMENTS ARRIVE WHOLE.** 24 sections / 284 questions and 22 sections / 81 questions. A
   published row with half a corpus under it looks identical, on the list screen, to a correct one.
2. **THEY ARE THE DESIGNER-AUTHORED FAMILY AND THE GLOBAL INSTRUMENT IS UNTOUCHED.**
   ``QuestionnaireSection`` / ``QuestionnaireQuestion`` — the ONE global artisan questionnaire at
   ``/questionnaire`` SINGULAR, which every researcher answers — must have exactly as many rows after
   the seed as before it. Seeding two workshops' questions in there would change what the whole
   country is asked, which is not what was requested.
3. **IDEMPOTENT.** Not "does not crash twice": the second run must write NOTHING, and there must be
   exactly one row per title. A seeder that re-created the tree would give every deployment two more
   published forms per run and no way for a designer to tell which is current.
4. **AN ANSWERED QUESTION IS NEVER REWORDED.** An answer belongs to the wording it was given under —
   the reason ``QuestionnaireFormQuestion.supersededById`` exists — and a script that rewrote prompts
   in place would do by the back door what the API refuses at the front.
5. **A DESIGNER WHO OWNS NOTHING CAN SEE THEM, ANSWER THEM AND REUSE THEM.** The two halves of "to
   edit or use as is", end to end over HTTP, from an account with no questionnaires, no workshops and
   no grants.

── HOW THIS FILE IS ARRANGED, AND WHY IT LOOKS TOP-HEAVY ────────────────────────────────────────

Every database call happens in ONE module-scoped fixture, before the ``TestClient`` exists, and the
tests assert over what it recorded. That is this suite's convention and it is not stylistic: the
Prisma client is a module-level singleton shared with the running app and the client's lifespan
connects it, so a test that calls ``db.connect()`` raises ``AlreadyConnectedError`` and one that
calls ``db.disconnect()`` pulls the connection out from under every test after it. See
``test_seed_shared_questionnaire.py``, whose docstring records thirteen tests failing for exactly
that reason.

It also keeps the seeder's cost honest: seeding both instruments is ~400 row writes, and a
function-scoped fixture would pay it per test.

Needs Postgres. Skips itself when ``DATABASE_URL`` is not local, exactly as the other questionnaire
suites do.
"""

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
    """Run the seeder twice, drive the answered-question rule, and hand back what happened.

    ALL THE DATABASE WORK IS HERE — see the module docstring for why it cannot be spread across the
    tests. What comes back is a plain dict of facts, so each test below asserts one property of one
    run rather than re-running a ~400-write seeder eight times.

    THE TWO PUBLISHED ROWS ARE NOT TORN DOWN. They are a repository-wide fixture rather than this
    test's private data — publishing them is exactly what the script is for — and deleting them
    would take both instruments away from a developer's local database every time the suite ran.
    What IS undone: the deliberate rewording in step 3, which exists only to drive the rule, and the
    reused copy and sitting created in step 5, which are this test's own litter (a reused copy of the
    2nd-workshop form is 24 sections and 284 questions; leaving one behind per run is not a fixture,
    it is a leak).
    """
    from fastapi.testclient import TestClient

    from app.core.security import create_access_token
    from app.main import app

    facts: dict[str, Any] = {}
    made_questionnaire_ids: list[str] = []
    made_entry_ids: list[str] = []
    await db.connect()
    try:
        # An owner must exist or the script refuses by design (`Questionnaire.ownerId` is NOT NULL,
        # and hanging two national instruments off whichever user happened to be first is worse than
        # a refusal). Every environment this runs in has one; created here if not.
        if await db.user.count(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}}) == 0:
            await db.user.create(
                data={
                    "email": f"seed-toolkit-{uuid.uuid4().hex[:8]}@example.org",
                    "name": "Seed Admin",
                    "role": "ADMIN",
                }
            )
        owner = await db.user.find_first(where={"role": {"in": ["ADMIN", "MASTER_ADMIN"]}})

        # The GLOBAL instrument, counted before and after. Property 2.
        facts["global_sections_before"] = await db.questionnairesection.count()
        facts["global_questions_before"] = await db.questionnairequestion.count()

        # 1. The first run.
        facts["first"] = {
            instrument.title: (questionnaire_id, counts)
            for instrument, questionnaire_id, counts in await seed_toolkit_questionnaires()
        }
        facts["global_sections_after"] = await db.questionnairesection.count()
        facts["global_questions_after"] = await db.questionnairequestion.count()

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
                # Normalised, for the reason ``services/access_roster._account_role`` gives: Prisma
                # hands back an enum member on a live row, and an equality test against a bare
                # string has silently answered False on this very column before.
                "ownerRole": str(getattr(owner.role, "value", owner.role)),
                "sections": await db.questionnaireformsection.count(
                    where={"questionnaireId": row.id}
                ),
                "questions": await db.questionnaireformquestion.count(
                    where={"section": {"is": {"questionnaireId": row.id}}}
                ),
                "rowsUnderThisTitle": await db.questionnaire.count(where={"title": row.title}),
            }

        # 2. The second run, which must write nothing at all.
        facts["second"] = {
            instrument.title: (questionnaire_id, counts)
            for instrument, questionnaire_id, counts in await seed_toolkit_questionnaires()
        }

        # 3. The answered-question rule, DRIVEN rather than assumed. Reword a question, answer it,
        #    and re-run: the seeder wants to put the corpus wording back and must not, because the
        #    answer was given under the wording that is there now.
        target = INSTRUMENTS[1]  # the 3rd workshop — the smaller corpus, so step 3 is the cheap one
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
            # Put the fixture back whatever happened: the answer, the sitting, and the wording.
            if entry is not None:
                await db.questionnaireformentry.delete(where={"id": entry.id})
            await db.questionnaireformquestion.update(
                where={"id": question.id}, data={"prompt": original_prompt}
            )

        # 4. A brand-new designer, for the visibility and usability halves below. Created here so
        #    everything after the fixture can be pure HTTP.
        newcomer = await db.user.create(
            data={
                "email": f"toolkit-newcomer-{uuid.uuid4().hex[:8]}@example.org",
                "name": "Brand New Designer",
                "role": "DESIGNER",
            }
        )
        facts["newcomer_id"] = newcomer.id
        facts["newcomer_token"] = create_access_token(subject=newcomer.id)

        # One question id from each instrument, so the "use as is" test can answer without first
        # having to walk the whole form over HTTP.
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

    # What the HTTP tests made, removed — and ONLY that. The two published rows stay.
    #
    # THE SITTINGS ARE THE PART THAT MATTERS. A sitting carries a respondent's NAME, and these are
    # recorded against an instrument every designer can see; a suite that left one behind per run
    # would fill the standard form with fake respondents that a real designer then reads on the
    # answer screen. There is no DELETE route for an entry — deliberately, a sitting is fieldwork —
    # so the cleanup is a direct delete here rather than over HTTP.
    #
    # Deleted BEFORE the copies, though they are independent, because an answer row points at a
    # question and a question at a section: `onDelete: Cascade` takes the whole tree with the
    # questionnaire, and doing the narrow delete first keeps a failure here from being masked by a
    # cascade that would have removed the evidence.
    await db.connect()
    try:
        for entry_id in made_entry_ids:
            await db.questionnaireformentry.delete(where={"id": entry_id})
        for questionnaire_id in made_questionnaire_ids:
            await db.questionnaire.delete(where={"id": questionnaire_id})
    finally:
        await db.disconnect()


# --------------------------------------------------------------------------------------
# 1. Both instruments arrive, whole, published, and tellable apart
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_the_instrument_is_published_and_unattached(seeded, instrument) -> None:
    """Published to every designer, attached to no workshop, filed as a workshop interview.

    ``designWorkshopId is None`` is the load-bearing half. An instrument that belongs to EVERY
    workshop, attached to ONE, puts its sittings into that workshop's ministry report annexure —
    ``report_items`` selects on ``designWorkshopId`` with no permission filter — so every artisan
    interviewed on the standard form by any designer in the country would print in one report.
    """
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
    """24/284 for the 2nd workshop and 22/81 for the 3rd, spelled out rather than derived.

    The numbers are literals here and in ``INSTRUMENTS``, and they are deliberately not read from
    the JSON: a test that re-read the file it is testing would agree with a seeder that wrote half
    of it, as long as it read the same half.
    """
    row = seeded["rows"][instrument.title]
    assert (row["sections"], row["questions"]) == (
        instrument.expected_sections,
        instrument.expected_questions,
    )


def test_the_two_rows_are_tellable_apart_at_a_glance() -> None:
    """The owner asked for two instruments, so a designer has to be able to pick the right one.

    The titles carry the ordinal FIRST because that is the only difference a designer can see from
    the list screen: both are artisan interviews, both are published, both are unattached, and the
    question counts are not shown there.
    """
    titles = [instrument.title for instrument in INSTRUMENTS]
    assert len(set(titles)) == 2, "two instruments sharing a title is one instrument"
    assert titles[0].startswith("2nd Craft Toolkit Workshop")
    assert titles[1].startswith("3rd Craft Toolkit Workshop")


# --------------------------------------------------------------------------------------
# 2. The global instrument is a different family of tables and was not touched
# --------------------------------------------------------------------------------------


def test_the_global_artisan_instrument_is_untouched(seeded) -> None:
    """The one thing this script must not do, asserted as a number.

    ``QuestionnaireSection`` / ``QuestionnaireQuestion`` is the ONE GLOBAL instrument at
    ``/questionnaire`` SINGULAR — a different set of tables, a different API, a different permission
    model, and the form every researcher in the country answers. Writing two workshops' corpora into
    it would change what all of them are asked. The word the two families share is the whole reason
    this assertion exists.
    """
    assert seeded["global_sections_after"] == seeded["global_sections_before"]
    assert seeded["global_questions_after"] == seeded["global_questions_before"]


# --------------------------------------------------------------------------------------
# 3. Idempotence, in the sense that actually matters
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_re_running_it_writes_nothing(seeded, instrument) -> None:
    """"Does not crash on a second run" would be satisfied by a seeder that doubled the row.

    What is asserted is that the second run reports ZERO of every write it can make, that it landed
    on the same row, and that exactly one row carries this title.
    """
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


# --------------------------------------------------------------------------------------
# 4. The one thing the seeder will not do
# --------------------------------------------------------------------------------------


def test_an_answered_question_is_never_reworded(seeded) -> None:
    """The rule the API enforces, enforced by the seeder too — and REPORTED rather than silent.

    A skipped row nobody is told about is how an operator comes to believe a correction landed. The
    seeder counts it and prints which row it left alone.
    """
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


# --------------------------------------------------------------------------------------
# 5. "To edit or use as is", end to end, as an ordinary designer who owns nothing
# --------------------------------------------------------------------------------------


def test_a_designer_who_owns_nothing_sees_both(seeded) -> None:
    """THE WHOLE POINT: both published forms reach a designer with no forms, no workshops, no grants.

    The list AND the attach dropdown, which the owner asked for in one breath — a form a designer can
    see but not select is the feature working right up to the moment it is used.
    """
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
    """"USE AS IS" — start a sitting on a shared form nobody owns, and record an answer into it.

    NOT A GATE THAT WAS WIDENED, A GATE THAT WAS CHECKED. ``create_entry`` and ``record_answers`` are
    reached through ``_require_recordable_questionnaire``, which returns immediately when
    ``designWorkshopId`` is NULL — which these rows are, deliberately. If this test ever goes red,
    the answer is at that route with its own argument, NOT ``isShared`` being made to mean more.
    """
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
    # Registered for teardown BEFORE the answer is recorded, so a failure below still cleans up the
    # sitting it left on an instrument every designer can see.
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
    """"TO EDIT" — copy a shared form nobody owns into one of the caller's own, then edit THAT.

    ``POST /{id}/reuse`` is the one mutating route in that module that deliberately does not call
    ``_require_owner``, and its docstring gives the reason: the instrument already leaves the system
    for any designer through ``GET /{id}/question-set.xlsx``, so refusing here would refuse in JSON
    exactly what the .xlsx door hands over, and be routed around with no provenance recorded.

    The copy must carry the WHOLE corpus and ZERO fieldwork — the questions are the template, the
    sittings belong to whoever took them.
    """
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

    # AND IT IS EDITABLE, which is the half "reuse" exists for. Rewording a question on the ORIGINAL
    # would be refused (`_require_owner`); on the copy it is the owner's own form.
    question_id = copy["sections"][0]["questions"][0]["id"]
    edited = client.patch(
        f"/api/questionnaires/{copy['id']}/questions/{question_id}",
        headers=headers,
        json={"prompt": "My own wording of the first question."},
    )
    assert edited.status_code == 200, edited.text


# --------------------------------------------------------------------------------------
# 6. The corpus is validated before a single row is written
# --------------------------------------------------------------------------------------


def test_a_corpus_that_is_not_what_it_claims_refuses() -> None:
    """A swapped file must refuse, not publish 81 questions under the 2nd workshop's name.

    The two corpora sit in one directory and their names differ by one character. The seeder matches
    a re-run on the TITLE, so a swap that got through once would be re-asserted on every run
    afterwards, and the row would go on claiming to be the 2nd workshop's instrument forever.

    No database — :func:`load_corpus` reads and validates the file and writes nothing, which is the
    property that makes "validated before the first write" true rather than aspirational.
    """
    from dataclasses import replace

    swapped = replace(INSTRUMENTS[0], corpus=INSTRUMENTS[1].corpus)
    with pytest.raises(SeedRefused) as refusal:
        load_corpus(swapped)
    assert "22 sections / 81 questions" in str(refusal.value)
    assert "24 / 284" in str(refusal.value)


@pytest.mark.parametrize("instrument", INSTRUMENTS, ids=lambda i: i.corpus)
def test_the_vendored_corpus_is_the_shape_the_seeder_promises(instrument) -> None:
    """The corpora are vendored copies of the field repository's; this is what keeps a re-copy honest.

    ``load_corpus`` raises on a duplicate section code (``@@unique([questionnaireId, code])``) and on
    a question ``sortOrder`` that is not contiguous from 1 (that pair is the seeder's identity for a
    question, so a gap makes a re-run create duplicates instead of matching). Running it here means a
    corpus refreshed from the field repository is checked by the suite and not only by the operator
    who happens to run the script next.
    """
    sections = load_corpus(instrument)
    assert len(sections) == instrument.expected_sections
    assert (
        sum(len(section["questions"]) for section in sections) == instrument.expected_questions
    )
