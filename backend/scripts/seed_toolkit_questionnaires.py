"""Publish the 2nd and 3rd Craft Toolkit Workshop instruments to EVERY designer.

THE REQUEST, IN THE OWNER'S WORDS
=================================

*"seed the questionnaires from 2nd and 3rd toolkit workshop into designer app for all designers, to
use in case they want to edit or use as is"*

Two instruments, published, and BOTH halves of "edit or use as is" reachable without an
administrator in the loop. Neither half is built here — see "ALREADY BUILT" below — because both
already exist and the only thing missing was a row for them to act on.

====================================================================================================
THE FAMILY OF TABLES THIS WRITES TO, AND THE ONE IT DELIBERATELY DOES NOT
====================================================================================================

This repository has TWO things called a questionnaire and they share nothing but the word:

* ``QuestionnaireSection`` / ``QuestionnaireQuestion`` — the ONE GLOBAL ARTISAN INSTRUMENT, reachable
  at ``/questionnaire`` SINGULAR, answered by researchers, seeded by ``scripts/seed_questionnaire.py``.
  **This script does not touch it.** Seeding two workshops' questions in there would change what
  every researcher in the country is asked on a screen nobody asked us to change, and the global
  instrument has no per-row visibility at all — there is exactly one of it.

* ``Questionnaire`` -> ``QuestionnaireFormSection`` -> ``QuestionnaireFormQuestion`` — the
  DESIGNER-AUTHORED family, reachable at ``/questionnaires`` PLURAL, which is where a designer builds,
  uploads, answers and reuses forms. A row here with ``isShared = true`` is visible to every designer
  by the fourth clause of ``services/questionnaire_forms.visible_questionnaire_where``.

**The second family is the whole of this job.** The block comment above ``model Questionnaire`` in
schema.prisma makes the same separation from the database's side, and ``seed_shared_questionnaire.py``
beside this file says it again — the word they share is the reason all three have to.

====================================================================================================
TWO ROWS, AND THE ONE THAT ALREADY OVERLAPS — READ THIS BEFORE RUNNING BOTH SEEDERS
====================================================================================================

``scripts/seed_shared_questionnaire.py`` already publishes ONE shared form, titled "Standard artisan
questionnaire", from ``app/data/questionnaire_questions.json`` — and that file is the 2nd Craft
Toolkit Workshop's instrument. It is not a near-copy, it is the same corpus: 284 of its 285 questions
are byte-identical to the 2nd-workshop corpus this script reads, and the single difference is one
extra RESP question, ``"Date of Interview"`` at RESP #12, which this repository added locally and the
field repository does not carry.

So a deployment that runs BOTH seeders publishes the 2nd workshop's questions twice, under two names.
That is stated here rather than resolved here, and deliberately:

* **Nothing is deleted or retitled.** "Standard artisan questionnaire" is what a badge, a test, the
  attach dropdown's ordering assertion and any number of live sittings already point at. A seeder is
  the wrong place to retire another seeder's published row — that is an administrator's act, and the
  admin PATCH that clears ``isShared`` is the door built for it.
* **The row this script publishes is named for its workshop** (see :data:`INSTRUMENTS`), so the two
  are distinguishable on the list screen at a glance, which is the property the owner asked for.
* An operator who wants exactly one 2nd-workshop form should run this script and then withdraw
  publication of "Standard artisan questionnaire" through the admin PATCH — in that order, so there
  is never a moment with neither.

The summary this script prints names the overlap every time it runs, so nobody discovers it from the
list screen.

====================================================================================================
"TO EDIT OR USE AS IS" IS ALREADY BUILT. VERIFIED, NOT WIDENED.
====================================================================================================

Both halves work TODAY for an ordinary DESIGNER who owns neither row, and no gate was touched to make
that true. Traced 2026-09-20 through ``app/api/routes/questionnaire_forms.py``:

* **USE AS IS** — ``POST /questionnaires/{id}/entries`` then
  ``PUT /questionnaires/{id}/entries/{eid}/answers`` (the web client's ``/questionnaires/{id}/answer``
  screen). Both are gated by ``_require_questionnaire`` (Designer/Admin/Master Admin, and the row
  exists) plus ``_require_recordable_questionnaire``, and that second helper RETURNS IMMEDIATELY when
  ``designWorkshopId`` is NULL — which these two rows are, by the paragraph below. ``create_entry``
  is explicitly not owner-gated ("a form only its author may answer is a form nobody uses"), and
  ``record_answers`` passes ``rewriting=False`` so a designer may answer a sitting they did not start.
  The only remaining refusal is ``isActive = false``, which this script asserts true.
* **EDIT** — ``POST /questionnaires/{id}/reuse`` copies the form into one of the caller's own. It is
  the one mutating route in that module that deliberately does NOT call ``_require_owner``, and its
  docstring gives the reason: the instrument already leaves the system for any designer through
  ``GET /{id}/question-set.xlsx``, so refusing in JSON what the .xlsx hands over would only be routed
  around, with no provenance recorded. The copy carries questions and zero fieldwork.

Nothing here required widening a gate, and if a future change makes either refuse, the fix belongs at
that route with its own argument — not in a seeder.

ATTACHED TO NO WORKSHOP, ON PURPOSE. ``designWorkshopId`` stays NULL: an instrument that belongs to
every workshop must not have its sittings swept into ONE workshop's ministry report annexure by
``report_items``, which selects on ``designWorkshopId`` with no permission filter. ``isShared`` is the
column that publishes a form; "unattached" is not, and reading it as publication is one of the two
accidents ``Questionnaire.isShared``'s own schema comment rejects.

====================================================================================================
THE THREE DEFECTS THE FIELD REPOSITORY RECORDS, AND WHAT EACH ONE IS HERE
====================================================================================================

``documentation-portal/backend/app/services/questionnaire_seeding.py`` documents three ways its
predecessor was wrong. They are answered here rather than re-made, and two of them have a DIFFERENT
answer in this schema, which is why they are worked through instead of copied:

1. **Upserting a section on a code that is no longer globally unique.** In the field repository
   ``QuestionnaireSection.code`` lost its global ``@unique`` and the upsert stopped compiling. Here
   the equivalent was never global: ``QuestionnaireFormSection`` carries ``@@unique([questionnaireId,
   code])`` and its schema comment says why ("two designers both naming a section 'A' is normal and
   must not collide"). So sections are looked up with ``find_many(where={"questionnaireId": ...})``
   and indexed by code IN PYTHON — never ``find_first(where={"code": code})``, which would reach into
   every other designer's form and, across these two corpora, would collide on all 22 of the 3rd
   workshop's codes because every one of them also exists in the 2nd workshop's.

2. **``sortOrder`` taken from the array index.** In the field repository that was fatal because
   ``sortOrder`` was globally ``@@unique``. Here it is NOT unique at all — ``QuestionnaireFormSection``
   has only ``@@index([questionnaireId, sortOrder])`` — so there is no collision to dodge and no
   negative-parking pass to write. Section order therefore IS the 1-based array index, because the
   corpus carries no section ordering of its own (a section object has exactly ``code``, ``title``
   and ``questions``). QUESTION order is NOT the index: it is the ``sortOrder`` the corpus states,
   because that value is this seeder's identity for a question and inventing a different one would
   make every re-run create duplicates.

3. **Rewriting the prompt of any question whose (sectionId, sortOrder) matched, answers and all.**
   Kept as the match key — it is the only key the corpus supports — and guarded: A QUESTION THAT
   ALREADY HAS ANSWERS IS NEVER REWORDED. See :func:`_seed_one`. This is the same rule the API
   enforces through ``QuestionnaireFormQuestion.supersededById``; a seeder that rewrote prompts in
   place would do by the back door exactly what the front door refuses.

====================================================================================================
IDEMPOTENT — AND WHAT EXACTLY A RE-RUN DOES TO AN EXISTING ROW
====================================================================================================

Each instrument is found by ``(isShared = true, title = <its title>)`` — what it IS, rather than an
id nobody writes down. Running this twice:

* does NOT create a second copy of either instrument, and the second run reports zero writes;
* REFRESHES the description, and the title and wording of every section and question that has NOT
  been answered, so a correction to a corpus reaches a deployment that already ran this;
* RE-ASSERTS ``isShared`` **and** ``isActive``, and PRINTS a line naming the row when it had to.
  Both, not one: ``visible_questionnaire_where``'s fourth clause is the PAIR
  ``{"isShared": True, "isActive": True}``, so re-publishing a retired row without reactivating it
  would leave the operator's instruction half-applied and the form still invisible to every
  designer — a green summary over a screen that did not change. If the instrument was retired ON
  PURPOSE, do not re-run this; withdraw it again through the admin PATCH;
* NEVER touches ``designWorkshopId`` on an existing row. NULL is what this script creates, but an
  administrator who deliberately attached the row to a workshop must not have that undone by a
  wording refresh;
* NEVER deletes and NEVER deactivates anything. A section or question that has vanished from a
  corpus may hold a fortnight of somebody's fieldwork, so it is left standing and NAMED on stdout.
  A silent survivor is how the wrong question goes on being asked with nobody noticing;
* NEVER sets ``kind`` to anything but ``WORKSHOP_INTERVIEW`` and never writes to the GLOBAL
  instrument, the default questionnaire or any workshop binding — this repository has no
  ``isDefault`` column and no workshop-to-questionnaire binding at all, so there is nothing here to
  flip by accident; the sentence stands because the field repository's seeders had exactly that
  hazard and a reader coming from them will look for it.

WHO OWNS THE TWO ROWS
=====================

``Questionnaire.ownerId`` is NOT NULL, so both rows need an account, and they take the MASTER ADMIN —
or, failing that, the oldest ADMIN. That is the same choice ``seed_shared_questionnaire.py`` makes
and for the same reason: ownership here is BOOKKEEPING, not authority. ``isShared`` is what makes an
instrument everybody's, and ``_require_owner`` governs only REWORDING it, which is correctly an
administrator's act rather than the act of whichever designer happened to be created first. With no
admin account at all this script REFUSES and says which script to run instead, rather than hanging
two national instruments off an arbitrary user.

    cd backend
    PYTHONUTF8=1 .venv/Scripts/python.exe -m scripts.seed_toolkit_questionnaires

``PYTHONUTF8=1`` is not decoration: the 3rd workshop's section titles contain EN DASHES ("SCIENCE
BEHIND THE CRAFT - PROBING QUESTIONS" is spelled with U+2013 in the corpus) and this script prints
section titles and prompts. On a Windows console left at cp1252 that is a ``UnicodeEncodeError`` in
the middle of a run that has already written rows.
"""

import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.core.db import connect_db, db, disconnect_db

DATA_DIR = Path(__file__).resolve().parents[1] / "app" / "data"


class SeedRefused(RuntimeError):
    """A corpus is malformed, or is not the instrument its entry claims.

    Raised BEFORE the first write of the whole run, so a bad file publishes nothing at all rather
    than leaving half an instrument in front of every designer in the country.
    """


@dataclass(frozen=True)
class Instrument:
    """One published form: its identity, its corpus, and the shape that corpus must have."""

    #: The published row's title, and the key a re-run finds it by.
    #:
    #: A CONSTANT AND NOT AN ARGUMENT, for the reason ``seed_shared_questionnaire.TITLE`` gives: it
    #: is an IDENTITY. A deployment that ran this with one title and re-ran it with another would
    #: hold two published copies of one workshop's instrument and no way for a designer to tell
    #: which is current. Renaming one deliberately is an edit here PLUS a rename of the live row,
    #: in that order.
    #:
    #: The ordinal leads the title because that is the whole job of the name on the list screen: the
    #: two rows differ in which workshop they were used at and in nothing a designer can see from
    #: the outside, and "2nd"/"3rd" is the first thing to read in both.
    title: str
    description: str
    #: The corpus, under ``app/data``. VENDORED from the field repository rather than read across a
    #: sibling checkout: a path to ``../documentation-portal`` works on one developer's laptop and is
    #: a ``FileNotFoundError`` inside the container, which copies only ``backend/`` in.
    corpus: str
    #: What the corpus MUST contain, restated here so a mis-copied file refuses instead of publishing
    #: 81 questions under the 2nd workshop's name. Checked before the first write.
    expected_sections: int
    expected_questions: int


#: The two instruments, in the order they are seeded and the order their titles sort in.
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

#: Every write this script makes. Zero of all of them is what "the second run changed nothing" means.
WRITE_COUNTS = (
    "sections_created",
    "sections_updated",
    "questions_created",
    "questions_updated",
    "republished",
)

#: What this script found and deliberately did NOT write. Reported, never acted on.
OBSERVED_COUNTS = (
    "questions_left_alone",
    "sections_not_in_corpus",
    "questions_not_in_corpus",
)


def load_corpus(instrument: Instrument) -> list[dict[str, Any]]:
    """The corpus for ``instrument``, fully validated. Raises :class:`SeedRefused`, writes nothing.

    ``utf-8-sig`` rather than ``utf-8`` because a corpus is an EXPORT, not a hand-edited file: the
    parsing script that produces these and the Windows editors that touch them afterwards add a
    UTF-8 BOM without being asked, and a BOM in front of the opening bracket is a
    ``json.JSONDecodeError`` on character 0. Neither file carries one today - both start with ``[``,
    checked - and ``utf-8-sig`` costs nothing on a file that does not. Both sibling seeders open
    their corpora the same way for the same reason.

    EVERY CHECK HAPPENS BEFORE THE CALLER'S FIRST WRITE. A seeder that discovered a duplicate code
    on section eleven has already published ten sections of a broken instrument to every designer,
    and the recovery from that is manual.
    """
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

    # THE CORPUS IS THE INSTRUMENT IT CLAIMS TO BE. The two files live side by side in one directory
    # and differ by three characters in the name; a swapped pair would otherwise publish the 3rd
    # workshop's 81 questions under the 2nd workshop's title, and every re-run afterwards would keep
    # it that way because the seeder matches on the TITLE.
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
    """The master admin, else the oldest admin. Raises with the next command to run if neither.

    See the module docstring's "WHO OWNS THE TWO ROWS": this is provenance, not authority.
    """
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
    """Which of ``question_ids`` already carry an answer.

    ONE query per section rather than one per question. This runs 24 times over 284 questions and
    then 22 times over 81, and a per-question existence check would be 365 sequential round trips to
    a database that on the deployed system is in another region.
    """
    if not question_ids:
        return set()
    answers = await db.questionnaireformanswer.find_many(where={"questionId": {"in": question_ids}})
    return {answer.questionId for answer in answers}


async def _seed_one(instrument: Instrument, sections: list[dict[str, Any]], owner_id: str) -> tuple[str, dict[str, int]]:
    """Publish or refresh ONE instrument. Returns ``(questionnaire_id, counts)``.

    Returned rather than only printed so a test can drive this directly and assert that the SECOND
    run reports zero writes - which is what "idempotent" actually means, and a property a seeder
    that merely survives being run twice does not have.
    """
    counts = dict.fromkeys(WRITE_COUNTS + OBSERVED_COUNTS, 0)

    # FOUND BY WHAT IT IS - published, under this title - and not by an id nobody records. The same
    # key ``seed_shared_questionnaire`` uses, so the two scripts cannot adopt each other's row.
    existing = await db.questionnaire.find_first(
        where={"isShared": True, "title": instrument.title}
    )
    if existing is None:
        # A row may exist UNDER THIS TITLE WITHOUT BEING PUBLISHED - an admin withdrew it, or this
        # script created it and the PATCH cleared the flag. Adopting it is what makes the re-assert
        # below reachable; creating a second row would leave two identical instruments and only one
        # of them visible.
        existing = await db.questionnaire.find_first(where={"title": instrument.title})

    if existing is None:
        record = await db.questionnaire.create(
            data={
                "title": instrument.title,
                "description": instrument.description,
                "ownerId": owner_id,
                # PUBLISHED TO EVERY DESIGNER. This flag, paired with isActive, is the entire fourth
                # clause of ``visible_questionnaire_where``.
                "isShared": True,
                "isActive": True,
                # NOT attached to a workshop - see the module docstring. A shared instrument whose
                # sittings land in one workshop's ministry annexure is the failure this prevents.
                "designWorkshopId": None,
                # WORKSHOP_INTERVIEW, because that is what both of these ARE: sections of questions
                # put to an individual artisan about their craft, tools, household and market.
                # ``questionnaire_kinds`` files that under stage 6, the artisan baseline. Leaving it
                # NULL would land both instruments in every designer's report as unfiled material -
                # ``kind`` is a ROUTING INSTRUCTION, and NULL routes nowhere.
                "kind": "WORKSHOP_INTERVIEW",
                # The corpus this row was built from, by its repository-relative name. It differs
                # from ``seed_shared_questionnaire``'s NULL on purpose: that script argued a name
                # would "send a designer off to edit a file that does not exist", and this name
                # points at a file that DOES exist and is the only way to tell, from the row alone,
                # which workshop's corpus produced it.
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
                # RE-ASSERTED, BOTH OF THEM. A re-run is the operator saying "publish this
                # instrument", which is the same instruction it was the first time - and the
                # visibility clause is the PAIR, so asserting one without the other leaves the form
                # invisible while the summary reports success. Printed below when it fired, because
                # an override nobody is told about is indistinguishable from a bug.
                "isShared": True,
                "isActive": True,
                "kind": "WORKSHOP_INTERVIEW",
                "sourceFilename": f"app/data/{instrument.corpus}",
                # ``designWorkshopId`` IS ABSENT FROM THIS DICT AND MUST STAY ABSENT. An
                # administrator may have attached this row deliberately; a wording refresh is not
                # the moment to detach it.
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
    # INDEXED BY CODE IN PYTHON, over rows already narrowed to THIS questionnaire. Never
    # ``find_first(where={"code": code})``: ``code`` is unique only within a questionnaire, so a
    # global lookup would reach into another designer's private form - and across these two corpora
    # it would match on every one of the 3rd workshop's 22 codes, all of which also exist in the
    # 2nd workshop's.
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
                    # THE 1-BASED ARRAY INDEX, and that is correct HERE. The corpus carries no
                    # section ordering of its own (a section object is exactly code/title/questions)
                    # and ``QuestionnaireFormSection`` has no unique index on sortOrder - only
                    # ``@@index([questionnaireId, sortOrder])`` - so there is nothing to collide
                    # with and no negative-parking pass to write. The field repository needed one
                    # only because its equivalent column was globally ``@@unique``.
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
        # MATCHED ON ``sortOrder`` WITHIN THE SECTION - position, which is all the corpus carries.
        # Matching on the PROMPT instead would make every corrected wording look like a brand-new
        # question and leave the old one standing, which is how an 81-question instrument becomes a
        # 130-question one over three re-runs.
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
                        # NOTHING IS REQUIRED, matching the instrument as the field carries it.
                        # Required-ness here would print "[Not recorded]" in a ministry report for
                        # every artisan who did not answer a question nobody told them was
                        # mandatory - see the report annexure's rule for that marker.
                        "isRequired": False,
                        "sortOrder": order,
                    }
                )
                counts["questions_created"] += 1
                continue
            if stored_question.prompt == prompt and stored_question.isActive:
                continue
            if stored_question.id in answered:
                # THE ONE THING THIS SCRIPT WILL NOT DO. An answer belongs to the wording it was
                # given under: rewording an answered question leaves "12" sitting under "How many
                # weavers work with you?" when it was said about "How many looms do you own?", and
                # a ministry report then states there are twelve weavers.
                # ``QuestionnaireFormQuestion.supersededById`` exists precisely so a REWORDING makes
                # a new row instead. Reported rather than skipped in silence, so an operator who
                # corrected a corpus knows which rows did not move and can supersede them through
                # PATCH /api/questionnaires/{id}/questions/{qid}, which is the door built for it.
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
                # KEPT, NEVER DELETED AND NEVER DEACTIVATED, because it may already carry answers -
                # and NAMED, because a silent survivor is how a question the corpus dropped goes on
                # being asked with nobody noticing.
                counts["questions_not_in_corpus"] += 1
                print(
                    f"    kept, not in the corpus: {code} #{stored_question.sortOrder} - "
                    f"{stored_question.prompt[:60]!r}. NOT deactivated; retire it through the "
                    f"builder if it should stop being offered."
                )

    for row in stored_sections:
        if row.code not in corpus_codes:
            # Left exactly where it is, unlike the field repository's seeder, which has to PARK such
            # a section on a slot above the corpus to dodge its globally unique sortOrder. There is
            # no unique index here, so a duplicated sortOrder is legal and harmless; moving the row
            # would reorder somebody's live form to solve a problem this schema does not have.
            counts["sections_not_in_corpus"] += 1
            print(
                f"    kept, not in the corpus: section {row.code} - {row.title[:60]!r} at "
                f"sortOrder {row.sortOrder}. NOT deactivated and NOT moved."
            )

    return record.id, counts


async def seed_toolkit_questionnaires() -> list[tuple[Instrument, str, dict[str, int]]]:
    """Publish or refresh both instruments. Returns one ``(instrument, id, counts)`` per row.

    THE PLAN IS PRINTED AND EVERY CORPUS IS VALIDATED BEFORE THE FIRST WRITE. Loading both files up
    front is what makes "the 3rd workshop's corpus is malformed" refuse without having already
    published the 2nd - the two rows are independent in the database and an operator who saw one
    summary and one traceback would have to work out by hand which half landed.
    """
    loaded = [(instrument, load_corpus(instrument)) for instrument in INSTRUMENTS]
    owner_id = await _owner_id()
    owner = await db.user.find_unique(where={"id": owner_id})

    # ``str(getattr(role, "value", role))`` and not ``owner.role``: Prisma hands back an enum member
    # on a live row, and this one is a ``StrEnum`` today so an f-string prints "ADMIN" — but
    # ``services/access_roster._account_role`` records the same column having been read the other way
    # round, printing "UserRole.ADMIN" into a line an operator is meant to recognise. Cheap to be
    # right regardless of how the client is generated next.
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
    # PRINTED EVERY RUN, not once in a docstring. See "TWO ROWS, AND THE ONE THAT ALREADY OVERLAPS":
    # an operator who runs both seeders gets the 2nd workshop's questions twice under two names, and
    # the list screen is a bad place to discover that.
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
