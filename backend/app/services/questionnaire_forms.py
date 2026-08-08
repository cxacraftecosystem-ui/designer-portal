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

from datetime import UTC, datetime
from typing import Any

from app.core.db import db
from app.services.concurrency import gather_reads
from app.services.questionnaire_xlsx import (
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

    section_of = {s.id: s for s in sections}
    # THE ORDER OF THIS LIST IS THE ORDER THE ANNEXURE PRINTS, and it is built once here rather than
    # sorted per sitting: section position first, then question position, which is the order the
    # designer wrote the form in and the order the .xlsx download uses. A sitting is then a lookup
    # against it, so two sittings of the same form cannot come out in two different orders — which,
    # in a document read side by side with another respondent's answers, is what makes it comparable.
    ordered: dict[str, list[Any]] = {}
    for question in questions or []:
        section = section_of.get(question.sectionId)
        if section is None:
            continue
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

    await _store_uploaded_answers(questionnaire.id, parsed, created_questions, owner_id)
    return questionnaire.id, {
        "created": len(created_questions),
        "sections": len(parsed.sections[:MAX_SECTIONS]),
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "unchanged": 0,
        "versionBefore": questionnaire.version,
        "versionAfter": questionnaire.version,
        "problems": [p.payload() for p in parsed.problems],
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
) -> None:
    """Answers that came in already filled on the spreadsheet, one sitting per answer column."""
    if not parsed.entryLabels:
        return
    for label in parsed.entryLabels:
        rows: list[dict[str, Any]] = []
        for section in parsed.sections:
            for question in section.questions:
                question_id = question_ids.get(question.row)
                if question_id is None:
                    continue
                text = question.answers.get(label)
                note = question.answerNotes.get(label)
                if not text and not note:
                    continue
                rows.append(
                    {
                        "questionId": question_id,
                        "answerText": text,
                        "notes": note,
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
                "createdById": user_id,
            }
        )
        await db.questionnaireformanswer.create_many(
            data=[row | {"entryId": entry.id} for row in rows]
        )


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
    for answer in answers:
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
