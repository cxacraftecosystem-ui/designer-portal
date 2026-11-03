from __future__ import annotations

from typing import Any

from app.questionnaire.readers import (  # noqa: F401 - re-exported for the readers that call them here
    _db,
    answer_counts,
    covered_pairs,
    enabled,
    forms_for_design_workshop,
    session_answers,
    session_counts,
)


async def annexure_sittings(form_ids: list[str], *, design_workshop_id: str, client: Any = None) -> dict[str, dict[str, Any]]:
    ids = sorted({str(f) for f in form_ids if f})
    if not enabled() or not ids:
        return {}
    from app.services.report_questionnaires import QuestionnaireAnswer, QuestionnaireSitting

    db = _db(client)
    questions = await db.query_raw(
        'SELECT q."id", q."questionnaireId", q."lineageId", q."prompt", q."sectionCode", q."sectionTitle", q."isRequired", '
        'q."isActive", q."retiredAt", q."supersededById" FROM "QuestionnaireQuestion" q '
        'LEFT JOIN "QuestionnaireSection" s ON s."id" = q."sectionId" '
        'WHERE q."questionnaireId" = ANY($1::text[]) ORDER BY s."sortOrder", q."sortOrder", q."id"',
        ids,
    )
    sessions = await db.query_raw(
        'SELECT i."id", i."questionnaireId", i."title", i."legacyTitle", i."source"::text AS source, i."note", i."sessionDate", '
        'u."name" AS lead FROM "QuestionnaireInterview" i LEFT JOIN "User" u ON u."id" = i."createdById" '
        'WHERE i."questionnaireId" = ANY($1::text[]) AND i."designWorkshopId" = $2 AND i."deletedAt" IS NULL '
        'AND i."mergedIntoId" IS NULL AND i."clientKey" IS NOT NULL ORDER BY i."sessionDate", i."createdAt"',
        ids, design_workshop_id,
    )
    if not sessions:
        return {}
    sids = [str(s["id"]) for s in sessions]
    owners = await db.query_raw(
        'SELECT p."interviewId" AS s, \'A:\' || p."artisanId" AS k, a."name" FROM "QuestionnaireInterviewArtisan" p '
        'JOIN "Artisan" a ON a."id" = p."artisanId" WHERE p."interviewId" = ANY($1::text[]) '
        'UNION ALL SELECT r."sessionId", \'R:\' || r."id", r."name" FROM "QuestionnaireRespondent" r '
        'WHERE r."sessionId" = ANY($1::text[]) AND r."artisanId" IS NULL',
        sids,
    )
    answers = await db.query_raw(
        'SELECT rv."sessionId" AS s, COALESCE(\'A:\' || a."artisanId", \'R:\' || a."respondentId") AS k, a."questionLineageId" AS l, '
        'rv."text", rv."note" FROM "QuestionnaireAnswer" a JOIN "QuestionnaireAnswerRevision" rv ON rv."id" = a."currentRevisionId" '
        'WHERE rv."sessionId" = ANY($1::text[])',
        sids,
    )
    by_answer = {(str(r["s"]), str(r["k"]), str(r["l"])): r for r in answers}
    owners_by: dict[str, list[dict[str, Any]]] = {}
    for o in owners:
        owners_by.setdefault(str(o["s"]), []).append(o)
    questions_by: dict[str, list[dict[str, Any]]] = {}
    for q in questions:
        questions_by.setdefault(str(q["questionnaireId"]), []).append(q)
    out: dict[str, dict[str, Any]] = {}
    for session in sessions:
        form_id = str(session["questionnaireId"])
        form_questions = questions_by.get(form_id, [])
        bucket = out.setdefault(form_id, {
            "questionCount": sum(1 for q in form_questions if q.get("isActive") and not q.get("retiredAt") and not q.get("supersededById")),
            "sittings": [],
        })
        day = session.get("sessionDate")
        for owner in owners_by.get(str(session["id"]), []):
            key = (str(session["id"]), str(owner["k"]))
            bucket["sittings"].append(QuestionnaireSitting(
                entry_id=f"{session['id']}:{owner['k']}",
                title=str(session.get("legacyTitle") or session.get("title") or ""),
                respondent_name=str(owner.get("name") or ""),
                source=str(session.get("source") or "APP"),
                notes=str(session.get("note") or ""),
                recorded_at=day.isoformat() if hasattr(day, "isoformat") else str(day or ""),
                recorded_by=str(session.get("lead") or ""),
                answers=tuple(
                    QuestionnaireAnswer(
                        prompt=str(q.get("prompt") or ""),
                        answer_text=str((by_answer.get((*key, str(q["lineageId"]))) or {}).get("text") or ""),
                        notes=str((by_answer.get((*key, str(q["lineageId"]))) or {}).get("note") or ""),
                        section_code=str(q.get("sectionCode") or ""),
                        section_title=str(q.get("sectionTitle") or ""),
                        is_required=bool(q.get("isRequired")),
                        is_retired=not q.get("isActive") or bool(q.get("retiredAt")) or bool(q.get("supersededById")),
                    )
                    for q in form_questions
                ),
            ))
    for bucket in out.values():
        bucket["sittings"] = tuple(bucket["sittings"])
    return out
