from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from types import SimpleNamespace

from app.core.db import db
from app.questionnaire import forms as unified_forms
from app.services.concurrency import gather_reads
from app.services.cross_portal_codes import question_code
from app.services.questionnaire_kinds import label_for
from app.services.questionnaire_xlsx import (
    MAX_QUESTIONS,
    MAX_SECTIONS,
    ParsedQuestionnaire,
    derive_section_code,
)

from app.services.report_questionnaires import (
    QuestionnaireAnswer,
    QuestionnaireItem,
    QuestionnaireSitting,
)

UPLOAD_ENTRY_SOURCE = "UPLOAD"
APP_ENTRY_SOURCE = "APP"


class QuestionnaireEditError(ValueError):
    pass


def _now() -> datetime:
    return datetime.now(UTC)


def visible_questionnaire_where(user: Any) -> dict[str, Any]:
    return {
        "OR": [
            {"ownerId": user.id},
            {"designWorkshop": {"is": {"createdById": user.id, "deletedAt": None}}},
            {"designWorkshop": {"is": {"deletedAt": None, "viewers": {"some": {"userId": user.id}}}}},
            {"isShared": True, "isActive": True},
        ]
    }



async def load_form(
    questionnaire_id: str,
    *,
    include_retired: bool = False,
    only_entries_of: str | None = None,
) -> dict[str, Any] | None:
    questionnaire = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if questionnaire is None or questionnaire.deletedAt is not None:
        return None

    unified = await unified_forms.form_unified(questionnaire_id)
    T = unified_forms.tables(db, unified)
    sections = await T.sections.find_many(
        where={"questionnaireId": questionnaire_id},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
    )
    section_ids = [s.id for s in sections]
    questions = (
        await T.questions.find_many(
            where={"sectionId": {"in": section_ids}},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
        )
        if section_ids
        else []
    )
    if unified:
        from app.questionnaire import shims_dr

        entries = [SimpleNamespace(**{**e, "deletedAt": None, "createdBy": None}) for e in await shims_dr.entries_for_form(questionnaire_id)]
        answers = [SimpleNamespace(**a) for e in entries for a in (e.answers or [])]
        answered_ids = await unified_forms.answered_question_ids(db, [q.id for q in questions])
    else:
        entries = await db.questionnaireformentry.find_many(
            where={"questionnaireId": questionnaire_id},
            order={"createdAt": "asc"},
            include={"createdBy": True},
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
                        "hasAnswers": q.id in answered_ids,
                    }
                    for q in by_section.get(section.id, [])
                ],
            }
        )

    answers_by_entry: dict[str, list[Any]] = {}
    for answer in answers:
        answers_by_entry.setdefault(answer.entryId, []).append(answer)

    visible_entries = [
        e
        for e in entries
        if e.deletedAt is None and (only_entries_of is None or e.createdById == only_entries_of)
    ]

    return {
        "id": questionnaire.id,
        "title": questionnaire.title,
        "description": questionnaire.description,
        "ownerId": questionnaire.ownerId,
        "designWorkshopId": questionnaire.designWorkshopId,
        "isActive": questionnaire.isActive,
        "isShared": questionnaire.isShared,
        "isDefault": bool(getattr(questionnaire, "isDefault", False)),
        "kind": questionnaire.kind,
        "kindLabel": label_for(questionnaire.kind),
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
    questionnaires = await db.questionnaire.find_many(
        where={"designWorkshopId": design_workshop_id, "isActive": True, "deletedAt": None},
        order={"createdAt": "asc"},
    )
    from app.services import questionnaire_readers

    bound = await questionnaire_readers.forms_for_design_workshop(design_workshop_id)
    attached = {q.id for q in questionnaires}
    extra_ids = [str(b["id"]) for b in bound if str(b["id"]) not in attached]
    if extra_ids:
        questionnaires = [*questionnaires, *await db.questionnaire.find_many(
            where={"id": {"in": extra_ids}}, order={"createdAt": "asc"}
        )]
    if not questionnaires:
        return []
    v2_sittings = await questionnaire_readers.annexure_sittings(
        [q.id for q in questionnaires], design_workshop_id=design_workshop_id
    )

    ids = [q.id for q in questionnaires]
    unified_ids = await unified_forms.unified_form_ids(ids)
    legacy_ids = [i for i in ids if i not in unified_ids]
    order = [{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}]
    legacy_sections, unified_sections, entries = await gather_reads(
        db.questionnaireformsection.find_many(where={"questionnaireId": {"in": legacy_ids}}, order=order) if legacy_ids else _none(),
        db.questionnairesection.find_many(where={"questionnaireId": {"in": sorted(unified_ids)}}, order=order) if unified_ids else _none(),
        db.questionnaireformentry.find_many(
            where={"questionnaireId": {"in": legacy_ids}, "deletedAt": None},
            order={"createdAt": "asc"},
            include={"createdBy": True},
        )
        if legacy_ids
        else _none(),
    )
    sections = [*(legacy_sections or []), *(unified_sections or [])]
    legacy_section_ids = [s.id for s in legacy_sections or []]
    unified_section_ids = [s.id for s in unified_sections or []]
    entry_ids = [e.id for e in entries or []]
    legacy_questions, unified_questions, answers = await gather_reads(
        db.questionnaireformquestion.find_many(where={"sectionId": {"in": legacy_section_ids}}, order=order)
        if legacy_section_ids
        else _none(),
        db.questionnairequestion.find_many(where={"sectionId": {"in": unified_section_ids}}, order=order)
        if unified_section_ids
        else _none(),
        db.questionnaireformanswer.find_many(
            where={"entryId": {"in": entry_ids}},
        )
        if entry_ids
        else _none(),
    )
    questions = [*(legacy_questions or []), *(unified_questions or [])]
    entries = entries or []

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
                kind=str(questionnaire.kind or ""),
                question_count=sum(1 for _s, q in pairs if q.isActive) or v2_sittings.get(
                    questionnaire.id, {}
                ).get("questionCount", 0),
                sittings=(*sittings, *v2_sittings.get(questionnaire.id, {}).get("sittings", ())),
            )
        )
    return items


async def _none() -> list[Any]:
    return []


async def export_payload(questionnaire_id: str) -> dict[str, Any] | None:
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
                answers_by_question.setdefault(answer["questionId"], {})[label] = answer[
                    "answerText"
                ]
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
    questionnaire = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if questionnaire is None or questionnaire.deletedAt is not None:
        return None

    T = unified_forms.tables(db, await unified_forms.form_unified(questionnaire_id))
    sections = await T.sections.find_many(
        where={"questionnaireId": questionnaire_id, "isActive": True},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
    )
    section_ids = [s.id for s in sections]
    questions = (
        await T.questions.find_many(
            where={"sectionId": {"in": section_ids}, "isActive": True, "supersededById": None} if T.unified
            else {"sectionId": {"in": section_ids}, "isActive": True},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
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
        "kind": questionnaire.kind,
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
    base = (entry.get("respondentName") or entry.get("title") or "Answer").strip() or "Answer"
    base = base.replace("—", " ").replace("–", " ").replace("-", " ").replace(":", " ")
    base = " ".join(base.split())[:40] or "Answer"
    candidate = base
    n = 2
    while candidate in taken:
        candidate = f"{base} {n}"
        n += 1
    return candidate



def _uploaded_answer_rows(parsed: ParsedQuestionnaire) -> int:
    total = 0
    for label in parsed.entryLabels:
        for section in parsed.sections:
            for question in section.questions:
                if question.answers.get(label) or question.answerNotes.get(label):
                    total += 1
    return total


def _came_out_of_the_platform(parsed: ParsedQuestionnaire) -> str | None:
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
    kind: str | None = None,
    source_filename: str | None = None,
    user: Any = None,
) -> tuple[str, dict[str, Any]]:
    codes_taken: set[str] = set()
    planned_sections = [
        (index, _unique_code(section.code, section.title, codes_taken), section)
        for index, section in enumerate(parsed.sections[:MAX_SECTIONS], start=1)
    ]
    problems = [p.payload() for p in parsed.problems]
    answer_rows = _uploaded_answer_rows(parsed)
    evidence = _came_out_of_the_platform(parsed) if answer_rows else None
    entries_created = 0
    answers_imported = 0
    provenance: dict[str, Any] | None = None
    created_questions: dict[int, str] = {}
    unified = await unified_forms.authoring_unified()

    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
        Tx = unified_forms.tables(tx, unified)
        questionnaire = await tx.questionnaire.create(
            data={
                "title": (title or parsed.title or "Untitled questionnaire").strip(),
                "description": (description or parsed.description or None),
                "ownerId": owner_id,
                "designWorkshopId": design_workshop_id,
                "kind": kind,
                "sourceFilename": source_filename,
            }
        )
        if planned_sections:
            await Tx.sections.create_many(
                data=[
                    {
                        "questionnaireId": questionnaire.id,
                        "code": code,
                        "title": section.title or code,
                        "sortOrder": index,
                    }
                    for index, code, section in planned_sections
                ]
            )
            written = await Tx.sections.find_many(
                where={"questionnaireId": questionnaire.id}
            )
            id_by_code = {row.code: row.id for row in written}
            question_data = [
                {
                    "sectionId": id_by_code[code],
                    "prompt": question.prompt,
                    "helpText": question.helpText,
                    "isRequired": question.isRequired,
                    "sortOrder": position,
                    "questionCode": question_code(code, question.prompt),
                }
                for _index, code, section in planned_sections
                for position, question in enumerate(section.questions, start=1)
            ]
            if question_data:
                await Tx.questions.create_many(data=question_data)
                stored = await Tx.questions.find_many(
                    where={"sectionId": {"in": sorted(id_by_code.values())}}
                )
                id_by_place = {(row.sectionId, row.sortOrder): row.id for row in stored}
                for _index, code, section in planned_sections:
                    for position, question in enumerate(section.questions, start=1):
                        created_questions[question.row] = id_by_place[(id_by_code[code], position)]
        if answer_rows and not evidence and not unified:
            entries_created, answers_imported = await _store_uploaded_answers(
                questionnaire.id,
                parsed,
                created_questions,
                owner_id,
                source_filename=source_filename,
                client=tx,
            )

    if unified and answer_rows and not evidence:
        from app.questionnaire import library

        made = await library.import_workbook_answers(
            questionnaire.id, parsed, user, design_workshop_id=design_workshop_id
        )
        entries_created, answers_imported = made["sessions"], made["answers"]

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
    client: Any = None,
) -> tuple[int, int]:
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
        writer = db if client is None else client
        entry = await writer.questionnaireformentry.create(
            data={
                "questionnaireId": questionnaire_id,
                "title": label,
                "respondentName": label if label != "Answer" else None,
                "source": UPLOAD_ENTRY_SOURCE,
                "notes": note,
                "createdById": user_id,
            }
        )
        await writer.questionnaireformanswer.create_many(
            data=[row | {"entryId": entry.id} for row in rows]
        )
        entries_created += 1
        answers_written += len(rows)
    return (entries_created, answers_written)



REUSE_TITLE_SUFFIX = "reused"


def reuse_title(source_title: str, taken: set[str]) -> str:
    base = (source_title or "Questionnaire").strip() or "Questionnaire"
    base = base[:200].rstrip()
    lowered = {t.strip().lower() for t in taken}
    candidate = f"{base} ({REUSE_TITLE_SUFFIX})"
    n = 2
    while candidate.lower() in lowered:
        candidate = f"{base} ({REUSE_TITLE_SUFFIX} {n})"
        n += 1
    return candidate


_INHERIT_KIND = "\u2500inherit\u2500"


async def reuse_questionnaire(
    source_id: str,
    *,
    owner_id: str,
    design_workshop_id: str | None = None,
    title: str | None = None,
    description: str | None = None,
    kind: str | None = _INHERIT_KIND,
) -> tuple[str, dict[str, Any]] | None:
    form = await load_question_set(source_id)
    if form is None:
        return None

    chosen = (title or "").strip()
    if not chosen:
        where: dict[str, Any] = (
            {"designWorkshopId": design_workshop_id}
            if design_workshop_id
            else {"designWorkshopId": None, "ownerId": owner_id}
        )
        neighbours = await db.questionnaire.find_many(
            where=where, take=500, order=[{"createdAt": "desc"}]
        )
        chosen = reuse_title(form["title"], {row.title for row in neighbours})

    sections_in = form["sections"][:MAX_SECTIONS]
    sections_dropped = len(form["sections"]) - len(sections_in)

    section_data: list[dict[str, Any]] = []
    kept_by_code: dict[str, list[dict[str, Any]]] = {}
    budget = MAX_QUESTIONS
    questions_dropped = 0
    for index, section in enumerate(sections_in, start=1):
        section_data.append(
            {
                "code": section["code"],
                "title": section["title"],
                "sortOrder": index,
            }
        )
        kept = section["questions"][: max(budget, 0)]
        questions_dropped += len(section["questions"]) - len(kept)
        budget -= len(kept)
        kept_by_code[section["code"]] = kept

    unified = await unified_forms.authoring_unified()
    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=60)) as tx:
        Tx = unified_forms.tables(tx, unified)
        made = await tx.questionnaire.create(
            data={
                "title": chosen,
                "description": (description if description is not None else form["description"])
                or None,
                "ownerId": owner_id,
                "designWorkshopId": design_workshop_id,
                "kind": (form["kind"] if kind == _INHERIT_KIND else kind),
                "sourceFilename": None,
            }
        )
        question_data: list[dict[str, Any]] = []
        if section_data:
            await Tx.sections.create_many(
                data=[row | {"questionnaireId": made.id} for row in section_data]
            )
            written = await Tx.sections.find_many(
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
                    "questionCode": question_code(code, question["prompt"]),
                }
                for code, questions in kept_by_code.items()
                for position, question in enumerate(questions, start=1)
            ]
            if question_data:
                await Tx.questions.create_many(data=question_data)

    sections_made = len(section_data)
    questions_made = len(question_data)

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
            + f" of “{form['title']}”."
            + settled
        )
    else:
        reason = (
            f"This is a new questionnaire with no questions in it: “{form['title']}” has none "
            "still being asked — they were retired, or none were ever added — and a retired "
            "question is kept where its answers are rather than copied." + settled
        )

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
            "answersSkipped": 0,
            "reason": reason,
        },
        "versionBefore": made.version,
        "versionAfter": made.version,
        "problems": problems,
    }



async def apply_parsed_edit(
    questionnaire_id: str,
    parsed: ParsedQuestionnaire,
    *,
    user_id: str,
    title: str | None = None,
    description: str | None = None,
    may_remove: bool = False,
) -> dict[str, Any]:
    existing = await db.questionnaire.find_unique(where={"id": questionnaire_id})
    if existing is None:
        raise QuestionnaireEditError("That questionnaire no longer exists.")

    T = unified_forms.tables(db, await unified_forms.form_unified(questionnaire_id))
    sections = await T.sections.find_many(
        where={"questionnaireId": questionnaire_id},
        order=[{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
    )
    section_ids = [s.id for s in sections]
    questions = (
        await T.questions.find_many(
            where={"sectionId": {"in": section_ids}},
            order=[{"sortOrder": "asc"}, {"createdAt": "asc"}, {"id": "asc"}],
        )
        if section_ids
        else []
    )
    answered = await _answered_question_ids([q.id for q in questions])

    problems = [p.payload() for p in parsed.problems]
    by_id = {q.id: q for q in questions}
    section_by_id = {s.id: s for s in sections}
    section_by_code = {s.code.strip().lower(): s for s in sections}
    by_prompt: dict[tuple[str, str], Any] = {}
    for question in questions:
        if question.isActive:
            by_prompt.setdefault((question.sectionId, question.prompt.strip().lower()), question)

    answers_in_file = _uploaded_answer_rows(parsed)
    if answers_in_file:
        problems.append(
            {
                "sheet": parsed.sheet,
                "row": None,
                "severity": "warning",
                "reason": (
                    f"This file carries {answers_in_file} "
                    + ("answer" if answers_in_file == 1 else "answers")
                    + ". Uploading it again changes the questions only, so "
                    + ("it was" if answers_in_file == 1 else "they were")
                    + " not imported. Record answers in a sitting in the app."
                ),
                "value": None,
            }
        )

    report = {
        "created": 0,
        "updated": 0,
        "superseded": 0,
        "retired": 0,
        "removed": 0,
        "kept": 0,
        "answersNotImported": answers_in_file,
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

    async with db.tx(max_wait=timedelta(seconds=10), timeout=timedelta(seconds=120)) as tx:
        Tx = unified_forms.tables(tx, T.unified)
        for index, parsed_section in enumerate(parsed.sections[:MAX_SECTIONS], start=1):
            code = (parsed_section.code or "").strip()
            section = section_by_code.get(code.lower()) if code else None
            if section is None:
                new_code = _unique_code(code, parsed_section.title, codes_taken)
                section = await Tx.sections.create(
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
                wanted_title = parsed_section.title or section.title
                if section.title != wanted_title or section.sortOrder != index or not section.isActive:
                    section = await Tx.sections.update(
                        where={"id": section.id},
                        data={"title": wanted_title, "sortOrder": index, "isActive": True},
                    )
            touched_sections.add(section.id)

            for position, parsed_question in enumerate(parsed_section.questions, start=1):
                match = _match_question(parsed_question, section, by_id, by_prompt, problems, parsed)
                if match is None:
                    await Tx.questions.create(
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
                    if reworded:
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
                    replacement = await Tx.questions.create(
                        data={
                            "sectionId": section.id,
                            "prompt": parsed_question.prompt,
                            "helpText": parsed_question.helpText,
                            "isRequired": parsed_question.isRequired,
                            "sortOrder": position,
                        }
                    )
                    await Tx.questions.update(
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
                    data["prompt"] = parsed_question.prompt
                if match.helpText != parsed_question.helpText:
                    data["helpText"] = parsed_question.helpText
                if match.isRequired != parsed_question.isRequired:
                    data["isRequired"] = parsed_question.isRequired
                if match.sortOrder != position:
                    data["sortOrder"] = position
                if match.sectionId != section.id:
                    data["sectionId"] = section.id
                if data:
                    await Tx.questions.update(where={"id": match.id}, data=data)
                    report["updated"] += 1
                else:
                    report["unchanged"] += 1

        version_bumps += await _remove_absent(
            questions,
            sections,
            answered,
            touched_questions,
            touched_sections,
            report,
            client=tx,
            tables=Tx,
            may_remove=may_remove,
            parsed=parsed,
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
            await tx.questionnaire.update(where={"id": questionnaire_id}, data=updates)
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
    wanted = (parsed_question.questionId or "").strip()
    if wanted:
        found = by_id.get(wanted)
        if found is not None:
            return found
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
    *,
    client: Any = None,
    tables: Any = None,
    may_remove: bool = False,
    parsed: ParsedQuestionnaire | None = None,
) -> int:
    writer = db if client is None else client
    W = tables if tables is not None else unified_forms.tables(writer, False)
    if not may_remove:
        for question in questions:
            if question.id in touched_questions or not question.isActive:
                continue
            report["kept"] += 1
            report["problems"].append(
                {
                    "sheet": parsed.sheet if parsed is not None else None,
                    "row": None,
                    "severity": "warning",
                    "reason": (
                        "This question is not in your file but was kept. Only the form's author "
                        "or an admin can remove questions."
                    ),
                    "value": question.prompt[:120],
                }
            )
        return 0
    bumps = 0
    for question in questions:
        if question.id in touched_questions:
            continue
        if question.id in answered:
            if question.isActive:
                await W.questions.update(
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
        if not W.unified:
            await writer.questionnaireformanswer.delete_many(where={"questionId": question.id})
        await W.questions.delete(where={"id": question.id})
        report["removed"] += 1

    answered_sections = {q.sectionId for q in questions if q.id in answered}
    for section in sections:
        if section.id in touched_sections:
            continue
        if section.id in answered_sections:
            if section.isActive:
                await W.sections.update(
                    where={"id": section.id}, data={"isActive": False}
                )
            continue
        await W.sections.delete(where={"id": section.id})
    return bumps


async def _answered_question_ids(question_ids: list[str]) -> set[str]:
    if not question_ids:
        return set()
    rows = await db.questionnaireformanswer.find_many(where={"questionId": {"in": question_ids}})
    legacy = {
        row.questionId
        for row in rows
        if (row.answerText or "").strip() or (row.notes or "").strip()
    }
    if unified_forms.enabled():
        legacy |= await unified_forms.answered_question_ids(db, question_ids)
    return legacy



async def guard_question_edit(
    question: Any, *, new_prompt: str | None, deleting: bool
) -> str | None:
    answered = await _answered_question_ids([question.id])
    if not answered:
        return None
    if deleting:
        return "retire"
    if new_prompt is not None and new_prompt.strip() != question.prompt.strip():
        return "supersede"
    return None


async def supersede_question(question: Any, *, prompt: str, tables: Any = None, **fields: Any) -> Any:
    T = tables if tables is not None else unified_forms.tables(db, False)
    replacement = await T.questions.create(
        data={
            "sectionId": question.sectionId,
            "prompt": prompt,
            "helpText": fields.get("helpText", question.helpText),
            "isRequired": fields.get("isRequired", question.isRequired),
            "sortOrder": fields.get("sortOrder", question.sortOrder),
        }
    )
    await T.questions.update(
        where={"id": question.id},
        data={"isActive": False, "retiredAt": _now(), "supersededById": replacement.id},
    )
    return replacement


async def bump_version(questionnaire_id: str) -> None:
    await db.questionnaire.update(
        where={"id": questionnaire_id}, data={"version": {"increment": 1}}
    )



async def save_answers(
    entry: Any,
    answers: list[Any],
    *,
    user_id: str,
    is_admin: bool = False,
) -> dict[str, Any]:
    if not answers:
        return {"created": 0, "updated": 0, "unchanged": 0, "rejected": []}

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
        raise QuestionnaireEditError("Some of those questions belong to a different questionnaire.")
    retired = {q.id for q in questions if not q.isActive}

    by_question = {row.questionId: row for row in existing_rows}
    to_create: list[dict[str, Any]] = []
    to_update: list[tuple[str, dict[str, Any]]] = []
    rejected: list[dict[str, str]] = []
    unchanged = 0
    deduped = {answer.questionId: answer for answer in answers}
    for answer in deduped.values():
        current = by_question.get(answer.questionId)
        unchanged_here = (
            current is not None
            and current.answerText == answer.answerText
            and current.notes == answer.notes
        )
        if answer.questionId in retired and not unchanged_here:
            rejected.append(
                {
                    "questionId": answer.questionId,
                    "code": QUESTION_RETIRED,
                    "message": "This question was retired, so it takes no new answers.",
                }
            )
            continue
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
        if unchanged_here:
            unchanged += 1
            continue
        if current.answerText and current.answeredById != user_id and not is_admin:
            rejected.append(
                {
                    "questionId": answer.questionId,
                    "code": ANSWER_OWNED_BY_OTHER,
                    "message": "Someone else recorded this answer. Only they or an admin can change it.",
                }
            )
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

    if to_create:
        await db.questionnaireformanswer.create_many(data=to_create)
    for row_id, data in to_update:
        await db.questionnaireformanswer.update(where={"id": row_id}, data=data)
    return {
        "created": len(to_create),
        "updated": len(to_update),
        "unchanged": unchanged,
        "rejected": rejected,
    }


QUESTION_RETIRED = "QUESTION_RETIRED"
ANSWER_OWNED_BY_OTHER = "ANSWER_OWNED_BY_OTHER"
