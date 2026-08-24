"""Custom questionnaires: reading them, writing them, and the one rule that governs editing them.

A designer downloads the .xlsx pro-forma, types their own questions into it, uploads it, and then
records answers against it in the platform. This module is everything between the parsed spreadsheet
(:mod:`app.services.questionnaire_xlsx`) and the database.

================================================================================================
THE EDIT-AFTER-ANSWERS RULE
================================================================================================

What happens when a designer edits a questionnaire that people have already answered? It is the
question this feature lives or dies on, because both easy answers are wrong:

* **Let the edit through.** A question reading "How many looms do you own?" is answered "12". The
  designer later rewords it to "How many weavers work with you?". Nothing in the database changed
  except one string — and the repository now asserts that this artisan works with twelve weavers.
  Nobody edited an answer. Nobody will ever find it. It goes into a report submitted to a ministry.
* **Refuse the edit.** A designer who typed a question wrongly, or spotted a translation error after
  the first interview, is told their questionnaire is frozen forever. They work around it by
  uploading a second questionnaire, and the answers they have already collected are stranded on the
  first one.

THE RULE, in one line: **an answer is evidence, and the words it was given under are part of that
evidence.** So the wording an answer was recorded against is immutable, and everything else is not.
Concretely, and this is exactly what the code below does:

1. A question **nobody has answered** is fully editable and can be deleted outright. This is the
   ordinary case — a designer drafting their form — and it must stay frictionless.

2. A question **with answers** can still have its help text, its required flag and its position
   changed. None of those alter what a recorded answer asserts.

3. Rewording a question **with answers** SUPERSEDES it. The original question is retired with its
   original wording and keeps its answers; the new wording is stored as a NEW question in the same
   place. Both are in the record, linked by ``supersededById``. No answer ever changes meaning, and
   the designer still gets the correction they asked for. Nothing is refused and nothing is lost.

4. Deleting a question **with answers** RETIRES it: it stops being asked, and its answers stay
   readable and exportable. Deleting is only ever a real delete when there is nothing to orphan.

5. The same for a section: retired if anything under it has been answered, deleted if not. A section
   *title* may be changed freely even when answered — a heading is not what an answer answers.

6. ``Questionnaire.version`` increments on every supersede and every retire, so a client holding a
   cached copy of the form can tell it is stale with an integer compare.

Rule 4 is also enforced one layer down, in the database: ``QuestionnaireFormAnswer.questionId`` is
``ON DELETE RESTRICT``. A rule that lives only in Python is one ``delete_many`` away from silently
orphaning a fortnight of somebody's fieldwork, and the person who writes that ``delete_many`` will
not have read this docstring.

================================================================================================
MATCHING A RE-UPLOADED SPREADSHEET TO THE QUESTIONS ALREADY STORED
================================================================================================

None of the above means anything unless a re-uploaded workbook can be matched to the questions that
are already there. Three ways, tried in order:

1. **The Question ID column.** Filled in on every questionnaire downloaded back out of the app, and
   the reason that column exists. An id belonging to a DIFFERENT questionnaire is reported rather
   than silently honoured — that is a designer who uploaded the wrong file, and the cost of guessing
   is somebody else's questions grafted onto their form.
2. **Exact prompt text within the same section.** This is what carries the designer who built their
   form from scratch in Excel, never downloaded it back, and re-uploads an edited copy with no ids
   in it. Without this fallback every question in that file would read as new, the entire existing
   form would retire in one go, and an answered questionnaire would come back doubled.
3. Otherwise it is a new question.

Anything present in the database and absent from the upload is removed under rule 4.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from app.core.db import db
from app.services.concurrency import gather_reads
from app.services.questionnaire_xlsx import (
    MAX_QUESTIONS,
    MAX_SECTIONS,
    ParsedQuestionnaire,
    derive_section_code,
)
# The report annexure's value objects, imported rather than re-declared. ``report_questionnaires``
# is pure — it reaches ``report_model`` and nothing else, never the database — so this direction of
# the dependency is the safe one and there is no cycle: the report pipeline imports THIS module.
# Two shapes for one thing would drift, and the drift would show as the annexure printing a field
# the loader stopped filling.
from app.services.report_questionnaires import (
    QuestionnaireAnswer,
    QuestionnaireItem,
    QuestionnaireSitting,
)

# The label an answer column with no name of its own becomes. See ParsedQuestionnaire.entryLabels.
UPLOAD_ENTRY_SOURCE = "UPLOAD"
APP_ENTRY_SOURCE = "APP"


class QuestionnaireEditError(ValueError):
    """An edit that cannot be applied at all, with a message written to be shown to the designer."""


def _now() -> datetime:
    return datetime.now(UTC)


# --- Reading ------------------------------------------------------------------------------------


async def load_form(
    questionnaire_id: str,
    *,
    include_retired: bool = False,
    only_entries_of: str | None = None,
) -> dict[str, Any] | None:
    """One questionnaire with its sections, questions, sittings and answers, ready to serialise.

    FOUR queries, flat, regardless of how big the form is — not a nested ``include``, which Prisma
    issues as its own sequential round trip per level and which on this deployment is a cross-region
    hop each. A twelve-section questionnaire would otherwise cost a dozen.

    ``include_retired`` is what the EDITOR asks for and the ANSWER SCREEN does not: a retired
    question must still be visible where its recorded answers are read, and must never be offered
    for a new answer.

    ``only_entries_of`` NARROWS THE SITTINGS TO ONE PERSON'S, and exists because reading the form
    is deliberately open to any designer — a colleague handed the form has to be able to fill it
    in (see the note on ``/questionnaires`` in frontend/lib/permissions.ts) — while the sittings
    recorded against it are not the same kind of thing as the questions. Each one carries a
    respondent's NAME, the interviewer's notes and every recorded answer, and that rode along on
    the form payload to anyone holding the id. The questions are the instrument; the sittings are
    the fieldwork, and only the person who recorded them, the owner and an admin see all of them.
    ``hasAnswers`` is still computed over EVERY answer, because whether a question may be reworded
    is a fact about the question and not about who is looking at it.
    """
    questionnaire = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if questionnaire is None:
        return None

    sections, entries = await gather_reads(
        db.questionnaireformsection.find_many(
            where={"questionnaireId": questionnaire_id},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        ),
        db.questionnaireformentry.find_many(
            where={"questionnaireId": questionnaire_id},
            order={"createdAt": "asc"},
            include={"createdBy": True},
        ),
    )
    section_ids = [s.id for s in sections]
    questions = (
        await db.questionnaireformquestion.find_many(
            where={"sectionId": {"in": section_ids}},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        )
        if section_ids
        else []
    )
    entry_ids = [e.id for e in entries]
    answers = (
        await db.questionnaireformanswer.find_many(where={"entryId": {"in": entry_ids}})
        if entry_ids
        else []
    )

    answered_ids = {a.questionId for a in answers if (a.answerText or "").strip()}
    by_section: dict[str, list[Any]] = {}
    for question in questions:
        if not include_retired and not question.isActive:
            continue
        by_section.setdefault(question.sectionId, []).append(question)

    section_payloads = []
    for section in sections:
        if not include_retired and not section.isActive:
            continue
        section_payloads.append(
            {
                "id": section.id,
                "code": section.code,
                "title": section.title,
                "sortOrder": section.sortOrder,
                "isActive": section.isActive,
                "questions": [
                    {
                        "id": q.id,
                        "prompt": q.prompt,
                        "helpText": q.helpText,
                        "isRequired": q.isRequired,
                        "sortOrder": q.sortOrder,
                        "isActive": q.isActive,
                        "retiredAt": q.retiredAt,
                        "supersededById": q.supersededById,
                        # The single fact every client needs in order to show the right affordances:
                        # a question with answers cannot be reworded in place or removed, and the UI
                        # should say so BEFORE the designer types, not after they press save.
                        "hasAnswers": q.id in answered_ids,
                    }
                    for q in by_section.get(section.id, [])
                ],
            }
        )

    answers_by_entry: dict[str, list[Any]] = {}
    for answer in answers:
        answers_by_entry.setdefault(answer.entryId, []).append(answer)

    # Filtered here rather than in the query above, so ``answered_ids`` — and therefore every
    # question's ``hasAnswers`` — still sees the whole form. Narrowing the query would tell a
    # colleague they may reword a question that in fact already carries somebody else's answers.
    visible_entries = (
        entries if only_entries_of is None
        else [e for e in entries if e.createdById == only_entries_of]
    )

    return {
        "id": questionnaire.id,
        "title": questionnaire.title,
        "description": questionnaire.description,
        "ownerId": questionnaire.ownerId,
        "designWorkshopId": questionnaire.designWorkshopId,
        "isActive": questionnaire.isActive,
        "version": questionnaire.version,
        "sourceFilename": questionnaire.sourceFilename,
        "createdAt": questionnaire.createdAt,
        "updatedAt": questionnaire.updatedAt,
        "sections": section_payloads,
        "questionCount": sum(len(s["questions"]) for s in section_payloads),
        "entries": [
            {
                "id": entry.id,
                "title": entry.title,
                "respondentName": entry.respondentName,
                "source": entry.source,
                "notes": entry.notes,
                "createdAt": entry.createdAt,
                "createdById": entry.createdById,
                "createdByName": getattr(entry.createdBy, "name", None),
                "answers": [
                    {
                        "id": a.id,
                        "questionId": a.questionId,
                        "answerText": a.answerText,
                        "notes": a.notes,
                        "answeredById": a.answeredById,
                        "updatedAt": a.updatedAt,
                    }
                    for a in answers_by_entry.get(entry.id, [])
                ],
            }
            for entry in visible_entries
        ],
    }


async def report_items(design_workshop_id: str) -> list[QuestionnaireItem]:
    """Every questionnaire attached to one design workshop, as the report annexure reads it.

    FIVE FLAT QUERIES REGARDLESS OF HOW MANY QUESTIONNAIRES ARE ATTACHED, for the reason
    :func:`load_form` is flat: on this deployment the database is in another AWS region and a nested
    Prisma ``include`` is its own sequential round trip per level, so a per-questionnaire
    ``load_form`` loop would cost four hops times the number of forms on a path — report generation —
    that already measured 6.8 s of pure network before it was made to gather.

    RETIRED QUESTIONS ARE INCLUDED, exactly as :func:`export_payload` includes them and for the same
    reason: an answer recorded against a question that was later reworded stays attached to the
    WORDING IT WAS GIVEN UNDER (``QuestionnaireFormQuestion.supersededById``), and dropping the old
    wording would either lose the answer or, worse, reprint it under the new question — which is the
    "twelve looms becomes twelve weavers" failure the supersede rule exists to prevent, arriving in a
    ministry report by a different door.

    DEACTIVATED QUESTIONNAIRES ARE NOT INCLUDED. ``PATCH {isActive: false}`` is what this API has
    INSTEAD of a delete (see the route module's docstring): it takes the form out of every list and
    every dropdown while keeping the answers. The report is a list. Printing a questionnaire the
    designer has retired would make this the one surface where a deleted record comes back, in the
    document that is hardest to retract.

    NO PERMISSION FILTER HERE, AND THAT IS CORRECT RATHER THAN AN OMISSION — the one thing to check
    before adding a second caller. The sittings recorded against a questionnaire are readable by its
    owner, an admin, or anyone who works on the design workshop it is attached to
    (``_works_on_this_questionnaires_workshop`` in api/routes/questionnaire_forms.py). Generating a
    report for a design workshop requires passing ``load_workshop_or_404``, which admits the
    workshop's creator, a ``DesignWorkshopViewer`` grant holder, and an admin — a SUBSET of that set,
    since the attachment is what puts the questionnaire inside the workshop's own access boundary. So
    every caller of this function is already entitled to every row it returns, and the server, not a
    client, is what decides that. A caller reaching this from anywhere other than a design-workshop
    report must re-establish the same thing rather than assume it.
    """
    questionnaires = await db.questionnaire.find_many(
        where={"designWorkshopId": design_workshop_id, "isActive": True},
        order={"createdAt": "asc"},
    )
    if not questionnaires:
        return []

    ids = [q.id for q in questionnaires]
    sections, entries = await gather_reads(
        db.questionnaireformsection.find_many(
            where={"questionnaireId": {"in": ids}},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        ),
        db.questionnaireformentry.find_many(
            where={"questionnaireId": {"in": ids}},
            order={"createdAt": "asc"},
            include={"createdBy": True},
        ),
    )
    section_ids = [s.id for s in sections]
    entry_ids = [e.id for e in entries]
    questions, answers = await gather_reads(
        db.questionnaireformquestion.find_many(
            where={"sectionId": {"in": section_ids}},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        ) if section_ids else _none(),
        db.questionnaireformanswer.find_many(
            where={"entryId": {"in": entry_ids}},
        ) if entry_ids else _none(),
    )

    # THE ORDER OF THIS LIST IS THE ORDER THE ANNEXURE PRINTS, and it is built once here rather than
    # sorted per sitting: section position first, then question position, which is the order the
    # designer wrote the form in and the order the .xlsx download uses. A sitting is then a lookup
    # against it, so two sittings of the same form cannot come out in two different orders — which,
    # in a document read side by side with another respondent's answers, is what makes it comparable.
    #
    # WALKED SECTION BY SECTION, AND IT MUST BE. `QuestionnaireFormQuestion.sortOrder` is scoped to
    # its SECTION — the upload numbers `enumerate(section.questions, start=1)` and the create route
    # takes `max(sortOrder) + 1` over the section's siblings alone — so the flat query above, ordered
    # by sortOrder across every section at once, comes back INTERLEAVED: A1, B1, A2, B2. Appending in
    # that order printed one single-row table per question with the section label repeated above each,
    # A/B/A/B down the page, in the appendix of evidence handed to a ministry officer. `load_form` is
    # safe from the same query only because it re-buckets by `sectionId` before it emits; this does
    # the same, so the per-section order the query does give (sortOrder, then createdAt) is kept and
    # the section order comes from `sections`, which IS globally ordered within one questionnaire.
    questions_by_section: dict[str, list[Any]] = {}
    for question in questions or []:
        questions_by_section.setdefault(question.sectionId, []).append(question)

    ordered: dict[str, list[Any]] = {}
    for section in sections:
        for question in questions_by_section.get(section.id, ()):
            ordered.setdefault(section.questionnaireId, []).append((section, question))

    answers_by_entry: dict[str, dict[str, Any]] = {}
    for answer in answers or []:
        answers_by_entry.setdefault(answer.entryId, {})[answer.questionId] = answer

    entries_by_questionnaire: dict[str, list[Any]] = {}
    for entry in entries:
        entries_by_questionnaire.setdefault(entry.questionnaireId, []).append(entry)

    items: list[QuestionnaireItem] = []
    for questionnaire in questionnaires:
        pairs = ordered.get(questionnaire.id, [])
        sittings: list[QuestionnaireSitting] = []
        for entry in entries_by_questionnaire.get(questionnaire.id, []):
            recorded = answers_by_entry.get(entry.id, {})
            sittings.append(
                QuestionnaireSitting(
                    entry_id=entry.id,
                    title=str(entry.title or ""),
                    respondent_name=str(entry.respondentName or ""),
                    source=str(entry.source or ""),
                    notes=str(entry.notes or ""),
                    recorded_at=entry.createdAt.isoformat() if entry.createdAt else "",
                    recorded_by=str(getattr(entry.createdBy, "name", "") or ""),
                    answers=tuple(
                        QuestionnaireAnswer(
                            prompt=str(question.prompt or ""),
                            answer_text=str(
                                getattr(recorded.get(question.id), "answerText", "") or ""
                            ),
                            notes=str(getattr(recorded.get(question.id), "notes", "") or ""),
                            section_code=str(section.code or ""),
                            section_title=str(section.title or ""),
                            is_required=bool(question.isRequired),
                            is_retired=not question.isActive,
                        )
                        for section, question in pairs
                    ),
                )
            )
        items.append(
            QuestionnaireItem(
                questionnaire_id=questionnaire.id,
                title=str(questionnaire.title or ""),
                description=str(questionnaire.description or ""),
                version=int(questionnaire.version or 1),
                source_filename=str(questionnaire.sourceFilename or ""),
                # The questions the form ASKS today. Retired ones are printed where they carry an
                # answer but are not counted here, because this number is what a reader compares
                # against the instrument, and the instrument no longer contains them.
                question_count=sum(1 for _s, q in pairs if q.isActive),
                sittings=tuple(sittings),
            )
        )
    return items


async def _none() -> list[Any]:
    """An awaitable empty result, so the gather above stays one shape rather than two branches."""
    return []


async def export_payload(questionnaire_id: str) -> dict[str, Any] | None:
    """``load_form`` reshaped into what ``build_questionnaire_workbook`` wants.

    Retired questions ARE included, with their original wording and their answers. A download that
    quietly dropped them would be a download that loses recorded fieldwork, and re-uploading it
    would then read as "the designer deleted these" — the round trip has to be lossless in both
    directions or it is not a round trip.
    """
    form = await load_form(questionnaire_id, include_retired=True)
    if form is None:
        return None

    labels: list[str] = []
    answers_by_question: dict[str, dict[str, str]] = {}
    notes_by_question: dict[str, dict[str, str]] = {}
    for entry in form["entries"]:
        label = _entry_label(entry, labels)
        labels.append(label)
        for answer in entry["answers"]:
            if answer["answerText"]:
                answers_by_question.setdefault(answer["questionId"], {})[label] = answer["answerText"]
            if answer["notes"]:
                notes_by_question.setdefault(answer["questionId"], {})[label] = answer["notes"]

    sections = [
        {
            "code": section["code"],
            "title": section["title"],
            "questions": [
                {
                    "id": q["id"],
                    "prompt": q["prompt"],
                    "helpText": q["helpText"],
                    "isRequired": q["isRequired"],
                    "answers": answers_by_question.get(q["id"], {}),
                    "answerNotes": notes_by_question.get(q["id"], {}),
                }
                for q in section["questions"]
            ],
        }
        for section in form["sections"]
    ]
    return {
        "title": form["title"],
        "description": form["description"],
        "questionnaire_id": form["id"],
        "version": form["version"],
        "sections": sections,
        "entry_labels": labels,
    }


async def load_question_set(questionnaire_id: str) -> dict[str, Any] | None:
    """The INSTRUMENT alone: sections, questions, order, help text, required flags. No fieldwork.

    ================================================================================================
    WHY THIS IS ITS OWN LOADER AND NOT ``load_form`` WITH THE SITTINGS DROPPED
    ================================================================================================

    Because a filter is a thing somebody can forget, and a query is not. ``load_form`` reads the
    entries and every answer under them and then hands them to its caller; a questions-only export
    built on top of it would be one careless ``dict`` spread — or one new key added to ``load_form``
    for some other screen — away from putting a respondent's name back into a file that is
    explicitly advertised as carrying none. This function never issues those two queries at all, so
    the artefact it feeds physically cannot contain an answer. That property is worth three extra
    lines of Prisma.

    ONLY ACTIVE SECTIONS AND ACTIVE QUESTIONS. A retired question is kept in the database because
    answers hang off it, not because it is still part of the instrument — see
    ``build_question_set_workbook`` for why sending one would plant a question the sender
    deliberately replaced into the receiver's brand-new form.

    THE QUESTIONS ARE RE-BUCKETED BY SECTION, and that is not tidying. ``sortOrder`` is scoped to the
    SECTION (each section's questions are numbered from 1), so this flat query — ordered by sortOrder
    across every section at once — comes back INTERLEAVED: A1, B1, A2, B2. ``report_items`` printed a
    ministry annexure that way once; the fix there is the same one made here, and any future reader
    of this table has to make it too.
    """
    questionnaire = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if questionnaire is None:
        return None

    sections = await db.questionnaireformsection.find_many(
        where={"questionnaireId": questionnaire_id, "isActive": True},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    section_ids = [s.id for s in sections]
    questions = (
        await db.questionnaireformquestion.find_many(
            where={"sectionId": {"in": section_ids}, "isActive": True},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        )
        if section_ids
        else []
    )
    by_section: dict[str, list[Any]] = {}
    for question in questions:
        by_section.setdefault(question.sectionId, []).append(question)

    return {
        "id": questionnaire.id,
        "title": questionnaire.title,
        "description": questionnaire.description,
        "ownerId": questionnaire.ownerId,
        "version": questionnaire.version,
        "sections": [
            {
                "code": section.code,
                "title": section.title,
                "questions": [
                    {
                        "id": q.id,
                        "prompt": q.prompt,
                        "helpText": q.helpText,
                        "isRequired": q.isRequired,
                        "sortOrder": q.sortOrder,
                    }
                    for q in by_section.get(section.id, [])
                ],
            }
            for section in sections
        ],
    }


async def export_question_set_payload(questionnaire_id: str) -> dict[str, Any] | None:
    """``load_question_set`` reshaped into what ``build_question_set_workbook`` wants.

    THE QUESTION ID IS BLANKED HERE, deliberately and visibly, rather than simply not being fetched.
    It is fetched — ``load_question_set`` returns it, because a JSON preview of "what is in this
    file" wants a stable key per row — and it is dropped at exactly the point the WORKBOOK is built.
    Keeping the drop on this line, next to the empty ``answers``, is what makes the whole rule
    readable in one place: what leaves this function is prompt, help text, required flag and
    position, and nothing that ties a row to the sender's database or to anybody's answer.
    """
    form = await load_question_set(questionnaire_id)
    if form is None:
        return None
    return {
        "title": form["title"],
        "description": form["description"],
        "source_title": form["title"],
        "sections": [
            {
                "code": section["code"],
                "title": section["title"],
                "questions": [
                    {
                        "id": "",
                        "prompt": q["prompt"],
                        "helpText": q["helpText"],
                        "isRequired": q["isRequired"],
                        "answers": {},
                        "answerNotes": {},
                    }
                    for q in section["questions"]
                ],
            }
            for section in form["sections"]
        ],
    }


def _entry_label(entry: dict[str, Any], taken: list[str]) -> str:
    """A column heading for one sitting, unique within the workbook.

    Uniqueness is not cosmetic: the heading is what pairs "Answer — Ramesh" back to "Notes — Ramesh"
    on re-upload, so two sittings sharing a heading would merge into one on the way back in.
    """
    base = (entry.get("respondentName") or entry.get("title") or "Answer").strip() or "Answer"
    # The parser splits a heading on dashes and colons to find this label, so a label carrying one
    # would come back truncated.
    base = base.replace("—", " ").replace("–", " ").replace("-", " ").replace(":", " ")
    base = " ".join(base.split())[:40] or "Answer"
    candidate = base
    n = 2
    while candidate in taken:
        candidate = f"{base} {n}"
        n += 1
    return candidate


# --- Writing: creating a questionnaire from an uploaded workbook --------------------------------


def _uploaded_answer_rows(parsed: ParsedQuestionnaire) -> int:
    """How many answer rows this workbook would write, counted exactly as the writer counts them.

    Derived rather than approximated by ``len(parsed.entryLabels)``: a workbook can carry six answer
    COLUMNS and one answer, and a number that said "six" would make the provenance sentence below
    lie about the size of what it just refused to import. ``answerNotes`` counts too — a row with
    only a note and no answer text still produces a row.
    """
    total = 0
    for label in parsed.entryLabels:
        for section in parsed.sections:
            for question in section.questions:
                if question.answers.get(label) or question.answerNotes.get(label):
                    total += 1
    return total


def _came_out_of_the_platform(parsed: ParsedQuestionnaire) -> str | None:
    """Whether this workbook is a DOWNLOAD of a questionnaire rather than a hand-filled pro-forma.

    Returns a short clause naming the evidence, or ``None`` when nothing says it came out of the app.

    TWO SIGNALS, BOTH OF THEM WRITTEN ONLY BY ``build_questionnaire_workbook``:

    1. ``Questionnaire ID`` on the Details sheet.
    2. Any filled-in cell in the ``Question ID`` column.

    The pro-forma writes both blank and the question-set export writes both blank, so a designer who
    typed their own paper interviews into a spreadsheet trips neither — which is the ordinary,
    documented, intended path and it must stay frictionless.

    THE SECOND SIGNAL IS NOT REDUNDANT. A designer who deletes one cell on the Details sheet, or who
    copies the Questionnaire sheet into a fresh file (losing the Details sheet entirely), would
    otherwise arrive here looking exactly like somebody's own typing while carrying a stranger's
    respondents. Neither signal can be removed without re-opening that door, and asking for both is
    the difference between a rule and a courtesy.
    """
    origin = (parsed.questionnaireId or "").strip()
    if origin:
        return f"its Details sheet names questionnaire {origin}"
    if any(
        (question.questionId or "").strip()
        for section in parsed.sections
        for question in section.questions
    ):
        return "its Question ID column is filled in"
    return None


async def create_from_parsed(
    parsed: ParsedQuestionnaire,
    *,
    owner_id: str,
    title: str | None = None,
    description: str | None = None,
    design_workshop_id: str | None = None,
    source_filename: str | None = None,
) -> tuple[str, dict[str, Any]]:
    """Store a freshly parsed workbook as a new questionnaire. Returns ``(id, change report)``.

    Answers the workbook already carried are stored as ordinary sittings with ``source="UPLOAD"``,
    which is what stops there being two different kinds of answer in the system: a designer who
    typed their interviews into the spreadsheet and one who typed them into the app end up with the
    same rows, so everything downstream — the edit rules, the export, the answer screen — needs no
    special case for either.

    ================================================================================================
    …UNLESS THE ANSWERS ARE ALREADY RECORDED SOMEWHERE ELSE. THIS PARAGRAPH IS A BUG FIX.
    ================================================================================================

    WHAT THIS USED TO DO. It ignored ``parsed.questionnaireId`` entirely and called
    ``_store_uploaded_answers`` unconditionally, with ``answeredById`` and ``createdById`` both set
    to the UPLOADER. So designer B, handed designer A's downloaded workbook — the only artefact that
    existed, because there was no questions-only export — uploaded it and silently acquired A's
    respondents' answers as B's own recorded fieldwork, under B's name, in B's report annexure. No
    screen said it had happened. Nobody edited an answer. The interview simply changed hands, and
    ``QUESTIONNAIRE_ANNEXURE`` is in all six report templates, so it changed hands into a document
    submitted to a ministry.

    THE RULE NOW, and the reasoning for it rather than a permission check. A workbook that came out
    of this platform (see ``_came_out_of_the_platform``) imports its QUESTIONS ONLY. Its answers are
    NOT re-recorded — not refused, not re-attributed, simply not written a second time — because
    they already exist in the database, attached to the questionnaire the file names, with their
    true authors on them. Writing them here would not be importing anything; it would be DUPLICATING
    somebody's fieldwork and stamping a new author on the copy, so the same interview would print
    twice under two different designers' names.

    NOTE THAT THIS HOLDS FOR THE OWNER TOO, and that is deliberate rather than an oversight about
    convenience. A designer who downloads their own answered questionnaire and uploads it as a NEW
    one is FORKING the instrument; the fork's sittings would be a second copy of the same fieldwork
    with a single re-stamped author, which is the same defect wearing a friendlier hat. A permission
    test would have admitted exactly that case, which is why the rule is about the DATA rather than
    about who is holding it.

    WHAT STILL WORKS UNCHANGED is the documented ordinary case: a designer who ran interviews on
    paper and typed them into a blank pro-forma. That file has no Questionnaire ID and no Question
    IDs, there is no other recorder anywhere in the picture, and attributing those answers to the
    person who typed them in is honest. Those imports are stamped with a provenance note on each
    sitting saying they arrived on a spreadsheet, so "attributed to the uploader" is stated on the
    record rather than merely being true.

    EITHER WAY THE REPORT SAYS WHICH HAPPENED. ``report["provenance"]`` carries a sentence written to
    be shown verbatim, and the same sentence is pushed into ``report["problems"]`` so that every
    client which already renders the parser's problem list tells the designer about this without
    needing to be changed first. Silence was the whole defect; it is not permitted in either branch.
    """
    questionnaire = await db.questionnaire.create(
        data={
            "title": (title or parsed.title or "Untitled questionnaire").strip(),
            "description": (description or parsed.description or None),
            "ownerId": owner_id,
            "designWorkshopId": design_workshop_id,
            "sourceFilename": source_filename,
        }
    )
    created_questions: dict[int, str] = {}  # row number -> question id, to attach uploaded answers
    codes_taken: set[str] = set()
    for index, section in enumerate(parsed.sections[:MAX_SECTIONS], start=1):
        code = _unique_code(section.code, section.title, codes_taken)
        made = await db.questionnaireformsection.create(
            data={
                "questionnaireId": questionnaire.id,
                "code": code,
                "title": section.title or code,
                "sortOrder": index,
            }
        )
        for position, question in enumerate(section.questions, start=1):
            row = await db.questionnaireformquestion.create(
                data={
                    "sectionId": made.id,
                    "prompt": question.prompt,
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "sortOrder": position,
                }
            )
            created_questions[question.row] = row.id

    problems = [p.payload() for p in parsed.problems]
    answer_rows = _uploaded_answer_rows(parsed)
    evidence = _came_out_of_the_platform(parsed) if answer_rows else None
    entries_created = 0
    answers_imported = 0
    provenance: dict[str, Any] | None = None

    if answer_rows and evidence:
        provenance = {
            "action": "answersNotImported",
            "sourceQuestionnaireId": (parsed.questionnaireId or "").strip() or None,
            "answersSkipped": answer_rows,
            "reason": (
                f"This workbook came out of the platform ({evidence}), and the {answer_rows} "
                "answers in it are fieldwork that is already recorded there, under the names of the "
                "people who recorded it. Its questions were imported and its answers were NOT: "
                "copying them into a second questionnaire would duplicate that fieldwork and record "
                "it under your name. Open the questionnaire they belong to in order to read them, "
                "or type your own interviews into a blank pro-forma."
            ),
        }
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": None,
                "severity": "warning",
                "reason": provenance["reason"],
                "value": None,
            }
        )
    elif answer_rows:
        entries_created, answers_imported = await _store_uploaded_answers(
            questionnaire.id, parsed, created_questions, owner_id, source_filename=source_filename
        )
        provenance = {
            "action": "answersImported",
            "sourceQuestionnaireId": None,
            "answersImported": answers_imported,
            "entriesCreated": entries_created,
            "reason": (
                f"{answers_imported} answers were already typed into this workbook, so they were "
                f"recorded as {entries_created} "
                + ("sitting" if entries_created == 1 else "sittings")
                + " and attributed to you. This file carries no Question IDs and no Questionnaire "
                "ID, which is what says it was filled in by hand rather than downloaded out of the "
                "platform — if somebody else recorded these interviews, say so in each sitting's "
                "notes, because the app can only attribute them to whoever uploaded the file."
            ),
        }
        # DELIBERATELY NOT ALSO PUSHED INTO ``problems``, unlike the branch above. A "problem" in this
        # report means a row of the workbook that could not be read cleanly, or one that was read but
        # not applied as written — and every client renders the list under headings that say exactly
        # that. Nothing went wrong here: the file was hand-filled, its answers were imported in full,
        # and they went to the only person they could honestly go to. Filing that under "rows the
        # import had to assume something about" would teach designers that a perfectly ordinary
        # upload produces warnings, which is the fastest way to make them stop reading the list that
        # does carry the rows they have lost.

    return questionnaire.id, {
        "created": len(created_questions),
        "sections": len(parsed.sections[:MAX_SECTIONS]),
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "unchanged": 0,
        "entriesCreated": entries_created,
        "answersImported": answers_imported,
        "answersSkipped": answer_rows if evidence else 0,
        "provenance": provenance,
        "versionBefore": questionnaire.version,
        "versionAfter": questionnaire.version,
        "problems": problems,
    }


def _unique_code(code: str, title: str, taken: set[str]) -> str:
    """A section code unique within its questionnaire.

    The database enforces ``@@unique([questionnaireId, code])``, so a workbook carrying "A" twice
    would otherwise fail the whole upload on the second insert with a bare 500 — after the first
    half of the designer's questionnaire had already been written.
    """
    wanted = (code or "").strip()
    if not wanted or wanted.lower() in taken:
        wanted = derive_section_code(title or wanted or "Section", taken)
    else:
        taken.add(wanted.lower())
    return wanted


async def _store_uploaded_answers(
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    question_ids: dict[int, str],
    user_id: str,
    *,
    source_filename: str | None = None,
) -> tuple[int, int]:
    """Answers that came in already filled on the spreadsheet, one sitting per answer column.

    Returns ``(sittings created, answer rows written)`` so the change report can state what happened
    with real numbers rather than with the number of answer COLUMNS, which is not the same thing.

    ONLY EVER REACHED FOR A HAND-FILLED WORKBOOK — see the long note in :func:`create_from_parsed`.
    Its caller decides; this function does not second-guess it, because two places deciding one rule
    is how the two drift.

    EVERY SITTING IT WRITES CARRIES A PROVENANCE NOTE. ``source = "UPLOAD"`` already records that the
    answers arrived on a spreadsheet, but only in a column no report prints, and ``answeredById`` is
    the uploader with nothing next to it to say that the uploader is where the attribution stops.
    The note names the file, prints in the report annexure beside the answers, and is the one thing a
    reader has to tell "I interviewed this person" from "I typed up a spreadsheet". It is only ever
    written where the sitting has no notes of its own — a sitting's notes belong to the interviewer.
    """
    if not parsed.entryLabels:
        return (0, 0)
    named = f" '{source_filename}'" if source_filename else ""
    note = (
        f"These answers arrived already filled in on the uploaded spreadsheet{named} and are "
        "attributed to the designer who uploaded it."
    )
    entries_created = 0
    answers_written = 0
    for label in parsed.entryLabels:
        rows: list[dict[str, Any]] = []
        for section in parsed.sections:
            for question in section.questions:
                question_id = question_ids.get(question.row)
                if question_id is None:
                    continue
                text = question.answers.get(label)
                note_text = question.answerNotes.get(label)
                if not text and not note_text:
                    continue
                rows.append(
                    {
                        "questionId": question_id,
                        "answerText": text,
                        "notes": note_text,
                        "answeredById": user_id,
                    }
                )
        if not rows:
            continue
        entry = await db.questionnaireformentry.create(
            data={
                "questionnaireId": questionnaire_id,
                "title": label,
                # A bare "Answer" column names nobody; a labelled one names the respondent.
                "respondentName": label if label != "Answer" else None,
                "source": UPLOAD_ENTRY_SOURCE,
                "notes": note,
                "createdById": user_id,
            }
        )
        await db.questionnaireformanswer.create_many(
            data=[row | {"entryId": entry.id} for row in rows]
        )
        entries_created += 1
        answers_written += len(rows)
    return (entries_created, answers_written)

# --- Writing: reusing one questionnaire at another workshop ---------------------------------------


#: The word the default reuse title is built round, and the one the collision check counts up from.
#: Held here rather than typed inline at the service and again at the dialog's pre-fill, so
#: "X (reused)" cannot come to mean two different strings in two places.
REUSE_TITLE_SUFFIX = "reused"


def reuse_title(source_title: str, taken: set[str]) -> str:
    """``"X (reused)"``, or ``"X (reused 2)"`` when that name is already taken at the target.

    COLLISION-AVOIDED RATHER THAN REFUSED. Reusing the same instrument at the SAME workshop is a
    legitimate act — a baseline round and a follow-up round, which a sitting has no notion of — and a
    refusal would be walkable in two clicks anyway (download the question set, upload it), producing
    the identical row with no stated provenance at all. The dialog warns before the press; this is
    what makes the two rows tellable apart afterwards.

    ``QuestionnaireReuse.title`` caps at 220 characters, so the base is trimmed to leave room for the
    suffix rather than letting a 218-character title produce a 226-character one that the database
    accepts and no list column can read.
    """
    base = (source_title or "Questionnaire").strip() or "Questionnaire"
    base = base[:200].rstrip()
    lowered = {t.strip().lower() for t in taken}
    candidate = f"{base} ({REUSE_TITLE_SUFFIX})"
    n = 2
    while candidate.lower() in lowered:
        candidate = f"{base} ({REUSE_TITLE_SUFFIX} {n})"
        n += 1
    return candidate


async def reuse_questionnaire(
    source_id: str,
    *,
    owner_id: str,
    design_workshop_id: str | None = None,
    title: str | None = None,
    description: str | None = None,
) -> tuple[str, dict[str, Any]] | None:
    """Copy a questionnaire's INSTRUMENT into a new row, optionally attached to another workshop.

    Returns ``(new questionnaire id, change report)``, or ``None`` when ``source_id`` names nothing.

    ================================================================================================
    WHY A COPY AND NOT A SECOND POINTER
    ================================================================================================

    The owner's request is that a designer may "use the same questionnaire later on for a different
    workshop as well in case they want to reuse the same template". Two shapes could serve that
    sentence and only one of them is safe here:

    * **A join table** (one questionnaire, many workshops) LEAKS FIELDWORK, because a SITTING has no
      workshop. ``QuestionnaireFormEntry.questionnaireId`` points at the QUESTIONNAIRE, and
      ``report_items`` selects ``{"designWorkshopId": ..., "isActive": True}`` with no permission
      filter — so every workshop's sittings would print in every attached workshop's annexure:
      workshop A's named respondents inside the .docx workshop B submits to a ministry. It also
      widens ``_works_on_this_questionnaires_workshop``, which reads a SINGULAR ``designWorkshopId``,
      into "any of n", so one viewer grant would admit its holder to every sitting at every attached
      workshop. And an edit at B would reach A: rewording an ANSWERED question SUPERSEDES it, which
      ADDS a question to A's live form that A never wrote, and retiring one at B retires it at A
      mid-fieldwork.
    * **A copy** — this function. Two rows, two section/question trees, two histories, and B's edit
      cannot reach A because there is no shared row to edit. It costs divergence (a typo fixed on one
      copy is not fixed on the other, and question ids differ, so comparing across workshops has to
      match on prompt text) and it needs no migration and no change to any access predicate.

    ================================================================================================
    ENTRIES AND ANSWERS ARE NEVER WRITTEN, AND THE QUERY IS WHAT GUARANTEES IT
    ================================================================================================

    The copy arrives with zero ``QuestionnaireFormEntry`` and zero ``QuestionnaireFormAnswer``. That
    is not a filter over a payload that had them in it: the source is read through
    ``load_question_set``, which NEVER ISSUES THE ENTRY OR ANSWER QUERIES AT ALL — its own
    docstring's reason, "a filter is a thing somebody can forget, and a query is not". Reading
    through ``load_form`` and dropping ``entries`` would work today and would break the first time
    somebody adds a key to ``load_form`` for another screen.

    This is settled policy in this module rather than a new judgement. ``create_from_parsed`` already
    refuses to re-record answers that exist elsewhere, and it argues that rule ABOUT THE DATA rather
    than about who is holding it — "a permission test would have admitted exactly that case" — which
    is why it binds the source's own owner reusing their own form just as firmly as a colleague.

    ``retiredAt`` and ``supersededById`` are left NULL for a second, sharper reason: copying a
    ``supersededById`` would point a row in the NEW questionnaire at a row in the SOURCE one, and
    ``_question_in`` exists precisely to stop cross-questionnaire question pointers. ``version``
    starts at 1 because the copy has no edit history yet, and retired sections and questions are
    excluded because a retired question is kept for the answers hanging off it, not because it is
    still part of the instrument (see ``build_question_set_workbook``).

    ``owner_id`` IS THE CALLER, not the source's owner. ``_require_owner`` governs rewording, so a
    copy its maker could not reword would not be a reuse — they would have to ask the original's
    author to change a form the author is not using.

    ALL OF IT OR NONE OF IT. The row, its sections and its questions are written inside one
    ``db.tx()`` and in four statements rather than one per question, because a copy that got half way
    is indistinguishable from a whole one: the designer sees an error, retries, and is left owning a
    truncated instrument beside a complete "(reused 2)" with nothing on any screen able to say which
    is short. The bounds are the parse path's own (``MAX_SECTIONS``/``MAX_QUESTIONS``), and a
    truncation goes into ``problems`` rather than passing silently.

    THE REPORT IS THE UPLOAD REPORT'S SHAPE, key for key, so ``QFormUploadReport`` types it and the
    existing ``UploadReport`` panel renders it with no new component. ``provenance.reason`` is
    written here to be shown VERBATIM, and it goes in ``provenance`` ONLY — deliberately NOT pushed
    into ``problems`` the way ``create_from_parsed``'s refusal branch is. Nothing went wrong here and
    there is no workbook: filing a successful reuse under "rows that could not be read" is how
    designers learn to stop reading the list that does carry the rows they have lost.
    """
    form = await load_question_set(source_id)
    if form is None:
        return None

    chosen = (title or "").strip()
    if not chosen:
        # THE TITLES ALREADY IN THE PLACE THIS COPY IS GOING, so the default name counts itself up
        # instead of producing a second row indistinguishable from the first in every list. Scoped to
        # the TARGET: at a workshop, the forms attached to that workshop (whoever owns them, because
        # the report annexure prints them all); with no target, this designer's own unattached
        # templates, which is the only place an unattached copy shows up.
        #
        # ``isActive`` is deliberately NOT filtered. A deactivated form is hidden from the lists but
        # its title is still the title of a row somebody may bring back into use, and colliding with
        # it would be a collision that appears later, out of nowhere.
        where: dict[str, Any] = (
            {"designWorkshopId": design_workshop_id}
            if design_workshop_id
            else {"designWorkshopId": None, "ownerId": owner_id}
        )
        # ORDERED AND BOUNDED, in that order of importance. The bound stops a workshop with a long
        # history from being read in full to pick one name; the ordering is what makes the 500 rows it
        # keeps the NEWEST 500 rather than whichever 500 the planner handed back, so the names most
        # likely to be collided with — the ones made recently, by this round of work — are the ones
        # actually counted against. Past the bound the copy can still take a duplicate title, which is
        # a naming annoyance and not a data fault: nothing in the schema makes a title unique, and the
        # dialog's own warning is a second chance to catch it.
        neighbours = await db.questionnaire.find_many(
            where=where, take=500, order=[{"createdAt": "desc"}]
        )
        chosen = reuse_title(form["title"], {row.title for row in neighbours})

    # ============================================================================================
    # THE COPY IS ONE TRANSACTION, IN FOUR ROUND TRIPS, AND BOUNDED
    # ============================================================================================
    #
    # WHY A TRANSACTION. This used to be a bare ``create`` followed by one ``create`` per section and
    # one per question — up to 2200 sequential statements, none of them in a transaction, against a
    # database ``services/records.py`` describes as sitting in another region. A request that timed
    # out at question 900 of 2000 left the ``Questionnaire`` row and 900 questions COMMITTED,
    # attached to the target workshop and owned by the caller, while the client showed an error: the
    # designer retries, and now owns a truncated instrument next to a complete "(reused 2)" with
    # nothing on any screen able to say which of the two is short. A half-copied instrument is worse
    # than no copy, because it is indistinguishable from a whole one — the same argument this
    # module's report makes about silent success, one level down. Either the whole copy exists or
    # none of it does.
    #
    # WHY BATCHED. ``create_many`` is already how this file writes answers (see
    # ``_record_uploaded_answers``), and it turns the copy into four statements whatever the size of
    # the instrument: the row, its sections, a read-back for their ids, and every question at once.
    # That is also what keeps the transaction short enough to be an honest transaction rather than a
    # lock held open across a thousand round trips.
    #
    # THE SECTIONS ARE READ BACK BY ``code`` because ``create_many`` returns a count and not rows,
    # and ``@@unique([questionnaireId, code])`` is what makes that mapping exact — the source's own
    # codes are unique within the source questionnaire for the same reason, so no two sections of
    # this copy can claim one id.
    #
    # BOUNDED, with the parse path's own bounds. ``create_from_parsed`` and ``apply_parsed_edit``
    # both slice ``[:MAX_SECTIONS]`` where they walk sections; this third writer into the same
    # tables had no bound at all, so a source assembled through some other door could hand it a tree
    # larger than any workbook is allowed to produce. A truncation is REPORTED rather than silent:
    # ``problems`` is rendered by the panel that already exists for exactly this, and a copy that
    # dropped the tail of an instrument must not look like a complete one.
    sections_in = form["sections"][:MAX_SECTIONS]
    sections_dropped = len(form["sections"]) - len(sections_in)

    section_data: list[dict[str, Any]] = []
    kept_by_code: dict[str, list[dict[str, Any]]] = {}
    budget = MAX_QUESTIONS
    questions_dropped = 0
    for index, section in enumerate(sections_in, start=1):
        section_data.append(
            {
                # The source's code, which is safe because ``@@unique([questionnaireId, code])`` is
                # scoped to the questionnaire — and worth keeping, because a section code is what a
                # designer comparing the two instruments reads across.
                "code": section["code"],
                "title": section["title"],
                # Re-numbered from 1 rather than carried across. ``load_question_set`` returns ACTIVE
                # sections only, so the source's own numbers can have gaps where a retired section
                # used to sit, and a copy carrying the gaps sorts the same but reads wrong.
                "sortOrder": index,
            }
        )
        kept = section["questions"][: max(budget, 0)]
        questions_dropped += len(section["questions"]) - len(kept)
        budget -= len(kept)
        kept_by_code[section["code"]] = kept

    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
        made = await tx.questionnaire.create(
            data={
                "title": chosen,
                # An explicitly sent description wins; otherwise the source's travels with the
                # questions, because it is part of how the instrument reads.
                "description": (description if description is not None else form["description"])
                or None,
                "ownerId": owner_id,
                "designWorkshopId": design_workshop_id,
                # NOT the source's ``sourceFilename``. There is no spreadsheet behind this row, and
                # naming one would send a designer off to edit a file that produced something else.
                "sourceFilename": None,
            }
        )
        question_data: list[dict[str, Any]] = []
        if section_data:
            await tx.questionnaireformsection.create_many(
                data=[row | {"questionnaireId": made.id} for row in section_data]
            )
            written = await tx.questionnaireformsection.find_many(
                where={"questionnaireId": made.id}
            )
            id_by_code = {row.code: row.id for row in written}
            question_data = [
                {
                    "sectionId": id_by_code[code],
                    "prompt": question["prompt"],
                    "helpText": question["helpText"],
                    "isRequired": question["isRequired"],
                    "sortOrder": position,
                }
                for code, questions in kept_by_code.items()
                for position, question in enumerate(questions, start=1)
            ]
            if question_data:
                await tx.questionnaireformquestion.create_many(data=question_data)

    sections_made = len(section_data)
    questions_made = len(question_data)

    # WHAT THE TWO ROWS NOW ARE, said in the server's own sentence and shown verbatim.
    #
    # THE ZERO CASE HAS ITS OWN SENTENCE. "carrying the 0 questions of “X”" was reachable — a source
    # whose every question has been retired copies nothing, and so does one nobody has added a
    # question to yet — and it was the one ungrammatical string in a message the client is required
    # to print as it stands. It also has to avoid CLAIMING retirement, because "no questions yet" and
    # "every question retired" both arrive here.
    settled = (
        " The two are separate from here on: editing one does not change the other. No sitting and "
        "no answer was copied — the fieldwork recorded against the original stays on the original, "
        "under the names of the people who recorded it — so this copy starts empty and ready for "
        "its own."
    )
    if questions_made:
        reason = (
            f"This is a new questionnaire carrying the {questions_made} "
            + ("question" if questions_made == 1 else "questions")
            + f" of “{form['title']}”." + settled
        )
    else:
        reason = (
            f"This is a new questionnaire with no questions in it: “{form['title']}” has none "
            "still being asked — they were retired, or none were ever added — and a retired "
            "question is kept where its answers are rather than copied." + settled
        )

    # THE TAIL THAT DID NOT FIT, NAMED. Empty in every ordinary reuse; the client already draws this
    # list, so saying nothing here is the one thing that would make a truncated copy pass for whole.
    problems: list[dict[str, Any]] = []
    for count, unit, ceiling in (
        (sections_dropped, "section", MAX_SECTIONS),
        (questions_dropped, "question", MAX_QUESTIONS),
    ):
        if count > 0:
            problems.append(
                {
                    "sheet": None,
                    "row": None,
                    "severity": "warning",
                    "reason": (
                        f"“{form['title']}” has more than {ceiling} {unit}s, which is the most one "
                        f"questionnaire may hold. {count} {unit}"
                        + ("" if count == 1 else "s")
                        + " at the end of it were not copied. The original still has all of them."
                    ),
                    "value": None,
                }
            )

    return made.id, {
        "created": questions_made,
        "sections": sections_made,
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "unchanged": 0,
        "entriesCreated": 0,
        "answersImported": 0,
        "answersSkipped": 0,
        "provenance": {
            "action": "reused",
            "sourceQuestionnaireId": source_id,
            # ZERO, and STATED rather than omitted. "No answers were copied" and "this report does
            # not mention answers" read identically, and only the first of them is a fact.
            "answersSkipped": 0,
            "reason": reason,
        },
        "versionBefore": made.version,
        "versionAfter": made.version,
        # PRESENT EVEN WHEN EMPTY, which is the ordinary case here. Every client already renders
        # this list, and a report that omitted the key would make "nothing went wrong"
        # indistinguishable from "the key was never filled in" — and would hide the one thing this
        # list carries for a reuse: an instrument too large to have been copied whole.
        "problems": problems,
    }

# --- Writing: applying an edit to a questionnaire that may already have answers ------------------


async def apply_parsed_edit(
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    *,
    user_id: str,
    title: str | None = None,
    description: str | None = None,
) -> dict[str, Any]:
    """Re-import a workbook over an existing questionnaire under the edit-after-answers rule.

    See the module docstring for the rule and for why it is the one it is. The returned report names
    every question that was created, reworded-into-a-new-question, retired or removed, because a
    designer who uploads a corrected spreadsheet needs to be told what the app decided to do with
    their corrections — an edit that silently supersedes six questions is indistinguishable, from
    the outside, from one that silently lost them.
    """
    existing = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if existing is None:
        raise QuestionnaireEditError("That questionnaire no longer exists.")

    sections = await db.questionnaireformsection.find_many(
        where={"questionnaireId": questionnaire_id},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
    )
    section_ids = [s.id for s in sections]
    questions = (
        await db.questionnaireformquestion.find_many(
            where={"sectionId": {"in": section_ids}},
            # ORDERED, and not for tidiness. `by_prompt` below keeps the FIRST question it sees for
            # a given wording, so an unordered read would resolve a duplicate prompt differently on
            # different runs — the same upload editing a different question depending on what
            # Postgres felt like returning first.
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}],
        )
        if section_ids
        else []
    )
    answered = await _answered_question_ids([q.id for q in questions])

    problems = [p.payload() for p in parsed.problems]
    by_id = {q.id: q for q in questions}
    section_by_id = {s.id: s for s in sections}
    section_by_code = {s.code.strip().lower(): s for s in sections}
    # (section id, prompt) -> question, for the no-Question-ID fallback described in the docstring.
    # ACTIVE QUESTIONS ONLY. A retired question keeps its original wording for ever, so a designer
    # who reuses that wording for a new question would otherwise have it silently matched onto the
    # retired row — reactivating a question they deliberately replaced, next to its replacement.
    by_prompt: dict[tuple[str, str], Any] = {}
    for question in questions:
        if question.isActive:
            by_prompt.setdefault((question.sectionId, question.prompt.strip().lower()), question)

    report = {
        "created": 0,
        "updated": 0,
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "unchanged": 0,
        "sections": 0,
        "versionBefore": existing.version,
        "versionAfter": existing.version,
        "problems": problems,
        "details": [],
    }
    version_bumps = 0
    touched_questions: set[str] = set()
    touched_sections: set[str] = set()
    codes_taken = {s.code.strip().lower() for s in sections}

    for index, parsed_section in enumerate(parsed.sections[:MAX_SECTIONS], start=1):
        code = (parsed_section.code or "").strip()
        section = section_by_code.get(code.lower()) if code else None
        if section is None:
            new_code = _unique_code(code, parsed_section.title, codes_taken)
            section = await db.questionnaireformsection.create(
                data={
                    "questionnaireId": questionnaire_id,
                    "code": new_code,
                    "title": parsed_section.title or new_code,
                    "sortOrder": index,
                }
            )
            section_by_code[new_code.lower()] = section
            section_by_id[section.id] = section
            report["sections"] += 1
        else:
            # A section title may change even when answered: a heading is not the thing an answer
            # answers. Reactivated on sight, so re-uploading a section a designer had removed brings
            # it back rather than leaving a retired duplicate.
            wanted_title = parsed_section.title or section.title
            if section.title != wanted_title or section.sortOrder != index or not section.isActive:
                section = await db.questionnaireformsection.update(
                    where={"id": section.id},
                    data={"title": wanted_title, "sortOrder": index, "isActive": True},
                )
        touched_sections.add(section.id)

        for position, parsed_question in enumerate(parsed_section.questions, start=1):
            match = _match_question(parsed_question, section, by_id, by_prompt, problems, parsed)
            if match is None:
                await db.questionnaireformquestion.create(
                    data={
                        "sectionId": section.id,
                        "prompt": parsed_question.prompt,
                        "helpText": parsed_question.helpText,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                report["created"] += 1
                continue

            touched_questions.add(match.id)
            reworded = match.prompt.strip() != parsed_question.prompt.strip()

            if not match.isActive:
                # A RETIRED question, matched by the id in the file. The export deliberately writes
                # retired questions out — dropping them would lose the answers recorded against
                # them — so every download carries these rows and every re-upload matches them. They
                # are therefore LEFT ALONE. Reactivating on sight, which the section branch above can
                # safely do, would here mean that downloading a questionnaire and uploading it back
                # unchanged resurrects every question the designer has ever replaced, each one next
                # to the replacement that superseded it.
                if reworded:
                    # Not applied, and therefore said out loud rather than dropped in silence.
                    problems.append(
                        {
                            "sheet": parsed.sheet,
                            "row": parsed_question.row,
                            "severity": "warning",
                            "reason": (
                                "This question was retired because it already had answers recorded "
                                "against it, so the new wording on this row was NOT applied. Edit "
                                + (
                                    "the question that replaced it"
                                    if match.supersededById
                                    else "an active question"
                                )
                                + " instead, or add a new row with a blank Question ID."
                            ),
                            "value": parsed_question.prompt[:120],
                        }
                    )
                report["unchanged"] += 1
                continue

            if reworded and match.id in answered:
                # RULE 3. The recorded answers keep the wording they were given under; the new
                # wording becomes a new question in the same place.
                replacement = await db.questionnaireformquestion.create(
                    data={
                        "sectionId": section.id,
                        "prompt": parsed_question.prompt,
                        "helpText": parsed_question.helpText,
                        "isRequired": parsed_question.isRequired,
                        "sortOrder": position,
                    }
                )
                await db.questionnaireformquestion.update(
                    where={"id": match.id},
                    data={
                        "isActive": False,
                        "retiredAt": _now(),
                        "supersededById": replacement.id,
                    },
                )
                touched_questions.add(replacement.id)
                report["superseded"] += 1
                version_bumps += 1
                report["details"].append(
                    {
                        "action": "superseded",
                        "questionId": match.id,
                        "replacementId": replacement.id,
                        "before": match.prompt,
                        "after": parsed_question.prompt,
                        "reason": (
                            "This question already has answers recorded against it, so its original "
                            "wording and those answers were kept and your new wording was added as a "
                            "new question."
                        ),
                    }
                )
                continue

            data: dict[str, Any] = {}
            if reworded:
                data["prompt"] = parsed_question.prompt  # RULE 1: nobody has answered it
            if match.helpText != parsed_question.helpText:
                data["helpText"] = parsed_question.helpText
            if match.isRequired != parsed_question.isRequired:
                data["isRequired"] = parsed_question.isRequired
            if match.sortOrder != position:
                data["sortOrder"] = position
            if match.sectionId != section.id:
                data["sectionId"] = section.id
            # No isActive branch here: a retired question was handled and skipped above. Bringing one
            # back is a deliberate act through PATCH, never a side effect of re-uploading a file that
            # always contained it.
            if data:
                await db.questionnaireformquestion.update(where={"id": match.id}, data=data)
                report["updated"] += 1
            else:
                report["unchanged"] += 1

    version_bumps += await _remove_absent(
        questions, sections, answered, touched_questions, touched_sections, report
    )

    updates: dict[str, Any] = {}
    if title and title.strip() and title.strip() != existing.title:
        updates["title"] = title.strip()
    elif parsed.title and parsed.title.strip() != existing.title:
        updates["title"] = parsed.title.strip()
    if description is not None and description != existing.description:
        updates["description"] = description
    elif parsed.description and parsed.description != existing.description:
        updates["description"] = parsed.description
    if version_bumps:
        updates["version"] = existing.version + version_bumps
    if updates:
        await db.questionnaire.update(where={"id": questionnaire_id}, data=updates)
    report["versionAfter"] = existing.version + version_bumps
    return report


def _match_question(
    parsed_question: Any,
    section: Any,
    by_id: dict[str, Any],
    by_prompt: dict[tuple[str, str], Any],
    problems: list[dict[str, Any]],
    parsed: ParsedQuestionnaire,
) -> Any | None:
    """Which stored question this spreadsheet row is, or None if it is new. See the docstring."""
    wanted = (parsed_question.questionId or "").strip()
    if wanted:
        found = by_id.get(wanted)
        if found is not None:
            return found
        # An id from somebody else's questionnaire, or from a form that has since been deleted.
        # Honouring it would graft another designer's question onto this form; refusing the whole
        # upload over one stale cell would be worse. Import the row as new and say so.
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": parsed_question.row,
                "severity": "warning",
                "reason": (
                    f"Question ID '{wanted}' does not belong to this questionnaire, so the row was "
                    "imported as a new question. If you meant to edit an existing question, "
                    "download this questionnaire again and edit that copy."
                ),
                "value": parsed_question.prompt[:120],
            }
        )
        return None
    return by_prompt.get((section.id, parsed_question.prompt.strip().lower()))


async def _remove_absent(
    questions: list[Any],
    sections: list[Any],
    answered: set[str],
    touched_questions: set[str],
    touched_sections: set[str],
    report: dict[str, Any],
) -> int:
    """RULE 4 and RULE 5: what happens to rows the designer took out of the spreadsheet.

    Answered -> retired, and its answers stay. Unanswered -> actually deleted, because there is
    nothing to orphan and a form littered with every question its author ever thought better of is
    not a form. Returns how many version bumps this earned.
    """
    bumps = 0
    for question in questions:
        if question.id in touched_questions:
            continue
        if question.id in answered:
            if question.isActive:
                await db.questionnaireformquestion.update(
                    where={"id": question.id},
                    data={"isActive": False, "retiredAt": _now()},
                )
                report["retired"] += 1
                bumps += 1
                report["details"].append(
                    {
                        "action": "retired",
                        "questionId": question.id,
                        "before": question.prompt,
                        "reason": (
                            "This question was removed from your spreadsheet but already has answers "
                            "recorded against it, so it was retired rather than deleted. It is no "
                            "longer asked, and its answers are still in the record."
                        ),
                    }
                )
            continue
        await db.questionnaireformquestion.delete(where={"id": question.id})
        report["removed"] += 1

    answered_sections = {q.sectionId for q in questions if q.id in answered}
    for section in sections:
        if section.id in touched_sections:
            continue
        if section.id in answered_sections:
            if section.isActive:
                await db.questionnaireformsection.update(
                    where={"id": section.id}, data={"isActive": False}
                )
            continue
        # Safe to delete outright: nothing under it was ever answered, so the cascade to its
        # questions cannot hit the ON DELETE RESTRICT that guards an answered one.
        await db.questionnaireformsection.delete(where={"id": section.id})
    return bumps


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
    """Which of these questions have an answer with actual text against them.

    A blank answer row does not count. The app writes one when a designer opens a sitting, tabs
    through it and saves; treating that as "answered" would freeze a question nobody ever answered.
    """
    if not question_ids:
        return set()
    rows = await db.questionnaireformanswer.find_many(where={"questionId": {"in": question_ids}})
    # Filtered here rather than in the where clause: "not null" is not the test — an answer saved as
    # a single space is null-ish to a person and non-null to Postgres, and only Python knows that.
    return {row.questionId for row in rows if (row.answerText or "").strip()}


# --- Writing: single-question edits from the editor UI -------------------------------------------


async def guard_question_edit(question: Any, *, new_prompt: str | None, deleting: bool) -> str | None:
    """The same rule, for the one-question-at-a-time editor rather than a re-upload.

    Returns the ACTION the caller must take instead of the naive one — ``"supersede"`` or
    ``"retire"`` — or ``None`` when the plain edit is safe. Stated as one function so the endpoint
    and the upload path cannot drift into two different answers to the same question.
    """
    answered = await _answered_question_ids([question.id])
    if not answered:
        return None
    if deleting:
        return "retire"
    if new_prompt is not None and new_prompt.strip() != question.prompt.strip():
        return "supersede"
    return None


async def supersede_question(question: Any, *, prompt: str, **fields: Any) -> Any:
    """Retire ``question`` and create its replacement in the same place. RULE 3, single-question form."""
    replacement = await db.questionnaireformquestion.create(
        data={
            "sectionId": question.sectionId,
            "prompt": prompt,
            "helpText": fields.get("helpText", question.helpText),
            "isRequired": fields.get("isRequired", question.isRequired),
            "sortOrder": fields.get("sortOrder", question.sortOrder),
        }
    )
    await db.questionnaireformquestion.update(
        where={"id": question.id},
        data={"isActive": False, "retiredAt": _now(), "supersededById": replacement.id},
    )
    return replacement


async def bump_version(questionnaire_id: str) -> None:
    """One more destructive-ish edit applied after answers existed. See the schema comment."""
    await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"version": {"increment": 1}}
    )


# --- Writing: recording answers ------------------------------------------------------------------


async def save_answers(
    entry: Any,
    answers: list[Any],
    *,
    user_id: str,
    is_admin: bool = False,
) -> dict[str, Any]:
    """Record a batch of answers against one sitting. Idempotent, and cheap in round trips.

    Modelled on ``upsert_responses`` in the questionnaire route, and for the reasons named there: a
    section carries dozens of questions and the client submits the whole section, so validating and
    reading per answer turned one save into a hundred sequential cross-region round trips. All the
    validation happens in one statement, all the reading in one more, every genuinely new answer
    goes in with a single insert, and the only per-row writes left are the answers whose text
    actually changed.

    Skipping unchanged rows is not only an optimisation: it stops a save that touched one answer
    from re-stamping ``answeredById`` across the whole section and taking authorship of work the
    saver never did.

    RETIRED QUESTIONS ARE REFUSED. A question that has been superseded or retired keeps the answers
    it already has, and must not collect new ones — otherwise the wording a designer deliberately
    replaced quietly carries on gathering evidence.
    """
    if not answers:
        return {"created": 0, "updated": 0, "unchanged": 0}

    question_ids = sorted({a.questionId for a in answers if a.questionId})
    existing_rows, questions = await gather_reads(
        db.questionnaireformanswer.find_many(
            where={"entryId": entry.id, "questionId": {"in": question_ids}}
        ),
        db.questionnaireformquestion.find_many(
            where={"id": {"in": question_ids}}, include={"section": True}
        ),
    )
    known = {q.id: q for q in questions}
    missing = [qid for qid in question_ids if qid not in known]
    if missing:
        raise QuestionnaireEditError(
            f"{len(missing)} of those questions are not in this questionnaire."
        )
    foreign = [
        q.id
        for q in questions
        if getattr(q.section, "questionnaireId", None) != entry.questionnaireId
    ]
    if foreign:
        # The failure this prevents: an answer sheet for questionnaire A quietly accumulating
        # answers to questionnaire B's questions, which then appear in A's export.
        raise QuestionnaireEditError(
            "Some of those questions belong to a different questionnaire."
        )
    retired = [q.prompt for q in questions if not q.isActive]
    if retired:
        raise QuestionnaireEditError(
            "These questions have been retired and can no longer be answered: "
            + "; ".join(retired[:3])
            + ("…" if len(retired) > 3 else "")
        )

    by_question = {row.questionId: row for row in existing_rows}
    to_create: list[dict[str, Any]] = []
    to_update: list[tuple[str, dict[str, Any]]] = []
    unchanged = 0
    # ONE ENTRY PER QUESTION, LAST WINS — the same collapse the validation above already performs
    # with ``sorted({...})``. ``QuestionnaireFormAnswer`` is UNIQUE on ``(entryId, questionId)``, so a
    # body naming one question twice built two INSERTs for it and the save came back as a bare 500
    # instead of a saved sitting. Last wins rather than ``skip_duplicates`` (which would drop an
    # answer the designer typed) and rather than first-wins (which would contradict the update
    # branch below, where two entries for one stored row run two updates and the later one stands).
    deduped = {answer.questionId: answer for answer in answers}
    for answer in deduped.values():
        current = by_question.get(answer.questionId)
        if current is None:
            to_create.append(
                {
                    "entryId": entry.id,
                    "questionId": answer.questionId,
                    "answerText": answer.answerText,
                    "notes": answer.notes,
                    "answeredById": user_id,
                }
            )
            continue
        if current.answerText and current.answeredById != user_id and not is_admin:
            raise QuestionnaireEditError(
                "Only the person who recorded this answer, or an admin, can change it."
            )
        if current.answerText == answer.answerText and current.notes == answer.notes:
            unchanged += 1
            continue
        to_update.append(
            (
                current.id,
                {
                    "answerText": answer.answerText,
                    "notes": answer.notes,
                    "answeredById": user_id,
                },
            )
        )

    # Validation for the WHOLE batch has already run, so nothing below can leave a half-written set.
    if to_create:
        await db.questionnaireformanswer.create_many(data=to_create)
    for row_id, data in to_update:
        await db.questionnaireformanswer.update(where={"id": row_id}, data=data)
    return {"created": len(to_create), "updated": len(to_update), "unchanged": unchanged}
